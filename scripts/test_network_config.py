import tempfile
import unittest
from pathlib import Path
import json
import uuid
import stat
import isolated_paths
import network_config as config
class NetworkConfigTests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory(dir=isolated_paths.require_base(),prefix='network-config.');self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name);self.ports={'proxy':25595,'lobby':25596,'economy':25597};self.lobby={'world-uuid':str(uuid.uuid4()),'world-key':'minecraft:overworld','region':'lobby','entry-x':2.5,'entry-y':64,'entry-z':2.5,'entry-yaw':0,'entry-pitch':0}
 def generate(self,path='new',economy='runtime-mode: economy\neconomy-runtime:\n  db-port: 34567\n'):
  return config.generate(self.root/path,uuid.uuid4(),self.ports,self.lobby,economy,4)
 def test_private_fresh_outputs_have_modern_auth_fixed_routes_and_no_auto_apply(self):
  result=self.generate();root=self.root/'new';self.assertFalse(result['operating_applied']);self.assertEqual(0o700,stat.S_IMODE(root.stat().st_mode))
  for name in result['files']:self.assertEqual(0o600,stat.S_IMODE((root/name).stat().st_mode))
  text=(root/'proxy/velocity.toml').read_text();self.assertIn('online-mode = true',text);self.assertIn('try = ["lobby"]',text);self.assertIn('bungee-plugin-message-channel = false',text)
  self.assertEqual((root/'secrets/forwarding.secret').read_bytes(),(root/'proxy/forwarding.secret').read_bytes());self.assertNotEqual((root/'secrets/relay.secret').read_bytes(),(root/'secrets/forwarding.secret').read_bytes());self.assertNotIn((root/'secrets/relay.secret').read_text().strip(),json.dumps(result))
 def test_survival_backend_keeps_three_world_profile_and_economy_route(self):
  self.generate(economy='runtime-mode: survival\nsurvival-runtime:\n  allow-create: false\n')
  text=(self.root/'new/economy/plugins/Hearth/config.yml').read_text();self.assertIn('runtime-mode: survival',text);self.assertIn('allow-create: false',text);self.assertIn('server-id: economy',text)
 def test_existing_output_is_preserved(self):
  self.generate();original=(self.root/'new/network-bundle.json').read_bytes()
  with self.assertRaises(ValueError):self.generate()
  self.assertEqual(original,(self.root/'new/network-bundle.json').read_bytes())
 def test_invalid_or_conflicting_ports_publish_nothing(self):
  for value in (25595,80):
   self.ports['economy']=value
   with self.assertRaises(ValueError):self.generate()
   self.assertFalse((self.root/'new').exists())
 def test_story_and_verification_profiles_are_not_silently_converted(self):
  for text in ('runtime-mode: story\n','runtime-mode: economy\nverification-enabled: true\n'):
   with self.assertRaises(ValueError):self.generate(economy=text)
 def test_role_network_overlay_preserves_economic_settings_and_removes_old_network(self):
  self.generate(economy='runtime-mode: economy\nnetwork:\n  enabled: false\neconomy-runtime:\n  db-port: 34567\n')
  text=(self.root/'new/economy/plugins/Hearth/config.yml').read_text();self.assertEqual(1,text.count('network:\n'));self.assertIn('db-port: 34567',text);self.assertIn('server-id: economy',text)
 def test_invalid_lobby_coordinates_and_symlink_parents_are_refused(self):
  self.lobby['entry-y']=float('nan')
  with self.assertRaises(ValueError):self.generate()
  self.lobby['entry-y']=64;(self.root/'link').symlink_to(self.root,target_is_directory=True)
  with self.assertRaises(ValueError):self.generate('link/new')
if __name__=='__main__':unittest.main()
