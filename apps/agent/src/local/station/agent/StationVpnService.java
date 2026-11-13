package local.station.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;

/**
 * Yandex-only VPN guard. It routes only public Yandex prefixes into a TUN
 * interface and drops them. PC lab/ADB/Agent traffic stays outside the VPN, so
 * control remains available. Auto-stops after 60 seconds.
 */
public final class StationVpnService extends VpnService {
    private static final String TAG = "StationVpnTest";
    private static final String CHANNEL_ID = "station_vpn_test";
    private static final int NOTIFICATION_ID = 2;
    private static final long MAX_RUNTIME_MS = 60000;
    private static volatile boolean active;
    private static volatile long startedAt;
    private static volatile long packetsDropped;
    // Public IPv4 prefixes announced by Yandex AS13238 and AS20055.
    private static final String[] YANDEX_ROUTES = {
        "5.45.192.0/18", "5.255.192.0/18", "9.155.192.0/18", "9.249.160.0/21",
        "15.211.0.0/16", "15.218.0.0/16", "23.131.120.0/24", "37.9.64.0/18",
        "37.140.128.0/18", "50.34.0.0/15", "50.37.64.0/18", "50.37.128.0/17",
        "50.38.0.0/17", "50.39.64.0/18", "50.39.128.0/17", "50.43.0.0/17",
        "50.45.128.0/17", "50.46.0.0/15", "50.52.0.0/17", "50.53.0.0/16",
        "50.54.128.0/17", "50.106.0.0/18", "50.107.24.0/21", "50.109.224.0/19",
        "50.120.64.0/19", "50.123.64.0/18", "50.125.0.0/16", "50.126.64.0/18",
        "64.52.96.0/22", "66.35.0.0/20", "69.55.208.0/20", "72.21.10.0/23",
        "74.52.128.0/17", "77.88.0.0/18", "84.252.160.0/19", "87.250.224.0/19",
        "92.255.112.0/20", "93.158.128.0/18", "95.108.128.0/17",
        "107.191.236.0/22", "137.83.56.0/24", "137.83.80.0/23",
        "141.8.128.0/18", "149.18.2.0/24", "162.33.232.0/21", "162.43.200.0/21",
        "162.248.8.0/22", "162.248.140.0/22", "172.86.128.0/20",
        "178.154.128.0/18", "185.32.187.0/24", "185.190.224.0/22",
        "192.75.144.0/24", "192.112.37.0/24", "192.182.128.0/19",
        "192.183.160.0/19", "192.183.192.0/19", "198.179.52.0/22",
        "198.237.200.0/22", "203.81.192.0/20", "204.11.64.0/22",
        "204.155.26.0/23", "204.194.220.0/23", "205.174.152.0/22",
        "205.196.7.0/24", "205.196.14.0/24", "206.166.252.0/23",
        "207.32.192.0/20", "208.52.160.0/24", "208.52.175.0/24",
        "208.90.160.0/21", "208.123.176.0/21", "209.188.99.0/24",
        "209.237.136.0/22", "213.180.192.0/19"
    };

    private ParcelFileDescriptor tunnel;
    private Thread reader;
    private volatile boolean running;

    static boolean isActive() { return active; }
    static long startedAt() { return startedAt; }
    static long packetsDropped() { return packetsDropped; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Station VPN test",
                NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        startForeground(NOTIFICATION_ID, notification("VPN test starting"));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (tunnel == null) startTunnel();
        return START_NOT_STICKY;
    }

    private void startTunnel() {
        try {
            Builder builder = new Builder();
            builder.setSession("Station Lab Yandex-only guard");
            builder.setMtu(1500);
            builder.setBlocking(true);
            builder.addAddress("10.111.0.2", 32);
            for (String prefix : YANDEX_ROUTES) {
                int slash = prefix.indexOf('/');
                builder.addRoute(prefix.substring(0, slash), Integer.parseInt(prefix.substring(slash + 1)));
            }
            try {
                builder.addDisallowedApplication(getPackageName());
            } catch (Exception error) {
                Log.w(TAG, "could not exclude Agent", error);
            }
            tunnel = builder.establish();
            if (tunnel == null) {
                update("VPN was not authorized");
                stopSelf();
                return;
            }
            running = true;
            active = true;
            startedAt = System.currentTimeMillis();
            packetsDropped = 0;
            update("Yandex routes blocked (60 s max)");
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(this::stopSelf, MAX_RUNTIME_MS);
            reader = new Thread(this::dropTraffic, "StationVpnDrop");
            reader.setDaemon(true);
            reader.start();
            Log.i(TAG, "VPN established: " + YANDEX_ROUTES.length + " Yandex routes dropped");
        } catch (Exception error) {
            Log.e(TAG, "VPN establish failed", error);
            update("VPN error: " + error.getClass().getSimpleName());
            stopSelf();
        }
    }

    private void dropTraffic() {
        byte[] buffer = new byte[32768];
        ParcelFileDescriptor current = tunnel;
        if (current == null) return;
        try (FileInputStream input = new FileInputStream(current.getFileDescriptor())) {
            while (running && input.read(buffer) >= 0) {
                packetsDropped++;
            }
        } catch (Exception error) {
            if (running) Log.w(TAG, "VPN read stopped", error);
        }
    }

    @Override public void onRevoke() {
        Log.w(TAG, "VPN revoked by system");
        stopSelf();
    }

    @Override public void onDestroy() {
        running = false;
        active = false;
        startedAt = 0;
        ParcelFileDescriptor current = tunnel;
        tunnel = null;
        if (current != null) {
            try { current.close(); } catch (Exception ignored) { }
        }
        stopForeground(true);
        super.onDestroy();
    }

    private void update(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, VpnTestActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Station VPN test")
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }
}
