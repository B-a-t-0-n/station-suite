package local.station.voicebridge;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/** Actual handler/pipeline/normalizer/reply codec; no connected socket, API or Android calls. */
public final class RuntimeVoiceHandlerHostTest {
    private static int assertions, nextId;
    private static byte[] audio;
    private static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "RuntimeHandlerHostTimer"); thread.setDaemon(true); return thread;
    });
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static final class Output {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final Socket socket = new Socket();
        final LoopbackVoiceServer.ReplyWriter writer;
        Output() throws Exception {
            Class<?> runClass = Class.forName("local.station.voicebridge.LoopbackVoiceServer$Run");
            Constructor<?> runCtor = runClass.getDeclaredConstructor(boolean.class); runCtor.setAccessible(true);
            Object run = runCtor.newInstance(true);
            Field active = runClass.getDeclaredField("active"); active.setAccessible(true); active.set(run, true);
            Field deadline = runClass.getDeclaredField("deadlineNanos"); deadline.setAccessible(true); deadline.set(run, Long.MAX_VALUE);
            Field timer = runClass.getDeclaredField("timer"); timer.setAccessible(true); timer.set(run, TIMER);
            Constructor<LoopbackVoiceServer.ReplyWriter> protocolCtor = LoopbackVoiceServer.ReplyWriter.class
                    .getDeclaredConstructor(runClass, Socket.class, java.io.OutputStream.class);
            protocolCtor.setAccessible(true); LoopbackVoiceServer.ReplyWriter protocol = protocolCtor.newInstance(run, socket, bytes);
            Field connection = LoopbackVoiceServer.ReplyWriter.class.getDeclaredField("connection"); connection.setAccessible(true);
            Constructor<LoopbackVoiceServer.ReplyWriter> ctor = LoopbackVoiceServer.ReplyWriter.class
                    .getDeclaredConstructor(connection.getType(), boolean.class);
            ctor.setAccessible(true); writer = ctor.newInstance(connection.get(protocol), true);
            java.lang.reflect.Method activate = runClass.getDeclaredMethod("activate", LoopbackVoiceServer.ReplyWriter.class);
            activate.setAccessible(true); activate.invoke(run, writer);
        }
    }
    private static void quietCompletion(Output output) throws Exception {
        byte[] wire = output.bytes.toByteArray(); int offset = 0, messages = 0;
        while (offset < wire.length) {
            int opcode = wire[offset++] & 15, size = wire[offset++] & 127;
            if (size == 126) { size = ((wire[offset] & 255) << 8) | (wire[offset + 1] & 255); offset += 2; }
            check(opcode == 1 && size < 65536 && offset + size <= wire.length, "completion emitted audio/close or malformed frame");
            JSONObject message = new JSONObject(new String(wire, offset, size, java.nio.charset.StandardCharsets.UTF_8));
            offset += size;
            if (messages == 0) {
                JSONObject directive = message.getJSONObject("directive");
                check("Result".equals(directive.getJSONObject("header").getString("name"))
                        && directive.getJSONObject("payload").getJSONArray("recognition").getJSONObject(0).getString("normalized").isEmpty(),
                        "completion exposed transcript or omitted ASR end");
            } else if (messages == 1) check(message.has("streamcontrol"), "completion omitted input end");
            else if (messages == 2) {
                JSONObject payload = message.getJSONObject("directive").getJSONObject("payload");
                JSONObject voice = payload.getJSONObject("voice_response");
                check(!voice.getBoolean("has_voice_response") && !voice.has("output_speech")
                        && payload.getJSONObject("response").getJSONArray("directives").length() == 0,
                        "completion requested TTS or retained speech directives");
            }
            messages++;
        }
        check(messages == 3, "missing or duplicate terminal completion");
        check(!output.socket.isClosed(), "child completion/finally closed persistent connection");
    }
    private static LegacyVoiceSession.Metadata metadata() throws Exception {
        Constructor<LegacyVoiceSession.Metadata> ctor = LegacyVoiceSession.Metadata.class.getDeclaredConstructor(
                long.class, String.class, String.class, long.class, String.class, boolean.class, int.class);
        ctor.setAccessible(true); int id = ++nextId;
        return ctor.newInstance((long) id, "ref-" + id, "request-" + id, 0L, "session", false, audio.length);
    }
    private static final class Fake implements VoicePipeline.Backend {
        final AtomicInteger asr = new AtomicInteger(), llm = new AtomicInteger(), tts = new AtomicInteger();
        final AtomicInteger warmups = new AtomicInteger();
        volatile CountDownLatch warmEntered = new CountDownLatch(1), warmRelease = new CountDownLatch(0), warmExit;
        volatile boolean warmFailure;
        volatile CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(0);
        volatile Exception failure;
        volatile boolean deleted = true;
        volatile CountDownLatch streamEntered, streamRelease;
        volatile boolean streamFailure;
        volatile String synthesized;
        volatile Exception synthesisFailure;
        AtomicLong time;
        volatile long asrMillis, llmMillis, ttsMillis;
        public void validate(JSONObject config) { }
        public JSONObject prewarm(JSONObject config, CloudClient.Warmup budget) throws Exception {
            warmups.incrementAndGet(); warmEntered.countDown();
            while (!warmRelease.await(5, TimeUnit.MILLISECONDS) && !budget.isCancelled()) { }
            if (warmExit != null && !warmExit.await(3, TimeUnit.SECONDS)) throw new AssertionError("synthetic warm cleanup gate");
            if (warmFailure) throw new java.io.IOException("SYNTHETIC_PRIVATE_WARMUP_ERROR");
            return new JSONObject().put("ok", !budget.isCancelled()).put("oauthReady", true)
                    .put("gigachatReady", true).put("speechifyReady", true).put("models", "SYNTHETIC_PRIVATE_MODELS");
        }
        public JSONObject transcribe(JSONObject config, byte[] input) throws Exception {
            asr.incrementAndGet(); entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("synthetic gate expired");
            time.addAndGet(asrMillis);
            if (failure != null) throw failure;
            return new JSONObject().put("ok", true).put("text", "SYNTHETIC_PRIVATE_TRANSCRIPT").put("fileDeleted", deleted);
        }
        public JSONObject ask(JSONObject config, String prompt) throws Exception {
            llm.incrementAndGet(); time.addAndGet(llmMillis);
            return new JSONObject().put("ok", true).put("text", "SYNTHETIC_PRIVATE_ANSWER");
        }
        public VoicePipeline.Speech synthesize(JSONObject config, String answer) throws Exception {
            tts.incrementAndGet(); time.addAndGet(ttsMillis);
            synthesized = answer;
            if (synthesisFailure != null) throw synthesisFailure;
            return new VoicePipeline.Speech(audio, 27, "ogg");
        }
        public VoicePipeline.StreamSpeech synthesizeStreaming(JSONObject config, String answer, VoicePipeline.AudioSink sink)
                throws Exception {
            if (streamEntered == null) return VoicePipeline.Backend.super.synthesizeStreaming(config, answer, sink);
            synthesized = answer;
            tts.incrementAndGet(); int offset = 0, pages = 0;
            while (offset < audio.length) {
                if (sink.isCancelled()) throw new java.io.IOException("cancelled");
                int segments = audio[offset + 26] & 255, length = 27 + segments;
                for (int i = 0; i < segments; i++) length += audio[offset + 27 + i] & 255;
                sink.write(audio, offset, length); offset += length;
                if (++pages == 3) {
                    streamEntered.countDown();
                    if (!streamRelease.await(3, TimeUnit.SECONDS)) throw new AssertionError("stream test gate expired");
                    if (streamFailure) throw new java.io.IOException("SYNTHETIC_PRIVATE_STREAM_ERROR");
                }
            }
            return new VoicePipeline.StreamSpeech(audio.length, -1, "ogg", 3, 7);
        }
    }
    private static final class Fixture {
        final Fake fake = new Fake();
        final AtomicLong clock = new AtomicLong(100);
        final AtomicReference<JSONObject> config = new AtomicReference<>();
        final AtomicInteger observed = new AtomicInteger();
        final RuntimeVoiceHandler handler;
        Fixture() throws Exception {
            fake.time = clock;
            config.set(new JSONObject().put("private", "SYNTHETIC_PRIVATE_KEY"));
            handler = new RuntimeVoiceHandler(new VoicePipeline(fake, clock::get), config::get,
                    value -> { if (!value.has("private")) throw new Exception("SYNTHETIC_PRIVATE_BODY"); },
                    clock::get, observed::incrementAndGet);
        }
        Output question() throws Exception {
            Output output = new Output(); handler.commandReady(audio, metadata(), output.writer); return output;
        }
        void idle() throws Exception {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (handler.activeWorkers() != 0 && System.nanoTime() < until) Thread.yield();
            check(handler.activeWorkers() == 0, "worker leaked");
            check(!VoiceCloudGate.isBusy(), "global gate leaked after cleanup");
            String status = handler.snapshot().toString();
            check(!status.contains("SYNTHETIC_PRIVATE"), "private data in health snapshot");
            check(!status.contains("ref-") && !status.contains("request-"), "native IDs in health snapshot");
        }
    }
    private static void sequentialAndDuplicate() throws Exception {
        Fixture f = new Fixture();
        check(f.handler.snapshot().optBoolean("healthy"), "configuration readiness absent before question");
        Output one = new Output(); LegacyVoiceSession.Metadata meta = metadata();
        f.handler.commandReady(audio, meta, one.writer); f.idle();
        check(one.bytes.size() > audio.length, "first actual legacy reply missing");
        Output duplicate = new Output(); f.handler.commandReady(audio, meta, duplicate.writer); f.idle();
        check(f.fake.asr.get() == 1, "duplicate command called API"); quietCompletion(duplicate);
        Output two = f.question(); f.idle();
        check(two.bytes.size() > audio.length, "second actual legacy reply missing");
        check(!one.socket.isClosed() && !two.socket.isClosed(), "successful child finally closed connection");
        check(f.fake.asr.get() == 2 && f.fake.llm.get() == 2 && f.fake.tts.get() == 2, "sequential calls not exactly once");
        check(f.handler.snapshot().optLong("completed") == 2, "completed counter wrong");
        f.handler.close(); check(f.handler.canRelease(), "idle close not releasable");
    }

    private static void startupWarmupNeverBlocksQuestionOrLifecycle() throws Exception {
        Fixture disabled = new Fixture(); disabled.handler.startWarmup();
        check(disabled.fake.warmups.get() == 0 && !disabled.handler.snapshot().optBoolean("warming"), "non-local mode unexpectedly warmed");
        disabled.handler.close();
        Fixture f = new Fixture();
        f.config.get().put("voice_local_asr", true);
        f.fake.warmRelease = new CountDownLatch(1); f.fake.warmExit = new CountDownLatch(1);
        f.handler.startWarmup();
        check(f.fake.warmEntered.await(1, TimeUnit.SECONDS), "startup worker missing");
        check(!VoiceCloudGate.isBusy(), "metadata warmup acquired billable command gate");
        f.handler.refreshConfiguration(); f.handler.startWarmup();
        check(f.fake.warmups.get() == 1, "refresh or second start retried startup metadata");
        long started = System.nanoTime();
        Output answer = f.question(); f.idle();
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500, "question waited for warmup remainder");
        check(answer.bytes.size() > audio.length && f.fake.asr.get() == 1 && f.fake.llm.get() == 1
                && f.fake.tts.get() == 1, "first question was rejected, queued or duplicated by startup");
        check(f.handler.snapshot().optBoolean("warming"), "synthetic cancellation cleanup was not held");
        f.handler.close();
        check(!f.handler.canRelease() && !f.handler.snapshot().optBoolean("canRelease"), "close released a live warmup worker");
        check("CLEANUP".equals(f.handler.snapshot().optString("healthReason")), "closed startup cleanup health wrong");
        f.fake.warmExit.countDown();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!f.handler.canRelease() && System.nanoTime() < until) Thread.yield();
        check(f.handler.canRelease(), "startup cancellation leaked handler lifecycle");
        check(!f.handler.snapshot().toString().contains("SYNTHETIC_PRIVATE"), "startup result leaked raw fields");

        Fixture success = new Fixture(); success.config.get().put("voice_local_asr", true);
        success.handler.startWarmup();
        until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (success.handler.snapshot().optBoolean("warming") && System.nanoTime() < until) Thread.yield();
        JSONObject status = success.handler.snapshot();
        check(!status.optBoolean("warming") && status.optLong("warmedMs", -1) >= 0
                && status.optBoolean("warmupOauthReady") && status.optBoolean("warmupGigaReady")
                && status.optBoolean("warmupSpeechifyReady") && "READY".equals(status.optString("warmupCode")), "safe warmup metrics missing");
        check(success.fake.asr.get() == 0 && success.fake.llm.get() == 0 && success.fake.tts.get() == 0
                && status.optLong("transactions") == 0, "startup triggered billable pipeline");
        check(!status.toString().contains("SYNTHETIC_PRIVATE") && !status.has("models"), "catalog exposed through health");
        success.handler.close();

        Fixture failure = new Fixture(); failure.config.get().put("voice_local_asr", true); failure.fake.warmFailure = true;
        failure.handler.startWarmup();
        until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (failure.handler.snapshot().optBoolean("warming") && System.nanoTime() < until) Thread.yield();
        check(!failure.handler.snapshot().optBoolean("circuitOpen") && failure.handler.snapshot().optBoolean("healthy")
                && failure.handler.snapshot().optLong("failed") == 0, "optional warmup changed command health/circuit");
        failure.question(); failure.idle(); check(failure.fake.llm.get() == 1, "optional failure blocked real question");
        failure.handler.close();
    }
    private static void bargeAndCloseKeepCleanup() throws Exception {
        Fixture f = new Fixture(); f.fake.release = new CountDownLatch(1);
        Output one = f.question(); check(f.fake.entered.await(3, TimeUnit.SECONDS), "ASR not entered");
        Output barge = f.question();
        check(!one.writer.isOpen() && !barge.writer.isOpen(), "barge did not invalidate stale/unqueued outputs");
        check(f.fake.asr.get() == 1 && f.handler.activeWorkers() == 1, "barge overlapped providers");
        quietCompletion(barge);
        Object external = new Object(); check(!VoiceCloudGate.tryAcquire(external), "busy gate admitted external cloud call");
        f.handler.close(); check(!f.handler.canRelease(), "close interrupted in-flight cleanup ownership");
        check(VoiceCloudGate.isBusy(), "close released global gate early");
        f.fake.release.countDown(); f.idle();
        check(f.handler.canRelease() && one.bytes.size() == 0, "stale output escaped after close");
        check(f.handler.snapshot().optLong("discarded") == 1, "stale result not counted");
        check(VoiceCloudGate.tryAcquire(external), "cleaned worker blocked next owner");
        check(VoiceCloudGate.release(external), "matching owner failed to release");
    }
    private static void cooldownAndChangedKeys() throws Exception {
        Fixture f = new Fixture();
        Constructor<CloudClient.CloudException> ctor = CloudClient.CloudException.class
                .getDeclaredConstructor(String.class, String.class, int.class);
        ctor.setAccessible(true); f.fake.failure = ctor.newInstance("gigachat", "RATE_LIMITED", 429);
        Output failed = f.question(); f.idle();
        check(f.handler.snapshot().optBoolean("circuitOpen"), "rate failure did not open circuit"); quietCompletion(failed);
        check("RATE_LIMITED".equals(f.handler.snapshot().optString("lastCode")), "safe failure code missing");
        check(f.handler.snapshot().optInt("httpStatus") == 429, "safe HTTP status missing");
        Output cooling = f.question(); f.idle(); check(f.fake.asr.get() == 1, "cooldown retried API"); quietCompletion(cooling);
        f.clock.addAndGet(2100); f.fake.failure = null;
        Output recovered = f.question(); f.idle();
        check(recovered.bytes.size() > 0 && f.fake.asr.get() == 2, "new utterance did not recover after cooldown");
        check(!f.handler.snapshot().optBoolean("circuitOpen"), "success did not close circuit");
        f.fake.failure = ctor.newInstance("gigachat", "HTTP_ERROR", 401);
        f.question(); f.idle(); check(f.handler.snapshot().optLong("backoffRemainingMs") == 300000, "auth cooldown absent");
        f.config.set(new JSONObject().put("private", "SYNTHETIC_PRIVATE_REPLACEMENT"));
        check(f.handler.refreshConfiguration(), "local config refresh failed");
        check(!f.handler.snapshot().optBoolean("circuitOpen"), "replacement keys did not reset circuit");
        f.fake.failure = null; f.question(); f.idle();
        check(f.fake.asr.get() == 4, "replacement keys did not permit new utterance");
        f.handler.close();
    }
    private static void retryAfterCleanupFailureAndBadConfig() throws Exception {
        Fixture f = new Fixture(); f.fake.deleted = false;
        f.question(); f.idle();
        check(f.fake.llm.get() == 0 && f.fake.tts.get() == 0, "failed ASR delete continued pipeline");
        check("ASR_FILE_NOT_DELETED".equals(f.handler.snapshot().optString("lastCode")), "cleanup failure not visible");
        f.handler.close();
        f = new Fixture();
        Constructor<SpeechifyClient.SpeechifyException> ctor = SpeechifyClient.SpeechifyException.class
                .getDeclaredConstructor(String.class, int.class, int.class); ctor.setAccessible(true);
        f.fake.failure = ctor.newInstance("RATE_LIMITED", 429, 45);
        f.question(); f.idle();
        check(f.handler.snapshot().optLong("backoffRemainingMs") == 45000, "Retry-After ignored");
        f.config.set(new JSONObject()); check(!f.handler.refreshConfiguration(), "bad config reported ready");
        f.question(); f.idle(); check(f.fake.asr.get() == 1, "bad config reached provider");
        check(!f.handler.snapshot().optBoolean("credentialsReady"), "bad config readiness true");
        f.handler.close();
    }
    private static void gateIdentity() {
        Object a = new Object(), b = new Object();
        check(VoiceCloudGate.tryAcquire(a), "free gate refused owner");
        check(!VoiceCloudGate.tryAcquire(a) && !VoiceCloudGate.release(b), "nonexclusive or foreign release");
        check(VoiceCloudGate.isBusy() && VoiceCloudGate.release(a) && !VoiceCloudGate.isBusy(), "identity release broken");
    }
    private static void safePipelineTimings() throws Exception {
        Fixture f = new Fixture();
        check(f.handler.snapshot().optLong("lastElapsedMs", 999) == -1, "unrun pipeline timing claimed known");
        f.fake.asrMillis = 11; f.fake.llmMillis = 7; f.fake.ttsMillis = 5;
        f.question(); f.idle();
        JSONObject success = f.handler.snapshot();
        check(success.optLong("lastElapsedMs") == 23 && success.optLong("lastAsrMs") == 11
                && success.optLong("lastLlmMs") == 7 && success.optLong("lastTtsMs") == 5,
                "successful pipeline stage timings missing or changed");
        f.fake.deleted = false;
        f.question(); f.idle();
        JSONObject failure = f.handler.snapshot();
        check(failure.optLong("lastElapsedMs") == 11 && failure.optLong("lastAsrMs") == 11
                && failure.optLong("lastLlmMs", 999) == -1 && failure.optLong("lastTtsMs", 999) == -1,
                "failure timings retained stale successful stage metrics");
        check(failure.optLong("failed") == 1 && failure.optLong("completed") == 1,
                "timing projection changed request accounting");
        f.handler.close();
    }
    private static void externalOwnershipAndStaleConfigurationFailure() throws Exception {
        Fixture f = new Fixture(); Object external = new Object();
        check(VoiceCloudGate.tryAcquire(external), "test external owner not acquired");
        Output rejected = f.question();
        check(f.fake.asr.get() == 0 && !rejected.writer.isOpen() && f.handler.activeWorkers() == 0,
                "runtime overlapped external cloud owner");
        quietCompletion(rejected);
        check(VoiceCloudGate.release(external), "runtime released foreign owner");
        f.fake.release = new CountDownLatch(1);
        Constructor<CloudClient.CloudException> ctor = CloudClient.CloudException.class
                .getDeclaredConstructor(String.class, String.class, int.class); ctor.setAccessible(true);
        f.fake.failure = ctor.newInstance("gigachat", "HTTP_ERROR", 401);
        f.question(); check(f.fake.entered.await(3, TimeUnit.SECONDS), "old config worker not entered");
        f.config.set(new JSONObject().put("private", "SYNTHETIC_PRIVATE_NEW_KEY"));
        check(f.handler.refreshConfiguration(), "replacement config not ready");
        f.fake.release.countDown(); f.idle();
        check(!f.handler.snapshot().optBoolean("circuitOpen"), "failure from old keys poisoned new configuration");
        f.fake.failure = null; f.question(); f.idle(); check(f.fake.asr.get() == 2, "new configuration not usable");
        f.handler.close();
    }
    private static void streamingBeforeCompletionAndAbort() throws Exception {
        Fixture f = new Fixture();
        f.fake.streamEntered = new CountDownLatch(1); f.fake.streamRelease = new CountDownLatch(1);
        Output output = f.question();
        check(f.fake.streamEntered.await(3, TimeUnit.SECONDS), "stream did not reach first audio");
        check(output.bytes.size() > 0 && f.handler.activeWorkers() == 1
                && f.handler.snapshot().optLong("completed") == 0, "native audio waited for entire synthesis");
        check(VoiceCloudGate.isBusy(), "stream released cloud gate before final cleanup");
        f.fake.streamRelease.countDown(); f.idle();
        check(f.handler.snapshot().optLong("completed") == 1 && !output.socket.isClosed(),
                "stream completion failed or closed persistent connection");
        check(f.handler.snapshot().optLong("lastTtsFirstAudioMs") == 7
                && f.handler.snapshot().optBoolean("lastStreamingAudio"), "stream timing/mode missing");
        f.handler.close();

        f = new Fixture(); f.fake.streamEntered = new CountDownLatch(1); f.fake.streamRelease = new CountDownLatch(1);
        f.fake.streamFailure = true; output = f.question();
        check(f.fake.streamEntered.await(3, TimeUnit.SECONDS) && output.bytes.size() > 0,
                "partial failure fixture did not send audio");
        f.fake.streamRelease.countDown(); f.idle();
        check(f.handler.snapshot().optLong("failed") == 1 && f.handler.snapshot().optLong("completed") == 0,
                "partial provider failure counted as successful answer");
        check(output.socket.isClosed(), "partial failed stream left native waiting for EOS");
        f.handler.close();

        f = new Fixture(); f.fake.streamEntered = new CountDownLatch(1); f.fake.streamRelease = new CountDownLatch(1);
        output = f.question(); check(f.fake.streamEntered.await(3, TimeUnit.SECONDS), "cancel fixture not streaming");
        int sent = output.bytes.size(); f.handler.close(); f.fake.streamRelease.countDown(); f.idle();
        check(output.bytes.size() == sent, "cancelled stream emitted more pages or a second completion");
        check(f.handler.snapshot().optLong("discarded") == 1 && f.handler.snapshot().optLong("failed") == 0,
                "intentional stream cancellation poisoned API health");

        f = new Fixture(); f.config.get().put("voice_streaming_tts", false);
        output = f.question(); f.idle();
        check(f.handler.snapshot().optLong("completed") == 1 && !f.handler.snapshot().optBoolean("lastStreamingAudio")
                && output.bytes.size() > audio.length, "explicit buffered fallback stopped working");
        f.handler.close();
    }
    private static void finalizedLocalTranscriptOnly() throws Exception {
        Fixture f = new Fixture();
        Output first = new Output(); LegacyVoiceSession.Metadata metadata = metadata();
        f.config.get().put("voice_direct_audio",true);
        f.handler.transcriptReady("SYNTHETIC_PRIVATE_LOCAL_TRANSCRIPT",metadata,first.writer);f.idle();
        check(f.fake.asr.get()==0&&f.fake.llm.get()==1&&f.fake.tts.get()==1,"local final text uploaded audio or duplicated providers");
        check(first.bytes.size()>audio.length,"local final text did not produce native response");
        Output duplicate = new Output();
        f.handler.transcriptReady("SYNTHETIC_PRIVATE_LOCAL_TRANSCRIPT",metadata,duplicate.writer);f.idle();
        check(f.fake.llm.get()==1&&f.fake.tts.get()==1,"duplicate local completion called providers");
        quietCompletion(duplicate);
        for(String invalid:new String[]{null," ",new String(new char[VoicePipeline.MAX_TRANSCRIPT_CHARS+1]).replace('\0','я')}){
            Output rejected = new Output();
            f.handler.transcriptReady(invalid,metadata(),rejected.writer);f.idle();quietCompletion(rejected);
        }
        check(f.fake.asr.get()==0&&f.fake.llm.get()==1&&f.fake.tts.get()==1,"invalid local final text called APIs");
        f.handler.close();
    }
    private static String firstReplyTranscript(Output output) throws Exception {
        byte[] wire = output.bytes.toByteArray();
        check(wire.length >= 2 && (wire[0] & 15) == 1, "reply missing first ASR JSON frame");
        int offset = 2, length = wire[1] & 127;
        if (length == 126) { length = ((wire[offset] & 255) << 8) | (wire[offset + 1] & 255); offset += 2; }
        check(length < 65536 && offset + length <= wire.length, "first ASR frame malformed");
        JSONObject directive = new JSONObject(new String(wire, offset, length, java.nio.charset.StandardCharsets.UTF_8))
                .getJSONObject("directive");
        check("ASR".equals(directive.getJSONObject("header").getString("namespace")), "first reply is not ASR");
        return directive.getJSONObject("payload").getJSONArray("recognition").getJSONObject(0).getString("normalized");
    }
    private static void localFailureFeedbackOnly() throws Exception {
        Fixture f = new Fixture(); f.config.get().put("voice_direct_audio", true); f.fake.ttsMillis = 5;
        String transcript = "SYNTHETIC_PRIVATE_FINAL_COMMAND";
        String[] phrases = {"Сейчас не получается включить музыку.", "Не удалось найти эту музыку.",
                "Сейчас не получается выполнить эту команду.", "Таймер установлен.",
                "Не удалось подтвердить установку таймера. Проверь раздел Часы в пульте.",
                "Активного таймера нет.", "Сейчас несколько таймеров. Скажи: останови все таймеры.",
                "Не удалось подтвердить остановку таймера. Проверь раздел Часы в пульте."};
        int index = 0;
        for (VoicePipeline.LocalFeedback feedback : VoicePipeline.LocalFeedback.values()) {
            LegacyVoiceSession.Metadata meta = metadata(); Output output = new Output();
            f.handler.commandFeedback(transcript, feedback, meta, output.writer); f.idle();
            check(f.fake.asr.get() == 0 && f.fake.llm.get() == 0 && f.fake.tts.get() == index + 1,
                    "local failure feedback must call TTS once and never cloud ASR/LLM");
            check(phrases[index++].equals(f.fake.synthesized), "failure feedback phrase is not fixed or claims success");
            check(transcript.equals(firstReplyTranscript(output)), "failed finalized command transcript was replaced");
            JSONObject status = f.handler.snapshot();
            check(status.optLong("lastAsrMs", -1) == 0 && status.optLong("lastLlmMs", -1) == 0
                    && status.optLong("lastTtsMs", -1) == 5 && status.optBoolean("lastStreamingAudio")
                    && !status.optBoolean("lastFileDeleted", true), "feedback timing or streaming mode incorrect");
            Output duplicate = new Output(); f.handler.transcriptReady(transcript, meta, duplicate.writer); f.idle();
            quietCompletion(duplicate);
            check(f.fake.llm.get() == 0 && f.fake.tts.get() == index, "feedback admission bypassed shared duplicate guard");
        }
        check(f.handler.snapshot().optLong("completed") == phrases.length, "feedback altered success accounting");
        f.handler.close();

        f = new Fixture(); f.config.get().put("voice_streaming_tts", false);
        Output buffered = new Output();
        f.handler.commandFailed(transcript, VoicePipeline.LocalFeedback.MUSIC_UNAVAILABLE, metadata(), buffered.writer);
        f.idle();
        check(f.fake.asr.get() == 0 && f.fake.llm.get() == 0 && f.fake.tts.get() == 1
                && !f.handler.snapshot().optBoolean("lastStreamingAudio")
                && transcript.equals(firstReplyTranscript(buffered)), "feedback ignored explicit buffered setting");
        f.handler.close();

        for (String invalid : new String[]{null, " ", new String(new char[VoicePipeline.MAX_TRANSCRIPT_CHARS + 1])
                .replace('\0', 'я'), "bad\u0000", "bad\ud800", "bad\udc00"}) {
            f = new Fixture(); Output rejected = new Output();
            f.handler.commandFailed(invalid, VoicePipeline.LocalFeedback.MUSIC_UNAVAILABLE, metadata(), rejected.writer);
            f.idle(); quietCompletion(rejected);
            check(f.fake.asr.get() == 0 && f.fake.llm.get() == 0 && f.fake.tts.get() == 0,
                    "malformed command feedback reached provider");
            f.handler.close();
        }
        f = new Fixture(); Output rejected = new Output();
        f.handler.commandFailed(transcript, null, metadata(), rejected.writer); f.idle(); quietCompletion(rejected);
        check(f.fake.asr.get() == 0 && f.fake.llm.get() == 0 && f.fake.tts.get() == 0,
                "null feedback enum reached providers");
        f.handler.close();
    }
    private static void feedbackOwnershipCancellationAndCircuit() throws Exception {
        Fixture f = new Fixture(); f.fake.streamEntered = new CountDownLatch(1); f.fake.streamRelease = new CountDownLatch(1);
        Output output = new Output();
        f.handler.commandFailed("SYNTHETIC_PRIVATE_FAILED_COMMAND", VoicePipeline.LocalFeedback.MUSIC_UNAVAILABLE,
                metadata(), output.writer);
        check(f.fake.streamEntered.await(3, TimeUnit.SECONDS), "feedback did not start incremental synthesis");
        int sent = output.bytes.size(); Object foreign = new Object();
        check(f.handler.activeWorkers() == 1 && VoiceCloudGate.isBusy() && !VoiceCloudGate.tryAcquire(foreign)
                && !VoiceCloudGate.release(foreign), "feedback did not retain exclusive owner gate");
        f.handler.close();
        check(!f.handler.canRelease() && VoiceCloudGate.isBusy(), "close released feedback worker before cleanup");
        f.fake.streamRelease.countDown(); f.idle();
        check(output.bytes.size() == sent && f.handler.snapshot().optLong("discarded") == 1
                && f.handler.snapshot().optLong("failed") == 0, "closed feedback sent stale output or poisoned circuit");

        f = new Fixture(); foreign = new Object(); check(VoiceCloudGate.tryAcquire(foreign), "external test gate failed");
        Output blocked = new Output();
        f.handler.commandFailed("SYNTHETIC_PRIVATE_FAILED_COMMAND", VoicePipeline.LocalFeedback.MUSIC_NOT_FOUND,
                metadata(), blocked.writer);
        check(f.fake.tts.get() == 0 && f.handler.activeWorkers() == 0, "feedback bypassed external owner");
        quietCompletion(blocked); check(VoiceCloudGate.release(foreign), "feedback released external owner gate");
        f.handler.close();

        f = new Fixture();
        Constructor<SpeechifyClient.SpeechifyException> ctor = SpeechifyClient.SpeechifyException.class
                .getDeclaredConstructor(String.class, int.class, int.class); ctor.setAccessible(true);
        f.fake.synthesisFailure = ctor.newInstance("RATE_LIMITED", 429, 45);
        Output failed = new Output();
        f.handler.commandFailed("SYNTHETIC_PRIVATE_FAILED_COMMAND", VoicePipeline.LocalFeedback.MUSIC_UNAVAILABLE,
                metadata(), failed.writer); f.idle(); quietCompletion(failed);
        check(f.handler.snapshot().optLong("backoffRemainingMs") == 45000
                && f.handler.snapshot().optLong("failed") == 1, "feedback ignored TTS circuit and Retry-After");
        Output cooldown = new Output();
        f.handler.commandFailed("SYNTHETIC_PRIVATE_FAILED_COMMAND", VoicePipeline.LocalFeedback.MUSIC_UNAVAILABLE,
                metadata(), cooldown.writer); f.idle(); quietCompletion(cooldown);
        check(f.fake.asr.get() == 0 && f.fake.llm.get() == 0 && f.fake.tts.get() == 1,
                "feedback bypassed shared API cooldown");
        f.handler.close();

        f = new Fixture(); f.config.set(new JSONObject()); Output badConfig = new Output();
        f.handler.commandFailed("SYNTHETIC_PRIVATE_FAILED_COMMAND", VoicePipeline.LocalFeedback.MUSIC_UNAVAILABLE,
                metadata(), badConfig.writer); f.idle(); quietCompletion(badConfig);
        check(f.fake.tts.get() == 0 && !f.handler.snapshot().optBoolean("credentialsReady"),
                "feedback bypassed configuration validation");
        f.handler.close();
    }
    public static void main(String[] args) throws Exception {
        audio = Files.readAllBytes(Paths.get(args[0]));
        startupWarmupNeverBlocksQuestionOrLifecycle();
        gateIdentity(); sequentialAndDuplicate(); bargeAndCloseKeepCleanup(); cooldownAndChangedKeys();
        retryAfterCleanupFailureAndBadConfig();
        externalOwnershipAndStaleConfigurationFailure();
        safePipelineTimings();
        streamingBeforeCompletionAndAbort();
        finalizedLocalTranscriptOnly();
        localFailureFeedbackOnly();
        feedbackOwnershipCancellationAndCircuit();
        TIMER.shutdownNow();
        System.out.println(assertions + " runtime assertions passed; synthetic in-memory providers, no sockets/API/device");
    }
}
