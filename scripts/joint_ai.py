"""기존 Paper/PG 감시와 고정 AI 런타임을 하나의 시계·합산 RSS 감시에 연결합니다."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import threading
import time
import isolated_paths
import s5_ai_trial as s5
import space_reclaim

GIB=1024**3


def descendants(processes, roots):
    result={pid for pid in roots if pid in processes}
    while True:
        children={pid for pid,row in processes.items() if row['parent'] in result}
        if children <= result:return result
        result.update(children)


def current(processes, owned):
    return {pid for pid,start in owned.items() if pid in processes and processes[pid]['start']==start}


def live_roots(processes, identities):
    """reaped roots must not claim a foreign process that later reuses the same PID."""
    return {pid for pid,start,running in identities if running and pid in processes and processes[pid]['start']==start}


def totals(processes, ai, all_owned, gpu):
    ai_rss=sum(processes[pid]['rss_kib'] for pid in ai)
    combined=sum(processes[pid]['rss_kib'] for pid in all_owned)
    if ai_rss>4096*1024:raise RuntimeError('AI_RSS_ABOVE_4_GIB')
    if combined>6144*1024:raise RuntimeError('JOINT_RSS_ABOVE_6_GIB')
    if gpu>5120:raise RuntimeError('GPU_ABOVE_5_GIB')
    return ai_rss,combined


class JointTrial(s5.Trial):
    def __init__(self, root, monitor):
        super().__init__(root)
        self.started=monitor.started
        self.monitor=monitor
        self.all_owned={}
        self.watcher=threading.Thread(target=self.watch,daemon=True)

    def watch(self):
        try:
            while not self.halt.wait(.5):
                self.guard()
                processes={}
                for entry in Path('/proc').iterdir():
                    if entry.name.isdigit():
                        try:
                            row=self.process(int(entry.name));processes[row['pid']]=row
                        except (OSError,ValueError,IndexError):pass
                ai_roots=live_roots(processes,[(p.pid,self.owned.get(p.pid),p.poll() is None) for p,_ in list(self.children)])
                native_roots={item['proc'].pid for item in list(self.monitor.children.values()) if item['proc'].poll() is None}
                ai_tree=descendants(processes,ai_roots)
                all_tree=descendants(processes,ai_roots|native_roots|{os.getpid()})
                with self.lock:
                    for pid in ai_tree:self.owned[pid]=processes[pid]['start']
                    for pid in all_tree:self.all_owned[pid]=processes[pid]['start']
                    ai=current(processes,self.owned);all_now=current(processes,self.all_owned)
                usage=subprocess.run(['nvidia-smi','--query-compute-apps=pid,used_gpu_memory','--format=csv,noheader,nounits'],capture_output=True,text=True,timeout=2)
                if usage.returncode:raise RuntimeError('GPU_SAMPLING_FAILED')
                gpu=0
                for line in usage.stdout.splitlines():
                    parts=[p.strip() for p in line.split(',')]
                    if parts and parts[0].isdigit() and int(parts[0]) in all_now:
                        if len(parts)!=2 or not parts[1].isdigit():raise RuntimeError('GPU_MEMORY_UNOBSERVABLE')
                        gpu+=int(parts[1])
                ai_rss,combined=totals(processes,ai,all_now,gpu)
                for name,item in list(self.monitor.children.items()):
                    if name.startswith('paper') and item['proc'].pid in all_now and processes[item['proc'].pid]['rss_kib']>=2560*1024:raise RuntimeError('PAPER_RSS_ABOVE_2560_MIB')
                disk=sum(p.lstat().st_size for p in self.root.rglob('*') if p.is_file() and not p.is_symlink())
                if disk>12*GIB:raise RuntimeError('AI_DISK_ABOVE_12_GIB')
                self.samples.append(dict(seconds=round(time.monotonic()-self.started,3),ai_rss_kib=ai_rss,combined_rss_kib=combined,gpu_mib=gpu,ai_disk_bytes=disk,host_available_kib=s5.available_kib()))
                if len(self.samples)>1200:raise RuntimeError('JOINT_SAMPLE_LIMIT')
        except Exception as error:self.error=type(error).__name__+': '+str(error)


class JointAI:
    def __init__(self, monitor, artifact_source):
        self.monitor=monitor
        self.root=isolated_paths.fresh('hearth-s5-trial.')
        (self.root/'.hearth-s5-isolated').write_text(s5.MARKER)
        self.trial=JointTrial(self.root,monitor)
        self.original_home=os.environ.get('HOME')
        self.server=None;self.ready=False;self.cleanup_errors=[]
        self.source=isolated_paths.workspace(artifact_source,'hearth-s5-trial.',legacy_source=True)
        assert self.source!=self.root and (self.source/'.hearth-s5-isolated').read_text()==s5.MARKER
        assert self.source.stat().st_uid==os.getuid()
        # One original main() clock, no independently restarted AI budget.
        (self.root/'joint-clock.json').write_text(json.dumps(dict(shared_native_workspace=str(monitor.root),original_monotonic_started=monitor.started))+'\n')
        os.sched_setaffinity(0,set(sorted(os.sched_getaffinity(0))[:2]))
        os.nice(max(0,10-os.getpriority(os.PRIO_PROCESS,0)))
        if shutil.disk_usage(self.root).free<16*GIB:
            result=space_reclaim.reclaim();evidence=isolated_paths.fresh('hearth-space-reclaim.');(evidence/'result.json').write_text(json.dumps(result)+'\n')
            assert result['outcome']=='PASS','Storage reclamation failed'
        assert shutil.disk_usage(self.root).free>=16*GIB
        self.trial.guard();s5.port_free();self.trial.watcher.start()

    def prepare_artifacts(self):
        trial=self.trial;trial.guard()
        approved=json.loads((s5.REPO/'verification/s5-ai-readiness.json').read_text());runtime,model=approved['proposed_runtime'],approved['proposed_model']
        manifest=self.source/'model-manifest.json'
        assert manifest.is_file() and not manifest.is_symlink() and manifest.stat().st_size<=100000
        raw=manifest.read_bytes();assert hashlib.sha256(raw).hexdigest()==model['manifest_sha256_computed_from_fetched_metadata']
        (self.root/'model-manifest.json').write_bytes(raw);trial.reused+=len(raw)
        parsed=json.loads(raw);jobs=[(self.source/'ollama.tar.zst',self.root/'ollama.tar.zst',runtime['declared_bytes'],runtime['declared_sha256'])]
        for layer in [parsed['config']]+parsed['layers']:
            algorithm,digest=layer['digest'].split(':');assert algorithm=='sha256' and len(digest)==64 and all(c in '0123456789abcdef' for c in digest)
            relative=Path('models/blobs')/('sha256-'+digest);source=self.source/relative
            assert source.resolve().is_relative_to(self.source)
            jobs.append((source,self.root/relative,layer['size'],digest))
        with ThreadPoolExecutor(max_workers=2) as workers:
            futures=[workers.submit(trial.reuse,*job) for job in jobs]
            for future in futures:future.result()
        end=time.monotonic()+90
        def guard():
            trial.guard()
            if time.monotonic()>=end:raise RuntimeError('EXTRACTION_ACTION_DEADLINE_90_SECONDS')
        s5.unpack(self.root/'ollama.tar.zst',self.root/'runtime',guard)
        tag=self.root/'models/manifests/registry.ollama.ai/library/qwen3/4b-instruct-2507-q4_K_M';tag.parent.mkdir(parents=True);tag.write_bytes(raw)

    def start(self):
        self.trial.guard();profile=self.root/'profile';profile.mkdir(mode=0o700)
        env=os.environ.copy()
        env.update(TMPDIR=str(self.root),HOME=str(profile),OLLAMA_HOST='127.0.0.1:11435',OLLAMA_MODELS=str(self.root/'models'),OLLAMA_TMPDIR=str(self.root/'runner-tmp'),OLLAMA_NO_CLOUD='1',OLLAMA_NOHISTORY='1',OLLAMA_MAX_LOADED_MODELS='1',OLLAMA_NUM_PARALLEL='1',OLLAMA_MAX_QUEUE='1',OLLAMA_CONTEXT_LENGTH='4096',OLLAMA_KEEP_ALIVE='60s',OLLAMA_LOAD_TIMEOUT='90s',OLLAMA_DEBUG='0',GOMAXPROCS='2')
        self.server=self.trial.launch([str(self.root/'runtime/bin/ollama'),'serve'],env,'ollama')
        end=time.monotonic()+30
        while True:
            self.trial.guard();self.monitor.pump()
            if self.server.poll() is not None:raise RuntimeError('OLLAMA_EXITED_BEFORE_READY')
            try:
                result=self.trial.api('/api/version');assert result['version']=='0.40.16';break
            except OSError:
                if time.monotonic()>=end:raise RuntimeError('OLLAMA_READY_TIMEOUT')
        self.ready=True

    def cleanup(self):
        self.cleanup_errors=self.trial.cleanup()
        self.trial.watcher.join(timeout=3)
        if self.trial.watcher.is_alive():self.cleanup_errors.append('Joint resource watcher did not close')
        return self.cleanup_errors

    def result(self, outcome):
        samples=self.trial.samples
        native=self.monitor.root/'joint-phone-result.json'
        observed=json.loads(native.read_text()) if native.is_file() else {}
        report=dict(outcome=outcome,runtime_ready=self.ready,real_model=observed.get('real_model',False),profile='joint-paper-pg-ai',actual_minecraft_client=False,four_player_capacity_pass=False,shared_native_workspace=str(self.monitor.root),shared_clock=True,duration_seconds=round(time.monotonic()-self.monitor.started,3),guard_failure=self.trial.error,cleanup_errors=self.cleanup_errors,downloaded_bytes=self.trial.downloaded,locally_reused_bytes=self.trial.reused,receipts=self.trial.receipts,parent_home_unchanged=os.environ.get('HOME')==self.original_home,max_combined_rss_kib=max((s['combined_rss_kib'] for s in samples),default=0),max_ai_rss_kib=max((s['ai_rss_kib'] for s in samples),default=0),max_gpu_mib=max((s['gpu_mib'] for s in samples),default=0),min_host_available_kib=min((s['host_available_kib'] for s in samples),default=s5.available_kib()),limits=dict(total_seconds=600,work_seconds=550,ai_rss_gib=4,combined_rss_gib=6,gpu_gib=5,host_available_gib=6,cpu=2,nice=10))
        if report['duration_seconds']>600 or self.cleanup_errors or self.trial.error:report['outcome']='FAIL'
        (self.root/'resource-samples.json').write_text(json.dumps(samples)+'\n');(self.root/'run-result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        return dict(result=str(self.root/'run-result.json'),**report)
