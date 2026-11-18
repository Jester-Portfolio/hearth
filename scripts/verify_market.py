#!/usr/bin/env python3
"""시장 에이전트·PG·실제 주기 Paper와 선택형 고정 로컬 LLM의 공유 한도 검사."""
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
from verify_network import copy_tree,digest,port
from verify_native_economy import NativeMonitor
from joint_ai import JointAI

class MarketMonitor(NativeMonitor):
 joint=None
 def pump(self):
  if self.joint:self.joint.trial.guard()
  super().pump()
  if not self.stopping:
   for name,item in self.children.items():
    if any('HEARTH_MARKET_PROBE FAIL' in line and not line.rstrip().endswith('FAIL ai') for line in item['lines'][-40:]):raise RuntimeError('MARKET_PROBE_FAILED_'+name)
 def wait_either(self,name,yes,no,seconds):
  until=min(self.deadline,time.monotonic()+seconds)
  while time.monotonic()<until:
   lines=self.children[name]['lines']
   if any(yes in row for row in lines):return True
   if any(no in row for row in lines):return False
   if self.children[name]['proc'].poll() is not None:raise RuntimeError('MARKET_ROLE_EXITED')
   self.pump()
  raise RuntimeError('MARKET_ACTION_TIMEOUT')

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--workspace',type=Path,required=True);p.add_argument('--java',type=Path,required=True);p.add_argument('--plugin',type=Path,required=True);p.add_argument('--prepared-worlds',type=Path);p.add_argument('--prepared-database',type=Path);p.add_argument('--restore-source',type=Path);p.add_argument('--ai-source',type=Path);p.add_argument('--accept-eula',action='store_true');a=p.parse_args();root=isolated_paths.workspace(a.workspace,'hearth-native-economy-run.');os.sched_setaffinity(0,sorted(os.sched_getaffinity(0))[:2]);started=time.monotonic();m=MarketMonitor(root,started);joint=None;failure=None;outcome='FAIL';algorithm='FAIL';ai='NOT_REQUESTED';cleanup=[];checks=[];ports=[];ai_result=None;attempted=False
 try:
  if not a.accept_eula or (root/'.hearth-native-economy-isolated').read_text()!='Hearth native economy isolated v1\n' or (root/'.hearth-market-isolated').read_text()!='Hearth market isolated v1\n':raise ValueError('MARKET_FIXTURE_GATE')
  restore=a.restore_source is not None
  if restore:
   worlds=database=isolated_paths.workspace(a.restore_source,'hearth-native-economy-run.');passed=json.loads((worlds/'market-result.json').read_text())
   if passed['algorithm']!='PASS' or passed['cleanup_errors'] or (database/'data/postmaster.pid').exists() or list((worlds/'server/plugins/Hearth').glob('*.active')):raise ValueError('MARKET_PASSED_ALGORITHM_SOURCE_REQUIRED')
   originals=[worlds/'server/plugins/Hearth/config.yml',worlds/'server/plugins/Hearth/survival-worlds.properties',worlds/'server/plugins/Hearth/market-automation.properties',database/'data/global/pg_control']
  else:
   worlds=isolated_paths.workspace(a.prepared_worlds,'hearth-survival-run.');database=isolated_paths.workspace(a.prepared_database,'hearth-s4-11.');wr=json.loads((worlds/'final-restore-result.json').read_text());dr=json.loads((database/'run-result.json').read_text())
   if wr['outcome']!='PASS' or wr['cleanup_errors'] or dr['outcome']!='PASS' or dr['cleanup_errors'] or not dr['all_owned_processes_and_ports_closed'] or (database/'data/postmaster.pid').exists() or list((worlds/'server/plugins/Hearth').glob('*.active')):raise ValueError('MARKET_INACTIVE_SOURCES_REQUIRED')
   originals=[worlds/'server/plugins/Hearth/config.yml',worlds/'server/plugins/Hearth/survival-worlds.properties',worlds/'server/world/dimensions/minecraft/overworld/data/paper/metadata.dat',database/'server/plugins/Hearth/economy-world.properties',database/'data/global/pg_control']
  before={str(f):digest(f) for f in originals};plugin_sha=digest(a.plugin)
  copy_tree(worlds/'server',root/'server',m.guard);copy_tree(database/'data',root/'data',m.guard);shutil.copy2(worlds/'paper.jar',root/'paper.jar');shutil.copy2(a.plugin,root/'server/plugins/Hearth.jar')
  if a.ai_source:
   joint=JointAI(m,a.ai_source);m.joint=joint;joint.prepare_artifacts();joint.start();ai='PENDING'
  cfg=root/'server/plugins/Hearth/config.yml'
  if restore:
   realm=uuid.UUID((worlds/'market-realm.txt').read_text().strip());(root/'restore-realm.txt').write_text(str(realm)+'\n')
  else:realm=uuid.uuid4()
  (root/'market-realm.txt').write_text(str(realm)+'\n');helper=[str(a.java),'-Xmx128m','-XX:ActiveProcessorCount=2','-Djava.io.tmpdir='+str(root),'-cp',str(root/'fixture-classes')+':'+str(root/'resolved-jars/*')+':'+str(a.plugin),'io.github.jesterportfolio.hearth.persistence.NativeEconomyDatabaseFixture',str(root),'restore' if restore else 'market'];m.launch('database',helper,root);m.wait('database','HEARTH_NATIVE_DB_READY',seconds=75);db_port=int((root/'db-port.txt').read_text());ports.append(db_port);game_port=port();ports.append(game_port)
  if restore:
   text=re.sub(r'(?m)^  db-port: [0-9]+$',f'  db-port: {db_port}',cfg.read_text());text=re.sub(r'(?m)^  ai-mode: (?:none|ollama)$','  ai-mode: '+('ollama' if joint else 'none'),text);m.send('database','market-save');m.wait('database','HEARTH_MARKET_DB_SAVED',seconds=10)
  else:
   text=cfg.read_text()+'\nsurvival-economy:\n  enabled: true\n  realm: '+str(realm)+'\n  allow-new-realm: true\n  content-file: survival-economy.properties\n  db-host: 127.0.0.1\n  db-port: '+str(db_port)+'\n  db-name: hearth_s4_11\n  db-user: postgres\n  password-environment: HEARTH_ISOLATED_MARKET_PASSWORD\nmarket-automation:\n  enabled: true\n  allow-new-controller: true\n  interval-ticks: 1\n  confirmations: 2\n  minimum-hold-ticks: 2\n  ai-mode: '+('ollama' if joint else 'none')+'\n'
   content=(Path(__file__).resolve().parents[1]/'src/main/resources/economy-demo.properties').read_text().replace('fixture-balance=100000','fixture-balance=0').replace('tick-millis=5000','tick-millis=1000');content=re.sub(r'(?m)^(merchant\.[a-z_]+\.y)=64$',r'\1=-63',content);(root/'server/plugins/Hearth/survival-economy.properties').write_text(content)
  cfg.write_text(text);os.environ['HEARTH_ISOLATED_MARKET_PASSWORD']='isolated-not-a-secret'
  with (root/'server/server.properties').open('a') as f:f.write('\nserver-ip=127.0.0.1\nserver-port='+str(game_port)+'\nonline-mode=true\nwhite-list=true\nenforce-whitelist=true\nenable-rcon=false\nenable-query=false\n')
  command=[str(a.java),'-Xms256m','-Xmx1536m','-XX:ActiveProcessorCount=2','-Djava.io.tmpdir='+str(root),'-Dhearth.market.verification.root='+str(root),'-jar',str(root/'paper.jar'),'--nogui'];registry=(root/'server/plugins/Hearth/survival-worlds.properties').read_bytes()
  for index,phase in ([(1,'resume')] if restore else [(1,'prepare'),(2,'resume')]):
   name='paper-market-'+str(index);m.launch(name,command,root/'server');m.wait(name,'HEARTH_SURVIVAL_READY worlds=3',seconds=120);m.send(name,'heartheconomy market-verify '+phase);m.wait(name,'HEARTH_MARKET_PROBE PASS '+phase,seconds=25)
   if index==1 and joint:
    attempted=True;m.send(name,'heartheconomy automation prepare');prepared=m.wait_either(name,'시장 AI 명시적 준비 완료','시장 AI 준비 실패',90)
    if prepared:
     m.send(name,'heartheconomy market-verify ai');ai='PASS' if m.wait_either(name,'HEARTH_MARKET_PROBE PASS ai','HEARTH_MARKET_PROBE FAIL ai',12) else 'FAIL'
    else:ai='FAIL'
   m.send(name,'hearthsurvivaladmin drain');m.wait(name,'생존 저장·종료 준비 완료',seconds=25);m.stop(name,'stop');assert (root/'server/plugins/Hearth/survival-worlds.properties').read_bytes()==registry
   if index==1 and not restore:
    m.send('database','market-save');m.wait('database','HEARTH_MARKET_DB_SAVED',seconds=10);cfg.write_text(cfg.read_text().replace('allow-new-realm: true','allow-new-realm: false').replace('allow-new-controller: true','allow-new-controller: false').replace('ai-mode: ollama','ai-mode: none'))
  m.send('database','compare');m.wait('database','HEARTH_NATIVE_DB_COMPARE_PASS',seconds=15);m.stop('database','finish');assert all(digest(Path(f))==sha for f,sha in before.items());assert digest(root/'server/plugins/Hearth.jar')==plugin_sha
  for item in m.children.values():
   for line in item['lines']:
    for prefix in ('HEARTH_MARKET_DB_CHECK PASS ','HEARTH_MARKET_CHECK PASS ','HEARTH_NATIVE_DB_CHECK PASS '):
     if prefix in line:checks.append(line.split(prefix,1)[1])
  algorithm='PASS';outcome='PASS' if ai in ('PASS','NOT_REQUESTED') else 'PARTIAL'
 except Exception as error:failure=type(error).__name__+': '+str(error);print('MARKET FAIL',failure,flush=True)
 finally:
  cleanup=m.finish()
  if joint:
   cleanup+=joint.cleanup();ai_result=joint.result('PASS' if outcome=='PASS' else 'FAIL','market-ai-result.json')
   if ai_result['outcome']!='PASS' and outcome=='PASS':outcome='FAIL'
  for number in ports:
   with socket.socket() as s:
    try:s.bind(('127.0.0.1',number))
    except OSError:cleanup.append('OWNED_PORT_REMAINS')
  if cleanup:outcome='FAIL'
  result=dict(scope='RESTORE_ONLY' if a.restore_source else 'FULL',outcome=outcome,algorithm=algorithm,ai_outcome=ai,ai_attempted=attempted,failure=failure,cleanup_errors=cleanup,duration_seconds=round(time.monotonic()-started,3),checks=checks,plugin_sha256=digest(a.plugin),processes=m.summary(),ai=ai_result,combined_samples=m.combined,limits={'total_seconds':600,'work_seconds':550,'cpu':2,'paper_heap_mib':1536,'paper_rss_stop_mib':2560,'ai_rss_stop_mib':4096,'combined_rss_stop_mib':6144,'gpu_stop_mib':5120,'host_available_min_mib':6144,'paper_starts_max':2},actual_client='NOT_RUN',actual_player_trades='NOT_RUN',operating_changes='NONE');(root/'market-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print('MARKET',outcome,'evidence:',root/'market-result.json',flush=True)
 return 0 if outcome=='PASS' else 1
if __name__=='__main__':raise SystemExit(main())
