package local.station.agent;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** TV-friendly consent and status screen for the DNS-only guard. */
public final class VpnConsentActivity extends Activity {
    private static final int REQUEST_VPN = 1;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.START);
        body.setPadding(48, 40, 48, 40);

        TextView title = new TextView(this);
        title.setText("Station DNS guard");
        title.setTextSize(28);
        body.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Блокирует только DNS для OTA и телеметрии. Wi-Fi, LAN, ADB и PC Control остаются вне VPN.");
        hint.setTextSize(18);
        hint.setPadding(0, 12, 0, 20);
        body.addView(hint);

        status = new TextView(this);
        status.setTextSize(18);
        status.setPadding(0, 0, 0, 20);
        body.addView(status);

        Button start = new Button(this);
        start.setText("Включить DNS guard");
        start.setOnClickListener(v -> startGuard());
        body.addView(start);

        Button stop = new Button(this);
        stop.setText("Выключить DNS guard");
        stop.setOnClickListener(v -> stopGuard());
        body.addView(stop);

        setContentView(body);
        handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra("start", false)) startGuard();
        if (intent.getBooleanExtra("stop", false)) stopGuard();
    }

    private void startGuard() {
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            status.setText("Подтвердите VPN в системном окне");
            startActivityForResult(prepare, REQUEST_VPN);
            return;
        }
        startService(new Intent(this, DnsGuardVpnService.class));
        status.setText("DNS guard запускается…");
    }

    private void stopGuard() {
        stopService(new Intent(this, DnsGuardVpnService.class));
        status.setText("DNS guard остановлен");
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN) {
            if (resultCode == RESULT_OK) {
                startService(new Intent(this, DnsGuardVpnService.class));
                status.setText("DNS guard запускается…");
            } else {
                status.setText("VPN не разрешён");
            }
        }
    }

    private void refresh() {
        if (status == null) return;
        if (!DnsGuardVpnService.isActive()) {
            status.setText("DNS guard выключен");
            return;
        }
        status.setText("DNS guard активен\n"
                + "запросов: " + DnsGuardVpnService.queries()
                + " · заблокировано: " + DnsGuardVpnService.blocked()
                + " · pin: " + DnsGuardVpnService.pinned()
                + " · forward: " + DnsGuardVpnService.forwarded()
                + " · ошибок: " + DnsGuardVpnService.errors()
                + "\n" + DnsGuardVpnService.lastEvent());
        status.postDelayed(this::refresh, 1000);
    }
}
