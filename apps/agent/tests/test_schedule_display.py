"""Actual display reconciler, fake native IPC/runtime/clock only; no threads/device."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path(os.environ['STATION_JAVA_HOME']) / 'bin'
JSON = ROOT.parents[1] / 'pc/tests/speechify-host/deps/json-20240303.jar'


class ScheduleDisplayTests(unittest.TestCase):
    def test_durable_stop_delivery_retries_and_unobserved_ring(self):
        with tempfile.TemporaryDirectory(prefix='schedule-display-') as directory:
            target = Path(directory)
            clock = target / 'android/os/SystemClock.java'
            clock.parent.mkdir(parents=True)
            clock.write_text('package android.os;public final class SystemClock{public static long elapsedRealtime(){return 100000;}}', encoding='utf-8')
            commands = [
                [JAVA / 'javac.exe', '--release', '8', '-encoding', 'UTF-8', '-cp', JSON, '-d', target,
                 ROOT / 'src/local/station/agent/StationScheduleDisplay.java', clock,
                 ROOT / 'tests/display-host/ScheduleDisplayHostTest.java'],
                [JAVA / 'java.exe', '-cp', str(target) + ';' + str(JSON), 'local.station.agent.ScheduleDisplayHostTest']]
            for command in commands:
                result = subprocess.run([str(part) for part in command], capture_output=True, text=True,
                                        encoding='utf-8', errors='replace', timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
