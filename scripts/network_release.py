#!/usr/bin/env python3
"""준비된 네트워크의 불변 파일/예산을 묶는 오프라인 배포 후보 생성·검증. 서비스를 실행하지 않는다."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import uuid

ROLES = ('proxy', 'lobby', 'economy', 'postgres')

def regular(path):
    path = Path(path).absolute()
    if path != path.resolve(strict=True) or not path.is_file():
        raise ValueError('RELEASE_PATH')
    return path

def digest(path):
    h = hashlib.sha256()
    with regular(path).open('rb') as f:
        for b in iter(lambda: f.read(1048576), b''):
            h.update(b)
    return h.hexdigest()

def proxy_settings(text):
    # Hearth 생성기의 scalar TOML만 읽는다. 일반 TOML 편집기/추측 복구를 제공하지 않는다.
    result={};section='';seen=set()
    for line in text.splitlines():
        line=line.strip()
        if not line or line.startswith('#'):continue
        if line.startswith('['):section=line.strip('[]');continue
        if '=' not in line:raise ValueError('RELEASE_PROXY_TOML')
        key,value=(v.strip() for v in line.split('=',1))
        if (section,key) in seen:raise ValueError('RELEASE_PROXY_DUPLICATE')
        seen.add((section,key))
        if (not section and key in ('bind','online-mode','player-info-forwarding-mode')) or section=='servers' and key in ('lobby','economy'):
            parsed=json.loads(value)
            if section:result.setdefault(section,{})[key]=parsed
            else:result[key]=parsed
    return result

def yaml_fields(text):
    # 보안 관련 mapping의 정확한 경로를 비교한다. YAML anchor/flow/list를 추측하지 않는다.
    stack=[];result={}
    for raw in text.splitlines():
        if not raw.strip() or raw.lstrip().startswith('#'):continue
        match=re.fullmatch(r'( *)([a-zA-Z0-9_-]+):(?: *(.*))?',raw)
        if not match:
            if stack and stack[0][1] in ('proxies','network'):raise ValueError('RELEASE_SECURITY_YAML')
            continue
        indent=len(match[1]);key=match[2];value=(match[3] or '').strip()
        while stack and stack[-1][0]>=indent:stack.pop()
        path=tuple(k for _,k in stack)+(key,)
        if path in result:raise ValueError('RELEASE_DUPLICATE_YAML')
        result[path]=value
        if not value:stack.append((indent,key))
    return result

def read_env(path):
    raw=regular(path).read_bytes()
    if len(raw)>16384 or any(b not in (10,) and not 32<=b<=126 for b in raw):raise ValueError('RELEASE_ENV_ENCODING')
    values={}
    for line in raw.decode('ascii').split('\n'):
        if not line or line.startswith('#'):continue
        if '=' not in line:raise ValueError('RELEASE_ENV_FORMAT')
        key,value=line.split('=',1)
        if key in values:raise ValueError('RELEASE_ENV_DUPLICATE')
        values[key]=value
    if set(values)!={'HEARTH_VELOCITY_SECRET','HEARTH_NETWORK_SECRET','HEARTH_DB_PASSWORD'} or any(not v for v in values.values()) or not re.fullmatch('[a-f0-9]{64}',values['HEARTH_VELOCITY_SECRET']) or not re.fullmatch('[a-f0-9]{64}',values['HEARTH_NETWORK_SECRET']):raise ValueError('RELEASE_SECRETS')
    return values

def review(spec):
    if set(spec) != {'format','host_cpu','host_memory_mib','services','backup','acceptance'} or spec['format'] != 1:
        raise ValueError('RELEASE_SCHEMA')
    if set(spec['services']) != set(ROLES):
        raise ValueError('RELEASE_ROLES')
    roots, ports, cpus, memory = [], [], 0, 0
    files = {}
    for role, s in spec['services'].items():
        if set(s) != {'data','image','cpu','memory_mib','heap_mib','port','uid','gid','jar','inputs','stop_seconds','pids'}:
            raise ValueError('RELEASE_SERVICE_SCHEMA')
        root = Path(s['data']).absolute()
        if root != root.resolve(strict=True) or not root.is_dir() or 'hearth-story-private' in root.parts:
            raise ValueError('RELEASE_ROOT')
        if any(root == r or root in r.parents or r in root.parents for r in roots):
            raise ValueError('RELEASE_OVERLAPPING_DATA')
        roots.append(root)
        if not re.fullmatch(r'[a-zA-Z0-9./:_-]+@sha256:[a-f0-9]{64}', s['image']):
            raise ValueError('RELEASE_PINNED_IMAGE_REQUIRED')
        if type(s['cpu']) not in (int,float) or not 0 < s['cpu'] <= 128:
            raise ValueError('RELEASE_CPU')
        for key in ('memory_mib','heap_mib','port','uid','gid','stop_seconds','pids'):
            if type(s[key]) is not int or s[key] < (0 if key == 'heap_mib' else 1):
                raise ValueError('RELEASE_BUDGET')
        if s['memory_mib'] < 128 or not 1024 <= s['port'] <= 65535 or s['heap_mib'] >= s['memory_mib'] or role != 'postgres' and not s['heap_mib']:
            raise ValueError('RELEASE_BUDGET')
        cpus += s['cpu']; memory += s['memory_mib']; ports.append(s['port'])
        required = {'proxy': {'velocity.toml','forwarding.secret','plugins/hearth-network/network.properties','plugins/Hearth.jar','runtime.env'},
                    'lobby': {'server.properties','plugins/Hearth/config.yml','config/paper-global.yml','plugins/Hearth.jar','runtime.env','eula.txt'},
                    'economy': {'server.properties','plugins/Hearth/config.yml','config/paper-global.yml','plugins/Hearth.jar','runtime.env','eula.txt'},
                    'postgres': {'PG_VERSION','postgresql.conf'}}[role]
        if not isinstance(s['inputs'], list) or not required <= set(s['inputs']) or len(s['inputs']) > 128:
            raise ValueError('RELEASE_INPUTS')
        names = s['inputs'] + ([s['jar']] if role != 'postgres' else [])
        if len(names)>128 or len(names)!=len(set(names)):
            raise ValueError('RELEASE_INPUT_LIMIT')
        if role == 'postgres' and s['jar'] != '':
            raise ValueError('RELEASE_POSTGRES_JAR')
        for name in names:
            if not isinstance(name,str) or not re.fullmatch('[A-Za-z0-9_./-]+',name) or Path(name).is_absolute() or '..' in Path(name).parts or not name:
                raise ValueError('RELEASE_INPUT_PATH')
            files[str(root/name)] = digest(root/name)
        # No active Paper/PG data may become an offline candidate.
        if (root/'postmaster.pid').exists() or list(root.glob('plugins/Hearth/*.active')):
            raise ValueError('RELEASE_STOPPED_DATA_REQUIRED')
    if len(set(ports)) != 4 or type(spec['host_cpu']) not in (int,float) or not 0 < cpus <= spec['host_cpu'] or type(spec['host_memory_mib']) is not int or not 0 < memory < spec['host_memory_mib']:
        raise ValueError('RELEASE_HOST_BUDGET_OR_PORT')
    proxy = spec['services']['proxy']; config = proxy_settings((Path(proxy['data'])/'velocity.toml').read_text())
    if config.get('online-mode') is not True or config.get('player-info-forwarding-mode') != 'modern' or config.get('bind') != '127.0.0.1:'+str(proxy['port']):
        raise ValueError('RELEASE_PROXY_AUTH')
    tokens=[]
    jars=[]
    for role in ('proxy','lobby','economy'):
        root=Path(spec['services'][role]['data']);env=root/'runtime.env'
        if env.stat().st_mode & 0o077:raise ValueError('RELEASE_SECRET_MODE')
        values=read_env(env)
        tokens.append(values);jars.append(digest(root/'plugins/Hearth.jar'))
    if tokens[0] != tokens[1] or tokens[0] != tokens[2] or len(set(jars)) != 1 or (Path(proxy['data'])/'forwarding.secret').read_text().strip() != tokens[0]['HEARTH_VELOCITY_SECRET']:
        raise ValueError('RELEASE_NETWORK_IDENTITY')
    for role in ('lobby','economy'):
        s = spec['services'][role]; root = Path(s['data'])
        props = dict(re.findall(r'^([^#\s=]+)=(.*)$',(root/'server.properties').read_text(),re.M))
        if props.get('server-ip') != '127.0.0.1' or props.get('server-port') != str(s['port']) or props.get('online-mode') != 'false' or config.get('servers',{}).get(role) != '127.0.0.1:'+str(s['port']):
            raise ValueError('RELEASE_BACKEND_BINDING')
        if not re.search(r'^runtime-mode: '+('lobby' if role=='lobby' else 'survival')+r'\s*$',(root/'plugins/Hearth/config.yml').read_text(),re.M):
            raise ValueError('RELEASE_BACKEND_MODE')
        fields=yaml_fields((root/'config/paper-global.yml').read_text())
        required={('proxies','velocity','enabled'):'true',('proxies','velocity','online-mode'):'true',('proxies','velocity','secret'):tokens[0]['HEARTH_VELOCITY_SECRET']}
        if any((fields.get(k,'').strip('\"\'') if k[-1]=='secret' else fields.get(k,'')) != value for k,value in required.items()) or (root/'eula.txt').read_text().strip() != 'eula=true':
            raise ValueError('RELEASE_FORWARDING_OR_EULA')
        cfg=yaml_fields((root/'plugins/Hearth/config.yml').read_text())
        proxy_props=dict(re.findall(r'^([^#\s=]+)=(.*)$',(Path(proxy['data'])/'plugins/hearth-network/network.properties').read_text(),re.M))
        expected={('network','enabled'):'true',('network','server-id'):role,('network','network-id'):proxy_props.get('network-id',''),('network','forwarding-secret-environment'):'HEARTH_VELOCITY_SECRET',('network','relay-secret-environment'):'HEARTH_NETWORK_SECRET'}
        if str(uuid.UUID(proxy_props.get('network-id',''))) != proxy_props.get('network-id') or any(cfg.get(k) != value for k,value in expected.items()):
            raise ValueError('RELEASE_HEARTH_AUTH')
    backup=spec['backup']
    if set(backup) != {'interval_seconds','retain','offsite','rpo_seconds','rto_seconds'} or any(type(backup[k]) is not int or backup[k] < 1 for k in ('interval_seconds','retain','rpo_seconds','rto_seconds')) or backup['interval_seconds'] > backup['rpo_seconds'] or not isinstance(backup['offsite'],str) or not backup['offsite']:
        raise ValueError('RELEASE_BACKUP_POLICY')
    if set(spec['acceptance']) != {'client','load','restore','release_regression'} or any(type(v) is not bool for v in spec['acceptance'].values()):
        raise ValueError('RELEASE_ACCEPTANCE')
    return files

ADAPTERS=('network-entrypoint.sh','postgres-entrypoint.sh','network-postgres-entrypoint.sh','runtime-env.sh','NetworkLaunchCheck.java')
ADAPTER_PATHS=('infrastructure/deployment/network-entrypoint.sh','infrastructure/deployment/postgres-entrypoint.sh','infrastructure/deployment/network-postgres-entrypoint.sh','infrastructure/deployment/runtime-env.sh','src/main/java/io/github/jesterportfolio/hearth/administration/NetworkLaunchCheck.java')

def native_identity(root):
    path=Path(root)/'plugins/Hearth/native-economy.properties'
    if not path.exists():return {'realm':None,'group':None},None
    path=regular(path)
    if path.stat().st_size>4096:raise ValueError('RELEASE_BINDING_LIMIT')
    values=dict(re.findall(r'^([^#\s=]+)=(.*)$',path.read_text(),re.M));identity={k:str(uuid.UUID(values[k])) for k in ('realm','group')}
    return identity,digest(path)

def make_context(spec,files,adapters):
    normalized=json.loads(json.dumps(spec));normalized['acceptance']={k:False for k in normalized['acceptance']}
    props=dict(re.findall(r'^([^#\s=]+)=(.*)$',(Path(spec['services']['proxy']['data'])/'plugins/hearth-network/network.properties').read_text(),re.M))
    native,binding=native_identity(spec['services']['economy']['data']);identity={'network':str(uuid.UUID(props['network-id'])),**native}
    body={'review':normalized,'files':files,'adapters':adapters,'identity':identity,'binding':binding}
    scope=hashlib.sha256(json.dumps(body,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    return {'scope':scope,'identity':identity,'binding':binding}

def context(profile):return make_context(profile['review'],profile['files'],{k:profile['artifacts'][k] for k in ADAPTERS})

def certificate_bytes(context,value=None):
    keys=('client','load','restore','release_regression','rollback')
    lines=['format=1','scope='+context['scope'],'complete='+str(value is not None).lower(),'head='+(value['head'] if value else '0'*64)]
    lines += [k+'='+(value[k] if value else '0'*64) for k in keys]
    return ('\n'.join(lines)+'\n').encode()

def generate(spec, output, *, evidence=None):
    files=review(spec);repository=Path(__file__).resolve().parent.parent
    adapters={Path(k).name:digest(repository/k) for k in ADAPTER_PATHS};scope=make_context(spec,files,adapters)
    complete=all(spec['acceptance'].values());certificate=None
    if complete:
        if evidence is None:raise ValueError('RELEASE_ACCEPTANCE_EVIDENCE_REQUIRED')
        import network_acceptance
        certificate=network_acceptance.certificate(evidence[0],evidence[1],scope['scope'])
    cert=certificate_bytes(scope,certificate)
    output=Path(output).absolute()
    if output.exists() or output.is_symlink() or output.parent != output.parent.resolve(strict=True):
        raise ValueError('RELEASE_FRESH_OUTPUT')
    # Candidate only. Missing acceptance gates stay explicit and block launch review.
    result={'format':3,'status':'CANDIDATE','review':spec,'files':files,'acceptance_complete':complete,'context':scope,'attestation':({'dossier':certificate['dossier'],'head':certificate['head']} if certificate else None)}
    output.mkdir(mode=0o700)
    (output/'acceptance.properties').write_bytes(cert)
    services={}
    for role,s in spec['services'].items():
        services[role]={'image':s['image'],'user':f"{s['uid']}:{s['gid']}",'network_mode':'host','read_only':True,'restart':'no','cpus':s['cpu'],'mem_limit':str(s['memory_mib'])+'m','stop_grace_period':str(s['stop_seconds'])+'s','pids_limit':s['pids'],'cap_drop':['ALL'],'security_opt':['no-new-privileges:true'],
          'working_dir':'/data','volumes':[{'type':'bind','source':s['data'],'target':'/data','bind':{'create_host_path':False}}],
          'environment':{'HEARTH_NETWORK_APPROVAL':'${HEARTH_NETWORK_APPROVAL:?separate operating approval required}'}}
        manifest='format=3\nrole='+role+'\nacceptance-complete='+str(result['acceptance_complete']).lower()+'\nscope='+scope['scope']+'\nacceptance-sha256='+hashlib.sha256(cert).hexdigest()+'\njar='+s['jar']+'\n'
        for path,sha in files.items():
            try:relative=Path(path).relative_to(Path(s['data']))
            except ValueError:continue
            manifest+='file.'+str(relative)+'='+sha+'\n'
        (output/(role+'.properties')).write_text(manifest)
        services[role]['environment'].update(HEARTH_ROLE=role,HEARTH_LAUNCH_SHA256=hashlib.sha256(manifest.encode()).hexdigest())
        if role=='postgres':
            services[role]['entrypoint']=['/bin/sh','/review/network-postgres-entrypoint.sh']
            services[role]['environment'].update(HEARTH_OPERATING_APPROVAL='${HEARTH_NETWORK_APPROVAL:?separate operating approval required}',HEARTH_PG_PORT=str(s['port']),PGDATA='/data')
            launch_files=['network-postgres-entrypoint.sh','postgres-entrypoint.sh','runtime-env.sh','postgres.properties']
        else:
            services[role]['entrypoint']=['/bin/sh','/review/network-entrypoint.sh']
            services[role]['environment'].update(HEARTH_HEAP_MIB=str(s['heap_mib']),HEARTH_CPUS=str(max(1,int(s['cpu']))))
            launch_files=['network-entrypoint.sh','runtime-env.sh','NetworkLaunchCheck.java',role+'.properties']
        if certificate:
            services[role]['volumes'].append({'type':'bind','source':str(Path(certificate['dossier'])/'state'),'target':'/review/acceptance-state','read_only':True,'bind':{'create_host_path':False}})
        launch_files.append('acceptance.properties')
        for filename in launch_files:
            services[role]['volumes'].append({'type':'bind','source':str(output/filename),'target':'/review/'+filename,'read_only':True,'bind':{'create_host_path':False}})
        services[role]['tmpfs']=[f"/run/hearth:rw,nosuid,nodev,noexec,size=128m,uid={s['uid']},gid={s['gid']},mode=0700"]
    services['postgres']['healthcheck']={'test':['CMD','pg_isready','-h','127.0.0.1','-p',str(spec['services']['postgres']['port'])],'interval':'10s','timeout':'3s','retries':3}
    for role in ('lobby','economy'):
        services[role]['depends_on']={'postgres':{'condition':'service_healthy'}}
    services['proxy']['depends_on']={role:{'condition':'service_started'} for role in ('lobby','economy')}
    for source in ADAPTER_PATHS:
        shutil.copy2(repository/source,output/Path(source).name)
    if any(digest(output/name)!=sha for name,sha in adapters.items()):raise ValueError('RELEASE_ADAPTER_CHANGED')
    (output/'compose.json').write_text(json.dumps({'name':'hearth-'+re.search(r'(?m)^network-id=([a-f0-9-]{36})$',(Path(spec['services']['proxy']['data'])/'plugins/hearth-network/network.properties').read_text()).group(1).replace('-',''),'services':services},indent=2)+'\n')
    for artifact in output.iterdir():
        if artifact.is_file():artifact.chmod(0o444)
    (output/'backup-policy.json').write_text(json.dumps({'format':1,'policy':spec['backup'],'contract':'stopped full Paper + full PostgreSQL, restore together','executor':'scripts/offline_backup.py','automatic_stop':False},indent=2)+'\n')
    result['artifacts']={p.name:digest(p) for p in output.iterdir() if p.is_file()}
    (output/'release.json').write_text(json.dumps(result,indent=2)+'\n');(output/'release.json').chmod(0o444)
    return result

def artifacts(manifest):
    result=json.loads(regular(manifest).read_text())
    names={'compose.json','backup-policy.json','proxy.properties','lobby.properties','economy.properties','network-entrypoint.sh','postgres-entrypoint.sh','NetworkLaunchCheck.java'}
    if result.get('format') in (2,3):names|={'postgres.properties','network-postgres-entrypoint.sh','runtime-env.sh'}
    if result.get('format')==3:names|={'acceptance.properties'}
    fields={'format','status','review','files','acceptance_complete','artifacts'}|({'context','attestation'} if result.get('format')==3 else set())
    if set(result)!=fields or result['format'] not in (1,2,3) or result['status']!='CANDIDATE' or set(result['artifacts'])!=names or any(digest(Path(manifest).parent/name)!=result['artifacts'][name] for name in names):raise ValueError('RELEASE_ARTIFACT_CHANGED')
    if result['acceptance_complete']!=all(result['review']['acceptance'].values()):raise ValueError('RELEASE_ACCEPTANCE')
    return result

def inspect(manifest):
    result=artifacts(manifest)
    if result['files']!=review(result['review']):raise ValueError('RELEASE_CHANGED')
    if result['format']==3:
        if result['context']!=context(result):raise ValueError('RELEASE_SCOPE_CHANGED')
        if result['acceptance_complete']:
            a=result['attestation'];root=Path(a['dossier']);head=root/'state/head.json'
            if (root/'state/pending.json').exists() or digest(head)!=a['head']:raise ValueError('RELEASE_ACCEPTANCE_HEAD_CHANGED')
        elif result['attestation'] is not None:raise ValueError('RELEASE_UNEXPECTED_ATTESTATION')
    return {'unchanged':True,'acceptance_complete':result['acceptance_complete'],'operating_approval':'SEPARATE_REQUIRED'}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=('plan','inspect'));p.add_argument('input',type=Path);p.add_argument('--output',type=Path);a=p.parse_args()
    try:
        if a.action=='plan':
            if a.output is None:raise ValueError('RELEASE_OUTPUT_REQUIRED')
            generate(json.loads(regular(a.input).read_text()),a.output);print('RELEASE_CANDIDATE_WRITTEN')
        else:print(json.dumps(inspect(a.input),separators=(',',':')))
        return 0
    except (ValueError,OSError,KeyError,TypeError):
        print('RELEASE_REVIEW_REFUSED');return 2
if __name__=='__main__':raise SystemExit(main())
