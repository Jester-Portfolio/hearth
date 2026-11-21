import tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import isolated_paths,platform_soak
class SoakTests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory(dir=isolated_paths.require_base(),prefix='platform-soak.');self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name);self.now=0
 def sleep(self,n):self.now+=n
 def run_observe(self,reader):
  with patch('runtime_health.inspect',side_effect=reader):return platform_soak.observe('log','boot',self.root/'samples.jsonl',3,1,5,18,50,1024,clock=lambda:self.now,sleep=self.sleep)
 def row(self,i):return dict(observed_ms=i,controller_ready=True,tps=20,mspt=3,heap_used_bytes=128)
 def test_new_healthy_samples_pass_without_workload_generation(self):
  r=self.run_observe([self.row(i) for i in range(3)]);self.assertEqual('PASS',r['outcome']);self.assertEqual(3,r['samples']);self.assertEqual(3,len((self.root/'samples.jsonl').read_text().splitlines()))
 def test_stale_repeated_or_unavailable_sample_fails(self):
  r=self.run_observe([self.row(1),self.row(1),ValueError('failure')]);self.assertEqual('FAIL',r['outcome']);self.assertEqual(1,r['failed_samples'])
 def test_over_budget_is_observed_and_limits_cannot_be_enlarged(self):
  rows=[self.row(i) for i in range(3)];rows[1]['mspt']=60;self.assertEqual('FAIL',self.run_observe(rows)['outcome'])
  with self.assertRaises(ValueError):platform_soak.observe('a','b',self.root/'other',551,1,5,18,50,1024)
