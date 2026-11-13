"""Compile actual pipeline/clients for Android28, then test orchestration with fake providers.

No devices, API calls, microphone, playback or real configuration access.
"""
from pathlib import Path
import os
import hashlib
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2] / 'apps/voicebridge'
TESTS = Path(__file__).resolve().parent
JAVAC = Path(os.environ.get('STATION_JAVAC_HOME', os.environ.get('STATION_JAVA_HOME', ''))) / 'bin/javac.exe'
JAVA = Path(os.environ.get('STATION_JAVA_HOME', '')) / 'bin' / 'java.exe'
ANDROID = Path(os.environ.get('STATION_ANDROID_PARTS', str(ROOT.parents[1] / '.deps/android'))) / 'platform/android.jar'
JSON_JAR = TESTS / "speechify-host/deps/json-20240303.jar"
JSON_SHA256 = "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed"


class VoicePipelineTests(unittest.TestCase):
    def test_actual_orchestration_and_safety_boundaries(self):
        self.assertEqual(hashlib.sha256(JSON_JAR.read_bytes()).hexdigest(), JSON_SHA256)
        sources = [ROOT / "src/local/station/voicebridge" / name for name in
                   ("VoicePipeline.java", "CloudClient.java", "SpeechifyClient.java")]
        test = TESTS / "pipeline-host/VoicePipelineHostTest.java"
        with tempfile.TemporaryDirectory(prefix="voice-pipeline-host-") as temporary:
            target = Path(temporary)
            android_classes = target / "android"
            host_classes = target / "host"
            android_classes.mkdir()
            host_classes.mkdir()
            classpath = str(JSON_JAR) + ";" + str(ANDROID)
            commands = [
                [JAVAC, "-encoding", "UTF-8", "-source", "8", "-target", "8", "-Xlint:all",
                 "-classpath", ANDROID, "-d", android_classes, *sources],
                [JAVAC, "-encoding", "UTF-8", "-source", "8", "-target", "8", "-Xlint:all",
                 "-classpath", classpath, "-d", host_classes, *sources, test],
                [JAVA, "-cp", str(host_classes) + ";" + classpath,
                 "local.station.voicebridge.VoicePipelineHostTest"],
            ]
            for command in commands:
                result = subprocess.run([str(x) for x in command], capture_output=True, text=True,
                                        encoding="utf8", errors="replace", timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("assertions passed; artificial providers only, no sockets/API requests", result.stdout)
            print(result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
