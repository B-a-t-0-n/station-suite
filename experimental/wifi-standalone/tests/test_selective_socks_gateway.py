"""Compile actual gateway and exercise real numeric-loopback SOCKS peers only."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


def java_tool(name):
    configured = (os.environ.get('STATION_JAVAC_HOME') if name == 'javac' else None) or os.environ.get('STATION_JAVA_HOME') or os.environ.get('JAVA_HOME')
    if configured:
        candidate = Path(configured) / 'bin' / (name + ('.exe' if os.name == 'nt' else ''))
        if candidate.is_file():
            return str(candidate)
    candidate = shutil.which(name)
    if not candidate:
        raise RuntimeError(name + ' required for offline gateway tests')
    return candidate


class SelectiveSocksGatewayTests(unittest.TestCase):
    def test_auth_dispatch_no_fallback_stream_deadlines_capacity_and_eof(self):
        with tempfile.TemporaryDirectory(prefix='station-selective-socks-host-') as temporary:
            sources = [ROOT / 'src/local/station/relay/SelectiveSocksGateway.java', ROOT / 'tests/SelectiveSocksGatewayHostTest.java']
            for command in [
                [java_tool('javac'), '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-Xlint:all', '-d', temporary, *map(str, sources)],
                [java_tool('java'), '-cp', temporary, 'local.station.relay.SelectiveSocksGatewayHostTest'],
            ]:
                result = subprocess.run(command, capture_output=True, text=True, encoding='utf8', errors='replace', timeout=35)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('assertions passed; authenticated gateway with real loopback SOCKS peers only; no device or external network', result.stdout)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
