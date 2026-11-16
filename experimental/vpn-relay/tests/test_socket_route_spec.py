"""Adversarial admission checks for a one-request route canary; no Android/network."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


def java_tool(name):
    configured = os.environ.get('STATION_JAVA_HOME') or os.environ.get('JAVA_HOME')
    if configured:
        candidate = Path(configured) / 'bin' / (name + ('.exe' if os.name == 'nt' else ''))
        if candidate.is_file():
            return str(candidate)
    candidate = shutil.which(name)
    if not candidate:
        raise RuntimeError(name + ' is required for offline socket admission tests')
    return candidate


class SocketRouteSpecTests(unittest.TestCase):
    def test_only_fixed_lab_uid_mode_port_and_handle_arguments(self):
        with tempfile.TemporaryDirectory(prefix='station-route-spec-') as directory:
            sources = [ROOT / 'src/local/station/vpnprobe/SocketRouteSpec.java',
                       ROOT / 'tests/SocketRouteSpecHostTest.java']
            subprocess.run([java_tool('javac'), '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                            '-d', directory, *map(str, sources)], check=True, capture_output=True, timeout=30)
            result = subprocess.run([java_tool('java'), '-cp', directory, 'local.station.vpnprobe.SocketRouteSpecHostTest'],
                                    check=True, capture_output=True, text=True, timeout=10)
            self.assertIn('assertions passed', result.stdout)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
