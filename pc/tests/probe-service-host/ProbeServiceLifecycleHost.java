package local.station.voicebridge;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import org.json.JSONObject;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Actual Service and handler run against a deterministic main queue and in-memory dependencies. */
public final class ProbeServiceLifecycleHost {
    private static int assertions;
    private static byte[] fixture;
    private ProbeServiceLifecycleHost() { }

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static Object busy() throws Exception {
        Field field = ProbeService.class.getDeclaredField("BUSY"); field.setAccessible(true);
        return ((AtomicReference<?>) field.get(null)).get();
    }
    private static LegacyPipelineHandler handler(ProbeService service) throws Exception {
        Field field = ProbeService.class.getDeclaredField("legacyPipeline"); field.setAccessible(true);
        return (LegacyPipelineHandler) field.get(service);
    }
    private static void start(ProbeService service, String operation) {
        service.onStartCommand(new Intent().putExtra("operation", operation), 0, 1);
    }
    private static ProbeService service() {
        ProbeService service = new ProbeService(); service.onCreate(); return service;
    }
    private static void await(CountDownLatch latch) throws Exception {
        check(latch.await(3, TimeUnit.SECONDS), "worker did not reach deterministic gate");
    }
    private static void join(Thread thread) throws Exception {
        thread.join(3000); check(!thread.isAlive(), "worker did not finish");
        if (LoopbackVoiceServer.failure != null) throw new AssertionError(LoopbackVoiceServer.failure);
    }
    private static void denyNextCloud() throws Exception {
        int before = CloudClient.calls.get();
        ProbeService denied = service(); start(denied, "probe");
        check(denied.stopCalls == 1, "unowned rejected instance did not stop");
        check(CloudClient.calls.get() == before, "cloud operation admitted before cleanup");
        denied.onDestroy();
        check(busy() != null, "foreign onDestroy released the active owner");
    }
    private static void admitNextCloud() throws Exception {
        check(busy() == null, "cleanup left ownership wedged");
        CloudClient.prepare(false);
        int before = CloudClient.calls.get();
        ProbeService next = service(); start(next, "probe"); await(CloudClient.entered);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while ((busy() != null || Handler.pendingImmediate() == 0) && System.nanoTime() < deadline)
            Thread.yield();
        check(CloudClient.calls.get() == before + 1, "next cloud operation was not admitted exactly once");
        check(busy() == null, "finished cloud operation did not release ownership");
        Handler.drain();
        check(next.stopCalls == 1, "finished cloud service did not stop");
        check("probe".equals(PrivateConfig.last(next).optString("operation")), "next cloud result missing");
        next.onDestroy(); Handler.drain();
    }
    private static void legacyCase(String mode) throws Exception {
        check(busy() == null, "previous case retained owner");
        VoicePipeline.prepare();
        ProbeService service = service(); start(service, "legacy_pipeline");
        LoopbackVoiceServer server = LoopbackVoiceServer.latest;
        check(server.active && busy() != null, "native pipeline was not admitted");
        check("legacy_pipeline".equals(PrivateConfig.last(service).optString("operation")), "start result absent");
        Thread worker = server.command(fixture); await(VoicePipeline.entered);
        LegacyPipelineHandler handler = handler(service);
        check(handler.activeWorkers() == 1, "actual handler worker did not enter pipeline");

        if ("destroy-blocked".equals(mode)) service.onDestroy();
        else if ("expiry-blocked".equals(mode)) Handler.runDelayed();
        else start(service, "transport_stop");

        check(!server.active && handler.snapshot().optBoolean("closed"), "stop did not close admission and transport");
        check(handler.activeWorkers() == 1 && !handler.canRelease(), "stop released in-flight cleanup");
        check(service.stopCalls == 0, "stopSelf called while native worker remained active");
        check(busy() != null, "stop released static service ownership too early");
        denyNextCloud();
        VoicePipeline.release.countDown(); join(worker);
        check(VoicePipeline.cleaned, "provider cleanup not completed before callback");
        check(handler.canRelease() && handler.activeWorkers() == 0, "actual handler did not finish cleanup");
        check("discarded".equals(handler.snapshot().optString("state")), "closed native reply was not discarded");
        check(!handler.snapshot().optBoolean("replySent") && server.writer.replies == 0, "stale reply sent after stop");
        check(busy() != null, "queued completion released service before main-thread handoff");

        if ("queued-before-destroy".equals(mode)) {
            check(Handler.pendingImmediate() > 0, "completion callback was not queued");
            service.onDestroy();
            check(busy() == null, "onDestroy failed to release cleaned owner after removing its callback");
        }
        Handler.drain();
        check(busy() == null, "main completion left service ownership held");
        if (!"queued-before-destroy".equals(mode))
            check("transport_worker_finished".equals(PrivateConfig.last(service).optString("operation")),
                    "worker completion did not publish safe final status");
        if ("destroy-blocked".equals(mode) || "queued-before-destroy".equals(mode))
            check(service.stopCalls == 0, "destroyed service was stopped by a stale callback");
        else check(service.stopCalls == 1, "normal cleanup did not stop service");
        service.onDestroy(); Handler.drain();
        admitNextCloud();
    }
    private static void oldStopCannotStopNewOwner() throws Exception {
        VoicePipeline.prepare();
        ProbeService service = service(); start(service, "legacy_canary");
        Object old = busy(); start(service, "transport_stop");
        check(busy() == null && Handler.pendingImmediate() > 0, "old completion was not queued");
        start(service, "legacy_pipeline"); Object next = busy();
        check(next != null && next != old, "new operation did not acquire a distinct owner");
        Handler.drain();
        check(service.stopCalls == 0 && busy() == next && LoopbackVoiceServer.latest.active,
                "old queued stop stopped the newer operation");
        start(service, "transport_stop"); Handler.drain(); service.onDestroy();
        check(busy() == null, "new owner could not finish");
        admitNextCloud();
    }
    private static void destroyedCloudStillOwnsCleanup() throws Exception {
        CloudClient.prepare(true);
        ProbeService service = service(); start(service, "probe"); await(CloudClient.entered);
        service.onDestroy(); check(busy() != null, "destroy released cloud cleanup"); denyNextCloud();
        CloudClient.release.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while ((busy() != null || Handler.pendingImmediate() == 0) && System.nanoTime() < deadline)
            Thread.yield();
        check(busy() == null, "destroyed cloud worker failed to release ownership");
        Handler.drain(); check(service.stopCalls == 0, "destroyed cloud service stopped again");
        admitNextCloud();
    }
    public static void main(String[] args) throws Exception {
        fixture = Files.readAllBytes(Paths.get(args[0]));
        legacyCase("stop-blocked");
        legacyCase("expiry-blocked");
        legacyCase("destroy-blocked");
        legacyCase("queued-before-destroy");
        oldStopCannotStopNewOwner();
        destroyedCloudStillOwnsCleanup();
        check(busy() == null, "final owner leak");
        System.out.println(assertions + " lifecycle assertions passed; actual ProbeService/handler, no sockets/API/device");
    }
}

/** In-memory dependencies; production service and adapter sources are compiled unchanged. */
final class PrivateConfig {
    private static final Map<Context, JSONObject> results = Collections.synchronizedMap(new IdentityHashMap<Context, JSONObject>());
    static JSONObject read(Context context, String name) { return new JSONObject(); }
    static void write(Context context, String name, JSONObject result) throws Exception {
        results.put(context, new JSONObject(result.toString()));
    }
    static JSONObject last(Context context) { return results.get(context); }
}

final class LocalAsrProbe {
    static JSONObject run(Context context) { throw new AssertionError("unexpected local ASR probe branch"); }
}
final class SherpaAsrProbe {
    static JSONObject run(Context context) { throw new AssertionError("unexpected Sherpa ASR probe branch"); }
    static JSONObject run(Context context, int threads) { throw new AssertionError("unexpected Sherpa endpoint probe branch"); }
}

final class VoicePipeline {
    static CountDownLatch entered, release;
    static volatile boolean cleaned;
    static void prepare() { entered = new CountDownLatch(1); release = new CountDownLatch(1); cleaned = false; }
    VoicePipeline(Context context) { }
    Result run(JSONObject config, byte[] audio) throws PipelineException {
        entered.countDown();
        try {
            if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("synthetic pipeline gate timeout");
            cleaned = true;
            return new Result(new JSONObject().put("fileDeleted", true).put("ok", true), audio);
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
    static final class Result {
        final JSONObject summary;
        final byte[] audio;
        final String transcript = "synthetic", answer = "synthetic";
        Result(JSONObject summary, byte[] audio) { this.summary = summary; this.audio = audio; }
    }
    static final class PipelineException extends Exception {
        private static final long serialVersionUID = 1L;
        String code, stage, provider; int httpStatus, retryAfterSeconds; JSONObject summary;
    }
}

final class CloudClient {
    static final AtomicInteger calls = new AtomicInteger();
    static CountDownLatch entered, release;
    static void prepare(boolean block) { entered = new CountDownLatch(1); release = new CountDownLatch(block ? 1 : 0); }
    CloudClient(Context context) { }
    JSONObject probe(JSONObject config) throws Exception {
        calls.incrementAndGet(); entered.countDown();
        if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("synthetic cloud gate timeout");
        return new JSONObject().put("ok", true);
    }
    JSONObject preflight(JSONObject config) { throw new AssertionError("unexpected provider branch"); }
    JSONObject ask(JSONObject config, String text) { throw new AssertionError("unexpected provider branch"); }
    JSONObject transcribeGigaChat(JSONObject config, byte[] audio) { throw new AssertionError("unexpected provider branch"); }
    static final class CloudException extends Exception {
        private static final long serialVersionUID = 1L;
        String code, provider, exceptionType; int httpStatus;
    }
}

final class SpeechifyClient {
    Result synthesizeOgg(JSONObject config, String text) { throw new AssertionError("unexpected provider branch"); }
    static final class Result {
        byte[] audio; String model, voiceId, language, audioFormat; int billableCharacters;
    }
    static final class SpeechifyException extends Exception {
        private static final long serialVersionUID = 1L;
        String code, provider; int httpStatus, retryAfterSeconds;
    }
}

final class LoopbackVoiceServer {
    interface CommandHandler {
        void commandReady(byte[] audio, LegacyVoiceSession.Metadata metadata, ReplyWriter writer) throws Exception;
    }
    static final class ReplyWriter {
        volatile boolean open = true; int replies;
        boolean isOpen() { return open; }
        void reply(LegacyVoiceSession.Metadata meta, String transcript, String answer, byte[] audio) throws IOException {
            if (!open) throw new IOException("closed"); replies++;
        }
    }
    static LoopbackVoiceServer latest;
    static volatile Throwable failure;
    final CommandHandler handler;
    final ReplyWriter writer = new ReplyWriter();
    volatile boolean active;
    LoopbackVoiceServer(long lease) { this(lease, null); }
    LoopbackVoiceServer(long lease, CommandHandler handler) { this.handler = handler; latest = this; failure = null; }
    static LoopbackVoiceServer voiceWindow(long lease, LegacyPipelineHandler handler) {
        return new LoopbackVoiceServer(lease, handler);
    }
    void start() { active = true; }
    void stop() { active = false; writer.open = false; }
    JSONObject status() {
        try { return new JSONObject().put("active", active); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    Thread command(byte[] audio) throws Exception {
        Constructor<LegacyVoiceSession.Metadata> ctor = LegacyVoiceSession.Metadata.class.getDeclaredConstructor(
                long.class, String.class, String.class, long.class, String.class, boolean.class, int.class);
        ctor.setAccessible(true);
        LegacyVoiceSession.Metadata meta = ctor.newInstance(7L, "synthetic-ref", "synthetic-request", 0L,
                "synthetic-session", false, audio.length);
        Thread worker = new Thread(() -> {
            try { handler.commandReady(audio, meta, writer); }
            catch (Throwable problem) { failure = problem; }
        }, "SyntheticLifecycleWorker");
        worker.start(); return worker;
    }
}
