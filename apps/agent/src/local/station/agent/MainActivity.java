package local.station.agent;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small TV screen; the real UI is the web page on port 18080. */
public final class MainActivity extends Activity {
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(40, 32, 40, 32);
        TextView title = new TextView(this);
        title.setText("Station Agent");
        title.setTextSize(30);
        body.addView(title);
        TextView url = new TextView(this);
        url.setText("http://192.168.77.100:18080");
        url.setTextSize(24);
        body.addView(url);
        status = new TextView(this);
        status.setTextSize(20);
        status.setText("Запускаю локальный сервер...");
        body.addView(status);
        setContentView(body);
        startAgent();
    }

    private void startAgent() {
        Intent service = new Intent(this, AgentService.class);
        startForegroundService(service);
        status.setText("Сервер работает. Откройте адрес выше в браузере.");
    }

    @Override protected void onResume() {
        super.onResume();
        if (status != null) status.setText("Сервер работает: http://192.168.77.100:18080");
    }
}
