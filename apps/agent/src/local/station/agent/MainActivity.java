package local.station.agent;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small TV screen; the real UI is the web page on port 18080. */
public final class MainActivity extends Activity {
    private static final int REQUEST_ENABLE_BLUETOOTH = 51;
    private static final int REQUEST_PAIR_REMOTE = 52;
    private TextView status;
    private String pairingStatus;

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
        Button pairing = new Button(this);
        pairing.setText("Подключить Bluetooth-пульт");
        pairing.setOnClickListener(view -> requestBluetoothPairing());
        body.addView(pairing);
        setContentView(body);
        startAgent();
        consumePairingRequest(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        consumePairingRequest(intent);
    }

    private void consumePairingRequest(Intent intent) {
        if (intent != null && intent.getBooleanExtra("request_bluetooth_pairing", false)) {
            intent.removeExtra("request_bluetooth_pairing");
            requestBluetoothPairing();
        }
    }

    private void requestBluetoothPairing() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            status.setText("Bluetooth недоступен на этом устройстве.");
            return;
        }
        try {
            if (!adapter.isEnabled()) {
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                        REQUEST_ENABLE_BLUETOOTH);
                return;
            }
            Intent request = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            request.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120);
            pairingStatus = "Ожидается системное разрешение Bluetooth.";
            startActivityForResult(request, REQUEST_PAIR_REMOTE);
        } catch (ActivityNotFoundException | SecurityException error) {
            pairingStatus = "Системный экран сопряжения Bluetooth недоступен.";
            status.setText(pairingStatus);
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_ENABLE_BLUETOOTH && result == RESULT_OK) {
            requestBluetoothPairing();
        } else if (request == REQUEST_PAIR_REMOTE) {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            boolean visible = adapter != null && adapter.getScanMode()
                    == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE;
            pairingStatus = visible ? "Выберите эту Станцию в настройках Bluetooth телефона."
                    : result == RESULT_OK ? "Прошивка не поддерживает системный экран сопряжения."
                    : "Режим сопряжения не включён.";
            status.setText(pairingStatus);
        }
    }

    private void startAgent() {
        Intent service = new Intent(this, AgentService.class);
        startForegroundService(service);
        status.setText("Сервер работает. Откройте адрес выше в браузере.");
    }

    @Override protected void onResume() {
        super.onResume();
        if (status != null) status.setText(pairingStatus != null ? pairingStatus
                : "Сервер работает: http://192.168.77.100:18080");
    }
}
