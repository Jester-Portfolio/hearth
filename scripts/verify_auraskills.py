#!/usr/bin/env python3
"""소유 격리 Paper/PG 복사본에서 AuraSkills API·YAML·동시 구동 검증. Player 합성 없음."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import time
import isolated_paths
from verify_network import copy_tree, digest, port
from verify_native_economy import NativeMonitor, port_closed

AURA_SHA = 'de54cbd2e33d65e8b1704751ae4121ed2f5b466ba89c63a1aadf3a3e629d6a40'
HUD_SHA = 'dc837b15d6cc867ea6fec3fe05ef2390a6e0928c59788c797f2631f139cc818d'


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('workspace', 'source', 'java', 'plugin', 'probe', 'aura', 'aura-config'):
        p.add_argument('--' + name, type=Path, required=True)
    p.add_argument('--restore', action='store_true')
    p.add_argument('--travel-final', action='store_true', help='최종 TPA/기본 off 경로 두 JVM; 기존 PG 행렬 재실행 없음')
    p.add_argument('--travel', action='store_true', help='전투 저장·복원 두 JVM 및 PG 경합/실패 통합')
    p.add_argument('--admission', action='store_true', help='정식 접근 제어 off/on 및 PG 권한 통합')
    p.add_argument('--aura-only', action='store_true', help='기존 baseline 성공 후 Aura 영향 검사만 실행')
    p.add_argument('--betterhud', type=Path)
    p.add_argument('--accept-eula', action='store_true')
    a = p.parse_args()
    if a.travel_final:
        a.travel = True
    for artifact in (a.java, a.plugin, a.probe, a.aura, a.aura_config):
        if not artifact.is_file():
            p.error('artifact is not a regular file: ' + str(artifact))
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
        result = json.loads((source / ('aura-trial-result.json' if a.restore else 'native-economy-result.json')).read_text())
        if result['outcome'] != 'PASS' or result['cleanup_errors'] or (source / 'data/postmaster.pid').exists() or list((source / 'server/plugins/Hearth').glob('*.active')):
            raise ValueError('INACTIVE_PASSED_SOURCE_REQUIRED')
        if digest(a.aura) != AURA_SHA or (a.betterhud and digest(a.betterhud) != HUD_SHA):
            raise ValueError('OFFICIAL_ARTIFACT_HASH')
        for rel in ('data/global/pg_control', 'server/plugins/Hearth/config.yml', 'server/plugins/Hearth/survival-worlds.properties'):
            before[str(source / rel)] = digest(source / rel)
        for name in ('server', 'data', 'fixture-classes', 'resolved-jars'):
            if (a.admission or a.travel) and name in ('fixture-classes', 'resolved-jars'):
                if not (root / name).is_dir():
                    raise ValueError('STAGE_CURRENT_NATIVE_FIXTURE_FIRST')
            else:
                copy_tree(source / name, root / name, m.guard)
        shutil.copy2(source / 'paper.jar', root / 'paper.jar')
        (root / '.hearth-aura-isolated').write_text('Hearth AuraSkills isolated v1\n')
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
        helper = [str(a.java), '-Xmx128m', '-XX:ActiveProcessorCount=2', '-Djava.io.tmpdir=' + str(root), '-cp', str(root / 'fixture-classes') + ':' + str(root / 'resolved-jars/*') + ':' + str(a.plugin), 'io.github.jesterportfolio.hearth.persistence.NativeEconomyDatabaseFixture', str(root), 'travel' if a.travel and not a.travel_final else 'restore' if a.travel_final else 'skills' if a.admission else 'restore']
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
        phases = ['travel-off', 'travel-prepare'] if a.travel_final else ['travel-prepare', 'travel-resume'] if a.travel else ['admission-off', 'admission-on'] if a.admission else ['restore'] if a.restore else ['aura'] if a.aura_only else ['baseline', 'aura']
        for phase in phases:
            if a.travel_final and phase == 'travel-prepare':
                config.write_text(config.read_text().replace('combat-travel:\n  enabled: false', 'combat-travel:\n  enabled: true'))
            if not a.travel and phase not in ('baseline', 'admission-off'):
                shutil.copy2(a.aura, plugins / 'AuraSkills.jar')
                folder = plugins / 'AuraSkills'
                folder.mkdir(exist_ok=True)
                if not a.restore:
                    text = a.aura_config.read_text().replace('check_for_updates: true', 'check_for_updates: false').replace('action_bar:\n  enabled: true', 'action_bar:\n  enabled: false').replace('boss_bar:\n  enabled: true', 'boss_bar:\n  enabled: false')
                    text = text.replace('\nblocked_worlds:\n  - Example', '\nblocked_worlds:\n  - hearth_policy_blocked').replace('\ndisabled_worlds:\n  - Example', '\ndisabled_worlds:\n  - hearth_policy_disabled')
                    (folder / 'config.yml').write_text(text)
                if a.admission:
                    config.write_text(config.read_text().replace('skills:\n  enabled: false', 'skills:\n  enabled: true'))
                    policy='format=1\npublic_home.rules=none\nprivate_home.rules=none\nnovice_resource.rules=none\nresource.rules=mining,craft\nnether.rules=none\nend.rules=none\nrule.mining.kind=specific\nrule.mining.skills=auraskills/mining\nrule.mining.minimum=20\nrule.craft.kind=specific\nrule.craft.skills=hearthprobe/metallurgy\nrule.craft.minimum=3\n'
                    (plugins / 'Hearth/skills-access.properties').write_text(policy)
                if a.betterhud:
                    shutil.copy2(a.betterhud, plugins / 'BetterHud.jar')
            name = 'paper-' + phase
            m.launch(name, command, root / 'server')
            m.wait(name, 'HEARTH_SURVIVAL_ECONOMY_READY', seconds=120)
            m.wait(name, 'HEARTH_SURVIVAL_READY worlds=3', seconds=20)
            m.wait(name, 'HEARTH_AURA_PROBE_READY', seconds=15)
            expected = 'HEARTH_ACCESS_PROBE PASS' if phase == 'travel-off' else 'HEARTH_COMBAT_PROBE PASS ' + phase.removeprefix('travel-') if a.travel else 'HEARTH_ACCESS_PROBE PASS' if a.admission else 'HEARTH_AURA_PROBE PASS ' + phase
            m.send(name, 'hearthsurvivaladmin access-verify' if phase == 'travel-off' else 'hearthsurvivaladmin travel-verify ' + phase.removeprefix('travel-') if a.travel else 'hearthsurvivaladmin access-verify' if a.admission else 'hearthauraprobe ' + phase)
            until = min(m.deadline, time.monotonic() + 90)
            while not any(expected in line for line in m.children[name]['lines']):
                m.pump()
                if any('HEARTH_COMBAT_PROBE FAIL' in line or 'HEARTH_COMBAT_TRAVEL_HOLD' in line or 'HEARTH_AURA_PROBE FAIL' in line or 'HEARTH_ACCESS_PROBE FAIL' in line for line in m.children[name]['lines']):
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
                        m.wait(name, 'HEARTH_SURVIVAL_READY worlds=3', seconds=20)
                        m.send(name, 'hearthsurvivaladmin drain')
                        m.wait(name, '생존 저장·종료 준비 완료', seconds=20)
                    except Exception:
                        pass
        cleanup = m.finish()
        cleanup.extend('OWNED_PORT_REMAINS_' + str(number) for number in ports if not port_closed(number))
        if cleanup:
            outcome = 'FAIL'
        report = dict(outcome=outcome, failure=failure, cleanup_errors=cleanup, restore=a.restore, admission=a.admission, travel=a.travel, travel_final=a.travel_final, duration_seconds=round(time.monotonic()-started, 3), processes=m.summary(), combined_samples=m.combined, source_hashes=before, aura_sha256=digest(a.aura), hearth_sha256=digest(a.plugin), probe_sha256=digest(a.probe) if a.probe.is_file() else None, limits=dict(cpu=2, total_seconds=600, work_seconds=550, paper_heap_mib=1536, paper_rss_stop_mib=2560, combined_rss_stop_mib=6144, host_available_min_mib=6144, paper_starts_max=2), actual_client='NOT_RUN', fake_player='NOT_USED', operating_changes='NONE')
        (root / 'aura-trial-result.json').write_text(json.dumps(report, indent=2) + '\n')
        print('AURA', outcome, root / 'aura-trial-result.json', flush=True)
    return 0 if outcome == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
