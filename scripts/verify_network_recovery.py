#!/usr/bin/env python3
"""네트워크 전체 snapshot을 복원한 소유 격리 PG·두 Paper·Velocity의 실제 기동/종료 수용."""
import sys
if __name__ == "__main__": sys.dont_write_bytecode = True
import argparse,hashlib,json,os,re,shutil,time,uuid
from pathlib import Path
import isolated_paths,offline_backup,network_config,network_release,network_recovery,runtime_health
from verify_network import Monitor,copy_tree,digest,port,ping,VELOCITY_SHA
from verify_native_economy import NativeMonitor,port_closed

class RecoveryMonitor(NativeMonitor):
 def stop(self,name,command,seconds=30):
  if name in ('proxy','paper-lobby','database'):return Monitor.stop(self,name,command,seconds)
  return super().stop(name,command,seconds)

def normal_properties(path,values):
 text=path.read_text()
 for key,value in values.items():
  text=re.sub(r'(?m)^'+re.escape(key)+r'=.*\n?', '',text)
  text+='\n'+key+'='+str(value)+'\n'
 path.write_text(text)

def read_ready(m,root,role,mode,boot):
 end=time.monotonic()+15
 while time.monotonic()<end:
  m.pump()
  try:
   row=runtime_health.inspect(root/'restored'/role/'logs/latest.log',boot,mode,12)
   if row['controller_ready']:return row
  except (OSError,ValueError):pass
  time.sleep(.05)
 raise RuntimeError('RECOVERY_OBSERVATION_NOT_READY_'+role)

def main():
 p=argparse.ArgumentParser(description=__doc__)
 for key in ('workspace','java','plugin','economy-source','lobby-source'):p.add_argument('--'+key,required=True,type=Path)
 p.add_argument('--accept-eula',action='store_true');a=p.parse_args()
 root=isolated_paths.workspace(a.workspace,'hearth-network-run.');economy=isolated_paths.workspace(a.economy_source,'hearth-native-economy-run.');lobby=isolated_paths.workspace(a.lobby_source,'hearth-network-run.')
 os.sched_setaffinity(0,sorted(os.sched_getaffinity(0))[:2]);started=time.monotonic();m=RecoveryMonitor(root,started);checks=[];ports=[];before={};failure=None;cleanup=[];outcome='FAIL';observations={}
 def check(ok,name):
  if not ok:raise RuntimeError(name)
  checks.append(name);print('NETWORK_RECOVERY_CHECK PASS',name,flush=True)
 try:
  check(a.accept_eula and (root/'.hearth-network-isolated').read_text()=='Hearth network isolated v1\n','owned_EULA_fixture_gate')
  for source,result in ((economy,'aura-trial-result.json'),(lobby,'network-result.json')):
   data=json.loads((source/result).read_text());check(data['outcome']=='PASS' and not data['cleanup_errors'] and not (source/'data/postmaster.pid').exists() and not any(source.glob('**/*.active')),'clean_passed_source_'+result)
  for f in (economy/'data/global/pg_control',economy/'server/plugins/Hearth/config.yml',lobby/'lobby/plugins/Hearth/config.yml'):before[str(f)]=digest(f)
  check(digest(lobby/'velocity.jar')==VELOCITY_SHA,'pinned_actual_Velocity_artifact')
  prepared=root/'prepared';prepared.mkdir(mode=0o700)
  copy_tree(economy/'server',prepared/'economy',m.guard);copy_tree(lobby/'lobby',prepared/'lobby',m.guard);copy_tree(economy/'data',prepared/'postgres',m.guard)
  for role in ('proxy','lobby','economy','postgres'):
   value=port()
   while value in ports:value=port()
   ports.append(value)
  numbers=dict(zip(('proxy','lobby','economy','postgres'),ports))
  text=(prepared/'economy/plugins/Hearth/config.yml').read_text();text=re.sub(r'(?m)^  db-port: [0-9]+$', '  db-port: '+str(numbers['postgres']),text);text=re.sub(r'(?m)^  password-environment: .*$', '  password-environment: HEARTH_DB_PASSWORD',text)
  original=(lobby/'lobby/plugins/Hearth/config.yml').read_text();lobby_settings={}
  for k in ('world-key','world-uuid','region'):
   lobby_settings[k]=re.search(r'(?m)^  '+k+': (.*)$',original).group(1)
  for k in ('entry-x','entry-y','entry-z','entry-yaw','entry-pitch'):lobby_settings[k]=float(re.search(r'(?m)^  '+k+': (.*)$',original).group(1))
  settings=root/'network-settings';network_config.generate(settings,uuid.uuid4(),{r:numbers[r] for r in ('proxy','lobby','economy')},lobby_settings,text,4)
  shutil.copytree(settings/'proxy',prepared/'proxy')
  env='HEARTH_VELOCITY_SECRET='+(settings/'secrets/forwarding.secret').read_text().strip()+'\nHEARTH_NETWORK_SECRET='+(settings/'secrets/relay.secret').read_text().strip()+'\nHEARTH_DB_PASSWORD=isolated-not-a-secret\n'
  for role in ('proxy','lobby','economy'):
   data=prepared/role;(data/'plugins').mkdir(exist_ok=True);shutil.copy2(a.plugin,data/'plugins/Hearth.jar')
   (data/'runtime.env').write_text(env);(data/'runtime.env').chmod(0o600)
   if role=='proxy':shutil.copy2(lobby/'velocity.jar',data/'server.jar')
   else:
    shutil.copy2(economy/'paper.jar',data/'server.jar');shutil.copy2(settings/role/'plugins/Hearth/config.yml',data/'plugins/Hearth/config.yml')
    cfg=data/'config/paper-global.yml';cfg.write_text(network_config.section(cfg.read_text(),'proxies',(settings/'paper-global.fragment.yml').read_text()))
    normal_properties(data/'server.properties',dict({'server-ip':'127.0.0.1','server-port':numbers[role],'online-mode':'false','white-list':'true','enforce-whitelist':'true','enable-query':'false','enable-rcon':'false'}))
    probe=data/'plugins/HearthAuraProbe.jar'
    if probe.exists():
     unused=root/'unused-probes';unused.mkdir(exist_ok=True);shutil.move(probe,unused/(role+'-HearthAuraProbe.jar'))
  # Explicit fixture budgets/images are never selected operating values or installed images.
  services={}
  for role in ('proxy','lobby','economy','postgres'):
   names={'proxy':['velocity.toml','forwarding.secret','plugins/hearth-network/network.properties','plugins/Hearth.jar','runtime.env'],'lobby':['server.properties','plugins/Hearth/config.yml','config/paper-global.yml','plugins/Hearth.jar','runtime.env','eula.txt'],'economy':['server.properties','plugins/Hearth/config.yml','config/paper-global.yml','plugins/Hearth.jar','runtime.env','eula.txt'],'postgres':['PG_VERSION','postgresql.conf']}[role]
   services[role]=dict(data=str(prepared/role),image='fixture/review-only@sha256:'+'a'*64,cpu=.25 if role in ('proxy','postgres') else .75,memory_mib={'proxy':512,'lobby':2048,'economy':2560,'postgres':256}[role],heap_mib=0 if role=='postgres' else 256 if role=='proxy' else 1536,port=numbers[role],uid=os.getuid(),gid=os.getgid(),jar='' if role=='postgres' else 'server.jar',inputs=names,stop_seconds=30,pids=128)
  spec=dict(format=1,host_cpu=2,host_memory_mib=6144,services=services,backup=dict(interval_seconds=100,retain=2,offsite='fixture-only-not-operating',rpo_seconds=100,rto_seconds=600),acceptance=dict(client=False,load=False,restore=False,release_regression=False))
  candidate=root/'source-candidate';network_release.generate(spec,candidate)
  budget=offline_backup.Budget(guard=m.guard)
  receipt=network_recovery.create(candidate/'release.json',root/'snapshot',offline_confirmed=True,budget=budget);network_recovery.verify(root/'snapshot',receipt['recovery_sha256'],budget=budget)
  restored=network_recovery.restore(root/'snapshot',root/'restored',receipt['recovery_sha256'],budget=budget)
  check(restored['acceptance_reset'] and not network_release.inspect(root/'restored/candidate/release.json')['acceptance_complete'],'four_roles_restore_together_and_acceptance_is_not_inherited')
  snapshot=json.loads((root/'snapshot/bundle/manifest.json').read_text());members={row['path'] for row in snapshot['files']}
  check(any('/platform-archive/config/' in k for k in members) and any('/dimensions/hearth_resource/' in k for k in members),'native_items_config_archive_and_resource_generations_are_included')
  restore_spec=json.loads((root/'restored/restored-review.json').read_text());os.environ.update(network_release.read_env(root/'restored/proxy/runtime.env'))
  proxy=[str(a.java),'-Xms64m','-Xmx256m','-XX:ActiveProcessorCount=2','-Djava.io.tmpdir='+str(root),'-jar','server.jar'];m.launch('proxy',proxy,root/'restored/proxy');m.wait('proxy','HEARTH_NETWORK_PROXY_READY',seconds=40)
  marker=root/'restored/proxy/plugins/hearth-network/proxy-runtime.active';check(marker.is_file(),'actual_proxy_holds_exclusive_runtime_marker')
  try:network_recovery.create(root/'restored/candidate/release.json',root/'rejected-live-copy',offline_confirmed=True);raise RuntimeError('LIVE_COPY_ACCEPTED')
  except (ValueError,offline_backup.BackupError):check(not (root/'rejected-live-copy').exists() and marker.exists(),'live_proxy_snapshot_refuses_without_removing_its_marker')
  (root/'network-recovery.properties').write_text('postgres-directory='+str(root/'restored/postgres')+'\npostgres-port='+str(numbers['postgres'])+'\n')
  helper=[str(a.java),'-Xmx128m','-XX:ActiveProcessorCount=2','-Djava.io.tmpdir='+str(root),'-cp',str(root/'fixture-classes')+':'+str(root/'resolved-jars/*')+':'+str(a.plugin),'io.github.jesterportfolio.hearth.persistence.NetworkDatabaseFixture',str(root)]
  m.launch('database',helper,root);m.wait('database','HEARTH_NETWORK_DB_READY',seconds=75)
  for role in ('lobby','economy'):
   boot=str(uuid.uuid4());(root/(role+'-boot')).write_text(boot+'\n')
   command=[str(a.java),'-Xms256m','-Xmx1536m','-XX:ActiveProcessorCount=2','-Djava.io.tmpdir='+str(root),'-Dhearth.runtime.boot='+boot,'-jar','server.jar','--nogui']
   m.launch('paper-'+role,command,root/'restored'/role)
  m.wait('paper-lobby','HEARTH_LOBBY_READY',seconds=120);m.wait('paper-economy','HEARTH_SURVIVAL_READY worlds=5',seconds=120)
  for role,mode in (('lobby','lobby'),('economy','survival')):observations[role]=read_ready(m,root,role,mode,root/(role+'-boot'))
  check(observations['economy']['format']==2 and observations['economy']['platform']['ready']==1,'restored_normal_survival_platform_and_lobby_report_current_ready')
  check('version' in ping(numbers['proxy']),'actual_proxy_status_ping_without_Player_or_login')
  m.stop('proxy','shutdown');check(not marker.exists(),'clean_proxy_shutdown_removes_only_its_own_marker')
  m.send('paper-economy','hearthsurvivaladmin drain');m.wait('paper-economy','생존 저장·종료 준비 완료',seconds=25);m.stop('paper-economy','stop');m.stop('paper-lobby','stop')
  m.send('database','compare');m.wait('database','HEARTH_NETWORK_DB_COMPARE_PASS',seconds=20);m.stop('database','finish');check(True,'restored_accounts_archived_IDs_and_story_rows_preserve_original_values')
  check(all(digest(Path(name))==value for name,value in before.items()),'original_sources_remain_unchanged')
  network_recovery.verify(root/'snapshot',receipt['recovery_sha256']);check(True,'backup_snapshot_remains_verifiable_after_rehearsal')
  outcome='PASS'
 except Exception as error:
  failure=type(error).__name__+': '+str(error);print('NETWORK_RECOVERY FAIL',failure,flush=True)
 finally:
  cleanup=m.finish();cleanup.extend('OWNED_PORT_REMAINS_'+str(n) for n in ports if not port_closed(n))
  if cleanup:outcome='FAIL'
  result=dict(outcome=outcome,failure=failure,cleanup_errors=cleanup,checks=checks,observations=observations,duration_seconds=round(time.monotonic()-started,3),source_hashes=before,plugin_sha256=digest(a.plugin),processes=m.summary(),combined_samples=m.combined,limits=dict(cpu=2,total_seconds=600,work_seconds=550,paper_starts_max=2,proxy_starts_max=1,paper_heap_mib=1536,paper_rss_stop_mib=2560,combined_rss_stop_mib=6144,proxy_heap_mib=256,proxy_rss_stop_mib=512),actual_client='NOT_RUN',fake_player='NOT_USED',operating_changes='NONE')
  (root/'network-recovery-result.json').write_text(json.dumps(result,indent=2)+'\n');print('NETWORK_RECOVERY',outcome,root/'network-recovery-result.json',flush=True)
 return 0 if outcome=='PASS' else 1
if __name__=='__main__':raise SystemExit(main())
