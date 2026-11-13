package local.station.agent;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal local uniproxy transport for native Quasar.
 *
 * The Station already supports {@code uniProxyUrl=ws://127.0.0.1:17681/uni.ws}.
 * This server accepts that WebSocket, answers the System handshake, and records
 * incoming Ogg/Opus microphone frames. It deliberately does not synthesize a
 * voice yet: the first goal is to prove that the native stack can use a local
 * server on the Station without PC, root or VPN.
 */
final class LocalVoiceServer implements Runnable {
    static final int PORT = 17681;
    private static final String TAG = "StationLocalVoice";
    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_HANDSHAKE = 16 * 1024;
    private static final int MAX_FRAME = 4 * 1024 * 1024;

    private volatile boolean running;
    private ServerSocket listener;
    private Thread thread;
    private volatile String lastError = "";

    private static final AtomicInteger connections = new AtomicInteger();
    private static final AtomicInteger textMessages = new AtomicInteger();
    private static final AtomicInteger audioFrames = new AtomicInteger();
    private static final AtomicInteger audioBytes = new AtomicInteger();
    private static volatile String lastEvent = "";

    static int connections() { return connections.get(); }
    static int textMessages() { return textMessages.get(); }
    static int audioFrames() { return audioFrames.get(); }
    static int audioBytes() { return audioBytes.get(); }
    static String lastEvent() { return lastEvent; }

    synchronized void start() {
        if (thread != null) return;
        running = true;
        lastError = "";
        thread = new Thread(this, "StationLocalVoice");
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void stop() {
        running = false;
        ServerSocket current = listener;
        listener = null;
        if (current != null) {
            try { current.close(); } catch (IOException ignored) { }
        }
        thread = null;
    }

    boolean listening() {
        return running && listener != null && !listener.isClosed();
    }

    String lastError() { return lastError; }

    @Override public void run() {
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 8);
            listener = socket;
            Log.i(TAG, "local uniproxy listening on 127.0.0.1:" + PORT);
            while (running) {
                Socket client = socket.accept();
                connections.incrementAndGet();
                Thread worker = new Thread(() -> handle(client), "StationLocalVoiceConn");
                worker.setDaemon(true);
                worker.start();
            }
        } catch (Exception error) {
            lastError = error.getClass().getSimpleName();
            if (running) Log.e(TAG, "listener stopped", error);
        } finally {
            listener = null;
        }
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(30000);
            InputStream input = new BufferedInputStream(client.getInputStream());
            OutputStream output = client.getOutputStream();
            if (!handshake(input, output)) {
                Log.w(TAG, "websocket handshake rejected");
                return;
            }
            Log.i(TAG, "native uniproxy connected");
            frameLoop(input, output);
        } catch (Exception error) {
            if (running) Log.w(TAG, "session ended: " + error.getClass().getSimpleName());
        } finally {
            try { client.close(); } catch (IOException ignored) { }
            Log.i(TAG, "native uniproxy disconnected");
        }
    }

    private boolean handshake(InputStream input, OutputStream output) throws Exception {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (head.size() < MAX_HANDSHAKE) {
            int value = input.read();
            if (value < 0) return false;
            head.write(value);
            if (value == '\r' || value == '\n') {
                if (value == '\n' && matched >= 3) break;
                matched = value == '\n' ? matched + 1 : matched;
            } else {
                matched = 0;
            }
        }
        String request = new String(head.toByteArray(), StandardCharsets.ISO_8859_1);
        String key = header(request, "sec-websocket-key");
        if (key == null || key.isEmpty()) return false;

        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] digest = sha1.digest((key + WEBSOCKET_GUID).getBytes(StandardCharsets.ISO_8859_1));
        String accept = Base64.getEncoder().encodeToString(digest);
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        output.write(response.getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
        lastEvent = "handshake";
        return true;
    }

    private static String header(String request, String name) {
        String prefix = name.toLowerCase(Locale.US) + ":";
        for (String line : request.split("\r?\n")) {
            if (line.toLowerCase(Locale.US).startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    private void frameLoop(InputStream input, OutputStream output) throws Exception {
        while (running) {
            int first = input.read();
            int second = input.read();
            if (first < 0 || second < 0) throw new EOFException();
            boolean fin = (first & 0x80) != 0;
            int opcode = first & 0x0f;
            boolean masked = (second & 0x80) != 0;
            long length = second & 0x7f;
            if (length == 126) {
                length = ((long) readByte(input) << 8) | readByte(input);
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) length = (length << 8) | readByte(input);
            }
            if (length < 0 || length > MAX_FRAME) {
                Log.w(TAG, "frame too large: " + length);
                close(output, 1009);
                return;
            }
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(input, mask);
            }
            byte[] payload = new byte[(int) length];
            readFully(input, payload);
            if (masked) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i & 3];
                }
            }

            if (opcode == 0x8) {
                close(output, 1000);
                return;
            }
            if (opcode == 0x9) {
                sendFrame(output, 0xA, payload);
                continue;
            }
            if (opcode == 0xA) continue;
            if (opcode == 0x1) {
                textMessages.incrementAndGet();
                onText(payload, output);
            } else if (opcode == 0x2) {
                audioFrames.incrementAndGet();
                audioBytes.addAndGet(payload.length);
                if (audioFrames.get() == 1 || audioFrames.get() % 25 == 0) {
                    lastEvent = "audio " + audioFrames.get() + " frames/" + audioBytes.get() + " bytes";
                    Log.i(TAG, "mic audio: frame=" + audioFrames.get()
                            + " bytes=" + payload.length + " total=" + audioBytes.get());
                }
            }
            if (!fin) {
                // Native Quasar sends small complete frames; fragmentation is not expected.
                Log.w(TAG, "fragmented frame ignored");
                return;
            }
        }
    }

    private void onText(byte[] payload, OutputStream output) {
        try {
            String text = new String(payload, StandardCharsets.UTF_8);
            JSONObject message = new JSONObject(text);
            JSONObject header = message.optJSONObject("event") != null
                    ? message.getJSONObject("event").optJSONObject("header")
                    : message.optJSONObject("header");
            String namespace = header == null ? "" : header.optString("namespace");
            String name = header == null ? "" : header.optString("name");
            String ref = header == null ? "" : header.optString("messageId");
            lastEvent = "text " + namespace + "." + name;
            Log.i(TAG, "uniproxy text: " + namespace + "." + name + " bytes=" + payload.length);
            if ("System".equals(namespace) && "SynchronizeState".equals(name)) {
                String sessionId = "station-local-" + Long.toHexString(System.currentTimeMillis());
                sendText(output, "{\"directive\":{\"header\":{\"namespace\":\"System\","
                        + "\"name\":\"SynchronizeStateResponse\",\"refMessageId\":\"" + ref + "\"},"
                        + "\"payload\":{\"SessionId\":\"" + sessionId + "\"}}}");
                long ack = System.currentTimeMillis();
                sendText(output, "{\"directive\":{\"header\":{\"namespace\":\"System\","
                        + "\"name\":\"Push\",\"messageId\":\"local-" + ack + "\"},"
                        + "\"payload\":{\"analytics_meta_info\":{\"request_id\":\"" + ack + "$" + ref
                        + "\"},\"directives\":[{\"name\":\"notify\",\"payload\":{\"version_id\":\"0\"}}]}}}");
                lastEvent = "synchronized " + sessionId;
            }
        } catch (Exception error) {
            Log.w(TAG, "bad text frame", error);
        }
    }

    private static void sendText(OutputStream output, String text) throws IOException {
        sendFrame(output, 0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void close(OutputStream output, int code) {
        try {
            byte[] body = new byte[]{(byte) (code >> 8), (byte) code};
            sendFrame(output, 0x8, body);
        } catch (IOException ignored) { }
    }

    private static synchronized void sendFrame(OutputStream output, int opcode, byte[] payload)
            throws IOException {
        output.write(0x80 | opcode);
        int length = payload.length;
        if (length < 126) {
            output.write(length);
        } else if (length <= 0xffff) {
            output.write(126);
            output.write((length >> 8) & 0xff);
            output.write(length & 0xff);
        } else {
            output.write(127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                output.write((length >> shift) & 0xff);
            }
        }
        output.write(payload);
        output.flush();
    }

    private static int readByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) throw new EOFException();
        return value;
    }

    private static void readFully(InputStream input, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int count = input.read(buffer, offset, buffer.length - offset);
            if (count < 0) throw new EOFException();
            offset += count;
        }
    }
}
