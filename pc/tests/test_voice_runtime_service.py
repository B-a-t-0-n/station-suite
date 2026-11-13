"""Actual persistent Service/settings/receivers against deterministic Android stubs."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest
from test_probe_service_lifecycle import STUBS, ROOT, JAVAC, JAVA, JSON_JAR


class VoiceRuntimeServiceTests(unittest.TestCase):
    def test_persistence_disable_cleanup_recreation_and_listener_recovery(self):
        stubs = dict(STUBS)
        del stubs['local/station/voicebridge/RuntimeSettings.java']
        stubs['android/app/Service.java'] = stubs['android/app/Service.java'].replace(
            'START_NOT_STICKY=2', 'START_NOT_STICKY=2,START_STICKY=1')
        stubs['android/os/Process.java'] = 'package android.os; public final class Process {public static int myPid(){return 42;}}'
        stubs['android/os/SystemClock.java'] = '''package android.os; public final class SystemClock {
            private static long now=10000;public static long elapsedRealtime(){return now;}
            public static void advance(long amount){now+=amount;}}'''
        stubs['android/content/BroadcastReceiver.java'] = '''package android.content;
            public abstract class BroadcastReceiver {public abstract void onReceive(Context context,Intent intent);}'''
        stubs['android/content/SharedPreferences.java'] = '''package android.content;
            public interface SharedPreferences {boolean getBoolean(String name,boolean fallback);Editor edit();
                interface Editor {Editor putBoolean(String name,boolean value);boolean commit();}}'''
        stubs['android/content/Intent.java'] = '''package android.content;public class Intent {
            public static final String ACTION_BOOT_COMPLETED="boot",ACTION_MY_PACKAGE_REPLACED="upgrade";
            private final java.util.Map<String,String> extras=new java.util.HashMap<String,String>();private String action;
            public Class<?> target;public Intent(){}public Intent(Context context,Class<?> target){this.target=target;}
            public Intent putExtra(String name,String value){extras.put(name,value);return this;}
            public String getStringExtra(String name){return extras.get(name);}
            public Intent setAction(String value){action=value;return this;}public String getAction(){return action;}}'''
        stubs['android/content/Context.java'] = '''package android.content;public class Context {
            public static final int MODE_PRIVATE=0;public static int launched;
            public static int stopped;public static Class<?> lastStarted,lastStopped,failStartTarget;
            private static final java.util.Map<String,Boolean> prefs=new java.util.HashMap<String,Boolean>();
            public Context getApplicationContext(){return this;}
            public java.io.File getFilesDir(){throw new AssertionError("unexpected filesystem");}
            public android.content.res.AssetManager getAssets(){throw new AssertionError("unexpected assets");}
            public <T>T getSystemService(Class<T> type){try{return type.newInstance();}catch(Exception e){throw new AssertionError(e);}}
            public void startForegroundService(Intent intent){if(intent.target==failStartTarget)throw new SecurityException("RAW_PRIVATE_START_ERROR");launched++;lastStarted=intent.target;}
            public boolean stopService(Intent intent){stopped++;lastStopped=intent.target;return true;}
            public SharedPreferences getSharedPreferences(String name,int mode){return new SharedPreferences(){
                public boolean getBoolean(String key,boolean fallback){return prefs.containsKey(key)?prefs.get(key):fallback;}
                public Editor edit(){return new Editor(){public Editor putBoolean(String key,boolean value){prefs.put(key,value);return this;}
                    public boolean commit(){return true;}};}};}}'''
        stubs['local/station/voicebridge/LocalServicesService.java'] = '''package local.station.voicebridge;
            public final class LocalServicesService extends android.app.Service {
                static boolean provisioned,failCheck; static boolean configured(android.content.Context context){if(failCheck)throw new IllegalStateException("RAW_PRIVATE_CONFIG_ERROR");return provisioned;}}'''
        with tempfile.TemporaryDirectory(prefix='runtime-service-host-') as temporary:
            target = Path(temporary)
            sources = []
            for name, source in stubs.items():
                path = target / 'stubs' / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source, encoding='utf8'); sources.append(path)
            sources += [ROOT / 'src/local/station/voicebridge' / name for name in
                        ('VoiceRuntimeService.java', 'RuntimeSettings.java', 'RuntimeReceiver.java',
                         'BootReceiver.java', 'VoiceCloudGate.java')]
            sources.append(Path(__file__).resolve().parent / 'runtime-service-host/VoiceRuntimeServiceHostTest.java')
            classes = target / 'classes'; classes.mkdir()
            commands = [
                [JAVAC, '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-Xlint:all,-auxiliaryclass',
                 '-classpath', JSON_JAR, '-d', classes, *sources],
                [JAVA, '-cp', str(classes) + os.pathsep + str(JSON_JAR),
                 'local.station.voicebridge.VoiceRuntimeServiceHostTest']]
            for command in commands:
                result = subprocess.run([str(x) for x in command], capture_output=True, text=True,
                                        encoding='utf8', errors='replace', timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('runtime service lifecycle assertions passed', result.stdout)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
