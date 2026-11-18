#!/usr/bin/env python3
"""새 소유 3월드 Paper 두 JVM 복원 검사. Player/인증/클릭은 합성하지 않습니다."""
import argparse
import json
import os
from pathlib import Path
import shutil
import socket
import time
import uuid
import isolated_paths
import survival_config
from verify_network import Monitor,copy_tree,port,digest


def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--workspace',type=Path,required=True);p.add_argument('--java',type=Path,required=True);p.add_argument('--plugin',type=Path,required=True);p.add_argument('--prepared-source',type=Path,required=True);p.add_argument('--accept-eula',action='store_true');a=p.parse_args();root=isolated_paths.workspace(a.workspace,'hearth-survival-run.');os.sched_setaffinity(0,sorted(os.sched_getaffinity(0))[:2]);started=time.monotonic();m=Monitor(root,started);failure=None;cleanup=[];checks=[];outcome='FAIL';server_port=None;baseline={}
 try:
  if not a.accept_eula or (root/'.hearth-survival-isolated').read_text()!='Hearth survival isolated v1\n':raise ValueError('SURVIVAL_FIXTURE_GATE')
  source=isolated_paths.workspace(a.prepared_source,'hearth-s4-11.');result=json.loads((source/'run-result.json').read_text())
  if result['outcome']!='PASS' or result['cleanup_errors'] or not result['all_owned_processes_and_ports_closed'] or (source/'data/postmaster.pid').exists() or list((source/'server/plugins/Hearth').glob('*.active')):raise ValueError('INACTIVE_PASSED_SOURCE_REQUIRED')
  for rel in ('server/plugins/Hearth/config.yml','server/plugins/Hearth/economy-world.properties','server/world/dimensions/minecraft/overworld/data/paper/metadata.dat','data/global/pg_control'):baseline[str(source/rel)]=digest(source/rel)
  server=root/'server';server.mkdir();(server/'plugins/Hearth').mkdir(parents=True);(server/'config').mkdir();(server/'world/dimensions/minecraft').mkdir(parents=True)
  # 기존 인벤토리/DB/독자 월드는 채택하지 않고 원본은 읽기 전용으로 보존합니다.
  for name in ('libraries','versions','cache'):
   if (source/'server'/name).is_dir():copy_tree(source/'server'/name,server/name,m.guard)
  for name in ('worldedit-bukkit-7.4.4.jar','worldguard-bukkit-7.0.19.jar'):shutil.copy2(source/'server/plugins'/name,server/'plugins'/name)
  for path in (source/'server/world').iterdir():
   if path.is_file() and path.name!='session.lock':shutil.copy2(path,server/'world'/path.name)
  for dimension in ('overworld','the_nether','the_end'):copy_tree(source/'server/world/dimensions/minecraft'/dimension,server/'world/dimensions/minecraft'/dimension,m.guard)
  # 서버 내 기존 플레이어 파일/통계는 복사하지 않습니다. 본 시험은 접속하지 않습니다.
  for path in (server/'world').rglob('*'):
   if path.is_symlink():raise ValueError('SURVIVAL_SOURCE_LINK')
  shutil.copy2(a.plugin,server/'plugins/Hearth.jar');shutil.copy2(source/'paper-26.2-134.jar',root/'paper.jar');plugin_sha=digest(a.plugin);server_port=port()
  (server/'eula.txt').write_text('eula=true\n');(server/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={server_port}\nonline-mode=true\nwhite-list=true\nenforce-whitelist=true\nenable-rcon=false\nenable-query=false\nmax-players=4\ngamemode=survival\ndifficulty=normal\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\nlevel-name=world\n')
  # 표준 FLAT 생성기는 시험 준비 비용만 제한하며 일반 설정 기본값은 NORMAL입니다.
  text=survival_config.configuration(uuid.uuid4(),True).replace('key: hearth_survival:wilderness','key: hearth_survival:wilderness\n      terrain: FLAT\n      seed: 42');config=server/'plugins/Hearth/config.yml';config.write_text(text)
  command=[str(a.java),'-Xms256m','-Xmx1536m','-XX:ActiveProcessorCount=2','-Djava.io.tmpdir='+str(root),'-Dhearth.survival.verification.root='+str(root),'-jar',str(root/'paper.jar'),'--nogui']
  registry=None;first=None
  for index in (1,2):
   name='paper-survival-'+str(index);m.launch(name,command,server);m.wait(name,'HEARTH_SURVIVAL_READY worlds=3',seconds=150);m.send(name,'hearthsurvivaladmin verify');m.wait(name,'HEARTH_SURVIVAL_PROBE PASS',seconds=30);m.stop(name,'stop');
   if not any('HEARTH_SURVIVAL_DISABLE_CLOSE PASS' in line for line in m.children[name]['lines']):raise RuntimeError('SURVIVAL_CLEAN_CLOSE_MISSING')
   current=(server/'plugins/Hearth/survival-worlds.properties').read_bytes();identity=(root/'first-identities.properties').read_bytes()
   if index==1:
    registry=current;first=identity;config.write_text(text.replace('allow-create: true','allow-create: false'));checks+=['three_actual_paper_worlds_NORMAL_NORMAL_NETHER_with_safe_natural_entries','registered_world_command_alias_and_three_GUI_item_objects_without_Player','RPG_session_combat_inventory_listeners_absent_and_native_autosave_enabled']
   else:
    assert current==registry and identity==first;checks+=['new_JVM_allow_create_false_restores_identical_UUID_seed_entry_floor_and_registry','both_owned_Paper_JVMs_cleanly_closed']
  assert all(digest(Path(path))==sha for path,sha in baseline.items());assert digest(a.plugin)==plugin_sha and digest(server/'plugins/Hearth.jar')==plugin_sha;checks.append('original_config_economy_mapping_PG_control_and_world_metadata_preserved');outcome='PASS'
 except Exception as error:failure=type(error).__name__+': '+str(error);print('SURVIVAL FAIL',failure,flush=True)
 finally:
  cleanup=m.finish()
  if server_port:
   with socket.socket() as s:
    try:s.bind(('127.0.0.1',server_port))
    except OSError:cleanup.append('OWNED_PORT_REMAINS')
  if cleanup:outcome='FAIL'
  report=dict(outcome=outcome,failure=failure,cleanup_errors=cleanup,duration_seconds=round(time.monotonic()-started,3),checks=checks,processes=m.summary(),combined_samples=m.combined,plugin_sha256=digest(a.plugin),limits={'total_seconds':600,'work_seconds':550,'cpu':2,'combined_rss_stop_mib':6144,'paper_starts_max':2,'paper_heap_mib':1536,'paper_rss_stop_mib':2560,'host_available_min_mib':6144},actual_client='NOT_RUN',actual_player_teleport_inventory_and_network_switch='NOT_RUN',operating_changes='NONE');(root/'survival-result.json').write_text(json.dumps(report,indent=2)+'\n');print('SURVIVAL',outcome,'evidence:',root/'survival-result.json',flush=True)
 return 0 if outcome=='PASS' else 1
if __name__=='__main__':raise SystemExit(main())
