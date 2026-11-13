"""Exercise encrypted restore validation without touching ADB or a deployment."""
import base64
import importlib.util
import json
from pathlib import Path
import secrets
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from cryptography.exceptions import InvalidTag

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


if __name__ == '__main__':
    unittest.main()
