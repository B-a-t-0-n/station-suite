package local.station.agent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Test the real HTTP route; no bound socket, native command, sound, or timer runtime. */
public final class AgentScheduleHttpHostTest {
    private static int assertions;
    private static final class Peer extends Socket {
        final ByteArrayInputStream input;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final InetAddress address;
        Peer(String request, boolean local) throws Exception {
            input = new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8));
            address = InetAddress.getByAddress(local ? new byte[]{127, 0, 0, 1} : new byte[]{(byte)192, (byte)168, 77, 1});
        }
        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public InetAddress getInetAddress() { return address; }
        @Override public void setSoTimeout(int value) { }
        @Override public void close() { }
    }
    private static void check(boolean condition, String reason) {
        assertions++;
        if (!condition) throw new AssertionError(reason);
    }
    private static String dispatch(AgentHttpServer server, String method, String target, boolean local) throws Exception {
        Peer peer = new Peer(method + " " + target + " HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n", local);
        Method handle = AgentHttpServer.class.getDeclaredMethod("handle", Socket.class);
        handle.setAccessible(true);
        handle.invoke(server, peer);
        return new String(peer.output.toByteArray(), StandardCharsets.UTF_8);
    }
    private static String query(String payload) throws Exception {
        return "/api/schedules?request=" + URLEncoder.encode(payload, "UTF-8");
    }
    private static void rejected(AgentHttpServer server, AgentService service, String method, String target,
                                 boolean local, int status) throws Exception {
        int before = service.scheduleCalls;
        String response = dispatch(server, method, target, local);
        check(response.startsWith("HTTP/1.1 " + status + " "), "wrong rejection: " + response);
        check(service.scheduleCalls == before, "rejected HTTP request reached schedules");
    }
    public static void main(String[] args) throws Exception {
        AgentService service = new AgentService();
        AgentHttpServer server = new AgentHttpServer(new android.content.Context(), service);
        String response = dispatch(server, "GET", "/api/schedules", true);
        check(response.startsWith("HTTP/1.1 200 OK"), "GET unavailable");
        check(service.scheduleCalls == 1 && "list".equals(service.received.optString("op")), "GET was not list");
        String payload = "{\"op\":\"create_timer\",\"requestId\":\"01234567-89ab-cdef-0123-456789abcdef\",\"durationMs\":30000,\"label\":\"Проверка\"}";
        response = dispatch(server, "POST", query(payload), true);
        check(response.startsWith("HTTP/1.1 200 OK") && response.contains("\"id\":\"test-id\""), "POST response lost");
        check(service.scheduleCalls == 2 && service.received.getLong("durationMs") == 30000, "duration changed");
        check("Проверка".equals(service.received.optString("label")), "UTF-8 query changed");
        rejected(server, service, "GET", "/api/schedules", false, 403);
        rejected(server, service, "POST", query(payload), false, 403);
        rejected(server, service, "PUT", query(payload), true, 405);
        rejected(server, service, "DELETE", "/api/schedules", true, 405);
        rejected(server, service, "GET", query(payload), true, 400);
        rejected(server, service, "POST", "/api/schedules", true, 400);
        rejected(server, service, "POST", "/api/schedules?request=", true, 400);
        rejected(server, service, "POST", query("not JSON"), true, 400);
        rejected(server, service, "POST", query("[]"), true, 400);
        rejected(server, service, "POST", query("{} {}"), true, 400);
        rejected(server, service, "POST", query("{\"op\":"), true, 400);
        rejected(server, service, "POST", query(payload) + "&request=%7B%7D", true, 400);
        rejected(server, service, "POST", query(payload) + "&ignored=1", true, 400);
        rejected(server, service, "POST", "/api/schedules?other=1", true, 400);
        StringBuilder large = new StringBuilder("{\"label\":\"");
        for (int i = 0; i < 2050; i++) large.append('я');
        large.append("\"}");
        rejected(server, service, "POST", query(large.toString()), true, 400);
        large = new StringBuilder("/api/schedules?request=");
        for (int i = 0; i < 33000; i++) large.append('x');
        rejected(server, service, "POST", large.toString(), true, 413);
        response = dispatch(server, "GET", "/api/equalizer", true);
        check(response.startsWith("HTTP/1.1 200 OK") && service.equalizerCalls == 1
                && "get".equals(service.received.optString("op")), "EQ local read must translate to get");
        response = dispatch(server, "POST", "/api/equalizer?request=" + URLEncoder.encode("{\"op\":\"reset\"}", "UTF-8"), true);
        check(response.startsWith("HTTP/1.1 200 OK") && service.equalizerCalls == 2
                && "reset".equals(service.received.optString("op")), "EQ local write shares bounded parser");
        rejected(server, service, "GET", "/api/equalizer", false, 403);
        rejected(server, service, "PUT", "/api/equalizer", true, 405);
        rejected(server, service, "POST", "/api/equalizer?request=%5B%5D", true, 400);
        check(service.equalizerCalls == 2, "rejected EQ routes must not touch actuator");
        response = dispatch(server, "GET", "/api/hardware", false);
        check(response.startsWith("HTTP/1.1 200 OK") && response.contains("\"uptimeMs\":17")
                && response.contains("Cache-Control: no-store") && service.hardwareCalls == 1,
                "hardware snapshot must be available to the web panel");
        response = dispatch(server, "POST", "/api/hardware", false);
        check(response.startsWith("HTTP/1.1 405 ") && service.hardwareCalls == 1,
                "hardware endpoint is read-only");
        System.out.println(assertions + " assertions passed; fake peers only");
    }
}
