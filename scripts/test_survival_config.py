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
if __name__=='__main__':unittest.main()
