"""Check hardware counter arithmetic with synthetic samples."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path(os.environ['STATION_JAVA_HOME']) / 'bin'
SDK = Path(os.environ['STATION_ANDROID_PARTS']) / 'platform/android.jar'
JSON = ROOT.parents[1] / 'pc/tests/speechify-host/deps/json-20240303.jar'


class HardwareMetricsTests(unittest.TestCase):
    def test_counter_deltas_and_fallbacks(self):
        source = ROOT / 'src/local/station/agent/StationHardwareMetrics.java'
        harness = ROOT / 'tests/hardware-metrics-host/StationHardwareMetricsHostTest.java'
        dependencies = os.pathsep.join(map(str, (JSON, SDK)))
        with tempfile.TemporaryDirectory(prefix='station-hardware-metrics-') as directory:
            for command in (
                [JAVA / 'javac.exe', '-encoding', 'UTF-8', '--release', '8',
                 '-classpath', dependencies, '-d', directory, source, harness],
                [JAVA / 'java.exe', '-classpath', directory + os.pathsep + dependencies,
                 'local.station.agent.StationHardwareMetricsHostTest'],
            ):
                result = subprocess.run(list(map(str, command)), capture_output=True,
                                        text=True, encoding='utf8', errors='replace', timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                if result.stdout.strip():
                    print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
