import importlib.util
import io
import json
import os
from pathlib import Path
import tarfile
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('update_static', Path(__file__).with_name('update-static.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
common = module.common
OLD = '20261021T071943Z-72b3b16437b8'
NEW = '20261021T180000Z-54ebff69c084'


def seed(root):
    for name in ['index.html', 'download.html', '404.html', 'api/index.html', 'guide/quick-start.html']:
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('public test page', encoding='utf-8')


class UpdateSafetyTest(unittest.TestCase):
    def test_persistent_download_mode_verifies_live_manifest_not_static_snapshot(self):
        live = {'available': True, 'sha256': 'b' * 64}
        with patch.object(module, 'LIVE_DOWNLOADS', live), patch.object(common, 'verify_http') as verify:
            module.verify_live('docs.example.com', {'sha256': 'a' * 64})
            verify.assert_called_once_with('docs.example.com', live, live_downloads=True)

    def test_static_mode_preserves_original_exact_snapshot_verification(self):
        snapshot = {'sha256': 'a' * 64}
        with patch.object(module, 'LIVE_DOWNLOADS', None), patch.object(common, 'verify_http') as verify:
            module.verify_live('docs.example.com', snapshot)
            verify.assert_called_once_with('docs.example.com', snapshot)

    def test_release_ids_cannot_escape_or_select_nested_paths(self):
        for value in ['../other', '/var/www', OLD + '/other', '.', 'current', OLD + '\\other']:
            with self.assertRaises(ValueError):
                module.release_path(Path('/site'), value)

    def test_tree_budget_and_extension_checked_before_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            seed(root)
            with patch.object(common, 'verify_release', return_value={'sha256': 'a' * 64}):
                self.assertEqual(module.verify_tree(root)['sha256'], 'a' * 64)
                for name in ['secret.pem', '.env']:
                    path = root / name
                    path.write_text('not public', encoding='utf-8')
                    with self.assertRaises(ValueError):
                        module.verify_tree(root)
                    path.unlink()
                with patch.object(common, 'MAX_TOTAL', 1), self.assertRaisesRegex(ValueError, 'budget'):
                    module.verify_tree(root)

    def test_missing_required_page_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, 'Missing required'):
                module.verify_tree(Path(directory))

    def test_successful_activation_does_not_reload_nginx(self):
        with patch.object(module, 'atomic_switch') as switch, patch.object(module, 'verify_live') as verify, patch.object(common, 'run') as run:
            module.activate(Path('/site'), 'docs.example.com', Path('/old'), {'old': True}, Path('/new'), {'new': True})
            switch.assert_called_once_with(Path('/site'), Path('/old'), Path('/new'))
            verify.assert_called_once_with('docs.example.com', {'new': True})
            run.assert_not_called()

    def test_failed_activation_restores_and_rechecks_previous_release(self):
        with patch.object(module, 'atomic_switch') as switch, patch.object(module, 'verify_live', side_effect=[ValueError('bad HTTP'), None]) as verify:
            with self.assertRaisesRegex(ValueError, 'ACTIVATION_FAILED_RESTORED'):
                module.activate(Path('/site'), 'docs.example.com', Path('/old'), {'old': True}, Path('/new'), {'new': True})
            self.assertEqual(switch.call_args_list[1].args, (Path('/site'), Path('/new'), Path('/old')))
            self.assertEqual(verify.call_args_list[1].args, ('docs.example.com', {'old': True}))

    def test_failed_restore_is_not_reported_as_success(self):
        with patch.object(module, 'atomic_switch', side_effect=[None, ValueError('concurrent switch')]), patch.object(module, 'verify_live', side_effect=ValueError('bad HTTP')):
            with self.assertRaisesRegex(ValueError, 'ACTIVATION_AND_RESTORE_FAILED'):
                module.activate(Path('/site'), 'docs.example.com', Path('/old'), {}, Path('/new'), {})

    def test_live_check_retries_are_bounded(self):
        with patch.object(common, 'verify_http', side_effect=ValueError('wrong manifest')) as verify, patch.object(module.time, 'sleep'):
            with self.assertRaises(ValueError):
                module.verify_live('docs.example.com', {})
            self.assertEqual(verify.call_count, 5)

    def test_stale_current_does_not_create_temporary_link(self):
        with patch.object(module, 'current_release', return_value=Path('/different')):
            with self.assertRaisesRegex(ValueError, 'changed'):
                module.atomic_switch(Path('/site'), Path('/old'), Path('/new'))

    def test_bad_archive_digest_creates_no_release(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / 'site.tar.gz'
            archive.write_bytes(b'not an archive')
            with self.assertRaisesRegex(ValueError, 'SHA-256'):
                module.prepare_archive(root, archive, '0' * 64)
            self.assertFalse((root / 'releases').exists())

    def test_archive_validation_precedes_extraction(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / 'site.tar.gz'
            with tarfile.open(archive, 'w:gz') as output:
                entry = tarfile.TarInfo('../../secret')
                entry.size = 1
                output.addfile(entry, io.BytesIO(b'x'))
            with self.assertRaisesRegex(ValueError, 'escapes'):
                module.prepare_archive(root, archive, common.digest(archive))
            self.assertFalse((root / 'releases').exists())

    def test_archive_prepares_fresh_candidate_without_activating(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'releases').mkdir()
            archive = root / 'site.tar.gz'
            with tarfile.open(archive, 'w:gz') as output:
                for name in ['index.html', 'download.html', '404.html', 'api/index.html', 'downloads/release.json', 'guide/quick-start.html']:
                    entry = tarfile.TarInfo('./' + name)
                    entry.size = 1
                    output.addfile(entry, io.BytesIO(b'x'))
            stamp = module.datetime.datetime(2026, 10, 21, tzinfo=module.datetime.timezone.utc)
            with patch.object(common, 'verify_release', return_value={'sha256': 'a' * 64}), patch.object(module.datetime, 'datetime') as timer:
                timer.now.return_value = stamp
                target, release = module.prepare_archive(root, archive, common.digest(archive))
                self.assertEqual(release['sha256'], 'a' * 64)
                self.assertEqual((target / 'index.html').read_bytes(), b'x')
                self.assertFalse((root / 'current').exists())
                with self.assertRaisesRegex(ValueError, 'already exists'):
                    module.prepare_archive(root, archive, common.digest(archive))

    def test_low_disk_space_creates_no_candidate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / 'site.tar.gz'
            with tarfile.open(archive, 'w:gz') as output:
                for name in ['index.html', 'download.html', '404.html', 'api/index.html', 'downloads/release.json']:
                    entry = tarfile.TarInfo(name)
                    output.addfile(entry, io.BytesIO())
            with patch.object(module.shutil, 'disk_usage', return_value=SimpleNamespace(free=0)):
                with self.assertRaisesRegex(ValueError, 'free space'):
                    module.prepare_archive(root, archive, common.digest(archive))
            self.assertFalse((root / 'releases').exists())


class HttpIdentityTest(unittest.TestCase):
    def test_same_size_wrong_http_manifest_cannot_pass(self):
        class Response(io.BytesIO):
            status = 200
        class Client:
            def open(self, request, timeout):
                return Response(json.dumps({'sha256': 'wrong'}).encode())
        with patch.object(common.urllib.request, 'build_opener', return_value=Client()):
            with self.assertRaisesRegex(ValueError, 'manifest'):
                common.verify_http('docs.example.com', {'sha256': 'a' * 64})


@unittest.skipUnless(os.name == 'posix', 'Real symlink/flock transactions require Linux')
class LinuxTransactionTest(unittest.TestCase):
    def test_atomic_switch_and_explicit_rollback_keep_both_releases(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            old = module.release_path(base, OLD)
            new = module.release_path(base, NEW)
            old.mkdir(parents=True); new.mkdir()
            (base / 'current').symlink_to(old, target_is_directory=True)
            module.atomic_switch(base, old, new)
            self.assertEqual(module.current_release(base), new)
            module.atomic_switch(base, new, old)
            self.assertEqual(module.current_release(base), old)
            self.assertTrue(new.is_dir())
            self.assertFalse((base / 'current.next').exists())

    def test_http_failure_really_restores_original_link(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            old = module.release_path(base, OLD)
            new = module.release_path(base, NEW)
            old.mkdir(parents=True); new.mkdir()
            (base / 'current').symlink_to(old, target_is_directory=True)
            with patch.object(module, 'verify_live', side_effect=[ValueError('wrong live APK'), None]):
                with self.assertRaisesRegex(ValueError, 'RESTORED'):
                    module.activate(base, 'docs.example.com', old, {}, new, {})
            self.assertEqual(module.current_release(base), old)
            self.assertTrue(new.is_dir())

    def test_lock_contention_and_release_after_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            with module.deployment_lock(base):
                with self.assertRaisesRegex(ValueError, 'active'):
                    with module.deployment_lock(base):
                        self.fail('Concurrent writer acquired lock')
            with module.deployment_lock(base):
                pass

    def test_outside_current_and_linked_release_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            (base / 'releases').mkdir()
            outside = base / 'outside' / OLD
            outside.mkdir(parents=True)
            (base / 'current').symlink_to(outside, target_is_directory=True)
            with self.assertRaises(ValueError):
                module.current_release(base)
            (base / 'current').unlink()
            (base / 'releases' / OLD).symlink_to(outside, target_is_directory=True)
            with self.assertRaises(ValueError):
                module.release_path(base, OLD)

    def test_preexisting_temporary_link_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            old = module.release_path(base, OLD)
            new = module.release_path(base, NEW)
            old.mkdir(parents=True); new.mkdir()
            (base / 'current').symlink_to(old, target_is_directory=True)
            (base / 'current.next').symlink_to(new, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, 'preserved'):
                module.atomic_switch(base, old, new)
            self.assertEqual(module.current_release(base), old)
            self.assertTrue((base / 'current.next').is_symlink())

    def test_check_is_read_only_and_stale_expected_current_refuses_update(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            old = module.release_path(base, OLD)
            old.mkdir(parents=True)
            seed(old)
            (base / 'current').symlink_to(old, target_is_directory=True)
            options = SimpleNamespace(host='docs.example.com', check=True, expected_current=None, expected_release=OLD)
            with patch.object(module, 'BASE', base), patch.object(os, 'geteuid', return_value=0), patch.object(common, 'run'), patch.object(common, 'verify_release', return_value={'sha256': 'a' * 64}), patch.object(module, 'verify_live'):
                module.update(options)
                self.assertFalse((base / '.deploy.lock').exists())
                options.check = False
                options.expected_current = 'b' * 64
                with self.assertRaisesRegex(ValueError, 'differs'):
                    module.update(options)
                options.expected_current = 'a' * 64
                options.expected_release = NEW
                with self.assertRaisesRegex(ValueError, 'expected-release'):
                    module.update(options)
                self.assertEqual(module.current_release(base), old)
                self.assertEqual(len(list((base / 'releases').iterdir())), 1)

    def test_nonregular_lock_is_rejected_without_blocking(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            os.mkfifo(base / '.deploy.lock')
            with self.assertRaisesRegex(ValueError, 'regular'):
                with module.deployment_lock(base):
                    self.fail('FIFO acquired as deployment lock')


if __name__ == '__main__':
    unittest.main()
