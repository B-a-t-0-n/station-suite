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

/** Bounded /32 relay canary. This is not an autonomous firewall or production VPN. */
public final class RelayService extends VpnService {
    static final String ACTION_RELAY = ProbeSpec.PACKAGE + ".RELAY";
    private RelayTransport transport;
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
        if (intent == null || !ACTION_RELAY.equals(intent.getAction())) {
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
        String stage = "start";
        try {
            if (stopped.get()) throw new IOException("Probe is stopped");
            stage = "consent";
            if (VpnService.prepare(this) != null) throw new IOException("OS consent is absent");
            stage = "ethernet_preflight";
            requireIsolatedEthernet();
            ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
            android.net.LinkProperties link = connectivity.getLinkProperties(connectivity.getActiveNetwork());
            boolean expectedAddress = false;
            if (link != null) for (android.net.LinkAddress address : link.getLinkAddresses())
                if ("192.168.77.100".equals(address.getAddress().getHostAddress())) expectedAddress = true;
            if (!expectedAddress || !"192.168.77.1".equals(ip)) throw new IOException("Exact isolated lab addresses required");
            Log.i(TAG, "RELAY_PHYSICAL networkHandle=" + connectivity.getActiveNetwork().getNetworkHandle());
            stage = "prepare_local_transport";
            synchronized (descriptorLock) {
                if (stopped.get()) throw new IOException("Probe is stopped");
                transport = new RelayTransport(getFilesDir(), new java.io.File(getApplicationInfo().nativeLibraryDir,
                        "libhev-socks5-tunnel.so"), ip, "192.168.77.100");
            }
            transport.prepare();
            stage = "builder";
            Builder builder = new Builder().setSession("Station /32 local relay test (10s)")
                    .addAddress(RelayPacketPolicy.TUN_IP, 32).addRoute(ip, 32).setMtu(1500)
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
            if (watchdogDeadline - now < ProbeSpec.MIN_WATCHDOG_REMAINING_MS || stopped.get())
                throw new IOException("Insufficient independent watchdog margin");
            final long deadline = now + ProbeSpec.DURATION_MS;
            deadlineExecutor.schedule(() -> finish("STOPPED relay deadline"), ProbeSpec.DURATION_MS, TimeUnit.MILLISECONDS);
            stage = "establish";
            ParcelFileDescriptor established = builder.establish();
            if (established == null) throw new IOException("Android refused VPN interface");
            synchronized (descriptorLock) {
                if (stopped.get() || SystemClock.elapsedRealtime() >= deadline) {
                    established.close(); throw new IOException("Probe expired during establish");
                }
                tunnel = established;
            }
            Log.i(TAG, "RELAY_READY destination=" + ip + "/32 durationMs=10000 excludedManagement=true ipv6Capture=false");
            stage = "relay_packet_pump";
            transport.run(established, deadline, () -> {
                try { requireWifiOff(); }
                catch (IOException enabled) { throw new IllegalStateException("wifi_changed"); }
            });
        } catch (Exception | LinkageError failure) {
            if (!stopped.get()) Log.w(TAG, "RELAY_REFUSED_OR_STOPPED stage=" + stage + " error=" + failure.getClass().getSimpleName());
        } finally { finish("STOPPED relay complete"); }
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
        if (transport != null) transport.stop();
        if (deadlineExecutor != null) deadlineExecutor.shutdownNow();
        Log.i(TAG, reason);
        stopForeground(true);
        stopSelf();
    }

    @Override public void onRevoke() { finish("STOPPED consent revoked"); super.onRevoke(); }
    @Override public void onDestroy() { finish("STOPPED service destroyed"); super.onDestroy(); }
}
