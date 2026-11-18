"""Compile real public TCP admission with synthetic ownership and packet fixtures only."""
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
        raise RuntimeError(name + ' required for offline admission tests')
    return candidate


class TcpFlowAdmissionTests(unittest.TestCase):
    def test_owner_fail_closed_generations_direction_expiry_and_bounds(self):
        with tempfile.TemporaryDirectory(prefix='station-tcp-admission-host-') as temporary:
            sources = [ROOT / 'src/local/station/wifi/TcpFlowAdmission.java',
                       ROOT / 'src/local/station/wifi/DnsPolicy.java',
                       ROOT / 'src/local/station/relay/SelectiveSocksGateway.java',
                       ROOT.parent / 'vpn-coverage/src/local/station/vpnprobe/PacketMetadata.java',
                       ROOT / 'tests/TcpFlowAdmissionHostTest.java']
            for command in [
                [java_tool('javac'), '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-Xlint:all',
                 '-d', temporary, *map(str, sources)],
                [java_tool('java'), '-cp', temporary, 'local.station.wifi.TcpFlowAdmissionHostTest'],
            ]:
                result = subprocess.run(command, capture_output=True, text=True, encoding='utf8',
                                        errors='replace', timeout=35)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('assertions passed; offline public TCP UID admission only; no device/network/proc', result.stdout)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
