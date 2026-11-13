"""Actual fixed input protocol with fake executor and ephemeral loopback clients only."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest
import test_schedule_http as shared


class StationInputTests(unittest.TestCase):
    def test_fixed_parser_rate_limit_lifecycle_and_no_arbitrary_execution(self):
        root = shared.ROOT
        with tempfile.TemporaryDirectory(prefix='station-input-host-') as directory:
            temporary = Path(directory)
            stub = temporary / 'android/os/Process.java'
            stub.parent.mkdir(parents=True)
            stub.write_text('package android.os; public final class Process {public static int myUid(){return 2000;}}', encoding='utf8')
            classes = temporary / 'classes'; classes.mkdir()
            actual = [root / ('src/local/station/agent/' + name + '.java') for name in
                      ('StationInputHelper', 'StationInputClient', 'LocalDiagnostics', 'StationDisplaySettings',
                       'PcDisplaySettingsBridge', 'NativeDisplaySettings')]
            for command in [
                [shared.JAVA / 'javac.exe', '-encoding', 'UTF-8', '--release', '8', '-Xlint:all', '-cp', shared.JSON,
                 '-d', classes, stub, *actual, root / 'tests/station-input-host/StationInputHostTest.java'],
                [shared.JAVA / 'java.exe', '-cp', str(classes) + ';' + str(shared.JSON), 'local.station.agent.StationInputHostTest'],
            ]:
                result = subprocess.run([str(item) for item in command], capture_output=True, text=True,
                                        encoding='utf8', errors='replace', timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('assertions passed; fake executor only', result.stdout)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
