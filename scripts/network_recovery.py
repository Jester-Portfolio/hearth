#!/usr/bin/env python3
"""프록시·두 Paper·전체 PG를 하나의 정지 snapshot으로 보존하고 새 경로만 복원한다."""
import sys
if __name__ == "__main__": sys.dont_write_bytecode = True
import argparse,copy,hashlib,json,os
from pathlib import Path
import offline_backup as backup
import network_release as release

FORMAT='hearth-network-recovery-v1'

def write_json(path,value):
    with path.open('x') as out:
        path.chmod(0o600);json.dump(value,out,sort_keys=True,indent=2);out.write('\n');out.flush();os.fsync(out.fileno())
    return release.digest(path)

def stopped(roots):
    for role,root in roots.items():
        for p in root.rglob('*.active'):
            raise backup.BackupError('NETWORK_ACTIVE_MARKER')
        for name in ('native-economy-pending','rpg-death-pending'):
            p=root/'plugins/Hearth'/name
            if p.is_dir() and any(p.iterdir()):raise backup.BackupError('NETWORK_PENDING_OPERATIONS')

def create(manifest,destination,*,offline_confirmed=False,budget=None):
    backup.require(offline_confirmed,'OFFLINE_CONFIRMATION_REQUIRED')
    release.inspect(manifest)
    source=release.regular(manifest);original=release.digest(source);review=json.loads(source.read_text())
    roots={role:backup.canonical_directory(s['data']) for role,s in review['review']['services'].items()}
    stopped(roots)
    out=Path(destination).absolute()
    backup.require(all(out!=r and r not in out.parents and out not in r.parents for r in roots.values()),'OVERLAPPING_DESTINATION')
    # Freeze only the reviewed artifact set; no unrelated private directory is copied.
    budget=budget or backup.Budget();out=backup.fresh(out);assets=out/'source-review';assets.mkdir(mode=0o700)
    for name in ['release.json',*sorted(review['artifacts'])]:
        p=source.parent/name;backup.copy_file(p,assets/name,backup.fingerprint(backup.owned(p)),budget)
    if review.get('format',0)>=3 and review.get('attestation'):
        import network_acceptance
        network_acceptance.export(Path(review['attestation']['dossier']),review['attestation']['head'],assets/'audit',budget=budget)
    receipt=backup.create({**roots,'assets':assets},out/'bundle',offline_confirmed=True,budget=budget)
    release.inspect(source);stopped(roots);backup.require(release.digest(source)==original,'REVIEW_CHANGED')
    value={'format':FORMAT,'bundle':'bundle','backup_manifest_sha256':receipt['manifest_sha256'],'source_release_sha256':original,'services_started':False}
    seal=write_json(out/'recovery.json',value)
    return {'recovery_sha256':seal,**receipt,'services_started':False}

def verify(directory,expected,*,budget=None):
    directory=backup.canonical_directory(directory);p=directory/'recovery.json'
    backup.require(release.digest(p)==expected,'RECOVERY_RECEIPT_MISMATCH')
    receipt=json.loads(p.read_text());backup.require(set(receipt)=={'format','bundle','backup_manifest_sha256','source_release_sha256','services_started'} and receipt['format']==FORMAT and receipt['bundle']=='bundle' and receipt['services_started'] is False,'RECOVERY_SCHEMA')
    manifest=backup.verify(directory/'bundle',receipt['backup_manifest_sha256'],budget=budget)
    backup.require(manifest['format']==backup.NETWORK_FORMAT,'NETWORK_BUNDLE_REQUIRED')
    payload=directory/'bundle/payload';source=payload/'assets/release.json'
    backup.require(release.digest(source)==receipt['source_release_sha256'],'REVIEW_RECEIPT_MISMATCH')
    profile=release.artifacts(source);index={row['path']:row for row in manifest['files']}
    # Bind every originally reviewed input to the actual four-role snapshot without consulting old paths.
    for name,sha in profile['files'].items():
        matches=[]
        for role,s in profile['review']['services'].items():
            try:rel=Path(name).relative_to(s['data'])
            except ValueError:continue
            matches.append(role+'/'+rel.as_posix())
        backup.require(len(matches)==1 and index.get(matches[0],{}).get('sha256')==sha,'NETWORK_SNAPSHOT_IDENTITY')
    if profile['format']>=4:
        # Recompute the original scope on snapshot paths to bind the critical input inventory.
        for role,s in profile['review']['services'].items():
            original=Path(s['data']);expected=release.critical_digest(payload/role)
            props=(payload/'assets'/f'{role}.properties').read_text()
            backup.require('critical-sha256='+expected+'\n' in props,'NETWORK_CRITICAL_IDENTITY')
    return receipt,profile

def restore(directory,destination,expected,*,budget=None):
    budget=budget or backup.Budget();receipt,profile=verify(directory,expected,budget=budget)
    # Caller-owned restore preserves the operator's UID choices; no chown/implicit identity change.
    backup.require(all(s['uid']==os.getuid() and s['gid']==os.getgid() for s in profile['review']['services'].values()),'RESTORE_IDENTITY_REVIEW_REQUIRED')
    target=Path(destination).absolute();backup.restore(Path(directory)/'bundle',target,receipt['backup_manifest_sha256'],budget=budget)
    spec=copy.deepcopy(profile['review'])
    for role,s in spec['services'].items():s['data']=str(target/role)
    spec['acceptance']={k:False for k in spec['acceptance']}
    write_json(target/'restored-review.json',spec)
    release.generate(spec,target/'candidate')
    value={'format':FORMAT,'source_recovery_sha256':expected,'source_release_sha256':receipt['source_release_sha256'],'restored_release_sha256':release.digest(target/'candidate/release.json'),'acceptance_reset':True,'services_started':False}
    seal=write_json(target/'network-restore.json',value)
    return {'restore_sha256':seal,**value}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=('create','verify','restore'));p.add_argument('--release',type=Path);p.add_argument('--source',type=Path);p.add_argument('--destination',type=Path);p.add_argument('--recovery-sha256');p.add_argument('--offline-confirmed',action='store_true');a=p.parse_args()
    try:
        if a.action=='create':result=create(a.release,a.destination,offline_confirmed=a.offline_confirmed)
        elif a.action=='verify':verify(a.source,a.recovery_sha256);result={'verified':True,'services_started':False}
        else:result=restore(a.source,a.destination,a.recovery_sha256)
        print(json.dumps(result,separators=(',',':')));return 0
    except (backup.BackupError,OSError,ValueError,KeyError,TypeError):print('NETWORK_RECOVERY_REFUSED');return 2
if __name__=='__main__':raise SystemExit(main())
