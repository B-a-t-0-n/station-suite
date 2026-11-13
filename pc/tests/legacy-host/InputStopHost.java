package local.station.voicebridge;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import org.json.JSONObject;

public final class InputStopHost {
    private static final ArrayList<LoopbackVoiceServer.ReplyWriter> writers = new ArrayList<>();
    private static final ArrayList<LegacyVoiceSession.Metadata> metadata = new ArrayList<>();
    private static int callbacks, bytes;
    public static void main(String[] args) throws Exception {
        LoopbackVoiceServer server = LoopbackVoiceServer.runtime((audio, meta, writer) -> {
            synchronized (writers) { callbacks++; bytes += audio.length; }
            if (args.length != 0 && args[0].equals("quiet-final")) {
                try { writer.completeWithoutSpeech(meta, (String) null); throw new AssertionError("null text"); }
                catch (java.io.IOException expected) { }
                char[] tooLong = new char[16385]; java.util.Arrays.fill(tooLong, 'x');
                try { writer.completeWithoutSpeech(meta, new String(tooLong)); throw new AssertionError("large text"); }
                catch (java.io.IOException expected) { }
                writer.completeWithoutSpeech(meta, "Сделай тише");
            } else writer.completeWithoutSpeech(meta);
            writer.close();
        }, new LoopbackVoiceServer.ProgressiveInput() {
            public boolean offerStart(LegacyVoiceSession.Metadata meta, LoopbackVoiceServer.ReplyWriter writer) {
                synchronized (writers) { writers.add(writer); metadata.add(meta); } return true;
            }
            public boolean offerChunk(LoopbackVoiceServer.ReplyWriter writer, byte[] audio) { return true; }
            public boolean offerEnd(LegacyVoiceSession.Metadata meta, LoopbackVoiceServer.ReplyWriter writer) { return true; }
            public void cancel(LoopbackVoiceServer.ReplyWriter writer, LoopbackVoiceServer.CancelReason reason) { }
        });
        server.start(); System.out.println(server.status());
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, "UTF-8")); String line;
        while ((line = input.readLine()) != null && !line.equals("quit")) {
            boolean requested = false; long elapsed = 0;
            synchronized (writers) {
                if (line.startsWith("request-")) {
                    int index = Integer.parseInt(line.substring(8)); long started = System.nanoTime();
                    requested = writers.get(index).requestInputStop(metadata.get(index));
                    elapsed = System.nanoTime() - started;
                }
                if (line.startsWith("partial-")) {
                    int colon = line.indexOf(':'); int index = Integer.parseInt(line.substring(8, colon));
                    long started = System.nanoTime();
                    requested = writers.get(index).requestPartialAsr(metadata.get(index), line.substring(colon + 1));
                    elapsed = System.nanoTime() - started;
                }
                JSONObject status = server.status().put("fixtureCallbacks", callbacks).put("fixtureBytes", bytes)
                        .put("fixtureStarts", writers.size()).put("fixtureRequested", requested).put("fixtureRequestNanos", elapsed);
                System.out.println(status);
            }
        }
        server.stop();
    }
}
