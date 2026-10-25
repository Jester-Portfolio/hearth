#!/usr/bin/env python3
"""S4-6~8 순차 두 JVM 통합 시험. S4-5 감시·소유 DB 정리와 고정 아티팩트를 재사용합니다."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import time
import zipfile

REPO=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('s45',REPO/'scripts/verify-s4-5.py')
s45=importlib.util.module_from_spec(spec);spec.loader.exec_module(s45)
s42=s45.s42; s44=s45.s44

class Monitor(s45.Monitor):
    def action(self,name,phase):
        position=len(self.children[name]['lines'])
        self.send(name,'hearthverify inventory '+phase)
        self.wait(name,'HEARTH_INV_RESULT PASS '+phase,position,90)
    def blocked(self,name,phase,token):
        self.db('lock')
        position=len(self.children[name]['lines'])
        self.send(name,'hearthverify inventory '+phase)
        self.wait(name,token,position,10)
        self.delay(0.6)
        self.db('unlock')
        self.wait(name,'HEARTH_INV_RESULT PASS '+phase,position,20)

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
    assert not args.workspace.is_symlink() and root.parent==Path('/tmp') and root.name.startswith('hearth-s4-6.')
    assert root.stat().st_uid==os.getuid() and (root/'.hearth-inventory-isolated').read_text().strip()=='S4-6 isolated test only'
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
        assert 'io/github/jesterportfolio/hearth/verification/InventoryVerification.class' in jar.namelist()
        assert not any(n.startswith(('org/bukkit/','org/postgresql/','com/zaxxer/','io/zonky/')) for n in jar.namelist())
    runtime=root/'server'; runtime.mkdir()
    # 검증된 다운로드 캐시만 재사용합니다. 월드·플러그인 설정·기존 시험 결과는 복사하지 않습니다.
    for name in ['cache','libraries']:
        shutil.copytree(args.artifact_source/'server'/name,runtime/name)
    assert not (runtime/'libraries/org/postgresql').exists(), 'pgJDBC는 Paper libraries로 처음 해석해야 합니다'
    (runtime/'.hearth-s4-isolated').write_text('S4-6 disposable test data only\n')
    plugins=runtime/'plugins'; plugins.mkdir()
    for name in ['worldguard-bukkit-7.0.19.jar','worldedit-bukkit-7.4.4.jar']:
        shutil.copyfile(root/name,plugins/name)
    shutil.copyfile(args.plugin,plugins/'Hearth.jar')
    (plugins/'Hearth').mkdir(); (plugins/'Hearth/config.yml').write_text('verification-enabled: true\ndb-verification-enabled: false\ninventory-verification-enabled: true\ninventory-autosave-seconds: 30\n')
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
motd=Hearth isolated S4-6 verification
''')
    monitor=Monitor(root,started,prefix='HEARTH_INV',work_seconds=550)
    outcome='FAIL'; failure=None; cleanup=[]
    try:
        helper=[str(args.java.resolve()),'-Xmx128m','-XX:ActiveProcessorCount=2','-Djava.io.tmpdir='+str(root),
                '-cp',str(root/'fixture-classes')+':'+str(root/'resolved-jars/*'),
                'io.github.jesterportfolio.hearth.persistence.S45DatabaseFixture',str(root),'--inventory']
        monitor.launch('database',helper,root)
        monitor.wait('database','HEARTH_S45_DB READY',seconds=30)
        paper=[str(args.java.resolve()),'-Xms256m','-Xmx1536m','-XX:ActiveProcessorCount=2',
               '-Dhearth.verification.root='+str(runtime),'-Dhearth.verification.inventory=true',
               '-jar',str(root/'paper-26.2-134.jar'),'--nogui']
        for phase in ['prepare','resume']:
            s42.port_free()
            name='paper-'+phase
            monitor.launch(name,paper,runtime)
            monitor.wait(name,'Done (',seconds=120)
            monitor.wait(name,'HEARTH_INV_MODULE READY',seconds=10)
            monitor.send(name,'hearthverify restart '+phase)
            monitor.wait(name,'HEARTH_S4_2_RESULT PASS '+phase,seconds=90)
            if phase=='prepare':
                monitor.action(name,'s46')
                monitor.blocked(name,'race','HEARTH_INV_RACE_SUBMITTED')
                monitor.db('write-fail-on'); monitor.action(name,'write-failure'); monitor.db('write-fail-off')
                monitor.action(name,'recover'); monitor.action(name,'s46-done')
                monitor.action(name,'switch'); monitor.action(name,'logout')
            else:
                monitor.action(name,'restore'); monitor.action(name,'missing'); monitor.action(name,'apply-failure')
                monitor.db('inventory-corrupt'); monitor.action(name,'corrupt'); monitor.db('inventory-repair')
                monitor.db('write-fail-on'); monitor.action(name,'source-failure'); monitor.db('write-fail-off')
                monitor.blocked(name,'stale','HEARTH_INV_STALE_REQUESTED')
                monitor.blocked(name,'queue','HEARTH_INV_QUEUE_SUBMITTED')
                monitor.action(name,'repeat')
                monitor.blocked(name,'logout-race','HEARTH_INV_LOGOUT_SUBMITTED')
                monitor.db('stop-db'); monitor.action(name,'disconnected')
            monitor.action(name,'close')
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
                    duration_seconds=round(time.monotonic()-monitor.started,3), limits=dict(total_seconds=600,work_seconds=550,
                    paper_starts_max=2,paper_heap_mib=1536,paper_rss_stop_mib=2560,host_available_min_mib=6144,nice=10,active_processors=2),
                    processes=monitor.summary(),all_owned_processes_and_ports_closed=not cleanup,
                    plugin_sha256=hashlib.sha256(args.plugin.read_bytes()).hexdigest(),
                    runtime_library_sources=[line.split('HEARTH_S45_LIBRARY ',1)[1] for item in monitor.children.values() for line in item['lines'] if 'HEARTH_S45_LIBRARY ' in line],
                    runtime_library_jars=[str(p.relative_to(runtime)) for p in (runtime/'libraries').rglob('*.jar') if any(part in p.parts for part in ['postgresql','HikariCP','slf4j-api','checker-qual'])],
                    not_executed=['실제 인증 플레이어/PlayerInventory 이벤트','PostgreSQL 프로세스 재시작','TPS/MSPT·장기 부하·메모리 누수','운영 배포·엔더 상자/커서/GUI·경제 realm'])
        (root/'run-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
        print('S4-6~8 '+outcome+'; 증거: '+str(root/'run-result.json'),flush=True)
    return 0 if outcome=='PASS' else 1


if __name__=='__main__':
    raise SystemExit(main())
