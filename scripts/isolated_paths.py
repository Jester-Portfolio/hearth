"""승인한 사용자 소유 시험 공간. /tmp는 과거 아티팩트 읽기에만 허용합니다."""
import argparse
import os
from pathlib import Path
import re
import stat
import tempfile

BASE=Path('/home/server/hearth-tmp')
MARKER='Hearth isolated test base v1\n'


def require_base():
    assert os.getuid()!=0,'Root test execution is forbidden'
    assert BASE.is_dir() and BASE.absolute()==BASE.resolve(strict=True),'Test base must be canonical'
    s=BASE.stat();assert s.st_uid==os.getuid() and stat.S_IMODE(s.st_mode)==0o700,'Test base must be owned and mode 0700'
    marker=BASE/'.hearth-test-base'
    assert marker.is_file() and not marker.is_symlink() and marker.stat().st_uid==os.getuid() and marker.read_text()==MARKER,'Test base marker rejected'
    return BASE


def ensure_base():
    if not BASE.exists():
        BASE.mkdir(mode=0o700)
        marker=BASE/'.hearth-test-base'
        with marker.open('x') as stream:stream.write(MARKER)
        marker.chmod(0o600)
    return require_base()


def workspace(path,prefix,legacy_source=False):
    path=Path(path);absolute=path.absolute();root=path.resolve(strict=True)
    assert root==absolute and root.is_dir() and not path.is_symlink(),'Noncanonical workspace'
    assert os.getuid()!=0 and root.stat().st_uid==os.getuid(),'Workspace owner rejected'
    if legacy_source and root.parent==Path('/tmp'):pass
    else:assert root.parent==require_base(),'Workspace outside approved test base'
    assert re.fullmatch(re.escape(prefix)+r'[A-Za-z0-9_-]{1,80}',root.name),'Workspace prefix rejected'
    return root


def fresh(prefix):
    assert re.fullmatch(r'hearth-[a-z0-9-]+\.',prefix),'Test prefix rejected'
    return Path(tempfile.mkdtemp(prefix=prefix,dir=ensure_base()))


def java_command(command,root):
    root=workspace(root,root.name.split('.',1)[0]+'.')
    temp=root/'jvm-tmp';temp.mkdir(mode=0o700,exist_ok=True)
    assert not temp.is_symlink() and temp.resolve().parent==root and temp.stat().st_uid==os.getuid()
    if Path(command[0]).name=='java':
        settings=[arg.split('=',1)[1] for arg in command if arg.startswith('-Djava.io.tmpdir=')]
        assert len(settings)<=1,'Duplicate Java temporary roots'
        for value in settings:
            supplied=Path(value);resolved=supplied.resolve(strict=True)
            assert supplied.absolute()==resolved and resolved.is_dir() and resolved.is_relative_to(root),'Java temporary path outside workspace'
        if not settings:command=[command[0],'-Djava.io.tmpdir='+str(temp)]+list(command[1:])
    return command


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--prefix');args=parser.parse_args()
    print(fresh(args.prefix) if args.prefix else ensure_base())
