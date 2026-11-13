package local.station.remote;

import android.bluetooth.BluetoothProfile;

/** Named bridge avoids anonymous-class metadata unsupported by the bundled D8. */
final class HidProfileListener implements BluetoothProfile.ServiceListener {
    private final HidRemoteClient client;
    private final int session;

    HidProfileListener(HidRemoteClient client, int session) {
        this.client = client;
        this.session = session;
    }

    @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
        client.profileConnected(session, profile, proxy);
    }

    @Override public void onServiceDisconnected(int profile) {
        client.profileDisconnected(session);
    }
}
