package local.station.voicebridge;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** No native load, microphone, file audio, network, credentials, or device access. */
public final class LocalSpeechRecognizerHostTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        incrementalAndModelReuse();
        limitsAndSafeErrors();
        cancellationAndOwnership();
        failureCleanup();
        System.out.println("local ASR host checks passed: " + checks);
    }

    private static void incrementalAndModelReuse() throws Exception {
        FakeEngine backend = new FakeEngine();
        LocalSpeechRecognizer model = new LocalSpeechRecognizer(backend);
        LocalSpeechRecognizer.Session session = model.newSession();
        expect("SESSION_BUSY", () -> model.newSession());
        FakeStream stream = backend.streams.get(0);
        stream.endpointResults.add("{\"text\":\"  включи   \"}");
        stream.finalJson = "{\"text\":\"  музыку  \"}";
        byte[] data = new byte[20005];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 113);
        session.acceptPcm16(data, 3, 20000);
        check(stream.lengths.size() == 3, "incremental native chunks");
        check(stream.maxLength == 8192, "bounded native scratch");
        check(stream.firstByte == data[3] && stream.lastByte == data[20002], "offset exactness");
        check(session.pcmBytes() == 20000, "numeric bytes");
        check("включи музыку".equals(session.finish()), "join endpoint and final segments");
        check(stream.closes == 1 && backend.closes == 0, "finish closes only recognizer");
        check(stream.lastArray != null && isZero(stream.lastArray), "PCM scratch wiped");
        expect("SESSION_CLOSED", () -> session.finish());
        LocalSpeechRecognizer.Session second = model.newSession();
        check(backend.streams.size() == 2, "warm model reused");
        check("".equals(second.finish()), "empty result stays empty");
        model.close(); model.close();
        check(backend.closes == 1, "model idempotent close");
        expect("MODEL_CLOSED", () -> model.newSession());
    }

    private static void limitsAndSafeErrors() throws Exception {
        FakeEngine backend = new FakeEngine();
        LocalSpeechRecognizer model = new LocalSpeechRecognizer(backend);
        LocalSpeechRecognizer.Session first = model.newSession();
        expect("PCM_ARGUMENT", () -> first.acceptPcm16(null, 0, 0));
        expect("PCM_ARGUMENT", () -> first.acceptPcm16(new byte[4], 0, 3));
        expect("PCM_ARGUMENT", () -> first.acceptPcm16(new byte[4], 3, 2));
        expect("PCM_ARGUMENT", () -> first.acceptPcm16(new byte[4], -1, 2));
        expect("PCM_ARGUMENT", () -> first.acceptPcm16(new byte[4], 0, Integer.MAX_VALUE));
        first.acceptPcm16(new byte[0], 0, 0);
        check(backend.streams.get(0).lengths.isEmpty(), "invalid or empty PCM not consumed");
        first.acceptPcm16(new byte[LocalSpeechRecognizer.MAX_PCM_BYTES], 0,
                LocalSpeechRecognizer.MAX_PCM_BYTES);
        expect("PCM_LIMIT", () -> first.acceptPcm16(new byte[2], 0, 2));
        check(backend.streams.get(0).closes == 1, "PCM limit releases stream");
        LocalSpeechRecognizer.Session session = model.newSession();
        backend.streams.get(1).finalJson = "{\"text\":\"" + repeat('я', 1025) + "\"}";
        LocalSpeechRecognizer.Session tooLong = session;
        expect("TEXT_LIMIT", () -> tooLong.finish());
        session = model.newSession();
        backend.streams.get(2).finalJson = "{\"text\":\"" + repeat('я', 1024) + "\"}";
        check(session.finish().length() == 1024, "exact text boundary accepted");
        session = model.newSession();
        backend.streams.get(3).finalJson = "{\"text\":\"" + repeat('я', 16385) + "\"}";
        LocalSpeechRecognizer.Session largeJson = session;
        expect("RESULT_LIMIT", () -> largeJson.finish());
        session = model.newSession();
        backend.streams.get(4).finalJson = "{\"text\":123}";
        LocalSpeechRecognizer.Session badJson = session;
        expect("RESULT_FORMAT", () -> badJson.finish());
        model.close();
    }

    private static void cancellationAndOwnership() throws Exception {
        FakeEngine backend = new FakeEngine();
        LocalSpeechRecognizer model = new LocalSpeechRecognizer(backend);
        LocalSpeechRecognizer.Session session = model.newSession();
        FakeStream stream = backend.streams.get(0);
        stream.entered = new CountDownLatch(1);
        stream.release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { session.acceptPcm16(new byte[2], 0, 2); }
            catch (Throwable error) { failure.set(error); }
            finally { session.close(); }
        });
        worker.start();
        check(stream.entered.await(2, TimeUnit.SECONDS), "native accept entered");
        long before = System.nanoTime();
        session.cancel();
        model.close();
        check(System.nanoTime() - before < TimeUnit.MILLISECONDS.toNanos(100), "cancel never blocks");
        check(stream.closes == 0 && backend.closes == 0, "no native free during accept");
        stream.release.countDown();
        worker.join(2000);
        check(!worker.isAlive(), "cancelled worker terminates");
        check(failure.get() instanceof LocalSpeechRecognizer.RecognitionException
                && "CANCELLED".equals(((LocalSpeechRecognizer.RecognitionException) failure.get()).code),
                "cancel safe code");
        check(stream.closes == 1 && backend.closes == 1, "recognizer freed before model");
        check(!stream.closedInsideAccept, "native free serialized");

        FakeEngine beforeEngine = new FakeEngine();
        LocalSpeechRecognizer beforeModel = new LocalSpeechRecognizer(beforeEngine);
        LocalSpeechRecognizer.Session beforeSession = beforeModel.newSession();
        beforeSession.cancel();
        expect("CANCELLED", () -> beforeSession.finish());
        check(beforeEngine.streams.get(0).finalCalls == 0, "cancel before finish excludes decoder");
        beforeModel.close();
    }

    private static void failureCleanup() throws Exception {
        FakeEngine backend = new FakeEngine();
        LocalSpeechRecognizer model = new LocalSpeechRecognizer(backend);
        LocalSpeechRecognizer.Session session = model.newSession();
        backend.streams.get(0).acceptFailure = true;
        expect("ASR_ACCEPT", () -> session.acceptPcm16(new byte[2], 0, 2));
        check(backend.streams.get(0).closes == 1, "accept exception cleanup");
        LocalSpeechRecognizer.Session finish = model.newSession();
        backend.streams.get(1).finishFailure = true;
        expect("ASR_FINISH", () -> finish.finish());
        check(backend.streams.get(1).closes == 1, "finish exception cleanup");
        model.close();
    }

    private static final class FakeEngine implements LocalSpeechRecognizer.Engine {
        final List<FakeStream> streams = new ArrayList<>();
        int closes;
        @Override public LocalSpeechRecognizer.PcmStream newStream() {
            FakeStream stream = new FakeStream(); streams.add(stream); return stream;
        }
        @Override public void close() { closes++; }
    }

    private static final class FakeStream implements LocalSpeechRecognizer.PcmStream {
        final List<Integer> lengths = new ArrayList<>();
        final List<String> endpointResults = new ArrayList<>();
        int maxLength, closes, finalCalls, endpointIndex;
        byte firstByte, lastByte;
        byte[] lastArray;
        String finalJson = "{\"text\":\"\"}";
        boolean acceptFailure, finishFailure, accepting, closedInsideAccept;
        CountDownLatch entered, release;
        @Override public boolean accept(byte[] data, int length) throws Exception {
            if (acceptFailure) throw new IllegalStateException("PRIVATE_TRANSCRIPT_DO_NOT_EXPOSE");
            accepting = true;
            try {
                if (entered != null) {
                    entered.countDown();
                    if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("TIMEOUT");
                }
                if (lengths.isEmpty()) firstByte = data[0];
                lengths.add(length); maxLength = Math.max(maxLength, length);
                lastByte = data[length - 1]; lastArray = data;
                return endpointIndex < endpointResults.size();
            } finally { accepting = false; }
        }
        @Override public String result() { return endpointResults.get(endpointIndex++); }
        @Override public String finalResult() {
            finalCalls++;
            if (finishFailure) throw new IllegalStateException("PRIVATE_TRANSCRIPT_DO_NOT_EXPOSE");
            return finalJson;
        }
        @Override public void close() { closes++; closedInsideAccept |= accepting; }
    }

    private interface Action { void run() throws Exception; }
    private static void expect(String code, Action action) throws Exception {
        try { action.run(); throw new AssertionError("Expected safe error " + code); }
        catch (LocalSpeechRecognizer.RecognitionException error) {
            check(code.equals(error.code) && code.equals(error.getMessage())
                    && error.getCause() == null, "fixed error " + code);
        }
    }
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++;
    }
    private static boolean isZero(byte[] bytes) {
        for (byte value : bytes) if (value != 0) return false;
        return true;
    }
    private static String repeat(char value, int length) {
        char[] chars = new char[length]; java.util.Arrays.fill(chars, value); return new String(chars);
    }
}
