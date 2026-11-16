"""Compile the real DNS codec; no Android, device or network access."""
from pathlib import Path
import os
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
        raise RuntimeError(name + ' is required for offline DNS codec tests')
    return candidate


class DnsPolicyTests(unittest.TestCase):
    def test_wire_format_pins_upstream_validation_and_malformed_packets(self):
        with tempfile.TemporaryDirectory(prefix='station-wifi-dns-host-') as temporary:
            sources = [ROOT / 'src/local/station/wifi/DnsPolicy.java', ROOT / 'tests/DnsPolicyHostTest.java']
            for command in [
                [java_tool('javac'), '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-Xlint:all',
                 '-d', temporary, *map(str, sources)],
                [java_tool('java'), '-cp', temporary, 'local.station.wifi.DnsPolicyHostTest'],
            ]:
                result = subprocess.run(command, capture_output=True, text=True, encoding='utf8',
                                        errors='replace', timeout=35)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('assertions passed; offline DNS packet codec only; no device/network', result.stdout)
            print(result.stdout.strip())

    def test_local_tcp_direction_flow_expiry_capacity_and_packet_bounds(self):
        with tempfile.TemporaryDirectory(prefix='station-wifi-local-tcp-host-') as temporary:
            sources = [ROOT / 'src/local/station/wifi/DnsPolicy.java',
                       ROOT / 'src/local/station/wifi/LocalPacketPolicy.java',
                       ROOT.parent / 'vpn-coverage/src/local/station/vpnprobe/PacketMetadata.java',
                       ROOT / 'tests/LocalPacketPolicyHostTest.java']
            for command in [
                [java_tool('javac'), '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-Xlint:all',
                 '-d', temporary, *map(str, sources)],
                [java_tool('java'), '-cp', temporary, 'local.station.wifi.LocalPacketPolicyHostTest'],
            ]:
                result = subprocess.run(command, capture_output=True, text=True, encoding='utf8',
                                        errors='replace', timeout=35)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('assertions passed; offline local TCP admission only; no device/network', result.stdout)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
