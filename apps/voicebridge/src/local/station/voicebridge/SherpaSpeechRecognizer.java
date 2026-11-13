package local.station.voicebridge;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.LibraryLoader;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import java.io.File;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reusable CPU-only Russian streaming Zipformer. All model/session operations
 * belong to one worker; only Session.cancel() is safe for another thread.
 * No microphone, network, files of speech, logging, or command execution.
 */
public final class SherpaSpeechRecognizer implements OnDeviceRecognizer {
    public static final int SAMPLE_RATE = 16000;
    public static final int MAX_PCM_BYTES = SAMPLE_RATE * 2 * 30;
    public static final int MAX_TEXT_CHARS = 1024;
    public static final int TAIL_SAMPLES = SAMPLE_RATE * 8 / 10;
    private static final int CHUNK_SAMPLES = 4096;
    private static final int MAX_DECODE_STEPS = 4096;
    private static boolean nativeLoaded;
    private Engine engine;
    private Session active;
    private boolean closed;
    private final long loadMillis;
    private final int threads;
    private final boolean observeEndpoint;

    public static final class RecognitionException extends Exception {
        private static final long serialVersionUID = 1L;
        public final String code;
        private RecognitionException(String code) { super(code); this.code = code; }
    }

    public SherpaSpeechRecognizer(File directory, int threads) throws RecognitionException {
        this(directory, threads, false);
    }

    public SherpaSpeechRecognizer(File directory, int threads, boolean observeEndpoint) throws RecognitionException {
        if (threads != 1 && threads != 2 && threads != 4) throw fail("THREAD_COUNT");
        this.threads = threads;
        this.observeEndpoint = observeEndpoint;
        if (!validModel(directory)) throw fail("MODEL_MISSING");
        long started = System.nanoTime();
        try {
            loadNative();
            engine = new SherpaEngine(directory, threads, observeEndpoint);
        } catch (Exception | LinkageError error) { throw fail("MODEL_LOAD"); }
        loadMillis = elapsedMillis(started);
    }

    /* Host-only ownership/PCM tests; never loads JNI. */
    SherpaSpeechRecognizer(Engine engine) {
        this(engine, false);
    }

    SherpaSpeechRecognizer(Engine engine, boolean observeEndpoint) {
        if (engine == null) throw new IllegalArgumentException("ENGINE_MISSING");
        this.engine = engine;
        this.observeEndpoint = observeEndpoint;
        threads = 1;
        loadMillis = 0;
    }

    @Override public long loadMillis() { return loadMillis; }
    public int threads() { return threads; }

    @Override public synchronized Session newSession() throws RecognitionException {
        if (closed) throw fail("MODEL_CLOSED");
        if (active != null) throw fail("SESSION_BUSY");
        try {
            active = new Session(engine.newStream());
            return active;
        } catch (Exception | LinkageError error) { throw fail("SESSION_OPEN"); }
    }

    /** Active native memory is freed by its worker after accept/decode returns. */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (active != null) active.cancel(); else releaseEngine();
    }

    private synchronized void released(Session session) {
        if (active == session) active = null;
        if (closed && active == null) releaseEngine();
    }

    private void releaseEngine() {
        Engine value = engine;
        engine = null;
        if (value != null) {
            try { value.close(); } catch (Exception | LinkageError ignored) { }
        }
    }

    public final class Session implements OnDeviceRecognizer.Session {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final float[] scratch = new float[CHUNK_SAMPLES];
        private PcmStream stream;
        private long pcmBytes, decodeNanos, finishNanos;
        private int decodeSteps;
        private boolean ended;

        private Session(PcmStream stream) {
            if (stream == null) throw new IllegalArgumentException("STREAM_MISSING");
            this.stream = stream;
        }

        @Override public synchronized void acceptPcm16(byte[] data, int offset, int count)
                throws RecognitionException {
            checkOpen();
            if (data == null || offset < 0 || count < 0 || offset > data.length - count
                    || (count & 1) != 0) throw fail("PCM_ARGUMENT");
            if (pcmBytes + (long) count > MAX_PCM_BYTES) {
                close(); throw fail("PCM_LIMIT");
            }
            long started = System.nanoTime();
            try {
                int remaining = count;
                while (remaining != 0) {
                    checkCancelled();
                    int samples = Math.min(remaining / 2, scratch.length);
                    for (int i = 0; i < samples; i++) {
                        int p = offset + i * 2;
                        short value = (short) ((data[p] & 255) | (data[p + 1] << 8));
                        scratch[i] = value / 32768.0f;
                    }
                    float[] values = samples == scratch.length ? scratch : Arrays.copyOf(scratch, samples);
                    try { stream.accept(values); }
                    finally { if (values != scratch) Arrays.fill(values, 0); }
                    pcmBytes += samples * 2L;
                    drain();
                    remaining -= samples * 2;
                    offset += samples * 2;
                }
            } catch (RecognitionException error) {
                close(); throw error;
            } catch (Exception | LinkageError error) {
                close(); throw fail("ASR_ACCEPT");
            } finally {
                Arrays.fill(scratch, 0);
                decodeNanos += System.nanoTime() - started;
            }
        }

        /**
         * Command-end flush: 800 ms zeros, then native inputFinished/drain.
         * The pinned encoder requires T=77 feature frames and advances 64;
         * an 80-frame tail also flushes an utterance ending at a chunk boundary.
         */
        @Override public synchronized String finish() throws RecognitionException {
            checkOpen();
            long started = System.nanoTime();
            try {
                checkCancelled();
                if (pcmBytes == 0) return "";
                stream.accept(new float[TAIL_SAMPLES]);
                stream.inputFinished();
                drain();
                checkCancelled();
                return normalize(stream.text());
            } catch (RecognitionException error) { throw error;
            } catch (Exception | LinkageError error) { throw fail("ASR_FINISH");
            } finally {
                finishNanos = System.nanoTime() - started;
                decodeNanos += finishNanos;
                close();
            }
        }

        private void drain() throws Exception {
            while (stream.ready()) {
                checkCancelled();
                if (++decodeSteps > MAX_DECODE_STEPS) throw fail("DECODE_LIMIT");
                stream.decode();
            }
            checkCancelled();
        }

        @Override public void cancel() { cancelled.set(true); }
        @Override public synchronized long pcmBytes() { return pcmBytes; }
        @Override public synchronized long decodeMillis() { return decodeNanos / 1000000L; }
        /** Called only by the decoding worker. An observation never changes stream ownership. */
        @Override public synchronized boolean endpointReady() {
            if (!observeEndpoint || ended || cancelled.get() || pcmBytes == 0) return false;
            try {
                return stream.endpointReady() && !normalize(stream.text()).isEmpty();
            } catch (Exception | LinkageError ignored) { return false; }
        }
        /** A transport-only intermediate hypothesis; no native state is advanced. */
        @Override public synchronized String previewText() {
            if (ended || cancelled.get() || pcmBytes == 0) return "";
            try { return normalize(stream.text()); }
            catch (Exception | LinkageError ignored) { return ""; }
        }
        public synchronized long finishMillis() { return finishNanos / 1000000L; }
        public synchronized int decodeSteps() { return decodeSteps; }

        @Override public synchronized void close() {
            if (ended) return;
            ended = true;
            PcmStream value = stream;
            stream = null;
            Arrays.fill(scratch, 0);
            try { if (value != null) value.close(); }
            catch (Exception | LinkageError ignored) { }
            finally { released(this); }
        }

        private void checkOpen() throws RecognitionException {
            if (ended) throw fail("SESSION_CLOSED");
            if (cancelled.get()) { close(); throw fail("CANCELLED"); }
        }
        private void checkCancelled() throws RecognitionException {
            if (cancelled.get()) throw fail("CANCELLED");
        }
    }

    interface Engine { PcmStream newStream() throws Exception; void close(); }
    interface PcmStream {
        void accept(float[] samples) throws Exception;
        void inputFinished() throws Exception;
        boolean ready() throws Exception;
        void decode() throws Exception;
        String text() throws Exception;
        default boolean endpointReady() throws Exception { return false; }
        void close();
    }

    private static final class SherpaEngine implements Engine {
        private final OnlineRecognizer recognizer;
        SherpaEngine(File directory, int threads, boolean observeEndpoint) {
            OnlineTransducerModelConfig transducer = OnlineTransducerModelConfig.builder()
                    .setEncoder(new File(directory, "encoder.int8.onnx").getAbsolutePath())
                    .setDecoder(new File(directory, "decoder.onnx").getAbsolutePath())
                    .setJoiner(new File(directory, "joiner.int8.onnx").getAbsolutePath()).build();
            OnlineModelConfig model = OnlineModelConfig.builder().setTransducer(transducer)
                    .setTokens(new File(directory, "tokens.txt").getAbsolutePath())
                    .setNumThreads(threads).setDebug(false).setProvider("cpu")
                    .setModelType("zipformer2").build();
            OnlineRecognizerConfig config = OnlineRecognizerConfig.builder()
                    .setFeatureConfig(FeatureConfig.builder().setSampleRate(SAMPLE_RATE)
                            .setFeatureDim(80).setDither(0).build())
                    .setOnlineModelConfig(model).setEnableEndpoint(observeEndpoint)
                    .setEndpointConfig(EndpointConfig.builder().setRule2(EndpointRule.builder()
                            .setMustContainNonSilence(true).setMinTrailingSilence(0.8f)
                            .setMinUtteranceLength(0).build()).build())
                    .setDecodingMethod("greedy_search").build();
            recognizer = new OnlineRecognizer(config);
        }
        @Override public PcmStream newStream() {
            OnlineStream stream = recognizer.createStream();
            if (stream.getPtr() == 0) throw new IllegalStateException("STREAM_OPEN");
            return new SherpaStream(recognizer, stream);
        }
        @Override public void close() { recognizer.release(); }
    }

    private static final class SherpaStream implements PcmStream {
        private final OnlineRecognizer recognizer;
        private final OnlineStream stream;
        SherpaStream(OnlineRecognizer recognizer, OnlineStream stream) {
            this.recognizer = recognizer; this.stream = stream;
        }
        @Override public void accept(float[] samples) { stream.acceptWaveform(samples, SAMPLE_RATE); }
        @Override public void inputFinished() { stream.inputFinished(); }
        @Override public boolean ready() { return recognizer.isReady(stream); }
        @Override public void decode() { recognizer.decode(stream); }
        @Override public String text() { return recognizer.getResult(stream).getText(); }
        @Override public boolean endpointReady() { return recognizer.isEndpoint(stream); }
        @Override public void close() { stream.release(); }
    }

    /** JVM helper auto-loader expects desktop OS libraries; Android loads packaged JNI. */
    private static synchronized void loadNative() {
        if (nativeLoaded) return;
        LibraryLoader.setAutoLoadEnabled(false);
        System.loadLibrary("sherpa-onnx-jni");
        nativeLoaded = true;
    }

    private static boolean validModel(File directory) {
        if (directory == null || !directory.isDirectory()) return false;
        for (String name : new String[] {"encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt"}) {
            File file = new File(directory, name);
            if (!file.isFile() || file.length() == 0) return false;
        }
        return true;
    }

    private static String normalize(String source) throws RecognitionException {
        if (source == null || source.length() > MAX_TEXT_CHARS * 4) throw fail("RESULT_LIMIT");
        StringBuilder result = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) { if (result.length() != 0) space = true; continue; }
            if (result.length() + (space ? 2 : 1) > MAX_TEXT_CHARS) throw fail("TEXT_LIMIT");
            if (space) result.append(' ');
            result.append(c); space = false;
        }
        return result.toString();
    }
    private static long elapsedMillis(long started) { return (System.nanoTime() - started) / 1000000L; }
    private static RecognitionException fail(String code) { return new RecognitionException(code); }
}
