"""Actual Java final-ASR parser contract, with typed fake timer actions only."""
from pathlib import Path
import os
import hashlib
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2] / 'apps/voicebridge'
HOST = Path(__file__).resolve().parent / "local-commands-host"
JAVAC = Path(os.environ.get('STATION_JAVAC_HOME', os.environ.get('STATION_JAVA_HOME', ''))) / 'bin/javac.exe'
JAVA = Path(os.environ.get('STATION_JAVA_HOME', '')) / 'bin' / 'java.exe'
JSON_JAR = HOST.parent / "speechify-host/deps/json-20240303.jar"
JSON_SHA256 = "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed"


class VoiceTimerParserTests(unittest.TestCase):
    def test_actual_final_asr_parser(self):
        self.assertEqual(hashlib.sha256(JSON_JAR.read_bytes()).hexdigest(), JSON_SHA256)
        source = ROOT / "src/local/station/voicebridge/LocalVoiceCommands.java"
        with tempfile.TemporaryDirectory(prefix="voice-timer-parser-") as temporary:
            commands = [
                [JAVAC, "-encoding", "UTF-8", "-source", "8", "-target", "8", "-Xlint:all",
                 "-classpath", JSON_JAR, "-d", temporary, source, HOST / "VoiceTimerParserHostTest.java"],
                [JAVA, "-cp", str(temporary) + ";" + str(JSON_JAR),
                 "local.station.voicebridge.VoiceTimerParserHostTest"],
            ]
            for command in commands:
                result = subprocess.run([str(part) for part in command], capture_output=True, text=True,
                                        encoding="utf-8", errors="replace", timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("timer parser assertions passed; fake actions only", result.stdout)
            print(result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
