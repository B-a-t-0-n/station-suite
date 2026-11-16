package local.station.vpnprobe;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

/** Production parsers only; no Android classes, sockets, device, or network. */
public final class ProbeHostTest {
    private static int assertions;
    private interface Action { void run(); }
    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }
    private static void rejects(Action action, String label) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError("Accepted unsafe input: " + label);
    }
    private static void put16(byte[] bytes, int position, int value) {
        bytes[position] = (byte)(value >>> 8);
        bytes[position + 1] = (byte)value;
    }
    private static byte[] packet(int ihlWords, int protocol, int transportBytes) {
        byte[] bytes = new byte[ihlWords * 4 + transportBytes];
        bytes[0] = (byte)(0x40 | ihlWords);
        put16(bytes, 2, bytes.length);
        bytes[9] = (byte)protocol;
        byte[] source = {(byte)192, (byte)168, 77, 100};
        byte[] destination = {(byte)192, (byte)168, 77, 1};
        System.arraycopy(source, 0, bytes, 12, 4);
        System.arraycopy(destination, 0, bytes, 16, 4);
        if (transportBytes >= 4) {
            put16(bytes, ihlWords * 4, 65535);
            put16(bytes, ihlWords * 4 + 2, 443);
        }
        return bytes;
    }
    private static PacketMetadata parse(byte[] bytes) {
        return PacketMetadata.parse(bytes, bytes.length);
    }
    private static void spec() {
        for (String valid : new String[]{"10.0.0.1", "10.255.255.254", "172.16.0.1",
                "172.31.255.254", "192.168.77.1", "192.168.0.254"}) {
            check(valid.equals(ProbeSpec.validateConfigIp(valid)), "private numeric target unchanged");
        }
        for (final String invalid : new String[]{null, "", "localhost", "example.org", "::1",
                "[::1]", "127.0.0.1", "0.0.0.0", "169.254.1.1", "172.15.1.1", "172.32.1.1",
                "192.169.1.1", "224.0.0.1", "255.255.255.255", "8.8.8.8", "192.168.77.0",
                "192.168.77.255", "10.111.77.2", "192.168.077.1", "192.168.77.01", "192.168.77.256",
                "192.168.77.-1", "192.168.77.+1", "192.168.77.1 ", " 192.168.77.1", "192.168.77.1\n",
                "192.168.77.1/32", "192.168.77.1:443", "http://192.168.77.1", "192.168.77",
                "192.168.77.1.2", "192..77.1", "0xc0.168.77.1", "3232255233", "192.168.77.\u0661"}) {
            rejects(() -> ProbeSpec.validateConfigIp(invalid), "config target " + invalid);
        }
        int[] octets = ProbeSpec.parseIpv4("255.0.128.1");
        check(Arrays.equals(octets, new int[]{255, 0, 128, 1}), "unsigned canonical octets");
        octets[0] = 0;
        check(ProbeSpec.parseIpv4("255.0.128.1")[0] == 255, "returned octets detached");
        String nonce = "0123456789abcdef0123456789abcdef";
        check(nonce.equals(ProbeSpec.validateNonce(nonce)), "fresh lowerhex nonce unchanged");
        String upper = nonce.toUpperCase(java.util.Locale.ROOT);
        check(upper.equals(ProbeSpec.validateNonce(upper)), "hex nonce contract also preserves uppercase");
        for (final String invalid : new String[]{null, "", nonce.substring(1), nonce + "0",
                nonce.substring(0, 31) + "g", nonce + "\n", " " + nonce,
                nonce.substring(0, 31) + "\u0661"}) {
            rejects(() -> ProbeSpec.validateNonce(invalid), "malformed nonce");
        }
        ProbeSpec.requireFresh(1000, 1000);
        ProbeSpec.requireFresh(1000, 1000 + ProbeSpec.MAX_START_AGE_MS);
        check(true, "fresh and exact freshness boundary accepted");
        rejects(() -> ProbeSpec.requireFresh(-1, 1000), "negative creation");
        rejects(() -> ProbeSpec.requireFresh(1001, 1000), "future timestamp");
        rejects(() -> ProbeSpec.requireFresh(1000, 1001 + ProbeSpec.MAX_START_AGE_MS), "expired timestamp");
        rejects(() -> ProbeSpec.requireFresh(0, Long.MAX_VALUE), "very old timestamp without overflow");
        check(ProbeSpec.DURATION_MS < ProbeSpec.MIN_WATCHDOG_REMAINING_MS,
                "watchdog admission has time beyond complete canary duration");
        check(ProbeSpec.MIN_WATCHDOG_REMAINING_MS <= ProbeSpec.MAX_WATCHDOG_REMAINING_MS,
                "nonempty watchdog admission window");
    }
    private static void metadata() {
        check(PacketMetadata.parse(null, 0) == null, "null packet");
        check(PacketMetadata.parse(new byte[0], -1) == null, "negative length");
        check(PacketMetadata.parse(new byte[19], 19) == null, "truncated IP header");
        check(PacketMetadata.parse(new byte[20], 21) == null, "caller length beyond buffer");
        byte[] tcp = packet(5, 6, 20);
        PacketMetadata m = parse(tcp);
        check(m != null && m.protocol == 6 && m.sourcePort == 65535 && m.destinationPort == 443,
                "TCP ports use network endian and unsigned range");
        check("192.168.77.100".equals(m.sourceIp) && "192.168.77.1".equals(m.destinationIp),
                "IP tuple retained");
        String snapshot = m.toJson(17);
        Arrays.fill(tcp, (byte)0);
        check(snapshot.equals(m.toJson(17)), "metadata does not retain caller mutable packet buffer");
        check(snapshot.equals("{\"count\":17,\"sourceIp\":\"192.168.77.100\",\"destinationIp\":\"192.168.77.1\","
                + "\"protocol\":6,\"sourcePort\":65535,\"destinationPort\":443}"),
                "JSON exposes only approved tuple and count");
        byte[] udp = packet(6, 17, 8);
        put16(udp, 20, 1234); // IPv4 options must never be interpreted as ports.
        put16(udp, 22, 9999);
        m = parse(udp);
        check(m != null && m.sourcePort == 65535 && m.destinationPort == 443, "IHL controls UDP port offset");
        for (int protocol : new int[]{6, 17}) {
            int header = protocol == 6 ? 20 : 8;
            byte[] shortPacket = packet(5, protocol, header - 1);
            m = parse(shortPacket);
            check(m != null && m.sourcePort == -1 && m.destinationPort == -1,
                    "truncated transport never invents ports");
            byte[] fragment = packet(5, protocol, header);
            put16(fragment, 6, 1);
            m = parse(fragment);
            check(m != null && m.sourcePort == -1 && m.destinationPort == -1,
                    "later fragment cannot spoof transport ports");
            put16(fragment, 6, 0x2000);
            m = parse(fragment);
            check(m != null && m.sourcePort == 65535 && m.destinationPort == 443,
                    "initial fragment can expose complete transport header");
            byte[] padded = Arrays.copyOf(packet(5, protocol, header), 80);
            put16(padded, 2, 20);
            m = parse(padded);
            check(m != null && m.sourcePort == -1 && m.destinationPort == -1,
                    "IP total length wins over backing-buffer padding");
        }
        byte[] malformed = packet(5, 6, 20);
        malformed[0] = 0x65;
        check(parse(malformed) == null, "IPv6 cannot masquerade as IPv4");
        malformed[0] = 0x44;
        check(parse(malformed) == null, "IHL below minimum");
        malformed[0] = 0x4f;
        check(parse(malformed) == null, "IHL beyond available bytes");
        malformed[0] = 0x45;
        put16(malformed, 2, 19);
        check(parse(malformed) == null, "IP total shorter than header");
        put16(malformed, 2, 41);
        check(parse(malformed) == null, "IP total longer than supplied bytes");
        byte[] icmp = packet(5, 1, 8);
        m = parse(icmp);
        check(m != null && m.protocol == 1 && m.sourcePort == -1 && m.destinationPort == -1,
                "unknown transport has no ports");
        byte[] payload = "Authorization: Bearer PRIVATE_TEST_PAYLOAD".getBytes(StandardCharsets.US_ASCII);
        byte[] withPayload = Arrays.copyOf(packet(5, 6, 20), 40 + payload.length);
        put16(withPayload, 2, withPayload.length);
        System.arraycopy(payload, 0, withPayload, 40, payload.length);
        String json = parse(withPayload).toJson(1);
        check(!json.contains("Authorization") && !json.contains("PRIVATE_TEST_PAYLOAD") && json.length() < 180,
                "transport body and credentials never enter metadata JSON");
    }
    private static void robustness() {
        Random random = new Random(0x53544154494f4eL);
        for (int index = 0; index < 10000; index++) {
            byte[] candidate = new byte[random.nextInt(160)];
            random.nextBytes(candidate);
            int length = random.nextInt(candidate.length + 8) - 3;
            PacketMetadata m = PacketMetadata.parse(candidate, length);
            if (m != null) {
                check(m.sourcePort >= -1 && m.sourcePort <= 65535
                        && m.destinationPort >= -1 && m.destinationPort <= 65535, "bounded fuzzed ports");
                check(m.toJson(index).length() < 200, "bounded fuzzed metadata");
            }
        }
        check(true, "10000 deterministic malformed/truncated inputs caused no exception");
    }
    public static void main(String[] args) {
        spec(); metadata(); robustness();
        System.out.println(assertions + " assertions passed; production parsers only; no device or network");
    }
}
