package local.station.socketprobe;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

/** One payload-free TCP connection between fixed IPv4 loopback endpoints. */
public final class StationSocketOwnerClient {
    private static final byte[] LOOPBACK = {127, 0, 0, 1};
    private static final int SOURCE_PORT = 24110;
    private static final int SERVER_PORT = 24111;
    private static final long NANOS_PER_MS = 1000000L;

    public static void main(String[] args) {
        final long started = System.nanoTime();
        Thread deadline = new Thread(new Runnable() {
            public void run() {
                long remaining;
                while ((remaining = 7000L - elapsedMs(started)) > 0) {
                    try { Thread.sleep(remaining); }
                    catch (InterruptedException ignored) { }
                }
                Runtime.getRuntime().halt(124);
            }
        }, "station-socket-owner-hard-deadline");
        deadline.setDaemon(true);
        deadline.start();

        int uid = -1;
        int expectedUid = -1;
        int attempts = 0;
        boolean sourceVerified = false;
        boolean connected = false;
        boolean eof = false;
        boolean holdTimedOut = false;
        String errorType = null;
        Socket socket = null;
        try {
            uid = android.os.Process.myUid();
            if (args.length != 1) throw new IllegalArgumentException();
            expectedUid = Integer.parseInt(args[0]);
            if ((expectedUid != 10005 && expectedUid != 2000) || uid != expectedUid) {
                throw new SecurityException();
            }

            InetAddress loopback = InetAddress.getByAddress(LOOPBACK);
            InetSocketAddress source = new InetSocketAddress(loopback, SOURCE_PORT);
            InetSocketAddress target = new InetSocketAddress(loopback, SERVER_PORT);
            final long retryStarted = System.nanoTime();
            IOException lastConnectError = null;
            while (elapsedMs(retryStarted) < 4000L && elapsedMs(started) < 6500L) {
                long remaining = Math.min(4000L - elapsedMs(retryStarted),
                                          6500L - elapsedMs(started));
                if (remaining <= 0) break;
                Socket candidate = new Socket();
                boolean accepted = false;
                try {
                    ++attempts;
                    candidate.setReuseAddress(true);
                    candidate.bind(source);
                    candidate.connect(target, (int) Math.min(150L, Math.max(1L, remaining)));
                    connected = true;
                    sourceVerified = candidate.getLocalPort() == SOURCE_PORT
                        && loopback.equals(candidate.getLocalAddress());
                    if (!sourceVerified || candidate.getPort() != SERVER_PORT
                        || !loopback.equals(candidate.getInetAddress())) {
                        throw new SecurityException();
                    }
                    accepted = true;
                    socket = candidate;
                    break; // Never attempt a second successful connection.
                } catch (IOException error) {
                    lastConnectError = error;
                } finally {
                    if (!accepted) {
                        try { candidate.close(); }
                        catch (IOException ignored) { }
                    }
                }
                long pause = Math.min(30L, Math.max(0L, 4000L - elapsedMs(retryStarted)));
                if (pause > 0) Thread.sleep(pause);
            }
            if (socket == null) {
                if (lastConnectError != null) throw lastConnectError;
                throw new SocketTimeoutException();
            }

            // Send nothing. Keep the socket owned by this UID while the APK
            // queries ownership, until peer EOF or at most three seconds.
            long holdMs = Math.min(3000L, 6600L - elapsedMs(started));
            if (holdMs <= 0) throw new SocketTimeoutException();
            socket.setSoTimeout((int) holdMs);
            try {
                if (socket.getInputStream().read() == -1) eof = true;
                else throw new IllegalStateException(); // No payload is expected.
            } catch (SocketTimeoutException expected) {
                holdTimedOut = true;
            }
        } catch (Throwable error) {
            errorType = error.getClass().getSimpleName();
        } finally {
            if (socket != null) {
                try { socket.close(); }
                catch (IOException ignored) { }
            }
        }

        // Error class only: never exception text, stack traces or remote input.
        String json = "{\"uid\":" + uid
            + ",\"expectedUid\":" + expectedUid
            + ",\"sourceAddress\":\"127.0.0.1\",\"sourcePort\":" + SOURCE_PORT
            + ",\"serverAddress\":\"127.0.0.1\",\"serverPort\":" + SERVER_PORT
            + ",\"sourceVerified\":" + sourceVerified
            + ",\"connected\":" + connected
            + ",\"attempts\":" + attempts
            + ",\"successfulConnections\":" + (connected ? 1 : 0)
            + ",\"eof\":" + eof
            + ",\"holdTimedOut\":" + holdTimedOut
            + ",\"elapsedMs\":" + elapsedMs(started)
            + ",\"errorType\":" + (errorType == null ? "null" : "\"" + errorType + "\"")
            + "}";
        System.out.println(json);
        System.out.flush();
        Runtime.getRuntime().halt(errorType == null ? 0 : 1);
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / NANOS_PER_MS;
    }
}
