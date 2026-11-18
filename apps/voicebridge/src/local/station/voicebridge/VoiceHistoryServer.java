package local.station.voicebridge;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Fixed, token-protected local protocol. No network clients can directly reach this service. */
final class VoiceHistoryServer implements Closeable {
    static final int PORT = 17688;
    private final VoiceHistory history;
    private final int requestedPort;
    private ServerSocket listener;
    private Socket activeClient;
    private ScheduledThreadPoolExecutor deadlines;

    VoiceHistoryServer(VoiceHistory history) { this(history, PORT); }

    VoiceHistoryServer(VoiceHistory history, int port) {
        this.history = history;
        this.requestedPort = port;
    }

    synchronized void start() throws IOException {
        if (listener != null) return;
        ServerSocket server = new ServerSocket();
        try {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("127.0.0.1", requestedPort), 4);
            ScheduledThreadPoolExecutor timeouts = new ScheduledThreadPoolExecutor(1, runnable -> {
                Thread worker = new Thread(runnable, "StationVoiceHistoryTimeout");
                worker.setDaemon(true);
                return worker;
            });
            timeouts.setRemoveOnCancelPolicy(true);
            listener = server;
            deadlines = timeouts;
            Thread accept = new Thread(() -> serve(server, timeouts), "StationVoiceHistoryListener");
            accept.setDaemon(true);
            accept.start();
        } catch (IOException | RuntimeException failure) {
            closeQuietly(server);
            if (listener == server) close();
            throw failure;
        }
    }

    synchronized int port() {
        return listener == null ? -1 : listener.getLocalPort();
    }

    @Override public synchronized void close() {
        closeQuietly(listener);
        listener = null;
        closeQuietly(activeClient);
        activeClient = null;
        if (deadlines != null) deadlines.shutdownNow();
        deadlines = null;
    }

    private void serve(ServerSocket server, ScheduledThreadPoolExecutor timeouts) {
        while (!server.isClosed()) {
            try (Socket client = server.accept()) {
                final ScheduledFuture<?> timeout;
                synchronized (this) {
                    if (server != listener) return;
                    activeClient = client;
                    timeout = timeouts.schedule(() -> closeQuietly(client), 3, TimeUnit.SECONDS);
                }
                try {
                    client.setSoTimeout(750);
                    handle(client, server.getLocalPort());
                } finally {
                    timeout.cancel(false);
                    synchronized (this) {
                        if (activeClient == client) activeClient = null;
                    }
                }
            } catch (IOException | RuntimeException unavailable) {
                if (server.isClosed()) return;
            }
        }
    }

    private void handle(Socket client, int port) throws IOException {
        OutputStream output = client.getOutputStream();
        final Request request;
        try {
            request = Request.read(new BufferedInputStream(client.getInputStream()), port);
        } catch (InvalidRequest invalid) {
            error(output, 400, "bad_request");
            return;
        }
        if (!"/history".equals(request.target) && !"/history/clear".equals(request.target)) {
            error(output, 404, "not_found");
            return;
        }
        boolean clear = "/history/clear".equals(request.target);
        if (!(clear ? "POST" : "GET").equals(request.method)) {
            error(output, 405, "method_not_allowed");
            return;
        }
        final String result;
        try {
            if (clear) {
                history.clear(request.token);
                result = "{\"ok\":true}";
            } else result = history.read(request.token);
        } catch (VoiceHistory.AccessDenied denied) {
            error(output, 401, "unauthorized");
            return;
        } catch (VoiceHistory.Disabled disabled) {
            error(output, 503, "disabled");
            return;
        } catch (Exception unavailable) {
            error(output, 503, "unavailable");
            return;
        }
        response(output, 200, result);
    }

    private static void error(OutputStream output, int status, String code) throws IOException {
        response(output, status, "{\"ok\":false,\"code\":\"" + code + "\"}");
    }

    private static void response(OutputStream output, int status, String result) throws IOException {
        byte[] body = result.getBytes(StandardCharsets.UTF_8);
        if (body.length > VoiceHistory.MAX_RESPONSE_BYTES) throw new IOException("history_response_size");
        String reason;
        switch (status) {
            case 200: reason = "OK"; break;
            case 400: reason = "Bad Request"; break;
            case 401: reason = "Unauthorized"; break;
            case 404: reason = "Not Found"; break;
            case 405: reason = "Method Not Allowed"; break;
            default: reason = "Service Unavailable";
        }
        String header = "HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: application/json; charset=utf-8\r\n"
                + "Cache-Control: no-store\r\nConnection: close\r\nContent-Length: " + body.length + "\r\n\r\n";
        output.write(header.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable != null) try { closeable.close(); } catch (IOException ignored) { }
    }

    private static final class InvalidRequest extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private static final class Request {
        final String method, target, token;

        Request(String method, String target, String token) {
            this.method = method;
            this.target = target;
            this.token = token;
        }

        static Request read(InputStream input, int port) throws IOException {
            String[] line = line(input).split(" ", -1);
            if (line.length != 3 || (!"HTTP/1.1".equals(line[2]) && !"HTTP/1.0".equals(line[2])))
                throw new InvalidRequest();
            String host = null, authorization = null, length = null;
            for (int i = 0; i < 24; i++) {
                String header = line(input);
                if (header.isEmpty()) {
                    if (!("127.0.0.1:" + port).equals(host)) throw new InvalidRequest();
                    String token = authorization != null && authorization.startsWith("Bearer ")
                            ? authorization.substring(7) : null;
                    return new Request(line[0], line[1], token);
                }
                int separator = header.indexOf(':');
                if (separator <= 0) throw new InvalidRequest();
                String name = header.substring(0, separator).toLowerCase(Locale.ROOT);
                String value = header.substring(separator + 1).trim();
                switch (name) {
                    case "host":
                        if (host != null) throw new InvalidRequest();
                        host = value;
                        break;
                    case "authorization":
                        if (authorization != null) throw new InvalidRequest();
                        authorization = value;
                        break;
                    case "content-length":
                        if (length != null || !"0".equals(value)) throw new InvalidRequest();
                        length = value;
                        break;
                    case "transfer-encoding": case "origin": case "sec-fetch-site":
                        throw new InvalidRequest();
                    default:
                        if (!name.matches("[a-z0-9-]{1,64}")) throw new InvalidRequest();
                }
            }
            throw new InvalidRequest();
        }

        private static String line(InputStream input) throws IOException {
            StringBuilder line = new StringBuilder();
            for (int length = 0; length < 512; length++) {
                int c = input.read();
                if (c == '\r' && input.read() == '\n') return line.toString();
                if (c < 32 || c > 126) throw new InvalidRequest();
                line.append((char) c);
            }
            throw new InvalidRequest();
        }
    }
}
