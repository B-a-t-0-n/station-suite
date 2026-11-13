"""Real CloudClient on Android compiler + fake HTTPS host; no sockets, credentials, or API calls."""
from pathlib import Path
import os
import hashlib
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2] / 'apps/voicebridge'
HOST = Path(__file__).resolve().parent / "cloud-host"
JAVAC = Path(os.environ.get('STATION_JAVAC_HOME', os.environ.get('STATION_JAVA_HOME', ''))) / 'bin/javac.exe'
JAVA = Path(os.environ.get('STATION_JAVA_HOME', '')) / 'bin' / 'java.exe'
ANDROID = Path(os.environ.get('STATION_ANDROID_PARTS', str(ROOT.parents[1] / '.deps/android'))) / 'platform/android.jar'
JSON_JAR = HOST.parent / "speechify-host/deps/json-20240303.jar"
JSON_SHA256 = "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed"


class CloudClientTests(unittest.TestCase):
    def test_android_compile_and_fake_https(self):
        self.assertEqual(hashlib.sha256(JSON_JAR.read_bytes()).hexdigest(), JSON_SHA256)
        source = ROOT / "src/local/station/voicebridge/CloudClient.java"
        with tempfile.TemporaryDirectory(prefix="cloud-host-") as temporary:
            android_classes = Path(temporary) / "android"
            host_classes = Path(temporary) / "host"
            android_classes.mkdir()
            host_classes.mkdir()
            commands = [
                [JAVAC, "-encoding", "UTF-8", "-source", "8", "-target", "8", "-Xlint:all",
                 "-classpath", ANDROID, "-d", android_classes, source],
                [JAVAC, "-encoding", "UTF-8", "-source", "8", "-target", "8", "-Xlint:all",
                 "-classpath", JSON_JAR, "-d", host_classes, source, *HOST.rglob("*.java")],
                [JAVA, "-Dcloud.test.assets=" + str(ROOT / "assets"), "-cp",
                 str(host_classes) + ";" + str(JSON_JAR), "local.station.voicebridge.CloudClientHostTest"],
            ]
            for command in commands:
                result = subprocess.run([str(part) for part in command], capture_output=True, text=True,
                                        encoding="utf-8", errors="replace", timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("assertions passed; no sockets/API requests", result.stdout)
            print(result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
