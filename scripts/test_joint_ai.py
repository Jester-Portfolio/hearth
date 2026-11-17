import unittest
import time
from pathlib import Path
from types import SimpleNamespace
from joint_ai import descendants,current,totals,JointTrial,live_roots

class JointBudgetTests(unittest.TestCase):
    def test_separate_backend_tree_does_not_treat_Paper_or_PG_as_AI(self):
        rows={1:dict(parent=0,start='1',rss_kib=10),2:dict(parent=1,start='2',rss_kib=100),3:dict(parent=2,start='3',rss_kib=200),4:dict(parent=1,start='4',rss_kib=300),5:dict(parent=4,start='5',rss_kib=50),6:dict(parent=0,start='6',rss_kib=900)}
        ai=descendants(rows,{2});joint=descendants(rows,{1})
        self.assertEqual({2,3},ai);self.assertEqual({1,2,3,4,5},joint);self.assertEqual((300,660),totals(rows,ai,joint,0))
    def test_cumulative_owned_tree_keeps_orphan_but_rejects_reused_pid(self):
        rows={3:dict(parent=0,start='original',rss_kib=1),4:dict(parent=0,start='new',rss_kib=1)}
        self.assertEqual({3},current(rows,{3:'original',4:'old',5:'gone'}))
    def test_absent_roots_and_unrelated_host_processes_are_not_owned(self):
        rows={3:dict(parent=2,start='3',rss_kib=5),5:dict(parent=0,start='5',rss_kib=99)}
        self.assertEqual(set(),descendants(rows,{1}));self.assertEqual({3},descendants(rows,{3}))
    def test_joint_limit_is_not_sum_of_separate_maxima_or_separate_clocks(self):
        rows={1:dict(rss_kib=4*1024**2),2:dict(rss_kib=2*1024**2)}
        self.assertEqual((4*1024**2,6*1024**2),totals(rows,{1},{1,2},5120))
        rows[2]['rss_kib']+=1
        with self.assertRaisesRegex(RuntimeError,'JOINT_RSS'):totals(rows,{1},{1,2},0)
    def test_reaped_or_reused_root_pid_cannot_claim_foreign_process_tree(self):
        rows={10:dict(parent=0,start='new',rss_kib=10),11:dict(parent=10,start='child',rss_kib=10),20:dict(parent=0,start='owned',rss_kib=10)}
        roots=live_roots(rows,[(10,'old',True),(10,'new',False),(20,'owned',True),(30,'gone',True)])
        self.assertEqual({20},roots)
        self.assertEqual({20},descendants(rows,roots))
    def test_AI_uses_original_native_clock_without_granting_new_work_time(self):
        started=time.monotonic()-551
        trial=JointTrial(Path('.'),SimpleNamespace(started=started))
        self.assertEqual(started,trial.started)
        with self.assertRaisesRegex(RuntimeError,'WORK_DEADLINE'):trial.guard()
        self.assertEqual({},trial.owned)
    def test_existing_individual_AI_and_GPU_caps_remain(self):
        rows={1:dict(rss_kib=4*1024**2+1)}
        with self.assertRaisesRegex(RuntimeError,'AI_RSS'):totals(rows,{1},{1},0)
        with self.assertRaisesRegex(RuntimeError,'GPU'):totals({},set(),set(),5121)

if __name__=='__main__':unittest.main()
