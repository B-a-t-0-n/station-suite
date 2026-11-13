package local.station.voicebridge;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.Model;
import org.vosk.Recognizer;

/**
 * One reusable offline model and one bounded command stream at a time.
 * Construct and call accept/finish/close on a background worker, never the UI or
 * WebSocket reader. Only cancel() is intended to interrupt from another thread.
 * This class performs no network I/O, audio capture, logging, or PCM retention.
 */
public final class LocalSpeechRecognizer implements OnDeviceRecognizer {
    public static final int SAMPLE_RATE = 16000;
    public static final int MAX_PCM_BYTES = SAMPLE_RATE * 2 * 30;
    public static final int MAX_TEXT_CHARS = 1024;
    private static final int CHUNK_BYTES = 8192;
    private static final int MAX_RESULT_JSON_CHARS = 16384;

    private Engine engine;
    private Session active;
    private boolean closed;
    private final long loadMillis;

    /** Fixed safe codes only: no transcript, path, native error, or cause. */
    public static final class RecognitionException extends Exception {
        private static final long serialVersionUID = 1L;
        public final String code;
        private RecognitionException(String code) { super(code); this.code = code; }
    }

    /** Loads the already unpacked model. Keep this instance warm across commands. */
    public LocalSpeechRecognizer(File modelDirectory) throws RecognitionException {
        long started = System.nanoTime();
        if (!validModel(modelDirectory)) throw fail("MODEL_MISSING");
        try {
            engine = new VoskEngine(modelDirectory);
        } catch (Exception | LinkageError error) {
            throw fail("MODEL_LOAD");
        }
        loadMillis = elapsedMillis(started);
    }

    /* A host-only seam: fake engines test ownership/cancellation without native code. */
    LocalSpeechRecognizer(Engine engine) {
        if (engine == null) throw new IllegalArgumentException("ENGINE_MISSING");
        this.engine = engine;
        loadMillis = 0;
    }

    public long loadMillis() { return loadMillis; }

    public synchronized Session newSession() throws RecognitionException {
        if (closed) throw fail("MODEL_CLOSED");
        if (active != null) throw fail("SESSION_BUSY");
        try {
            active = new Session(engine.newStream());
            return active;
        } catch (Exception | LinkageError error) {
            throw fail("SESSION_OPEN");
        }
    }

    /** Marks any active stream cancelled; the worker releases it before the model. */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (active != null) active.cancel();
        else releaseEngine();
    }

    private synchronized void released(Session session) {
        if (active == session) active = null;
        if (closed && active == null) releaseEngine();
    }

    private void releaseEngine() {
        Engine releasing = engine;
        engine = null;
        if (releasing != null) {
            try { releasing.close(); } catch (Exception | LinkageError ignored) { }
        }
    }

    public final class Session implements OnDeviceRecognizer.Session {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final byte[] scratch = new byte[CHUNK_BYTES];
        private final StringBuilder text = new StringBuilder();
        private PcmStream stream;
        private long pcmBytes, decodeNanos;
        private boolean ended;

        private Session(PcmStream stream) {
            if (stream == null) throw new IllegalArgumentException("STREAM_MISSING");
            this.stream = stream;
        }

        /**
         * Consumes signed PCM16 little endian, mono, 16000 Hz, incrementally.
         * A call contains whole samples (even length); offset may be arbitrary.
         * Endpoint segments are joined so a pause never discards earlier words.
         */
        public synchronized void acceptPcm16(byte[] data, int offset, int length)
                throws RecognitionException {
            checkOpen();
            if (data == null || offset < 0 || length < 0 || offset > data.length - length
                    || (length & 1) != 0) throw fail("PCM_ARGUMENT");
            if (pcmBytes + (long) length > MAX_PCM_BYTES) {
                close();
                throw fail("PCM_LIMIT");
            }
            long started = System.nanoTime();
            try {
                int remaining = length;
                while (remaining != 0) {
                    checkCancelled();
                    int count = Math.min(remaining, scratch.length);
                    System.arraycopy(data, offset, scratch, 0, count);
                    boolean endpoint = stream.accept(scratch, count);
                    checkCancelled();
                    pcmBytes += count;
                    if (endpoint) appendResult(stream.result());
                    offset += count;
                    remaining -= count;
                }
            } catch (RecognitionException error) {
                close();
                throw error;
            } catch (Exception | LinkageError error) {
                close();
                throw fail("ASR_ACCEPT");
            } finally {
                decodeNanos += System.nanoTime() - started;
            }
        }

        /** Finalizes once and releases native stream resources even on error. */
        public synchronized String finish() throws RecognitionException {
            checkOpen();
            long started = System.nanoTime();
            try {
                checkCancelled();
                appendResult(stream.finalResult());
                checkCancelled();
                return text.toString();
            } catch (RecognitionException error) {
                throw error;
            } catch (Exception | LinkageError error) {
                throw fail("ASR_FINISH");
            } finally {
                decodeNanos += System.nanoTime() - started;
                close();
            }
        }

        /** Never waits for a native accept call or frees its memory concurrently. */
        public void cancel() { cancelled.set(true); }

        public synchronized long pcmBytes() { return pcmBytes; }
        public synchronized long decodeMillis() { return decodeNanos / 1000000L; }

        /** Always call on the worker in a finally block after cancellation. */
        @Override public synchronized void close() {
            if (ended) return;
            ended = true;
            PcmStream releasing = stream;
            stream = null;
            text.setLength(0);
            java.util.Arrays.fill(scratch, (byte) 0);
            try {
                if (releasing != null) releasing.close();
            } catch (Exception | LinkageError ignored) {
                // Native diagnostics must never leak through a transcript/error path.
            } finally {
                released(this);
            }
        }

        private void checkOpen() throws RecognitionException {
            if (ended) throw fail("SESSION_CLOSED");
            if (cancelled.get()) {
                close();
                throw fail("CANCELLED");
            }
        }

        private void checkCancelled() throws RecognitionException {
            if (cancelled.get()) throw fail("CANCELLED");
        }

        private void appendResult(String json) throws RecognitionException {
            if (json == null || json.length() > MAX_RESULT_JSON_CHARS) throw fail("RESULT_LIMIT");
            final String segment;
            try {
                JSONObject object = new JSONObject(json);
                Object value = object.get("text");
                if (!(value instanceof String)) throw fail("RESULT_FORMAT");
                segment = (String) value;
            } catch (RecognitionException error) {
                throw error;
            } catch (Exception error) {
                throw fail("RESULT_FORMAT");
            }
            boolean pendingSpace = text.length() != 0;
            for (int i = 0; i < segment.length(); i++) {
                char value = segment.charAt(i);
                if (Character.isWhitespace(value)) {
                    if (text.length() != 0) pendingSpace = true;
                    continue;
                }
                int addition = pendingSpace ? 2 : 1;
                if (text.length() + addition > MAX_TEXT_CHARS) throw fail("TEXT_LIMIT");
                if (pendingSpace) text.append(' ');
                text.append(value);
                pendingSpace = false;
            }
        }
    }

    interface Engine {
        PcmStream newStream() throws Exception;
        void close();
    }

    interface PcmStream {
        boolean accept(byte[] data, int length) throws Exception;
        String result() throws Exception;
        String finalResult() throws Exception;
        void close();
    }

    private static final class VoskEngine implements Engine {
        private final Model model;
        VoskEngine(File path) throws Exception {
            // Kaldi/Vosk errors otherwise write model/decoder details to stderr.
            LibVosk.vosk_set_log_level(-10);
            model = new Model(path.getAbsolutePath());
        }
        @Override public PcmStream newStream() throws Exception {
            return new VoskStream(new Recognizer(model, (float) SAMPLE_RATE));
        }
        @Override public void close() { model.close(); }
    }

    private static final class VoskStream implements PcmStream {
        private final Recognizer recognizer;
        VoskStream(Recognizer recognizer) {
            this.recognizer = recognizer;
            try {
                recognizer.setWords(false);
                recognizer.setPartialWords(false);
                recognizer.setMaxAlternatives(0);
            } catch (RuntimeException | LinkageError error) {
                try { recognizer.close(); } catch (RuntimeException | LinkageError ignored) { }
                throw error;
            }
        }
        @Override public boolean accept(byte[] data, int length) {
            return recognizer.acceptWaveForm(data, length);
        }
        @Override public String result() { return recognizer.getResult(); }
        @Override public String finalResult() { return recognizer.getFinalResult(); }
        @Override public void close() { recognizer.close(); }
    }

    private static boolean validModel(File path) {
        if (path == null || !path.isDirectory()) return false;
        for (String required : new String[] {"am/final.mdl", "conf/model.conf",
                "conf/mfcc.conf", "graph/HCLr.fst", "graph/Gr.fst"}) {
            if (!new File(path, required).isFile()) return false;
        }
        return true;
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1000000L;
    }

    private static RecognitionException fail(String code) { return new RecognitionException(code); }
}
