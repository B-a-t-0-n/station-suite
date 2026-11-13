package local.station.agent;

import java.util.Arrays;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONObject;

/** Main-thread-owned settings/effect lifecycle for the Agent player only. */
final class AgentEqualizer {
    interface Engine {
        int bandCount();
        int minLevelMb();
        int maxLevelMb();
        int centerMilliHz(int band);
        void setLevelMb(int band, int levelMb);
        int levelMb(int band);
        void setEnabled(boolean enabled);
        boolean enabled();
        default boolean hasControl() { return true; }
        void release();
    }
    interface Backend {
        /** A disposable, owned nonzero MediaPlayer session; never starts playback. */
        Engine probe();
        Engine attach(int sessionId);
    }
    interface Store { String load(); boolean save(String value); }
    private static final double[] PRESET_HZ = {60, 230, 910, 3600, 14000};
    private static final class Preset {
        final String id, name, description;
        final int[] anchors;
        final boolean enabled;
        Preset(String id, String name, String description, boolean enabled, int... anchors) {
            this.id = id; this.name = name; this.description = description;
            this.enabled = enabled; this.anchors = anchors;
        }
    }
    private static final Preset[] PRESETS = {
        new Preset("normal", "Обычный", "Исходное звучание без обработки", false, 0, 0, 0, 0, 0),
        new Preset("bass", "Больше баса", "Более глубокие низкие частоты", true, 400, 250, 0, -100, -150),
        new Preset("vocal", "Вокал", "Выразительнее голоса и речь", true, -200, -100, 250, 200, 0),
        new Preset("soft", "Мягкий", "Спокойное звучание с мягкими высокими", true, 100, 50, 0, -150, -300),
        new Preset("energetic", "Энергичный", "Больше объёма и яркости", true, 250, 100, -100, 150, 250),
        new Preset("cinema", "Кино", "Более насыщенный звук и ясная речь", true, 300, 100, 0, 150, 100)
    };
    private final Backend backend;
    private final Store store;
    private String saved;
    private Engine attached;
    private int[] centers, levels;
    private int minimum, maximum;
    private boolean enabled, available;
    private String signature = "";
    private int playerSessionId, attachedSessionId, attachAttempts, failedBand = -1;
    private String diagnosticPhase = "idle", diagnosticReason = "none";

    AgentEqualizer(Backend backend, Store store) {
        if (backend == null || store == null) throw new IllegalArgumentException("dependencies_required");
        this.backend = backend; this.store = store;
        try { saved = store.load(); } catch (RuntimeException ignored) { saved = null; }
    }

    JSONObject execute(JSONObject request) {
        return execute(request, playerSessionId);
    }

    /** A prepared current player prevents a capability probe from hiding failed attachment. */
    JSONObject execute(JSONObject request, int currentPlayerSessionId) {
        try {
            if (request == null || !(request.opt("op") instanceof String)) return status(false, "invalid_request");
            String op = request.getString("op");
            Preset preset = null;
            if ("get".equals(op) || "reset".equals(op)) keys(request, "op");
            else if ("set_enabled".equals(op)) {
                keys(request, "op", "enabled");
                if (!(request.opt("enabled") instanceof Boolean)) return status(false, "invalid_request");
            } else if ("set_band".equals(op)) {
                keys(request, "op", "band", "levelMb");
                if (!integer(request.opt("band")) || !integer(request.opt("levelMb")))
                    return status(false, "invalid_request");
            } else if ("set_preset".equals(op)) {
                keys(request, "op", "preset");
                if (!(request.opt("preset") instanceof String)) return status(false, "invalid_request");
                preset = preset(request.getString("preset"));
                // An unknown choice must not attach an effect, probe capabilities or save anything.
                if (preset == null) return status(false, "invalid_preset");
            } else return status(false, "invalid_request");
            boolean attempted = false;
            if (currentPlayerSessionId != playerSessionId) {
                if (currentPlayerSessionId > 0) { attach(currentPlayerSessionId); attempted = true; } else detach();
            } else if (currentPlayerSessionId > 0 && attached == null && !"get".equals(op)) {
                // Retry on an explicit user edit. Never recreate/restart the user's MediaPlayer.
                attach(currentPlayerSessionId); attempted = true;
            }
            if (attached != null) {
                try { verify(attached, levels, enabled); }
                catch (RuntimeException rejected) { failed(rejected); releaseAttached(); }
            }
            if (currentPlayerSessionId > 0 && attached == null && !"get".equals(op) && !attempted) {
                // A stale effect discovered during this edit can recover within the same request.
                attach(currentPlayerSessionId);
            }
            if (currentPlayerSessionId > 0 && attached == null) {
                // Capabilities and saved values may still be shown, but not claimed as applied.
                ensureCapabilities();
                return status(false, "effect_not_attached");
            }
            if (!ensureCapabilities()) return status(false, "equalizer_unavailable");
            if ("get".equals(op)) return status(true, "");
            int[] next = levels.clone(); boolean nextEnabled = enabled;
            if ("set_enabled".equals(op)) nextEnabled = request.getBoolean("enabled");
            else if ("set_band".equals(op)) {
                long band = ((Number) request.opt("band")).longValue();
                long level = ((Number) request.opt("levelMb")).longValue();
                if (band < 0 || band >= next.length) return status(false, "invalid_band");
                if (level < minimum || level > maximum) return status(false, "invalid_level");
                next[(int) band] = (int) level;
            } else if (preset != null) {
                next = curve(preset); nextEnabled = preset.enabled;
            } else { Arrays.fill(next, 0); nextEnabled = false; }
            String encoded = encode(next, nextEnabled);
            boolean changed = !Arrays.equals(next, levels) || nextEnabled != enabled;
            if (!changed && encoded.equals(saved)) return status(true, "");
            int[] old = levels.clone(); boolean oldEnabled = enabled;
            try { if (attached != null && changed) apply(attached, next, nextEnabled); }
            catch (RuntimeException rejected) { failed(rejected); releaseAttached(); return status(false, "effect_not_attached"); }
            boolean stored;
            try { stored = store.save(encoded); } catch (RuntimeException failed) { stored = false; }
            if (!stored) {
                try { if (attached != null) apply(attached, old, oldEnabled); }
                catch (RuntimeException rejected) { failed(rejected); releaseAttached(); }
                return status(false, "storage_unavailable");
            }
            levels = next; enabled = nextEnabled; saved = encoded;
            diagnosticPhase = attached == null ? "idle" : "attached"; diagnosticReason = "none"; failedBand = -1;
            return status(true, "");
        } catch (Exception invalid) { return status(false, "invalid_request"); }
    }

    /** Reapply after each new owned player session. Failure must not prevent music playback. */
    void attach(int sessionId) {
        releaseAttached(); playerSessionId = sessionId;
        if (sessionId <= 0) { available = false; diagnosticPhase = "session"; diagnosticReason = "invalid_session"; return; }
        attachAttempts++; failedBand = -1;
        Engine engine = null;
        try {
            diagnosticPhase = "create";
            engine = backend.attach(sessionId);
            diagnosticPhase = "capabilities";
            readCapabilities(engine);
            available = true;
            apply(engine, levels, enabled);
            attached = engine; attachedSessionId = sessionId; engine = null;
            diagnosticPhase = "attached"; diagnosticReason = "none"; failedBand = -1;
        } catch (RuntimeException unavailable) { failed(unavailable); }
        finally { release(engine); }
    }

    void detach() {
        releaseAttached(); playerSessionId = 0;
        diagnosticPhase = "idle"; diagnosticReason = "none"; failedBand = -1;
    }
    private void releaseAttached() { Engine old = attached; attached = null; attachedSessionId = 0; release(old); }

    private boolean ensureCapabilities() {
        if (available && centers != null) return true;
        Engine probe = null;
        try { probe = backend.probe(); readCapabilities(probe); available = true; return true; }
        catch (RuntimeException unsupported) { available = false; return false; }
        finally { release(probe); }
    }

    private void readCapabilities(Engine engine) {
        if (engine == null) throw new IllegalArgumentException("engine_missing");
        int count = engine.bandCount(), low = engine.minLevelMb(), high = engine.maxLevelMb();
        if (count < 1 || count > 32 || low < Short.MIN_VALUE || high > Short.MAX_VALUE || low > 0 || high < 0 || low >= high)
            throw new IllegalArgumentException("capabilities_invalid");
        int[] frequencies = new int[count]; StringBuilder shape = new StringBuilder(low + ":" + high);
        for (int i = 0; i < count; i++) {
            frequencies[i] = engine.centerMilliHz(i);
            if (frequencies[i] <= 0 || frequencies[i] > 1000000000)
                throw new IllegalArgumentException("frequency_invalid");
            shape.append(':').append(frequencies[i]);
        }
        String nextSignature = shape.toString();
        if (!nextSignature.equals(signature)) {
            centers = frequencies; minimum = low; maximum = high; signature = nextSignature;
            levels = new int[count]; enabled = false; restore();
        }
    }

    /** A saved curve only applies to the exact engine range and ordered frequency layout. */
    private void restore() {
        if (saved == null || saved.length() > 8192) return;
        try {
            JSONObject state = new JSONObject(saved);
            if (!Integer.valueOf(1).equals(state.opt("version")) || !signature.equals(state.opt("signature"))
                    || !(state.opt("enabled") instanceof Boolean)) return;
            JSONArray gains = state.optJSONArray("levels");
            if (gains == null || gains.length() != levels.length) return;
            int[] restored = new int[levels.length];
            for (int i = 0; i < restored.length; i++) {
                Object gain = gains.opt(i);
                if (!integer(gain) || ((Number) gain).longValue() < minimum || ((Number) gain).longValue() > maximum) return;
                restored[i] = ((Number) gain).intValue();
            }
            levels = restored; enabled = state.getBoolean("enabled");
        } catch (Exception invalid) { /* Invalid/incompatible saved values never reach the effect. */ }
    }

    private String encode(int[] gains, boolean on) throws Exception {
        JSONArray values = new JSONArray(); for (int gain : gains) values.put(gain);
        return new JSONObject().put("version", 1).put("signature", signature)
                .put("enabled", on).put("levels", values).toString();
    }

    private static Preset preset(String id) {
        for (Preset preset : PRESETS) if (preset.id.equals(id)) return preset;
        return null;
    }

    /** Map musical anchors to the actual engine; retain endpoints beyond the anchor range. */
    private int[] curve(Preset preset) {
        int[] result = new int[centers.length];
        for (int band = 0; band < result.length; band++) {
            double hz = centers[band] / 1000.0;
            double level = preset.anchors[0];
            if (hz >= PRESET_HZ[PRESET_HZ.length - 1]) level = preset.anchors[PRESET_HZ.length - 1];
            else if (hz > PRESET_HZ[0]) {
                for (int upper = 1; upper < PRESET_HZ.length; upper++) if (hz <= PRESET_HZ[upper]) {
                    double ratio = Math.log(hz / PRESET_HZ[upper - 1]) / Math.log(PRESET_HZ[upper] / PRESET_HZ[upper - 1]);
                    level = preset.anchors[upper - 1] + ratio * (preset.anchors[upper] - preset.anchors[upper - 1]);
                    break;
                }
            }
            result[band] = (int) Math.max(minimum, Math.min(maximum, Math.round(level)));
        }
        return result;
    }

    private String selectedPreset() {
        if (available && centers != null) for (Preset preset : PRESETS)
            if (enabled == preset.enabled && Arrays.equals(levels, curve(preset))) return preset.id;
        return "custom";
    }

    private void apply(Engine engine, int[] gains, boolean on) {
        // Disable before changing a curve; an enabling request never exposes a partial curve.
        diagnosticPhase = "control";
        if (!engine.hasControl()) throw new IllegalStateException("control_lost");
        diagnosticPhase = "disable";
        engine.setEnabled(false);
        for (int i = 0; i < gains.length; i++) {
            failedBand = i; diagnosticPhase = "band_set";
            engine.setLevelMb(i, gains[i]);
            diagnosticPhase = "band_readback";
            if (engine.levelMb(i) != gains[i]) throw new IllegalStateException("level_not_applied");
        }
        failedBand = -1; diagnosticPhase = "enable";
        engine.setEnabled(on);
        diagnosticPhase = "enable_readback";
        if (engine.enabled() != on) throw new IllegalStateException("enabled_not_applied");
    }

    private void verify(Engine engine, int[] gains, boolean on) {
        diagnosticPhase = "control"; failedBand = -1;
        if (!engine.hasControl()) throw new IllegalStateException("control_lost");
        diagnosticPhase = "enable_readback";
        if (engine.enabled() != on) throw new IllegalStateException("enabled_not_applied");
        diagnosticPhase = "band_readback";
        for (int i = 0; i < gains.length; i++) {
            failedBand = i;
            if (engine.levelMb(i) != gains[i]) throw new IllegalStateException("level_not_applied");
        }
        failedBand = -1; diagnosticPhase = "attached"; diagnosticReason = "none";
    }

    private void failed(RuntimeException failure) {
        diagnosticReason = failure instanceof SecurityException ? "security"
                : failure instanceof UnsupportedOperationException ? "unsupported"
                : failure instanceof IllegalArgumentException ? "bad_value"
                : failure instanceof IllegalStateException ? "state" : "runtime";
    }

    private JSONObject status(boolean ok, String code) {
        JSONObject response = new JSONObject(); JSONArray bands = new JSONArray(), presets = new JSONArray();
        try {
            for (Preset preset : PRESETS) presets.put(new JSONObject().put("id", preset.id)
                    .put("name", preset.name).put("description", preset.description));
            if (available && centers != null) for (int i = 0; i < centers.length; i++)
                bands.put(new JSONObject().put("index", i).put("centerHz", centers[i] / 1000.0).put("levelMb", levels[i]));
            response.put("ok", ok).put("available", available).put("scope", "agent_player")
                    .put("enabled", enabled).put("attached", attached != null)
                    .put("effectiveEnabled", attached != null && enabled)
                    .put("playerSessionId", playerSessionId).put("attachedSessionId", attachedSessionId)
                    .put("attachAttempts", attachAttempts).put("diagnosticPhase", diagnosticPhase)
                    .put("diagnosticReason", diagnosticReason).put("failedBand", failedBand)
                    .put("presets", presets).put("selectedPreset", selectedPreset())
                    .put("minLevelMb", available ? minimum : 0).put("maxLevelMb", available ? maximum : 0).put("bands", bands);
            if (!code.isEmpty()) response.put("code", code);
        } catch (Exception ignored) { }
        return response;
    }
    private static boolean integer(Object value) { return value instanceof Integer || value instanceof Long; }
    private static void keys(JSONObject request, String... allowed) {
        Iterator<String> names = request.keys();
        while (names.hasNext()) {
            String key = names.next(); boolean found = false;
            for (String name : allowed) if (name.equals(key)) found = true;
            if (!found) throw new IllegalArgumentException("unknown_argument");
        }
        if (request.length() != allowed.length) throw new IllegalArgumentException("missing_argument");
    }
    private static void release(Engine engine) { if (engine != null) try { engine.release(); } catch (RuntimeException ignored) { } }
}
