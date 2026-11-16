package local.station.hevsmoke;

import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import hev.htproxy.TProxyService;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONObject;

/** Explicit app_process smoke: no VpnService, real TUN, IP socket, routes or settings. */
public final class NativeRuntimeSmoke {
    private static final int PROCESS_DEADLINE_MS = 6000;
    private static final String CONFIG = "tunnel:\n  mtu: 1500\n  multi-queue: false\n  icmp: 'reply'\n"
            + "  offload:\n    tso: false\n    uso: false\n    jumbo: false\n"
            + "socks5:\n  address: 127.0.0.1\n  port: 17694\n  udp: 'tcp'\n  pipeline: false\n  tcp-fastopen: false\n"
            + "misc:\n  task-stack-size: 86016\n  tcp-buffer-size: 4096\n  udp-recv-buffer-size: 4096\n"
            + "  udp-copy-buffer-nums: 1\n  max-session-count: 4\n  connect-timeout: 750\n"
            + "  tcp-read-write-timeout: 1500\n  udp-read-write-timeout: 750\n  log-file: null\n  log-level: error\n";

    private NativeRuntimeSmoke() { }

    public static void main(String[] args) {
        JSONObject report = new JSONObject();
        int status = 1;
        long started = SystemClock.elapsedRealtime();
        Thread expiry = new Thread(() -> {
            SystemClock.sleep(PROCESS_DEADLINE_MS);
            System.out.println("NATIVE_SMOKE_HARD_DEADLINE"); System.out.flush();
            // Only this disposable app_process, not an application package or another PID.
            Runtime.getRuntime().halt(124);
        }, "hev-smoke-deadline");
        expiry.setDaemon(true); expiry.start();
        try {
            if (Process.myUid() < 10000 || args.length < 3
                    || !(args[0].equals("--load-only") && args.length == 3
                        || args[0].equals("--packet-pair") && args.length == 4))
                throw new IllegalArgumentException("app_uid_fixed_mode_required");
            report.put("uid", Process.myUid()).put("mode", args[0]);
            TProxyService.loadVerified(args[1], args[2]);
            report.put("libraryLoaded", true);
            if (TProxyService.TProxyIsRunning()) throw new IllegalStateException("unexpected_running_worker");
            long[] emptyStats = TProxyService.TProxyGetStats();
            if (emptyStats == null || emptyStats.length != 4 || !Arrays.equals(emptyStats, new long[4]))
                throw new IllegalStateException("unexpected_initial_stats");
            if (!TProxyService.TProxyStopService()) throw new IllegalStateException("initial_stop_failed");
            report.put("initialStopped", true);
            if (args[0].equals("--packet-pair")) packetPair(args[3], report, started + 4200);
            report.put("success", true);
            status = 0;
        } catch (Throwable failure) {
            try { report.put("success", false).put("failureClass", failure.getClass().getName()); }
            catch (Exception ignored) { }
        } finally {
            try { report.put("elapsedMs", SystemClock.elapsedRealtime() - started); } catch (Exception ignored) { }
            System.out.println("NATIVE_SMOKE_RESULT=" + report.toString()); System.out.flush();
            System.exit(status);
        }
    }

    private static void packetPair(String directory, JSONObject report, long deadline) throws Exception {
        File parent = new File(directory);
        if (!parent.isAbsolute() || !parent.isDirectory() || !parent.getAbsoluteFile().equals(parent.getCanonicalFile()))
            throw new IllegalArgumentException("canonical_existing_work_directory_required");
        File config = new File(parent, "station-hev-runtime-smoke.yml");
        if (!config.createNewFile()) throw new IllegalStateException("smoke_config_already_exists");
        FileDescriptor nativeEnd = new FileDescriptor(), peer = new FileDescriptor();
        ParcelFileDescriptor owned = null;
        boolean startCalled = false;
        try {
            Os.chmod(config.getAbsolutePath(), 0600);
            try (FileOutputStream output = new FileOutputStream(config)) { output.write(CONFIG.getBytes(StandardCharsets.US_ASCII)); }
            // Bionic/Linux SOCK_NONBLOCK==O_NONBLOCK, SOCK_CLOEXEC==O_CLOEXEC.
            // The O_* constants are public in API28; the corresponding SOCK_* names are hidden.
            Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_DGRAM | OsConstants.O_NONBLOCK | OsConstants.O_CLOEXEC,
                    0, nativeEnd, peer);
            owned = ParcelFileDescriptor.dup(nativeEnd);
            Os.close(nativeEnd);
            nativeEnd = null;
            startCalled = true;
            if (!TProxyService.TProxyStartService(config.getAbsolutePath(), owned.getFd()))
                throw new IllegalStateException("worker_creation_failed");
            // start=true is not readiness. A validated reply proves lwIP consumed a complete packet.
            send(peer, SyntheticPackets.echoRequest(), deadline);
            if (!receive(peer, true, deadline)) throw new IllegalStateException("synthetic_echo_not_observed");
            report.put("syntheticEchoReply", true);
            send(peer, SyntheticPackets.tcpSyn(), deadline);
            if (!receive(peer, false, deadline)) throw new IllegalStateException("synthetic_synack_not_observed");
            report.put("syntheticSynAck", true);
            boolean running = TProxyService.TProxyIsRunning();
            report.put("nativeStillRunning", running);
            if (!running) throw new IllegalStateException("worker_exited_after_packet");
            long[] stats = TProxyService.TProxyGetStats();
            report.put("statsLength", stats == null ? -1 : stats.length);
            if (stats == null || stats.length != 4) throw new IllegalStateException("unexpected_running_stats_shape");
        } finally {
            // Do not close/reuse caller-owned native fd while native can still be polling/reading it.
            // A blocking JNI join is bounded by the separate process hard deadline above.
            boolean stopped = !startCalled || TProxyService.TProxyStopService();
            boolean nativeStopped = !startCalled || !TProxyService.TProxyIsRunning();
            report.put("stopReturned", stopped).put("nativeStopped", nativeStopped);
            if (owned != null) owned.close();
            if (nativeEnd != null && nativeEnd.valid()) Os.close(nativeEnd);
            if (peer.valid()) Os.close(peer);
            boolean deleted = config.delete();
            if (!deleted) report.put("configDeleteFailed", true);
            if (!stopped || !nativeStopped) throw new IllegalStateException("native_stop_failed");
            if (!deleted) throw new IllegalStateException("smoke_config_delete_failed");
        }
    }

    private static void waitFor(FileDescriptor fd, short events, long deadline) throws Exception {
        StructPollfd poll = new StructPollfd(); poll.fd = fd; poll.events = events;
        while (SystemClock.elapsedRealtime() < deadline) {
            int left = (int) Math.min(100, deadline - SystemClock.elapsedRealtime());
            Os.poll(new StructPollfd[]{poll}, Math.max(1, left));
            if ((poll.revents & (OsConstants.POLLERR | OsConstants.POLLHUP | OsConstants.POLLNVAL)) != 0)
                throw new IllegalStateException("socket_pair_poll_error");
            if ((poll.revents & events) != 0) return;
        }
        throw new IllegalStateException("smoke_packet_deadline");
    }

    private static void send(FileDescriptor fd, byte[] packet, long deadline) throws Exception {
        for (;;) {
            waitFor(fd, (short) OsConstants.POLLOUT, deadline);
            try {
                if (Os.write(fd, packet, 0, packet.length) != packet.length) throw new IllegalStateException("short_datagram_write");
                return;
            } catch (ErrnoException retry) { if (retry.errno != OsConstants.EAGAIN) throw retry; }
        }
    }

    private static boolean receive(FileDescriptor fd, boolean echo, long deadline) throws Exception {
        byte[] buffer = new byte[1501];
        for (int attempts = 0; attempts < 8;) {
            waitFor(fd, (short) OsConstants.POLLIN, deadline);
            int length;
            try { length = Os.read(fd, buffer, 0, buffer.length); }
            catch (ErrnoException retry) { if (retry.errno == OsConstants.EAGAIN) continue; throw retry; }
            attempts++;
            if (length < 20 || length > 1500) throw new IllegalStateException("unexpected_pair_packet_length");
            byte[] packet = Arrays.copyOf(buffer, length);
            if (echo ? SyntheticPackets.isEchoReply(packet) : SyntheticPackets.isSynAck(packet)) return true;
        }
        return false;
    }
}
