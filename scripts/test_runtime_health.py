import json
import tempfile
from pathlib import Path
import unittest
import uuid
import isolated_paths
import runtime_health as health

class RuntimeHealthTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir=isolated_paths.require_base(),prefix='runtime-health.')
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.boot = str(uuid.uuid4())
        self.boot_file = self.root/'boot'
        self.boot_file.write_text(self.boot+'\n')
        self.log = self.root/'latest.log'
        self.row = dict(format=1,boot=self.boot,pid=19,observed_ms=100000,mode='story',phase='READY',controller_ready=True,worlds=2,held_worlds=0,ai_preparation_recorded=False,tps=20,mspt=4,heap_used_bytes=1024)
    def test_platform_hold_overrides_live_controller_and_schema_is_bounded(self):
        platform=dict(enabled=1,ready=1,maintenance=0,config_busy=0,config_held=0,vault_held=0,job_held=0,io_pending=0,sales=0,generation=0,reset_pending=0)
        self.row.update(format=2,platform=platform);self.write();self.assertTrue(self.read()['controller_ready'])
        platform['job_held']=1;self.row['controller_ready']=False;self.write();self.assertFalse(self.read()['controller_ready'])
        platform['private_player']=3;self.write()
        with self.assertRaises(health.Unavailable):self.read()
    def write(self,row=None,prefix='[01:02:03] [Server thread/INFO]: [Hearth] HEARTH_RUNTIME_REPORT '):
        self.log.write_text(prefix+json.dumps(self.row if row is None else row)+'\n')
    def read(self,**args):
        return health.inspect(self.log,self.boot_file,'story',10,**{'now':105,**args})
    def test_live_controller_report_does_not_claim_ai_preparation(self):
        self.write()
        self.assertTrue(self.read()['controller_ready'])
        self.assertFalse(self.read()['ai_preparation_recorded'])
    def test_lobby_report_is_checked_with_its_own_expected_role(self):
        self.row['mode']='lobby';self.write()
        self.assertTrue(health.inspect(self.log,self.boot_file,'lobby',10,now=105)['controller_ready'])
        with self.assertRaisesRegex(health.Unavailable,'MISMATCH'):self.read()
    def test_survival_role_reports_three_native_worlds(self):
        self.row.update(mode='survival',worlds=3);self.write()
        self.assertTrue(health.inspect(self.log,self.boot_file,'survival',10,now=105)['controller_ready'])
    def test_stale_future_and_other_boot_reports_fail(self):
        self.write()
        for now in (120,90):
            with self.assertRaisesRegex(health.Unavailable,'STALE'):self.read(now=now)
        self.row['boot']=str(uuid.uuid4());self.write()
        with self.assertRaisesRegex(health.Unavailable,'MISMATCH'):self.read()
    def test_player_chat_and_non_server_thread_cannot_supply_reports(self):
        for prefix in ('[01:02:03] [Server thread/INFO]: <name> HEARTH_RUNTIME_REPORT ','[01:02:03] [Async Chat Thread/INFO]: [Hearth] HEARTH_RUNTIME_REPORT '):
            self.write(prefix=prefix)
            with self.assertRaisesRegex(health.Unavailable,'MISSING'):self.read()
    def test_latest_hold_and_closed_override_previous_ready(self):
        self.write();prior=self.log.read_text()
        for phase in ('HOLD','CLOSED'):
            self.row.update(phase=phase,controller_ready=False);self.write();self.log.write_text(prior+self.log.read_text())
            self.assertFalse(self.read()['controller_ready'])
    def test_unknown_private_fields_duplicates_and_invalid_metrics_fail(self):
        for key,value in (('player','private'),('tps',float('nan')),('pid',True),('controller_ready',False),('held_worlds',3)):
            row={**self.row,key:value};self.write(row)
            with self.assertRaises(health.Unavailable):self.read()
        self.write();self.log.write_text(self.log.read_text().replace('"format": 1','"format": 1,"format": 1'))
        with self.assertRaisesRegex(health.Unavailable,'DUPLICATE'):self.read()
    def test_partial_line_is_ignored_but_new_complete_error_refuses(self):
        self.write();prior=self.log.read_text();self.log.write_text(prior+'[01:02:04] [Server thread/INFO]: [Hearth] HEARTH_RUNTIME_REPORT {')
        self.assertTrue(self.read()['controller_ready'])
        self.log.write_text(prior+'[01:02:05] [Server thread/WARN]: [Hearth] HEARTH_RUNTIME_REPORT_UNAVAILABLE\n')
        with self.assertRaisesRegex(health.Unavailable,'UNAVAILABLE'):self.read()
    def test_new_complete_malformed_report_does_not_fall_back_to_old_ready(self):
        self.write();self.log.write_text(self.log.read_text()+'[01:02:04] [Server thread/INFO]: [Hearth] HEARTH_RUNTIME_REPORT {\n')
        with self.assertRaises(ValueError):self.read()
    def test_symlink_and_nonregular_sources_are_rejected(self):
        self.write();alias=self.root/'alias';alias.symlink_to(self.log)
        with self.assertRaises(health.Unavailable):health.inspect(alias,self.boot_file,'story',10,now=105)
        with self.assertRaises(health.Unavailable):health.inspect(self.root,self.boot_file,'story',10,now=105)
    def test_bounded_tail_retains_latest_report_and_never_uses_ancient_report(self):
        self.write();line=self.log.read_text();self.log.write_text('x'*health.LIMIT+'\n'+line)
        self.assertTrue(self.read()['controller_ready'])
        self.log.write_text(line+'x'*health.LIMIT+'\n')
        with self.assertRaisesRegex(health.Unavailable,'MISSING'):self.read()

if __name__=='__main__':unittest.main()
