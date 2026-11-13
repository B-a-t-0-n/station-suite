"""Compile actual ProbeService + LegacyPipelineHandler; simulate Android lifecycle only.

Dependency stubs perform no network, file configuration access, playback, device
operations or cloud calls. The real normalizer validates the public synthetic Ogg.
"""
from pathlib import Path
import hashlib
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2] / 'apps/voicebridge'
JAVAC = Path(os.environ.get('STATION_JAVAC_HOME', os.environ.get('STATION_JAVA_HOME', ''))) / 'bin/javac.exe'
JAVA = Path(os.environ.get('STATION_JAVA_HOME', '')) / 'bin' / 'java.exe'
JSON_JAR = Path(__file__).resolve().parent / 'speechify-host/deps/json-20240303.jar'
JSON_SHA = '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'

STUBS = {
'local/station/voicebridge/RuntimeSettings.java': '''package local.station.voicebridge; final class RuntimeSettings { static boolean enabled(android.content.Context context){return false;} }''',
'android/R.java': '''package android; public final class R { public static final class drawable { public static final int ic_menu_info_details=1; } }''',
'android/content/Context.java': '''package android.content;
public class Context {
 public Context getApplicationContext(){return this;}
 public java.io.File getFilesDir(){throw new AssertionError("unexpected filesystem access");}
 public android.content.res.AssetManager getAssets(){throw new AssertionError("unexpected asset access");}
 public <T> T getSystemService(Class<T> kind){try{return kind.newInstance();}catch(Exception e){throw new AssertionError(e);}}
}''',
'android/content/Intent.java': '''package android.content; public class Intent {
 private final java.util.Map<String,String> extras=new java.util.HashMap<String,String>();
 public Intent putExtra(String name,String value){extras.put(name,value);return this;}
 public String getStringExtra(String name){return extras.get(name);}
}''',
'android/content/res/AssetManager.java': '''package android.content.res; public class AssetManager {
 public java.io.InputStream open(String name) throws java.io.IOException {throw new AssertionError("unexpected asset access");}
}''',
'android/app/Service.java': '''package android.app; public class Service extends android.content.Context {
 public static final int START_NOT_STICKY=2; public int stopCalls;
 public void onCreate(){} public void onDestroy(){}
 public int onStartCommand(android.content.Intent intent,int flags,int startId){return START_NOT_STICKY;}
 public android.os.IBinder onBind(android.content.Intent intent){return null;}
 public void startForeground(int id,Notification notification){} public void stopSelf(){stopCalls++;}
}''',
'android/app/Notification.java': '''package android.app; public class Notification { public static class Builder {
 public Builder(android.content.Context context,String channel){} public Builder setSmallIcon(int id){return this;}
 public Builder setContentTitle(String title){return this;} public Builder setContentText(String text){return this;}
 public Builder setOnlyAlertOnce(boolean value){return this;} public Notification build(){return new Notification();}
}}''',
'android/app/NotificationChannel.java': '''package android.app; public class NotificationChannel {
 public NotificationChannel(String id,String name,int importance){} public void setSound(Object sound,Object attributes){}
}''',
'android/app/NotificationManager.java': '''package android.app; public class NotificationManager {
 public static final int IMPORTANCE_LOW=2; public void createNotificationChannel(NotificationChannel channel){}
}''',
'android/os/IBinder.java': '''package android.os; public interface IBinder {}''',
'android/os/Looper.java': '''package android.os; public final class Looper {
 private static final Looper MAIN=new Looper(); public static Looper getMainLooper(){return MAIN;}
}''',
'android/os/SystemClock.java': '''package android.os; public final class SystemClock {
 public static long elapsedRealtime(){return System.nanoTime()/1000000L;}
}''',
'android/os/Handler.java': '''package android.os; public class Handler {
 private static final class Task {final Handler owner;final Runnable action;Task(Handler o,Runnable a){owner=o;action=a;}}
 private static final java.util.ArrayDeque<Task> queue=new java.util.ArrayDeque<Task>();
 private static final java.util.ArrayDeque<Task> delayed=new java.util.ArrayDeque<Task>();
 public Handler(Looper looper){}
 public boolean post(Runnable action){synchronized(queue){queue.add(new Task(this,action));}return true;}
 public boolean postDelayed(Runnable action,long delay){synchronized(queue){delayed.add(new Task(this,action));}return true;}
 public void removeCallbacksAndMessages(Object token){synchronized(queue){
  java.util.Iterator<Task> it=queue.iterator();while(it.hasNext())if(it.next().owner==this)it.remove();
  it=delayed.iterator();while(it.hasNext())if(it.next().owner==this)it.remove();}}
 public static int pendingImmediate(){synchronized(queue){return queue.size();}}
 public static void drain(){for(;;){Task task;synchronized(queue){task=queue.poll();}if(task==null)return;task.action.run();}}
 public static void runDelayed(){java.util.ArrayDeque<Task> copy;
  synchronized(queue){copy=new java.util.ArrayDeque<Task>(delayed);delayed.clear();}
  while(!copy.isEmpty())copy.remove().action.run();}
}''',
'android/speech/tts/TextToSpeech.java': '''package android.speech.tts; public class TextToSpeech {
 public static final int ERROR=-1,SUCCESS=0; public interface OnInitListener {void onInit(int status);}
 public static final class EngineInfo {public String name;}
 public TextToSpeech(android.content.Context context,OnInitListener listener){}
 public void shutdown(){} public java.util.List<EngineInfo> getEngines(){return java.util.Collections.emptyList();}
 public int isLanguageAvailable(java.util.Locale language){return 0;}
}'''
}


class ProbeServiceLifecycleTests(unittest.TestCase):
    def test_actual_service_native_worker_ownership_and_queued_cleanup(self):
        self.assertEqual(hashlib.sha256(JSON_JAR.read_bytes()).hexdigest(), JSON_SHA)
        with tempfile.TemporaryDirectory(prefix='probe-service-host-') as temporary:
            target = Path(temporary)
            sources = []
            for name, source in STUBS.items():
                path = target / 'stubs' / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source, encoding='utf8')
                sources.append(path)
            sources += [ROOT / 'src/local/station/voicebridge' / name for name in
                        ('ProbeService.java', 'LegacyPipelineHandler.java', 'LegacyVoiceSession.java', 'NativeOggInput.java', 'VoiceCloudGate.java')]
            sources.append(Path(__file__).resolve().parent / 'probe-service-host/ProbeServiceLifecycleHost.java')
            classes = target / 'classes'
            classes.mkdir()
            commands = [
                [JAVAC, '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-Xlint:all,-auxiliaryclass',
                 '-classpath', JSON_JAR, '-d', classes, *sources],
                [JAVA, '-cp', str(classes) + os.pathsep + str(JSON_JAR),
                 'local.station.voicebridge.ProbeServiceLifecycleHost', ROOT / 'assets/legacy_reply_fixture.ogg']]
            for command in commands:
                result = subprocess.run([str(x) for x in command], capture_output=True, text=True,
                                        encoding='utf8', errors='replace', timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('lifecycle assertions passed; actual ProbeService/handler, no sockets/API/device', result.stdout)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
