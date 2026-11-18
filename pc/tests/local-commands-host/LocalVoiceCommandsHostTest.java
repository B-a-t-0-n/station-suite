package local.station.voicebridge;

import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** Actual parser/actuator with in-memory connections. No Android UI, socket or playback. */
public final class LocalVoiceCommandsHostTest {
    private static int assertions;
    private static void check(boolean value, String name) {
        assertions++;
        if (!value) throw new AssertionError(name);
    }
    private static LocalVoiceCommands.Decision parse(String text) { return LocalVoiceCommands.parseFinal(text); }
    private static void matches(String text, LocalVoiceCommands.Action action, int percent) {
        LocalVoiceCommands.Decision decision = parse(text);
        check(decision.kind == LocalVoiceCommands.Kind.MATCH && decision.handled(), "whole final command matches");
        check(decision.command.action == action && decision.command.percent == percent, "typed bounded action");
    }
    private static void rejected(String text) {
        LocalVoiceCommands.Decision decision = parse(text);
        check(decision.kind == LocalVoiceCommands.Kind.REJECTED && decision.handled() && decision.command == null,
                "unsafe or unsupported command bypasses LLM without actuator");
        AtomicInteger calls = new AtomicInteger();
        LocalVoiceCommands.Result result = LocalVoiceCommands.execute(decision, command -> {
            calls.incrementAndGet(); return LocalVoiceCommands.Result.accepted(command);
        });
        check(result.handled && !result.accepted && "rejected".equals(result.code) && calls.get() == 0, "rejected text cannot actuate");
    }
    private static String number(int value) {
        String[] unit = {"ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"};
        String[] teen = {"десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать"};
        String[] tens = {"", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто"};
        if (value == 100) return "сто";
        if (value < 10) return unit[value];
        if (value < 20) return teen[value - 10];
        return tens[value / 10] + (value % 10 == 0 ? "" : " " + unit[value % 10]);
    }
    private static String repeat(String text, int count) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < count; i++) value.append(text);
        return value.toString();
    }
    private static String encoded(String text) throws Exception { return URLEncoder.encode(text, "UTF-8"); }
    private static String track(String url, String title, String artist) throws Exception {
        return new JSONObject().put("tracks", new org.json.JSONArray().put(new JSONObject()
                .put("url", url).put("title", title).put("artist", artist))).toString();
    }
    private static void music(String phrase, String query) {
        matches(phrase, LocalVoiceCommands.Action.PLAY_QUERY, -1);
        check(query.equals(parse(phrase).command.query), "only extracted bounded music search terms reach actuator");
    }
    public static void main(String[] args) throws Exception {
        String[][] cases = {
                {"громче", "сделай громче", "прибавь громкость", "увеличь громкость"},
                {"тише", "потише", "сделай тише", "убавь громкость", "уменьши громкость"},
                {"выключи звук", "убери звук", "без звука"}, {"включи звук", "верни звук"},
                {"пауза", "поставь на паузу", "поставь музыку на паузу", "приостанови музыку"},
                {"продолжи", "продолжи музыку", "продолжи воспроизведение", "возобнови музыку", "возобнови воспроизведение"},
                {"останови музыку", "выключи музыку", "останови воспроизведение"},
                {"домой", "на главный экран", "открой главный экран", "вернись на главный экран",
                        "открой главную", "открой главное", "открой главную страницу"}
        };
        LocalVoiceCommands.Action[] actions = {LocalVoiceCommands.Action.VOLUME_UP, LocalVoiceCommands.Action.VOLUME_DOWN,
                LocalVoiceCommands.Action.MUTE, LocalVoiceCommands.Action.UNMUTE, LocalVoiceCommands.Action.PAUSE,
                LocalVoiceCommands.Action.RESUME, LocalVoiceCommands.Action.STOP, LocalVoiceCommands.Action.HOME};
        for (int i = 0; i < cases.length; i++) for (String phrase : cases[i]) {
            matches(phrase, actions[i], -1);
            matches("АЛИСА,  " + phrase.toUpperCase(java.util.Locale.ROOT) + "!", actions[i], -1);
            matches("пожалуйста " + phrase, actions[i], -1);
            matches(phrase + " пожалуйста.", actions[i], -1);
        }
        for (int volume = 0; volume <= 100; volume++) {
            matches("громкость " + volume + " процентов", LocalVoiceCommands.Action.VOLUME_SET, volume);
            matches("Алиса установи громкость на " + number(volume) + " процентов", LocalVoiceCommands.Action.VOLUME_SET, volume);
            matches("поставь громкость " + volume + "%", LocalVoiceCommands.Action.VOLUME_SET, volume);
        }
        matches("сделай громкость нуль процентов", LocalVoiceCommands.Action.VOLUME_SET, 0);
        for (String generic : new String[]{"включи музыку", "поставь музыку", "Алиса, включи музыку пожалуйста."}) music(generic, "");
        for (String[] named : new String[][]{{"включи песню группа крови", "группа крови"},
                {"включи Кино", "кино"}, {"поставь песню Queen", "queen"},
                {"поставь трек AC-DC 2020", "ac-dc 2020"}, {"поставь музыку король и шут", "король и шут"},
                {"включи Queen don't stop me now", "queen don't stop me now"},
                {"включи песню свет", "свет"}, {"Алиса, пожалуйста поставь трек кино!", "кино"}})
            music(named[0], named[1]);
        matches("следующий трек", LocalVoiceCommands.Action.NEXT, -1);
        matches("предыдущий трек", LocalVoiceCommands.Action.PREVIOUS, -1);
        matches("включи следующий трек", LocalVoiceCommands.Action.NEXT, -1);
        matches("поставь предыдущий трек", LocalVoiceCommands.Action.PREVIOUS, -1);
        String[] unsafe = {null, "громкость 101 процентов", "громкость 999999999999999999999 процентов",
                "громкость минус пять процентов", "громкость -5%", "громкость 5.5%", "громкость пять",
                "громкость сто один процентов", "громкость 01 процентов", "громкость 5e1 процентов",
                "громкость на максимум", "сделай громче и выключи звук", "выключи звук потом включи",
                "не выключи звук", "не надо громче", "громче?", "поставь музыку на паузу?",
                "открой настройки", "назад", "запусти ютуб",
                "включи свет", "громче;rm -rf /", "громче && домой", "громче\nдомой",
                "громче\r\n", "\u0000громче", "громче\u0000", "громче\u202e", "громче\u200b",
                "\"громче\"", "громче, тише", "громче! тише", "громче..",
                "открой http://127.0.0.1/api/stop", "включи com.android.settings", "домой?", "алиса громче домой",
                "громче " + repeat("а", 257), "громче $(input keyevent 26)", "на главный экран и назад",
                "включи песню", "поставь трек", "включи музыку и свет", "включи кино и выключи звук",
                "поставь кино потом домой", "включи не кино", "не включи музыку", "включи кино?", "включи кино??",
                "включи песню 'кино'", "\"включи кино\"", "включи http://localhost/api/stop", "включи evil.com",
                "включи кино\r\n", "включи кино\u0000", "включи кино\u200b", "включи кино;домой",
                "включи кино && домой", "включи песню ${HOME}", "включи песню " + repeat("а", 161),
                "включи bluetooth", "включи wifi", "включи лампу", "включи плейлист любимое", "поставь на пять минут",
                "поставь громкость", "поставь будильник", "включи youtube", "включи звук и музыку"};
        for (String text : unsafe) rejected(text);
        for (String question : new String[]{"", "   ", "сколько будет два плюс два", "назови столицу россии",
                "почему музыка звучит тихо", "как работает громкость", "что означает слово пауза",
                "расскажи, как работает станция", "что такое WiFi?", "как обновить YouTube?",
                "Алиса, расскажи, как настроить Wi-Fi.", "не знаешь, какая завтра погода?",
                "Привет! Как дела?", "Explain Bluetooth", "грoмче", "алиса ал truncated", repeat("а", 257)}) {
            LocalVoiceCommands.Decision decision = parse(question);
            AtomicInteger calls = new AtomicInteger();
            LocalVoiceCommands.Result result = LocalVoiceCommands.execute(decision, command -> {
                calls.incrementAndGet(); return LocalVoiceCommands.Result.accepted(command);
            });
            check(decision.kind == LocalVoiceCommands.Kind.NO_MATCH && !decision.handled(), "question remains non-command");
            check(!result.handled && !result.accepted && calls.get() == 0, "question has no local side effect");
        }
        AtomicInteger calls = new AtomicInteger();
        LocalVoiceCommands.Result applied = LocalVoiceCommands.execute(parse("громкость пятьдесят процентов"), command -> {
            check(command.action == LocalVoiceCommands.Action.VOLUME_SET && command.percent == 50, "executor gets typed data only");
            calls.incrementAndGet(); return LocalVoiceCommands.Result.accepted(command);
        });
        check(applied.handled && applied.accepted && calls.get() == 1 && "station_volume".equals(applied.target), "exactly one actuator call");
        applied = LocalVoiceCommands.execute(parse("громче"), command -> { throw new IOException("PRIVATE SPEECH AND KEY"); });
        check(!applied.accepted && "executor_failure".equals(applied.code), "executor errors sanitized");
        check("executor_unavailable".equals(LocalVoiceCommands.execute(parse("громче"), null).code), "missing adapter no fallback");
        check("executor_failure".equals(LocalVoiceCommands.execute(parse("громче"), command -> null).code), "null adapter result safe");
        check("rejected".equals(LocalVoiceCommands.execute(null, null).code), "null decision safe");

        // Concrete local actuator maps only enums and bounded percentages to fixed Agent routes.
        String[] phrases = {"громче", "тише", "громкость 30 процентов", "выключи звук", "включи звук"};
        String[] paths = {"/api/volume?delta=1", "/api/volume?delta=-1", "/api/volume?value=30", "/api/mute?on=1", "/api/mute?on=0"};
        for (int i = 0; i < phrases.length; i++) {
            FakeFactory factory = new FakeFactory(); Fake request = factory.add(paths[i], "{\"ok\":true,\"volume\":30}");
            LocalVoiceCommands.Result result = LocalVoiceCommands.execute(parse(phrases[i]), new LocalVoiceCommands.AgentExecutor(null, factory));
            check(result.accepted && result.handled && result.volumePercent == 30 && "station_volume".equals(result.target), "local volume accepted with measured readback");
            check(factory.opened == 1 && request.disconnected && request.inputClosed, "one request, all resources released");
            requestChecks(request, true);
        }
        for (String phrase : new String[]{"пауза", "продолжи", "останови музыку"}) {
            String endpoint = phrase.equals("пауза") ? "/api/pause" : phrase.equals("продолжи") ? "/api/resume" : "/api/stop";
            FakeFactory factory = new FakeFactory();
            Fake status = factory.add("/api/status", "{\"prepared\":true,\"title\":\"PRIVATE_TRACK\",\"url\":\"PRIVATE_URL\"}");
            Fake mutation = factory.add(endpoint, "{\"ok\":true}");
            LocalVoiceCommands.Result result = LocalVoiceCommands.execute(parse(phrase), new LocalVoiceCommands.AgentExecutor(null, factory));
            check(result.accepted && "agent_player".equals(result.target) && result.volumePercent == -1, "Agent media explicitly scoped");
            check(factory.opened == 2 && status.disconnected && mutation.disconnected, "status admission before one media action");
            requestChecks(status, false); requestChecks(mutation, true);
            FakeFactory idle = new FakeFactory(); idle.add("/api/status", "{\"prepared\":false}");
            result = LocalVoiceCommands.execute(parse(phrase), new LocalVoiceCommands.AgentExecutor(null, idle));
            check(!result.accepted && "media_unavailable".equals(result.code) && idle.opened == 1, "no Agent media means no global media key/fallback");
        }
        for (String prepared : new String[]{"null", "\"true\"", "1"}) {
            FakeFactory factory = new FakeFactory(); factory.add("/api/status", "{\"prepared\":" + prepared + "}");
            applied = LocalVoiceCommands.execute(parse("продолжи"), new LocalVoiceCommands.AgentExecutor(null, factory));
            check("media_unavailable".equals(applied.code) && factory.opened == 1, "media availability strictly boolean");
        }
        AtomicInteger homes = new AtomicInteger(); FakeFactory noHttp = new FakeFactory();
        applied = LocalVoiceCommands.execute(parse("домой"), new LocalVoiceCommands.AgentExecutor(() -> { homes.incrementAndGet(); return true; }, noHttp));
        check(applied.accepted && "home".equals(applied.target) && homes.get() == 1 && noHttp.opened == 0, "HOME only fixed no-argument adapter");
        check("home_unavailable".equals(LocalVoiceCommands.execute(parse("домой"), new LocalVoiceCommands.AgentExecutor(null, noHttp)).code), "HOME never falls back to PC");
        check("home_unavailable".equals(LocalVoiceCommands.execute(parse("домой"), new LocalVoiceCommands.AgentExecutor(() -> false, noHttp)).code), "HOME failure not falsely accepted");

        // No redirects, error-body reads, retries, action chaining or permissive volume responses.
        for (int status : new int[]{301, 302, 401, 404, 500}) {
            FakeFactory factory = new FakeFactory(); Fake request = factory.add(paths[0], "PRIVATE_ERROR"); request.status = status;
            applied = LocalVoiceCommands.execute(parse("громче"), new LocalVoiceCommands.AgentExecutor(null, factory));
            check(!applied.accepted && "agent_unavailable".equals(applied.code), "HTTP failure fixed code");
            check(factory.opened == 1 && !request.errorRead && !request.inputRead && request.disconnected, "failed action no retry or error read");
        }
        for (String response : new String[]{"[]", "PRIVATE_TEXT", "{\"ok\":\"true\",\"volume\":20}", "{\"ok\":false}",
                "{\"ok\":true,\"volume\":-1}", "{\"ok\":true,\"volume\":101}", "{\"ok\":true,\"volume\":20.5}",
                "{\"ok\":true,\"volume\":\"20\"}", "{\"ok\":true}"}) {
            FakeFactory factory = new FakeFactory(); factory.add(paths[0], response);
            applied = LocalVoiceCommands.execute(parse("громче"), new LocalVoiceCommands.AgentExecutor(null, factory));
            check(!applied.accepted && applied.handled && factory.opened == 1, "malformed action result blocks fallback");
            check(!applied.code.contains("PRIVATE"), "no body in result");
        }
        for (boolean declared : new boolean[]{true, false}) {
            FakeFactory factory = new FakeFactory(); Fake request = factory.add(paths[0], repeat("X", 8193));
            if (declared) request.declared = 8193;
            applied = LocalVoiceCommands.execute(parse("громче"), new LocalVoiceCommands.AgentExecutor(null, factory));
            check(!applied.accepted && "agent_bad_response".equals(applied.code) && request.disconnected, "declared/streamed response bound");
        }
        FakeFactory failed = new FakeFactory(); Fake broken = failed.add(paths[0], "{}"); broken.failure = new IOException("PRIVATE_ERROR");
        applied = LocalVoiceCommands.execute(parse("громче"), new LocalVoiceCommands.AgentExecutor(null, failed));
        check(!applied.accepted && "agent_unavailable".equals(applied.code) && failed.opened == 1, "network failure no retry or external route");
        check(broken.disconnected, "network error cleanup");

        // Concurrent actions are rejected, not queued; a blocked completion retains admission.
        FakeFactory blockedFactory = new FakeFactory(); Fake blocked = blockedFactory.add(paths[0], "{\"ok\":true,\"volume\":10}");
        blocked.entered = new CountDownLatch(1); blocked.release = new CountDownLatch(1);
        LocalVoiceCommands.AgentExecutor executor = new LocalVoiceCommands.AgentExecutor(null, blockedFactory);
        AtomicReference<LocalVoiceCommands.Result> workerResult = new AtomicReference<>();
        Thread worker = new Thread(() -> workerResult.set(LocalVoiceCommands.execute(parse("громче"), executor)));
        worker.start(); check(blocked.entered.await(2, TimeUnit.SECONDS), "first typed action in progress");
        applied = LocalVoiceCommands.execute(parse("тише"), executor);
        check(!applied.accepted && "busy".equals(applied.code) && blockedFactory.opened == 1, "no command backlog");
        blocked.release.countDown(); worker.join(2000);
        check(!worker.isAlive() && workerResult.get().accepted && blocked.disconnected, "admission retained until cleanup");
        blockedFactory.add(paths[1], "{\"ok\":true,\"volume\":5}");
        check(LocalVoiceCommands.execute(parse("тише"), executor).accepted && blockedFactory.opened == 2, "next independent final command can execute");
        musicExecutorTests();
        System.out.println("LocalVoiceCommandsHostTest: " + assertions + " assertions passed; fake HTTP/actions only");
    }

    private static void musicExecutorTests() throws Exception {
        String url = "http://127.0.0.1:17686/audio/track-0123456789abcdef0123456789abcdef.mp3";
        String title = "Track &url=http://evil.invalid/#?", artist = "PRIVATE Artist";
        String play = "/api/play?url=" + encoded(url) + "&title=" + encoded(title) + "&artist=" + encoded(artist);
        for (String query : new String[]{"", "кино", "queen 2020"}) {
            FakeFactory factory = new FakeFactory();
            String search = query.isEmpty() ? "/api/library" : "/api/search?q=" + encoded(query);
            JSONObject resultBody = new JSONObject(track(url, title, artist)).put("unused", repeat("a", 9000));
            Fake lookup = factory.add(search, resultBody.toString());
            Fake mutation = factory.add(play, "{\"ok\":true}");
            LocalVoiceCommands.Result result = LocalVoiceCommands.execute(parse("включи " + (query.isEmpty() ? "музыку" : query)),
                    new LocalVoiceCommands.AgentExecutor(null, factory));
            check(result.accepted && result.handled && "agent_player".equals(result.target), "music routes through Agent, never LLM");
            check(factory.opened == 2 && lookup.inputClosed && lookup.disconnected && mutation.disconnected,
                    "one bounded catalog request followed by one playback admission");
            check(mutation.getURL().getQuery().split("&").length == 3, "metadata cannot add query arguments");
            requestChecks(lookup, false); requestChecks(mutation, true);
        }
        for (String invalid : new String[]{"https://192.168.77.1:8899/audio/a.mp3", "http://evil.invalid/audio/a.mp3",
                "http://192.168.77.1:8899@evil.invalid/audio/a.mp3", "http://192.168.77.1:88990/audio/a.mp3",
                "http://192.168.77.1:8899/audio/../a.mp3", "http://192.168.77.1:8899/audio/a/b.mp3",
                "http://192.168.77.1:8899/audio/a.mp3?url=x", "http://192.168.77.1:8899/audio/a.mp3#x",
                "http://192.168.77.1:8899/audio/%2e%2e.mp3", "http://192.168.77.1:8899/audio/a..mp3",
                "http://192.168.77.1:8899/audio/a.mp3\r\n", "http://192.168.77.1:8899/audio/а.mp3",
                "file:///audio/a.mp3", "http://192.168.77.1:8899/audio/" + repeat("a", 129) + ".mp3"}) {
            FakeFactory factory = new FakeFactory(); factory.add("/api/search?q=queen", track(invalid, title, artist));
            LocalVoiceCommands.Result result = LocalVoiceCommands.execute(parse("включи queen"), new LocalVoiceCommands.AgentExecutor(null, factory));
            check(!result.accepted && "music_bad_response".equals(result.code) && factory.opened == 1, "untrusted URL never reaches playback");
        }
        for (String body : new String[]{"{}", "{\"tracks\":[]}", "{\"tracks\":\"bad\"}",
                "{\"error\":\"PRIVATE ERROR\",\"tracks\":[]}", track(url, "bad\r\nmetadata", artist),
                track(url, repeat("a", 257), artist)}) {
            FakeFactory factory = new FakeFactory(); factory.add("/api/search?q=queen", body);
            LocalVoiceCommands.Result result = LocalVoiceCommands.execute(parse("включи queen"), new LocalVoiceCommands.AgentExecutor(null, factory));
            check(!result.accepted && result.handled && factory.opened == 1 && !result.code.contains("PRIVATE"),
                    "missing catalog or invalid metadata cannot play or leak diagnostics");
        }
        for (boolean declared : new boolean[]{true, false}) {
            FakeFactory factory = new FakeFactory(); Fake lookup = factory.add("/api/search?q=queen", repeat("X", 65537));
            if (declared) lookup.declared = 65537;
            LocalVoiceCommands.Result result = LocalVoiceCommands.execute(parse("включи queen"), new LocalVoiceCommands.AgentExecutor(null, factory));
            check(!result.accepted && "agent_bad_response".equals(result.code) && lookup.disconnected && factory.opened == 1,
                    "catalog bounded to 64 KiB, not an arbitrary HTTP proxy");
        }
        for (LocalVoiceCommands.Action action : new LocalVoiceCommands.Action[]{LocalVoiceCommands.Action.NEXT, LocalVoiceCommands.Action.PREVIOUS}) {
            String phrase = action == LocalVoiceCommands.Action.NEXT ? "следующий трек" : "предыдущий трек";
            FakeFactory factory = new FakeFactory();
            factory.add("/api/status", "{\"prepared\":true,\"queueSize\":3,\"queueIndex\":1,\"hasNext\":true,\"hasPrevious\":true}");
            factory.add(action == LocalVoiceCommands.Action.NEXT ? "/api/next" : "/api/previous", "{\"ok\":true}");
            check(LocalVoiceCommands.execute(parse(phrase), new LocalVoiceCommands.AgentExecutor(null, factory)).accepted
                    && factory.opened == 2, "known prepared queue admits one relative track action");
            for (String body : new String[]{"{\"prepared\":false}", "{\"prepared\":true}",
                    "{\"prepared\":true,\"queueSize\":3,\"queueIndex\":1,\"hasNext\":\"true\",\"hasPrevious\":\"true\"}",
                    "{\"prepared\":true,\"queueSize\":3,\"queueIndex\":3,\"hasNext\":true,\"hasPrevious\":true}",
                    "{\"prepared\":true,\"queueSize\":1,\"queueIndex\":0,\"hasNext\":true,\"hasPrevious\":true}"}) {
                FakeFactory idle = new FakeFactory(); idle.add("/api/status", body);
                check(!LocalVoiceCommands.execute(parse(phrase), new LocalVoiceCommands.AgentExecutor(null, idle)).accepted
                        && idle.opened == 1, "unprepared, missing or contradictory queue never mutates media");
            }
        }
        AtomicBoolean cancelled = new AtomicBoolean(true); FakeFactory noRequest = new FakeFactory();
        LocalVoiceCommands.Result result = LocalVoiceCommands.execute(parse("включи queen"),
                new LocalVoiceCommands.AgentExecutor(null, noRequest, cancelled::get));
        check(!result.accepted && "cancelled".equals(result.code) && noRequest.opened == 0, "stale turn does not search or play");
        cancelled.set(false);
        FakeFactory afterSearch = new FakeFactory(); Fake lookup = afterSearch.add("/api/search?q=queen", track(url, title, artist));
        lookup.afterInputClose = () -> cancelled.set(true);
        result = LocalVoiceCommands.execute(parse("включи queen"), new LocalVoiceCommands.AgentExecutor(null, afterSearch, cancelled::get));
        check(!result.accepted && "cancelled".equals(result.code) && afterSearch.opened == 1, "barge after catalog response blocks playback");
        cancelled.set(false);
        FakeFactory inRead = new FakeFactory(); Fake waiting = inRead.add("/api/search?q=queen", track(url, title, artist));
        waiting.entered = new CountDownLatch(1); waiting.release = new CountDownLatch(1); waiting.releaseOnDisconnect = true;
        LocalVoiceCommands.AgentExecutor executor = new LocalVoiceCommands.AgentExecutor(null, inRead, cancelled::get);
        AtomicReference<LocalVoiceCommands.Result> answer = new AtomicReference<>();
        Thread worker = new Thread(() -> answer.set(LocalVoiceCommands.execute(parse("включи queen"), executor)));
        worker.start(); check(waiting.entered.await(2, TimeUnit.SECONDS), "catalog response read is blocked");
        cancelled.set(true); worker.join(1500);
        check(!worker.isAlive() && !answer.get().accepted && "cancelled".equals(answer.get().code)
                && waiting.disconnected && inRead.opened == 1, "bounded cancellation disconnects own in-flight catalog before any play");
        FakeFactory throwing = new FakeFactory();
        result = LocalVoiceCommands.execute(parse("включи queen"), new LocalVoiceCommands.AgentExecutor(null, throwing,
                () -> { throw new IllegalStateException("PRIVATE SPEECH"); }));
        check(!result.accepted && "cancelled".equals(result.code) && throwing.opened == 0, "broken guard fails closed without leaking text");
    }

    private static void requestChecks(Fake request, boolean mutate) throws Exception {
        check((mutate ? "POST" : "GET").equals(request.getRequestMethod()), "mutations use fixed zero-body POST");
        check(!request.getInstanceFollowRedirects() && !request.getUseCaches() && !request.getAllowUserInteraction(), "no redirects/cache/UI");
        check("close".equals(request.getRequestProperty("Connection")) && "identity".equals(request.getRequestProperty("Accept-Encoding")), "local connection and decoding bounded");
        check(request.getConnectTimeout() > 0 && request.getConnectTimeout() <= 1200
                && request.getReadTimeout() > 0 && request.getReadTimeout() <= 15000, "one bounded operation deadline");
        check(request.sent.size() == 0 && (!mutate || request.outputClosed), "no free-form request body");
    }
    private static final class FakeFactory implements LocalVoiceCommands.Connections {
        final List<Fake> requests = new ArrayList<>(); int opened;
        Fake add(String path, String body) throws Exception {
            Fake request = new Fake(new URL("http://127.0.0.1:18080" + path), body); requests.add(request); return request;
        }
        public HttpURLConnection open(URL address, Proxy proxy) {
            if (opened >= requests.size()) throw new AssertionError("unexpected actuator call");
            Fake request = requests.get(opened++);
            check(address.equals(request.getURL()), "only typed allowlisted local route");
            check(proxy == Proxy.NO_PROXY && address.getHost().equals("127.0.0.1") && address.getPort() == 18080, "no system proxy or PC/network address");
            return request;
        }
    }
    private static final class Fake extends HttpURLConnection {
        final byte[] body;
        int status = 200; long declared = -1;
        boolean disconnected, inputRead, inputClosed, errorRead, outputClosed;
        IOException failure; CountDownLatch entered, release;
        boolean releaseOnDisconnect;
        Runnable afterInputClose;
        final ByteArrayOutputStream sent = new ByteArrayOutputStream() {
            @Override public void close() { outputClosed = true; }
        };
        Fake(URL address, String body) { super(address); this.body = body.getBytes(StandardCharsets.UTF_8); }
        public void connect() { }
        public boolean usingProxy() { return false; }
        public void disconnect() { disconnected = true; if (releaseOnDisconnect && release != null) release.countDown(); }
        public OutputStream getOutputStream() { return sent; }
        public int getResponseCode() throws IOException {
            if (failure != null) throw failure;
            if (entered != null) {
                entered.countDown();
                try { if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("blocked_fixture"); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); throw new IOException("interrupted_fixture"); }
            }
            return status;
        }
        public long getContentLengthLong() { return declared; }
        public InputStream getInputStream() {
            inputRead = true;
            return new ByteArrayInputStream(body) { @Override public void close() {
                inputClosed = true; if (afterInputClose != null) afterInputClose.run();
            } };
        }
        public InputStream getErrorStream() { errorRead = true; return new ByteArrayInputStream(body); }
    }
}
