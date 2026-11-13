package local.station.agent;

import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONTokener;
import java.util.Iterator;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

/** Serialized on-device display persistence and independent passive native readback. */
final class StationDisplaySettings {
    interface Bridge { JSONObject exchange(JSONObject request, long deadline) throws Exception; }
    interface Native { JSONObject read(long deadline) throws Exception; }
    private final Bridge bridge;
    private final Native nativeReader;
    private final ThreadPoolExecutor worker;

    StationDisplaySettings(Bridge bridge, Native nativeReader) {
        this.bridge = bridge; this.nativeReader = nativeReader;
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), action -> {
            Thread thread = new Thread(action, "StationDisplaySettings"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
    static StationDisplaySettings create() {
        return new StationDisplaySettings(new PcDisplaySettingsBridge(), NativeDisplaySettings::read);
    }
    void close() { worker.shutdownNow(); }

    JSONObject execute(JSONObject request) throws Exception {
        final JSONObject valid;
        try { valid = validate(request); }
        catch (Exception invalid) { return unavailable("invalid_display_request"); }
        final long deadline = System.nanoTime() + 3000000000L;
        Future<JSONObject> task;
        try { task = worker.submit(() -> perform(valid, deadline)); }
        catch (java.util.concurrent.RejectedExecutionException busy) { return unavailable("display_busy"); }
        try { return task.get(3200, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) {
            task.cancel(true); Thread.currentThread().interrupt(); return unavailable("display_interrupted");
        } catch (Exception failure) {
            task.cancel(true); return unavailable("display_unavailable");
        }
    }

    private JSONObject perform(JSONObject request, long deadline) throws Exception {
        JSONObject saved = null, current = null;
        String code = "";
        try {
            // Reserve time for native readback if the separate local config process is unavailable.
            saved = validateBridge(bridge.exchange(request, Math.min(deadline, System.nanoTime() + 1600000000L)));
            JSONObject acknowledged = saved.getJSONObject("requested");
            if ("set_clock_size".equals(request.getString("op"))
                    && !request.getString("size").equals(acknowledged.opt("clockSize")))
                throw new IllegalArgumentException("bridge_ack_mismatch");
            if ("set_brightness".equals(request.getString("op"))) {
                JSONObject actual = acknowledged.optJSONObject("brightness");
                if (!brightness(actual) || !request.get("auto").equals(actual.get("auto"))
                        || Math.abs(request.getDouble("value") - actual.getDouble("value")) > .000001)
                    throw new IllegalArgumentException("bridge_ack_mismatch");
            }
            if ("set_music_style".equals(request.getString("op"))
                    && !request.getString("style").equals(acknowledged.opt("musicStyle")))
                throw new IllegalArgumentException("bridge_ack_mismatch");
        } catch (Exception unavailable) { saved = null; code = "local_bridge_unavailable"; }
        boolean get = "get".equals(request.getString("op"));
        long readDeadline = Math.min(deadline, System.nanoTime() + (!get && saved != null ? 800000000L : 1000000000L));
        do {
            try {
                current = nativeReader.read(Math.min(readDeadline, System.nanoTime() + (!get && saved != null ? 200000000L : 1000000000L)));
                if ("native_display_unavailable".equals(code)) code = "";
            } catch (Exception unavailable) { if (current == null && code.isEmpty()) code = "native_display_unavailable"; }
            if (get || saved == null || current != null && matches(current, saved.getJSONObject("requested"))) break;
            long remaining = readDeadline - System.nanoTime(); if (remaining <= 0) break;
            TimeUnit.NANOSECONDS.sleep(Math.min(80000000L, remaining));
        } while (System.nanoTime() < readDeadline && !Thread.currentThread().isInterrupted());
        JSONObject requested = saved == null ? new JSONObject() : saved.getJSONObject("requested");
        boolean nativeAvailable = current != null;
        if (current == null) current = unknownCurrent();
        String state;
        if (!nativeAvailable || saved == null) state = "unavailable";
        else if (requested.length() == 0) state = "defaults";
        else state = matches(current, requested) ? "applied" : "pending";
        JSONObject result = new JSONObject().put("scope", "station_led")
                .put("ok", get ? nativeAvailable || saved != null : saved != null)
                .put("writeAvailable", saved != null).put("nativeAvailable", nativeAvailable)
                .put("state", state).put("current", current).put("requested", requested)
                .put("expiresAtMs", saved == null ? 0 : saved.getLong("expiresAtMs"))
                .put("revision", saved == null ? "" : saved.getString("revision"))
                .put("musicModes", musicModes());
        if (!code.isEmpty()) result.put("code", code);
        return result;
    }

    static JSONObject validate(JSONObject request) throws Exception {
        if (request == null || !(request.opt("op") instanceof String)) throw new IllegalArgumentException();
        String op = request.getString("op");
        if ("get".equals(op)) keys(request, "op");
        else if ("set_clock_size".equals(op)) {
            keys(request, "op", "size");
            if (!clockSize(request.opt("size"))) throw new IllegalArgumentException();
        } else if ("set_brightness".equals(op)) {
            keys(request, "op", "auto", "value");
            if (!brightness(request)) throw new IllegalArgumentException();
        } else if ("set_music_style".equals(op)) {
            keys(request, "op", "style");
            if (!musicStyle(request.opt("style"))) throw new IllegalArgumentException();
        } else throw new IllegalArgumentException();
        return new JSONObject(request.toString());
    }

    static JSONObject validateBridge(JSONObject value) throws Exception {
        if (value == null || !Boolean.TRUE.equals(value.opt("ok"))
                || !"station_display_settings".equals(value.opt("scope"))) throw new IllegalArgumentException();
        JSONObject requested = value.optJSONObject("requested");
        if (requested == null) throw new IllegalArgumentException();
        for (Iterator<String> it = requested.keys(); it.hasNext();) {
            String name = it.next();
            if ("clockSize".equals(name)) { if (!clockSize(requested.opt(name))) throw new IllegalArgumentException(); }
            else if ("musicStyle".equals(name)) { if (!musicStyle(requested.opt(name))) throw new IllegalArgumentException(); }
            else if ("brightness".equals(name)) {
                JSONObject brightness = requested.optJSONObject(name);
                if (!brightness(brightness)) throw new IllegalArgumentException();
                keys(brightness, "auto", "value");
            } else throw new IllegalArgumentException();
        }
        Object expiry = value.opt("expiresAtMs"), revision = value.opt("revision");
        if (!(expiry instanceof Number) || ((Number) expiry).doubleValue() < 0
                || ((Number) expiry).doubleValue() > 9007199254740991d
                || ((Number) expiry).doubleValue() != ((Number) expiry).longValue()
                || !(revision instanceof String) || !((String) revision).matches("[A-Za-z0-9._:-]{0,128}"))
            throw new IllegalArgumentException();
        return new JSONObject().put("requested", new JSONObject(requested.toString()))
                .put("expiresAtMs", ((Number) expiry).longValue()).put("revision", revision);
    }

    static boolean matches(JSONObject current, JSONObject requested) throws Exception {
        if (requested.has("clockSize") && (!current.optBoolean("clockSizeExplicit", false)
                || !requested.getString("clockSize").equals(current.opt("clockSize")))) return false;
        if (requested.has("musicStyle") && (!current.optBoolean("musicStyleExplicit", false)
                || !requested.getString("musicStyle").equals(current.opt("musicStyle")))) return false;
        if (requested.has("brightness")) {
            JSONObject want = requested.getJSONObject("brightness"), actual = current.optJSONObject("brightness");
            if (!brightness(actual) || !want.get("auto").equals(actual.get("auto"))
                    || Math.abs(want.getDouble("value") - actual.getDouble("value")) > .000001) return false;
        }
        return true;
    }
    static boolean clockSize(Object value) { return "small".equals(value) || "middle".equals(value) || "large".equals(value); }
    static boolean musicStyle(Object value) {
        return "clock".equals(value) || "wave".equals(value) || "pads".equals(value)
                || "bricks".equals(value) || "fluid".equals(value) || "random".equals(value);
    }
    static JSONArray musicModes() throws Exception {
        JSONArray modes = new JSONArray();
        String[][] definitions = {
            {"clock", "Часы", "Обычные часы во время музыки."},
            {"wave", "Волны", "Плавная волна, которая движется в такт звуку."},
            {"pads", "Плитки", "Светящиеся плитки реагируют на музыку."},
            {"bricks", "Кирпичики", "Столбики с плавно опускающимися пиками."},
            {"fluid", "Плавный", "Текучие переливы в ритме музыки."},
            {"random", "Автоматически", "Четыре вида анимации сменяются каждые 30 секунд."}
        };
        for (String[] mode : definitions) modes.put(new JSONObject().put("id", mode[0])
                .put("title", mode[1]).put("description", mode[2]).put("previewKind", mode[0]));
        return modes;
    }
    static boolean brightness(JSONObject value) {
        if (value == null || !(value.opt("auto") instanceof Boolean) || !(value.opt("value") instanceof Number)) return false;
        double gain = ((Number) value.opt("value")).doubleValue();
        return !Double.isNaN(gain) && !Double.isInfinite(gain) && gain >= 0 && gain <= 1;
    }
    static void keys(JSONObject value, String... names) {
        if (value.length() != names.length) throw new IllegalArgumentException();
        for (String name : names) if (!value.has(name)) throw new IllegalArgumentException();
    }
    static JSONObject parseObject(String raw) throws Exception {
        JSONTokener parser = new JSONTokener(raw); Object decoded = parser.nextValue();
        if (!(decoded instanceof JSONObject) || parser.nextClean() != 0) throw new IllegalArgumentException();
        return (JSONObject) decoded;
    }
    static JSONObject unknownCurrent() throws Exception {
        return new JSONObject().put("clockSize", "unknown").put("clockSizeExplicit", false)
                .put("musicStyle", "unknown").put("musicStyleExplicit", false);
    }
    static JSONObject unavailable(String code) throws Exception {
        return new JSONObject().put("scope", "station_led").put("ok", false).put("writeAvailable", false)
                .put("nativeAvailable", false).put("state", "unavailable").put("current", unknownCurrent())
                .put("requested", new JSONObject()).put("expiresAtMs", 0).put("revision", "")
                .put("musicModes", musicModes()).put("code", code);
    }
}
