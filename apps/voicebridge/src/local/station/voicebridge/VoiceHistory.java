package local.station.voicebridge;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/** Text-only history, shared by the local runtime, the Station UI and the loopback reader. */
final class VoiceHistory {
    static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final int MAX_HISTORY_BYTES = MAX_RESPONSE_BYTES - 512;
    private static final int LIMIT = 100, RETENTION_DAYS = 7, MAX_TEXT_CHARS = 1000;
    private static final long RETENTION_MS = TimeUnit.DAYS.toMillis(RETENTION_DAYS);
    private static final Pattern CODE = Pattern.compile("[0-9a-fA-F]{32}");
    private static final Pattern LABEL = Pattern.compile("[a-zA-Z0-9_]{1,64}");
    private static final Pattern CREDENTIAL = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])"
            + "(?:(?:bearer|api[ _-]?key|access[ _-]?token|токен|парол\\p{L}*|ключ авторизации|код подтверждения)"
            + "(?![\\p{L}\\p{N}_])|sk[-_][a-z0-9_-]{16,}|gh[pousr]_[a-z0-9_]{16,}|"
            + "eyJ[a-z0-9_-]{10,}\\.[a-z0-9_-]{10,})");
    private static VoiceHistory instance;

    static synchronized VoiceHistory get(Context context) {
        if (instance == null) instance = new VoiceHistory(context.getFilesDir(), System::currentTimeMillis);
        return instance;
    }

    private final File settingsFile, historyFile;
    private final LongSupplier clock;
    private final ThreadPoolExecutor writes = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), runnable -> {
                Thread worker = new Thread(runnable, "StationVoiceHistory");
                worker.setDaemon(true);
                return worker;
            });
    private ArrayDeque<Entry> entries;
    private boolean loadedSettings;
    private volatile boolean enabled = true;
    private volatile long generation;
    private String accessCode = "";

    VoiceHistory(File directory, LongSupplier clock) {
        settingsFile = new File(directory, "voice-history-settings.json");
        historyFile = new File(directory, "voice-history.json");
        this.clock = clock;
        writes.allowCoreThreadTimeOut(true);
    }

    synchronized String accessCode() throws Exception {
        loadSettings();
        return accessCode.isEmpty() ? rotateAccessCode() : accessCode;
    }

    synchronized String rotateAccessCode() throws Exception {
        loadSettings();
        byte[] entropy = new byte[16];
        new SecureRandom().nextBytes(entropy);
        StringBuilder next = new StringBuilder(32);
        for (byte value : entropy) {
            next.append("0123456789ABCDEF".charAt((value & 255) >> 4));
            next.append("0123456789ABCDEF".charAt(value & 15));
        }
        String replacement = next.toString();
        saveSettings(enabled, replacement);
        accessCode = replacement;
        return replacement;
    }

    synchronized boolean isEnabled() {
        loadSettings();
        return enabled;
    }

    synchronized void setEnabled(boolean keepHistory) throws Exception {
        loadSettings();
        if (enabled == keepHistory) return;
        saveSettings(keepHistory, accessCode);
        enabled = keepHistory;
        generation++;
    }

    synchronized void clear() throws IOException {
        Files.deleteIfExists(temporary(historyFile).toPath());
        Files.deleteIfExists(historyFile.toPath());
        generation++;
        entries = new ArrayDeque<>();
    }

    static final class AccessDenied extends Exception {
        private static final long serialVersionUID = 1L;
    }

    static final class Disabled extends Exception {
        private static final long serialVersionUID = 1L;
    }

    synchronized String read(String token) throws Exception {
        authorize(token);
        if (!enabled) throw new Disabled();
        loadEntries();
        if (prune(entries, clock.getAsLong())) persist(entries);
        return "{\"ok\":true,\"enabled\":true,\"retentionDays\":" + RETENTION_DAYS
                + ",\"limit\":" + LIMIT + ",\"entries\":" + encode(entries) + "}";
    }

    synchronized void clear(String token) throws Exception {
        authorize(token);
        clear();
    }

    private void authorize(String token) throws AccessDenied {
        loadSettings();
        if (accessCode.isEmpty() || token == null || !CODE.matcher(token).matches()
                || !MessageDigest.isEqual(accessCode.getBytes(StandardCharsets.US_ASCII),
                        token.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) throw new AccessDenied();
    }

    void record(String transcript, String answer, String code, String outcome, long elapsedMs) {
        if (!enabled || transcript == null || transcript.trim().isEmpty()) return;
        long revision = generation;
        long timeMs = clock.getAsLong();
        try {
            writes.execute(() -> {
                try {
                    append(revision, new Entry(timeMs, transcript, answer, code, outcome, elapsedMs));
                } catch (Exception unavailable) {
                    // History must not interrupt the voice request or expose text through logs.
                }
            });
        } catch (RejectedExecutionException unavailable) {
            // Bound local work even if storage falls behind.
        }
    }

    private synchronized void append(long revision, Entry entry) throws Exception {
        loadSettings();
        if (!enabled || generation != revision || entry.transcript.isEmpty()) return;
        loadEntries();
        ArrayDeque<Entry> updated = new ArrayDeque<>(entries);
        updated.addFirst(entry);
        prune(updated, clock.getAsLong());
        persist(updated);
        entries = updated;
    }

    private void loadSettings() {
        if (loadedSettings) return;
        try {
            if (settingsFile.isFile()) {
                JSONObject settings = new JSONObject(readFile(settingsFile, 1024));
                Object selected = settings.opt("enabled");
                if (!(selected instanceof Boolean)) throw new IOException("history_settings");
                String token = settings.optString("accessCode", "");
                if (!token.isEmpty() && !CODE.matcher(token).matches()) throw new IOException("history_settings");
                enabled = (Boolean) selected;
                accessCode = token.toUpperCase(Locale.ROOT);
            }
        } catch (Exception unavailable) {
            enabled = false;
            accessCode = "";
        }
        loadedSettings = true;
    }

    private void saveSettings(boolean keepHistory, String token) throws Exception {
        atomicWrite(settingsFile, new JSONObject().put("enabled", keepHistory).put("accessCode", token).toString());
    }

    private void loadEntries() {
        if (entries != null) return;
        entries = new ArrayDeque<>();
        if (!historyFile.isFile()) return;
        try {
            JSONArray saved = new JSONArray(readFile(historyFile, MAX_HISTORY_BYTES));
            for (int i = 0; i < Math.min(saved.length(), LIMIT); i++) {
                JSONObject item = saved.optJSONObject(i);
                if (item == null || !(item.opt("transcript") instanceof String)) continue;
                Entry entry = new Entry(item.optLong("timeMs", 0), item.optString("transcript"), item.optString("answer"),
                        item.optString("code"), item.optString("outcome"), item.optLong("elapsedMs", -1));
                if (entry.timeMs > 0 && !entry.transcript.isEmpty()) entries.addLast(entry);
            }
        } catch (Exception unavailable) {
            entries.clear();
        }
    }

    private void persist(ArrayDeque<Entry> records) throws IOException {
        if (records.isEmpty()) Files.deleteIfExists(historyFile.toPath());
        else atomicWrite(historyFile, encode(records));
    }

    private static boolean prune(ArrayDeque<Entry> records, long now) {
        int previousSize = records.size();
        records.removeIf(record -> record.timeMs <= now - RETENTION_MS || record.timeMs > now + RETENTION_MS);
        int bytes = 2;
        for (Entry record : records) bytes += record.bytes + 1;
        while (records.size() > LIMIT || bytes > MAX_HISTORY_BYTES) bytes -= records.removeLast().bytes + 1;
        return records.size() != previousSize;
    }

    private static String encode(ArrayDeque<Entry> records) {
        StringBuilder json = new StringBuilder("[");
        for (Entry record : records) {
            if (json.length() > 1) json.append(',');
            json.append(record.json);
        }
        return json.append(']').toString();
    }

    private static File temporary(File file) {
        return new File(file.getParentFile(), file.getName() + ".tmp");
    }

    private static String readFile(File file, int limit) throws IOException {
        if (file.length() > limit) throw new IOException("history_size");
        byte[] bytes = Files.readAllBytes(file.toPath());
        if (bytes.length > limit) throw new IOException("history_size");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void atomicWrite(File file, String value) throws IOException {
        File temp = temporary(file);
        try {
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(value.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp.toPath());
        }
    }

    private static String label(String value) {
        return value != null && LABEL.matcher(value).matches() ? value : "unknown";
    }

    private static String outcome(String value) {
        switch (label(value)) {
            case "answer": case "action": case "requested": case "clarification":
            case "no_match": case "error": case "cancelled": return value;
            default: return "unknown";
        }
    }

    private static String cleanText(String text) {
        if (text == null) return "";
        int end = Math.min(MAX_TEXT_CHARS, text.length());
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        text = text.substring(0, end).trim();
        return CREDENTIAL.matcher(text).find() ? "[Скрыто: возможно, секретные данные]" : text;
    }

    private static final class Entry {
        final long timeMs;
        final String transcript, json;
        final int bytes;

        Entry(long timeMs, String transcript, String answer, String code, String outcome, long elapsedMs) throws Exception {
            this.timeMs = timeMs;
            this.transcript = cleanText(transcript);
            json = new JSONObject().put("timeMs", timeMs).put("transcript", this.transcript)
                    .put("answer", cleanText(answer)).put("code", label(code)).put("outcome", outcome(outcome))
                    .put("elapsedMs", elapsedMs < 0 || elapsedMs > 600_000 ? -1 : elapsedMs).toString();
            bytes = json.getBytes(StandardCharsets.UTF_8).length;
        }
    }
}
