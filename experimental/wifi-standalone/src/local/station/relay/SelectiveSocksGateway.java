package local.station.relay;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Authenticated loopback TCP gateway for the native TUN engine, not an admission
 * policy for TUN packets. The caller must admit TV flows before the native engine.
 * No DNS lookup, non-loopback connect, direct fallback, UDP or IPv6 is supported.
 * Capability credentials must stay in process/private native configuration and
 * must never be logged (the locked hev source logs them at DEBUG level).
 */
public final class SelectiveSocksGateway implements AutoCloseable {
    private static final byte[] LOOPBACK = {127, 0, 0, 1};
    private static final long MS = 1000000L;

    public interface Clock { long nanoTime(); }

    /** Fresh unguessable engine credential; no caller-supplied weak variant. */
    public static final class Capability {
        private final String username, password;
        private Capability(String username, String password) {
            this.username = username; this.password = password;
        }
        public static Capability random() {
            SecureRandom random = new SecureRandom();
            byte[] user = new byte[16], pass = new byte[32];
            random.nextBytes(user); random.nextBytes(pass);
            return new Capability(hex(user), hex(pass));
        }
        public String username() { return username; }
        public String password() { return password; }
        @Override public String toString() { return "Capability[redacted]"; }
    }

    /** The upstream host is always numeric loopback, never configurable. */
    public static final class SocksHop {
        private final int port;
        private final byte[] username, password;
        private SocksHop(int port, byte[] username, byte[] password) {
            if (port < 1 || port > 65535) throw new IllegalArgumentException("hop_port");
            this.port = port; this.username = username; this.password = password;
        }
        public static SocksHop loopback(int port) { return new SocksHop(port, null, null); }
        public static SocksHop authenticatedLoopback(int port, String user, String pass) {
            return new SocksHop(port, credential(user), credential(pass));
        }
        @Override public String toString() { return "SocksHop[loopback,redacted]"; }
    }

    /** Independent local/WAN capacities; counters and video budgets are 64-bit. */
    public static final class Limits {
        public final int maxLocal, maxWan, maxHandshakes;
        public final int handshakeTimeoutMs, connectTimeoutMs, idleTimeoutMs;
        public final long flowLifetimeMs, maxBytesPerDirection;
        public Limits(int maxLocal, int maxWan, int maxHandshakes,
                      int handshakeTimeoutMs, int connectTimeoutMs, int idleTimeoutMs,
                      long flowLifetimeMs, long maxBytesPerDirection) {
            if (maxLocal < 1 || maxLocal > 64 || maxWan < 1 || maxWan > 64
                || maxHandshakes < 1 || maxHandshakes > 64
                || handshakeTimeoutMs < 50 || handshakeTimeoutMs > 30000
                || connectTimeoutMs < 10 || connectTimeoutMs > handshakeTimeoutMs
                || idleTimeoutMs < 50 || idleTimeoutMs > 3600000
                || flowLifetimeMs < handshakeTimeoutMs || flowLifetimeMs > 86400000L
                || maxBytesPerDirection < 1 || maxBytesPerDirection > (1L << 40)) {
                throw new IllegalArgumentException("bounded_limits_required");
            }
            this.maxLocal = maxLocal; this.maxWan = maxWan; this.maxHandshakes = maxHandshakes;
            this.handshakeTimeoutMs = handshakeTimeoutMs; this.connectTimeoutMs = connectTimeoutMs;
            this.idleTimeoutMs = idleTimeoutMs; this.flowLifetimeMs = flowLifetimeMs;
            this.maxBytesPerDirection = maxBytesPerDirection;
        }
        public static Limits defaults() {
            return new Limits(8, 8, 8, 3000, 1000, 300000, 28800000L, 64L << 30);
        }
    }

    private final byte[] virtualAddress, expectedUser, expectedPassword;
    private final int listenPort;
    private final SocksHop local, wan;
    private final Limits limits;
    private final Clock clock;
    private final ExecutorService handlers, pumps;
    private final ScheduledExecutorService watchdog;
    private final boolean ownExecutors;
    private final Semaphore localCapacity, wanCapacity, handshakeCapacity, totalCapacity;
    private final Set<Flow> flows = Collections.synchronizedSet(new HashSet<Flow>());
    private final AtomicLong accepted = new AtomicLong(), authDenied = new AtomicLong();
    private final AtomicLong policyDenied = new AtomicLong(), capacityDenied = new AtomicLong();
    private final AtomicLong upstreamFailed = new AtomicLong(), localOpened = new AtomicLong(), wanOpened = new AtomicLong();
    private final AtomicLong uploaded = new AtomicLong(), downloaded = new AtomicLong();
    private volatile boolean closed;
    private ServerSocket listener;
    private ScheduledFuture<?> expiry;

    public SelectiveSocksGateway(String virtualIpv4, int listenPort, int localSocksPort,
                                 SocksHop happ, Capability capability,
                                 boolean nativeRfc1929Supported, Limits limits) {
        this(virtualIpv4, listenPort, localSocksPort, happ, capability,
             nativeRfc1929Supported, limits, () -> System.nanoTime(), null, null, null);
    }

    /** Injected executors/clock are caller-owned; either all executors or none. */
    public SelectiveSocksGateway(String virtualIpv4, int listenPort, int localSocksPort,
                                 SocksHop happ, Capability capability,
                                 boolean nativeRfc1929Supported, Limits limits, Clock clock,
                                 ExecutorService handlers, ExecutorService pumps,
                                 ScheduledExecutorService watchdog) {
        if (!nativeRfc1929Supported) throw new IllegalArgumentException("native_auth_required");
        if (capability == null || happ == null || limits == null || clock == null
            || listenPort < 0 || listenPort > 65535) throw new IllegalArgumentException("configuration");
        virtualAddress = ipv4(virtualIpv4);
        // The service branch must never overlap the public branch.
        int a = virtualAddress[0] & 255, b = virtualAddress[1] & 255;
        if (!(a == 10 || a == 172 && b >= 16 && b <= 31 || a == 192 && b == 168)) {
            throw new IllegalArgumentException("private_virtual_address_required");
        }
        this.listenPort = listenPort; this.local = SocksHop.loopback(localSocksPort); this.wan = happ;
        expectedUser = credential(capability.username()); expectedPassword = credential(capability.password());
        this.limits = limits; this.clock = clock;
        int total = limits.maxLocal + limits.maxWan + limits.maxHandshakes;
        localCapacity = new Semaphore(limits.maxLocal); wanCapacity = new Semaphore(limits.maxWan);
        handshakeCapacity = new Semaphore(limits.maxHandshakes);
        totalCapacity = new Semaphore(total);
        ownExecutors = handlers == null && pumps == null && watchdog == null;
        if (!ownExecutors && (handlers == null || pumps == null || watchdog == null || handlers == pumps)) {
            throw new IllegalArgumentException("separate_executors_required");
        }
        this.handlers = ownExecutors ? pool(total, "selective-socks-handler") : handlers;
        this.pumps = ownExecutors ? pool(limits.maxLocal + limits.maxWan, "selective-socks-upload") : pumps;
        this.watchdog = ownExecutors ? Executors.newSingleThreadScheduledExecutor(daemon("selective-socks-watchdog")) : watchdog;
    }

    /** Blocks until close; starts no Android service and changes no network state. */
    public void run() throws IOException {
        synchronized (this) {
            if (closed || listener != null) throw new IllegalStateException("single_start_only");
            listener = new ServerSocket(); listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(loopback(), listenPort), limits.maxHandshakes);
            try { expiry = watchdog.scheduleAtFixedRate(this::expire, 20, 20, TimeUnit.MILLISECONDS); }
            catch (RejectedExecutionException failure) { close(); throw new IOException("watchdog_unavailable"); }
        }
        try {
            while (!closed) {
                Socket client = listener.accept();
                if (!totalCapacity.tryAcquire()) { capacityDenied.incrementAndGet(); client.close(); continue; }
                if (!handshakeCapacity.tryAcquire()) {
                    capacityDenied.incrementAndGet(); client.close(); totalCapacity.release(); continue;
                }
                Flow flow = new Flow(client);
                synchronized (flows) {
                    if (closed) { client.close(); flow.releaseHandshake(); totalCapacity.release(); break; }
                    flows.add(flow);
                }
                accepted.incrementAndGet();
                try { handlers.execute(() -> handle(flow)); }
                catch (RejectedExecutionException rejected) {
                    capacityDenied.incrementAndGet(); flow.close(); flow.releaseHandshake(); flows.remove(flow); totalCapacity.release();
                }
            }
        } catch (IOException failure) { if (!closed) throw failure; }
        finally { close(); }
    }

    public synchronized boolean isReady() { return !closed && listener != null && listener.isBound(); }
    public synchronized int boundListenPort() { return listener == null ? -1 : listener.getLocalPort(); }

    private void handle(Flow flow) {
        Semaphore branchCapacity = null;
        Future<?> upload = null;
        try {
            flow.client.setSoTimeout(limits.handshakeTimeoutMs);
            InputStream input = flow.client.getInputStream(); OutputStream output = flow.client.getOutputStream();
            if (!authenticate(input, output)) return;
            byte[] request = exact(input, 4);
            if (request[0] != 5 || request[2] != 0) { deny(output, 1); return; }
            if (request[1] != 1) { deny(output, 7); return; }
            if (request[3] != 1) { deny(output, 8); return; }
            byte[] address = exact(input, 4); int port = octet(input) * 256 + octet(input);
            boolean service = Arrays.equals(address, virtualAddress) && (port == 80 || port == 443);
            if (port == 0 || (!service && !isPublicIpv4(address))) { deny(output, 2); return; }
            branchCapacity = service ? localCapacity : wanCapacity;
            if (!branchCapacity.tryAcquire()) { branchCapacity = null; capacityDenied.incrementAndGet(); reply(output, 2); return; }
            flow.ensureActive();
            Socket backend = new Socket();
            if (!flow.attach(backend)) return;
            backend.setSoTimeout(limits.handshakeTimeoutMs);
            try {
                SocksHop hop = service ? local : wan;
                backend.connect(new InetSocketAddress(loopback(), hop.port), limits.connectTimeoutMs);
                negotiate(backend, hop, address, port);
            } catch (IOException failure) {
                upstreamFailed.incrementAndGet(); reply(output, 1); return;
            }
            flow.ensureActive(); reply(output, 0); flow.established = true;
            flow.releaseHandshake();
            if (service) localOpened.incrementAndGet(); else wanOpened.incrementAndGet();
            flow.client.setSoTimeout(Math.min(1000, limits.idleTimeoutMs));
            backend.setSoTimeout(Math.min(1000, limits.idleTimeoutMs));
            upload = pumps.submit(() -> {
                try { transfer(flow, flow.client, backend, uploaded); }
                catch (IOException failure) { flow.close(); }
                finally { try { backend.shutdownOutput(); } catch (IOException ignored) { } }
            });
            transfer(flow, backend, flow.client, downloaded);
            // A server half-close must not discard a still-active client upload.
            while (true) {
                flow.ensureActive();
                try { upload.get(250, TimeUnit.MILLISECONDS); break; }
                catch (TimeoutException pending) { }
                catch (ExecutionException failure) { throw new IOException("upload_failed"); }
                catch (InterruptedException stop) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
            }
        } catch (IOException | RejectedExecutionException failure) {
            // No exception messages, destination names or capability are logged.
        } finally {
            flow.close(); flow.releaseHandshake(); if (upload != null) upload.cancel(true);
            if (branchCapacity != null) branchCapacity.release();
            flows.remove(flow); totalCapacity.release();
        }
    }

    private boolean authenticate(InputStream input, OutputStream output) throws IOException {
        if (octet(input) != 5) { authDenied.incrementAndGet(); return false; }
        int count = octet(input);
        if (count < 1 || count > 16) { authDenied.incrementAndGet(); return false; }
        boolean offered = false;
        for (byte method : exact(input, count)) if (method == 2) offered = true;
        output.write(new byte[]{5, (byte) (offered ? 2 : 255)}); output.flush();
        if (!offered) { authDenied.incrementAndGet(); return false; }
        if (octet(input) != 1) { authDenied.incrementAndGet(); return false; }
        byte[] user = exact(input, octet(input));
        byte[] password = exact(input, octet(input));
        boolean valid = MessageDigest.isEqual(expectedUser, user) & MessageDigest.isEqual(expectedPassword, password);
        output.write(new byte[]{1, (byte) (valid ? 0 : 1)}); output.flush();
        Arrays.fill(user, (byte) 0); Arrays.fill(password, (byte) 0);
        if (!valid) authDenied.incrementAndGet();
        return valid;
    }

    /** Exactly the configured method; method downgrade and failed hops never fall back. */
    private static void negotiate(Socket backend, SocksHop hop, byte[] address, int port) throws IOException {
        InputStream input = backend.getInputStream(); OutputStream output = backend.getOutputStream();
        int method = hop.username == null ? 0 : 2;
        output.write(new byte[]{5, 1, (byte) method}); output.flush();
        if (octet(input) != 5 || octet(input) != method) throw new IOException("upstream_method");
        if (method == 2) {
            output.write(1); output.write(hop.username.length); output.write(hop.username);
            output.write(hop.password.length); output.write(hop.password); output.flush();
            if (octet(input) != 1 || octet(input) != 0) throw new IOException("upstream_auth");
        }
        output.write(new byte[]{5, 1, 0, 1}); output.write(address);
        output.write(port >>> 8); output.write(port); output.flush();
        byte[] reply = exact(input, 4);
        if (reply[0] != 5 || reply[1] != 0 || reply[2] != 0) throw new IOException("upstream_reply");
        int type = reply[3] & 255;
        if (type == 1) exact(input, 4);
        else if (type == 4) exact(input, 16);
        else if (type == 3) { int length = octet(input); if (length == 0) throw new IOException("upstream_bound"); exact(input, length); }
        else throw new IOException("upstream_bound");
        exact(input, 2);
    }

    private void transfer(Flow flow, Socket source, Socket destination, AtomicLong total) throws IOException {
        InputStream input = source.getInputStream(); OutputStream output = destination.getOutputStream();
        byte[] buffer = new byte[16384]; long transferred = 0;
        while (true) {
            flow.ensureActive(); int count;
            try { count = input.read(buffer); }
            catch (SocketTimeoutException idle) { continue; }
            if (count < 0) { destination.shutdownOutput(); return; }
            if (count == 0 || transferred > limits.maxBytesPerDirection - count) throw new IOException("flow_budget");
            flow.ensureActive(); output.write(buffer, 0, count); output.flush();
            transferred += count; total.addAndGet(count); flow.lastActivity = clock.nanoTime();
        }
    }

    private void deny(OutputStream output, int code) throws IOException { policyDenied.incrementAndGet(); reply(output, code); }
    private static void reply(OutputStream output, int code) throws IOException {
        output.write(new byte[]{5, (byte) code, 0, 1, 127, 0, 0, 1, 0, 0}); output.flush();
    }
    private void expire() {
        Flow[] active; synchronized (flows) { active = flows.toArray(new Flow[0]); }
        for (Flow flow : active) if (flow.expired()) flow.close();
    }
    private final class Flow {
        final Socket client; final long started = clock.nanoTime();
        volatile long lastActivity = started;
        volatile boolean established, stopped;
        private boolean handshakeHeld = true;
        private Socket backend;
        Flow(Socket client) { this.client = client; }
        synchronized boolean attach(Socket value) {
            if (stopped || closed) { closeSocket(value); return false; }
            backend = value; return true;
        }
        boolean expired() {
            long now = clock.nanoTime();
            return closed || stopped || now - started >= limits.flowLifetimeMs * MS
                || (!established && now - started >= limits.handshakeTimeoutMs * MS)
                || (established && now - lastActivity >= limits.idleTimeoutMs * MS);
        }
        void ensureActive() throws IOException { if (expired()) throw new IOException("flow_expired"); }
        synchronized void releaseHandshake() { if (handshakeHeld) { handshakeHeld = false; handshakeCapacity.release(); } }
        synchronized void close() { stopped = true; closeSocket(client); closeSocket(backend); }
    }

    public String summary() {
        return "{\"accepted\":" + accepted + ",\"authDenied\":" + authDenied
            + ",\"policyDenied\":" + policyDenied + ",\"capacityDenied\":" + capacityDenied
            + ",\"upstreamFailed\":" + upstreamFailed + ",\"localOpened\":" + localOpened
            + ",\"wanOpened\":" + wanOpened + ",\"uploadedBytes\":" + uploaded
            + ",\"downloadedBytes\":" + downloaded + ",\"active\":" + flows.size()
            + ",\"closed\":" + closed + "}";
    }
    @Override public void close() {
        synchronized (this) {
            closed = true;
            if (listener != null) try { listener.close(); } catch (IOException ignored) { }
            if (expiry != null) expiry.cancel(false);
        }
        synchronized (flows) { for (Flow flow : flows) flow.close(); }
        if (ownExecutors) { handlers.shutdownNow(); pumps.shutdownNow(); watchdog.shutdownNow(); }
    }

    /** Conservative globally routable IPv4 subset; no resolution or allow-by-name. */
    public static boolean isPublicIpv4(byte[] address) {
        if (address == null || address.length != 4) return false;
        int a = address[0] & 255, b = address[1] & 255, c = address[2] & 255;
        return !(a == 0 || a == 10 || a == 127 || a >= 224
            || a == 100 && b >= 64 && b <= 127 || a == 169 && b == 254
            || a == 172 && b >= 16 && b <= 31 || a == 192 && b == 168
            || a == 192 && b == 0 && (c == 0 || c == 2)
            || a == 192 && b == 88 && c == 99 || a == 198 && (b == 18 || b == 19)
            || a == 198 && b == 51 && c == 100 || a == 203 && b == 0 && c == 113);
    }
    private static byte[] ipv4(String value) {
        if (value == null) throw new IllegalArgumentException("ipv4_required");
        String[] pieces = value.split("\\.", -1); byte[] result = new byte[4];
        if (pieces.length != 4) throw new IllegalArgumentException("ipv4_required");
        for (int i = 0; i < 4; ++i) {
            if (!pieces[i].matches("0|[1-9][0-9]{0,2}")) throw new IllegalArgumentException("ipv4_required");
            int part = Integer.parseInt(pieces[i]); if (part > 255) throw new IllegalArgumentException("ipv4_required");
            result[i] = (byte) part;
        }
        return result;
    }
    private static byte[] credential(String value) {
        if (value == null) throw new IllegalArgumentException("credentials_required");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 1 || bytes.length > 255) throw new IllegalArgumentException("credential_length");
        return bytes;
    }
    private static String hex(byte[] bytes) {
        char[] text = new char[bytes.length * 2], alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; ++i) { text[i * 2] = alphabet[(bytes[i] & 255) >>> 4]; text[i * 2 + 1] = alphabet[bytes[i] & 15]; }
        return new String(text);
    }
    private static byte[] exact(InputStream input, int count) throws IOException {
        byte[] result = new byte[count]; int offset = 0;
        while (offset < count) { int read = input.read(result, offset, count - offset); if (read < 0) throw new EOFException(); if (read == 0) throw new IOException("empty_read"); offset += read; }
        return result;
    }
    private static int octet(InputStream input) throws IOException { int value = input.read(); if (value < 0) throw new EOFException(); return value; }
    private static InetAddress loopback() throws IOException { return InetAddress.getByAddress(LOOPBACK); }
    private static void closeSocket(Socket socket) { if (socket != null) try { socket.close(); } catch (IOException ignored) { } }
    private static ThreadFactory daemon(String name) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> { Thread thread = new Thread(task, name + "-" + sequence.incrementAndGet()); thread.setDaemon(true); return thread; };
    }
    private static ExecutorService pool(int capacity, String name) {
        return new ThreadPoolExecutor(0, capacity, 30, TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>(), daemon(name), new ThreadPoolExecutor.AbortPolicy());
    }
}
