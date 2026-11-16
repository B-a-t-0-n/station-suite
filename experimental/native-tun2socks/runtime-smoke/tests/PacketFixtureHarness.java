package local.station.hevsmoke;

public final class PacketFixtureHarness {
    private static byte[] unhex(String text) {
        if (text.length() % 2 != 0 || !text.matches("[0-9a-f]*")) throw new IllegalArgumentException();
        byte[] value = new byte[text.length() / 2];
        for (int i = 0; i < value.length; i++) value[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        return value;
    }
    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte value : bytes) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println(hex(SyntheticPackets.echoRequest()));
            System.out.println(hex(SyntheticPackets.tcpSyn()));
        } else if (args.length == 2) {
            System.out.println(SyntheticPackets.isEchoReply(unhex(args[0])));
            System.out.println(SyntheticPackets.isSynAck(unhex(args[1])));
        } else throw new IllegalArgumentException();
    }
}
