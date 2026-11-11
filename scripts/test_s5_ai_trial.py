import ctypes
import io
from pathlib import Path
import tarfile
import tempfile
import time
import unittest
import hashlib
import sys
from types import SimpleNamespace
from unittest.mock import patch
import s5_ai_trial as trial


class TrialSafetyTests(unittest.TestCase):
    def test_verified_reuse_keeps_original_and_does_not_download(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/'source';source.write_bytes(b'fixture');stamp=source.stat().st_mtime_ns;monitor=trial.Trial(root);target=root/'new/artifact'
            with patch.object(trial,'available_kib',return_value=8*1024**2),patch.object(trial,'urlopen') as network:
                monitor.reuse(source,target,7,hashlib.sha256(b'fixture').hexdigest());network.assert_not_called()
            self.assertEqual(b'fixture',source.read_bytes());self.assertEqual(stamp,source.stat().st_mtime_ns);self.assertEqual(source.read_bytes(),target.read_bytes());self.assertEqual(0,monitor.downloaded);self.assertEqual(7,monitor.reused)

    def test_reuse_bad_hash_never_publishes_or_changes_original(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/'source';source.write_bytes(b'fixture');monitor=trial.Trial(root);target=root/'artifact'
            with patch.object(trial,'available_kib',return_value=8*1024**2):
                with self.assertRaisesRegex(RuntimeError,'HASH'):monitor.reuse(source,target,7,'0'*64)
            self.assertFalse(target.exists());self.assertEqual(b'fixture',source.read_bytes())

    def test_reusing_artifacts_does_not_bypass_initial_disk_precondition(self):
        with tempfile.TemporaryDirectory(prefix='hearth-s5-trial.',dir='/tmp') as directory:
            root=Path(directory);(root/'.hearth-s5-isolated').write_text(trial.MARKER)
            args=['s5_ai_trial.py','--workspace',str(root),'--java','/unused','--classpath','unused','--artifact-source','/unread']
            with patch.object(sys,'argv',args),patch.object(trial.shutil,'disk_usage',return_value=SimpleNamespace(free=15*trial.GIB)),patch.object(trial,'urlopen') as network,patch.object(trial.subprocess,'Popen') as processes:
                with self.assertRaises(AssertionError):trial.main()
                network.assert_not_called();processes.assert_not_called()

    def test_paths_and_links_cannot_escape_owned_root(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ('../outside', '/etc/passwd', 'lib/../../outside'):
                with self.assertRaises(RuntimeError):
                    trial.safe_path(root, name)
            (root/'link').symlink_to('/tmp', target_is_directory=True)
            with self.assertRaises(RuntimeError):
                trial.safe_path(root, 'link/outside')

    def compressed(self, data):
        lib = ctypes.CDLL(ctypes.util.find_library('zstd'))
        lib.ZSTD_compressBound.argtypes = [ctypes.c_size_t]
        lib.ZSTD_compressBound.restype = ctypes.c_size_t
        lib.ZSTD_compress.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_void_p, ctypes.c_size_t, ctypes.c_int]
        lib.ZSTD_compress.restype = ctypes.c_size_t
        capacity = lib.ZSTD_compressBound(len(data))
        out = ctypes.create_string_buffer(capacity)
        size = lib.ZSTD_compress(out, capacity, data, len(data), 1)
        return out.raw[:size]

    def test_existing_libzstd_stream_and_safe_archive_extraction(self):
        tar_bytes = io.BytesIO()
        with tarfile.open(fileobj=tar_bytes, mode='w') as archive:
            member = tarfile.TarInfo('bin/test-tool')
            member.mode = 0o755
            member.size = 5
            archive.addfile(member, io.BytesIO(b'hello'))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root/'archive.zst'
            source.write_bytes(self.compressed(tar_bytes.getvalue()))
            trial.unpack(source, root/'runtime', lambda: None)
            self.assertEqual(b'hello', (root/'runtime/bin/test-tool').read_bytes())
            self.assertEqual(0o755, (root/'runtime/bin/test-tool').stat().st_mode & 0o777)

    def test_archive_absolute_symlink_preserves_outside_file(self):
        tar_bytes = io.BytesIO()
        with tarfile.open(fileobj=tar_bytes, mode='w') as archive:
            link = tarfile.TarInfo('link')
            link.type = tarfile.SYMTYPE
            link.linkname = '/tmp/outside'
            archive.addfile(link)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root/'archive.zst'
            source.write_bytes(self.compressed(tar_bytes.getvalue()))
            with self.assertRaisesRegex(RuntimeError, 'ABSOLUTE_LINK'):
                trial.unpack(source, root/'runtime', lambda: None)
            self.assertFalse((root/'runtime/link').exists())

    def test_guard_preserves_host_and_elapsed_limits(self):
        with tempfile.TemporaryDirectory() as directory:
            monitor = trial.Trial(Path(directory))
            with patch.object(trial, 'available_kib', return_value=8*1024**2):
                monitor.guard()
                monitor.started = time.monotonic()-551
                with self.assertRaisesRegex(RuntimeError, '550'):
                    monitor.guard()
            monitor.started = time.monotonic()
            with patch.object(trial, 'available_kib', return_value=5*1024**2):
                with self.assertRaisesRegex(RuntimeError, '6_GIB'):
                    monitor.guard()

    def test_bad_hash_cannot_publish_artifact_or_overwrite_partial(self):
        class Response(io.BytesIO):
            def geturl(self):
                return 'https://official.example/artifact'
        with tempfile.TemporaryDirectory() as directory:
            monitor = trial.Trial(Path(directory))
            target = Path(directory)/'artifact'
            with patch.object(trial, 'available_kib', return_value=8*1024**2), patch.object(trial, 'urlopen', return_value=Response(b'fixture')):
                with self.assertRaisesRegex(RuntimeError, 'HASH'):
                    monitor.fetch('https://official.example/artifact', target, 7, '0'*64)
            self.assertFalse(target.exists())
            self.assertEqual(b'fixture', target.with_name('artifact.partial').read_bytes())


if __name__ == '__main__':
    unittest.main()
