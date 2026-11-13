package local.station.voicebridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import org.json.JSONObject;

/** Public synthetic fixture only. No socket, microphone, cloud configuration or API. */
public final class StreamingReplyHostTest {
    private static int assertions;
    private static final class Sink implements LegacyVoiceReply.Sink {
        final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        int json, pages, firstAudio;
        @Override public void json(JSONObject value) { json++; }
        @Override public void binary(byte[] value) {
            require(value[0] == 0 && value[1] == 0 && value[2] == 0 && value[3] == 2, "stream prefix");
            audio.write(value, 4, value.length - 4); pages++;
        }
        @Override public void audioPacketSent() { firstAudio++; }
    }
    private interface Operation { void run() throws Exception; }
    private static void require(boolean value, String message) {
        assertions++; if (!value) throw new AssertionError(message);
    }
    private static void rejects(Operation operation, String code) throws Exception {
        try { operation.run(); throw new AssertionError("accepted invalid stream"); }
        catch (IOException failure) { require(failure.getMessage().equals(code), "fixed validation code"); }
    }
    private static ArrayList<byte[]> pages(byte[] ogg) {
        ArrayList<byte[]> result = new ArrayList<>();
        for (int offset = 0; offset < ogg.length;) {
            int segments = ogg[offset + 26] & 255, count = 27 + segments;
            for (int i = 0; i < segments; i++) count += ogg[offset + 27 + i] & 255;
            result.add(Arrays.copyOfRange(ogg, offset, offset + count)); offset += count;
        }
        return result;
    }
    private static byte[] changed(byte[] source, int offset, int value) {
        byte[] page = source.clone(); page[offset] = (byte) value;
        Arrays.fill(page, 22, 26, (byte) 0);
        int crc = 0;
        for (byte next : page) {
            crc ^= (next & 255) << 24;
            for (int i = 0; i < 8; i++) crc = (crc << 1) ^ (crc < 0 ? 0x04c11db7 : 0);
        }
        for (int i = 0; i < 4; i++) page[22 + i] = (byte) (crc >>> (i * 8));
        return page;
    }
    private static LegacyVoiceReply.Streaming stream(LegacyVoiceSession.Metadata meta, Sink sink) {
        return new LegacyVoiceReply.Streaming(meta, "Сколько будет два плюс два?", "Два плюс два равно четырём.", sink);
    }
    private static void feed(LegacyVoiceReply.Streaming stream, byte[] page) throws Exception {
        stream.page(page, 0, page.length);
    }
    public static void main(String[] args) throws Exception {
        Constructor<LegacyVoiceSession.Metadata> constructor = LegacyVoiceSession.Metadata.class.getDeclaredConstructor(
                long.class, String.class, String.class, long.class, String.class, boolean.class, int.class);
        constructor.setAccessible(true);
        LegacyVoiceSession.Metadata meta = constructor.newInstance(101L, "synthetic-reference", "synthetic-request",
                1L, "synthetic-session", true, 17);
        byte[] audio = Files.readAllBytes(Paths.get(args[0])); ArrayList<byte[]> pages = pages(audio);
        Sink output = new Sink(); LegacyVoiceReply.Streaming valid = stream(meta, output);
        for (int i = 0; i < pages.size(); i++) {
            byte[] padded = new byte[pages.get(i).length + 7];
            System.arraycopy(pages.get(i), 0, padded, 3, pages.get(i).length);
            valid.page(padded, 3, pages.get(i).length); Arrays.fill(padded, (byte) 0);
            if (i == 1) require(output.json == 0 && output.pages == 0 && output.firstAudio == 0, "headers withheld");
            if (i == 2) require(output.pages == 3 && output.firstAudio == 1, "early audio before EOF");
        }
        require(output.pages == pages.size() - 1 && !valid.isFinished(), "EOS withheld before finish");
        valid.finish();
        require(valid.isFinished() && output.firstAudio == 1, "one first-audio event");
        require(output.json == 6, "ASR input-end validation Vins TTS output-end");
        require(Arrays.equals(audio, output.audio.toByteArray()), "buffer ownership and byte-exact pages");
        rejects(valid::finish, "missing_ogg_eos");
        rejects(() -> feed(valid, pages.get(0)), "stream_already_ended");

        byte[] corrupt = pages.get(0).clone(); corrupt[corrupt.length - 1] ^= 1;
        rejects(() -> feed(stream(meta, new Sink()), corrupt), "invalid_ogg_crc");
        rejects(() -> feed(stream(meta, new Sink()), changed(pages.get(0), 37, 2)), "invalid_opus_head");
        rejects(() -> feed(stream(meta, new Sink()), changed(pages.get(0), 46, 1)), "invalid_opus_head");
        rejects(() -> stream(meta, new Sink()).page(audio, 0, pages.get(0).length + pages.get(1).length), "invalid_stream_page");
        rejects(() -> stream(meta, new Sink()).page(audio, -1, 47), "invalid_stream_page");
        for (int kind = 0; kind < 4; kind++) {
            LegacyVoiceReply.Streaming value = stream(meta, new Sink()); feed(value, pages.get(0));
            final byte[] bad;
            final String code;
            if (kind == 0) { bad = changed(pages.get(1), 18, 7); code = "invalid_ogg_sequence"; }
            else if (kind == 1) { bad = changed(pages.get(1), 14, (pages.get(1)[14] & 255) ^ 1); code = "invalid_ogg_sequence"; }
            else if (kind == 2) { bad = changed(pages.get(1), 5, 1); code = "invalid_ogg_continuation"; }
            else { bad = changed(pages.get(1), 27 + (pages.get(1)[26] & 255), 'X'); code = "invalid_opus_tags"; }
            rejects(() -> feed(value, bad), code);
        }
        Sink partial = new Sink(); LegacyVoiceReply.Streaming unfinished = stream(meta, partial);
        for (int i = 0; i < pages.size() - 1; i++) feed(unfinished, pages.get(i));
        int before = partial.json;
        rejects(unfinished::finish, "missing_ogg_eos");
        require(before == partial.json && !unfinished.isFinished(), "no fake EOS/control on missing provider EOS");
        LegacyVoiceReply.Streaming afterEos = stream(meta, new Sink());
        for (byte[] page : pages) feed(afterEos, page);
        rejects(() -> feed(afterEos, pages.get(pages.size() - 1)), "stream_already_ended");
        System.out.println("PASS streaming output " + assertions + " assertions; public synthetic fixture only");
    }
}
