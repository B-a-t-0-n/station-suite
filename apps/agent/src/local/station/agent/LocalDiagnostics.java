package local.station.agent;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Read-only, fixed local probes; responses expose only selected booleans and counters. */
final class LocalDiagnostics {
    static final String VERSION = "0.7.6";
    private LocalDiagnostics() { }
    static JSONObject snapshot(JSONObject own, JSONObject input) throws Exception {
        JSONObject agent = agent(own), services = new JSONObject().put("agent", true)
                .put("input", Boolean.TRUE.equals(input.opt("ok")) && Boolean.TRUE.equals(input.opt("ready")))
                .put("display", false).put("music", false);
        try {
            JSONObject display = new PcDisplaySettingsBridge().exchange(new JSONObject().put("op", "get"), System.nanoTime() + 500000000L);
            StationDisplaySettings.validateBridge(display); services.put("display", true);
        } catch (Exception unavailable) { }
        try {
            JSONObject music = musicStatus(); services.put("music", Boolean.TRUE.equals(music.opt("running")) && Boolean.TRUE.equals(music.opt("configured")));
        } catch (Exception unavailable) { }
        JSONObject timers = own.optJSONObject("scheduleRuntime");
        services.put("timers", timers != null && Boolean.TRUE.equals(timers.opt("ready")));
        return new JSONObject().put("ok", true).put("scope", "station_local").put("agent", agent)
                .put("services", services).put("updatedAtMs", System.currentTimeMillis());
    }
    static JSONObject agent(JSONObject own) throws Exception {
        return new JSONObject().put("version", VERSION).put("playing", own.optBoolean("playing", false))
                .put("prepared", own.optBoolean("prepared", false)).put("volume", Math.max(0, Math.min(100, own.optInt("volume", 0))));
    }
    private static JSONObject musicStatus() throws Exception {
        HttpURLConnection connection = (HttpURLConnection)new URL("http://127.0.0.1:17686/status").openConnection(Proxy.NO_PROXY);
        long deadline = System.nanoTime() + 500000000L;
        try {
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false); connection.setConnectTimeout(250); connection.setReadTimeout(250);
            if (connection.getResponseCode() != 200 || connection.getContentLength() > 1024) throw new IllegalArgumentException("local_status");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream stream = connection.getInputStream()) {
                byte[] buffer = new byte[256]; int count;
                while ((count = stream.read(buffer)) >= 0) {
                    if (bytes.size() + count > 1024 || System.nanoTime() >= deadline) throw new IllegalArgumentException("local_status");
                    bytes.write(buffer, 0, count);
                    connection.setReadTimeout(Math.max(1, (int)Math.min(250, (deadline - System.nanoTime()) / 1000000)));
                }
            }
            return StationDisplaySettings.parseObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        } finally { connection.disconnect(); }
    }
}
