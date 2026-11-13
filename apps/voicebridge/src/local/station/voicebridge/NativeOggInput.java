package local.station.voicebridge;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A bounded container adapter for a selected, transport-finalized native command.
 * Call only after its matching streamcontrol closes the command, never at a timeout.
 * No codec decoding, raw PCM fallback, I/O, logging or Android dependencies.
 */
public final class NativeOggInput {
    public static final int MAX_BYTES = 512 * 1024;
    private static final int MAX_PAGES = 4096, MAX_SEGMENTS = 4, MAX_TAG_BYTES = 65536;
    private static final int[] CRC_TABLE = crcTable();

    public static final class Result {
        public final byte[] audio;
        public final int segments, pages, eosRepairs, serialRewrites, inputBytes, outputBytes;
        private Result(byte[] audio, int segments, int pages, int eosRepairs, int serialRewrites) {
            this.audio = audio; this.segments = segments; this.pages = pages;
            this.eosRepairs = eosRepairs; this.serialRewrites = serialRewrites;
            this.inputBytes = audio.length; this.outputBytes = audio.length;
        }
    }

    /** Fixed codes only, with no caller bytes, identifiers, metadata or underlying cause. */
    public static final class InputException extends Exception {
        private static final long serialVersionUID = 1L;
        public final String code;
        private InputException(String code) { super(code); this.code = code; }
    }

    private NativeOggInput() { }

    /**
     * Preserve every granule/lacing/codec byte. Only EOS, colliding stream serials and
     * checksums can change. Repeated BOS starts a new independently headed chain.
     */
    public static Result normalize(byte[] input) throws InputException {
        if (input == null || input.length < 47 || input.length > MAX_BYTES) throw fail("INPUT_SIZE");
        // A snapshot prevents later caller changes from invalidating a completed check.
        byte[] audio = input.clone();
        List<Page> pages = new ArrayList<>();
        List<Segment> segments = new ArrayList<>();
        Set<Long> usedSerials = new HashSet<>();
        Segment current = null;
        int offset = 0, eosRepairs = 0, serialRewrites = 0;
        while (offset < audio.length) {
            if (pages.size() == MAX_PAGES) throw fail("PAGE_LIMIT");
            Page page = page(audio, offset);
            if ((page.flags & 2) != 0) {
                if (current != null) finish(current);
                if (segments.size() == MAX_SEGMENTS) throw fail("SEGMENT_LIMIT");
                long outputSerial = page.serial;
                while (usedSerials.contains(outputSerial)) outputSerial = (outputSerial + 1) & 0xffffffffL;
                usedSerials.add(outputSerial);
                current = new Segment(page.serial, outputSerial);
                segments.add(current);
                if (outputSerial != page.serial) serialRewrites++;
                head(audio, page);
                current.nextSequence = 1;
            } else {
                if (current == null) throw fail("MISSING_BOS");
                if (current.eos) throw fail("PAGE_AFTER_EOS");
                if (page.serial != current.inputSerial) throw fail("INTERLEAVED_STREAM");
                if (page.sequence != current.nextSequence) throw fail("PAGE_SEQUENCE");
                current.nextSequence++;
                packets(audio, page, current);
            }
            current.last = page;
            current.eos = (page.flags & 4) != 0;
            pages.add(page);
            page.segment = current;
            offset += page.length;
        }
        if (current == null) throw fail("MISSING_BOS");
        finish(current);
        for (Segment segment : segments) {
            if ((segment.last.flags & 4) == 0) {
                audio[segment.last.offset + 5] |= 4;
                eosRepairs++;
            }
        }
        for (Page page : pages) {
            little(audio, page.offset + 14, page.segment.outputSerial);
            little(audio, page.offset + 22, crc(audio, page.offset, page.length));
        }
        return new Result(audio, segments.size(), pages.size(), eosRepairs, serialRewrites);
    }

    private static Page page(byte[] data, int offset) throws InputException {
        if (data.length - offset < 27) throw fail("TRUNCATED_PAGE");
        if (!ascii(data, offset, "OggS") || data[offset + 4] != 0) throw fail("INVALID_PAGE");
        int flags = data[offset + 5] & 255, count = data[offset + 26] & 255;
        // Empty pages are outside the captured command profile; never use one to synthesize EOS.
        if ((flags & ~7) != 0 || count == 0) throw fail("INVALID_PAGE");
        if (data.length - offset < 27 + count) throw fail("TRUNCATED_PAGE");
        int length = 27 + count;
        for (int i = 0; i < count; i++) length += data[offset + 27 + i] & 255;
        if (data.length - offset < length) throw fail("TRUNCATED_PAGE");
        if ((crc(data, offset, length) & 0xffffffffL) != little(data, offset + 22)) throw fail("PAGE_CRC");
        long granule = 0;
        for (int i = 7; i >= 0; i--) granule = (granule << 8) | (data[offset + 6 + i] & 255L);
        if (granule < -1) throw fail("INVALID_GRANULE");
        return new Page(offset, length, count, flags, little(data, offset + 14),
                little(data, offset + 18), granule);
    }

    private static void head(byte[] data, Page page) throws InputException {
        int body = page.body();
        if (page.flags != 2 || page.sequence != 0 || page.granule != 0
                || page.count != 1 || (data[page.offset + 27] & 255) != 19
                || !ascii(data, body, "OpusHead") || (data[body + 8] & 255) != 1
                || (data[body + 9] & 255) != 1 || data[body + 18] != 0)
            throw fail("OPUS_HEAD");
    }

    private static void packets(byte[] data, Page page, Segment segment) throws InputException {
        if (((page.flags & 1) != 0) != segment.packetOpen) throw fail("PACKET_CONTINUATION");
        boolean tagsPage = !segment.tagsComplete;
        int body = page.body(), completed = 0;
        for (int i = 0; i < page.count; i++) {
            int size = data[page.offset + 27 + i] & 255;
            segment.packetBytes += size;
            if (tagsPage) {
                if (segment.packetBytes > MAX_TAG_BYTES) throw fail("TAGS_LIMIT");
                segment.tags.write(data, body, size);
            }
            body += size;
            segment.packetOpen = size == 255;
            if (!segment.packetOpen) {
                if (segment.packetBytes == 0) throw fail("EMPTY_PACKET");
                completed++;
                if (tagsPage) {
                    if (i != page.count - 1) throw fail("TAGS_PAGE");
                    tags(segment.tags.toByteArray());
                    segment.tags = null;
                    segment.tagsComplete = true;
                } else {
                    segment.audioPackets++;
                }
                segment.packetBytes = 0;
            }
        }
        if (tagsPage) {
            if (page.granule != (completed == 0 ? -1 : 0)) throw fail("INVALID_GRANULE");
        } else if (completed == 0) {
            if (page.granule != -1) throw fail("INVALID_GRANULE");
        } else {
            if (page.granule < 0 || page.granule < segment.lastGranule) throw fail("INVALID_GRANULE");
            segment.lastGranule = page.granule;
        }
        if ((page.flags & 4) != 0 && (segment.packetOpen || tagsPage || completed == 0))
            throw fail("INCOMPLETE_SEGMENT");
    }

    private static void tags(byte[] packet) throws InputException {
        if (packet.length < 16 || !ascii(packet, 0, "OpusTags")) throw fail("OPUS_TAGS");
        long vendor = little(packet, 8);
        if (vendor > packet.length - 16) throw fail("OPUS_TAGS");
        int offset = 12 + (int) vendor;
        long comments = little(packet, offset);
        offset += 4;
        if (comments > (packet.length - offset) / 4) throw fail("OPUS_TAGS");
        for (long i = 0; i < comments; i++) {
            if (packet.length - offset < 4) throw fail("OPUS_TAGS");
            long length = little(packet, offset);
            offset += 4;
            if (length > packet.length - offset) throw fail("OPUS_TAGS");
            offset += (int) length;
        }
        // RFC 7845 permits trailing padding; its bytes are preserved, not interpreted.
    }

    private static void finish(Segment segment) throws InputException {
        if (!segment.tagsComplete || segment.audioPackets == 0 || segment.packetOpen
                || segment.last == null || segment.last.granule < 0)
            throw fail("INCOMPLETE_SEGMENT");
    }

    private static final class Segment {
        final long inputSerial, outputSerial;
        long nextSequence, lastGranule;
        int packetBytes, audioPackets;
        boolean packetOpen, tagsComplete, eos;
        ByteArrayOutputStream tags = new ByteArrayOutputStream();
        Page last;
        Segment(long inputSerial, long outputSerial) { this.inputSerial = inputSerial; this.outputSerial = outputSerial; }
    }

    private static final class Page {
        final int offset, length, count, flags;
        final long serial, sequence, granule;
        Segment segment;
        Page(int offset, int length, int count, int flags, long serial, long sequence, long granule) {
            this.offset = offset; this.length = length; this.count = count; this.flags = flags;
            this.serial = serial; this.sequence = sequence; this.granule = granule;
        }
        int body() { return offset + 27 + count; }
    }

    private static boolean ascii(byte[] data, int offset, String text) {
        if (offset < 0 || data.length - offset < text.length()) return false;
        for (int i = 0; i < text.length(); i++) if (data[offset + i] != text.charAt(i)) return false;
        return true;
    }
    private static long little(byte[] data, int offset) {
        return (data[offset] & 255L) | ((data[offset + 1] & 255L) << 8)
                | ((data[offset + 2] & 255L) << 16) | ((data[offset + 3] & 255L) << 24);
    }
    private static void little(byte[] data, int offset, long value) {
        for (int i = 0; i < 4; i++) data[offset + i] = (byte) (value >>> (8 * i));
    }
    private static int crc(byte[] data, int offset, int length) {
        int value = 0;
        for (int i = 0; i < length; i++) {
            int next = i >= 22 && i < 26 ? 0 : data[offset + i] & 255;
            value = (value << 8) ^ CRC_TABLE[((value >>> 24) ^ next) & 255];
        }
        return value;
    }
    private static int[] crcTable() {
        int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            int value = i << 24;
            for (int j = 0; j < 8; j++) value = (value << 1) ^ (value < 0 ? 0x04c11db7 : 0);
            table[i] = value;
        }
        return table;
    }
    private static InputException fail(String code) { return new InputException(code); }
}
