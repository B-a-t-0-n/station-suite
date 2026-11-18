"""Compile the Android accessibility integration and check deterministic behavior without a device."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class TvPlaybackControlTests(unittest.TestCase):
    def test_video_timestamps_bounds_and_permission(self):
        test_dir = Path(__file__).resolve().parent
        repo = test_dir.parents[1]
        src = repo / "apps/voicebridge/src/local/station/voicebridge"
        android = Path(os.environ["STATION_ANDROID_PARTS"]) / "platform/android.jar"
        java = Path(os.environ["STATION_JAVA_HOME"]) / "bin/java.exe"
        javac = Path(os.environ.get("STATION_JAVAC_HOME", os.environ["STATION_JAVA_HOME"])) / "bin/javac.exe"
        sources = [src / f"{name}.java" for name in
                   ("TvVoiceCommand", "TvChannelCommand", "TvPlaybackControl", "TvPlaybackAccessibilityService",
                    "MediaVoiceContext")]
        sources.append(test_dir / "tv-playback-host/TvPlaybackHostTest.java")
        with tempfile.TemporaryDirectory(prefix="station-tv-playback-") as output:
            for command in (
                [javac, "-encoding", "UTF-8", "-source", "8", "-target", "8", "-classpath", android,
                 "-d", output, *sources],
                [java, "-cp", output + os.pathsep + str(android), "local.station.voicebridge.TvPlaybackHostTest"],
            ):
                result = subprocess.run([str(arg) for arg in command], capture_output=True,
                                        text=True, encoding="utf-8", errors="replace", timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                if result.stdout.strip():
                    print(result.stdout.strip())
