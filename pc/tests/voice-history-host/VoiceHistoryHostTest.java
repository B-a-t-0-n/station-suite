package local.station.voicebridge;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class VoiceHistoryHostTest {
    public static void main(String[] args) throws Exception {
        File directory = new File(args[0]);
        AtomicLong now = new AtomicLong(1_800_000_000_000L);
        VoiceHistory history = new VoiceHistory(directory, now::get);
        check(history.isEnabled(), "history starts when explicitly requested");
        try {
            history.read("");
            throw new AssertionError("access before token activation");
        } catch (VoiceHistory.AccessDenied expected) { }
        String token = history.accessCode();
        check(token.matches("[0-9A-F]{32}") && token.equals(history.accessCode()), "32 hex code stored");

        history.record("Перемотай на две минуты", "Перемотала", "video_control_applied", "action", 41);
        JSONObject first = waitFor(history, token, "Перемотай на две минуты");
        check(first.getString("answer").equals("Перемотала") && first.getLong("elapsedMs") == 41,
                "entry keeps text and elapsed time");
        check(first.getString("outcome").equals("action") && first.getLong("timeMs") == now.get(), "typed outcome");
        history.record("Какой пароль секретное-значение", "Bearer synthetic-do-not-expose",
                "answered", "answer", 25);
        JSONObject redacted = waitFor(history, token, "[Скрыто: возможно, секретные данные]");
        check(!redacted.toString().contains("секретное-значение") && !redacted.toString().contains("synthetic-do"),
                "likely credentials are omitted");

        VoiceHistory reloaded = new VoiceHistory(directory, now::get);
        check(token.equals(reloaded.accessCode()), "access code persists");
        check(feed(reloaded, token).length() == 2, "history persists");
        try (VoiceHistoryServer server = new VoiceHistoryServer(reloaded, 0)) {
            server.start();
            int port = server.port();
            check(port > 0, "port bound");
            check(request(port, "GET", "/history", "", "").startsWith("HTTP/1.1 401 "), "authentication required");
            String ok = request(port, "GET", "/history", token.toLowerCase(Locale.ROOT), "");
            check(ok.startsWith("HTTP/1.1 200 ") && ok.contains("Cache-Control: no-store")
                    && ok.contains("Перемотай") && !ok.contains(token), "authorized text only");
            check(request(port, "POST", "/history", token, "").startsWith("HTTP/1.1 405 "), "wrong method");
            check(request(port, "GET", "/history?token=" + token, "", "").startsWith("HTTP/1.1 404 "), "query rejected");
            check(request(port, "GET", "/history", token, "Origin: http://other\r\n")
                    .startsWith("HTTP/1.1 400 "), "unexpected browser origin rejected");
            check(request(port, "POST", "/history/clear", token, "Content-Length: 3\r\n")
                    .startsWith("HTTP/1.1 400 "), "body rejected");
            check(request(port, "POST", "/history/clear", "", "Content-Length: 0\r\n")
                    .startsWith("HTTP/1.1 401 "), "clear requires authentication");

            reloaded.setEnabled(false);
            reloaded.record("С выключенной историей", "", "accepted", "action", 0);
            check(request(port, "GET", "/history", token, "").contains("\"disabled\""), "opt-out");
            check(!new VoiceHistory(directory, now::get).isEnabled(), "opt-out survives restart");
            reloaded.setEnabled(true);
            check(feed(reloaded, token).length() == 2, "opt-out preserves previous records");

            String newToken = reloaded.rotateAccessCode();
            check(!newToken.equals(token), "rotate creates new access code");
            check(request(port, "GET", "/history", token, "").startsWith("HTTP/1.1 401 "), "rotation revokes old code");
            check(request(port, "POST", "/history/clear", newToken, "Content-Length: 0\r\n")
                    .startsWith("HTTP/1.1 200 "), "authenticated clear");
            check(feed(reloaded, newToken).length() == 0, "clear removes visible history");
            check(!Files.exists(Paths.get(directory.toString(), "voice-history.json")), "clear removes persisted data");

            for (int i = 0; i < 105; i++) {
                String transcript = "Запрос " + i;
                reloaded.record(transcript, "", "accepted", "action", i);
                waitFor(reloaded, newToken, transcript);
            }
            JSONArray recent = feed(reloaded, newToken);
            check(recent.length() == 100 && recent.getJSONObject(99).getString("transcript").equals("Запрос 5"),
                    "entry cap drops oldest data");
            now.addAndGet(TimeUnit.DAYS.toMillis(7) + 1);
            check(feed(reloaded, newToken).length() == 0, "seven-day retention");
            check(!Files.exists(Paths.get(directory.toString(), "voice-history.json")), "expiration prunes persisted data");
        }
        Files.write(new File(directory, "voice-history.json").toPath(), "corrupt".getBytes(StandardCharsets.UTF_8));
        VoiceHistory recovered = new VoiceHistory(directory, now::get);
        String current = recovered.accessCode();
        check(feed(recovered, current).length() == 0, "corrupt records ignored");
        recovered.record("Следующий запрос", "Ответ", "answered", "answer", 20);
        check(waitFor(recovered, current, "Следующий запрос").getString("answer").equals("Ответ"), "fresh write repairs file");
        System.out.println("Voice history retention, persistence, auth and localhost endpoint verified");
    }

    private static JSONArray feed(VoiceHistory history, String token) throws Exception {
        return new JSONObject(history.read(token)).getJSONArray("entries");
    }

    private static JSONObject waitFor(VoiceHistory history, String token, String text) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < until) {
            JSONObject entry = feed(history, token).optJSONObject(0);
            if (entry != null && text.equals(entry.optString("transcript"))) return entry;
            Thread.sleep(10);
        }
        throw new AssertionError("asynchronous history append did not complete");
    }

    private static String request(int port, String method, String target, String token, String headers)
            throws Exception {
        try (Socket connection = new Socket("127.0.0.1", port)) {
            connection.setSoTimeout(2_000);
            String authorization = token.isEmpty() ? "" : "Authorization: Bearer " + token + "\r\n";
            String request = method + " " + target + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n"
                    + authorization + headers + "\r\n";
            connection.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            InputStream input = connection.getInputStream();
            byte[] buffer = new byte[1024];
            int size;
            while ((size = input.read(buffer)) >= 0) output.write(buffer, 0, size);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
