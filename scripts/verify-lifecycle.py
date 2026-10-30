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
    if re.fullmatch(r'hearthverify combat-player-(?:start|release) [A-Za-z0-9_]{3,16}',command) or re.fullmatch(r'hearthverify combat-player-revive [A-Za-z0-9_]{3,16} public_rescue',command):
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
    parser.add_argument('--world-effects', action='store_true', help='Issue #1 단일 블록 효과·충돌·두 JVM 복원에 필요한 축소 시나리오만 실행합니다')
    parser.add_argument('--combat-players', action='store_true', help='전투 Player 적용기 로딩·세션 저장/재접속·제어 포트 회복을 검사합니다')
    parser.add_argument('--combat-state', action='store_true', help='보스 참가/일시정지/관전/부활 제어와 실제 몹 AI를 한 JVM에서 검사합니다')
    parser.add_argument('--field-encounters', action='store_true', help='필드 조우·고정 난이도·진행 격리·정리를 한 JVM에서 검사합니다')
    parser.add_argument('--branch-progression', action='store_true', help='확정 분기·개인 보상/장 화면과 두 JVM 복원을 검사합니다')
    parser.add_argument('--quest-completion', action='store_true', help='실패/재시작·즉시 보상·장 스킵과 두 JVM 복원을 검사합니다')
    parser.add_argument('--general-npcs', action='store_true', help='일반 NPC·일지 대화 연결·연속 조사와 두 JVM 복원을 검사합니다')
    parser.add_argument('--npc-topics', action='store_true', help='조건부 NPC 질문·개인 일지·두 JVM 복원을 검사합니다')
    parser.add_argument('--quest-interactions', action='store_true', help='상호작용 목표와 두 JVM 복원을 검사합니다')
    parser.add_argument('--npc-pages', action='store_true', help='NPC 대화 페이지와 토큰 정리를 한 JVM에서 검증합니다')
    parser.add_argument('--quest-locations', action='store_true', help='목표 위치 공개의 표시·저장 경계를 한 JVM에서 검증합니다')
    parser.add_argument('--playthrough', action='store_true', help='공개 시험 코스의 연속 플레이 흐름과 두 JVM 복원을 검증합니다')
    parser.add_argument('--phone-dialog',action='store_true',help='기존 navigation 시험에서 Dialog 프로토타입을 함께 초기화합니다')
    parser.add_argument('--quest-navigation', action='store_true', help='보고·추적·알림 동선을 한 JVM에서 검증합니다')
    parser.add_argument('--game-information', action='store_true', help='공개 게임 정보 조회 경계를 한 JVM에서 검증합니다')
    parser.add_argument('--npc-views', action='store_true', help='개인 NPC 표시 데이터·표식 엔티티를 한 JVM에서 검증합니다')
    parser.add_argument('--player-interactions', action='store_true', help='41슬롯 조작 정책·dirty 저장 연결을 한 JVM에서 검증합니다')
    parser.add_argument('--gameplay-guide', action='store_true', help='공유 진행 안내·GUI 연결만 한 JVM에서 검증합니다')
    parser.add_argument('--story-batch', action='store_true', help='두 JVM에서 공유 단계·채팅 투표 제어 경계·분기·접근·복원을 검사합니다')
    parser.add_argument('--explore-batch', action='store_true', help='두 JVM에서 탐험·일지·공동체·발견 알림·설정과 V005를 검사합니다')
    parser.add_argument('--quest-batch', action='store_true', help='두 JVM에서 다단계/추적/알림/설정/GUI·HUD 데이터와 V004를 검사합니다')
    parser.add_argument('--quest-requires', action='store_true', help='한 JVM에서 개인 선행 완료 조건·잠금 비노출·시작 경합을 검사합니다')
    parser.add_argument('--quest-start', action='store_true', help='한 JVM에서 지역 진입 자동 시작·NPC 차단·중복/실패/stale를 검사합니다')
    parser.add_argument('--quest-npc', action='store_true', help='한 NPC의 여러 개인 퀘스트 선택·nonce·지연 응답을 검사합니다')
    parser.add_argument('--quest-auto', action='store_true', help='한 JVM에서 보고형/자동 완료형의 목표·저장·중복·실패·stale를 검사합니다')
    parser.add_argument('--quest-info', action='store_true', help='한 JVM에서 퀘스트 상세/목록 클릭 데이터와 기존 읽기 경계를 검사합니다')
    parser.add_argument('--quest-list', action='store_true', help='기존 세션/DB 도구로 읽기 전용 개인 목록만 검사합니다')
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
    if args.combat_players and (not args.story_batch or any([args.combat_state,args.field_encounters,args.branch_progression,args.quest_completion,args.playthrough,args.general_npcs,args.npc_topics,args.quest_interactions,args.npc_pages,args.quest_locations,args.quest_navigation,args.game_information,args.npc_views,args.player_interactions,args.gameplay_guide,args.world_effects])):
        parser.error('--combat-players는 --sessions --quests --story-batch와 단독 실행합니다')
    if args.combat_state and (not args.story_batch or any([args.manual_player,args.field_encounters,args.branch_progression,args.quest_completion,args.playthrough,args.general_npcs,args.npc_topics,args.quest_interactions,args.npc_pages,args.quest_locations,args.quest_navigation,args.game_information,args.npc_views,args.player_interactions,args.gameplay_guide,args.world_effects])):
        parser.error('--combat-state는 --sessions --quests --story-batch와 단독 실행합니다')
    if args.field_encounters and (not args.story_batch or any([args.manual_player,args.branch_progression,args.quest_completion,args.playthrough,args.general_npcs,args.npc_topics,args.quest_interactions,args.npc_pages,args.quest_locations,args.quest_navigation,args.game_information,args.npc_views,args.player_interactions,args.gameplay_guide,args.world_effects])):
        parser.error('--field-encounters는 --sessions --quests --story-batch와 단독 실행합니다')
    if args.branch_progression and (not args.story_batch or any([args.quest_completion,args.playthrough,args.general_npcs,args.npc_topics,args.quest_interactions,args.npc_pages,args.quest_locations,args.quest_navigation,args.game_information,args.npc_views,args.player_interactions,args.gameplay_guide,args.quest_info,args.quest_auto,args.quest_npc,args.quest_start,args.quest_requires,args.quest_list,args.manual_player])):
        parser.error('--branch-progression은 --sessions --quests --story-batch와 함께 실행합니다')
    if args.quest_completion and (not args.story_batch or any([args.playthrough,args.general_npcs,args.npc_topics,args.quest_interactions,args.npc_pages])):
        parser.error('--quest-completion은 --sessions --quests --story-batch와 함께 실행합니다')
    if args.general_npcs and (not args.story_batch or any([args.playthrough,args.quest_interactions,args.npc_topics,args.npc_pages])):
        parser.error('--general-npcs는 --sessions --quests --story-batch와 함께 단독 시나리오로 실행합니다')
    if args.npc_topics and (not args.story_batch or args.playthrough or args.quest_interactions):
        parser.error('--npc-topics는 --sessions --quests --story-batch와 실행하며 --npc-pages로 읽기 경계를 함께 검사할 수 있습니다')
    if args.quest_interactions and (not args.story_batch or args.playthrough):
        parser.error('--quest-interactions는 --sessions --quests --story-batch와 함께 실행합니다')
    if args.npc_pages and (not args.story_batch or args.playthrough):
        parser.error('--npc-pages는 --sessions --quests --story-batch와 함께 실행합니다')
    if args.quest_locations and (not args.story_batch or args.playthrough):
        parser.error('--quest-locations는 --sessions --quests --story-batch와 함께 실행합니다')
    if args.playthrough and (not args.story_batch or any([args.world_effects,args.quest_navigation,args.game_information,args.npc_views,args.player_interactions,args.gameplay_guide])):
        parser.error('--playthrough는 --sessions --quests --story-batch와 함께 단독 실행합니다')
    if args.phone_dialog and not (args.quest_navigation or args.quest_completion):
        parser.error("--phone-dialog는 --quest-navigation 또는 --quest-completion이 필요합니다")
    if args.quest_navigation and (not args.story_batch or args.world_effects or args.game_information or args.npc_views or args.player_interactions):
        parser.error('--quest-navigation은 --sessions --quests --story-batch와 함께 단독 실행합니다')
    if args.game_information and (not args.story_batch or args.world_effects or args.player_interactions or args.npc_views):
        parser.error('--game-information은 --sessions --quests --story-batch와 함께 실행합니다')
    if args.npc_views and (not args.story_batch or args.world_effects or args.player_interactions):
        parser.error('--npc-views는 --sessions --quests --story-batch와 함께 실행합니다')
    if args.player_interactions and (not args.sessions or args.quests or args.manual_player):
        parser.error('--player-interactions는 자동 --sessions 단독으로 실행합니다')
    if args.gameplay_guide and (not args.story_batch or args.world_effects):
        parser.error('--gameplay-guide는 --story-batch와 함께 사용하며 --world-effects와 혼용하지 않습니다')
    if args.world_effects and not args.story_batch:
        parser.error('--world-effects는 --sessions --quests --story-batch가 필요합니다')
    if args.story_batch and (not args.quests or not args.sessions or (args.manual_player and not (args.playthrough or args.combat_players)) or any([args.explore_batch,args.quest_batch,args.quest_requires,args.quest_start,args.quest_npc,args.quest_auto,args.quest_info,args.quest_list])):
        parser.error('--story-batch는 자동 --sessions --quests 단독 공유 진행 검증 모드입니다')
    if args.explore_batch and (not args.quests or not args.sessions or args.manual_player or any([args.quest_batch,args.quest_requires,args.quest_start,args.quest_npc,args.quest_auto,args.quest_info,args.quest_list])):
        parser.error('--explore-batch는 자동 --sessions --quests 단독 탐험 검증 모드에서만 사용합니다')
    if args.quest_batch and (not args.quests or not args.sessions or args.manual_player or args.quest_requires or args.quest_start or args.quest_npc or args.quest_auto or args.quest_info or args.quest_list):
        parser.error("--quest-batch는 자동 --sessions --quests 단독 배치 검증 모드에서만 사용합니다")
    if args.quest_requires and (not args.quests or not args.sessions or args.manual_player or args.quest_start or args.quest_npc or args.quest_auto or args.quest_info or args.quest_list):
        parser.error("--quest-requires는 자동 --sessions --quests 단독 선행 조건 검증 모드에서만 사용합니다")
    if args.quest_start and (not args.quests or not args.sessions or args.manual_player or args.quest_npc or args.quest_auto or args.quest_info or args.quest_list):
        parser.error("--quest-start는 자동 --sessions --quests 단독 시작 검증 모드에서만 사용합니다")
    if args.quest_npc and (not args.quests or not args.sessions or args.manual_player or args.quest_auto or args.quest_info or args.quest_list):
        parser.error("--quest-npc는 자동 --sessions --quests 단독 NPC 선택 검증 모드에서만 사용합니다")
    if args.quest_auto and (not args.quests or not args.sessions or args.manual_player or args.quest_info or args.quest_list):
        parser.error("--quest-auto는 자동 --sessions --quests 단독 완료 검증 모드에서만 사용합니다")
    if args.quest_info and (not args.quests or not args.sessions or args.manual_player or args.quest_list):
        parser.error("--quest-info는 자동 --sessions --quests 단독 상세 모드에서만 사용합니다")
    if args.quest_list and (not args.quests or not args.sessions or args.manual_player):
        parser.error("--quest-list는 자동 --sessions --quests 모드에서만 사용합니다")
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
    if args.quests and not args.manual_player and not args.quest_batch and not args.explore_batch and not args.story_batch:
        # 기존 읽기 전용 모드의 고정 두 퀘스트 fixture를 보존합니다. 새 경로에만 기록합니다.
        with zipfile.ZipFile(args.plugin) as jar:
            content=jar.read('quest-demo.yml').decode('utf-8')
        cut='  - id: route_marker\n' if args.quest_requires else '  - id: entry_marker\n' if args.quest_info or args.quest_list else '  - id: followup_marker\n'
        content=re.sub(r'(?ms)^  - id: field_researcher\n.*?(?=^quests:)', '', content)
        content=re.sub(r'(?ms)^    topics:\n.*?(?=^    \S)', '', content)
        (plugins/'Hearth/quest-demo.yml').write_text(content.partition(cut)[0])
    idle_seconds=10 if args.manual_player else 1
    (plugins/'Hearth/config.yml').write_text(f"quest-verification-enabled: {str(args.quests).lower()}\n"+'verification-enabled: true\ndb-verification-enabled: false\ninventory-verification-enabled: false\nlifecycle-verification-enabled: true\n'+f'lifecycle-test-idle-seconds: {idle_seconds}\n')
    if args.phone_dialog:
        with (plugins/'Hearth/config.yml').open('a') as config:config.write('phone-dialog-enabled: true\n')
    if args.combat_state or args.combat_players:
        with (plugins/'Hearth/config.yml').open('a') as config:
            config.write('combat-state-verification-enabled: true\n')
    if args.combat_players:
        with (plugins/'Hearth/config.yml').open('a') as config:
            config.write('combat-player-verification-enabled: true\n')
    if args.field_encounters:
        with (plugins/'Hearth/config.yml').open('a') as config:
            config.write('field-encounter-verification-enabled: true\n')
    if args.quest_completion or args.branch_progression or args.combat_players:
        with (plugins/'Hearth/config.yml').open('a') as config:
            config.write('quest-completion-verification-enabled: true\n')
    if args.playthrough or args.quest_interactions or args.general_npcs:
        with (plugins/'Hearth/config.yml').open('a') as config:
            config.write('playthrough-verification-enabled: true\n')
    if args.explore_batch or args.story_batch:
        with (plugins/'Hearth/config.yml').open('a') as config:
            config.write('exploration-verification-enabled: true\n')
    if args.world_effects:
        with (plugins/'Hearth/config.yml').open('a') as config:
            config.write('world-effect-verification-enabled: true\n')
    if args.story_batch:
        with (plugins/'Hearth/config.yml').open('a') as config:
            config.write('story-verification-enabled: true\n')
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
        if args.quest_batch or args.explore_batch or args.story_batch:
            legacy=root/'legacy-migrations'; legacy.mkdir()
            with zipfile.ZipFile(args.plugin) as jar:
                for file in (['V001__progress.sql','V002__story_inventory.sql','V003__personal_quest.sql']+(['V004__quest_steps_and_journal.sql'] if args.explore_batch or args.story_batch else [])+(['V005__exploration_diary_and_markers.sql'] if args.story_batch else [])):
                    (legacy/file).write_bytes(jar.read('db/migration/'+file))
            monitor.db('story-legacy' if args.story_batch else 'explore-legacy' if args.explore_batch else 'quest-legacy')
        paper=[str(args.java.resolve()),'-Xms256m','-Xmx1536m','-XX:ActiveProcessorCount=2',
               '-Dhearth.verification.root='+str(runtime),'-Dhearth.verification.lifecycle=true',
               '-jar',str(root/'paper-26.2-134.jar'),'--nogui']
        if args.sessions:
            paper.insert(-3,'-Dhearth.verification.sessions=true')
            paper.insert(-3,'-Dhearth.verification.players=true')
        if args.world_effects:
            paper.insert(-3,'-Dhearth.verification.worldEffect=true')
        if args.story_batch:
            paper.insert(-3,'-Dhearth.verification.sharedStory=true')
        if args.explore_batch or args.story_batch:
            paper.insert(-3,'-Dhearth.verification.exploration=true')
        if args.quests:
            paper.insert(-3,'-Dhearth.verification.quests=true')
        if args.manual_player:
            paper.insert(-3,'-Dhearth.verification.sessionManual=true')
        if args.playthrough or args.quest_interactions or args.general_npcs:
            paper.insert(-3,'-Dhearth.verification.playthrough=true')
        if args.quest_completion or args.branch_progression or args.combat_players:
            paper.insert(-3,'-Dhearth.verification.questCompletion=true')
        if args.field_encounters:
            paper.insert(-3,'-Dhearth.verification.field=true')
        if args.combat_state or args.combat_players:
            paper.insert(-3,'-Dhearth.verification.combatState=true')
        if args.combat_players:
            paper.insert(-3,'-Dhearth.verification.combatPlayers=true')
        for phase in (['prepare'] if args.phone_dialog or args.combat_players or args.combat_state or args.field_encounters or args.npc_pages or args.quest_locations or args.quest_navigation or args.game_information or args.npc_views or args.player_interactions or args.gameplay_guide or args.quest_info or args.quest_auto or args.quest_npc or args.quest_start or args.quest_requires else ['prepare','resume']):
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
                        if args.combat_players:
                            monitor.action(name,'combat-player-state')
                        elif args.combat_state:
                            monitor.action(name,'combat-state')
                        elif args.field_encounters:
                            monitor.action(name,'field-encounters')
                        elif args.branch_progression:
                            monitor.action(name,'branch-progression-start')
                        elif args.quest_completion:
                            monitor.action(name,'quest-completion-start')
                        elif args.general_npcs:
                            monitor.action(name,'general-npcs-start')
                        elif args.npc_topics:
                            monitor.action(name,'npc-dialogue-book' if args.npc_pages else 'npc-topics-start')
                        elif args.quest_interactions:
                            monitor.action(name,'interaction-quest-start')
                        elif args.npc_pages:
                            monitor.action(name,'npc-pages')
                        elif args.quest_locations:
                            monitor.action(name,'location-play')
                        elif args.playthrough:
                            monitor.action(name,'playthrough-run')
                        elif args.quest_navigation:
                            monitor.action(name,'navigation-play')
                        elif args.player_interactions:
                            monitor.action(name,'interaction-play')
                        elif args.world_effects:
                            monitor.action(name,'effect-prepare')
                            monitor.db('story-fail-on'); monitor.action(name,'effect-db-failure'); monitor.db('story-fail-off')
                            monitor.action(name,'effect-db-recover')
                            monitor.db('lock-story'); monitor.action(name,'effect-pending'); monitor.db('unlock')
                            monitor.action(name,'effect-applied'); monitor.action(name,'effect-cycle')
                        elif args.story_batch:
                            monitor.action(name,'story-prepare');
                            if args.gameplay_guide: monitor.action(name,'gameplay-guide')
                            if args.npc_views: monitor.action(name,'npc-views')
                            if args.game_information: monitor.action(name,'game-information')
                            monitor.action(name,'story-vote')
                            monitor.db('lock-story'); monitor.action(name,'story-persist-start'); monitor.db('unlock')
                            monitor.action(name,'story-persist-done'); monitor.action(name,'story-cycle')
                            if args.gameplay_guide: monitor.action(name,'gameplay-guide')
                            if args.npc_views: monitor.action(name,'npc-views')
                            if args.game_information: monitor.action(name,'game-information')
                        elif args.explore_batch:
                            monitor.action(name,'explore-prepare')
                            monitor.db('lock-quest'); monitor.action(name,'explore-pending'); monitor.db('unlock')
                            monitor.action(name,'explore-discover'); monitor.action(name,'explore-ui'); monitor.action(name,'explore-cycle')
                        elif args.quest_batch:
                            monitor.action(name,'batch-prepare'); monitor.action(name,'batch-ui'); monitor.action(name,'batch-cycle')
                        elif args.quest_requires:
                            monitor.action(name,'quest-prereq-prepare'); monitor.action(name,'quest-prereq-complete')
                            monitor.db('lock-quest'); monitor.action(name,'quest-prereq-start'); monitor.db('unlock')
                            monitor.action(name,'quest-prereq-start-done'); monitor.action(name,'quest-prereq-error-ready')
                            monitor.db('table-hide'); monitor.action(name,'quest-prereq-error'); monitor.db('table-show')
                            monitor.action(name,'quest-prereq-error-done'); monitor.action(name,'quest-prereq-stale-ready')
                            monitor.db('lock-quest'); monitor.action(name,'quest-prereq-stale'); monitor.db('unlock')
                            monitor.action(name,'quest-prereq-stale-done'); monitor.action(name,'quest-prereq-check')
                            monitor.db('lock-quest'); monitor.action(name,'quest-list-pending'); monitor.db('unlock')
                            monitor.action(name,'quest-prereq-menu-done')
                        elif args.quest_start:
                            monitor.action(name,'quest-entry-prepare')
                            monitor.db('lock-quest'); monitor.action(name,'quest-entry-start'); monitor.db('unlock')
                            monitor.action(name,'quest-entry-start-done')
                            monitor.db('quest-fail-on'); monitor.action(name,'quest-entry-failure'); monitor.db('quest-fail-off')
                            monitor.action(name,'quest-entry-stale-ready')
                            monitor.db('lock-quest'); monitor.action(name,'quest-entry-stale'); monitor.db('unlock')
                            monitor.action(name,'quest-entry-stale-done'); monitor.action(name,'quest-entry-check')
                        elif args.quest_auto or args.quest_npc:
                            monitor.action(name,'quest-auto-prepare')
                            if args.quest_npc: monitor.action(name,'quest-npc-check')
                            monitor.db('lock-quest'); monitor.action(name,'quest-auto-save-start'); monitor.db('unlock')
                            monitor.action(name,'quest-auto-save-done')
                            monitor.db('quest-fail-on'); monitor.action(name,'quest-auto-failure'); monitor.db('quest-fail-off')
                            monitor.action(name,'quest-auto-stale-ready')
                            monitor.db('lock-quest'); monitor.action(name,'quest-auto-stale-start'); monitor.db('unlock')
                            monitor.action(name,'quest-auto-stale-done')
                            if args.quest_npc:
                                monitor.action(name,'quest-npc-check')
                                monitor.db('lock-quest'); monitor.action(name,'quest-npc-pending'); monitor.db('unlock')
                                monitor.action(name,'quest-npc-pending-done'); monitor.action(name,'quest-npc-cycle')
                        elif args.quest_info:
                            monitor.action(name,'quest-info-prepare')
                            monitor.db('lock-quest'); monitor.action(name,'quest-list-pending'); monitor.db('unlock')
                            monitor.action(name,'quest-info-pending-done')
                            monitor.db('table-hide'); monitor.action(name,'quest-info-error'); monitor.db('table-show')
                            monitor.action(name,'quest-info-check')
                        elif args.quest_list:
                            monitor.action(name,'quest-list-prepare')
                            monitor.db('lock-quest'); monitor.action(name,'quest-list-pending'); monitor.db('unlock')
                            monitor.action(name,'quest-list-pending-done')
                        elif args.quests:
                            monitor.action(name,'quest-first'); monitor.action(name,'quest-multi-ready')
                            monitor.db('lock-quest'); monitor.action(name,'quest-multi-inflight'); monitor.db('unlock')
                            monitor.action(name,'quest-multi-done'); monitor.action(name,'quest-cycle')
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
                    elif args.branch_progression:
                        monitor.action(name,'session-resume'); monitor.action(name,'branch-progression-restored')
                    elif args.quest_completion:
                        monitor.action(name,'session-resume'); monitor.action(name,'quest-completion-restored')
                    elif args.general_npcs:
                        monitor.action(name,'session-resume'); monitor.action(name,'general-npcs-restored')
                    elif args.npc_topics:
                        monitor.action(name,'session-resume'); monitor.action(name,'npc-topics-restored')
                    elif args.quest_interactions:
                        monitor.action(name,'session-resume'); monitor.action(name,'interaction-quest-restored')
                    elif args.playthrough:
                        monitor.action(name,'session-resume'); monitor.action(name,'playthrough-restored')
                    elif args.world_effects:
                        monitor.action(name,'session-resume'); monitor.action(name,'effect-resume'); monitor.action(name,'effect-conflict')
                    elif args.story_batch:
                        monitor.action(name,'session-resume'); monitor.action(name,'story-resume'); monitor.action(name,'story-replay')
                        monitor.action(name,'story-unknown')
                        monitor.db('story-fail-on'); monitor.action(name,'story-failure'); monitor.db('story-fail-off')
                    elif args.explore_batch:
                        monitor.action(name,'session-resume'); monitor.action(name,'explore-resume'); monitor.action(name,'explore-complete')
                        monitor.db('diary-event-fail-on'); monitor.action(name,'explore-failure'); monitor.db('diary-event-fail-off')
                        monitor.db('lock-quest'); monitor.action(name,'batch-ui-pending'); monitor.db('unlock')
                        monitor.action(name,'batch-ui-pending-done')
                    elif args.quest_batch:
                        monitor.action(name,'session-resume'); monitor.action(name,'batch-resume'); monitor.action(name,'batch-complete')
                        monitor.db('notice-fail-on'); monitor.action(name,'batch-notice-failure'); monitor.db('notice-fail-off')
                        monitor.db('preferences-fail-on'); monitor.action(name,'batch-preferences-failure'); monitor.db('preferences-fail-off')
                        monitor.db('lock-quest'); monitor.action(name,'batch-ui-pending'); monitor.db('unlock')
                        monitor.action(name,'batch-ui-pending-done')
                    elif args.quest_list:
                        monitor.action(name,'session-resume'); monitor.action(name,'quest-list-restored')
                        monitor.db('table-hide'); monitor.action(name,'quest-list-error'); monitor.db('table-show')
                        monitor.action(name,'quest-list-check')
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
                    paper_starts_max=1 if args.npc_pages or args.phone_dialog or args.gameplay_guide or args.quest_info or args.quest_auto or args.quest_npc or args.quest_start or args.quest_requires else 2,paper_heap_mib=1536,paper_rss_stop_mib=2560,host_available_min_mib=6144,nice=10,active_processors=2),
                    processes=monitor.summary(),all_owned_processes_and_ports_closed=not cleanup,
                    plugin_sha256=hashlib.sha256(args.plugin.read_bytes()).hexdigest(),
                    session_checks=[line.split('HEARTH_SESSION_CHECK PASS ',1)[1] for item in monitor.children.values() for line in item['lines'] if 'HEARTH_SESSION_CHECK PASS ' in line],
                    combat_players_mode=args.combat_players, combat_state_mode=args.combat_state, field_encounters_mode=args.field_encounters, branch_progression_mode=args.branch_progression, quest_completion_mode=args.quest_completion, general_npcs_mode=args.general_npcs, npc_topics_mode=args.npc_topics, world_effects_mode=args.world_effects, story_batch_mode=args.story_batch, explore_batch_mode=args.explore_batch, sessions_mode=args.sessions, quests_mode=args.quests, quest_list_mode=args.quest_list, quest_info_mode=args.quest_info, quest_auto_mode=args.quest_auto, quest_npc_mode=args.quest_npc, quest_start_mode=args.quest_start, quest_requires_mode=args.quest_requires, quest_batch_mode=args.quest_batch, manual_player_mode=args.manual_player,
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
