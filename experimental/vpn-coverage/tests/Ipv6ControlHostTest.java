package local.station.vpnprobe;

import java.util.Arrays;
import java.util.Random;
import java.nio.charset.StandardCharsets;

/** Narrow TUN-local IPv6 control classification; never an IPv6 firewall test. */
public final class Ipv6ControlHostTest {
    private static int assertions;
    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }
    private static void put16(byte[] value, int offset, int number) {
        value[offset] = (byte)(number >>> 8);
        value[offset + 1] = (byte)number;
    }
    private static byte[] control(int type, boolean hopByHop, boolean linkLocal) {
        int icmpOffset = hopByHop ? 48 : 40;
        byte[] value = new byte[icmpOffset + 32];
        value[0] = 0x60;
        put16(value, 4, value.length - 40);
        value[6] = (byte)(hopByHop ? 0 : 58);
        value[7] = (byte)((type == 131 || type == 132 || type == 143) ? 1 : 255);
        if (linkLocal) {
            value[8] = (byte)0xfe;
            value[9] = (byte)0x80;
            value[23] = 2;
        }
        value[24] = (byte)0xff;
        value[25] = 2;
        value[39] = 1;
        if (hopByHop) { value[40] = 58; value[41] = 0; }
        value[icmpOffset] = (byte)type;
        return value;
    }
    private static boolean ignored(byte[] value) {
        return PacketMetadata.isTunIpv6Control(value, value.length);
    }
    private static void positives() {
        for (int type : new int[]{131, 132, 133, 135, 136, 143}) {
            for (boolean hop : new boolean[]{false, true}) {
                for (boolean local : new boolean[]{false, true}) {
                    boolean mld = type == 131 || type == 132 || type == 143;
                    check(ignored(control(type, hop, local)) == (!hop || mld),
                            "ICMPv6 control tuple type=" + type + " HBH=" + hop);
                }
            }
        }
        byte[] highestLinkLocal = control(135, false, true);
        highestLinkLocal[9] = (byte)0xbf;
        check(ignored(highestLinkLocal), "fe80/10 upper boundary is link-local");
    }
    private static void rejectUnrelated() {
        check(!PacketMetadata.isTunIpv6Control(null, 0), "null not silently ignored");
        byte[] valid = control(135, false, true);
        check(!PacketMetadata.isTunIpv6Control(valid, -1), "negative read length");
        check(!PacketMetadata.isTunIpv6Control(valid, valid.length + 1), "read length beyond buffer");
        check(!PacketMetadata.isTunIpv6Control(valid, valid.length - 1), "truncated declared IPv6 payload");
        byte[] wrongVersion = valid.clone(); wrongVersion[0] = 0x40;
        check(!ignored(wrongVersion), "IPv4 must take its normal parser path");
        byte[] applicationUdp = valid.clone(); applicationUdp[6] = 17;
        check(!ignored(applicationUdp), "application UDP never classified as local control");
        byte[] applicationTcp = valid.clone(); applicationTcp[6] = 6;
        check(!ignored(applicationTcp), "application TCP never classified as local control");
        byte[] globalSource = valid.clone(); globalSource[8] = 0x20; globalSource[9] = 1;
        check(!ignored(globalSource), "global unicast source never ignored");
        byte[] uniqueLocalSource = valid.clone(); uniqueLocalSource[8] = (byte)0xfd; uniqueLocalSource[9] = 1;
        check(!ignored(uniqueLocalSource), "ULA source never ignored");
        byte[] loopbackSource = control(135, false, false); loopbackSource[23] = 1;
        check(!ignored(loopbackSource), "loopback source is not unspecified");
        byte[] outsideLower = valid.clone(); outsideLower[9] = 0x7f;
        check(!ignored(outsideLower), "fe7f is outside fe80/10");
        byte[] outsideUpper = valid.clone(); outsideUpper[9] = (byte)0xc0;
        check(!ignored(outsideUpper), "fec0 is outside fe80/10");
        byte[] globalDest = valid.clone(); globalDest[24] = 0x20; globalDest[25] = 1;
        check(!ignored(globalDest), "global destination never ignored");
        byte[] siteMulticast = valid.clone(); siteMulticast[25] = 5;
        check(!ignored(siteMulticast), "ff05 multicast is outside ff02/16");
        byte[] multicastFlags = valid.clone(); multicastFlags[25] = 0x12;
        check(!ignored(multicastFlags), "ff12 is outside exact ff02/16");
        for (int type : new int[]{0, 1, 128, 129, 130, 134, 137, 138, 144, 255}) {
            check(!ignored(control(type, false, true)), "unapproved ICMPv6 type=" + type);
            check(!ignored(control(type, true, true)), "unapproved ICMPv6 after HBH type=" + type);
        }
        byte[] hbhUdp = control(135, true, true); hbhUdp[40] = 17;
        check(!ignored(hbhUdp), "HBH to UDP must not ignore application traffic");
        byte[] routing = control(135, true, true); routing[6] = 43;
        check(!ignored(routing), "unhandled routing extension refuses classification");
        byte[] fragment = control(135, true, true); fragment[6] = 44;
        check(!ignored(fragment), "fragment extension refuses classification");
        byte[] nested = control(135, true, true); nested[40] = 0;
        check(!ignored(nested), "nested HBH refuses classification");
        for (int type : new int[]{131, 132, 133, 135, 136, 143}) {
            byte[] wrongCode = control(type, false, true); wrongCode[41] = 1;
            check(!ignored(wrongCode), "nonzero ICMPv6 code refused for type=" + type);
            for (int hopLimit : new int[]{0, 2, 64, 254}) {
                byte[] wrongHop = control(type, false, true); wrongHop[7] = (byte)hopLimit;
                check(!ignored(wrongHop), "invalid hop limit=" + hopLimit + " type=" + type);
            }
            byte[] wrongFamilyHop = control(type, false, true);
            wrongFamilyHop[7] = (byte)((type == 131 || type == 132 || type == 143) ? 255 : 1);
            check(!ignored(wrongFamilyHop), "MLD and NDP hop limits are distinct type=" + type);
        }
    }
    private static void malformed() {
        for (boolean hop : new boolean[]{false, true}) {
            byte[] valid = control(135, hop, true);
            for (int length = 0; length < valid.length; length++) {
                check(!PacketMetadata.isTunIpv6Control(valid, length), "truncated control read length=" + length);
            }
            byte[] padded = Arrays.copyOf(valid, valid.length + 1);
            check(!ignored(padded), "extra data beyond IPv6 declared payload is not silently discarded");
            byte[] undersized = valid.clone(); put16(undersized, 4, 0);
            check(!ignored(undersized), "zero payload cannot provide ICMP header");
            put16(undersized, 4, 65535);
            check(!ignored(undersized), "oversized declared payload refuses classification");
        }
        byte[] badHbh = control(135, true, true); badHbh[41] = 20;
        check(!ignored(badHbh), "HBH length beyond packet is refused");
        byte[] noIcmp = Arrays.copyOf(control(135, true, true), 48);
        put16(noIcmp, 4, 8);
        check(!ignored(noIcmp), "HBH without ICMP header is refused");
        byte[] shortIcmp = Arrays.copyOf(control(135, false, true), 43);
        put16(shortIcmp, 4, 3);
        check(!ignored(shortIcmp), "less than complete ICMPv6 base header is refused");
        int[][] minimumBodies = {{131, 24}, {132, 24}, {133, 8}, {135, 24}, {136, 24}, {143, 8}};
        for (int[] boundary : minimumBodies) {
            byte[] exact = Arrays.copyOf(control(boundary[0], false, true), 40 + boundary[1]);
            put16(exact, 4, boundary[1]);
            check(ignored(exact), "complete minimum control body type=" + boundary[0]);
            byte[] tooShort = Arrays.copyOf(exact, exact.length - 1);
            put16(tooShort, 4, boundary[1] - 1);
            check(!ignored(tooShort), "incomplete type-specific body type=" + boundary[0]);
        }
        byte[] longerHbh = new byte[64];
        System.arraycopy(control(143, true, true), 0, longerHbh, 0, 40);
        put16(longerHbh, 4, 24); longerHbh[40] = 58; longerHbh[41] = 1; longerHbh[56] = (byte)143;
        check(ignored(longerHbh), "bounded two-unit HBH reaches full MLD header");
        longerHbh[41] = 2;
        check(!ignored(longerHbh), "HBH cannot consume the ICMP body");
        Random random = new Random(0x495056364354524cL);
        for (int index = 0; index < 10000; index++) {
            byte[] bytes = new byte[random.nextInt(160)]; random.nextBytes(bytes);
            int length = random.nextInt(bytes.length + 8) - 3;
            PacketMetadata.isTunIpv6Control(bytes, length);
        }
        check(true, "10000 malformed inputs cause no exception");
    }
    private static void boundedDiagnostics() {
        byte[] direct = control(135, false, true);
        check(PacketMetadata.headerSummary(direct, direct.length).equals(
                "version=6 length=72 declaredLength=72 nextHeader=58 icmpType=135 ipv4Destination=unavailable"),
                "diagnostics show bounded IPv6 direct-header fields");
        byte[] hbh = control(143, true, true);
        check(PacketMetadata.headerSummary(hbh, hbh.length).equals(
                "version=6 length=80 declaredLength=80 nextHeader=0 icmpType=143 ipv4Destination=unavailable"),
                "diagnostics can identify MLD after HBH");
        byte[] payloadMarker = "PRIVATE_PAYLOAD_1234".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(payloadMarker, 0, direct, 48, payloadMarker.length);
        String summary = PacketMetadata.headerSummary(direct, direct.length);
        check(!summary.contains("PRIVATE") && summary.length() < 200,
                "diagnostics do not dump packet bodies");
        direct[6] = 17;
        check(PacketMetadata.headerSummary(direct, direct.length).contains("nextHeader=17 icmpType=-1"),
                "application UDP is not mislabeled as ICMP");
        put16(hbh, 4, 1);
        check(PacketMetadata.headerSummary(hbh, hbh.length).contains("icmpType=-1"),
                "malformed payload does not yield a claimed ICMP type");
        for (int length : new int[]{-1, 0, 1, 39, direct.length + 1, Integer.MAX_VALUE}) {
            check(PacketMetadata.headerSummary(direct, length).length() < 200,
                    "truncated or impossible read retains bounded diagnostics");
        }
        check(PacketMetadata.headerSummary(null, 0).equals("version=-1 length=0 nextHeader=-1 icmpType=-1"),
                "missing packet has no inferred payload fields");
        Random random = new Random(0x444941474e4f5354L);
        for (int index = 0; index < 10000; index++) {
            byte[] bytes = new byte[random.nextInt(160)]; random.nextBytes(bytes);
            int length = random.nextInt(bytes.length + 8) - 3;
            String description = PacketMetadata.headerSummary(bytes, length);
            if (description.length() >= 200) throw new AssertionError("unbounded diagnostics");
        }
        check(true, "10000 malformed summaries remain bounded without exceptions");
    }
    public static void main(String[] args) {
        positives(); rejectUnrelated(); malformed(); boundedDiagnostics();
        System.out.println(assertions + " assertions passed; TUN IPv6 control classifier only; no IPv6 security claim");
    }
}
