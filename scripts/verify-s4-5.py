#!/usr/bin/env python3
"""S4-5 순차 Paper 두 JVM·신규 PostgreSQL 한 개의 제한된 통합 시험."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import selectors
import shutil
import signal
import socket
import subprocess
import time
import zipfile

REPO = Path(__file__).resolve().parents[1]


def load_tool(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), REPO / 'scripts' / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


s42 = load_tool('verify-s4-2')
s44 = load_tool('verify-s4-4')


class Monitor:
    def __init__(self, root, started, prefix='HEARTH_S45', work_seconds=430):
        self.root = root
        self.started = started
        self.prefix = prefix
        self.deadline = self.started + work_seconds  # 480초 중 정상 종료·정리 예산 50초를 남깁니다.
        self.selector = selectors.DefaultSelector()
        self.children = {}
        self.stopping = False

    def launch(self, name, command, cwd):
        self.guard()
        started = time.monotonic()
        proc = subprocess.Popen(command, cwd=cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, start_new_session=True, preexec_fn=lambda: os.nice(10))
        item = dict(proc=proc, started=started, lines=[], pending=b'', log=(self.root / (name + '-console.log')).open('wb'),
                    samples=[], ready=None, stop_at=None, exit_at=None)
        self.children[name] = item
        self.selector.register(proc.stdout, selectors.EVENT_READ, name)
        return item

    def guard(self):
        if self.stopping:
            return
        if time.monotonic() >= self.deadline:
            raise RuntimeError('시험 작업 시간 한도 도달: 추가 기동 금지')
        if s42.available_kib() < 6 * 1024**2:
            raise RuntimeError('호스트 가용 RAM 6GiB 미만')

    def sample(self):
        for name, item in self.children.items():
            proc = item['proc']
            try:
                text = Path(f'/proc/{proc.pid}/status').read_text()
                rss = int(next(line.split()[1] for line in text.splitlines() if line.startswith('VmRSS:')))
                fields = Path(f'/proc/{proc.pid}/stat').read_text().split()
                sample = dict(seconds=round(time.monotonic()-item['started'], 3), rss_kib=rss,
                              cpu_ticks=int(fields[13])+int(fields[14]), host_available_kib=s42.available_kib())
                item['samples'].append(sample)
                if name.startswith('paper') and rss >= 2560 * 1024 and not self.stopping:
                    raise RuntimeError('Paper RSS 2.5GiB 이상')
            except (FileNotFoundError, StopIteration, ProcessLookupError):
                pass
            if proc.poll() is not None and item['exit_at'] is None:
                item['exit_at'] = time.monotonic()

    def pump(self):
        self.guard()
        self.sample()
        for key, _ in self.selector.select(0.1):
            item = self.children[key.data]
            chunk = os.read(key.fileobj.fileno(), 65536)
            if not chunk:
                self.selector.unregister(key.fileobj)
                continue
            item['log'].write(chunk)
            item['log'].flush()
            item['pending'] += chunk
            while b'\n' in item['pending']:
                raw, item['pending'] = item['pending'].split(b'\n', 1)
                line = raw.decode(errors='replace')
                item['lines'].append(line)
                if 'Done (' in line and item['ready'] is None:
                    item['ready'] = round(time.monotonic()-item['started'], 3)
                if any(token in line for token in [self.prefix+'_RESULT', 'HEARTH_S4_2_RESULT', 'HEARTH_S45_DB OK', 'HEARTH_S45_DB READY', self.prefix+'_DISABLE_CLOSE']):
                    print(key.data + ': ' + line, flush=True)
                if not self.stopping and any(token in line for token in [self.prefix+'_RESULT FAIL', 'HEARTH_S4_2_RESULT FAIL', 'Error occurred while', 'Could not load', 'Unknown or incomplete command']):
                    raise RuntimeError('통합 시험/플러그인 오류: ' + key.data)

    def wait(self, name, token, since=0, seconds=90):
        until = min(self.deadline, time.monotonic()+seconds)
        while not any(token in line for line in self.children[name]['lines'][since:]):
            if self.children[name]['proc'].poll() is not None:
                # 마지막 파이프 출력을 한 번 수집하고 판정합니다.
                self.pump()
                if any(token in line for line in self.children[name]['lines'][since:]):
                    return
                raise RuntimeError(name + ': 예상 토큰 이전 종료: ' + token)
            if time.monotonic() >= until:
                raise RuntimeError(name + ': 대기 한도 초과: ' + token)
            self.pump()

    def send(self, name, text):
        proc = self.children[name]['proc']
        proc.stdin.write((text+'\n').encode())
        proc.stdin.flush()

    def db(self, command):
        position = len(self.children['database']['lines'])
        self.send('database', command)
        self.wait('database', 'HEARTH_S45_DB OK '+command, position, 15)

    def action(self, name, phase):
        position = len(self.children[name]['lines'])
        self.send(name, 'hearthverify db '+phase)
        self.wait(name, 'HEARTH_S45_RESULT PASS '+phase, position, 30)

    def delay(self, seconds):
        until = time.monotonic()+seconds
        while time.monotonic() < until:
            self.pump()

    def stop(self, name, command, seconds=20):
        item = self.children[name]
        proc = item['proc']
        if proc.poll() is None:
            item['stop_at'] = time.monotonic()
            self.send(name, command)
            until = time.monotonic()+seconds
            while proc.poll() is None and time.monotonic() < until:
                self.pump()
            if proc.poll() is None:
                os.killpg(proc.pid, signal.SIGTERM)
                proc.wait(timeout=5)
                raise RuntimeError(name+': 정상 종료 시간 초과')
        self.pump()
        assert proc.returncode == 0, name+': 비정상 종료'
        if name.startswith('paper'):
            assert any(self.prefix+'_DISABLE_CLOSE PASS' in line for line in item['lines']), 'DB 풀 종료 확인 누락'

    def finish(self):
        self.stopping = True
        errors = []
        for name in reversed(self.children):
            try:
                self.stop(name, 'finish' if name == 'database' else 'stop', 15)
            except Exception as error:
                errors.append(type(error).__name__+': '+str(error))
        try:
            s44.cleanup_owned_database(self.root)
            s42.port_free()
            endpoint = self.root / 'db-endpoint.properties'
            if endpoint.exists():
                port = int(next(line.split('=')[1] for line in endpoint.read_text().splitlines() if line.startswith('port=')))
                with socket.socket() as probe:
                    probe.bind(('127.0.0.1', port))
            for item in self.children.values():
                assert item['proc'].poll() is not None, '시험 프로세스 잔존'
                try:
                    os.killpg(item['proc'].pid, 0)
                except ProcessLookupError:
                    pass
                else:
                    raise RuntimeError('시험 프로세스 그룹 잔존')
        except Exception as error:
            errors.append(type(error).__name__+': '+str(error))
        for name, item in self.children.items():
            item['log'].close()
            (self.root / (name+'-resources.json')).write_text(json.dumps(item['samples'], indent=2)+'\n')
        self.selector.close()
        return errors

    def summary(self):
        result = {}
        for name, item in self.children.items():
            samples = item['samples']
            result[name] = dict(pid=item['proc'].pid, exit_code=item['proc'].returncode, ready_seconds=item['ready'],
                duration_seconds=round((item['exit_at'] or time.monotonic())-item['started'],3),
                shutdown_seconds=round(item['exit_at']-item['stop_at'],3) if item['stop_at'] and item['exit_at'] else None,
                max_sampled_rss_kib=max((s['rss_kib'] for s in samples),default=0),
                observed_cpu_seconds=round(max((s['cpu_ticks'] for s in samples),default=0)/os.sysconf('SC_CLK_TCK'),3),
                min_sampled_host_available_kib=min((s['host_available_kib'] for s in samples),default=0), sample_count=len(samples),
                checks=[line.split(self.prefix+'_CHECK PASS ',1)[1] for line in item['lines'] if self.prefix+'_CHECK PASS ' in line],
                timings=[line.split(self.prefix+'_TIMING ',1)[1] for line in item['lines'] if self.prefix+'_TIMING ' in line],
                states=[line.split(self.prefix+'_STATE ',1)[1] for line in item['lines'] if self.prefix+'_STATE ' in line])
        return result


def main():
    started = time.monotonic()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workspace', required=True, type=Path)
    parser.add_argument('--java', required=True, type=Path)
    parser.add_argument('--plugin', required=True, type=Path)
    parser.add_argument('--artifact-source', required=True, type=Path)
    parser.add_argument('--accept-eula', action='store_true')
    args = parser.parse_args()
    root = args.workspace.resolve(strict=True)
    assert args.accept_eula and os.getuid()!=0, 'EULA 동의·일반 사용자 실행 필요'
    assert not args.workspace.is_symlink() and root.parent==Path('/tmp') and root.name.startswith('hearth-s4-5.')
    assert root.stat().st_uid==os.getuid() and (root/'.hearth-s45-isolated').read_text().strip()=='S4-5 isolated test only'
    assert not (root/'server').exists() and not (root/'data').exists(), '기존 시험 데이터 재사용 금지'
    assert s42.available_kib()>=6*1024**2 and shutil.disk_usage(root).free>=4*1024**3
    s42.port_free()
    artifacts=json.loads((REPO/'verification/s4-1-artifacts.json').read_text())['artifacts']
    for artifact in artifacts:
        source=args.artifact_source/artifact['file']
        assert hashlib.new(artifact['algorithm'],source.read_bytes()).hexdigest()==artifact['hash'], '공식 아티팩트 해시 불일치'
        shutil.copyfile(source,root/artifact['file'])
    for name, expected in s44.HASHES.items():
        assert hashlib.sha256((root/'resolved-jars'/name).read_bytes()).hexdigest()==expected, 'DB 시험 의존성 해시 불일치'
    with zipfile.ZipFile(args.plugin) as jar:
        assert 'io/github/jesterportfolio/hearth/verification/DatabaseVerification.class' in jar.namelist()
        assert not any(n.startswith(('org/bukkit/','org/postgresql/','com/zaxxer/','io/zonky/')) for n in jar.namelist())
    runtime=root/'server'; runtime.mkdir()
    # 검증된 다운로드 캐시만 재사용합니다. 월드·플러그인 설정·기존 시험 결과는 복사하지 않습니다.
    for name in ['cache','libraries']:
        shutil.copytree(args.artifact_source/'server'/name,runtime/name)
    assert not (runtime/'libraries/org/postgresql').exists(), 'pgJDBC는 Paper libraries로 처음 해석해야 합니다'
    (runtime/'.hearth-s4-isolated').write_text('S4-5 disposable test data only\n')
    plugins=runtime/'plugins'; plugins.mkdir()
    for name in ['worldguard-bukkit-7.0.19.jar','worldedit-bukkit-7.4.4.jar']:
        shutil.copyfile(root/name,plugins/name)
    shutil.copyfile(args.plugin,plugins/'Hearth.jar')
    (plugins/'Hearth').mkdir(); (plugins/'Hearth/config.yml').write_text('verification-enabled: true\ndb-verification-enabled: true\n')
    (plugins/'bStats').mkdir(); (plugins/'bStats/config.yml').write_text('enabled: false\n')
    (runtime/'eula.txt').write_text('eula=true\n')
    (runtime/'server.properties').write_text('''server-ip=127.0.0.1
server-port=25585
online-mode=true
white-list=true
enforce-whitelist=true
enable-rcon=false
enable-query=false
max-players=1
view-distance=2
simulation-distance=2
spawn-protection=0
level-name=world
level-type=minecraft:flat
generator-settings={"layers":[{"block":"minecraft:bedrock","height":1}],"biome":"minecraft:plains"}
pause-when-empty-seconds=-1
motd=Hearth isolated S4-5 verification
''')
    monitor=Monitor(root,started)
    outcome='FAIL'; failure=None; cleanup=[]
    try:
        helper=[str(args.java.resolve()),'-Xmx128m','-XX:ActiveProcessorCount=2','-Djava.io.tmpdir='+str(root),
                '-cp',str(root/'fixture-classes')+':'+str(root/'resolved-jars/*'),
                'io.github.jesterportfolio.hearth.persistence.S45DatabaseFixture',str(root)]
        monitor.launch('database',helper,root)
        monitor.wait('database','HEARTH_S45_DB READY',seconds=30)
        paper=[str(args.java.resolve()),'-Xms256m','-Xmx1536m','-XX:ActiveProcessorCount=2',
               '-Dhearth.verification.root='+str(runtime),'-Dhearth.verification.database=true',
               '-jar',str(root/'paper-26.2-134.jar'),'--nogui']
        for phase in ['prepare','resume']:
            s42.port_free()
            name='paper-'+phase
            monitor.launch(name,paper,runtime)
            monitor.wait(name,'Done (',seconds=120)
            monitor.wait(name,'HEARTH_S45_MODULE READY',seconds=10)
            monitor.send(name,'hearthverify restart '+phase)
            monitor.wait(name,'HEARTH_S4_2_RESULT PASS '+phase,seconds=90)
            monitor.action(name,phase)
            if phase=='prepare':
                monitor.action(name,'close')
            else:
                monitor.action(name,'revision')
                monitor.db('write-fail-on'); monitor.action(name,'write-failure'); monitor.db('write-fail-off')
                monitor.db('schema-corrupt'); monitor.action(name,'schema-mismatch')
                monitor.db('schema-restore'); monitor.action(name,'schema-restore')
                monitor.db('table-hide'); monitor.action(name,'schema-missing')
                monitor.db('table-show'); monitor.action(name,'schema-restore')
                for action, token in [('queue','QUEUE_SUBMITTED'),('drain','DRAIN_SUBMITTED')]:
                    monitor.db('lock')
                    position=len(monitor.children[name]['lines'])
                    monitor.send(name,'hearthverify db '+action)
                    monitor.wait(name,'HEARTH_S45_'+token,position,10)
                    monitor.delay(0.6)
                    monitor.db('unlock')
                    monitor.wait(name,'HEARTH_S45_RESULT PASS '+action,position,15)
                monitor.action(name,'disconnect-ready')
                monitor.db('stop-db')
                monitor.action(name,'disconnected')
            monitor.stop(name,'stop')
            s42.port_free()
        monitor.stop('database','finish',10)
        for library in ['postgresql-42.7.14.jar','HikariCP-7.1.0.jar']:
            loaded=list((runtime/'libraries').rglob(library))
            assert loaded and all(hashlib.sha256(p.read_bytes()).hexdigest()==s44.HASHES[library] for p in loaded), 'Paper libraries 해석/아티팩트 불일치'
        outcome='PASS'
    except Exception as error:
        failure=type(error).__name__+': '+str(error)
        print('시험 실패: '+failure,flush=True)
    finally:
        cleanup=monitor.finish()
        if cleanup:
            outcome='FAIL'
        result=dict(outcome=outcome, failure=failure, cleanup_errors=cleanup, workspace=str(root),
                    duration_seconds=round(time.monotonic()-monitor.started,3), limits=dict(total_seconds=480,work_seconds=430,
                    paper_starts_max=2,paper_heap_mib=1536,paper_rss_stop_mib=2560,host_available_min_mib=6144,nice=10,active_processors=2),
                    processes=monitor.summary(),all_owned_processes_and_ports_closed=not cleanup,
                    plugin_sha256=hashlib.sha256(args.plugin.read_bytes()).hexdigest(),
                    runtime_library_sources=[line.split('HEARTH_S45_LIBRARY ',1)[1] for item in monitor.children.values() for line in item['lines'] if 'HEARTH_S45_LIBRARY ' in line],
                    runtime_library_jars=[str(p.relative_to(runtime)) for p in (runtime/'libraries').rglob('*.jar') if any(part in p.parts for part in ['postgresql','HikariCP','slf4j-api','checker-qual'])],
                    not_executed=['실제 인증 플레이어','PostgreSQL 프로세스 재시작','TPS/MSPT·장기 부하·메모리 누수','운영 배포·전체 인벤토리·자동 저장'])
        (root/'run-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
        print('S4-5 '+outcome+'; 증거: '+str(root/'run-result.json'),flush=True)
    return 0 if outcome=='PASS' else 1


if __name__=='__main__':
    raise SystemExit(main())
