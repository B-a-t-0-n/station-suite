package local.station.voicebridge;

import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory fake providers plus actual loopback transport. Never included in an APK. */
public final class LegacyPipelineHandlerHost {
    private static final String PRIVATE = "synthetic-private-marker";
    private static final class Fake implements VoicePipeline.Backend {
        final byte[] output;
        final boolean blocked;
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger validations = new AtomicInteger(), transcriptions = new AtomicInteger();
        final AtomicInteger answers = new AtomicInteger(), syntheses = new AtomicInteger();
        Fake(byte[] output, boolean blocked) { this.output = output; this.blocked = blocked; }
        public void validate(JSONObject config) throws Exception {
            validations.incrementAndGet();
            if (!PRIVATE.equals(config.getString("private"))) throw new AssertionError("snapshot mutated");
        }
        public JSONObject transcribe(JSONObject config, byte[] audio) throws Exception {
            transcriptions.incrementAndGet();
            if (blocked && !release.await(10, TimeUnit.SECONDS)) throw new AssertionError("fixture wait timed out");
            return new JSONObject().put("ok", true).put("text", "Сколько будет два плюс два?")
                    .put("fileDeleted", true).put("unknown", PRIVATE);
        }
        public JSONObject ask(JSONObject config, String prompt) throws Exception {
            answers.incrementAndGet();
            return new JSONObject().put("ok", true).put("text", "Два плюс два равно четырём.");
        }
        public VoicePipeline.Speech synthesize(JSONObject config, String text) {
            syntheses.incrementAndGet(); return new VoicePipeline.Speech(output, 27, "ogg");
        }
        JSONObject counters() throws Exception {
            return new JSONObject().put("validations", validations.get()).put("asr", transcriptions.get())
                    .put("llm", answers.get()).put("tts", syntheses.get());
        }
    }
    public static void main(String[] args) throws Exception {
        byte[] ogg = Files.readAllBytes(Paths.get(args[1]));
        boolean block = args[0].equals("block");
        final Fake fake = new Fake(ogg, block), fake2 = new Fake(ogg, false);
        final AtomicInteger finished = new AtomicInteger(), finished2 = new AtomicInteger(), callbacks = new AtomicInteger();
        JSONObject settings = new JSONObject().put("private", PRIVATE);
        LegacyPipelineHandler handler = new LegacyPipelineHandler(
                new VoicePipeline(fake, () -> System.nanoTime() / 1000000L), settings, finished::incrementAndGet);
        LegacyPipelineHandler second = new LegacyPipelineHandler(
                new VoicePipeline(fake2, () -> System.nanoTime() / 1000000L), settings, finished2::incrementAndGet);
        settings.put("private", "mutated-after-construction");
        LoopbackVoiceServer server = new LoopbackVoiceServer(30000, (audio, meta, writer) -> {
            int number = callbacks.incrementAndGet();
            (block && number > 1 ? second : handler).commandReady(audio, meta, writer);
        });
        server.start();
        System.out.println(server.status().toString());
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
        String line;
        while ((line = input.readLine()) != null) {
            if (line.equals("quit")) break;
            if (line.equals("close")) { server.stop(); handler.close(); second.close(); }
            if (line.equals("release")) fake.release.countDown();
            JSONObject status = new JSONObject().put("handler", handler.snapshot()).put("second", second.snapshot())
                    .put("finished", finished.get()).put("finished2", finished2.get())
                    .put("providers", fake.counters()).put("providers2", fake2.counters());
            System.out.println(status.toString());
        }
        server.stop(); handler.close(); second.close(); fake.release.countDown();
    }
}
