package local.station.voicebridge;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Only ownership/lifecycle dependencies; none recognize, decode, open sockets or act. */
final class LoopbackVoiceServer {
    interface ProgressiveInput {
        boolean offerStart(LegacyVoiceSession.Metadata metadata, ReplyWriter writer);
        boolean offerChunk(ReplyWriter writer, byte[] audio);
        boolean offerEnd(LegacyVoiceSession.Metadata metadata, ReplyWriter writer);
        void cancel(ReplyWriter writer, CancelReason reason);
    }
    interface CommandHandler { void commandReady(byte[] audio, LegacyVoiceSession.Metadata metadata, ReplyWriter writer); }
    enum CancelReason { SUPERSEDED, WRITER_CLOSED }
    static final class ReplyWriter {
        volatile boolean open = true;
        final AtomicInteger closes = new AtomicInteger(), quiet = new AtomicInteger();
        volatile boolean quietHadText;
        CountDownLatch checkEntered, checkRelease;
        boolean isOpen() {
            CountDownLatch entered = checkEntered, release = checkRelease;
            if (entered != null) {
                checkEntered = null; entered.countDown();
                try { if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("fixture_timeout"); }
                catch (InterruptedException ignored) { throw new AssertionError("fixture_interrupted"); }
            }
            return open;
        }
        void completeWithoutSpeech(LegacyVoiceSession.Metadata ignored) { quiet.incrementAndGet(); }
        void completeWithoutSpeech(LegacyVoiceSession.Metadata ignored, String text) {
            quietHadText = !text.isEmpty(); quiet.incrementAndGet();
        }
        boolean requestPartialAsr(LegacyVoiceSession.Metadata ignored, String text) { return open && !text.isEmpty(); }
        boolean requestServerEndpoint(LegacyVoiceSession.Metadata ignored) { return false; }
        void finishInput(LegacyVoiceSession.Metadata ignored, String text) { throw new AssertionError("unexpected server endpoint"); }
        void abortInputFinalization(LegacyVoiceSession.Metadata ignored) { throw new AssertionError("unexpected server endpoint"); }
        void close() { closes.incrementAndGet(); open = false; }
    }
}
final class LegacyVoiceSession {
    static final int SERVER_ENDPOINT_REASON=-3;
    static final class Metadata { final int audioBytes; final int endReason=0;final long captureMillis = 1; Metadata(int bytes) { audioBytes = bytes; } }
}
final class RuntimeVoiceHandler {
    final AtomicInteger transfers = new AtomicInteger();
    void recordLocal(String text, String code, boolean executed, long elapsedMs) { }
    void transcriptReady(String ignored, LegacyVoiceSession.Metadata metadata, LoopbackVoiceServer.ReplyWriter writer) {
        transfers.incrementAndGet();
    }
    void commandFailed(String ignored, VoicePipeline.LocalFeedback feedback, LegacyVoiceSession.Metadata metadata,
                       LoopbackVoiceServer.ReplyWriter writer) { throw new AssertionError("unexpected feedback"); }
    void commandFeedback(String ignored, VoicePipeline.LocalFeedback feedback, LegacyVoiceSession.Metadata metadata,
                       LoopbackVoiceServer.ReplyWriter writer) { throw new AssertionError("unexpected timer feedback"); }
}
final class VoicePipeline { enum LocalFeedback { MUSIC_NOT_FOUND, MUSIC_UNAVAILABLE, PLAYBACK_INACTIVE, TIMER_CREATED, TIMER_UNAVAILABLE, TIMER_NOT_RUNNING, TIMER_AMBIGUOUS, TIMER_STOP_UNAVAILABLE } }
final class NativeOggInput { static final int MAX_BYTES = 512 * 1024; }
final class StreamingOpusInput implements AutoCloseable {
    private int bytes;
    StreamingOpusInput(AndroidOpusDecoder ignored) { }
    void accept(byte[] input, int offset, int count) { bytes += count; }
    int inputBytes() { return bytes; }
    int parsedBytes(){return bytes;}int pages(){return 1;}int audioPackets(){return 1;}
    int pendingPageBytes(){return 0;}int expectedPageBytes(){return -1;}boolean completeBoundary(){return bytes>0;}
    void finish() { }
    void poll() { }
    public void close() { }
}
final class AndroidOpusDecoder {
    interface PcmSink {
        void accept(byte[] pcm, int offset, int count) throws Exception;
        boolean cancelled();
    }
    AndroidOpusDecoder(PcmSink ignored) { }
}
class LocalSpeechRecognizer implements OnDeviceRecognizer {
    static volatile String text = "";
    static volatile CountDownLatch finishEntered, finishRelease;
    LocalSpeechRecognizer(File ignored) throws RecognitionException { }
    public long loadMillis() { return 0; }
    public Session newSession() { return new Session(); }
    public void close() { }
    static final class RecognitionException extends Exception {
        private static final long serialVersionUID = 1L;
        final String code = "CANCELLED";
    }
    static final class Session implements OnDeviceRecognizer.Session {
        volatile boolean cancelled;
        public void acceptPcm16(byte[] pcm, int offset, int count) { }
        public String finish() throws Exception {
            CountDownLatch entered = finishEntered, release = finishRelease;
            if (entered != null) {
                entered.countDown();
                if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("fixture_timeout");
            }
            if (cancelled) throw new RecognitionException();
            return text;
        }
        public long decodeMillis() { return 0; }
        public long pcmBytes() { return 0; }
        public void cancel() { cancelled = true; }
        public void close() { }
    }
}
final class SherpaSpeechRecognizer extends LocalSpeechRecognizer {
    SherpaSpeechRecognizer(File model, int threads) throws RecognitionException { super(model); }
    SherpaSpeechRecognizer(File model, int threads, boolean observe) throws RecognitionException { super(model); }
}
