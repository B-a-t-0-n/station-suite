package local.station.voicebridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** Deterministic internal-boundary and partial-write failure checks, no network/API. */
public final class EndpointStateHostTest {
    static int checks;
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    static JSONObject event(String namespace, String name, String id) throws Exception {
        return new JSONObject().put("event", new JSONObject().put("header", new JSONObject()
                .put("namespace", namespace).put("name", name).put("messageId", id).put("streamId", 1))
                .put("payload", new JSONObject()));
    }
    static final class Fixture {
        long clock = 100; int ends, callbacks; boolean fallback;
        LegacyVoiceSession.Metadata start, completed; byte[] audio;
        final LegacyVoiceSession session = new LegacyVoiceSession(new LegacyVoiceSession.Clock() {
            public long monotonicMillis() { return clock; }
            public long wallTimeMillis() { return clock; }
        }, value -> { }, (bytes, metadata) -> { callbacks++; audio = bytes; check(completed == metadata); }, 120000, true,
                new LegacyVoiceSession.AudioObserver() {
                    public boolean offerStart(LegacyVoiceSession.Metadata metadata) { start = metadata; return true; }
                    public boolean offerChunk(byte[] frame, int off, int count) { return true; }
                    public boolean offerEnd(LegacyVoiceSession.Metadata metadata) { completed = metadata; ends++; return !fallback; }
                    public void cancel(LegacyVoiceSession.AudioCancelReason reason) { }
                });
        Fixture() throws Exception {
            session.onMessage(event("System", "SynchronizeState", "sync"));
            session.onMessage(event("Vins", "VoiceInput", "input"));
        }
        void bytes() throws Exception { session.onBinary(new byte[]{0, 0, 0, 1, 10, 20}); }
    }
    static Field field(Class<?> owner, String name) throws Exception {
        Field result = owner.getDeclaredField(name); result.setAccessible(true); return result;
    }
    static final class FailingOutput extends ByteArrayOutputStream {
        int flushes; Runnable onFailure;
        @Override public void flush() throws IOException {
            if (++flushes == 2) {
                if (onFailure != null) onFailure.run();
                throw new IOException("synthetic_second_frame_failure");
            }
        }
    }
    static void partialFailure(LegacyVoiceSession.Metadata metadata, boolean barge) throws Exception {
        Class<?> runType = Class.forName("local.station.voicebridge.LoopbackVoiceServer$Run");
        Constructor<?> runCtor = runType.getDeclaredConstructor(boolean.class); runCtor.setAccessible(true);
        Object run = runCtor.newInstance(true); field(runType, "active").set(run, true);
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r, "SyntheticDeadline"); thread.setDaemon(true); return thread;
        });
        field(runType, "timer").set(run, timer);
        Class<?> connType = Class.forName("local.station.voicebridge.LoopbackVoiceServer$Connection");
        Constructor<?> connCtor = connType.getDeclaredConstructor(runType, Socket.class, java.io.OutputStream.class);
        connCtor.setAccessible(true);
        Socket socket = new Socket(); FailingOutput output = new FailingOutput();
        Object connection = connCtor.newInstance(run, socket, output);
        Constructor<LoopbackVoiceServer.ReplyWriter> writerCtor = LoopbackVoiceServer.ReplyWriter.class
                .getDeclaredConstructor(connType, boolean.class); writerCtor.setAccessible(true);
        LoopbackVoiceServer.ReplyWriter old = writerCtor.newInstance(connection, true);
        LoopbackVoiceServer.ReplyWriter newer = writerCtor.newInstance(connection, true);
        Method activate = runType.getDeclaredMethod("activate", LoopbackVoiceServer.ReplyWriter.class); activate.setAccessible(true);
        activate.invoke(run, old);
        Method completed = LoopbackVoiceServer.ReplyWriter.class.getDeclaredMethod("inputCompleted", LegacyVoiceSession.Metadata.class);
        completed.setAccessible(true); completed.invoke(old, metadata);
        if (barge) output.onFailure = () -> {
            try { activate.invoke(run, newer); } catch (Exception failure) { throw new AssertionError(failure); }
        };
        try {
            try { old.finishInput(metadata, "Сделай тише"); throw new AssertionError("partial failure accepted"); }
            catch (IOException expected) { check(!old.isOpen()); }
            check(((AtomicLong) field(runType, "inputFinalizationFailures").get(run)).get() == 1);
            check(((AtomicLong) field(runType, "inputFinalizations").get(run)).get() == 0);
            try { old.completeWithoutSpeech(metadata); throw new AssertionError("duplicate quiet sent"); }
            catch (IOException expected) { check(true); }
            check(socket.isClosed() != barge);
            if (barge) {
                old.abortInputFinalization(metadata);
                check(!socket.isClosed()); check(newer.isOpen());
            }
        } finally { socket.close(); timer.shutdownNow(); }
    }
    public static void main(String[] args) throws Exception {
        Fixture disabled = new Fixture(); disabled.bytes();
        check(!disabled.session.completeFromServer(disabled.start)); check(disabled.callbacks == 0);
        Fixture f = new Fixture(); f.session.enableRuntimeReuse();
        check(!f.session.completeFromServer(f.start)); check(f.callbacks == 0);
        f.bytes(); f.session.onBinary(new byte[]{0, 0, 0, 99, 77}); f.clock += 700;
        check(!f.session.completeFromServer(disabled.start));
        check(f.session.completeFromServer(f.start)); check(f.callbacks == 1 && f.ends == 1);
        check(Arrays.equals(f.audio, new byte[]{10, 20})); check(f.completed.audioBytes == 2);
        check(f.completed.captureMillis == 700 && f.completed.endReason == -3);
        check(f.session.bufferedBytes() == 0 && f.session.state() == LegacyVoiceSession.State.COMPLETE);
        check(!f.session.completeFromServer(f.start)); check(f.callbacks == 1);
        f.session.onMessage(new JSONObject().put("streamcontrol", new JSONObject()
                .put("streamId", 1).put("action", 0).put("reason", 7)));
        check(f.callbacks == 1 && f.session.lastCommandEndReason() == -3);
        LegacyVoiceSession.Metadata old = f.start;
        f.session.rearmRuntime(); f.session.onMessage(event("Vins", "VoiceInput", "next")); f.bytes();
        check(!f.session.completeFromServer(old)); check(f.session.completeFromServer(f.start)); check(f.callbacks == 2);
        Fixture fallback = new Fixture(); fallback.fallback = true; fallback.session.enableRuntimeReuse(); fallback.bytes();
        check(fallback.session.completeFromServer(fallback.start)); check(fallback.callbacks == 1);
        Fixture expired = new Fixture(); expired.session.enableRuntimeReuse(); expired.bytes(); expired.clock += 15000;
        check(!expired.session.completeFromServer(expired.start)); check(expired.callbacks == 0);
        partialFailure(f.completed, false); partialFailure(f.completed, true);
        System.out.println("Server endpoint state PASS checks=" + checks);
    }
}
