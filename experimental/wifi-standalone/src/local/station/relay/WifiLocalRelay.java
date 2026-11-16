package local.station.relay;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/** Experimental long-lived local-only SOCKS relay. No WAN backend or fallback. */
public final class WifiLocalRelay implements AutoCloseable {
    static final int LISTEN_PORT = 17694, MAX_CONNECTIONS = 8, FLOW_BYTES = 1024 * 1024;
    private final byte[] destination;
    private final long deadline;
    private final int listenPort, configPort, pushPort, httpPort;
    private final Set<Socket> sockets = Collections.synchronizedSet(new HashSet<Socket>());
    private final Set<Thread> handlers = Collections.synchronizedSet(new HashSet<Thread>());
    private final Semaphore permits = new Semaphore(MAX_CONNECTIONS);
    private final AtomicInteger accepted = new AtomicInteger(), denied = new AtomicInteger(), failures = new AtomicInteger();
    private final AtomicInteger http = new AtomicInteger(), config = new AtomicInteger(), push = new AtomicInteger();
    private final AtomicInteger upstreamBytes = new AtomicInteger(), downstreamBytes = new AtomicInteger();
    private final AtomicInteger unfinishedUploads = new AtomicInteger();
    private volatile boolean closed;
    private ServerSocket listener;

    public WifiLocalRelay(String configIp, int durationMs) {
        this(configIp, durationMs, LISTEN_PORT, 17683, 17684, 17685);
    }

    /** Package-private host-test seam: all endpoints remain numeric loopback. */
    WifiLocalRelay(String configIp, int durationMs, int listenPort, int configPort, int pushPort, int httpPort) {
        destination = RelayProtocol.privateIpv4(configIp);
        if (durationMs < 0 || durationMs > 3600000) throw new IllegalArgumentException("bounded_duration_required");
        for (int port : new int[]{listenPort, configPort, pushPort, httpPort})
            if (port < 0 || port > 65535) throw new IllegalArgumentException("invalid_loopback_port");
        this.listenPort = listenPort; this.configPort = configPort; this.pushPort = pushPort; this.httpPort = httpPort;
        deadline = durationMs == 0 ? Long.MAX_VALUE : System.nanoTime() + durationMs * 1000000L;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !args[0].equals("--serve")) throw new IllegalArgumentException("serve_config_ip_duration_required");
        try (WifiLocalRelay server = new WifiLocalRelay(args[1], Integer.parseInt(args[2]))) {
            server.run();
            System.out.println(server.summary());
        }
    }

    public void run() throws IOException {
        synchronized (this) {
            if (closed || listener != null) throw new IllegalStateException("single_start_only");
            listener = new ServerSocket();
            // Permit restarting after our own closed connections entered TIME_WAIT.
            // SO_REUSEPORT is never set: a second live listener still cannot bind.
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), listenPort), MAX_CONNECTIONS);
        }
        Thread expiry = new Thread(() -> {
            while (!closed) {
                long left = deadline - System.nanoTime();
                if (left <= 0) { close(); return; }
                try { Thread.sleep(Math.min(100, Math.max(1, left / 1000000L))); }
                catch (InterruptedException stop) { close(); return; }
            }
        }, "relay-expiry");
        expiry.setDaemon(true); expiry.start();
        System.out.println("LOCAL_RELAY_READY"); System.out.flush();
        try {
            while (!closed) {
                Socket client = listener.accept();
                if (!permits.tryAcquire()) { denied.incrementAndGet(); client.close(); continue; }
                if (!track(client)) { permits.release(); continue; }
                accepted.incrementAndGet();
                Thread handler = new Thread(() -> {
                    try { handle(client); }
                    catch (IOException problem) { if (!closed) failures.incrementAndGet(); }
                    finally { release(client); permits.release(); handlers.remove(Thread.currentThread()); }
                }, "local-relay-flow");
                handler.setDaemon(true); handlers.add(handler); handler.start();
            }
        } catch (IOException stop) { if (!closed) throw stop; }
        finally {
            close();
            Thread[] remaining;
            synchronized (handlers) { remaining = handlers.toArray(new Thread[0]); }
            for (Thread thread : remaining) try { thread.join(750); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
        }
    }

    private boolean track(Socket socket) throws IOException {
        synchronized (sockets) {
            if (closed) { socket.close(); return false; }
            socket.setSoTimeout(1500); sockets.add(socket); return true;
        }
    }

    synchronized int boundListenPort() { return listener == null ? -1 : listener.getLocalPort(); }
    public synchronized boolean isReady() { return !closed && listener != null && listener.isBound() && !listener.isClosed(); }

    private void release(Socket socket) {
        sockets.remove(socket);
        try { socket.close(); } catch (IOException ignored) { }
    }

    private void reject(OutputStream output, int code) throws IOException {
        denied.incrementAndGet();
        ensureActive();
        output.write(new byte[]{5, (byte) code, 0, 1, 127, 0, 0, 1, 0, 0}); output.flush();
    }

    private void handle(Socket client) throws IOException {
        InputStream input = client.getInputStream(); OutputStream output = client.getOutputStream();
        if (RelayProtocol.read(input) != 5) { denied.incrementAndGet(); return; }
        int count = RelayProtocol.read(input);
        if (count < 1 || count > 16) { denied.incrementAndGet(); return; }
        byte[] methods = RelayProtocol.exact(input, count);
        boolean noAuth = false; for (byte method : methods) if (method == 0) noAuth = true;
        ensureActive();
        output.write(new byte[]{5, (byte) (noAuth ? 0 : 255)}); output.flush();
        if (!noAuth) { denied.incrementAndGet(); return; }
        byte[] request = RelayProtocol.exact(input, 4);
        if (request[0] != 5 || request[2] != 0) { reject(output, 1); return; }
        if (request[1] != 1) { reject(output, 7); return; }
        if (request[3] != 1) { reject(output, 8); return; }
        byte[] address = RelayProtocol.exact(input, 4);
        int port = RelayProtocol.read(input) * 256 + RelayProtocol.read(input);
        if (!Arrays.equals(address, destination) || port != 80 && port != 443) { reject(output, 2); return; }
        // Deferred local backend selection: TLS/HTTP begins only after the SOCKS reply.
        ensureActive();
        output.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 0}); output.flush();
        RelayProtocol.FirstFlight first;
        try { first = port == 80 ? RelayProtocol.http(input) : RelayProtocol.tls(input); }
        catch (IOException invalid) { denied.incrementAndGet(); return; }
        Socket backend = new Socket();
        if (!track(backend)) return;
        Thread upload = null;
        try {
            // The only outbound address is numeric loopback. DNS is never consulted.
            ensureActive();
            int localPort = first.backendPort == 17683 ? configPort : first.backendPort == 17684 ? pushPort : httpPort;
            backend.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), localPort), 750);
            ensureActive();
            backend.getOutputStream().write(first.bytes); backend.getOutputStream().flush();
            upstreamBytes.addAndGet(first.bytes.length);
            if (port == 80) {
                http.incrementAndGet();
                // Connectivity endpoints accept exactly one no-body request.
                transfer(backend.getInputStream(), output, 65536, downstreamBytes);
                return;
            }
            if (first.backendPort == 17684) push.incrementAndGet(); else config.incrementAndGet();
            if (first.backendPort == 17684) {
                // Push is intentionally idle between messages; the absolute relay deadline still applies.
                backend.setSoTimeout(240000); client.setSoTimeout(240000);
            }
            upload = new Thread(() -> {
                try { transfer(input, socketOutput(backend), FLOW_BYTES - first.bytes.length, upstreamBytes); }
                catch (IOException stop) { }
                finally { try { backend.shutdownOutput(); } catch (IOException ignored) { } }
            }, "local-relay-upload");
            upload.setDaemon(true); upload.start();
            transfer(backend.getInputStream(), output, FLOW_BYTES, downstreamBytes);
        } finally {
            release(backend); release(client);
            if (upload != null) {
                try { upload.join(500); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
                if (upload.isAlive()) unfinishedUploads.incrementAndGet();
            }
        }
    }

    private static OutputStream socketOutput(Socket socket) throws IOException { return socket.getOutputStream(); }

    private void ensureActive() throws IOException {
        if (closed || System.nanoTime() >= deadline) throw new IOException("relay_deadline");
    }

    private void transfer(InputStream input, OutputStream output, int limit, AtomicInteger counter) throws IOException {
        byte[] buffer = new byte[4096];
        while (limit > 0) {
            ensureActive();
            int count = input.read(buffer, 0, Math.min(buffer.length, limit));
            if (count < 0) return;
            if (count == 0) throw new IOException("empty_read");
            ensureActive();
            output.write(buffer, 0, count); output.flush();
            counter.addAndGet(count); limit -= count;
        }
        throw new IOException("flow_budget_exhausted");
    }

    public String summary() {
        return "LOCAL_RELAY_RESULT={\"accepted\":" + accepted + ",\"denied\":" + denied
                + ",\"ioFailures\":" + failures + ",\"http\":" + http + ",\"configTls\":" + config
                + ",\"pushTls\":" + push + ",\"upstreamBytes\":" + upstreamBytes
                + ",\"downstreamBytes\":" + downstreamBytes + ",\"closed\":" + closed
                + ",\"unfinishedUploads\":" + unfinishedUploads + ",\"activeHandlers\":" + handlers.size() + "}";
    }

    @Override public void close() {
        synchronized (sockets) {
            closed = true;
            for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
            sockets.clear();
        }
        synchronized (this) { if (listener != null) try { listener.close(); } catch (IOException ignored) { } }
    }
}
