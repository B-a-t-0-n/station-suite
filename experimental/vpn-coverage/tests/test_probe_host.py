"""Host checks for safety admission and metadata minimization. No Android/device/network."""
from pathlib import Path
import hashlib
import hmac
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
        raise RuntimeError(f'{name} is required for offline parser tests')
    return candidate


class ProbeHostTests(unittest.TestCase):
    def test_narrow_ipv6_control_classifier(self):
        with tempfile.TemporaryDirectory(prefix='station-vpn-ipv6-control-host-') as temporary:
            commands = [
                [java_tool('javac'), '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-Xlint:all', '-d', temporary,
                 str(ROOT / 'src/local/station/vpnprobe/PacketMetadata.java'),
                 str(ROOT / 'tests/Ipv6ControlHostTest.java')],
                [java_tool('java'), '-cp', temporary, 'local.station.vpnprobe.Ipv6ControlHostTest'],
            ]
            for command in commands:
                result = subprocess.run(command, capture_output=True, text=True, encoding='utf8',
                                        errors='replace', timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('assertions passed; TUN IPv6 control classifier only; no IPv6 security claim', result.stdout)
            print(result.stdout.strip())

    def test_actual_admission_and_metadata_parsers(self):
        with tempfile.TemporaryDirectory(prefix='station-vpn-probe-host-') as temporary:
            sources = ROOT / 'src/local/station/vpnprobe'
            commands = [
                [java_tool('javac'), '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-Xlint:all', '-d', temporary,
                 str(sources / 'ProbeSpec.java'), str(sources / 'PacketMetadata.java'),
                 str(ROOT / 'tests/ProbeHostTest.java')],
                [java_tool('java'), '-cp', temporary, 'local.station.vpnprobe.ProbeHostTest'],
            ]
            for command in commands:
                result = subprocess.run(command, capture_output=True, text=True, encoding='utf8',
                                        errors='replace', timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('assertions passed; production parsers only; no device or network', result.stdout)
            print(result.stdout.strip())

    def test_watchdog_mutual_authentication_against_independent_python_hmac(self):
        with tempfile.TemporaryDirectory(prefix='station-watchdog-protocol-host-') as temporary:
            source = ROOT / 'src/local/station/vpnprobe/WatchdogProtocol.java'
            compile_result = subprocess.run(
                [java_tool('javac'), '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-Xlint:all', '-d', temporary,
                 str(source), str(ROOT / 'tests/WatchdogProtocolHostTest.java')],
                capture_output=True, text=True, encoding='utf8', errors='replace', timeout=30)
            self.assertEqual(compile_result.returncode, 0, compile_result.stdout + compile_result.stderr)
            java = [java_tool('java'), '-cp', temporary, 'local.station.vpnprobe.WatchdogProtocolHostTest']
            checks = subprocess.run(java, capture_output=True, text=True, encoding='utf8',
                                    errors='replace', timeout=30)
            self.assertEqual(checks.returncode, 0, checks.stdout + checks.stderr)
            self.assertIn('assertions passed; authenticated watchdog protocol only; no sockets', checks.stdout)
            print(checks.stdout.strip())
            # This implementation is independent of the Java protocol and uses public test keys only.
            for secret, challenge, remaining in [
                ('00112233445566778899aabbccddeeff', 'fedcba98765432100123456789abcdef', 15000),
                ('00112233445566778899AABBCCDDEEFF', 'FEDCBA98765432100123456789ABCDEF', 12000),
                ('ffffffffffffffffffffffffffffffff', '00000000000000000000000000000000', 1),
            ]:
                arm_mac = hmac.new(bytes.fromhex(secret), ('STATION-VPN/1 ARM\n' + challenge).encode('ascii'),
                                   hashlib.sha256).hexdigest()
                ready_mac = hmac.new(bytes.fromhex(secret),
                                     f'STATION-VPN/1 READY\n{challenge}\n{remaining}\n2000'.encode('ascii'),
                                     hashlib.sha256).hexdigest()
                output = subprocess.run([*java, 'vectors', secret, challenge, str(remaining)],
                                        capture_output=True, text=True, encoding='utf8',
                                        errors='replace', timeout=30)
                self.assertEqual(output.returncode, 0, output.stderr)
                self.assertEqual(output.stdout.splitlines(),
                                 [f'STATION-VPN/1 ARM {challenge} {arm_mac}', ready_mac])


if __name__ == '__main__':
    unittest.main()
