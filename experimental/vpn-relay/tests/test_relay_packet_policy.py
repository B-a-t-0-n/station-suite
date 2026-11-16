"""Compile actual packet policy and shared coverage parsers; no Android/network/device."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
COVERAGE = ROOT.parent / 'vpn-coverage/src/local/station/vpnprobe'


def java_tool(name):
    configured = os.environ.get('STATION_JAVA_HOME') or os.environ.get('JAVA_HOME')
    if configured:
        candidate = Path(configured) / 'bin' / (name + ('.exe' if os.name == 'nt' else ''))
        if candidate.is_file():
            return str(candidate)
    candidate = shutil.which(name)
    if not candidate:
        raise RuntimeError(name + ' is required for offline packet policy tests')
    return candidate


class RelayPacketPolicyTests(unittest.TestCase):
    def test_directional_admission_bounds_flow_limits_and_fuzz(self):
        with tempfile.TemporaryDirectory(prefix='station-relay-packet-host-') as temporary:
            sources = [COVERAGE / 'ProbeSpec.java', COVERAGE / 'PacketMetadata.java',
                       ROOT / 'src/local/station/vpnprobe/RelayPacketPolicy.java',
                       ROOT / 'tests/RelayPacketPolicyHostTest.java']
            for command in [
                [java_tool('javac'), '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-Xlint:all',
                 '-d', temporary, *map(str, sources)],
                [java_tool('java'), '-cp', temporary, 'local.station.vpnprobe.RelayPacketPolicyHostTest'],
            ]:
                result = subprocess.run(command, capture_output=True, text=True, encoding='utf8',
                                        errors='replace', timeout=35)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('assertions passed; directional packet policy only; no TCP stack/device/network', result.stdout)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
