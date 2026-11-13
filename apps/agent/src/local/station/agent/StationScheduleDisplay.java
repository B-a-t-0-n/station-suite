package local.station.agent;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Reconciles Agent timers with real native timers, which own both front display and sound. */
final class StationScheduleDisplay implements Runnable {
    private final StationScheduleRuntime schedules;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(new DisplayThread());
    private final Map<String, Long> known = new LinkedHashMap<>();
    private final Set<String> ringing = new HashSet<>();
    private volatile boolean closed;
    private boolean initialized, pendingStop;
    private long lastSync, lastOffset;
    private String deliveredStopToken = "";
    private volatile String code = "initializing";
    private volatile int sent, nativeCount;

    StationScheduleDisplay(StationScheduleRuntime schedules) {
        this(schedules, true);
    }
    StationScheduleDisplay(StationScheduleRuntime schedules, boolean startWorker) {
        this.schedules = schedules;
        if (startWorker) worker.scheduleWithFixedDelay(this, 1, 1, TimeUnit.SECONDS);
    }
    @Override public void run() {
        if (closed) return;
        try {
            JSONObject screen = schedules.screenState();
            if (!screen.optBoolean("ready")) { code = "schedule_unavailable"; return; }
            if (!initialized) {
                Map<String, Long> saved = NativeTimerDisplay.ownedTimers();
                if (saved == null) { code = "native_state_unavailable"; return; }
                known.putAll(saved);
                if (!NativeTimerDisplay.clear(NativeTimerDisplay.LEGACY_ID)) { code = "transport_unavailable"; return; }
                known.remove(NativeTimerDisplay.LEGACY_ID); sent++; initialized = true;
            }
            JSONArray timers = screen.optJSONArray("timers");
            if (timers == null) { code = "schedule_unavailable"; return; }
            Map<String, JSONObject> desired = new LinkedHashMap<>(); Set<String> nextRinging = new HashSet<>();
            for (int i = 0; i < timers.length(); i++) {
                JSONObject timer = timers.getJSONObject(i); String id = NativeTimerDisplay.ownedId(timer.getString("id"));
                desired.put(id, timer); if (timer.optBoolean("ringing")) nextRinging.add(id);
            }
            for (String id : ringing) if (!nextRinging.contains(id)) pendingStop = true;
            String stopToken = screen.optString("nativeStopToken", "");
            if (!stopToken.isEmpty()) {
                if (!stopToken.equals(deliveredStopToken)) {
                    if (!NativeTimerDisplay.stopSignals()) { code = "transport_unavailable"; return; }
                    sent++; deliveredStopToken = stopToken;
                }
                if (!schedules.acknowledgeNativeStop(stopToken)) { code = "stop_ack_unavailable"; return; }
                pendingStop = false; ringing.clear(); ringing.addAll(nextRinging);
            } else if (pendingStop) {
                if (!NativeTimerDisplay.stopSignals()) { code = "transport_unavailable"; return; }
                sent++; pendingStop = false;
                // Do not repeat the global native STOP if a later addressed removal needs a retry.
                ringing.clear(); ringing.addAll(nextRinging);
            }
            for (String id : new HashSet<String>(known.keySet())) if (!desired.containsKey(id)) {
                if (!NativeTimerDisplay.clear(id)) { code = "transport_unavailable"; return; }
                known.remove(id); sent++;
            }
            long elapsed = android.os.SystemClock.elapsedRealtime(), wall = System.currentTimeMillis();
            long offset = wall - elapsed;
            boolean renew = elapsed - lastSync >= 60000 || Math.abs(offset - lastOffset) > 1500;
            for (Map.Entry<String, JSONObject> entry : desired.entrySet()) {
                JSONObject timer = entry.getValue(); long left = timer.optLong("remainingMs");
                if (timer.optBoolean("enabled") && left > 0) {
                    long due = wall + left; Long prior = known.get(entry.getKey());
                    if (prior == null || Math.abs(prior.longValue() - due) > 1500 || renew) {
                        if (!NativeTimerDisplay.show(timer.getString("id"), wall, left)) { code = "transport_unavailable"; return; }
                        known.put(entry.getKey(), due); sent++;
                    }
                } else if (timer.optBoolean("ringing") && !known.containsKey(entry.getKey())) {
                    // Never restart a due timer after service recreation: it may already be ringing natively.
                    known.put(entry.getKey(), wall);
                }
            }
            ringing.clear(); ringing.addAll(nextRinging); nativeCount = known.size();
            if (renew) lastSync = elapsed;
            lastOffset = offset; code = desired.isEmpty() ? "idle" : "native_timers_sent";
        } catch (Exception unavailable) { code = "display_unavailable"; }
    }
    void served() { }
    byte[] timerGif() { return new byte[0]; }
    JSONObject status() {
        JSONObject result = new JSONObject();
        try { result.put("mode", "native_timers").put("code", code).put("sent", sent).put("ownedTimers", nativeCount); }
        catch (Exception ignored) { }
        return result;
    }
    // Native timers are durable; process/service teardown must not cancel their future deadlines.
    void destroy() { closed = true; worker.shutdownNow(); }
    private static final class DisplayThread implements ThreadFactory {
        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "station-native-timers"); thread.setDaemon(true); return thread;
        }
    }
}
