package local.station.hevsmoke;

import java.util.Arrays;

/** Fixed synthetic packets, only for an AF_UNIX socketpair; never sent to a network. */
final class SyntheticPackets {
    static final byte[] SOURCE = {10, 111, 77, 2}, DESTINATION = {(byte) 198, 18, 0, 1};
    private SyntheticPackets() { }

    static int u16(byte[] bytes, int offset) { return (bytes[offset] & 255) * 256 + (bytes[offset + 1] & 255); }
    static long u32(byte[] bytes, int offset) {
        return ((long) u16(bytes, offset) << 16) | u16(bytes, offset + 2);
    }
    private static void put16(byte[] bytes, int offset, int value) { bytes[offset] = (byte) (value >>> 8); bytes[offset + 1] = (byte) value; }
    private static void put32(byte[] bytes, int offset, long value) { put16(bytes, offset, (int) (value >>> 16)); put16(bytes, offset + 2, (int) value); }
    static int checksum(byte[] bytes, int offset, int count, int initial) {
        long sum = initial;
        for (int i = 0; i < count; i += 2) sum += (bytes[offset + i] & 255) * 256 + (i + 1 < count ? bytes[offset + i + 1] & 255 : 0);
        while ((sum >>> 16) != 0) sum = (sum & 65535) + (sum >>> 16);
        return (int) (~sum & 65535);
    }
    private static byte[] ipv4(int protocol, int length) {
        byte[] bytes = new byte[length];
        bytes[0] = 0x45; put16(bytes, 2, length); put16(bytes, 4, 0x7374); bytes[8] = 64; bytes[9] = (byte) protocol;
        System.arraycopy(SOURCE, 0, bytes, 12, 4); System.arraycopy(DESTINATION, 0, bytes, 16, 4);
        put16(bytes, 10, checksum(bytes, 0, 20, 0));
        return bytes;
    }
    static byte[] echoRequest() {
        byte[] bytes = ipv4(1, 32);
        bytes[20] = 8; put16(bytes, 24, 0x7374); put16(bytes, 26, 1);
        bytes[28] = 'H'; bytes[29] = 'E'; bytes[30] = 'V'; bytes[31] = '!';
        put16(bytes, 22, checksum(bytes, 20, 12, 0));
        return bytes;
    }
    static byte[] tcpSyn() {
        byte[] bytes = ipv4(6, 40);
        put16(bytes, 20, 46090); put16(bytes, 22, 80); put32(bytes, 24, 0x11223344L);
        bytes[32] = 0x50; bytes[33] = 2; put16(bytes, 34, 4096);
        put16(bytes, 36, checksum(bytes, 20, 20, tcpPseudoSum(bytes, 20)));
        return bytes;
    }
    private static int tcpPseudoSum(byte[] bytes, int tcpLength) {
        int sum = 6 + tcpLength;
        for (int i = 12; i < 20; i += 2) sum += u16(bytes, i);
        return sum;
    }
    private static int reverseHeader(byte[] bytes, int protocol) {
        if (bytes == null || bytes.length < 20 || (bytes[0] & 255) != 0x45 || bytes[9] != protocol
                || u16(bytes, 2) != bytes.length || checksum(bytes, 0, 20, 0) != 0
                || (u16(bytes, 6) & 0x3fff) != 0 || bytes[8] == 0
                || !Arrays.equals(Arrays.copyOfRange(bytes, 12, 16), DESTINATION)
                || !Arrays.equals(Arrays.copyOfRange(bytes, 16, 20), SOURCE)) return -1;
        return 20;
    }
    static boolean isEchoReply(byte[] bytes) {
        int header = reverseHeader(bytes, 1);
        return header == 20 && bytes.length == 32 && bytes[20] == 0 && bytes[21] == 0
                && u16(bytes, 24) == 0x7374 && u16(bytes, 26) == 1
                && checksum(bytes, 20, 12, 0) == 0 && bytes[28] == 'H' && bytes[29] == 'E'
                && bytes[30] == 'V' && bytes[31] == '!';
    }
    static boolean isSynAck(byte[] bytes) {
        int header = reverseHeader(bytes, 6);
        if (header != 20 || bytes.length < 40 || u16(bytes, 20) != 80 || u16(bytes, 22) != 46090
                || (bytes[33] & 255) != 0x12 || u32(bytes, 28) != 0x11223345L) return false;
        int tcpHeader = (bytes[32] & 255) >>> 4;
        return tcpHeader >= 5 && tcpHeader * 4 <= bytes.length - 20
                && checksum(bytes, 20, bytes.length - 20, tcpPseudoSum(bytes, bytes.length - 20)) == 0;
    }
}
