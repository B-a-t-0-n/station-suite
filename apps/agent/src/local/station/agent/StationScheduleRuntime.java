package local.station.agent;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.TimeZone;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Sole model owner. Persisted state always precedes alarms and sound; no network dependency. */
public final class StationScheduleRuntime {
    public interface Host {
        void pauseMusic();
        void showScheduleSignal();
        default boolean useNativeTimers() { return false; }
    }
    public static final String ACTION_EVENT = "local.station.agent.SCHEDULE_EVENT";
    private static final String CHANNEL = "station_schedule_signal";
    private static final int SIGNAL_NOTIFICATION = 70, MAX_STATE_BYTES = 262144;
    private final Context context;
    private final Host host;
    private final boolean nativeTimers;
    private final AlarmManager alarms;
    private final NotificationManager notifications;
    private final AtomicFile file;
    private final String bootId;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor events = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(4));
    private final AtomicBoolean queued = new AtomicBoolean(), dismissRequested = new AtomicBoolean();
    private final AtomicLong stoppedGeneration = new AtomicLong(-1);
    private final ScheduleSignal signal;
    // Main-thread audio session membership, distinct from the worker's desired state.
    private final HashSet<String> activeSignalOccurrences = new HashSet<>();
    private final LinkedHashSet<Long> pendingWakeLeases = new LinkedHashSet<>();
    private StationSchedules model;
    private volatile boolean destroyed, ready;
    private volatile long soundGeneration;
    private volatile long activeSignalGeneration;
    private boolean wantedSignal;
    private String ringingIdentity = "";
    private long armedAtMs;
    private String lastCode = "initializing";

    public StationScheduleRuntime(Context context, Host host) {
        this.context = context.getApplicationContext(); this.host = host;
        nativeTimers = host.useNativeTimers();
        alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        notifications = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        file = new AtomicFile(new File(context.getFilesDir(), "station-schedules.json"));
        bootId = readBootId(context);
        signal = new ScheduleSignal(context, new SignalListener(this));
        try {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Таймеры и будильники",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null); channel.enableVibration(false);
            notifications.createNotificationChannel(channel);
            if (bootId.isEmpty()) throw new IllegalStateException("clock_unavailable");
            model = new StationSchedules(readState()); ready = true; lastCode = "ok";
            requestEvent();
        } catch (Exception invalid) {
            ready = false; lastCode = bootId.isEmpty() ? "clock_unavailable" : "state_unavailable";
            cancelAlarm();
        }
    }

    /** Called by RFCOMM worker. No caller-visible exception or unbounded queue. */
    public synchronized JSONObject execute(JSONObject request) {
        if (!ready || destroyed) return failure("schedule_unavailable");
        JSONObject before = model.save();
        try {
            StationSchedules.Now now = now();
            model.advance(now);
            JSONArray nativeRingingBefore = new JSONArray();
            if (nativeTimers && request != null && ("dismiss".equals(request.optString("op")) || "delete".equals(request.optString("op"))
                    || "stop_timer".equals(request.optString("op")))) {
                JSONArray ringing = model.ringing();
                for (int i = 0; i < ringing.length(); i++) {
                    JSONObject entry = ringing.getJSONObject(i);
                    if ("timer".equals(entry.optString("type"))) nativeRingingBefore.put(entry.getString("id"));
                }
            }
            JSONObject result = model.execute(request, now);
            if (result.optBoolean("ok") && nativeRingingBefore.length() > 0) {
                JSONArray after = model.ringing(); boolean nativeStop = false;
                for (int i = 0; i < nativeRingingBefore.length(); i++) {
                    String id = nativeRingingBefore.getString(i); boolean remains = false;
                    for (int j = 0; j < after.length(); j++)
                        if (id.equals(after.getJSONObject(j).getString("id"))) remains = true;
                    if (!remains) nativeStop = true;
                }
                // A replayed receipt must never stop a newly ringing occurrence.
                if (nativeStop) { dismissNativeRinging(now); model.requestNativeTimerStop(); }
            }
            model.advance(now);
            persistIfChanged(before);
            reconcile(now);
            JSONObject visible = model.snapshot(now);
            for (String key : new String[]{"revision", "nowMs", "timezone", "entries"})
                result.put(key, visible.get(key));
            return result;
        } catch (Exception unavailable) {
            failClosed(before); return failure("storage_unavailable");
        }
    }

    /** Numeric/enum diagnostics only; labels and private request history remain private. */
    public synchronized JSONObject status() {
        JSONObject result = new JSONObject();
        try {
            result.put("ready", ready && !destroyed).put("armed", armedAtMs > 0)
                    .put("nextWakeAtMs", armedAtMs).put("lastCode", lastCode)
                    .put("signalRunning", signal.isRunning()).put("signalCode", signal.lastCode())
                    .put("signalError", signal.lastError()).put("timerEngine", nativeTimers ? "native" : "agent");
            if (model == null) return result.put("revision", "").put("ringing", 0).put("count", 0).put("nativeTimerRinging", 0);
            JSONObject visible = model.snapshot(now());
            int nativeRinging = 0;
            JSONArray allRinging = model.ringing();
            if (nativeTimers) for (int i = 0; i < allRinging.length(); i++)
                if ("timer".equals(allRinging.getJSONObject(i).optString("type"))) nativeRinging++;
            return result.put("revision", visible.optString("revision"))
                    .put("ringing", allRinging.length()).put("nativeTimerRinging", nativeRinging)
                    .put("count", visible.getJSONArray("entries").length())
                    .put("timezone", visible.optString("timezone"));
        } catch (Exception unavailable) { return result; }
    }

    /** Read-only on-device display data. This never advances the model or triggers sound. */
    public synchronized JSONObject screenState() {
        JSONObject result = new JSONObject();
        try {
            boolean available = ready && !destroyed && model != null;
            JSONArray ringing = available ? model.ringing() : new JSONArray();
            JSONArray timers = new JSONArray();
            result.put("ready", available).put("ringing", ringing.length()).put("timers", timers)
                    .put("nativeStopToken", available && nativeTimers ? model.nativeTimerStopToken() : "");
            if (ringing.length() > 0) {
                JSONObject first = ringing.getJSONObject(0);
                String type = first.optString("type"), label = first.optString("label", "").trim();
                result.put("type", type).put("label", label.isEmpty()
                        ? ("timer".equals(type) ? "Таймер" : "Будильник") : label);
            }
            if (available) {
                JSONArray entries = model.snapshot(now()).getJSONArray("entries");
                long nearest = Long.MAX_VALUE;
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.getJSONObject(i);
                    if ("timer".equals(entry.optString("type"))) {
                        long left = entry.optLong("remainingMs", 0);
                        boolean enabled = entry.optBoolean("enabled"), isRinging = entry.optBoolean("ringing");
                        if (enabled || isRinging) timers.put(new JSONObject().put("id", entry.getString("id"))
                                .put("remainingMs", left).put("ringing", isRinging).put("enabled", enabled));
                        if (enabled && left > 0 && left < nearest) {
                            nearest = left;
                            result.put("timerId", entry.getString("id")).put("remainingMs", left);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            try { result.put("ready", false).put("timers", new JSONArray()); } catch (Exception unavailable) { }
        }
        return result;
    }

    /** A successful native send is acknowledged durably before the reconciler forgets it. */
    synchronized boolean acknowledgeNativeStop(String token) {
        if (!ready || destroyed || model == null) return false;
        JSONObject before = model.save();
        try {
            if (!model.acknowledgeNativeTimerStop(token)) return true;
            persistIfChanged(before); return true;
        } catch (Exception unavailable) {
            // Keep the delivery intent pending. A subsequent display tick may retry
            // the disk acknowledgement without sending global STOP a second time.
            try { model = new StationSchedules(before); } catch (RuntimeException ignored) { }
            return false;
        }
    }

    /** Receiver only forwards a fixed event name. Notification dismissal is explicitly dismiss-all. */
    public synchronized void onEvent(Intent intent) {
        if (intent == null || !ACTION_EVENT.equals(intent.getAction())) return;
        long lease = intent.getLongExtra(ScheduleReceiver.EXTRA_WAKE_LEASE, 0);
        String event = intent.getStringExtra(ScheduleReceiver.EXTRA_EVENT);
        if (!ScheduleReceiver.accepted(event) || destroyed || !ready) { ScheduleReceiver.release(lease); return; }
        if (lease > 0) {
            if (pendingWakeLeases.size() >= 16) {
                Long oldest = pendingWakeLeases.iterator().next(); pendingWakeLeases.remove(oldest);
                ScheduleReceiver.release(oldest);
            }
            pendingWakeLeases.add(lease);
        }
        if (ScheduleReceiver.ACTION_DISMISS.equals(event)) dismissRequested.set(true);
        requestEvent();
    }

    private void requestEvent() {
        if (destroyed || !ready || !queued.compareAndSet(false, true)) return;
        try { events.execute(new EventTask(this)); }
        catch (RuntimeException rejected) { queued.set(false); releasePendingLeases(); }
    }

    private void event() {
        queued.set(false);
        synchronized (this) {
            List<Long> leases = new ArrayList<>(pendingWakeLeases); pendingWakeLeases.clear();
            if (!ready || destroyed) { releaseLeases(leases); return; }
            JSONObject before = model.save();
            try {
                StationSchedules.Now now = now();
                long stopped = stoppedGeneration.getAndSet(-1);
                if (dismissRequested.getAndSet(false)) model.dismissAll(now);
                else if (stopped == soundGeneration) {
                    if (nativeTimers) dismissAgentRinging(now);
                    else model.dismissAll(now);
                }
                // A newly due occurrence must not be dismissed by the old signal's expiry.
                model.advance(now);
                persistIfChanged(before); reconcile(now);
            } catch (Exception unavailable) { failClosed(before); }
            finally {
                // Same main queue, after SignalTask: its playback wake hold is acquired first.
                if (!main.post(new ReleaseLeasesTask(leases))) releaseLeases(leases);
            }
        }
    }

    private StationSchedules.Now now() {
        return new StationSchedules.Now(System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                bootId, TimeZone.getDefault());
    }

    private JSONObject readState() throws Exception {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath() + ".bak").exists()) return null;
        try (FileInputStream input = file.openRead(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] block = new byte[4096]; int count;
            while ((count = input.read(block)) != -1) {
                if (bytes.size() + count > MAX_STATE_BYTES) throw new IllegalStateException("state_too_large");
                bytes.write(block, 0, count);
            }
            return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    private void persistIfChanged(JSONObject before) throws Exception {
        byte[] bytes = model.save().toString().getBytes(StandardCharsets.UTF_8);
        if (new String(bytes, StandardCharsets.UTF_8).equals(before.toString())) return;
        if (bytes.length > MAX_STATE_BYTES) throw new IllegalStateException("state_too_large");
        FileOutputStream output = null;
        try {
            output = file.startWrite(); output.write(bytes); output.flush(); output.getFD().sync();
            file.finishWrite(output); output = null;
        } finally { if (output != null) file.failWrite(output); }
    }

    private void reconcile(StationSchedules.Now now) throws Exception {
        long next = model.nextWakeAtMs(now);
        if (next != armedAtMs) {
            if (next <= 0) cancelAlarm();
            else {
                long trigger = Math.max(System.currentTimeMillis() + 100, next);
                // User-visible clocks bypass the per-app allow-while-idle interval throttle.
                PendingIntent show = PendingIntent.getActivity(context, 70,
                        new Intent(context, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                alarms.setAlarmClock(new AlarmManager.AlarmClockInfo(trigger, show), fireIntent());
                armedAtMs = next;
            }
        }
        JSONArray ringing = model.ringing();
        if (nativeTimers) {
            JSONArray audible = new JSONArray();
            for (int i = 0; i < ringing.length(); i++) {
                JSONObject entry = ringing.getJSONObject(i);
                if (!"timer".equals(entry.optString("type"))) audible.put(entry);
            }
            ringing = audible;
        }
        String[] occurrences = new String[ringing.length()];
        StringBuilder identity = new StringBuilder(); int volume = 5;
        for (int i = 0; i < ringing.length(); i++) {
            JSONObject entry = ringing.getJSONObject(i);
            occurrences[i] = entry.getString("id") + ":" + entry.optLong("ringingAtMs");
            identity.append(occurrences[i]).append(':').append(entry.optInt("volumePercent")).append(';');
            volume = Math.max(volume, entry.optInt("volumePercent", 35));
        }
        String key = identity.toString();
        if (!key.equals(ringingIdentity)) {
            ringingIdentity = key; wantedSignal = ringing.length() > 0;
            long generation = ++soundGeneration;
            main.post(new SignalTask(this, generation, wantedSignal, Math.min(80, volume), occurrences));
        }
        lastCode = "ok";
    }

    /** The native STOP button stops all native ringing; keep our timer rows consistent. */
    private void dismissNativeRinging(StationSchedules.Now now) throws Exception {
        JSONArray ringing = model.ringing();
        for (int i = 0; i < ringing.length(); i++) {
            JSONObject entry = ringing.getJSONObject(i);
            if ("timer".equals(entry.optString("type"))) model.execute(new JSONObject()
                    .put("op", "dismiss").put("requestId", UUID.randomUUID().toString())
                    .put("id", entry.getString("id")), now);
        }
    }

    /** Agent audio expiry/focus loss must not dismiss independently ringing native timers. */
    private void dismissAgentRinging(StationSchedules.Now now) throws Exception {
        JSONArray ringing = model.ringing();
        for (int i = 0; i < ringing.length(); i++) {
            JSONObject entry = ringing.getJSONObject(i);
            if (!"timer".equals(entry.optString("type"))) model.execute(new JSONObject()
                    .put("op", "dismiss").put("requestId", UUID.randomUUID().toString())
                    .put("id", entry.getString("id")), now);
        }
    }

    private PendingIntent fireIntent() {
        return PendingIntent.getBroadcast(context, 70, new Intent(context, ScheduleReceiver.class)
                .setAction(ScheduleReceiver.ACTION_FIRE), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void cancelAlarm() {
        try { alarms.cancel(fireIntent()); } catch (RuntimeException ignored) { }
        armedAtMs = 0;
    }

    private void applySignal(long generation, boolean wanted, int volume, String[] occurrences) {
        if (generation != soundGeneration || destroyed) return;
        if (!wanted) { stopSignal(); return; }
        boolean continuing = false;
        for (String occurrence : occurrences) if (activeSignalOccurrences.contains(occurrence)) {
            continuing = true; break;
        }
        // The model can expire an old occurrence and fire a new one before the old
        // player's delayed expiry runs. That new occurrence needs its own session.
        // Overlapping occurrences still share the original bounded audio lease.
        if (signal.isRunning() && !continuing) signal.stop();
        activeSignalOccurrences.clear();
        for (String occurrence : occurrences) activeSignalOccurrences.add(occurrence);
        activeSignalGeneration = generation;
        try {
            boolean wasRunning = signal.isRunning();
            if (!wasRunning) host.pauseMusic();
            signal.start(volume);
            if (signal.isRunning()) {
                PendingIntent dismiss = PendingIntent.getBroadcast(context, 71,
                        new Intent(context, ScheduleReceiver.class).setAction(ScheduleReceiver.ACTION_DISMISS),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                Notification notification = new Notification.Builder(context, CHANNEL)
                        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("Таймер или будильник")
                        .setContentText("Сигнал на Станции").setCategory(Notification.CATEGORY_ALARM)
                        .setOngoing(true).setOnlyAlertOnce(true)
                        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Остановить", dismiss).build();
                notifications.notify(SIGNAL_NOTIFICATION, notification);
                if (!wasRunning) {
                    try { host.showScheduleSignal(); }
                    catch (RuntimeException unavailable) { /* Display failure must never silence the alarm. */ }
                }
            }
        } catch (RuntimeException unavailable) { stopSignal(); signalStopped(generation); }
    }

    private void signalStopped(long generation) {
        if (destroyed || generation != soundGeneration) return;
        stoppedGeneration.set(generation); requestEvent();
    }

    private void failClosed(JSONObject before) {
        try { model = new StationSchedules(before); } catch (RuntimeException ignored) { }
        ready = false; lastCode = "storage_unavailable"; cancelAlarm();
        wantedSignal = false; ringingIdentity = "";
        main.post(new StopTask(this, ++soundGeneration));
    }

    /** Stops process-owned output; a saved future alarm intentionally survives service recreation. */
    public synchronized void destroy() {
        if (destroyed) return;
        destroyed = true; ++soundGeneration; events.shutdownNow();
        releasePendingLeases();
        if (Looper.myLooper() == Looper.getMainLooper()) stopSignal();
        else main.post(new StopTask(this, soundGeneration));
    }

    private void stopSignal() {
        activeSignalOccurrences.clear(); signal.stop(); notifications.cancel(SIGNAL_NOTIFICATION);
    }

    private synchronized void releasePendingLeases() {
        releaseLeases(new ArrayList<>(pendingWakeLeases)); pendingWakeLeases.clear();
    }

    private static void releaseLeases(List<Long> leases) {
        for (Long lease : leases) ScheduleReceiver.release(lease);
    }

    private JSONObject failure(String code) {
        try {
            JSONObject result = model == null ? new JSONObject() : model.snapshot(now());
            return result.put("ok", false).put("code", code).put("error", "Расписание временно недоступно");
        } catch (Exception ignored) { return new JSONObject(); }
    }

    private static String readBootId(Context context) {
        try {
            int count = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            if (count >= 0) return "android:" + count;
        } catch (RuntimeException ignored) { }
        try (FileInputStream input = new FileInputStream("/proc/sys/kernel/random/boot_id")) {
            byte[] bytes = new byte[64]; int count = input.read(bytes);
            String value = new String(bytes, 0, Math.max(0, count), StandardCharsets.US_ASCII).trim();
            if (value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) return value;
        } catch (Exception ignored) { }
        return "";
    }

    private static final class EventTask implements Runnable {
        private final StationScheduleRuntime owner;
        EventTask(StationScheduleRuntime owner) { this.owner = owner; }
        @Override public void run() { owner.event(); }
    }
    private static final class SignalTask implements Runnable {
        private final StationScheduleRuntime owner; private final long generation;
        private final boolean wanted; private final int volume;
        private final String[] occurrences;
        SignalTask(StationScheduleRuntime owner, long generation, boolean wanted, int volume, String[] occurrences) {
            this.owner = owner; this.generation = generation; this.wanted = wanted; this.volume = volume;
            this.occurrences = occurrences;
        }
        @Override public void run() { owner.applySignal(generation, wanted, volume, occurrences); }
    }
    private static final class StopTask implements Runnable {
        private final StationScheduleRuntime owner; private final long generation;
        StopTask(StationScheduleRuntime owner, long generation) { this.owner = owner; this.generation = generation; }
        @Override public void run() { if (generation == owner.soundGeneration) owner.stopSignal(); }
    }
    private static final class SignalListener implements ScheduleSignal.Listener {
        private final StationScheduleRuntime owner;
        SignalListener(StationScheduleRuntime owner) { this.owner = owner; }
        @Override public void stopped(String reason) { owner.signalStopped(owner.activeSignalGeneration); }
    }
    private static final class ReleaseLeasesTask implements Runnable {
        private final List<Long> leases;
        ReleaseLeasesTask(List<Long> leases) { this.leases = leases; }
        @Override public void run() { releaseLeases(leases); }
    }
}
