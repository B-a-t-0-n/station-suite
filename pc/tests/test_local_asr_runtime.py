"""Actual LocalAsrRuntime worker ownership; fake codec/JNI only, no API/device."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2] / 'apps/voicebridge'
JAVAC = Path(os.environ.get('STATION_JAVAC_HOME', os.environ.get('STATION_JAVA_HOME', ''))) / 'bin/javac.exe'
JAVA = Path(os.environ.get('STATION_JAVA_HOME', '')) / 'bin' / 'java.exe'
JSON = Path(__file__).resolve().parent / 'speechify-host/deps/json-20240303.jar'


class LocalAsrRuntimeTests(unittest.TestCase):
    def test_final_only_queue_cancellation_race_and_warm_model_ownership(self):
        stubs = {
            'android/content/Context.java': '''package android.content;public class Context {
                public Context getApplicationContext(){return this;}
                public java.io.File getFilesDir(){return new java.io.File("SYNTHETIC-NO-IO");}
                public void startActivity(Intent intent){throw new AssertionError("unexpected control action");}}''',
            'android/content/Intent.java': '''package android.content;public class Intent {
                public static final String ACTION_MAIN="main",CATEGORY_HOME="home";
                public static final int FLAG_ACTIVITY_NEW_TASK=1;
                public Intent(String action){}public Intent addCategory(String category){return this;}
                public Intent addFlags(int flags){return this;}}''',
        }
        with tempfile.TemporaryDirectory(prefix='local-asr-runtime-host-') as temporary:
            target = Path(temporary)
            sources = []
            for name, source in stubs.items():
                path = target / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source, encoding='utf-8')
                sources.append(path)
            sources += [ROOT / 'src/local/station/voicebridge/OnDeviceRecognizer.java',
                        ROOT / 'src/local/station/voicebridge/LocalAsrRuntime.java',
                        ROOT / 'src/local/station/voicebridge/LocalVoiceCommands.java',
                        ROOT / 'src/local/station/voicebridge/MediaVoiceContext.java',
                        ROOT / 'src/local/station/voicebridge/TvVoiceCommand.java',
                        ROOT / 'src/local/station/voicebridge/TvChannelCommand.java',
                        Path(__file__).resolve().parent / 'local-asr-host/LocalAsrRuntimeHostTest.java']
            commands = [
                [JAVAC, '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-Xlint:all,-auxiliaryclass',
                 '-classpath', JSON, '-d', target, *sources],
                [JAVA, '-cp', str(target) + os.pathsep + str(JSON),
                 'local.station.voicebridge.LocalAsrRuntimeHostTest'],
            ]
            for command in commands:
                result = subprocess.run([str(x) for x in command], capture_output=True, text=True,
                                        encoding='utf-8', errors='replace', timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('local runtime assertions passed', result.stdout)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
