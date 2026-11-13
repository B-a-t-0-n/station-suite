package local.station.agent;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.UUID;

/** Pure, bounded schedule state. The owner must persist a transition before producing sound. */
public final class StationSchedules {
    public static final int MAX_ENTRIES = 32, MAX_REQUESTS = 64;
    public static final long MIN_TIMER_MS = 5000, MAX_TIMER_MS = 86400000;
    public static final long MAX_LATENESS_MS = 300000, MAX_RING_MS = 120000;
    private static final long MAX_WALL_MS = 253402214400000L;
    private final List<Entry> entries = new ArrayList<>();
    private final LinkedHashMap<String, Receipt> requests = new LinkedHashMap<>();
    private String revisionSeed = UUID.randomUUID().toString(), timezone = "";
    private String nativeTimerStopToken = "";
    private long revisionCounter = 1;

    public static final class Now {
        public final long wallMs, elapsedMs;
        public final String bootId;
        private final TimeZone zone;
        public Now(long wallMs, long elapsedMs, String bootId, TimeZone zone) {
            if (wallMs < 0 || wallMs > MAX_WALL_MS || elapsedMs < 0 || elapsedMs > Long.MAX_VALUE - MAX_TIMER_MS
                    || bootId == null || !bootId.matches("[A-Za-z0-9._:-]{1,128}") || zone == null)
                throw new IllegalArgumentException("clock_invalid");
            this.wallMs = wallMs; this.elapsedMs = elapsedMs; this.bootId = bootId;
            this.zone = (TimeZone) zone.clone();
        }
    }
    private static final class Entry {
        String id, type, label = "", outcome = "scheduled", timerBoot = "", ringBoot = "", lastOccurrence = "";
        boolean enabled = true, ringing;
        int volume = 35, hour, minute, daysMask;
        long dueWall, dueElapsed, timerAnchor, nextAt, ringWall, ringElapsed;
        Entry() { }
    }
    private static final class Receipt {
        final String fingerprint, id;
        final int stoppedTimers;
        Receipt(String fingerprint, String id) { this(fingerprint, id, -1); }
        Receipt(String fingerprint, String id, int stoppedTimers) {
            this.fingerprint = fingerprint; this.id = id; this.stoppedTimers = stoppedTimers;
        }
    }
    private static final class Invalid extends Exception {
        private static final long serialVersionUID = 1L;
        final String code;
        Invalid(String code) { super(code); this.code = code; }
    }

    /** Null creates empty state. Invalid persisted state fails closed, never partially loads. */
    public StationSchedules(JSONObject saved) {
        if (saved == null) return;
        try { load(saved); }
        catch (Exception invalid) { throw new IllegalArgumentException("state_invalid"); }
    }

    /** Does not fire entries. Unknown input produces fixed diagnostics with no exception text. */
    public JSONObject execute(JSONObject request, Now now) {
        try {
            if (request == null) throw bad("bad_request");
            String op = string(request, "op", 32);
            if ("list".equals(op)) { keys(request, "op"); return snapshot(now); }
            String requestId = uuid(string(request, "requestId", 36));
            String fingerprint = fingerprint(request);
            Receipt prior = requests.get(requestId);
            if (prior != null) {
                if (!prior.fingerprint.equals(fingerprint)) throw bad("request_conflict");
                JSONObject result = snapshot(now); result.put("id", prior.id);
                if (prior.stoppedTimers >= 0) result.put("stoppedTimers", prior.stoppedTimers);
                return result;
            }
            String id; int stoppedTimers = -1;
            if ("create_timer".equals(op)) {
                keys(request, "op", "requestId", "durationMs", "label", "volumePercent");
                long duration = integer(request, "durationMs", MIN_TIMER_MS, MAX_TIMER_MS);
                Entry created = new Entry(); created.id = UUID.randomUUID().toString(); created.type = "timer";
                attributes(request, created);
                created.dueWall = now.wallMs + duration; created.dueElapsed = now.elapsedMs + duration;
                created.timerAnchor = now.elapsedMs; created.timerBoot = now.bootId;
                capacity(); entries.add(created); id = created.id;
            } else if ("create_alarm".equals(op)) {
                keys(request, "op", "requestId", "hour", "minute", "daysMask", "label", "volumePercent", "enabled");
                Entry created = new Entry(); created.id = UUID.randomUUID().toString(); created.type = "alarm";
                created.hour = (int) integer(request, "hour", 0, 23);
                created.minute = (int) integer(request, "minute", 0, 59);
                created.daysMask = (int) integer(request, "daysMask", 0, 127);
                attributes(request, created);
                if (request.has("enabled")) created.enabled = bool(request, "enabled");
                created.nextAt = created.enabled ? nextAlarm(created, now.wallMs, now.zone) : 0;
                capacity(); entries.add(created); id = created.id;
            } else if ("update_alarm".equals(op)) {
                keys(request, "op", "requestId", "id", "hour", "minute", "daysMask", "label", "volumePercent", "enabled");
                Entry current = find(uuid(string(request, "id", 36)));
                if (!"alarm".equals(current.type)) throw bad("wrong_type");
                if (request.length() <= 3) throw bad("bad_request");
                // Validate a detached copy before replacing live state.
                Entry updated = readEntry(writeEntry(current));
                if (request.has("hour")) updated.hour = (int) integer(request, "hour", 0, 23);
                if (request.has("minute")) updated.minute = (int) integer(request, "minute", 0, 59);
                if (request.has("daysMask")) updated.daysMask = (int) integer(request, "daysMask", 0, 127);
                attributes(request, updated);
                boolean timeChanged = request.has("hour") || request.has("minute") || request.has("daysMask");
                if (request.has("enabled")) updated.enabled = bool(request, "enabled");
                if (timeChanged || request.has("enabled")) {
                    updated.ringing = false; updated.outcome = updated.enabled ? "scheduled" : "cancelled";
                    updated.nextAt = updated.enabled ? nextAlarm(updated, now.wallMs, now.zone) : 0;
                }
                entries.set(entries.indexOf(current), updated); id = updated.id;
            } else if ("set_enabled".equals(op)) {
                keys(request, "op", "requestId", "id", "enabled");
                Entry current = find(uuid(string(request, "id", 36)));
                boolean enabled = bool(request, "enabled");
                if (enabled && "timer".equals(current.type) && !current.enabled) throw bad("timer_finished");
                if (enabled != current.enabled) {
                    current.enabled = enabled; current.ringing = false;
                    current.outcome = enabled ? "scheduled" : "cancelled";
                    if ("alarm".equals(current.type)) current.nextAt = enabled ? nextAlarm(current, now.wallMs, now.zone) : 0;
                } else if (!enabled && current.ringing) { current.ringing = false; current.outcome = "cancelled"; }
                id = current.id;
            } else if ("stop_timer".equals(op)) {
                keys(request, "op", "requestId", "ringingOnly", "all");
                boolean ringingOnly = request.has("ringingOnly") && bool(request, "ringingOnly");
                boolean all = request.has("all") && bool(request, "all");
                if (ringingOnly && all) throw bad("bad_request");
                List<Entry> ringing = new ArrayList<>(), pending = new ArrayList<>();
                for (Entry entry : entries) if ("timer".equals(entry.type)) {
                    if (entry.ringing) ringing.add(entry);
                    else if (entry.enabled) pending.add(entry);
                }
                // Choose the whole transaction before changing any row. A ringing signal
                // takes priority over future timers unless the user explicitly asked all.
                boolean cancelPending = all || !ringingOnly && ringing.isEmpty();
                if (cancelPending && !all && pending.size() > 1) throw bad("timer_ambiguous");
                for (Entry entry : ringing) { entry.ringing = false; entry.enabled = false; entry.outcome = "dismissed"; }
                if (cancelPending) for (Entry entry : pending) {
                    entry.enabled = false; entry.outcome = "cancelled";
                }
                stoppedTimers = ringing.size() + (cancelPending ? pending.size() : 0);
                id = requestId; // Aggregate receipt; this is not a schedule entry ID.
            } else if ("delete".equals(op) || "dismiss".equals(op)) {
                keys(request, "op", "requestId", "id");
                Entry current = find(uuid(string(request, "id", 36)));
                if ("delete".equals(op)) entries.remove(current);
                else if (current.ringing) { current.ringing = false; current.outcome = "dismissed"; }
                id = current.id;
            } else throw bad("unknown_operation");
            requests.put(requestId, new Receipt(fingerprint, id, stoppedTimers));
            while (requests.size() > MAX_REQUESTS) requests.remove(requests.keySet().iterator().next());
            if (timezone.isEmpty()) timezone = now.zone.getID();
            changed();
            JSONObject result = snapshot(now); result.put("id", id);
            if (stoppedTimers >= 0) result.put("stoppedTimers", stoppedTimers);
            return result;
        } catch (Invalid invalid) { return failure(now, invalid.code); }
        catch (Exception invalid) { return failure(now, "bad_request"); }
    }

    /** Transition due/missed/expired occurrences exactly once; no sound or other side effects. */
    public boolean advance(Now now) {
        boolean modified = false;
        String zoneId = now.zone.getID();
        boolean zoneChanged = !timezone.isEmpty() && !timezone.equals(zoneId);
        if (!timezone.equals(zoneId)) { timezone = zoneId; modified = true; }
        for (Entry entry : entries) {
            if (entry.ringing) {
                long age = ringAge(entry, now);
                if (age < 0 || age >= MAX_RING_MS) {
                    entry.ringing = false; entry.outcome = "expired"; modified = true;
                }
            }
            if (!entry.enabled) continue;
            if ("alarm".equals(entry.type) && zoneChanged) {
                entry.nextAt = nextAlarm(entry, now.wallMs, now.zone); modified = true;
            }
            long remaining;
            if ("timer".equals(entry.type)) {
                remaining = timerRemaining(entry, now);
                long wallDue = now.wallMs + remaining;
                if (!entry.timerBoot.equals(now.bootId) || now.elapsedMs < entry.timerAnchor) {
                    entry.timerBoot = now.bootId; entry.timerAnchor = now.elapsedMs;
                    entry.dueElapsed = Math.max(0, now.elapsedMs + remaining); modified = true;
                }
                if (Math.abs(entry.dueWall - wallDue) > 1000) { entry.dueWall = Math.max(0, wallDue); modified = true; }
            } else remaining = entry.nextAt - now.wallMs;
            if (remaining > 0 || entry.ringing) continue;
            boolean timely = remaining >= -MAX_LATENESS_MS;
            if ("timer".equals(entry.type)) entry.enabled = false;
            else {
                entry.lastOccurrence = occurrence(entry.nextAt, now.zone, entry.hour, entry.minute);
                if (entry.daysMask == 0) { entry.enabled = false; entry.nextAt = 0; }
                else entry.nextAt = nextAlarm(entry, now.wallMs, now.zone);
            }
            entry.outcome = timely ? "fired" : "missed";
            entry.ringing = timely;
            if (timely) { entry.ringWall = now.wallMs; entry.ringElapsed = now.elapsedMs; entry.ringBoot = now.bootId; }
            modified = true;
        }
        if (modified) changed();
        return modified;
    }

    public boolean dismissAll(Now now) {
        boolean modified = false;
        for (Entry entry : entries) if (entry.ringing) {
            entry.ringing = false; entry.outcome = "dismissed"; modified = true;
        }
        if (modified) changed();
        return modified;
    }

    /** Read-only visible state; timer remaining uses monotonic time on its originating boot. */
    public JSONObject snapshot(Now now) {
        try {
            JSONArray result = new JSONArray();
            for (Entry entry : entries) {
                JSONObject value = common(entry);
                if ("timer".equals(entry.type)) {
                    long remaining = Math.max(0, timerRemaining(entry, now));
                    value.put("dueAtMs", entry.enabled ? now.wallMs + timerRemaining(entry, now) : entry.dueWall)
                            .put("remainingMs", entry.enabled ? remaining : 0);
                } else value.put("hour", entry.hour).put("minute", entry.minute).put("daysMask", entry.daysMask)
                        .put("nextAtMs", entry.enabled ? entry.nextAt : 0);
                result.put(value);
            }
            return new JSONObject().put("ok", true).put("revision", revisionSeed + ":" + revisionCounter)
                    .put("nowMs", now.wallMs).put("timezone", now.zone.getID()).put("entries", result);
        } catch (Exception impossible) { throw new IllegalStateException("state_invalid"); }
    }

    public JSONArray ringing() {
        JSONArray result = new JSONArray();
        try {
            for (Entry entry : entries) if (entry.ringing) result.put(new JSONObject().put("id", entry.id)
                    .put("type", entry.type).put("label", entry.label).put("volumePercent", entry.volume)
                    .put("ringingAtMs", entry.ringWall));
        } catch (Exception impossible) { throw new IllegalStateException("state_invalid"); }
        return result;
    }

    /** Wall equivalent of earliest elapsed deadline, including ringing expiry. Zero means none. */
    public long nextWakeAtMs(Now now) {
        long delay = Long.MAX_VALUE;
        for (Entry entry : entries) {
            if (entry.ringing) delay = Math.min(delay, Math.max(0, MAX_RING_MS - ringAge(entry, now)));
            if (entry.enabled) delay = Math.min(delay, Math.max(0, "timer".equals(entry.type)
                    ? timerRemaining(entry, now) : entry.nextAt - now.wallMs));
        }
        return delay == Long.MAX_VALUE ? 0 : now.wallMs + delay;
    }

    public JSONObject save() {
        try {
            JSONArray values = new JSONArray(), receipts = new JSONArray();
            for (Entry entry : entries) values.put(writeEntry(entry));
            for (String requestId : requests.keySet()) {
                Receipt receipt = requests.get(requestId);
                JSONObject value = new JSONObject().put("requestId", requestId).put("fingerprint", receipt.fingerprint).put("id", receipt.id);
                if (receipt.stoppedTimers >= 0) value.put("stoppedTimers", receipt.stoppedTimers);
                receipts.put(value);
            }
            return new JSONObject().put("version", 1).put("revisionSeed", revisionSeed)
                    .put("revisionCounter", revisionCounter).put("timezone", timezone)
                    .put("entries", values).put("requests", receipts).put("nativeTimerStopToken", nativeTimerStopToken);
        } catch (Exception impossible) { throw new IllegalStateException("state_invalid"); }
    }

    private void load(JSONObject saved) throws Exception {
        if (integer(saved, "version", 1, 1) != 1) throw bad("state_invalid");
        revisionSeed = uuid(string(saved, "revisionSeed", 36));
        revisionCounter = integer(saved, "revisionCounter", 1, Long.MAX_VALUE);
        timezone = string(saved, "timezone", 128);
        if (saved.has("nativeTimerStopToken")) {
            nativeTimerStopToken = string(saved, "nativeTimerStopToken", 36);
            if (!nativeTimerStopToken.isEmpty()) nativeTimerStopToken = uuid(nativeTimerStopToken);
        }
        JSONArray values = saved.getJSONArray("entries"), history = saved.getJSONArray("requests");
        if (values.length() > MAX_ENTRIES || history.length() > MAX_REQUESTS) throw bad("state_invalid");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < values.length(); i++) {
            Entry entry = readEntry(values.getJSONObject(i));
            if (!ids.add(entry.id)) throw bad("state_invalid");
            entries.add(entry);
        }
        for (int i = 0; i < history.length(); i++) {
            JSONObject receipt = history.getJSONObject(i);
            String requestId = uuid(string(receipt, "requestId", 36));
            String hash = string(receipt, "fingerprint", 64), id = uuid(string(receipt, "id", 36));
            if (!hash.matches("[0-9a-f]{64}") || requests.containsKey(requestId)) throw bad("state_invalid");
            int stoppedTimers = receipt.has("stoppedTimers") ? (int) integer(receipt, "stoppedTimers", 0, MAX_ENTRIES) : -1;
            requests.put(requestId, new Receipt(hash, id, stoppedTimers));
        }
    }
    private static JSONObject common(Entry entry) throws Exception {
        return new JSONObject().put("id", entry.id).put("type", entry.type).put("label", entry.label)
                .put("enabled", entry.enabled).put("ringing", entry.ringing)
                .put("volumePercent", entry.volume).put("outcome", entry.outcome);
    }
    /** Runtime-only durable delivery intent; native transport remains outside this model. */
    String nativeTimerStopToken() { return nativeTimerStopToken; }
    void requestNativeTimerStop() { nativeTimerStopToken = UUID.randomUUID().toString(); changed(); }
    boolean acknowledgeNativeTimerStop(String token) {
        if (token == null || token.isEmpty() || !token.equals(nativeTimerStopToken)) return false;
        nativeTimerStopToken = ""; changed(); return true;
    }
    private static JSONObject writeEntry(Entry entry) throws Exception {
        return common(entry).put("dueWall", entry.dueWall).put("dueElapsed", entry.dueElapsed)
                .put("timerAnchor", entry.timerAnchor).put("timerBoot", entry.timerBoot)
                .put("hour", entry.hour).put("minute", entry.minute).put("daysMask", entry.daysMask)
                .put("nextAt", entry.nextAt).put("lastOccurrence", entry.lastOccurrence)
                .put("ringWall", entry.ringWall).put("ringElapsed", entry.ringElapsed).put("ringBoot", entry.ringBoot);
    }
    private static Entry readEntry(JSONObject value) throws Exception {
        Entry entry = new Entry();
        entry.id = uuid(string(value, "id", 36)); entry.type = string(value, "type", 5);
        if (!"timer".equals(entry.type) && !"alarm".equals(entry.type)) throw bad("state_invalid");
        entry.label = label(value, "label"); entry.enabled = bool(value, "enabled"); entry.ringing = bool(value, "ringing");
        entry.volume = (int) integer(value, "volumePercent", 5, 80);
        entry.outcome = string(value, "outcome", 16);
        if (!oneOf(entry.outcome, "scheduled", "fired", "dismissed", "expired", "missed", "cancelled")) throw bad("state_invalid");
        entry.dueWall = integer(value, "dueWall", 0, MAX_WALL_MS + MAX_TIMER_MS);
        entry.dueElapsed = integer(value, "dueElapsed", 0, Long.MAX_VALUE);
        entry.timerAnchor = integer(value, "timerAnchor", 0, Long.MAX_VALUE);
        entry.timerBoot = string(value, "timerBoot", 128);
        entry.hour = (int) integer(value, "hour", 0, 23); entry.minute = (int) integer(value, "minute", 0, 59);
        entry.daysMask = (int) integer(value, "daysMask", 0, 127);
        entry.nextAt = integer(value, "nextAt", 0, MAX_WALL_MS + MAX_TIMER_MS * 8);
        entry.lastOccurrence = string(value, "lastOccurrence", 64);
        entry.ringWall = integer(value, "ringWall", 0, MAX_WALL_MS);
        entry.ringElapsed = integer(value, "ringElapsed", 0, Long.MAX_VALUE);
        entry.ringBoot = string(value, "ringBoot", 128);
        if ("timer".equals(entry.type) && entry.enabled && (entry.timerBoot.isEmpty() || entry.dueWall == 0
                || entry.dueElapsed < entry.timerAnchor)) throw bad("state_invalid");
        if ("alarm".equals(entry.type) && entry.enabled && entry.nextAt == 0) throw bad("state_invalid");
        if (entry.ringing && (!"fired".equals(entry.outcome) || entry.ringBoot.isEmpty())) throw bad("state_invalid");
        return entry;
    }
    private static long timerRemaining(Entry entry, Now now) {
        return entry.timerBoot.equals(now.bootId) && now.elapsedMs >= entry.timerAnchor
                ? entry.dueElapsed - now.elapsedMs : entry.dueWall - now.wallMs;
    }
    private static long ringAge(Entry entry, Now now) {
        return entry.ringBoot.equals(now.bootId) && now.elapsedMs >= entry.ringElapsed
                ? now.elapsedMs - entry.ringElapsed : now.wallMs - entry.ringWall;
    }
    private static long nextAlarm(Entry entry, long after, TimeZone zone) {
        Calendar day = Calendar.getInstance(zone, Locale.ROOT); day.setTimeInMillis(after);
        day.set(Calendar.HOUR_OF_DAY, 12); day.set(Calendar.MINUTE, 0); day.set(Calendar.SECOND, 0); day.set(Calendar.MILLISECOND, 0);
        for (int offset = 0; offset <= 8; offset++) {
            Calendar candidate = (Calendar) day.clone(); candidate.add(Calendar.DAY_OF_MONTH, offset);
            int weekday = (candidate.get(Calendar.DAY_OF_WEEK) + 5) % 7;
            if (entry.daysMask != 0 && (entry.daysMask & (1 << weekday)) == 0) continue;
            candidate.set(Calendar.HOUR_OF_DAY, entry.hour); candidate.set(Calendar.MINUTE, entry.minute);
            long at = candidate.getTimeInMillis();
            if (at > after && !entry.lastOccurrence.equals(occurrence(at, zone, entry.hour, entry.minute))) return at;
        }
        throw new IllegalStateException("calendar_invalid");
    }
    private static String occurrence(long wall, TimeZone zone, int hour, int minute) {
        Calendar date = Calendar.getInstance(zone, Locale.ROOT); date.setTimeInMillis(wall);
        return date.get(Calendar.YEAR) + "-" + date.get(Calendar.MONTH) + "-" + date.get(Calendar.DAY_OF_MONTH) + "-" + hour + "-" + minute;
    }
    private static void attributes(JSONObject request, Entry entry) throws Exception {
        if (request.has("label")) entry.label = label(request, "label");
        if (request.has("volumePercent")) entry.volume = (int) integer(request, "volumePercent", 5, 80);
    }
    private static String label(JSONObject object, String key) throws Exception {
        String value = string(object, key, 48);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT) throw bad("bad_label");
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw bad("bad_label");
            } else if (Character.isLowSurrogate(c)) throw bad("bad_label");
        }
        return value.trim();
    }
    private Entry find(String id) throws Invalid {
        for (Entry entry : entries) if (entry.id.equals(id)) return entry;
        throw bad("not_found");
    }
    private void capacity() throws Invalid { if (entries.size() >= MAX_ENTRIES) throw bad("limit_reached"); }
    private void changed() {
        if (revisionCounter == Long.MAX_VALUE) { revisionSeed = UUID.randomUUID().toString(); revisionCounter = 1; }
        else revisionCounter++;
    }
    private JSONObject failure(Now now, String code) {
        try { return snapshot(now).put("ok", false).put("code", code).put("error", message(code)); }
        catch (Exception impossible) { throw new IllegalStateException("state_invalid"); }
    }
    private static String message(String code) {
        if ("not_found".equals(code)) return "Расписание не найдено";
        if ("request_conflict".equals(code)) return "Этот запрос уже использован для другого действия";
        if ("limit_reached".equals(code)) return "Достигнут предел: 32 расписания";
        if ("timer_finished".equals(code)) return "Создайте новый таймер";
        if ("timer_ambiguous".equals(code)) return "Установлено несколько таймеров. Уточните, какой остановить";
        if ("wrong_type".equals(code)) return "Изменять время можно только у будильника";
        if ("unknown_operation".equals(code)) return "Неизвестная операция расписания";
        if ("bad_label".equals(code)) return "Неверная подпись расписания";
        return "Неверные параметры расписания";
    }
    private static String string(JSONObject value, String key, int maximum) throws Exception {
        Object raw = value.opt(key);
        if (!(raw instanceof String) || ((String) raw).length() > maximum) throw bad("bad_request");
        return (String) raw;
    }
    private static long integer(JSONObject value, String key, long minimum, long maximum) throws Exception {
        Object raw = value.opt(key);
        if (!(raw instanceof Integer || raw instanceof Long)) throw bad("bad_request");
        long number = ((Number) raw).longValue();
        if (number < minimum || number > maximum) throw bad("bad_request");
        return number;
    }
    private static boolean bool(JSONObject value, String key) throws Exception {
        Object raw = value.opt(key); if (!(raw instanceof Boolean)) throw bad("bad_request"); return (Boolean) raw;
    }
    private static String uuid(String value) throws Invalid {
        if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw bad("bad_request");
        return value.toLowerCase(Locale.ROOT);
    }
    private static String fingerprint(JSONObject value) throws Exception {
        TreeSet<String> names = new TreeSet<>(); Iterator<String> keys = value.keys(); while (keys.hasNext()) names.add(keys.next());
        StringBuilder canonical = new StringBuilder();
        for (String name : names) {
            Object raw = value.opt(name);
            if (!(raw instanceof String || raw instanceof Boolean || raw instanceof Integer || raw instanceof Long)) throw bad("bad_request");
            canonical.append(name.length()).append(':').append(name).append(':').append(new JSONArray().put(raw).toString()).append(';');
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(); for (byte part : digest) hex.append(String.format(Locale.ROOT, "%02x", part & 255));
        return hex.toString();
    }
    private static void keys(JSONObject value, String... allowed) throws Invalid {
        Set<String> names = new HashSet<>(); for (String name : allowed) names.add(name);
        Iterator<String> keys = value.keys(); while (keys.hasNext()) if (!names.contains(keys.next())) throw bad("bad_request");
    }
    private static boolean oneOf(String value, String... allowed) { for (String option : allowed) if (value.equals(option)) return true; return false; }
    private static Invalid bad(String code) { return new Invalid(code); }
}
