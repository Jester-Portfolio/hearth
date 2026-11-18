#!/usr/bin/env python3
"""새 소유 복사본의 Velocity·로비·경제/PG 통합. 실제 Player/인증/전환 입력을 합성하지 않습니다."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import struct
import time
import uuid
import gzip
import isolated_paths
import network_config
from s5_ai_trial import Trial
from joint_ai import descendants

spec=importlib.util.spec_from_file_location('network_s45',Path(__file__).with_name('verify-s4-5.py'));s45=importlib.util.module_from_spec(spec);spec.loader.exec_module(s45)
VELOCITY_SHA='0844bcabdcdb4f5b875906fd4c3365bc1bb76918e42bc595ed88ad195258dd75'

class Monitor(s45.Monitor):
 def __init__(self,root,started):
  super().__init__(root,started,'HEARTH_NETWORK',550);self.identities={};self.combined=[];self.starts=0
 def launch(self,name,command,cwd):
  if name.startswith('paper'):
   self.starts+=1
   if self.starts>2:raise RuntimeError('PAPER_START_LIMIT')
  return super().launch(name,command,cwd)
 def sample(self):
  super().sample();rows={}
  for entry in Path('/proc').iterdir():
   if entry.name.isdigit():
    try:row=Trial.process(int(entry.name));rows[row['pid']]=row
    except (OSError,ValueError,IndexError):pass
  roots={item['proc'].pid for item in self.children.values() if item['proc'].poll() is None}
  tree=descendants(rows,roots)
  for pid in tree:self.identities[pid]=rows[pid]['start']
  total=sum(rows[pid]['rss_kib'] for pid in tree)
  self.combined.append({'seconds':round(time.monotonic()-self.started,3),'rss_kib':total})
  if total>6144*1024 and not self.stopping:raise RuntimeError('NETWORK_COMBINED_RSS_ABOVE_6_GIB')
  proxy=self.children.get('proxy')
  if proxy and proxy['proc'].pid in rows and rows[proxy['proc'].pid]['rss_kib']>512*1024 and not self.stopping:raise RuntimeError('PROXY_RSS_ABOVE_512_MIB')
 def pump(self):
  super().pump()
  if not self.stopping:
   for name,item in self.children.items():
    if any(any(code in line for code in ('HEARTH_ECONOMY_RUNTIME_HOLD','HEARTH_LOBBY_REFUSED','HEARTH_LOBBY_HOLD','HEARTH_NETWORK_PROXY_HOLD','HEARTH_NETWORK_BACKEND_PROBE FAIL')) for line in item['lines'][-30:]):raise RuntimeError('NETWORK_ROLE_HOLD_'+name)
 def stop(self,name,command,seconds=30):
  item=self.children[name];proc=item['proc']
  if proc.poll() is None:
   item['stop_at']=time.monotonic();self.send(name,command);until=time.monotonic()+seconds
   while proc.poll() is None and time.monotonic()<until:self.pump()
   if proc.poll() is None:raise RuntimeError('NORMAL_STOP_TIMEOUT_'+name)
  self.pump()
  if proc.returncode!=0:raise RuntimeError('NONZERO_EXIT_'+name)
  marker={'paper-lobby':'HEARTH_LOBBY_DISABLE_CLOSE PASS','paper-economy-runtime':'HEARTH_ECONOMY_DISABLE_CLOSE PASS','database':'HEARTH_NETWORK_DB_CLOSED'}.get(name)
  if marker and not any(marker in line for line in item['lines']):raise RuntimeError('CLEAN_CLOSE_MISSING_'+name)
 def finish(self):
  self.stopping=True;errors=[]
  for name in reversed(self.children):
   item=self.children[name];proc=item['proc']
   if proc.poll() is None:
    try:self.stop(name,'finish' if name=='database' else 'shutdown' if name=='proxy' else 'stop',max(.1,min(8,self.started+600-time.monotonic()-4)))
    except Exception as error:errors.append(type(error).__name__+':'+str(error))
   if proc.poll() is None:
    try:os.killpg(proc.pid,signal.SIGTERM);proc.wait(timeout=max(.1,min(3,self.started+600-time.monotonic())))
    except (OSError,TimeoutError):errors.append('OWNED_PROCESS_STOP_FAILED_'+name)
  for pid,start in self.identities.items():
   try:
    if Trial.process(pid)['start']==start:errors.append('OWNED_PROCESS_REMAINS')
   except (OSError,ValueError,IndexError):pass
  for name,item in self.children.items():item['log'].close()
  self.selector.close();return errors

def copy_tree(source,target,guard):
 # 완료한 비활성 격리 원본만 읽습니다. 링크를 따라 다른 데이터/비공개 저장소를 읽지 않습니다.
 def checked(src,dst):
  guard();p=Path(src)
  if p.is_symlink() or not p.is_file():raise ValueError('SOURCE_LINK_OR_FILE_TYPE')
  return shutil.copy2(src,dst)
 for path in source.rglob('*'):
  guard()
  if path.is_symlink():raise ValueError('SOURCE_LINK')
 shutil.copytree(source,target,copy_function=checked,ignore=shutil.ignore_patterns('logs','session.lock','postmaster.pid','*.active'))

def port():
 with socket.socket() as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def metadata_uuid(path):
 data=path.read_bytes()
 if len(data)>65536:raise ValueError('METADATA_LIMIT')
 if data[:2]==b'\x1f\x8b':data=gzip.decompress(data)
 tag=b'\x0b\x00\x04uuid\x00\x00\x00\x04'
 if data.count(tag)!=1:raise ValueError('METADATA_UUID_FORMAT')
 pos=data.index(tag)+len(tag);return str(uuid.UUID(bytes=data[pos:pos+16]))
def varint(n):
 data=bytearray()
 while n>127:data.append((n&127)|128);n>>=7
 data.append(n);return bytes(data)
def read_varint(stream):
 n=0
 for shift in range(0,35,7):
  b=stream.read(1)
  if not b:raise ValueError('PING_EOF')
  n|=(b[0]&127)<<shift
  if b[0]<128:return n
 raise ValueError('PING_VARINT')
def ping(port_number):
 with socket.create_connection(('127.0.0.1',port_number),timeout=3) as sock:
  host=b'localhost';hello=b'\x00'+varint(773)+varint(len(host))+host+struct.pack('>H',port_number)+b'\x01';sock.sendall(varint(len(hello))+hello+b'\x01\x00')
  with sock.makefile('rb') as stream:
   length=read_varint(stream)
   if length>65536:raise ValueError('PING_SIZE')
   if read_varint(stream)!=0:raise ValueError('PING_PACKET')
   size=read_varint(stream);body=stream.read(size)
   if len(body)!=size:raise ValueError('PING_TRUNCATED')
   return json.loads(body)

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--workspace',type=Path,required=True);p.add_argument('--java',type=Path,required=True);p.add_argument('--plugin',type=Path,required=True);p.add_argument('--velocity',type=Path,required=True);p.add_argument('--prepared-source',type=Path,required=True);p.add_argument('--accept-eula',action='store_true');a=p.parse_args();root=isolated_paths.workspace(a.workspace,'hearth-network-run.');os.sched_setaffinity(0,sorted(os.sched_getaffinity(0))[:2]);started=time.monotonic();m=Monitor(root,started);failure=None;cleanup=[];checks=[];outcome='FAIL';ports={}
 try:
  if not a.accept_eula or (root/'.hearth-network-isolated').read_text()!='Hearth network isolated v1\n':raise ValueError('NETWORK_FIXTURE_GATE')
  source=isolated_paths.workspace(a.prepared_source,'hearth-s4-11.');result=json.loads((source/'run-result.json').read_text())
  if result['outcome']!='PASS' or result['cleanup_errors'] or not result['all_owned_processes_and_ports_closed'] or (source/'data/postmaster.pid').exists() or list((source/'server/plugins/Hearth').glob('*.active')):raise ValueError('INACTIVE_PASSED_SOURCE_REQUIRED')
  if digest(a.velocity)!=VELOCITY_SHA:raise ValueError('VELOCITY_HASH')
  plugin_sha=digest(a.plugin)
  baseline=[source/'server/plugins/Hearth/economy-world.properties',source/'server/plugins/Hearth/config.yml',source/'server/world/dimensions/hearth_verify/economy_fixture/data/paper/metadata.dat',source/'data/global/pg_control'];original={str(f):digest(f) for f in baseline}
  copy_tree(source/'data',root/'data',m.guard)
  for role in ('lobby','economy'):copy_tree(source/'server',root/role,m.guard);shutil.copy2(a.plugin,root/role/'plugins/Hearth.jar')
  mapping=root/'economy/plugins/Hearth/economy-world.properties'
  mapping.write_text(re.sub(r'(?m)^directory=.*$', 'directory='+str(root/'economy/world/dimensions/hearth_verify/economy_fixture'),mapping.read_text()))
  shutil.copy2(source/'paper-26.2-134.jar',root/'paper.jar');shutil.copy2(a.velocity,root/'velocity.jar')
  for role in ('proxy','lobby','economy'):
   value=port()
   while value in ports.values():value=port()
   ports[role]=value
  helper=[str(a.java),'-Xmx128m','-XX:ActiveProcessorCount=2','-Djava.io.tmpdir='+str(root),'-cp',str(root/'fixture-classes')+':'+str(root/'resolved-jars/*')+':'+str(a.plugin),'io.github.jesterportfolio.hearth.persistence.NetworkDatabaseFixture',str(root)]
  m.launch('database',helper,root);m.wait('database','HEARTH_NETWORK_DB_READY',seconds=40);db_port=int((root/'db-port.txt').read_text())
  economy=(root/'economy/plugins/Hearth/config.yml').read_text();economy=re.sub(r'(?m)^  db-port: [0-9]+$',f'  db-port: {db_port}',economy);economy=re.sub(r'(?m)^runtime-observation-seconds:.*\n','',economy)+'\nruntime-observation-seconds: 5\n'
  lobby={'world-uuid':metadata_uuid(root/'lobby/world/dimensions/minecraft/overworld/data/paper/metadata.dat'),'world-key':'minecraft:overworld','region':'lobby','entry-x':2.5,'entry-y':-63,'entry-z':2.5,'entry-yaw':0,'entry-pitch':0}
  bundle=root/'config-bundle';network_config.generate(bundle,uuid.uuid4(),ports,lobby,economy,4)
  os.environ['HEARTH_VELOCITY_SECRET']=(bundle/'secrets/forwarding.secret').read_text().strip();os.environ['HEARTH_NETWORK_SECRET']=(bundle/'secrets/relay.secret').read_text().strip();os.environ['HEARTH_ISOLATED_ECONOMY_PASSWORD']='isolated-not-a-secret'
  for role in ('lobby','economy'):
   shutil.copy2(bundle/role/'plugins/Hearth/config.yml',root/role/'plugins/Hearth/config.yml')
   global_config=root/role/'config/paper-global.yml';global_config.write_text(network_config.section(global_config.read_text(),'proxies',(bundle/'paper-global.fragment.yml').read_text()))
   with (root/role/'server.properties').open('a') as f:f.write('\n'+(bundle/role/'server.properties.fragment').read_text())
  region=root/'lobby/plugins/WorldGuard/worlds/world/regions.yml';region.parent.mkdir(parents=True,exist_ok=True);region.write_text('regions:\n  lobby:\n    min: {x: 0, y: -64, z: 0}\n    max: {x: 15, y: -50, z: 15}\n    members: {}\n    flags: {build: deny, pvp: deny, mob-spawning: deny}\n    owners: {}\n    type: cuboid\n    priority: 0\n')
  shutil.copytree(bundle/'proxy',root/'proxy');(root/'proxy/plugins').mkdir(exist_ok=True);shutil.copy2(a.plugin,root/'proxy/plugins/Hearth.jar')
  proxy=[str(a.java),'-Xms64m','-Xmx256m','-XX:ActiveProcessorCount=2','-Djava.io.tmpdir='+str(root),'-jar',str(root/'velocity.jar')]
  m.launch('proxy',proxy,root/'proxy');m.wait('proxy','HEARTH_NETWORK_PROXY_READY',seconds=40)
  for role in ('lobby','economy'):
   name='paper-lobby' if role=='lobby' else 'paper-economy-runtime';command=[str(a.java),'-Xms256m','-Xmx1536m','-XX:ActiveProcessorCount=2','-Djava.io.tmpdir='+str(root),'-Dhearth.network.verification.root='+str(root),'-jar',str(root/'paper.jar'),'--nogui'];m.launch(name,command,root/role)
  m.wait('paper-lobby','HEARTH_LOBBY_READY',seconds=120);m.wait('paper-economy-runtime','HEARTH_ECONOMY_RUNTIME_READY',seconds=120);checks.append('velocity_and_two_actual_paper_roles_ready_with_modern_forwarding')
  status=ping(ports['proxy']);assert 'version' in status and 'players' in status;checks.append('actual_velocity_server_list_status_without_login_or_fake_player')
  m.send('paper-economy-runtime','heartheconomy network-verify');m.wait('paper-economy-runtime','HEARTH_NETWORK_BACKEND_PROBE PASS',seconds=45)
  checks += [line.split('HEARTH_NETWORK_CHECK PASS ',1)[1] for line in m.children['paper-economy-runtime']['lines'] if 'HEARTH_NETWORK_CHECK PASS ' in line]
  m.send('proxy','velocity reload');m.wait('proxy','HEARTH_NETWORK_PROXY_RELOAD_HOLD',seconds=10);checks.append('actual_proxy_reload_closes_network_admissions_and_switch_gate')
  m.send('paper-economy-runtime','heartheconomy drain');m.wait('paper-economy-runtime','HEARTH_ECONOMY_RUNTIME_DRAINED PASS',seconds=45);m.stop('paper-economy-runtime','stop');m.stop('paper-lobby','stop');m.stop('proxy','shutdown');m.send('database','compare');m.wait('database','HEARTH_NETWORK_DB_COMPARE_PASS',seconds=10);m.stop('database','finish')
  assert all(digest(Path(f))==sha for f,sha in original.items());assert digest(a.plugin)==plugin_sha and all(digest(root/role/'plugins/Hearth.jar')==plugin_sha for role in ('proxy','lobby','economy'));checks.append('read_only_source_config_metadata_and_original_cluster_control_preserved');outcome='PASS'
 except Exception as e:failure=type(e).__name__+': '+str(e);print('NETWORK FAIL',failure,flush=True)
 finally:
  cleanup=m.finish()
  for value in ports.values():
   with socket.socket() as s:
    try:s.bind(('127.0.0.1',value))
    except OSError:cleanup.append('OWNED_PORT_REMAINS')
  if cleanup:outcome='FAIL'
  report=dict(outcome=outcome,failure=failure,cleanup_errors=cleanup,duration_seconds=round(time.monotonic()-started,3),checks=checks,processes=m.summary(),combined_samples=m.combined,plugin_sha256=digest(a.plugin),velocity_sha256=digest(a.velocity),limits={'total_seconds':600,'work_seconds':550,'cpu':2,'combined_rss_stop_mib':6144,'paper_starts_max':2,'paper_heap_mib':1536,'paper_rss_stop_mib':2560,'proxy_heap_mib':256,'proxy_rss_stop_mib':512,'host_available_min_mib':6144},actual_client='NOT_RUN',actual_authenticated_switch='NOT_RUN',operating_changes='NONE');(root/'network-result.json').write_text(json.dumps(report,indent=2)+'\n');print('NETWORK',outcome,'evidence:',root/'network-result.json',flush=True)
 return 0 if outcome=='PASS' else 1
if __name__=='__main__':raise SystemExit(main())
