#!/usr/bin/env python3
"""S4-11 월드 생명주기 순차 두 JVM 통합 시험. S4-5 감시·소유 DB 정리와 고정 아티팩트를 재사용합니다."""
import argparse
import hashlib
import importlib.util
import json
import os
import re
import select
import sys
from pathlib import Path
import shutil
import time
import zipfile

REPO=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('s45',REPO/'scripts/verify-s4-5.py')
s45=importlib.util.module_from_spec(spec);spec.loader.exec_module(s45)
s42=s45.s42; s44=s45.s44

class Monitor(s45.Monitor):
    def manual_session(self,name):
        print(name+': 수동 시험 준비 완료. 접속/행동 후 클라이언트를 종료하고 stop을 입력하세요. 시간 한도는 연장하지 않습니다.',flush=True)
        while True:
            self.pump()
            if self.children[name]['proc'].poll() is not None:
                raise RuntimeError('수동 시험 중 Paper 예기치 않은 종료')
            if select.select([sys.stdin],[],[],0)[0]:
                command=sys.stdin.readline().strip()
                if command=='stop':
                    return
                if not permitted_session_command(command):
                    print('허용되지 않은 수동 시험 명령입니다.',flush=True)
                    continue
                self.send(name,command)
            # 기본 감시가 파일 로그를 보존하고, 운영자에게 실제 행동/저장 판정도 보여 줍니다.
            item=self.children[name]; position=item.get('manual_position',len(item['lines']))
            for line in item['lines'][position:]:
                if any(token in line for token in ['HEARTH_SESSION_', 'HEARTH_S4_3_', 'HEARTH_LIFE_STATE', 'joined the game', 'left the game']):
                    print(name+': '+line,flush=True)
            item['manual_position']=len(item['lines'])

    def action(self,name,phase):
        position=len(self.children[name]['lines'])
        self.send(name,'hearthverify lifecycle '+phase)
        self.wait(name,'HEARTH_LIFE_RESULT PASS '+phase,position,90)
    def blocked(self,name,phase,token):
        self.db('lock')
        position=len(self.children[name]['lines'])
        self.send(name,'hearthverify lifecycle '+phase)
        self.wait(name,token,position,10)
        self.delay(0.6)
        self.db('unlock')
        self.wait(name,'HEARTH_LIFE_RESULT PASS '+phase,position,20)

def automatic_first(monitor,name):
    monitor.action(name,'session-basic')
    monitor.db('lock'); monitor.action(name,'session-race'); monitor.db('unlock')
    monitor.action(name,'session-race-done')
    monitor.db('lock'); monitor.action(name,'session-prepare-quit'); monitor.db('unlock')
    monitor.action(name,'session-prepare-done'); monitor.action(name,'session-move-quit')


def permitted_session_command(command):
    # 기존 S4-3의 좁은 콘솔 허용 목록과 좌표 제한을 재사용합니다.
    if s42.permitted_manual_command(command):
        return True
    return bool(re.fullmatch(r'hearthverify session-(?:fixture|link-existing|status) [A-Za-z0-9_]{3,16}',command))

def main():
    started = time.monotonic()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workspace', required=True, type=Path)
    parser.add_argument('--java', required=True, type=Path)
    parser.add_argument('--plugin', required=True, type=Path)
    parser.add_argument('--artifact-source', required=True, type=Path)
    parser.add_argument('--accept-eula', action='store_true')
    parser.add_argument('--quests', action='store_true', help='격리 개인 방문 퀘스트/NPC와 기존 세션을 검증합니다')
    parser.add_argument('--sessions', action='store_true', help='새 격리 환경에서 최소 세션 어댑터/저장 장벽을 검증합니다')
    parser.add_argument('--manual-player', action='store_true', help='인증 클라이언트가 준비된 뒤에만 사용하는 별도 수동 데이터/두 JVM 모드')
    parser.add_argument('--operator-ready', action='store_true', help='프로필·기존 SSH 전달 경로·클라이언트 준비를 운영자가 확인했습니다')
    parser.add_argument('--profile', help='인증 Java Edition 프로필 이름. 비밀번호/토큰은 받지 않습니다')
    args = parser.parse_args()
    if args.manual_player and not (args.sessions and args.operator_ready and sys.stdin.isatty() and re.fullmatch(r'[A-Za-z0-9_]{3,16}',args.profile or '')):
        parser.error('수동 모드는 --sessions --operator-ready --profile 이름과 운영자 TTY/준비된 인증 클라이언트가 필요합니다')
    if not args.manual_player and (args.operator_ready or args.profile):
        parser.error('실제 프로필은 별도 --manual-player 모드에서만 사용합니다')
    if args.quests and not args.sessions:
        parser.error("--quests는 --sessions가 필요합니다")
    root = args.workspace.resolve(strict=True)
    assert args.accept_eula and os.getuid()!=0, 'EULA 동의·일반 사용자 실행 필요'
    assert not args.workspace.is_symlink() and root.parent==Path('/tmp') and root.name.startswith('hearth-s4-11.')
    assert root.stat().st_uid==os.getuid() and (root/'.hearth-lifecycle-isolated').read_text().strip()=='S4-11 isolated test only'
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
        assert 'io/github/jesterportfolio/hearth/verification/LifecycleVerification.class' in jar.namelist()
        assert not any(n.startswith(('org/bukkit/','org/postgresql/','com/zaxxer/','io/zonky/')) for n in jar.namelist())
    runtime=root/'server'; runtime.mkdir()
    # 검증된 다운로드 캐시만 재사용합니다. 월드·플러그인 설정·기존 시험 결과는 복사하지 않습니다.
    for name in ['cache','libraries']:
        shutil.copytree(args.artifact_source/'server'/name,runtime/name)
    assert not (runtime/'libraries/org/postgresql').exists(), 'pgJDBC는 Paper libraries로 처음 해석해야 합니다'
    (runtime/'.hearth-s4-isolated').write_text('S4-11 disposable test data only\n')
    plugins=runtime/'plugins'; plugins.mkdir()
    for name in ['worldguard-bukkit-7.0.19.jar','worldedit-bukkit-7.4.4.jar']:
        shutil.copyfile(root/name,plugins/name)
    shutil.copyfile(args.plugin,plugins/'Hearth.jar')
    (plugins/'Hearth').mkdir()
    idle_seconds=10 if args.manual_player else 1
    (plugins/'Hearth/config.yml').write_text(f"quest-verification-enabled: {str(args.quests).lower()}\n"+'verification-enabled: true\ndb-verification-enabled: false\ninventory-verification-enabled: false\nlifecycle-verification-enabled: true\n'+f'lifecycle-test-idle-seconds: {idle_seconds}\n')
    if args.sessions:
        with (plugins/'Hearth/config.yml').open('a') as config:
            config.write('session-verification-enabled: true\ninventory-autosave-seconds: 30\n')
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
motd=Hearth isolated S4-11 verification
''')
    monitor=Monitor(root,started,prefix='HEARTH_LIFE',work_seconds=550)
    outcome='FAIL'; failure=None; cleanup=[]
    try:
        helper=[str(args.java.resolve()),'-Xmx128m','-XX:ActiveProcessorCount=2','-Djava.io.tmpdir='+str(root),
                '-cp',str(root/'fixture-classes')+':'+str(root/'resolved-jars/*'),
                'io.github.jesterportfolio.hearth.persistence.S45DatabaseFixture',str(root),'--lifecycle']
        monitor.launch('database',helper,root)
        monitor.wait('database','HEARTH_S45_DB READY',seconds=30)
        paper=[str(args.java.resolve()),'-Xms256m','-Xmx1536m','-XX:ActiveProcessorCount=2',
               '-Dhearth.verification.root='+str(runtime),'-Dhearth.verification.lifecycle=true',
               '-jar',str(root/'paper-26.2-134.jar'),'--nogui']
        if args.sessions:
            paper.insert(-3,'-Dhearth.verification.sessions=true')
            paper.insert(-3,'-Dhearth.verification.players=true')
        if args.quests:
            paper.insert(-3,'-Dhearth.verification.quests=true')
        if args.manual_player:
            paper.insert(-3,'-Dhearth.verification.sessionManual=true')
        for phase in ['prepare','resume']:
            s42.port_free()
            name='paper-'+phase
            monitor.launch(name,paper,runtime)
            monitor.wait(name,'Done (',seconds=120)
            monitor.wait(name,'HEARTH_LIFE_MODULE READY',seconds=10)
            if phase=='prepare':
                start_phase='prepare-player' if args.sessions else 'prepare'
                monitor.send(name,'hearthverify restart '+start_phase)
                monitor.wait(name,'HEARTH_S4_2_RESULT PASS '+start_phase,seconds=90)
                if args.sessions:
                    monitor.wait(name,'HEARTH_S4_3_READY',seconds=10)
                if args.sessions:
                    monitor.action(name,'session-prepare')
                    if args.manual_player:
                        monitor.send(name,'whitelist add '+args.profile)
                        monitor.wait(name,'Added '+args.profile,seconds=20)
                        monitor.send(name,'deop '+args.profile)
                        monitor.send(name,'hearthverify session-fixture '+args.profile)
                        monitor.wait(name,'HEARTH_SESSION_FIXTURE READY',seconds=20)
                        monitor.manual_session(name)
                    else:
                        if args.quests:
                            monitor.action(name,'quest-first'); monitor.action(name,'quest-cycle')
                        else:
                            automatic_first(monitor,name)
                else:
                    monitor.action(name,'prepare')
                if not args.sessions:
                    monitor.action(name,'wait-safety')
                    monitor.action(name,'idle-cancel')
                    monitor.db('lock')
                    monitor.action(name,'begin-cycle')
                    monitor.delay(0.3)
                    monitor.db('unlock')
                    monitor.wait(name,'HEARTH_LIFE_SAVE_PAUSED',seconds=20)
                    monitor.action(name,'queue-entry')
                    monitor.action(name,'resume-save')
            else:
                # 기존 매핑을 직접 로드합니다. 복제·신규 월드·신규 DB 행 생성은 하지 않습니다.
                if args.sessions:
                    if args.manual_player:
                        monitor.send(name,'hearthverify player-open')
                        monitor.wait(name,'HEARTH_S4_3_READY',seconds=30)
                        monitor.action(name,'session-resume')
                        monitor.send(name,'hearthverify session-link-existing '+args.profile)
                        monitor.wait(name,'HEARTH_SESSION_FIXTURE READY',seconds=20)
                        monitor.manual_session(name)
                    elif args.quests:
                        monitor.action(name,'session-resume'); monitor.action(name,'quest-restored')
                        monitor.db('quest-fail-on'); monitor.action(name,'quest-failure'); monitor.db('quest-fail-off')
                        monitor.action(name,'quest-cycle')
                    else:
                        monitor.action(name,'session-resume'); monitor.action(name,'session-restored')
                        monitor.action(name,'session-move-failure')
                        monitor.db('write-fail-on'); monitor.action(name,'session-write-failure'); monitor.db('write-fail-off')
                        for stage in ['personal','inventory']:
                            monitor.db('lock-personal' if stage=='personal' else 'lock')
                            monitor.action(name,'session-'+stage+'-generation')
                            monitor.db('unlock')
                            monitor.action(name,'session-'+stage+'-generation-done')
                else:
                    monitor.action(name,'resume')
                if not args.sessions:
                    monitor.action(name,'stale')
                    monitor.db('write-fail-on'); monitor.action(name,'db-failure'); monitor.db('write-fail-off')
                    monitor.action(name,'boundaries')
                    monitor.action(name,'unload-false')
            monitor.action(name,'close')
            monitor.stop(name,'stop')
            s42.port_free()
        monitor.stop('database','finish',10)
        for library in ['postgresql-42.7.14.jar','HikariCP-7.1.0.jar']:
            loaded=list((runtime/'libraries').rglob(library))
            assert loaded and all(hashlib.sha256(p.read_bytes()).hexdigest()==s44.HASHES[library] for p in loaded), 'Paper libraries 해석/아티팩트 불일치'
        assert args.plugin.read_bytes()==(plugins/'Hearth.jar').read_bytes(), '실행 JAR과 최종 빌드 JAR 불일치'
        outcome='MANUAL_CLOSED_UNVERIFIED' if args.manual_player else 'PASS'
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
                    session_checks=[line.split('HEARTH_SESSION_CHECK PASS ',1)[1] for item in monitor.children.values() for line in item['lines'] if 'HEARTH_SESSION_CHECK PASS ' in line],
                    sessions_mode=args.sessions, quests_mode=args.quests, manual_player_mode=args.manual_player,
                    installed_plugin_sha256=hashlib.sha256((plugins/'Hearth.jar').read_bytes()).hexdigest(),
                    snapshots=[line.split('HEARTH_LIFE_SNAPSHOT ',1)[1] for item in monitor.children.values() for line in item['lines'] if 'HEARTH_LIFE_SNAPSHOT ' in line],
                    runtime_library_jars=[str(p.relative_to(runtime)) for p in (runtime/'libraries').rglob('*.jar') if any(part in p.parts for part in ['postgresql','HikariCP','slf4j-api','checker-qual'])],
                    player_actions_verdict='NOT_AUTOMATICALLY_ASSESSED' if args.manual_player else 'NOT_RUN',
                    not_executed=([] if args.manual_player else ['실제 인증 플레이어/로그인·텔레포트'])+['PostgreSQL 프로세스 재시작','실제 파일시스템 저장 장애·강제 종료 복구','TPS/MSPT·장기 부하·메모리 누수','운영 배포·경제 realm'])
        (root/'run-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
        print('S4-11 '+outcome+'; 증거: '+str(root/'run-result.json'),flush=True)
    return 0 if outcome in ['PASS','MANUAL_CLOSED_UNVERIFIED'] else 1


if __name__=='__main__':
    raise SystemExit(main())
