package local.station.voicebridge;

import android.content.Context;
import android.os.Debug;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic bundled fixtures only. No microphone, playback, cloud, or actions. */
public final class SherpaAsrProbe {
    private static final String EXPECTED = "сколько будет два плюс два";
    private SherpaAsrProbe() { }

    /** Sequential CPU 1/2/4-thread comparison; at most one model is resident. */
    public static JSONObject run(Context context) { return run(context, 0); }

    /** threads=0 compares all; 1/2/4 compares that setting then 12 quality fixtures. */
    public static JSONObject run(Context context, int threads) {
        JSONObject result = new JSONObject();
        JSONArray configurations = new JSONArray();
        put(result, "syntheticFixture", true);
        put(result, "microphone", false);
        put(result, "audioPlayed", false);
        put(result, "networkRequests", 0);
        put(result, "controlActions", 0);
        put(result, "engine", "sherpa-onnx-zipformer2-int8-ru");
        put(result, "endpointObservation", true);
        put(result, "configurations", configurations);
        put(result, "pssBeforeKiB", pss());
        long totalStarted = System.nanoTime();
        byte[] baseline = null;
        String phase = "FIXTURE_DECODE";
        try {
            if (threads != 0 && threads != 1 && threads != 2 && threads != 4)
                throw new IOException("THREAD_COUNT");
            baseline = decodeFixture(context, "pipeline_fixture.ogg");
            int bestThreads = 1;
            long bestMillis = Long.MAX_VALUE;
            boolean matches = true;
            for (int count : threads == 0 ? new int[] {1, 2, 4} : new int[] {threads}) {
                phase = "MODEL_LOAD";
                try (SherpaSpeechRecognizer model = open(context, count)) {
                    JSONObject configuration = new JSONObject();
                    JSONArray passes = new JSONArray();
                    put(configuration, "threads", count);
                    put(configuration, "modelLoadMillis", model.loadMillis());
                    put(configuration, "pssLoadedKiB", pss());
                    put(configuration, "passes", passes);
                    configurations.put(configuration);
                    phase = "BENCHMARK";
                    for (int pass = 1; pass <= 2; pass++) {
                        JSONObject metrics = recognize(model, baseline, EXPECTED, pass == 1 ? 8192 : 2048);
                        put(metrics, "pass", pass);
                        passes.put(metrics);
                        matches &= metrics.optBoolean("matchesExpected", false);
                        long millis = metrics.optLong("decodeMillis", Long.MAX_VALUE);
                        if (pass == 2 && millis < bestMillis) { bestMillis = millis; bestThreads = count; }
                    }
                    put(configuration, "pssWarmKiB", pss());
                    put(configuration, "nativeHeapAllocatedBytes", Debug.getNativeHeapAllocatedSize());
                    if (threads != 0) {
                        phase = "QUALITY";
                        quality(context, model, result);
                    }
                }
            }
            put(result, "bestThreads", bestThreads);
            if (threads == 0) {
                phase = "QUALITY_MODEL_LOAD";
                try (SherpaSpeechRecognizer model = open(context, bestThreads)) {
                    phase = "QUALITY";
                    quality(context, model, result);
                }
            }
            put(result, "ok", true);
            put(result, "baselineMatchesExpected", matches);
        } catch (SherpaSpeechRecognizer.RecognitionException error) {
            put(result, "ok", false); put(result, "reason", error.code); put(result, "phase", phase);
        } catch (Exception | LinkageError error) {
            put(result, "ok", false); put(result, "reason", "SHERPA_PROBE_FAILED"); put(result, "phase", phase);
        } finally {
            if (baseline != null) Arrays.fill(baseline, (byte) 0);
        }
        put(result, "pssAfterKiB", pss());
        put(result, "elapsedMillis", elapsed(totalStarted));
        return result;
    }

    private static SherpaSpeechRecognizer open(Context context, int threads)
            throws SherpaSpeechRecognizer.RecognitionException {
        return new SherpaSpeechRecognizer(new File(context.getFilesDir(), "local-sherpa-model"), threads, true);
    }

    private static JSONObject recognize(SherpaSpeechRecognizer model, byte[] pcm,
            String expected, int chunkBytes) throws Exception {
        long started = System.nanoTime();
        try (SherpaSpeechRecognizer.Session session = model.newSession()) {
            for (int offset = 0; offset < pcm.length; offset += chunkBytes) {
                if (Thread.currentThread().isInterrupted()) { session.cancel(); throw new IOException("CANCELLED"); }
                session.acceptPcm16(pcm, offset, Math.min(chunkBytes, pcm.length - offset));
            }
            String text = normalize(session.finish());
            JSONObject result = new JSONObject();
            put(result, "matchesExpected", expected.equals(text));
            // This method is reachable only with bundled synthetic fixtures, never live input.
            if (!expected.equals(text)) put(result, "syntheticMismatchText", text);
            put(result, "recognizedChars", text.length());
            put(result, "wordEdits", wordEdits(expected, text));
            put(result, "expectedWords", expected.split(" ").length);
            put(result, "pcmBytes", session.pcmBytes());
            put(result, "audioMillis", session.pcmBytes() * 1000L / (16000 * 2));
            put(result, "decodeMillis", session.decodeMillis());
            put(result, "finishMillis", session.finishMillis());
            put(result, "decodeSteps", session.decodeSteps());
            put(result, "pcmChunkBytes", chunkBytes);
            put(result, "elapsedMillis", elapsed(started));
            long denominator = session.pcmBytes() * 1000L / (16000 * 2);
            put(result, "realTimeFactorMillionths", denominator == 0 ? 0
                    : session.decodeMillis() * 1000000L / denominator);
            return result;
        }
    }

    private static void quality(Context context, SherpaSpeechRecognizer model,
            JSONObject result) throws Exception {
        JSONArray phrases;
        try (InputStream stream = context.getAssets().open("local-asr-fixtures/manifest.json")) {
            byte[] bytes = readBounded(stream, 16384);
            phrases = new JSONObject(new String(bytes, "UTF-8")).getJSONArray("phrases");
        }
        if (phrases.length() != 12) throw new IOException("FIXTURE_MANIFEST");
        JSONArray passes = new JSONArray();
        int matches = 0, edits = 0, words = 0;
        put(result, "quality", passes);
        for (int i = 0; i < phrases.length(); i++) {
            JSONObject phrase = phrases.getJSONObject(i);
            String id = phrase.getString("id"), kind = phrase.getString("kind");
            String expected = normalize(phrase.getString("expected"));
            if (!id.matches("[0-9]{2}") || expected.length() > 256 || expected.length() == 0
                    || !("question".equals(kind) || "command".equals(kind) || "negative".equals(kind)))
                throw new IOException("FIXTURE_MANIFEST");
            byte[] pcm = decodeFixture(context, "local-asr-fixtures/" + id + ".ogg");
            try {
                JSONObject pass = recognize(model, pcm, expected, 2048);
                put(pass, "fixture", i + 1); put(pass, "kind", kind);
                passes.put(pass);
                if (pass.optBoolean("matchesExpected", false)) matches++;
                edits += pass.optInt("wordEdits", 0); words += pass.optInt("expectedWords", 0);
            } finally { Arrays.fill(pcm, (byte) 0); }
        }
        put(result, "qualityMatches", matches); put(result, "qualityTotal", phrases.length());
        put(result, "qualityWordEdits", edits); put(result, "qualityExpectedWords", words);
    }

    private static byte[] decodeFixture(Context context, String assetName) throws Exception {
        final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        AndroidOpusDecoder decoder = new AndroidOpusDecoder(new AndroidOpusDecoder.PcmSink() {
            @Override public void accept(byte[] bytes, int offset, int count) throws Exception {
                if (pcm.size() + (long) count > SherpaSpeechRecognizer.MAX_PCM_BYTES)
                    throw new IOException("FIXTURE_LIMIT");
                pcm.write(bytes, offset, count);
            }
            @Override public boolean cancelled() { return Thread.currentThread().isInterrupted(); }
        });
        try (StreamingOpusInput input = new StreamingOpusInput(decoder);
                InputStream asset = context.getAssets().open(assetName)) {
            byte[] buffer = new byte[1024];
            try {
                int count;
                while ((count = asset.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("CANCELLED");
                    if (count != 0) input.accept(buffer, 0, count);
                }
                input.finish();
                byte[] value = pcm.toByteArray();
                if (value.length == 0 || (value.length & 1) != 0) throw new IOException("FIXTURE_PCM");
                return value;
            } finally { Arrays.fill(buffer, (byte) 0); }
        }
    }

    private static byte[] readBounded(InputStream stream, int max) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024]; int count;
        while ((count = stream.read(buffer)) != -1) {
            if (output.size() + count > max) throw new IOException("FIXTURE_MANIFEST");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    static int wordEdits(String expected, String actual) {
        String[] left = expected.length() == 0 ? new String[0] : expected.split(" ");
        String[] right = actual.length() == 0 ? new String[0] : actual.split(" ");
        int[] previous = new int[right.length + 1], next = new int[right.length + 1];
        for (int j = 0; j <= right.length; j++) previous[j] = j;
        for (int i = 1; i <= left.length; i++) {
            next[0] = i;
            for (int j = 1; j <= right.length; j++) next[j] = Math.min(
                    Math.min(previous[j] + 1, next[j - 1] + 1),
                    previous[j - 1] + (left[i - 1].equals(right[j - 1]) ? 0 : 1));
            int[] swap = previous; previous = next; next = swap;
        }
        return previous[right.length];
    }

    static String normalize(String text) {
        String source = text.toLowerCase(Locale.ROOT).replace('ё', 'е');
        StringBuilder output = new StringBuilder(); boolean space = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (space) output.append(' ');
                output.append(c); space = false;
            } else if (output.length() != 0) space = true;
        }
        return output.toString();
    }
    private static long pss() { try { return Debug.getPss(); } catch (RuntimeException error) { return -1; } }
    private static long elapsed(long started) { return (System.nanoTime() - started) / 1000000L; }
    private static void put(JSONObject result, String key, Object value) {
        try { result.put(key, value); } catch (Exception ignored) { }
    }
}
