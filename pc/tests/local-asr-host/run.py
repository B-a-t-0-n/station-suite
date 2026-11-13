"""Run bounded offline adapter tests without loading Vosk JNI or touching ADB."""
from pathlib import Path
import os
import subprocess

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parents[3] / 'apps/voicebridge'
DEPS = Path(os.environ.get('STATION_ASR_DEPS', str(PROJECT.parents[1] / '.deps/asr'))) / 'extracted'
JAVA = Path(os.environ.get('STATION_JAVA_HOME', '')) / 'bin'
OUT = HERE / 'classes'
OUT.mkdir(exist_ok=True)
classpath = os.pathsep.join(str(p) for p in [
    DEPS / 'vosk-android-0.3.75/classes.jar',
    DEPS / 'jna-5.18.1/classes.jar',
    HERE.parent / 'speechify-host/deps/json-20240303.jar',
])
sources = [PROJECT / 'src/local/station/voicebridge' / name for name in
           ['OnDeviceRecognizer.java', 'LocalSpeechRecognizer.java']]
commands = [
    [JAVA / 'javac.exe', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
     '-Xlint:all', '-classpath', classpath, '-d', OUT,
     *sources, HERE / 'LocalSpeechRecognizerHostTest.java'],
    [JAVA / 'java.exe', '-classpath', str(OUT) + os.pathsep + classpath,
     'local.station.voicebridge.LocalSpeechRecognizerHostTest'],
]
for command in commands:
    subprocess.run([str(part) for part in command], check=True, timeout=30)
