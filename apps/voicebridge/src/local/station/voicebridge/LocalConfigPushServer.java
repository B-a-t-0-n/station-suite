package local.station.voicebridge;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import org.json.JSONObject;

/** Local native update_config transport. Creating it does not start a listener. */
final class LocalConfigPushServer {
    static final int PORT = 17684, MAX_CLIENTS = 2, MAX_HEAD = 8192, MAX_FRAME = 4096;
    static final String PATH = "/station-config-sync";
    static final String HOST = "station-config.quasar.yandex.net";
    static final String SIGN = "station-local-config";
    static final String UPDATE = "{\"service\":\"quasar-realtime\",\"operation\":\"update_config\",\"message\":\"{}\"}";
    static final String HEARTBEAT = "{\"service\":\"quasar-realtime\",\"operation\":\"ping\",\"server-interval-sec\":30}";
    private final AtomicLong revision = new AtomicLong(1), connections = new AtomicLong();
    private final AtomicLong notifications = new AtomicLong(), heartbeats = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong lifecycle = new AtomicLong();
    private final CopyOnWriteArrayList<Socket> clients = new CopyOnWriteArrayList<>();
    private final int port;
    private final SSLContext tls;
    private volatile boolean running;
    private volatile ServerSocket listener;
    private volatile ScheduledThreadPoolExecutor deadlines;
    private volatile String lastRejectCode = "none";
    private volatile JSONObject lastRejectDetails = new JSONObject();

    LocalConfigPushServer() { this(null, PORT); }
    LocalConfigPushServer(SSLContext tls) { this(requireTls(tls), PORT); }
    // An ephemeral loopback port makes host tests independent of the native port.
    LocalConfigPushServer(int port) { this(null, port); }
    LocalConfigPushServer(SSLContext tls, int port) {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("port");
        this.port = port; this.tls = tls;
    }
    private static SSLContext requireTls(SSLContext tls) {
        if (tls == null) throw new IllegalArgumentException("tls context"); return tls;
    }
    synchronized void start() {
        if (running) return;
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "StationLocalPushDeadline"); thread.setDaemon(true); return thread;
        });
        timer.setRemoveOnCancelPolicy(true); deadlines = timer;
        running = true;
        long generation = lifecycle.incrementAndGet();
        Thread thread = new Thread(() -> serve(generation, timer), "StationLocalConfigPush");
        thread.setDaemon(true); thread.start();
    }
    // The config source invokes this only after the new response is committed.
    void signalRefresh() { revision.incrementAndGet(); }
    synchronized void stop() {
        running = false;
        lifecycle.incrementAndGet();
        close(listener);
        for (Socket client : clients) close(client);
        ScheduledThreadPoolExecutor timer = deadlines; deadlines = null;
        if (timer != null) timer.shutdownNow();
    }
    JSONObject status() throws Exception {
        ServerSocket current = listener;
        return new JSONObject().put("listening", current != null && !current.isClosed())
                .put("clients", clients.size()).put("connections", connections.get())
                .put("notifications", notifications.get()).put("heartbeats", heartbeats.get())
                .put("rejected", rejected.get()).put("requestedRevision", revision.get())
                .put("tls", tls != null)
                .put("lastRejectCode", lastRejectCode).put("lastRejectDetails", new JSONObject(lastRejectDetails.toString()));
    }
    int boundPort() {
        ServerSocket current = listener;
        return current == null || current.isClosed() ? -1 : current.getLocalPort();
    }
    private boolean isRunning(long generation) { return running && lifecycle.get() == generation; }
    private ServerSocket createListener() throws Exception {
        if (tls == null) return new ServerSocket();
        SSLServerSocket server = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket();
        java.util.ArrayList<String> protocols = new java.util.ArrayList<>();
        for (String protocol : server.getSupportedProtocols())
            if ("TLSv1.2".equals(protocol) || "TLSv1.3".equals(protocol)) protocols.add(protocol);
        if (protocols.isEmpty()) { close(server); throw new IllegalStateException("tls protocols"); }
        server.setEnabledProtocols(protocols.toArray(new String[0])); return server;
    }
    private void serve(long generation, ScheduledThreadPoolExecutor timer) {
        while (isRunning(generation)) {
            try (ServerSocket server = createListener()) {
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), MAX_CLIENTS);
                server.setSoTimeout(1000); listener = server;
                while (isRunning(generation)) {
                    Socket socket;
                    try { socket = server.accept(); }
                    catch (SocketTimeoutException idle) { continue; }
                    if (!isRunning(generation) || !socket.getInetAddress().isLoopbackAddress() || clients.size() >= MAX_CLIENTS) {
                        lastRejectCode = !isRunning(generation) ? "not_running" : !socket.getInetAddress().isLoopbackAddress() ? "non_loopback" : "client_limit";
                        lastRejectDetails = new JSONObject(); rejected.incrementAndGet(); close(socket); continue;
                    }
                    clients.add(socket);
                    Thread worker = new Thread(() -> {
                        try { handle(socket, generation, timer); }
                        catch (Exception unavailable) {
                            if (unavailable instanceof HandshakeRejected) {
                                lastRejectCode = ((HandshakeRejected) unavailable).code;
                                lastRejectDetails = ((HandshakeRejected) unavailable).details;
                            } else { lastRejectCode = rejectCode(unavailable); lastRejectDetails = failureDiagnostics(unavailable, "frame"); }
                            rejected.incrementAndGet();
                        }
                        finally { clients.remove(socket); close(socket); }
                    }, "StationLocalConfigPushClient");
                    worker.setDaemon(true); worker.start();
                }
            } catch (Exception unavailable) {
                if (isRunning(generation)) try { Thread.sleep(1000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            } finally { if (lifecycle.get() == generation) listener = null; }
        }
    }
    private void handle(Socket socket, long generation, ScheduledThreadPoolExecutor timer) throws Exception {
        if (tls != null) {
            ScheduledFuture<?> cutoff = timer.schedule(() -> close(socket), 2000, TimeUnit.MILLISECONDS);
            try { socket.setSoTimeout(2000); ((SSLSocket) socket).startHandshake(); }
            catch (Exception unavailable) { throw new HandshakeRejected("tls_handshake_failed",
                    failureDiagnostics(unavailable, "tls").put("tls", true).put("handshakeComplete", false)); }
            finally { cutoff.cancel(false); }
        }
        InputStream input = socket.getInputStream(); OutputStream output = socket.getOutputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        long deadline = System.nanoTime() + 2000000000L;
        int matched = 0;
        try {
            while (head.size() < MAX_HEAD && matched < 4) {
                long left = deadline - System.nanoTime();
                if (left <= 0) throw new IllegalArgumentException("upgrade timeout");
                socket.setSoTimeout((int) Math.max(1, Math.min(1000, left / 1000000)));
                int value = input.read();
                if (value < 0) throw new IllegalArgumentException("upgrade eof");
                head.write(value);
                int expected = (matched % 2 == 0) ? 13 : 10;
                matched = value == expected ? matched + 1 : value == 13 ? 1 : 0;
            }
            if (matched != 4) throw new IllegalArgumentException("upgrade size");
        } catch (Exception unavailable) {
            throw new HandshakeRejected(rejectCode(unavailable), mergeDiagnostics(
                    headDiagnostics(head.toByteArray(), matched), failureDiagnostics(unavailable, "header")));
        }
        long nowSeconds = System.currentTimeMillis() / 1000;
        String raw = new String(head.toByteArray(), StandardCharsets.US_ASCII);
        String accept; long expiry;
        try { accept = handshake(raw, socket.getLocalPort(), nowSeconds); expiry = requestExpiry(raw); }
        catch (Exception invalid) { throw new HandshakeRejected(rejectCode(invalid), mergeDiagnostics(
                handshakeDiagnostics(raw, socket.getLocalPort(), nowSeconds), failureDiagnostics(invalid, "upgrade"))); }
        output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); output.flush();
        connections.incrementAndGet(); socket.setSoTimeout(100);
        long sent = 0, heartbeatAt = 0;
        byte[] pending = new byte[0], block = new byte[2048];
        while (isRunning(generation) && !socket.isClosed()) {
            if (System.currentTimeMillis() / 1000 >= expiry) { writeFrame(output, 8, new byte[0]); return; }
            long now = System.nanoTime();
            if (now >= heartbeatAt) {
                writeFrame(output, 1, HEARTBEAT.getBytes(StandardCharsets.UTF_8));
                heartbeats.incrementAndGet(); heartbeatAt = now + 10000000000L;
            }
            long requested = revision.get();
            if (sent != requested) {
                writeFrame(output, 1, UPDATE.getBytes(StandardCharsets.UTF_8));
                sent = requested; notifications.incrementAndGet();
            }
            try {
                int count = input.read(block); if (count < 0) return;
                if (pending.length + count > MAX_FRAME + 14 + block.length) throw new IllegalArgumentException("frame buffer");
                byte[] combined = Arrays.copyOf(pending, pending.length + count);
                System.arraycopy(block, 0, combined, pending.length, count);
                int offset = 0;
                while (offset < combined.length) {
                    Frame frame = clientFrame(combined, offset);
                    if (frame == null) break;
                    offset += frame.bytes;
                    if (frame.opcode == 8) { writeFrame(output, 8, new byte[0]); return; }
                    if (frame.opcode == 9) writeFrame(output, 10, frame.payload);
                    // Client text/binary/pong cannot request any action or config change.
                }
                pending = Arrays.copyOfRange(combined, offset, combined.length);
            } catch (SocketTimeoutException idle) { /* Check revision and heartbeat within100ms. */ }
        }
    }
    static String handshake(String raw, int localPort, long nowSeconds) throws Exception {
        if (raw.length() > MAX_HEAD || !raw.endsWith("\r\n\r\n")) throw new IllegalArgumentException("upgrade bounds");
        String[] lines = raw.split("\r\n");
        String[] first = lines[0].split(" ", -1);
        if (first.length != 3 || !"GET".equals(first[0]) || !"HTTP/1.1".equals(first[2])) throw new IllegalArgumentException("request");
        Map<String,String> query = query(first[1]);
        if (!SIGN.equals(query.get("sign")) || !admittedService(query.get("service"))
                || !"q".equals(query.get("client"))) throw new IllegalArgumentException("local lease");
        long expiry = parseExpiry(query.get("ts"));
        if (expiry <= nowSeconds || expiry > nowSeconds + 3605L) throw new IllegalArgumentException("lease time");
        Map<String,String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':'); if (colon <= 0) throw new IllegalArgumentException("header");
            String key = lines[i].substring(0, colon).toLowerCase(Locale.ROOT);
            if (headers.put(key, lines[i].substring(colon + 1).trim()) != null) throw new IllegalArgumentException("duplicate header");
        }
        if (!admittedHost(headers.get("host"), localPort)
                || !"websocket".equalsIgnoreCase(headers.get("upgrade"))
                || !"13".equals(headers.get("sec-websocket-version"))
                || !Arrays.asList(headers.getOrDefault("connection", "").toLowerCase(Locale.ROOT).split("\\s*,\\s*")).contains("upgrade")
                || headers.containsKey("content-length") || headers.containsKey("transfer-encoding")) throw new IllegalArgumentException("upgrade headers");
        String key = headers.get("sec-websocket-key");
        if (key == null || Base64.getDecoder().decode(key).length != 16) throw new IllegalArgumentException("upgrade key");
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(
                (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
    }
    /** Fixed codes and booleans only: no request values or arbitrary exception messages. */
    private static boolean admittedService(String service) {
        // The exact firmware's static pushd may retain this vendor list until native restart.
        // Both are finite known subscriptions; no caller-defined groups are accepted.
        return "quasar-realtime".equals(service) || "quasar-realtime,messenger-prod".equals(service);
    }
    private static boolean admittedHost(String host, int localPort) {
        return HOST.equals(host) || (HOST + ":443").equals(host)
                || localPort != PORT && ("127.0.0.1:" + localPort).equals(host);
    }
    private static String rejectCode(Exception failure) {
        String message = failure.getMessage();
        for (String literal : new String[]{"upgrade timeout", "upgrade size", "upgrade eof", "upgrade bounds", "request", "local lease",
                "lease time", "header", "duplicate header", "upgrade headers", "upgrade key", "path", "query",
                "query fields", "timestamp", "native atoi range", "frame buffer", "client frame", "frame length", "frame bounds"})
            if (literal.equals(message)) return literal.replace(' ', '_');
        return failure instanceof SocketTimeoutException ? "read_timeout" : failure instanceof java.io.IOException ? "transport_failure" : "invalid_request";
    }
    private static final class HandshakeRejected extends Exception {
        private static final long serialVersionUID = 1L;
        final String code; final JSONObject details;
        HandshakeRejected(String code, JSONObject details) { super("local_push_rejected"); this.code = code; this.details = details; }
    }
    private static JSONObject mergeDiagnostics(JSONObject target, JSONObject extra) throws Exception {
        java.util.Iterator<String> keys = extra.keys();
        while (keys.hasNext()) { String key = keys.next(); target.put(key, extra.get(key)); }
        return target;
    }
    /** Only class names from a fixed list and classified flags survive; messages never leave this method. */
    static JSONObject failureDiagnostics(Exception failure, String stage) {
        try {
        String name = failure.getClass().getSimpleName();
        if (!Arrays.asList("SSLHandshakeException", "SSLProtocolException", "SSLPeerUnverifiedException", "SSLException",
                "SocketTimeoutException", "SocketException", "EOFException", "IOException", "IllegalArgumentException").contains(name)) name = "Exception";
        StringBuilder classified = new StringBuilder(); Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 4; depth++, cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null) classified.append(message.substring(0, Math.min(message.length(), 2048)).toLowerCase(Locale.ROOT)).append(' ');
        }
        String text = classified.toString();
        return new JSONObject().put("stage", Arrays.asList("tls", "header", "upgrade", "frame").contains(stage) ? stage : "other")
                .put("exceptionClass", name)
                .put("protocolVersion", text.contains("protocol_version") || text.contains("protocol version"))
                .put("noSharedCipher", text.contains("no_shared_cipher") || text.contains("no shared cipher") || text.contains("no cipher suites in common"))
                .put("certificateUnknown", text.contains("certificate_unknown") || text.contains("certificate unknown"))
                .put("badCertificate", text.contains("bad_certificate") || text.contains("bad certificate"))
                .put("certificateExpired", text.contains("certificate_expired") || text.contains("certificate expired"))
                .put("certificateNotYetValid", text.contains("certificate not yet valid") || text.contains("certificate is not yet valid"))
                .put("unknownCa", text.contains("unknown_ca") || text.contains("unknown ca"))
                .put("unsupportedProtocol", text.contains("unsupported_protocol") || text.contains("unsupported protocol") || text.contains("no appropriate protocol"))
                .put("handshakeFailure", text.contains("handshake_failure") || text.contains("handshake failure"))
                .put("connectionReset", text.contains("connection reset"))
                .put("peerClosed", text.contains("closed by peer") || text.contains("peer closed") || text.contains("remote host terminated"))
                .put("timeout", failure instanceof SocketTimeoutException || text.contains("timed out") || text.contains("timeout"));
        } catch (Exception unavailable) { return new JSONObject(); }
    }
    /** Prefix classification only. Never returns request bytes, path, query or TLS values. */
    static JSONObject headDiagnostics(byte[] bytes, int matched) throws Exception {
        int count = bytes == null ? 0 : Math.min(bytes.length, MAX_HEAD);
        boolean tls = count >= 3 && (bytes[0] & 255) == 22 && (bytes[1] & 255) == 3
                && (bytes[2] & 255) <= 4;
        boolean http = count >= 4 && bytes[0] == 'G' && bytes[1] == 'E' && bytes[2] == 'T' && bytes[3] == ' ';
        int progress = Math.max(0, Math.min(4, matched));
        return new JSONObject().put("headBytes", count).put("startsTls", tls).put("startsHttp", http)
                .put("headerTerminatorProgress", progress).put("headerComplete", progress == 4);
    }
    static JSONObject handshakeDiagnostics(String raw, int port, long nowSeconds) throws Exception {
        JSONObject result = new JSONObject(); Map<String,String> values = new HashMap<>(), headers = new HashMap<>();
        int unknown = 0, duplicate = 0; boolean decoded = true; String target = "";
        if (raw == null || raw.length() > MAX_HEAD) return result.put("bounded", false);
        String[] lines = raw.split("\r\n"); String[] first = lines.length == 0 ? new String[0] : lines[0].split(" ", -1);
        if (first.length == 3) target = first[1];
        boolean path = target.startsWith(PATH + "?") && target.indexOf('#') < 0 && target.length() <= 4096;
        if (path) for (String part : target.substring(PATH.length() + 1).split("&")) {
            if (part.isEmpty()) continue; int equal = part.indexOf('='); if (equal < 1) { decoded = false; continue; }
            try {
                String name = URLDecoder.decode(part.substring(0, equal), "UTF-8"), value = URLDecoder.decode(part.substring(equal + 1), "UTF-8");
                if (!Arrays.asList("service", "client", "session", "sign", "ts", "user").contains(name)) unknown++;
                else if (values.put(name, value) != null) duplicate++;
            } catch (Exception invalid) { decoded = false; }
        }
        for (int i = 1; i < lines.length; i++) { int colon = lines[i].indexOf(':'); if (colon > 0)
            headers.put(lines[i].substring(0, colon).toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim()); }
        boolean timestamp = false, future = false, within = false;
        try { long expiry = parseExpiry(values.get("ts")); timestamp = true; future = expiry > nowSeconds; within = expiry <= nowSeconds + 3605L; }
        catch (Exception invalid) { }
        String service = values.get("service");
        return result.put("bounded", true).put("pathMatches", path).put("queryDecodable", decoded)
                .put("unknownQueryKeys", unknown).put("duplicateQueryKeys", duplicate)
                .put("signMatches", SIGN.equals(values.get("sign"))).put("serviceMatches", admittedService(values.get("service")))
                .put("serviceLength", service == null ? 0 : Math.min(service.length(), 1024))
                .put("serviceEmpty", service == null || service.isEmpty())
                .put("serviceIsJSONQuotedExpected", "\"quasar-realtime\"".equals(service))
                .put("serviceIsJSONArrayExpected", "[\"quasar-realtime\"]".equals(service))
                .put("serviceMatchesVendorDefault", "quasar-realtime,messenger-prod".equals(service))
                .put("clientMatches", "q".equals(values.get("client"))).put("timestampValid", timestamp)
                .put("timestampFuture", future).put("timestampWithinLease", within)
                .put("hostMatches", admittedHost(headers.get("host"), port))
                .put("upgradeMatches", "websocket".equalsIgnoreCase(headers.get("upgrade")))
                .put("versionMatches", "13".equals(headers.get("sec-websocket-version")))
                .put("connectionMatches", Arrays.asList(headers.getOrDefault("connection", "").toLowerCase(Locale.ROOT).split("\\s*,\\s*")).contains("upgrade"))
                .put("hasBodyHeaders", headers.containsKey("content-length") || headers.containsKey("transfer-encoding"));
    }
    private static Map<String,String> query(String target) throws Exception {
        if (!target.startsWith(PATH + "?") || target.indexOf('#') >= 0 || target.length() > 4096) throw new IllegalArgumentException("path");
        Map<String,String> values = new HashMap<>();
        for (String item : target.substring(PATH.length() + 1).split("&")) {
            if (item.isEmpty()) continue; // Native builder appends '?&service='.
            int equal = item.indexOf('='); if (equal <= 0) throw new IllegalArgumentException("query");
            String key = URLDecoder.decode(item.substring(0, equal), "UTF-8");
            String value = URLDecoder.decode(item.substring(equal + 1), "UTF-8");
            if (!(key.equals("service") || key.equals("client") || key.equals("session") || key.equals("sign") || key.equals("ts") || key.equals("user"))
                    || value.length() > 1024 || values.put(key, value) != null) throw new IllegalArgumentException("query fields");
        }
        return values;
    }
    private static long requestExpiry(String raw) throws Exception {
        return parseExpiry(query(raw.substring(0, raw.indexOf("\r\n")).split(" ", -1)[1]).get("ts"));
    }
    private static long parseExpiry(String value) {
        if (value == null || !value.matches("[0-9]{1,10}")) throw new IllegalArgumentException("timestamp");
        long expiry = Long.parseLong(value);
        if (expiry <= 0 || expiry > Integer.MAX_VALUE) throw new IllegalArgumentException("native atoi range");
        return expiry;
    }
    static final class Frame {
        final int opcode, bytes; final byte[] payload;
        Frame(int opcode, int bytes, byte[] payload) { this.opcode=opcode;this.bytes=bytes;this.payload=payload; }
    }
    static Frame clientFrame(byte[] raw, int offset) {
        int available = raw.length - offset; if (available < 2) return null;
        int first=raw[offset]&255, second=raw[offset+1]&255, opcode=first&15;
        if ((first&0xf0)!=0x80 || (second&128)==0 || !(opcode==1||opcode==2||opcode==8||opcode==9||opcode==10)) throw new IllegalArgumentException("client frame");
        int length=second&127, header=2;
        if (length==127) throw new IllegalArgumentException("frame length");
        if (length==126) { if(available<4)return null; length=((raw[offset+2]&255)<<8)|(raw[offset+3]&255);header=4; }
        if(length>MAX_FRAME || opcode>=8&&length>125 || opcode==8&&length==1)throw new IllegalArgumentException("frame bounds");
        if(available<header+4+length)return null;
        byte[] payload=new byte[length];for(int i=0;i<length;i++)payload[i]=(byte)(raw[offset+header+4+i]^raw[offset+header+(i%4)]);
        return new Frame(opcode,header+4+length,payload);
    }
    static void writeFrame(OutputStream output,int opcode,byte[] payload)throws Exception {
        if(payload.length>MAX_FRAME)throw new IllegalArgumentException("frame bounds");
        output.write(128|opcode);
        if(payload.length<126)output.write(payload.length);
        else {output.write(126);output.write(payload.length>>>8);output.write(payload.length&255);}
        output.write(payload);output.flush();
    }
    private static void close(java.io.Closeable item) {
        if (item != null) try { item.close(); } catch (Exception ignored) { }
    }
}
