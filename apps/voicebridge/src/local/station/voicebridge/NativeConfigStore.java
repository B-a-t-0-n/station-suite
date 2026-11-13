package local.station.voicebridge;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Private bootstrap template plus atomic, LED-only requested state. No network or fallback. */
final class NativeConfigStore implements NativeConfigProtocol.Source {
    static final String TEMPLATE_FILE = "native-sync.json", DISPLAY_FILE = "display-settings.json";
    static final String LOCAL_VOICE_URL = "ws://127.0.0.1:17682/uni.ws";
    static final String LOCAL_PUSH_URL = "wss://station-config.quasar.yandex.net/station-config-sync", LOCAL_PUSH_SIGN = "station-local-config";
    static final int MAX_TEMPLATE = 262144, MAX_DISPLAY = 1024;
    interface Clock { long nowMillis(); }
    interface PushSubscription { JSONObject get(long nowMillis) throws Exception; }
    interface CommitListener { void changed() throws Exception; }
    private final File template, display;
    private final Clock clock;
    private final PushSubscription push;
    private final CommitListener commits;

    NativeConfigStore(File privateDirectory) throws Exception { this(privateDirectory, System::currentTimeMillis, null, null); }
    NativeConfigStore(File privateDirectory, Clock clock, PushSubscription push) throws Exception {
        this(privateDirectory, clock, push, null);
    }
    NativeConfigStore(File privateDirectory, Clock clock, PushSubscription push, CommitListener commits) throws Exception {
        if (privateDirectory == null || !privateDirectory.isDirectory() || clock == null) throw invalid();
        File directory = privateDirectory.getCanonicalFile();
        template = new File(directory, TEMPLATE_FILE); display = new File(directory, DISPLAY_FILE);
        this.clock = clock; this.push = push; this.commits = commits;
    }
    @Override public synchronized JSONObject sync() throws Exception {
        JSONObject result = validateTemplate(NativeConfigJson.object(read(template, MAX_TEMPLATE), MAX_TEMPLATE));
        JSONObject system = requiredObject(requiredObject(result, "config"), "system_config");
        system.put("xivaSubscribeUrl", LOCAL_PUSH_URL);
        // This firmware reads services inside pushd, while its URL override is top-level.
        objectOrCreate(system, "pushd").put("xivaServices", "quasar-realtime");
        JSONObject document = readDisplay();
        if (document != null && active(document)) merge(result, document.getJSONObject("settings"));
        return result;
    }
    @Override public synchronized JSONObject pushSubscribe() throws Exception {
        if (push == null) {
            long seconds = clock.nowMillis() / 1000;
            if (seconds < 0 || seconds >= Integer.MAX_VALUE) throw invalid();
            return new JSONObject().put("status", "ok").put("sign", LOCAL_PUSH_SIGN)
                    .put("ts", String.valueOf(Math.min((long) Integer.MAX_VALUE, seconds + 3600)));
        }
        JSONObject response = push.get(clock.nowMillis());
        if (response == null || !"ok".equals(response.opt("status"))) throw invalid();
        return NativeConfigJson.copy(response);
    }
    /** Root provisions TEMPLATE_FILE privately; no HTTP endpoint can replace it. */
    static JSONObject validateTemplate(JSONObject value) throws Exception {
        if (value == null || !"ok".equals(value.opt("status"))) throw invalid();
        JSONObject config = requiredObject(value, "config");
        requiredObject(config, "account_config"); requiredObject(config, "device_config");
        JSONObject system = requiredObject(config, "system_config");
        if (!LOCAL_VOICE_URL.equals(system.opt("uniProxyUrl"))) throw invalid();
        if (value.has("hasUpdate") && !Boolean.FALSE.equals(value.opt("hasUpdate"))
                || value.has("hasCriticalUpdate") && !Boolean.FALSE.equals(value.opt("hasCriticalUpdate"))) throw invalid();
        return NativeConfigJson.copy(value);
    }
    @Override public synchronized JSONObject display(JSONObject command) throws Exception {
        if (command == null) throw invalid();
        String op = command.optString("op", ""); JSONObject changes = new JSONObject();
        if ("get".equals(op)) keys(command, "op");
        else if ("set_clock_size".equals(op)) {
            keys(command, "op", "size"); changes.put("clockSize", command.get("size"));
        } else if ("set_brightness".equals(op)) {
            keys(command, "op", "auto", "value");
            changes.put("brightness", new JSONObject().put("auto", command.get("auto")).put("value", command.get("value")));
        } else if ("set_music_style".equals(op)) {
            keys(command, "op", "style"); changes.put("musicStyle", command.get("style"));
        } else throw invalid();
        if (changes.length() > 0) validateSettings(changes);
        JSONObject document = readDisplay();
        boolean refreshQueued = false;
        if (changes.length() > 0) {
            // Refuse writes if bootstrap is missing or incompatible. Never create a partial native config.
            JSONObject baseline = validateTemplate(NativeConfigJson.object(read(template, MAX_TEMPLATE), MAX_TEMPLATE));
            JSONObject settings = document != null && active(document)
                    ? NativeConfigJson.copy(document.getJSONObject("settings")) : new JSONObject();
            for (Iterator<String> names = changes.keys(); names.hasNext();) { String name = names.next(); settings.put(name, changes.get(name)); }
            // Reject incompatible LED containers before acknowledging or committing a request.
            merge(baseline, settings);
            document = validateDisplay(new JSONObject().put("version", 1).put("expiresAtMs", 0).put("settings", settings));
            byte[] encoded = document.toString().getBytes(StandardCharsets.UTF_8);
            if (encoded.length > MAX_DISPLAY) throw invalid();
            atomic(display, encoded);
            JSONObject check = readDisplay();
            if (check == null || !Arrays.equals(encoded, read(display, MAX_DISPLAY))) throw new IllegalStateException("local_config_save_failed");
            document = check;
            if (commits != null) {
                try { commits.changed(); refreshQueued = true; } catch (Exception unavailable) { /* Persistence is already confirmed. */ }
            }
        }
        JSONObject settings = document != null && active(document)
                ? NativeConfigJson.copy(document.getJSONObject("settings")) : new JSONObject();
        JSONObject response = new JSONObject().put("ok", true).put("scope", "station_display_settings")
                .put("requested", settings).put("expiresAtMs", document == null ? 0 : document.getLong("expiresAtMs"))
                .put("revision", document == null ? "" : digest(read(display, MAX_DISPLAY)));
        if (changes.length() > 0) response.put("refreshQueued", refreshQueued);
        return response;
    }
    private JSONObject readDisplay() throws Exception {
        if (!display.exists()) return null;
        return validateDisplay(NativeConfigJson.object(read(display, MAX_DISPLAY), MAX_DISPLAY));
    }
    private boolean active(JSONObject document) throws Exception {
        long expiry = document.getLong("expiresAtMs"); return expiry == 0 || expiry > clock.nowMillis();
    }
    static JSONObject validateDisplay(JSONObject value) throws Exception {
        keys(value, "version", "expiresAtMs", "settings");
        if (!integer(value.opt("version"), 1, 1) || !integer(value.opt("expiresAtMs"), 0, Long.MAX_VALUE)) throw invalid();
        validateSettings(requiredObject(value, "settings")); return NativeConfigJson.copy(value);
    }
    private static void validateSettings(JSONObject settings) throws Exception {
        if (settings.length() == 0) throw invalid();
        for (Iterator<String> names = settings.keys(); names.hasNext();) {
            String name = names.next(); Object value = settings.get(name);
            if ("clockSize".equals(name)) { if (!member(value, "small", "middle", "large")) throw invalid(); }
            else if ("musicStyle".equals(name)) { if (!member(value, "clock", "wave", "pads", "bricks", "fluid", "random")) throw invalid(); }
            else if ("brightness".equals(name)) {
                JSONObject brightness = requiredObject(settings, name); keys(brightness, "auto", "value");
                Object level = brightness.opt("value");
                if (!(brightness.opt("auto") instanceof Boolean) || !(level instanceof Number)) throw invalid();
                double number = ((Number) level).doubleValue();
                if (Double.isNaN(number) || Double.isInfinite(number) || number < 0 || number > 1) throw invalid();
            } else throw invalid();
        }
    }
    private static void merge(JSONObject payload, JSONObject settings) throws Exception {
        JSONObject config = requiredObject(payload, "config"); JSONObject device = requiredObject(config, "device_config");
        JSONObject led = objectOrCreate(device, "led");
        if (settings.has("clockSize")) objectOrCreate(led, "time_visualization").put("size", settings.get("clockSize"));
        if (settings.has("brightness")) {
            JSONObject target = objectOrCreate(led, "brightness"), value = settings.getJSONObject("brightness");
            target.put("auto", value.get("auto")).put("value", value.get("value"));
        }
        if (settings.has("musicStyle")) {
            String style = settings.getString("musicStyle");
            objectOrCreate(led, "music_equalizer_visualization").put("auto", "random".equals(style)).put("style", style);
            JSONObject animation = objectOrCreate(requiredObject(config, "system_config"), "spectrogramAnimation");
            animation.put("enabled", !"clock".equals(style)); JSONObject presets = objectOrCreate(animation, "presets");
            for (String name : new String[]{"wave", "pads", "bricks", "fluid"}) presets.put(name, new JSONObject().put("type", name));
            presets.put("random", new JSONObject().put("type", "random").put("presets", new JSONArray()
                    .put("wave").put("pads").put("bricks").put("fluid")).put("interval", 30));
        }
    }
    private static JSONObject requiredObject(JSONObject value, String key) {
        JSONObject result = value.optJSONObject(key); if (result == null) throw invalid(); return result;
    }
    private static JSONObject objectOrCreate(JSONObject value, String key) throws Exception {
        if (!value.has(key)) value.put(key, new JSONObject()); return requiredObject(value, key);
    }
    private static boolean integer(Object value, long low, long high) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) return false;
        long number = ((Number) value).longValue(); return number >= low && number <= high;
    }
    private static boolean member(Object value, String... values) { return value instanceof String && Arrays.asList(values).contains(value); }
    static void keys(JSONObject value, String... keys) {
        if (value == null || value.length() != keys.length) throw invalid();
        Set<String> expected = new HashSet<>(Arrays.asList(keys));
        for (Iterator<String> names = value.keys(); names.hasNext();) if (!expected.contains(names.next())) throw invalid();
    }
    private static byte[] read(File file, int max) throws Exception {
        if (!file.isFile() || !file.getCanonicalFile().equals(file.getAbsoluteFile())) throw invalid();
        try (FileInputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream result = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) >= 0) {
                if (result.size() + count > max) throw invalid(); result.write(buffer, 0, count);
            }
            return result.toByteArray();
        }
    }
    private static void atomic(File destination, byte[] bytes) throws Exception {
        File temporary = File.createTempFile(".native-display-", ".tmp", destination.getParentFile());
        try {
            if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
                Files.setPosixFilePermissions(temporary.toPath(), EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            else if (!temporary.setReadable(true, true) || !temporary.setWritable(true, true)) throw invalid();
            try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(bytes); output.getFD().sync(); }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit(); }
    }
    private static String digest(byte[] value) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte item : MessageDigest.getInstance("SHA-256").digest(value)) result.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        return result.toString();
    }
    private static IllegalArgumentException invalid() { return NativeConfigJson.invalid(); }
}
