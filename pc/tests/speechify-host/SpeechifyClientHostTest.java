package local.station.voicebridge;

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
import java.util.Arrays;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;

/** Runs the real client with an in-memory HttpsURLConnection; no sockets or service account. */
public final class SpeechifyClientHostTest {
    private static final byte[] MP3 = new byte[]{'I','D','3',4,0,0,0,0,0,0};
    private static int assertions;

    private static void check(boolean condition, String name) {
        assertions++;
        if (!condition) throw new AssertionError(name);
    }
    private static JSONObject config() throws Exception {
        return new JSONObject().put("speechify_api_key", "synthetic-not-a-real-key");
    }
    private static byte[] json(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static byte[] success(byte[] audio) throws Exception {
        return success(audio, "mp3");
    }
    private static byte[] success(byte[] audio, String format) throws Exception {
        return json(new JSONObject().put("audio_format", format).put("audio_data", Base64.getEncoder().encodeToString(audio))
                .put("billable_characters_count", 12).toString());
    }
    private static byte[] oggHeader() {
        byte[] audio = new byte[47];
        byte[] magic = new byte[]{'O','g','g','S'};
        System.arraycopy(magic, 0, audio, 0, magic.length);
        audio[5]=2; audio[26]=1; audio[27]=19;
        byte[] opus = new byte[]{'O','p','u','s','H','e','a','d',1,1,0,0,(byte)0xc0,0x5d,0,0,0,0,0};
        System.arraycopy(opus, 0, audio, 28, opus.length);
        return audio;
    }
    private static void fails(String code, Fake transport, JSONObject config, String input) throws Exception {
        fails(code, transport, config, input, false);
    }
    private static void fails(String code, Fake transport, JSONObject config, String input, boolean ogg) throws Exception {
        Factory factory = new Factory(transport);
        try {
            SpeechifyClient client = new SpeechifyClient(factory);
            if (ogg) client.synthesizeOgg(config, input); else client.synthesize(config, input);
            throw new AssertionError("missing " + code);
        }
        catch (SpeechifyClient.SpeechifyException e) {
            check(code.equals(e.code), "expected " + code + " got " + e.code);
            check(e.getCause() == null && e.getSuppressed().length == 0, "no unsafe underlying exceptions");
        }
        check(transport.disconnected || factory.opens == 0, "transport cleanup");
    }

    public static void main(String[] args) throws Exception {
        Fake ok = new Fake(success(MP3));
        Factory opened = new Factory(ok);
        SpeechifyClient client = new SpeechifyClient(opened);
        client.requireSynthesisConfigured(config());
        check(opened.opens == 0, "preflight stays offline");
        SpeechifyClient.Result result = client.synthesize(config(), "Проверка голоса.");
        check(Arrays.equals(MP3, result.audio), "decoded bytes");
        check(result.billableCharacters == 12 && "simba-3.5".equals(result.model), "model + usage");
        check("vasilisa".equals(result.voiceId) && "ru-RU".equals(result.language) && "mp3".equals(result.audioFormat), "defaults");
        check(opened.opens == 1 && "https://api.speechify.ai/v1/audio/speech".equals(opened.url.toString()), "only fixed URL");
        check(opened.proxy == Proxy.NO_PROXY, "explicit direct default");
        check("POST".equals(ok.getRequestMethod()), "POST");
        check(!ok.getInstanceFollowRedirects() && !ok.getUseCaches() && !ok.getAllowUserInteraction(), "network switches");
        check(ok.getConnectTimeout() == 10000 && ok.getReadTimeout() == 30000, "bounded socket timeouts");
        check(!ok.tlsOverride && !ok.hostnameOverride, "platform TLS and hostname checks retained");
        check("Bearer synthetic-not-a-real-key".equals(ok.getRequestProperty("Authorization")), "Bearer header");
        JSONObject body = new JSONObject(new String(ok.sent.toByteArray(), StandardCharsets.UTF_8));
        check(body.length() == 5 && "Проверка голоса.".equals(body.getString("input")), "exact request fields");
        check("vasilisa".equals(body.getString("voice_id")) && "simba-3.5".equals(body.getString("model"))
                && "ru-RU".equals(body.getString("language")) && "mp3".equals(body.getString("audio_format")), "wire values");
        check(ok.disconnected && ok.inputClosed, "success cleanup");

        byte[] ogg = oggHeader();
        Fake oggTransport = new Fake(success(ogg, "ogg"));
        Factory oggFactory = new Factory(oggTransport);
        SpeechifyClient.Result oggResult = new SpeechifyClient(oggFactory).synthesizeOgg(config(), "Проверка голоса.");
        check(Arrays.equals(ogg, oggResult.audio) && "ogg".equals(oggResult.audioFormat), "Ogg decoded + dynamic format");
        check(oggResult.billableCharacters == 12 && "vasilisa".equals(oggResult.voiceId)
                && "simba-3.5".equals(oggResult.model) && "ru-RU".equals(oggResult.language), "Ogg preserves synthesis metadata");
        JSONObject oggBody = new JSONObject(new String(oggTransport.sent.toByteArray(), StandardCharsets.UTF_8));
        check(oggBody.length() == 6 && "ogg".equals(oggBody.getString("audio_format"))
                && "ogg_24000".equals(oggBody.getString("output_format")), "explicit Ogg wire contract");
        check("simba-3.5".equals(oggBody.getString("model")) && "ru-RU".equals(oggBody.getString("language"))
                && "vasilisa".equals(oggBody.getString("voice_id"))
                && "<speak><prosody volume=\"+4dB\">Проверка голоса.</prosody></speak>".equals(oggBody.getString("input")),
                "buffered Ogg applies the default gain");
        check("https://api.speechify.ai/v1/audio/speech".equals(oggFactory.url.toString())
                && !oggTransport.tlsOverride && !oggTransport.hostnameOverride
                && !oggTransport.getInstanceFollowRedirects(), "Ogg retains endpoint/TLS/redirect safety");
        check(oggTransport.disconnected && oggTransport.inputClosed, "Ogg cleanup");
        for (int position : new int[]{0,4,26,27,28,35,36,37}) {
            byte[] malformedOgg = ogg.clone();
            malformedOgg[position] = position == 27 ? (byte)8 : (byte)0;
            if (position == 4) malformedOgg[position]=1;
            fails("BAD_RESPONSE", new Fake(success(malformedOgg, "ogg")), config(), "Тест", true);
        }
        fails("BAD_RESPONSE", new Fake(success(Arrays.copyOf(ogg, 40), "ogg")), config(), "Тест", true);
        fails("BAD_RESPONSE", new Fake(success(MP3, "ogg")), config(), "Тест", true);
        fails("BAD_RESPONSE", new Fake(success(ogg, "mp3")), config(), "Тест", true);
        fails("BAD_RESPONSE", new Fake(success(ogg, "ogg")), config(), "Тест");
        fails("RESPONSE_TOO_LARGE", new Fake(success(new byte[4 * 1024 * 1024 + 1], "ogg")), config(), "Тест", true);
        Fake oggRate = new Fake(json("{}")); oggRate.status=429;
        fails("RATE_LIMITED", oggRate, config(), "Тест", true);

        Fake proxy = new Fake(success(MP3));
        Factory proxyFactory = new Factory(proxy);
        result = new SpeechifyClient(proxyFactory).synthesize(config().put("speechify_voice_id", "tikhon").put("lab_proxy_port", 18443), "Тест");
        check("tikhon".equals(result.voiceId) && proxyFactory.proxy.type() == Proxy.Type.HTTP
                && proxyFactory.proxy.address().toString().equals("/127.0.0.1:18443"), "loopback-only CONNECT proxy");
        fails("MISSING_KEY", new Fake(success(MP3)), new JSONObject(), "Тест");
        fails("INVALID_KEY_FORMAT", new Fake(success(MP3)), config().put("speechify_api_key", "safe\r\nInjected: yes"), "Тест");
        fails("INVALID_CONFIG", new Fake(success(MP3)), config().put("speechify_api_key", 3), "Тест");
        fails("INVALID_VOICE_ID", new Fake(success(MP3)), config().put("speechify_voice_id", "https://other.invalid"), "Тест");
        for (Object port : new Object[]{-1, 65536, "18443\n", true, 1.5}) {
            fails("INVALID_PROXY_PORT", new Fake(success(MP3)), config().put("lab_proxy_port", port), "Тест");
        }
        fails("INVALID_INPUT", new Fake(success(MP3)), config(), "  ");
        fails("INVALID_INPUT", new Fake(success(MP3)), config(), "bad\ud800");
        fails("INVALID_INPUT", new Fake(success(MP3)), config(), "bad\udc00");
        fails("INVALID_INPUT", new Fake(success(MP3)), config(), "bad\u0000");
        char[] tooMany = new char[2001]; Arrays.fill(tooMany, 'я');
        fails("INVALID_INPUT", new Fake(success(MP3)), config(), new String(tooMany));
        StringBuilder emoji = new StringBuilder();
        for (int i = 0; i < 2000; i++) emoji.append("\ud83d\ude00");
        new SpeechifyClient(new Factory(new Fake(success(MP3)))).synthesize(config(), emoji.toString());
        check(true, "2000 Unicode code points accepted, UTF-16 length 4000");
        fails("INVALID_INPUT", new Fake(success(MP3)), config(), emoji.append('я').toString());

        Fake redirect = new Fake(json("private redirect page")); redirect.status = 302;
        fails("REDIRECT_BLOCKED", redirect, config(), "Тест");
        check(!redirect.inputRead && !redirect.errorRead, "redirect body never read");
        for (int status : new int[]{400,401,402,403,413,429,500,502,503}) {
            String[] expected = {"REQUEST_REJECTED","AUTHENTICATION_FAILED","QUOTA_OR_BILLING_LIMIT","ACCESS_DENIED","REQUEST_REJECTED","RATE_LIMITED","SERVICE_UNAVAILABLE","SERVICE_UNAVAILABLE","SERVICE_UNAVAILABLE"};
            int[] statuses = {400,401,402,403,413,429,500,502,503};
            Fake failure = new Fake(json("{}")); failure.status = status;
            failure.error = json("{\"error\":{\"code\":\"unknown\",\"message\":\"PRIVATE_BODY_AND_KEY\"}}");
            int position = 0; while (statuses[position] != status) position++;
            fails(expected[position], failure, config(), "Тест");
            check(!failure.inputRead && failure.errorRead, "error stream only");
        }
        Fake rate = new Fake(json("{}")); rate.status = 429; rate.retryAfter = "12";
        rate.error = json("{\"error\":{\"code\":\"concurrency_limit_reached\",\"message\":\"PRIVATE_SENTINEL\"}}");
        try { new SpeechifyClient(new Factory(rate)).synthesize(config(), "Тест"); throw new AssertionError(); }
        catch (SpeechifyClient.SpeechifyException e) {
            check("CONCURRENCY_LIMIT".equals(e.code) && e.httpStatus == 429 && e.retryAfterSeconds == 12, "rate metadata");
            check(!e.toString().contains("PRIVATE") && !e.toString().contains("synthetic"), "redacted error");
        }
        Fake spend = new Fake(json("{}")); spend.status = 402;
        spend.error = json("{\"error\":{\"code\":\"spend_cap_exceeded\"}}");
        fails("SPEND_LIMIT", spend, config(), "Тест");
        Fake malicious = new Fake(json("{}")); malicious.status = 429; malicious.retryAfter = "12\r\nPRIVATE";
        try { new SpeechifyClient(new Factory(malicious)).synthesize(config(), "Тест"); throw new AssertionError(); }
        catch (SpeechifyClient.SpeechifyException e) { check(e.retryAfterSeconds == -1, "invalid Retry-After omitted"); }

        Fake declared = new Fake(success(MP3)); declared.length = 6 * 1024 * 1024 + 1L;
        fails("RESPONSE_TOO_LARGE", declared, config(), "Тест");
        check(!declared.inputRead, "declared excessive length rejected before read");
        Fake oversized = new Fake(new byte[6 * 1024 * 1024 + 1]);
        fails("RESPONSE_TOO_LARGE", oversized, config(), "Тест");
        fails("RESPONSE_TOO_LARGE", new Fake(success(new byte[4 * 1024 * 1024 + 1])), config(), "Тест");
        for (String malformed : new String[]{"{}", "{bad", "{\"audio_format\":\"wav\"}",
                new String(success(MP3), StandardCharsets.UTF_8) + "{}",
                "[" + new String(success(MP3), StandardCharsets.UTF_8) + "]",
                "{\"audio_format\":\"mp3\",\"audio_data\":\"%===\",\"billable_characters_count\":12}",
                "{\"audio_format\":\"mp3\",\"audio_data\":\"SUR\",\"billable_characters_count\":12}",
                "{\"audio_format\":\"mp3\",\"audio_data\":\"AAAAAA==\",\"billable_characters_count\":12}",
                "{\"audio_format\":\"mp3\",\"audio_data\":\"SUQzBA==\",\"billable_characters_count\":-1}",
                "{\"audio_format\":\"mp3\",\"audio_data\":\"SUQzBA==\",\"billable_characters_count\":1.5}"}) {
            fails("BAD_RESPONSE", new Fake(json(malformed)), config(), "Тест");
        }
        Fake html = new Fake(success(MP3)); html.contentType = "text/html";
        fails("BAD_RESPONSE", html, config(), "Тест");
        fails("BAD_RESPONSE", new Fake(new byte[]{(byte)0xc3,0x28}), config(), "Тест");
        Fake timeout = new Fake(success(MP3)); timeout.responseFailure = new SocketTimeoutException("PRIVATE_TIMEOUT");
        fails("TIMEOUT", timeout, config(), "Тест");
        Fake tls = new Fake(success(MP3)); tls.responseFailure = new SSLException("PRIVATE_TLS");
        fails("TLS_ERROR", tls, config(), "Тест");
        Fake network = new Fake(success(MP3)); network.responseFailure = new IOException("PRIVATE_NETWORK");
        fails("NETWORK_ERROR", network, config(), "Тест");
        Thread.currentThread().interrupt();
        try { fails("CANCELLED", new Fake(success(MP3)), config(), "Тест"); }
        finally { Thread.interrupted(); }

        final Fake blocked = new Fake(success(MP3));
        blocked.entered = new CountDownLatch(1); blocked.release = new CountDownLatch(1);
        final AtomicReference<Throwable> workerFailure = new AtomicReference<Throwable>();
        Thread worker = new Thread(new Runnable() { public void run() {
            try { new SpeechifyClient(new Factory(blocked)).synthesize(config(), "Тест"); }
            catch (Throwable e) { workerFailure.set(e); }
        }});
        worker.start();
        check(blocked.entered.await(2, TimeUnit.SECONDS), "first request entered");
        try { fails("BUSY", new Fake(success(MP3)), config(), "Тест"); }
        finally { blocked.release.countDown(); worker.join(2000); }
        check(!worker.isAlive() && workerFailure.get() == null && blocked.disconnected, "slot released + worker cleanup");
        new SpeechifyClient(new Factory(new Fake(success(MP3)))).synthesize(config(), "Тест");
        check(true, "slot reusable after all error paths");
        streamingTests();
        lateTimerLifecycle();
        System.out.println("SpeechifyClientHostTest: " + assertions + " assertions passed; no sockets/API requests");
    }

    private static void streamingTests() throws Exception {
        final byte[] ogg = streamFixture();
        final Fake response = streamFake(ogg);
        response.fragment = 3;
        final List<byte[]> pages = new ArrayList<byte[]>();
        final List<Integer> consumed = new ArrayList<Integer>();
        SpeechifyClient.AudioSink sink = new SpeechifyClient.AudioSink() {
            public void write(byte[] bytes, int offset, int count) {
                check(count >= 27 && count <= 65307 && offset == 0 && count == bytes.length, "one bounded page per callback");
                pages.add(bytes.clone()); consumed.add(response.consumed);
            }
            public boolean isCancelled() { return false; }
        };
        Factory factory = new Factory(response);
        SpeechifyClient.StreamResult result = new SpeechifyClient(factory).streamOgg(config(), "Тест", sink);
        check(factory.opens == 1 && "https://api.speechify.ai/v1/audio/stream".equals(factory.url.toString()), "fixed stream endpoint");
        check("audio/ogg".equals(response.getRequestProperty("Accept")) && response.getRequestProperty("Connection") == null,
                "raw Ogg and no forced Connection close header");
        check(factory.proxy == Proxy.NO_PROXY && !response.tlsOverride && !response.hostnameOverride,
                "stream preserves platform TLS");
        check(!response.getInstanceFollowRedirects() && !response.getUseCaches(), "stream redirects/cache disabled");
        JSONObject body = new JSONObject(new String(response.sent.toByteArray(), StandardCharsets.UTF_8));
        check(body.length() == 5 && "ogg_24000".equals(body.getString("output_format")) && !body.has("audio_format"), "stream JSON contract");
        check("simba-3.5".equals(body.getString("model")) && "vasilisa".equals(body.getString("voice_id"))
                && "ru-RU".equals(body.getString("language")), "stream preserves Russian voice/model");
        check("<speak><prosody volume=\"+4dB\">Тест</prosody></speak>".equals(body.getString("input")),
                "streaming and buffered Ogg use the same default gain");
        check(result.audioBytes == ogg.length && result.pages == 4 && result.inputCharacters == 4, "stream byte/page/input counts");
        check(result.billableCharacters == -1 && "ogg".equals(result.audioFormat), "stream does not invent billing metadata");
        check(result.firstByteMs >= 0 && result.firstAudioMs >= result.firstByteMs && result.elapsedMs >= result.firstAudioMs,
                "stream timing order");
        check(pages.size() == 4 && consumed.get(2) < ogg.length, "first audio page delivered before EOF/full clip");
        ByteArrayOutputStream reconstructed = new ByteArrayOutputStream();
        for (byte[] page : pages) reconstructed.write(page);
        check(Arrays.equals(ogg, reconstructed.toByteArray()), "all original page bytes preserved");
        check(response.disconnected && response.inputClosed, "stream success cleanup");
        final Fake proxied = streamFake(ogg);
        Factory proxy = new Factory(proxied);
        new SpeechifyClient(proxy).streamOgg(config().put("lab_proxy_port", 18443), "Тест", collecting());
        check(proxy.proxy.type() == Proxy.Type.HTTP && "/127.0.0.1:18443".equals(proxy.proxy.address().toString()), "stream loopback proxy only");

        Fake adjusted = streamFake(ogg);
        new SpeechifyClient(new Factory(adjusted)).streamOgg(
                config().put(SpeechifyClient.VOICE_GAIN_KEY, -2), "Раз <два> & три", collecting());
        JSONObject adjustedBody = new JSONObject(new String(adjusted.sent.toByteArray(), StandardCharsets.UTF_8));
        check("<speak><prosody volume=\"-2dB\">Раз &lt;два&gt; &amp; три</prosody></speak>".equals(adjustedBody.getString("input")),
                "custom gain escapes spoken text before inserting it into SSML");
        Fake neutral = streamFake(ogg);
        new SpeechifyClient(new Factory(neutral)).streamOgg(
                config().put(SpeechifyClient.VOICE_GAIN_KEY, 0), "Тест", collecting());
        JSONObject neutralBody = new JSONObject(new String(neutral.sent.toByteArray(), StandardCharsets.UTF_8));
        check("<speak>Тест</speak>".equals(neutralBody.getString("input")), "neutral setting omits gain");
        for (Object invalid : new Object[]{-7, 7, Long.MAX_VALUE, "2", 2.5}) {
            check(SpeechifyClient.voiceGainDb(config().put(SpeechifyClient.VOICE_GAIN_KEY, invalid)) == 4,
                    "invalid setting falls back to the default");
        }

        streamFails("BAD_RESPONSE", streamFake(Arrays.copyOf(ogg, 12)), collecting());
        streamFails("BAD_RESPONSE", streamFake(Arrays.copyOf(ogg, ogg.length - 1)), collecting());
        byte[] missingEos = ogg.clone(); missingEos[pageOffset(missingEos,3)+5]=0; repairPage(missingEos,3);
        streamFails("BAD_RESPONSE", streamFake(missingEos), collecting());
        byte[] trailing = Arrays.copyOf(ogg, ogg.length+1); trailing[trailing.length-1]=1;
        streamFails("BAD_RESPONSE", streamFake(trailing), collecting());
        byte[] crc = ogg.clone(); crc[pageOffset(crc,2)+28]^=1;
        streamFails("BAD_RESPONSE", streamFake(crc), collecting());
        for (int[] mutation : new int[][]{{0,37,2},{0,46,1},{0,5,0},{0,18,1},{1,28,0},{2,14,9},{2,18,7},{2,5,1}}) {
            byte[] changed=ogg.clone(); changed[pageOffset(changed,mutation[0])+mutation[1]]=(byte)mutation[2]; repairPage(changed,mutation[0]);
            streamFails("BAD_RESPONSE",streamFake(changed),collecting());
        }
        Fake html = streamFake(ogg); html.contentType="application/json";
        streamFails("BAD_RESPONSE",html,collecting());
        Fake declared = streamFake(ogg); declared.length=4*1024*1024+1L;
        streamFails("RESPONSE_TOO_LARGE",declared,collecting());
        check(!declared.inputRead, "stream declared limit checked before reading");
        Fake quota = streamFake(ogg); quota.status=402; quota.error=json("{\"error\":{\"code\":\"spend_cap_exceeded\",\"message\":\"PRIVATE\"}}");
        streamFails("SPEND_LIMIT",quota,collecting());
        Fake redirect = streamFake(ogg); redirect.status=302;
        streamFails("REDIRECT_BLOCKED",redirect,collecting());
        Fake timeout=streamFake(ogg); timeout.responseFailure=new SocketTimeoutException("PRIVATE");
        streamFails("TIMEOUT",timeout,collecting());
        Fake tls=streamFake(ogg); tls.responseFailure=new SSLException("PRIVATE");
        streamFails("TLS_ERROR",tls,collecting());

        final AtomicBoolean cancel = new AtomicBoolean(true);
        Fake before=streamFake(ogg); Factory beforeFactory=new Factory(before);
        try { new SpeechifyClient(beforeFactory).streamOgg(config(), "Тест", cancelling(cancel)); throw new AssertionError(); }
        catch (SpeechifyClient.SpeechifyException e) { check("CANCELLED".equals(e.code) && beforeFactory.opens==0,"cancel before network"); }
        cancel.set(false);
        streamFails("CANCELLED",streamFake(ogg),new SpeechifyClient.AudioSink(){
            public void write(byte[] b,int o,int n){cancel.set(true);}
            public boolean isCancelled(){return cancel.get();}
        });
        streamFails("CANCELLED",streamFake(ogg),new SpeechifyClient.AudioSink(){
            public void write(byte[] b,int o,int n)throws IOException{throw new IOException("PRIVATE_OUTPUT");}
            public boolean isCancelled(){return false;}
        });
        final Fake blocked=streamFake(ogg);
        final BlockingInput blockedInput=new BlockingInput(Arrays.copyOf(ogg,pageOffset(ogg,2)));
        blocked.customInput=blockedInput;
        final AtomicBoolean stop=new AtomicBoolean(false);
        final AtomicReference<Throwable> workerFailure=new AtomicReference<Throwable>();
        Thread worker=new Thread(new Runnable(){public void run(){
            try {new SpeechifyClient(new Factory(blocked)).streamOgg(config(),"Тест",cancelling(stop));}
            catch(Throwable e){workerFailure.set(e);}
        }});
        worker.start();
        check(blockedInput.entered.await(2,TimeUnit.SECONDS),"stream reaches blocked provider read");
        Fake busy=streamFake(ogg); streamFails("BUSY",busy,collecting());
        long cancellationStarted=System.nanoTime(); stop.set(true); worker.join(2000);
        check(!worker.isAlive() && blocked.disconnected && blocked.inputClosed,"monitor disconnect unblocks cancelled read");
        check(workerFailure.get() instanceof SpeechifyClient.SpeechifyException
                && "CANCELLED".equals(((SpeechifyClient.SpeechifyException)workerFailure.get()).code),"blocked read cancellation classified");
        check((System.nanoTime()-cancellationStarted)/1000000<1500,"cancellation bounded without waiting read timeout");
        new SpeechifyClient(new Factory(streamFake(ogg))).streamOgg(config(),"Тест",collecting());
        check(true,"stream slot reusable after cancellation");

        ByteArrayOutputStream many=new ByteArrayOutputStream();
        many.write(Arrays.copyOf(ogg,pageOffset(ogg,2)));
        for(int i=2;i<4098;i++) many.write(oggPage(i==4097?4:0,i,i*960L,new byte[]{1,2,3}));
        streamFails("BAD_RESPONSE",streamFake(many.toByteArray()),collecting());
        ByteArrayOutputStream large=new ByteArrayOutputStream();
        large.write(Arrays.copyOf(ogg,pageOffset(ogg,2)));
        for(int i=2;large.size()<4*1024*1024+8192;i++) large.write(oggPage(0,i,i*960L,new byte[4000]));
        streamFails("RESPONSE_TOO_LARGE",streamFake(large.toByteArray()),collecting());
    }

    private static void lateTimerLifecycle()throws Exception{
        final Fake streaming=streamFake(streamFixture());
        final ManualTimers timers=new ManualTimers();
        final AtomicBoolean cancelled=new AtomicBoolean(false);
        final CountDownLatch monitorEntered=new CountDownLatch(1),releaseMonitor=new CountDownLatch(1),pagesWritten=new CountDownLatch(4);
        final AtomicReference<Throwable> failure=new AtomicReference<Throwable>();
        SpeechifyClient.AudioSink sink=new SpeechifyClient.AudioSink(){
            public void write(byte[] b,int o,int n){pagesWritten.countDown();}
            public boolean isCancelled(){
                if("late-monitor".equals(Thread.currentThread().getName())){
                    monitorEntered.countDown();
                    try{if(!releaseMonitor.await(3,TimeUnit.SECONDS))throw new IllegalStateException("mock deadline");}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException();}
                }
                return cancelled.get();
            }
        };
        streaming.entered=new CountDownLatch(1);streaming.release=new CountDownLatch(1);
        Thread worker=new Thread(new Runnable(){public void run(){
            try{new SpeechifyClient(new Factory(streaming),timers).streamOgg(config(),"Тест",sink);}
            catch(Throwable e){failure.set(e);}
        }},"stream-worker");
        worker.start();
        check(streaming.entered.await(2,TimeUnit.SECONDS),"stream scheduled monitor before response");
        Thread activeMonitor=new Thread(timers.action,"late-monitor");activeMonitor.start();
        check(monitorEntered.await(2,TimeUnit.SECONDS),"monitor already executing during response");
        streaming.release.countDown();
        check(pagesWritten.await(2,TimeUnit.SECONDS),"complete stream delivered while monitor is executing");
        worker.join(100);
        check(worker.isAlive()&&streaming.disconnects==0,"pool release waits for executing monitor lifecycle lock");
        releaseMonitor.countDown();activeMonitor.join(2000);worker.join(2000);
        check(!worker.isAlive()&&!activeMonitor.isAlive()&&failure.get()==null,"executing monitor and completed stream exit cleanly");
        check(timers.future.cancelled&&streaming.disconnects==1,"finished stream cancels monitor and releases once");
        cancelled.set(true);
        timers.action.run();
        check(streaming.disconnects==1,"dequeued late monitor cannot disconnect returned pooled socket");

        Fake batch=new Fake(success(MP3));ManualTimers batchTimers=new ManualTimers();
        new SpeechifyClient(new Factory(batch),batchTimers).synthesize(config(),"Тест");
        check(batchTimers.future.cancelled&&batch.disconnects==1,"batch deadline cancelled at completion");
        batchTimers.action.run();
        check(batch.disconnects==1,"late batch deadline cannot disconnect released wrapper");
    }
    private static final class ManualTimers implements SpeechifyClient.Timers{
        volatile Runnable action;final ManualFuture future=new ManualFuture();
        public ScheduledFuture<?> once(Runnable action,long delay){this.action=action;return future;}
        public ScheduledFuture<?> repeating(Runnable action,long delay,long period){this.action=action;return future;}
    }
    private static final class ManualFuture implements ScheduledFuture<Object>{
        volatile boolean cancelled;
        public boolean cancel(boolean interrupt){cancelled=true;return true;}
        public boolean isCancelled(){return cancelled;}
        public boolean isDone(){return cancelled;}
        public long getDelay(TimeUnit unit){return 0;}
        public int compareTo(Delayed delayed){return 0;}
        public Object get(){return null;}
        public Object get(long timeout,TimeUnit unit){return null;}
    }

    private static SpeechifyClient.AudioSink collecting(){return new SpeechifyClient.AudioSink(){
        public void write(byte[] b,int o,int n){} public boolean isCancelled(){return false;}
    };}
    private static SpeechifyClient.AudioSink cancelling(final AtomicBoolean stop){return new SpeechifyClient.AudioSink(){
        public void write(byte[] b,int o,int n){} public boolean isCancelled(){return stop.get();}
    };}
    private static Fake streamFake(byte[] bytes)throws Exception{Fake f=new Fake(bytes);f.contentType="audio/ogg";return f;}
    private static void streamFails(String code,Fake transport,SpeechifyClient.AudioSink sink)throws Exception{
        Factory f=new Factory(transport);
        try {new SpeechifyClient(f).streamOgg(config(),"Тест",sink);throw new AssertionError("missing stream "+code);}
        catch(SpeechifyClient.SpeechifyException e){
            check(code.equals(e.code),"stream expected "+code+" got "+e.code);
            check(e.getCause()==null && !e.toString().contains("PRIVATE") && !e.toString().contains("synthetic"),"safe stream exception");
        }
        check(transport.disconnected || f.opens==0,"stream failure releases transport");
    }
    private static byte[] streamFixture()throws Exception{
        byte[] head=Arrays.copyOfRange(oggHeader(),28,47);
        byte[] tags=new byte[16];System.arraycopy(json("OpusTags"),0,tags,0,8);
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write(oggPage(2,0,0,head));out.write(oggPage(0,1,0,tags));
        out.write(oggPage(0,2,960,new byte[]{1,2,3}));out.write(oggPage(4,3,1920,new byte[]{4,5,6}));
        return out.toByteArray();
    }
    private static byte[] oggPage(int flags,int sequence,long granule,byte[] packet){
        int segments=packet.length/255+1;
        byte[] page=new byte[27+segments+packet.length];
        System.arraycopy(json("OggS"),0,page,0,4);page[5]=(byte)flags;page[26]=(byte)segments;
        for(int i=0;i<8;i++)page[6+i]=(byte)(granule>>>(8*i));
        putLittle(page,14,1);putLittle(page,18,sequence);
        for(int i=0;i<segments;i++)page[27+i]=(byte)(i==segments-1?packet.length%255:255);
        System.arraycopy(packet,0,page,27+segments,packet.length);putLittle(page,22,crc(page,0,page.length));return page;
    }
    private static int pageOffset(byte[] data,int index){
        int offset=0;
        for(int i=0;i<index;i++){int count=data[offset+26]&255;int size=27+count;for(int j=0;j<count;j++)size+=data[offset+27+j]&255;offset+=size;}
        return offset;
    }
    private static void repairPage(byte[] data,int index){
        int start=pageOffset(data,index),count=data[start+26]&255,size=27+count;
        for(int i=0;i<count;i++)size+=data[start+27+i]&255;
        putLittle(data,start+22,crc(data,start,size));
    }
    private static int crc(byte[] data,int offset,int length){
        int value=0;
        for(int i=0;i<length;i++){
            value^=(i>=22 && i<26?0:data[offset+i]&255)<<24;
            for(int bit=0;bit<8;bit++)value=(value<<1)^(value<0?0x04c11db7:0);
        }
        return value;
    }
    private static void putLittle(byte[] data,int offset,long value){for(int i=0;i<4;i++)data[offset+i]=(byte)(value>>>(8*i));}
    private static final class BlockingInput extends InputStream{
        final ByteArrayInputStream prefix;final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        BlockingInput(byte[] bytes){prefix=new ByteArrayInputStream(bytes);}
        public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255;}
        public int read(byte[] b,int o,int n)throws IOException{
            if(prefix.available()>0)return prefix.read(b,o,n);
            entered.countDown();
            try{if(!release.await(3,TimeUnit.SECONDS))throw new IOException("mock block timeout");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException();}
            throw new IOException("closed");
        }
        public void close(){release.countDown();}
    }

    private static final class Factory implements SpeechifyClient.ConnectionFactory {
        final Fake connection; int opens; URL url; Proxy proxy;
        Factory(Fake connection) { this.connection = connection; }
        public HttpsURLConnection open(URL url, Proxy proxy) { opens++; this.url=url; this.proxy=proxy; return connection; }
    }
    private static final class Fake extends HttpsURLConnection {
        byte[] body; byte[] error = json("{}"); int status = 200; long length = -1;
        String contentType = "application/json; charset=utf-8"; String retryAfter;
        volatile boolean disconnected, inputClosed;
        volatile int disconnects;
        boolean inputRead, errorRead, tlsOverride, hostnameOverride;
        int fragment=8192, consumed; InputStream customInput;
        ByteArrayOutputStream sent = new ByteArrayOutputStream(); IOException responseFailure;
        CountDownLatch entered, release;
        Fake(byte[] body) throws Exception { super(new URL("https://api.speechify.ai/v1/audio/speech")); this.body=body; }
        public void disconnect() { disconnected=true;disconnects++;if(customInput!=null)try{customInput.close();inputClosed=true;}catch(IOException ignored){} }
        public boolean usingProxy() { return false; }
        public void connect() { }
        public String getCipherSuite() { return "mock"; }
        public Certificate[] getLocalCertificates() { return null; }
        public Certificate[] getServerCertificates() { return null; }
        public void setSSLSocketFactory(SSLSocketFactory value) { tlsOverride=true; super.setSSLSocketFactory(value); }
        public void setHostnameVerifier(HostnameVerifier value) { hostnameOverride=true; super.setHostnameVerifier(value); }
        public OutputStream getOutputStream() { return sent; }
        public int getResponseCode() throws IOException {
            if (responseFailure != null) throw responseFailure;
            if (entered != null) {
                entered.countDown();
                try { if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("mock timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(); }
            }
            return status;
        }
        public String getContentType() { return contentType; }
        public long getContentLengthLong() { return length; }
        public String getHeaderField(String name) { return "Retry-After".equals(name) ? retryAfter : null; }
        public InputStream getInputStream() {
            inputRead=true;
            if(customInput!=null)return customInput;
            return new ByteArrayInputStream(body) {
                public synchronized int read(byte[] b,int o,int n){int r=super.read(b,o,Math.min(n,fragment));if(r>0)consumed+=r;return r;}
                public void close() { inputClosed=true; }
            };
        }
        public InputStream getErrorStream() { errorRead=true; return new ByteArrayInputStream(error); }
    }
}
