"""Actual incremental parser/Android decoder with fake codec and synthetic PCM; no devices/API."""
from pathlib import Path
import os
import hashlib
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2] / 'apps/voicebridge'
HOST = Path(__file__).resolve().parent / "streaming-opus-host"
JAVAC = Path(os.environ.get('STATION_JAVAC_HOME', os.environ.get('STATION_JAVA_HOME', ''))) / 'bin/javac.exe'
JAVA = Path(os.environ.get('STATION_JAVA_HOME', '')) / 'bin' / 'java.exe'
ANDROID = Path(os.environ.get('STATION_ANDROID_PARTS', str(ROOT.parents[1] / '.deps/android'))) / 'platform/android.jar'
FIXTURE_SHA256 = "7cf6c9c078dc8ae1bfc9ba7d1c7158750862f4071b6c9bc11b42f8c6191b5ac5"


class StreamingOpusTests(unittest.TestCase):
    def test_production_parser_and_decoder_with_fake_codec(self):
        fixture = ROOT / "assets/legacy_reply_fixture.ogg"
        self.assertEqual(hashlib.sha256(fixture.read_bytes()).hexdigest(), FIXTURE_SHA256)
        sources = [ROOT / "src/local/station/voicebridge" / name for name in
                   ("NativeOggInput.java", "StreamingOpusInput.java", "AndroidOpusDecoder.java")]
        with tempfile.TemporaryDirectory(prefix="streaming-opus-host-") as temporary:
            android_classes = Path(temporary) / "android"
            host_classes = Path(temporary) / "host"
            android_classes.mkdir()
            host_classes.mkdir()
            commands = [
                [JAVAC, "-encoding", "UTF-8", "-source", "8", "-target", "8", "-Xlint:all",
                 "-classpath", ANDROID, "-d", android_classes, *sources],
                [JAVAC, "-encoding", "UTF-8", "-source", "8", "-target", "8", "-Xlint:all",
                 "-d", host_classes, *sources, *HOST.rglob("*.java")],
                [JAVA, "-cp", host_classes, "local.station.voicebridge.StreamingOpusHostTest", fixture],
            ]
            for command in commands:
                result = subprocess.run([str(part) for part in command], capture_output=True, text=True,
                                        encoding="utf-8", errors="replace", timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("assertions passed; fake codec/synthetic PCM only", result.stdout)
            print(result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
