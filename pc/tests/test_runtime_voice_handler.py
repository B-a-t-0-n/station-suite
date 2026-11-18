"""Actual runtime orchestration with fake providers and unconnected in-memory WS output."""
from pathlib import Path
import hashlib
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2] / 'apps/voicebridge'
JAVAC = Path(os.environ.get('STATION_JAVAC_HOME', os.environ.get('STATION_JAVA_HOME', ''))) / 'bin/javac.exe'
JAVA = Path(os.environ.get('STATION_JAVA_HOME', '')) / 'bin' / 'java.exe'
ANDROID = Path(os.environ.get('STATION_ANDROID_PARTS', str(ROOT.parents[1] / '.deps/android'))) / 'platform/android.jar'
JSON = Path(__file__).resolve().parent / 'speechify-host/deps/json-20240303.jar'


class RuntimeVoiceHandlerTests(unittest.TestCase):
    def test_multiple_questions_cancellation_circuit_and_cleanup(self):
        self.assertEqual(hashlib.sha256(JSON.read_bytes()).hexdigest(),
                         '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed')
        with tempfile.TemporaryDirectory(prefix='voice-runtime-handler-') as temporary:
            target = Path(temporary)
            sources = [ROOT / 'src/local/station/voicebridge' / name for name in
                       ('RuntimeVoiceHandler.java', 'VoiceHistory.java', 'VoiceCloudGate.java', 'VoicePipeline.java',
                        'CloudClient.java', 'SpeechifyClient.java', 'NativeOggInput.java',
                        'LegacyVoiceSession.java', 'LegacyVoiceReply.java', 'LoopbackVoiceServer.java')]
            test = Path(__file__).resolve().parent / 'runtime-host/RuntimeVoiceHandlerHostTest.java'
            for classpath, output, extra in ((str(ANDROID), target / 'android', []),
                    (str(JSON) + os.pathsep + str(ANDROID), target / 'host', [test])):
                output.mkdir()
                result = subprocess.run([str(x) for x in [JAVAC, '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                    '-Xlint:all', '-classpath', classpath, '-d', output, *sources, *extra]],
                    capture_output=True, text=True, encoding='utf8', timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            result = subprocess.run([str(JAVA), '-cp', str(target / 'host') + os.pathsep + str(JSON)
                    + os.pathsep + str(ANDROID), 'local.station.voicebridge.RuntimeVoiceHandlerHostTest',
                    str(ROOT / 'assets/legacy_reply_fixture.ogg')], capture_output=True, text=True,
                    encoding='utf8', timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('runtime assertions passed', result.stdout)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
