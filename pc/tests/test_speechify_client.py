"""Compile production client for Android 28, then test fake HTTPS on the host JVM.

No device access, API account, TLS overrides, network listeners or synthesis requests.
JSON-java is a host-only parser dependency; it is never added to the Android APK.
"""
from pathlib import Path
import os
import hashlib
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2] / 'apps/voicebridge'
HOST = Path(__file__).resolve().parent / "speechify-host"
JAVAC = Path(os.environ.get('STATION_JAVAC_HOME', os.environ.get('STATION_JAVA_HOME', ''))) / 'bin/javac.exe'
JAVA = Path(os.environ.get('STATION_JAVA_HOME', '')) / 'bin' / 'java.exe'
ANDROID = Path(os.environ.get('STATION_ANDROID_PARTS', str(ROOT.parents[1] / '.deps/android'))) / 'platform/android.jar'
JSON_JAR = HOST / "deps/json-20240303.jar"
JSON_SHA256 = "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed"


class SpeechifyHostTests(unittest.TestCase):
    def test_android_compile_and_fake_https_contract(self):
        self.assertEqual(hashlib.sha256(JSON_JAR.read_bytes()).hexdigest(), JSON_SHA256)
        source = ROOT / "src/local/station/voicebridge/SpeechifyClient.java"
        test = HOST / "SpeechifyClientHostTest.java"
        with tempfile.TemporaryDirectory(prefix="speechify-host-") as temporary:
            android_classes = Path(temporary) / "android"
            host_classes = Path(temporary) / "host"
            android_classes.mkdir()
            host_classes.mkdir()
            commands = [
                [JAVAC, "-encoding", "UTF-8", "-source", "8", "-target", "8", "-Xlint:all",
                 "-classpath", ANDROID, "-d", android_classes, source],
                [JAVAC, "-encoding", "UTF-8", "-source", "8", "-target", "8", "-Xlint:all",
                 "-classpath", JSON_JAR, "-d", host_classes, source, test],
                [JAVA, "-cp", str(host_classes) + ";" + str(JSON_JAR), "local.station.voicebridge.SpeechifyClientHostTest"],
            ]
            for command in commands:
                result = subprocess.run([str(part) for part in command], capture_output=True, text=True,
                                        encoding="utf-8", errors="replace", timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("assertions passed; no sockets/API requests", result.stdout)
            print(result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
