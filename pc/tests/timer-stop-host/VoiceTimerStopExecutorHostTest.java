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
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** In-memory peer drives exact production AgentExecutor, never a socket. */
public final class VoiceTimerStopExecutorHostTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static LocalVoiceCommands.Result execute(String phrase, Factory peer) {
        return LocalVoiceCommands.execute(LocalVoiceCommands.parseFinal(phrase),
                new LocalVoiceCommands.AgentExecutor(null, peer));
    }
    private static LocalVoiceCommands.Result execute(String phrase, Factory peer, BooleanSupplier foregroundVideo) {
        LocalVoiceCommands.AgentExecutor executor = new LocalVoiceCommands.AgentExecutor(null, peer);
        return LocalVoiceCommands.execute(LocalVoiceCommands.parseFinal(phrase),
                command -> executor.apply(command, foregroundVideo));
    }
    private static void requests(Factory peer, int count) {
        check(peer.requests.size() == count, "expected HTTP count without retry");
        for (Fake request : peer.requests) {
            check(request.disconnected && request.inputClosed, "response resources closed");
            check(!request.getInstanceFollowRedirects() && !request.getUseCaches()
                    && !request.getAllowUserInteraction(), "no redirects/cache/UI");
            check(request.getConnectTimeout() > 0 && request.getConnectTimeout() <= 1200
                    && request.getReadTimeout() > 0 && request.getReadTimeout() <= 15000, "bounded deadline");
            check("127.0.0.1".equals(request.getURL().getHost()) && request.getURL().getPort() == 18080,
                    "only fixed loopback Agent");
        }
    }
    private static void payload(Factory peer, String flag) throws Exception {
        JSONObject body = peer.payload;
        check("stop_timer".equals(body.opt("op")), "typed stop op");
        check(body.getString("requestId").matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "fresh UUID");
        check(body.length() == (flag.isEmpty() ? 2 : 3), "no transcript/duration/id guessed by ASR");
        if (!flag.isEmpty()) check(Boolean.TRUE.equals(body.opt(flag)), "scope flag exactly boolean true");
        Fake first = peer.requests.get(0);
        check("POST".equals(first.getRequestMethod()) && first.getDoOutput() && first.outputClosed
                && first.sent.size() == 0, "zero body local schedule mutation");
    }
    public static void main(String[] ignored) throws Exception {
        for (String phrase : new String[]{"останови таймер", "отмени таймер", "выключи таймер", "убери таймер",
                "стоп", "останови все таймеры", "отмени все таймеры", "выключи все таймеры"}) {
            Factory peer = new Factory();
            LocalVoiceCommands.Result result = execute(phrase, peer);
            check(result.accepted && result.handled && "accepted".equals(result.code)
                    && "station_schedule".equals(result.target), "native timer handled, music untouched");
            check(result.action == (phrase.contains("все") ? LocalVoiceCommands.Action.STOP_ALL_TIMERS
                    : LocalVoiceCommands.Action.STOP_TIMER), "SMART_STOP resolves to actual timer action");
            payload(peer, phrase.equals("стоп") ? "ringingOnly" : phrase.contains("все") ? "all" : "");
            requests(peer, 1);
        }
        Factory idle = new Factory(); idle.stopped = 0;
        LocalVoiceCommands.Result fallback = execute("стоп", idle);
        check(fallback.accepted && fallback.action == LocalVoiceCommands.Action.STOP
                && "agent_player".equals(fallback.target), "zero-ring ACK enables existing music stop");
        payload(idle, "ringingOnly"); requests(idle, 3);
        check("/api/status".equals(idle.requests.get(1).getURL().getPath())
                && "GET".equals(idle.requests.get(1).getRequestMethod())
                && "/api/stop".equals(idle.requests.get(2).getURL().getPath())
                && "POST".equals(idle.requests.get(2).getRequestMethod()), "schedule-first exact fallback order");
        Factory video = new Factory(); video.stopped = 0;
        LocalVoiceCommands.Result deferred = execute("Алиса, стоп", video, () -> {
            check(video.requests.size() == 1 && video.requests.get(0).inputClosed,
                    "visible video checked only after zero ringing timers were confirmed");
            return true;
        });
        check(!deferred.accepted && LocalVoiceCommands.VIDEO_STOP_DEFERRED.equals(deferred.code),
                "visible video defers to the real Okko controller rather than claiming a stop");
        payload(video, "ringingOnly"); requests(video, 1);
        Factory ringing = new Factory();
        check(execute("стоп", ringing, () -> {
            throw new AssertionError("timer must take priority over visible video");
        }).accepted, "confirmed ringing timer stops without examining video");
        requests(ringing, 1);
        Factory uncertain = new Factory(); uncertain.mode = "bad_id"; uncertain.stopped = 0;
        check("agent_bad_response".equals(execute("стоп", uncertain, () -> {
            throw new AssertionError("an invalid ACK cannot route to a player");
        }).code), "uncertain timer state leaves both players untouched");
        requests(uncertain, 1);
        check(LocalVoiceCommands.isSmartStop("Алиса, пожалуйста стоп!") && !LocalVoiceCommands.isSmartStop("стоп?")
                && !LocalVoiceCommands.isSmartStop("останови таймер"), "TV dispatch recognizes only the same exact smart-stop grammar");
        for (String phrase : new String[]{"отмени таймер", "отмени все таймеры"}) {
            Factory peer = new Factory(); peer.stopped = 0;
            LocalVoiceCommands.Result result = execute(phrase, peer);
            check(!result.accepted && "timer_not_running".equals(result.code), "explicit zero never stops music");
            requests(peer, 1);
        }
        Factory music = new Factory();
        check(execute("останови музыку", music).accepted && music.payload == null, "explicit music never mutates schedules");
        requests(music, 2);
        for (String mode : new String[]{"bad_id", "missing_stopped", "string_stopped", "negative", "large",
                "no_entries", "too_many", "bad_entry", "ringing", "enabled_all", "malformed"}) {
            Factory peer = new Factory(); peer.mode = mode;
            String phrase = "enabled_all".equals(mode) ? "отмени все таймеры" : "стоп";
            LocalVoiceCommands.Result result = execute(phrase, peer);
            check(!result.accepted && "agent_bad_response".equals(result.code), "invalid ACK no fallback: " + mode);
            requests(peer, 1);
        }
        Factory ambiguous = new Factory(); ambiguous.mode = "ambiguous";
        LocalVoiceCommands.Result rejected = execute("отмени таймер", ambiguous);
        check(!rejected.accepted && "timer_ambiguous".equals(rejected.code), "ambiguous selection never guesses");
        requests(ambiguous, 1);
        Factory failure = new Factory(); failure.mode = "rejected";
        check("agent_rejected".equals(execute("стоп", failure).code), "server rejection never stops music");
        requests(failure, 1);
        Factory cancelled = new Factory();
        LocalVoiceCommands.Result stopped = LocalVoiceCommands.execute(LocalVoiceCommands.parseFinal("отмени таймер"),
                new LocalVoiceCommands.AgentExecutor(null, cancelled, () -> true));
        check(!stopped.accepted && "cancelled".equals(stopped.code) && cancelled.requests.isEmpty(), "stale ownership has no HTTP");
        System.out.println(assertions + " stop executor assertions passed; fake HTTP only");
    }
    private static final class Factory implements LocalVoiceCommands.Connections {
        final List<Fake> requests = new ArrayList<>();
        JSONObject payload;
        String mode = "";
        int stopped = 1;
        public HttpURLConnection open(URL url, Proxy proxy) throws IOException {
            check(proxy == Proxy.NO_PROXY, "no system proxy");
            try {
                String body;
                if ("/api/schedules".equals(url.getPath())) {
                    check(url.getQuery().startsWith("request=") && url.getQuery().indexOf('&') < 0, "one structured argument");
                    payload = new JSONObject(URLDecoder.decode(url.getQuery().substring(8), "UTF-8"));
                    JSONArray entries = new JSONArray();
                    JSONObject response = new JSONObject().put("ok", true).put("id", payload.getString("requestId"))
                            .put("stoppedTimers", stopped).put("entries", entries);
                    if ("bad_id".equals(mode)) response.put("id", "different");
                    if ("missing_stopped".equals(mode)) response.remove("stoppedTimers");
                    if ("string_stopped".equals(mode)) response.put("stoppedTimers", "1");
                    if ("negative".equals(mode)) response.put("stoppedTimers", -1);
                    if ("large".equals(mode)) response.put("stoppedTimers", 33);
                    if ("no_entries".equals(mode)) response.remove("entries");
                    if ("too_many".equals(mode)) for (int i = 0; i < 33; i++) entries.put(new JSONObject());
                    if ("bad_entry".equals(mode)) entries.put("invalid");
                    if ("ringing".equals(mode) || "enabled_all".equals(mode)) entries.put(new JSONObject()
                            .put("type", "timer").put("enabled", "enabled_all".equals(mode)).put("ringing", "ringing".equals(mode)));
                    if ("ambiguous".equals(mode)) response = new JSONObject().put("ok", false).put("code", "timer_ambiguous");
                    if ("rejected".equals(mode)) response = new JSONObject().put("ok", false);
                    body = "malformed".equals(mode) ? "[bad" : response.toString();
                } else if ("/api/status".equals(url.getPath())) body = "{\"prepared\":true}";
                else if ("/api/stop".equals(url.getPath())) body = "{\"ok\":true}";
                else throw new AssertionError("unexpected path");
                Fake request = new Fake(url, body); requests.add(request); return request;
            } catch (RuntimeException invalid) { throw invalid; }
            catch (Exception invalid) { throw new IOException("fake_invalid"); }
        }
    }
    private static final class Fake extends HttpURLConnection {
        final byte[] body;
        boolean disconnected, outputClosed, inputClosed;
        final ByteArrayOutputStream sent = new ByteArrayOutputStream() {
            @Override public void close() { outputClosed = true; }
        };
        Fake(URL url, String body) { super(url); this.body = body.getBytes(StandardCharsets.UTF_8); }
        public void connect() { }
        public boolean usingProxy() { return false; }
        public void disconnect() { disconnected = true; }
        public int getResponseCode() { return 200; }
        public long getContentLengthLong() { return body.length; }
        public OutputStream getOutputStream() { return sent; }
        public InputStream getInputStream() { return new ByteArrayInputStream(body) {
            @Override public void close() { inputClosed = true; }
        }; }
    }
}
