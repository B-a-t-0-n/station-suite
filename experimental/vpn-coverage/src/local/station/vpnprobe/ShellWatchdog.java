package local.station.vpnprobe;

import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Run only as a separate shell-UID app_process, never inside the APK process. */
public final class ShellWatchdog {
    private static final String PACKAGE = "local.station.vpnprobe";
    private static final long ARM_WINDOW_MS = 30000;
    private static final long STOP_AFTER_ARM_MS = 15000;

    private ShellWatchdog() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !("--serve".equals(args[0]) || "--arm".equals(args[0]))
                || !args[1].matches("[a-fA-F0-9]{32}")) {
            throw new IllegalArgumentException("fixed_mode_and_nonce_required");
        }
        int uid = android.os.Process.myUid();
        if (("--serve".equals(args[0]) && uid != 2000)
                || ("--arm".equals(args[0]) && uid != 2000 && uid < 10000))
            throw new IllegalArgumentException("watchdog_uid_required");
        if ("--arm".equals(args[0])) {
            arm(args[1]);
            return;
        }
        serve(args[1]);
    }

    private static void arm(String nonce) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), WatchdogProtocol.PORT), 750);
            socket.setSoTimeout(1000);
            byte[] random = new byte[16]; new SecureRandom().nextBytes(random);
            StringBuilder challenge = new StringBuilder(32);
            for (byte value : random) challenge.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            socket.getOutputStream().write((WatchdogProtocol.armRequest(nonce, challenge.toString()) + "\n")
                    .getBytes(StandardCharsets.US_ASCII));
            JSONObject reply = new JSONObject(line(socket.getInputStream()));
            long remaining = reply.getLong("remainingMs");
            boolean authenticated = reply.getBoolean("ready") && reply.getInt("uid") == 2000
                    && challenge.toString().equals(reply.getString("challenge"))
                    && WatchdogProtocol.validReady(nonce, challenge.toString(), remaining, reply.getString("proof"));
            if (!authenticated) throw new IllegalStateException("watchdog_authentication_failed");
            System.out.println("{\"ready\":true,\"authenticated\":true,\"uid\":2000,\"remainingMs\":" + remaining + "}");
        }
    }

    private static void serve(String nonce) throws Exception {
        long stopAt = SystemClock.elapsedRealtime() + ARM_WINDOW_MS;
        // A separate Java thread owns the deadline even while accept/read blocks.
        final long[] deadline = {stopAt};
        Thread killer = new Thread(() -> {
            for (;;) {
                long remaining;
                synchronized (deadline) { remaining = deadline[0] - SystemClock.elapsedRealtime(); }
                if (remaining <= 0) break;
                SystemClock.sleep(Math.min(remaining, 100));
            }
            forceStop();
            // app_process is independent of the package stopped above.
            System.exit(0);
        }, "StationVpnIndependentStop");
        killer.setDaemon(false);
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(false);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), WatchdogProtocol.PORT), 2);
            killer.start();
            System.out.println("WATCHDOG_LISTENING uid=2000 armWindowMs=" + ARM_WINDOW_MS);
            System.out.flush();
            boolean armed = false;
            while (!armed) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(400);
                    if (!socket.getInetAddress().isLoopbackAddress()) continue;
                    String challenge;
                    try { challenge = WatchdogProtocol.checkArm(nonce, line(socket.getInputStream())); }
                    catch (IllegalArgumentException invalid) { continue; }
                    long remaining;
                    synchronized (deadline) {
                        // Arming can shorten the fixed overall lifetime, never extend it.
                        deadline[0] = Math.min(deadline[0], SystemClock.elapsedRealtime() + STOP_AFTER_ARM_MS);
                        remaining = deadline[0] - SystemClock.elapsedRealtime();
                    }
                    OutputStream output = socket.getOutputStream();
                    output.write(("{\"ready\":true,\"challenge\":\"" + challenge
                            + "\",\"remainingMs\":" + remaining + ",\"uid\":2000,\"proof\":\""
                            + WatchdogProtocol.readyProof(nonce, challenge, remaining) + "\"}\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                    armed = true;
                    System.out.println("WATCHDOG_ARMED remainingMs=" + remaining);
                    System.out.flush();
                } catch (java.io.IOException badClient) {
                    // A stalled or malformed client cannot delay the independent deadline.
                }
            }
        } catch (Exception failure) {
            forceStop();
            throw failure;
        }
        killer.join();
    }

    private static String line(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (bytes.size() < 256) {
            int value = input.read();
            if (value == '\n') return new String(bytes.toByteArray(), StandardCharsets.US_ASCII);
            if (value < 32 || value > 126) throw new java.io.IOException("invalid_watchdog_line");
            bytes.write(value);
        }
        throw new java.io.IOException("watchdog_line_too_long");
    }

    private static void forceStop() {
        boolean stopped = false;
        try {
            Process command = new ProcessBuilder("/system/bin/am", "force-stop", "--user", "0", PACKAGE)
                    .redirectErrorStream(true).start();
            if (command.waitFor(3000, TimeUnit.MILLISECONDS)) stopped = command.exitValue() == 0;
            else command.destroyForcibly();
        } catch (Exception ignored) { }
        System.out.println("WATCHDOG_FORCE_STOP commandOk=" + stopped);
        System.out.flush();
    }
}
