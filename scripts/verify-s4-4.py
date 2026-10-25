#!/usr/bin/env python3
"""새 사용자 소유 PostgreSQL만 실행하는 S4-4 시험 도구. 데이터는 삭제하지 않습니다."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import tempfile
import time

# Maven Central 게시 체크섬과 대조한 아티팩트의 로컬 SHA-256. 버전 변경 시 재검토합니다.
HASHES = {
    'postgresql-42.7.14.jar': '73914527305a40cce504b0d3d90b23caf565136912d607ae8ae7c5895512332c',
    'HikariCP-7.1.0.jar': '2be45c641b841e81df87292e181f2c203500c5f0e9a1676f135927387f3cf40b',
    'embedded-postgres-2.2.2.jar': '9b1722ccfbfb441b7b1440edd35c1a229b173d82ab25d6111dd8642b48ba66fa',
    'embedded-postgres-binaries-linux-amd64-18.3.0.jar': '1974c3a59c3ae010e9818e81637e425aad94eaac26e4ef533b42e081fe7ca345',
}


def cleanup_owned_database(root):
    """정상 종료 실패 시에도 이 도구가 만든 데이터 디렉터리의 postmaster만 중지합니다."""
    pidfile = root / 'data/postmaster.pid'
    if not pidfile.exists():
        return
    lines = pidfile.read_text().splitlines()
    pid, port = int(lines[0]), int(lines[3])
    proc = Path('/proc') / str(pid)
    if proc.exists():
        command = (proc / 'cmdline').read_bytes().split(b'\0')
        assert proc.stat().st_uid == os.getuid(), '시험 프로세스 소유권 불일치'
        assert str(root / 'data').encode() in command, '다른 DB 프로세스 종료 금지'
        assert Path(lines[1]).resolve() == root / 'data', 'DB 경로 불일치'
        os.kill(pid, signal.SIGINT)  # PostgreSQL fast shutdown, 디스크/원본 삭제 없음
        until = time.monotonic() + 10
        while proc.exists() and time.monotonic() < until:
            time.sleep(0.1)
        assert not proc.exists(), '시험 DB 종료 실패: 수동 확인 필요'
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', port))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--gradle', type=Path, required=True)
    parser.add_argument('--gradle-home', type=Path, required=True)
    args = parser.parse_args()
    assert os.getuid() != 0, 'root 실행 금지'
    available = next(int(line.split()[1]) for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith('MemAvailable:'))
    assert available >= 4 * 1024 * 1024, '가용 메모리 4GiB 미만: 실행 중단'
    assert shutil.disk_usage('/tmp').free >= 2 * 1024**3, '임시 디스크 여유 2GiB 미만'
    project = Path(__file__).resolve().parents[1]
    root = Path(tempfile.mkdtemp(prefix='hearth-s4-4.', dir='/tmp')).resolve()
    (root / '.hearth-s44-isolated').write_text('S4-4 isolated test only\n')
    print(f'시험 경로: {root}', flush=True)
    env = os.environ.copy()
    env['JAVA_HOME'] = str(args.java_home.resolve())
    env['GRADLE_USER_HOME'] = str(args.gradle_home.resolve())
    command = [str(args.gradle.resolve()), '--no-daemon', '--offline', '--console=plain']
    active = None

    def run(task, name):
        nonlocal active
        with (root / name).open('w') as log:
            active = subprocess.Popen(command + [task, f'-Ps44Workspace={root}'], cwd=project,
                                      env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            try:
                result = active.wait(timeout=120)
            except subprocess.TimeoutExpired:
                os.killpg(active.pid, signal.SIGTERM)
                active.wait(timeout=15)
                raise RuntimeError('시험 실행 시간 초과')
            finally:
                if active.poll() is not None:
                    active = None
            assert result == 0, f'{task} 실패: {root / name} 확인'

    try:
        run('stageS44Runtime', 'stage.log')
        for name, expected in HASHES.items():
            content = (root / 'resolved-jars' / name).read_bytes()
            assert hashlib.sha256(content).hexdigest() == expected, f'해시 불일치: {name}'
            # 실제 Gradle 캐시에 있는 동일 이름의 아티팩트도 대조합니다.
            candidates = list((args.gradle_home / 'caches/modules-2/files-2.1').rglob(name))
            assert candidates and all(hashlib.sha256(p.read_bytes()).hexdigest() == expected for p in candidates)
        run('postgresIntegrationTest', 'integration.log')
        assert (root / 'db-result.txt').read_text().startswith('PASS\n')
        assert (root / 'db-stopped.txt').is_file(), '프로세스/포트 정리 증거 누락'
        (root / 'result.json').write_text(json.dumps({'result': 'PASS', 'sha256': HASHES}, indent=2) + '\n')
        print((root / 'db-result.txt').read_text(), end='')
    finally:
        try:
            if active is not None and active.poll() is None:
                os.killpg(active.pid, signal.SIGTERM)
                active.wait(timeout=15)
        finally:
            cleanup_owned_database(root)
            print(f'증거 보존: {root}', flush=True)


if __name__ == '__main__':
    main()
