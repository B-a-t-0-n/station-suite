package local.station.vpnprobe;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.net.wifi.WifiManager;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONException;
import org.json.JSONObject;

/** Drop-only /32 canary. This is not a firewall, DNS proxy, tunnel, or production VPN. */
public final class ProbeService extends VpnService {
    private static final String TAG = "StationVpnProbe";
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final Object descriptorLock = new Object();
    private ParcelFileDescriptor tunnel;
    private Socket pendingWatchdog;
    private ScheduledExecutorService deadlineExecutor;

    @Override public void onCreate() {
        super.onCreate();
        deadlineExecutor = Executors.newSingleThreadScheduledExecutor();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(101, ProbeNotification.create(this, "Validating independent watchdog"));
        if (intent == null || !ProbeSpec.ACTION_PROBE.equals(intent.getAction())) {
            finish("REFUSED missing explicit probe request");
            return START_NOT_STICKY;
        }
        if (!started.compareAndSet(false, true)) {
            finish("REFUSED repeated request does not extend the deadline");
            return START_NOT_STICKY;
        }
        try {
            final String ip = ProbeSpec.validateConfigIp(intent.getStringExtra(ProbeSpec.EXTRA_IP));
            final String nonce = ProbeSpec.validateNonce(intent.getStringExtra(ProbeSpec.EXTRA_NONCE));
            final long created = intent.getLongExtra(ProbeSpec.EXTRA_CREATED, -1);
            ProbeSpec.requireFresh(created, SystemClock.elapsedRealtime());
            deadlineExecutor.schedule(new Runnable() {
                @Override public void run() { finish("STOPPED request deadline"); }
            }, Math.max(0, ProbeSpec.MAX_START_AGE_MS
                    - (SystemClock.elapsedRealtime() - created)), TimeUnit.MILLISECONDS);
            new Thread(new Runnable() {
                @Override public void run() { runProbe(ip, nonce, created); }
            }, "station-vpn-capture").start();
        } catch (RuntimeException error) {
            finish("REFUSED " + error.getClass().getSimpleName());
        }
        return START_NOT_STICKY;
    }

    private void runProbe(String ip, String nonce, long created) {
        byte[] packet = new byte[65_535];
        long count = 0;
        long ipv6Control = 0;
        String stage = "start";
        try {
            if (stopped.get()) throw new IOException("Probe is stopped");
            stage = "consent";
            if (VpnService.prepare(this) != null) throw new IOException("OS consent is absent");
            stage = "ethernet_preflight";
            requireIsolatedEthernet();
            stage = "builder";
            Builder builder = new Builder().setSession("Station /32 coverage test (10s)")
                    .addAddress("10.111.77.2", 32).addRoute(ip, 32).setMtu(1500)
                    .setBlocking(false).allowFamily(OsConstants.AF_INET6);
            stage = "exclude_management";
            excludeManagementUids(builder);
            ProbeSpec.requireFresh(created, SystemClock.elapsedRealtime());
            stage = "watchdog_arm";
            long watchdogDeadline = armWatchdog(nonce);
            stage = "final_preflight";
            requireIsolatedEthernet();
            ProbeSpec.requireFresh(created, SystemClock.elapsedRealtime());
            long now = SystemClock.elapsedRealtime();
            if (watchdogDeadline - now < ProbeSpec.MIN_WATCHDOG_REMAINING_MS || stopped.get()) {
                throw new IOException("Insufficient watchdog deadline before establish");
            }
            final long deadline = now + ProbeSpec.DURATION_MS;
            deadlineExecutor.schedule(new Runnable() {
                @Override public void run() { finish("STOPPED deadline"); }
            }, ProbeSpec.DURATION_MS, TimeUnit.MILLISECONDS);
            stage = "establish";
            ParcelFileDescriptor established = builder.establish();
            if (established == null) throw new IOException("Android refused VPN interface");
            synchronized (descriptorLock) {
                if (stopped.get() || SystemClock.elapsedRealtime() >= deadline) {
                    established.close();
                    throw new IOException("Probe expired while establishing interface");
                }
                tunnel = established;
            }
            Log.i(TAG, "CAPTURE_READY destination=" + ip + "/32 durationMs=10000"
                    + " excludedManagement=true ipv6Capture=false");
            stage = "metadata_file";
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    openFileOutput("metadata-last.jsonl", MODE_PRIVATE), StandardCharsets.UTF_8))) {
                StructPollfd poll = new StructPollfd();
                poll.fd = established.getFileDescriptor();
                poll.events = (short) OsConstants.POLLIN;
                while (!stopped.get() && SystemClock.elapsedRealtime() < deadline
                        && count + ipv6Control < ProbeSpec.MAX_RECORDS) {
                    stage = "capture_wifi_check";
                    requireWifiOff();
                    poll.revents = 0;
                    stage = "capture_poll";
                    int ready = Os.poll(new StructPollfd[] {poll}, 100);
                    if (ready == 0) continue;
                    if ((poll.revents & (OsConstants.POLLERR | OsConstants.POLLHUP
                            | OsConstants.POLLNVAL)) != 0) break;
                    if ((poll.revents & OsConstants.POLLIN) == 0) continue;
                    int size;
                    try {
                        stage = "capture_read";
                        size = Os.read(established.getFileDescriptor(), packet, 0, packet.length);
                    } catch (ErrnoException error) {
                        if (error.errno == OsConstants.EAGAIN || error.errno == OsConstants.EINTR) continue;
                        throw error;
                    }
                    if (size <= 0) continue;
                    stage = "capture_header";
                    if (PacketMetadata.isTunIpv6Control(packet, size)) {
                        ipv6Control++;
                        Arrays.fill(packet, 0, size, (byte) 0);
                        continue;
                    }
                    PacketMetadata metadata = PacketMetadata.parse(packet, size);
                    String unexpected = metadata == null || !ip.equals(metadata.destinationIp)
                            ? PacketMetadata.headerSummary(packet, size) : null;
                    Arrays.fill(packet, 0, size, (byte) 0);
                    if (unexpected != null) {
                        Log.w(TAG, "UNEXPECTED_HEADER " + unexpected);
                        throw new IOException("Unexpected packet outside validated IPv4 canary");
                    }
                    stage = "metadata_write";
                    String record = metadata.toJson(++count);
                    writer.write(record);
                    writer.newLine();
                    writer.flush();
                    Log.i(TAG, "PACKET " + record);
                    // Drop: never forward, inject, decrypt, resolve DNS or inspect the body.
                }
            }
            Log.i(TAG, "CAPTURE_END records=" + count + " ipv6Control=" + ipv6Control);
        } catch (IOException | ErrnoException | JSONException | PackageManager.NameNotFoundException
                | RuntimeException error) {
            // Do not print exception messages: input/response data are not diagnostic payload.
            Log.w(TAG, "PROBE_REFUSED_OR_STOPPED stage=" + stage + " error="
                    + error.getClass().getSimpleName());
        } finally {
            Arrays.fill(packet, (byte) 0);
            finish("STOPPED capture complete records=" + count + " ipv6Control=" + ipv6Control);
        }
    }

    private void requireWifiOff() throws IOException {
        WifiManager wifi = getApplicationContext().getSystemService(WifiManager.class);
        if (wifi == null || wifi.isWifiEnabled()
                || wifi.getWifiState() != WifiManager.WIFI_STATE_DISABLED) {
            throw new IOException("This diagnostic requires Wi-Fi disabled");
        }
    }

    private void requireIsolatedEthernet() throws IOException {
        requireWifiOff();
        ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
        if (connectivity == null) throw new IOException("No connectivity service");
        Network active = connectivity.getActiveNetwork();
        NetworkCapabilities activeCaps = active == null ? null : connectivity.getNetworkCapabilities(active);
        if (activeCaps == null || !activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                || activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            throw new IOException("Physical Ethernet must be the active network");
        }
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                throw new IOException("Unexpected physical or existing VPN network");
            }
        }
    }

    private void excludeManagementUids(Builder builder) throws PackageManager.NameNotFoundException {
        PackageManager manager = getPackageManager();
        for (String name : ProbeSpec.EXCLUDED_PACKAGES) {
            ApplicationInfo info = manager.getApplicationInfo(name, 0);
            if (("com.android.shell".equals(name) && info.uid != 2000)
                    || (!"com.android.shell".equals(name) && info.uid < 10_000)
                    || (ProbeSpec.PACKAGE.equals(name) && info.uid != android.os.Process.myUid())) {
                throw new SecurityException("Unexpected management package UID");
            }
            builder.addDisallowedApplication(name);
        }
    }

    private long armWatchdog(String nonce) throws IOException, JSONException {
        long exchangeStarted = SystemClock.elapsedRealtime();
        try (Socket socket = new Socket()) {
            synchronized (descriptorLock) {
                if (stopped.get()) throw new IOException("Probe is stopped");
                pendingWatchdog = socket;
            }
            socket.setSoTimeout(750);
            socket.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}),
                    WatchdogProtocol.PORT), 750);
            byte[] challengeBytes = new byte[16];
            new SecureRandom().nextBytes(challengeBytes);
            StringBuilder encodedChallenge = new StringBuilder(32);
            for (byte value : challengeBytes) {
                encodedChallenge.append(Character.forDigit((value >>> 4) & 15, 16));
                encodedChallenge.append(Character.forDigit(value & 15, 16));
            }
            Arrays.fill(challengeBytes, (byte) 0);
            String challenge = encodedChallenge.toString();
            socket.getOutputStream().write((WatchdogProtocol.armRequest(nonce, challenge) + "\n")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            InputStream input = socket.getInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            boolean terminated = false;
            while (bytes.size() < 1024) {
                if (SystemClock.elapsedRealtime() - exchangeStarted > 1_000) {
                    throw new IOException("Watchdog exchange expired");
                }
                int value = input.read();
                if (value == -1) break;
                if (value == '\n') { terminated = true; break; }
                if (value < 32 || value > 126) throw new IOException("Non-ASCII watchdog response");
                bytes.write(value);
            }
            if (!terminated) throw new IOException("Missing bounded watchdog response");
            if (SystemClock.elapsedRealtime() - exchangeStarted > 1_000) {
                throw new IOException("Watchdog exchange expired");
            }
            JSONObject response = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.US_ASCII));
            if (!Boolean.TRUE.equals(response.opt("ready")) || !challenge.equals(response.opt("challenge"))
                    || !(response.opt("uid") instanceof Number)
                    || ((Number) response.get("uid")).longValue() != 2000
                    || !(response.opt("remainingMs") instanceof Number)) {
                throw new SecurityException("Invalid watchdog acknowledgement");
            }
            long remaining = ((Number) response.get("remainingMs")).longValue();
            if (remaining < ProbeSpec.MIN_WATCHDOG_REMAINING_MS
                    || remaining > ProbeSpec.MAX_WATCHDOG_REMAINING_MS
                    || !WatchdogProtocol.validReady(nonce, challenge, remaining,
                            response.optString("proof", ""))) {
                throw new SecurityException("Invalid watchdog lifetime");
            }
            // Subtract the whole round trip conservatively, including response read/parse time.
            long deadline = exchangeStarted + remaining;
            if (deadline - SystemClock.elapsedRealtime() < ProbeSpec.MIN_WATCHDOG_REMAINING_MS) {
                throw new IOException("Watchdog margin was consumed before establish");
            }
            return deadline;
        } finally {
            synchronized (descriptorLock) { pendingWatchdog = null; }
        }
    }

    private void finish(String reason) {
        if (!stopped.compareAndSet(false, true)) return;
        synchronized (descriptorLock) {
            if (pendingWatchdog != null) {
                try { pendingWatchdog.close(); } catch (IOException ignored) { }
                pendingWatchdog = null;
            }
            if (tunnel != null) {
                try { tunnel.close(); } catch (IOException ignored) { }
                tunnel = null;
            }
        }
        if (deadlineExecutor != null) deadlineExecutor.shutdownNow();
        Log.i(TAG, reason);
        stopForeground(true);
        stopSelf();
    }

    @Override public void onRevoke() { finish("STOPPED consent revoked"); super.onRevoke(); }
    @Override public void onDestroy() { finish("STOPPED service destroyed"); super.onDestroy(); }
}
