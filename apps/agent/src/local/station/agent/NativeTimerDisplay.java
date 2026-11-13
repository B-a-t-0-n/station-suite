package local.station.agent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Incremental native timers. Add/remove are addressed; native STOP stops all ringing native signals. */
final class NativeTimerDisplay {
    static final String PREFIX = "station-agent-timer-", LEGACY_ID = "station-agent-display-timer-v1";
    private NativeTimerDisplay() { }

    static String ownedId(String id) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("timer_id");
        return PREFIX + id;
    }
    static boolean show(String id, long wall, long left) { return send(encodeAdd(id, wall, left)); }
    static boolean clear(String owned) { return send(encodeRemove(owned)); }
    static boolean stopSignals() { return send(envelope(bytes(4, new byte[0]))); }

    static byte[] encodeAdd(String id, long wall, long left) {
        if (left <= 0 || left > 86400000L || wall <= 1000 || wall > Long.MAX_VALUE - left)
            throw new IllegalArgumentException("timer_bounds");
        long seconds = (left + 999) / 1000, start = wall + left - seconds * 1000;
        byte[] alarm = concat(bytes(1, ownedId(id).getBytes(StandardCharsets.UTF_8)),
                integer(2, 2), integer(3, start), integer(4, seconds));
        return envelope(bytes(1, alarm));
    }
    static byte[] encodeRemove(String owned) {
        if (!LEGACY_ID.equals(owned) && (owned == null || !owned.startsWith(PREFIX)
                || !owned.equals(ownedId(owned.substring(PREFIX.length())))))
            throw new IllegalArgumentException("timer_id");
        return envelope(bytes(2, owned.getBytes(StandardCharsets.UTF_8)));
    }
    private static byte[] envelope(byte[] message) {
        return concat(bytes(1, UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)), bytes(23, message));
    }
    private static Socket connect() throws Exception {
        Socket socket = new Socket();
        try { socket.connect(new InetSocketAddress("127.0.0.1", 9886), 1000); return socket; }
        catch (Exception unavailable) { socket.close(); throw unavailable; }
    }
    private static boolean send(byte[] message) {
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            for (int shift = 0; shift < 32; shift += 8) out.write((message.length >>> shift) & 255);
            out.write(message); out.flush();
            // The initial state greeting is not an acknowledgement of the following mutation.
            long until = System.nanoTime() + 500000000L; byte[] reply = new byte[256];
            while (System.nanoTime() < until) {
                socket.setSoTimeout((int) Math.max(1, (until - System.nanoTime() + 999999) / 1000000));
                try { if (socket.getInputStream().read(reply) < 0) break; }
                catch (SocketTimeoutException done) { break; }
            }
            return true;
        } catch (Exception unavailable) { return false; }
    }

    /** Snapshot on connection, without a mutation. Null means unavailable, never an empty-success guess. */
    static Map<String, Long> ownedTimers() {
        try (Socket socket = connect()) {
            socket.setSoTimeout(750);
            InputStream in = socket.getInputStream();
            byte[] header = read(in, 4);
            int length = 0; for (int i = 0; i < 4; i++) length |= (header[i] & 255) << (8 * i);
            if (length < 1 || length > 262144) return null;
            Wire message = new Wire(read(in, length)); Map<String, Long> timers = new LinkedHashMap<>();
            boolean found = false;
            while (message.next()) if (message.field == 46 && message.kind == 2) {
                found = true; Wire state = new Wire(message.value);
                while (state.next()) if (state.field == 1 && state.kind == 2) {
                    Wire alarm = new Wire(state.value); String id = ""; long start = 0, duration = 0;
                    while (alarm.next()) {
                        if (alarm.field == 1 && alarm.kind == 2) id = new String(alarm.value, StandardCharsets.UTF_8);
                        else if (alarm.field == 3 && alarm.kind == 0) start = alarm.number;
                        else if (alarm.field == 4 && alarm.kind == 0) duration = alarm.number;
                    }
                    if (id.startsWith(PREFIX) || LEGACY_ID.equals(id)) {
                        encodeRemove(id); // Validate the namespace and UUID before retaining a cleanup target.
                        if (start >= 0 && duration >= 0 && duration <= 86400 && start <= Long.MAX_VALUE - duration * 1000)
                            timers.put(id, start + duration * 1000);
                    }
                }
            }
            return found ? timers : null;
        } catch (Exception unavailable) { return null; }
    }
    private static byte[] read(InputStream in, int count) throws Exception {
        byte[] result = new byte[count]; int offset = 0;
        while (offset < count) { int n = in.read(result, offset, count - offset); if (n < 0) throw new Exception("ipc_eof"); offset += n; }
        return result;
    }
    private static final class Wire {
        final byte[] raw; int offset, field, kind; long number; byte[] value;
        Wire(byte[] raw) { this.raw = raw; }
        long varint() {
            long result = 0;
            for (int shift = 0; shift < 64 && offset < raw.length; shift += 7) {
                int n = raw[offset++] & 255; result |= (long) (n & 127) << shift;
                if (n < 128) return result;
            }
            throw new IllegalArgumentException("ipc_varint");
        }
        boolean next() {
            if (offset == raw.length) return false;
            long tag = varint(); field = (int) (tag >>> 3); kind = (int) (tag & 7); value = null;
            if (field < 1) throw new IllegalArgumentException("ipc_field");
            if (kind == 0) number = varint();
            else {
                long size = kind == 2 ? varint() : kind == 1 ? 8 : kind == 5 ? 4 : -1;
                if (size < 0 || size > raw.length - offset) throw new IllegalArgumentException("ipc_size");
                value = java.util.Arrays.copyOfRange(raw, offset, offset + (int) size); offset += (int) size;
            }
            return true;
        }
    }
    private static byte[] integer(int field, long value) { return concat(varint(field * 8), varint(value)); }
    private static byte[] bytes(int field, byte[] value) { return concat(varint(field * 8 + 2), varint(value.length), value); }
    private static byte[] concat(byte[]... pieces) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] piece : pieces) out.write(piece, 0, piece.length);
        return out.toByteArray();
    }
    private static byte[] varint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        do { int n = (int) (value & 127); value >>>= 7; out.write(value == 0 ? n : n | 128); } while (value != 0);
        return out.toByteArray();
    }
}
