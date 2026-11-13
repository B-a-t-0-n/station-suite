package local.station.agent;

import org.json.JSONObject;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Passive syncd snapshot. Never obtains an output stream or sends an IPC command. */
final class NativeDisplaySettings {
    static final int MAX_BYTES = 65536;
    private NativeDisplaySettings() { }

    static JSONObject read(long deadline) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 9897), remaining(deadline, 350));
            InputStream input = socket.getInputStream();
            int received = 0;
            while (received < MAX_BYTES) {
                byte[] header = read(input, 4, socket, deadline);
                long length = 0;
                for (int i = 0; i < 4; i++) length |= (long) (header[i] & 255) << (8 * i);
                if (length < 1 || length > MAX_BYTES - received - 4) throw new IllegalArgumentException("snapshot_bound");
                byte[] frame = read(input, (int) length, socket, deadline);
                received += 4 + (int) length;
                JSONObject result = parseFrame(frame);
                if (result != null) return result;
            }
            throw new IllegalArgumentException("snapshot_missing");
        }
    }

    static int remaining(long deadline, int maximum) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new IllegalStateException("deadline");
        return (int) Math.min(maximum, Math.max(1, (nanos + 999999) / 1000000));
    }

    private static byte[] read(InputStream input, int count, Socket socket, long deadline) throws Exception {
        byte[] result = new byte[count]; int offset = 0;
        while (offset < count) {
            socket.setSoTimeout(remaining(deadline, 600));
            int n = input.read(result, offset, count - offset);
            if (n <= 0) throw new IllegalArgumentException("snapshot_eof");
            offset += n;
        }
        return result;
    }

    /** Null means this frame has no current user_config_update. Cached field106 is not proof of application. */
    static JSONObject parseFrame(byte[] frame) throws Exception {
        if (frame == null || frame.length < 1 || frame.length > MAX_BYTES) throw new IllegalArgumentException("snapshot_bound");
        Wire envelope = new Wire(frame);
        JSONObject found = null;
        while (envelope.next()) {
            if (envelope.field != 63 || envelope.kind != 2) continue;
            Wire user = new Wire(envelope.value); byte[] config = null;
            while (user.next()) if (user.field == 2 && user.kind == 2) {
                if (config != null) throw new IllegalArgumentException("duplicate_config");
                config = user.value;
            }
            if (config == null || found != null) throw new IllegalArgumentException("snapshot_config");
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(config)).toString();
            found = current(StationDisplaySettings.parseObject(json));
        }
        return found;
    }

    static JSONObject current(JSONObject config) throws Exception {
        JSONObject device = config.optJSONObject("device_config");
        JSONObject led = device == null ? null : device.optJSONObject("led");
        JSONObject time = led == null ? null : led.optJSONObject("time_visualization");
        Object size = time == null ? null : time.opt("size");
        boolean explicit = StationDisplaySettings.clockSize(size);
        JSONObject result = new JSONObject().put("clockSize", explicit ? size : "middle")
                .put("clockSizeExplicit", explicit);
        JSONObject brightness = led == null ? null : led.optJSONObject("brightness");
        if (StationDisplaySettings.brightness(brightness)) result.put("brightness", new JSONObject()
                .put("auto", brightness.get("auto")).put("value", brightness.get("value")));
        // Only expose a bounded music selector; never return the native config/catalog itself.
        JSONObject system = config.optJSONObject("system_config");
        JSONObject animation = system == null ? null : system.optJSONObject("spectrogramAnimation");
        JSONObject selector = led == null ? null : led.optJSONObject("music_equalizer_visualization");
        String music = "unknown"; boolean musicExplicit = false;
        if (animation != null && Boolean.FALSE.equals(animation.opt("enabled"))) {
            music = "clock";
            musicExplicit = selector != null && Boolean.FALSE.equals(selector.opt("auto"))
                    && "clock".equals(selector.opt("style"));
        } else if (animation != null && Boolean.TRUE.equals(animation.opt("enabled")) && selector != null) {
            Object auto = selector.opt("auto"), style = selector.opt("style");
            String selected = Boolean.TRUE.equals(auto) ? "random" : Boolean.FALSE.equals(auto)
                    && style instanceof String ? (String) style : "unknown";
            if (!"clock".equals(selected) && StationDisplaySettings.musicStyle(selected)
                    && presetKnown(animation.optJSONObject("presets"), selected)) {
                music = selected; musicExplicit = true;
            }
        }
        result.put("musicStyle", music).put("musicStyleExplicit", musicExplicit);
        return result;
    }

    private static boolean presetKnown(JSONObject presets, String id) {
        if (presets == null) return false;
        JSONObject preset = presets.optJSONObject(id);
        if (preset == null || !id.equals(preset.opt("type"))) return false;
        if (!"random".equals(id)) return true;
        org.json.JSONArray ids = preset.optJSONArray("presets");
        if (ids == null || ids.length() != 4) return false;
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < ids.length(); i++) {
            Object value = ids.opt(i);
            if (!(value instanceof String) || "clock".equals(value) || "random".equals(value)
                    || !StationDisplaySettings.musicStyle(value) || !seen.add((String) value)
                    || !presetKnown(presets, (String) value)) return false;
        }
        return true;
    }

    private static final class Wire {
        final byte[] raw; int offset, field, kind; byte[] value;
        Wire(byte[] raw) { this.raw = raw; }
        long varint() {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (offset >= raw.length) throw new IllegalArgumentException("wire_truncated");
                int n = raw[offset++] & 255;
                if (shift == 63 && n > 1) throw new IllegalArgumentException("wire_overflow");
                value |= (long) (n & 127) << shift;
                if (n < 128) return value;
            }
            throw new IllegalArgumentException("wire_varint");
        }
        boolean next() {
            if (offset == raw.length) return false;
            long tag = varint();
            if (tag <= 0 || (tag >>> 3) > 536870911) throw new IllegalArgumentException("wire_tag");
            field = (int) (tag >>> 3); kind = (int) (tag & 7); value = null;
            if (field < 1) throw new IllegalArgumentException("wire_field");
            if (kind == 0) varint();
            else {
                long size = kind == 2 ? varint() : kind == 1 ? 8 : kind == 5 ? 4 : -1;
                if (size < 0 || size > raw.length - offset) throw new IllegalArgumentException("wire_size");
                value = Arrays.copyOfRange(raw, offset, offset + (int) size); offset += (int) size;
            }
            return true;
        }
    }
}
