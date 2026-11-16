package local.station.vpnprobe;

import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;
import hev.htproxy.TProxyService;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import local.station.relay.LocalServiceRelay;

/** The Java pump is the sole TUN consumer. lwIP sees a separate datagram pair. */
final class RelayTransport {
    private static final String TAG = "StationVpnProbe";
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger records = new AtomicInteger(), bytes = new AtomicInteger();
    private final AtomicInteger forwarded = new AtomicInteger(), injected = new AtomicInteger();
    private final AtomicInteger dropped = new AtomicInteger(), ipv6Controls = new AtomicInteger();
    private final AtomicInteger nativeIpv6Controls = new AtomicInteger();
    private final FileDescriptor pump = new FileDescriptor(), engine = new FileDescriptor();
    private ParcelFileDescriptor nativeDescriptor;
    private LocalServiceRelay relay;
    private Thread relayThread, reverseThread, nativeStop;
    private boolean nativeStarted;
    private final File config;
    private final File nativeLibrary;
    private final Object metadataLock = new Object();
    private BufferedWriter metadataWriter;
    private final String ip, physicalIp;

    RelayTransport(File directory, File nativeLibrary, String ip, String physicalIp) {
        this.config = new File(directory, "relay-canary.yml");
        this.nativeLibrary = nativeLibrary;
        this.ip = ProbeSpec.validateConfigIp(ip); this.physicalIp = ProbeSpec.validateConfigIp(physicalIp);
    }

    synchronized void prepare() throws Exception {
        if (closed.get()) throw new IOException("transport_already_stopped");
        // Loaded only in the disposable probe process, never the working apps.
        TProxyService.loadVerified(nativeLibrary.getAbsolutePath(), RelayBuild.NATIVE_SHA256);
        File metadataFile = new File(config.getParentFile(), "relay-metadata-last.jsonl");
        metadataWriter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(metadataFile), StandardCharsets.UTF_8));
        Os.chmod(metadataFile.getAbsolutePath(), 0600);
        relay = new LocalServiceRelay(ip, 20000);
        relayThread = new Thread(() -> {
            try { relay.run(); }
            catch (IOException failure) { Log.w(TAG, "RELAY_LISTENER_STOPPED error=" + failure.getClass().getSimpleName()); }
        }, "station-local-relay");
        relayThread.setDaemon(true); relayThread.start();
        long limit = SystemClock.elapsedRealtime() + 1000;
        while (!relay.isReady() && relayThread.isAlive() && SystemClock.elapsedRealtime() < limit) SystemClock.sleep(10);
        if (!relay.isReady()) throw new IOException("local_relay_not_ready");
        String yaml = "tunnel:\n  name: station-relay-canary\n  mtu: 1500\n  ipv4: 10.111.77.2\n"
                + "  icmp: 'off'\n  multi-queue: false\n  offload:\n    tso: false\n    uso: false\n    jumbo: false\n"
                + "socks5:\n  address: 127.0.0.1\n  port: 17694\n  pipeline: false\n  udp: 'udp'\n"
                + "misc:\n  max-session-count: 8\n  connect-timeout: 1500\n  tcp-read-write-timeout: 3000\n"
                + "  udp-read-write-timeout: 1000\n  log-file: null\n  log-level: error\n  limit-nofile: 1024\n";
        try (FileOutputStream output = new FileOutputStream(config)) { output.write(yaml.getBytes(StandardCharsets.US_ASCII)); }
        Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_DGRAM | OsConstants.O_NONBLOCK | OsConstants.O_CLOEXEC, 0, pump, engine);
        nativeDescriptor = ParcelFileDescriptor.dup(engine);
        nativeStarted = TProxyService.TProxyStartService(config.getAbsolutePath(), nativeDescriptor.getFd());
        if (!nativeStarted) throw new IOException("native_worker_not_started");
        SystemClock.sleep(150);
        if (!TProxyService.TProxyIsRunning()) throw new IOException("native_initialization_failed");
        // This state is provisional. Only a returned test response proves native readiness.
        Log.i(TAG, "RELAY_TRANSPORT_PREPARED provisionalNativeWorker=true");
    }

    void run(ParcelFileDescriptor tunnel, long deadline, Runnable checkWifi) throws Exception {
        final RelayPacketPolicy policy = new RelayPacketPolicy(ip, physicalIp);
        final AtomicBoolean reverseFailure = new AtomicBoolean();
        reverseThread = new Thread(() -> {
            byte[] packet = new byte[65535];
            try {
                while (active(deadline)) {
                    int length = readPacket(pump, packet, deadline);
                    if (length == 0) continue;
                    budget(length);
                    // lwIP emits its own interface-up neighbor solicitation. Never inject it.
                    if (PacketMetadata.isTunIpv6Control(packet, length)) {
                        nativeIpv6Controls.incrementAndGet();
                        Arrays.fill(packet, 0, length, (byte) 0);
                        continue;
                    }
                    try { policy.inbound(packet, length); }
                    catch (IllegalArgumentException invalid) {
                        Log.w(TAG, "RELAY_UNEXPECTED_RETURN " + packetSummary(packet, length));
                        throw invalid;
                    }
                    writeMetadata("IN", packet, length);
                    writePacket(tunnel.getFileDescriptor(), packet, length, deadline);
                    injected.incrementAndGet();
                    Arrays.fill(packet, 0, length, (byte) 0);
                }
            } catch (Exception failure) {
                if (active(deadline)) {
                    reverseFailure.set(true);
                    Log.w(TAG, "RELAY_REVERSE_STOPPED error=" + failure.getClass().getSimpleName());
                }
            } finally { Arrays.fill(packet, (byte) 0); }
        }, "station-relay-reverse");
        reverseThread.setDaemon(true); reverseThread.start();
        byte[] packet = new byte[65535];
        try {
            while (active(deadline)) {
                checkWifi.run();
                if (reverseFailure.get() || !relay.isReady() || !TProxyService.TProxyIsRunning()) throw new IOException("relay_worker_failed");
                int length = readPacket(tunnel.getFileDescriptor(), packet, deadline);
                if (length == 0) continue;
                budget(length);
                int decision = policy.outbound(packet, length);
                if (decision == RelayPacketPolicy.IPV6_CONTROL) ipv6Controls.incrementAndGet();
                else {
                    PacketMetadata metadata = PacketMetadata.parse(packet, length);
                    writeMetadata("OUT", packet, length);
                    if (decision == RelayPacketPolicy.FORWARD) {
                        writePacket(pump, packet, length, deadline); forwarded.incrementAndGet();
                    } else dropped.incrementAndGet();
                }
                Arrays.fill(packet, 0, length, (byte) 0);
            }
        } finally { Arrays.fill(packet, (byte) 0); }
    }

    private boolean active(long deadline) { return !closed.get() && SystemClock.elapsedRealtime() < deadline; }
    private void budget(int length) throws IOException {
        if (records.incrementAndGet() > 512 || bytes.addAndGet(length) > 768000) throw new IOException("relay_packet_budget");
    }

    private int readPacket(FileDescriptor descriptor, byte[] packet, long deadline) throws Exception {
        if (!active(deadline)) return 0;
        StructPollfd poll = new StructPollfd(); poll.fd = descriptor; poll.events = (short) OsConstants.POLLIN;
        if (Os.poll(new StructPollfd[]{poll}, 50) == 0) return 0;
        if ((poll.revents & (OsConstants.POLLERR | OsConstants.POLLHUP | OsConstants.POLLNVAL)) != 0) throw new IOException("packet_descriptor_closed");
        if (!active(deadline)) return 0;
        try { return Os.read(descriptor, packet, 0, packet.length); }
        catch (ErrnoException retry) { if (retry.errno == OsConstants.EAGAIN || retry.errno == OsConstants.EINTR) return 0; throw retry; }
    }

    private void writePacket(FileDescriptor descriptor, byte[] packet, int length, long deadline) throws Exception {
        long waitUntil = Math.min(deadline, SystemClock.elapsedRealtime() + 250);
        while (active(waitUntil)) {
            try { if (Os.write(descriptor, packet, 0, length) != length) throw new IOException("partial_packet_write"); return; }
            catch (ErrnoException retry) { if (retry.errno != OsConstants.EAGAIN && retry.errno != OsConstants.EINTR) throw retry; }
            StructPollfd poll = new StructPollfd(); poll.fd = descriptor; poll.events = (short) OsConstants.POLLOUT;
            Os.poll(new StructPollfd[]{poll}, 20);
        }
        throw new IOException("packet_write_deadline");
    }

    synchronized void stop() {
        if (!closed.compareAndSet(false, true)) return;
        if (relay != null) relay.close();
        closeFd(pump);
        nativeStop = new Thread(() -> {
            boolean joined = !nativeStarted || TProxyService.TProxyStopService();
            boolean workerStopped = !nativeStarted || !TProxyService.TProxyIsRunning();
            // A failed native join must not let a still-running thread see a reused fd.
            // The independent fixed-package watchdog ends the process if join/stop fails.
            if (joined && workerStopped) {
                if (nativeDescriptor != null) try { nativeDescriptor.close(); } catch (IOException ignored) { }
                closeFd(engine);
            }
            if (reverseThread != null) try { reverseThread.join(300); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            if (relayThread != null) try { relayThread.join(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            synchronized (metadataLock) {
                if (metadataWriter != null) try { metadataWriter.close(); } catch (IOException ignored) { }
                metadataWriter = null;
            }
            config.delete();
            Log.i(TAG, "RELAY_TRANSPORT_END nativeJoined=" + joined + " nativeStopped=" + workerStopped + " records=" + records + " bytes=" + bytes
                    + " forwarded=" + forwarded + " injected=" + injected + " dropped=" + dropped + " ipv6Control=" + ipv6Controls
                    + " nativeIpv6Control=" + nativeIpv6Controls);
            if (relay != null) Log.i(TAG, relay.summary());
        }, "station-relay-native-stop");
        nativeStop.setDaemon(true); nativeStop.start();
    }

    private void writeMetadata(String direction, byte[] packet, int length) throws IOException {
        String summary = packetSummary(packet, length);
        synchronized (metadataLock) {
            if (closed.get() || metadataWriter == null) return;
            metadataWriter.write(direction + " " + summary);
            metadataWriter.newLine(); metadataWriter.flush();
        }
        Log.i(TAG, (direction.equals("IN") ? "RELAY_RETURN_PACKET " : "RELAY_PACKET ") + summary);
    }

    private String packetSummary(byte[] packet, int length) {
        PacketMetadata metadata = PacketMetadata.parse(packet, length);
        if (metadata == null) return PacketMetadata.headerSummary(packet, length);
        String json = metadata.toJson(records.get());
        int offset = (packet[0] & 15) * 4;
        int flags = metadata.protocol == 6 && offset + 20 <= length ? packet[offset + 13] & 255 : -1;
        int payload = flags < 0 ? 0 : length - offset - ((packet[offset + 12] & 255) >>> 4) * 4;
        return json.substring(0, json.length() - 1) + ",\"tcpFlags\":" + flags + ",\"payloadBytes\":" + payload + "}";
    }

    private static void closeFd(FileDescriptor descriptor) { if (descriptor.valid()) try { Os.close(descriptor); } catch (ErrnoException ignored) { } }
}
