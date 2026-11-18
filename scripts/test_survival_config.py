import tempfile
import unittest
from pathlib import Path
import uuid
import stat
import isolated_paths
import survival_config as config
class SurvivalConfigTests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory(dir=isolated_paths.require_base(),prefix='survival-config.');self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)
 def test_fresh_private_profile_has_three_worlds_and_creation_is_opt_in(self):
  file=config.write(self.root/'game.yml',uuid.uuid4());text=file.read_text();self.assertIn('allow-create: false',text);self.assertIn('runtime-mode: survival',text);self.assertEqual(3,text.count('      key:'));self.assertEqual(0o600,stat.S_IMODE(file.stat().st_mode))
 def test_existing_file_and_symlink_original_are_preserved(self):
  file=self.root/'game.yml';file.write_text('original')
  with self.assertRaises(FileExistsError):config.write(file,uuid.uuid4(),True)
  self.assertEqual('original',file.read_text());link=self.root/'link';link.symlink_to(file)
  with self.assertRaises(FileExistsError):config.write(link,uuid.uuid4())
  self.assertEqual('original',file.read_text())
 def test_invalid_group_does_not_create_output(self):
  with self.assertRaises(ValueError):config.write(self.root/'game.yml','invalid')
  self.assertFalse((self.root/'game.yml').exists())
 def economy(self):
  return {'realm':str(uuid.uuid4()),'allow-new-realm':False,'content-file':'market.properties','db-host':'127.0.0.1','db-port':5432,'db-name':'hearth','db-user':'hearth','password-environment':'HEARTH_DB_PASSWORD'}
 def test_economy_overlay_is_explicit_and_preserves_three_worlds_without_secrets(self):
  settings=self.economy();file=config.write(self.root/'game.yml',uuid.uuid4(),False,settings);text=file.read_text();self.assertIn('survival-economy:',text);self.assertIn('allow-new-realm: false',text);self.assertIn('password-environment: "HEARTH_DB_PASSWORD"',text);self.assertEqual(3,text.count('      key:'))
 def test_invalid_database_or_content_path_publishes_nothing(self):
  for key,value in (('content-file','../data.properties'),('db-host','host/password'),('db-port',65536),('password-environment','secret=value'),('allow-new-realm','true')):
   settings=self.economy();settings[key]=value
   with self.assertRaises(ValueError):config.write(self.root/'game.yml',uuid.uuid4(),False,settings)
   self.assertFalse((self.root/'game.yml').exists())
 def test_yaml_reserved_database_names_remain_strings(self):
  import json
  settings=self.economy();settings.update({'db-user':'true','db-name':'null','db-host':'123'})
  text=config.configuration(uuid.uuid4(),False,settings)
  for key in ('db-user','db-name','db-host'):
   line=next(row for row in text.splitlines() if row.startswith('  '+key+': '))
   self.assertEqual(settings[key],json.loads(line.split(': ',1)[1]))
if __name__=='__main__':unittest.main()
