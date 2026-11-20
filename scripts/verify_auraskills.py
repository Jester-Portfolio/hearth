#!/usr/bin/env python3
"""소유 격리 Paper/PG 복사본에서 AuraSkills API·YAML·동시 구동 검증. Player 합성 없음."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import time
import zipfile
import hashlib
import isolated_paths
from verify_network import copy_tree, digest, port
from verify_native_economy import NativeMonitor, port_closed

AURA_SHA = 'de54cbd2e33d65e8b1704751ae4121ed2f5b466ba89c63a1aadf3a3e629d6a40'
HUD_SHA = 'dc837b15d6cc867ea6fec3fe05ef2390a6e0928c59788c797f2631f139cc818d'


def configure_rpg(config, data, hud):
    """Fixture-only choices; shipped defaults retain all undecided policy values."""
    config.write_text(config.read_text() + '\nskills:\n  enabled: true\n  access-file: skills-access.properties\ncombat-travel:\n  enabled: true\n  include-pve: true\n  request-seconds: 60\neconomy-rpg:\n  enabled: true\n  policy-file: rpg-policy.properties\n  content-file: rpg-content.properties\n')
    resources=Path(__file__).resolve().parent.parent / 'src/main/resources'
    shutil.copy2(resources / 'skills-access.properties', data / 'skills-access.properties')
    policy=(resources / 'rpg-policy.properties').read_text().replace('drop-unit=unset','drop-unit=item').replace('xp-scope=unset','xp-scope=both_progress').replace('xp-rounding=unset','xp-rounding=floor').replace('death.public_home.xp=unset','death.public_home.xp=none').replace('death.public_home.drop=unset','death.public_home.drop=0').replace('xp-blocked=none','xp-blocked=public_home,private_home').replace('abilities-blocked=none','abilities-blocked=public_home,private_home').replace('penalty.enabled=false','penalty.enabled=true')
    policy+='\npenalty.kind=specific\npenalty.skills=hearth/trial\npenalty.minimum=5\ncharm.scopes=items,vanilla_xp,aura_xp\n'
    (data / 'rpg-policy.properties').write_text(policy)
    content=(resources / 'rpg-content.properties').read_text().replace('custom-skills=','custom-skills=trial').replace('custom-stats=','custom-stats=trial_power').replace('gear-stats=','gear-stats=trial').replace('allow-create-extras=false','allow-create-extras=true').replace('extra.novice.terrain=NORMAL','extra.novice.terrain=FLAT').replace('hud-mode=actionbar','hud-mode='+('betterhud' if hud else 'actionbar'))
    content+='\nextra.novice.seed=42\nextra.end.seed=42\nstat.trial_power.name=Trial Power\nstat.trial_power.trait=auraskills/attack_damage\nstat.trial_power.coefficient=1\nskill.trial.name=Trial Skill\nskill.trial.passive-stat=hearth/trial_power\nskill.trial.passive-base=2\nskill.trial.passive-per-level=1\nskill.trial.active-effect=HEAL\nskill.trial.active-value=2\nskill.trial.active-seconds=1\nskill.trial.active-mana=5\nskill.trial.active-cooldown=30\ngear.trial.stat=hearth/trial_power\ngear.trial.upgrade-per-level=2\ngear.trial.enchant-key=hearth:trial_enchant\ngear.trial.enchant-per-level=1\ngear.trial.cap=100\ngear.trial.slots=main_hand\ngear.trial.materials=minecraft:diamond_sword\n'
    (data / 'rpg-content.properties').write_text(content)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('workspace', 'source', 'java', 'plugin', 'probe', 'aura', 'aura-config'):
        p.add_argument('--' + name, type=Path, required=True)
    p.add_argument('--restore', action='store_true')
    p.add_argument('--platform', action='store_true', help='장비/자동 판매/설정 이전/실제 자원 세대 교체와 두 JVM 복원')
    p.add_argument('--platform-contexts', action='store_true', help='최종/백업 복원의 active+pending 세대 두 JVM 영향 검사')
    p.add_argument('--platform-db-evidence', type=Path, help='동일 E012/DB 코드의 통과 증거 재사용; 새 CLI 검사는 실행')
    p.add_argument('--platform-final', action='store_true', help='동일 계약의 최종/백업 복원 한 JVM 검사')
    p.add_argument('--enchants-final-phase', choices=('effects','arrows'), default='effects')
    p.add_argument('--enchants-effects-final', action='store_true', help='완료한 cold 복사본에서 최종 투사체/설정 변경만 한 JVM 검사')
    p.add_argument('--enchants', action='store_true', help='자체 인챈트 105종·북 작업·E011·두 JVM 복원 1묶음')
    p.add_argument('--rpg-events-final', action='store_true', help='변경된 이벤트 등록/방문자 PvP 연결만 한 JVM 검사')
    p.add_argument('--rpg-final', action='store_true', help='완료한 RPG cold 복사본에서 영향받은 최종 코드 한 JVM 검사')
    p.add_argument('--rpg-db-evidence', type=Path, help='동일 DB 코드의 성공 증거 재사용; Paper 수정만 재검사')
    p.add_argument('--rpg', action='store_true', help='전체 경제 RPG·E010 경합/실패·5월드·새 JVM 복원 1묶음')
    p.add_argument('--travel-final', action='store_true', help='최종 TPA/기본 off 경로 두 JVM; 기존 PG 행렬 재실행 없음')
    p.add_argument('--travel', action='store_true', help='전투 저장·복원 두 JVM 및 PG 경합/실패 통합')
    p.add_argument('--admission', action='store_true', help='정식 접근 제어 off/on 및 PG 권한 통합')
    p.add_argument('--aura-only', action='store_true', help='기존 baseline 성공 후 Aura 영향 검사만 실행')
    p.add_argument('--betterhud', type=Path)
    p.add_argument('--accept-eula', action='store_true')
    a = p.parse_args()
    if a.platform_contexts:
        a.platform_final = True
    if a.platform or a.platform_final:
        a.rpg_final = True
    if a.enchants_effects_final:
        a.rpg_final = True
    if a.enchants:
        a.rpg_final = True
    if a.rpg_events_final:
        a.rpg_final = True
    if a.rpg_final:
        a.rpg = True
    if a.travel_final:
        a.travel = True
    for artifact in (a.java, a.plugin, a.probe, a.aura, a.aura_config):
        if not artifact.is_file():
            p.error('artifact is not a regular file: ' + str(artifact))
    if a.rpg and (a.travel or a.admission or a.restore or a.aura_only):
        p.error('--rpg excludes other trial modes')
    if a.travel and (a.admission or a.restore or a.aura_only):
        p.error('--travel excludes other trial modes')
    if a.admission and (a.restore or a.aura_only):
        p.error('--admission excludes --restore and --aura-only')
    if a.restore and (a.betterhud is None or a.aura_only):
        p.error('--restore requires --betterhud and excludes --aura-only')
    if a.betterhud is not None and not a.betterhud.is_file():
        p.error('BetterHud artifact is not a regular file')
    root = isolated_paths.workspace(a.workspace, 'hearth-native-economy-run.')
    source = isolated_paths.workspace(a.source, 'hearth-native-economy-run.')
    os.sched_setaffinity(0, sorted(os.sched_getaffinity(0))[:2])
    started = time.monotonic()
    m = NativeMonitor(root, started)
    ports, before, failure, cleanup = [], {}, None, []
    outcome = 'FAIL'
    try:
        if not a.accept_eula or (root / '.hearth-native-economy-isolated').read_text() != 'Hearth native economy isolated v1\n':
            raise ValueError('FIXTURE_GATE')
        result = json.loads((source / ('aura-trial-result.json' if a.restore or a.rpg_final else 'native-economy-result.json')).read_text())
        if result['outcome'] != 'PASS' or result['cleanup_errors'] or (source / 'data/postmaster.pid').exists() or list((source / 'server/plugins/Hearth').glob('*.active')):
            raise ValueError('INACTIVE_PASSED_SOURCE_REQUIRED')
        if digest(a.aura) != AURA_SHA or (a.betterhud and digest(a.betterhud) != HUD_SHA):
            raise ValueError('OFFICIAL_ARTIFACT_HASH')
        for rel in ('data/global/pg_control', 'server/plugins/Hearth/config.yml', 'server/plugins/Hearth/survival-worlds.properties'):
            before[str(source / rel)] = digest(source / rel)
        for name in ('server', 'data', 'fixture-classes', 'resolved-jars'):
            if (a.admission or a.travel or a.rpg) and name in ('fixture-classes', 'resolved-jars'):
                if not (root / name).is_dir():
                    raise ValueError('STAGE_CURRENT_NATIVE_FIXTURE_FIRST')
            else:
                copy_tree(source / name, root / name, m.guard)
        shutil.copy2(source / 'paper.jar', root / 'paper.jar')
        (root / '.hearth-aura-isolated').write_text('Hearth AuraSkills isolated v1\n')
        if a.rpg_final:
            shutil.copy2(source / 'rpg-expected.properties', root / 'rpg-expected.properties')
        if a.restore:
            shutil.copy2(source / 'aura-storage-expected.json', root / 'aura-storage-expected.json')
        plugins = root / 'server/plugins'
        shutil.copy2(a.plugin, plugins / 'Hearth.jar')
        shutil.copy2(a.probe, plugins / 'HearthAuraProbe.jar')
        (plugins / 'bStats').mkdir(exist_ok=True)
        (plugins / 'bStats/config.yml').write_text('enabled: false\n')
        config = plugins / 'Hearth/config.yml'
        realm = re.search(r'(?m)^  realm: ([a-f0-9-]{36})$', config.read_text())
        if not realm:
            raise ValueError('ISOLATED_REALM')
        (root / 'restore-realm.txt').write_text(realm.group(1) + '\n')
        os.environ['HEARTH_ISOLATED_NATIVE_PASSWORD'] = 'isolated-not-a-secret'
        rpg_inputs = {key: hashlib.sha256(zipfile.ZipFile(a.plugin).read(key)).hexdigest() for key in ('io/github/jesterportfolio/hearth/economy/RpgStore.class','db/economy/E010__rpg_policies.sql')} if a.rpg else {}
        if a.rpg:
            rpg_inputs['fixture'] = digest(root / 'fixture-classes/io/github/jesterportfolio/hearth/persistence/RpgDatabaseChecks.class')
            (root / 'rpg-db-code-sha256.json').write_text(json.dumps(rpg_inputs, sort_keys=True)+'\n')
        if a.rpg_db_evidence:
            if not a.rpg:
                raise ValueError('RPG_EVIDENCE_MODE_REQUIRED')
            evidence = isolated_paths.workspace(a.rpg_db_evidence, 'hearth-native-economy-run.')
            lines = (evidence / 'database-console.log').read_text()
            if json.loads((evidence / 'rpg-db-code-sha256.json').read_text()) != rpg_inputs or lines.count('HEARTH_RPG_DB_CHECK PASS ') != 13 or 'HEARTH_NATIVE_DB_CLOSED' not in lines or (evidence/'data/postmaster.pid').exists():
                raise ValueError('SAME_PASSED_RPG_DATABASE_CODE_REQUIRED')
        if a.platform_final:
            shutil.copy2(source/'platform-expected.properties', root/'platform-expected.properties')
        if a.platform_db_evidence:
            evidence=isolated_paths.workspace(a.platform_db_evidence,'hearth-native-economy-run.')
            prior=json.loads((evidence/'aura-trial-result.json').read_text())
            lines=(evidence/'database-console.log').read_text()
            if prior['cleanup_errors'] or lines.count('HEARTH_PLATFORM_DB_CHECK PASS ') != 15 or 'HEARTH_NATIVE_DB_CLOSED' not in lines or (evidence/'data/postmaster.pid').exists():
                raise ValueError('PASSED_PLATFORM_DATABASE_REQUIRED')
            previous=zipfile.ZipFile(evidence/'server/plugins/Hearth.jar')
            current=zipfile.ZipFile(a.plugin)
            keys=[name for name in current.namelist() if name.startswith(('io/github/jesterportfolio/hearth/economy/PlatformStore','io/github/jesterportfolio/hearth/economy/EnchantStore','io/github/jesterportfolio/hearth/economy/EconomyPolicyMigration','io/github/jesterportfolio/hearth/economy/EconomyStore','db/economy/'))]
            if any(previous.read(name)!=current.read(name) for name in keys) or digest(evidence/'fixture-classes/io/github/jesterportfolio/hearth/persistence/PlatformDatabaseChecks.class')!=digest(root/'fixture-classes/io/github/jesterportfolio/hearth/persistence/PlatformDatabaseChecks.class'):
                raise ValueError('SAME_PLATFORM_DATABASE_CODE_REQUIRED')
        helper = [str(a.java), '-Xmx128m', '-XX:ActiveProcessorCount=2', '-Djava.io.tmpdir=' + str(root), '-cp', str(root / 'fixture-classes') + ':' + str(root / 'resolved-jars/*') + ':' + str(a.plugin), 'io.github.jesterportfolio.hearth.persistence.NativeEconomyDatabaseFixture', str(root), 'restore' if a.platform_final or a.enchants_effects_final else 'platform-cli' if a.platform_db_evidence else 'platform' if a.platform else 'enchants' if a.enchants else 'rpg' if a.rpg and not a.rpg_db_evidence else 'restore' if a.rpg else 'travel' if a.travel and not a.travel_final else 'restore' if a.travel_final else 'skills' if a.admission else 'restore']
        m.launch('database', helper, root)
        m.wait('database', 'HEARTH_NATIVE_DB_READY', seconds=75)
        dbport = int((root / 'db-port.txt').read_text())
        gameport = port()
        ports.extend((dbport, gameport))
        config.write_text(re.sub(r'(?m)^  db-port: [0-9]+$', '  db-port: ' + str(dbport), config.read_text()))
        with (root / 'server/server.properties').open('a') as f:
            f.write(f'\nserver-ip=127.0.0.1\nserver-port={gameport}\nonline-mode=true\nwhite-list=true\nenforce-whitelist=true\nenable-query=false\nenable-rcon=false\n')
        command = [str(a.java), '-Xms256m', '-Xmx1536m', '-XX:ActiveProcessorCount=2', '-Djava.io.tmpdir=' + str(root), '-Dhearth.aura.verification.root=' + str(root), '-jar', str(root / 'paper.jar'), '--nogui']
        if a.admission:
            config.write_text(config.read_text() + '\nskills:\n  enabled: false\n  access-file: skills-access.properties\n')
        if a.travel:
            config.write_text(config.read_text() + '\ncombat-travel:\n  enabled: '+('false' if a.travel_final else 'true')+'\n  include-pve: true\n  request-seconds: 60\n')
        if a.rpg_final:
            policy=plugins / 'Hearth/rpg-policy.properties'
            policy.write_text(policy.read_text() + '\nitem-rate.minecraft\\:diamond_sword=0.07\nitem-rate.pdc/hearth\\:trial_enchant=0.25\nitem-rate.pdc/hearth\\:other_rate=0.5\n')
        if a.rpg and not a.rpg_final:
            configure_rpg(config, plugins / 'Hearth', a.betterhud is not None)
        if a.enchants:
            config.write_text(config.read_text()+'\nhearth-enchants:\n  enabled: true\n')
            enchants=plugins / 'Hearth/enchants'
            enchants.mkdir(exist_ok=True)
            (enchants / 'operations.yml').write_text((Path(__file__).resolve().parent.parent / 'src/main/resources/enchants/operations.yml').read_text().replace('enabled: false','enabled: true',1))
        if a.enchants_effects_final:
            shutil.copy2(Path(__file__).resolve().parent.parent / 'src/main/resources/enchants/ce_sticky_shot.yml', plugins / 'Hearth/enchants/ce_sticky_shot.yml')
        if a.platform:
            config.write_text(config.read_text()+'\nhearth-platform:\n  enabled: true\n')
            properties=(Path(__file__).resolve().parent.parent / 'src/main/resources/platform.properties').read_text().replace('upgrade.enabled=false','upgrade.enabled=true').replace('resource-reset.enabled=false','resource-reset.enabled=true')
            content_file=re.search(r'content-file: ([^\s]+)',config.read_text().split('survival-economy:')[1]).group(1)
            good=re.search(r'^goods=([^,\n]+)',(plugins/'Hearth'/content_file).read_text(),re.M).group(1)
            properties=properties.replace('autosell.goods=','autosell.goods='+good)
            (plugins / 'Hearth/platform.properties').write_text(properties)
        phases = ['platform-context-prepare','platform-context-resume'] if a.platform_contexts else ['platform-resume'] if a.platform_final else ['platform-prepare','platform-resume'] if a.platform else ['enchants-'+a.enchants_final_phase] if a.enchants_effects_final else ['enchants-prepare','enchants-resume'] if a.enchants else ['rpg-events'] if a.rpg_events_final else ['rpg-resume'] if a.rpg_final else ['rpg-prepare', 'rpg-resume'] if a.rpg else ['travel-off', 'travel-prepare'] if a.travel_final else ['travel-prepare', 'travel-resume'] if a.travel else ['admission-off', 'admission-on'] if a.admission else ['restore'] if a.restore else ['aura'] if a.aura_only else ['baseline', 'aura']
        for phase in phases:
            if a.rpg and phase == 'rpg-resume':
                content=plugins / 'Hearth/rpg-content.properties'
                content.write_text(content.read_text().replace('allow-create-extras=true','allow-create-extras=false'))
            if a.travel_final and phase == 'travel-prepare':
                config.write_text(config.read_text().replace('combat-travel:\n  enabled: false', 'combat-travel:\n  enabled: true'))
            if not a.travel and phase not in ('baseline', 'admission-off'):
                shutil.copy2(a.aura, plugins / 'AuraSkills.jar')
                folder = plugins / 'AuraSkills'
                folder.mkdir(exist_ok=True)
                if not a.restore and not a.rpg_final and (not a.rpg or phase == 'rpg-prepare'):
                    text = a.aura_config.read_text().replace('check_for_updates: true', 'check_for_updates: false').replace('action_bar:\n  enabled: true', 'action_bar:\n  enabled: false').replace('boss_bar:\n  enabled: true', 'boss_bar:\n  enabled: false')
                    text = text.replace('\nblocked_worlds:\n  - Example', '\nblocked_worlds:\n  - hearth_policy_blocked').replace('\ndisabled_worlds:\n  - Example', '\ndisabled_worlds:\n  - hearth_policy_disabled')
                    (folder / 'config.yml').write_text(text)
                if a.admission:
                    config.write_text(config.read_text().replace('skills:\n  enabled: false', 'skills:\n  enabled: true'))
                    policy='format=1\npublic_home.rules=none\nprivate_home.rules=none\nnovice_resource.rules=none\nresource.rules=mining,craft\nnether.rules=none\nend.rules=none\nrule.mining.kind=specific\nrule.mining.skills=auraskills/mining\nrule.mining.minimum=20\nrule.craft.kind=specific\nrule.craft.skills=hearthprobe/metallurgy\nrule.craft.minimum=3\n'
                    (plugins / 'Hearth/skills-access.properties').write_text(policy)
                if a.betterhud:
                    shutil.copy2(a.betterhud, plugins / 'BetterHud.jar')
                    if a.rpg and phase == 'rpg-prepare':
                        hud=plugins / 'BetterHud'
                        hud.mkdir(exist_ok=True)
                        text=zipfile.ZipFile(a.betterhud).read('config.yml').decode().replace('merge-boss-bar: true','merge-boss-bar: false').replace('metrics: true','metrics: false').replace('version-check: true','version-check: false')
                        (hud / 'config.yml').write_text(text)
            name = 'paper-' + phase
            m.launch(name, command, root / 'server')
            m.wait(name, 'HEARTH_SURVIVAL_ECONOMY_READY', seconds=120)
            m.wait(name, 'HEARTH_SURVIVAL_READY worlds='+('5' if a.rpg else '3'), seconds=20)
            m.wait(name, 'HEARTH_AURA_PROBE_READY', seconds=15)
            expected = 'HEARTH_PLATFORM_PROBE PASS '+phase.removeprefix('platform-') if a.platform or a.platform_final else 'HEARTH_ENCHANT_PROBE PASS '+a.enchants_final_phase if a.enchants_effects_final else 'HEARTH_ENCHANT_PROBE PASS ' + phase.removeprefix('enchants-') if a.enchants else 'HEARTH_RPG_PROBE PASS ' + phase.removeprefix('rpg-') if a.rpg else 'HEARTH_ACCESS_PROBE PASS' if phase == 'travel-off' else 'HEARTH_COMBAT_PROBE PASS ' + phase.removeprefix('travel-') if a.travel else 'HEARTH_ACCESS_PROBE PASS' if a.admission else 'HEARTH_AURA_PROBE PASS ' + phase
            m.send(name, 'hearthplatform verify '+phase.removeprefix('platform-') if a.platform or a.platform_final else 'hearthenchants verify '+a.enchants_final_phase if a.enchants_effects_final else 'hearthenchants verify '+phase.removeprefix('enchants-') if a.enchants else 'hearthsurvivaladmin rpg-verify ' + phase.removeprefix('rpg-') if a.rpg else 'hearthsurvivaladmin access-verify' if phase == 'travel-off' else 'hearthsurvivaladmin travel-verify ' + phase.removeprefix('travel-') if a.travel else 'hearthsurvivaladmin access-verify' if a.admission else 'hearthauraprobe ' + phase)
            until = min(m.deadline, time.monotonic() + 90)
            while not any(expected in line for line in m.children[name]['lines']):
                m.pump()
                if any('HEARTH_PLATFORM_PROBE FAIL' in line or 'HEARTH_ENCHANT_PROBE FAIL' in line or 'HEARTH_RPG_PROBE FAIL' in line or 'HEARTH_RPG_HOLD' in line or 'HEARTH_COMBAT_PROBE FAIL' in line or 'HEARTH_COMBAT_TRAVEL_HOLD' in line or 'HEARTH_AURA_PROBE FAIL' in line or 'HEARTH_ACCESS_PROBE FAIL' in line for line in m.children[name]['lines']):
                    raise RuntimeError('AURA_PROBE_FAILED_' + phase)
                if m.children[name]['proc'].poll() is not None or time.monotonic() > until:
                    raise RuntimeError('AURA_PROBE_TIMEOUT_' + phase)
            m.send(name, 'hearthsurvivaladmin drain')
            m.wait(name, '생존 저장·종료 준비 완료', seconds=25)
            m.stop(name, 'stop')
            print('AURA_PHASE PASS', phase, flush=True)
        m.send('database', 'compare')
        m.wait('database', 'HEARTH_NATIVE_DB_COMPARE_PASS', seconds=15)
        m.stop('database', 'finish')
        if not all(digest(Path(path)) == sha for path, sha in before.items()):
            raise RuntimeError('SOURCE_CHANGED')
        if digest(plugins / 'Hearth.jar') != digest(a.plugin):
            raise RuntimeError('HEARTH_ARTIFACT_CHANGED')
        outcome = 'PASS'
    except Exception as error:
        failure = type(error).__name__ + ': ' + str(error)
        print('AURA FAIL', failure, flush=True)
    finally:
        # 기능 검사 실패 시에도 준비된 소유 서버는 저장 drain 후 종료합니다.
        if failure:
            for name, item in m.children.items():
                if name.startswith('paper') and item['proc'].poll() is None:
                    try:
                        m.wait(name, 'HEARTH_SURVIVAL_READY worlds='+('5' if a.rpg else '3'), seconds=20)
                        m.send(name, 'hearthsurvivaladmin drain')
                        m.wait(name, '생존 저장·종료 준비 완료', seconds=20)
                    except Exception:
                        pass
        cleanup = m.finish()
        cleanup.extend('OWNED_PORT_REMAINS_' + str(number) for number in ports if not port_closed(number))
        if cleanup:
            outcome = 'FAIL'
        report = dict(outcome=outcome, failure=failure, cleanup_errors=cleanup, restore=a.restore, platform=a.platform, platform_final=a.platform_final, platform_contexts=a.platform_contexts, platform_db_evidence=str(a.platform_db_evidence) if a.platform_db_evidence else None, enchants=a.enchants, enchants_effects_final=a.enchants_effects_final, rpg=a.rpg, rpg_final=a.rpg_final, rpg_events_final=a.rpg_events_final, rpg_db_evidence=str(a.rpg_db_evidence) if a.rpg_db_evidence else None, admission=a.admission, travel=a.travel, travel_final=a.travel_final, duration_seconds=round(time.monotonic()-started, 3), processes=m.summary(), combined_samples=m.combined, source_hashes=before, aura_sha256=digest(a.aura), hearth_sha256=digest(a.plugin), probe_sha256=digest(a.probe) if a.probe.is_file() else None, limits=dict(cpu=2, total_seconds=600, work_seconds=550, paper_heap_mib=1536, paper_rss_stop_mib=2560, combined_rss_stop_mib=6144, host_available_min_mib=6144, paper_starts_max=2), actual_client='NOT_RUN', fake_player='NOT_USED', operating_changes='NONE')
        (root / 'aura-trial-result.json').write_text(json.dumps(report, indent=2) + '\n')
        print('AURA', outcome, root / 'aura-trial-result.json', flush=True)
    return 0 if outcome == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
