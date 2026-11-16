package local.station.wifi;

import java.util.Arrays;
import java.util.Locale;

/** Bounded IPv4/UDP DNS codec. Split DNS is routing policy, not an OTA firewall. */
public final class DnsPolicy {
    public static final String DNS_IP = "10.111.0.1";
    public static final String TUN_IP = "10.111.77.2";
    public static final String SERVICE_IP = "10.111.77.1";
    private static final byte[] DNS_ADDRESS = {10, 111, 0, 1};
    private static final byte[] SERVICE_ADDRESS = {10, 111, 77, 1};
    private static final int MAX_DNS = 4096;

    private DnsPolicy() {}

    public static final class Query {
        public final byte[] dnsBytes;
        public final byte[] clientIp;
        public final int clientPort;
        public final String name;
        public final int type;
        private final byte[] question;
        private final byte[] replyAddress;
        private final int transactionId;
        private final int queryFlags;

        private Query(byte[] dns, byte[] address, int port, Question parsed) {
            dnsBytes = dns.clone();
            clientIp = address.clone();
            replyAddress = address.clone();
            clientPort = port;
            name = parsed.name;
            type = parsed.type;
            question = Arrays.copyOfRange(dns, 12, parsed.end);
            transactionId = u16(dns, 0);
            queryFlags = u16(dns, 2);
        }
    }

    /** Only complete, unfragmented IPv4 UDP queries addressed to our DNS server. */
    public static Query parsePacket(byte[] packet, int length) {
        if (packet == null || length < 40 || length > packet.length) return null;
        if ((packet[0] & 0xf0) != 0x40 || (packet[9] & 255) != 17) return null;
        int ihl = (packet[0] & 15) * 4;
        if (ihl < 20 || ihl > 60 || ihl + 20 > length || u16(packet, 2) != length) return null;
        if ((u16(packet, 6) & 0xbfff) != 0 || checksum(packet, 0, ihl, 0) != 0) return null;
        if (!sameAddress(packet, 16, DNS_ADDRESS)) return null;
        int udpLength = u16(packet, ihl + 4);
        int port = u16(packet, ihl);
        if (port == 0 || u16(packet, ihl + 2) != 53 || udpLength < 20
                || udpLength != length - ihl || udpLength - 8 > MAX_DNS) return null;
        // UDP checksum zero is legal for IPv4. Verify a checksum when supplied.
        if (u16(packet, ihl + 6) != 0) {
            int pseudo = sum(packet, 12, 8, 0) + 17 + udpLength;
            if (checksum(packet, ihl, udpLength, pseudo) != 0) return null;
        }
        byte[] dns = Arrays.copyOfRange(packet, ihl + 8, length);
        int flags = u16(dns, 2);
        if ((flags & 0xfecf) != 0 || u16(dns, 4) != 1 || u16(dns, 6) != 0
                || u16(dns, 8) != 0 || u16(dns, 10) > 1) return null;
        Question question = question(dns);
        if (question == null) return null;
        if (u16(dns, 10) == 0) {
            if (question.end != dns.length) return null;
        } else if (!validOpt(dns, question.end)) return null;
        return new Query(dns, Arrays.copyOfRange(packet, 12, 16), port, question);
    }

    /** A pin/NXDOMAIN response, or null if the question needs real upstream DNS. */
    public static byte[] localResponse(Query query) {
        if (query == null) return null;
        boolean blocked = isBlocked(query.name);
        if (!blocked && !isPinned(query.name)) return null;
        boolean answer = !blocked && query.type == 1;
        byte[] dns = new byte[12 + query.question.length + (answer ? 16 : 0)];
        put16(dns, 0, query.transactionId);
        put16(dns, 2, 0x8080 | (query.queryFlags & 0x0110) | (blocked ? 3 : 0));
        put16(dns, 4, 1);
        put16(dns, 6, answer ? 1 : 0);
        System.arraycopy(query.question, 0, dns, 12, query.question.length);
        if (answer) {
            int offset = 12 + query.question.length;
            put16(dns, offset, 0xc00c);
            put16(dns, offset + 2, 1);
            put16(dns, offset + 4, 1);
            put16(dns, offset + 8, 30); // 32-bit TTL = 30s.
            put16(dns, offset + 10, 4);
            System.arraycopy(SERVICE_ADDRESS, 0, dns, offset + 12, 4);
        }
        return packet(query, dns);
    }

    /** Validate the upstream response ID/question and RR bounds, then wrap it. */
    public static byte[] reply(Query query, byte[] dnsReply) {
        if (query == null || dnsReply == null || dnsReply.length < 12 || dnsReply.length > MAX_DNS
                || u16(dnsReply, 0) != query.transactionId || (u16(dnsReply, 2) & 0xf840) != 0x8000
                || u16(dnsReply, 4) != 1) return null;
        Question actual = question(dnsReply);
        if (actual == null || !actual.name.equals(query.name) || actual.type != query.type) return null;
        int records = u16(dnsReply, 6) + u16(dnsReply, 8) + u16(dnsReply, 10);
        if (records > 128) return null;
        int offset = actual.end;
        for (int i = 0; i < records; i++) {
            offset = skipName(dnsReply, offset);
            if (offset < 0 || offset + 10 > dnsReply.length) return null;
            int size = u16(dnsReply, offset + 8);
            offset += 10;
            if (size > dnsReply.length - offset) return null;
            offset += size;
        }
        if (offset != dnsReply.length) return null;
        return packet(query, dnsReply);
    }

    public static boolean isPinned(String name) {
        return "quasar.yandex.net".equals(name) || "quasar.yandex.ru".equals(name)
                || "station-config.quasar.yandex.net".equals(name)
                || "scbh.yandex.net".equals(name) || "api.lbs.yandex.net".equals(name)
                || "uniproxy.alice.yandex.net".equals(name) || "oauth.yandex.ru".equals(name);
    }

    public static boolean isBlocked(String name) {
        return under(name, "clck.yandex.net") || under(name, "startup.mobile.yandex.net")
                || under(name, "startup.mobile.webvisor.com") || under(name, "dr.yandex.net")
                || under(name, "dr2.yandex.net") || under(name, "yandexmetrica.com")
                || under(name, "mc.yandex.ru");
    }

    private static boolean under(String name, String suffix) {
        return name != null && (name.equals(suffix) || name.endsWith("." + suffix));
    }

    private static Question question(byte[] dns) {
        int offset = 12;
        StringBuilder name = new StringBuilder();
        while (offset < dns.length) {
            int size = dns[offset++] & 255;
            if (size == 0) {
                if (name.length() == 0 || offset - 12 > 255 || offset + 4 > dns.length) return null;
                int type = u16(dns, offset);
                if (u16(dns, offset + 2) != 1) return null;
                return new Question(name.toString().toLowerCase(Locale.ROOT), type, offset + 4);
            }
            if (size > 63 || offset + size > dns.length || offset + size - 12 >= 255) return null;
            if (name.length() != 0) name.append('.');
            for (int i = 0; i < size; i++) {
                int value = dns[offset++] & 255;
                if (!(value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z'
                        || value >= '0' && value <= '9' || value == '-' || value == '_')) return null;
                name.append((char) value);
            }
        }
        return null;
    }

    private static boolean validOpt(byte[] dns, int offset) {
        if (offset + 11 > dns.length || dns[offset] != 0 || u16(dns, offset + 1) != 41
                || dns[offset + 5] != 0 || dns[offset + 6] != 0) return false;
        int end = offset + 11 + u16(dns, offset + 9);
        if (end != dns.length) return false;
        offset += 11;
        while (offset < end) {
            if (offset + 4 > end) return false;
            int size = u16(dns, offset + 2);
            offset += 4;
            if (size > end - offset) return false;
            offset += size;
        }
        return true;
    }

    private static int skipName(byte[] dns, int offset) {
        int end = -1;
        int labels = 0;
        int size = 0;
        for (int steps = 0; steps < 128; steps++) {
            if (offset < 12 || offset >= dns.length) return -1;
            int count = dns[offset++] & 255;
            if (count == 0) return size <= 255 ? (end < 0 ? offset : end) : -1;
            if ((count & 0xc0) == 0xc0) {
                if (offset >= dns.length) return -1;
                int pointer = (count & 63) * 256 + (dns[offset] & 255);
                if (pointer >= offset - 1) return -1; // RFC1035 compression points backwards.
                if (end < 0) end = offset + 1;
                offset = pointer;
            } else {
                if (count > 63 || offset + count > dns.length || ++labels > 127) return -1;
                size += count + 1;
                offset += count;
            }
        }
        return -1;
    }

    private static byte[] packet(Query query, byte[] dns) {
        byte[] result = new byte[28 + dns.length];
        result[0] = 0x45;
        put16(result, 2, result.length);
        put16(result, 6, 0x4000);
        result[8] = 64;
        result[9] = 17;
        System.arraycopy(DNS_ADDRESS, 0, result, 12, 4);
        System.arraycopy(query.replyAddress, 0, result, 16, 4);
        put16(result, 20, 53);
        put16(result, 22, query.clientPort);
        put16(result, 24, 8 + dns.length);
        System.arraycopy(dns, 0, result, 28, dns.length);
        int udpChecksum = checksum(result, 20, 8 + dns.length, sum(result, 12, 8, 0) + 17 + 8 + dns.length);
        put16(result, 26, udpChecksum == 0 ? 0xffff : udpChecksum);
        put16(result, 10, checksum(result, 0, 20, 0));
        return result;
    }

    private static boolean sameAddress(byte[] packet, int offset, byte[] address) {
        for (int i = 0; i < 4; i++) if (packet[offset + i] != address[i]) return false;
        return true;
    }
    private static int u16(byte[] data, int offset) {
        return (data[offset] & 255) * 256 + (data[offset + 1] & 255);
    }
    private static void put16(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 8);
        data[offset + 1] = (byte) value;
    }
    private static int sum(byte[] data, int offset, int length, int initial) {
        int value = initial;
        int end = offset + length;
        for (; offset + 1 < end; offset += 2) value += u16(data, offset);
        if (offset < end) value += (data[offset] & 255) << 8;
        return value;
    }
    private static int checksum(byte[] data, int offset, int length, int initial) {
        int value = sum(data, offset, length, initial);
        while ((value >>> 16) != 0) value = (value & 65535) + (value >>> 16);
        return ~value & 65535;
    }
    private static final class Question {
        final String name;
        final int type;
        final int end;
        Question(String name, int type, int end) { this.name = name; this.type = type; this.end = end; }
    }
}
