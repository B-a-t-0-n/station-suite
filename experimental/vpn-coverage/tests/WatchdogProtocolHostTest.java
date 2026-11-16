package local.station.vpnprobe;

import java.util.Locale;

/** Pure authenticated wire-format tests. All key material here is a public fixture. */
public final class WatchdogProtocolHostTest {
    private static final String SECRET = "00112233445566778899aabbccddeeff"; // gitleaks:allow -- public test vector
    private static final String OTHER_SECRET = "10112233445566778899aabbccddeeff"; // gitleaks:allow -- public test vector
    private static final String CHALLENGE = "fedcba98765432100123456789abcdef";
    private static int assertions;
    private interface Action { void run(); }
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static void rejects(Action action, String message) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError("Unsafe acceptance: " + message);
    }
    private static String mutate(String value) {
        return (value.charAt(0) == '0' ? '1' : '0') + value.substring(1);
    }
    private static void runChecks() {
        String arm = WatchdogProtocol.armRequest(SECRET, CHALLENGE);
        String proof = WatchdogProtocol.readyProof(SECRET, CHALLENGE, 15000);
        check(CHALLENGE.equals(WatchdogProtocol.checkArm(SECRET, arm)), "valid ARM authenticates challenge");
        check(WatchdogProtocol.validReady(SECRET, CHALLENGE, 15000, proof), "valid READY authenticates");
        check(!arm.contains(SECRET) && !arm.contains(SECRET.toUpperCase(Locale.ROOT)),
                "wire request never contains secret text");
        check(!proof.contains(SECRET), "wire response proof never contains secret text");
        check(arm.split(" ", -1).length == 4 && arm.length() < 128,
                "bounded ARM wire format contains challenge and proof only");
        check(proof.matches("[0-9a-f]{64}"), "READY is fixed 256-bit lowercasehex MAC");
        check(arm.equals(WatchdogProtocol.armRequest(SECRET.toUpperCase(Locale.ROOT), CHALLENGE)),
                "secret hex case decodes to identical key bytes");
        check(proof.equals(WatchdogProtocol.readyProof(SECRET.toUpperCase(Locale.ROOT), CHALLENGE, 15000)),
                "secret text case does not alter response MAC");
        String upperChallenge = CHALLENGE.toUpperCase(Locale.ROOT);
        String upperArm = WatchdogProtocol.armRequest(SECRET, upperChallenge);
        check(upperChallenge.equals(WatchdogProtocol.checkArm(SECRET, upperArm)),
                "challenge is authenticated and returned verbatim");
        check(!arm.equals(upperArm), "challenge text case is covered by ARM MAC");
        check(!proof.equals(WatchdogProtocol.readyProof(SECRET, upperChallenge, 15000)),
                "challenge text case is covered by READY MAC");
        rejects(() -> WatchdogProtocol.checkArm(OTHER_SECRET, arm), "wrong ARM secret");
        rejects(() -> WatchdogProtocol.checkArm(SECRET, arm.replace(CHALLENGE, mutate(CHALLENGE))),
                "tampered ARM challenge");
        String armProof = arm.substring(arm.lastIndexOf(' ') + 1);
        rejects(() -> WatchdogProtocol.checkArm(SECRET, arm.substring(0, arm.lastIndexOf(' ') + 1)
                + mutate(armProof)), "tampered ARM MAC");
        for (final String malformed : new String[]{null, "", arm + " ", arm + " extra", arm + "\n",
                arm.replace(" ARM ", " READY "), arm.replace("/1", "/2"), arm.replace(" ARM ", " ARM  "),
                "STATION-VPN/1 ARM " + CHALLENGE + " " + armProof.toUpperCase(Locale.ROOT),
                "STATION-VPN/1 ARM " + CHALLENGE + " " + armProof.substring(1),
                "STATION-VPN/1 ARM " + CHALLENGE + " " + armProof.substring(0, 63) + "g"}) {
            rejects(() -> WatchdogProtocol.checkArm(SECRET, malformed), "malformed ARM");
        }
        check(!WatchdogProtocol.validReady(OTHER_SECRET, CHALLENGE, 15000, proof), "wrong READY secret");
        check(!WatchdogProtocol.validReady(SECRET, mutate(CHALLENGE), 15000, proof), "tampered READY challenge");
        check(!WatchdogProtocol.validReady(SECRET, CHALLENGE, 14999, proof), "tampered READY deadline");
        check(!WatchdogProtocol.validReady(SECRET, CHALLENGE, 15000, mutate(proof)), "tampered READY MAC");
        check(!WatchdogProtocol.validReady(SECRET, CHALLENGE, 15000, armProof),
                "ARM MAC cannot authenticate READY due to domain separation");
        String replayArm = "STATION-VPN/1 ARM " + CHALLENGE + " " + proof;
        rejects(() -> WatchdogProtocol.checkArm(SECRET, replayArm), "READY MAC cannot authenticate ARM");
        for (final long invalid : new long[]{Long.MIN_VALUE, -1, 0, 15001, Long.MAX_VALUE}) {
            rejects(() -> WatchdogProtocol.readyProof(SECRET, CHALLENGE, invalid), "out-of-range READY lifetime");
            check(!WatchdogProtocol.validReady(SECRET, CHALLENGE, invalid, proof), "invalid lifetime refuses READY");
        }
        check(WatchdogProtocol.validReady(SECRET, CHALLENGE, 1,
                WatchdogProtocol.readyProof(SECRET, CHALLENGE, 1)), "positive protocol lifetime lower boundary");
        for (final String invalid : new String[]{null, "", "0", SECRET + "0", SECRET.substring(1),
                SECRET.substring(0, 31) + "g", SECRET + "\n"}) {
            rejects(() -> WatchdogProtocol.armRequest(invalid, CHALLENGE), "malformed secret");
            rejects(() -> WatchdogProtocol.armRequest(SECRET, invalid), "malformed challenge");
            check(!WatchdogProtocol.validReady(invalid, CHALLENGE, 15000, proof), "invalid READY secret");
            check(!WatchdogProtocol.validReady(SECRET, invalid, 15000, proof), "invalid READY challenge");
        }
        for (String invalid : new String[]{null, "", proof + "0", proof.substring(1), proof + "\n",
                proof.toUpperCase(Locale.ROOT), " " + proof}) {
            check(!WatchdogProtocol.validReady(SECRET, CHALLENGE, 15000, invalid), "malformed READY MAC");
        }
    }
    public static void main(String[] args) {
        if (args.length == 4 && "vectors".equals(args[0])) {
            System.out.println(WatchdogProtocol.armRequest(args[1], args[2]));
            System.out.println(WatchdogProtocol.readyProof(args[1], args[2], Long.parseLong(args[3])));
            return;
        }
        runChecks();
        System.out.println(assertions + " assertions passed; authenticated watchdog protocol only; no sockets");
    }
}
