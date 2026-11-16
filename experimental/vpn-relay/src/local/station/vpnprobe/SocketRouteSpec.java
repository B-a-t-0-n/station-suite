package local.station.vpnprobe;

/** Fixed laboratory admission only. No Android calls, DNS or configurable destination. */
final class SocketRouteSpec {
    static final String DESTINATION = "192.168.77.1";
    static final String PHYSICAL_SOURCE = "192.168.77.100";
    final String mode;
    final int sourcePort;
    final long networkHandle;

    private SocketRouteSpec(String mode, int sourcePort, long networkHandle) {
        this.mode = mode; this.sourcePort = sourcePort; this.networkHandle = networkHandle;
    }

    static SocketRouteSpec parse(String[] args, int uid) {
        if (uid != 10005 && uid != 2000) throw new IllegalArgumentException("fixed_lab_uid_required");
        if (args == null || args.length != 3) throw new IllegalArgumentException("mode_port_handle_required");
        String mode = args[0];
        if (!("default".equals(mode) || "network".equals(mode)
                || "source".equals(mode) || "device".equals(mode)))
            throw new IllegalArgumentException("fixed_lab_mode_required");
        if (args[1] == null || !args[1].matches("4610[0-9]|4611[0-5]"))
            throw new IllegalArgumentException("fixed_lab_port_required");
        long handle = 0;
        if ("network".equals(mode)) {
            if (args[2] == null || !args[2].matches("[1-9][0-9]{0,18}"))
                throw new IllegalArgumentException("observed_physical_network_handle_required");
            handle = Long.parseLong(args[2]);
            if (handle <= 0) throw new IllegalArgumentException("positive_network_handle_required");
        } else if (!"-".equals(args[2])) throw new IllegalArgumentException("unexpected_network_handle");
        return new SocketRouteSpec(mode, Integer.parseInt(args[1]), handle);
    }
}
