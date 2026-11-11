#!/usr/bin/env python3
"""사용자 소유·비활성 AI 시험의 중복 아티팩트만 CoW 공유합니다. 삭제/덮어쓰기 없음."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import struct
import tempfile
import time

GIB=1024**3
MARKER='S5 isolated AI trial only\n'
FIDEDUPERANGE=0xc0189436  # linux/fs.h: _IOWR(0x94, 54, struct file_dedupe_range)
FS_IOC_FIEMAP=0xc020660b  # linux/fs.h / linux/fiemap.h; read-only extent lookup


def identity(path):
    s=path.stat()
    return dict(inode=s.st_ino,device=s.st_dev,size=s.st_size,mtime_ns=s.st_mtime_ns,mode=s.st_mode,uid=s.st_uid,gid=s.st_gid,links=s.st_nlink)


def checksum(path,guard):
    value=hashlib.sha256()
    with path.open('rb') as stream:
        while True:
            guard();block=stream.read(1024**2)
            if not block:break
            value.update(block)
    return value.hexdigest()


def fully_shared(path,guard):
    """이미 모든 데이터 블록이 공유되면 다시 공유해도 공간이 늘지 않으므로 생략합니다."""
    size=path.stat().st_size;offset=0
    try:
        with path.open('rb') as stream:
            for _ in range(32):
                guard();data=bytearray(struct.pack('=QQIIII',offset,2**64-1-offset,0,0,128,0)+bytes(56*128))
                fcntl.ioctl(stream.fileno(),FS_IOC_FIEMAP,data,True);mapped=struct.unpack_from('=QQIIII',data)[3]
                if not 0<mapped<=128:return False
                for i in range(mapped):
                    logical,physical,length,_,_,flags,_,_,_=struct.unpack_from('=QQQQQIIII',data,32+56*i)
                    if logical!=offset or not length or not flags&0x2000 or flags&~0x2001:return False
                    offset=logical+length
                    if offset>=size:return bool(flags&1)
        return False
    except OSError:return False


def active(root):
    for entry in Path('/proc').iterdir():
        if not entry.name.isdigit() or int(entry.name)==os.getpid():continue
        try:
            if entry.stat().st_uid!=os.getuid():continue
            for link in ('cwd','exe'):
                target=(entry/link).resolve(strict=True)
                if target==root or root in target.parents:return True
            for arg in (entry/'cmdline').read_bytes().split(b'\0'):
                if arg==os.fsencode(root) or arg.startswith(os.fsencode(root)+b'/'):return True
        except (OSError,ValueError):continue
    return False


def candidates(roots,guard=lambda:None):
    found=[];skipped=[]
    for root in roots:
        guard()
        try:
            if (root.parent!=Path('/tmp') or not root.name.startswith('hearth-s5-trial.') or root.is_symlink()
                    or root.stat().st_uid!=os.getuid() or (root/'.hearth-s5-isolated').is_symlink() or (root/'.hearth-s5-isolated').read_text()!=MARKER):continue
            if active(root):skipped.append(str(root));continue
            paths=[root/'ollama.tar.zst']+list(root.glob('models*/blobs/sha256-*'))
            runtime=root/'runtime'
            if runtime.exists():
                for parent,dirs,files in os.walk(runtime,followlinks=False):
                    guard()
                    dirs[:]=[d for d in dirs if not (Path(parent)/d).is_symlink()]
                    paths.extend(Path(parent)/f for f in files)
            for path in paths:
                guard()
                if not path.exists() or path.is_symlink() or not path.resolve().is_relative_to(root):continue
                if any(parent.is_symlink() for parent in path.parents if parent!=root and root in parent.parents):continue
                s=path.stat()
                if stat.S_ISREG(s.st_mode) and s.st_uid==os.getuid() and s.st_nlink==1 and s.st_size>=1024**2:found.append(path)
        except (OSError,ValueError):continue
    return found,skipped


def dedupe_pair(source,target,expected,guard):
    guard();before=[identity(p) for p in (source,target)]
    assert all(s['uid']==os.getuid() and s['links']==1 and stat.S_ISREG(s['mode']) for s in before)
    assert before[0]['device']==before[1]['device'] and before[0]['inode']!=before[1]['inode'] and before[0]['size']==before[1]['size']
    assert not source.is_symlink() and not target.is_symlink()
    assert checksum(source,guard)==checksum(target,guard)==expected,'ARTIFACT_CHANGED'
    done=0
    with source.open('rb') as incoming,target.open('r+b') as output:
        assert [identity(p) for p in (source,target)]==before,'ARTIFACT_CHANGED'
        while done<before[0]['size']:
            guard();length=min(16*1024**2,before[0]['size']-done)
            data=bytearray(struct.pack('=QQHHIqQQiI',done,length,1,0,0,output.fileno(),done,0,0,0))
            fcntl.ioctl(incoming.fileno(),FIDEDUPERANGE,data,True)
            _,_,_,_,_,_,_,amount,status,_=struct.unpack('=QQHHIqQQiI',data)
            if status!=0 or amount<=0:raise RuntimeError('DEDUPE_REJECTED status='+str(status))
            done+=amount
        os.fsync(output.fileno())
    assert checksum(source,guard)==checksum(target,guard)==expected,'POST_HASH_MISMATCH'
    after=[identity(p) for p in (source,target)];assert before==after,'POST_METADATA_CHANGED'
    return dict(source=str(source),target=str(target),sha256=expected,bytes_compared_and_shared=done,before=before,after=after)


def reclaim(roots=None,minimum=16*GIB,seconds=90):
    started=time.monotonic();free=lambda:shutil.disk_usage('/tmp').free
    report=dict(method='FIDEDUPERANGE',minimum_free_bytes=minimum,free_before_bytes=free(),pairs=[],active_roots_skipped=[],shared_files_skipped=0,files_deleted=0,limits=dict(seconds=seconds,processors=2,nice=10))
    def guard():
        if time.monotonic()-started>=seconds:raise TimeoutError('SPACE_RECLAIM_DEADLINE')
    try:
        if free()<minimum:
            guard();paths,skipped=candidates(list(Path('/tmp').glob('hearth-s5-trial.*')) if roots is None else roots,guard);report['active_roots_skipped']=skipped
            groups={}
            for path in paths:groups.setdefault((path.stat().st_size,path.name),[]).append(path)
            for _,group in sorted(groups.items(),reverse=True):
                guard()
                if free()>=minimum:break
                if len(group)<2:continue
                sharing=[(p,fully_shared(p,guard)) for p in group]
                if all(shared for _,shared in sharing):report['shared_files_skipped']+=len(group);continue
                by_hash={}
                for path,shared in sorted(sharing,key=lambda item:not item[1]):
                    guard()
                    if free()>=minimum:break
                    if shared and by_hash:report['shared_files_skipped']+=1;continue
                    digest=checksum(path,guard);prior=by_hash.get(digest)
                    if prior is None:by_hash[digest]=path
                    elif prior.stat().st_ino!=path.stat().st_ino:report['pairs'].append(dedupe_pair(prior,path,digest,guard))
    except (OSError,RuntimeError,AssertionError) as error:report['failure']=type(error).__name__+': '+str(error)
    report.update(free_after_bytes=free(),duration_seconds=round(time.monotonic()-started,3))
    report['outcome']='PASS' if report['free_after_bytes']>=minimum and 'failure' not in report else 'SPACE_NOT_READY'
    return report


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.parse_args()
    assert os.getuid()!=0,'User-owned cleanup only'
    os.sched_setaffinity(0,set(sorted(os.sched_getaffinity(0))[:2]));os.nice(10)
    evidence=Path(tempfile.mkdtemp(prefix='hearth-space-reclaim.',dir='/tmp'))
    report=reclaim();(evidence/'result.json').write_text(json.dumps(report,indent=2)+'\n')
    print(report['outcome']+' free_MiB='+str(report['free_after_bytes']//1024**2)+' evidence='+str(evidence))
    return 0 if report['outcome']=='PASS' else 1


if __name__=='__main__':raise SystemExit(main())
