package local.station.agent;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Exact 20221212 protobuf subset. Only descriptor-addressed MediaRequest commands. */
final class NativeMusicWire {
    static final int MAX_FRAME = 262144;
    private NativeMusicWire() { }

    static byte[] play(String requestId, String playerId, String streamId, String url,
                       String title, String artist) {
        validateUrl(url);
        byte[] metadata = join(text(1, bounded(title, 1024)), text(2, bounded(artist, 1024)));
        byte[] audio = join(text(1, streamId), text(2, url), integer(4, 1), bytes(9, metadata),
                text(10, "station-agent"), integer(11, 1), integer(16, 1));
        return request(requestId, playerId, streamId, bytes(1, audio));
    }
    static byte[] pause(String requestId, String playerId, String streamId) {
        return request(requestId, playerId, streamId, bytes(4, integer(1, 0)));
    }
    static byte[] resume(String requestId, String playerId, String streamId) {
        return request(requestId, playerId, streamId, integer(5, 1));
    }
    static byte[] stop(String requestId, String playerId, String streamId) {
        return request(requestId, playerId, streamId, bytes(3, new byte[0]));
    }
    static byte[] seek(String requestId, String playerId, String streamId, long positionMs) {
        if (positionMs < 0 || positionMs > 7L * 86400000) throw new IllegalArgumentException("native_seek");
        // MediaRequest.Rewind.ABSOLUTE=2; amount is a double in seconds.
        return request(requestId, playerId, streamId,
                bytes(13, join(integer(1, 2), fixedDouble(2, positionMs / 1000.0))));
    }
    private static byte[] request(String requestId, String playerId, String streamId, byte[] command) {
        owner(playerId, "station-agent-music-"); owner(streamId, "station-agent-stream-");
        byte[] descriptor = join(integer(1, 1), text(2, playerId), text(3, streamId));
        return join(text(1, bounded(requestId, 128)), bytes(8, join(command, bytes(22, descriptor))));
    }
    static ByteBuffer frame(byte[] message) {
        if (message.length < 1 || message.length > MAX_FRAME) throw new IllegalArgumentException("native_frame");
        ByteBuffer result = ByteBuffer.allocate(message.length + 4).order(ByteOrder.LITTLE_ENDIAN);
        result.putInt(message.length).put(message).flip(); return result;
    }
    static void validateUrl(String url) {
        if (url == null || url.length() > 8192 || (!url.startsWith("http://") && !url.startsWith("https://"))
                || url.indexOf('\r') >= 0 || url.indexOf('\n') >= 0)
            throw new IllegalArgumentException("native_url");
    }
    private static void owner(String id, String prefix) {
        if (id == null || !id.startsWith(prefix) || !id.substring(prefix.length()).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("native_owner");
    }
    private static String bounded(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    static final class Event {
        int event, state, type; String playerId = "", streamId = "", audioId = "", error = "";
        long positionMs = -1, durationMs = -1;
        boolean matches(String player, String stream) {
            return type == 1 && player.equals(playerId) && stream.equals(streamId)
                    && (audioId.isEmpty() || stream.equals(audioId));
        }
    }
    static List<Event> events(byte[] message) {
        List<Event> result = new ArrayList<>(); Wire outer = new Wire(message);
        while (outer.next()) {
            if (outer.field == 127 && outer.kind == 2) result.add(event(outer.value));
            // Some native endpoints send an AppState snapshot on connection.
            else if (outer.field == 33 && outer.kind == 2) appEvents(outer.value, result);
        }
        return result;
    }
    private static void appEvents(byte[] data, List<Event> result) {
        Wire state = new Wire(data);
        while (state.next()) if (state.field == 6 && state.kind == 2) result.add(event(state.value));
    }
    private static Event event(byte[] data) {
        Event event = new Event(); Wire wire = new Wire(data);
        while (wire.next()) {
            if (wire.field == 1 && wire.kind == 0) event.event = (int) wire.number;
            else if (wire.field == 2 && wire.kind == 0) event.state = (int) wire.number;
            else if (wire.field == 6 && wire.kind == 2) event.error = string(wire.value, 512);
            else if (wire.field == 4 && wire.kind == 2) {
                Wire descriptor = new Wire(wire.value);
                while (descriptor.next()) {
                    if (descriptor.field == 1 && descriptor.kind == 0) event.type = (int) descriptor.number;
                    else if (descriptor.field == 2 && descriptor.kind == 2) event.playerId = string(descriptor.value, 128);
                    else if (descriptor.field == 3 && descriptor.kind == 2) event.streamId = string(descriptor.value, 128);
                }
            } else if (wire.field == 3 && wire.kind == 2) {
                Wire audio = new Wire(wire.value);
                while (audio.next()) {
                    if (audio.field == 1 && audio.kind == 2) event.audioId = string(audio.value, 128);
                    else if (audio.field == 5 && audio.kind == 0) event.positionMs = seconds(audio.number);
                    else if (audio.field == 6 && audio.kind == 0) event.durationMs = seconds(audio.number);
                }
            }
        }
        return event;
    }
    private static long seconds(long value) { return value >= 0 && value <= 7 * 86400 ? value * 1000 : -1; }
    private static String string(byte[] data, int limit) {
        if (data.length > limit * 4) throw new IllegalArgumentException("native_string");
        return bounded(new String(data, StandardCharsets.UTF_8), limit);
    }
    static final class Wire {
        final byte[] raw; int offset, field, kind; long number; byte[] value;
        Wire(byte[] raw) {
            if (raw.length > MAX_FRAME) throw new IllegalArgumentException("native_size"); this.raw = raw;
        }
        long varint() {
            long result = 0;
            for (int count = 0; count < 10 && offset < raw.length; count++) {
                int value = raw[offset++] & 255;
                if (count == 9 && value > 1) throw new IllegalArgumentException("native_varint");
                result |= (long) (value & 127) << (count * 7);
                if (value < 128) return result;
            }
            throw new IllegalArgumentException("native_varint");
        }
        boolean next() {
            if (offset == raw.length) return false;
            long tag = varint(); field = (int) (tag >>> 3); kind = (int) (tag & 7); value = null;
            if (tag < 8 || (tag >>> 3) > 536870911) throw new IllegalArgumentException("native_field");
            if (kind == 0) number = varint();
            else {
                long size = kind == 2 ? varint() : kind == 1 ? 8 : kind == 5 ? 4 : -1;
                if (size < 0 || size > raw.length - offset) throw new IllegalArgumentException("native_size");
                value = java.util.Arrays.copyOfRange(raw, offset, offset + (int) size); offset += (int) size;
            }
            return true;
        }
    }
    static byte[] integer(int field, long value) { return join(varint(field * 8L), varint(value)); }
    static byte[] text(int field, String value) { return bytes(field, value.getBytes(StandardCharsets.UTF_8)); }
    static byte[] bytes(int field, byte[] value) { return join(varint(field * 8L + 2), varint(value.length), value); }
    static byte[] fixedDouble(int field, double value) {
        return join(varint(field * 8L + 1), ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array());
    }
    static byte[] join(byte[]... pieces) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] piece : pieces) out.write(piece, 0, piece.length); return out.toByteArray();
    }
    static byte[] varint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        do { int b = (int) (value & 127); value >>>= 7; out.write(value == 0 ? b : b | 128); } while (value != 0);
        return out.toByteArray();
    }
}
