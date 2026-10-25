#!/usr/bin/env python3
"""Bounded localhost smoke test. No downloads, production data, services or SQL changes."""
import argparse
import hashlib
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


def available_kib():
    for line in Path('/proc/meminfo').read_text().splitlines():
        if line.startswith('MemAvailable:'):
            return int(line.split()[1])
    raise RuntimeError('Cannot measure available memory')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workspace', type=Path, required=True)
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--plugin', type=Path, required=True)
    parser.add_argument('--accept-eula', action='store_true', help='Operator accepts https://www.minecraft.net/eula')
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error('Explicit operator EULA agreement is required; server was not started')
    base = args.workspace.resolve(strict=True)
    if args.workspace.is_symlink():
        parser.error('Workspace must not be a symlink')
    if os.geteuid() == 0 or base.parent != Path('/tmp') or not base.name.startswith('hearth-s4-1.'):
        parser.error('Use a non-root-owned /tmp/hearth-s4-1.* workspace')
    if base.stat().st_uid != os.getuid() or base.is_symlink():
        parser.error('Workspace must be owned by the invoking user')
    if available_kib() < 6 * 1024 * 1024 or shutil.disk_usage(base).free < 4 * 1024**3:
        parser.error('Need 6 GiB available host memory and 4 GiB free disk')
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 25585))
    repo = Path(__file__).resolve().parents[1]
    manifest = json.loads((repo / 'verification/s4-1-artifacts.json').read_text())
    for artifact in manifest['artifacts']:
        path = base / artifact['file']
        digest = hashlib.new(artifact['algorithm'], path.read_bytes()).hexdigest()
        if digest != artifact['hash']:
            raise RuntimeError('Official checksum mismatch: ' + artifact['file'])
    with zipfile.ZipFile(args.plugin) as jar:
        names = jar.namelist()
        if 'plugin.yml' not in names or 'io/github/jesterportfolio/hearth/HearthPlugin.class' not in names:
            raise RuntimeError('Incomplete Hearth JAR')
        if any(n.startswith(('org/bukkit/', 'com/sk89q/')) for n in names):
            raise RuntimeError('Dependency classes unexpectedly bundled')
    runtime = base / 'server'
    runtime.mkdir()  # Never reuse/overwrite a server directory or world.
    (runtime / '.hearth-s4-isolated').write_text('S4-1 disposable test data only\n')
    plugins = runtime / 'plugins'
    plugins.mkdir()
    shutil.copyfile(args.plugin, plugins / 'Hearth.jar')
    for name in ['worldguard-bukkit-7.0.19.jar', 'worldedit-bukkit-7.4.4.jar']:
        shutil.copyfile(base / name, plugins / name)
    (plugins / 'Hearth').mkdir()
    (plugins / 'Hearth/config.yml').write_text('verification-enabled: true\n')
    (plugins / 'bStats').mkdir()
    (plugins / 'bStats/config.yml').write_text('enabled: false\n')
    (runtime / 'eula.txt').write_text('eula=true\n')
    (runtime / 'server.properties').write_text('''server-ip=127.0.0.1
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
allow-nether=false
level-name=world
level-type=minecraft:flat
generator-settings={"layers":[{"block":"minecraft:bedrock","height":1}],"biome":"minecraft:plains"}
pause-when-empty-seconds=-1
motd=Hearth isolated S4 verification
''')
    command = [str(args.java.resolve(strict=True)), '-Xms256m', '-Xmx1536m', '-XX:ActiveProcessorCount=2',
               '-Dhearth.verification.root=' + str(runtime), '-jar', str(base / 'paper-26.2-134.jar'), '--nogui']
    def lower_priority():
        os.nice(10)
    started = time.monotonic()
    child = subprocess.Popen(command, cwd=runtime, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                             stderr=subprocess.STDOUT, start_new_session=True, preexec_fn=lower_priority)
    poller = selectors.DefaultSelector()
    poller.register(child.stdout, selectors.EVENT_READ)
    pending = b''
    outcome = 'NOT_COMPLETED'
    sent_test = sent_stop = False
    stop_at = None
    samples = []
    try:
        with (base / 'console.log').open('wb') as log:
            while child.poll() is None:
                now = time.monotonic()
                status = Path(f'/proc/{child.pid}/status')
                if status.exists():
                    stats = dict(line.split(':', 1) for line in status.read_text().splitlines() if ':' in line)
                    rss = int(stats.get('VmRSS', '0 kB').split()[0])
                    cpu_fields = Path(f'/proc/{child.pid}/stat').read_text().split()
                    samples.append(dict(seconds=round(now-started, 2), rss_kib=rss,
                                        cpu_ticks=int(cpu_fields[13])+int(cpu_fields[14]),
                                        available_kib=available_kib()))
                    if rss > 2560 * 1024 or samples[-1]['available_kib'] < 6 * 1024 * 1024:
                        outcome = 'RESOURCE_LIMIT'
                if now-started > 240 and outcome == 'NOT_COMPLETED':
                    outcome = 'TIME_LIMIT'
                if outcome != 'NOT_COMPLETED' and not sent_stop:
                    child.stdin.write(b'stop\n'); child.stdin.flush()
                    sent_stop = True; stop_at = now
                if stop_at is not None and now-stop_at > 30:
                    os.killpg(child.pid, signal.SIGTERM)
                    break
                for key, _ in poller.select(timeout=1):
                    chunk = os.read(key.fileobj.fileno(), 65536)
                    if not chunk:
                        continue
                    log.write(chunk); log.flush()
                    pending += chunk
                    while b'\n' in pending:
                        line, pending = pending.split(b'\n', 1)
                        message = line.decode(errors='replace')
                        if 'Done (' in message and not sent_test:
                            child.stdin.write(b'hearthverify smoke\n'); child.stdin.flush(); sent_test = True
                        if 'HEARTH_S4_' in message or 'ERROR' in message or 'Done (' in message:
                            print(message, flush=True)
                        if 'HEARTH_S4_RESULT PASS' in message:
                            outcome = 'PASS'
                        elif 'HEARTH_S4_RESULT FAIL' in message:
                            outcome = 'FAIL'
                        elif sent_test and 'Unknown or incomplete command' in message:
                            outcome = 'COMMAND_REJECTED'
            try:
                child.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid, signal.SIGKILL)
                child.wait()
            log.write(child.stdout.read())
    finally:
        if child.poll() is None:
            child.terminate()
            try: child.wait(timeout=15)
            except subprocess.TimeoutExpired: child.kill(); child.wait()
        poller.close()
        (base / 'resource-samples.json').write_text(json.dumps(samples, indent=2)+'\n')
        result = dict(outcome=outcome, exit_code=child.returncode, duration_seconds=round(time.monotonic()-started,2),
                      max_rss_kib=max((s['rss_kib'] for s in samples),default=0), sample_count=len(samples),
                      command=command, cpu_count_limit=2, heap_max_mib=1536)
        (base / 'run-result.json').write_text(json.dumps(result, indent=2)+'\n')
        print(json.dumps(result), flush=True)
    return 0 if outcome == 'PASS' and child.returncode == 0 else 1


if __name__ == '__main__':
    raise SystemExit(main())
