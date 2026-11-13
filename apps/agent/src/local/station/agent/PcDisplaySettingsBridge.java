package local.station.agent;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** One fixed on-device bridge. Historical class name retained; no PC dependency. */
final class PcDisplaySettingsBridge implements StationDisplaySettings.Bridge {
    static final String ENDPOINT = "http://127.0.0.1:17685/api/display";
    interface ConnectionFactory { HttpURLConnection open(URL url, Proxy proxy) throws Exception; }
    private final ConnectionFactory connections;
    PcDisplaySettingsBridge() { this((url, proxy) -> (HttpURLConnection) url.openConnection(proxy)); }
    PcDisplaySettingsBridge(ConnectionFactory connections) { this.connections = connections; }
    @Override public JSONObject exchange(JSONObject request, long deadline) throws Exception {
        HttpURLConnection connection = connections.open(new URL(ENDPOINT), Proxy.NO_PROXY);
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(NativeDisplaySettings.remaining(deadline, 600));
            connection.setReadTimeout(NativeDisplaySettings.remaining(deadline, 600));
            connection.setRequestProperty("Accept", "application/json");
            if (!"get".equals(request.getString("op"))) {
                byte[] payload = request.toString().getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(payload.length);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(payload); }
            }
            if (connection.getResponseCode() != 200) throw new IllegalArgumentException("bridge_status");
            if (connection.getContentLength() > 4096) throw new IllegalArgumentException("bridge_bound");
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[512];
            try (InputStream input = connection.getInputStream()) {
                while (true) {
                    connection.setReadTimeout(NativeDisplaySettings.remaining(deadline, 600));
                    int n = input.read(buffer);
                    if (n < 0) break;
                    if (output.size() + n > 4096) throw new IllegalArgumentException("bridge_bound");
                    output.write(buffer, 0, n);
                }
            }
            return StationDisplaySettings.parseObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
        } finally { connection.disconnect(); }
    }
}
