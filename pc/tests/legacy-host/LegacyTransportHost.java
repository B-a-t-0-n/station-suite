package local.station.voicebridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Host-only artificial fixture. Never packaged in the Android application. */
public final class LegacyTransportHost {
    private static void rejects(Runnable action) {
        try { action.run(); throw new AssertionError("invalid factory accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    private static void factoryChecks() throws Exception {
        LoopbackVoiceServer.CommandHandler handler = (audio, metadata, writer) -> { };
        rejects(() -> LoopbackVoiceServer.voiceWindow(1, null));
        rejects(() -> LoopbackVoiceServer.voiceWindow(0, handler));
        rejects(() -> LoopbackVoiceServer.voiceWindow(300001, handler));
        rejects(() -> new LoopbackVoiceServer(120001));
        rejects(() -> new LoopbackVoiceServer(120001, handler));
        rejects(() -> LoopbackVoiceServer.runtime(null));
        if (LoopbackVoiceServer.MAX_LEASE_MILLIS != 120000L) throw new AssertionError("old cap changed");
        JSONObject manual = LoopbackVoiceServer.voiceWindow(300000, handler).status();
        JSONObject old = new LoopbackVoiceServer(120000, handler).status();
        JSONObject runtime = LoopbackVoiceServer.runtime(handler).status();
        if (!manual.getBoolean("manualVoiceWindow") || manual.getLong("maxLeaseMillis") != 300000
                || manual.getLong("leaseMillis") != 300000 || manual.getBoolean("active")
                || old.getBoolean("manualVoiceWindow") || old.getLong("maxLeaseMillis") != 120000
                || !runtime.getBoolean("permanentVoiceRuntime") || runtime.getLong("leaseMillis") != 0
                || runtime.getBoolean("active") || runtime.getLong("maxSessionMillis") != 120000)
            throw new AssertionError("factory status or no-autostart contract");
        System.out.println("PASS manual voice window bounds, required handler, old caps, no autostart");
    }
    public static void main(String[] args) throws Exception {
        if (args[0].equals("factorychecks")) { factoryChecks(); return; }
        final byte[] ogg = Files.readAllBytes(Paths.get(args[1]));
        final AtomicInteger callbacks = new AtomicInteger(), duplicatesRejected = new AtomicInteger();
        final boolean corrupt = args[0].equals("corrupt");
        if (corrupt) ogg[ogg.length - 1] ^= 1;
        LoopbackVoiceServer.CommandHandler handler = (audio, metadata, writer) -> {
            callbacks.incrementAndGet();
            if (!Arrays.equals(audio, "SYNTHETIC-COMMAND".getBytes("UTF-8"))
                    || metadata.audioBytes != audio.length) throw new AssertionError("wrong synthetic command");
            writer.reply(metadata, "Сколько будет два плюс два?", "Два плюс два равно четырём.", ogg);
            try {
                writer.reply(metadata, "Сколько будет два плюс два?", "Два плюс два равно четырём.", ogg);
                throw new AssertionError("duplicate reply accepted");
            } catch (IOException expected) {
                if (!"reply_already_sent".equals(expected.getMessage())) throw expected;
                duplicatesRejected.incrementAndGet();
            }
        };
        LoopbackVoiceServer server = args[0].equals("manual")
                ? LoopbackVoiceServer.voiceWindow(Long.parseLong(args[2]), handler)
                : new LoopbackVoiceServer(Long.parseLong(args[2]), args[0].equals("canary") ? null : handler);
        server.start();
        System.out.println(server.status().toString());
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
        String line;
        while ((line = input.readLine()) != null) {
            if (line.equals("quit")) break;
            if (line.equals("restart")) { server.stop(); server.start(); }
            if (line.equals("contender")) {
                LoopbackVoiceServer contender = new LoopbackVoiceServer(1000, (LoopbackVoiceServer.CommandHandler) null);
                contender.start();
                JSONObject status = contender.status();
                contender.stop();
                System.out.println(status.toString());
                continue;
            }
            JSONObject status = server.status().put("fixtureCallbacks", callbacks.get())
                    .put("fixtureDuplicatesRejected", duplicatesRejected.get());
            System.out.println(status.toString());
        }
        server.stop();
    }
}
