"""Synthetic operator reports exercise the journal contract, never Minecraft acceptance."""
import copy,json,unittest
from concurrent.futures import ThreadPoolExecutor
from unittest import mock
import network_acceptance as acceptance,network_release as release,network_recovery as recovery
import test_network_release as fixtures

class NetworkAcceptanceTests(unittest.TestCase):
 def setUp(self):
  self.fixture=fixtures.NetworkReleaseTest();self.fixture.setUp();self.addCleanup(self.fixture.doCleanups)
  self.root=self.fixture.root;self.spec=self.fixture.spec
  self.binding=self.root/'economy/plugins/Hearth/native-economy.properties'
  self.binding.write_text('realm=00000000-0000-0000-0000-000000000002\ngroup=00000000-0000-0000-0000-000000000003\n')
  pg=self.root/'postgres';(pg/'global').mkdir();(pg/'global/pg_control').write_bytes(b'synthetic cold control');(pg/'PG_VERSION').write_text('17\n')
  self.candidate=self.root/'candidate/release.json';release.generate(self.spec,self.candidate.parent)
  self.dossier=self.root/'dossier';acceptance.initialize(self.candidate,self.dossier)
  self.report=self.root/'report.json';self.report.write_text(json.dumps({'outcome':'PASS','fixture':'synthetic unit report only'}))
 def head(self):return release.digest(self.dossier/'state/head.json')
 def record(self,kind='client',outcome='PASS',expected=None):
  return acceptance.add(self.dossier,expected or self.head(),kind,outcome,acceptance.METHODS[kind],'synthetic-unit-report',self.report,release.digest(self.report))
 def pair(self):
  receipt=recovery.create(self.candidate,self.root/'snapshot',offline_confirmed=True)
  return acceptance.rollback(self.dossier,self.head(),self.root/'snapshot',receipt['recovery_sha256'])
 def ready(self):
  for k in acceptance.KINDS:self.record(k)
  return self.pair()
 def test_boolean_flags_cannot_replace_missing_evidence(self):
  spec=copy.deepcopy(self.spec);spec['acceptance']={k:True for k in acceptance.KINDS}
  with self.assertRaisesRegex(ValueError,'EVIDENCE_REQUIRED'):release.generate(spec,self.root/'boolean-only')
  with self.assertRaisesRegex(ValueError,'NOT_READY'):acceptance.seal(self.dossier,self.head(),self.root/'not-ready')
  self.assertFalse((self.root/'boolean-only').exists());self.assertFalse((self.root/'not-ready').exists())
 def test_observation_or_failed_report_cannot_claim_actual_client_or_load(self):
  for body in ({'outcome':'PASS','actual_client':'NOT_RUN'},{'outcome':'FAIL'}):
   self.report.write_text(json.dumps(body))
   for kind in ('client','load'):
    with self.assertRaisesRegex(ValueError,'NOT_AN_ACCEPTANCE_PASS'):self.record(kind)
  self.assertEqual(0,json.loads((self.dossier/'state/head.json').read_text())['revision'])
 def test_cas_allows_only_one_concurrent_writer(self):
  old=self.head()
  def write(_):
   try:self.record(expected=old);return 'PASS'
   except ValueError as e:return str(e)
  with ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(write,range(2)))
  self.assertCountEqual(['PASS','DOSSIER_CAS'],results)
  self.assertEqual(1,len(list((self.dossier/'events').iterdir())))
 def test_scope_binds_budget_inputs_and_native_identity(self):
  for change in ('budget','jar','binding'):
   with self.subTest(change=change):
    if change=='budget':
     spec=copy.deepcopy(self.spec);spec['host_cpu']=5;other=release.generate(spec,self.root/'other')
     self.assertNotEqual(release.artifacts(self.candidate)['context']['scope'],other['context']['scope'])
     with self.assertRaisesRegex(ValueError,'NOT_READY'):acceptance.certificate(self.dossier,self.head(),other['context']['scope'])
    else:
     path=self.binding if change=='binding' else self.root/'economy/server.jar';original=path.read_bytes();path.write_bytes(original+b'\n#changed')
     with self.assertRaisesRegex(ValueError,'CHANGED'):acceptance.status(self.dossier)
     path.write_bytes(original)
 def test_late_failure_revokes_sealed_candidate_and_history_is_preserved(self):
  self.assertTrue(self.ready()['ready_for_review']);sealed=self.root/'sealed';profile=acceptance.seal(self.dossier,self.head(),sealed)
  self.assertTrue(release.inspect(sealed/'release.json')['acceptance_complete'])
  with self.assertRaisesRegex(ValueError,'UNACCEPTED_BASE_REQUIRED'):acceptance.initialize(sealed/'release.json',self.root/'recursive-dossier')
  self.assertTrue(profile['attestation']);self.record('client','FAIL')
  self.assertFalse(acceptance.status(self.dossier)['ready_for_review'])
  with self.assertRaisesRegex(ValueError,'HEAD_CHANGED'):release.inspect(sealed/'release.json')
  self.assertEqual(6,len(list((self.dossier/'events').iterdir())))
  self.assertTrue((self.dossier/'events/000001.json').exists())
 def test_recorded_artifact_is_checked_and_copied_before_source_disappears(self):
  with self.assertRaisesRegex(ValueError,'SOURCE_CHANGED'):acceptance.add(self.dossier,self.head(),'client','PASS','authenticated-client','unit',self.report,'0'*64)
  self.record();self.report.unlink();self.assertEqual('PASS',acceptance.status(self.dossier)['states']['client'])
  saved=next((self.dossier/'evidence').iterdir());saved.write_bytes(b'tamper')
  with self.assertRaisesRegex(ValueError,'EVIDENCE_CHANGED'):acceptance.status(self.dossier)
 def test_event_before_head_interruption_requires_explicit_resume(self):
  old=self.head()
  with mock.patch.object(acceptance,'publish',side_effect=OSError('injected interruption')):
   with self.assertRaises(OSError):self.record()
  self.assertEqual(old,self.head());self.assertTrue(acceptance.status(self.dossier)['pending_event'])
  with self.assertRaisesRegex(ValueError,'RESUME_REQUIRED'):self.record()
  result=acceptance.resume(self.dossier,old);self.assertEqual('PASS',result['states']['client']);self.assertNotEqual(old,self.head())
  self.assertFalse((self.dossier/'state/pending.json').exists())
 def test_marker_before_evidence_retries_identical_intent_without_new_decision(self):
  write=acceptance.write_new
  def fail_evidence(path,data):
   if path.parent.name=='evidence':raise OSError('injected interruption')
   write(path,data)
  with mock.patch.object(acceptance,'write_new',side_effect=fail_evidence):
   with self.assertRaises(OSError):self.record()
  marker=(self.dossier/'state/pending.json').read_bytes()
  with self.assertRaisesRegex(ValueError,'PENDING_EVIDENCE_MISSING'):acceptance.resume(self.dossier,self.head())
  with self.assertRaisesRegex(ValueError,'PENDING_CONFLICT'):self.record(outcome='FAIL')
  self.record();self.assertEqual(json.loads(marker),json.loads((self.dossier/'events/000001.json').read_text()))
 def test_head_committed_marker_remaining_resumes_without_extra_event(self):
  self.record();event=(self.dossier/'events/000001.json').read_bytes();acceptance.write_new(self.dossier/'state/pending.json',event);old=self.head()
  self.assertTrue(acceptance.status(self.dossier)['pending_event']);acceptance.resume(self.dossier,old)
  self.assertEqual(old,self.head());self.assertEqual(1,len(list((self.dossier/'events').iterdir())))
 def test_foreign_economy_snapshot_cannot_be_used_as_rollback(self):
  original=self.binding.read_bytes();self.binding.write_text(original.decode().replace('000000000003','000000000004'))
  other=self.root/'foreign';release.generate(self.spec,other)
  receipt=recovery.create(other/'release.json',self.root/'foreign-snapshot',offline_confirmed=True);self.binding.write_bytes(original)
  with self.assertRaisesRegex(ValueError,'ROLLBACK_IDENTITY'):acceptance.rollback(self.dossier,self.head(),self.root/'foreign-snapshot',receipt['recovery_sha256'])
 def test_sealed_snapshot_preserves_private_audit_and_restore_requires_new_scope(self):
  self.ready();sealed=self.root/'sealed';acceptance.seal(self.dossier,self.head(),sealed)
  receipt=recovery.create(sealed/'release.json',self.root/'sealed-snapshot',offline_confirmed=True)
  recovery.verify(self.root/'sealed-snapshot',receipt['recovery_sha256'])
  audit=self.root/'sealed-snapshot/bundle/payload/assets/audit';self.assertTrue((audit/'events/000005.json').is_file());self.assertTrue((audit/'head.json').is_file())
  recovery.restore(self.root/'sealed-snapshot',self.root/'restored',receipt['recovery_sha256'])
  restored=release.artifacts(self.root/'restored/candidate/release.json');self.assertFalse(restored['acceptance_complete']);self.assertNotEqual(restored['context']['scope'],release.artifacts(self.candidate)['context']['scope'])
 def test_missing_native_identity_does_not_certify_unprepared_economy(self):
  self.binding.unlink();out=self.root/'unprepared';release.generate(self.spec,out);d=self.root/'unprepared-dossier';acceptance.initialize(out/'release.json',d)
  self.assertIsNone(acceptance.status(d)['identity']['realm']);self.assertFalse(acceptance.status(d)['ready_for_review'])
