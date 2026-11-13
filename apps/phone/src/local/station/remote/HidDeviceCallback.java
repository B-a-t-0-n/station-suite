package local.station.remote;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;

/** HID profile callbacks are delivered on the client's main-thread executor. */
final class HidDeviceCallback extends BluetoothHidDevice.Callback {
    private final HidRemoteClient client;
    private final int session;

    HidDeviceCallback(HidRemoteClient client, int session) {
        this.client = client;
        this.session = session;
    }

    @Override public void onAppStatusChanged(BluetoothDevice plugged, boolean ready) {
        client.appStatusChanged(session, ready);
    }

    @Override public void onConnectionStateChanged(BluetoothDevice device, int state) {
        client.connectionChanged(session, device, state);
    }

    @Override public void onGetReport(BluetoothDevice device, byte type, byte id, int size) {
        client.getReport(session, device, type, id);
    }

    @Override public void onVirtualCableUnplug(BluetoothDevice device) {
        client.virtualCableUnplug(session);
    }
}
