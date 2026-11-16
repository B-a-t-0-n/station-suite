package local.station.vpnprobe;

import java.util.Arrays;
import java.util.Random;

/** Header admission only: no TCP implementation, sockets, Android or device. */
public final class RelayPacketPolicyHostTest {
    private static final String CONFIG = "192.168.77.1", PHYSICAL = "192.168.77.100";
    private static final String TUN = RelayPacketPolicy.TUN_IP;
    private static int assertions;
    private interface Action { void run(); }
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static void rejects(Action action, String message) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError("Policy accepted: " + message);
    }
    private static void put16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte)(value >>> 8); bytes[offset + 1] = (byte)value;
    }
    private static void ip(byte[] bytes, int offset, String ip) {
        String[] values = ip.split("\\.");
        for (int i = 0; i < 4; i++) bytes[offset + i] = (byte)Integer.parseInt(values[i]);
    }
    private static byte[] tcp(String source, String destination, int sourcePort, int destinationPort,
                              int flags, int ipBytes, int tcpBytes, int payload) {
        byte[] result = new byte[ipBytes + tcpBytes + payload];
        result[0] = (byte)(0x40 | ipBytes / 4); put16(result, 2, result.length);
        result[8] = 64; result[9] = 6;
        ip(result, 12, source); ip(result, 16, destination);
        put16(result, ipBytes, sourcePort); put16(result, ipBytes + 2, destinationPort);
        result[ipBytes + 12] = (byte)(tcpBytes / 4 << 4); result[ipBytes + 13] = (byte)flags;
        // Checksums/seq/ACK are deliberately not implemented by this admission fixture.
        return result;
    }
    private static byte[] syn(String source, int sourcePort, int destinationPort) {
        return tcp(source, CONFIG, sourcePort, destinationPort, 2, 20, 20, 0);
    }
    private static byte[] response(String destination, int destinationPort, int sourcePort) {
        return tcp(CONFIG, destination, sourcePort, destinationPort, 0x12, 20, 20, 0);
    }
    private static RelayPacketPolicy policy() { return new RelayPacketPolicy(CONFIG, PHYSICAL); }
    private static int out(RelayPacketPolicy policy, byte[] packet) { return policy.outbound(packet, packet.length); }

    private static void in(RelayPacketPolicy rule, byte[] packet) { rule.inbound(packet, packet.length); }
    private static void in(RelayPacketPolicy rule, byte[] packet, int length) { rule.inbound(packet, length); }

    private static void constructors() {
        policy(); check(true, "valid isolated config and physical source");
        for (String invalid : new String[]{null, "", "8.8.8.8", "127.0.0.1", "::1", TUN,
                "192.168.077.1", "192.168.77.0", "192.168.77.255"}) {
            rejects(() -> new RelayPacketPolicy(invalid, PHYSICAL), "invalid config");
            rejects(() -> new RelayPacketPolicy(CONFIG, invalid), "invalid physical address");
        }
        rejects(() -> new RelayPacketPolicy(CONFIG, CONFIG), "same config and device address");
    }

    private static void directionsAndState() {
        for (String source : new String[]{TUN, PHYSICAL}) {
            for (int port : new int[]{80, 443}) {
                RelayPacketPolicy rule = policy();
                byte[] request = syn(source, 46080, port);
                check(out(rule, request) == RelayPacketPolicy.FORWARD, "fresh SYN admits either exact source");
                in(rule, response(source, 46080, port)); check(true, "reverse tuple after admitted SYN");
                byte[] data = tcp(source, CONFIG, 46080, port, 0x18, 20, 20, 37);
                check(out(rule, data) == RelayPacketPolicy.FORWARD, "subsequent payload is opaque");
                byte[] reply = tcp(CONFIG, source, port, 46080, 0x18, 20, 20, 71);
                in(rule, reply); check(true, "reverse opaque data follows admitted tuple");
                byte[] reset = tcp(source, CONFIG, 46080, port, 4, 20, 20, 0);
                check(out(rule, reset) == RelayPacketPolicy.FORWARD, "existing flow may reset");
                byte[] finish = tcp(source, CONFIG, 46080, port, 0x11, 20, 20, 0);
                check(out(rule, finish) == RelayPacketPolicy.FORWARD, "existing flow may finish");
                // The bounded tuple allowlist intentionally does not implement TCP state retirement.
                check(out(rule, request) == RelayPacketPolicy.FORWARD, "SYN retransmission does not allocate new slot");
                Arrays.fill(request, (byte)0);
                in(rule, response(source, 46080, port)); check(true, "state is detached from caller packet bytes");
            }
        }
        for (int flags : new int[]{0, 1, 4, 0x10, 0x12, 3, 6, 0x13, 0x18, 0x0a, 0x22, 0x2a, 0xca}) {
            RelayPacketPolicy rule = policy();
            byte[] old = tcp(TUN, CONFIG, 46081, 443, flags, 20, 20, 0);
            check(out(rule, old) == RelayPacketPolicy.DROP, "untracked keepalive/invalid opener does not create flow");
            rejects(() -> in(rule, response(TUN, 46081, 443)), "dropped opener cannot authorize reverse packet");
            check(out(rule, syn(TUN, 46081, 443)) == RelayPacketPolicy.FORWARD, "fresh SYN later admits the tuple");
        }
        RelayPacketPolicy rule = policy();
        check(out(rule, syn(TUN, 46082, 443)) == RelayPacketPolicy.FORWARD, "baseline flow");
        rejects(() -> in(rule, response(PHYSICAL, 46082, 443)), "TUN and physical source identities are distinct");
        rejects(() -> in(rule, response(TUN, 46083, 443)), "reverse client port must match");
        rejects(() -> in(rule, response(TUN, 46082, 80)), "reverse service port must match");
        rejects(() -> in(rule, syn(TUN, 46082, 443)), "outbound packet cannot be injected as inbound");
        rejects(() -> in(policy(), response(TUN, 46082, 443)), "new instance has no inherited state");
        for (int flags : new int[]{0x42, 0x82, 0xc2}) {
            RelayPacketPolicy ecn = policy();
            check(out(ecn, tcp(TUN, CONFIG, 47000, 443, flags, 20, 20, 0)) == RelayPacketPolicy.FORWARD,
                    "TCP ECN SYN flags remain supported");
        }
    }

    private static void boundsAndMalformedFrames() {
        RelayPacketPolicy rule = policy();
        byte[] valid = syn(TUN, 65535, 443);
        rejects(() -> rule.outbound(null, 0), "null packet");
        rejects(() -> rule.outbound(valid, -1), "negative length");
        rejects(() -> rule.outbound(valid, valid.length + 1), "length outside backing array");
        for (int length = 0; length < valid.length; length++) {
            final int count = length;
            rejects(() -> rule.outbound(valid, count), "truncated fixed header");
        }
        byte[] padded = Arrays.copyOf(valid, 80);
        check(rule.outbound(padded, valid.length) == RelayPacketPolicy.FORWARD, "unused backing-array tail is not forwarded");
        rejects(() -> policy().outbound(padded, padded.length), "IP total length must equal frame length");
        byte[] maximum = tcp(TUN, CONFIG, 45000, 80, 2, 20, 20, 1460);
        check(out(policy(), maximum) == RelayPacketPolicy.FORWARD, "exact MTU frame");
        rejects(() -> out(policy(), tcp(TUN, CONFIG, 45000, 80, 2, 20, 20, 1461)), "MTU overflow");
        for (int ipHeader : new int[]{20, 24, 40, 60}) {
            for (int tcpHeader : new int[]{20, 24, 40, 60}) {
                byte[] options = tcp(PHYSICAL, CONFIG, 45001, 443, 2, ipHeader, tcpHeader, 9);
                check(out(policy(), options) == RelayPacketPolicy.FORWARD, "IP/TCP header offsets follow data offsets");
            }
        }
        for (int declared : new int[]{0, 19, 20, 39, 41, 65535}) {
            byte[] wrong = valid.clone(); put16(wrong, 2, declared);
            rejects(() -> out(policy(), wrong), "mismatched IP total length");
        }
        for (int first : new int[]{0, 0x44, 0x4f, 0x60, 0x65}) {
            byte[] wrong = valid.clone(); wrong[0] = (byte)first;
            rejects(() -> out(policy(), wrong), "invalid version or IHL");
        }
        for (int tcpWords : new int[]{0, 4, 6, 15}) {
            byte[] wrong = valid.clone(); wrong[32] = (byte)(tcpWords << 4);
            rejects(() -> out(policy(), wrong), "invalid/truncated TCP data offset");
        }
        for (int flags : new int[]{1, 0x1fff, 0x2000, 0x3fff, 0x8000, 0xc000, 0xffff}) {
            byte[] fragment = valid.clone(); put16(fragment, 6, flags);
            rejects(() -> out(policy(), fragment), "fragment/reserved IPv4 flags");
        }
        byte[] df = valid.clone(); put16(df, 6, 0x4000);
        check(out(policy(), df) == RelayPacketPolicy.FORWARD, "DF is permitted");
        for (int source : new int[]{0, 1, 65535}) {
            byte[] port = syn(TUN, source, 443);
            if (source == 0) rejects(() -> out(policy(), port), "zero source port");
            else check(out(policy(), port) == RelayPacketPolicy.FORWARD, "nonzero full-range source port");
        }
        for (int target : new int[]{0, 1, 53, 123, 8080, 65535}) {
            rejects(() -> out(policy(), syn(TUN, 46080, target)), "service port outside exact pair");
        }
        for (String address : new String[]{"8.8.8.8", "127.0.0.1", "192.168.77.2", "10.111.77.3"}) {
            rejects(() -> out(policy(), tcp(address, CONFIG, 46080, 443, 2, 20, 20, 0)), "unapproved source");
            rejects(() -> out(policy(), tcp(TUN, address, 46080, 443, 2, 20, 20, 0)), "unapproved destination");
            rejects(() -> in(rule, tcp(address, TUN, 443, 65535, 0x12, 20, 20, 0)), "unapproved reverse source");
            rejects(() -> in(rule, tcp(CONFIG, address, 443, 65535, 0x12, 20, 20, 0)), "unapproved reverse destination");
        }
        byte[] unknownProtocol = valid.clone(); unknownProtocol[9] = (byte)132;
        rejects(() -> out(policy(), unknownProtocol), "unsupported transport");
    }

    private static byte[] control6(int type, boolean global) {
        byte[] packet = new byte[72]; packet[0] = 0x60; put16(packet, 4, 32);
        packet[6] = 58; packet[7] = (byte)(type == 131 || type == 132 || type == 143 ? 1 : 255);
        packet[8] = (byte)(global ? 0x20 : 0xfe); packet[9] = (byte)(global ? 1 : 0x80);
        packet[23] = 2; packet[24] = (byte)0xff; packet[25] = 2; packet[39] = 1; packet[40] = (byte)type;
        return packet;
    }

    private static void controlsAndFlowLimit() {
        for (int protocol : new int[]{1, 17}) {
            byte[] packet = syn(TUN, 46080, 443); packet[9] = (byte)protocol;
            check(out(policy(), packet) == RelayPacketPolicy.DROP, "ICMP/UDP never enter TCP stack");
            byte[] physical = packet.clone(); ip(physical, 12, PHYSICAL);
            check(out(policy(), physical) == RelayPacketPolicy.DROP, "physical ICMP/UDP dropped");
            RelayPacketPolicy rule = policy();
            rule.outbound(packet, packet.length);
            rejects(() -> in(rule, response(TUN, 46080, 443)), "dropped transport does not create state");
        }
        for (int type : new int[]{131, 132, 133, 135, 136, 143}) {
            byte[] packet = control6(type, false);
            check(out(policy(), packet) == RelayPacketPolicy.IPV6_CONTROL, "local IPv6 control counted and dropped");
            rejects(() -> out(policy(), control6(type, true)), "global IPv6 never admitted as control");
            rejects(() -> in(policy(), packet, packet.length), "IPv6 output cannot enter real TUN");
        }
        byte[] routerAdvertisement = control6(134, false);
        rejects(() -> out(policy(), routerAdvertisement), "router advertisement not silently ignored");
        byte[] application6 = control6(135, false); application6[6] = 6;
        rejects(() -> out(policy(), application6), "IPv6 TCP not silently ignored");
        RelayPacketPolicy rule = policy();
        for (int i = 0; i < 32; i++) check(out(rule, syn(TUN, 10000 + i, 443)) == RelayPacketPolicy.FORWARD, "flow budget up to 32");
        check(out(rule, syn(TUN, 10000, 443)) == RelayPacketPolicy.FORWARD, "retransmission at limit remains admitted");
        rejects(() -> out(rule, syn(TUN, 10032, 443)), "33rd flow stops admission");
        rejects(() -> in(rule, response(TUN, 10032, 443)), "rejected overflow did not insert state");
        rejects(() -> out(rule, syn(PHYSICAL, 10000, 443)), "other source consumes separate tuple slot");
        rejects(() -> out(rule, syn(TUN, 10000, 80)), "other service consumes separate tuple slot");
        check(out(policy(), syn(TUN, 10032, 443)) == RelayPacketPolicy.FORWARD, "new test instance has empty quota");
    }

    private static void fuzz() {
        Random random = new Random(0x72656c6179676174L);
        for (int i = 0; i < 20000; i++) {
            byte[] candidate = new byte[random.nextInt(1601)]; random.nextBytes(candidate);
            int length = random.nextInt(candidate.length + 7) - 3;
            try {
                int admitted = policy().outbound(candidate, length);
                check(admitted == RelayPacketPolicy.DROP || admitted == RelayPacketPolicy.IPV6_CONTROL,
                        "random frame cannot authorize outbound flow");
            } catch (IllegalArgumentException expected) { }
            try { in(policy(), candidate, length); throw new AssertionError("random inbound authorized"); }
            catch (IllegalArgumentException expected) { }
        }
        for (int i = 0; i < 2000; i++) {
            int ipBytes = 20 + random.nextInt(11) * 4, tcpBytes = 20 + random.nextInt(11) * 4;
            int payload = random.nextInt(1501 - ipBytes - tcpBytes);
            String source = random.nextBoolean() ? TUN : PHYSICAL;
            int sourcePort = 1 + random.nextInt(65535), service = random.nextBoolean() ? 80 : 443;
            byte[] candidate = tcp(source, CONFIG, sourcePort, service, 2, ipBytes, tcpBytes, payload);
            RelayPacketPolicy rule = policy();
            check(out(rule, candidate) == RelayPacketPolicy.FORWARD, "structured IHL/TCP/payload fuzz remains opaque");
            byte[] reverse = tcp(CONFIG, source, service, sourcePort, 0x18, ipBytes, tcpBytes, payload);
            in(rule, reverse);
        }
        check(true, "20000 arbitrary frames and 2000 structured tuples completed without unchecked exceptions");
    }

    public static void main(String[] args) {
        constructors(); directionsAndState(); boundsAndMalformedFrames(); controlsAndFlowLimit(); fuzz();
        System.out.println(assertions + " assertions passed; directional packet policy only; no TCP stack/device/network");
    }
}
