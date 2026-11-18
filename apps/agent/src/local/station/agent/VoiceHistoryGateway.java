package local.station.agent;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Authenticated, fixed loopback exchange for the Agent's same-origin history routes. */
final class VoiceHistoryGateway {
    static final String PATH = "/api/voice/history";
    static final String CLEAR_PATH = PATH + "/clear";
    private static final int PORT = 17688;
    private static final int MAX_HEADERS = 8192;
    private static final int MAX_RESPONSE = 256 * 1024;
    private static final int HEADER_TIMEOUT_MS = 2000;
    private static final int UPSTREAM_TIMEOUT_MS = 2500;
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Pattern TOKEN = Pattern.compile("(?i:Bearer) [0-9A-Fa-f]{32}");
    private final Semaphore requests = new Semaphore(2);
    private final int port;

    VoiceHistoryGateway() { this(PORT); }

    VoiceHistoryGateway(int port) { this.port = port; }

    static boolean matches(String target) {
        return target.equals(PATH) || target.startsWith(PATH + "/") || target.startsWith(PATH + "?")
                || target.startsWith(PATH + "#");
    }

    static final class Response {
        final int status;
        final String json;

        Response(int status, String json) {
            this.status = status;
            this.json = json;
        }
    }

    Response handle(String method, String target, Socket client, BufferedReader reader) {
        if (!PATH.equals(target) && !CLEAR_PATH.equals(target)) return error(400, "invalid_request");
        boolean clear = CLEAR_PATH.equals(target);
        if (!(clear ? "POST" : "GET").equals(method)) return error(405, "method_not_allowed");
        final String authorization;
        try {
            authorization = readClientAuthorization(client, reader);
        } catch (IOException invalid) {
            return error(400, "invalid_request");
        }
        if (authorization == null || !TOKEN.matcher(authorization).matches())
            return error(401, "unauthorized");
        if (!requests.tryAcquire()) return error(429, "busy");
        try {
            return request(clear, authorization.substring(7).toUpperCase(Locale.ROOT));
        } catch (Exception unavailable) {
            return error(503, "unavailable");
        } finally {
            requests.release();
        }
    }

    private static String readClientAuthorization(Socket socket, Reader reader) throws IOException {
        long deadline = deadlineAfter(HEADER_TIMEOUT_MS);
        String authorization = null;
        boolean contentLength = false;
        int remaining = MAX_HEADERS;
        while (remaining > 0) {
            String line = readLine(socket, reader::read, deadline, remaining);
            remaining -= line.length() + 2;
            if (line.isEmpty()) return authorization;
            int colon = line.indexOf(':');
            if (colon <= 0) throw new IOException("malformed_header");
            String name = line.substring(0, colon);
            if (!HEADER_NAME.matcher(name).matches()) throw new IOException("malformed_header");
            String value = line.substring(colon + 1).trim();
            if ("Authorization".equalsIgnoreCase(name)) {
                if (authorization != null) throw new IOException("duplicate_auth");
                authorization = value;
            } else if ("Content-Length".equalsIgnoreCase(name)) {
                if (contentLength || !"0".equals(value)) throw new IOException("unexpected_body");
                contentLength = true;
            } else if ("Transfer-Encoding".equalsIgnoreCase(name)) {
                throw new IOException("unexpected_body");
            } else if ("Sec-Fetch-Site".equalsIgnoreCase(name) && !"same-origin".equals(value)
                    && !"none".equals(value)) {
                throw new IOException("origin_mismatch");
            }
        }
        throw new IOException("headers_too_large");
    }

    private Response request(boolean clear, String token) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 350);
            long deadline = deadlineAfter(UPSTREAM_TIMEOUT_MS);
            String request = (clear ? "POST /history/clear" : "GET /history")
                    + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n"
                    + "Authorization: Bearer " + token + "\r\nAccept: application/json\r\n"
                    + (clear ? "Content-Length: 0\r\n" : "") + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            InputStream input = new BufferedInputStream(socket.getInputStream());
            String status = readLine(socket, input::read, deadline, 128);
            if (status.matches("HTTP/1\\.[01] 401(?: .*)?")) return error(401, "unauthorized");
            if (status.matches("HTTP/1\\.[01] 405(?: .*)?")) return error(405, "method_not_allowed");
            boolean ok = status.matches("HTTP/1\\.[01] 200(?: .*)?");
            if (!ok && !status.matches("HTTP/1\\.[01] 503(?: .*)?")) return error(503, "unavailable");
            int length = readUpstreamLength(socket, input, deadline);
            byte[] body = new byte[length];
            for (int offset = 0; offset < length;) {
                socket.setSoTimeout(remainingMs(deadline));
                int count = input.read(body, offset, length - offset);
                if (count < 1) throw new EOFException();
                offset += count;
            }
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            JSONTokener parser = new JSONTokener(text);
            Object value = parser.nextValue();
            if (!(value instanceof JSONObject) || parser.nextClean() != 0) return error(503, "unavailable");
            JSONObject response = (JSONObject) value;
            if (!ok) return error(503, "disabled".equals(response.opt("code")) ? "disabled" : "unavailable");
            if (!Boolean.TRUE.equals(response.opt("ok"))) return error(503, "unavailable");
            if (clear) return new Response(200, "{\"ok\":true}");
            if (!Boolean.TRUE.equals(response.opt("enabled")) || response.optJSONArray("entries") == null)
                return error(503, "unavailable");
            return new Response(200, text);
        }
    }

    private static int readUpstreamLength(Socket socket, InputStream input, long deadline) throws IOException {
        int remaining = MAX_HEADERS;
        int length = -1;
        boolean json = false;
        while (remaining > 0) {
            String line = readLine(socket, input::read, deadline, remaining);
            remaining -= line.length() + 2;
            if (line.isEmpty()) {
                if (!json || length <= 0) throw new IOException("invalid_history_response");
                return length;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) throw new IOException("malformed_header");
            String name = line.substring(0, colon);
            if (!HEADER_NAME.matcher(name).matches()) throw new IOException("malformed_header");
            String value = line.substring(colon + 1).trim();
            if ("Content-Length".equalsIgnoreCase(name)) {
                if (length >= 0 || !value.matches("[0-9]{1,6}")) throw new IOException("invalid_history_length");
                length = Integer.parseInt(value);
                if (length > MAX_RESPONSE) throw new IOException("invalid_history_length");
            } else if ("Content-Type".equalsIgnoreCase(name)) {
                if (json || !"application/json".equalsIgnoreCase(value.split(";", 2)[0].trim()))
                    throw new IOException("invalid_history_type");
                json = true;
            } else if ("Transfer-Encoding".equalsIgnoreCase(name)) {
                throw new IOException("invalid_history_encoding");
            }
        }
        throw new IOException("headers_too_large");
    }

    private interface ByteReader { int read() throws IOException; }

    private static String readLine(Socket socket, ByteReader reader, long deadline, int remaining)
            throws IOException {
        StringBuilder line = new StringBuilder();
        while (remaining-- > 0) {
            socket.setSoTimeout(remainingMs(deadline));
            int read = reader.read();
            if (read == -1) throw new EOFException();
            if (read == '\n') {
                int length = line.length();
                if (length == 0 || line.charAt(length - 1) != '\r') throw new IOException("malformed_line");
                line.setLength(length - 1);
                return line.toString();
            }
            if (line.length() > 0 && line.charAt(line.length() - 1) == '\r')
                throw new IOException("malformed_line");
            if (read < 32 && read != '\r' && read != '\t' || read == 127) throw new IOException("malformed_line");
            line.append((char) read);
        }
        throw new IOException("headers_too_large");
    }

    private static long deadlineAfter(int ms) {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms);
    }

    private static int remainingMs(long deadline) throws SocketTimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new SocketTimeoutException();
        return (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    private static Response error(int status, String code) {
        return new Response(status, "{\"ok\":false,\"code\":\"" + code + "\"}");
    }
}
