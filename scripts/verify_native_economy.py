#!/usr/bin/env python3
"""격리 복사 PG·생존 Paper 두 JVM의 경제 연결 검사. 실제 Player/클릭은 합성하지 않습니다."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import socket
import time
import uuid
import isolated_paths
from verify_network import Monitor,copy_tree,digest,port

class NativeMonitor(Monitor):
 def pump(self):
  super().pump()
  if not self.stopping:
   for name,item in self.children.items():
    if any(any(code in line for code in ('HEARTH_SURVIVAL_HOLD','HEARTH_SURVIVAL_REFUSED','HEARTH_SURVIVAL_ECONOMY_HOLD','HEARTH_NATIVE_ECONOMY_PROBE FAIL')) for line in item['lines'][-40:]):raise RuntimeError('NATIVE_ROLE_HOLD_'+name)
 def stop(self,name,command,seconds=30):
  item=self.children[name];proc=item['proc']
  if proc.poll() is None:
   item['stop_at']=time.monotonic();self.send(name,command);until=time.monotonic()+seconds
   while proc.poll() is None and time.monotonic()<until:self.pump()
   if proc.poll() is None:raise RuntimeError('NORMAL_STOP_TIMEOUT_'+name)
  self.pump()
  if proc.returncode!=0:raise RuntimeError('NONZERO_EXIT_'+name)
  markers=['HEARTH_NATIVE_DB_CLOSED'] if name=='database' else ['HEARTH_SURVIVAL_DISABLE_CLOSE PASS','HEARTH_SURVIVAL_ECONOMY_CLOSE PASS']
  if not self.stopping and not all(any(token in line for line in item['lines']) for token in markers):raise RuntimeError('CLEAN_CLOSE_MISSING_'+name)

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--workspace',type=Path,required=True);p.add_argument('--java',type=Path,required=True);p.add_argument('--plugin',type=Path,required=True);p.add_argument('--prepared-worlds',type=Path);p.add_argument('--prepared-database',type=Path);p.add_argument('--restore-source',type=Path);p.add_argument('--accept-eula',action='store_true');a=p.parse_args();root=isolated_paths.workspace(a.workspace,'hearth-native-economy-run.');os.sched_setaffinity(0,sorted(os.sched_getaffinity(0))[:2]);started=time.monotonic();m=NativeMonitor(root,started);error=None;cleanup=[];checks=[];outcome='FAIL';ports=[]
 try:
  if not a.accept_eula or (root/'.hearth-native-economy-isolated').read_text()!='Hearth native economy isolated v1\n':raise ValueError('NATIVE_FIXTURE_GATE')
  restore=a.restore_source is not None
  if restore:
   worlds=database=isolated_paths.workspace(a.restore_source,'hearth-native-economy-run.');passed=json.loads((worlds/'native-economy-result.json').read_text())
   if passed['outcome']!='PASS' or passed['cleanup_errors'] or (database/'data/postmaster.pid').exists() or list((worlds/'server/plugins/Hearth').glob('*.active')):raise ValueError('INACTIVE_PASSED_NATIVE_SOURCE_REQUIRED')
   originals=[worlds/'server/plugins/Hearth/config.yml',worlds/'server/plugins/Hearth/survival-worlds.properties',worlds/'server/plugins/Hearth/native-economy.properties',worlds/'server/world/dimensions/minecraft/overworld/data/paper/metadata.dat',database/'data/global/pg_control',worlds/'native-expected.properties']
  else:
   worlds=isolated_paths.workspace(a.prepared_worlds,'hearth-survival-run.');world_result=json.loads((worlds/'final-restore-result.json').read_text());database=isolated_paths.workspace(a.prepared_database,'hearth-s4-11.');db_result=json.loads((database/'run-result.json').read_text())
   if world_result['outcome']!='PASS' or world_result['cleanup_errors'] or db_result['outcome']!='PASS' or db_result['cleanup_errors'] or not db_result['all_owned_processes_and_ports_closed'] or (database/'data/postmaster.pid').exists() or list((worlds/'server/plugins/Hearth').glob('*.active')):raise ValueError('INACTIVE_PASSED_SOURCE_REQUIRED')
   originals=[worlds/'server/plugins/Hearth/config.yml',worlds/'server/plugins/Hearth/survival-worlds.properties',worlds/'server/world/dimensions/minecraft/overworld/data/paper/metadata.dat',database/'server/plugins/Hearth/economy-world.properties',database/'data/global/pg_control']
  before={str(f):digest(f) for f in originals};plugin_sha=digest(a.plugin)
  copy_tree(worlds/'server',root/'server',m.guard);copy_tree(database/'data',root/'data',m.guard);shutil.copy2(worlds/'paper.jar',root/'paper.jar');shutil.copy2(a.plugin,root/'server/plugins/Hearth.jar')
  config=root/'server/plugins/Hearth/config.yml'
  if restore:
   match=re.search(r'(?m)^  realm: ([a-f0-9-]{36})$',config.read_text())
   if not match:raise ValueError('NATIVE_RESTORE_REALM')
   (root/'restore-realm.txt').write_text(match.group(1)+'\n');shutil.copy2(worlds/'native-expected.properties',root/'native-expected.properties')
  helper=[str(a.java),'-Xmx128m','-XX:ActiveProcessorCount=2','-Djava.io.tmpdir='+str(root),'-cp',str(root/'fixture-classes')+':'+str(root/'resolved-jars/*')+':'+str(a.plugin),'io.github.jesterportfolio.hearth.persistence.NativeEconomyDatabaseFixture',str(root)]+(['restore'] if restore else []);m.launch('database',helper,root);m.wait('database','HEARTH_NATIVE_DB_READY',seconds=75);db_port=int((root/'db-port.txt').read_text());ports.append(db_port);game_port=port();ports.append(game_port)
  text=config.read_text()
  if restore:text=re.sub(r'(?m)^  db-port: [0-9]+$',f'  db-port: {db_port}',text)
  else:
   realm=uuid.uuid4();text+='\nsurvival-economy:\n  enabled: true\n  realm: '+str(realm)+'\n  allow-new-realm: true\n  content-file: survival-economy.properties\n  db-host: 127.0.0.1\n  db-port: '+str(db_port)+'\n  db-name: hearth_s4_11\n  db-user: postgres\n  password-environment: HEARTH_ISOLATED_NATIVE_PASSWORD\n'
   source_content=Path(__file__).resolve().parents[1]/'src/main/resources/economy-demo.properties';content=source_content.read_text().replace('fixture-balance=100000','fixture-balance=0').replace('tick-millis=5000','tick-millis=60000');content=re.sub(r'(?m)^(merchant\.[a-z_]+\.y)=64$',r'\1=-63',content);(root/'server/plugins/Hearth/survival-economy.properties').write_text(content)
  config.write_text(text);os.environ['HEARTH_ISOLATED_NATIVE_PASSWORD']='isolated-not-a-secret'
  with (root/'server/server.properties').open('a') as f:f.write('\nserver-ip=127.0.0.1\nserver-port='+str(game_port)+'\nonline-mode=true\nwhite-list=true\nenforce-whitelist=true\nenable-query=false\nenable-rcon=false\n')
  command=[str(a.java),'-Xms256m','-Xmx1536m','-XX:ActiveProcessorCount=2','-Djava.io.tmpdir='+str(root),'-Dhearth.native.economy.verification.root='+str(root),'-jar',str(root/'paper.jar'),'--nogui'];world_registry=(root/'server/plugins/Hearth/survival-worlds.properties').read_bytes()
  for index,phase in ([(2,'resume')] if restore else [(1,'prepare'),(2,'resume')]):
   name='paper-native-'+str(index);m.launch(name,command,root/'server');m.wait(name,'HEARTH_SURVIVAL_ECONOMY_READY',seconds=120);m.wait(name,'HEARTH_SURVIVAL_READY worlds=3',seconds=10);m.send(name,'heartheconomy native-verify '+phase);m.wait(name,'HEARTH_NATIVE_ECONOMY_PROBE PASS '+phase,seconds=90);m.send(name,'hearthsurvivaladmin drain');m.wait(name,'생존 저장·종료 준비 완료',seconds=25);m.stop(name,'stop');assert (root/'server/plugins/Hearth/survival-worlds.properties').read_bytes()==world_registry
   if index==1:config.write_text(config.read_text().replace('allow-new-realm: true','allow-new-realm: false'));assert not (root/'server/plugins/Hearth/native-economy.active').exists()
  m.send('database','compare');m.wait('database','HEARTH_NATIVE_DB_COMPARE_PASS',seconds=15);m.stop('database','finish');assert all(digest(Path(f))==sha for f,sha in before.items());assert digest(a.plugin)==plugin_sha and digest(root/'server/plugins/Hearth.jar')==plugin_sha
  for item in m.children.values():
   for line in item['lines']:
    for prefix in ('HEARTH_NATIVE_DB_CHECK PASS ','HEARTH_NATIVE_ECONOMY_CHECK PASS '):
     if prefix in line:checks.append(line.split(prefix,1)[1])
  checks+=['original_inactive_worlds_registry_economy_mapping_and_database_control_preserved','owned_Paper_drains_PG_and_preserves_native_pending_markers_without_real_Player'];outcome='PASS'
 except Exception as failure:error=type(failure).__name__+': '+str(failure);print('NATIVE_ECONOMY FAIL',error,flush=True)
 finally:
  cleanup=m.finish()
  for number in ports:
   with socket.socket() as s:
    try:s.bind(('127.0.0.1',number))
    except OSError:cleanup.append('OWNED_PORT_REMAINS')
  if cleanup:outcome='FAIL'
  result=dict(scope='RESTORE_ONLY' if a.restore_source else 'TWO_JVM_FULL',outcome=outcome,failure=error,cleanup_errors=cleanup,duration_seconds=round(time.monotonic()-started,3),checks=checks,processes=m.summary(),combined_samples=m.combined,plugin_sha256=digest(a.plugin),limits={'total_seconds':600,'work_seconds':550,'cpu':2,'combined_rss_stop_mib':6144,'paper_starts_max':2,'paper_heap_mib':1536,'paper_rss_stop_mib':2560,'host_available_min_mib':6144},actual_client='NOT_RUN',actual_Player_saveData_click_native_item_transfer_and_network_switch='NOT_RUN',operating_changes='NONE');(root/'native-economy-result.json').write_text(json.dumps(result,indent=2)+'\n');print('NATIVE_ECONOMY',outcome,'evidence:',root/'native-economy-result.json',flush=True)
 return 0 if outcome=='PASS' else 1
if __name__=='__main__':raise SystemExit(main())
