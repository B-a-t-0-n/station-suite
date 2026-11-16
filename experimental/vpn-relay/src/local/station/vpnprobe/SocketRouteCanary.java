package local.station.vpnprobe;

import android.net.Network;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** One fixed lab HEAD request in a disposable app_process, never a production service. */
public final class SocketRouteCanary {
    private SocketRouteCanary() { }

    public static void main(String[] args) throws Exception {
        final int uid = android.os.Process.myUid();
        final SocketRouteSpec spec = SocketRouteSpec.parse(args, uid);
        final long started = SystemClock.elapsedRealtime();
        final long deadline = started + 3000;
        Thread hardDeadline = new Thread(() -> {
            SystemClock.sleep(4000);
            System.out.println("SOCKET_ROUTE_RESULT={\"hardTimeout\":true}"); System.out.flush();
            // Only this disposable process. A package force-stop is not a watchdog for this child.
            Runtime.getRuntime().halt(124);
        }, "station-route-canary-deadline");
        hardDeadline.setDaemon(true); hardDeadline.start();
        FileDescriptor fd = null;
        String stage = "socket", exception = "", localIp = "";
        int errno = 0;
        boolean bindingAttempted = false, bindingSucceeded = false;
        boolean connectAttempted = false, connected = false, http204 = false;
        try {
            fd = Os.socket(OsConstants.AF_INET,
                    OsConstants.SOCK_STREAM | OsConstants.O_NONBLOCK | OsConstants.O_CLOEXEC, 0);
            if ("network".equals(spec.mode)) {
                stage = "bind_network"; bindingAttempted = true;
                Network physical = Network.fromNetworkHandle(spec.networkHandle);
                if (physical == null || physical.getNetworkHandle() != spec.networkHandle)
                    throw new IllegalArgumentException("invalid_observed_network_handle");
                physical.bindSocket(fd); bindingSucceeded = true;
            } else if ("device".equals(spec.mode)) {
                stage = "bind_device"; bindingAttempted = true;
                // Exact Android9 @hide signature; no accessibility override or alternate lookup.
                // A permission/hidden-API denial is a result; no attempt is made to evade it.
                Os.class.getMethod("setsockoptIfreq", FileDescriptor.class,
                        int.class, int.class, String.class)
                        .invoke(null, fd, OsConstants.SOL_SOCKET, OsConstants.SO_BINDTODEVICE, "eth0");
                bindingSucceeded = true;
            }
            stage = "bind_port";
            InetAddress source = InetAddress.getByAddress(new byte[4]);
            if ("source".equals(spec.mode)) {
                stage = "bind_source"; bindingAttempted = true;
                source = InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 77, 100});
            }
            Os.bind(fd, source, spec.sourcePort);
            if ("source".equals(spec.mode)) bindingSucceeded = true;
            stage = "connect"; connectAttempted = true;
            InetAddress destination = InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 77, 1});
            while (true) {
                requireTime(deadline);
                try { Os.connect(fd, destination, 80); break; }
                catch (ErrnoException pending) {
                    if (pending.errno == OsConstants.EISCONN) break;
                    if (pending.errno != OsConstants.EINPROGRESS && pending.errno != OsConstants.EALREADY
                            && pending.errno != OsConstants.EINTR) throw pending;
                    // Retry connect only on this same socket/peer; API28 hides getsockoptInt.
                    waitReady(fd, OsConstants.POLLOUT, deadline);
                }
            }
            connected = true;
            localIp = ((InetSocketAddress) Os.getsockname(fd)).getAddress().getHostAddress();
            stage = "write_request";
            byte[] request = ("HEAD /generate_204 HTTP/1.1\r\nHost: scbh.yandex.net\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            for (int offset = 0; offset < request.length;) {
                requireTime(deadline);
                try {
                    int written = Os.write(fd, request, offset, request.length - offset);
                    if (written <= 0) throw new IOException("canary_short_write");
                    offset += written;
                } catch (ErrnoException pending) {
                    if (pending.errno != OsConstants.EAGAIN && pending.errno != OsConstants.EINTR) throw pending;
                    waitReady(fd, OsConstants.POLLOUT, deadline);
                }
            }
            stage = "read_status";
            ByteArrayOutputStream status = new ByteArrayOutputStream();
            byte[] one = new byte[1];
            boolean terminated = false;
            while (status.size() < 96) {
                requireTime(deadline);
                int count;
                try { count = Os.read(fd, one, 0, 1); }
                catch (ErrnoException pending) {
                    if (pending.errno != OsConstants.EAGAIN && pending.errno != OsConstants.EINTR) throw pending;
                    waitReady(fd, OsConstants.POLLIN, deadline); continue;
                }
                if (count == 0) throw new IOException("canary_status_eof");
                int value = one[0] & 255;
                if (value == '\n') { terminated = true; break; }
                if (value != '\r' && (value < 32 || value > 126)) throw new IOException("canary_non_ascii_status");
                if (value != '\r') status.write(value);
            }
            if (!terminated) throw new IOException("canary_status_limit");
            String line = new String(status.toByteArray(), StandardCharsets.US_ASCII);
            http204 = line.startsWith("HTTP/1.1 204 ") || line.startsWith("HTTP/1.0 204 ");
            stage = "complete";
        } catch (Exception failure) {
            Throwable cause = failure instanceof InvocationTargetException && failure.getCause() != null
                    ? failure.getCause() : failure;
            exception = cause.getClass().getName();
            if (cause instanceof ErrnoException) errno = ((ErrnoException) cause).errno;
        } finally {
            if (fd != null && fd.valid()) try { Os.close(fd); } catch (ErrnoException ignored) { }
        }
        // All string values originate from fixed enums, Java class names or kernel IPv4 addresses.
        System.out.println("SOCKET_ROUTE_RESULT={\"uid\":" + uid + ",\"mode\":\"" + spec.mode
                + "\",\"sourcePort\":" + spec.sourcePort + ",\"bindingAttempted\":" + bindingAttempted
                + ",\"bindingSucceeded\":" + bindingSucceeded + ",\"connectAttempted\":" + connectAttempted
                + ",\"connected\":" + connected + ",\"http204\":" + http204
                + ",\"stage\":\"" + stage + "\",\"exception\":\"" + exception
                + "\",\"errno\":" + errno + ",\"localIp\":\"" + localIp + "\",\"elapsedMs\":"
                + (SystemClock.elapsedRealtime() - started) + "}");
        System.out.flush();
    }

    private static void requireTime(long deadline) throws IOException {
        if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("canary_io_deadline");
    }

    private static void waitReady(FileDescriptor fd, int event, long deadline) throws Exception {
        StructPollfd poll = new StructPollfd(); poll.fd = fd; poll.events = (short) event;
        while (true) {
            requireTime(deadline);
            try {
                if (Os.poll(new StructPollfd[]{poll}, 50) == 0) continue;
                if ((poll.revents & OsConstants.POLLNVAL) != 0) throw new IOException("canary_fd_invalid");
                // POLLERR/HUP are resolved by the next connect/read/write operation.
                return;
            } catch (ErrnoException pending) { if (pending.errno != OsConstants.EINTR) throw pending; }
        }
    }
}
