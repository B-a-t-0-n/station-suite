package local.station.voicebridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/** Bounded incremental demuxer. Final native validation is mandatory before committing text. */
public final class StreamingOpusInput implements AutoCloseable {
    public interface Decoder extends AutoCloseable {
        void start(byte[] head) throws Exception;
        void packet(byte[] packet) throws Exception;
        /** Nonblocking output progress only; never signals input end or commits text. */
        default void poll() throws Exception { }
        void finish() throws Exception;
        @Override void close();
    }
    private final Decoder decoder;
    private final byte[] input = new byte[NativeOggInput.MAX_BYTES];
    private final ByteArrayOutputStream packet = new ByteArrayOutputStream(2048);
    private int used, parsed, headers, segments, pages, audioPackets, segmentAudioPackets;
    private boolean ended, closed;

    public StreamingOpusInput(Decoder decoder) {
        if (decoder == null) throw new IllegalArgumentException("decoder_required");
        this.decoder = decoder;
    }

    /** Only the selected command stream, never the wakeword PCM stream. One worker owns this. */
    public void accept(byte[] data, int offset, int count) throws Exception {
        if (closed || ended || data == null || offset < 0 || count < 0 || offset > data.length - count
                || used > input.length - count) throw new IOException("INPUT_SIZE");
        System.arraycopy(data, offset, input, used, count); used += count;
        while (used - parsed >= 27) {
            if (!ascii(input, parsed, "OggS") || input[parsed + 4] != 0) throw new IOException("INVALID_PAGE");
            int flags = input[parsed + 5] & 255, laces = input[parsed + 26] & 255;
            if ((flags & ~7) != 0 || laces == 0) throw new IOException("INVALID_PAGE");
            if (used - parsed < 27 + laces) return;
            int length = 27 + laces;
            for (int i = 0; i < laces; i++) length += input[parsed + 27 + i] & 255;
            if (used - parsed < length) return;
            if (crc(input, parsed, length) != le32(input, parsed + 22)) throw new IOException("PAGE_CRC");
            boolean bos = (flags & 2) != 0;
            if (bos) {
                if (packet.size() != 0 || ++segments > 4) throw new IOException("INCOMPLETE_SEGMENT");
                if (segments > 1) decoder.finish();
                headers = 0; segmentAudioPackets = 0;
            } else if (segments == 0) throw new IOException("MISSING_BOS");
            if (((flags & 1) != 0) != (packet.size() != 0)) throw new IOException("PACKET_CONTINUATION");
            int body = parsed + 27 + laces;
            for (int i = 0; i < laces; i++) {
                int size = input[parsed + 27 + i] & 255;
                if (packet.size() > 65536 - size) throw new IOException("PACKET_LIMIT");
                packet.write(input, body, size); body += size;
                if (size != 255) {
                    byte[] bytes = packet.toByteArray(); packet.reset();
                    if (headers == 0) {
                        if (!bos || laces != 1 || bytes.length != 19 || !ascii(bytes, 0, "OpusHead")
                                || bytes[8] != 1 || bytes[9] != 1 || bytes[18] != 0)
                            throw new IOException("OPUS_HEAD");
                        decoder.start(bytes); headers = 1;
                    } else if (headers == 1) {
                        if (!ascii(bytes, 0, "OpusTags") || i != laces - 1) throw new IOException("OPUS_TAGS");
                        headers = 2;
                    } else {
                        if (bytes.length == 0) throw new IOException("EMPTY_PACKET");
                        decoder.packet(bytes);
                        audioPackets++; segmentAudioPackets++;
                    }
                }
            }
            parsed += length; pages++;
        }
    }

    /** ASR-worker idle progress after validated codec headers; no EOS or container finalization. */
    public void poll() throws Exception {
        if (closed || ended) throw new IOException("INPUT_STATE");
        if (headers == 2) decoder.poll();
    }

    /** Called exclusively for a matching command-end event, never a capture timeout. */
    public void finish() throws Exception {
        if (closed || ended || parsed != used || packet.size() != 0) throw new IOException("INCOMPLETE_INPUT");
        NativeOggInput.normalize(Arrays.copyOf(input, used));
        decoder.finish(); ended = true;
    }
    public int inputBytes() { return used; }
    /** Worker-owned counters contain no audio, transcript or stream identifiers. */
    public int parsedBytes() { return parsed; }
    public int pages() { return pages; }
    public int audioPackets() { return audioPackets; }
    public int pendingPageBytes() { return used - parsed; }
    /** Expected current page length, or -1 until the complete lacing table is available. */
    public int expectedPageBytes() {
        if (used == parsed) return 0;
        if (used - parsed < 27) return -1;
        int laces = input[parsed + 26] & 255;
        if (used - parsed < 27 + laces) return -1;
        int length = 27 + laces;
        for (int i = 0; i < laces; i++) length += input[parsed + 27 + i] & 255;
        return length;
    }
    /** Structural boundary only: finish() must still validate the complete native container. */
    public boolean completeBoundary() {
        return !closed && segments > 0 && headers == 2 && segmentAudioPackets > 0
                && parsed == used && packet.size() == 0;
    }
    @Override public void close() {
        if (closed) return;
        closed = true; decoder.close(); packet.reset(); Arrays.fill(input, (byte) 0);
    }
    private static boolean ascii(byte[] data, int offset, String value) {
        if (offset < 0 || data.length - offset < value.length()) return false;
        for (int i = 0; i < value.length(); i++) if (data[offset + i] != value.charAt(i)) return false;
        return true;
    }
    private static int le32(byte[] data, int offset) {
        return (data[offset] & 255) | ((data[offset + 1] & 255) << 8)
                | ((data[offset + 2] & 255) << 16) | ((data[offset + 3] & 255) << 24);
    }
    private static final int[] TABLE = table();
    private static int[] table() {
        int[] result = new int[256];
        for (int i = 0; i < 256; i++) {
            int value = i << 24;
            for (int j = 0; j < 8; j++) value = (value << 1) ^ (value < 0 ? 0x04c11db7 : 0);
            result[i] = value;
        }
        return result;
    }
    private static int crc(byte[] bytes, int offset, int count) {
        int result = 0;
        for (int i = 0; i < count; i++) {
            int b = i >= 22 && i < 26 ? 0 : bytes[offset + i] & 255;
            result = (result << 8) ^ TABLE[((result >>> 24) ^ b) & 255];
        }
        return result;
    }
}
