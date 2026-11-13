package local.station.voicebridge;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/** Explicit lifecycle only; loopback TLS native replies and plain LED-only local API. */
final class NativeConfigServer {
    static final int TLS_PORT = 17683, HTTP_PORT = 17685, MAX_CLIENTS = 3;
    private static final long CONNECTION_MS = 6000;
    private final NativeConfigProtocol.Source source;
    private final SSLContext tls;
    private final int tlsPort, httpPort;
    private final Object lock = new Object();
    private final Set<Socket> clients = new HashSet<>();
    private final AtomicLong accepted = new AtomicLong(), responses = new AtomicLong(), rejected = new AtomicLong(), failed = new AtomicLong();
    private volatile String lastCode = "not_started";
    private volatile String lastSourceFailure = "";
    private volatile boolean running;
    private ServerSocket nativeListener, localListener;
    private ThreadPoolExecutor workers;
    private ScheduledThreadPoolExecutor deadlines;

    NativeConfigServer(NativeConfigProtocol.Source source, SSLContext tls) { this(source, tls, TLS_PORT, HTTP_PORT); }
    /** Ephemeral ports are only an isolated host-test seam; production uses the fixed constructor. */
    NativeConfigServer(NativeConfigProtocol.Source source, SSLContext tls, int tlsPort, int httpPort) {
        if (source == null || tls == null || tlsPort < 0 || tlsPort > 65535 || httpPort < 0 || httpPort > 65535) throw new IllegalArgumentException("invalid_local_config_server");
        this.source = source; this.tls = tls; this.tlsPort = tlsPort; this.httpPort = httpPort;
    }
    synchronized void start() throws Exception {
        if (running) return;
        ServerSocket secure = null, local = null;
        try {
            secure = tls.getServerSocketFactory().createServerSocket(); secure.setReuseAddress(true);
            SSLServerSocket ssl = (SSLServerSocket) secure;
            List<String> protocols = new ArrayList<>();
            for (String protocol : ssl.getSupportedProtocols()) if ("TLSv1.2".equals(protocol) || "TLSv1.3".equals(protocol)) protocols.add(protocol);
            if (protocols.isEmpty()) throw new IllegalStateException("native_config_tls_unavailable");
            ssl.setEnabledProtocols(protocols.toArray(new String[0]));
            secure.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), tlsPort), 3);
            local = new ServerSocket(); local.setReuseAddress(true);
            local.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), httpPort), 3);
            workers = new ThreadPoolExecutor(MAX_CLIENTS, MAX_CLIENTS, 0, TimeUnit.MILLISECONDS,
                    new SynchronousQueue<Runnable>(), daemon("StationConfigWorker"), new ThreadPoolExecutor.AbortPolicy());
            deadlines = new ScheduledThreadPoolExecutor(1, daemon("StationConfigDeadline")); deadlines.setRemoveOnCancelPolicy(true);
            nativeListener = secure; localListener = local; running = true; lastCode = "ready";
            accept(secure, true); accept(local, false);
        } catch (Exception unavailable) {
            close(secure); close(local); stop(); lastCode = "start_failed"; throw new IllegalStateException("native_config_start_failed");
        }
    }
    synchronized void stop() {
        running = false; close(nativeListener); close(localListener); nativeListener = null; localListener = null;
        synchronized (lock) { for (Socket socket : clients) close(socket); clients.clear(); }
        if (workers != null) workers.shutdownNow(); if (deadlines != null) deadlines.shutdownNow();
        workers = null; deadlines = null; lastCode = "stopped";
    }
    synchronized int boundHttpPort() { return localListener == null ? -1 : localListener.getLocalPort(); }
    synchronized int boundTlsPort() { return nativeListener == null ? -1 : nativeListener.getLocalPort(); }
    JSONObject status() throws Exception {
        int count; synchronized (lock) { count = clients.size(); }
        return new JSONObject().put("active", running).put("clients", count).put("accepted", accepted.get())
                .put("responses", responses.get()).put("rejected", rejected.get()).put("failed", failed.get()).put("lastCode", lastCode).put("lastSourceFailure",lastSourceFailure);
    }
    private void accept(ServerSocket server, boolean secure) {
        Thread thread = daemon("StationConfigAccept").newThread(() -> {
            while (running && !server.isClosed()) {
                Socket socket = null;
                try {
                    socket = server.accept(); accepted.incrementAndGet();
                    if (!socket.getInetAddress().isLoopbackAddress()) { rejected.incrementAndGet(); close(socket); continue; }
                    socket.setSoTimeout(2000);
                    final Socket client = socket;
                    synchronized (lock) { if (!running) { close(client); break; } clients.add(client); }
                    workers.execute(() -> serve(client, secure));
                } catch (Exception unavailable) {
                    if (socket != null) { synchronized (lock) { clients.remove(socket); } close(socket); rejected.incrementAndGet(); }
                    else if (running) { lastCode = "listener_failed"; failed.incrementAndGet(); break; }
                }
            }
        }); thread.start();
    }
    private void serve(Socket socket, boolean secure) {
        ScheduledFuture<?> deadline = null;
        try {
            ScheduledThreadPoolExecutor timer = deadlines;
            if (timer == null || !running) return;
            deadline = timer.schedule(() -> close(socket), CONNECTION_MS, TimeUnit.MILLISECONDS);
            String sni = null;
            if (secure) {
                SSLSocket ssl = (SSLSocket) socket; ssl.startHandshake();
                if (ssl.getSession() instanceof ExtendedSSLSession) {
                    List<SNIServerName> names = ((ExtendedSSLSession) ssl.getSession()).getRequestedServerNames();
                    if (names.size() == 1 && names.get(0) instanceof SNIHostName) sni = ((SNIHostName) names.get(0)).getAsciiName().toLowerCase(java.util.Locale.ROOT);
                }
            }
            InputStream input = socket.getInputStream(); OutputStream output = socket.getOutputStream();
            NativeConfigProtocol.Request request;
            try { request = NativeConfigProtocol.parseHead(head(input)); }
            catch (Exception invalid) { output.write(NativeConfigProtocol.empty(400, false).bytes()); output.flush(); rejected.incrementAndGet(); return; }
            byte[] body = new byte[request.contentLength]; int offset = 0;
            while (offset < body.length) { int count = input.read(body, offset, body.length - offset); if (count < 0) throw new IllegalArgumentException("native_config_body_incomplete"); offset += count; }
            NativeConfigProtocol.Source observed = new NativeConfigProtocol.Source() {
                public JSONObject sync() throws Exception { return source.sync(); }
                public JSONObject pushSubscribe() throws Exception { return source.pushSubscribe(); }
                public JSONObject display(JSONObject command) throws Exception {
                    try { return source.display(command); }
                    catch(Exception failure) { lastSourceFailure=failure.getClass().getSimpleName(); throw failure; }
                }
            };
            NativeConfigProtocol.Response result = NativeConfigProtocol.respond(request, body, secure, sni, observed);
            output.write(result.bytes()); output.flush(); responses.incrementAndGet();
            lastCode = result.status < 400 ? "served" : result.status == 503 ? "source_unavailable" : "request_rejected";
            if (result.status >= 400) rejected.incrementAndGet();
        } catch (Exception unavailable) { failed.incrementAndGet(); lastCode = "connection_failed"; }
        finally { if (deadline != null) deadline.cancel(false); close(socket); synchronized (lock) { clients.remove(socket); } }
    }
    private static byte[] head(InputStream input) throws Exception {
        ByteArrayOutputStream result = new ByteArrayOutputStream(); int ending = 0;
        while (result.size() < NativeConfigProtocol.MAX_HEAD) {
            int value = input.read(); if (value < 0) throw new IllegalArgumentException("native_config_head_incomplete"); result.write(value);
            if (value == (ending == 0 || ending == 2 ? '\r' : '\n')) { if (++ending == 4) return result.toByteArray(); }
            else ending = value == '\r' ? 1 : 0;
        }
        throw new IllegalArgumentException("native_config_head_bound");
    }
    /** Private material is provisioned out of band; this method never prints it. */
    static SSLContext tlsFromPrivateFile(File file, char[] password) throws Exception {
        if (file == null || !file.isFile() || file.length() < 1 || file.length() > 131072
                || !file.getCanonicalFile().equals(file.getAbsoluteFile()) || password == null) throw new IllegalArgumentException("native_config_tls_file");
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (FileInputStream input = new FileInputStream(file)) { keys.load(input, password); }
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); factory.init(keys, password);
        SSLContext context = SSLContext.getInstance("TLS"); context.init(factory.getKeyManagers(), null, new SecureRandom()); return context;
    }
    private static ThreadFactory daemon(String name) { return action -> { Thread thread = new Thread(action, name); thread.setDaemon(true); return thread; }; }
    private static void close(java.io.Closeable value) { if (value != null) try { value.close(); } catch (Exception ignored) { } }
}
