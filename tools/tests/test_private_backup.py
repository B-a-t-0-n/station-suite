"""Exercise encrypted restore validation without touching ADB or a deployment."""
import base64
import importlib.util
import json
from pathlib import Path
import secrets
import sys
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from cryptography.exceptions import InvalidTag

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from test_recover import RecoveryAdb

SPEC = importlib.util.spec_from_file_location('private_backup', Path(__file__).resolve().parents[1] / 'private_backup.py')
backup = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(backup)


class PrivateBackupTests(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.password = self.root / 'pass.txt'
        self.password.write_bytes(secrets.token_bytes(48).hex().encode())
        self.archive = self.root / 'test.stationbackup'
        self.args = SimpleNamespace(password_file=self.password, archive=self.archive,
                                    apply=False, private_dir=self.root / 'restored')
        for name in backup.PC_FILES.values():
            path = self.root / name
            path.write_bytes(('fixture-' + name).encode())
            setattr(self.args, name, path)

    def make_archive(self):
        def fake_adb(args, *command, **kwargs):
            return SimpleNamespace(returncode=0, stdout=('fixture-' + command[-1]).encode())
        with patch.object(backup, 'adb', side_effect=fake_adb), patch.object(backup, 'protect'), patch('builtins.print'):
            backup.backup(self.args)

    def test_round_trip_preserves_every_allowed_file(self):
        self.make_archive()
        manifest, entries = backup.decode(self.args)
        self.assertEqual(set(entries), backup.ALL_FILES)
        self.assertEqual(len(manifest['files']), len(backup.ALL_FILES))
        self.assertNotIn(b'fixture-', self.archive.read_bytes())

    def test_wrong_password_and_tampering_are_rejected(self):
        self.make_archive()
        original = self.password.read_bytes()
        self.password.write_bytes(secrets.token_bytes(48).hex().encode())
        with self.assertRaises(InvalidTag): backup.decode(self.args)
        self.password.write_bytes(original)
        blob = bytearray(self.archive.read_bytes()); blob[-1] ^= 1
        self.archive.write_bytes(blob)
        with self.assertRaises(InvalidTag): backup.decode(self.args)

    def test_verify_and_dry_restore_never_call_adb(self):
        self.make_archive()
        with patch.object(backup, 'adb', side_effect=AssertionError('ADB must not run')), patch('builtins.print'):
            backup.restore(self.args)
        self.assertFalse(self.args.private_dir.exists())

    def test_private_paths_inside_checkout_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'PRIVATE_PATH_INSIDE_PUBLIC_CHECKOUT'):
            backup.outside(backup.SUITE / 'accidental.stationbackup')

    def test_archive_cannot_add_untrusted_paths(self):
        self.make_archive()
        blob = self.archive.read_bytes(); offset = len(backup.MAGIC)
        salt, nonce = blob[offset:offset+16], blob[offset+16:offset+28]
        aes = backup.AESGCM(backup.key(backup.password(self.password), salt))
        value = json.loads(aes.decrypt(nonce, blob[offset+28:], backup.MAGIC))
        value['files']['../../outside'] = base64.b64encode(b'untrusted').decode()
        nonce = secrets.token_bytes(12)
        self.archive.write_bytes(backup.MAGIC + salt + nonce + aes.encrypt(nonce, json.dumps(value).encode(), backup.MAGIC))
        with self.assertRaisesRegex(ValueError, 'INVALID_MANIFEST'): backup.decode(self.args)

    def test_restore_binary_control_z_through_private_ascii_stage(self):
        fake = RecoveryAdb({})
        args = SimpleNamespace(adb='fake-adb', serial='station')
        files = {'files/native-tls.p12': b'binary\x1a\x00\xfftail', 'files/config.json': b'{"fixture":true}'}
        with patch.object(backup.subprocess, 'run', side_effect=fake.run):
            backup.restore_device_files(args, files)
        for name, data in files.items(): self.assertEqual(fake.files[name], data)
        self.assertTrue(fake.private_uploads)
        self.assertFalse(any('.station-recovery-' in name for name in fake.files))

    def test_restore_remote_corruption_rejects_before_promotion_and_cleans_stage(self):
        fake = RecoveryAdb({}, corrupt_private=True)
        args = SimpleNamespace(adb='fake-adb', serial='station')
        with patch.object(backup.subprocess, 'run', side_effect=fake.run):
            with self.assertRaisesRegex(ValueError, 'PRIVATE_REMOTE_HASH_MISMATCH'):
                backup.restore_device_files(args, {'files/native-tls.p12': b'control\x1aend'})
        self.assertNotIn('files/native-tls.p12', fake.files)
        self.assertFalse(any('.station-recovery-' in name for name in fake.files | {d: b'' for d in fake.dirs}))

    def test_restore_reports_fixed_stage_code_and_preserves_cause_without_secret(self):
        args = SimpleNamespace(adb='fake-adb', serial='station')
        # Fail each operation once, then permit cleanup of only our own stage.
        for position, code in enumerate(['CREATE_FILES', 'CREATE_STAGE', 'WRITE_STAGE', 'HASH_STAGE',
                                        'CREATE_TARGET_DIR', 'PROMOTE', 'MODE']):
            with self.subTest(operation=code):
                fake = RecoveryAdb({}); calls = 0
                original = ValueError('ADB_OPERATION_FAILED')
                def failing(*command, **kwargs):
                    nonlocal calls
                    calls += 1
                    if calls == position + 1: raise original
                    return fake.run(*command, **kwargs)
                with patch.object(backup.subprocess, 'run', side_effect=failing):
                    with self.assertRaisesRegex(ValueError, '^PRIVATE_ADB_' + code + '_FAILED$') as caught:
                        backup.restore_device_files(args, {'files/native-tls.p12': b'secret\x1a\x00payload'})
                self.assertIs(caught.exception.__cause__, original)
                self.assertNotIn('secret', str(caught.exception))
                self.assertFalse(any('.station-recovery-' in name for name in fake.files | {d:b'' for d in fake.dirs}))

    def test_cleanup_failure_preserves_original_stage_error(self):
        fake = RecoveryAdb({}); args = SimpleNamespace(adb='fake-adb', serial='station')
        def failing(command, **kwargs):
            if '-T' in command or ' rmdir ' in ' '.join(command):
                raise ValueError('ADB_OPERATION_FAILED')
            return fake.run(command, **kwargs)
        with patch.object(backup.subprocess, 'run', side_effect=failing), patch('builtins.print') as output:
            with self.assertRaisesRegex(ValueError, '^PRIVATE_ADB_WRITE_STAGE_FAILED$'):
                backup.restore_device_files(args, {'files/config.json': b'private payload'})
        self.assertEqual(output.call_args.args[0], 'Private temporary stage cleanup incomplete')


if __name__ == '__main__':
    unittest.main()
