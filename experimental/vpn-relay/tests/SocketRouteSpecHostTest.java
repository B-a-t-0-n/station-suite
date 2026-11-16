package local.station.vpnprobe;

public final class SocketRouteSpecHostTest {
    private static int assertions;
    private static void check(boolean condition) {
        assertions++; if (!condition) throw new AssertionError("case=" + assertions);
    }
    private static void reject(String[] args, int uid) {
        try { SocketRouteSpec.parse(args, uid); throw new AssertionError("admitted invalid test"); }
        catch (IllegalArgumentException expected) { assertions++; }
    }
    public static void main(String[] ignored) {
        for (int uid : new int[]{2000, 10005}) for (String mode : new String[]{"default", "source", "device", "network"}) {
            for (int port = 46100; port <= 46115; port++) {
                SocketRouteSpec spec = SocketRouteSpec.parse(new String[]{mode, Integer.toString(port),
                        mode.equals("network") ? "429837296653" : "-"}, uid);
                check(spec.mode.equals(mode) && spec.sourcePort == port);
                check(spec.networkHandle == (mode.equals("network") ? 429837296653L : 0));
            }
        }
        for (int uid : new int[]{0, 1000, 9999, 10000, 10006, 102000, -1})
            reject(new String[]{"default", "46100", "-"}, uid);
        for (String mode : new String[]{null, "", "protect", "NETWORK", "default ", "eth1", "192.168.77.1"})
            reject(new String[]{mode, "46100", "-"}, 10005);
        for (String port : new String[]{null, "", "80", "46116", "46099", "046100", "46100 ", "+46100", "46100\n", "46100;id"})
            reject(new String[]{"default", port, "-"}, 10005);
        for (String handle : new String[]{null, "", "-", "0", "-1", "+1", "01", "1 ", "1\n", "9223372036854775808", "18446744073709551615"})
            reject(new String[]{"network", "46100", handle}, 10005);
        for (String mode : new String[]{"default", "source", "device"})
            reject(new String[]{mode, "46100", "429837296653"}, 10005);
        reject(null, 10005); reject(new String[]{}, 10005);
        reject(new String[]{"default", "46100"}, 10005);
        reject(new String[]{"default", "46100", "-", "192.168.77.2"}, 10005);
        check(SocketRouteSpec.DESTINATION.equals("192.168.77.1"));
        check(SocketRouteSpec.PHYSICAL_SOURCE.equals("192.168.77.100"));
        System.out.println(assertions + " assertions passed; fixed lab socket client admission; no network/device");
    }
}
