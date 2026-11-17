import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import isolated_paths
import offline_backup as backup


class OfflineBackupTests(unittest.TestCase):
    def setUp(self):
        self.space=tempfile.TemporaryDirectory(prefix='hearth-backup-unit.',dir=isolated_paths.require_base())
        self.root=Path(self.space.name)
        self.paper=self.root/'paper';self.paper.mkdir(mode=0o700)
        self.pg=self.root/'postgres';(self.pg/'global').mkdir(parents=True,mode=0o700)
        (self.pg/'PG_VERSION').write_text('17\n');(self.pg/'global/pg_control').write_bytes(b'control')
        (self.paper/'world').mkdir();(self.paper/'world/session.lock').write_bytes(b'world-lock')
        (self.paper/'world/region.mca').write_bytes(b'region original')
        (self.paper/'settings.yml').write_text('private: retained locally\n')
        (self.paper/'empty').mkdir()
        self.roots={'paper':self.paper,'postgres':self.pg}
        self.bundle=self.root/'bundle'

    def tearDown(self):self.space.cleanup()
    def pack(self,**options):return backup.create(self.roots,self.bundle,offline_confirmed=True,**options)
    def alter_manifest(self,change):
        path=self.bundle/'manifest.json';data=json.loads(path.read_text());change(data)
        raw=json.dumps(data).encode();path.write_bytes(raw);return hashlib.sha256(raw).hexdigest()

    def test_complete_bundle_restore_keeps_bytes_empty_dirs_private_modes_and_no_services(self):
        result=self.pack();manifest=backup.verify(self.bundle,result['manifest_sha256'])
        out=self.root/'restored';receipt=backup.restore(self.bundle,out,result['manifest_sha256'])
        self.assertFalse(receipt['services_started']);self.assertTrue((out/'paper/empty').is_dir())
        for label,source in self.roots.items():
            for f in source.rglob('*'):
                target=out/label/f.relative_to(source)
                if f.is_file():self.assertEqual(f.read_bytes(),target.read_bytes());self.assertEqual(0,target.stat().st_mode&0o077)
        self.assertEqual(manifest['bytes'],result['bytes']);self.assertEqual(0,self.bundle.stat().st_mode&0o077)
        backup.verify(self.bundle,result['manifest_sha256'])

    def test_unconfirmed_or_live_cluster_preserves_source_and_creates_no_bundle(self):
        with self.assertRaisesRegex(backup.BackupError,'OFFLINE_CONFIRMATION'):backup.create(self.roots,self.bundle)
        (self.pg/'postmaster.pid').write_text('123')
        with self.assertRaisesRegex(backup.BackupError,'CLEANLY_STOPPED'):self.pack()
        self.assertFalse(self.bundle.exists());self.assertEqual(b'region original',(self.paper/'world/region.mca').read_bytes())

    def test_actual_posix_world_lock_blocks_copy(self):
        script="import fcntl,sys;f=open(sys.argv[1],'r+b');fcntl.lockf(f,fcntl.LOCK_EX);print('ready',flush=True);sys.stdin.read()"
        p=subprocess.Popen([sys.executable,'-c',script,str(self.paper/'world/session.lock')],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
        try:
            self.assertEqual('ready',p.stdout.readline().strip())
            with self.assertRaisesRegex(backup.BackupError,'WORLD_IS_LOCKED'):self.pack()
            self.assertFalse(self.bundle.exists())
        finally:p.communicate('finish',timeout=3)

    def test_existing_backup_and_restore_destination_never_overwritten(self):
        receipt=self.pack();before=(self.bundle/'manifest.json').read_bytes()
        with self.assertRaises(FileExistsError):self.pack()
        target=self.root/'restore';target.mkdir();(target/'keep').write_text('preserve')
        with self.assertRaises(FileExistsError):backup.restore(self.bundle,target,receipt['manifest_sha256'])
        self.assertEqual('preserve',(target/'keep').read_text());self.assertEqual(before,(self.bundle/'manifest.json').read_bytes())

    def test_symlink_hardlink_and_fifo_sources_rejected(self):
        paths=[self.paper/'symlink',self.paper/'hardlink',self.paper/'fifo']
        for kind,path in zip(['symlink','hardlink','fifo'],paths):
            if kind=='symlink':path.symlink_to(self.paper/'settings.yml')
            elif kind=='hardlink':os.link(self.paper/'settings.yml',path)
            else:os.mkfifo(path)
            try:
                with self.assertRaises(backup.BackupError):self.pack()
                self.assertFalse(self.bundle.exists())
            finally:path.unlink()

    def test_source_and_destination_overlap_rejected(self):
        for destination in [self.paper/'inside',self.paper,self.root]:
            with self.assertRaises(backup.BackupError):backup.create(self.roots,destination,offline_confirmed=True)
        with self.assertRaises(backup.BackupError):backup.create({'paper':self.paper,'postgres':self.paper/'world'},self.bundle,offline_confirmed=True)

    def test_changed_source_after_copy_leaves_incomplete_evidence_without_manifest(self):
        real_copy=backup.copy_file
        def changed(source,target,before,budget):
            digest=real_copy(source,target,before,budget)
            if source.name=='settings.yml':source.write_text('changed during copy')
            return digest
        with patch.object(backup,'copy_file',side_effect=changed):
            with self.assertRaisesRegex(backup.BackupError,'SOURCE_TREE_CHANGED'):self.pack()
        self.assertTrue(self.bundle.is_dir());self.assertFalse((self.bundle/'manifest.json').exists())

    def test_tampered_missing_added_and_linked_payload_never_restored(self):
        receipt=self.pack();file=self.bundle/'payload/paper/settings.yml';old=file.read_bytes()
        file.write_bytes(b'x'*len(old))
        with self.assertRaisesRegex(backup.BackupError,'HASH_MISMATCH'):backup.restore(self.bundle,self.root/'out',receipt['manifest_sha256'])
        self.assertFalse((self.root/'out').exists());file.write_bytes(old)
        extra=self.bundle/'payload/unlisted';extra.write_text('extra')
        with self.assertRaisesRegex(backup.BackupError,'MEMBERS_MISMATCH'):backup.verify(self.bundle,receipt['manifest_sha256'])
        extra.unlink();file.unlink()
        with self.assertRaises(backup.BackupError):backup.verify(self.bundle,receipt['manifest_sha256'])
        file.symlink_to(self.paper/'settings.yml')
        with self.assertRaises(backup.BackupError):backup.verify(self.bundle,receipt['manifest_sha256'])

    def test_manifest_receipt_is_external_and_traversal_or_duplicates_rejected(self):
        self.pack()
        with self.assertRaisesRegex(backup.BackupError,'RECEIPT_MISMATCH'):backup.verify(self.bundle,'0'*64)
        for path in ['../escape','/absolute','paper/../escape','paper\\escape','paper//bad','paper/./bad']:
            receipt=self.alter_manifest(lambda m:m['files'][0].update(path=path))
            with self.assertRaisesRegex(backup.BackupError,'MANIFEST_PATH'):backup.restore(self.bundle,self.root/'out',receipt)
        receipt=self.alter_manifest(lambda m:m['files'].__setitem__(0,dict(m['files'][1])))
        with self.assertRaisesRegex(backup.BackupError,'DUPLICATE'):backup.verify(self.bundle,receipt)
        self.assertFalse((self.root/'out').exists())

    def test_byte_time_and_callback_limits_leave_originals(self):
        with self.assertRaisesRegex(backup.BackupError,'BYTE_LIMIT'):self.pack(budget=backup.Budget(max_bytes=1))
        with self.assertRaisesRegex(backup.BackupError,'TIME_LIMIT'):self.pack(budget=backup.Budget(seconds=-1))
        def stop():raise RuntimeError('shared deadline')
        with self.assertRaisesRegex(RuntimeError,'shared deadline'):self.pack(budget=backup.Budget(guard=stop))
        self.assertFalse(self.bundle.exists())

    def test_bundle_modes_and_unlisted_top_level_files_rejected(self):
        receipt=self.pack();self.bundle.chmod(0o755)
        with self.assertRaisesRegex(backup.BackupError,'PRIVATE_BUNDLE'):backup.verify(self.bundle,receipt['manifest_sha256'])
        self.bundle.chmod(0o700);(self.bundle/'extra').write_text('extra')
        with self.assertRaisesRegex(backup.BackupError,'EXTRA_BUNDLE_FILES'):backup.verify(self.bundle,receipt['manifest_sha256'])

    def test_restore_failure_preserves_bundle_and_partial_without_success_receipt(self):
        receipt=self.pack();before=(self.bundle/'manifest.json').read_bytes();out=self.root/'out'
        with patch.object(backup,'copy_file',side_effect=OSError('injected disk failure')):
            with self.assertRaises(OSError):backup.restore(self.bundle,out,receipt['manifest_sha256'])
        self.assertTrue(out.is_dir());self.assertFalse((out/'restore-receipt.json').exists())
        self.assertEqual(before,(self.bundle/'manifest.json').read_bytes());backup.verify(self.bundle,receipt['manifest_sha256'])


if __name__=='__main__':unittest.main()
