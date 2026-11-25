import os,subprocess,sys,unittest
from pathlib import Path
class FollowupProfileTest(unittest.TestCase):
 def invoke(self,*flags):
  script=Path(__file__).resolve().parent/'verify_auraskills.py'
  required=[]
  for name in ('workspace','source','java','plugin','probe','aura','aura-config'):required+=['--'+name,'/home/server/hearth-tmp/unused-followup-preflight']
  return subprocess.run([sys.executable,str(script),*required,*flags],capture_output=True,text=True,env=dict(os.environ,PYTHONDONTWRITEBYTECODE='1'),timeout=5)
 def test_incompatible_profiles_refuse_before_any_workspace_or_process(self):
  result=self.invoke('--followup','--platform');self.assertEqual(2,result.returncode);self.assertIn('mutually exclusive',result.stderr)
 def test_db_evidence_cannot_be_attached_to_a_different_profile(self):
  result=self.invoke('--followup-db-evidence','/home/server/hearth-tmp/unused-followup-preflight');self.assertEqual(2,result.returncode);self.assertIn('requires --followup',result.stderr)
