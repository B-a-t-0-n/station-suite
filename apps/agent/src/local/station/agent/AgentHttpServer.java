package local.station.agent;

import android.content.Context;
import android.util.Log;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Tiny same-origin web server for the Station agent. */
final class AgentHttpServer implements Runnable {
    private static final String TAG = "StationAgent";
    private static final int PORT = 18080;
    private static final int MAX_REQUEST_LINE = 32768, MAX_SCHEDULE_REQUEST = 4096;
    private final AgentService service;
    private final VoiceHistoryGateway voiceHistory;
    private final byte[] html;
    private final byte[] scheduleGif;
    private final byte[] scheduleClearGif;
    private volatile boolean running = true;
    private ServerSocket listener;

    AgentHttpServer(Context context, AgentService service) {
        this(context, service, new VoiceHistoryGateway());
    }

    AgentHttpServer(Context context, AgentService service, VoiceHistoryGateway voiceHistory) {
        this.service = service;
        this.voiceHistory = voiceHistory;
        byte[] page = new byte[0];
        try (InputStream input = context.getAssets().open("index.html")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
            page = output.toByteArray();
        } catch (Exception error) {
            Log.e(TAG, "assets", error);
        }
        html = page;
        byte[] gif = new byte[0];
        try (InputStream input = context.getAssets().open("schedule-alert.gif")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048]; int count;
            while ((count = input.read(buffer)) > 0 && output.size() + count <= 32768) output.write(buffer, 0, count);
            gif = output.toByteArray();
        } catch (Exception error) { Log.w(TAG, "offline display asset unavailable"); }
        scheduleGif = gif;
        byte[] clear = new byte[0];
        try (InputStream input = context.getAssets().open("schedule-clear.gif")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int count;
            while ((count = input.read(buffer)) > 0 && output.size() + count <= 4096) output.write(buffer, 0, count);
            clear = output.toByteArray();
        } catch (Exception error) { Log.w(TAG, "offline display clear unavailable"); }
        scheduleClearGif = clear;
    }

    void start() {
        Thread thread = new Thread(this, "station-agent-http");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        try { if (listener != null) listener.close(); } catch (Exception ignored) { }
    }

    @Override public void run() {
        try {
            listener = new ServerSocket(PORT, 50, InetAddress.getByName("0.0.0.0"));
            Log.i(TAG, "web server on :" + PORT);
            while (running) {
                Socket socket = listener.accept();
                Thread worker = new Thread(() -> handle(socket), "station-agent-request");
                worker.setDaemon(true);
                worker.start();
            }
        } catch (Exception error) {
            if (running) Log.e(TAG, "server", error);
        }
    }

    private void handle(Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(120000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            String request = readRequestLine(reader);
            if (request == null) return;
            if (request.length() > MAX_REQUEST_LINE) {
                respondJson(client, 413, "{\"ok\":false,\"code\":\"request_too_large\"}"); return;
            }
            String[] parts = request.split(" ");
            if (parts.length < 2) { respond(client, 400, "text/plain", "bad request"); return; }
            if (VoiceHistoryGateway.matches(parts[1])) {
                VoiceHistoryGateway.Response response = parts.length == 3 && parts[2].matches("HTTP/1\\.[01]")
                        ? voiceHistory.handle(parts[0], parts[1], client, reader)
                        : new VoiceHistoryGateway.Response(400, "{\"ok\":false,\"code\":\"invalid_request\"}");
                respondBytes(client, response.status, "application/json; charset=utf-8",
                        response.json.getBytes(StandardCharsets.UTF_8), false);
                return;
            }
            String displayLength = null;
            boolean displayBadHeaders = false;
            while (true) {
                String line = reader.readLine();
                if (line == null || line.isEmpty()) break;
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String name = line.substring(0, colon).trim();
                    if ("Content-Length".equalsIgnoreCase(name)) {
                        if (displayLength != null) displayBadHeaders = true;
                        displayLength = line.substring(colon + 1).trim();
                    }
                    if ("Transfer-Encoding".equalsIgnoreCase(name)) displayBadHeaders = true;
                }
            }
            URI uri = URI.create(parts[1]);
            String path = uri.getPath();
            Map<String, String> query = parseQuery(uri.getRawQuery());

            if ("/api/display".equals(path)) {
                if (!client.getInetAddress().isLoopbackAddress()) {
                    respondJson(client, 403, "{\"ok\":false,\"code\":\"loopback_required\"}"); return;
                }
                if (!"GET".equals(parts[0]) && !"POST".equals(parts[0])) {
                    respondJson(client, 405, "{\"ok\":false,\"code\":\"method_not_allowed\"}"); return;
                }
                JSONObject command;
                try {
                    if (displayBadHeaders) throw new IllegalArgumentException();
                    if ("GET".equals(parts[0])) {
                        if (uri.getRawQuery() != null || displayLength != null && !"0".equals(displayLength))
                            throw new IllegalArgumentException();
                        command = new JSONObject().put("op", "get");
                    } else if (uri.getRawQuery() != null) {
                        if (displayLength != null && !"0".equals(displayLength)) throw new IllegalArgumentException();
                        command = scheduleRequest("POST", uri.getRawQuery());
                    } else {
                        if (displayLength == null || !displayLength.matches("[0-9]{1,4}")) throw new IllegalArgumentException();
                        int length = Integer.parseInt(displayLength);
                        if (length < 1 || length > 512) throw new IllegalArgumentException();
                        client.setSoTimeout(1500);
                        char[] body = new char[length]; int offset = 0;
                        while (offset < length) {
                            int count = reader.read(body, offset, length - offset);
                            if (count < 1) throw new IllegalArgumentException();
                            offset += count;
                        }
                        // DISPLAY requests contain only ASCII op names, enum IDs, booleans and numbers.
                        for (char value : body) if (value > 127) throw new IllegalArgumentException();
                        command = StationDisplaySettings.parseObject(new String(body));
                    }
                } catch (Exception invalid) {
                    respondJson(client, 400, StationDisplaySettings.unavailable("invalid_display_request").toString()); return;
                }
                respondJson(client, 200, service.displayCommand(command).toString());
            } else if ("/api/schedules".equals(path) || "/api/equalizer".equals(path)) {
                if (!client.getInetAddress().isLoopbackAddress()) {
                    respondJson(client, 403, "{\"ok\":false,\"code\":\"loopback_required\"}"); return;
                }
                if (!"GET".equals(parts[0]) && !"POST".equals(parts[0])) {
                    respondJson(client, 405, "{\"ok\":false,\"code\":\"method_not_allowed\"}"); return;
                }
                JSONObject command;
                try { command = scheduleRequest(parts[0], uri.getRawQuery()); }
                catch (Exception ignored) {
                    respondJson(client, 400, "{\"ok\":false,\"code\":\"invalid_request\"}"); return;
                }
                boolean equalizer = "/api/equalizer".equals(path);
                if (equalizer && "GET".equals(parts[0])) command.put("op", "get");
                respondJson(client, 200, (equalizer ? service.equalizerCommand(command)
                        : service.scheduleCommand(command)).toString());
            } else if ("/led/schedule-alert.gif".equals(path) || "/led/schedule-clear.gif".equals(path)
                    || "/led/schedule-timer.gif".equals(path)) {
                byte[] gif = path.endsWith("schedule-timer.gif") ? service.scheduleTimerGif()
                        : path.endsWith("schedule-clear.gif") ? scheduleClearGif : scheduleGif;
                if (!client.getInetAddress().isLoopbackAddress() || !"GET".equals(parts[0]) || gif.length == 0) {
                    respond(client, 404, "text/plain", "not found"); return;
                }
                respondBytes(client, 200, "image/gif", gif);
                service.scheduleDisplayAssetServed();
            } else if ("/".equals(path) || "/index.html".equals(path)) {
                respond(client, 200, "text/html; charset=utf-8", new String(html, StandardCharsets.UTF_8));
            } else if ("/api/status".equals(path)) {
                respondJson(client, 200, service.status().toString());
            } else if ("/api/hardware".equals(path)) {
                if (!"GET".equals(parts[0])) {
                    respondJson(client, 405, "{\"ok\":false,\"code\":\"method_not_allowed\"}"); return;
                }
                respondJson(client, 200, service.hardwareStatus());
            } else if ("/api/local/status".equals(path)) {
                if (!"GET".equals(parts[0])) { respondJson(client, 405, "{\"ok\":false,\"code\":\"method_not_allowed\"}"); return; }
                respondJson(client, 200, service.localStatus());
            } else if ("/api/volume".equals(path)) {
                int value;
                if (query.containsKey("value")) value = service.setVolumePercent(parseInt(query.get("value"), 50));
                else value = service.adjustVolume(parseInt(query.get("delta"), 10));
                respondJson(client, 200, "{\"ok\":true,\"volume\":" + value + "}");
            } else if ("/api/mute".equals(path)) {
                int value = service.mute("1".equals(query.get("on")));
                respondJson(client, 200, "{\"ok\":true,\"volume\":" + value + "}");
            } else if ("/api/play".equals(path)) {
                String url = query.get("url");
                if (!AgentMediaQueue.playbackUrl(url)) {
                    respondJson(client, 400, "{\"ok\":false,\"error\":\"url rejected\"}");
                    return;
                }
                service.play(url, query.get("title"), query.get("artist"));
                respondJson(client, 200, "{\"ok\":true}");
            } else if ("/api/pause".equals(path)) {
                service.pause();
                respondJson(client, 200, "{\"ok\":true}");
            } else if ("/api/resume".equals(path)) {
                service.resume();
                respondJson(client, 200, "{\"ok\":true}");
            } else if ("/api/stop".equals(path)) {
                service.stop();
                respondJson(client, 200, "{\"ok\":true}");
            } else if ("/api/next".equals(path)) {
                service.next();
                respondJson(client, 200, "{\"ok\":true}");
            } else if ("/api/previous".equals(path)) {
                service.previous();
                respondJson(client, 200, "{\"ok\":true}");
            } else if ("/api/seek".equals(path)) {
                service.seekTo(parseInt(query.get("position"), 0));
                respondJson(client, 200, "{\"ok\":true}");
            } else if ("/api/apps".equals(path)) {
                respondJson(client, 200, service.apps());
            } else if ("/api/launch".equals(path)) {
                String packageName = query.get("package");
                String activityName = query.get("activity");
                if (packageName == null || activityName == null) {
                    respondJson(client, 400, "{\"ok\":false,\"error\":\"package and activity are required\"}");
                } else {
                    respondJson(client, 200, service.launch(packageName, activityName));
                }
            } else if ("/api/tv/key".equals(path)) {
                respondJson(client, 200, service.tvKey(query.get("code")));
            } else if ("/api/tv/status".equals(path)) {
                respondJson(client, 200, service.tvStatus());
            } else if ("/api/proxy/status".equals(path)) {
                respondJson(client, 200, service.proxyStatus().toString());
            } else if ("/api/vpn/status".equals(path)) {
                respondJson(client, 200, service.vpnStatus().toString());
            } else if ("/api/vpn/start".equals(path)) {
                respondJson(client, 200, service.vpnStart());
            } else if ("/api/vpn/stop".equals(path)) {
                respondJson(client, 200, service.vpnStop());
            } else if ("/api/dns-guard/status".equals(path)) {
                respondJson(client, 200, service.dnsGuardStatus().toString());
            } else if ("/api/dns-guard/start".equals(path)) {
                respondJson(client, 200, service.dnsGuardStart());
            } else if ("/api/dns-guard/stop".equals(path)) {
                respondJson(client, 200, service.dnsGuardStop());
            } else if ("/api/search".equals(path)) {
                String q = query.get("q");
                if (q == null || q.trim().isEmpty()) {
                    respondJson(client, 400, "{\"error\":\"empty query\"}");
                } else {
                    respondJson(client, 200, service.search(q.trim()));
                }
            } else if ("/api/library".equals(path)) {
                respondJson(client, 200, service.library());
            } else if ("/api/playlists".equals(path)) {
                respondJson(client, 200, service.playlists());
            } else if ("/api/playlist".equals(path)) {
                String owner = query.get("owner_id");
                String playlistId = query.get("id");
                if (owner == null || playlistId == null) {
                    respondJson(client, 400, "{\"error\":\"owner_id and id are required\"}");
                } else {
                    respondJson(client, 200, service.playlist(owner, playlistId));
                }
            } else if ("/api/native".equals(path)) {
                String command = query.get("cmd");
                boolean allowed = command != null && (
                        "sound_louder".equals(command) || "sound_quiter".equals(command)
                        || "sound_mute".equals(command) || "sound_unmute".equals(command));
                boolean ok = allowed && NativeCommands.send(command);
                respondJson(client, ok ? 200 : 400, "{\"ok\":" + ok + "}");
            } else {
                respondJson(client, 404, "{\"error\":\"not found\"}");
            }
        } catch (Exception error) {
            Log.w(TAG, "request", error);
        }
    }

    /** Bound the query before URI decoding. Oversize input is not read or dispatched further. */
    private static String readRequestLine(BufferedReader reader) throws Exception {
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = reader.read()) != -1) {
            if (value == '\n') break;
            if (value != '\r') line.append((char) value);
            if (line.length() > MAX_REQUEST_LINE) return line.toString();
        }
        return value == -1 && line.length() == 0 ? null : line.toString();
    }

    /** Same validated schedule model as Bluetooth, available only to other on-device clients. */
    private static JSONObject scheduleRequest(String method, String raw) throws Exception {
        if ("GET".equals(method)) {
            if (raw != null && !raw.isEmpty()) throw new IllegalArgumentException("invalid_request");
            return new JSONObject().put("op", "list");
        }
        if (raw == null || !raw.startsWith("request=") || raw.indexOf('&') >= 0
                || raw.length() > MAX_SCHEDULE_REQUEST * 3 + 8)
            throw new IllegalArgumentException("invalid_request");
        String payload = URLDecoder.decode(raw.substring(8), "UTF-8");
        if (payload.isEmpty() || payload.getBytes(StandardCharsets.UTF_8).length > MAX_SCHEDULE_REQUEST)
            throw new IllegalArgumentException("invalid_request");
        JSONTokener parser = new JSONTokener(payload);
        Object value = parser.nextValue();
        if (!(value instanceof JSONObject) || parser.nextClean() != 0)
            throw new IllegalArgumentException("invalid_request");
        return (JSONObject) value;
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (Exception ignored) { return fallback; }
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw == null || raw.isEmpty()) return result;
        for (String piece : raw.split("&")) {
            int equals = piece.indexOf('=');
            try {
                if (equals < 0) result.put(URLDecoder.decode(piece, "UTF-8"), "");
                else result.put(URLDecoder.decode(piece.substring(0, equals), "UTF-8"),
                                URLDecoder.decode(piece.substring(equals + 1), "UTF-8"));
            } catch (Exception ignored) { }
        }
        return result;
    }

    private static void respondJson(Socket socket, int status, String json) throws Exception {
        respond(socket, status, "application/json; charset=utf-8", json);
    }

    private static void respond(Socket socket, int status, String type, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        respondBytes(socket, status, type, bytes);
    }

    private static void respondBytes(Socket socket, int status, String type, byte[] bytes) throws Exception {
        respondBytes(socket, status, type, bytes, true);
    }

    private static void respondBytes(Socket socket, int status, String type, byte[] bytes,
                                     boolean allowCrossOrigin) throws Exception {
        OutputStream output = socket.getOutputStream();
        String header = "HTTP/1.1 " + status + " " + reason(status) + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + (allowCrossOrigin ? "Access-Control-Allow-Origin: *\r\n"
                    : "Cross-Origin-Resource-Policy: same-origin\r\nX-Content-Type-Options: nosniff\r\n")
                + "Connection: close\r\n\r\n";
        output.write(header.getBytes(StandardCharsets.US_ASCII));
        output.write(bytes);
        output.flush();
    }

    private static String reason(int status) {
        if (status == 200) return "OK";
        if (status == 400) return "Bad Request";
        if (status == 401) return "Unauthorized";
        if (status == 403) return "Forbidden";
        if (status == 404) return "Not Found";
        if (status == 405) return "Method Not Allowed";
        if (status == 413) return "Payload Too Large";
        if (status == 429) return "Too Many Requests";
        if (status == 503) return "Service Unavailable";
        return "Error";
    }
}
