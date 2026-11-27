import copy
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import isolated_paths
import project_database as database

SCRIPTS=Path(__file__).resolve().parent
PROFILE=SCRIPTS.parent/'infrastructure/deployment/hearth-postgres.json'


class ProjectDatabaseTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(dir=isolated_paths.require_base(),prefix='project-database.')
        self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)
        self.profile=json.loads(PROFILE.read_text())

    def cli(self,*args):
        return subprocess.run([sys.executable,str(SCRIPTS/'survival_config.py'),*args],capture_output=True,text=True,timeout=10)

    def test_tracked_profile_is_valid_and_loader_never_opens_credentials(self):
        reader=Path.read_text
        calls=[]
        def tracked(path,*a,**kw):
            calls.append(path)
            return reader(path,*a,**kw)
        with patch.object(Path,'read_text',tracked):
            loaded=database.load(PROFILE)
        self.assertEqual([PROFILE],calls)
        self.assertEqual(5433,loaded['connection']['db-port'])
        self.assertNotIn('password',loaded['connection'])

    def test_unknown_secret_keys_unsafe_paths_and_unpinned_images_are_rejected(self):
        cases=[('connection','password','do-not-export'),('deployment','image','postgres:latest'),
               ('deployment','password-file','/other/secrets/app.password'),('connection','db-host','0.0.0.0'),
               ('deployment','uid',True),('deployment','data','/home/hearth-services/postgres/../data'),
               ('deployment','resource-limits-selected',True)]
        for section,key,value in cases:
            with self.subTest(key=key):
                p=copy.deepcopy(self.profile);p[section][key]=value
                with self.assertRaises(ValueError):database.validate(p)

    def test_duplicate_keys_and_symlink_profiles_are_rejected(self):
        p=self.root/'profile.json';p.write_text('{"format":1,"format":1}')
        with self.assertRaises(ValueError):database.load(p)
        p.unlink();p.symlink_to(PROFILE)
        with self.assertRaises(ValueError):database.load(p)

    def test_compose_environment_uses_same_connection_without_approval_or_password(self):
        p=self.root/'pg.env';database.write_environment(self.profile,p)
        text=p.read_text()
        self.assertIn('HEARTH_PG_PORT=5433\n',text)
        self.assertIn('HEARTH_PG_DATA='+self.profile['deployment']['data']+'\n',text)
        self.assertNotIn('PASSWORD',text);self.assertNotIn('APPROVAL',text)
        self.assertEqual(0o600,stat.S_IMODE(p.stat().st_mode))
        with self.assertRaises(FileExistsError):database.write_environment(self.profile,p)
        self.assertEqual(text,p.read_text())

    def test_profile_wires_existing_economy_generator_and_keeps_creation_opt_in(self):
        p=self.root/'game.yml'
        result=self.cli('--output',str(p),'--with-economy','--database-profile',str(PROFILE),
                        '--group-id','00000000-0000-0000-0000-000000000001','--economy-realm','00000000-0000-0000-0000-000000000002')
        self.assertEqual(0,result.returncode,result.stderr)
        text=p.read_text()
        self.assertIn('db-port: 5433',text);self.assertIn('db-name: "hearth_economy"',text)
        self.assertIn('password-environment: "HEARTH_DB_PASSWORD"',text)
        self.assertIn('allow-create: false',text);self.assertIn('allow-new-realm: false',text)
        self.assertIn('group-id: 00000000-0000-0000-0000-000000000001',text)
        self.assertEqual(3,text.count('      key:'))
        self.assertNotIn('app.password',text)

    def test_profile_conflicting_overrides_and_missing_economy_publish_nothing(self):
        p=self.root/'game.yml'
        for extra in ([],['--with-economy','--db-port','5432'],['--with-economy','--db-user','other']):
            result=self.cli('--output',str(p),'--database-profile',str(PROFILE),*extra)
            self.assertEqual(2,result.returncode);self.assertFalse(p.exists())
            self.assertEqual('SURVIVAL_CONFIG_REFUSED\n',result.stderr)

    def test_manual_database_and_legacy_defaults_still_work_without_profile(self):
        for name,extra,port in [('default',[],5432),('custom',['--db-port','25433'],25433)]:
            p=self.root/(name+'.yml');r=self.cli('--output',str(p),'--with-economy',*extra)
            self.assertEqual(0,r.returncode,r.stderr);self.assertIn('db-port: '+str(port),p.read_text())


if __name__=='__main__':unittest.main()
