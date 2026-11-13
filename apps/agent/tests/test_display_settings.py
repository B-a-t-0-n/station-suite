"""Production display parser/controller/HTTP with fake peers; no device or live network."""
from pathlib import Path
import os
import hashlib
import subprocess
import tempfile
import unittest
import test_schedule_http as http

ROOT, JAVA, JSON = http.ROOT, http.JAVA, http.JSON


class DisplaySettingsTests(unittest.TestCase):
    def test_production_display(self):
        self.assertEqual(hashlib.sha256(JSON.read_bytes()).hexdigest(), http.JSON_SHA256)
        with tempfile.TemporaryDirectory(prefix='station-display-') as directory:
            temporary = Path(directory)
            for name, content in http.STUBS.items():
                path = temporary / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding='utf8')
            classes = temporary / 'classes'
            classes.mkdir()
            actual = [ROOT / ('src/local/station/agent/' + name + '.java') for name in (
                'AgentHttpServer', 'AgentMediaQueue', 'StationDisplaySettings', 'NativeDisplaySettings', 'PcDisplaySettingsBridge')]
            source = ROOT / 'tests/display-settings-host/StationDisplaySettingsHostTest.java'
            result = subprocess.run([str(JAVA / 'javac.exe'), '-encoding', 'UTF-8', '--release', '8',
                                     '-Xlint:all', '-cp', str(JSON), '-d', str(classes),
                                     *map(str, temporary.rglob('*.java')), *map(str, actual), str(source)],
                                    capture_output=True, text=True, encoding='utf8', errors='replace', timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            result = subprocess.run([str(JAVA / 'java.exe'), '-cp', str(classes) + ';' + str(JSON),
                                     'local.station.agent.StationDisplaySettingsHostTest'],
                                    capture_output=True, text=True, encoding='utf8', errors='replace', timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
