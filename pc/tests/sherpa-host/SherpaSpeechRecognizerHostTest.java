package local.station.voicebridge;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class SherpaSpeechRecognizerHostTest {
    private static int assertions;
    interface Action { void run() throws Exception; }
    static void check(boolean value, String label) { assertions++; if (!value) throw new AssertionError(label); }
    static void fails(String code, Action action) throws Exception {
        try { action.run(); throw new AssertionError("expected " + code); }
        catch (SherpaSpeechRecognizer.RecognitionException error) { check(code.equals(error.code), "safe code " + code); }
    }
    static final class Engine implements SherpaSpeechRecognizer.Engine {
        final List<Stream> streams = new ArrayList<>();
        int closes; Stream next;
        @Override public SherpaSpeechRecognizer.PcmStream newStream() {
            Stream stream = next == null ? new Stream() : next;
            next = null; streams.add(stream); return stream;
        }
        @Override public void close() { closes++; }
    }
    static class Stream implements SherpaSpeechRecognizer.PcmStream {
        final List<float[]> arrays = new ArrayList<>();
        int closes, decode, accepts, endpointCalls, textCalls; boolean finalInput, readyForever, failAccept, endpoint, failEndpoint, failText;
        String result = "  сколько\nбудет  два плюс два  ";
        CountDownLatch entered, release;
        @Override public void accept(float[] samples) throws Exception {
            if (entered != null) { entered.countDown(); if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("blocked"); }
            if (failAccept) throw new IllegalStateException("PRIVATE_NATIVE_DETAIL");
            arrays.add(samples.clone()); accepts++;
        }
        @Override public void inputFinished() { finalInput = true; }
        @Override public boolean ready() { return readyForever || decode < accepts; }
        @Override public void decode() { decode++; }
        @Override public String text() {
            textCalls++;
            if (failText) throw new IllegalStateException("PRIVATE_TEXT_DETAIL");
            return result;
        }
        @Override public boolean endpointReady() {
            endpointCalls++;
            if (failEndpoint) throw new IllegalStateException("PRIVATE_ENDPOINT_DETAIL");
            return endpoint;
        }
        @Override public void close() { closes++; }
    }

    public static void main(String[] args) throws Exception {
        conversionAndReuse(); validation(); boundsAndFailures(); cancellationOwnership(); chunkBoundaryTail();
        passiveEndpointObservation();
        transportPreviewOnly();
        check(SherpaAsrProbe.normalize(" ЁЖ,  два? ").equals("еж два"), "fixture normalization");
        check(SherpaAsrProbe.wordEdits("два плюс два", "два два") == 1, "deletion WER");
        check(SherpaAsrProbe.wordEdits("два плюс два", "два минус два") == 1, "substitution WER");
        check(SherpaAsrProbe.wordEdits("два два", "два плюс два") == 1, "insertion WER");
        check(SherpaAsrProbe.wordEdits("два", "") == 1, "empty result WER");
        System.out.println("SherpaSpeechRecognizer host PASS assertions=" + assertions);
    }

    private static void conversionAndReuse() throws Exception {
        Engine engine = new Engine();
        SherpaSpeechRecognizer model = new SherpaSpeechRecognizer(engine);
        check(model instanceof OnDeviceRecognizer, "adapter interface");
        SherpaSpeechRecognizer.Session session = model.newSession();
        byte[] pcm = {99, 0, (byte) 128, (byte) 255, 127, (byte) 255, (byte) 255, 0, 0, 77};
        session.acceptPcm16(pcm, 1, 8);
        Stream stream = engine.streams.get(0);
        float[] values = stream.arrays.get(0);
        check(values.length == 4, "exact sample count");
        check(values[0] == -1f, "negative full scale");
        check(values[1] == 32767 / 32768.0f, "positive full scale");
        check(values[2] == -1 / 32768.0f, "minus one sample");
        check(values[3] == 0f, "zero sample");
        check(session.pcmBytes() == 8, "real PCM only");
        check(session.finish().equals("сколько будет два плюс два"), "whole final normalized");
        check(stream.finalInput && stream.arrays.get(1).length == 12800, "800ms tail and native final");
        for (float value : stream.arrays.get(1)) check(value == 0, "zero tail");
        check(session.pcmBytes() == 8, "padding excluded from duration");
        check(stream.closes == 1, "native stream released");
        session.close(); check(stream.closes == 1, "idempotent close");
        fails("SESSION_CLOSED", () -> session.finish());
        try (SherpaSpeechRecognizer.Session next = model.newSession()) {
            byte[] large = new byte[20000]; next.acceptPcm16(large, 0, large.length);
            check(engine.streams.size() == 2, "new stream same model");
            check(engine.streams.get(1).arrays.size() == 3, "bounded conversion chunks");
        }
        check(engine.closes == 0, "model retained between utterances");
        model.close(); model.close(); check(engine.closes == 1, "model released once");
        fails("MODEL_CLOSED", () -> model.newSession());
    }

    private static void validation() throws Exception {
        Engine engine = new Engine();
        try (SherpaSpeechRecognizer model = new SherpaSpeechRecognizer(engine)) {
            SherpaSpeechRecognizer.Session session = model.newSession();
            fails("SESSION_BUSY", () -> model.newSession());
            fails("PCM_ARGUMENT", () -> session.acceptPcm16(null, 0, 0));
            fails("PCM_ARGUMENT", () -> session.acceptPcm16(new byte[2], -1, 2));
            fails("PCM_ARGUMENT", () -> session.acceptPcm16(new byte[2], 0, 1));
            fails("PCM_ARGUMENT", () -> session.acceptPcm16(new byte[2], Integer.MAX_VALUE, 2));
            fails("PCM_ARGUMENT", () -> session.acceptPcm16(new byte[2], 0, -2));
            session.acceptPcm16(new byte[0], 0, 0);
            check(session.finish().equals(""), "empty final no hallucination");
            check(engine.streams.get(0).accepts == 0, "empty input no padding/decode");
        }
        fails("THREAD_COUNT", () -> new SherpaSpeechRecognizer(null, 3));
        fails("MODEL_MISSING", () -> new SherpaSpeechRecognizer(null, 1));
    }

    private static void boundsAndFailures() throws Exception {
        Engine engine = new Engine();
        try (SherpaSpeechRecognizer model = new SherpaSpeechRecognizer(engine)) {
            SherpaSpeechRecognizer.Session limit = model.newSession();
            fails("PCM_LIMIT", () -> limit.acceptPcm16(new byte[SherpaSpeechRecognizer.MAX_PCM_BYTES + 2], 0,
                    SherpaSpeechRecognizer.MAX_PCM_BYTES + 2));
            check(engine.streams.get(0).closes == 1, "limit releases stream");
            engine.next = new Stream(); engine.next.failAccept = true;
            SherpaSpeechRecognizer.Session broken = model.newSession();
            fails("ASR_ACCEPT", () -> broken.acceptPcm16(new byte[2], 0, 2));
            check(engine.streams.get(1).closes == 1, "native error cleanup");
            engine.next = new Stream(); engine.next.readyForever = true;
            SherpaSpeechRecognizer.Session endless = model.newSession();
            fails("DECODE_LIMIT", () -> endless.acceptPcm16(new byte[2], 0, 2));
            check(engine.streams.get(2).decode == 4096, "bounded native loop");
            engine.next = new Stream(); engine.next.result = new String(new char[1025]).replace('\0', 'а');
            SherpaSpeechRecognizer.Session longText = model.newSession();
            longText.acceptPcm16(new byte[2], 0, 2);
            fails("TEXT_LIMIT", () -> longText.finish());
            check(engine.streams.get(3).closes == 1, "oversized final cleanup");
        }
    }

    private static void cancellationOwnership() throws Exception {
        Engine engine = new Engine(); Stream stream = new Stream(); engine.next = stream;
        stream.entered = new CountDownLatch(1); stream.release = new CountDownLatch(1);
        SherpaSpeechRecognizer model = new SherpaSpeechRecognizer(engine);
        SherpaSpeechRecognizer.Session session = model.newSession();
        Throwable[] failure = new Throwable[1];
        Thread worker = new Thread(() -> {
            try { session.acceptPcm16(new byte[2], 0, 2); failure[0] = new AssertionError("cancel missing"); }
            catch (SherpaSpeechRecognizer.RecognitionException error) {
                if (!"CANCELLED".equals(error.code)) failure[0] = error;
            }
        });
        worker.start(); check(stream.entered.await(2, TimeUnit.SECONDS), "native entered");
        long started = System.nanoTime(); session.cancel(); model.close();
        check(System.nanoTime() - started < 500000000L, "cancel/model close nonblocking");
        check(stream.closes == 0 && engine.closes == 0, "no concurrent native free");
        stream.release.countDown(); worker.join(2000);
        check(!worker.isAlive() && failure[0] == null, "worker observes cancelled");
        check(stream.closes == 1 && engine.closes == 1, "worker releases stream before model");
    }

    private static void chunkBoundaryTail() throws Exception {
        // Exact pinned encoder readiness: strict processed+T < available, T=77,
        // 64-frame advance. Test every possible remainder of a final chunk.
        Engine engine = new Engine();
        try (SherpaSpeechRecognizer model = new SherpaSpeechRecognizer(engine)) {
            for (int remainder = 0; remainder < 64; remainder++) {
                final int realFrames = 640 + remainder;
                final long[] samples = {0}; final int[] processed = {0};
                engine.next = new Stream() {
                    @Override public void accept(float[] values) { samples[0] += values.length; }
                    @Override public boolean ready() { return processed[0] + 77 < samples[0] / 160; }
                    @Override public void decode() { processed[0] += 64; }
                };
                try (SherpaSpeechRecognizer.Session session = model.newSession()) {
                    byte[] pcm = new byte[realFrames * 160 * 2];
                    session.acceptPcm16(pcm, 0, pcm.length); session.finish();
                    check(processed[0] >= realFrames, "final real feature frames decoded remainder " + remainder);
                    check(session.pcmBytes() == pcm.length, "tail excluded remainder " + remainder);
                }
            }
        }
    }

    private static void passiveEndpointObservation() throws Exception {
        Engine engine = new Engine();
        try (SherpaSpeechRecognizer model = new SherpaSpeechRecognizer(engine)) {
            try (SherpaSpeechRecognizer.Session session = model.newSession()) {
                session.acceptPcm16(new byte[2], 0, 2);
                Stream stream = engine.streams.get(0); stream.endpoint = true;
                check(!session.endpointReady() && stream.endpointCalls == 0, "default observation disabled without native call");
            }
        }
        engine = new Engine();
        try (SherpaSpeechRecognizer model = new SherpaSpeechRecognizer(engine, true)) {
            SherpaSpeechRecognizer.Session session = model.newSession();
            Stream stream = engine.streams.get(0); stream.endpoint = true;
            check(!session.endpointReady() && stream.endpointCalls == 0, "empty stream cannot endpoint");
            session.acceptPcm16(new byte[2], 0, 2);
            stream.result = " \n\t ";
            check(!session.endpointReady(), "native endpoint requires nonempty text");
            stream.result = "  пауза "; stream.endpoint = false;
            check(!session.endpointReady(), "text alone cannot endpoint");
            stream.endpoint = true;
            check(session.endpointReady(), "nonempty text plus native endpoint observed");
            check(!stream.finalInput && stream.closes == 0 && stream.accepts == 1, "observation does not finalize reset or add padding");
            stream.failEndpoint = true;
            check(!session.endpointReady(), "passive diagnostic native failure stays false");
            stream.failEndpoint = false;
            check(session.finish().equals("пауза"), "final transcript still owned by matching end");
            int calls = stream.endpointCalls;
            check(!session.endpointReady() && stream.endpointCalls == calls, "closed session never touches JNI endpoint");
            try (SherpaSpeechRecognizer.Session next = model.newSession()) {
                next.acceptPcm16(new byte[2], 0, 2); next.cancel();
                check(!next.endpointReady() && engine.streams.get(1).endpointCalls == 0, "cancelled session never touches JNI endpoint");
            }
        }
    }

    private static void transportPreviewOnly() throws Exception {
        Engine engine = new Engine();
        try (SherpaSpeechRecognizer model = new SherpaSpeechRecognizer(engine)) {
            SherpaSpeechRecognizer.Session session = model.newSession();
            Stream stream = engine.streams.get(0);
            check(session.previewText().isEmpty() && stream.textCalls == 0, "empty input has no preview or native result call");
            session.acceptPcm16(new byte[2], 0, 2);
            int decodes = stream.decode;
            stream.result = "  включи\n  ";
            check(session.previewText().equals("включи"), "bounded normalized intermediate hypothesis");
            stream.result = "  включи  музыку ";
            check(session.previewText().equals("включи музыку"), "later hypothesis may extend and replace earlier preview");
            check(stream.decode == decodes && stream.accepts == 1 && !stream.finalInput && stream.closes == 0,
                    "preview adds no decoding padding finalization reset or release");
            stream.result = " \t\n "; check(session.previewText().isEmpty(), "whitespace preview excluded");
            stream.result = new String(new char[4096]).replace('\0', ' ');
            check(session.previewText().isEmpty(), "maximum source-sized whitespace preview excluded");
            stream.result = null; check(session.previewText().isEmpty(), "null result excluded");
            stream.result = new String(new char[1024]).replace('\0', 'а');
            check(session.previewText().length() == 1024, "exact maximum normalized preview accepted");
            stream.result = new String(new char[1025]).replace('\0', 'а');
            check(session.previewText().isEmpty(), "oversized normalized hypothesis excluded");
            stream.result = new String(new char[4097]).replace('\0', ' ');
            check(session.previewText().isEmpty(), "source result bounded before normalization");
            stream.failText = true; check(session.previewText().isEmpty(), "native result failure contains no diagnostic or speech");
            stream.failText = false; stream.result = "пауза";
            check(session.finish().equals("пауза"), "final text still obtained only after normal finish");
            int calls = stream.textCalls;
            check(session.previewText().isEmpty() && stream.textCalls == calls, "closed preview does not call JNI");
            try (SherpaSpeechRecognizer.Session next = model.newSession()) {
                next.acceptPcm16(new byte[2], 0, 2); next.cancel();
                Stream cancelled = engine.streams.get(1);
                check(next.previewText().isEmpty() && cancelled.textCalls == 0, "cancelled preview does not call JNI");
            }
        }
    }
}
