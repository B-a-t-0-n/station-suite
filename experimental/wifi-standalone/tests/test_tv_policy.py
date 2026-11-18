"""Real TV config, installed-UID seam and public CIDRs; no Android/device/network."""
from pathlib import Path
import hashlib
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
JSON_JAR = ROOT.parents[1] / 'pc/tests/speechify-host/deps/json-20240303.jar'
JSON_SHA = '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'


def java_tool(name):
    configured = (os.environ.get('STATION_JAVAC_HOME') if name == 'javac' else None) or os.environ.get('STATION_JAVA_HOME') or os.environ.get('JAVA_HOME')
    if configured:
        candidate = Path(configured) / 'bin' / (name + ('.exe' if os.name == 'nt' else ''))
        if candidate.is_file():
            return str(candidate)
    candidate = shutil.which(name)
    if not candidate:
        raise RuntimeError(name + ' required for offline TV tests')
    return candidate


class TvPolicyTests(unittest.TestCase):
    def test_private_config_shared_uid_changes_and_public_route_equivalence(self):
        self.assertEqual(hashlib.sha256(JSON_JAR.read_bytes()).hexdigest(), JSON_SHA)
        with tempfile.TemporaryDirectory(prefix='station-tv-policy-host-') as temporary:
            base = Path(temporary)
            sources = [ROOT / ('src/local/station/wifi/' + name + '.java')
                       for name in ['TvPrivateConfig', 'TvUidPolicy', 'TvRoutePlan']]
            sources += [ROOT / 'src/local/station/relay/SelectiveSocksGateway.java',
                        ROOT / 'tests/TvPolicyHostTest.java']
            for command in [
                [java_tool('javac'), '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-Xlint:all',
                 '-classpath', str(JSON_JAR), '-d', temporary, *map(str, sources)],
                [java_tool('java'), '-cp', temporary + os.pathsep + str(JSON_JAR),
                 'local.station.wifi.TvPolicyHostTest', str(base / 'private')],
            ]:
                result = subprocess.run(command, capture_output=True, text=True, encoding='utf8',
                                        errors='replace', timeout=35)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('assertions passed; offline TV config/UID/routes only; no device/network', result.stdout)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
