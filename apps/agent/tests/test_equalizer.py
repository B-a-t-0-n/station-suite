"""Compile all Android source, then exercise actual own-player EQ controller with fake engines."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path(os.environ['STATION_JAVA_HOME']) / 'bin'
ANDROID = Path(os.environ['STATION_ANDROID_PARTS']) / 'platform/android.jar'
JSON = ROOT.parents[1] / 'pc/tests/speechify-host/deps/json-20240303.jar'


class AgentEqualizerTests(unittest.TestCase):
    def test_real_controller_and_production_android_compile(self):
        with tempfile.TemporaryDirectory(prefix='agent-equalizer-test-') as folder:
            target = Path(folder)
            android, host = target / 'android', target / 'host'
            android.mkdir(); host.mkdir()
            commands = [
                [JAVA / 'javac.exe', '-encoding', 'UTF-8', '--release', '8', '-Xlint:all',
                 '-cp', ANDROID, '-d', android, *sorted((ROOT / 'src').rglob('*.java'))],
                [JAVA / 'javac.exe', '-encoding', 'UTF-8', '--release', '8', '-Xlint:all',
                 '-cp', JSON, '-d', host, ROOT / 'src/local/station/agent/AgentEqualizer.java',
                 ROOT / 'tests/equalizer-host/AgentEqualizerHostTest.java'],
                [JAVA / 'java.exe', '-cp', str(host) + ';' + str(JSON), 'local.station.agent.AgentEqualizerHostTest'],
            ]
            for command in commands:
                result = subprocess.run([str(part) for part in command], capture_output=True, text=True,
                    encoding='utf-8', errors='replace', timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('equalizer assertions passed', result.stdout)
            print(result.stdout.strip())

    def test_actual_android_adapter_owns_probe_and_never_uses_global_session(self):
        with tempfile.TemporaryDirectory(prefix='agent-equalizer-adapter-') as folder:
            target = Path(folder)
            sources = ROOT / 'src/local/station/agent'
            commands = [
                [JAVA / 'javac.exe', '-encoding', 'UTF-8', '--release', '8', '-Xlint:all',
                 '-cp', JSON, '-d', target, sources / 'AgentEqualizer.java', sources / 'AndroidAgentEqualizer.java',
                 *sorted((ROOT / 'tests/equalizer-adapter-host').rglob('*.java'))],
                [JAVA / 'java.exe', '-cp', str(target) + ';' + str(JSON), 'local.station.agent.AndroidAgentEqualizerHostTest'],
            ]
            for command in commands:
                result = subprocess.run([str(part) for part in command], capture_output=True, text=True,
                    encoding='utf-8', errors='replace', timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('adapter ownership assertions passed', result.stdout)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
