package local.station.voicebridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import org.json.JSONObject;
import java.util.concurrent.atomic.AtomicLong;

/** Persistent local voice listener. Native Quasar owns the microphones and wake word. */
public final class VoiceRuntimeService extends Service {
    private static final Object OWNERSHIP = new Object();
    private static RuntimeVoiceHandler processOwner;
    private static LocalAsrRuntime processAsrOwner;
    private static final AtomicLong HEARTBEAT_SEQUENCE = new AtomicLong();
    private final Handler main = new Handler(Looper.getMainLooper());
    private RuntimeVoiceHandler handler;
    private LocalAsrRuntime localAsr;
    private LoopbackVoiceServer listener;
    private boolean destroyed, stopping;
    private boolean localAsrRequested, localAsrConfigFailed;
    private long nextListenAttempt, lastConfigRefresh;
    private int listenFailures;
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            tick();
            if (!destroyed) main.postDelayed(this, 2000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel("voice_runtime", "Голос Станции",
                NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        startForeground(2, new Notification.Builder(this, "voice_runtime")
                .setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("Голос Станции")
                .setContentText("Голосовой режим включён").setOnlyAlertOnce(true).build());
        main.post(heartbeat);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!RuntimeSettings.enabled(this)) {
            stopping = true;
            closeAdmission();
            tick();
            return START_NOT_STICKY;
        }
        tick();
        return START_STICKY;
    }

    private void tick() {
        if (destroyed) return;
        if (stopping && RuntimeSettings.enabled(this) && canRelease()) {
            releaseOwner();
            handler = null; localAsr = null; listener = null; stopping = false;
            localAsrRequested = localAsrConfigFailed = false;
        }
        boolean enabled = RuntimeSettings.enabled(this) && !stopping;
        if (!enabled) {
            stopping = true;
            closeAdmission();
            writeStatus(RuntimeSettings.enabled(this));
            if (!RuntimeSettings.enabled(this) && canRelease()) {
                releaseOwner();
                stopSelf();
            }
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (handler == null && !ProbeService.isBusy() && !VoiceCloudGate.isBusy()) {
            synchronized (OWNERSHIP) {
                if (processOwner != null && processOwner.canRelease()
                        && (processAsrOwner == null || processAsrOwner.canRelease())) {
                    processOwner = null; processAsrOwner = null;
                }
                if (processOwner == null) {
                    handler = new RuntimeVoiceHandler(this, () -> PrivateConfig.read(this, "config.json"),
                            () -> main.post(() -> { if (!destroyed) writeStatus(RuntimeSettings.enabled(this) && !stopping); }),
                            new TvVoiceControl(this));
                    processOwner = handler;
                    try {
                        JSONObject config = PrivateConfig.read(this, "config.json");
                        if (Boolean.TRUE.equals(config.opt("voice_local_asr"))) {
                            localAsrRequested = true;
                            localAsr = new LocalAsrRuntime(this, handler,
                                    () -> main.post(() -> { if (!destroyed) writeStatus(RuntimeSettings.enabled(this) && !stopping); }),
                                    config.optString("voice_local_asr_engine", "sherpa"), config.optInt("voice_local_asr_threads", 2),
                                    config.optBoolean("voice_local_endpoint_observation", false),
                                    config.optBoolean("voice_local_server_endpoint", false));
                            processAsrOwner = localAsr;
                        }
                    } catch (Exception ignored) { localAsrConfigFailed = localAsrRequested; }
                }
            }
        }
        if (handler != null && now - lastConfigRefresh >= 10000) {
            handler.refreshConfiguration();
            lastConfigRefresh = now;
        }
        if (handler != null && !localAsrConfigFailed
                && (listener == null || !listener.status().optBoolean("active")) && now >= nextListenAttempt) {
            if (listener != null) listener.stop();
            listener = localAsr == null ? LoopbackVoiceServer.runtime(handler) : LoopbackVoiceServer.runtime(localAsr, localAsr);
            listener.start();
            boolean ready = listener.status().optBoolean("active");
            listenFailures = ready ? 0 : Math.min(5, listenFailures + 1);
            nextListenAttempt = now + (ready ? 2000 : Math.min(60000, 2000L << listenFailures));
        }
        writeStatus(true);
    }

    private void closeAdmission() {
        if (handler != null) handler.close();
        if (localAsr != null) localAsr.close();
        if (listener != null) listener.stop();
    }
    private void releaseOwner() {
        synchronized (OWNERSHIP) {
            if (processOwner == handler && canRelease()) { processOwner = null; processAsrOwner = null; }
        }
    }
    private boolean canRelease() {
        return (handler == null || handler.canRelease()) && (localAsr == null || localAsr.canRelease());
    }
    private void writeStatus(boolean enabled) {
        try {
            JSONObject state = handler == null ? new JSONObject() : handler.snapshot();
            JSONObject transport = listener == null ? new JSONObject() : listener.status();
            boolean ready = enabled && transport.optBoolean("active");
            state.put("enabled", enabled);
            state.put("listenerReady", ready);
            state.put("credentialsConfigured", state.optBoolean("credentialsReady"));
            state.put("healthy", ready && state.optBoolean("healthy"));
            if (localAsr != null) {
                state.put("localAsr", localAsr.snapshot());
                state.put("healthy", state.optBoolean("healthy") && localAsr.isReady());
            }
            state.put("failedCode", state.optString("lastCode", handler == null ? "STARTING" : ""));
            if (localAsrConfigFailed) state.put("failedCode", "LOCAL_ASR_SETUP");
            state.put("updatedAtMs", System.currentTimeMillis());
            state.put("heartbeatSeq", HEARTBEAT_SEQUENCE.incrementAndGet());
            state.put("servicePid", android.os.Process.myPid());
            state.put("transport", transport);
            PrivateConfig.write(this, "runtime-status.json", state);
        } catch (Exception ignored) { }
    }
    @Override public void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        closeAdmission();
        writeStatus(RuntimeSettings.enabled(this));
        releaseOwner();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
