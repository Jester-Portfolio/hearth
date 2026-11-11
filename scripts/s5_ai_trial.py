#!/usr/bin/env python3
"""승인한 S5 한 묶음: 새 사용자 소유 시험 경로, 고정 해시, 다운로드 포함 600초, 소유 프로세스만 정리."""
import isolated_paths
import argparse
from concurrent.futures import ThreadPoolExecutor
import ctypes
import ctypes.util
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import signal
import socket
import subprocess
import tarfile
import threading
import time
import tempfile
from urllib.request import Request, urlopen
import space_reclaim

REPO = Path(__file__).resolve().parents[1]
GIB = 1024 ** 3
MARKER = 'S5 isolated AI trial only\n'
MODEL = 'qwen3:4b-instruct-2507-q4_K_M'
HOST = 'http://127.0.0.1:11435'


def available_kib():
    return int(next(line.split()[1] for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith('MemAvailable:')))


def port_free():
    with socket.socket() as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(('127.0.0.1', 11435))


def reviewable_result(root, result):
    if result.get('cleanup_errors') or result.get('guard_failure'):
        return False
    if result.get('outcome') == 'ACTUAL_MODEL_ACCEPTANCE_FAILED':
        return True
    # 첫 실제 자료 준비 응답의 번호 형식 거부만 복구 허용. 자원/HTTP/시간 실패는 재개하지 않습니다.
    return (result.get('outcome') == 'FAIL'
            and result.get('failure') == 'AssertionError: Actual-format warmup failed'
            and (root/'warmup.json').is_file()
            and (root/'warmup.log').is_file()
            and 'AssistantFailure: LOCAL_AI_INVALID_REFERENCE' in (root/'warmup.log').read_text())


class ZstdReader(io.RawIOBase):
    """이미 설치된 libzstd의 bounded 스트림. 시스템/파이썬 패키지를 설치하지 않습니다."""
    class Buffer(ctypes.Structure):
        _fields_ = [('src', ctypes.c_void_p), ('size', ctypes.c_size_t), ('pos', ctypes.c_size_t)]

    def __init__(self, source, guard):
        self.source, self.guard = source, guard
        self.library = ctypes.CDLL(ctypes.util.find_library('zstd'))
        lib = self.library
        lib.ZSTD_createDStream.restype = ctypes.c_void_p
        lib.ZSTD_initDStream.argtypes = [ctypes.c_void_p]
        lib.ZSTD_initDStream.restype = ctypes.c_size_t
        lib.ZSTD_DCtx_setParameter.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int]
        lib.ZSTD_DCtx_setParameter.restype = ctypes.c_size_t
        lib.ZSTD_decompressStream.argtypes = [ctypes.c_void_p, ctypes.POINTER(self.Buffer), ctypes.POINTER(self.Buffer)]
        lib.ZSTD_decompressStream.restype = ctypes.c_size_t
        lib.ZSTD_isError.argtypes = [ctypes.c_size_t]
        lib.ZSTD_isError.restype = ctypes.c_uint
        lib.ZSTD_freeDStream.argtypes = [ctypes.c_void_p]
        self.stream = lib.ZSTD_createDStream()
        if not self.stream:
            raise RuntimeError('ZSTD_CREATE_FAILED')
        self.check(lib.ZSTD_initDStream(self.stream))
        self.check(lib.ZSTD_DCtx_setParameter(self.stream, 100, 27))  # windowLogMax 128MiB
        self.input = self.Buffer(None, 0, 0)
        self.input_data = None
        self.pending = bytearray()
        self.remaining = 1
        self.ended = False

    def check(self, value):
        if self.library.ZSTD_isError(value):
            raise RuntimeError('ZSTD_INVALID_STREAM')
        return value

    def read(self, size=-1):
        if size < 0 or size > 4 * 1024**2:
            raise ValueError('BOUNDED_ZSTD_READ_REQUIRED')
        while len(self.pending) < size and not self.ended:
            self.guard()
            if self.input.pos == self.input.size:
                chunk = self.source.read(1024**2)
                if not chunk:
                    if self.remaining:
                        raise RuntimeError('ZSTD_TRUNCATED')
                    self.ended = True
                    break
                self.input_data = ctypes.create_string_buffer(chunk)
                self.input = self.Buffer(ctypes.cast(self.input_data, ctypes.c_void_p), len(chunk), 0)
            output_data = ctypes.create_string_buffer(1024**2)
            output = self.Buffer(ctypes.cast(output_data, ctypes.c_void_p), len(output_data), 0)
            self.remaining = self.check(self.library.ZSTD_decompressStream(self.stream, ctypes.byref(output), ctypes.byref(self.input)))
            self.pending.extend(output_data.raw[:output.pos])
        answer = bytes(self.pending[:size])
        del self.pending[:size]
        return answer

    def close(self):
        if getattr(self, 'stream', None):
            self.library.ZSTD_freeDStream(self.stream)
            self.stream = None
        super().close()


def safe_path(root, name):
    relative = PurePosixPath(name)
    if relative.is_absolute() or '..' in relative.parts:
        raise RuntimeError('ARCHIVE_PATH')
    path = root.joinpath(*relative.parts)
    if not path.resolve().is_relative_to(root.resolve()):
        raise RuntimeError('ARCHIVE_LINK_ESCAPE')
    return path


def unpack(archive, root, guard):
    root.mkdir()
    expanded = 0
    with archive.open('rb') as source, ZstdReader(source, guard) as reader, tarfile.open(fileobj=reader, mode='r|') as tar:
        for member in tar:
            guard()
            target = safe_path(root, member.name)
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.exists() or target.is_symlink():
                raise RuntimeError('ARCHIVE_OVERWRITE')
            if member.isfile():
                expanded += member.size
                if expanded > 8 * GIB:
                    raise RuntimeError('ARCHIVE_EXPANSION_LIMIT')
                with tar.extractfile(member) as content, target.open('xb') as output:
                    while True:
                        guard()
                        chunk = content.read(1024**2)
                        if not chunk:
                            break
                        output.write(chunk)
                target.chmod(0o755 if member.mode & 0o111 else 0o644)
            elif member.issym():
                if PurePosixPath(member.linkname).is_absolute():
                    raise RuntimeError('ARCHIVE_ABSOLUTE_LINK')
                if not (target.parent / member.linkname).resolve().is_relative_to(root.resolve()):
                    raise RuntimeError('ARCHIVE_LINK_ESCAPE')
                target.symlink_to(member.linkname)
            elif member.islnk():
                link = safe_path(root, member.linkname)
                if not link.is_file() or link.is_symlink():
                    raise RuntimeError('ARCHIVE_HARDLINK')
                os.link(link, target)
            else:
                raise RuntimeError('ARCHIVE_SPECIAL_FILE')


class Trial:
    def __init__(self, root):
        self.root = root
        self.started = time.monotonic()
        self.error = None
        self.lock = threading.RLock()
        self.halt = threading.Event()
        self.children = []
        self.owned = {}
        self.downloaded = 0
        self.reused = 0
        self.samples = []
        self.receipts = []

    def guard(self):
        if self.error:
            raise RuntimeError(self.error)
        if time.monotonic() - self.started >= 550:
            raise RuntimeError('WORK_DEADLINE_550_SECONDS')
        if available_kib() < 6 * 1024**2:
            raise RuntimeError('HOST_AVAILABLE_BELOW_6_GIB')

    @staticmethod
    def process(pid):
        data = Path('/proc/' + str(pid))
        fields = (data / 'stat').read_text().rsplit(')', 1)[1].split()
        status = (data / 'status').read_text().splitlines()
        rss = int(next((line.split()[1] for line in status if line.startswith('VmRSS:')), '0'))
        return dict(pid=pid, parent=int(fields[1]), start=fields[19], rss_kib=rss)

    def watch(self):
        try:
            while not self.halt.wait(0.5):
                self.guard()
                processes = {}
                for entry in Path('/proc').iterdir():
                    if entry.name.isdigit():
                        try:
                            row = self.process(int(entry.name))
                            processes[row['pid']] = row
                        except (OSError, ValueError, IndexError):
                            pass
                owned_ids = {os.getpid()} | {p.pid for p, _ in self.children}
                changed = True
                while changed:
                    added = {pid for pid, row in processes.items() if row['parent'] in owned_ids} - owned_ids
                    changed = bool(added)
                    owned_ids.update(added)
                with self.lock:
                    for pid in owned_ids:
                        if pid in processes:
                            self.owned[pid] = processes[pid]['start']
                    current = {pid for pid, start in self.owned.items() if pid in processes and processes[pid]['start'] == start}
                rss = sum(processes[pid]['rss_kib'] for pid in current)
                result = subprocess.run(['nvidia-smi', '--query-compute-apps=pid,used_gpu_memory', '--format=csv,noheader,nounits'], capture_output=True, text=True, timeout=2)
                if result.returncode:
                    raise RuntimeError('GPU_SAMPLING_FAILED')
                gpu = 0
                for line in result.stdout.splitlines():
                    parts = [p.strip() for p in line.split(',')]
                    if parts and parts[0].isdigit() and int(parts[0]) in current:
                        if len(parts) != 2 or not parts[1].isdigit():
                            raise RuntimeError('GPU_MEMORY_UNOBSERVABLE')
                        gpu += int(parts[1])
                disk = sum(p.lstat().st_size for p in self.root.rglob('*') if p.is_file() and not p.is_symlink())
                self.samples.append(dict(seconds=round(time.monotonic()-self.started, 3), rss_kib=rss, gpu_mib=gpu, disk_bytes=disk, host_available_kib=available_kib()))
                if rss > 4096 * 1024 or gpu > 5120 or disk > 12 * GIB:
                    raise RuntimeError('AI_RESOURCE_LIMIT')
        except Exception as error:
            self.error = type(error).__name__ + ': ' + str(error)

    def fetch(self, url, target, size, digest):
        self.guard()
        target.parent.mkdir(parents=True, exist_ok=True)
        partial = target.with_name(target.name + '.partial')
        checksum, count = hashlib.sha256(), 0
        with urlopen(Request(url, headers={'User-Agent': 'Hearth-S5-isolated-verification'}), timeout=15) as source, partial.open('xb') as output:
            if not source.geturl().startswith('https://'):
                raise RuntimeError('DOWNLOAD_HTTPS_REQUIRED')
            while True:
                self.guard()
                chunk = source.read(1024**2)
                if not chunk:
                    break
                count += len(chunk)
                with self.lock:
                    self.downloaded += len(chunk)
                    if self.downloaded > 6 * GIB or count > size:
                        raise RuntimeError('DOWNLOAD_BUDGET')
                checksum.update(chunk)
                output.write(chunk)
        if count != size or checksum.hexdigest() != digest:
            raise RuntimeError('ARTIFACT_HASH_OR_SIZE_MISMATCH')
        assert not target.exists()
        partial.rename(target)
        with self.lock:
            self.receipts.append(dict(file=str(target.relative_to(self.root)), bytes=count, sha256=checksum.hexdigest(), verified=True))
        print('S5 verified artifact bytes='+str(count), flush=True)

    def launch(self, command, env, name):
        self.guard()
        log = (self.root / (name + '.log')).open('wb')
        child = subprocess.Popen(isolated_paths.java_command(command,self.root), cwd=self.root, env=env, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, start_new_session=True)
        self.children.append((child, log))
        self.owned[child.pid] = self.process(child.pid)['start']
        return child

    def reuse(self, source, target, size, digest):
        self.guard()
        assert source.is_file() and not source.is_symlink() and source.stat().st_size==size
        target.parent.mkdir(parents=True,exist_ok=True)
        partial=target.with_name(target.name+'.partial');checksum=hashlib.sha256();count=0
        with source.open('rb') as incoming,partial.open('xb') as output:
            while True:
                self.guard();chunk=incoming.read(1024**2)
                if not chunk:break
                count+=len(chunk)
                if count>size:raise RuntimeError('REUSED_ARTIFACT_SIZE')
                checksum.update(chunk);output.write(chunk)
        if count!=size or checksum.hexdigest()!=digest:raise RuntimeError('REUSED_ARTIFACT_HASH')
        assert not target.exists();partial.rename(target)
        with self.lock:
            self.reused+=count
            self.receipts.append(dict(file=str(target.relative_to(self.root)),bytes=count,sha256=digest,verified=True,reused=True))
        print('S5 verified reused artifact bytes='+str(count),flush=True)

    def wait(self, process, seconds):
        end = min(self.started+550, time.monotonic()+seconds)
        while process.poll() is None:
            self.guard()
            if time.monotonic() >= end:
                raise RuntimeError('ACTION_DEADLINE')
            self.halt.wait(0.1)
        return process.returncode

    def api(self, path, value=None, timeout=6):
        self.guard()
        request = Request(HOST + path, data=None if value is None else json.dumps(value).encode(), headers={'Content-Type': 'application/json'})
        with urlopen(request, timeout=timeout) as response:
            return json.loads(response.read(1024**2))

    def cleanup(self):
        self.halt.set()
        errors = []
        for process, log in reversed(self.children):
            if process.poll() is None:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                    process.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=3)
                except ProcessLookupError:
                    pass
            log.close()
        for pid, start in list(self.owned.items()):
            if pid == os.getpid():
                continue
            try:
                if self.process(pid)['start'] == start:
                    os.kill(pid, signal.SIGTERM)
            except (OSError, ValueError):
                pass
        for _ in range(30):
            remaining = []
            for pid, start in self.owned.items():
                if pid != os.getpid():
                    try:
                        if self.process(pid)['start'] == start:
                            remaining.append(pid)
                    except (OSError, ValueError):
                        pass
            if not remaining:
                break
            time.sleep(0.1)
        if remaining:
            errors.append('Owned child still present: ' + str(remaining))
        try:
            port_free()
        except OSError:
            errors.append('Trial port still listening')
        for attempt in range(10):
            status = subprocess.run(['nvidia-smi', '--query-compute-apps=pid,used_gpu_memory', '--format=csv,noheader,nounits'], capture_output=True, text=True, timeout=2)
            gpu_owned = [line for line in status.stdout.splitlines() if line.split(',')[0].strip().isdigit() and int(line.split(',')[0].strip()) in self.owned]
            if status.returncode == 0 and not gpu_owned:
                break
            time.sleep(0.2)
        if status.returncode or gpu_owned:
            errors.append('Owned GPU process release not confirmed')
        return errors


def main():
    budget_started=time.monotonic()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workspace', type=Path, required=True)
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--artifact-source',type=Path,help='이전 소유 격리 경로의 아카이브/blob만 재검증·복사하며 프로필/로그/설정은 재사용하지 않습니다')
    parser.add_argument('--grounded',action='store_true',help='자료 번호·세션 캐시·최신성 확인 probe를 사용합니다')
    parser.add_argument('--continue-after-review', action='store_true', help='원본 결과/파일을 보존하고 같은 최초 600초 예산의 남은 시간만 사용합니다')
    args = parser.parse_args()
    root = isolated_paths.workspace(args.workspace,'hearth-s5-trial.')
    assert os.getuid() != 0 and not args.workspace.is_symlink() and root.parent == isolated_paths.BASE and root.name.startswith('hearth-s5-trial.')
    assert root.stat().st_uid == os.getuid() and (root / '.hearth-s5-isolated').read_text() == MARKER
    os.sched_setaffinity(0, set(sorted(os.sched_getaffinity(0))[:2]))
    os.nice(10)
    if args.continue_after_review:
        original=json.loads((root/'run-result.json').read_text())
        assert reviewable_result(root,original)
        assert not (root/'run-result-reviewed.json').exists() and not (root/'models-reviewed').exists()
    else:
        assert set(p.name for p in root.iterdir()) == {'.hearth-s5-isolated'}, 'Fresh workspace required'
        if shutil.disk_usage(root).free < 16 * GIB:
            reclaimed=space_reclaim.reclaim()
            evidence=isolated_paths.fresh('hearth-space-reclaim.')
            (evidence/'result.json').write_text(json.dumps(reclaimed,indent=2)+'\n')
            print('S5 storage preflight '+reclaimed['outcome']+' evidence='+str(evidence),flush=True)
            assert reclaimed['outcome']=='PASS','Storage reclamation was not verified'
        assert shutil.disk_usage(root).free >= 16 * GIB
    assert available_kib() >= 6 * 1024**2
    source_root=None
    if args.artifact_source is not None:
        source_root=isolated_paths.workspace(args.artifact_source,'hearth-s5-trial.',legacy_source=True)
        assert not args.artifact_source.is_symlink() and source_root!=root and source_root.parent in (Path('/tmp'),isolated_paths.BASE) and source_root.name.startswith('hearth-s5-trial.')
        assert source_root.stat().st_uid==os.getuid() and (source_root/'.hearth-s5-isolated').read_text()==MARKER
    port_free()
    trial = Trial(root)
    trial.started=budget_started
    if args.continue_after_review:
        elapsed=time.time()-(root/'.hearth-s5-isolated').stat().st_mtime
        assert 0<=elapsed<450, 'No new time budget is granted by review'
        trial.started=time.monotonic()-elapsed
    suffix='-reviewed' if args.continue_after_review else ''
    watcher = threading.Thread(target=trial.watch, daemon=True)
    watcher.start()
    report = dict(outcome='FAIL', real_model=False, parent_home_unchanged=True)
    original_home = os.environ.get('HOME')
    try:
        approved = json.loads((REPO / 'verification/s5-ai-readiness.json').read_text())
        runtime, model = approved['proposed_runtime'], approved['proposed_model']
        manifest_file = root / 'model-manifest.json'
        if args.continue_after_review:
            raw=manifest_file.read_bytes();trial.downloaded=original['downloaded_bytes'];trial.reused=original.get('locally_reused_bytes',0)
        elif source_root is not None:
            source_manifest=source_root/'model-manifest.json'
            assert source_manifest.is_file() and source_manifest.stat().st_size<=100000 and not source_manifest.is_symlink()
            raw=source_manifest.read_bytes();trial.reused+=len(raw)
        else:
            with urlopen(model['manifest_url'], timeout=15) as response:
                raw = response.read(100000)
            trial.downloaded += len(raw)
        assert hashlib.sha256(raw).hexdigest() == model['manifest_sha256_computed_from_fetched_metadata']
        if not args.continue_after_review:manifest_file.write_bytes(raw)
        manifest = json.loads(raw)
        jobs = [(runtime['artifact_url'], root / 'ollama.tar.zst', runtime['declared_bytes'], runtime['declared_sha256'])]
        for layer in [manifest['config']] + manifest['layers']:
            algorithm, digest = layer['digest'].split(':')
            assert algorithm == 'sha256' and len(digest) == 64 and all(c in '0123456789abcdef' for c in digest)
            jobs.append(('https://registry.ollama.ai/v2/library/qwen3/blobs/' + layer['digest'], root / 'models/blobs' / ('sha256-' + digest), layer['size'], digest))
        if args.continue_after_review:
            for _,file,size,digest in jobs:
                trial.guard();checksum=hashlib.sha256()
                with file.open('rb') as source:
                    for chunk in iter(lambda:source.read(1024**2),b''):trial.guard();checksum.update(chunk)
                assert file.stat().st_size==size and checksum.hexdigest()==digest,'Original artifact changed'
            trial.receipts=original['receipts'];print('S5 review continuation: original hashes unchanged, no downloads, original deadline retained',flush=True)
        else:
            print('S5 artifact preparation: verified local copies' if source_root else 'S5 download started: pinned runtime + pinned model; 6GiB aggregate limit', flush=True)
            with ThreadPoolExecutor(max_workers=2) as workers:
                if source_root is None:futures=[workers.submit(trial.fetch,*job) for job in jobs]
                else:
                    futures=[]
                    for _,file,size,digest in jobs:
                        source=source_root/file.relative_to(root)
                        assert source.resolve().is_relative_to(source_root)
                        futures.append(workers.submit(trial.reuse,source,file,size,digest))
                for future in futures:
                    try:
                        future.result()
                    except Exception:
                        trial.error = 'ARTIFACT_PREPARATION_FAILED'
                        for pending in futures:pending.cancel()
                        raise
        trial.guard()
        print('S5 artifacts verified; extracting into new owned runtime directory', flush=True)
        extraction_end = time.monotonic()+90
        def extraction_guard():
            trial.guard()
            if time.monotonic() >= extraction_end:
                raise RuntimeError('EXTRACTION_ACTION_DEADLINE_90_SECONDS')
        if not args.continue_after_review:unpack(root / 'ollama.tar.zst', root / 'runtime', extraction_guard)
        models=root/('models'+suffix)
        if args.continue_after_review:
            shutil.copytree(root/'models/blobs',models/'blobs')
            trial.guard()
        tag = models / 'manifests/registry.ollama.ai/library/qwen3/4b-instruct-2507-q4_K_M'
        tag.parent.mkdir(parents=True)
        if not tag.exists():tag.write_bytes(raw)
        trial_profile = root / ('profile'+suffix)
        trial_profile.mkdir(mode=0o700)
        env = os.environ.copy()
        # Child HOME retains its normal meaning as this process's isolated profile;
        # the caller's HOME/os.environ and existing profile are never reassigned.
        env.update(TMPDIR=str(root),HOME=str(trial_profile), OLLAMA_HOST='127.0.0.1:11435', OLLAMA_MODELS=str(models), OLLAMA_TMPDIR=str(root / ('runner-tmp'+suffix)), OLLAMA_NO_CLOUD='1', OLLAMA_NOHISTORY='1', OLLAMA_MAX_LOADED_MODELS='1', OLLAMA_NUM_PARALLEL='1', OLLAMA_MAX_QUEUE='1', OLLAMA_CONTEXT_LENGTH='4096', OLLAMA_KEEP_ALIVE='60s', OLLAMA_LOAD_TIMEOUT='90s', OLLAMA_DEBUG='0', GOMAXPROCS='2')
        server = trial.launch([str(root / 'runtime/bin/ollama'), 'serve'], env, 'ollama'+suffix)
        end = time.monotonic()+30
        while True:
            trial.guard()
            if server.poll() is not None:
                raise RuntimeError('OLLAMA_EXITED_BEFORE_READY')
            try:
                version = trial.api('/api/version')
                break
            except OSError:
                if time.monotonic() >= end:
                    raise RuntimeError('OLLAMA_READY_TIMEOUT')
                trial.halt.wait(0.2)
        assert version['version'] == runtime['version'].removeprefix('v')
        report['version'] = version
        report['tags'] = trial.api('/api/tags')
        print('S5 runtime ready; bounded model warmup started', flush=True)
        warm_file=root/('warmup'+suffix+'.json')
        warm_command=[str(args.java),'-Xmx192m','-XX:ActiveProcessorCount=2','-cp',args.classpath,'io.github.jesterportfolio.hearth.phone.OllamaTrialWarmup',HOST+'/api/generate',str(warm_file)]
        if args.grounded:warm_command.append('grounded')
        warm=trial.launch(warm_command,os.environ.copy(),'warmup'+suffix)
        assert trial.wait(warm,90)==0,'Actual-format warmup failed'
        report['warmup']=json.loads(warm_file.read_text())
        report['model_processes'] = trial.api('/api/ps')
        probe_file=root/('probe-result'+suffix+'.json')
        command=[str(args.java), '-Xmx192m', '-XX:ActiveProcessorCount=2', '-cp', args.classpath, 'io.github.jesterportfolio.hearth.phone.'+('OllamaGroundingProbe' if args.grounded else 'OllamaTrialProbe'), HOST+'/api/generate', str(probe_file)]
        probe = trial.launch(command, os.environ.copy(), 'probe'+suffix)
        assert trial.wait(probe, 90) == 0, 'Java adapter probe failed'
        report['probe'] = json.loads(probe_file.read_text())
        report['real_model'] = True
        if args.grounded:
            report['required_checks_passed']=report['probe']['required_checks_passed']
            report['outcome']='PASS' if report['required_checks_passed'] else 'ACTUAL_MODEL_ACCEPTANCE_FAILED'
        else:
            positive = [case for case in report['probe']['cases'] if case['name'] in ('korean_short', 'personal_A', 'other_run_B', 'unknown_information', 'after_cancel')]
            overflow = next(c for c in report['probe']['cases'] if c['name'] == 'context_overflow')
            cancel = next((c for c in report['probe']['cases'] if c['name'] == 'cancel_then_next'),dict(cancelled=True,history_empty=True))
            report['required_checks_passed'] = all(c['outcome'] == 'PASS' for c in positive) and overflow['outcome'] == 'CONTEXT_LIMIT' and cancel['cancelled'] and cancel['history_empty']
            report['outcome'] = 'PASS' if report['required_checks_passed'] else 'ACTUAL_MODEL_ACCEPTANCE_FAILED'
    except Exception as error:
        report['failure'] = type(error).__name__ + ': ' + str(error)
        print('S5 stopped: '+report['failure'], flush=True)
    finally:
        trial.halt.set()
        watcher.join(timeout=3)
        report['cleanup_errors'] = trial.cleanup()
        if trial.error:
            report['guard_failure'] = trial.error
            report.setdefault('failure', trial.error)
            report['outcome'] = 'FAIL'
        report.update(duration_seconds=round(time.monotonic()-trial.started, 3), downloaded_bytes=trial.downloaded, locally_reused_bytes=trial.reused,artifact_source=str(source_root) if source_root else None,receipts=trial.receipts, samples=len(trial.samples), max_sampled_rss_kib=max((s['rss_kib'] for s in trial.samples), default=0), max_sampled_gpu_mib=max((s['gpu_mib'] for s in trial.samples), default=0), min_host_available_kib=min((s['host_available_kib'] for s in trial.samples), default=available_kib()), parent_home_unchanged=os.environ.get('HOME') == original_home)
        (root/('resource-samples'+suffix+'.json')).write_text(json.dumps(trial.samples)+'\n')
        if report['cleanup_errors'] or report['duration_seconds'] > 600:
            report['outcome'] = 'FAIL'
        result_file=root/('run-result'+suffix+'.json')
        result_file.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
        print('S5 '+report['outcome']+'; result '+str(result_file), flush=True)
    return 0 if report['outcome'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
