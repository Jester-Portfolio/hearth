#!/usr/bin/env python3
"""정지된 Paper 트리와 PostgreSQL 전체 클러스터의 명시적 백업/새 경로 복원. 서비스 조작은 하지 않습니다."""
import argparse
from contextlib import ExitStack
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import stat
import time

FORMAT = 'hearth-offline-backup-v1'
MAX_FILES = 100_000
MAX_BYTES = 12 * 1024**3
MAX_SECONDS = 90


class BackupError(RuntimeError):
    pass


def require(condition, code):
    if not condition:
        raise BackupError(code)


class Budget:
    def __init__(self, guard=lambda: None, max_bytes=MAX_BYTES, seconds=MAX_SECONDS):
        self.guard = guard
        self.max_bytes = max_bytes
        self.deadline = time.monotonic() + seconds

    def check(self):
        self.guard()
        require(time.monotonic() < self.deadline, 'BACKUP_TIME_LIMIT')


def owned(path):
    info = path.lstat()
    require(info.st_uid == os.getuid() and not stat.S_ISLNK(info.st_mode), 'UNSAFE_OWNERSHIP_OR_LINK')
    require(stat.S_ISDIR(info.st_mode) or stat.S_ISREG(info.st_mode), 'UNSUPPORTED_FILE_TYPE')
    if stat.S_ISREG(info.st_mode):
        require(info.st_nlink == 1, 'HARD_LINK_REJECTED')
    return info


def canonical_directory(path):
    path = Path(path).absolute()
    require(path == path.resolve(strict=True), 'NON_CANONICAL_DIRECTORY')
    require(stat.S_ISDIR(owned(path).st_mode), 'DIRECTORY_REQUIRED')
    return path


def fresh(path):
    path = Path(path).absolute()
    parent = canonical_directory(path.parent)
    require(parent.stat().st_mode & 0o077 == 0, 'PRIVATE_DESTINATION_PARENT_REQUIRED')
    require(path.name not in ('', '.', '..'), 'INVALID_DESTINATION')
    # mkdir(exist_ok=False) preserves all existing output, including partial attempts.
    path.mkdir(mode=0o700)
    return path


def fingerprint(info):
    return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns, info.st_mode, info.st_nlink)


def relative(value):
    require(isinstance(value, str) and value and '\\' not in value, 'INVALID_MANIFEST_PATH')
    p = PurePosixPath(value)
    require(not p.is_absolute() and all(x not in ('', '.', '..') for x in value.split('/')), 'INVALID_MANIFEST_PATH')
    return p


def inventory(root, budget):
    rows = []
    def walk(directory):
        budget.check()
        for p in sorted(directory.iterdir()):
            info = owned(p)
            rel = p.relative_to(root).as_posix()
            row = dict(path=rel, directory=stat.S_ISDIR(info.st_mode), fingerprint=fingerprint(info))
            rows.append(row)
            require(len(rows) <= MAX_FILES, 'BACKUP_FILE_LIMIT')
            if row['directory']:
                walk(p)
    walk(root)
    require(sum(r['fingerprint'][2] for r in rows if not r['directory']) <= budget.max_bytes, 'BACKUP_BYTE_LIMIT')
    return rows


def copy_file(source, target, before, budget):
    digest = hashlib.sha256()
    fd = os.open(source, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, 'rb') as src:
        require(fingerprint(os.fstat(src.fileno())) == before, 'SOURCE_CHANGED')
        with target.open('xb') as dst:
            os.chmod(target, before[5] & 0o700)
            total = 0
            while chunk := src.read(1024 * 1024):
                budget.check()
                total += len(chunk)
                require(total <= before[2] and total <= budget.max_bytes, 'SOURCE_CHANGED_OR_BYTE_LIMIT')
                digest.update(chunk)
                dst.write(chunk)
            dst.flush()
            os.fsync(dst.fileno())
        require(total == before[2] and fingerprint(os.fstat(src.fileno())) == before, 'SOURCE_CHANGED')
    require(fingerprint(owned(source)) == before, 'SOURCE_CHANGED')
    return digest.hexdigest()


def file_hash(path, budget):
    before = fingerprint(owned(path))
    digest = hashlib.sha256()
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, 'rb') as stream:
        require(fingerprint(os.fstat(stream.fileno())) == before, 'SOURCE_CHANGED')
        while chunk := stream.read(1024 * 1024):
            budget.check()
            digest.update(chunk)
        require(fingerprint(os.fstat(stream.fileno())) == before, 'SOURCE_CHANGED')
    require(fingerprint(owned(path)) == before, 'SOURCE_CHANGED')
    return digest.hexdigest()


def assert_offline(roots, stack):
    pg = roots['postgres']
    require((pg/'PG_VERSION').is_file() and not (pg/'PG_VERSION').is_symlink(), 'PG_CLUSTER_REQUIRED')
    # Cold-cluster backup is intentionally narrower than online pg_basebackup/PITR.
    require(not (pg/'postmaster.pid').exists(), 'POSTGRES_MUST_BE_CLEANLY_STOPPED')
    require((pg/'global/pg_control').is_file(), 'PG_CONTROL_REQUIRED')
    for root in roots.values():
        for lock in root.rglob('session.lock'):
            owned(lock)
            handle = stack.enter_context(lock.open('rb'))
            try:
                fcntl.lockf(handle, fcntl.LOCK_SH | fcntl.LOCK_NB)
            except OSError:
                raise BackupError('PAPER_WORLD_IS_LOCKED') from None


def create(roots, destination, *, offline_confirmed=False, budget=None):
    require(offline_confirmed, 'OFFLINE_CONFIRMATION_REQUIRED')
    require(set(roots) in ({'paper', 'postgres'}, {'paper', 'postgres', 'assets'}), 'BACKUP_ROOTS_REQUIRED')
    budget = budget or Budget()
    roots = {label: canonical_directory(root) for label, root in roots.items()}
    paths = list(roots.values())
    require(all(a != b and a not in b.parents and b not in a.parents for i,a in enumerate(paths) for b in paths[i+1:]), 'OVERLAPPING_SOURCES')
    dest = Path(destination).absolute()
    require(all(dest != p and p not in dest.parents and dest not in p.parents for p in paths), 'OVERLAPPING_DESTINATION')
    with ExitStack() as stack:
        assert_offline(roots, stack)
        scans = {label: inventory(path, budget) for label, path in roots.items()}
        total = sum(r['fingerprint'][2] for rows in scans.values() for r in rows if not r['directory'])
        require(total <= budget.max_bytes, 'BACKUP_BYTE_LIMIT')
        require(sum(map(len, scans.values())) + len(roots) <= MAX_FILES, 'BACKUP_FILE_LIMIT')
        dest = fresh(dest)
        payload = dest/'payload'; payload.mkdir(mode=0o700)
        records = []
        for label, source in roots.items():
            target = payload/label; target.mkdir(mode=0o700)
            records.append(dict(path=label, directory=True))
            for row in scans[label]:
                budget.check()
                out = target/row['path']
                name = label+'/'+row['path']
                if row['directory']:
                    out.mkdir(mode=0o700)
                    records.append(dict(path=name, directory=True))
                else:
                    digest = copy_file(source/row['path'], out, row['fingerprint'], budget)
                    records.append(dict(path=name, directory=False, size=row['fingerprint'][2], mode=row['fingerprint'][5] & 0o700, sha256=digest))
        # Recheck all source identities/content after the whole copy, including additions/removals.
        expected = {r['path']: r for r in records}
        for label, source in roots.items():
            require(inventory(source,budget) == scans[label], 'SOURCE_TREE_CHANGED')
            for row in scans[label]:
                if not row['directory']:
                    require(file_hash(source/row['path'],budget) == expected[label+'/'+row['path']]['sha256'], 'SOURCE_CONTENT_CHANGED')
        assert_offline(roots, stack)
        manifest = dict(format=FORMAT, created_utc=datetime.now(timezone.utc).isoformat(), consistency='operator_confirmed_clean_stop', postgres_major=(roots['postgres']/'PG_VERSION').read_text().strip(), files=records, bytes=total)
        raw = (json.dumps(manifest,ensure_ascii=False,sort_keys=True,indent=2)+'\n').encode()
        require(len(raw) <= 32*1024**2, 'MANIFEST_SIZE_LIMIT')
        # The final manifest is the completion marker; partial directories are retained without it.
        with (dest/'manifest.json').open('xb') as stream:
            os.chmod(dest/'manifest.json',0o600); stream.write(raw); stream.flush(); os.fsync(stream.fileno())
        return dict(manifest_sha256=hashlib.sha256(raw).hexdigest(), files=len(records), bytes=total)


def verify(bundle, expected_sha256, *, budget=None):
    budget = budget or Budget()
    bundle = canonical_directory(bundle)
    require(bundle.stat().st_mode & 0o077 == 0, 'PRIVATE_BUNDLE_REQUIRED')
    require(set(p.name for p in bundle.iterdir()) == {'manifest.json','payload'}, 'INCOMPLETE_OR_EXTRA_BUNDLE_FILES')
    manifest_file = bundle/'manifest.json'; owned(manifest_file)
    require(manifest_file.stat().st_size <= 32*1024**2, 'MANIFEST_SIZE_LIMIT')
    raw = manifest_file.read_bytes()
    require(isinstance(expected_sha256,str) and len(expected_sha256)==64 and hashlib.sha256(raw).hexdigest()==expected_sha256, 'MANIFEST_RECEIPT_MISMATCH')
    manifest = json.loads(raw)
    require(manifest.get('format')==FORMAT and manifest.get('consistency')=='operator_confirmed_clean_stop', 'UNKNOWN_BACKUP_FORMAT')
    records = manifest['files']; require(isinstance(records,list) and len(records)<=MAX_FILES, 'BACKUP_FILE_LIMIT')
    index = {}
    for row in records:
        name = str(relative(row['path']))
        require(name not in index and type(row.get('directory')) is bool, 'DUPLICATE_OR_INVALID_MANIFEST_ENTRY')
        require(name.split('/')[0] in {'paper','postgres','assets'}, 'UNKNOWN_BACKUP_ROOT')
        if not row['directory']:
            require(type(row.get('size')) is int and row['size']>=0 and type(row.get('mode')) is int and row['mode'] & ~0o700 == 0 and isinstance(row.get('sha256'),str) and len(row['sha256'])==64, 'INVALID_MANIFEST_FILE')
        index[name] = row
    require(all(x in index and index[x]['directory'] for x in ('paper','postgres')), 'BACKUP_ROOTS_REQUIRED')
    payload = canonical_directory(bundle/'payload')
    actual = inventory(payload,budget)
    require(set(r['path'] for r in actual)==set(index), 'PAYLOAD_MEMBERS_MISMATCH')
    total = 0
    for row in actual:
        want = index[row['path']]
        require(row['directory']==want['directory'], 'PAYLOAD_TYPE_MISMATCH')
        if not row['directory']:
            total += want['size']; require(total <= budget.max_bytes, 'BACKUP_BYTE_LIMIT')
            require(row['fingerprint'][2]==want['size'] and row['fingerprint'][5] & 0o777==want['mode'], 'PAYLOAD_SIZE_OR_MODE_MISMATCH')
            require(file_hash(payload/row['path'],budget)==want['sha256'], 'PAYLOAD_HASH_MISMATCH')
    require(total==manifest['bytes'], 'PAYLOAD_TOTAL_MISMATCH')
    return manifest


def restore(bundle, destination, expected_sha256, *, budget=None):
    budget = budget or Budget()
    bundle = canonical_directory(bundle)
    dest = Path(destination).absolute()
    require(bundle != dest and bundle not in dest.parents and dest not in bundle.parents, 'OVERLAPPING_DESTINATION')
    manifest = verify(bundle,expected_sha256,budget=budget)
    target = fresh(dest)
    for row in sorted(manifest['files'],key=lambda r:(len(PurePosixPath(r['path']).parts),r['path'])):
        budget.check()
        source = bundle/'payload'/row['path']; out = target/row['path']
        if row['directory']:
            out.mkdir(mode=0o700)
        else:
            digest = copy_file(source,out,fingerprint(owned(source)),budget)
            require(digest==row['sha256'], 'BUNDLE_CHANGED_DURING_RESTORE')
    # Revalidate the archived source after extraction. Never mutate or start restored services.
    verify(bundle,expected_sha256,budget=budget)
    receipt = dict(format=FORMAT, manifest_sha256=expected_sha256, files_restored=len(manifest['files']), bytes=manifest['bytes'], services_started=False)
    with (target/'restore-receipt.json').open('x') as stream:
        os.chmod(target/'restore-receipt.json',0o600);json.dump(receipt,stream,sort_keys=True);stream.write('\n');stream.flush();os.fsync(stream.fileno())
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action',required=True)
    pack = sub.add_parser('create');pack.add_argument('--paper',type=Path,required=True);pack.add_argument('--postgres',type=Path,required=True);pack.add_argument('--assets',type=Path);pack.add_argument('--destination',type=Path,required=True);pack.add_argument('--offline-confirmed',action='store_true')
    for name in ('verify','restore'):
        p=sub.add_parser(name);p.add_argument('--bundle',type=Path,required=True);p.add_argument('--manifest-sha256',required=True)
        if name=='restore':p.add_argument('--destination',type=Path,required=True)
    args=parser.parse_args()
    try:
        if args.action=='create':
            roots={'paper':args.paper,'postgres':args.postgres}
            if args.assets is not None:roots['assets']=args.assets
            result=create(roots,args.destination,offline_confirmed=args.offline_confirmed)
        elif args.action=='restore':result=restore(args.bundle,args.destination,args.manifest_sha256)
        else:
            data=verify(args.bundle,args.manifest_sha256);result=dict(files=len(data['files']),bytes=data['bytes'])
        print(json.dumps(dict(outcome='PASS',**result),ensure_ascii=False))
        return 0
    except (BackupError,OSError,ValueError,KeyError,TypeError):
        # No paths, connection strings, secret config values or exception details in console output.
        print(json.dumps(dict(outcome='FAIL',code='BACKUP_REFUSED_OR_INCOMPLETE')))
        return 1


if __name__=='__main__':
    raise SystemExit(main())
