import copy,json,os,tempfile,unittest
from pathlib import Path
import isolated_paths,network_release as release
class NetworkReleaseTest(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory(dir=isolated_paths.require_base(),prefix='network-release.');self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name);services={};secret='a'*64;relay='b'*64
  for i,role in enumerate(release.ROLES):
   root=self.root/role;root.mkdir();inputs={'proxy':['velocity.toml','forwarding.secret','plugins/hearth-network/network.properties','plugins/Hearth.jar','runtime.env'],'lobby':['server.properties','plugins/Hearth/config.yml','config/paper-global.yml','plugins/Hearth.jar','runtime.env','eula.txt'],'economy':['server.properties','plugins/Hearth/config.yml','config/paper-global.yml','plugins/Hearth.jar','runtime.env','eula.txt'],'postgres':['PG_VERSION','postgresql.conf']}[role]
   for name in inputs:
    p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text('fixture');p.chmod(0o600)
   if role!='postgres':
    (root/'server.jar').write_bytes(b'fixed artifact');(root/'plugins/Hearth.jar').write_bytes(b'same hearth jar');(root/'runtime.env').write_text('HEARTH_VELOCITY_SECRET='+secret+'\nHEARTH_NETWORK_SECRET='+relay+'\nHEARTH_DB_PASSWORD=fixture\n')
   if role in ('lobby','economy'):
    (root/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={26000+i}\nonline-mode=false\n');(root/'plugins/Hearth/config.yml').write_text('runtime-mode: '+('lobby' if role=='lobby' else 'survival')+'\nnetwork:\n  enabled: true\n  server-id: '+role+'\n  network-id: 00000000-0000-0000-0000-000000000001\n  forwarding-secret-environment: HEARTH_VELOCITY_SECRET\n  relay-secret-environment: HEARTH_NETWORK_SECRET\n');(root/'config/paper-global.yml').write_text('proxies:\n  velocity:\n    enabled: true\n    online-mode: true\n    secret: '+secret+'\n');(root/'eula.txt').write_text('eula=true\n')
   services[role]=dict(data=str(root),image='fixture/java@sha256:'+'a'*64,cpu=.5,memory_mib=512,heap_mib=128 if role!='postgres' else 0,port=26000+i,uid=os.getuid() or 1000,gid=os.getgid() or 1000,jar='server.jar' if role!='postgres' else '',inputs=inputs,stop_seconds=30,pids=128)
  (self.root/'proxy/plugins/hearth-network/network.properties').write_text('network-id=00000000-0000-0000-0000-000000000001\n');(self.root/'proxy/forwarding.secret').write_text(secret+'\n');(self.root/'proxy/velocity.toml').write_text('bind="127.0.0.1:26000"\nonline-mode=true\nplayer-info-forwarding-mode="modern"\n[servers]\nlobby="127.0.0.1:26001"\neconomy="127.0.0.1:26002"\n')
  self.spec=dict(format=1,host_cpu=4,host_memory_mib=4096,services=services,backup=dict(interval_seconds=100,retain=3,offsite='reviewed-target',rpo_seconds=120,rto_seconds=600),acceptance=dict(client=False,load=False,restore=True,release_regression=False))
 def test_candidate_binds_files_budgets_and_keeps_unaccepted_status(self):
  out=self.root/'output';r=release.generate(self.spec,out);self.assertFalse(r['acceptance_complete']);self.assertTrue(release.inspect(out/'release.json')['unchanged']);self.assertNotIn('HEARTH_DB_PASSWORD=fixture',(out/'release.json').read_text());self.assertTrue((out/'backup-policy.json').is_file());c=json.loads((out/'compose.json').read_text());self.assertEqual('hearth-00000000000000000000000000000001',c['name']);self.assertEqual(4,len(c['services']));self.assertEqual('no',c['services']['economy']['restart'])
 def test_overlapping_paths_and_budget_or_port_overcommit_refuse(self):
  for change in ('root','cpu','port'):
   s=copy.deepcopy(self.spec)
   if change=='root':s['services']['lobby']['data']=s['services']['economy']['data']
   if change=='cpu':s['host_cpu']=1
   if change=='port':s['services']['postgres']['port']=26000
   with self.assertRaises(ValueError):release.review(s)
 def test_authentication_secret_drift_and_unpinned_image_refuse(self):
  s=copy.deepcopy(self.spec);s['services']['proxy']['image']='java:latest'
  with self.assertRaises(ValueError):release.review(s)
  (self.root/'economy/config/paper-global.yml').write_text('velocity:\n enabled: true\n online-mode: true\n secret: bad\n')
  with self.assertRaises(ValueError):release.review(self.spec)
 def test_existing_output_and_changed_source_preserve_candidate(self):
  out=self.root/'output';release.generate(self.spec,out)
  with self.assertRaises(ValueError):release.generate(self.spec,out)
  (self.root/'lobby/server.jar').write_bytes(b'changed')
  with self.assertRaises(ValueError):release.inspect(out/'release.json')
 def test_secrets_permissions_and_active_server_refuse(self):
  p=self.root/'proxy/runtime.env';p.chmod(0o644)
  with self.assertRaises(ValueError):release.review(self.spec)
  p.chmod(0o600);(self.root/'postgres/postmaster.pid').write_text('123')
  with self.assertRaises(ValueError):release.review(self.spec)

 def test_generated_launch_and_compose_artifacts_are_sealed(self):
  out=self.root/'output';release.generate(self.spec,out);f=out/'compose.json';f.chmod(0o600);f.write_text('{}')
  with self.assertRaises(ValueError):release.inspect(out/'release.json')

 def test_security_flags_in_other_sections_do_not_override_disabled_forwarding(self):
  p=self.root/'economy/config/paper-global.yml';p.write_text(p.read_text().replace('enabled: true','enabled: false')+'other:\n  enabled: true\n')
  with self.assertRaises(ValueError):release.review(self.spec)
