package local.station.vpnprobe;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/** Benign watchdog kill preflight. No sockets, VPN, background work or restart. */
public final class MarkerService extends Service {
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(102, ProbeNotification.create(this, "Watchdog kill preflight only"));
        Log.i("StationVpnProbe", "MARKER_READY pid=" + android.os.Process.myPid());
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
