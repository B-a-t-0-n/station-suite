"""Real consent-only Activity lifecycle with host Android stubs; no device/network."""
from pathlib import Path
import hashlib
import os
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JSON_JAR = ROOT.parents[1] / 'pc/tests/speechify-host/deps/json-20240303.jar'
JSON_SHA = '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'


def java_tool(name):
    configured = (os.environ.get('STATION_JAVAC_HOME') if name == 'javac' else None) or os.environ.get('STATION_JAVA_HOME') or os.environ.get('JAVA_HOME')
    if configured:
        candidate = Path(configured) / 'bin' / (name + ('.exe' if os.name == 'nt' else ''))
        if candidate.is_file():
            return str(candidate)
    candidate = shutil.which(name)
    if not candidate:
        raise RuntimeError(name + ' is required for offline Activity lifecycle tests')
    return candidate


STUBS = {
    'android/os/Bundle.java': '''package android.os; public class Bundle {
        private final java.util.Map<String,Boolean> values=new java.util.HashMap<String,Boolean>();
        public void putBoolean(String key,boolean value){values.put(key,value);}
        public boolean getBoolean(String key,boolean fallback){Boolean value=values.get(key);return value==null?fallback:value;}
    }''',
    'android/content/Intent.java': '''package android.content; public class Intent {
        private final java.util.Map<String,Object> extras=new java.util.HashMap<String,Object>();
        public Intent(){} public Intent(Context context,Class<?> type){}
        public Intent putExtra(String key,String value){extras.put(key,value);return this;}
        public Intent putExtra(String key,long value){extras.put(key,value);return this;}
        public String getStringExtra(String key){return (String)extras.get(key);}
        public long getLongExtra(String key,long fallback){Object value=extras.get(key);return value==null?fallback:((Long)value).longValue();}
        public Intent setAction(String action){return this;}
    }''',
    'android/content/SharedPreferences.java': '''package android.content; public class SharedPreferences {
        public Editor edit(){throw new AssertionError("consent must not edit persistent preferences");}
        public static class Editor { public Editor putBoolean(String key,boolean value){return this;} public boolean commit(){return true;} }
    }''',
    'android/content/Context.java': '''package android.content; public class Context {
        public java.io.File files;
        public int preferenceReads,serviceStarts,serviceStops,systemServiceReads;
        public Context getApplicationContext(){return this;}
        public java.io.File getFilesDir(){return files;}
        public SharedPreferences getSharedPreferences(String name,int mode){preferenceReads++;return new SharedPreferences();}
        public void startForegroundService(Intent intent){serviceStarts++;}
        public boolean stopService(Intent intent){serviceStops++;return true;}
        public <T>T getSystemService(Class<T> type){systemServiceReads++;throw new AssertionError("consent must not touch Wi-Fi");}
    }''',
    'android/app/Activity.java': '''package android.app; public class Activity extends android.content.Context {
        public static final int RESULT_OK=-1,RESULT_CANCELED=0;
        public android.content.Intent intent;
        public int dialogStarts,requestCode,finishes;
        public android.content.Intent getIntent(){return intent;}
        public void onCreate(android.os.Bundle state){}
        protected void onSaveInstanceState(android.os.Bundle state){}
        protected void onActivityResult(int request,int result,android.content.Intent data){}
        public void startActivityForResult(android.content.Intent intent,int request){dialogStarts++;requestCode=request;}
        public void finish(){finishes++;}
    }''',
    'android/net/VpnService.java': '''package android.net; public class VpnService {
        public static android.content.Intent consent;
        public static int preparations;
        public static android.content.Intent prepare(android.content.Context context){preparations++;return consent;}
    }''',
    'android/net/wifi/WifiConfiguration.java': '''package android.net.wifi; public class WifiConfiguration {
        public String SSID,preSharedKey;
        public java.util.BitSet allowedKeyManagement=new java.util.BitSet();
        public static class KeyMgmt {public static final int WPA_PSK=1;}
    }''',
    'android/net/wifi/ScanResult.java': '''package android.net.wifi; public class ScanResult {public String SSID;}''',
    'android/net/wifi/WifiManager.java': '''package android.net.wifi; public class WifiManager {
        public boolean startScan(){throw new AssertionError();}
        public java.util.List<ScanResult> getScanResults(){throw new AssertionError();}
        public boolean setWifiEnabled(boolean enabled){throw new AssertionError();}
        public boolean isWifiEnabled(){throw new AssertionError();}
        public int addNetwork(WifiConfiguration config){throw new AssertionError();}
        public boolean enableNetwork(int id,boolean exclusive){throw new AssertionError();}
        public boolean reconnect(){throw new AssertionError();}
    }''',
    'local/station/wifi/WifiRelayService.java': '''package local.station.wifi; public class WifiRelayService {}''',
    'local/station/wifi/TvControl.java': '''package local.station.wifi; final class TvControl {
        static int calls;static String action;
        static String command(android.app.Activity activity,String command){calls++;action=command;return "tv_stub";}
    }''',
    'local/station/wifi/SocketOwnerProbe.java': '''package local.station.wifi; final class SocketOwnerProbe {
        static int starts; static String nonce;
        static void start(android.app.Activity activity,String value){starts++;nonce=value;activity.finish();}
    }''',
    'local/station/wifi/SynOwnerProbeService.java': '''package local.station.wifi; final class SynOwnerProbeService {
        static int starts; static String nonce;
        static void request(android.app.Activity activity,String value){starts++;nonce=value;activity.finish();}
    }''',
}

HOST = '''package local.station.wifi;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
public final class ControlActivityHostTest {
    private static int assertions;
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    private static ControlActivity activity(Path root,String folder)throws Exception {
        ControlActivity value=new ControlActivity();value.files=Files.createDirectories(root.resolve(folder)).toFile();
        value.intent=new Intent().putExtra("command","prepare");return value;
    }
    private static String status(ControlActivity activity)throws Exception {
        return new JSONObject(new String(Files.readAllBytes(activity.files.toPath().resolve("control-status.json")),StandardCharsets.UTF_8)).getString("code");
    }
    private static void isolated(ControlActivity value){
        check(value.preferenceReads==0,"no persistent preference access");
        check(value.serviceStarts==0&&value.serviceStops==0,"no VPN service action");
        check(value.systemServiceReads==0,"no Wi-Fi operations");
    }
    public static void main(String[] args)throws Exception {
        Path root=java.nio.file.Paths.get(args[0]);
        VpnService.consent=new Intent();VpnService.preparations=0;
        ControlActivity pending=activity(root,"pending");
        Files.write(pending.files.toPath().resolve("control-status.json"),new JSONObject().put("code","consent_ready").toString().getBytes(StandardCharsets.UTF_8));
        pending.onCreate(null);
        check(pending.dialogStarts==1&&pending.requestCode==52,"one consent dialog");
        check(pending.finishes==0,"wait for consent result");
        check(!pending.files.toPath().resolve("control-status.json").toFile().exists(),"no premature success");
        isolated(pending);
        Bundle saved=new Bundle();pending.onSaveInstanceState(saved);
        ControlActivity recreated=activity(root,"pending");recreated.onCreate(saved);
        check(recreated.dialogStarts==0&&VpnService.preparations==1,"recreation does not repeat consent request");
        check(recreated.finishes==0,"recreation still waits for result");isolated(recreated);
        recreated.onActivityResult(99,ControlActivity.RESULT_OK,null);
        check(recreated.finishes==0,"unrelated result ignored");
        recreated.onActivityResult(52,ControlActivity.RESULT_OK,null);
        check("consent_ready".equals(status(recreated)),"accepted result persisted");
        check(recreated.finishes==1,"accepted result finishes");isolated(recreated);
        recreated.onActivityResult(52,ControlActivity.RESULT_CANCELED,null);
        check(recreated.finishes==1&&"consent_ready".equals(status(recreated)),"duplicate result ignored");
        ControlActivity declined=activity(root,"declined");declined.onCreate(null);
        declined.onActivityResult(52,ControlActivity.RESULT_CANCELED,null);
        check("consent_declined".equals(status(declined))&&declined.finishes==1,"declined result persisted and finishes");isolated(declined);
        VpnService.consent=null;
        ControlActivity ready=activity(root,"ready");ready.onCreate(null);
        check(ready.dialogStarts==0&&ready.finishes==1,"existing consent finishes without dialog");
        check("consent_ready".equals(status(ready)),"existing consent recorded");isolated(ready);
        ControlActivity diagnostic=activity(root,"diagnostic");
        diagnostic.intent=new Intent().putExtra("command","socket_owner_probe").putExtra("nonce","0123456789abcdef0123456789abcdef");
        int prepareBefore=VpnService.preparations;diagnostic.onCreate(null);
        check(SocketOwnerProbe.starts==1&&"0123456789abcdef0123456789abcdef".equals(SocketOwnerProbe.nonce),"diagnostic branch passes nonce exactly once");
        check(VpnService.preparations==prepareBefore,"diagnostic does not prepare VPN");
        check(!diagnostic.files.toPath().resolve("control-status.json").toFile().exists(),"diagnostic uses separate result file");
        check(diagnostic.finishes==1,"diagnostic controls its own completion");isolated(diagnostic);
        ControlActivity syn=activity(root,"syn");
        syn.intent=new Intent().putExtra("command","syn_owner_probe").putExtra("nonce","fedcba9876543210fedcba9876543210");
        syn.onCreate(null);
        check(SynOwnerProbeService.starts==1&&"fedcba9876543210fedcba9876543210".equals(SynOwnerProbeService.nonce),"SYN diagnostic receives exact nonce");
        check(!syn.files.toPath().resolve("control-status.json").toFile().exists(),"SYN diagnostic separate status");
        check(syn.finishes==1,"SYN diagnostic returns Activity");isolated(syn);
        int tvCalls=0;
        for(String action:new String[]{"tv_configure","tv_start","tv_stop"}){
            ControlActivity tv=activity(root,action);tv.intent=new Intent().putExtra("command",action);tv.onCreate(null);
            check(TvControl.calls==++tvCalls&&action.equals(TvControl.action),"TV branch delegates only its explicit action");
            check("tv_stub".equals(status(tv))&&tv.finishes==1,"TV branch returns its isolated result");isolated(tv);
        }
        System.out.println(assertions+" assertions passed; consent-only Activity lifecycle; no device/network");
    }
}'''


class ControlActivityTests(unittest.TestCase):
    def test_consent_activity_theme_can_wait_for_result(self):
        manifest = ET.parse(ROOT / 'AndroidManifest.xml').getroot()
        android = '{http://schemas.android.com/apk/res/android}'
        activity = next(item for item in manifest.find('application').findall('activity')
                        if item.get(android + 'name') == '.ControlActivity')
        self.assertEqual(activity.get(android + 'theme'), '@android:style/Theme.Translucent.NoTitleBar')
        self.assertEqual(activity.get(android + 'permission'), 'android.permission.DUMP')

    def test_prepare_and_recreation_never_start_vpn_or_change_wifi(self):
        self.assertEqual(hashlib.sha256(JSON_JAR.read_bytes()).hexdigest(), JSON_SHA)
        with tempfile.TemporaryDirectory(prefix='station-wifi-consent-host-') as temporary:
            root = Path(temporary)
            sources = []
            for name, source in dict(STUBS, **{'local/station/wifi/ControlActivityHostTest.java': HOST}).items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source, encoding='utf-8')
                sources.append(str(path))
            classes = root / 'classes'
            classes.mkdir()
            sources.append(str(ROOT / 'src/local/station/wifi/ControlActivity.java'))
            for command in [
                [java_tool('javac'), '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-cp', str(JSON_JAR), '-d', str(classes), *sources],
                [java_tool('java'), '-cp', str(classes) + os.pathsep + str(JSON_JAR), 'local.station.wifi.ControlActivityHostTest', str(root / 'files')],
            ]:
                result = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=35)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('assertions passed; consent-only Activity lifecycle; no device/network', result.stdout)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
