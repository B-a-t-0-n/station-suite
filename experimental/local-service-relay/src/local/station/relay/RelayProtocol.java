package local.station.relay;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Bounded parsers; no DNS, sockets, TLS termination or upstream fallback. */
final class RelayProtocol {
    static final int MAX_HELLO = 16384, MAX_WIRE = 32768, MAX_HTTP = 8192;
    private static final Set<String> CONFIG_NAMES = new HashSet<>(Arrays.asList(
            "quasar.yandex.net", "quasar.yandex.ru", "scbh.yandex.net", "startup.mobile.yandex.net",
            "clck.yandex.net", "uniproxy.alice.yandex.net", "oauth.yandex.ru"));
    static final String PUSH_NAME = "station-config.quasar.yandex.net";
    static final class FirstFlight {
        final byte[] bytes;
        final int backendPort;
        FirstFlight(byte[] bytes, int backendPort) { this.bytes = bytes; this.backendPort = backendPort; }
    }

    private RelayProtocol() { }

    static byte[] privateIpv4(String text) {
        if (text == null || !text.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) throw new IllegalArgumentException("private_ipv4_required");
        String[] parts = text.split("\\.");
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            int value = Integer.parseInt(parts[i]);
            if (value > 255 || !String.valueOf(value).equals(parts[i])) throw new IllegalArgumentException("canonical_ipv4_required");
            bytes[i] = (byte) value;
        }
        int first = bytes[0] & 255, second = bytes[1] & 255;
        if (!(first == 10 || first == 172 && second >= 16 && second <= 31 || first == 192 && second == 168))
            throw new IllegalArgumentException("private_ipv4_required");
        return bytes;
    }

    static int read(InputStream stream) throws IOException {
        int value = stream.read();
        if (value < 0) throw new IOException("truncated");
        return value;
    }

    static byte[] exact(InputStream stream, int count) throws IOException {
        if (count < 0 || count > MAX_WIRE) throw invalid();
        byte[] result = new byte[count];
        for (int offset = 0; offset < count;) {
            int length = stream.read(result, offset, count - offset);
            if (length < 0) throw new IOException("truncated");
            if (length == 0) throw invalid();
            offset += length;
        }
        return result;
    }

    static FirstFlight http(InputStream stream) throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        int matched = 0;
        byte[] end = {13, 10, 13, 10};
        while (wire.size() < MAX_HTTP && matched != 4) {
            int value = read(stream);
            if (value < 32 && value != 13 && value != 10 && value != 9 || value > 126) throw invalid();
            wire.write(value);
            matched = value == end[matched] ? matched + 1 : value == 13 ? 1 : 0;
        }
        if (matched != 4) throw invalid();
        byte[] bytes = wire.toByteArray();
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 13 && (i + 1 == bytes.length || bytes[i + 1] != 10)
                    || bytes[i] == 10 && (i == 0 || bytes[i - 1] != 13)) throw invalid();
        }
        String[] lines = new String(bytes, StandardCharsets.US_ASCII).substring(0, bytes.length - 4).split("\r\n", -1);
        String[] request = lines[0].split(" ", -1);
        if (request.length != 3 || !("GET".equals(request[0]) || "HEAD".equals(request[0]))
                || !("HTTP/1.1".equals(request[2]) || "HTTP/1.0".equals(request[2]))) throw invalid();
        Set<String> seen = new HashSet<>();
        String host = null;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0 || !lines[i].substring(0, colon).matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) throw invalid();
            String name = lines[i].substring(0, colon).toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1).trim();
            for (int j = 0; j < value.length(); j++)
                if (value.charAt(j) < 32 && value.charAt(j) != '\t' || value.charAt(j) == 127) throw invalid();
            if (!seen.add(name) || name.equals("transfer-encoding") || name.equals("upgrade") || name.equals("expect")
                    || name.equals("proxy-authorization") || name.equals("authorization") || name.equals("cookie")) throw invalid();
            if (name.equals("content-length") && !value.equals("0")) throw invalid();
            if (name.equals("host")) host = value.toLowerCase(Locale.ROOT);
        }
        if (host == null) throw invalid();
        if (host.endsWith(":80")) host = host.substring(0, host.length() - 3);
        boolean allowed = (host.equals("scbh.yandex.net") || host.equals("connectivitycheck.gstatic.com"))
                && request[1].equals("/generate_204") || host.equals("www.google.com") && request[1].equals("/gen_204");
        if (!allowed) throw invalid();
        // Exactly one validated request is forwarded. No second/pipelined HTTP request is relayed.
        return new FirstFlight(bytes, 17685);
    }

    static FirstFlight tls(InputStream stream) throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream(), handshake = new ByteArrayOutputStream();
        int required = -1;
        for (int records = 0; records < 8; records++) {
            byte[] header = exact(stream, 5);
            int length = (header[3] & 255) * 256 + (header[4] & 255);
            if (header[0] != 22 || header[1] != 3 || header[2] < 1 || header[2] > 3 || length == 0
                    || length > 18432 || wire.size() + 5 + length > MAX_WIRE) throw invalid();
            byte[] body = exact(stream, length);
            wire.write(header, 0, header.length); wire.write(body, 0, body.length);
            handshake.write(body, 0, body.length);
            byte[] accumulated = handshake.toByteArray();
            if (accumulated.length > MAX_HELLO + 4) throw invalid();
            if (accumulated.length >= 4 && required < 0) {
                if (accumulated[0] != 1) throw invalid();
                required = ((accumulated[1] & 255) << 16) + ((accumulated[2] & 255) << 8) + (accumulated[3] & 255) + 4;
                if (required > MAX_HELLO + 4 || required < 42) throw invalid();
            }
            if (required >= 0 && accumulated.length >= required) {
                if (accumulated.length != required) throw invalid();
                String name = serverName(Arrays.copyOfRange(accumulated, 4, required));
                int port = PUSH_NAME.equals(name) ? 17684 : CONFIG_NAMES.contains(name) ? 17683 : -1;
                if (port < 0) throw invalid();
                return new FirstFlight(wire.toByteArray(), port);
            }
        }
        throw invalid();
    }

    private static String serverName(byte[] hello) throws IOException {
        Cursor cursor = new Cursor(hello);
        if (cursor.u8() != 3) throw invalid();
        int minor = cursor.u8(); if (minor < 1 || minor > 3) throw invalid();
        cursor.skip(32);
        int session = cursor.u8(); if (session > 32) throw invalid(); cursor.skip(session);
        int ciphers = cursor.u16(); if (ciphers == 0 || ciphers % 2 != 0) throw invalid(); cursor.skip(ciphers);
        int compression = cursor.u8(); if (compression == 0) throw invalid(); cursor.skip(compression);
        int extensions = cursor.u16(); if (extensions != cursor.remaining()) throw invalid();
        String name = null;
        Set<Integer> seen = new HashSet<>();
        while (cursor.remaining() != 0) {
            int type = cursor.u16(), length = cursor.u16();
            if (!seen.add(type) || seen.size() > 128) throw invalid();
            byte[] extension = cursor.take(length);
            if (type != 0) continue;
            Cursor names = new Cursor(extension);
            if (names.u16() != names.remaining() || names.u8() != 0) throw invalid();
            int size = names.u16();
            if (size == 0 || size > 128 || size != names.remaining()) throw invalid();
            byte[] ascii = names.take(size);
            for (byte value : ascii) if (!(value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z'
                    || value >= '0' && value <= '9' || value == '.' || value == '-')) throw invalid();
            name = new String(ascii, StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
        }
        if (name == null) throw invalid();
        return name;
    }

    private static final class Cursor {
        private final byte[] bytes;
        private int offset;
        Cursor(byte[] bytes) { this.bytes = bytes; }
        int remaining() { return bytes.length - offset; }
        int u8() throws IOException { if (remaining() < 1) throw invalid(); return bytes[offset++] & 255; }
        int u16() throws IOException { return u8() * 256 + u8(); }
        void skip(int count) throws IOException { if (count < 0 || count > remaining()) throw invalid(); offset += count; }
        byte[] take(int count) throws IOException { int start = offset; skip(count); return Arrays.copyOfRange(bytes, start, offset); }
    }

    private static IOException invalid() { return new IOException("relay_policy_rejected"); }
}
