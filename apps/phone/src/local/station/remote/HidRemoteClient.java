package local.station.remote;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** Foreground Bluetooth keyboard/consumer remote. No network or Agent is used. */
final class HidRemoteClient {
    interface Listener {
        void onHidState(String state, boolean connected);
    }

    private static final int KEYBOARD_REPORT = 1;
    private static final int CONSUMER_REPORT = 2;
    private static final long KEY_HOLD_MS = 80;
    private static final long MIN_PRESS_INTERVAL_MS = 110;
    private static final int MAX_RECONNECTS = 2;

    // Standard keyboard (modifier, reserved, six usages) and one 16-bit consumer usage.
    private static final byte[] DESCRIPTOR = bytes(
            0x05, 0x01, 0x09, 0x06, 0xa1, 0x01, 0x85, 0x01,
            0x05, 0x07, 0x19, 0xe0, 0x29, 0xe7, 0x15, 0x00,
            0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
            0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
            0x19, 0x00, 0x29, 0x65, 0x81, 0x00, 0xc0,
            0x05, 0x0c, 0x09, 0x01, 0xa1, 0x01, 0x85, 0x02,
            0x15, 0x00, 0x26, 0xff, 0x03, 0x19, 0x00,
            0x2a, 0xff, 0x03, 0x75, 0x10, 0x95, 0x01,
            0x81, 0x00, 0xc0);

    private final Activity activity;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private BluetoothDevice target;
    private boolean requested, registered, connected, closed = true;
    private int generation, reconnects, heldReport;
    private long lastPress;
    private byte[] keyboard = new byte[8];
    private byte[] consumer = new byte[2];

    HidRemoteClient(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    /** Call while the Activity is resumed, after BLUETOOTH_CONNECT permission. */
    void start(BluetoothDevice device) {
        if (Build.VERSION.SDK_INT < 28) {
            state("Для Bluetooth-пульта нужен Android 9 или новее");
            return;
        }
        if (!closed && target != null && target.equals(device) && connected) return;
        close();
        closed = false;
        target = device;
        reconnects = 0;
        lastPress = 0;
        final int session = ++generation;
        try {
            adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                state("Включи Bluetooth");
                return;
            }
            if (device == null || device.getBondState() != BluetoothDevice.BOND_BONDED) {
                state("Сначала сопряги телефон со Станцией");
                return;
            }
            state("Подключаю кнопки пульта…");
            requested = adapter.getProfileProxy(activity, new HidProfileListener(this, session),
                    BluetoothProfile.HID_DEVICE);
            if (!requested) state("Телефон не предоставил профиль Bluetooth-пульта");
        } catch (SecurityException error) {
            requested = false;
            state("Разреши доступ к устройствам поблизости");
        } catch (Exception error) {
            requested = false;
            state("Не удалось подготовить Bluetooth-пульт");
        }
    }

    private void register(final int session) {
        try {
            BluetoothHidDeviceAppSdpSettings settings = new BluetoothHidDeviceAppSdpSettings(
                    "Station Remote", "TV remote keyboard", "Station Lab",
                    BluetoothHidDevice.SUBCLASS1_KEYBOARD, DESCRIPTOR);
            boolean accepted = hid.registerApp(settings, null, null, main::post,
                    new HidDeviceCallback(this, session));
            if (!accepted) {
                requested = false;
                state("Не удалось зарегистрировать кнопки Bluetooth");
            }
        } catch (Exception error) {
            state("Не удалось зарегистрировать кнопки Bluetooth");
        }
    }

    void profileConnected(int session, int profile, BluetoothProfile proxy) {
        main.post(() -> {
            if (closed || session != generation) {
                try { adapter.closeProfileProxy(profile, proxy); } catch (Exception ignored) { }
                return;
            }
            hid = (BluetoothHidDevice) proxy;
            register(session);
        });
    }

    void profileDisconnected(int session) {
        main.post(() -> {
            if (closed || session != generation) return;
            connected = registered = false;
            requested = false;
            heldReport = 0;
            hid = null;
            state("Bluetooth-пульт отключён · подключись снова");
        });
    }

    void appStatusChanged(int session, boolean ready) {
        if (closed || session != generation) return;
        registered = ready;
        if (ready) connect();
        else {
            connected = false;
            heldReport = 0;
            state("Кнопки пульта отключены · открой пульт снова");
        }
    }

    void connectionChanged(int session, BluetoothDevice device, int status) {
        if (closed || session != generation || !device.equals(target)) return;
        connected = status == BluetoothProfile.STATE_CONNECTED;
        if (connected) {
            reconnects = 0;
            heldReport = 0;
            keyboard = new byte[8];
            consumer = new byte[2];
            releaseAll();
            state("Кнопки подключены по Bluetooth");
        } else if (status == BluetoothProfile.STATE_CONNECTING) {
            state("Подключаю кнопки пульта…");
        } else if (status == BluetoothProfile.STATE_DISCONNECTED) {
            heldReport = 0;
            state("Кнопки отключены · восстанавливаю связь…");
            if (registered && reconnects++ < MAX_RECONNECTS) {
                main.postDelayed(() -> {
                    if (!closed && session == generation && !connected && registered) connect();
                }, 1500);
            } else state("Не удалось подключить кнопки · повтори подключение");
        }
    }

    void getReport(int session, BluetoothDevice device, byte type, byte id) {
        if (closed || session != generation || hid == null) return;
        try {
            if (id == KEYBOARD_REPORT) hid.replyReport(device, type, id, keyboard.clone());
            else if (id == CONSUMER_REPORT) hid.replyReport(device, type, id, consumer.clone());
            else hid.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID);
        } catch (Exception ignored) { }
    }

    void virtualCableUnplug(int session) {
        if (closed || session != generation) return;
        connected = false;
        heldReport = 0;
        state("Станция отключила Bluetooth-пульт");
    }

    private void connect() {
        if (closed || hid == null || !registered || target == null) return;
        try {
            if (!hid.connect(target)) state("Подключение кнопок не началось · повтори подключение");
            else {
                final int session = generation;
                main.postDelayed(() -> {
                    if (!closed && session == generation && !connected) {
                        state("Кнопки не подключились · повтори подключение");
                    }
                }, 12000);
            }
        } catch (Exception error) {
            state("Не удалось подключить кнопки Bluetooth");
        }
    }

    boolean isConnected() { return connected && !closed; }

    /** Returns true only after the key-down report was accepted by Bluetooth. */
    boolean press(String code) {
        if (code == null || !isConnected() || hid == null || heldReport != 0) return false;
        long now = SystemClock.elapsedRealtime();
        if (now - lastPress < MIN_PRESS_INTERVAL_MS) return false;
        int key = keyboardCode(code);
        int usage = consumerCode(code);
        if (key == 0 && usage == 0) return false;
        int report = key != 0 ? KEYBOARD_REPORT : CONSUMER_REPORT;
        byte[] value = key != 0 ? new byte[8] : new byte[2];
        if (key != 0) value[2] = (byte) key;
        else { value[0] = (byte) usage; value[1] = (byte) (usage >>> 8); }
        try {
            if (!hid.sendReport(target, report, value)) return false;
            if (report == KEYBOARD_REPORT) keyboard = value;
            else consumer = value;
            heldReport = report;
            lastPress = now;
            final int session = generation;
            main.postDelayed(() -> {
                if (!closed && session == generation) releaseAll();
            }, KEY_HOLD_MS);
            return true;
        } catch (Exception error) {
            state("Не удалось отправить кнопку Bluetooth");
            return false;
        }
    }

    private void releaseAll() {
        keyboard = new byte[8];
        consumer = new byte[2];
        heldReport = 0;
        if (!connected || hid == null || target == null) return;
        try {
            hid.sendReport(target, KEYBOARD_REPORT, keyboard);
            hid.sendReport(target, CONSUMER_REPORT, consumer);
        } catch (Exception ignored) { }
    }

    /** Call onPause: Android HID registration belongs to the visible app. */
    void close() {
        releaseAll();
        closed = true;
        generation++;
        main.removeCallbacksAndMessages(null);
        connected = registered = requested = false;
        if (hid != null) {
            try { if (target != null) hid.disconnect(target); } catch (Exception ignored) { }
            try { hid.unregisterApp(); } catch (Exception ignored) { }
            try { if (adapter != null) adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid); }
            catch (Exception ignored) { }
        }
        hid = null;
        target = null;
    }

    private void state(String message) { listener.onHidState(message, isConnected()); }

    private static int keyboardCode(String code) {
        switch (code) {
            case "up": return 0x52;
            case "down": return 0x51;
            case "left": return 0x50;
            case "right": return 0x4f;
            case "ok": return 0x28;
            default: return 0;
        }
    }

    private static int consumerCode(String code) {
        switch (code) {
            case "back": return 0x224;
            case "home": return 0x223;
            case "menu": return 0x40;
            case "play_pause": return 0xcd;
            case "next": return 0xb5;
            case "previous": return 0xb6;
            case "stop": return 0xb7;
            case "volume_up": return 0xe9;
            case "volume_down": return 0xea;
            case "mute": return 0xe2;
            default: return 0;
        }
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) out[i] = (byte) values[i];
        return out;
    }
}
