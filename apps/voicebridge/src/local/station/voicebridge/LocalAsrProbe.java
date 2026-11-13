package local.station.voicebridge;

import android.content.Context;
import android.os.Debug;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic bundled-fixture check only: no microphone, playback, API or commands. */
public final class LocalAsrProbe {
    private static final String EXPECTED = "сколько будет два плюс два";

    private LocalAsrProbe() { }

    /** Must run on the probe worker. Results contain no recognized speech text. */
    public static JSONObject run(Context context) {
        JSONObject result = new JSONObject();
        JSONArray passes = new JSONArray();
        put(result, "syntheticFixture", true);
        put(result, "microphone", false);
        put(result, "audioPlayed", false);
        put(result, "networkRequests", 0);
        put(result, "controlActions", 0);
        put(result, "passes", passes);
        String phase = "MODEL_LOAD";
        long totalStarted = System.nanoTime();
        try (LocalSpeechRecognizer model = new LocalSpeechRecognizer(
                new File(context.getFilesDir(), "local-asr-model"))) {
            put(result, "modelLoadMillis", model.loadMillis());
            memory(result, "warmModel");
            phase = "OGG_PASS";
            boolean matched = true;
            for (int i = 0; i < 2; i++) {
                JSONObject pass = ogg(context, model, i + 1, i == 0 ? 257 : 1024);
                passes.put(pass);
                matched &= pass.optBoolean("matchesExpected", false);
            }
            memory(result, "afterPasses");
            // The bundled source WAV is 22050 Hz; only the Opus decoder converts
            // this fixture to the recognizer's strict 16000 Hz input contract.
            put(result, "wavComparisonSkipped", "SOURCE_WAV_22050_HZ");
            put(result, "ok", matched);
            if (!matched) put(result, "reason", "FIXTURE_MISMATCH");
        } catch (LocalSpeechRecognizer.RecognitionException error) {
            put(result, "ok", false);
            put(result, "reason", error.code);
            put(result, "phase", phase);
        } catch (Exception | LinkageError error) {
            put(result, "ok", false);
            put(result, "reason", "LOCAL_ASR_PROBE_FAILED");
            put(result, "phase", phase);
        }
        put(result, "elapsedMillis", elapsed(totalStarted));
        return result;
    }

    private static JSONObject ogg(Context context, LocalSpeechRecognizer model,
            int passNumber, int chunkBytes) throws Exception {
        long started = System.nanoTime();
        long sessionStarted = System.nanoTime();
        try (LocalSpeechRecognizer.Session session = model.newSession()) {
            long sessionOpenMillis = elapsed(sessionStarted);
            AndroidOpusDecoder decoder = new AndroidOpusDecoder(new AndroidOpusDecoder.PcmSink() {
                @Override public void accept(byte[] pcm, int offset, int count) throws Exception {
                    session.acceptPcm16(pcm, offset, count);
                }
                @Override public boolean cancelled() { return Thread.currentThread().isInterrupted(); }
            });
            try (StreamingOpusInput input = new StreamingOpusInput(decoder);
                    InputStream asset = context.getAssets().open("pipeline_fixture.ogg")) {
                byte[] buffer = new byte[chunkBytes];
                int count;
                try {
                    while ((count = asset.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("CANCELLED");
                        if (count != 0) input.accept(buffer, 0, count);
                    }
                    input.finish();
                    long finishStarted = System.nanoTime();
                    String text = session.finish();
                    long finishMillis = elapsed(finishStarted);
                    JSONObject result = metrics(session, text, started);
                    put(result, "pass", passNumber);
                    put(result, "container", "ogg-opus");
                    put(result, "inputChunkBytes", chunkBytes);
                    put(result, "inputBytes", input.inputBytes());
                    put(result, "sessionOpenMillis", sessionOpenMillis);
                    put(result, "finishMillis", finishMillis);
                    memory(result, "warmPass");
                    return result;
                } finally { Arrays.fill(buffer, (byte) 0); }
            }
        }
    }

    private static JSONObject metrics(LocalSpeechRecognizer.Session session,
            String text, long started) {
        JSONObject result = new JSONObject();
        put(result, "matchesExpected", EXPECTED.equals(normalize(text)));
        put(result, "recognizedChars", text.length());
        put(result, "pcmBytes", session.pcmBytes());
        put(result, "decodeMillis", session.decodeMillis());
        put(result, "elapsedMillis", elapsed(started));
        return result;
    }

    private static String normalize(String text) {
        String source = text.toLowerCase(Locale.ROOT).replace('ё', 'е');
        StringBuilder output = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < source.length(); i++) {
            char value = source.charAt(i);
            if (Character.isLetterOrDigit(value)) {
                if (space) output.append(' ');
                output.append(value); space = false;
            } else if (output.length() != 0) space = true;
        }
        return output.toString();
    }

    private static long elapsed(long started) { return (System.nanoTime() - started) / 1000000L; }
    private static void memory(JSONObject result, String prefix) {
        try {
            Debug.MemoryInfo info = new Debug.MemoryInfo();
            Debug.getMemoryInfo(info);
            put(result, prefix + "PssKb", info.getTotalPss());
            put(result, prefix + "PrivateDirtyKb", info.getTotalPrivateDirty());
        } catch (RuntimeException | LinkageError ignored) {
            // Optional process-memory observations do not decide ASR success.
        }
    }
    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); } catch (Exception ignored) { }
    }
}
