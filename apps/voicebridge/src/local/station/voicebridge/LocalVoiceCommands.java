package local.station.voicebridge;

import org.json.JSONObject;
import org.json.JSONArray;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Final-ASR-only local commands. No shell, free-form intents, cloud, logging or retained speech. */
public final class LocalVoiceCommands {
    private static final int MAX_TEXT = 256, MAX_QUERY = 160;
    private static final Pattern TRACK_URL = Pattern.compile("http://127\\.0\\.0\\.1:17686/audio/track-[a-f0-9]{32}\\.mp3");
    private static final Pattern VOLUME = Pattern.compile(
            "(?:громкость|(?:установи|поставь|сделай) громкость)(?: на)? ([а-я0-9 ]+?)(?: (?:процент|процента|процентов)|%)");
    private static final Pattern TIMER = Pattern.compile(
            "(?:(?:поставь|установи|запусти|включи|заведи) таймер(?: на)?|таймер(?: на)?|засеки) (.+)");
    private static final String[] UNITS = {"ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"};
    private static final String[] TEENS = {"десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать"};
    private static final String[] TENS = {"", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто"};

    public enum Kind { MATCH, NO_MATCH, REJECTED }
    public enum Action { VOLUME_UP, VOLUME_DOWN, VOLUME_SET, MUTE, UNMUTE, PAUSE, RESUME, STOP, HOME,
        PLAY_QUERY, NEXT, PREVIOUS, CREATE_TIMER, SMART_STOP, STOP_TIMER, STOP_ALL_TIMERS }

    public static final class Command {
        public final Action action;
        /** 0..100 for VOLUME_SET; -1 for every other action. */
        public final int percent;
        /** Only PLAY_QUERY: bounded search terms, empty for the Agent library; never an URL or shell argument. */
        public final String query;
        /** 5000..86400000 for CREATE_TIMER; -1 for every other action. */
        public final long durationMs;
        private Command(Action action, int percent) { this(action, percent, ""); }
        private Command(Action action, int percent, String query) {
            this(action, percent, query, -1L);
        }
        private Command(Action action, int percent, String query, long durationMs) {
            this.action = action; this.percent = percent; this.query = query; this.durationMs = durationMs;
        }
    }
    public static final class Decision {
        public final Kind kind;
        public final Command command;
        private Decision(Kind kind, Command command) { this.kind = kind; this.command = command; }
        /** Both known and rejected commands must bypass the LLM. */
        public boolean handled() { return kind != Kind.NO_MATCH; }
    }
    public static final class Result {
        public final boolean handled, accepted;
        public final String code;
        public final Action action;
        /** agent_player, station_schedule, station_volume, home, or empty. */
        public final String target;
        /** Measured Agent volume, or -1 if unavailable/not applicable. */
        public final int volumePercent;
        private Result(boolean handled, boolean accepted, String code, Action action, int volumePercent) {
            this.handled = handled; this.accepted = accepted; this.code = code;
            this.action = action; this.volumePercent = volumePercent;
            this.target = action == Action.PAUSE || action == Action.RESUME || action == Action.STOP
                    || action == Action.PLAY_QUERY || action == Action.NEXT || action == Action.PREVIOUS ? "agent_player"
                    : action == Action.CREATE_TIMER || action == Action.SMART_STOP
                    || action == Action.STOP_TIMER || action == Action.STOP_ALL_TIMERS ? "station_schedule"
                    : action == Action.HOME ? "home" : action == null ? "" : "station_volume";
        }
        public static Result accepted(Command command) {
            if (command == null) return result(false, "rejected", null, -1);
            return result(true, "accepted", command.action, -1);
        }
        public static Result unavailable(Command command) {
            return result(false, "executor_unavailable", command == null ? null : command.action, -1);
        }
    }
    /** Trusted typed actuator. Only a bounded PLAY_QUERY search term may accompany its enum. */
    public interface Executor { Result apply(Command command) throws Exception; }
    /** Root supplies the fixed ACTION_MAIN/CATEGORY_HOME Android adapter, never an ASR argument. */
    public interface HomeAction { boolean goHome() throws Exception; }

    private LocalVoiceCommands() { }

    /** Never call on a partial hypothesis: a later word can negate or change a command. */
    public static Decision parseFinal(String finalText) {
        if (finalText == null) return rejected();
        if (finalText.length() > MAX_TEXT)
            return reserved(finalText.substring(0, MAX_TEXT)) ? rejected() : noMatch();
        String text = finalText.toLowerCase(Locale.ROOT).replace('ё', 'е');
        boolean grammarCharacters = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!(c >= 'а' && c <= 'я' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == ' '
                    || c == ',' || c == '.' || c == '!' || c == '?' || c == '%' || c == '-' || c == '\'')) grammarCharacters = false;
        }
        if (!grammarCharacters) return reserved(text) ? rejected() : noMatch();
        text = text.trim();
        if (text.isEmpty()) return noMatch();
        text = text.replaceAll(" +", " ");
        if (text.startsWith("алиса,")) text = text.substring(6).trim();
        else if (text.startsWith("алиса ")) text = text.substring(6).trim();
        if (text.endsWith(".") || text.endsWith("!")) text = text.substring(0, text.length() - 1).trim();
        if (text.startsWith("пожалуйста ")) text = text.substring(11).trim();
        if (text.endsWith(" пожалуйста")) text = text.substring(0, text.length() - 11).trim();
        Action action = null;
        if (oneOf(text, "громче", "сделай громче", "прибавь громкость", "увеличь громкость")) action = Action.VOLUME_UP;
        else if (oneOf(text, "тише", "потише", "сделай тише", "убавь громкость", "уменьши громкость")) action = Action.VOLUME_DOWN;
        else if (oneOf(text, "выключи звук", "убери звук", "без звука")) action = Action.MUTE;
        else if (oneOf(text, "включи звук", "верни звук")) action = Action.UNMUTE;
        else if (oneOf(text, "пауза", "поставь на паузу", "поставь музыку на паузу", "приостанови музыку")) action = Action.PAUSE;
        else if (oneOf(text, "продолжи", "продолжи музыку", "продолжи воспроизведение", "возобнови музыку", "возобнови воспроизведение")) action = Action.RESUME;
        else if ("стоп".equals(text)) action = Action.SMART_STOP;
        else if (oneOf(text, "останови таймер", "отмени таймер", "выключи таймер", "убери таймер")) action = Action.STOP_TIMER;
        else if (oneOf(text, "останови все таймеры", "отмени все таймеры", "выключи все таймеры")) action = Action.STOP_ALL_TIMERS;
        else if (oneOf(text, "останови музыку", "выключи музыку", "останови воспроизведение")) action = Action.STOP;
        else if (oneOf(text, "домой", "на главный экран", "открой главный экран", "вернись на главный экран")) action = Action.HOME;
        else if (oneOf(text, "следующий трек", "следующая песня", "включи следующий трек", "поставь следующий трек",
                "переключи трек", "переключи песню")) action = Action.NEXT;
        else if (oneOf(text, "предыдущий трек", "предыдущая песня", "включи предыдущий трек", "поставь предыдущий трек")) action = Action.PREVIOUS;
        if (action != null) return match(action, -1);
        Matcher volume = VOLUME.matcher(text);
        if (volume.matches()) {
            int percent = percent(volume.group(1));
            return percent >= 0 ? match(Action.VOLUME_SET, percent) : rejected();
        }
        // Timer syntax must be considered before the broad music imperative. The complete
        // duration is consumed; an unknown word, second command or question never executes.
        Matcher timer = TIMER.matcher(text);
        if (timer.matches()) {
            long duration = timerDurationMs(timer.group(1));
            return duration >= 5000L && duration <= 86400000L
                    ? new Decision(Kind.MATCH, new Command(Action.CREATE_TIMER, -1, "", duration)) : rejected();
        }
        if (text.startsWith("включи ") || text.startsWith("поставь ")) {
            String query = text.substring(text.indexOf(' ') + 1);
            boolean explicit = false;
            for (String prefix : new String[]{"песню", "трек", "музыку"}) {
                if (query.equals(prefix)) {
                    return "музыку".equals(prefix) ? play("") : rejected();
                }
                if (query.startsWith(prefix + " ")) {
                    query = query.substring(prefix.length() + 1); explicit = true; break;
                }
            }
            if (validMusicQuery(query) && (explicit || !nonMusicTarget(query))) return play(query);
            return rejected();
        }
        // Whole-string matching above prevents "не", compound requests, questions and quoted
        // commands from accidentally executing. Reserve imperative families for local handling.
        return reserved(text) ? rejected() : noMatch();
    }

    private static boolean validMusicQuery(String query) {
        if (query.isEmpty() || query.length() > MAX_QUERY || query.charAt(0) == '\''
                || query.charAt(query.length() - 1) == '\'') return false;
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (!(c >= 'а' && c <= 'я' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
                    || c == ' ' || c == '-' || c == '\'')) return false;
        }
        // Keep artist names such as "король и шут", but reject another imperative,
        // negation or question anywhere in a title-shaped request. No second action is parsed.
        for (String word : query.split(" +")) {
            if (oneOf(word, "не", "нет", "нельзя", "потом", "затем", "сначала", "после", "одновременно", "также",
                    "что", "как", "почему", "ли", "можно", "можешь", "включить", "поставить")
                    || commandFamily(word)) return false;
        }
        int conjunction = query.indexOf(" и ");
        if (conjunction >= 0 && nonMusicTarget(query.substring(conjunction + 3))) return false;
        return !query.startsWith("и ") && !query.endsWith(" и");
    }
    private static boolean nonMusicTarget(String query) {
        String[] targets = {"свет", "света", "звук", "громкость", "таймер", "будильник", "телевизор", "телек",
                "видео", "фильм", "кинотеатр", "ютуб", "youtube", "rutube", "рутуб", "okko", "окко", "lampa",
                "приложение", "настройки", "экран", "блютуз", "bluetooth", "wifi", "wi fi", "вай фай",
                "интернет", "vpn", "впн", "плейлист", "радио", "следующий", "предыдущий", "на", "и",
                "лампу", "лампочку", "светильник", "розетку", "кондиционер", "обогреватель", "чайник",
                "колонку", "станцию", "камеру", "микрофон", "режим", "игру", "запись"};
        for (String target : targets) if (query.equals(target) || query.startsWith(target + " ")) return true;
        return false;
    }

    public static Result execute(Decision decision, Executor executor) {
        if (decision == null || decision.kind == Kind.REJECTED) return result(false, "rejected", null, -1);
        if (decision.kind == Kind.NO_MATCH) return new Result(false, false, "no_match", null, -1);
        if (executor == null) return result(false, "executor_unavailable", decision.command.action, -1);
        try {
            Result applied = executor.apply(decision.command);
            return applied == null ? result(false, "executor_failure", decision.command.action, -1) : applied;
        } catch (Exception ignored) { return result(false, "executor_failure", decision.command.action, -1); }
    }

    private static boolean commandFamily(String text) {
        String[] prefixes = {"громкость", "громче", "тише", "потише", "пауза", "стоп", "домой",
                "сделай", "установи", "поставь", "прибавь", "увеличь", "убавь", "уменьши", "выключи",
                "включи", "убери", "верни", "продолжи", "возобнови", "останови", "приостанови",
                "открой", "вернись", "назад", "запусти", "переключи", "следующий", "предыдущий",
                "без звука", "на главный экран", "выключай", "включай", "делай", "таймер", "засеки", "заведи", "отмени",
                "останавливай", "отменяй"};
        for (String prefix : prefixes) {
            if (text.equals(prefix)) return true;
            if (text.startsWith(prefix) && text.length() > prefix.length()) {
                char next = text.charAt(prefix.length());
                if (!Character.isLetterOrDigit(next)) return true;
            }
        }
        return false;
    }
    /** Routing-only cleanup never makes an otherwise invalid phrase executable. */
    private static boolean reserved(String source) {
        String text = source.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll(" +", " ").trim();
        int start = 0;
        while (start < text.length() && !Character.isLetterOrDigit(text.charAt(start))) start++;
        text = text.substring(start);
        if (text.startsWith("алиса,")) text = text.substring(6).trim();
        else if (text.startsWith("алиса ")) text = text.substring(6).trim();
        if (text.startsWith("пожалуйста ")) text = text.substring(11).trim();
        if (text.startsWith("не надо ")) text = text.substring(8).trim();
        else if (text.startsWith("не нужно ")) text = text.substring(9).trim();
        else if (text.startsWith("не ")) text = text.substring(3).trim();
        return commandFamily(text);
    }
    private static int percent(String text) {
        if (text.matches("0|[1-9][0-9]?|100")) return Integer.parseInt(text);
        if ("нуль".equals(text)) return 0;
        if ("сто".equals(text)) return 100;
        for (int i = 0; i < 10; i++) if (UNITS[i].equals(text)) return i;
        for (int i = 0; i < 10; i++) if (TEENS[i].equals(text)) return i + 10;
        for (int tens = 2; tens < 10; tens++) {
            if (TENS[tens].equals(text)) return tens * 10;
            for (int unit = 1; unit < 10; unit++)
                if ((TENS[tens] + " " + UNITS[unit]).equals(text)) return tens * 10 + unit;
        }
        return -1;
    }
    /** Strict descending h/m/s components, each supplied once; integers and half-units only. */
    private static long timerDurationMs(String text) {
        if ("полчаса".equals(text)) return 1800000L;
        StringBuilder quantity = new StringBuilder();
        long total = 0L, previousUnit = Long.MAX_VALUE;
        boolean completed = false, conjunction = false;
        for (String token : text.split(" ")) {
            if ("и".equals(token)) {
                if (!completed || quantity.length() != 0 || conjunction) return -1L;
                conjunction = true;
                continue;
            }
            long unit = oneOf(token, "час", "часа", "часов") ? 3600000L
                    : oneOf(token, "минута", "минуту", "минуты", "минут") ? 60000L
                    : oneOf(token, "секунда", "секунду", "секунды", "секунд") ? 1000L : -1L;
            if (unit < 0L) {
                if (quantity.length() != 0) quantity.append(' ');
                quantity.append(token);
                continue;
            }
            if (unit >= previousUnit) return -1L;
            String amount = quantity.toString();
            long component;
            if (amount.isEmpty()) {
                if (!oneOf(token, "час", "минута", "минуту", "секунда", "секунду")) return -1L;
                component = unit;
            } else if (oneOf(amount, "полтора", "полторы")) {
                component = unit + unit / 2L;
            } else {
                int count = timerNumber(amount);
                if (count <= 0) return -1L;
                component = count * unit;
            }
            total += component;
            if (total > 86400000L) return -1L;
            previousUnit = unit;
            quantity.setLength(0);
            completed = true;
            conjunction = false;
        }
        return completed && !conjunction && quantity.length() == 0 ? total : -1L;
    }
    private static int timerNumber(String text) {
        if (text.matches("[1-9][0-9]{0,4}")) return Integer.parseInt(text);
        String normalized = text.replaceAll("(^| )од(?:на|ну)(?= |$)", "$1один")
                .replaceAll("(^| )две(?= |$)", "$1два");
        int small = percent(normalized);
        if (small >= 0) return small;
        String[] hundreds = {"", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот",
                "семьсот", "восемьсот", "девятьсот"};
        for (int i = 1; i < hundreds.length; i++) {
            if (normalized.equals(hundreds[i])) return i * 100;
            if (normalized.startsWith(hundreds[i] + " ")) {
                int rest = percent(normalized.substring(hundreds[i].length() + 1));
                return rest > 0 && rest < 100 ? i * 100 + rest : -1;
            }
        }
        return -1;
    }
    private static boolean oneOf(String text, String... variants) {
        for (String variant : variants) if (text.equals(variant)) return true;
        return false;
    }
    private static Decision match(Action action, int percent) { return new Decision(Kind.MATCH, new Command(action, percent)); }
    private static Decision play(String query) { return new Decision(Kind.MATCH, new Command(Action.PLAY_QUERY, -1, query)); }
    private static Decision rejected() { return new Decision(Kind.REJECTED, null); }
    private static Decision noMatch() { return new Decision(Kind.NO_MATCH, null); }
    private static Result result(boolean accepted, String code, Action action, int volume) {
        return new Result(true, accepted, code, action, volume);
    }

    /** Fixed loopback Agent APIs. Its catalog and allowlisted MP3 URLs still require the PC music server. */
    public static final class AgentExecutor implements Executor {
        private static final int MAX_RESPONSE = 8192, MAX_SEARCH_RESPONSE = 65536, TOTAL_TIMEOUT_MS = 15000;
        private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "local-command-deadline"); thread.setDaemon(true); return thread;
            }
        });
        private final AtomicBoolean busy = new AtomicBoolean();
        private final Connections connections;
        private final HomeAction home;
        private final BooleanSupplier cancelled;
        /** Protected by busy: no other operation can replace its deadline. */
        private long operationDeadline;
        public AgentExecutor(HomeAction home) {
            this(home, () -> false);
        }
        public AgentExecutor(HomeAction home, BooleanSupplier cancelled) {
            this(home, (address, proxy) -> (HttpURLConnection) address.openConnection(proxy), cancelled);
        }
        AgentExecutor(HomeAction home, Connections connections) {
            this(home, connections, () -> false);
        }
        AgentExecutor(HomeAction home, Connections connections, BooleanSupplier cancelled) {
            if (connections == null) throw new IllegalArgumentException("connections_required");
            if (cancelled == null) throw new IllegalArgumentException("cancellation_required");
            this.connections = connections; this.home = home; this.cancelled = cancelled;
        }
        @Override public Result apply(Command command) {
            if (command == null) return result(false, "rejected", null, -1);
            if (!busy.compareAndSet(false, true)) return result(false, "busy", command.action, -1);
            try {
                operationDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TOTAL_TIMEOUT_MS);
                checkCancelled();
                if (command.action == Action.HOME) {
                    boolean accepted = home != null && home.goHome();
                    return result(accepted, accepted ? "accepted" : "home_unavailable", command.action, -1);
                }
                if (command.action == Action.PLAY_QUERY) return playQuery(command);
                if (command.action == Action.CREATE_TIMER) return createTimer(command);
                if (command.action == Action.STOP_TIMER || command.action == Action.STOP_ALL_TIMERS
                        || command.action == Action.SMART_STOP) {
                    Result stopped = stopTimer(command);
                    if (command.action != Action.SMART_STOP || !"timer_not_running".equals(stopped.code)) return stopped;
                    // Only a valid zero-ring acknowledgement permits the usual music stop.
                    // No guessed fallback when the schedule endpoint is unavailable.
                    command = new Command(Action.STOP, -1);
                }
                String path;
                switch (command.action) {
                    case VOLUME_UP: path = "/api/volume?delta=1"; break;
                    case VOLUME_DOWN: path = "/api/volume?delta=-1"; break;
                    case VOLUME_SET: path = "/api/volume?value=" + command.percent; break;
                    case MUTE: path = "/api/mute?on=1"; break;
                    case UNMUTE: path = "/api/mute?on=0"; break;
                    case PAUSE: path = "/api/pause"; break;
                    case RESUME: path = "/api/resume"; break;
                    case STOP: path = "/api/stop"; break;
                    case NEXT: path = "/api/next"; break;
                    case PREVIOUS: path = "/api/previous"; break;
                    default: return result(false, "rejected", command.action, -1);
                }
                if (command.action == Action.PAUSE || command.action == Action.RESUME || command.action == Action.STOP
                        || command.action == Action.NEXT || command.action == Action.PREVIOUS) {
                    JSONObject status = request("/api/status", false);
                    if (!Boolean.TRUE.equals(status.opt("prepared"))) return result(false, "media_unavailable", command.action, -1);
                    if (command.action == Action.NEXT || command.action == Action.PREVIOUS) {
                        int size = integer(status.opt("queueSize")), index = integer(status.opt("queueIndex"));
                        String available = command.action == Action.NEXT ? "hasNext" : "hasPrevious";
                        if (size < 1 || index < 0 || index >= size || !Boolean.TRUE.equals(status.opt(available))
                                || command.action == Action.NEXT && index + 1 >= size
                                || command.action == Action.PREVIOUS && index == 0)
                            return result(false, "queue_unavailable", command.action, -1);
                    }
                }
                checkCancelled();
                JSONObject response = request(path, true);
                if (!Boolean.TRUE.equals(response.opt("ok"))) return result(false, "agent_rejected", command.action, -1);
                int volume = -1;
                if (command.action == Action.VOLUME_UP || command.action == Action.VOLUME_DOWN
                        || command.action == Action.VOLUME_SET || command.action == Action.MUTE || command.action == Action.UNMUTE) {
                    Object raw = response.opt("volume");
                    if (!(raw instanceof Integer || raw instanceof Long) || ((Number) raw).longValue() < 0
                            || ((Number) raw).longValue() > 100) return result(false, "agent_bad_response", command.action, -1);
                    volume = ((Number) raw).intValue();
                }
                return result(true, "accepted", command.action, volume);
            } catch (LocalFailure failure) { return result(false, failure.code, command.action, -1); }
            catch (Exception ignored) { return result(false, "executor_failure", command.action, -1); }
            finally { busy.set(false); }
        }

        private Result stopTimer(Command command) throws Exception {
            String requestId = java.util.UUID.randomUUID().toString();
            JSONObject payload = new JSONObject().put("op", "stop_timer").put("requestId", requestId);
            if (command.action == Action.SMART_STOP) payload.put("ringingOnly", true);
            if (command.action == Action.STOP_ALL_TIMERS) payload.put("all", true);
            checkCancelled();
            JSONObject response = request("/api/schedules?request=" + encode(payload.toString()), true, 32768);
            if (!Boolean.TRUE.equals(response.opt("ok")))
                return result(false, "timer_ambiguous".equals(response.opt("code")) ? "timer_ambiguous"
                        : "agent_rejected", command.action, -1);
            Object stopped = response.opt("stoppedTimers");
            JSONArray entries = response.optJSONArray("entries");
            if (!requestId.equals(response.opt("id")) || !(stopped instanceof Integer || stopped instanceof Long)
                    || ((Number) stopped).longValue() < 0 || ((Number) stopped).longValue() > 32
                    || entries == null || entries.length() > 32)
                return result(false, "agent_bad_response", command.action, -1);
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) return result(false, "agent_bad_response", command.action, -1);
                if (!"timer".equals(entry.opt("type"))) continue;
                if (!(entry.opt("ringing") instanceof Boolean) || !(entry.opt("enabled") instanceof Boolean)
                        || Boolean.TRUE.equals(entry.opt("ringing"))
                        || command.action == Action.STOP_ALL_TIMERS && Boolean.TRUE.equals(entry.opt("enabled")))
                    return result(false, "agent_bad_response", command.action, -1);
            }
            if (((Number) stopped).longValue() == 0) return result(false, "timer_not_running", command.action, -1);
            return result(true, "accepted", command.action == Action.SMART_STOP ? Action.STOP_TIMER : command.action, -1);
        }

        private Result createTimer(Command command) throws Exception {
            if (command.durationMs < 5000 || command.durationMs > 86400000)
                return result(false, "rejected", command.action, -1);
            // One request ID per admitted final utterance, never an automatic retry.
            JSONObject payload = new JSONObject().put("op", "create_timer")
                    .put("requestId", java.util.UUID.randomUUID().toString())
                    .put("durationMs", command.durationMs);
            checkCancelled();
            JSONObject response = request("/api/schedules?request=" + encode(payload.toString()), true, 32768);
            if (!Boolean.TRUE.equals(response.opt("ok")))
                return result(false, "agent_rejected", command.action, -1);
            Object rawId = response.opt("id");
            if (!(rawId instanceof String) || !((String) rawId).matches(
                    "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                return result(false, "agent_bad_response", command.action, -1);
            JSONArray entries = response.optJSONArray("entries");
            if (entries == null || entries.length() > 32)
                return result(false, "agent_bad_response", command.action, -1);
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null || !rawId.equals(entry.opt("id"))) continue;
                Object left = entry.opt("remainingMs");
                boolean confirmed = "timer".equals(entry.opt("type"))
                        && Boolean.TRUE.equals(entry.opt("enabled"))
                        && "scheduled".equals(entry.opt("outcome"))
                        && (left instanceof Long || left instanceof Integer)
                        && ((Number) left).longValue() > 0 && ((Number) left).longValue() <= command.durationMs;
                return result(confirmed, confirmed ? "accepted" : "agent_bad_response", command.action, -1);
            }
            return result(false, "agent_bad_response", command.action, -1);
        }

        private Result playQuery(Command command) throws Exception {
            if (command.query == null || !command.query.isEmpty() && !validMusicQuery(command.query))
                return result(false, "rejected", command.action, -1);
            checkCancelled();
            JSONObject search = request(command.query.isEmpty() ? "/api/library"
                    : "/api/search?q=" + encode(command.query), false, MAX_SEARCH_RESPONSE);
            checkCancelled();
            if (search.has("error")) return result(false, "music_search_unavailable", command.action, -1);
            JSONArray tracks = search.optJSONArray("tracks");
            if (tracks == null || tracks.length() == 0) return result(false, "music_not_found", command.action, -1);
            JSONObject selected = null;
            for (int i = 0; i < tracks.length(); i++) {
                JSONObject track = tracks.optJSONObject(i);
                if (track != null && track.opt("url") instanceof String
                        && TRACK_URL.matcher((String) track.opt("url")).matches()) { selected = track; break; }
            }
            if (selected == null) return result(false, "music_bad_response", command.action, -1);
            String title = metadata(selected.opt("title")), artist = metadata(selected.opt("artist"));
            if (title == null || artist == null) return result(false, "music_bad_response", command.action, -1);
            String path = "/api/play?url=" + encode((String) selected.opt("url"))
                    + "&title=" + encode(title) + "&artist=" + encode(artist);
            checkCancelled();
            JSONObject response = request(path, true);
            return result(Boolean.TRUE.equals(response.opt("ok")), Boolean.TRUE.equals(response.opt("ok"))
                    ? "accepted" : "agent_rejected", command.action, -1);
        }
        private static int integer(Object value) {
            if (!(value instanceof Integer || value instanceof Long)) return -1;
            long number = ((Number) value).longValue();
            return number >= 0 && number <= 10000 ? (int) number : -1;
        }
        private static String metadata(Object value) {
            if (!(value instanceof String) || ((String) value).length() > 256) return null;
            String text = (String) value;
            for (int i = 0; i < text.length(); i++)
                if (Character.isISOControl(text.charAt(i)) || Character.isSurrogate(text.charAt(i))) return null;
            return text;
        }
        private static String encode(String value) throws Exception { return URLEncoder.encode(value, "UTF-8"); }
        private void checkCancelled() throws LocalFailure {
            if (isCancelled()) throw new LocalFailure("cancelled");
            if (System.nanoTime() >= operationDeadline) throw new LocalFailure("agent_unavailable");
        }
        private boolean isCancelled() {
            try { return cancelled.getAsBoolean(); } catch (RuntimeException ignored) { return true; }
        }

        private JSONObject request(String path, boolean mutate) throws LocalFailure {
            return request(path, mutate, MAX_RESPONSE);
        }
        private JSONObject request(String path, boolean mutate, int maxResponse) throws LocalFailure {
            HttpURLConnection connection = null;
            ScheduledFuture<?> deadline = null, cancellation = null;
            final Object lifecycle = new Object();
            final AtomicBoolean completed = new AtomicBoolean(), expired = new AtomicBoolean();
            try {
                checkCancelled();
                connection = connections.open(new URL("http://127.0.0.1:18080" + path), Proxy.NO_PROXY);
                connection.setInstanceFollowRedirects(false); connection.setUseCaches(false); connection.setAllowUserInteraction(false);
                int remaining = (int) Math.min(TOTAL_TIMEOUT_MS, Math.max(1,
                        TimeUnit.NANOSECONDS.toMillis(operationDeadline - System.nanoTime())));
                connection.setConnectTimeout(Math.min(1200, remaining)); connection.setReadTimeout(remaining);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Accept-Encoding", "identity"); connection.setRequestProperty("Connection", "close");
                connection.setRequestMethod(mutate ? "POST" : "GET");
                if (mutate) { connection.setDoOutput(true); connection.setFixedLengthStreamingMode(0); }
                final HttpURLConnection active = connection;
                deadline = DEADLINES.schedule(() -> {
                    synchronized (lifecycle) { if (!completed.get()) { expired.set(true); active.disconnect(); } }
                }, remaining, TimeUnit.MILLISECONDS);
                // Barge/disable can cancel a blocked response read, rather than waiting 15 seconds.
                // This timer owns only this request; finally closes its lifecycle before returning.
                cancellation = DEADLINES.scheduleAtFixedRate(() -> {
                    synchronized (lifecycle) {
                        if (!completed.get() && isCancelled()) { expired.set(true); active.disconnect(); }
                    }
                }, 50, 50, TimeUnit.MILLISECONDS);
                checkCancelled();
                if (mutate) connection.getOutputStream().close();
                if (connection.getResponseCode() != 200) throw new LocalFailure("agent_unavailable");
                checkCancelled();
                if (connection.getContentLengthLong() > maxResponse) throw new LocalFailure("agent_bad_response");
                InputStream input = connection.getInputStream();
                try {
                    ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        checkCancelled();
                        if (expired.get()) throw new LocalFailure("agent_unavailable");
                        if (count > maxResponse - output.size()) throw new LocalFailure("agent_bad_response");
                        output.write(buffer, 0, count);
                    }
                    checkCancelled();
                    if (expired.get()) throw new LocalFailure("agent_unavailable");
                    try { return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8)); }
                    catch (Exception ignored) { throw new LocalFailure("agent_bad_response"); }
                } finally { try { input.close(); } catch (Exception ignored) { } }
            } catch (LocalFailure failure) { throw failure; }
            catch (Exception ignored) { throw new LocalFailure(isCancelled() ? "cancelled" : "agent_unavailable"); }
            finally {
                synchronized (lifecycle) { completed.set(true); }
                if (deadline != null) deadline.cancel(false);
                if (cancellation != null) cancellation.cancel(false);
                if (connection != null) try { connection.disconnect(); } catch (Exception ignored) { }
            }
        }
    }
    interface Connections { HttpURLConnection open(URL address, Proxy proxy) throws IOException; }
    private static final class LocalFailure extends IOException {
        private static final long serialVersionUID = 1L;
        final String code;
        LocalFailure(String code) { super(code); this.code = code; }
    }
}
