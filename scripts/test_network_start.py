import hashlib,os,subprocess,tempfile,unittest
from pathlib import Path
import isolated_paths,network_release
class NetworkStartTests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory(dir=isolated_paths.require_base(),prefix='network-start.');self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name);self.helper=Path(__file__).resolve().parent.parent/'infrastructure/deployment/runtime-env.sh'
 def env_file(self,password):
  p=self.root/'runtime.env';p.write_text('HEARTH_VELOCITY_SECRET='+'a'*64+'\nHEARTH_NETWORK_SECRET='+'b'*64+'\nHEARTH_DB_PASSWORD='+password+'\n');p.chmod(0o600);return p
 def test_literal_password_preserves_dollars_quotes_spaces_and_equals(self):
  value='a$UNSET_NAME \'quoted\' "double" # =tail';p=self.env_file(value);expected=hashlib.sha256(value.encode()).hexdigest();r=subprocess.run(['/bin/sh','-c','. "$1"; hearth_load_env "$2" || exit 2; printf %s "$HEARTH_DB_PASSWORD" | sha256sum','fixture',str(self.helper),str(p)],capture_output=True,text=True);self.assertEqual(0,r.returncode);self.assertEqual(expected,r.stdout.split()[0]);self.assertEqual(value,network_release.read_env(p)['HEARTH_DB_PASSWORD'])
 def test_duplicate_or_executable_env_text_is_never_evaluated(self):
  p=self.env_file('$(touch NEVER_EXECUTE)');p.write_text(p.read_text()+'HEARTH_DB_PASSWORD=duplicate\n')
  with self.assertRaises(ValueError):network_release.read_env(p)
  r=subprocess.run(['/bin/sh','-c','. "$1"; hearth_load_env "$2"','fixture',str(self.helper),str(p)],cwd=self.root,capture_output=True);self.assertNotEqual(0,r.returncode);self.assertFalse((self.root/'NEVER_EXECUTE').exists())
 def pg_manifest(self,accepted):
  f=self.root/'PG_VERSION';f.write_text('17\n');m=self.root/'postgres.properties';m.write_text('format=2\nrole=postgres\nacceptance-complete='+str(accepted).lower()+'\njar=\nfile.PG_VERSION='+hashlib.sha256(f.read_bytes()).hexdigest()+'\n');return m
 def check_pg(self,m):return subprocess.run(['/bin/sh','-c','. "$1"; hearth_pg_manifest "$2" "$3" "$4"','fixture',str(self.helper),str(self.root),str(m),hashlib.sha256(m.read_bytes()).hexdigest()],capture_output=True).returncode
 def test_postgres_has_its_own_acceptance_and_file_hash_gate(self):
  m=self.pg_manifest(False);self.assertNotEqual(0,self.check_pg(m));m=self.pg_manifest(True);self.assertEqual(0,self.check_pg(m));(self.root/'PG_VERSION').write_text('18\n');self.assertNotEqual(0,self.check_pg(m))
 def reviewed(self):
  head=self.root/'acceptance-state/head.json';head.parent.mkdir();head.write_text('synthetic current result')
  cert=self.root/'acceptance.properties';cert.write_bytes(network_release.certificate_bytes({'scope':'a'*64},{'head':hashlib.sha256(head.read_bytes()).hexdigest(),**{k:'b'*64 for k in ('client','load','restore','release_regression','rollback')}}))
  return head,cert
 def manifested(self,cert):
  m=self.pg_manifest(True);m.write_text(m.read_text().replace('format=2','format=3')+'scope='+'a'*64+'\nacceptance-sha256='+hashlib.sha256(cert.read_bytes()).hexdigest()+'\n');return m
 def test_current_head_and_pending_update_gate_postgres_like_java_roles(self):
  head,cert=self.reviewed();m=self.manifested(cert);self.assertEqual(0,self.check_pg(m));original=head.read_bytes();head.write_text('new FAIL');self.assertNotEqual(0,self.check_pg(m));head.write_bytes(original)
  (head.parent/'pending.json').write_text('interrupted result');self.assertNotEqual(0,self.check_pg(m));self.assertTrue((head.parent/'pending.json').exists())
 def test_duplicate_evidence_and_scope_mismatch_do_not_pass_certificate(self):
  head,cert=self.reviewed();original=cert.read_text();cert.write_text(original.replace('load=','client='));m=self.manifested(cert);self.assertNotEqual(0,self.check_pg(m))
  cert.write_text(original.replace('scope='+'a'*64,'scope='+'c'*64));m=self.manifested(cert);self.assertNotEqual(0,self.check_pg(m))
 def test_format4_postgres_keeps_certificate_gate_and_expected_empty_plugin_inventory(self):
  head,cert=self.reviewed();m=self.manifested(cert);text=m.read_text().replace('format=3','format=4')+'critical-sha256='+hashlib.sha256(b'').hexdigest()+'\n';m.write_text(text);self.assertEqual(0,self.check_pg(m));m.write_text(text.replace(hashlib.sha256(b'').hexdigest(),'a'*64));self.assertNotEqual(0,self.check_pg(m))
