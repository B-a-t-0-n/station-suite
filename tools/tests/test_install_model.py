"""Exercise model transfer and cleanup against an in-memory ADB filesystem only."""
import hashlib
import importlib.util
import json
from pathlib import Path
import shlex
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('station_install', Path(__file__).resolve().parents[1] / 'install.py')
install = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(install)


class FakeAdb:
    def __init__(self, corrupt=False, mkdir_failure=False):
        self.files = {'files/local-sherpa-model/existing.txt': b'keep'}
        self.dirs = {'files/local-sherpa-model'}
        self.commands = []
        self.corrupt, self.mkdir_failure = corrupt, mkdir_failure

    def run(self, command, **kwargs):
        # In particular, no bytes may travel through a Windows stdin pipe.
        assert 'input' not in kwargs and 'stdin' not in kwargs, kwargs
        assert command[:3] == ['fake-adb', '-s', 'station']
        args = command[3:]
        if args[0] == 'push':
            assert len(args) == 3
            self.commands.append(args)
            self.files[args[2]] = Path(args[1]).read_bytes()
            return SimpleNamespace(returncode=0, stdout=b'')
        assert args[0] == 'shell' and len(args) == 2, args
        return SimpleNamespace(returncode=0, stdout=self.shell(shlex.split(args[1])))

    def shell(self, command):
        self.commands.append(command)
        if command[:2] == ['run-as', install.PACKAGES['voicebridge']]: command = command[2:]
        if command[:2] == ['sh', '-c']:
            assert len(command) == 3
            for part in command[2].split(' && '): self.shell(shlex.split(part))
            return b''
        op = command[0]
        if op == 'mkdir':
            if self.mkdir_failure and command[-1].startswith('/data/local/tmp/'):
                raise SystemExit('fixture mkdir failure')
            self.dirs.add(command[-1])
        elif op in ('umask', 'chmod', 'am'): pass
        elif op == 'cp':
            self.files[command[2]] = self.files[command[1]] + (b'bad' if self.corrupt else b'')
        elif op == 'mv': self.files[command[2]] = self.files.pop(command[1])
        elif op == 'sha256sum':
            return (hashlib.sha256(self.files[command[1]]).hexdigest() + '  ' + command[1] + '\n').encode()
        elif op == 'rm':
            assert command[1] == '-f' and len(command) == 3
            self.files.pop(command[2], None)
        elif op == 'rmdir':
            assert not any(name.startswith(command[1] + '/') for name in self.files)
            self.dirs.remove(command[1])
        else: raise AssertionError(command)
        return b''


class InstallModelTests(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.models = []
        for name, payload in [('encoder.int8.onnx', b'head\x1a\x00\xfftail'), ('decoder.onnx', b'other\x1aend')]:
            path = self.root / name; path.write_bytes(payload)
            self.models.append((path, hashlib.sha256(payload).hexdigest()))
        self.args = SimpleNamespace(adb='fake-adb', station='station')

    def deploy(self, fake):
        with patch.object(install.subprocess, 'run', side_effect=fake.run), patch.object(install.secrets, 'token_hex', return_value='a' * 24):
            install.deploy_model(self.args, self.models)

    def assert_clean(self, fake):
        self.assertEqual(fake.dirs, {'files/local-sherpa-model'})
        self.assertEqual(fake.files['files/local-sherpa-model/existing.txt'], b'keep')
        self.assertFalse(any('.station-model-' in path or '/station-model-' in path for path in fake.files))

    def test_binary_push_private_hash_then_atomic_final_paths_and_cleanup(self):
        fake = FakeAdb(); self.deploy(fake); self.assert_clean(fake)
        for path, _ in self.models:
            self.assertEqual(fake.files['files/local-sherpa-model/' + path.name], path.read_bytes())
        copies = [c for c in fake.commands if c[0] == 'push']
        self.assertEqual(len(copies), len(self.models))
        self.assertTrue(all(c[2].startswith('/data/local/tmp/station-model-') for c in copies))
        first_mv = next(i for i, c in enumerate(fake.commands) if c[:3] == ['run-as', install.PACKAGES['voicebridge'], 'mv'])
        checked = [c for c in fake.commands[:first_mv] if c[:3] == ['run-as', install.PACKAGES['voicebridge'], 'sha256sum']]
        self.assertEqual(len(checked), len(self.models))
        self.assertFalse(any(c[0] in ('install', 'uninstall', 'reboot') or 'startservice' in c for c in fake.commands))

    def test_remote_hash_mismatch_never_promotes_and_cleans_own_stages(self):
        fake = FakeAdb(corrupt=True)
        with self.assertRaisesRegex(SystemExit, 'integrity check failed on the device'): self.deploy(fake)
        self.assert_clean(fake)
        self.assertEqual(set(fake.files), {'files/local-sherpa-model/existing.txt'})
        self.assertFalse(any(c[:3] == ['run-as', install.PACKAGES['voicebridge'], 'mv'] for c in fake.commands))

    def test_failed_mkdir_does_not_clean_a_stage_we_do_not_own(self):
        fake = FakeAdb(mkdir_failure=True)
        with self.assertRaisesRegex(SystemExit, 'fixture mkdir failure'): self.deploy(fake)
        self.assertFalse(any(c[0] in ('rm', 'rmdir') or c[:3] == ['run-as', install.PACKAGES['voicebridge'], 'rm'] for c in fake.commands))

    def test_all_local_model_inputs_are_checked_before_any_device_call(self):
        config = self.root / 'config'; config.mkdir()
        entries = [{'path': 'model/' + p.name, 'bytes': p.stat().st_size, 'sha256': sha} for p, sha in self.models]
        (config / 'sherpa-downloads.json').write_text(json.dumps({'files': entries}))
        self.models[-1][0].write_bytes(b'tampered')
        with patch.object(install, 'ROOT', self.root), patch.object(install.sys, 'argv', ['install.py', '--adb', 'fake-adb', '--model', str(self.root)]), patch.object(install.subprocess, 'run', side_effect=AssertionError('ADB must not run')):
            with self.assertRaisesRegex(SystemExit, 'before deployment'): install.main()


if __name__ == '__main__': unittest.main()
