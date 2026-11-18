"""Compile the production local history storage and HTTP protocol with Android API 28."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class VoiceHistoryTests(unittest.TestCase):
    def test_private_store_and_loopback_protocol(self):
        tests = Path(__file__).parent
        root = tests.resolve().parents[1]
        sources = root / 'apps/voicebridge/src/local/station/voicebridge'
        sdk = Path(os.environ['STATION_ANDROID_PARTS']) / 'platform/android.jar'
        json = tests / 'speechify-host/deps/json-20240303.jar'
        java_home = os.environ['STATION_JAVA_HOME']
        java = Path(java_home) / 'bin/java.exe'
        javac = Path(os.environ.get('STATION_JAVAC_HOME', java_home)) / 'bin/javac.exe'
        with tempfile.TemporaryDirectory(prefix='station-voice-history-') as tmp:
            storage = Path(tmp) / 'private'
            storage.mkdir()
            jars = os.pathsep.join([str(json), str(sdk)])
            commands = [
                [javac, '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-classpath', jars,
                 '-d', tmp, sources / 'VoiceHistory.java', sources / 'VoiceHistoryServer.java',
                 tests / 'voice-history-host/VoiceHistoryHostTest.java'],
                [java, '-cp', tmp + os.pathsep + jars, 'local.station.voicebridge.VoiceHistoryHostTest', storage],
            ]
            for command in commands:
                result = subprocess.run([str(part) for part in command], capture_output=True, text=True,
                                        encoding='utf-8', errors='replace', timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                if result.stdout.strip():
                    print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
