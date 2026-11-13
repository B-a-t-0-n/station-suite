package local.station.agent;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/** Separate shell-UID app_process entry point; never started by the Android app. */
public final class StationInputHelper {
    static final int PORT = 17687, MAX_REQUEST = 128;
    static final String PREFIX = "STATION-INPUT/1 ";
    interface Executor { boolean execute(String[] arguments, int timeoutMs) throws Exception; }
    interface Clock { long millis(); }
    private final Executor executor;
    private final Clock clock;
    private final int port;
    private final ReentrantLock keyOwner = new ReentrantLock();
    private final AtomicLong accepted = new AtomicLong(), rejected = new AtomicLong();
    private final Set<Socket> clients = new HashSet<>();
    private final CountDownLatch ended = new CountDownLatch(1);
    private volatile boolean running;
    private ServerSocket listener;
    private ThreadPoolExecutor workers;
    private ScheduledThreadPoolExecutor deadlines;
    private long window = -1; private int inWindow;

    StationInputHelper(int port, Executor executor, Clock clock) {
        if (port < 0 || port > 65535 || executor == null || clock == null) throw new IllegalArgumentException("input_config");
        this.port = port; this.executor = executor; this.clock = clock;
    }
    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 2000 || args.length != 1 || !("--daemon".equals(args[0]) || "--ping".equals(args[0])))
            throw new IllegalStateException("station_input_shell_required");
        if ("--ping".equals(args[0])) { System.out.println(StationInputClient.status().toString()); return; }
        StationInputHelper server = new StationInputHelper(PORT, StationInputHelper::runFixed, () -> System.nanoTime() / 1000000L);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "StationInputShutdown"));
        server.start(); server.ended.await();
    }
    synchronized void start() throws Exception {
        if (running) return;
        ServerSocket server = new ServerSocket(); server.setReuseAddress(false);
        try { server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 3); }
        catch (Exception failure) { server.close(); throw new IllegalStateException("station_input_bind_failed"); }
        workers = new ThreadPoolExecutor(3, 3, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<Runnable>(),
                action -> { Thread thread = new Thread(action, "StationInputClient"); thread.setDaemon(true); return thread; }, new ThreadPoolExecutor.AbortPolicy());
        deadlines = new ScheduledThreadPoolExecutor(1, action -> { Thread thread = new Thread(action, "StationInputDeadline"); thread.setDaemon(true); return thread; });
        deadlines.setRemoveOnCancelPolicy(true); listener = server; running = true;
        Thread accept = new Thread(() -> accept(server), "StationInputAccept"); accept.setDaemon(true); accept.start();
    }
    synchronized int boundPort() { return listener == null ? -1 : listener.getLocalPort(); }
    synchronized void stop() {
        running = false; close(listener); listener = null;
        synchronized (clients) { for (Socket socket : clients) close(socket); clients.clear(); }
        if (workers != null) workers.shutdownNow(); if (deadlines != null) deadlines.shutdownNow(); ended.countDown();
    }
    private void accept(ServerSocket server) {
        while (running && !server.isClosed()) {
            Socket socket = null;
            try {
                socket = server.accept(); if (!socket.getInetAddress().isLoopbackAddress()) { close(socket); continue; }
                socket.setSoTimeout(300); final Socket client = socket;
                synchronized (clients) { if (!running) { close(client); break; } clients.add(client); }
                workers.execute(() -> handle(client));
            } catch (Exception unavailable) { if (socket != null) { synchronized (clients) { clients.remove(socket); } close(socket); } }
        }
    }
    private void handle(Socket socket) {
        java.util.concurrent.ScheduledFuture<?> deadline = null;
        try {
            deadline = deadlines.schedule(() -> close(socket), 2200, TimeUnit.MILLISECONDS);
            ByteArrayOutputStream input = new ByteArrayOutputStream(); InputStream stream = socket.getInputStream();
            boolean complete = false;
            while (input.size() < MAX_REQUEST) {
                int value = stream.read(); if (value < 0) break;
                if (value == '\n') { complete = true; break; }
                if (value < 32 || value > 126) throw new IllegalArgumentException("input_request"); input.write(value);
            }
            if (!complete) throw new IllegalArgumentException("input_request");
            JSONObject response = command(new String(input.toByteArray(), StandardCharsets.US_ASCII));
            socket.getOutputStream().write((response.toString() + "\n").getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().flush();
        } catch (Exception unavailable) { rejected.incrementAndGet(); }
        finally { if (deadline != null) deadline.cancel(false); close(socket); synchronized (clients) { clients.remove(socket); } }
    }
    JSONObject command(String request) throws Exception {
        if ((PREFIX + "PING").equals(request) || (PREFIX + "STATUS").equals(request))
            return new JSONObject().put("ok", true).put("scope", "station_input").put("ready", running)
                    .put("protocol", 1).put("keysAccepted", accepted.get()).put("keysRejected", rejected.get());
        String key = parseKey(request); String[] arguments = arguments(key);
        if (arguments == null) return failure("input_request");
        if (!keyOwner.tryLock()) return failure("input_busy");
        try {
            if (!admit()) return failure("input_rate_limited");
            if (!executor.execute(arguments, 1500)) return failure("input_execution_failed");
            accepted.incrementAndGet(); return new JSONObject().put("ok", true).put("scope", "station_input").put("code", "sent");
        } catch (Exception unavailable) { return failure("input_execution_failed"); }
        finally { keyOwner.unlock(); }
    }
    private synchronized boolean admit() {
        long now = clock.millis(); if (window < 0 || now < window || now - window >= 1000) { window = now; inWindow = 0; }
        return ++inWindow <= 10;
    }
    private JSONObject failure(String code) throws Exception { rejected.incrementAndGet(); return new JSONObject().put("ok", false).put("scope", "station_input").put("code", code); }
    static String parseKey(String request) {
        String prefix = PREFIX + "KEY ";
        return request != null && request.length() <= MAX_REQUEST && request.startsWith(prefix) ? request.substring(prefix.length()) : null;
    }
    static String[] arguments(String key) {
        if (key == null) return null; String code;
        switch (key) {
            case "up": code = "KEYCODE_DPAD_UP"; break; case "down": code = "KEYCODE_DPAD_DOWN"; break;
            case "left": code = "KEYCODE_DPAD_LEFT"; break; case "right": code = "KEYCODE_DPAD_RIGHT"; break;
            case "ok": case "center": code = "KEYCODE_DPAD_CENTER"; break; case "back": code = "KEYCODE_BACK"; break;
            case "home": code = "KEYCODE_HOME"; break; case "menu": code = "KEYCODE_MENU"; break;
            case "wake": code = "KEYCODE_WAKEUP"; break; case "volume_up": code = "KEYCODE_VOLUME_UP"; break;
            case "volume_down": code = "KEYCODE_VOLUME_DOWN"; break; case "mute": code = "KEYCODE_VOLUME_MUTE"; break;
            case "play": code = "KEYCODE_MEDIA_PLAY"; break; case "pause": code = "KEYCODE_MEDIA_PAUSE"; break;
            case "play_pause": code = "KEYCODE_MEDIA_PLAY_PAUSE"; break; case "next": code = "KEYCODE_MEDIA_NEXT"; break;
            case "previous": code = "KEYCODE_MEDIA_PREVIOUS"; break; case "stop": code = "KEYCODE_MEDIA_STOP"; break;
            case "settings": return new String[]{"/system/bin/am", "start", "-n", "com.android.tv.settings/.MainSettings"};
            case "apps": return new String[]{"/system/bin/am", "start", "-n", "com.android.tv.settings/.device.apps.AllAppsActivity"};
            default: return null;
        }
        return new String[]{"/system/bin/input", "keyevent", code};
    }
    private static boolean runFixed(String[] arguments, int timeoutMs) throws Exception {
        Process process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) || process.exitValue() != 0) return false;
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] block = new byte[256]; int count;
            try (InputStream stream = process.getInputStream()) { while ((count = stream.read(block)) >= 0) {
                if (output.size() + count > 1024) return false; output.write(block, 0, count);
            } }
            String fixedResult = new String(output.toByteArray(), StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);
            return !(fixedResult.contains("error:") || fixedResult.contains("exception") || fixedResult.contains("permission denial"));
        }
        finally { process.destroy(); if (process.isAlive()) process.destroyForcibly(); }
    }
    private static void close(java.io.Closeable item) { if (item != null) try { item.close(); } catch (Exception ignored) { } }
}
