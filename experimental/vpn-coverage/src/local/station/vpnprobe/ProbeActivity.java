package local.station.vpnprobe;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Explicit shell-authorized entry. Default action only requests OS consent. */
public final class ProbeActivity extends Activity {
    private static final int CONSENT_REQUEST = 41;
    private TextView status;
    private String nonce;
    private String configIp;
    private boolean requestedProbe;
    private long createdElapsed;
    private boolean dispatched;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 32, 40, 32);
        root.setBackgroundColor(0xff11111a);
        status = new TextView(this);
        status.setTextColor(0xffeeeeff);
        status.setTextSize(22);
        root.addView(status);
        Button stop = new Button(this);
        stop.setText("Остановить проверку");
        stop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                stopService(new Intent(ProbeActivity.this, ProbeService.class));
                status.setText("Проверка остановлена");
            }
        });
        root.addView(stop);
        setContentView(root);
        if (state != null) {
            status.setText("Открой новую явную проверку. Повторный запуск не выполнялся.");
            return;
        }
        try {
            Intent input = getIntent();
            if (input == null || input.getComponent() == null
                    || !ProbeActivity.class.getName().equals(input.getComponent().getClassName())) {
                throw new IllegalArgumentException("Explicit Activity intent required");
            }
            requestedProbe = ProbeSpec.ACTION_PROBE.equals(input.getAction());
            if (requestedProbe) {
                nonce = ProbeSpec.validateNonce(input.getStringExtra(ProbeSpec.EXTRA_NONCE));
                configIp = ProbeSpec.validateConfigIp(input.getStringExtra(ProbeSpec.EXTRA_IP));
                createdElapsed = SystemClock.elapsedRealtime();
            } else if (input.getAction() != null
                    && !ProbeSpec.ACTION_PREPARE.equals(input.getAction())) {
                throw new IllegalArgumentException("Unsupported Activity action");
            }
            Intent consent = VpnService.prepare(this);
            if (consent == null) consentGranted();
            else {
                status.setText("Согласие Android на отдельную VPN-пробу");
                startActivityForResult(consent, CONSENT_REQUEST);
            }
        } catch (RuntimeException error) {
            status.setText("Проверка не запущена: " + error.getMessage());
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != CONSENT_REQUEST) return;
        if (result == RESULT_OK) consentGranted();
        else status.setText("Согласие не получено. VPN не запускался.");
    }

    private void consentGranted() {
        if (!requestedProbe) {
            status.setText("Согласие сохранено. VPN не запущен.\nОжидается отдельная явная проверка с watchdog.");
            return;
        }
        if (dispatched) return;
        try {
            ProbeSpec.requireFresh(createdElapsed, SystemClock.elapsedRealtime());
            dispatched = true;
            Intent probe = new Intent(this, ProbeService.class).setAction(ProbeSpec.ACTION_PROBE)
                    .putExtra(ProbeSpec.EXTRA_IP, configIp)
                    .putExtra(ProbeSpec.EXTRA_NONCE, nonce)
                    .putExtra(ProbeSpec.EXTRA_CREATED, createdElapsed);
            startForegroundService(probe);
            status.setText("Запрошена проверка до 10 секунд: " + configIp
                    + "/32\nСначала проверяется независимый watchdog.");
        } catch (RuntimeException error) {
            status.setText("Проверка не запущена: " + error.getMessage());
        }
    }
}
