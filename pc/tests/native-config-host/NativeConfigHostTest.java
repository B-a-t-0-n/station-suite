package local.station.voicebridge;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

public final class NativeConfigHostTest {
    private static int assertions;
    private interface Action { void run() throws Exception; }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static void rejects(Action action) throws Exception { boolean rejected = false; try { action.run(); } catch (Exception expected) { rejected = true; } check(rejected, "must reject"); }
    private static byte[] utf(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static JSONObject copy(JSONObject value) throws Exception { return NativeConfigJson.copy(value); }
    private static JSONObject command(String op, String key, Object value) throws Exception { return new JSONObject().put("op", op).put(key, value); }
    private static void write(File file, JSONObject value) throws Exception { Files.write(file.toPath(), utf(value.toString())); }
    private static NativeConfigProtocol.Request request(String method, String target, String host, int size) {
        return NativeConfigProtocol.parseHead(utf(method + " " + target + " HTTP/1.1\r\nHost: " + host + "\r\nContent-Length: " + size + "\r\n\r\n"));
    }
    private static JSONObject responseBody(byte[] response) throws Exception {
        String text = new String(response, StandardCharsets.UTF_8); int split = text.indexOf("\r\n\r\n");
        return split + 4 == text.length() ? null : new JSONObject(text.substring(split + 4));
    }
    public static void main(String[] args) throws Exception {
        JSONObject fixtures = new JSONObject(new String(Files.readAllBytes(new File(args[0]).toPath()), StandardCharsets.UTF_8));
        JSONObject template = fixtures.getJSONObject("template");
        File directory = Files.createTempDirectory("station-config-fixture-").toFile();
        File bootstrap = new File(directory, NativeConfigStore.TEMPLATE_FILE), display = new File(directory, NativeConfigStore.DISPLAY_FILE);
        long[] now = {1700000000000L}; AtomicInteger notifications = new AtomicInteger();
        NativeConfigStore store = new NativeConfigStore(directory, () -> now[0], null, () -> notifications.incrementAndGet());
        rejects(() -> store.sync());
        check(store.display(new JSONObject().put("op", "get")).getJSONObject("requested").length() == 0, "initial empty state");
        rejects(() -> store.display(command("set_clock_size", "size", "large")));
        write(bootstrap, template);
        JSONObject pushd = store.sync().getJSONObject("config").getJSONObject("system_config").getJSONObject("pushd");
        check(pushd.get("xivaServices") instanceof String && pushd.getString("xivaServices").equals("quasar-realtime"), "nested local push service is a string");
        check(pushd.getBoolean("keep"), "pushd sibling fields preserved");
        check(!store.sync().getJSONObject("config").getJSONObject("system_config").has("xivaServices"), "does not inject unused top-level services");
        check(store.pushSubscribe().getString("ts").equals("1700003600"), "future push expiration");
        contract(fixtures.getJSONArray("cases"), store);
        strictJson(); strictHttp(store);
        JSONObject first = store.display(command("set_clock_size", "size", "large"));
        check(first.getBoolean("refreshQueued") && notifications.get() == 1, "notify after commit");
        check(first.getString("revision").length() == 64 && first.getLong("expiresAtMs") == 0, "saved revision");
        store.display(new JSONObject().put("op", "set_brightness").put("auto", false).put("value", .3));
        for (String style : new String[]{"clock", "wave", "pads", "bricks", "fluid", "random"}) {
            store.display(command("set_music_style", "style", style)); JSONObject sync = store.sync();
            JSONObject config = sync.getJSONObject("config"), led = config.getJSONObject("device_config").getJSONObject("led");
            check(led.getJSONObject("time_visualization").getString("size").equals("large"), "preserve clock setting");
            check(led.getJSONObject("brightness").getDouble("value") == .3, "preserve brightness setting");
            check(led.getJSONObject("music_equalizer_visualization").getString("style").equals(style), "music style");
            check(led.getJSONObject("music_equalizer_visualization").getBoolean("auto") == style.equals("random"), "random auto");
            check(config.getJSONObject("system_config").getJSONObject("spectrogramAnimation").getBoolean("enabled") != style.equals("clock"), "animation enable");
            check(config.getJSONObject("system_config").getString("uniProxyUrl").equals(NativeConfigStore.LOCAL_VOICE_URL), "voice preserved");
            check(led.getBoolean("keep") && config.getJSONObject("account_config").getInt("keep") == 7, "siblings preserved");
            check(sync.getJSONObject("glagol").getString("security").equals("synthetic-test-only"), "glagol preserved");
        }
        check(new JSONObject(new String(Files.readAllBytes(bootstrap.toPath()), StandardCharsets.UTF_8)).similar(template), "bootstrap immutable");
        byte[] saved = Files.readAllBytes(display.toPath()); int before = notifications.get();
        for (String bad : new String[]{"{\"op\":\"set_clock_size\",\"size\":\"huge\"}",
                "{\"op\":\"set_music_style\",\"style\":\"http://outside\"}",
                "{\"op\":\"set_brightness\",\"auto\":1,\"value\":0.5}",
                "{\"op\":\"set_brightness\",\"auto\":false,\"value\":1.1}",
                "{\"op\":\"get\",\"url\":\"https://outside\"}",
                "{\"op\":\"set_clock_size\",\"size\":\"small\",\"extra\":true}"}) rejects(() -> store.display(new JSONObject(bad)));
        check(Arrays.equals(saved, Files.readAllBytes(display.toPath())) && notifications.get() == before, "invalid commands never persist or notify");
        store.display(new JSONObject().put("op", "get")); check(notifications.get() == before, "reads never notify");
        JSONObject brokenTemplate = copy(template); brokenTemplate.getJSONObject("config").getJSONObject("device_config").put("led", "bad"); write(bootstrap, brokenTemplate);
        rejects(() -> store.display(command("set_clock_size", "size", "small")));
        check(Arrays.equals(saved, Files.readAllBytes(display.toPath())), "incompatible overlay never persists"); write(bootstrap, template);
        JSONObject expired = new JSONObject().put("version", 1).put("expiresAtMs", now[0] - 1)
                .put("settings", new JSONObject().put("clockSize", "small").put("musicStyle", "wave")); write(display, expired);
        check(store.display(new JSONObject().put("op", "get")).getJSONObject("requested").length() == 0, "expired canary not active");
        check(!store.sync().getJSONObject("config").getJSONObject("device_config").getJSONObject("led").has("time_visualization"), "expired overlay ignored");
        store.display(command("set_music_style", "style", "pads"));
        check(!store.display(new JSONObject().put("op", "get")).getJSONObject("requested").has("clockSize"), "expired leaves not promoted");
        Files.write(display.toPath(), utf("{\"version\":1,\"version\":1}"));
        rejects(() -> store.sync()); rejects(() -> store.display(command("set_clock_size", "size", "small")));
        check(new String(Files.readAllBytes(display.toPath()), StandardCharsets.UTF_8).equals("{\"version\":1,\"version\":1}"), "corrupt state not overwritten");
        Files.delete(display.toPath());
        NativeConfigStore noPush = new NativeConfigStore(directory, () -> now[0], null, () -> { throw new Exception("not connected"); });
        check(!noPush.display(command("set_clock_size", "size", "middle")).getBoolean("refreshQueued"), "push failure does not falsify save");
        check(noPush.display(new JSONObject().put("op", "get")).getJSONObject("requested").getString("clockSize").equals("middle"), "saved despite disconnected push");
        for (String invalid : new String[]{"{\"version\":1.0,\"expiresAtMs\":0,\"settings\":{\"clockSize\":\"small\"}}",
                "{\"version\":1,\"expiresAtMs\":-1,\"settings\":{\"clockSize\":\"small\"}}",
                "{\"version\":1,\"expiresAtMs\":0,\"settings\":{}}"}) rejects(() -> NativeConfigStore.validateDisplay(new JSONObject(invalid)));
        JSONObject wrongVoice = copy(template); wrongVoice.getJSONObject("config").getJSONObject("system_config").put("uniProxyUrl", "wss://outside.invalid/");
        rejects(() -> NativeConfigStore.validateTemplate(wrongVoice));
        rejects(() -> NativeConfigStore.validateTemplate(copy(template).put("hasUpdate", true)));
        now[0] = ((long)Integer.MAX_VALUE) * 1000; rejects(() -> store.pushSubscribe()); now[0] = 1700000000000L;
        realServer(store, new File(args[1]));
        System.out.println(assertions + " assertions passed; isolated fixture and loopback only");
    }
    private static void contract(JSONArray cases, NativeConfigStore store) throws Exception {
        for (int i = 0; i < cases.length(); i++) {
            JSONObject item = cases.getJSONObject(i); boolean tls = item.getInt("port") == 443;
            String method = item.getString("method"), host = item.getString("host"), target = item.getString("target");
            NativeConfigProtocol.Response response = NativeConfigProtocol.respond(request(method, target, host, 0), new byte[0], tls, tls ? host : null, store);
            check(response.status == item.getInt("status"), "PC status contract " + method + " " + target);
            JSONObject actual = responseBody(response.bytes()); Object expected = item.opt("body");
            check(expected == JSONObject.NULL ? actual == null : actual != null && ((JSONObject)expected).similar(actual), "PC body contract " + method + " " + target);
        }
    }
    private static void strictJson() throws Exception {
        for (String invalid : new String[]{"{'a':1}", "{a:1}", "{\"a\":1,}", "{\"a\":NaN}", "{\"a\":01}",
                "{\"a\":1,\"a\":2}", "{\"a\":1,\"\\u0061\":2}", "[]", "{}x", "{\"a\":\"\n\"}",
                "{\"a\":\"\\ud800\"}", "{\"a\":\"\\udc00\"}", "{\"a\":9223372036854775808}", "{\"a\":1e999}"})
            rejects(() -> NativeConfigJson.object(utf(invalid), 1024));
        rejects(() -> NativeConfigJson.object(new byte[]{'{','"','x','"',':','"',(byte)0xc3,'"','}'}, 1024));
        rejects(() -> NativeConfigJson.object(utf("{}"), 1));
        check(NativeConfigJson.object(utf("{\"a\":\"\\ud83d\\ude42\",\"b\":[true,false,null,-12,1.25e2]}"), 1024).length() == 2, "valid strict JSON");
        String nested = "0"; for (int i=0; i<40; i++) nested = "[" + nested + "]"; final String deep = "{\"a\":" + nested + "}";
        rejects(() -> NativeConfigJson.object(utf(deep), 1024));
    }
    private static void strictHttp(NativeConfigStore store) throws Exception {
        for (String invalid : new String[]{"GET / HTTP/1.1\r\n\r\n", "GET / HTTP/1.1\r\nHost: x\r\nHost: y\r\n\r\n",
                "GET / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n", "GET / HTTP/1.1\r\nHost: x\r\nOrigin: x\r\n\r\n",
                "GET //outside HTTP/1.1\r\nHost: x\r\n\r\n", "GET https://outside/ HTTP/1.1\r\nHost: x\r\n\r\n",
                "GET / HTTP/1.1\r\nHost: x\r\nContent-Length: 1\r\n\r\n", "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 65537\r\n\r\n",
                "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: -1\r\n\r\n", "GET / HTTP/1.1\nHost: x\n\n"})
            rejects(() -> NativeConfigProtocol.parseHead(utf(invalid)));
        check(NativeConfigProtocol.respond(request("GET", "/api/display", "127.0.0.1:17685", 0), new byte[0], true, "quasar.yandex.net", store).status == 421, "TLS cannot write local API");
        check(NativeConfigProtocol.respond(request("GET", "/api/display?x=1", "127.0.0.1:17685", 0), new byte[0], false, null, store).status == 404, "exact admin target");
        check(NativeConfigProtocol.respond(request("GET", "/check_updates", "quasar.yandex.net", 0), new byte[0], true, "other.invalid", store).status == 421, "SNI host binding");
        check(NativeConfigProtocol.respond(request("GET", "/check_updates", "quasar.yandex.net:444", 0), new byte[0], true, "quasar.yandex.net", store).status == 421, "fixed native host port");
        check(NativeConfigProtocol.respond(request("GET", "/check_updates", "quasar.yandex.net", 0), new byte[]{1}, true, "quasar.yandex.net", store).status == 400, "body framing");
    }
    private static void realServer(NativeConfigStore store, File keys) throws Exception {
        char[] password = "test-only-password".toCharArray();
        SSLContext serverTls = NativeConfigServer.tlsFromPrivateFile(keys, password);
        KeyStore trust = KeyStore.getInstance("PKCS12"); try (FileInputStream input = new FileInputStream(keys)) { trust.load(input, password); }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); factory.init(trust);
        SSLContext clientTls = SSLContext.getInstance("TLS"); clientTls.init(null, factory.getTrustManagers(), null);
        NativeConfigServer server = new NativeConfigServer(store, serverTls, 0, 0); server.start();
        try {
            check(server.status().getBoolean("active"), "explicit server start");
            byte[] response = exchange(new Socket("127.0.0.1", server.boundHttpPort()), "GET /api/display HTTP/1.1\r\nHost: 127.0.0.1:17685\r\n\r\n");
            check(responseBody(response).getString("scope").equals("station_display_settings"), "plain local LED API");
            check(new String(exchange(new Socket("127.0.0.1", server.boundHttpPort()), "GET /unknown HTTP/1.1\r\nHost: outside.invalid\r\n\r\n"), StandardCharsets.US_ASCII).startsWith("HTTP/1.1 421"), "no upstream fallthrough");
            SSLSocket socket = (SSLSocket)clientTls.getSocketFactory().createSocket("127.0.0.1", server.boundTlsPort());
            SSLParameters parameters = socket.getSSLParameters(); parameters.setServerNames(Collections.singletonList(new SNIHostName("quasar.yandex.net"))); socket.setSSLParameters(parameters);
            response = exchange(socket, "GET /check_updates HTTP/1.1\r\nHost: quasar.yandex.net\r\n\r\n");
            check(responseBody(response).getBoolean("hasUpdate") == false, "actual TLS SNI path");
            SSLSocket mismatch = (SSLSocket)clientTls.getSocketFactory().createSocket("127.0.0.1", server.boundTlsPort());
            parameters = mismatch.getSSLParameters(); parameters.setServerNames(Collections.singletonList(new SNIHostName("quasar.yandex.net"))); mismatch.setSSLParameters(parameters);
            check(new String(exchange(mismatch, "GET /check_updates HTTP/1.1\r\nHost: scbh.yandex.net\r\n\r\n"), StandardCharsets.US_ASCII).startsWith("HTTP/1.1 421"), "actual TLS rejects mismatched host");
            Socket incomplete = new Socket("127.0.0.1", server.boundHttpPort()); incomplete.setSoTimeout(8000);
            long started = System.nanoTime(); incomplete.getOutputStream().write(utf("POST /api/display HTTP/1.1\r\nHost: 127.0.0.1:17685\r\nContent-Length: 8\r\n\r\nx"));
            check(incomplete.getInputStream().read() == -1 && (System.nanoTime()-started)/1000000 < 7000, "bounded incomplete body"); incomplete.close();
            Socket idle = new Socket("127.0.0.1", server.boundHttpPort()); idle.setSoTimeout(2000); Thread.sleep(50); server.stop();
            check(idle.getInputStream().read() == -1, "stop closes accepted idle peer"); idle.close();
            check(!server.status().getBoolean("active") && server.status().getInt("clients") == 0, "stop releases server");
        } finally { server.stop(); Arrays.fill(password, '\0'); }
    }
    private static byte[] exchange(Socket socket, String request) throws Exception {
        try { socket.setSoTimeout(8000); socket.getOutputStream().write(utf(request)); socket.getOutputStream().flush();
            ByteArrayOutputStream result = new ByteArrayOutputStream(); InputStream input = socket.getInputStream(); byte[] buffer = new byte[2048]; int count;
            while ((count = input.read(buffer)) >= 0) { result.write(buffer, 0, count); check(result.size() <= NativeConfigProtocol.MAX_RESPONSE + 4096, "response bound"); }
            return result.toByteArray();
        } finally { socket.close(); }
    }
}
