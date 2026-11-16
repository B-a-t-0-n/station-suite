package local.station.agent;

import android.bluetooth.BluetoothAdapter;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;

/** One bounded Bluetooth pairing window, explicitly invoked by an authorized ADB shell. */
public final class StationBluetoothPairing {
    private StationBluetoothPairing() { }

    public static void main(String[] arguments) throws Exception {
        System.exit(run(arguments));
    }

    private static int run(String[] arguments) throws Exception {
        JSONObject result = new JSONObject().put("scope", "station_bluetooth_pairing").put("protocol", 1);
        if (android.os.Process.myUid() != 2000 || arguments.length != 1
                || !("--status".equals(arguments[0]) || "--open120".equals(arguments[0]))) {
            System.out.println(result.put("ok", false).put("error", "shell_and_fixed_action_required"));
            return 2;
        }
        try {
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                System.out.println(result.put("ok", false).put("error", "bluetooth_off_or_absent"));
                return 2;
            }
            if ("--open120".equals(arguments[0])) {
                Object accepted = BluetoothAdapter.class.getMethod("setScanMode", int.class, int.class)
                        .invoke(adapter, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE, 120);
                if (!Boolean.TRUE.equals(accepted)) {
                    System.out.println(result.put("ok", false).put("error", "scan_mode_rejected"));
                    return 2;
                }
                result.put("windowSeconds", 120);
                Runtime.getRuntime().addShutdownHook(new Thread(() -> restoreConnectable(adapter),
                        "StationBluetoothPairingClose"));
            }
            int mode = adapter.getScanMode();
            result.put("mode", mode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE ? "discoverable"
                    : mode == BluetoothAdapter.SCAN_MODE_CONNECTABLE ? "connectable" : "none");
            boolean ok = "--status".equals(arguments[0])
                    || mode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE;
            System.out.println(result.put("ok", ok));
            System.out.flush();
            if ("--open120".equals(arguments[0])) {
                try {
                    // On the tested firmware setScanMode's duration does not expire by itself.
                    if (ok) {
                        long remaining = 120000L;
                        long end = SystemClock.elapsedRealtime() + remaining;
                        while (remaining > 0) {
                            Thread.sleep(remaining);
                            remaining = end - SystemClock.elapsedRealtime();
                        }
                    }
                } finally {
                    restoreConnectable(adapter);
                }
            }
            return ok ? 0 : 2;
        } catch (Exception unavailable) {
            System.out.println(result.put("ok", false).put("error", "scan_mode_unavailable"));
            return 2;
        }
    }

    private static void restoreConnectable(BluetoothAdapter adapter) {
        try {
            if (adapter.isEnabled() && adapter.getScanMode()
                    == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                BluetoothAdapter.class.getMethod("setScanMode", int.class, int.class)
                        .invoke(adapter, BluetoothAdapter.SCAN_MODE_CONNECTABLE, 0);
            }
        } catch (Exception ignored) { }
    }
}
