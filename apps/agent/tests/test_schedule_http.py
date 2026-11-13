"""Exercise the production HTTP dispatcher with fake peers and no device/network."""
from pathlib import Path
import os
import hashlib
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path(os.environ['STATION_JAVA_HOME']) / 'bin'
JSON = ROOT.parents[1] / 'pc/tests/speechify-host/deps/json-20240303.jar'
JSON_SHA256 = '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'

STUBS = {
    'android/content/Context.java': '''package android.content;
public class Context { public android.content.res.AssetManager getAssets(){return new android.content.res.AssetManager();} }''',
    'android/content/res/AssetManager.java': '''package android.content.res;
public class AssetManager { public java.io.InputStream open(String name){return new java.io.ByteArrayInputStream(new byte[]{1});} }''',
    'android/util/Log.java': '''package android.util;
public class Log { public static int i(String a,String b){return 0;} public static int e(String a,String b,Throwable t){return 0;}
 public static int w(String a,String b){return 0;} public static int w(String a,String b,Throwable t){return 0;} }''',
    'local/station/agent/NativeCommands.java': '''package local.station.agent;
final class NativeCommands { static boolean send(String c){throw new AssertionError("native command touched");} }''',
    'local/station/agent/AgentService.java': '''package local.station.agent;
import org.json.JSONObject;
final class AgentService {
 int scheduleCalls, equalizerCalls, displayCalls, playCalls; JSONObject received;
 JSONObject displayCommand(JSONObject request) throws Exception {displayCalls++;received=request;return new JSONObject().put("ok",true).put("scope","station_led");}
 JSONObject equalizerCommand(JSONObject request) throws Exception {equalizerCalls++;received=request;return new JSONObject().put("ok",true).put("scope","agent_player");}
 JSONObject scheduleCommand(JSONObject request) throws Exception {scheduleCalls++; received=request;return new JSONObject().put("ok",true).put("id","test-id");}
 byte[] scheduleTimerGif(){return new byte[]{1};} void scheduleDisplayAssetServed(){} JSONObject status(){return new JSONObject();}
 int setVolumePercent(int n){return n;} int adjustVolume(int n){return n;} int mute(boolean b){return 0;}
 void play(String a,String b,String c){playCalls++;} void pause(){} void resume(){} void stop(){} void next(){} void previous(){} void seekTo(int n){}
 String apps(){return "{}";} String launch(String a,String b){return "{}";} String tvKey(String a){return "{}";} String tvStatus(){return "{}";} String localStatus(){return "{}";}
 JSONObject proxyStatus(){return new JSONObject();} JSONObject vpnStatus(){return new JSONObject();} String vpnStart(){return "{}";} String vpnStop(){return "{}";}
 JSONObject dnsGuardStatus(){return new JSONObject();} String dnsGuardStart(){return "{}";} String dnsGuardStop(){return "{}";}
 String search(String a){return "{}";} String library(){return "{}";} String playlists(){return "{}";} String playlist(String a,String b){return "{}";}
}''',
}


class ScheduleHttpTests(unittest.TestCase):
    def test_real_dispatch_with_fake_peers(self):
        self.assertEqual(hashlib.sha256(JSON.read_bytes()).hexdigest(), JSON_SHA256)
        with tempfile.TemporaryDirectory(prefix='station-schedule-http-') as directory:
            temporary = Path(directory)
            for name, content in STUBS.items():
                path = temporary / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding='utf8')
            classes = temporary / 'classes'
            classes.mkdir()
            commands = [
                [JAVA / 'javac.exe', '-encoding', 'UTF-8', '--release', '8', '-Xlint:all', '-cp', JSON,
                 '-d', classes, *sorted(temporary.rglob('*.java')),
                 ROOT / 'src/local/station/agent/AgentHttpServer.java',
                 ROOT / 'src/local/station/agent/AgentMediaQueue.java',
                 ROOT / 'src/local/station/agent/StationDisplaySettings.java',
                 ROOT / 'src/local/station/agent/NativeDisplaySettings.java',
                 ROOT / 'src/local/station/agent/PcDisplaySettingsBridge.java',
                 ROOT / 'tests/schedule-http-host/AgentScheduleHttpHostTest.java'],
                [JAVA / 'java.exe', '-cp', str(classes) + ';' + str(JSON),
                 'local.station.agent.AgentScheduleHttpHostTest'],
            ]
            for command in commands:
                result = subprocess.run([str(part) for part in command], capture_output=True,
                                        text=True, encoding='utf8', errors='replace', timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('assertions passed; fake peers only', result.stdout)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
