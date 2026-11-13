package local.station.agent;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Minimal ExternalCommandMessage client for the Station's external_commandsd. */
final class NativeCommands {
    private NativeCommands() { }

    static boolean send(String name) {
        return send(name, null);
    }

    /** Own finite front-panel animation only; no caller-supplied command name. */
    static boolean display(String payload) {
        if (payload == null || payload.length() > 2048) return false;
        return send("draw_led_screen", payload);
    }

    private static boolean send(String name, String payload) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", 9891), 1200);
            byte[] external = concat(fieldBytes(1, name.getBytes(StandardCharsets.UTF_8)),
                                     fieldVarint(10, 1));
            if (payload != null) external = concat(external,
                    fieldBytes(2, payload.getBytes(StandardCharsets.UTF_8)));
            byte[] message = fieldBytes(44, external);
            if (payload != null) message = concat(fieldBytes(1,
                    java.util.UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)), message);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.write(message.length & 0xff);
            output.write((message.length >>> 8) & 0xff);
            output.write((message.length >>> 16) & 0xff);
            output.write((message.length >>> 24) & 0xff);
            output.write(message);
            output.flush();
            return true;
        } catch (Exception error) {
            return false;
        } finally {
            try { socket.close(); } catch (Exception ignored) { }
        }
    }

    private static byte[] varint(int value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            int b = value & 0x7f;
            value >>>= 7;
            if (value != 0) out.write(b | 0x80);
            else { out.write(b); return out.toByteArray(); }
        }
    }

    private static byte[] fieldVarint(int number, int value) {
        return concat(varint(number << 3), varint(value));
    }

    private static byte[] fieldBytes(int number, byte[] data) {
        return concat(concat(varint((number << 3) | 2), varint(data.length)), data);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }
}
