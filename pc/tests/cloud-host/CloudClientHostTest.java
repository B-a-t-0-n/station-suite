package local.station.voicebridge;

import android.content.Context;
import android.content.res.AssetManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;

/** Calls production methods through in-memory HTTPS, including real CA configuration and cleanup. */
public final class CloudClientHostTest {
    private static final String ID = "11111111-2222-4333-8444-555555555555";
    private static final String KEY = "c3ludGhldGljLW5vdC1hLXJlYWwta2V5"; // gitleaks:allow -- Base64-encoded synthetic fixture marker; host test only.
    private static final String TOKEN = "synthetic-access-token";
    private static final String TRANSCRIPT = "Сколько будет два плюс два?";
    private static final String ANSWER = "Два плюс два — четыре.";
    private static int assertions;

    private static synchronized void check(boolean value, String name) {
        assertions++;
        if (!value) throw new AssertionError(name);
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static JSONObject config() throws Exception {
        return new JSONObject().put("gigachat_authorization_key", KEY).put("lab_proxy_port", 17544);
    }
    private static byte[] wav() {
        byte[] audio = new byte[44];
        System.arraycopy(bytes("RIFF"), 0, audio, 0, 4);
        System.arraycopy(bytes("WAVE"), 0, audio, 8, 4);
        return audio;
    }
    private static String understood() throws Exception {
        return new JSONObject().put("transcript", TRANSCRIPT).put("answer", ANSWER).toString();
    }
    private static String chat(String content, String reason) throws Exception {
        return new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                        .put("finish_reason", reason)
                        .put("message", new JSONObject().put("content", content))))
                .put("usage", new JSONObject().put("prompt_tokens", 777).put("completion_tokens", 35)
                        .put("total_tokens", 812).put("precached_prompt_tokens", 12)
                        .put("unknown_secret", KEY)).toString();
    }
    private static Factory fixture(String content, String reason) throws Exception {
        Factory f = new Factory();
        f.add("https://ngw.devices.sberbank.ru:9443/api/v2/oauth", new JSONObject()
                .put("access_token", TOKEN).put("expires_at", System.currentTimeMillis() + 1800000).toString());
        addTurn(f, content, reason);
        return f;
    }
    private static void addTurn(Factory f, String content, String reason) throws Exception {
        f.add("https://api.giga.chat/v1/files", new JSONObject().put("id", ID).toString());
        f.add("https://api.giga.chat/v1/chat/completions", chat(content, reason));
        f.add("https://api.giga.chat/v1/files/" + ID + "/delete", "{}");
    }
    private static CloudClient client(Factory f) { return new CloudClient(new Context(), f); }
    private static void timings(JSONObject result) throws Exception {
        JSONObject timing = result.getJSONObject("timings");
        check(timing.length() == 4, "four safe stage times");
        for (String name : new String[]{"oauthMs", "uploadMs", "audioChatMs", "deleteMs"}) {
            Object value = timing.get(name);
            check(value instanceof Long && ((Long) value).longValue() >= 0, "monotonic stage time");
        }
        check(!timing.toString().contains(TOKEN) && !timing.toString().contains(ID)
                && !timing.toString().contains(TRANSCRIPT), "timing privacy");
    }
    private static void failures(String content, String reason, String code) throws Exception {
        Factory f = fixture(content, reason);
        try { client(f).understandAudio(config(), wav()); throw new AssertionError("expected rejection"); }
        catch (CloudClient.CloudException e) {
            check(code.equals(e.code), "fixed parse/finalization error");
            check(e.getCause() == null && e.getSuppressed().length == 0, "no sensitive exception chain");
            check(!e.toString().contains(content) && !e.toString().contains(KEY)
                    && !e.toString().contains(ID), "safe failure details");
        }
        f.finished();
        check(f.items.get(3).disconnected && f.items.get(3).inputRead, "delete attempted on failure");
    }
    private static String repeat(String text, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(text);
        return result.toString();
    }

    private static void recreatedClients() throws Exception {
        // Exercise actual clients concurrently on cold initialization, then repeatedly recreate
        // them as Android does after disable/enable. The factory reference is the real pool key.
        final Factory first = fixture(understood(), "stop"), second = fixture(understood(), "stop");
        final CloudClient a = client(first), b = client(second);
        check(AssetManager.opens.get() == 0, "TLS factory remains lazy across constructors");
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicReference<Throwable> failed = new AtomicReference<Throwable>();
        Thread one = new Thread(() -> {
            try { start.await(); check(a.understandAudio(config(), wav()).getBoolean("fileDeleted"), "first recreated owner cleanup"); }
            catch (Throwable failure) { failed.compareAndSet(null, failure); }
        });
        Thread two = new Thread(() -> {
            try { start.await(); check(b.understandAudio(config(), wav()).getBoolean("fileDeleted"), "second recreated owner cleanup"); }
            catch (Throwable failure) { failed.compareAndSet(null, failure); }
        });
        one.start(); two.start(); start.countDown(); one.join(5000); two.join(5000);
        check(!one.isAlive() && !two.isAlive() && failed.get() == null, "concurrent first-use shared TLS initialization");
        first.finished(); second.finished();
        SSLSocketFactory shared = first.items.get(0).socketFactory;
        for (Factory f : Arrays.asList(first, second)) {
            check(f.items.get(0).socketFactory == shared && f.items.get(1).socketFactory == shared,
                    "separate clients share exact SSL pool identity");
            check("close".equals(f.items.get(0).getRequestProperty("Connection")), "each OAuth explicitly releases its CONNECT");
        }
        for (int instance = 0; instance < 8; instance++) {
            Factory f = fixture(understood(), "stop");
            CloudClient recreated = client(f);
            check(recreated.understandAudio(config(), wav()).getBoolean("fileDeleted"), "recreated client succeeds and cleans up");
            f.finished();
            check(f.opened == 4, "recreation does not add generation retries or extra OAuth");
            check("close".equals(f.items.get(0).getRequestProperty("Connection")), "recreation cannot retain OAuth keepalive");
            for (Fake request : f.items) {
                check(request.socketFactory == shared, "every recreated request preserves pool identity");
                check(request.disconnected && request.inputClosed, "each recreated wrapper fully released");
                if (request.getURL().getHost().equals("api.giga.chat"))
                    check(request.getRequestProperty("Connection") == null, "main origin stays eligible for reuse");
            }
            addTurn(f, understood(), "stop");
            recreated.understandAudio(config(), wav()); f.finished();
            check(f.opened == 7, "same-client second turn still uses its OAuth cache");
        }
        check(AssetManager.opens.get() == 1, "bundled immutable trust loaded exactly once per process");
    }

    private static void addWarmup(Factory f) throws Exception {
        f.add("https://ngw.devices.sberbank.ru:9443/api/v2/oauth", new JSONObject()
                .put("access_token", TOKEN).put("expires_at", System.currentTimeMillis() + 1800000).toString());
        f.add("https://api.giga.chat/v1/models", "{\"data\":[]}");
        f.add("https://api.speechify.ai/v1/audio/models", "{\"models\":[]}");
    }
    private static JSONObject warmConfig() throws Exception {
        return config().put("speechify_api_key", "synthetic-speech-key");
    }
    private static void warmupSuccessAndIsolation() throws Exception {
        Factory f = new Factory(); addWarmup(f);
        f.add("https://api.giga.chat/v1/chat/completions", chat(ANSWER, "stop"));
        CloudClient c = client(f);
        CloudClient.Warmup budget = new CloudClient.Warmup();
        JSONObject result = c.prewarm(warmConfig(), budget);
        check(result.getBoolean("ok") && result.getBoolean("oauthReady")
                && result.getBoolean("gigachatReady") && result.getBoolean("speechifyReady"), "service-only warmup ready");
        check(result.getLong("warmedMs") >= 0 && result.getLong("warmedMs") < 4000, "bounded warmup metric");
        String safe = result.toString();
        for (String secret : new String[]{KEY, TOKEN, "synthetic-speech-key", "GigaChat", "simba", TRANSCRIPT, ANSWER})
            check(!safe.contains(secret), "warmup report excludes credentials/catalog/text");
        for (int i = 0; i < 3; i++) {
            Fake request = f.items.get(i);
            check(request.disconnected && request.inputClosed, "warmup wrapper consumed and released");
            check(request.getConnectTimeout() <= 4000 && request.getReadTimeout() <= 4000, "short startup timeouts");
            check(!request.hostnameOverride, "warmup retains strict hostname verification");
            if (i > 0) check("GET".equals(request.getRequestMethod()) && request.sent.size() == 0
                    && request.getRequestProperty("Connection") == null, "only bounded metadata GETs eligible for pooling");
        }
        check("close".equals(f.items.get(0).getRequestProperty("Connection")), "OAuth releases relay worker");
        check(f.items.get(0).socketFactory == f.items.get(1).socketFactory && f.items.get(2).socketFactory == null,
                "Giga uses shared trust; Speechify uses unchanged platform TLS pool");
        check("Bearer synthetic-speech-key".equals(f.items.get(2).getRequestProperty("Authorization")), "Speechify metadata bearer");
        int warmDisconnects = f.items.get(2).disconnects.get();
        budget.cancel(); budget.close();
        check(c.ask(config(), TRANSCRIPT).getBoolean("ok"), "same production client consumes warmed OAuth cache");
        check(f.items.get(3).getConnectTimeout() == 10000 && f.items.get(3).getReadTimeout() == 30000,
                "warmup budget never changes user request timeouts");
        check(f.items.get(2).disconnects.get() == warmDisconnects && f.items.get(3).disconnects.get() == 1,
                "late warmup cancellation cannot close a user wrapper");
        f.finished();
    }
    private static void warmupCancellationAndBudget() throws Exception {
        for (boolean cancel : new boolean[]{true, false}) {
            Factory f = new Factory(); addWarmup(f);
            Fake blocked = f.items.get(0); blocked.entered = new CountDownLatch(1);
            blocked.release = new CountDownLatch(1);
            CloudClient c = client(f);
            CloudClient.Warmup budget = new CloudClient.Warmup(cancel ? 4000 : 90);
            AtomicReference<JSONObject> result = new AtomicReference<>();
            Thread worker = new Thread(() -> result.set(c.prewarm(uncheckedWarmConfig(), budget)));
            worker.start(); check(blocked.entered.await(1, TimeUnit.SECONDS), "warmup reached bounded synthetic blocked OAuth");
            long started = System.nanoTime();
            if (cancel) budget.cancel();
            worker.join(1000);
            check(!worker.isAlive() && blocked.disconnected, "cancel/deadline actually disconnects its HTTPS wrapper");
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 700, "no remaining four-second or 90-second wait");
            check(!result.get().getBoolean("ok") && f.opened == 1, "cancelled attempt never starts next stage or retries");
            budget.close();
        }
        // Even an intentionally uncooperative cancelled warmup must not own token()'s
        // network monitor, gate or the second HTTP slot needed by a real user question.
        Factory f = new Factory();
        f.add("https://ngw.devices.sberbank.ru:9443/api/v2/oauth", "{}");
        Fake blocked = f.items.get(0); blocked.entered = new CountDownLatch(1);
        blocked.release = new CountDownLatch(1); blocked.releaseOnDisconnect = false;
        f.add("https://ngw.devices.sberbank.ru:9443/api/v2/oauth", new JSONObject()
                .put("access_token", TOKEN).put("expires_at", System.currentTimeMillis() + 1800000).toString());
        f.add("https://api.giga.chat/v1/chat/completions", chat(ANSWER, "stop"));
        Fake userRequest = f.items.get(2);
        userRequest.entered = new CountDownLatch(1); userRequest.release = new CountDownLatch(1);
        CloudClient c = client(f); CloudClient.Warmup budget = new CloudClient.Warmup();
        Thread old = new Thread(() -> c.prewarm(uncheckedWarmConfig(), budget)); old.start();
        check(blocked.entered.await(1, TimeUnit.SECONDS), "old startup OAuth waiting");
        long started = System.nanoTime(); budget.cancel();
        AtomicReference<JSONObject> answer = new AtomicReference<>(); AtomicReference<Throwable> userFailure = new AtomicReference<>();
        Thread user = new Thread(() -> {
            try { answer.set(c.ask(uncheckedWarmConfig(), TRANSCRIPT)); }
            catch (Throwable error) { userFailure.set(error); }
        }); user.start();
        check(userRequest.entered.await(1, TimeUnit.SECONDS), "real question passes cancelled warmup without OAuth monitor wait");
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 300, "first question never waits for warmup remainder");
        budget.cancel();
        CountDownLatch cancellationTick = new CountDownLatch(1); cancellationTick.await(30, TimeUnit.MILLISECONDS);
        check(blocked.disconnected && !userRequest.disconnected, "repeated startup cancellation targets only its own old wrapper");
        userRequest.release.countDown(); user.join(1000);
        check(!user.isAlive() && userFailure.get() == null && answer.get().getBoolean("ok"), "user completes independently of cancelled startup");
        blocked.release.countDown(); old.join(1000); check(!old.isAlive(), "cancelled old worker eventually releases ownership");
        f.finished();

        Factory shared = new Factory(); addWarmup(shared);
        Fake first = shared.items.get(0), second = shared.items.get(1);
        first.entered = new CountDownLatch(1); first.release = new CountDownLatch(1);
        second.entered = new CountDownLatch(1); second.release = new CountDownLatch(1);
        CloudClient.Warmup sharedBudget = new CloudClient.Warmup(300);
        AtomicReference<JSONObject> sharedResult = new AtomicReference<>();
        Thread stages = new Thread(() -> sharedResult.set(client(shared).prewarm(uncheckedWarmConfig(), sharedBudget)));
        stages.start(); check(first.entered.await(1, TimeUnit.SECONDS), "first startup stage entered");
        // The artificial first response consumes part of one deadline, rather than
        // receiving a new four-second budget for each subsequent metadata origin.
        CountDownLatch delay = new CountDownLatch(1); delay.await(100, TimeUnit.MILLISECONDS);
        first.release.countDown(); check(second.entered.await(1, TimeUnit.SECONDS), "second startup stage entered");
        check(second.getReadTimeout() < first.getReadTimeout() - 50, "remaining deadline was reset between origins");
        stages.join(1000);
        check(!stages.isAlive() && second.disconnected && !sharedResult.get().optBoolean("ok")
                && shared.opened == 2, "one total deadline prevents third-stage traffic");
        sharedBudget.close();
    }
    private static JSONObject uncheckedWarmConfig() {
        try { return warmConfig(); } catch (Exception impossible) { throw new AssertionError(impossible); }
    }
    private static void warmupFailures() throws Exception {
        Factory missing = new Factory();
        JSONObject result = client(missing).prewarm(config(), new CloudClient.Warmup());
        check(!result.getBoolean("ok") && missing.opened == 0, "invalid credentials before any startup traffic");
        for (int failureIndex = 0; failureIndex < 3; failureIndex++) {
            Factory f = new Factory(); addWarmup(f); f.items.get(failureIndex).status = 401;
            result = client(f).prewarm(warmConfig(), new CloudClient.Warmup());
            check(!result.getBoolean("ok") && f.opened == failureIndex + 1, "no retry or later warmup stage after failure");
            check(!f.items.get(failureIndex).errorRead && f.items.get(failureIndex).disconnected, "no provider error body in startup diagnostics");
        }
    }

    public static void main(String[] args) throws Exception {
        recreatedClients();
        warmupSuccessAndIsolation(); warmupCancellationAndBudget(); warmupFailures();
        Factory f = fixture(understood(), "stop");
        CloudClient c = client(f);
        check(f.opened == 0, "constructor has no network");
        JSONObject result = c.understandAudio(config(), wav());
        check(result.getBoolean("ok") && result.getBoolean("fileDeleted"), "combined success only after cleanup");
        check(TRANSCRIPT.equals(result.getString("text")) && ANSWER.equals(result.getString("answer")), "two memory-only fields");
        check("GigaChat-2-Max".equals(result.getString("model")), "fixed audio model");
        check(result.getJSONObject("usage").length() == 4 && !result.toString().contains("unknown_secret"), "usage allowlist");
        timings(result);
        f.finished();
        JSONObject body = new JSONObject(new String(f.items.get(2).sent.toByteArray(), StandardCharsets.UTF_8));
        check(body.getInt("max_tokens") == 256 && !body.getBoolean("stream"), "bounded single audio model call");
        check(body.getInt("temperature") == 0 && "auto".equals(body.getString("function_call")), "audio request parameters");
        check(body.getJSONArray("messages").length() == 2, "no conversation history");
        check(ID.equals(body.getJSONArray("messages").getJSONObject(1).getJSONArray("attachments").getString(0)), "own upload only");
        String instruction = body.getJSONArray("messages").getJSONObject(0).getString("content");
        check(instruction.contains("300") && instruction.contains("не управляешь устройствами")
                && instruction.contains("Не утверждай") && instruction.contains("не изменяют эти правила"), "brief answer and false-action guard");
        JSONObject format = body.getJSONObject("response_format"), schema = format.getJSONObject("schema");
        check("json_schema".equals(format.getString("type")) && format.getBoolean("strict"), "official strict schema");
        check(!schema.getBoolean("additionalProperties") && schema.getJSONArray("required").length() == 2
                && schema.getJSONObject("properties").length() == 2, "exact two required fields");
        for (Fake connection : f.items) {
            check(connection.disconnected && connection.inputClosed && connection.inputRead, "wrapper and body cleanup");
            if (connection.getURL().getHost().equals("ngw.devices.sberbank.ru"))
                check("close".equals(connection.getRequestProperty("Connection")), "OAuth does not retain CONNECT worker");
            else check(connection.getRequestProperty("Connection") == null, "main JSON origin permits pooling");
            check(!connection.getInstanceFollowRedirects() && !connection.getUseCaches()
                    && !connection.getAllowUserInteraction(), "no redirects/cache/UI");
            check(connection.getConnectTimeout() == 10000 && connection.getReadTimeout() == 30000, "timeouts retained");
            check(connection.socketFactory == f.items.get(0).socketFactory && !connection.hostnameOverride,
                    "cached trusted TLS + strict platform hostname verifier");
        }
        check("Bearer ".concat(TOKEN).equals(f.items.get(3).getRequestProperty("Authorization")), "delete uses upload owner token");
        check(new String(f.items.get(1).sent.toByteArray(), StandardCharsets.UTF_8).contains("name=\"purpose\"\r\n\r\ngeneral"), "purpose general");

        // The persistent client reuses the OAuth token; a new turn is exactly three cloud requests.
        addTurn(f, understood(), "stop");
        result = c.understandAudio(config(), wav());
        check(f.opened == 7 && result.getBoolean("fileDeleted"), "warm combined turn has no OAuth or text ask");
        f.finished();
        // Old transcription and text answering stay callable independently and unchanged.
        addTurn(f, "Дословная расшифровка", "stop");
        result = c.transcribeGigaChat(config(), wav());
        check("Дословная расшифровка".equals(result.getString("text")) && !result.has("answer"), "legacy result preserved");
        timings(result);
        JSONObject legacyBody = new JSONObject(new String(f.items.get(8).sent.toByteArray(), StandardCharsets.UTF_8));
        check(!legacyBody.has("response_format") && legacyBody.getInt("max_tokens") == 256, "legacy request unchanged");
        f.add("https://api.giga.chat/v1/chat/completions", chat("Обычный ответ", "stop"));
        result = c.ask(config(), "Вопрос");
        check("Обычный ответ".equals(result.getString("text")), "old ask retained");
        JSONObject askBody = new JSONObject(new String(f.items.get(10).sent.toByteArray(), StandardCharsets.UTF_8));
        check(askBody.getInt("max_tokens") == 128 && !askBody.has("response_format"), "old ask parameters unchanged");
        f.finished();

        // Strict local validation rejects permissive JSON, extra/duplicate fields, junk and invalid Unicode.
        String[] invalid = {"{}", "[]", "null", "{transcript:\"t\",answer:\"a\"}",
                "{'transcript':'t','answer':'a'}", "{\"transcript\":\"t\",\"answer\":7}",
                "{\"transcript\":null,\"answer\":\"a\"}", "{\"transcript\":{},\"answer\":\"a\"}",
                "{\"transcript\":\"t\",\"transcript\":\"other\"}",
                "{\"answer\":\"a\",\"answer\":\"other\"}",
                "{\"transcript\":\"t\",\"answer\":\"a\",\"secret\":\"x\"}",
                "{\"transcript\":\"t\",\"answer\":\"a\",}",
                understood() + "{}", "\u0000" + understood(), understood() + "\u0001",
                "{\"transcript\":\"t\",\"answer\":\"\\x41\"}",
                "{\"transcript\":\"t\",\"answer\":\"\\uZZZZ\"}",
                "{\"transcript\":\"t\",\"answer\":\"\\u0000\"}",
                "{\"transcript\":\"t\",\"answer\":\"\\ud800\"}",
                "{\"transcript\":\"t\",\"answer\":\"\\udc00\"}",
                "{\"transcript\":\"t\",\"answer\":\"\\ud800A\"}",
                "{\"transcript\":\"t\",\"answer\":\"raw\nnewline\"}",
                new JSONObject().put("transcript", repeat("т", 1001)).put("answer", "a").toString(),
                new JSONObject().put("transcript", "t").put("answer", repeat("а", 1001)).toString(),
                new JSONObject().put("transcript", "t").put("answer", "  ").toString()};
        for (String value : invalid) failures(value, "stop", "BAD_RESPONSE");
        failures("{\"transcript\":\"\",\"answer\":\"\"}", "stop", "NO_SPEECH");
        failures(understood(), "length", "TRANSCRIPTION_TRUNCATED");
        for (String reason : new String[]{"blacklist", "function_call", "", "unknown"})
            failures(understood(), reason, "BAD_RESPONSE");
        Factory escaped = fixture(" \n{\"answer\":\"\\u041e\\u0442\\u0432\\u0435\\u0442 \\ud83d\\ude00\","
                + "\"transcript\":\"  Вопрос \\\"да\\\" \\/ \\t \\r \\n \\\\  \"}\t ", "stop");
        result = client(escaped).understandAudio(config(), wav());
        check(result.getString("answer").equals("Ответ 😀") && result.getString("text").startsWith("Вопрос \"да\" /"), "valid JSON escapes/order/whitespace");
        escaped.finished();
        Factory bounded = fixture(new JSONObject().put("transcript", repeat("т", 1000))
                .put("answer", repeat("а", 1000)).toString(), "stop");
        result = client(bounded).understandAudio(config(), wav());
        check(result.getString("text").length() == 1000 && result.getString("answer").length() == 1000, "inclusive hard text limits");
        bounded.finished();

        // Cloud errors still try only our own delete and never retry a generation or leak its body.
        for (int status : new int[]{301, 401, 429, 500}) {
            Factory error = fixture(understood(), "stop"); error.items.get(2).status = status;
            try { client(error).understandAudio(config(), wav()); throw new AssertionError("expected HTTP error"); }
            catch (CloudClient.CloudException e) {
                check(e.httpStatus == status, "status preserved without body");
                check((status == 301 ? "REDIRECT_BLOCKED" : status == 429 ? "RATE_LIMITED" : "HTTP_ERROR").equals(e.code), "safe HTTP code");
            }
            error.finished();
            check(!error.items.get(2).inputRead && !error.items.get(2).errorRead
                    && error.items.get(3).inputRead, "error body not read; cleanup attempted");
        }
        for (IOException failure : new IOException[]{new SocketTimeoutException("SECRET"), new SSLException("SECRET"), new IOException("SECRET")}) {
            Factory error = fixture(understood(), "stop"); error.items.get(2).responseFailure = failure;
            try { client(error).understandAudio(config(), wav()); throw new AssertionError("expected transport error"); }
            catch (CloudClient.CloudException e) {
                check(!e.toString().contains("SECRET") && e.getCause() == null, "transport message suppressed");
            }
            error.finished(); check(error.items.get(3).inputRead, "transport failure still deletes upload");
        }
        Factory cleanup = fixture(understood(), "stop"); cleanup.items.get(3).status = 500;
        result = client(cleanup).understandAudio(config(), wav());
        check(!result.getBoolean("fileDeleted") && cleanup.opened == 4, "failed cleanup stays unproved, no retry/extra ask");
        timings(result); cleanup.finished();
        Factory oversize = fixture(understood(), "stop"); oversize.items.get(2).declared = 256 * 1024 + 1;
        try { client(oversize).understandAudio(config(), wav()); throw new AssertionError("expected bound"); }
        catch (CloudClient.CloudException e) { check("RESPONSE_TOO_LARGE".equals(e.code), "bounded HTTP response"); }
        oversize.finished(); check(oversize.items.get(3).inputRead, "oversize response cleanup");
        Factory badId = fixture(understood(), "stop"); badId.items.get(1).body = bytes("{\"id\":\"../other-user/delete\"}");
        try { client(badId).understandAudio(config(), wav()); throw new AssertionError("expected ID validation"); }
        catch (CloudClient.CloudException e) { check("BAD_RESPONSE".equals(e.code) && badId.opened == 2, "untrusted file id cannot address a delete"); }

        for (byte[] invalidAudio : Arrays.asList((byte[]) null, new byte[0], new byte[44], new byte[2 * 1024 * 1024 + 1])) {
            Factory invalidFactory = new Factory();
            try { client(invalidFactory).understandAudio(config(), invalidAudio); throw new AssertionError("expected invalid input"); }
            catch (CloudClient.CloudException e) { check("INVALID_AUDIO".equals(e.code) && invalidFactory.opened == 0, "invalid audio before upload"); }
        }
        Factory invalidConfig = new Factory();
        try { client(invalidConfig).understandAudio(new JSONObject().put("gigachat_authorization_key", "bad\r\nkey"), wav()); throw new AssertionError("expected key validation"); }
        catch (CloudClient.CloudException e) { check("INVALID_KEY_FORMAT".equals(e.code) && invalidConfig.opened == 0, "key rejected before HTTP"); }
        System.out.println("CloudClientHostTest: " + assertions + " assertions passed; no sockets/API requests");
    }

    private static final class Factory implements CloudClient.ConnectionFactory {
        final List<Fake> items = new ArrayList<Fake>();
        volatile int opened;
        void add(String address, String response) throws Exception { items.add(new Fake(new URL(address), bytes(response))); }
        public synchronized HttpsURLConnection open(URL address, Proxy proxy) throws IOException {
            if (opened >= items.size()) throw new AssertionError("unexpected HTTP call");
            Fake next = items.get(opened++);
            check(address.equals(next.getURL()), "fixed endpoint/order");
            check(proxy.type() == Proxy.Type.HTTP && proxy.address().toString().contains("127.0.0.1:17544"), "explicit loopback proxy route");
            return next;
        }
        void finished() { check(opened == items.size(), "exact bounded request count"); }
    }
    private static final class Fake extends HttpsURLConnection {
        byte[] body;
        int status = 200;
        long declared = -1;
        volatile boolean disconnected;
        boolean inputRead, inputClosed, errorRead, hostnameOverride;
        CountDownLatch entered, release;
        boolean releaseOnDisconnect = true;
        final AtomicInteger disconnects = new AtomicInteger();
        SSLSocketFactory socketFactory;
        IOException responseFailure;
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        Fake(URL address, byte[] body) { super(address); this.body = body; }
        public void connect() { }
        public boolean usingProxy() { return true; }
        public void disconnect() {
            disconnected = true; disconnects.incrementAndGet();
            if (releaseOnDisconnect && release != null) release.countDown();
        }
        public String getCipherSuite() { return "mock"; }
        public Certificate[] getLocalCertificates() { return null; }
        public Certificate[] getServerCertificates() { return null; }
        public void setSSLSocketFactory(SSLSocketFactory value) { socketFactory = value; super.setSSLSocketFactory(value); }
        public void setHostnameVerifier(HostnameVerifier value) { hostnameOverride = true; super.setHostnameVerifier(value); }
        public OutputStream getOutputStream() { return sent; }
        public int getResponseCode() throws IOException {
            if (entered != null) {
                entered.countDown();
                try { if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("synthetic timeout"); }
                catch (InterruptedException interrupted) { throw new IOException("synthetic interruption"); }
                if (disconnected) throw new IOException("synthetic disconnect");
            }
            if (responseFailure != null) throw responseFailure; return status;
        }
        public long getContentLengthLong() { return declared; }
        public InputStream getInputStream() {
            inputRead = true;
            return new ByteArrayInputStream(body) { @Override public void close() { inputClosed = true; } };
        }
        public InputStream getErrorStream() { errorRead = true; return new ByteArrayInputStream(bytes("SECRET_ERROR_BODY")); }
    }
}
