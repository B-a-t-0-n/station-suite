package local.station.voicebridge;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Bounded synthetic observer queue. It never decodes speech, performs I/O, or waits in an offer. */
public final class ProgressiveInputHost {
    private static final class Turn {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean ended;
    }
    private static final class Observer implements LoopbackVoiceServer.ProgressiveInput {
        final String mode;
        final IdentityHashMap<LoopbackVoiceServer.ReplyWriter, Turn> pending = new IdentityHashMap<>();
        int starts, chunks, ends, cancellations, superseded, closed, empty;
        long copiedBytes;
        Observer(String mode) { this.mode = mode; }
        public synchronized boolean offerStart(LegacyVoiceSession.Metadata metadata, LoopbackVoiceServer.ReplyWriter writer) {
            starts++; pending.put(writer, new Turn());
            if (mode.equals("throw-start")) throw new IllegalStateException("synthetic private failure");
            return !mode.equals("false-start");
        }
        public synchronized boolean offerChunk(LoopbackVoiceServer.ReplyWriter writer, byte[] audio) {
            chunks++;
            if (mode.equals("throw-chunk")) throw new IllegalStateException("synthetic private failure");
            if (mode.equals("false-chunk")) return false;
            Turn turn = pending.get(writer);
            if (turn == null || audio.length > LegacyVoiceSession.MAX_COMMAND_BYTES - turn.bytes.size()) return false;
            turn.bytes.write(audio, 0, audio.length); copiedBytes += audio.length;
            if (mode.equals("mutate")) Arrays.fill(audio, (byte) 0);
            return true;
        }
        public synchronized boolean offerEnd(LegacyVoiceSession.Metadata metadata, LoopbackVoiceServer.ReplyWriter writer) {
            ends++;
            if (mode.equals("throw-end")) throw new IllegalStateException("synthetic private failure");
            if (mode.equals("false-end")) return false;
            Turn turn = pending.get(writer);
            if (turn == null || metadata.audioBytes != turn.bytes.size()) return false;
            turn.ended = true; return true;
        }
        public synchronized void cancel(LoopbackVoiceServer.ReplyWriter writer, LoopbackVoiceServer.CancelReason reason) {
            cancellations++; pending.remove(writer);
            if (reason == LoopbackVoiceServer.CancelReason.SUPERSEDED) superseded++;
            if (reason == LoopbackVoiceServer.CancelReason.CONNECTION_CLOSED) closed++;
            if (reason == LoopbackVoiceServer.CancelReason.EMPTY_COMMAND) empty++;
            if (mode.equals("throw-cancel")) throw new IllegalStateException("synthetic private failure");
        }
        synchronized boolean ownsEnded(LoopbackVoiceServer.ReplyWriter writer) {
            Turn turn = pending.get(writer); return turn != null && turn.ended;
        }
        synchronized JSONObject status() throws Exception {
            int retained = 0; for (Turn turn : pending.values()) retained += turn.bytes.size();
            return new JSONObject().put("fixtureObserverStarts", starts).put("fixtureObserverChunks", chunks)
                    .put("fixtureObserverEnds", ends).put("fixtureObserverBytes", copiedBytes)
                    .put("fixtureObserverCancellations", cancellations).put("fixturePending", pending.size())
                    .put("fixtureRetainedBytes", retained).put("fixtureSuperseded", superseded)
                    .put("fixturePeerClosed", closed).put("fixtureEmpty", empty);
        }
    }
    public static void main(String[] args) throws Exception {
        byte[] ogg = Files.readAllBytes(Paths.get(args[1])); Observer observer = new Observer(args[0]);
        AtomicInteger callbacks = new AtomicInteger(), complete = new AtomicInteger(), owned = new AtomicInteger();
        AtomicInteger stale = new AtomicInteger(), badPrimary = new AtomicInteger(); CountDownLatch release = new CountDownLatch(1);
        LoopbackVoiceServer server = LoopbackVoiceServer.runtime((audio, metadata, writer) -> {
            int number = callbacks.incrementAndGet();
            if (observer.ownsEnded(writer)) owned.incrementAndGet();
            if (!Arrays.equals(audio, "SYNTHETIC-COMMAND".getBytes(StandardCharsets.US_ASCII))) badPrimary.incrementAndGet();
            Thread worker = new Thread(() -> {
                try {
                    if (args[0].equals("hold") && number == 1 && !release.await(8, TimeUnit.SECONDS))
                        throw new AssertionError("synthetic wait expired");
                    writer.reply(metadata, "Сколько будет два плюс два?", "Два плюс два равно четырём.", ogg);
                } catch (IOException expected) {
                    if (!writer.isOpen()) stale.incrementAndGet(); else throw new AssertionError("unexpected write");
                } catch (InterruptedException unexpected) { throw new AssertionError(unexpected); }
                finally { writer.close(); complete.incrementAndGet(); }
            }, "ArtificialProgressivePrimary");
            worker.setDaemon(true); worker.start();
        }, observer);
        server.start(); System.out.println(server.status().toString());
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, "UTF-8")); String line;
        while ((line = input.readLine()) != null) {
            if (line.equals("quit")) break;
            if (line.equals("release")) release.countDown();
            if (line.equals("stop")) server.stop();
            JSONObject status = server.status(); JSONObject extra = observer.status();
            java.util.Iterator<String> keys = extra.keys();
            while (keys.hasNext()) { String key = keys.next(); status.put(key, extra.get(key)); }
            status.put("fixtureCallbacks", callbacks.get()).put("fixtureCompleted", complete.get())
                    .put("fixturePrimaryOwned", owned.get()).put("fixtureBadPrimary", badPrimary.get())
                    .put("fixtureStaleRejected", stale.get());
            System.out.println(status.toString());
        }
        server.stop(); release.countDown();
    }
}
