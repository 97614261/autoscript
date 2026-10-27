import ast
from contextlib import ExitStack
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('update_publisher', Path(__file__).with_name('update-publisher.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class UpdatePublisherTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.install, self.data, self.bundle = [self.root / name for name in ['install', 'data', 'bundle']]
        for directory in [self.install, self.data / 'downloads', self.bundle]:
            directory.mkdir(parents=True)
        for name in module.SOURCES:
            (self.install / name).write_bytes(b'# original ' + name.encode('ascii'))
            (self.bundle / name).write_bytes(b'# updated ' + name.encode('ascii'))
        (self.data / 'downloads' / 'release.json').write_bytes(b'{"available":false}\n')
        self.token = self.root / 'token'
        self.token.write_bytes(b'a' * 64)
        self.commands = []
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        for name, value in [('INSTALL', self.install), ('DATA', self.data), ('TOKEN', self.token)]:
            self.stack.enter_context(patch.object(module, name, value))
        self.stack.enter_context(patch.object(module, 'verify_bundle', return_value={name: (self.bundle / name).read_bytes() for name in module.SOURCES}))
        self.stack.enter_context(patch.object(module, 'require_administrator'))
        self.stack.enter_context(patch.object(module.runpy, 'run_path', return_value={}))
        self.stack.enter_context(patch.object(module, 'signed_ready'))
        self.stack.enter_context(patch.object(module, 'command', side_effect=self.command))
        self.addCleanup(self.temporary.cleanup)

    def command(self, *arguments):
        self.commands.append(arguments)
        if arguments[:2] == ('systemctl', 'show'):
            return 'zhigou-publisher'
        if arguments[:2] == ('runuser', '-u'):
            ast.parse(arguments[-1])
        return ''

    def original_bytes(self):
        return {name: (self.install / name).read_bytes() for name in module.SOURCES}

    def test_success_backs_up_code_and_preserves_manifest_token_and_configuration(self):
        before = self.original_bytes()
        manifest = (self.data / 'downloads' / 'release.json').read_bytes()
        module.upgrade(self.bundle)
        backups = list(self.install.glob('before-archive-*'))
        self.assertEqual(len(backups), 1)
        for name in module.SOURCES:
            self.assertEqual((backups[0] / name).read_bytes(), before[name])
            self.assertEqual((self.install / name).read_bytes(), (self.bundle / name).read_bytes())
        self.assertEqual((self.data / 'downloads' / 'release.json').read_bytes(), manifest)
        self.assertEqual(self.token.read_bytes(), b'a' * 64)
        self.assertFalse(any('nginx' in part or 'daemon-reload' in part for args in self.commands for part in args))
        self.assertEqual([args[1] for args in self.commands if args[0] == 'systemctl' and args[1] in ['start', 'stop']], ['stop', 'start'])

    def test_new_code_not_ready_restores_old_code_without_reverting_data(self):
        before = self.original_bytes()
        with patch.object(module, 'signed_ready', side_effect=ValueError('synthetic readiness failure')), patch.object(module.time, 'monotonic', side_effect=[0, 30]):
            with self.assertRaises(ValueError):
                module.upgrade(self.bundle)
        self.assertEqual(self.original_bytes(), before)
        self.assertEqual(len(list(self.install.glob('before-archive-*'))), 1)
        self.assertEqual([args[1] for args in self.commands if args[0] == 'systemctl' and args[1] in ['start', 'stop']], ['stop', 'start', 'stop', 'start'])
        self.assertEqual(json.loads((self.data / 'downloads' / 'release.json').read_bytes()), {'available': False})

    def test_partial_code_install_failure_restores_both_sources(self):
        before = self.original_bytes()
        original = module.install_bytes
        failed = False
        def install(path, body, mode):
            nonlocal failed
            if path == self.install / module.SOURCES[1] and body.startswith(b'# updated') and not failed:
                failed = True
                raise OSError('synthetic installation failure')
            return original(path, body, mode)
        with patch.object(module, 'install_bytes', side_effect=install), self.assertRaises(OSError):
            module.upgrade(self.bundle)
        self.assertTrue(failed)
        self.assertEqual(self.original_bytes(), before)

    def test_failed_preflight_never_stops_service(self):
        before = self.original_bytes()
        def command(*args):
            if args[:2] == ('systemctl', 'show'):
                return 'root'
            self.fail('Unexpected command after failed service-user preflight')
        with patch.object(module, 'command', side_effect=command), self.assertRaises(ValueError):
            module.upgrade(self.bundle)
        self.assertEqual(self.original_bytes(), before)
        self.assertEqual(list(self.install.glob('before-archive-*')), [])

    def test_corrupt_bundle_never_stops_service_or_backs_up(self):
        with patch.object(module, 'verify_bundle', side_effect=ValueError('synthetic checksum failure')), self.assertRaises(ValueError):
            module.upgrade(self.bundle)
        self.assertEqual(self.commands, [])
        self.assertEqual(list(self.install.glob('before-archive-*')), [])

    def test_invalid_backup_input_is_not_overwritten(self):
        path = self.install / module.SOURCES[0]
        path.write_bytes(b'x' * (1024 * 1024 + 1))
        with self.assertRaises(ValueError):
            module.upgrade(self.bundle)
        self.assertEqual(path.stat().st_size, 1024 * 1024 + 1)
        self.assertEqual(self.commands, [])


if __name__ == '__main__':
    unittest.main()
