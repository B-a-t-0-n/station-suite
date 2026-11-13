package local.station.voicebridge;

import android.media.MediaCodec;
import android.media.MediaFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Decode selected Opus packets on the ASR worker. No AudioTrack, AudioRecord or playback. */
public final class AndroidOpusDecoder implements StreamingOpusInput.Decoder {
    public interface PcmSink {
        void accept(byte[] pcm, int offset, int count) throws Exception;
        boolean cancelled();
    }
    private final PcmSink sink;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    // Deliver at most 64 ms of PCM at a time. The old 256 ms batch delayed
    // native speech acknowledgement even when Opus packets were already here.
    private final byte[] pcm = new byte[2048];
    private final double[] taps = filter();
    private final short[] ring = new short[63];
    private int ringAt, phase, pcmBytes, sampleRate = 48000;
    private MediaCodec codec;
    private long samples, pcmSamples;
    private boolean outputEnded;

    public AndroidOpusDecoder(PcmSink sink) {
        if (sink == null) throw new IllegalArgumentException("pcm_sink_required");
        this.sink = sink;
    }
    @Override public void start(byte[] head) throws Exception {
        closeCodec(); check();
        samples = 0; outputEnded = false; ringAt = phase = pcmBytes = 0; sampleRate = 48000;
        java.util.Arrays.fill(ring, (short) 0);
        int preSkip = (head[10] & 255) | ((head[11] & 255) << 8);
        MediaFormat format = MediaFormat.createAudioFormat("audio/opus", 48000, 1);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(head));
        format.setByteBuffer("csd-1", number(preSkip * 1000000000L / 48000));
        format.setByteBuffer("csd-2", number(80000000L));
        MediaCodec created = MediaCodec.createDecoderByType("audio/opus");
        codec = created;
        try { created.configure(format, null, null, 0); created.start(); }
        catch (Exception failure) { closeCodec(); throw new IOException("OPUS_CODEC_SETUP"); }
    }
    private static ByteBuffer number(long value) {
        ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());
        buffer.putLong(value); buffer.flip(); return buffer;
    }
    @Override public void packet(byte[] packet) throws Exception {
        if (codec == null || packet == null || packet.length == 0 || outputEnded) throw new IOException("OPUS_STATE");
        int count = packetSamples(packet);
        queue(packet, 0); samples += count; drain(false);
    }
    private void queue(byte[] packet, int flags) throws Exception {
        long deadline = System.nanoTime() + 2000000000L;
        while (true) {
            check();
            int index = codec.dequeueInputBuffer(10000);
            if (index >= 0) {
                ByteBuffer buffer = codec.getInputBuffer(index);
                if (buffer == null || buffer.capacity() < packet.length) throw new IOException("OPUS_INPUT_SIZE");
                buffer.clear(); buffer.put(packet);
                codec.queueInputBuffer(index, 0, packet.length, samples * 1000000L / 48000, flags); return;
            }
            drain(false);
            if (System.nanoTime() > deadline) throw new IOException("OPUS_INPUT_TIMEOUT");
        }
    }
    private void drain(boolean awaitEos) throws Exception {
        long deadline = System.nanoTime() + 2000000000L;
        while (!outputEnded) {
            check();
            int index = codec.dequeueOutputBuffer(info, awaitEos ? 10000 : 0);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat format = codec.getOutputFormat();
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                if (format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != 1
                        || sampleRate != 48000 && sampleRate != 16000
                        || format.containsKey("pcm-encoding") && format.getInteger("pcm-encoding") != 2)
                    throw new IOException("OPUS_PCM_FORMAT");
            } else if (index >= 0) {
                try {
                    ByteBuffer output = codec.getOutputBuffer(index);
                    if (output == null || info.size < 0 || (info.size & 1) != 0) throw new IOException("OPUS_PCM_SIZE");
                    output.order(ByteOrder.LITTLE_ENDIAN); output.position(info.offset);
                    output.limit(info.offset + info.size);
                    while (output.remaining() >= 2) sample(output.getShort());
                    outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                } finally { codec.releaseOutputBuffer(index, false); }
            } else if (!awaitEos) return;
            if (awaitEos && System.nanoTime() > deadline) throw new IOException("OPUS_OUTPUT_TIMEOUT");
        }
    }
    private void sample(short sample) throws Exception {
        if (sampleRate == 16000) { emit(sample); return; }
        ring[ringAt] = sample; ringAt = (ringAt + 1) % ring.length;
        if (++phase != 3) return;
        phase = 0; double result = 0;
        for (int i = 0; i < ring.length; i++) result += taps[i] * ring[(ringAt + i) % ring.length];
        emit((short) Math.max(-32768, Math.min(32767, Math.round(result))));
    }
    private void emit(short sample) throws Exception {
        if (++pcmSamples > 30 * 16000L) throw new IOException("PCM_LIMIT");
        pcm[pcmBytes++] = (byte) sample; pcm[pcmBytes++] = (byte) (sample >> 8);
        if (pcmBytes == pcm.length) flush();
    }
    private void flush() throws Exception {
        if (pcmBytes != 0) { check(); sink.accept(pcm, 0, pcmBytes); pcmBytes = 0; }
    }
    /** Same worker as packet(): collect asynchronously-ready PCM without queuing EOS. */
    @Override public void poll() throws Exception {
        if (codec == null || outputEnded) throw new IOException("OPUS_STATE");
        drain(false);
        // An idle stream need not fill the 64 ms delivery batch before ASR can
        // observe the samples already produced by MediaCodec.
        flush();
    }
    @Override public void finish() throws Exception {
        if (codec == null) throw new IOException("OPUS_STATE");
        if (!outputEnded) { queue(new byte[0], MediaCodec.BUFFER_FLAG_END_OF_STREAM); drain(true); }
        flush(); closeCodec();
    }
    public long pcmSamples() { return pcmSamples; }
    private void check() throws IOException {
        if (Thread.currentThread().isInterrupted() || sink.cancelled()) throw new IOException("CANCELLED");
    }
    private void closeCodec() {
        MediaCodec old = codec; codec = null;
        if (old != null) { try { old.stop(); } catch (Exception ignored) { } try { old.release(); } catch (Exception ignored) { } }
    }
    @Override public void close() { closeCodec(); java.util.Arrays.fill(pcm, (byte) 0); }
    private static double[] filter() {
        double[] result = new double[63]; double sum = 0, cutoff = 7000.0 / 48000;
        for (int i = 0; i < result.length; i++) {
            int n = i - 31;
            result[i] = (n == 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * n) / (Math.PI * n))
                    * (0.54 - 0.46 * Math.cos(2 * Math.PI * i / 62)); sum += result[i];
        }
        for (int i = 0; i < result.length; i++) result[i] /= sum;
        return result;
    }
    static int packetSamples(byte[] packet) throws IOException {
        if (packet == null || packet.length == 0) throw new IOException("OPUS_PACKET");
        int toc = packet[0] & 255, config = toc >>> 3, code = toc & 3;
        int frames = code == 0 ? 1 : code != 3 ? 2 : packet.length < 2 ? 0 : packet[1] & 63;
        int frame = config >= 16 ? 120 << (config & 3) : config >= 12 ? 480 << (config & 1)
                : (config & 3) == 3 ? 2880 : 480 << (config & 3);
        if (frames == 0 || frames * frame > 5760) throw new IOException("OPUS_PACKET");
        return frames * frame;
    }
}
