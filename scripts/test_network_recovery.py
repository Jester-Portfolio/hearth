import copy,hashlib,json,shutil,tempfile,unittest
from pathlib import Path
import isolated_paths,network_release,network_recovery
import test_network_release as fixtures
class NetworkRecoveryTests(unittest.TestCase):
 def setUp(self):
  self.fixture=fixtures.NetworkReleaseTest();self.fixture.setUp();self.addCleanup(self.fixture.doCleanups);self.root=self.fixture.root;self.spec=self.fixture.spec
  pg=self.root/'postgres';(pg/'global').mkdir(mode=0o700);(pg/'PG_VERSION').write_text('17\n');(pg/'global/pg_control').write_bytes(b'synthetic cold control')
  for role in ('lobby','economy'):
   p=self.root/role/'world';p.mkdir();(p/'session.lock').write_bytes(b'lock');(p/'region.mca').write_bytes(b'owned world '+role.encode())
  cold=self.root/'economy/world/.hearth-resource-archive/trial';cold.mkdir(parents=True);(cold/'region.mca').write_bytes(b'cold retained')
  self.manifest=self.root/'candidate/release.json';network_release.generate(self.spec,self.manifest.parent)
 def create(self):return network_recovery.create(self.manifest,self.root/'recovery',offline_confirmed=True)
 def test_four_role_snapshot_restores_cold_history_and_resets_all_acceptance(self):
  result=self.create();self.assertFalse(result['services_started']);target=self.root/'restored';restored=network_recovery.restore(self.root/'recovery',target,result['recovery_sha256']);self.assertTrue(restored['acceptance_reset']);self.assertFalse(restored['services_started']);self.assertEqual(b'cold retained',(target/'economy/world/.hearth-resource-archive/trial/region.mca').read_bytes());self.assertEqual(b'owned world lobby',(target/'lobby/world/region.mca').read_bytes());self.assertFalse(network_release.inspect(target/'candidate/release.json')['acceptance_complete']);spec=json.loads((target/'restored-review.json').read_text());self.assertTrue(all(s['data']==str(target/role) for role,s in spec['services'].items()));self.assertEqual({'proxy','lobby','economy','postgres'},set(spec['services']))
 def test_historical_snapshot_does_not_depend_on_original_paths(self):
  result=self.create()
  for role in network_release.ROLES:shutil.move(self.root/role,self.root/('old_'+role))
  network_recovery.verify(self.root/'recovery',result['recovery_sha256']);network_recovery.restore(self.root/'recovery',self.root/'new-paths',result['recovery_sha256'])
 def test_proxy_active_marker_is_preserved_and_creates_no_snapshot(self):
  p=self.root/'proxy/plugins/hearth-network/proxy-runtime.active';p.write_text('running')
  with self.assertRaises((ValueError,network_recovery.backup.BackupError)):self.create()
  self.assertEqual('running',p.read_text());self.assertFalse((self.root/'recovery').exists())
 def test_existing_restore_and_wrong_receipt_are_never_overwritten(self):
  result=self.create();target=self.root/'existing';target.mkdir();(target/'keep').write_text('preserve')
  with self.assertRaises(FileExistsError):network_recovery.restore(self.root/'recovery',target,result['recovery_sha256'])
  self.assertEqual('preserve',(target/'keep').read_text())
  with self.assertRaises(network_recovery.backup.BackupError):network_recovery.verify(self.root/'recovery','0'*64)
 def test_snapshot_member_tampering_refuses_before_restore(self):
  result=self.create();p=self.root/'recovery/bundle/payload/lobby/world/region.mca';p.write_bytes(b'changed')
  with self.assertRaises(network_recovery.backup.BackupError):network_recovery.restore(self.root/'recovery',self.root/'rejected',result['recovery_sha256'])
  self.assertFalse((self.root/'rejected').exists())
 def test_source_and_snapshot_destination_overlap_refuses(self):
  with self.assertRaises(network_recovery.backup.BackupError):network_recovery.create(self.manifest,self.root/'economy/snapshot',offline_confirmed=True)
