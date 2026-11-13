package local.station.voicebridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;

/** Explicit bounded tests; share cloud ownership with the permanent voice service. */
public final class ProbeService extends Service {
    private static final AtomicReference<Operation> BUSY = new AtomicReference<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private LoopbackVoiceServer loopback;
    private LegacyPipelineHandler legacyPipeline;
    private final AtomicBoolean ttsFinished = new AtomicBoolean();
    private Operation activeOperation;
    private boolean destroyed;

    static boolean isBusy() { return BUSY.get() != null; }

    private static final class Operation {
        final boolean worker;
        Operation(boolean worker) { this.worker = worker; }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel("voice_lab", "Station Voice Lab",
                NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        startForeground(1, new Notification.Builder(this, "voice_lab")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("Station Voice Lab")
                .setContentText("Проверка подключения к API")
                .setOnlyAlertOnce(true).build());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String operation = intent == null ? "" : intent.getStringExtra("operation");
        if ("transport_stop".equals(operation) || "transport_status".equals(operation)) {
            Operation owner = BUSY.get();
            // A transport command must not stop a cloud/TTS check or replace its result.
            if (owner != null && (owner != activeOperation || owner.worker || loopback == null)) {
                if (activeOperation == null) stopSelf();
                return START_NOT_STICKY;
            }
            if ("transport_stop".equals(operation)) {
                if (legacyPipeline != null) legacyPipeline.close();
                if (loopback != null) loopback.stop();
            }
            JSONObject result = new JSONObject();
            put(result, "operation", operation);
            put(result, "result", transportStatus());
            if ("transport_stop".equals(operation) && owner != null) finish(result, owner);
            else {
                try { PrivateConfig.write(this, "result.json", result); } catch (Exception ignored) { }
                if (owner == null) stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (RuntimeSettings.enabled(this)) {
            JSONObject denied = new JSONObject();
            put(denied, "operation", operation); put(denied, "ok", false);
            put(denied, "reason", "RUNTIME_ENABLED");
            try { PrivateConfig.write(this, "result.json", denied); } catch (Exception ignored) { }
            if (activeOperation == null) stopSelf();
            return START_NOT_STICKY;
        }
        boolean worker = "probe".equals(operation) || "ask".equals(operation)
                || "preflight".equals(operation) || "audio_probe".equals(operation)
                || "speechify_probe".equals(operation) || "pipeline_probe".equals(operation)
                || "local_asr_probe".equals(operation) || "sherpa_asr_probe".equals(operation)
                || "sherpa_endpoint_probe".equals(operation);
        final Operation owned = new Operation(worker);
        if (!BUSY.compareAndSet(null, owned)) {
            if (activeOperation == null) stopSelf();
            return START_NOT_STICKY;
        }
        activeOperation = owned;
        if ("tts_info".equals(operation)) {
            inspectTts(owned);
        } else if ("transport_start".equals(operation) || "legacy_canary".equals(operation)
                || "legacy_fixture".equals(operation) || "legacy_pipeline".equals(operation)) {
            final long leaseMillis = "legacy_pipeline".equals(operation) ? 300000
                    : "legacy_canary".equals(operation) ? 120000 : 30000;
            if ("legacy_pipeline".equals(operation)) {
                try {
                    legacyPipeline = new LegacyPipelineHandler(this, PrivateConfig.read(this, "config.json"),
                            () -> main.post(() -> nativeWorkerFinished(owned)));
                    loopback = LoopbackVoiceServer.voiceWindow(leaseMillis, legacyPipeline);
                } catch (Exception failure) {
                    JSONObject failed = new JSONObject();
                    put(failed, "operation", operation); put(failed, "ok", false);
                    put(failed, "error", "NativePipelineSetupFailed");
                    finish(failed, owned);
                    return START_NOT_STICKY;
                }
            } else if ("legacy_fixture".equals(operation)) {
                loopback = new LoopbackVoiceServer(leaseMillis, (audio, metadata, writer) -> {
                    if (writer.isOpen()) writer.reply(metadata,
                            "Сколько будет два плюс два?", "Два плюс два равно четырём.",
                            readAsset("legacy_reply_fixture.ogg"));
                });
            } else loopback = "legacy_canary".equals(operation)
                    ? new LoopbackVoiceServer(leaseMillis, (LoopbackVoiceServer.CommandHandler) null)
                    : new LoopbackVoiceServer(leaseMillis);
            loopback.start();
            JSONObject result = new JSONObject();
            put(result, "operation", operation);
            put(result, "result", transportStatus());
            try { PrivateConfig.write(this, "result.json", result); } catch (Exception ignored) { }
            main.postDelayed(() -> {
                if (BUSY.get() == owned && activeOperation == owned && loopback != null) {
                    JSONObject last = new JSONObject();
                    put(last, "operation", "transport_expired");
                    put(last, "result", transportStatus());
                    finish(last, owned);
                }
            }, leaseMillis + 200);
        } else if (worker) {
            try { new Thread(() -> runProbe(operation, owned), "VoiceLabRequest").start(); }
            catch (RuntimeException failure) { finish(new JSONObject(), owned); }
        } else {
            finish(new JSONObject(), owned);
        }
        return START_NOT_STICKY;
    }

    private void runProbe(String operation, Operation owned) {
        JSONObject result = new JSONObject();
        long started = android.os.SystemClock.elapsedRealtime();
        boolean cloudOwned = false;
        try {
            if ("local_asr_probe".equals(operation) || "sherpa_asr_probe".equals(operation)
                    || "sherpa_endpoint_probe".equals(operation)) {
                put(result, "operation", operation);
                put(result, "execution", "android_on_station");
                put(result, "result", "sherpa_endpoint_probe".equals(operation) ? SherpaAsrProbe.run(this,
                        PrivateConfig.read(this, "config.json").optInt("voice_local_asr_threads", 1))
                        : "sherpa_asr_probe".equals(operation) ? SherpaAsrProbe.run(this) : LocalAsrProbe.run(this));
                put(result, "ok", true);
                return;
            }
            cloudOwned = VoiceCloudGate.tryAcquire(owned);
            if (!cloudOwned) throw new IllegalStateException("CloudBusy");
            JSONObject config = PrivateConfig.read(this, "config.json");
            put(result, "operation", operation);
            put(result, "execution", "android_on_station");
            put(result, "transport", config.optInt("lab_proxy_port", 0) > 0 ? "adb_connect_tunnel" : "direct");
            put(result, "groqConfigured", !config.optString("groq_api_key").isEmpty());
            put(result, "cartesiaConfigured", !config.optString("cartesia_api_key").isEmpty());
            put(result, "speechifyConfigured", !config.optString("speechify_api_key").isEmpty());
            CloudClient client = new CloudClient(this);
            JSONObject response;
            if ("pipeline_probe".equals(operation)) {
                File output = new File(getFilesDir(), "pipeline_probe.ogg");
                if (output.exists() && !output.delete()) throw new IllegalStateException("AudioCleanupFailed");
                byte[] fixture = readAsset("pipeline_fixture.wav");
                put(result, "syntheticFixtureOnly", true);
                put(result, "microphoneCaptured", false);
                put(result, "audioPlayed", false);
                put(result, "fixtureBytes", fixture.length);
                VoicePipeline.Result pipeline = new VoicePipeline(this).run(config, fixture);
                response = pipeline.summary;
                response.put("audioSha256", saveAudio("pipeline_probe.ogg", pipeline.audio));
                response.put("outputFile", "pipeline_probe.ogg");
            } else if ("speechify_probe".equals(operation)) {
                File output = new File(getFilesDir(), "speechify_probe.ogg");
                File temporary = new File(getFilesDir(), "speechify_probe.ogg.tmp");
                // A failed new attempt must never leave a previous success as its output.
                if (output.exists() && !output.delete()) throw new IllegalStateException("AudioCleanupFailed");
                try {
                    SpeechifyClient.Result speech = new SpeechifyClient().synthesizeOgg(config,
                            "Проверка русского голоса. Два плюс два будет четыре.");
                    try (FileOutputStream stream = new FileOutputStream(temporary)) {
                        stream.write(speech.audio);
                        stream.getFD().sync();
                    }
                    if (!temporary.renameTo(output)) throw new IllegalStateException("AudioSaveFailed");
                    StringBuilder hash = new StringBuilder();
                    for (byte value : MessageDigest.getInstance("SHA-256").digest(speech.audio))
                        hash.append(String.format(Locale.ROOT, "%02x", value & 255));
                    response = new JSONObject().put("provider", "speechify")
                            .put("model", speech.model).put("voiceId", speech.voiceId)
                            .put("language", speech.language).put("audioFormat", speech.audioFormat)
                            .put("audioBytes", speech.audio.length).put("audioSha256", hash.toString())
                            .put("billableCharacters", speech.billableCharacters)
                            .put("outputFile", "speechify_probe.ogg");
                    put(result, "syntheticTextOnly", true);
                    put(result, "microphoneCaptured", false);
                    put(result, "audioPlayed", false);
                } finally {
                    if (temporary.exists()) temporary.delete();
                }
            } else if ("audio_probe".equals(operation)) {
                byte[] fixture = readAsset("asr_fixture.wav");
                put(result, "syntheticFixtureOnly", true);
                put(result, "microphoneCaptured", false);
                put(result, "fixtureBytes", fixture.length);
                response = client.transcribeGigaChat(config, fixture);
            } else response = "probe".equals(operation) ? client.probe(config)
                    : "preflight".equals(operation) ? client.preflight(config)
                    : client.ask(config, "Ответь одним коротким предложением по-русски: сколько будет два плюс два?");
            put(result, "result", response);
            put(result, "ok", true);
        } catch (Exception error) {
            // Exception text can contain HTTP bodies or credentials: keep only its type.
            put(result, "ok", false);
            put(result, "error", error.getClass().getSimpleName());
            if (error instanceof CloudClient.CloudException) {
                CloudClient.CloudException api = (CloudClient.CloudException) error;
                put(result, "reason", api.code);
                put(result, "provider", api.provider);
                put(result, "httpStatus", api.httpStatus);
                put(result, "exceptionType", api.exceptionType);
            } else if (error instanceof VoicePipeline.PipelineException) {
                VoicePipeline.PipelineException api = (VoicePipeline.PipelineException) error;
                put(result, "reason", api.code);
                put(result, "stage", api.stage);
                put(result, "provider", api.provider);
                put(result, "httpStatus", api.httpStatus);
                put(result, "retryAfterSeconds", api.retryAfterSeconds);
                put(result, "result", api.summary);
            } else if (error instanceof SpeechifyClient.SpeechifyException) {
                SpeechifyClient.SpeechifyException api = (SpeechifyClient.SpeechifyException) error;
                put(result, "reason", api.code);
                put(result, "provider", api.provider);
                put(result, "httpStatus", api.httpStatus);
                put(result, "retryAfterSeconds", api.retryAfterSeconds);
            }
        } finally {
            put(result, "elapsedMs", android.os.SystemClock.elapsedRealtime() - started);
            put(result, "finishedAtMs", System.currentTimeMillis());
            // CloudClient has completed its upload/delete finally before ownership ends.
            if (cloudOwned) VoiceCloudGate.release(owned);
            finish(result, owned);
        }
    }

    private byte[] readAsset(String name) throws Exception {
        try (InputStream input = getAssets().open(name)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > 2 * 1024 * 1024)
                    throw new IllegalStateException("FixtureTooLarge");
                bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        }
    }

    private String saveAudio(String name, byte[] audio) throws Exception {
        File output = new File(getFilesDir(), name);
        File temporary = new File(getFilesDir(), name + ".tmp");
        try {
            try (FileOutputStream stream = new FileOutputStream(temporary)) {
                stream.write(audio);
                stream.getFD().sync();
            }
            if (!temporary.renameTo(output)) throw new IllegalStateException("AudioSaveFailed");
            StringBuilder hash = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(audio))
                hash.append(String.format(Locale.ROOT, "%02x", value & 255));
            return hash.toString();
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }

    private void inspectTts(Operation owned) {
        ttsFinished.set(false);
        tts = new TextToSpeech(this, status -> main.post(() -> finishTts(status, owned)));
        main.postDelayed(() -> finishTts(TextToSpeech.ERROR, owned), 10000);
    }
    private void finishTts(int status, Operation owned) {
        if (BUSY.get() != owned || activeOperation != owned) return;
        if (!ttsFinished.compareAndSet(false, true)) return;
        JSONObject result = new JSONObject();
        put(result, "operation", "tts_info");
        put(result, "ok", status == TextToSpeech.SUCCESS);
        try {
            JSONArray engines = new JSONArray();
            if (tts != null) {
                for (TextToSpeech.EngineInfo engine : tts.getEngines()) engines.put(engine.name);
                if (status == TextToSpeech.SUCCESS)
                    put(result, "russianAvailability", tts.isLanguageAvailable(new Locale("ru", "RU")));
            }
            put(result, "engines", engines);
        } catch (Exception ignored) { put(result, "ok", false); }
        finish(result, owned);
    }
    private void finish(JSONObject result, Operation owned) {
        if (BUSY.get() != owned) return;
        if (legacyPipeline != null) legacyPipeline.close();
        if (loopback != null) loopback.stop();
        try { PrivateConfig.write(this, "result.json", result); } catch (Exception ignored) { }
        // Keep the service and transport status available while the upload/delete
        // finally is still running. Its completion callback performs final release.
        if (legacyPipeline != null && !legacyPipeline.canRelease()) return;
        BUSY.compareAndSet(owned, null);
        main.post(() -> {
            if (!destroyed && activeOperation == owned) {
                // Keep the cleanup owner reachable until a native cloud worker
                // has finished. onDestroy may remove its queued main callback.
                if (BUSY.get() != owned) activeOperation = null;
                stopSelf();
            }
        });
    }
    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); } catch (Exception ignored) { }
    }
    private JSONObject transportStatus() {
        JSONObject state = loopback == null ? new JSONObject() : loopback.status();
        if (legacyPipeline != null) put(state, "pipelineHandler", legacyPipeline.snapshot());
        return state;
    }
    private void nativeWorkerFinished(Operation owned) {
        if (BUSY.get() != owned || legacyPipeline == null) return;
        if (destroyed) {
            JSONObject result = new JSONObject();
            put(result, "operation", "transport_worker_finished");
            put(result, "result", transportStatus());
            try { PrivateConfig.write(this, "result.json", result); } catch (Exception ignored) { }
            if (legacyPipeline.canRelease()) BUSY.compareAndSet(owned, null);
        } else if (loopback != null && !loopback.status().optBoolean("active")) {
            JSONObject result = new JSONObject();
            put(result, "operation", "transport_worker_finished");
            put(result, "result", transportStatus());
            finish(result, owned);
        }
    }
    @Override public void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        if (tts != null) tts.shutdown();
        if (legacyPipeline != null) legacyPipeline.close();
        if (loopback != null) loopback.stop();
        Operation owned = activeOperation;
        // A destroyed Service cannot make an in-flight cloud operation available again.
        if (owned != null && !owned.worker && (legacyPipeline == null || legacyPipeline.canRelease()))
            BUSY.compareAndSet(owned, null);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
