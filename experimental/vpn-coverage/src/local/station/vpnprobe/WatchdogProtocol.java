package local.station.vpnprobe;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Mutual one-use authentication. The shell-created secret is never sent on the wire. */
public final class WatchdogProtocol {
    public static final int PORT = 17692;
    private static final String ARM = "STATION-VPN/1 ARM ";

    private WatchdogProtocol() { }

    public static String armRequest(String secret, String challenge) {
        hexBytes(challenge);
        return ARM + challenge + " " + proof(secret, "STATION-VPN/1 ARM\n" + challenge);
    }

    /** Returns the authenticated challenge, or throws; never returns the secret. */
    public static String checkArm(String secret, String request) {
        if (request == null || !request.startsWith(ARM)) throw new IllegalArgumentException("arm_request");
        String[] fields = request.substring(ARM.length()).split(" ", -1);
        if (fields.length != 2) throw new IllegalArgumentException("arm_fields");
        hexBytes(fields[0]);
        if (!same(proof(secret, "STATION-VPN/1 ARM\n" + fields[0]), fields[1]))
            throw new IllegalArgumentException("arm_authentication");
        return fields[0];
    }

    public static String readyProof(String secret, String challenge, long remaining) {
        hexBytes(challenge);
        if (remaining <= 0 || remaining > 15000) throw new IllegalArgumentException("ready_lifetime");
        return proof(secret, "STATION-VPN/1 READY\n" + challenge + "\n" + remaining + "\n2000");
    }

    public static boolean validReady(String secret, String challenge, long remaining, String candidate) {
        try { return same(readyProof(secret, challenge, remaining), candidate); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static String proof(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hexBytes(secret), "HmacSHA256"));
            byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.US_ASCII));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return hex.toString();
        } catch (java.security.GeneralSecurityException unavailable) {
            throw new IllegalStateException("watchdog_crypto_unavailable", unavailable);
        }
    }

    private static byte[] hexBytes(String text) {
        if (text == null || !text.matches("[a-fA-F0-9]{32}")) throw new IllegalArgumentException("hex_128");
        byte[] bytes = new byte[16];
        for (int index = 0; index < bytes.length; index++)
            bytes[index] = (byte) Integer.parseInt(text.substring(index * 2, index * 2 + 2), 16);
        return bytes;
    }

    private static boolean same(String expected, String candidate) {
        if (candidate == null || !candidate.matches("[0-9a-f]{64}")) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                candidate.getBytes(StandardCharsets.US_ASCII));
    }
}
