"""Actual stop actuator, direct in-memory HTTP peers, no device/network."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2] / 'apps/voicebridge'
HOST = Path(__file__).resolve().parent / 'timer-stop-host'
JAVAC = Path(os.environ.get('STATION_JAVAC_HOME', os.environ.get('STATION_JAVA_HOME', ''))) / 'bin/javac.exe'
JAVA = Path(os.environ.get('STATION_JAVA_HOME', '')) / 'bin' / 'java.exe'
JSON = HOST.parent / 'speechify-host/deps/json-20240303.jar'


class VoiceTimerStopExecutorTests(unittest.TestCase):
    def test_real_typed_stop_and_music_priority(self):
        with tempfile.TemporaryDirectory(prefix='timer-stop-executor-') as temporary:
            commands = [
                [JAVAC, '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-classpath', JSON,
                 '-d', temporary, ROOT / 'src/local/station/voicebridge/LocalVoiceCommands.java',
                 HOST / 'VoiceTimerStopExecutorHostTest.java'],
                [JAVA, '-cp', str(temporary) + ';' + str(JSON),
                 'local.station.voicebridge.VoiceTimerStopExecutorHostTest'],
            ]
            for command in commands:
                result = subprocess.run([str(part) for part in command], capture_output=True,
                    text=True, encoding='utf-8', errors='replace', timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('stop executor assertions passed; fake HTTP only', result.stdout)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
