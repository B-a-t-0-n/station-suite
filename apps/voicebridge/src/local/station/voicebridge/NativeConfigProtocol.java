package local.station.voicebridge;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pure exact-path responder. There is deliberately no upstream/proxy operation. */
final class NativeConfigProtocol {
    static final int MAX_HEAD = 16384, MAX_BODY = 65536, MAX_RESPONSE = 300000;
    interface Source {
        JSONObject sync() throws Exception;
        JSONObject pushSubscribe() throws Exception;
        JSONObject display(JSONObject command) throws Exception;
    }
    private static final Set<String> HOSTS = new HashSet<>(Arrays.asList("quasar.yandex.net", "quasar.yandex.ru",
            "scbh.yandex.net", "startup.mobile.yandex.net", "clck.yandex.net", "uniproxy.alice.yandex.net", "oauth.yandex.ru"));
    private static final Set<String> JSON_PATHS = new HashSet<>(Arrays.asList("/check_updates", "/get_sync_info",
            "/get_user_details", "/get_timezone", "/analytics/startup", "/push_subscribe", "/glagol/device_list", "/register", "/token"));
    static final class Request {
        final String method, path, target, host; final int contentLength;
        Request(String method, String target, String host, int contentLength) {
            this.method = method; this.target = target; this.path = target.split("\\?", 2)[0]; this.host = host; this.contentLength = contentLength;
        }
    }
    static final class Response {
        final int status; final byte[] body; final boolean head;
        Response(int status, JSONObject body, boolean head) {
            this.status = status; this.body = body == null ? new byte[0] : body.toString().getBytes(StandardCharsets.UTF_8); this.head = head;
            if (this.body.length > MAX_RESPONSE) throw invalid();
        }
        byte[] bytes() {
            String reason = status == 200 ? "OK" : status == 204 ? "No Content" : status == 400 ? "Bad Request"
                    : status == 404 ? "Not Found" : status == 405 ? "Method Not Allowed" : status == 421 ? "Misdirected Request" : "Service Unavailable";
            StringBuilder header = new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason)
                    .append("\r\nConnection: close\r\nCache-Control: no-store\r\n");
            if (status != 204) header.append("Content-Length: ").append(body.length).append("\r\n");
            if (body.length > 0) header.append("Content-Type: application/json\r\n");
            byte[] first = header.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII);
            if (head || body.length == 0) return first;
            byte[] result = Arrays.copyOf(first, first.length + body.length); System.arraycopy(body, 0, result, first.length, body.length); return result;
        }
    }
    static Request parseHead(byte[] bytes) {
        if (bytes == null || bytes.length < 4 || bytes.length > MAX_HEAD) throw invalid();
        for (byte value : bytes) if ((value & 255) > 127 || value == 0) throw invalid();
        String head = new String(bytes, StandardCharsets.US_ASCII);
        if (!head.endsWith("\r\n\r\n")) throw invalid();
        String[] lines = head.substring(0, head.length() - 4).split("\r\n", -1);
        String[] request = lines[0].split(" ", -1);
        if (request.length != 3 || !request[0].matches("[A-Z]{1,16}")
                || !("HTTP/1.1".equals(request[2]) || "HTTP/1.0".equals(request[2]))) throw invalid();
        String target = request[1];
        if (!target.startsWith("/") || target.startsWith("//") || target.length() > 4096 || target.indexOf('#') >= 0) throw invalid();
        for (int i = 0; i < target.length(); i++) if (target.charAt(i) < 33 || target.charAt(i) > 126) throw invalid();
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i]; int colon = line.indexOf(':');
            if (colon < 1 || !line.substring(0, colon).matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) throw invalid();
            String name = line.substring(0, colon).toLowerCase(Locale.ROOT), value = line.substring(colon + 1).trim();
            for (int j = 0; j < value.length(); j++) if (value.charAt(j) < 32 && value.charAt(j) != '\t' || value.charAt(j) == 127) throw invalid();
            if (headers.put(name, value) != null) throw invalid();
        }
        String host = headers.get("host");
        if (host == null || host.isEmpty() || host.length() > 128 || headers.containsKey("transfer-encoding")
                || headers.containsKey("expect") || headers.containsKey("upgrade") || headers.containsKey("origin")) throw invalid();
        int length = 0;
        if (headers.containsKey("content-length")) {
            String size = headers.get("content-length"); if (!size.matches("[0-9]{1,5}")) throw invalid();
            length = Integer.parseInt(size); if (length > MAX_BODY) throw invalid();
        }
        if (!("POST".equals(request[0])) && length != 0) throw invalid();
        return new Request(request[0], target, host.toLowerCase(Locale.ROOT), length);
    }
    static Response respond(Request request, byte[] body, boolean tls, String sni, Source source) {
        boolean head = "HEAD".equals(request.method);
        try {
            if (body == null || body.length != request.contentLength) return empty(400, head);
            // The plain local API exposes only LED requests; TLS native clients can never use it.
            if (!tls && ("127.0.0.1:17685".equals(request.host) || "localhost:17685".equals(request.host))) {
                if (!"/api/display".equals(request.target)) return empty(404, head);
                JSONObject command;
                if ("GET".equals(request.method) && body.length == 0) command = new JSONObject().put("op", "get");
                else if ("POST".equals(request.method) && body.length > 0 && body.length <= 512) {
                    try { command = NativeConfigJson.object(body, 512); }
                    catch (Exception invalid) { return empty(400, head); }
                } else return empty(405, head);
                try { return new Response(200, source.display(command), false); }
                catch (IllegalArgumentException invalid) { return empty(400, head); }
            }
            String host = normalizeHost(request.host, tls ? 443 : 80);
            if (host == null || tls && (sni == null || !host.equals(sni))) return empty(421, head);
            String method = request.method, path = request.path;
            if ("oauth.yandex.ru".equals(host)) {
                if (!tls || !"/token".equals(path)) return empty(404, head);
                if (!"POST".equals(method)) return empty(405, head);
                return new Response(200, oauth(), head);
            }
            if (!tls && ("www.google.com".equals(host) || "connectivitycheck.gstatic.com".equals(host))) {
                String expected = "www.google.com".equals(host) ? "/gen_204" : "/generate_204";
                return empty(!expected.equals(path) ? 404 : "GET".equals(method) || head ? 204 : 405, head);
            }
            if (!JSON_PATHS.contains(path) && !"/generate_204".equals(path)) return empty(404, head);
            boolean post = "POST".equals(method), get = "GET".equals(method) || head;
            if (!(get || post)) return empty(405, head);
            if (("/get_timezone".equals(path) || "/analytics/startup".equals(path) || "/get_user_details".equals(path)) && !get)
                return empty(405, head);
            if ("/token".equals(path)) return empty(404, head);
            if ("/register".equals(path) && !("GET".equals(method) || post)) return empty(405, head);
            if ("/generate_204".equals(path)) return empty(204, head);
            JSONObject response;
            if ("/check_updates".equals(path)) response = new JSONObject().put("status", "ok").put("hasUpdate", false).put("hasCriticalUpdate", false);
            else if ("/get_sync_info".equals(path)) response = source.sync();
            else if ("/push_subscribe".equals(path)) response = source.pushSubscribe();
            else if ("/get_timezone".equals(path)) response = new JSONObject().put("timezone", "Europe/Moscow").put("offset", 10800);
            else if ("/get_user_details".equals(path)) response = userDetails();
            else if ("/glagol/device_list".equals(path)) response = devices(source.sync(), request.target);
            else if ("/register".equals(path)) response = new JSONObject().put("status", "ok").put("message", "allvalid").put("hasCriticalUpdate", false);
            else response = new JSONObject().put("status", "ok");
            return new Response(200, response, head);
        } catch (Exception unavailable) { return empty(503, head); }
    }
    static Response empty(int status, boolean head) { return new Response(status, null, head); }
    private static String normalizeHost(String value, int port) {
        if (value == null) return null;
        String[] parts = value.split(":", -1);
        if (parts.length > 2 || parts.length == 2 && !String.valueOf(port).equals(parts[1])) return null;
        String name = parts[0].endsWith(".") ? parts[0].substring(0, parts[0].length() - 1) : parts[0];
        if (HOSTS.contains(name)) return name;
        return port == 80 && ("www.google.com".equals(name) || "connectivitycheck.gstatic.com".equals(name)) ? name : null;
    }
    private static JSONObject userDetails() throws Exception {
        JSONObject avatar = new JSONObject().put("default", "0/0-0").put("empty", true);
        JSONObject user = new JSONObject().put("uid", "1000000001").put("login", "station").put("yandex_uid", "1000000001")
                .put("email", "station@local").put("is_plus", false).put("avatar", avatar)
                .put("display_name", new JSONObject().put("avatar", avatar).put("name", "Станция"));
        return new JSONObject().put("status", "ok").put("user_details", user).put("user", user);
    }
    private static JSONObject oauth() throws Exception {
        return new JSONObject().put("access_token", "lab-only-no-real-account-access-token").put("token_type", "bearer")
                .put("expires_in", 3600).put("refresh_token", "lab-only-no-real-account-refresh-token").put("uid", 1)
                .put("x_token", "lab-only-no-real-x-token").put("xtoken", "lab-only-no-real-x-token").put("is_stub", true);
    }
    private static JSONObject devices(JSONObject sync, String target) throws Exception {
        Object glagol = sync.opt("glagol"); if (glagol == null) glagol = new JSONObject();
        String deviceId = "yandexstation_2";
        if (target.indexOf('?') >= 0) for (String part : target.split("\\?", 2)[1].split("&"))
            if (part.startsWith("device_id=") && part.length() > 10) deviceId = part.substring(10);
        JSONObject device = new JSONObject().put("activation_code", 0).put("activation_region", "RU")
                .put("config", new JSONObject().put("locale", "ru-RU").put("name", "Станция Макс")
                    .put("location", new JSONObject().put("latitude", 55.7558).put("longitude", 37.6173))
                    .put("dndMode", new JSONObject().put("enabled", false).put("features", new JSONObject().put("allowIncomingCalls", false)))
                    .put("standby", new JSONObject().put("deepStandbyEnabled", true).put("deepStandbyTimeoutMinutes", 240)))
                .put("glagol", glagol).put("id", deviceId).put("name", "Станция Макс")
                .put("networkInfo", new JSONObject()).put("platform", "yandexstation_2").put("promocode_activated", false).put("tags", new JSONArray());
        return new JSONObject().put("status", "ok").put("devices", new JSONArray().put(device));
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid_local_request"); }
}
