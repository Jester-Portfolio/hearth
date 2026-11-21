#!/usr/bin/env python3
"""같은 검토 범위에 대한 결과·원본 증거·롤백을 CAS 저널로 묶는다. 시험/운영 승인을 대신하지 않는다."""
import sys
if __name__=='__main__':sys.dont_write_bytecode=True
import argparse,copy,fcntl,hashlib,json,os,re,time,stat,uuid
from contextlib import contextmanager
from pathlib import Path
import network_release as release
import offline_backup as backup

KINDS=('client','load','restore','release_regression')
METHODS={'client':'authenticated-client','load':'real-player-load','restore':'restored-runtime','release_regression':'release-regression'}
ZERO='0'*64

def encoded(value):return (json.dumps(value,sort_keys=True,separators=(',',':'),allow_nan=False)+'\n').encode()
def hash_bytes(data):return hashlib.sha256(data).hexdigest()
def write_new(path,data):
    fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
    with os.fdopen(fd,'wb') as out:out.write(data);out.flush();os.fsync(out.fileno())
    sync_directory(path.parent)
def sync_directory(path):
    fd=os.open(path,os.O_RDONLY)
    try:os.fsync(fd)
    finally:os.close(fd)
def read_json(path):
    p=release.regular(path)
    if p.stat().st_size>131072:raise ValueError('DOSSIER_SIZE')
    def unique(pairs):
        out={}
        for k,v in pairs:
            if k in out:raise ValueError('DOSSIER_DUPLICATE_KEY')
            out[k]=v
        return out
    return json.loads(p.read_text(),object_pairs_hook=unique)
def private(root):
    root=backup.canonical_directory(root)
    if root.stat().st_mode&0o077:raise ValueError('DOSSIER_PRIVATE_REQUIRED')
    return root
@contextmanager
def locked(root):
    root=private(root);lock=root/'lock'
    with os.fdopen(os.open(lock,os.O_RDWR|os.O_NOFOLLOW),'r+b') as f:
        info=os.fstat(f.fileno())
        if info.st_uid!=os.getuid() or not stat.S_ISREG(info.st_mode) or info.st_nlink!=1:raise ValueError('DOSSIER_OWNER')
        fcntl.flock(f,fcntl.LOCK_EX);yield root

def initialize(candidate,destination):
    release.inspect(candidate);profile=release.artifacts(candidate)
    if profile['format']!=3:raise ValueError('DOSSIER_FORMAT3_REQUIRED')
    if profile['acceptance_complete']:raise ValueError('DOSSIER_UNACCEPTED_BASE_REQUIRED')
    context=release.context(profile)
    root=backup.fresh(destination);(root/'state').mkdir(mode=0o755);(root/'events').mkdir(mode=0o700);(root/'evidence').mkdir(mode=0o700)
    write_new(root/'lock',b'')
    meta={'format':1,'candidate':str(release.regular(candidate)),'scope':context['scope'],'identity':context['identity']}
    write_new(root/'dossier.json',encoded(meta));head={'format':1,'revision':0,'last':ZERO,'scope':meta['scope'],'meta':release.digest(root/'dossier.json')}
    write_new(root/'state/head.json',encoded(head));(root/'state/head.json').chmod(0o444);return status(root)

def load(root,allow_pending=False):
    for name in ('state','events','evidence'):backup.canonical_directory(root/name)
    meta=read_json(root/'dossier.json');head=read_json(root/'state/head.json')
    if set(meta)!={'format','candidate','scope','identity'} or meta['format']!=1 or set(head)!={'format','revision','last','scope','meta'} or head['format']!=1 or type(head['revision']) is not int or not 0<=head['revision']<=256 or head['scope']!=meta['scope'] or head['meta']!=release.digest(root/'dossier.json'):raise ValueError('DOSSIER_HEAD')
    paths=sorted((root/'events').iterdir());expected=[f'{i:06}.json' for i in range(1,head['revision']+1)]
    if [p.name for p in paths[:head['revision']]]!=expected or len(paths)>head['revision']+int(allow_pending):raise ValueError('DOSSIER_INCOMPLETE_EVENT')
    events=[];last=ZERO
    for i,p in enumerate(paths,1):
        e=read_json(p)
        if set(e)!={'sequence','previous','scope','kind','outcome','method','reference','artifact','recorded_ms'} or e['sequence']!=i or e['previous']!=last or e['scope']!=meta['scope'] or e['kind'] not in (*KINDS,'rollback') or e['outcome'] not in ('PASS','FAIL','NOT_RUN') or not re.fullmatch('[a-f0-9]{64}',e['artifact']) or e['method']!=({'rollback':'verified-network-snapshot',**METHODS}[e['kind']]) or not re.fullmatch('[A-Za-z0-9_./-]{1,128}',e['reference']) or type(e['recorded_ms']) is not int:raise ValueError('DOSSIER_EVENT')
        evidence=root/'evidence'/e['artifact']
        if evidence.stat().st_size>16*1024*1024 or release.digest(evidence)!=e['artifact']:raise ValueError('DOSSIER_EVIDENCE_CHANGED')
        if i<=head['revision']:last=release.digest(p);events.append(e)
        else:
            if p.name!=f'{i:06}.json':raise ValueError('DOSSIER_PENDING_NAME')
    if last!=head['last']:raise ValueError('DOSSIER_CHAIN_CHANGED')
    pending=read_json(paths[-1]) if len(paths)>head['revision'] else None
    marker=root/'state/pending.json'
    if marker.exists():
        intent=read_json(marker)
        if pending and intent!=pending or not pending and intent.get('sequence') not in (head['revision'],head['revision']+1):raise ValueError('DOSSIER_PENDING_CONFLICT')
        if not pending and intent.get('sequence')==head['revision'] and (not events or intent!=events[-1]):raise ValueError('DOSSIER_PENDING_CONFLICT')
    elif pending:raise ValueError('DOSSIER_PENDING_MARKER_MISSING')
    return meta,head,events,pending

def publish(root,head,event_path):
    next_head={**head,'revision':head['revision']+1,'last':release.digest(event_path)}
    temp=root/'state'/('head-'+str(time.time_ns())+'.writing');write_new(temp,encoded(next_head));temp.chmod(0o444);os.replace(temp,root/'state/head.json')
    sync_directory(root/'state')

def current(meta):
    release.inspect(meta['candidate']);profile=release.artifacts(meta['candidate']);context=release.context(profile)
    if context['scope']!=meta['scope']:raise ValueError('DOSSIER_SCOPE_CHANGED')
    return profile

def add(root,expected,kind,outcome,method,reference,artifact,artifact_sha):
    if kind not in KINDS or method!=METHODS[kind] or outcome not in ('PASS','FAIL','NOT_RUN') or not re.fullmatch('[A-Za-z0-9_./-]{1,128}',reference):raise ValueError('DOSSIER_ATTESTATION')
    source=release.regular(artifact)
    if source.stat().st_size>16*1024*1024:raise ValueError('DOSSIER_ARTIFACT_LIMIT')
    data=source.read_bytes()
    if hash_bytes(data)!=artifact_sha:raise ValueError('DOSSIER_SOURCE_CHANGED')
    if outcome=='PASS':
        try:info=json.loads(data)
        except (ValueError,UnicodeError):info={}
        if isinstance(info,dict) and (info.get('outcome','PASS')!='PASS' or kind in ('client','load') and info.get('actual_client')=='NOT_RUN'):raise ValueError('DOSSIER_NOT_AN_ACCEPTANCE_PASS')
    return append(root,expected,kind,outcome,method,reference,data)

def append(root,expected,kind,outcome,method,reference,data):
    with locked(root) as root:
        meta,head,events,pending=load(root,True)
        if release.digest(root/'state/head.json')!=expected:raise ValueError('DOSSIER_CAS')
        current(meta)
        if pending:raise ValueError('DOSSIER_RESUME_REQUIRED')
        if head['revision']>=256:raise ValueError('DOSSIER_CAPACITY')
        sha=hash_bytes(data);evidence=root/'evidence'/sha
        event={'sequence':head['revision']+1,'previous':head['last'],'scope':meta['scope'],'kind':kind,'outcome':outcome,'method':method,'reference':reference,'artifact':sha,'recorded_ms':int(time.time()*1000)}
        marker=root/'state/pending.json'
        if marker.exists():
            intent=read_json(marker)
            if any(intent.get(k)!=v for k,v in event.items() if k!='recorded_ms'):raise ValueError('DOSSIER_PENDING_CONFLICT')
            event=intent
        else:write_new(marker,encoded(event));marker.chmod(0o444)
        if evidence.exists():
            if release.digest(evidence)!=sha:raise ValueError('DOSSIER_EVIDENCE_CHANGED')
        else:write_new(evidence,data)
        path=root/'events'/f"{event['sequence']:06}.json";write_new(path,encoded(event));publish(root,head,path);marker.unlink();sync_directory(root/'state')
        return summarize(meta,[*events,event],release.digest(root/'state/head.json'))

def resume(root,expected):
    with locked(root) as root:
        meta,head,events,pending=load(root,True)
        if release.digest(root/'state/head.json')!=expected:raise ValueError('DOSSIER_CAS')
        current(meta)
        marker=root/'state/pending.json'
        if not marker.exists():raise ValueError('DOSSIER_NO_PENDING_EVENT')
        intent=read_json(marker)
        if pending:
            publish(root,head,root/'events'/f"{pending['sequence']:06}.json");events.append(pending)
        elif not events or intent!=events[-1]:
            raise ValueError('DOSSIER_PENDING_EVIDENCE_MISSING')
        marker.unlink();sync_directory(root/'state')
        return summarize(meta,events,release.digest(root/'state/head.json'))

def snapshot_identity(directory,profile):
    root=Path(directory)/'bundle/payload';props=dict(re.findall(r'^([^#\s=]+)=(.*)$',(root/'proxy/plugins/hearth-network/network.properties').read_text(),re.M))
    native,_=release.native_identity(root/'economy')
    return {'network':str(uuid.UUID(props['network-id'])),**native}

def rollback(root,expected,directory,receipt_sha):
    import network_recovery
    receipt,profile=network_recovery.verify(directory,receipt_sha)
    identity=snapshot_identity(directory,profile)
    with locked(root) as r:
        meta,head,events,pending=load(r,True)
        if identity!=meta['identity'] or not identity.get('realm') or not identity.get('group'):raise ValueError('DOSSIER_ROLLBACK_IDENTITY')
    data=encoded({'format':1,'source':str(backup.canonical_directory(directory)),'recovery_sha256':receipt_sha,'identity':identity})
    return append(root,expected,'rollback','PASS','verified-network-snapshot','paired-rollback',data)

def summarize(meta,events,head_sha):
    latest={e['kind']:e for e in events};states={k:latest[k]['outcome'] if k in latest else 'NOT_RUN' for k in (*KINDS,'rollback')}
    ready=all(v=='PASS' for v in states.values()) and bool(meta['identity'].get('realm')) and bool(meta['identity'].get('group'))
    return {'scope':meta['scope'],'identity':meta['identity'],'head_sha256':head_sha,'states':states,'ready_for_review':ready,'approval':'SEPARATE_REQUIRED','evidence_semantics':'operator reported results; artifact integrity checked'}

def status(root):
    with locked(root) as root:
        meta,head,events,pending=load(root,True);current(meta);result=summarize(meta,events,release.digest(root/'state/head.json'));result['pending_event']=(root/'state/pending.json').exists()
        if result['pending_event']:result['ready_for_review']=False
        return result

def certificate(root,expected,scope):
    with locked(root) as root:
        meta,head,events,pending=load(root)
        if release.digest(root/'state/head.json')!=expected:raise ValueError('DOSSIER_CAS')
        current(meta);summary=summarize(meta,events,expected)
        if (root/'state/pending.json').exists() or not summary['ready_for_review'] or scope!=meta['scope']:raise ValueError('DOSSIER_NOT_READY')
        last={e['kind']:e for e in events}
        import network_recovery
        pairing=read_json(root/'evidence'/last['rollback']['artifact']);_,profile=network_recovery.verify(pairing['source'],pairing['recovery_sha256'])
        if snapshot_identity(pairing['source'],profile)!=meta['identity']:raise ValueError('DOSSIER_ROLLBACK_IDENTITY')
        return {'scope':scope,'head':expected,'dossier':str(root),**{k:last[k]['artifact'] for k in (*KINDS,'rollback')}}

def seal(root,expected,destination):
    with locked(root) as r:
        meta,_,_,_=load(r);profile=current(meta)
    spec=copy.deepcopy(profile['review']);spec['acceptance']={k:True for k in KINDS}
    return release.generate(spec,destination,evidence=(root,expected))

def export(root,expected,destination,*,budget=None):
    with locked(root) as root:
        meta,head,events,pending=load(root)
        if release.digest(root/'state/head.json')!=expected or (root/'state/pending.json').exists():raise ValueError('DOSSIER_CAS_OR_PENDING')
        target=backup.fresh(destination);(target/'events').mkdir(mode=0o700);(target/'evidence').mkdir(mode=0o700)
        paths=[root/'dossier.json',root/'state/head.json',*[root/'events'/f"{e['sequence']:06}.json" for e in events],*[root/'evidence'/sha for sha in sorted({e['artifact'] for e in events})]]
        budget=budget or backup.Budget()
        for source in paths:
            relative=Path('head.json') if source==root/'state/head.json' else source.relative_to(root)
            backup.copy_file(source,target/relative,backup.fingerprint(backup.owned(source)),budget)
        return {'head_sha256':expected,'events':len(events),'services_started':False}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=('init','status','record','resume','rollback','seal'));p.add_argument('--candidate',type=Path);p.add_argument('--dossier',type=Path);p.add_argument('--output',type=Path);p.add_argument('--expected-head');p.add_argument('--kind',choices=KINDS);p.add_argument('--outcome',choices=('PASS','FAIL','NOT_RUN'));p.add_argument('--method');p.add_argument('--reference');p.add_argument('--artifact',type=Path);p.add_argument('--artifact-sha256');p.add_argument('--snapshot',type=Path);p.add_argument('--recovery-sha256');a=p.parse_args()
    try:
        if a.action=='init':r=initialize(a.candidate,a.output)
        elif a.action=='status':r=status(a.dossier)
        elif a.action=='record':r=add(a.dossier,a.expected_head,a.kind,a.outcome,a.method,a.reference,a.artifact,a.artifact_sha256)
        elif a.action=='resume':r=resume(a.dossier,a.expected_head)
        elif a.action=='rollback':r=rollback(a.dossier,a.expected_head,a.snapshot,a.recovery_sha256)
        else:r=seal(a.dossier,a.expected_head,a.output);r={'candidate_written':True,'scope':r['context']['scope'],'operating_applied':False}
        print(json.dumps(r,separators=(',',':')));return 0
    except (ValueError,KeyError,TypeError,OSError,backup.BackupError):print('NETWORK_ACCEPTANCE_REFUSED');return 2
if __name__=='__main__':raise SystemExit(main())
