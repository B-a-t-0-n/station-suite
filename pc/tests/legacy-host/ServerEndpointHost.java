package local.station.voicebridge;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic queue/socket fixture only; no recognition, command execution or cloud clients. */
public final class ServerEndpointHost {
    static final String TEXT = "Сделай тише", ANSWER = "Хорошо.";
    static final ArrayList<LoopbackVoiceServer.ReplyWriter> writers = new ArrayList<>();
    static final ArrayList<LegacyVoiceSession.Metadata> starts = new ArrayList<>(), ends = new ArrayList<>();
    static final ArrayList<ByteArrayOutputStream> chunks = new ArrayList<>();
    static int callbacks, endCalls, bytes; static boolean sameBytes = true;
    public static void main(String[] args) throws Exception {
        final byte[] ogg = Files.readAllBytes(Paths.get(args[1]));
        LoopbackVoiceServer server = LoopbackVoiceServer.runtime((audio, meta, writer) -> {
            synchronized (writers) {
                int i = writers.indexOf(writer); callbacks++; bytes += audio.length;
                sameBytes &= Arrays.equals(audio, chunks.get(i).toByteArray());
                sameBytes &= ends.get(i) == meta;
            }
        }, new LoopbackVoiceServer.ProgressiveInput() {
            public boolean offerStart(LegacyVoiceSession.Metadata meta, LoopbackVoiceServer.ReplyWriter writer) {
                synchronized (writers) { writers.add(writer); starts.add(meta); ends.add(null); chunks.add(new ByteArrayOutputStream()); }
                return true;
            }
            public boolean offerChunk(LoopbackVoiceServer.ReplyWriter writer, byte[] audio) {
                synchronized (writers) { chunks.get(writers.indexOf(writer)).write(audio, 0, audio.length); } return true;
            }
            public boolean offerEnd(LegacyVoiceSession.Metadata meta, LoopbackVoiceServer.ReplyWriter writer) {
                synchronized (writers) {
                    ends.set(writers.indexOf(writer), meta); endCalls++;
                    if (args[0].equals("fast")) try { writer.finishInput(meta, TEXT); }
                    catch (IOException failure) { throw new AssertionError("metadata not bound before observer end"); }
                }
                return true;
            }
            public void cancel(LoopbackVoiceServer.ReplyWriter writer, LoopbackVoiceServer.CancelReason reason) { }
        });
        server.start(); System.out.println(server.status());
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, "UTF-8")); String line;
        while ((line = input.readLine()) != null && !line.equals("quit")) {
            String code = ""; boolean accepted = false; long elapsed = 0;
            synchronized (writers) {
                if (!line.equals("status")) {
                    int split = line.indexOf('-'); String op = line.substring(0, split); int i = Integer.parseInt(line.substring(split + 1));
                    LoopbackVoiceServer.ReplyWriter writer = writers.get(i);
                    try {
                        long started = System.nanoTime();
                        if (op.equals("endpoint")) accepted = writer.requestServerEndpoint(starts.get(i));
                        else if (op.equals("premature")) writer.finishInput(starts.get(i), TEXT);
                        else if (op.equals("empty")) writer.finishInput(ends.get(i), " ");
                        else if (op.equals("finish")) writer.finishInput(ends.get(i), TEXT);
                        else if (op.equals("quiet")) writer.completeWithoutSpeech(ends.get(i));
                        else if (op.equals("reply")) writer.reply(ends.get(i), TEXT, ANSWER, ogg);
                        else if (op.equals("stream")) {
                            writer.beginStreaming(ends.get(i), TEXT, ANSWER);
                            for (int off = 0; off < ogg.length;) {
                                int segments = ogg[off + 26] & 255, count = 27 + segments;
                                for (int j = 0; j < segments; j++) count += ogg[off + 27 + j] & 255;
                                writer.writeOggPage(ogg, off, count); off += count;
                            }
                            writer.finishStreaming();
                        } else if (op.equals("close")) writer.close();
                        else if (op.equals("abort")) writer.abortInputFinalization(ends.get(i));
                        else throw new AssertionError("fixture operation");
                        elapsed = System.nanoTime() - started;
                    } catch (IOException expected) { code = expected.getMessage(); }
                }
                JSONArray reasons = new JSONArray(), open = new JSONArray();
                for (int i = 0; i < writers.size(); i++) {
                    reasons.put(ends.get(i) == null ? -99 : ends.get(i).endReason); open.put(writers.get(i).isOpen());
                }
                System.out.println(server.status().put("fixtureStarts", writers.size()).put("fixtureEnds", endCalls)
                        .put("fixtureCallbacks", callbacks).put("fixtureBytes", bytes).put("fixtureSameBytes", sameBytes)
                        .put("fixtureAccepted", accepted).put("fixtureRequestNanos", elapsed).put("fixtureCode", code)
                        .put("fixtureReasons", reasons).put("fixtureOpen", open));
            }
        }
        server.stop();
    }
}
