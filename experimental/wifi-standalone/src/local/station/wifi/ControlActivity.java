package local.station.wifi;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

/** Shell-only control. Credentials come from a private one-use file, never intent extras/logs. */
public final class ControlActivity extends Activity {
    private static final int CONSENT_ONLY = 52;
    private static final String CONSENT_PENDING = "consent_pending";
    private boolean consentPending;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String code="invalid_action";
        try {
            String action=getIntent().getStringExtra("command");
            if("socket_owner_probe".equals(action)) {
                SocketOwnerProbe.start(this,getIntent().getStringExtra("nonce"));return;
            } else if("syn_owner_probe".equals(action)) {
                SynOwnerProbeService.request(this,getIntent().getStringExtra("nonce"));return;
            } else if("tv_configure".equals(action)||"tv_start".equals(action)||"tv_stop".equals(action)) {
                code=TvControl.command(this,action);
            } else if("prepare".equals(action)) {
                if(state!=null&&state.getBoolean(CONSENT_PENDING,false)){consentPending=true;return;}
                Files.deleteIfExists(new File(getFilesDir(),"control-status.json").toPath());
                Intent consent=VpnService.prepare(this);
                if(consent!=null){consentPending=true;startActivityForResult(consent,CONSENT_ONLY);return;}
                code="consent_ready";
            } else if("start".equals(action)) {
                if(VpnService.prepare(this)!=null) code="consent_required";
                else {
                    long duration=getIntent().getLongExtra("durationMs",600000L);
                    if(duration!=0&&(duration<1000||duration>3600000))throw new IllegalArgumentException();
                    getSharedPreferences("wifi_mode",0).edit().putBoolean("enabled",duration==0).commit();
                    startForegroundService(new Intent(this,WifiRelayService.class).setAction("start").putExtra("durationMs",duration));code="starting";
                }
            } else if("stop".equals(action)) {
                getSharedPreferences("wifi_mode",0).edit().putBoolean("enabled",false).commit();
                stopService(new Intent(this,WifiRelayService.class));code="stopping";
            } else if("join".equals(action)) code=join();
            else if("scan".equals(action)) {
                WifiManager wifi=getApplicationContext().getSystemService(WifiManager.class);
                wifi.startScan();
                java.util.TreeSet<String> names=new java.util.TreeSet<>();
                for(android.net.wifi.ScanResult item:wifi.getScanResults())if(item.SSID!=null&&!item.SSID.isEmpty()&&names.size()<64)names.add(item.SSID);
                JSONObject scan=new JSONObject().put("ssids",new org.json.JSONArray(names));
                Files.write(new File(getFilesDir(),"wifi-scan.json").toPath(),scan.toString().getBytes(StandardCharsets.UTF_8));
                code="scan_requested";
            }
            else if("wifi_off".equals(action)) {
                getApplicationContext().getSystemService(WifiManager.class).setWifiEnabled(false); code="wifi_disabled";
            }
        }catch(Exception failure){code="control_failed";}
        try{Files.write(new File(getFilesDir(),"control-status.json").toPath(),new JSONObject().put("code",code).toString().getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){}
        finish();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean(CONSENT_PENDING,consentPending);
        super.onSaveInstanceState(state);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=CONSENT_ONLY||!consentPending)return;
        consentPending=false;
        try{Files.write(new File(getFilesDir(),"control-status.json").toPath(),new JSONObject()
                .put("code",result==RESULT_OK?"consent_ready":"consent_declined").toString().getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){}
        finish();
    }
    private String join() throws Exception {
        File input=new File(getFilesDir(),"wifi-join-once.json");
        byte[] bytes=null;
        try {
            if(!input.isFile()||input.length()>1024)throw new IllegalArgumentException();
            bytes=Files.readAllBytes(input.toPath());
            JSONObject values=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
            String ssid=values.getString("ssid"),password=values.getString("password");
            if(ssid.isEmpty()||ssid.getBytes(StandardCharsets.UTF_8).length>32||password.length()<8||password.length()>63)throw new IllegalArgumentException();
            WifiManager wifi=getApplicationContext().getSystemService(WifiManager.class);
            if(!wifi.isWifiEnabled())return "enable_wifi_first";
            WifiConfiguration config=new WifiConfiguration();
            config.SSID=quote(ssid);config.preSharedKey=quote(password);
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            int id=wifi.addNetwork(config);if(id<0)return "add_network_failed";
            if(!wifi.enableNetwork(id,true))return "enable_network_failed";
            wifi.reconnect();return "joining";
        }finally{input.delete();if(bytes!=null)java.util.Arrays.fill(bytes,(byte)0);}
    }
    private static String quote(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
}
