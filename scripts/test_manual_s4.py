import isolated_paths
import importlib.util
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import urlopen, Request
from manual_s4 import ClientPackServer


class ClientPackTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='hearth-s4-11.', dir=isolated_paths.require_base())
        self.root = Path(self.directory.name)
        (self.root / '.hearth-lifecycle-isolated').write_text('S4-11 isolated test only\n')
        self.pack = self.root / 'server/plugins/BetterHud/build.zip'
        self.pack.parent.mkdir(parents=True)
        self.server = ClientPackServer(self.root, 0)
        self.url = 'http://127.0.0.1:' + str(self.server.server.server_port)

    def tearDown(self):
        self.server.close()
        self.directory.cleanup()

    def rejected(self, path, status):
        with self.assertRaises(HTTPError) as failure:
            urlopen(self.url + path, timeout=2)
        self.assertEqual(status, failure.exception.code)

    def test_only_one_pack_endpoint_is_exposed(self):
        self.pack.write_bytes(b'fixture zip')
        for path in ['/', '/db-endpoint.properties', '/../../etc/passwd', '/hearth-s4.zip?path=../secret']:
            self.rejected(path, 404)
        with urlopen(self.url + '/hearth-s4.zip', timeout=2) as response:
            self.assertEqual(b'fixture zip', response.read())
            self.assertEqual('application/zip', response.headers['Content-Type'])
            self.assertEqual('no-store', response.headers['Cache-Control'])

    def test_missing_and_oversized_packs_are_unavailable(self):
        self.rejected('/hearth-s4.zip', 503)
        with self.pack.open('wb') as target:
            target.truncate(64 * 1024**2 + 1)
        self.rejected('/hearth-s4.zip', 503)

    def test_symlinks_cannot_serve_other_files(self):
        outside = self.root / 'private.txt'
        outside.write_bytes(b'private')
        self.pack.symlink_to(outside)
        self.rejected('/hearth-s4.zip', 503)

    def test_head_checks_availability_without_recording_a_download(self):
        self.pack.write_bytes(b'fixture zip')
        with urlopen(Request(self.url + '/hearth-s4.zip', method='HEAD'), timeout=2) as response:
            self.assertEqual(b'', response.read())
            self.assertEqual('11', response.headers['Content-Length'])
        self.assertEqual(0, self.server.downloads)


class ManualTimeLimitTests(unittest.TestCase):
    def test_explicit_manual_override_removes_only_elapsed_limit(self):
        spec = importlib.util.spec_from_file_location('lifecycle_test', Path(__file__).with_name('verify-lifecycle.py'))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory(dir=isolated_paths.require_base()) as root:
            monitor = module.Monitor(Path(root), time.monotonic() - 1000, work_seconds=550)
            try:
                with patch.object(module.s42, 'available_kib', return_value=8 * 1024**2):
                    with self.assertRaisesRegex(RuntimeError, '시간 한도'):
                        monitor.guard()
                    monitor.deadline = float('inf')
                    monitor.guard()
                with patch.object(module.s42, 'available_kib', return_value=5 * 1024**2):
                    with self.assertRaisesRegex(RuntimeError, 'RAM'):
                        monitor.guard()
            finally:
                monitor.selector.close()

    def test_manual_command_allowlist_does_not_open_generic_console_access(self):
        spec = importlib.util.spec_from_file_location('lifecycle_commands_test', Path(__file__).with_name('verify-lifecycle.py'))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        for command in ['hearthverify client-pack TestProfile', 'hearthverify gathering-fixture TestProfile', 'hearthrecovery list', 'hearthverify client-environment ui', 'hearthverify client-environment combat']:
            self.assertTrue(module.permitted_session_command(command))
        for command in ['hearthverify client-pack TestProfile http://example.com', 'hearthrecovery reset', 'give TestProfile minecraft:netherite_sword 64', 'execute run stop', 'hearthverify client-environment hard', 'hearthverify client-environment combat extra']:
            self.assertFalse(module.permitted_session_command(command))


if __name__ == '__main__':
    unittest.main()
