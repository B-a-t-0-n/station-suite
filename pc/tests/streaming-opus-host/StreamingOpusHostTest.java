package local.station.voicebridge;

import android.media.MediaCodec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Tests production demux/PCM logic with public fixture packets and generated sine samples. */
public final class StreamingOpusHostTest {
    private static int assertions;
    private static void check(boolean value, String name) { assertions++; if (!value) throw new AssertionError(name); }
    private interface Task { void run() throws Exception; }
    private static void fails(String code, Task task) throws Exception {
        try { task.run(); throw new AssertionError("expected " + code); }
        catch (IOException error) { check(code.equals(error.getMessage()), "fixed input/decoder error"); }
    }
    private static byte[] concat(byte[] a, byte[] b) {
        byte[] output = Arrays.copyOf(a, a.length + b.length); System.arraycopy(b, 0, output, a.length, b.length); return output;
    }
    private static List<byte[]> pages(byte[] input) {
        List<byte[]> result = new ArrayList<>();
        for (int offset = 0; offset < input.length;) {
            int laces = input[offset + 26] & 255, length = 27 + laces;
            for (int i = 0; i < laces; i++) length += input[offset + 27 + i] & 255;
            result.add(Arrays.copyOfRange(input, offset, offset + length)); offset += length;
        }
        return result;
    }
    private static int crc(byte[] page) {
        int value = 0;
        for (int i = 0; i < page.length; i++) {
            int b = i >= 22 && i < 26 ? 0 : page[i] & 255;
            value ^= b << 24;
            for (int bit = 0; bit < 8; bit++) value = (value << 1) ^ (value < 0 ? 0x04c11db7 : 0);
        }
        return value;
    }
    private static void repair(byte[] page) {
        int checksum = crc(page);
        for (int i = 0; i < 4; i++) page[22 + i] = (byte) (checksum >>> (8 * i));
    }
    private static byte[] joined(List<byte[]> pages) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); for (byte[] page : pages) output.write(page); return output.toByteArray();
    }
    private static final class Decoder implements StreamingOpusInput.Decoder {
        int starts, finishes, closes, polls; boolean active;
        final List<byte[]> packets = new ArrayList<>();
        public void start(byte[] head) { check(!active, "previous chain drained before start"); starts++; active = true; }
        public void packet(byte[] packet) { check(active, "packets only after head"); packets.add(Arrays.copyOf(packet, packet.length)); }
        public void poll() { check(active, "idle poll only with active codec"); polls++; }
        public void finish() { check(active, "one finish per decoder chain"); finishes++; active = false; }
        public void close() { closes++; active = false; }
    }
    private static Decoder parsed(byte[] input, int chunk) throws Exception {
        Decoder decoder = new Decoder();
        StreamingOpusInput parser = new StreamingOpusInput(decoder);
        try {
            for (int offset = 0; offset < input.length;) {
                int count = Math.min(chunk, input.length - offset);
                byte[] prefixed = new byte[count + 6]; System.arraycopy(input, offset, prefixed, 3, count);
                parser.accept(prefixed, 3, count); offset += count;
            }
            check(parser.inputBytes() == input.length, "selected input bounded counter");
            check(parser.parsedBytes() == input.length && parser.pendingPageBytes() == 0
                    && parser.expectedPageBytes() == 0, "complete pages numeric byte counters");
            check(parser.pages() == pages(input).size() && parser.audioPackets() == decoder.packets.size(),
                    "numeric page and decoded-packet counters");
            check(parser.completeBoundary(), "complete input boundary before matching end");
            check(decoder.finishes == decoder.starts - 1, "last chain not committed before explicit end");
            parser.finish();
            check(decoder.finishes == decoder.starts, "explicit end validates and drains final chain");
            fails("INPUT_STATE", parser::poll);
        } finally { parser.close(); }
        check(decoder.closes == 1, "parser closes decoder exactly once"); return decoder;
    }
    private static final class Sink implements AndroidOpusDecoder.PcmSink {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(); boolean cancelled;
        public void accept(byte[] pcm, int offset, int count) { bytes.write(pcm, offset, count); }
        public boolean cancelled() { return cancelled; }
    }
    private static byte[] head() {
        byte[] head = new byte[19]; System.arraycopy("OpusHead".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, head, 0, 8);
        head[8] = 1; head[9] = 1; head[10] = 0x38; head[11] = 1; return head;
    }
    private static byte[] pcm(int count, int rate, double frequency) {
        byte[] result = new byte[count * 2];
        for (int i = 0; i < count; i++) {
            short sample = (short) Math.round(10000 * Math.sin(2 * Math.PI * frequency * i / rate));
            result[2 * i] = (byte) sample; result[2 * i + 1] = (byte) (sample >>> 8);
        }
        return result;
    }
    private static byte[] decode(byte[] pcm, int rate, int chunk) throws Exception {
        MediaCodec.reset(); MediaCodec.outputRate = rate;
        for (int offset = 0; offset < pcm.length; offset += chunk)
            MediaCodec.nextPcm.add(Arrays.copyOfRange(pcm, offset, Math.min(pcm.length, offset + chunk)));
        Sink sink = new Sink(); AndroidOpusDecoder decoder = new AndroidOpusDecoder(sink);
        try {
            decoder.start(head());
            for (int i = 0; i < MediaCodec.nextPcm.size(); i++) decoder.packet(new byte[]{(byte) 0xf8, 1});
            decoder.finish();
            check(MediaCodec.created.get(0).released && MediaCodec.created.get(0).stopped, "codec stopped/released after EOS");
            check(decoder.pcmSamples() == sink.bytes.size() / 2, "PCM sample accounting");
        } finally { decoder.close(); }
        return sink.bytes.toByteArray();
    }
    private static double rms(byte[] pcm) {
        double sum = 0; int count = 0;
        for (int i = 256; i + 1 < pcm.length; i += 2) {
            short sample = (short) ((pcm[i] & 255) | ((pcm[i + 1] & 255) << 8)); sum += (double) sample * sample; count++;
        }
        return Math.sqrt(sum / count);
    }
    private static void delayedCodecOutputAndPartialPcm() throws Exception {
        MediaCodec.reset(); MediaCodec.outputRate = 16000; MediaCodec.asynchronousOutput = true;
        byte[] first = pcm(960, 16000, 1000), second = pcm(320, 16000, 1000);
        MediaCodec.nextPcm.add(first); MediaCodec.nextPcm.add(second);
        Sink sink = new Sink(); AndroidOpusDecoder decoder = new AndroidOpusDecoder(sink);
        try {
            decoder.start(head());
            decoder.packet(new byte[]{24}); // A real 60 ms packet duration; generated PCM only.
            MediaCodec codec = MediaCodec.created.get(0);
            check(sink.bytes.size() == 0, "asynchronous fixture must not make PCM ready during packet dequeue");
            check(codec.queuedFlags.equals(Arrays.asList(0)), "one packet queued before idle progress");
            codec.releaseDelayedOutput(); decoder.poll();
            check(Arrays.equals(first, sink.bytes.toByteArray()), "idle poll releases late codec output and sub-64ms PCM batch");
            check(codec.queuedFlags.equals(Arrays.asList(0)) && !codec.stopped && !codec.released,
                    "idle poll does not queue input, EOS, stop or release");
            for (long timeout : codec.outputTimeouts) check(timeout == 0, "idle polling stays nonblocking");
            decoder.poll(); decoder.poll();
            check(Arrays.equals(first, sink.bytes.toByteArray()), "empty idle polls do not duplicate PCM");
            decoder.packet(new byte[]{(byte) 152});
            check(Arrays.equals(first, sink.bytes.toByteArray()), "later asynchronous packet waits for readiness");
            codec.releaseDelayedOutput(); decoder.poll();
            check(Arrays.equals(concat(first, second), sink.bytes.toByteArray()), "continuous idle flush preserves every sample in order");
            check(codec.timestamps.equals(Arrays.asList(0L, 60000L)), "idle poll preserves native 60ms timestamp progression");
            decoder.finish();
            check(codec.queuedFlags.equals(Arrays.asList(0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)),
                    "only explicit finish queues EOS");
            check(Arrays.equals(concat(first, second), sink.bytes.toByteArray()), "finish after idle output neither repeats nor drops samples");
            fails("OPUS_STATE", decoder::poll);
        } finally { decoder.close(); }

        MediaCodec.reset(); MediaCodec.outputRate = 16000; MediaCodec.asynchronousOutput = true;
        MediaCodec.nextPcm.add(first); Sink cancelled = new Sink();
        AndroidOpusDecoder stopped = new AndroidOpusDecoder(cancelled);
        try {
            stopped.start(head()); stopped.packet(new byte[]{24});
            MediaCodec codec = MediaCodec.created.get(0); codec.releaseDelayedOutput(); cancelled.cancelled = true;
            fails("CANCELLED", stopped::poll);
            check(cancelled.bytes.size() == 0 && codec.queuedFlags.equals(Arrays.asList(0)),
                    "cancelled idle poll does not deliver PCM or invent EOS");
        } finally { stopped.close(); }
        check(MediaCodec.created.get(0).released, "cancelled idle codec is released by the owning worker");
    }
    public static void main(String[] args) throws Exception {
        delayedCodecOutputAndPartialPcm();
        MediaCodec.reset(); MediaCodec.outputRate=16000;
        MediaCodec.nextPcm.add(pcm(1024,16000,1000));
        Sink earlySink=new Sink(); AndroidOpusDecoder early=new AndroidOpusDecoder(earlySink);
        try {
            early.start(head()); early.packet(new byte[]{(byte)0xf8,1});
            check(earlySink.bytes.size()==2048,"64ms PCM reaches recognizer before input end");
            early.finish();
            check(earlySink.bytes.size()==2048,"early flush neither duplicates nor drops samples");
        } finally {early.close();}
        byte[] fixture = Files.readAllBytes(Paths.get(args[0])); List<byte[]> pages = pages(fixture);
        Decoder baseline = parsed(fixture, fixture.length);
        check(baseline.starts == 1 && baseline.packets.size() > 1, "fixture selects Opus packet stream");
        Decoder pendingDecoder = new Decoder();
        StreamingOpusInput pending = new StreamingOpusInput(pendingDecoder);
        pending.poll(); check(pendingDecoder.polls == 0, "empty input cannot poll unconfigured codec");
        check(pending.parsedBytes() == 0 && pending.pages() == 0 && pending.audioPackets() == 0
                && pending.expectedPageBytes() == 0 && !pending.completeBoundary(), "empty diagnostics");
        pending.accept(fixture, 0, 1);
        check(pending.pendingPageBytes() == 1 && pending.expectedPageBytes() == -1
                && !pending.completeBoundary(), "partial page header diagnostic");
        int headersLength = pages.get(0).length + pages.get(1).length;
        pending.accept(fixture, 1, pages.get(0).length - 1);
        pending.poll(); check(pendingDecoder.polls == 0, "OpusHead alone cannot poll before valid OpusTags");
        pending.accept(fixture, pages.get(0).length, pages.get(1).length);
        pending.poll(); check(pendingDecoder.polls == 1 && pendingDecoder.finishes == 0, "validated headers allow idle progress without finalization");
        check(pending.parsedBytes() == headersLength && pending.pages() == 2
                && pending.audioPackets() == 0 && !pending.completeBoundary(), "headers alone are not an audio boundary");
        int firstAudioLength = pages.get(2).length;
        pending.accept(fixture, headersLength, firstAudioLength - 1);
        pending.poll(); check(pendingDecoder.polls == 2 && pendingDecoder.packets.isEmpty(), "idle poll never bypasses pending page CRC");
        check(pending.parsedBytes() == headersLength && pending.pendingPageBytes() == firstAudioLength - 1
                && pending.expectedPageBytes() == firstAudioLength && pending.audioPackets() == 0
                && !pending.completeBoundary(), "complete Opus packets wait for page CRC in current decoder");
        pending.accept(fixture, headersLength + firstAudioLength - 1, 1);
        check(pending.parsedBytes() == headersLength + firstAudioLength && pending.pages() == 3
                && pending.audioPackets() > 0 && pending.pendingPageBytes() == 0
                && pending.expectedPageBytes() == 0 && pending.completeBoundary(), "page completion releases queued packets");
        pending.close();
        fails("INPUT_STATE", pending::poll);
        check(!pending.completeBoundary(), "closed parser is never a live boundary");
        for (int chunk : new int[]{1, 2, 3, 7, 26, 27, 28, 255, 1024, 4096}) {
            Decoder actual = parsed(fixture, chunk);
            check(actual.starts == 1 && actual.packets.size() == baseline.packets.size(), "arbitrary frame boundary count");
            for (int i = 0; i < baseline.packets.size(); i++)
                check(Arrays.equals(actual.packets.get(i), baseline.packets.get(i)), "packet payload unchanged across chunks");
        }
        List<byte[]> noEosPages = pages(fixture); byte[] last = noEosPages.get(noEosPages.size() - 1);
        last[5] &= ~4; repair(last); byte[] noEos = joined(noEosPages);
        check(parsed(noEos, 11).starts == 1, "native transport-finalized missing EOS accepted");
        Decoder chained = parsed(concat(noEos, noEos), 31);
        check(chained.starts == 2 && chained.finishes == 2 && chained.packets.size() == 2 * baseline.packets.size(), "same-serial reset chain drains/restarts decoder");

        for (int remove : new int[]{1, 10, last.length - 1}) {
            Decoder decoder = new Decoder(); StreamingOpusInput parser = new StreamingOpusInput(decoder);
            byte[] truncated = Arrays.copyOf(fixture, fixture.length - remove);
            parser.accept(truncated, 0, truncated.length);
            parser.poll();
            fails("INCOMPLETE_INPUT", parser::finish);
            check(decoder.finishes == 0, "truncated command never finalizes recognizer"); parser.close();
        }
        Decoder crcDecoder = new Decoder(); StreamingOpusInput badCrc = new StreamingOpusInput(crcDecoder);
        byte[] corrupt = fixture.clone(); corrupt[corrupt.length - 1] ^= 1;
        fails("PAGE_CRC", () -> badCrc.accept(corrupt, 0, corrupt.length)); badCrc.close();
        check(crcDecoder.finishes == 0, "CRC failure never finalizes recognizer");
        List<byte[]> gapPages = pages(fixture); gapPages.get(2)[18] += 1; repair(gapPages.get(2)); byte[] gap = joined(gapPages);
        Decoder gapDecoder = new Decoder(); StreamingOpusInput badSequence = new StreamingOpusInput(gapDecoder);
        badSequence.accept(gap, 0, gap.length);
        badSequence.poll();
        boolean sequenceRejected = false;
        try { badSequence.finish(); } catch (NativeOggInput.InputException expected) { sequenceRejected = true; }
        check(sequenceRejected && gapDecoder.finishes == 0, "final native validation rejects provisional sequence-hole decode"); badSequence.close();
        Decoder metadata = new Decoder(); StreamingOpusInput stereo = new StreamingOpusInput(metadata);
        byte[] stereoPage = pages.get(0).clone(); stereoPage[28 + 9] = 2; repair(stereoPage);
        fails("OPUS_HEAD", () -> stereo.accept(stereoPage, 0, stereoPage.length)); stereo.close();
        check(metadata.starts == 0, "reject stereo before decoder setup");
        Decoder invalid = new Decoder(); StreamingOpusInput limits = new StreamingOpusInput(invalid);
        fails("INPUT_SIZE", () -> limits.accept(new byte[NativeOggInput.MAX_BYTES + 1], 0, NativeOggInput.MAX_BYTES + 1));
        fails("INPUT_SIZE", () -> limits.accept(fixture, -1, 2));
        fails("INPUT_SIZE", () -> limits.accept(null, 0, 0)); limits.close(); limits.close();
        check(invalid.closes == 1, "close idempotence");

        // TOC timestamps cover SILK, hybrid, CELT and frame-count codes; no real packet decoding.
        check(AndroidOpusDecoder.packetSamples(new byte[]{0}) == 480, "SILK 10ms");
        check(AndroidOpusDecoder.packetSamples(new byte[]{24}) == 2880, "SILK 60ms");
        check(AndroidOpusDecoder.packetSamples(new byte[]{96}) == 480, "hybrid 10ms");
        check(AndroidOpusDecoder.packetSamples(new byte[]{(byte) 128}) == 120, "CELT 2.5ms");
        check(AndroidOpusDecoder.packetSamples(new byte[]{(byte) 152}) == 960, "CELT 20ms");
        check(AndroidOpusDecoder.packetSamples(new byte[]{(byte) 153}) == 1920, "two frames");
        check(AndroidOpusDecoder.packetSamples(new byte[]{(byte) 155, 6}) == 5760, "maximum 120ms packet");
        fails("OPUS_PACKET", () -> AndroidOpusDecoder.packetSamples(new byte[]{(byte) 155, 7}));
        fails("OPUS_PACKET", () -> AndroidOpusDecoder.packetSamples(new byte[]{3}));
        fails("OPUS_PACKET", () -> AndroidOpusDecoder.packetSamples(new byte[0]));

        byte[] source16 = pcm(1600, 16000, 1000);
        check(Arrays.equals(source16, decode(source16, 16000, 320)), "16k PCM passes through exact byte order");
        byte[] low = pcm(4800, 48000, 1000), high = pcm(4800, 48000, 12000);
        byte[] lowOut = decode(low, 48000, 1920), highOut = decode(high, 48000, 480);
        check(lowOut.length == 3200 && highOut.length == 3200, "48k to16k output ratio");
        check(Arrays.equals(lowOut, decode(low, 48000, 14)), "FIR/ring/phase independent of codec PCM chunk boundaries");
        check(rms(lowOut) > 6000 && rms(highOut) < rms(lowOut) * .03, "anti-alias FIR passes speech band and rejects12k");

        // Validate CSD pre-skip metadata, timestamps, chain release and explicit EOS path.
        MediaCodec.reset(); Sink sink = new Sink(); AndroidOpusDecoder decoder = new AndroidOpusDecoder(sink);
        decoder.start(head()); MediaCodec first = MediaCodec.created.get(0);
        check(first.configured.getByteBuffer("csd-1").order(ByteOrder.nativeOrder()).getLong(0) == 6500000L, "pre-skip CSD in nanoseconds");
        check(first.configured.getByteBuffer("csd-2").order(ByteOrder.nativeOrder()).getLong(0) == 80000000L, "seek-preroll metadata");
        decoder.packet(new byte[]{(byte) 0xf8, 1}); decoder.packet(new byte[]{(byte) 0xf8, 1}); decoder.finish();
        check(first.timestamps.equals(Arrays.asList(0L, 20000L, 40000L)), "input/EOS timestamps monotonic in48k units");
        decoder.start(head()); decoder.finish(); decoder.close();
        check(MediaCodec.created.size() == 2 && first.released && MediaCodec.created.get(1).released, "each logical chain owns released codec");
        for (int badRate : new int[]{8000, 24000, 44100}) {
            MediaCodec.reset(); MediaCodec.outputRate = badRate;
            AndroidOpusDecoder bad = new AndroidOpusDecoder(new Sink()); bad.start(head());
            fails("OPUS_PCM_FORMAT", () -> bad.packet(new byte[]{(byte) 0xf8})); bad.close();
        }
        MediaCodec.reset(); MediaCodec.outputChannels = 2;
        AndroidOpusDecoder channels = new AndroidOpusDecoder(new Sink()); channels.start(head());
        fails("OPUS_PCM_FORMAT", () -> channels.packet(new byte[]{(byte) 0xf8})); channels.close();
        MediaCodec.reset(); Sink cancelled = new Sink(); cancelled.cancelled = true;
        fails("CANCELLED", () -> new AndroidOpusDecoder(cancelled).start(head()));
        MediaCodec.reset(); MediaCodec.omitEos = true;
        AndroidOpusDecoder stalled = new AndroidOpusDecoder(new Sink()); stalled.start(head());
        long began = System.nanoTime(); fails("OPUS_OUTPUT_TIMEOUT", stalled::finish); stalled.close();
        check(System.nanoTime() - began < 4000000000L, "EOS timeout bounded without spin forever");
        check(MediaCodec.created.get(0).released, "timeout owner can release codec in finally");
        System.out.println("StreamingOpusHostTest: " + assertions + " assertions passed; fake codec/synthetic PCM only");
    }
}
