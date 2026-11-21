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
