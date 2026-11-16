package local.station.wifi;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

public final class DnsPolicyHostTest {
    private static int assertions;

    public static void main(String[] args) {
        localPins();
        blockedNames();
        upstream();
        malformedPackets();
        malformedDns();
        edns();
        fuzz();
        System.out.println(assertions + " assertions passed; offline DNS packet codec only; no device/network");
    }

    private static void localPins() {
        for (String name : new String[]{"quasar.yandex.net", "quasar.yandex.ru", "station-config.quasar.yandex.net", "scbh.yandex.net",
                "api.lbs.yandex.net", "uniproxy.alice.yandex.net", "oauth.yandex.ru"}) {
            byte[] original = query(name, 1);
            DnsPolicy.Query parsed = parse(original);
            check(parsed != null && name.equals(parsed.name), "pin query");
            check(parsed.clientPort == 34567 && parsed.type == 1, "client metadata");
            byte[] response = DnsPolicy.localResponse(parsed);
            verifyPacket(response);
            check(u16(response, 28) == 0x1234 && u16(response, 34) == 1, "ID and answer count");
            check(Arrays.equals(Arrays.copyOfRange(response, response.length - 4, response.length),
                    new byte[]{10, 111, 77, 1}), "local service address");
            byte[] aaaa = DnsPolicy.localResponse(parse(query(name, 28)));
            verifyPacket(aaaa);
            check(u16(aaaa, 34) == 0 && (aaaa[31] & 15) == 0, "AAAA has no external answer");
        }
        DnsPolicy.Query mixed = parse(query("QUASAR.YANDEX.NET", 1));
        check(mixed != null && "quasar.yandex.net".equals(mixed.name), "case folding");
        byte[] input = query("quasar.yandex.net", 1);
        DnsPolicy.Query copy = parse(input);
        Arrays.fill(input, (byte) 0);
        Arrays.fill(copy.dnsBytes, (byte) 0);
        Arrays.fill(copy.clientIp, (byte) 0);
        byte[] result = DnsPolicy.localResponse(copy);
        check(result != null && u16(result, 28) == 0x1234 && (result[19] & 255) == 2,
                "validated reply metadata retained independently");
        check(DnsPolicy.localResponse(null) == null, "null query");
    }

    private static void blockedNames() {
        for (String name : new String[]{"clck.yandex.net", "child.clck.yandex.net", "startup.mobile.yandex.net",
                "u.startup.mobile.webvisor.com", "dr.yandex.net", "x.dr2.yandex.net",
                "yandexmetrica.com", "sub.mc.yandex.ru"}) {
            byte[] response = DnsPolicy.localResponse(parse(query(name, 1)));
            check(response != null && (response[31] & 15) == 3 && u16(response, 34) == 0, "blocked NXDOMAIN");
        }
        check(DnsPolicy.localResponse(parse(query("notclck.yandex.net", 1))) == null, "suffix label boundary");
        check(DnsPolicy.localResponse(parse(query("clck.yandex.net.example.org", 1))) == null, "no suffix confusion");
    }

    private static void upstream() {
        DnsPolicy.Query q = parse(query("example.org", 1));
        check(q != null && DnsPolicy.localResponse(q) == null, "unknown needs upstream");
        byte[] dns = q.dnsBytes.clone();
        put16(dns, 2, 0x8180);
        byte[] result = DnsPolicy.reply(q, dns);
        verifyPacket(result);
        check(Arrays.equals(Arrays.copyOfRange(result, 28, result.length), dns), "opaque upstream payload preserved");
        byte[] record = Arrays.copyOf(dns, dns.length + 16);
        put16(record, 6, 1);
        int off = dns.length;
        put16(record, off, 0xc00c);
        put16(record, off + 2, 1); put16(record, off + 4, 1);
        put16(record, off + 8, 30); put16(record, off + 10, 4);
        record[off + 12] = 1; record[off + 13] = 2; record[off + 14] = 3; record[off + 15] = 4;
        check(DnsPolicy.reply(q, record) != null, "compressed answer owner accepted");
        byte[] bad = record.clone(); bad[0] ^= 1;
        check(DnsPolicy.reply(q, bad) == null, "transaction ID mismatch");
        bad = record.clone(); bad[2] = 1;
        check(DnsPolicy.reply(q, bad) == null, "query is not a response");
        bad = record.clone(); bad[13] = 'X';
        check(DnsPolicy.reply(q, bad) == null, "question name mismatch");
        bad = record.clone(); put16(bad, off - 4, 28);
        check(DnsPolicy.reply(q, bad) == null, "question type mismatch");
        bad = record.clone(); put16(bad, off + 10, 5);
        check(DnsPolicy.reply(q, bad) == null, "RR truncated RDATA");
        bad = record.clone(); put16(bad, off, 0xc000 | off);
        check(DnsPolicy.reply(q, bad) == null, "cyclic compression pointer");
        check(DnsPolicy.reply(q, Arrays.copyOf(record, record.length + 1)) == null, "unaccounted reply trailer");
        check(DnsPolicy.reply(null, dns) == null && DnsPolicy.reply(q, null) == null, "null reply inputs");
    }

    private static void malformedPackets() {
        byte[] good = query("example.org", 1);
        check(DnsPolicy.parsePacket(null, 40) == null && DnsPolicy.parsePacket(good, -1) == null,
                "null and negative length");
        check(DnsPolicy.parsePacket(good, good.length + 1) == null, "buffer overrun length");
        check(DnsPolicy.parsePacket(good, good.length - 1) == null, "truncated packet");
        for (int[] edit : new int[][]{{0, 0x65}, {0, 0x44}, {9, 6}, {16, 11}, {22, 1}, {3, 0}, {25, 0}}) {
            byte[] bad = good.clone(); bad[edit[0]] = (byte) edit[1]; repairIp(bad);
            check(parse(bad) == null, "bad IPv4/UDP field " + edit[0]);
        }
        for (int fragment : new int[]{0x2000, 0x4001, 0x8000}) {
            byte[] bad = good.clone(); put16(bad, 6, fragment); repairIp(bad);
            check(parse(bad) == null, "fragments/reserved flags rejected");
        }
        byte[] bad = good.clone(); bad[10] ^= 1;
        check(parse(bad) == null, "IPv4 checksum checked");
        byte[] withChecksum = good.clone();
        put16(withChecksum, 26, checksum(withChecksum, 20, withChecksum.length - 20,
                sum(withChecksum, 12, 8) + 17 + withChecksum.length - 20));
        check(parse(withChecksum) != null, "valid nonzero UDP checksum");
        withChecksum[withChecksum.length - 1] ^= 1;
        check(parse(withChecksum) == null, "nonzero UDP checksum checked");
    }

    private static void malformedDns() {
        byte[] good = query("example.org", 1);
        for (int[] edit : new int[][]{{30, 0x81}, {30, 0x79}, {30, 0x05}, {31, 1}, {33, 2},
                {35, 1}, {37, 1}, {39, 2}, {40, 0xc0}, {41, '/'}}) {
            byte[] bad = good.clone(); bad[edit[0]] = (byte) edit[1];
            check(parse(bad) == null, "bad DNS field " + edit[0]);
        }
        byte[] bad = good.clone(); put16(bad, bad.length - 2, 3);
        check(parse(bad) == null, "non-IN question");
        byte[] trailer = Arrays.copyOf(good, good.length + 1);
        put16(trailer, 2, trailer.length); put16(trailer, 24, trailer.length - 20); repairIp(trailer);
        check(parse(trailer) == null, "undeclared query trailer");
    }

    private static void edns() {
        byte[] good = query("example.org", 1);
        byte[] withOpt = Arrays.copyOf(good, good.length + 11);
        put16(withOpt, 2, withOpt.length); put16(withOpt, 24, withOpt.length - 20); put16(withOpt, 38, 1);
        int offset = good.length;
        put16(withOpt, offset + 1, 41); put16(withOpt, offset + 3, 1232);
        repairIp(withOpt);
        check(parse(withOpt) != null, "single EDNS OPT accepted");
        withOpt[offset + 6] = 1;
        check(parse(withOpt) == null, "unsupported EDNS version rejected");
    }

    private static void fuzz() {
        Random random = new Random(0x31337);
        for (int i = 0; i < 12000; i++) {
            byte[] bytes = new byte[random.nextInt(700)]; random.nextBytes(bytes);
            DnsPolicy.parsePacket(bytes, bytes.length);
            DnsPolicy.reply(parse(query("example.org", 1)), bytes);
        }
        byte[] valid = query("example.org", 1);
        for (int length = 0; length < valid.length; length++) {
            check(DnsPolicy.parsePacket(Arrays.copyOf(valid, length), length) == null, "all truncation boundaries");
        }
    }

    private static DnsPolicy.Query parse(byte[] packet) { return DnsPolicy.parsePacket(packet, packet.length); }
    private static byte[] query(String name, int type) {
        ByteArrayOutputStream dns = new ByteArrayOutputStream();
        for (int v : new int[]{0x12,0x34,1,0,0,1,0,0,0,0,0,0}) dns.write(v);
        for (String label : name.split("\\.")) {
            byte[] text = label.getBytes(StandardCharsets.US_ASCII); dns.write(text.length); dns.write(text, 0, text.length);
        }
        dns.write(0); dns.write(type >>> 8); dns.write(type); dns.write(0); dns.write(1);
        byte[] body = dns.toByteArray(); byte[] packet = new byte[28 + body.length];
        packet[0] = 0x45; put16(packet, 2, packet.length); put16(packet, 6, 0x4000); packet[8] = 64; packet[9] = 17;
        byte[] source = {10,111,77,2}, target = {10,111,0,1};
        System.arraycopy(source,0,packet,12,4); System.arraycopy(target,0,packet,16,4);
        put16(packet,20,34567); put16(packet,22,53); put16(packet,24,body.length+8);
        System.arraycopy(body,0,packet,28,body.length); repairIp(packet); return packet;
    }
    private static void verifyPacket(byte[] response) {
        check(response != null && u16(response,2) == response.length, "reply IPv4 length");
        check(checksum(response,0,20,0) == 0, "reply IPv4 checksum");
        check(u16(response,20) == 53 && u16(response,22) == 34567, "reply UDP ports");
        check(u16(response,24) == response.length - 20, "reply UDP length");
        check(checksum(response,20,response.length-20,sum(response,12,8)+17+response.length-20) == 0,
                "reply UDP checksum");
    }
    private static void repairIp(byte[] p) { put16(p,10,0); put16(p,10,checksum(p,0,20,0)); }
    private static int sum(byte[] p,int off,int length) {
        int total=0,end=off+length; for(;off+1<end;off+=2)total+=u16(p,off);
        if(off<end)total+=(p[off]&255)<<8; return total;
    }
    private static int checksum(byte[]p,int off,int length,int initial) {
        int value=sum(p,off,length)+initial; while((value>>>16)!=0)value=(value&65535)+(value>>>16); return ~value&65535;
    }
    private static int u16(byte[] p,int off) { return (p[off]&255)*256+(p[off+1]&255); }
    private static void put16(byte[]p,int off,int value) { p[off]=(byte)(value>>>8);p[off+1]=(byte)value; }
    private static void check(boolean value,String reason) { assertions++;if(!value)throw new AssertionError(reason); }
}
