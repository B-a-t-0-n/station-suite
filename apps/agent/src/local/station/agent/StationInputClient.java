package local.station.agent;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Fixed loopback finite-key protocol. No HTTP, caller endpoint, shell or PC fallback. */
final class StationInputClient {
    private StationInputClient() { }
    static JSONObject key(String key) {
        if (StationInputHelper.arguments(key) == null) return unavailable("invalid_key");
        return exchange(StationInputHelper.PREFIX + "KEY " + key);
    }
    static JSONObject status() { return exchange(StationInputHelper.PREFIX + "STATUS"); }
    private static JSONObject exchange(String request) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", StationInputHelper.PORT), 300); socket.setSoTimeout(1900);
            socket.getOutputStream().write((request + "\n").getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
            ByteArrayOutputStream reply = new ByteArrayOutputStream(); boolean complete = false;
            long deadline = System.nanoTime() + 1900000000L;
            while (reply.size() < 1024) {
                socket.setSoTimeout(Math.max(1, (int)Math.min(1900, (deadline - System.nanoTime()) / 1000000)));
                if (System.nanoTime() >= deadline) break;
                int value = socket.getInputStream().read(); if (value < 0) break; if (value == '\n') { complete = true; break; } reply.write(value);
            }
            if (!complete) return unavailable("input_unavailable");
            JSONObject response = StationDisplaySettings.parseObject(new String(reply.toByteArray(), StandardCharsets.UTF_8));
            if (!"station_input".equals(response.opt("scope")) || !(response.opt("ok") instanceof Boolean)) return unavailable("input_unavailable");
            return response;
        } catch (Exception unavailable) { return unavailable("input_unavailable"); }
    }
    private static JSONObject unavailable(String code) {
        try { return new JSONObject().put("ok", false).put("scope", "station_input").put("ready", false).put("code", code); }
        catch (Exception impossible) { return new JSONObject(); }
    }
}
