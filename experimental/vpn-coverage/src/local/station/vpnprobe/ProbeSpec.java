package local.station.vpnprobe;

/** Pure validation shared with offline tests. No DNS resolution or Android calls. */
public final class ProbeSpec {
    public static final String PACKAGE = "local.station.vpnprobe";
    public static final String ACTION_PREPARE = PACKAGE + ".PREPARE";
    public static final String ACTION_PROBE = PACKAGE + ".PROBE";
    public static final String ACTION_STOP = PACKAGE + ".STOP";
    public static final String EXTRA_IP = "config_ip";
    public static final String EXTRA_NONCE = "nonce";
    public static final String EXTRA_CREATED = "created_elapsed";
    public static final long DURATION_MS = 10_000;
    public static final long MAX_START_AGE_MS = 25_000;
    public static final long MIN_WATCHDOG_REMAINING_MS = 12_000;
    public static final long MAX_WATCHDOG_REMAINING_MS = 15_000;
    public static final int MAX_RECORDS = 512;
    public static final String[] EXCLUDED_PACKAGES = {
        "com.android.shell", "local.station.agent", "local.station.voicebridge", PACKAGE
    };

    private ProbeSpec() { }

    public static String validateNonce(String nonce) {
        if (nonce == null || !nonce.matches("[0-9a-fA-F]{32}")) {
            throw new IllegalArgumentException("Expected a 32-hex watchdog nonce");
        }
        return nonce;
    }

    public static String validateConfigIp(String ip) {
        int[] octets = parseIpv4(ip);
        if (!(octets[0] == 10 || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168))) {
            throw new IllegalArgumentException("Config IP must be numeric RFC1918 IPv4");
        }
        if (octets[3] == 0 || octets[3] == 255) {
            throw new IllegalArgumentException("Expected a unicast config host");
        }
        if ("10.111.77.2".equals(ip)) {
            throw new IllegalArgumentException("Config IP overlaps the probe TUN address");
        }
        return ip;
    }

    public static int[] parseIpv4(String ip) {
        if (ip == null || ip.length() < 7 || ip.length() > 15) {
            throw new IllegalArgumentException("Expected numeric IPv4");
        }
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException("Expected four IPv4 octets");
        int[] octets = new int[4];
        for (int index = 0; index < 4; index++) {
            String value = parts[index];
            if (!value.matches("0|[1-9][0-9]{0,2}")) {
                throw new IllegalArgumentException("IPv4 octets must be canonical decimal");
            }
            octets[index] = Integer.parseInt(value);
            if (octets[index] > 255) throw new IllegalArgumentException("IPv4 octet out of range");
        }
        return octets;
    }

    public static void requireFresh(long created, long now) {
        if (created < 0 || now < created || now - created > MAX_START_AGE_MS) {
            throw new IllegalArgumentException("Probe start request expired");
        }
    }
}
