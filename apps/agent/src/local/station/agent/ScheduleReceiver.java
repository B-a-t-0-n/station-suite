package local.station.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.SystemClock;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit alarm/dismiss intents and protected clock broadcasts only. */
public final class ScheduleReceiver extends BroadcastReceiver {
    public static final String ACTION_FIRE = "local.station.agent.SCHEDULE_FIRE";
    public static final String ACTION_DISMISS = "local.station.agent.SCHEDULE_DISMISS";
    public static final String EXTRA_EVENT = "scheduleEvent";
    public static final String EXTRA_ID = "scheduleId";
    static final String EXTRA_WAKE_LEASE = "scheduleWakeLease";
    private static final long HANDOFF_MS = 15000;
    private static final int MAX_HANDOFFS = 16;
    private static final LinkedHashMap<Long, Lease> leases = new LinkedHashMap<>();
    private static long nextLease;

    @Override public void onReceive(Context context, Intent received) {
        if (received == null || !accepted(received.getAction())) return;
        long lease = acquire(context);
        Intent service = new Intent(context, AgentService.class)
                .setAction(StationScheduleRuntime.ACTION_EVENT)
                .putExtra(EXTRA_EVENT, received.getAction()).putExtra(EXTRA_WAKE_LEASE, lease);
        // Dismiss notifications always dismiss the current signal, without arbitrary IDs.
        try { context.startForegroundService(service); }
        catch (RuntimeException unavailable) { release(lease); }
    }

    static boolean accepted(String action) {
        return ACTION_FIRE.equals(action) || ACTION_DISMISS.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
    }

    /** Independent, bounded leases prevent an older event from releasing a newer event's CPU hold. */
    private static synchronized long acquire(Context context) {
        Iterator<Map.Entry<Long, Lease>> iterator = leases.entrySet().iterator();
        long now = SystemClock.elapsedRealtime();
        while (iterator.hasNext()) {
            Lease lease = iterator.next().getValue();
            if (now >= lease.expires) { lease.close(); iterator.remove(); }
        }
        if (leases.size() >= MAX_HANDOFFS) {
            Long oldest = leases.keySet().iterator().next(); release(oldest);
        }
        try {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "StationAgent:ScheduleHandoff");
            lock.setReferenceCounted(false); lock.acquire(HANDOFF_MS);
            long id = ++nextLease;
            leases.put(id, new Lease(lock, now + HANDOFF_MS)); return id;
        } catch (RuntimeException unavailable) { return 0; }
    }

    static synchronized void release(long id) {
        Lease lease = leases.remove(id); if (lease != null) lease.close();
    }

    private static final class Lease {
        private final PowerManager.WakeLock lock; private final long expires;
        Lease(PowerManager.WakeLock lock, long expires) { this.lock = lock; this.expires = expires; }
        void close() { try { if (lock.isHeld()) lock.release(); } catch (RuntimeException ignored) { } }
    }
}
