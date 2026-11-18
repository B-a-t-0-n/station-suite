"""Check that only clear TV requests are routed and explicit sources are never widened."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest


class TvVoiceCommandTests(unittest.TestCase):
    def test_production_parser(self):
        repo = Path(__file__).resolve().parents[2]
        src = repo / 'apps/voicebridge/src/local/station/voicebridge'
        sources = [src / name for name in
                   ('TvVoiceCommand.java', 'TvChannelCommand.java', 'OkkoChannels.java', 'OkkoPublicChannelSnapshot.java',
                    'TvVideoSearch.java',
                    'OkkoPublicIndex.java', 'OkkoEpisodeCatalog.java')]
        harnesses = sorted(Path(__file__).with_name('tv-voice-host').glob('*.java'))
        json_jar = Path(__file__).with_name('speechify-host') / 'deps/json-20240303.jar'
        java = Path(os.environ['STATION_JAVA_HOME']) / 'bin/java.exe'
        javac = Path(os.environ.get('STATION_JAVAC_HOME', os.environ['STATION_JAVA_HOME'])) / 'bin/javac.exe'
        with tempfile.TemporaryDirectory(prefix='tv-voice-command-') as classes:
            commands = [
                [javac, '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-classpath', json_jar,
                 '-d', classes, *sources, *harnesses],
                *[[java, '-cp', classes + os.pathsep + str(json_jar), 'local.station.voicebridge.' + harness.stem]
                  for harness in harnesses]
            ]
            for command in commands:
                result = subprocess.run([str(argument) for argument in command], capture_output=True,
                                        text=True, encoding='utf-8', errors='replace', timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                if result.stdout.strip():
                    print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
