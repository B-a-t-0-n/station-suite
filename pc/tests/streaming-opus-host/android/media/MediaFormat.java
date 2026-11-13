package android.media;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public final class MediaFormat {
    public static final String KEY_SAMPLE_RATE = "sample-rate", KEY_CHANNEL_COUNT = "channel-count";
    private final Map<String, Object> values = new HashMap<>();
    public static MediaFormat createAudioFormat(String mime, int rate, int channels) {
        MediaFormat format = new MediaFormat();
        format.setInteger(KEY_SAMPLE_RATE, rate); format.setInteger(KEY_CHANNEL_COUNT, channels);
        return format;
    }
    public void setInteger(String key, int value) { values.put(key, value); }
    public int getInteger(String key) { return (Integer) values.get(key); }
    public boolean containsKey(String key) { return values.containsKey(key); }
    public void setByteBuffer(String key, ByteBuffer value) { values.put(key, value); }
    public ByteBuffer getByteBuffer(String key) { return (ByteBuffer) values.get(key); }
}
