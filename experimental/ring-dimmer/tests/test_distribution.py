"""Check the exact shell bytes shipped to Android, without newline conversion."""
from pathlib import Path
import unittest


class DistributionTests(unittest.TestCase):
    def test_android_shell_scripts_have_only_lf_line_endings(self):
        root = Path(__file__).resolve().parents[1]
        for name in ('ring-dimmer.sh', 'hidl-ring-dimmer.sh'):
            with self.subTest(name=name):
                payload = (root / name).read_bytes()
                self.assertNotIn(b'\r', payload, 'Android mksh treats a trailing CR as part of an argument')
                self.assertTrue(payload.endswith(b'\n'))
