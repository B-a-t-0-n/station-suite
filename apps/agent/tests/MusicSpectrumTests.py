"""Host FFT/lifecycle checks and Android compilation; no device access."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path(os.environ['STATION_JAVA_HOME']) / 'bin'
ANDROID = Path(os.environ['STATION_ANDROID_PARTS']) / 'platform/android.jar'
JSON = ROOT.parents[1] / 'pc/tests/speechify-host/deps/json-20240303.jar'

VISUALIZER = r'''
package android.media.audiofx;
public final class Visualizer {
    public static final int SUCCESS=0, SCALING_MODE_NORMALIZED=0;
    public interface OnDataCaptureListener {
        void onWaveFormDataCapture(Visualizer owner,byte[] data,int rate);
        void onFftDataCapture(Visualizer owner,byte[] data,int rate);
    }
    public static int creations,releases,lastSession,maximumRate=25000;
    public static String fail="";
    public static Visualizer last;
    public int size,rate,scaling=-1;
    public boolean waveform,fft,released;
    private OnDataCaptureListener listener,retained;
    public static void reset(){creations=releases=lastSession=0;maximumRate=25000;fail="";last=null;}
    public Visualizer(int session){
        if(session<=0)throw new AssertionError("unowned session");
        if(fail.equals("permission"))throw new SecurityException("private engine error");
        creations++;lastSession=session;last=this;
    }
    public static int[] getCaptureSizeRange(){return new int[]{128,1024};}
    public static int getMaxCaptureRate(){return maximumRate;}
    public int setCaptureSize(int value){size=value;return fail.equals("setup")?-1:0;}
    public int setScalingMode(int value){scaling=value;return 0;}
    public int setDataCaptureListener(OnDataCaptureListener value,int count,boolean wave,boolean frequency){
        listener=value;if(value!=null)retained=value;rate=count;waveform=wave;fft=frequency;return 0;
    }
    public int setEnabled(boolean on){return on&&fail.equals("enable")?-1:0;}
    public void release(){if(released)throw new AssertionError("double release");released=true;releases++;}
    public void emit(byte[] data,int sampleRate){if(listener!=null)listener.onFftDataCapture(this,data,sampleRate);}
    public void emitRetained(byte[] data,int sampleRate){if(retained!=null)retained.onFftDataCapture(this,data,sampleRate);}
}
'''


class MusicSpectrumTests(unittest.TestCase):
    def test_fft_and_lifecycle_and_android_compile(self):
        sources = ROOT / 'src/local/station/agent'
        with tempfile.TemporaryDirectory(prefix='music-spectrum-') as folder:
            target = Path(folder)
            stub = target / 'android/media/audiofx/Visualizer.java'
            stub.parent.mkdir(parents=True)
            stub.write_text(VISUALIZER, encoding='utf-8')
            host = target / 'host'
            android = target / 'android-compile'
            host.mkdir(); android.mkdir()
            commands = [
                [JAVA / 'javac.exe', '-encoding', 'UTF-8', '--release', '8', '-Xlint:all',
                 '-cp', JSON, '-d', host, stub,
                 sources / 'MusicSpectrumFrames.java', sources / 'MusicSpectrumSource.java',
                 ROOT / 'tests/MusicSpectrumHostTest.java'],
                [JAVA / 'java.exe', '-cp', str(host) + ';' + str(JSON), 'local.station.agent.MusicSpectrumHostTest'],
                [JAVA / 'javac.exe', '-encoding', 'UTF-8', '--release', '8', '-Xlint:all',
                 '-cp', ANDROID, '-d', android, *sorted((ROOT / 'src').rglob('*.java'))],
            ]
            for command in commands:
                result = subprocess.run([str(part) for part in command], capture_output=True, text=True,
                                        encoding='utf-8', errors='replace', timeout=40)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                if result.stdout.strip(): print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
