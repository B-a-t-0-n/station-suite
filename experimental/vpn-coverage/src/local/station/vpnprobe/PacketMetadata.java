package local.station.vpnprobe;

/** Parses IP/transport headers only. Never retains a packet or reads transport bodies. */
public final class PacketMetadata {
    public final String sourceIp;
    public final String destinationIp;
    public final int protocol;
    public final int sourcePort;
    public final int destinationPort;

    private PacketMetadata(String source, String destination, int protocol, int sourcePort,
            int destinationPort) {
        this.sourceIp = source;
        this.destinationIp = destination;
        this.protocol = protocol;
        this.sourcePort = sourcePort;
        this.destinationPort = destinationPort;
    }

    public static PacketMetadata parse(byte[] packet, int length) {
        if (packet == null || length < 20 || length > packet.length
                || (packet[0] & 0xf0) != 0x40) return null;
        int headerLength = (packet[0] & 15) * 4;
        int totalLength = unsignedShort(packet, 2);
        if (headerLength < 20 || headerLength > length || totalLength < headerLength
                || totalLength > length) return null;
        int protocol = packet[9] & 255;
        int sourcePort = -1;
        int destinationPort = -1;
        boolean initialFragment = (unsignedShort(packet, 6) & 0x1fff) == 0;
        int requiredHeader = protocol == 6 ? 20 : protocol == 17 ? 8 : 0;
        if (requiredHeader > 0 && initialFragment && totalLength >= headerLength + requiredHeader) {
            sourcePort = unsignedShort(packet, headerLength);
            destinationPort = unsignedShort(packet, headerLength + 2);
        }
        return new PacketMetadata(ip(packet, 12), ip(packet, 16), protocol,
                sourcePort, destinationPort);
    }

    /** Kernel interface-up control only; no general IPv6 traffic is admitted or forwarded. */
    public static boolean isTunIpv6Control(byte[] packet, int length) {
        if (!ipv6LengthIsExact(packet, length)) return false;
        boolean unspecified = true;
        for (int index = 8; index < 24; index++) unspecified &= packet[index] == 0;
        boolean linkLocal = (packet[8] & 255) == 0xfe && (packet[9] & 0xc0) == 0x80;
        if ((!unspecified && !linkLocal) || (packet[24] & 255) != 0xff
                || (packet[25] & 255) != 0x02) return false;
        int initialNext = packet[6] & 255;
        int offset = icmpv6Offset(packet, length);
        if (offset < 0 || length - offset < 4 || packet[offset + 1] != 0) return false;
        int type = packet[offset] & 255;
        boolean mld = type == 131 || type == 132 || type == 143;
        if (initialNext == 0 && !mld) return false;
        if ((packet[7] & 255) != (mld ? 1 : 255)) return false;
        int minimum;
        switch (type) {
            case 131: // MLD listener report v1
            case 132: // MLD listener done v1
            case 135: // Neighbor solicitation (including DAD)
            case 136: // Neighbor advertisement
                minimum = 24;
                break;
            case 133: // Router solicitation; router advertisements are deliberately excluded.
            case 143: // MLD listener report v2 fixed header
                minimum = 8;
                break;
            default:
                return false;
        }
        return length - offset >= minimum;
    }

    /** Bounded diagnostic fields only, including for malformed/unexpected frames. */
    public static String headerSummary(byte[] packet, int length) {
        if (packet == null || length <= 0 || length > packet.length) {
            return "version=-1 length=" + length + " nextHeader=-1 icmpType=-1";
        }
        int version = (packet[0] & 255) >>> 4;
        int next = version == 4 && length >= 20 ? packet[9] & 255
                : version == 6 && length >= 40 ? packet[6] & 255 : -1;
        int icmpOffset = ipv6LengthIsExact(packet, length) ? icmpv6Offset(packet, length) : -1;
        int type = icmpOffset >= 0 && icmpOffset < length ? packet[icmpOffset] & 255 : -1;
        int declared = version == 4 && length >= 20 ? unsignedShort(packet, 2)
                : version == 6 && length >= 40 ? 40 + unsignedShort(packet, 4) : -1;
        PacketMetadata ipv4 = version == 4 ? parse(packet, length) : null;
        return "version=" + version + " length=" + length + " declaredLength=" + declared
                + " nextHeader=" + next + " icmpType=" + type + " ipv4Destination="
                + (ipv4 == null ? "unavailable" : ipv4.destinationIp);
    }

    private static boolean ipv6LengthIsExact(byte[] packet, int length) {
        return packet != null && length >= 40 && length <= packet.length
                && (packet[0] & 0xf0) == 0x60 && unsignedShort(packet, 4) > 0
                && 40 + unsignedShort(packet, 4) == length;
    }

    private static int icmpv6Offset(byte[] packet, int length) {
        int next = packet[6] & 255;
        if (next == 58) return 40;
        if (next != 0 || length < 48 || (packet[40] & 255) != 58) return -1;
        int extensionLength = ((packet[41] & 255) + 1) * 8;
        if (extensionLength > length - 40 || length - 40 - extensionLength < 4) return -1;
        return 40 + extensionLength;
    }

    public String toJson(long count) {
        return "{\"count\":" + count + ",\"sourceIp\":\"" + sourceIp
                + "\",\"destinationIp\":\"" + destinationIp + "\",\"protocol\":" + protocol
                + ",\"sourcePort\":" + sourcePort + ",\"destinationPort\":" + destinationPort + "}";
    }

    private static int unsignedShort(byte[] data, int offset) {
        return ((data[offset] & 255) << 8) | (data[offset + 1] & 255);
    }

    private static String ip(byte[] packet, int offset) {
        return (packet[offset] & 255) + "." + (packet[offset + 1] & 255) + "."
                + (packet[offset + 2] & 255) + "." + (packet[offset + 3] & 255);
    }
}
