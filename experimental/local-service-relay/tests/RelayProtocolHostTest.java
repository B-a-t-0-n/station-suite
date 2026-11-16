package local.station.relay;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Independent wire fixtures against production parsers. No sockets or Android. */
public final class RelayProtocolHostTest {
    private static int assertions;
    private RelayProtocolHostTest() { }

    private interface Checked { void run() throws Exception; }
    private static void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }
    private static void rejects(Checked action, String label) throws Exception {
        assertions++;
        try { action.run(); }
        catch (IOException | IllegalArgumentException expected) { return; }
        throw new AssertionError("accepted " + label);
    }
    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    private static byte[] concat(byte[]... chunks) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) out.write(chunk);
        return out.toByteArray();
    }
    private static void u16(ByteArrayOutputStream out, int value) {
        out.write(value >>> 8); out.write(value);
    }
    private static byte[] extension(int type, byte[] contents) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        u16(out, type); u16(out, contents.length); out.write(contents); return out.toByteArray();
    }
    private static byte[] sni(String name) throws IOException {
        ByteArrayOutputStream entries = new ByteArrayOutputStream();
        byte[] encoded = ascii(name);
        entries.write(0); u16(entries, encoded.length); entries.write(encoded);
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        u16(payload, entries.size()); payload.write(entries.toByteArray());
        return extension(0, payload.toByteArray());
    }
    private static byte[] handshake(byte[] extensions) throws IOException {
        ByteArrayOutputStream hello = new ByteArrayOutputStream();
        hello.write(3); hello.write(3); hello.write(new byte[32]);
        hello.write(0); u16(hello, 2); hello.write(0x13); hello.write(1);
        hello.write(1); hello.write(0); u16(hello, extensions.length); hello.write(extensions);
        byte[] body = hello.toByteArray();
        return concat(new byte[]{1, (byte) (body.length >>> 16), (byte) (body.length >>> 8), (byte) body.length}, body);
    }
    private static byte[] record(byte[] bytes) throws IOException {
        return concat(new byte[]{22, 3, 3, (byte) (bytes.length >>> 8), (byte) bytes.length}, bytes);
    }
    private static byte[] records(byte[] handshake, int... pieces) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offset = 0;
        for (int size : pieces) {
            out.write(record(Arrays.copyOfRange(handshake, offset, offset + size))); offset += size;
        }
        if (offset < handshake.length) out.write(record(Arrays.copyOfRange(handshake, offset, handshake.length)));
        return out.toByteArray();
    }
    private static InputStream oneByteReads(byte[] bytes) {
        return new ByteArrayInputStream(bytes) {
            @Override public int read(byte[] target, int offset, int length) {
                return super.read(target, offset, Math.min(1, length));
            }
        };
    }
    private static RelayProtocol.FirstFlight parseTls(byte[] bytes) throws IOException {
        return RelayProtocol.tls(new ByteArrayInputStream(bytes));
    }
    private static byte[] request(String method, String target, String host, String extra) {
        return ascii(method + " " + target + " HTTP/1.1\r\nHost: " + host + "\r\n" + extra + "\r\n");
    }
    private static RelayProtocol.FirstFlight parseHttp(byte[] bytes) throws IOException {
        return RelayProtocol.http(new ByteArrayInputStream(bytes));
    }

    private static void ipv4() throws Exception {
        for (String accepted : new String[]{"10.0.0.0", "10.255.255.255", "172.16.0.0", "172.31.255.255", "192.168.0.0", "192.168.255.255"})
            check(RelayProtocol.privateIpv4(accepted).length == 4, accepted);
        for (String denied : new String[]{null, "", "127.0.0.1", "0.0.0.0", "8.8.8.8", "169.254.1.1", "172.15.255.255", "172.32.0.0", "192.169.0.1", "192.168.1.256", "192.168.1.-1", "192.168.01.1", "10.0.0", "10.0.0.1.2", "10.0.0.1 ", " 10.0.0.1", "10.0.0.1\n", "localhost", "::1", "::ffff:192.168.77.1", "0xc0a84d01", "3232255233"})
            rejects(() -> RelayProtocol.privateIpv4(denied), "address " + denied);
        for (int duration : new int[]{Integer.MIN_VALUE, -1, 0, 999, 20001, Integer.MAX_VALUE})
            rejects(() -> new LocalServiceRelay("192.168.77.1", duration), "duration " + duration);
        for (int duration : new int[]{1000, 20000}) {
            try (LocalServiceRelay relay = new LocalServiceRelay("192.168.77.1", duration)) {
                check(relay.boundListenPort() <= 0, "construction never binds a listener");
            }
        }
    }

    private static void tls() throws Exception {
        for (String config : new String[]{"quasar.yandex.net", "QUASAR.YANDEX.NET", "quasar.yandex.ru", "scbh.yandex.net", "startup.mobile.yandex.net", "clck.yandex.net", "uniproxy.alice.yandex.net", "oauth.yandex.ru"}) {
            byte[] wire = record(handshake(sni(config)));
            RelayProtocol.FirstFlight parsed = RelayProtocol.tls(oneByteReads(wire));
            check(parsed.backendPort == 17683, "config backend " + config);
            check(Arrays.equals(parsed.bytes, wire), "unmodified config hello " + config);
        }
        byte[] hello = handshake(sni("station-config.quasar.yandex.net"));
        byte[][] fragmented = {record(hello), records(hello, 1), records(hello, 1, 1, 1), records(hello, 4, 17, 9), records(hello, 1, 1, 1, 1, 1, 1, 1)};
        for (byte[] wire : fragmented) {
            RelayProtocol.FirstFlight parsed = RelayProtocol.tls(oneByteReads(wire));
            check(parsed.backendPort == 17684, "push backend fragmented");
            check(Arrays.equals(parsed.bytes, wire), "TLS records byte exact");
        }
        byte[] config = record(handshake(sni("quasar.yandex.net")));
        byte[] application = new byte[]{23, 3, 3, 0, 3, 4, 5, 6};
        ByteArrayInputStream next = new ByteArrayInputStream(concat(config, application));
        check(Arrays.equals(RelayProtocol.tls(next).bytes, config), "first flight only");
        check(next.available() == application.length, "later TLS data remains for duplex stream");
        for (int size = 0; size < config.length; size++) {
            final byte[] truncated = Arrays.copyOf(config, size);
            rejects(() -> parseTls(truncated), "TLS truncation " + size);
        }
        for (String name : new String[]{"example.org", "quasar.yandex.net.evil", "evilquasar.yandex.net", "quasar.yandex.net.", "quasar.yandex.net:443", "127.0.0.1", "", "quasar_yandex.net", "quasar.yandex.net\n"})
            rejects(() -> parseTls(record(handshake(sni(name)))), "SNI " + name);
        rejects(() -> parseTls(record(handshake(new byte[0]))), "missing SNI");
        rejects(() -> parseTls(record(handshake(concat(sni("quasar.yandex.net"), sni("station-config.quasar.yandex.net"))))), "duplicate SNI");
        rejects(() -> parseTls(record(handshake(concat(sni("quasar.yandex.net"), extension(21, new byte[0]), extension(21, new byte[0]))))), "duplicate extension");
        rejects(() -> parseTls(record(concat(handshake(sni("quasar.yandex.net")), new byte[]{0}))), "trailing handshake bytes in record");
        rejects(() -> parseTls(records(hello, 1, 1, 1, 1, 1, 1, 1, 1)), "nine TLS records");
        byte[] wrongType = config.clone(); wrongType[0] = 23;
        rejects(() -> parseTls(wrongType), "application data before ClientHello");
        byte[] wrongVersion = config.clone(); wrongVersion[2] = 0;
        rejects(() -> parseTls(wrongVersion), "SSLv3 record");
        byte[] notHello = config.clone(); notHello[5] = 2;
        rejects(() -> parseTls(notHello), "ServerHello");
        rejects(() -> parseTls(record(handshake(concat(sni("quasar.yandex.net"), extension(21, new byte[RelayProtocol.MAX_HELLO]))))), "oversized hello");
        rejects(() -> parseTls(new byte[]{22, 3, 3, 0, 0}), "zero record");
        check(parseTls(record(handshake(concat(sni("quasar.yandex.net"), extension(21, new byte[]{1, 2, 3}))))).backendPort == 17683, "bounded unknown extension");
    }

    private static void http() throws Exception {
        for (String method : new String[]{"GET", "HEAD"}) {
            for (String host : new String[]{"scbh.yandex.net", "SCBH.YANDEX.NET:80", "connectivitycheck.gstatic.com"}) {
                byte[] wire = request(method, "/generate_204", host, "Content-Length: 0\r\n");
                RelayProtocol.FirstFlight parsed = RelayProtocol.http(oneByteReads(wire));
                check(parsed.backendPort == 17685, "HTTP backend");
                check(Arrays.equals(parsed.bytes, wire), "HTTP first request byte exact");
            }
        }
        check(parseHttp(request("GET", "/gen_204", "www.google.com", "")).backendPort == 17685, "Google compatibility endpoint");
        for (String method : new String[]{"POST", "PUT", "DELETE", "CONNECT", "OPTIONS", "get"})
            rejects(() -> parseHttp(request(method, "/generate_204", "scbh.yandex.net", "")), "method " + method);
        for (String path : new String[]{"/api/display", "/api/config", "/", "/generate_204?x=1", "http://scbh.yandex.net/generate_204", "/generate_204#fragment", "/a/../generate_204"})
            rejects(() -> parseHttp(request("GET", path, "scbh.yandex.net", "")), "target " + path);
        for (String host : new String[]{"quasar.yandex.net", "evil.example", "scbh.yandex.net.evil", "scbh.yandex.net:443", "scbh.yandex.net.", "scbh.yandex.net:080", "127.0.0.1"})
            rejects(() -> parseHttp(request("GET", "/generate_204", host, "")), "Host " + host);
        for (String extra : new String[]{"Host: scbh.yandex.net\r\n", "hOsT: scbh.yandex.net\r\n", "Content-Length: 1\r\n", "Content-Length: 00\r\n", "Transfer-Encoding: chunked\r\n", "Upgrade: websocket\r\n", "Expect: 100-continue\r\n", "Authorization: x\r\n", "Proxy-Authorization: x\r\n", "Cookie: x=y\r\n", "X-Test: x\r\nX-Test: y\r\n", "Bad Header: x\r\n", " X-Folded: y\r\n", "X: x\ny\r\n", "X: x\ry\r\n", "X: x\n\nGET /api/display HTTP/1.1\nHost: scbh.yandex.net\r\n"})
            rejects(() -> parseHttp(request("GET", "/generate_204", "scbh.yandex.net", extra)), "header policy " + extra.replace('\r', '_').replace('\n', '_'));
        rejects(() -> parseHttp(ascii("GET /generate_204 HTTP/1.1\r\n\r\n")), "missing Host");
        byte[] first = request("HEAD", "/generate_204", "scbh.yandex.net", "");
        byte[] forbidden = request("GET", "/api/display", "scbh.yandex.net", "");
        ByteArrayInputStream pipeline = new ByteArrayInputStream(concat(first, forbidden));
        check(Arrays.equals(RelayProtocol.http(pipeline).bytes, first), "forward only first validated HTTP request");
        check(pipeline.available() == forbidden.length, "pipeline remains unconsumed and must never relay");
        byte[] longHeader = new byte[RelayProtocol.MAX_HTTP + 1]; Arrays.fill(longHeader, (byte) 'x');
        rejects(() -> parseHttp(longHeader), "bounded HTTP headers");
        for (int size = 0; size < first.length; size++) {
            final byte[] truncated = Arrays.copyOf(first, size);
            rejects(() -> parseHttp(truncated), "HTTP truncation " + size);
        }
    }

    public static void main(String[] args) throws Exception {
        ipv4(); tls(); http();
        System.out.println(assertions + " assertions passed; production parsers, independent wire fixtures, no sockets");
    }
}
