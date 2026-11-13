"""Compile production Android source and run pure queue/protocol/dispatch tests. No device or network."""
from pathlib import Path
import os
import hashlib
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path(os.environ['STATION_JAVA_HOME']) / 'bin'
ANDROID = Path(os.environ['STATION_ANDROID_PARTS']) / 'platform/android.jar'
JSON = ROOT.parents[1] / 'pc/tests/speechify-host/deps/json-20240303.jar'
JSON_SHA256 = '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'


class AgentMediaTests(unittest.TestCase):
    def test_production_media_state_and_protocol(self):
        self.assertEqual(hashlib.sha256(JSON.read_bytes()).hexdigest(), JSON_SHA256)
        with tempfile.TemporaryDirectory(prefix='station-agent-media-test-') as directory:
            temporary = Path(directory)
            android, host = temporary / 'android', temporary / 'host'
            android.mkdir(); host.mkdir()
            sources = ROOT / 'src/local/station/agent'
            commands = [
                [JAVA / 'javac.exe', '-encoding', 'UTF-8', '--release', '8', '-Xlint:all',
                 '-cp', ANDROID, '-d', android, *sorted((ROOT / 'src').rglob('*.java'))],
                [JAVA / 'javac.exe', '-encoding', 'UTF-8', '--release', '8', '-Xlint:all',
                 '-cp', JSON, '-d', host, sources / 'AgentMediaQueue.java',
                 sources / 'BluetoothMediaCommands.java', sources / 'AgentMediaDispatch.java',
                 ROOT / 'tests/media-host/AgentMediaHostTest.java'],
                [JAVA / 'java.exe', '-cp', str(host) + ';' + str(JSON), 'local.station.agent.AgentMediaHostTest'],
            ]
            for command in commands:
                result = subprocess.run([str(part) for part in command], capture_output=True, text=True,
                                        encoding='utf8', errors='replace', timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('assertions passed; fake actions only', result.stdout)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
