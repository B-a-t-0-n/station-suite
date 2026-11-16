package local.station.vpnprobe;

import java.util.HashSet;
import java.util.Set;

/** Header-only directional admission before/after the native TCP stack. */
public final class RelayPacketPolicy {
    public static final String TUN_IP = "10.111.77.2";
    public static final int DROP = 0, FORWARD = 1, IPV6_CONTROL = 2;
    private final String destination, physicalSource;
    private final Set<String> flows = new HashSet<>();

    public RelayPacketPolicy(String destination, String physicalSource) {
        this.destination = ProbeSpec.validateConfigIp(destination);
        this.physicalSource = ProbeSpec.validateConfigIp(physicalSource);
        if (destination.equals(physicalSource)) throw new IllegalArgumentException("distinct_config_and_device_required");
    }

    public synchronized int outbound(byte[] packet, int length) {
        if (PacketMetadata.isTunIpv6Control(packet, length)) return IPV6_CONTROL;
        PacketMetadata metadata = exactIpv4(packet, length);
        if (metadata == null || !destination.equals(metadata.destinationIp)
                || !(TUN_IP.equals(metadata.sourceIp) || physicalSource.equals(metadata.sourceIp))) throw rejected();
        if (metadata.protocol == 1 || metadata.protocol == 17) return DROP;
        if (!tcp(packet, length, metadata) || !(metadata.destinationPort == 80 || metadata.destinationPort == 443)) throw rejected();
        String key = key(metadata.sourceIp, metadata.sourcePort, metadata.destinationPort);
        if (!flows.contains(key)) {
            int offset = (packet[0] & 15) * 4;
            // Existing kernel keepalives have no lwIP state. Drop until a fresh SYN.
            if ((packet[offset + 13] & 0x3f) != 0x02) return DROP;
            if (flows.size() >= 32) throw rejected();
            flows.add(key);
        }
        return FORWARD;
    }

    public synchronized void inbound(byte[] packet, int length) {
        PacketMetadata metadata = exactIpv4(packet, length);
        if (metadata == null || !destination.equals(metadata.sourceIp)
                || !(TUN_IP.equals(metadata.destinationIp) || physicalSource.equals(metadata.destinationIp))
                || !tcp(packet, length, metadata) || !(metadata.sourcePort == 80 || metadata.sourcePort == 443)
                || !flows.contains(key(metadata.destinationIp, metadata.destinationPort, metadata.sourcePort))) throw rejected();
    }

    private static PacketMetadata exactIpv4(byte[] packet, int length) {
        PacketMetadata metadata = PacketMetadata.parse(packet, length);
        if (metadata == null || length > 1500 || u16(packet, 2) != length
                || (u16(packet, 6) & 0xbfff) != 0) return null;
        return metadata;
    }

    private static boolean tcp(byte[] packet, int length, PacketMetadata metadata) {
        if (metadata.protocol != 6 || metadata.sourcePort < 1 || metadata.destinationPort < 1) return false;
        int offset = (packet[0] & 15) * 4;
        if (offset + 20 > length) return false;
        int header = ((packet[offset + 12] & 255) >>> 4) * 4;
        return header >= 20 && offset + header <= length;
    }

    private static int u16(byte[] data, int offset) { return (data[offset] & 255) * 256 + (data[offset + 1] & 255); }
    private static String key(String ip, int source, int destination) { return ip + ":" + source + ":" + destination; }
    private static IllegalArgumentException rejected() { return new IllegalArgumentException("unexpected_relay_packet"); }
}
