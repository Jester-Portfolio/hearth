#!/usr/bin/env python3
"""Fresh isolated S4-2 fixture; sequential JVMs, no client impersonation or operational changes."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import selectors
import shutil
import signal
import socket
import subprocess
import sys
import time
import zipfile


def available_kib():
    for line in Path('/proc/meminfo').read_text().splitlines():
        if line.startswith('MemAvailable:'):
            return int(line.split()[1])
    raise RuntimeError('Cannot measure available memory')


def port_free():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 25585))


def permitted_manual_command(command):
    name = r'[A-Za-z0-9_]{3,16}'
    simple = [r'stop', r'save-all flush', rf'(?:whitelist (?:add|remove)|op|deop) {name}',
              rf'hearthverify player-status {name}', rf'gamemode creative {name}',
              rf'give {name} minecraft:(?:cobblestone|diamond_pickaxe) (?:[1-9]|[1-5][0-9]|6[0-4])']
    if any(re.fullmatch(pattern, command) for pattern in simple):
        return True
    # Console fixture edits stay in A/B; no arbitrary execute chains or commands affecting other worlds.
    coordinate = r'-?\d+(?:\.\d+)?'
    xyz = rf'{coordinate} {coordinate} {coordinate}'
    block = r'minecraft:(?:stone|air|cobblestone)'
    actions = [rf'tp {name} {xyz}', rf'fill {xyz} {xyz} {block}', rf'setblock {xyz} {block}',
               rf'if block {xyz} {block} run say [A-Za-z0-9_ -]{{1,80}}']
    if not any(re.fullmatch(r'execute in hearth_verify:restart_[ab] run '+action, command) for action in actions):
        return False
    words = command.split(' run ', 1)[1].split()
    values = words[1:7] if words[0] == 'fill' else (words[2:5] if words[0] in ('tp', 'if') else words[1:4])
    for i in range(0, len(values), 3):
        x, y, z = map(float, values[i:i+3])
        if not (-2 <= x <= 17 and 63 <= y <= 70 and -2 <= z <= 17):
            return False
        if words[0] != 'tp' and any(not n.is_integer() for n in (x,y,z)):
            return False
    return True


def run_jvm(base, java, phase, deadline, manual=False):
    if time.monotonic() >= deadline:
        raise RuntimeError('Combined test deadline reached; refusing another JVM')
    port_free()
    if available_kib() < 6 * 1024**2:
        raise RuntimeError('Need 6 GiB available memory before each JVM')
    runtime = base / 'server'
    command = [str(java.resolve(strict=True)), '-Xms256m', '-Xmx1536m', '-XX:ActiveProcessorCount=2',
               '-Dhearth.verification.root=' + str(runtime)]
    if manual:
        command.append('-Dhearth.verification.players=true')
    command += ['-jar', str(base / 'paper-26.2-134.jar'), '--nogui']
    started = time.monotonic()
    child = subprocess.Popen(command, cwd=runtime, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                             stderr=subprocess.STDOUT, start_new_session=True, preexec_fn=lambda: os.nice(10))
    poller = selectors.DefaultSelector()
    poller.register(child.stdout, selectors.EVENT_READ)
    if manual:
        poller.register(sys.stdin, selectors.EVENT_READ)
    temporary_ops = set()
    manual_ready = False
    samples = []
    pending = b''
    outcome = 'NOT_COMPLETED'
    stop_at = startup = None
    sent_phase = False
    timings, checks, events, snapshots = [], [], [], []
    try:
        with (base / (phase + '-console.log')).open('xb') as log:
            while child.poll() is None:
                now = time.monotonic()
                try:
                    stats = dict(line.split(':', 1) for line in Path(f'/proc/{child.pid}/status').read_text().splitlines() if ':' in line)
                    cpu_fields = Path(f'/proc/{child.pid}/stat').read_text().split()
                    rss = int(stats.get('VmRSS', '0 kB').split()[0])
                    samples.append(dict(seconds=round(now-started, 3), rss_kib=rss,
                                        available_kib=available_kib(), cpu_ticks=int(cpu_fields[13])+int(cpu_fields[14])))
                    if rss > 2560 * 1024 or samples[-1]['available_kib'] < 6 * 1024**2:
                        outcome = 'RESOURCE_LIMIT'
                except FileNotFoundError:
                    if child.poll() is None:
                        raise
                if now > deadline and outcome == 'NOT_COMPLETED':
                    outcome = 'TIME_LIMIT'
                if outcome != 'NOT_COMPLETED' and stop_at is None:
                    if child.poll() is None:
                        for name in sorted(temporary_ops):
                            child.stdin.write(('deop '+name+'\n').encode())
                        child.stdin.write(b'stop\n'); child.stdin.flush()
                    stop_at = now
                if stop_at is not None and now-stop_at > 30:
                    os.killpg(child.pid, signal.SIGTERM)
                    break
                for key, _ in poller.select(timeout=1):
                    if manual and key.fileobj is sys.stdin:
                        entered = sys.stdin.readline()
                        if stop_at is not None or outcome != 'NOT_COMPLETED':
                            continue  # Never forward permissions or fixture edits after shutdown begins.
                        if not entered:
                            outcome = 'MANUAL_ABORTED'
                            poller.unregister(sys.stdin)
                            continue
                        text = entered.strip()
                        if not permitted_manual_command(text):
                            print('Rejected: use only the fixture commands documented for S4-3', flush=True)
                            continue
                        if text == 'stop':
                            outcome = 'MANUAL_CLOSED'
                            continue
                        if not manual_ready:
                            print('Wait for HEARTH_S4_3_READY before fixture commands', flush=True)
                            continue
                        if text.startswith('op '):
                            temporary_ops.add(text.split()[1])
                        elif text.startswith('deop '):
                            temporary_ops.discard(text.split()[1])
                        child.stdin.write((text+'\n').encode()); child.stdin.flush()
                        continue
                    chunk = os.read(key.fileobj.fileno(), 65536)
                    if not chunk:
                        poller.unregister(key.fileobj)
                        continue
                    log.write(chunk); log.flush()
                    pending += chunk
                    while b'\n' in pending:
                        line, pending = pending.split(b'\n', 1)
                        message = line.decode(errors='replace')
                        if 'Done (' in message and startup is None:
                            startup = round(time.monotonic()-started, 3)
                            first = ('hearthverify player-open' if manual else
                                     ('hearthverify smoke' if phase == 'prepare' else 'hearthverify restart resume'))
                            child.stdin.write((first+'\n').encode()); child.stdin.flush()
                        if phase == 'prepare' and 'HEARTH_S4_RESULT PASS' in message and not sent_phase:
                            child.stdin.write(b'hearthverify restart prepare\n'); child.stdin.flush(); sent_phase = True
                        for token, target in [('HEARTH_S4_2_TIMING', timings), ('HEARTH_S4_2_CHECK', checks),
                                              ('HEARTH_S4_2_EVENT', events), ('HEARTH_S4_2_SNAPSHOT', snapshots)]:
                            if token in message:
                                target.append(message.split(token, 1)[1].strip())
                        if 'HEARTH_' in message or 'ERROR' in message or 'Done (' in message:
                            print(message, flush=True)
                        if manual and 'HEARTH_S4_3_READY' in message:
                            manual_ready = True
                            print('Manual fixture ready. Use terminal commands from docs/s4-3-verification.md; stop closes this JVM.', flush=True)
                        if ('HEARTH_S4_2_RESULT PASS ' + phase) in message and outcome == 'NOT_COMPLETED':
                            outcome = 'PASS'
                        elif ('ERROR' in message or 'HEARTH_S4_RESULT FAIL' in message
                              or 'HEARTH_S4_2_RESULT FAIL' in message or 'Unknown or incomplete command' in message):
                            outcome = 'FAIL'
            try:
                child.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid, signal.SIGKILL)
                child.wait()
            log.write(child.stdout.read())
    finally:
        if child.poll() is None:
            child.terminate()
            try:
                child.wait(timeout=15)
            except subprocess.TimeoutExpired:
                child.kill(); child.wait()
        poller.close()
        (base / (phase + '-resources.json')).write_text(json.dumps(samples, indent=2)+'\n')
        result = dict(phase=phase, outcome=outcome, pid=child.pid, exit_code=child.returncode,
                      duration_seconds=round(time.monotonic()-started, 3), ready_elapsed_seconds=startup,
                      max_rss_kib=max((s['rss_kib'] for s in samples), default=0),
                      min_host_available_kib=min((s['available_kib'] for s in samples), default=0),
                      sampled_cpu_seconds=round((samples[-1]['cpu_ticks']-samples[0]['cpu_ticks'])/os.sysconf('SC_CLK_TCK'),3) if samples else None,
                      sample_count=len(samples), timings=timings, checks=checks, events=events, snapshots=snapshots)
        (base / (phase + '-result.json')).write_text(json.dumps(result, indent=2)+'\n')
        print(json.dumps(result), flush=True)
    accepted = ('MANUAL_CLOSED',) if manual else ('PASS',)
    if result['outcome'] not in accepted or result['exit_code'] != 0:
        raise RuntimeError('Phase failed; preserve all evidence and do not start another JVM')
    port_free()
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workspace', required=True, type=Path)
    parser.add_argument('--java', required=True, type=Path)
    parser.add_argument('--plugin', required=True, type=Path)
    parser.add_argument('--accept-eula', action='store_true')
    parser.add_argument('--manual-player', action='store_true', help='S4-3: two bounded TTY sessions for an authenticated client; never auto-passes player tests')
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error('Explicit operator EULA acceptance is required; no server was created')
    if args.manual_player and not sys.stdin.isatty():
        parser.error('Manual player mode requires an operator TTY and an independently authenticated client')
    base = args.workspace.resolve(strict=True)
    if (args.workspace.is_symlink() or os.geteuid() == 0 or base.parent != Path('/tmp')
            or not base.name.startswith('hearth-s4-2.') or base.stat().st_uid != os.getuid()):
        parser.error('Need fresh user-owned /tmp/hearth-s4-2.* workspace')
    if available_kib() < 6 * 1024**2 or shutil.disk_usage(base).free < 4 * 1024**3:
        parser.error('Need 6 GiB available RAM and 4 GiB free disk')
    port_free()
    repo = Path(__file__).resolve().parents[1]
    for artifact in json.loads((repo/'verification/s4-1-artifacts.json').read_text())['artifacts']:
        if hashlib.new(artifact['algorithm'], (base/artifact['file']).read_bytes()).hexdigest() != artifact['hash']:
            raise RuntimeError('Official artifact checksum mismatch')
    with zipfile.ZipFile(args.plugin) as jar:
        names = jar.namelist()
        if ('plugin.yml' not in names or 'io/github/jesterportfolio/hearth/verification/RestartVerification.class' not in names
                or any(n.startswith(('org/bukkit/', 'com/sk89q/')) for n in names)):
            raise RuntimeError('Incomplete JAR or bundled API')
    runtime = base/'server'
    runtime.mkdir()  # Refuse any pre-existing server; old S4-1 evidence is never reused.
    (runtime/'.hearth-s4-isolated').write_text('S4-2 disposable test data only\n')
    plugins = runtime/'plugins'
    plugins.mkdir()
    shutil.copyfile(args.plugin, plugins/'Hearth.jar')
    for name in ['worldguard-bukkit-7.0.19.jar', 'worldedit-bukkit-7.4.4.jar']:
        shutil.copyfile(base/name, plugins/name)
    (plugins/'Hearth').mkdir()
    (plugins/'Hearth/config.yml').write_text('verification-enabled: true\n')
    (plugins/'bStats').mkdir()
    (plugins/'bStats/config.yml').write_text('enabled: false\n')
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
motd=Hearth isolated S4-2 verification
''')
    started = time.monotonic()
    deadline = started + 240  # Combined two-JVM work retains the original 240-second test budget.
    before = run_jvm(base, args.java, 'prepare', deadline)
    if args.manual_player:
        sessions = [run_jvm(base, args.java, name, deadline, manual=True) for name in ['player_before', 'player_after']]
        report = dict(outcome='MANUAL_SESSIONS_CLOSED_UNVERIFIED', prepare=before, sessions=sessions,
                      connected_player_tests='Require human review of client actions, event logs and final block states',
                      all_test_jvms_exited=True, port_released=True)
        (base/'manual-result.json').write_text(json.dumps(report, indent=2)+'\n')
        print('Manual sessions ended; no automatic player protection PASS is asserted', flush=True)
        return
    after = run_jvm(base, args.java, 'resume', deadline)
    if before['pid'] == after['pid']:
        raise RuntimeError('Expected distinct JVM PIDs')
    report = dict(outcome='PASS', workspace=str(base), duration_seconds=round(time.monotonic()-started,3),
                  jar_sha256=hashlib.sha256(args.plugin.read_bytes()).hexdigest(),
                  limits=dict(total_seconds=240, heap_max_mib=1536, rss_stop_mib=2560,
                              active_processor_count_hint=2, nice=10, host_available_min_mib=6144),
                  prepare=before, resume=after,
                  connected_player_tests='NOT RUN: no authenticated Minecraft client in this isolated fixture',
                  tps_mspt_and_leaks='NOT MEASURED', all_test_jvms_exited=True, port_released=True)
    (base/'run-result.json').write_text(json.dumps(report, indent=2)+'\n')
    print('S4-2 PASS; player tests NOT RUN; both JVMs exited; localhost port released', flush=True)


if __name__ == '__main__':
    main()
