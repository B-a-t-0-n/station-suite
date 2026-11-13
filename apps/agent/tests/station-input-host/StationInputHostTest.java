package local.station.agent;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class StationInputHostTest {
    private static int assertions;
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static JSONObject exchange(int port, String request) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000); socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
            ByteArrayOutputStream response = new ByteArrayOutputStream(); int value;
            while ((value = socket.getInputStream().read()) >= 0 && value != '\n') { response.write(value); check(response.size() <= 1024, "bounded response"); }
            return response.size() == 0 ? null : new JSONObject(new String(response.toByteArray(), StandardCharsets.UTF_8));
        }
    }
    public static void main(String[] args) throws Exception {
        AtomicInteger runs = new AtomicInteger(); long[] clock = {0};
        StationInputHelper server = new StationInputHelper(0, (arguments, timeout) -> {
            check(timeout == 1500, "fixed execution timeout");
            check(arguments[0].equals("/system/bin/input") || arguments[0].equals("/system/bin/am"), "only fixed platform executables");
            check(!Arrays.asList(arguments).contains("sh") && !Arrays.asList(arguments).contains("-c"), "never invokes a shell"); runs.incrementAndGet(); return true;
        }, () -> clock[0]);
        check(!server.command(StationInputHelper.PREFIX + "PING").getBoolean("ready") && runs.get() == 0, "passive constructor and PING");
        for (String key : new String[]{"up","down","left","right","ok","center","back","home","menu","wake","volume_up","volume_down","mute","play","pause","play_pause","next","previous","stop","settings","apps"}) {
            clock[0] += 1001;
            check(server.command(StationInputHelper.PREFIX + "KEY " + key).getBoolean("ok"), "known finite key");
            String[] first = StationInputHelper.arguments(key); first[0] = "MUTATED";
            check(!StationInputHelper.arguments(key)[0].equals("MUTATED"), "argv detached");
        }
        int before = runs.get();
        for (String request : new String[]{"KEY up", "STATION-INPUT/1 KEY up;reboot", "STATION-INPUT/1 KEY UP", "STATION-INPUT/1 KEY 19",
                "STATION-INPUT/1 KEY up 9999", "STATION-INPUT/1 KEY up\nKEY home", "GET / HTTP/1.1", "POST / HTTP/1.1", "STATION-INPUT/1 KEY http://outside", "STATION-INPUT/1 KEY "})
            check(!server.command(request).getBoolean("ok"), "unsupported request denied");
        check(runs.get() == before, "invalid request never invokes executor");
        clock[0] += 1001;
        for (int i=0;i<10;i++) check(server.command(StationInputHelper.PREFIX + "KEY up").getBoolean("ok"), "ten keys per window");
        check(server.command(StationInputHelper.PREFIX + "KEY up").getString("code").equals("input_rate_limited"), "rate cap");
        check(server.command(StationInputHelper.PREFIX + "PING").getBoolean("ok"), "PING does not consume rate budget");
        clock[0] += 1001; check(server.command(StationInputHelper.PREFIX + "KEY up").getBoolean("ok"), "rate budget replenishes");
        server.start(); int port = server.boundPort(); before = runs.get();
        try {
            JSONObject ping = exchange(port, StationInputHelper.PREFIX + "PING\n");
            check(ping.getBoolean("ok") && ping.getBoolean("ready") && ping.getInt("protocol") == 1 && runs.get() == before, "live PING never presses keys");
            check(!exchange(port, "GET / HTTP/1.1\n").getBoolean("ok") && runs.get() == before, "browser HTTP cannot send keys");
            check(exchange(port, new String(new char[129]).replace('\0','A') + "\n") == null && runs.get() == before, "oversized request closed without execution");
            check(exchange(port, "STATION-INPUT/1 KEY up\r\n") == null && runs.get() == before, "control bytes denied before execution");
            Socket idle = new Socket("127.0.0.1", port); idle.setSoTimeout(1000); Thread.sleep(30); server.stop();
            check(idle.getInputStream().read() == -1, "stop closes idle client"); idle.close();
        } finally { server.stop(); }
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        StationInputHelper busy = new StationInputHelper(0, (arguments, timeout) -> { entered.countDown(); release.await(1, TimeUnit.SECONDS); return true; }, () -> 0);
        Thread owner = new Thread(() -> { try { busy.command(StationInputHelper.PREFIX + "KEY up"); } catch (Exception failed) { throw new AssertionError(failed); } }); owner.start();
        check(entered.await(1, TimeUnit.SECONDS), "executor entered");
        check(busy.command(StationInputHelper.PREFIX + "KEY down").getString("code").equals("input_busy"), "overlapping key cannot queue late");
        release.countDown(); owner.join(1500); check(!owner.isAlive(), "executor released"); busy.stop();
        JSONObject clean = LocalDiagnostics.agent(new JSONObject().put("url", "SECRET_URL").put("path", "SECRET_PATH").put("token", "SECRET_TOKEN")
                .put("version", "SECRET_VERSION").put("volume", 1000));
        check(clean.length() == 4 && clean.getInt("volume") == 100 && !clean.toString().contains("SECRET"), "diagnostics whitelist excludes URLs paths and secrets");
        check(StationInputClient.key("up;reboot").getString("code").equals("invalid_key"), "client validates before socket");
        System.out.println(assertions + " assertions passed; fake executor only, no device keys");
    }
}
