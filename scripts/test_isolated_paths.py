import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import isolated_paths as paths


class IsolatedPathsTests(unittest.TestCase):
    def test_home_workspace_is_owned_canonical_and_prefix_bound(self):
        with tempfile.TemporaryDirectory(prefix='hearth-s4-11.',dir=paths.require_base()) as directory:
            root=Path(directory);self.assertEqual(root,paths.workspace(root,'hearth-s4-11.'));self.assertEqual(os.getuid(),root.stat().st_uid)
            self.assertEqual(Path('/home/server'),paths.BASE.parent)
            with self.assertRaises(AssertionError):paths.workspace(root,'hearth-s4-6.')
            with self.assertRaises(AssertionError):paths.workspace(root.parent,'hearth-s4-11.')

    def test_symlink_and_legacy_destination_are_rejected_without_changes(self):
        with tempfile.TemporaryDirectory(prefix='hearth-path-check.',dir=paths.require_base()) as directory:
            root=Path(directory);link=root.parent/('hearth-s4-11.link_'+root.name.split('.')[-1]);link.symlink_to(root)
            try:
                with self.assertRaises(AssertionError):paths.workspace(link,'hearth-s4-11.')
                with self.assertRaises(AssertionError):paths.workspace(Path('/tmp'),'hearth-s4-11.')
                self.assertTrue(root.is_dir())
            finally:link.unlink()

    def test_unknown_existing_base_is_not_repaired_or_overwritten(self):
        with tempfile.TemporaryDirectory(dir=paths.require_base()) as directory:
            root=Path(directory);note=root/'keep';note.write_text('original')
            with patch.object(paths,'BASE',root):
                with self.assertRaises(AssertionError):paths.ensure_base()
            self.assertEqual('original',note.read_text());self.assertFalse((root/'.hearth-test-base').exists())

    def test_base_marker_symlink_or_permissions_cannot_relax_scope(self):
        with tempfile.TemporaryDirectory(dir=paths.require_base()) as directory:
            root=Path(directory);marker=root/'.hearth-test-base';marker.write_text(paths.MARKER)
            with patch.object(paths,'BASE',root):
                self.assertEqual(root,paths.require_base());root.chmod(0o755)
                with self.assertRaises(AssertionError):paths.require_base()
                root.chmod(0o700);marker.unlink();marker.symlink_to(paths.BASE.parent/'.hearth-test-base')
                with self.assertRaises(AssertionError):paths.require_base()

    def test_child_java_tmpdir_stays_within_workspace(self):
        with tempfile.TemporaryDirectory(prefix='hearth-s5-trial.',dir=paths.require_base()) as directory:
            root=Path(directory);command=['/jdk/bin/java','-Xmx128m','Main'];changed=paths.java_command(command,root)
            self.assertIn('-Djava.io.tmpdir='+str(root/'jvm-tmp'),changed);self.assertEqual(3,len(command))
            self.assertEqual(['/jdk/bin/java','-Djava.io.tmpdir='+str(root),'Main'],paths.java_command(['/jdk/bin/java','-Djava.io.tmpdir='+str(root),'Main'],root))
            with self.assertRaises(AssertionError):paths.java_command(['/jdk/bin/java','-Djava.io.tmpdir=/tmp','Main'],root)
            with self.assertRaises(AssertionError):paths.java_command(['/jdk/bin/java','-Djava.io.tmpdir='+str(root),'-Djava.io.tmpdir='+str(root)],root)

    def test_fresh_prefix_rejects_traversal(self):
        for prefix in ['../outside.','hearth/a.','bad.','hearth-test/../.']:
            with self.assertRaises(AssertionError):paths.fresh(prefix)


if __name__=='__main__':unittest.main()
