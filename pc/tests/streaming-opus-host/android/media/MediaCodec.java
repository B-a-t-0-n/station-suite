package android.media;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Deterministic fake: caller supplies artificial PCM, never decodes or plays audio. */
public final class MediaCodec {
    public static final int INFO_OUTPUT_FORMAT_CHANGED = -2, BUFFER_FLAG_END_OF_STREAM = 4;
    public static int outputRate = 48000, outputChannels = 1, outputEncoding = 2;
    public static boolean omitEos, forbidInput, asynchronousOutput;
    public static final List<byte[]> nextPcm = new ArrayList<>();
    public static final List<MediaCodec> created = new ArrayList<>();
    public final List<Long> timestamps = new ArrayList<>();
    public final List<Integer> queuedFlags = new ArrayList<>();
    public final List<Long> outputTimeouts = new ArrayList<>();
    public boolean stopped, released, started;
    public MediaFormat configured;
    private final ByteBuffer input = ByteBuffer.allocate(65536);
    private boolean formatSent;
    private int pcmAt;
    private byte[] output;
    private byte[] delayedOutput;
    private boolean eosPending, eosCurrent;
    public static final class BufferInfo { public int offset, size, flags; public long presentationTimeUs; }
    public static void reset() {
        outputRate = 48000; outputChannels = 1; outputEncoding = 2;
        omitEos = forbidInput = asynchronousOutput = false; nextPcm.clear(); created.clear();
    }
    public static MediaCodec createDecoderByType(String mime) {
        if (!"audio/opus".equals(mime)) throw new AssertionError("unexpected_codec");
        MediaCodec result = new MediaCodec(); created.add(result); return result;
    }
    public void configure(MediaFormat format, Object surface, Object crypto, int flags) { configured = format; }
    public void start() { started = true; }
    public void stop() { stopped = true; }
    public void release() { released = true; }
    public int dequeueInputBuffer(long timeout) { return forbidInput ? -1 : 0; }
    public ByteBuffer getInputBuffer(int index) { return input; }
    public void queueInputBuffer(int index, int offset, int size, long time, int flags) {
        timestamps.add(time); queuedFlags.add(flags);
        if ((flags & BUFFER_FLAG_END_OF_STREAM) != 0) { if (!omitEos) eosPending = true; }
        else if (pcmAt < nextPcm.size()) {
            if (asynchronousOutput) delayedOutput = nextPcm.get(pcmAt++);
            else output = nextPcm.get(pcmAt++);
        }
    }
    /** Models output becoming ready after packet()'s immediate zero-timeout dequeue. */
    public void releaseDelayedOutput() { output = delayedOutput; delayedOutput = null; }
    public int dequeueOutputBuffer(BufferInfo info, long timeout) {
        if (outputTimeouts.size() < 256) outputTimeouts.add(timeout);
        if (!formatSent) { formatSent = true; return INFO_OUTPUT_FORMAT_CHANGED; }
        if (output != null) { info.offset = 0; info.size = output.length; info.flags = 0; eosCurrent = false; return 0; }
        if (eosPending) { info.offset = 0; info.size = 0; info.flags = BUFFER_FLAG_END_OF_STREAM; eosCurrent = true; return 0; }
        return -1;
    }
    public ByteBuffer getOutputBuffer(int index) { return ByteBuffer.wrap(eosCurrent ? new byte[0] : output); }
    public void releaseOutputBuffer(int index, boolean render) { if (eosCurrent) eosPending = false; else output = null; }
    public MediaFormat getOutputFormat() {
        MediaFormat format = MediaFormat.createAudioFormat("audio/raw", outputRate, outputChannels);
        format.setInteger("pcm-encoding", outputEncoding); return format;
    }
}
