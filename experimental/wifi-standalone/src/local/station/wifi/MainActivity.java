package local.station.wifi;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Explicit user-controlled split relay, never presented as an OTA firewall. */
public final class MainActivity extends Activity {
    private static final int CONSENT = 51;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout body = new LinearLayout(this); body.setOrientation(1); body.setPadding(48,32,48,32);
        TextView title = new TextView(this); title.setText("Станция · Wi-Fi"); title.setTextSize(28); body.addView(title);
        TextView note = new TextView(this); note.setText("Экспериментальный режим. Блокировка обновлений не гарантируется."); note.setTextSize(18); body.addView(note);
        Button start = new Button(this); start.setText("Включить локальные сервисы"); start.setOnClickListener(v -> prepare()); body.addView(start);
        Button stop = new Button(this); stop.setText("Выключить перехват"); stop.setOnClickListener(v -> {
            getSharedPreferences("wifi_mode",0).edit().putBoolean("enabled",false).commit();
            stopService(new Intent(this,WifiRelayService.class));
        }); body.addView(stop);
        Button network = new Button(this); network.setText("Выбрать сеть Wi-Fi"); network.setOnClickListener(v -> startActivity(new Intent("android.settings.WIFI_SETTINGS"))); body.addView(network);
        setContentView(body);
    }
    private void prepare() { Intent consent=VpnService.prepare(this); if(consent!=null) startActivityForResult(consent,CONSENT); else start(); }
    private void start() {
        getSharedPreferences("wifi_mode",0).edit().putBoolean("enabled",true).commit();
        startForegroundService(new Intent(this,WifiRelayService.class).setAction("start").putExtra("durationMs",0L));
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data); if(request==CONSENT&&result==RESULT_OK)start();
    }
}
