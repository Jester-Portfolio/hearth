from pathlib import Path
import errno
import hashlib
import tempfile
import unittest
from unittest.mock import patch
import space_reclaim as space


class SpaceReclaimTests(unittest.TestCase):
    def test_kernel_sharing_preserves_paths_hash_metadata_and_independent_writes(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);a=root/'a';b=root/'b';a.write_bytes(b'A'*65536);b.write_bytes(a.read_bytes());before=[space.identity(p) for p in (a,b)]
            try:result=space.dedupe_pair(a,b,hashlib.sha256(a.read_bytes()).hexdigest(),lambda:None)
            except OSError as error:
                if error.errno in (errno.EOPNOTSUPP,errno.ENOTTY,errno.EINVAL):self.skipTest('Filesystem does not support dedupe')
                raise
            self.assertEqual(before,result['after']);self.assertEqual(65536,result['bytes_compared_and_shared']);self.assertTrue(space.fully_shared(a,lambda:None));self.assertTrue(space.fully_shared(b,lambda:None))
            with patch.object(space.shutil,'disk_usage',return_value=type('Disk',(),{'free':0})()),patch.object(space,'candidates',return_value=([a,b],[])),patch.object(space,'checksum') as checksum:
                # 별도 basename 두 개를 같은 이름 그룹에 넣을 수 있도록 별도 폴더로 옮깁니다.
                folder=root/'other';folder.mkdir();new=folder/'a';b.rename(new)
                with patch.object(space,'candidates',return_value=([a,new],[])):
                    report=space.reclaim();self.assertEqual(2,report['shared_files_skipped']);checksum.assert_not_called()
            new.write_bytes(b'B'*65536);self.assertEqual(b'A'*65536,a.read_bytes())

    def test_different_bytes_or_symlinks_never_reach_ioctl(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);a=root/'a';b=root/'b';a.write_bytes(b'A'*4096);b.write_bytes(b'B'*4096)
            with patch.object(space.fcntl,'ioctl') as ioctl:
                with self.assertRaises(AssertionError):space.dedupe_pair(a,b,hashlib.sha256(a.read_bytes()).hexdigest(),lambda:None)
                link=root/'link';link.symlink_to(a)
                with self.assertRaises(AssertionError):space.dedupe_pair(link,b,'bad',lambda:None)
                ioctl.assert_not_called()
            self.assertEqual(b'A'*4096,a.read_bytes());self.assertEqual(b'B'*4096,b.read_bytes())

    def test_candidates_only_use_owned_marked_inactive_roots_and_skip_profiles_logs_and_symlinks(self):
        with tempfile.TemporaryDirectory(prefix='hearth-s5-trial.',dir='/tmp') as directory:
            root=Path(directory);(root/'.hearth-s5-isolated').write_text(space.MARKER)
            for name in ['ollama.tar.zst','runtime/lib/tool.so','models/blobs/sha256-fixture','profile/history','warmup.json']:
                p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b'A'*1024**2)
            (root/'runtime/link.so').symlink_to(root/'profile/history')
            with patch.object(space,'active',return_value=False):files,skipped=space.candidates([root]);self.assertEqual({'ollama.tar.zst','tool.so','sha256-fixture'},{p.name for p in files});self.assertEqual([],skipped)
            with patch.object(space,'active',return_value=True):files,skipped=space.candidates([root]);self.assertEqual([],files);self.assertEqual([str(root)],skipped)
            (root/'.hearth-s5-isolated').write_text('wrong marker')
            with patch.object(space,'active',return_value=False):self.assertEqual(([],[]),space.candidates([root]))

    def test_no_work_when_space_ready_and_deadline_or_unsupported_never_delete_files(self):
        with patch.object(space.shutil,'disk_usage',return_value=type('Disk',(),{'free':16*space.GIB})()),patch.object(space,'candidates') as scan:
            self.assertEqual('PASS',space.reclaim()['outcome']);scan.assert_not_called()
        with patch.object(space.shutil,'disk_usage',return_value=type('Disk',(),{'free':0})()),patch.object(space,'candidates',return_value=([],[])):
            result=space.reclaim(seconds=0);self.assertEqual('SPACE_NOT_READY',result['outcome']);self.assertEqual(0,result['files_deleted']);self.assertIn('SPACE_RECLAIM_DEADLINE',result['failure'])

    def test_unsupported_filesystem_preserves_both_files_and_reports_no_deletion(self):
        with tempfile.TemporaryDirectory(prefix='hearth-s5-trial.',dir='/tmp') as directory:
            root=Path(directory);(root/'.hearth-s5-isolated').write_text(space.MARKER);a=root/'models/blobs/sha256-fixture';b=root/'models-reviewed/blobs/sha256-fixture'
            for p in (a,b):p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b'A'*1024**2)
            before=[space.identity(p) for p in (a,b)]
            with patch.object(space,'active',return_value=False),patch.object(space.shutil,'disk_usage',return_value=type('Disk',(),{'free':0})()),patch.object(space.fcntl,'ioctl',side_effect=OSError(errno.EOPNOTSUPP,'unsupported')):
                result=space.reclaim(roots=[root]);self.assertEqual('SPACE_NOT_READY',result['outcome']);self.assertIn('unsupported',result['failure']);self.assertEqual(0,result['files_deleted'])
            self.assertEqual(before,[space.identity(p) for p in (a,b)]);self.assertEqual(a.read_bytes(),b.read_bytes())


if __name__=='__main__':unittest.main()
