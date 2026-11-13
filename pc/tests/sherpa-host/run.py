"""Pure fake-engine tests + Android28 Java8 compile. No JNI/device/API."""
from pathlib import Path
import os
import subprocess

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parents[3] / 'apps/voicebridge'
JAVA = Path(os.environ.get('STATION_JAVA_HOME', '')) / 'bin'
SDK = Path(os.environ.get('STATION_ANDROID_PARTS', str(PROJECT.parents[1] / '.deps/android')))
OUT = HERE / 'classes'
OUT.mkdir(exist_ok=True)
classpath = os.pathsep.join(str(p) for p in [
    Path(os.environ.get('STATION_SHERPA_DEPS', str(PROJECT.parents[1] / '.deps/sherpa'))) / 'downloads/sherpa-onnx-jvm-2.0.1.jar',
    HERE.parent / 'speechify-host/deps/json-20240303.jar',
    SDK / 'platform/android.jar',
])
sources = [PROJECT / 'src/local/station/voicebridge' / name for name in [
    'OnDeviceRecognizer.java', 'SherpaSpeechRecognizer.java', 'SherpaAsrProbe.java',
    'AndroidOpusDecoder.java', 'StreamingOpusInput.java', 'NativeOggInput.java']]
for command in [
    [JAVA / 'javac.exe', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
     '-Xlint:all', '-classpath', classpath, '-d', OUT,
     *sources, HERE / 'SherpaSpeechRecognizerHostTest.java'],
    [JAVA / 'java.exe', '-classpath', str(OUT) + os.pathsep + classpath,
     'local.station.voicebridge.SherpaSpeechRecognizerHostTest'],
]:
    subprocess.run([str(part) for part in command], check=True, timeout=40)
