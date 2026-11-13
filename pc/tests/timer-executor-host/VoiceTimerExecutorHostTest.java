package local.station.voicebridge;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Actual typed timer actuator with a fake connection factory; never opens a socket. */
public final class VoiceTimerExecutorHostTest {
    private static final String ID = "aabbccdd-0123-4567-89ab-cdef01234567";
    private static final String OTHER_ID = "11223344-0123-4567-89ab-cdef01234567";
    private static int assertions;

    private static void check(boolean value, String name) {
        assertions++;
        if (!value) throw new AssertionError(name);
    }
    private static JSONObject entry(long remainingMs) throws Exception {
        return new JSONObject().put("id", ID).put("type", "timer").put("enabled", true)
                .put("outcome", "scheduled").put("remainingMs", remainingMs);
    }
    private static JSONObject success(long remainingMs) throws Exception {
        return new JSONObject().put("ok", true).put("id", ID)
                .put("entries", new JSONArray().put(entry(remainingMs)));
    }
    private static String repeat(char c, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(c);
        return result.toString();
    }
    private static LocalVoiceCommands.Result execute(String phrase, Factory factory) {
        return LocalVoiceCommands.execute(LocalVoiceCommands.parseFinal(phrase),
                new LocalVoiceCommands.AgentExecutor(null, factory));
    }
    private static void failedResponse(String response, String code) throws Exception {
        Factory factory = new Factory(response);
        LocalVoiceCommands.Result result = execute("поставь таймер на 30 секунд", factory);
        check(result.handled && !result.accepted && code.equals(result.code), "invalid timer response: " + code);
        check(result.action == LocalVoiceCommands.Action.CREATE_TIMER && "station_schedule".equals(result.target),
                "failure retains only fixed typed timer target");
        check(factory.opened == 1 && factory.last.disconnected, "failed acknowledgement is closed and never retried");
    }
    public static void main(String[] args) throws Exception {
        for (long duration : new long[]{5000L, 30000L, 60000L, 5400000L, 86400000L}) {
            Factory factory = new Factory(success(duration).toString());
            LocalVoiceCommands.Decision decision = LocalVoiceCommands.parseFinal("Алиса, поставь таймер на " + duration / 1000L + " секунд");
            check(decision.kind == LocalVoiceCommands.Kind.MATCH && decision.command.action == LocalVoiceCommands.Action.CREATE_TIMER
                    && decision.command.durationMs == duration && decision.command.percent == -1 && decision.command.query.isEmpty(),
                    "only typed duration is passed to timer actuator");
            LocalVoiceCommands.Result result = LocalVoiceCommands.execute(decision, new LocalVoiceCommands.AgentExecutor(null, factory));
            check(result.accepted && result.handled && "accepted".equals(result.code)
                    && "station_schedule".equals(result.target) && result.volumePercent == -1, "native timer accepted");
            check(factory.opened == 1, "create has one request without preflight or retries");
            JSONObject request = requestChecks(factory.last, duration);
            String requestId = request.getString("requestId");
            check(UUID.fromString(requestId).toString().equals(requestId), "request id is canonical lower-case UUID");
            check(factory.last.disconnected && factory.last.inputClosed, "success releases response and connection");
        }

        Factory repeated = new Factory(success(30000L).toString());
        LocalVoiceCommands.AgentExecutor executor = new LocalVoiceCommands.AgentExecutor(null, repeated);
        check(LocalVoiceCommands.execute(LocalVoiceCommands.parseFinal("таймер на 30 секунд"), executor).accepted, "first explicit turn accepted");
        String first = requestChecks(repeated.last, 30000L).getString("requestId");
        check(LocalVoiceCommands.execute(LocalVoiceCommands.parseFinal("таймер на 30 секунд"), executor).accepted, "second explicit turn accepted");
        String second = requestChecks(repeated.last, 30000L).getString("requestId");
        check(!first.equals(second) && repeated.opened == 2, "distinct explicit timer turns have distinct idempotency keys");

        for (Object bad : new Object[]{false, "true", 1, JSONObject.NULL})
            failedResponse(success(30000L).put("ok", bad).toString(), "agent_rejected");
        failedResponse("{}", "agent_rejected");
        failedResponse("{\"ok\":false,\"error\":\"PRIVATE SERVER ERROR\"}", "agent_rejected");
        for (Object bad : new Object[]{ID.toUpperCase(java.util.Locale.ROOT), "not-a-uuid", "", 7, JSONObject.NULL})
            failedResponse(success(30000L).put("id", bad).toString(), "agent_bad_response");
        JSONObject missing = success(30000L); missing.remove("id");
        failedResponse(missing.toString(), "agent_bad_response");
        for (Object bad : new Object[]{new JSONArray(), "entries", JSONObject.NULL, 5})
            failedResponse(success(30000L).put("entries", bad).toString(), "agent_bad_response");
        missing = success(30000L); missing.remove("entries");
        failedResponse(missing.toString(), "agent_bad_response");
        for (Object[] mutation : new Object[][]{
                {"id", OTHER_ID}, {"id", ID.toUpperCase(java.util.Locale.ROOT)}, {"id", JSONObject.NULL},
                {"type", "alarm"}, {"type", JSONObject.NULL}, {"enabled", false}, {"enabled", "true"},
                {"enabled", 1}, {"outcome", "fired"}, {"outcome", "scheduled "}, {"outcome", JSONObject.NULL},
                {"remainingMs", 0}, {"remainingMs", -1}, {"remainingMs", 30001}, {"remainingMs", 30000.5},
                {"remainingMs", "30000"}, {"remainingMs", true}, {"remainingMs", JSONObject.NULL}}) {
            JSONObject response = success(30000L);
            response.getJSONArray("entries").getJSONObject(0).put((String) mutation[0], mutation[1]);
            failedResponse(response.toString(), "agent_bad_response");
        }
        for (String field : new String[]{"id", "type", "enabled", "outcome", "remainingMs"}) {
            JSONObject response = success(30000L); response.getJSONArray("entries").getJSONObject(0).remove(field);
            failedResponse(response.toString(), "agent_bad_response");
        }
        JSONObject withOtherEntry = success(29999L);
        withOtherEntry.put("entries", new JSONArray().put(entry(1000L).put("id", OTHER_ID))
                .put(JSONObject.NULL).put("unrelated").put(entry(29999L)));
        check(execute("таймер на 30 секунд", new Factory(withOtherEntry.toString())).accepted,
                "valid matching created timer is located independently of other list rows");
        JSONArray fullList = new JSONArray();
        for (int i = 0; i < 31; i++) fullList.put(entry(1000L).put("id", OTHER_ID));
        fullList.put(entry(30000L));
        check(execute("таймер на 30 секунд", new Factory(success(30000L).put("entries", fullList).toString())).accepted,
                "matching timer at legal 32-entry capacity is found");
        fullList.put(entry(1000L).put("id", OTHER_ID));
        failedResponse(success(30000L).put("entries", fullList).toString(), "agent_bad_response");
        Factory partialDeadline = new Factory(success(1L).toString());
        check(execute("таймер на 30 секунд", partialDeadline).accepted, "strictly positive remaining time after create accepted");
        failedResponse("not JSON PRIVATE ERROR", "agent_bad_response");

        String padded = success(30000L).put("padding", repeat('x', 12000)).toString();
        check(execute("таймер на 30 секунд", new Factory(padded)).accepted, "schedule snapshots can exceed old generic 8 KiB limit");
        for (boolean declared : new boolean[]{false, true}) {
            Factory oversized = new Factory(repeat('x', 32769));
            oversized.declared = declared ? 32769 : -1;
            LocalVoiceCommands.Result result = execute("таймер на 30 секунд", oversized);
            check(!result.accepted && "agent_bad_response".equals(result.code) && oversized.opened == 1
                    && oversized.last.disconnected, "32 KiB schedule reply bound applies to declared and streamed content");
        }
        for (int status : new int[]{301, 400, 403, 500}) {
            Factory failure = new Factory(success(30000L).toString()); failure.status = status;
            LocalVoiceCommands.Result result = execute("таймер на 30 секунд", failure);
            check(!result.accepted && "agent_unavailable".equals(result.code)
                    && failure.opened == 1 && !failure.last.inputRead && failure.last.disconnected,
                    "HTTP failure never follows redirect, parses error body or retries");
        }
        Factory lostAck = new Factory(success(30000L).toString()); lostAck.failure = new IOException("PRIVATE ACK LOST");
        LocalVoiceCommands.Result lost = execute("таймер на 30 секунд", lostAck);
        check(!lost.accepted && "agent_unavailable".equals(lost.code) && lostAck.opened == 1,
                "lost acknowledgement after sending never creates a second timer");
        check(lostAck.last.outputClosed && lostAck.last.disconnected, "uncertain sent mutation closed without retry");

        for (String invalid : new String[]{"поставь таймер на 0 секунд", "таймер на 4 секунды", "таймер на 25 часов",
                "поставь таймер", "поставь таймер на 30 секунд и выключи звук", "не ставь таймер на 30 секунд"}) {
            Factory factory = new Factory(success(30000L).toString());
            LocalVoiceCommands.Result result = execute(invalid, factory);
            check(!result.accepted && factory.opened == 0, "malformed or negated timer cannot contact actuator");
        }
        AtomicBoolean cancelled = new AtomicBoolean(true);
        Factory noRequest = new Factory(success(30000L).toString());
        LocalVoiceCommands.Result stale = LocalVoiceCommands.execute(LocalVoiceCommands.parseFinal("таймер на 30 секунд"),
                new LocalVoiceCommands.AgentExecutor(null, noRequest, cancelled::get));
        check(!stale.accepted && "cancelled".equals(stale.code) && noRequest.opened == 0,
                "stale finalized turn cannot create a timer");
        Factory brokenGuard = new Factory(success(30000L).toString());
        stale = LocalVoiceCommands.execute(LocalVoiceCommands.parseFinal("таймер на 30 секунд"),
                new LocalVoiceCommands.AgentExecutor(null, brokenGuard, () -> { throw new IllegalStateException("PRIVATE"); }));
        check(!stale.accepted && "cancelled".equals(stale.code) && brokenGuard.opened == 0,
                "broken ownership guard fails closed before sending");
        System.out.println(assertions + " assertions passed; fake HTTP only");
    }

    private static JSONObject requestChecks(Fake request, long duration) throws Exception {
        URL address = request.getURL();
        check("http".equals(address.getProtocol()) && "127.0.0.1".equals(address.getHost())
                && address.getPort() == 18080 && "/api/schedules".equals(address.getPath())
                && address.getRef() == null && address.getUserInfo() == null, "fixed on-device schedules route");
        String query = address.getQuery();
        check(query != null && query.startsWith("request=") && query.indexOf('&') < 0, "one URL-encoded JSON argument only");
        JSONObject payload = new JSONObject(URLDecoder.decode(query.substring(8), "UTF-8"));
        check(payload.length() == 3 && "create_timer".equals(payload.opt("op"))
                && payload.has("requestId") && payload.has("durationMs"), "exact typed payload, no transcript or arbitrary parameters");
        Object raw = payload.opt("durationMs");
        check((raw instanceof Integer || raw instanceof Long) && ((Number) raw).longValue() == duration,
                "duration is integer milliseconds, never inferred from free-form text by server");
        check("POST".equals(request.getRequestMethod()) && request.getDoOutput() && request.outputClosed
                && request.sent.size() == 0, "mutation is one closed empty-body POST");
        check(!request.getInstanceFollowRedirects() && !request.getUseCaches() && !request.getAllowUserInteraction(),
                "no redirects, cache or authentication UI");
        check(request.getConnectTimeout() > 0 && request.getConnectTimeout() <= 1200
                && request.getReadTimeout() > 0 && request.getReadTimeout() <= 15000, "bounded connection and whole operation");
        check("identity".equals(request.getRequestProperty("Accept-Encoding"))
                && "close".equals(request.getRequestProperty("Connection")), "bounded direct local response");
        return payload;
    }
    private static final class Factory implements LocalVoiceCommands.Connections {
        final String body; int opened, status = 200; long declared = -1;
        IOException failure; Fake last;
        Factory(String body) { this.body = body; }
        public HttpURLConnection open(URL address, Proxy proxy) {
            opened++;
            check(proxy == Proxy.NO_PROXY, "timer actuator bypasses system proxy");
            last = new Fake(address, body); last.status = status; last.declared = declared; last.failure = failure;
            return last;
        }
    }
    private static final class Fake extends HttpURLConnection {
        final byte[] body; int status; long declared;
        boolean disconnected, outputClosed, inputRead, inputClosed;
        IOException failure;
        final ByteArrayOutputStream sent = new ByteArrayOutputStream() {
            @Override public void close() { outputClosed = true; }
        };
        Fake(URL address, String body) { super(address); this.body = body.getBytes(StandardCharsets.UTF_8); }
        public void connect() { }
        public boolean usingProxy() { return false; }
        public void disconnect() { disconnected = true; }
        public OutputStream getOutputStream() { return sent; }
        public int getResponseCode() throws IOException { if (failure != null) throw failure; return status; }
        public long getContentLengthLong() { return declared; }
        public InputStream getInputStream() {
            inputRead = true;
            return new ByteArrayInputStream(body) { @Override public void close() { inputClosed = true; } };
        }
    }
}
