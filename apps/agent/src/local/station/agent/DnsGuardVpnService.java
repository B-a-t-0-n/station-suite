package local.station.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DNS-only split tunnel guard.
 *
 * It does not route Wi-Fi or LAN traffic. The VPN carries only DNS packets to
 * 10.111.0.1; every other IPv4 packet stays on the real network. Quasar names
 * used by the PC lab are pinned to the PC, telemetry/OTA names are answered
 * with NXDOMAIN, and all other names are forwarded to the active Wi-Fi DNS.
 * ADB, Agent HTTP, PC Control and local media are therefore outside the VPN.
 */
public final class DnsGuardVpnService extends VpnService {
    private static final String TAG = "StationDnsGuard";
    private static final String CHANNEL_ID = "station_dns_guard";
    private static final int NOTIFICATION_ID = 3;
    private static final String VPN_ADDRESS = "10.111.0.2";
    private static final String DNS_ADDRESS = "10.111.0.1";
    private static final String PC_IP = "192.168.77.1";
    private static final String VOICE_IP = "192.168.77.2";
    private static final long MAX_RUNTIME_MS = 10 * 60 * 1000L;

    private static volatile boolean active;
    private static volatile long startedAt;
    private static final AtomicInteger queries = new AtomicInteger();
    private static final AtomicInteger blocked = new AtomicInteger();
    private static final AtomicInteger pinned = new AtomicInteger();
    private static final AtomicInteger forwarded = new AtomicInteger();
    private static final AtomicInteger errors = new AtomicInteger();
    private static volatile String lastEvent = "";

    private volatile boolean running;
    private ParcelFileDescriptor tunnel;
    private FileInputStream input;
    private FileOutputStream output;
    private DatagramSocket upstream;
    private Thread reader;
    private Thread watchdog;
    private InetAddress upstreamAddress;

    static boolean isActive() { return active; }
    static long startedAt() { return startedAt; }
    static int queries() { return queries.get(); }
    static int blocked() { return blocked.get(); }
    static int pinned() { return pinned.get(); }
    static int forwarded() { return forwarded.get(); }
    static int errors() { return errors.get(); }
    static String lastEvent() { return lastEvent; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Station DNS guard",
                NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        startForeground(NOTIFICATION_ID, notification("DNS guard starting"));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (tunnel == null) startTunnel();
        return START_NOT_STICKY;
    }

    private void startTunnel() {
        try {
            upstreamAddress = pickUpstream();
            upstream = new DatagramSocket();
            upstream.setSoTimeout(2500);
            protect(upstream);

            Builder builder = new Builder();
            builder.setSession("Station DNS guard");
            builder.addAddress(VPN_ADDRESS, 32);
            builder.addDnsServer(DNS_ADDRESS);
            builder.addRoute(DNS_ADDRESS, 32);
            try {
                builder.addDisallowedApplication(getPackageName());
            } catch (Exception error) {
                Log.w(TAG, "could not exclude Agent", error);
            }
            tunnel = builder.establish();
            if (tunnel == null) {
                lastEvent = "not authorized";
                update("VPN was not authorized");
                stopSelf();
                return;
            }
            input = new FileInputStream(tunnel.getFileDescriptor());
            output = new FileOutputStream(tunnel.getFileDescriptor());
            running = true;
            active = true;
            startedAt = System.currentTimeMillis();
            lastEvent = "active; upstream=" + upstreamAddress.getHostAddress();
            update("DNS guard active; upstream " + upstreamAddress.getHostAddress());
            reader = new Thread(this::readLoop, "StationDnsGuardRead");
            reader.setDaemon(true);
            reader.start();
            watchdog = new Thread(this::watchdog, "StationDnsGuardWatchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            Log.i(TAG, "DNS-only VPN established; upstream=" + upstreamAddress.getHostAddress());
        } catch (Exception error) {
            lastEvent = "start failed: " + error.getClass().getSimpleName();
            Log.e(TAG, "DNS guard start failed", error);
            update("DNS guard error: " + error.getClass().getSimpleName());
            stopSelf();
        }
    }

    private InetAddress pickUpstream() {
        try {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            InetAddress ethernet = null;
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities caps = manager.getNetworkCapabilities(network);
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                LinkProperties properties = manager.getLinkProperties(network);
                if (properties == null) continue;
                List<InetAddress> servers = properties.getDnsServers();
                if (servers.isEmpty()) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return servers.get(0);
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) && ethernet == null) {
                    ethernet = servers.get(0);
                }
            }
            if (ethernet != null) return ethernet;
        } catch (Exception error) {
            Log.w(TAG, "DNS discovery failed", error);
        }
        try {
            return InetAddress.getByName(PC_IP);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private void readLoop() {
        byte[] buffer = new byte[32768];
        try {
            while (running) {
                int length = input.read(buffer);
                if (length < 0) break;
                handlePacket(buffer, length);
            }
        } catch (Exception error) {
            if (running) Log.w(TAG, "TUN read stopped", error);
        }
    }

    private void handlePacket(byte[] packet, int length) {
        try {
            if (length < 28) return;
            if (((packet[0] >> 4) & 0x0f) != 4) return; // IPv4 only; IPv6 stays outside this VPN.
            int headerLength = (packet[0] & 0x0f) * 4;
            if (headerLength < 20 || length < headerLength + 8) return;
            if ((packet[9] & 0xff) != 17) return; // UDP
            byte[] destination = Arrays.copyOfRange(packet, 16, 20);
            if (!Arrays.equals(destination, ipBytes(DNS_ADDRESS))) return;
            int sourcePort = u16(packet, headerLength);
            int destinationPort = u16(packet, headerLength + 2);
            if (destinationPort != 53) return;
            int udpLength = u16(packet, headerLength + 4);
            if (udpLength < 8) return;
            int dnsLength = udpLength - 8;
            if (dnsLength <= 0 || headerLength + 8 + dnsLength > length) return;
            byte[] query = Arrays.copyOfRange(packet, headerLength + 8, headerLength + 8 + dnsLength);
            byte[] source = Arrays.copyOfRange(packet, 12, 16);
            queries.incrementAndGet();

            Question question = parseQuestion(query);
            if (question == null) {
                sendResponse(source, sourcePort, buildError(query, 1));
                errors.incrementAndGet();
                return;
            }
            String name = question.name.toLowerCase();
            if (isBlocked(name)) {
                blocked.incrementAndGet();
                lastEvent = "blocked " + name;
                sendResponse(source, sourcePort, buildNegative(query, question, 3, false));
                Log.i(TAG, "blocked DNS " + name);
                return;
            }
            String override = overrideFor(name);
            if (override != null) {
                pinned.incrementAndGet();
                boolean ipv4 = question.type == 1;
                sendResponse(source, sourcePort, buildNegative(query, question, 0, ipv4));
                lastEvent = "pinned " + name + " -> " + override;
                Log.i(TAG, "pinned DNS " + name + " -> " + override);
                return;
            }
            forward(source, sourcePort, query);
        } catch (Exception error) {
            errors.incrementAndGet();
            Log.w(TAG, "DNS packet failed", error);
        }
    }

    private void forward(byte[] clientAddress, int clientPort, byte[] query) throws IOException {
        DatagramPacket request = new DatagramPacket(query, query.length, upstreamAddress, 53);
        upstream.send(request);
        byte[] buffer = new byte[4096];
        DatagramPacket response = new DatagramPacket(buffer, buffer.length);
        try {
            upstream.receive(response);
        } catch (Exception error) {
            errors.incrementAndGet();
            sendResponse(clientAddress, clientPort, buildError(query, 2));
            return;
        }
        forwarded.incrementAndGet();
        byte[] dns = Arrays.copyOf(response.getData(), response.getLength());
        sendResponse(clientAddress, clientPort, dns);
    }

    private static boolean isBlocked(String name) {
        return name.equals("clck.yandex.net") || name.endsWith(".clck.yandex.net")
                || name.equals("startup.mobile.yandex.net") || name.endsWith(".startup.mobile.yandex.net")
                || name.equals("startup.mobile.webvisor.com") || name.endsWith(".startup.mobile.webvisor.com")
                || name.equals("u.startup.mobile.webvisor.com")
                || name.equals("dr.yandex.net") || name.endsWith(".dr.yandex.net")
                || name.equals("dr2.yandex.net") || name.endsWith(".dr2.yandex.net")
                || name.equals("yandexmetrica.com") || name.endsWith(".yandexmetrica.com")
                || name.equals("mc.yandex.ru") || name.endsWith(".mc.yandex.ru");
    }

    private static String overrideFor(String name) {
        if (name.equals("quasar.yandex.net") || name.equals("quasar.yandex.ru")
                || name.equals("scbh.yandex.net") || name.equals("api.lbs.yandex.net")) {
            return PC_IP;
        }
        if (name.equals("uniproxy.alice.yandex.net")) return VOICE_IP;
        return null;
    }

    private void sendResponse(byte[] clientAddress, int clientPort, byte[] dns) {
        try {
            byte[] source = ipBytes(DNS_ADDRESS);
            int total = 20 + 8 + dns.length;
            byte[] packet = new byte[total];
            packet[0] = 0x45;
            packet[1] = 0;
            put16(packet, 2, total);
            put16(packet, 4, (int) (System.nanoTime() & 0xffff));
            put16(packet, 6, 0x4000);
            packet[8] = 64;
            packet[9] = 17;
            System.arraycopy(source, 0, packet, 12, 4);
            System.arraycopy(clientAddress, 0, packet, 16, 4);
            put16(packet, 20, 53);
            put16(packet, 22, clientPort);
            put16(packet, 24, 8 + dns.length);
            put16(packet, 26, 0);
            System.arraycopy(dns, 0, packet, 28, dns.length);
            put16(packet, 10, checksum(packet, 0, 20));
            output.write(packet);
            output.flush();
        } catch (Exception error) {
            errors.incrementAndGet();
            Log.w(TAG, "DNS response write failed", error);
        }
    }

    private static Question parseQuestion(byte[] dns) {
        if (dns.length < 12) return null;
        int flags = u16(dns, 2);
        if ((flags & 0x8000) != 0 || u16(dns, 4) != 1) return null;
        int offset = 12;
        StringBuilder name = new StringBuilder();
        while (offset < dns.length) {
            int length = dns[offset++] & 0xff;
            if (length == 0) break;
            if ((length & 0xc0) != 0 || length > 63 || offset + length > dns.length) return null;
            if (name.length() > 0) name.append('.');
            name.append(new String(dns, offset, length, StandardCharsets.ISO_8859_1));
            offset += length;
            if (name.length() > 255) return null;
        }
        if (offset + 4 > dns.length) return null;
        int type = u16(dns, offset);
        int qclass = u16(dns, offset + 2);
        if (qclass != 1) return null;
        return new Question(name.toString(), type, offset + 4);
    }

    private static byte[] buildNegative(byte[] query, Question question, int rcode, boolean answer) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        put16(out, u16(query, 0));
        int queryFlags = u16(query, 2);
        put16(out, 0x8000 | (queryFlags & 0x0100) | 0x0080 | (rcode & 0x0f));
        put16(out, 1);
        put16(out, answer ? 1 : 0);
        put16(out, 0);
        put16(out, 0);
        out.write(query, 12, question.end - 12);
        if (answer) {
            put16(out, 0xC00C);
            put16(out, 1);
            put16(out, 1);
            put32(out, 30);
            put16(out, 4);
            String value = overrideFor(question.name.toLowerCase());
            byte[] address = ipBytes(value == null ? PC_IP : value);
            out.write(address, 0, 4);
        }
        return out.toByteArray();
    }

    private static byte[] buildError(byte[] query, int rcode) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        put16(out, query.length >= 2 ? u16(query, 0) : 0);
        put16(out, 0x8000 | 0x0080 | (rcode & 0x0f));
        put16(out, 0);
        put16(out, 0);
        put16(out, 0);
        put16(out, 0);
        return out.toByteArray();
    }

    private void watchdog() {
        try {
            Thread.sleep(MAX_RUNTIME_MS);
        } catch (InterruptedException ignored) {
            return;
        }
        if (running) {
            lastEvent = "lease expired";
            Log.w(TAG, "DNS guard lease expired; stopping");
            stopSelf();
        }
    }

    @Override public void onRevoke() {
        Log.w(TAG, "DNS guard revoked by system");
        stopSelf();
    }

    @Override public void onDestroy() {
        running = false;
        active = false;
        startedAt = 0;
        if (watchdog != null) watchdog.interrupt();
        if (upstream != null) upstream.close();
        upstream = null;
        try { if (input != null) input.close(); } catch (Exception ignored) { }
        try { if (output != null) output.close(); } catch (Exception ignored) { }
        input = null;
        output = null;
        ParcelFileDescriptor current = tunnel;
        tunnel = null;
        if (current != null) {
            try { current.close(); } catch (Exception ignored) { }
        }
        stopForeground(true);
        super.onDestroy();
    }

    private void update(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, VpnConsentActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Station DNS guard")
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private static byte[] ipBytes(String value) {
        String[] parts = value.split("\\.");
        byte[] result = new byte[4];
        for (int i = 0; i < 4; i++) result[i] = (byte) Integer.parseInt(parts[i]);
        return result;
    }

    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private static void put16(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >> 8);
        data[offset + 1] = (byte) value;
    }

    private static void put16(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static void put32(ByteArrayOutputStream out, long value) {
        out.write((int) (value >> 24) & 0xff);
        out.write((int) (value >> 16) & 0xff);
        out.write((int) (value >> 8) & 0xff);
        out.write((int) value & 0xff);
    }

    private static int checksum(byte[] data, int offset, int length) {
        long sum = 0;
        for (int i = 0; i < length; i += 2) {
            int value = ((data[offset + i] & 0xff) << 8)
                    | (i + 1 < length ? data[offset + i + 1] & 0xff : 0);
            sum += value;
        }
        while ((sum >> 16) != 0) sum = (sum & 0xffff) + (sum >> 16);
        return (int) (~sum & 0xffff);
    }

    private static final class Question {
        final String name;
        final int type;
        final int end;

        Question(String name, int type, int end) {
            this.name = name;
            this.type = type;
            this.end = end;
        }
    }
}
