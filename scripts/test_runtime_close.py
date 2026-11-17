import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

spec=importlib.util.spec_from_file_location('runtime_close_monitor',Path(__file__).with_name('verify-s4-5.py'))
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class RuntimeCloseTests(unittest.TestCase):
    def completed(self,name,lines,code=0):
        monitor=module.Monitor.__new__(module.Monitor)
        monitor.prefix='HEARTH_LIFE'
        monitor.children={name:{'proc':SimpleNamespace(returncode=code,poll=lambda:code),'lines':lines}}
        monitor.pump=lambda:None
        return monitor

    def test_normal_story_clean_shutdown_uses_own_marker(self):
        m=self.completed('paper-story-runtime',['[INFO]: HEARTH_STORY_DISABLE_CLOSE PASS'])
        m.stop('paper-story-runtime','stop')

    def test_story_hold_or_ready_without_clean_close_is_rejected(self):
        for lines in [['HEARTH_STORY_DISABLE_CLOSE HOLD'],['HEARTH_STORY_RUNTIME_READY'],[]]:
            with self.subTest(lines=lines),self.assertRaises(AssertionError):
                self.completed('paper-story-runtime',lines).stop('paper-story-runtime','stop')

    def test_other_modes_cannot_satisfy_story_shutdown(self):
        for line in ['HEARTH_LIFE_DISABLE_CLOSE PASS','HEARTH_ECONOMY_DISABLE_CLOSE PASS']:
            with self.subTest(line=line),self.assertRaises(AssertionError):
                self.completed('paper-story-runtime',[line]).stop('paper-story-runtime','stop')

    def test_original_economy_and_verification_markers_remain_required(self):
        for name,marker in [('paper-economy-runtime','HEARTH_ECONOMY'),('paper-prepare','HEARTH_LIFE')]:
            self.completed(name,[marker+'_DISABLE_CLOSE PASS']).stop(name,'stop')
            with self.assertRaises(AssertionError):self.completed(name,['HEARTH_STORY_DISABLE_CLOSE PASS']).stop(name,'stop')

    def test_nonzero_exit_is_not_accepted_by_a_success_log(self):
        with self.assertRaises(AssertionError):self.completed('paper-story-runtime',['HEARTH_STORY_DISABLE_CLOSE PASS'],1).stop('paper-story-runtime','stop')


if __name__=='__main__':unittest.main()
