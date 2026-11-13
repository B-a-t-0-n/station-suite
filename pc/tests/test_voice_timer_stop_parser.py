"""Actual final-ASR timer stop parser; no sockets or device actions."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2] / 'apps/voicebridge'
HOST = Path(__file__).resolve().parent / 'local-commands-host'
JAVAC = Path(os.environ.get('STATION_JAVAC_HOME', os.environ.get('STATION_JAVA_HOME', ''))) / 'bin/javac.exe'
JAVA = Path(os.environ.get('STATION_JAVA_HOME', '')) / 'bin' / 'java.exe'
JSON = HOST.parent / 'speechify-host/deps/json-20240303.jar'


class VoiceTimerStopParserTests(unittest.TestCase):
    def test_actual_final_only_stop_parser(self):
        with tempfile.TemporaryDirectory(prefix='voice-timer-stop-parser-') as temporary:
            commands = [
                [JAVAC, '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-classpath', JSON,
                 '-d', temporary, ROOT / 'src/local/station/voicebridge/LocalVoiceCommands.java',
                 HOST / 'VoiceTimerStopParserHostTest.java'],
                [JAVA, '-cp', str(temporary) + ';' + str(JSON),
                 'local.station.voicebridge.VoiceTimerStopParserHostTest'],
            ]
            for command in commands:
                result = subprocess.run([str(part) for part in command], capture_output=True,
                    text=True, encoding='utf-8', errors='replace', timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('stop parser assertions passed; fake actions only', result.stdout)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
