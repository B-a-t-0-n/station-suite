"""Compile the on-device catalog/cache and exercise synthetic sources over actual loopback HTTP."""
from pathlib import Path
import os
import hashlib
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2] / 'apps/voicebridge'
HOST = Path(__file__).resolve().parent / "music-local-host"
JAVA = Path(os.environ.get('STATION_JAVA_HOME', '')) / 'bin'
ANDROID = Path(os.environ.get('STATION_ANDROID_PARTS', str(ROOT.parents[1] / '.deps/android'))) / 'platform/android.jar'
JSON_JAR = HOST.parent / "speechify-host/deps/json-20240303.jar"


class LocalMusicTests(unittest.TestCase):
    def test_source_and_loopback_service(self):
        self.assertEqual(hashlib.sha256(JSON_JAR.read_bytes()).hexdigest(),
                         "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed")
        source = ROOT / "src/local/station/voicebridge"
        sources = [source / (name + ".java") for name in
                   ("LocalMusicUrls", "LocalMusicRequests", "LocalMusicCatalog", "LocalMusicServer")]
        with tempfile.TemporaryDirectory(prefix="local-music-host-") as directory:
            android = Path(directory) / "android"
            host = Path(directory) / "host"
            android.mkdir()
            host.mkdir()
            commands = [
                [JAVA / "javac.exe", "-encoding", "UTF-8", "--release", "8", "-Xlint:all",
                 "-cp", ANDROID, "-d", android, *sources],
                [JAVA / "javac.exe", "-encoding", "UTF-8", "--release", "8", "-Xlint:all",
                 "-cp", JSON_JAR, "-d", host, *sources, *HOST.glob("*.java")],
                [JAVA / "java.exe", "-cp", str(host) + ";" + str(JSON_JAR),
                 "local.station.voicebridge.LocalMusicSourceHostTest"],
                [JAVA / "java.exe", "-cp", str(host) + ";" + str(JSON_JAR),
                 "local.station.voicebridge.LocalMusicServerHostTest"],
                [JAVA / "java.exe", "-cp", str(host) + ";" + str(JSON_JAR),
                 "local.station.voicebridge.LocalMusicTransportHostTest"],
            ]
            for command in commands:
                result = subprocess.run([str(part) for part in command], capture_output=True, text=True,
                                        encoding="utf-8", errors="replace", timeout=45)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                if result.stdout:
                    print(result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
