package local.station.vpnprobe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

final class ProbeNotification {
    private static final String CHANNEL = "station-vpn-probe";
    private ProbeNotification() { }

    static Notification create(Context context, String text) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                "VPN coverage experiment", NotificationManager.IMPORTANCE_LOW));
        return new Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Station VPN coverage probe")
                .setContentText(text).setOngoing(true).build();
    }
}
