package local.station.voicebridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import org.json.JSONObject;

/** Artificial async handler. It never constructs cloud clients or reads private configuration. */
public final class RuntimeTransportHost {
    public static void main(String[] args) throws Exception {
        final byte[] ogg = Files.readAllBytes(Paths.get(args[1]));
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch releaseSecond = new CountDownLatch(1);
        final AtomicInteger callbacks = new AtomicInteger(), completed = new AtomicInteger(), stale = new AtomicInteger();
        final AtomicInteger streamStage = new AtomicInteger();
        final ArrayList<LoopbackVoiceServer.ReplyWriter> writers = new ArrayList<>();
        LoopbackVoiceServer server = LoopbackVoiceServer.runtime((audio, metadata, writer) -> {
            int number = callbacks.incrementAndGet();
            synchronized (writers) { writers.add(writer); }
            if (args[0].equals("error") && number == 1) {
                writer.completeWithoutSpeech(metadata); writer.close(); completed.incrementAndGet(); return;
            }
            Thread worker = new Thread(() -> {
                try {
                    if ((args[0].equals("blocked") || args[0].equals("two-blocked")) && number == 1
                            && !release.await(8, TimeUnit.SECONDS))
                        throw new AssertionError("artificial worker timeout");
                    if (args[0].equals("two-blocked") && number == 2 && !releaseSecond.await(8, TimeUnit.SECONDS))
                        throw new AssertionError("artificial second worker timeout");
                    if (args[0].startsWith("stream")) {
                        writer.beginStreaming(metadata, "Сколько будет два плюс два?", "Два плюс два равно четырём.");
                        int index = 0;
                        for (int offset = 0; offset < ogg.length; index++) {
                            int segments = ogg[offset + 26] & 255, count = 27 + segments;
                            for (int i = 0; i < segments; i++) count += ogg[offset + 27 + i] & 255;
                            if (args[0].equals("stream-delayed") && number == 1 && index == 2) {
                                streamStage.set(1);
                                if (!release.await(8, TimeUnit.SECONDS)) throw new AssertionError("header delay timeout");
                            }
                            byte[] page = java.util.Arrays.copyOfRange(ogg, offset, offset + count);
                            if (args[0].equals("stream-corrupt") && number == 1 && index == 3) page[page.length - 1] ^= 1;
                            writer.writeOggPage(page, 0, page.length); offset += count;
                            if ((args[0].equals("stream-cancel") || args[0].equals("stream-abort")) && number == 1 && index == 2) {
                                streamStage.set(1);
                                if (!release.await(8, TimeUnit.SECONDS)) throw new AssertionError("partial delay timeout");
                                if (args[0].equals("stream-abort")) { writer.abortStreaming(); return; }
                            }
                        }
                        if (args[0].equals("stream-delayed") && number == 1) {
                            streamStage.set(2);
                            if (!releaseSecond.await(8, TimeUnit.SECONDS)) throw new AssertionError("EOS delay timeout");
                        }
                        writer.finishStreaming(); return;
                    }
                    // Deliberately call reply even when stale: the actual writer must prevent delivery.
                    writer.reply(metadata, "Сколько будет два плюс два?", "Два плюс два равно четырём.", ogg);
                } catch (IOException expected) {
                    if (!writer.isOpen()) stale.incrementAndGet();
                    else throw new AssertionError("unexpected write failure");
                } catch (InterruptedException unexpected) { throw new AssertionError(unexpected); }
                finally { writer.close(); completed.incrementAndGet(); }
            }, "ArtificialAsyncReply");
            worker.setDaemon(true); worker.start();
        });
        server.start(); System.out.println(server.status().toString());
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
        String line;
        while ((line = input.readLine()) != null) {
            if (line.equals("quit")) break;
            if (line.equals("release")) release.countDown();
            if (line.equals("release-second")) releaseSecond.countDown();
            if (line.equals("expire-first")) {
                java.lang.reflect.Method expire = LoopbackVoiceServer.ReplyWriter.class.getDeclaredMethod("expire");
                expire.setAccessible(true);
                synchronized (writers) { expire.invoke(writers.get(0)); }
            }
            JSONObject status = server.status().put("fixtureCallbacks", callbacks.get())
                    .put("fixtureCompleted", completed.get()).put("fixtureStaleRejected", stale.get())
                    .put("fixtureStreamStage", streamStage.get());
            System.out.println(status.toString());
        }
        server.stop(); release.countDown(); releaseSecond.countDown();
    }
}
