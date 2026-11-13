package local.station.agent;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Fake PC transport and native packets only. No live sockets, screen, sound or config writes. */
public final class StationDisplaySettingsHostTest {
    static int checks;
    static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    static JSONObject json(String raw) throws Exception { return StationDisplaySettings.parseObject(raw); }
    static JSONObject get() throws Exception { return json("{\"op\":\"get\"}"); }
    static JSONObject saved(JSONObject requested) throws Exception {
        return new JSONObject().put("ok", true).put("scope", "station_display_settings")
                .put("requested", requested).put("expiresAtMs", 0).put("revision", "abc123");
    }
    static byte[] varint(long n) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        do { int b = (int) (n & 127); n >>>= 7; out.write(n == 0 ? b : b | 128); } while (n != 0);
        return out.toByteArray();
    }
    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length); return out.toByteArray();
    }
    static byte[] field(int key, byte[] value) { return concat(varint(key * 8 + 2), varint(value.length), value); }
    static byte[] snapshot(int number, String config) {
        return field(number, concat(field(1, "NEVER_EXPOSE_PASSPORT".getBytes(StandardCharsets.UTF_8)),
                field(2, config.getBytes(StandardCharsets.UTF_8))));
    }
    static void parser() throws Exception {
        JSONObject current = NativeDisplaySettings.parseFrame(snapshot(63,
                "{\"account_config\":{\"token\":\"NEVER_EXPOSE_SECRET\"},\"device_config\":{}}"));
        check("middle".equals(current.getString("clockSize")), "default clock font");
        check(!current.getBoolean("clockSizeExplicit") && !current.has("brightness"), "defaults explicit or fabricated gain");
        for (String size : new String[]{"small", "middle", "large"}) {
            current = NativeDisplaySettings.parseFrame(snapshot(63, "{\"device_config\":{\"led\":{\"time_visualization\":{\"size\":\"" + size
                    + "\"},\"brightness\":{\"auto\":false,\"value\":0.25,\"token\":\"secret\"},\"ring\":\"secret\"}}}"));
            check(size.equals(current.getString("clockSize")) && current.getBoolean("clockSizeExplicit"), "native font");
            check(current.getJSONObject("brightness").length() == 2, "brightness leaks arbitrary keys");
            check(!current.toString().contains("secret") && !current.toString().contains("PASSPORT"), "native secret leaked");
        }
        for (String gain : new String[]{"\"0.5\"", "-0.1", "1.1", "null", "true"}) {
            current = NativeDisplaySettings.parseFrame(snapshot(63, "{\"device_config\":{\"led\":{\"brightness\":{\"auto\":true,\"value\":" + gain + "}}}}"));
            check(!current.has("brightness"), "invalid native brightness retained");
        }
        check(NativeDisplaySettings.parseFrame(snapshot(106, "{}")) == null, "cached config called current");
        current = NativeDisplaySettings.parseFrame(snapshot(63, "{\"device_config\":{\"led\":{\"time_visualization\":{\"format\":\"large\"}}}}"));
        check(!current.getBoolean("clockSizeExplicit"), "newer format key guessed");
        check("unknown".equals(current.getString("musicStyle")) && !current.getBoolean("musicStyleExplicit"), "missing native music guessed");
        JSONObject config = json("{\"device_config\":{\"led\":{\"music_equalizer_visualization\":{\"auto\":false,\"style\":\"wave\",\"token\":\"SECRET\"}}},\"system_config\":{\"spectrogramAnimation\":{\"enabled\":true,\"presets\":{\"wave\":{\"type\":\"wave\"}}}}}");
        current = NativeDisplaySettings.current(config);
        check("wave".equals(current.getString("musicStyle")) && current.getBoolean("musicStyleExplicit"), "known native music selector");
        check(!current.toString().contains("SECRET") && !current.has("presets"), "music native config leaked");
        JSONObject animation = config.getJSONObject("system_config").getJSONObject("spectrogramAnimation");
        JSONObject selector = config.getJSONObject("device_config").getJSONObject("led").getJSONObject("music_equalizer_visualization");
        animation.put("enabled", false);
        check(!NativeDisplaySettings.current(config).getBoolean("musicStyleExplicit"), "disabled alone called explicit clock");
        selector.put("style", "clock");
        check(NativeDisplaySettings.current(config).getBoolean("musicStyleExplicit"), "explicit disabled clock not recognized");
        animation.put("enabled", true);selector.put("style", "wave");
        animation.getJSONObject("presets").getJSONObject("wave").put("type", "alien");
        check("unknown".equals(NativeDisplaySettings.current(config).getString("musicStyle")), "unproven factory type accepted");
        JSONObject presets = json("{\"wave\":{\"type\":\"wave\"},\"pads\":{\"type\":\"pads\"},\"bricks\":{\"type\":\"bricks\"},\"fluid\":{\"type\":\"fluid\"},\"random\":{\"type\":\"random\",\"presets\":[\"wave\",\"pads\",\"bricks\",\"fluid\"],\"interval\":30}}");
        animation.put("presets", presets);selector.put("auto", true);
        check("random".equals(NativeDisplaySettings.current(config).getString("musicStyle")), "auto selector not mapped to random");
        presets.getJSONObject("random").put("presets", new org.json.JSONArray("[\"wave\",\"wave\",\"pads\",\"fluid\"]"));
        check(!NativeDisplaySettings.current(config).getBoolean("musicStyleExplicit"), "duplicate random preset accepted");
        byte[][] invalid = {new byte[0], new byte[]{0}, new byte[]{(byte) 0xfa,3,100},
                new byte[]{(byte) 0x80}, new byte[65537], snapshot(63,"[]"), snapshot(63,"{} {}"),
                field(63, concat(field(2,"{}".getBytes(StandardCharsets.UTF_8)),field(2,"{}".getBytes(StandardCharsets.UTF_8)))),
                field(63,field(2,new byte[]{(byte) 0xc0,(byte) 0xaf}))};
        for (byte[] packet : invalid) {
            try { NativeDisplaySettings.parseFrame(packet); throw new AssertionError("malformed native accepted"); }
            catch (IllegalArgumentException | org.json.JSONException | java.nio.charset.CharacterCodingException expected) { checks++; }
        }
    }
    static final class Fakes implements StationDisplaySettings.Bridge, StationDisplaySettings.Native {
        int calls, nativeCalls; boolean pcDown, nativeDown; JSONObject request, requested = new JSONObject(), current;
        Fakes() throws Exception { current = NativeDisplaySettings.current(new JSONObject()); }
        @Override public JSONObject exchange(JSONObject input, long deadline) throws Exception {
            calls++; request = input;
            check(!Thread.currentThread().getName().equals("main"), "PC on calling thread");
            check(deadline-System.nanoTime() < 1700000000L, "PC deadline not bounded");
            if (pcDown) throw new IOException("SECRET_EXCEPTION");
            return saved(requested);
        }
        @Override public JSONObject read(long deadline) throws Exception {
            nativeCalls++; if (nativeDown) throw new IOException("SECRET_EXCEPTION"); return current;
        }
    }
    static void controller() throws Exception {
        Fakes f = new Fakes(); StationDisplaySettings service = new StationDisplaySettings(f,f);
        try {
            JSONObject result = service.execute(get());
            check(result.getBoolean("ok") && "defaults".equals(result.getString("state")), "empty defaults");
            check(result.getJSONArray("musicModes").length() == 6, "music catalog missing");
            for (String invalid : new String[]{"{}", "{\"op\":\"nope\"}", "{\"op\":\"get\",\"host\":\"x\"}",
                    "{\"op\":\"set_clock_size\",\"size\":\"huge\"}", "{\"op\":\"set_clock_size\",\"size\":1}",
                    "{\"op\":\"set_brightness\",\"auto\":\"true\",\"value\":0.3}",
                    "{\"op\":\"set_brightness\",\"auto\":true,\"value\":\"0.3\"}",
                    "{\"op\":\"set_brightness\",\"auto\":false,\"value\":-1}",
                    "{\"op\":\"set_brightness\",\"auto\":false,\"value\":1.1}",
                    "{\"op\":\"set_brightness\",\"auto\":false}",
                    "{\"op\":\"set_music_style\",\"style\":\"alien\"}",
                    "{\"op\":\"set_music_style\",\"style\":true}",
                    "{\"op\":\"set_music_style\",\"style\":\"wave\",\"enabled\":true}"}) {
                int before = f.calls, reads = f.nativeCalls; result = service.execute(json(invalid));
                check(!result.getBoolean("ok") && "invalid_display_request".equals(result.getString("code")), "invalid request accepted");
                check(f.calls == before && f.nativeCalls == reads, "validation performed after network");
            }
            JSONObject set = json("{\"op\":\"set_clock_size\",\"size\":\"large\"}");
            f.requested = json("{\"clockSize\":\"large\"}"); result = service.execute(set);
            check(result.getBoolean("ok") && "pending".equals(result.getString("state")), "PC ACK mistaken for native apply");
            f.current = json("{\"clockSize\":\"large\",\"clockSizeExplicit\":true}"); result = service.execute(get());
            check("applied".equals(result.getString("state")), "actual native matching not applied");
            f.requested = json("{\"clockSize\":\"middle\"}"); f.current = NativeDisplaySettings.current(new JSONObject());
            check("pending".equals(service.execute(get()).getString("state")), "implicit default mistaken for explicit set");
            f.requested = new JSONObject(); check(!service.execute(set).getBoolean("ok"), "mismatched PC ACK accepted");
            f.requested = json("{\"brightness\":{\"auto\":true,\"value\":0.4}}");
            result = service.execute(json("{\"op\":\"set_brightness\",\"auto\":true,\"value\":0.4}"));
            check(result.getBoolean("ok") && "pending".equals(result.getString("state")), "brightness ACK");
            f.current.put("brightness", json("{\"auto\":true,\"value\":0.4}"));
            check("applied".equals(service.execute(get()).getString("state")), "brightness actual compare");
            JSONObject musicSet = json("{\"op\":\"set_music_style\",\"style\":\"wave\"}");
            check(!service.execute(musicSet).getBoolean("ok"), "missing music ACK accepted");
            f.requested = json("{\"musicStyle\":\"wave\",\"clockSize\":\"middle\"}");
            f.current.put("clockSizeExplicit", true).put("musicStyle", "wave").put("musicStyleExplicit", false);
            result = service.execute(musicSet);
            check(result.getBoolean("ok") && "pending".equals(result.getString("state")), "implicit music called applied");
            f.current.put("clockSize", "middle").put("musicStyleExplicit", true);
            check("applied".equals(service.execute(get()).getString("state")), "native music matching not applied");
            f.current.put("clockSize", "large");
            check("pending".equals(service.execute(get()).getString("state")), "music matching hid sibling mismatch");
            f.requested = json("{\"musicStyle\":\"pads\"}");
            check(!service.execute(musicSet).getBoolean("ok"), "wrong music ACK accepted");
            f.nativeDown = true; result = service.execute(get());
            check(result.getBoolean("writeAvailable") && !result.getBoolean("nativeAvailable")
                    && "unavailable".equals(result.getString("state")), "native down guessed applied");
            check("unknown".equals(result.getJSONObject("current").getString("clockSize")), "native down guessed middle");
            f.nativeDown = false; f.pcDown = true; int reads = f.nativeCalls; result = service.execute(get());
            check(result.getBoolean("nativeAvailable") && !result.getBoolean("writeAvailable") && result.getBoolean("ok"), "PC down hides native");
            check(f.nativeCalls == reads+1 && !result.toString().contains("SECRET_EXCEPTION"), "native skipped or exception leaked");
            result = service.execute(set); check(!result.getBoolean("ok"), "set accepted while PC down");
            f.nativeDown = true; check(!service.execute(get()).getBoolean("ok"), "all down returned success");
        } finally { service.close(); }
        check("display_busy".equals(service.execute(get()).getString("code")), "closed controller admitted work");
        for (JSONObject bad : new JSONObject[]{saved(new JSONObject()).put("scope","wrong"), saved(new JSONObject()).put("expiresAtMs",-1),
                saved(new JSONObject()).put("expiresAtMs",0.5), saved(new JSONObject()).put("revision","SECRET/unsafe"),
                saved(json("{\"unknown\":1}")), saved(json("{\"musicStyle\":\"alien\"}")),
                saved(json("{\"brightness\":{\"auto\":true,\"value\":0.5,\"secret\":1}}"))}) {
            try { StationDisplaySettings.validateBridge(bad); throw new AssertionError("bad PC contract accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        StationDisplaySettings serial = new StationDisplaySettings((request,deadline) -> {
            entered.countDown(); release.await(2,TimeUnit.SECONDS); return saved(new JSONObject());
        }, deadline -> NativeDisplaySettings.current(new JSONObject()));
        Thread first = new Thread(() -> { try { serial.execute(get()); } catch (Exception error) { throw new AssertionError(error); } });
        first.start(); check(entered.await(1,TimeUnit.SECONDS), "worker not started");
        check("display_busy".equals(serial.execute(get()).getString("code")), "overlapping display commands admitted");
        release.countDown(); first.join(1500); serial.close(); check(!first.isAlive(), "worker failed to return");
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        JSONObject requested = json("{\"clockSize\":\"large\"}");
        StationDisplaySettings fast = new StationDisplaySettings((request,deadline) -> saved(requested), deadline -> {
            check(deadline - System.nanoTime() <= 210000000L, "post-write native read monopolizes fast-apply budget");
            int count = reads.incrementAndGet();
            return count < 3 ? json("{\"clockSize\":\"middle\",\"clockSizeExplicit\":true}")
                    : json("{\"clockSize\":\"large\",\"clockSizeExplicit\":true}");
        });
        try {
            long began = System.nanoTime(); JSONObject result = fast.execute(json("{\"op\":\"set_clock_size\",\"size\":\"large\"}"));
            long elapsed = (System.nanoTime() - began) / 1000000;
            check(reads.get() == 3 && elapsed >= 120 && elapsed < 900, "bounded native polling does not wait for another phone request");
            check(result.getString("state").equals("applied") && result.getJSONObject("current").getString("clockSize").equals("large"),
                    "fast apply requires actual matching native readback");
        } finally { fast.close(); }
    }

    static final class FakeHttp extends HttpURLConnection {
        int status=200; byte[] body="{\"ok\":true}".getBytes(StandardCharsets.UTF_8); boolean disconnected;
        final ByteArrayOutputStream output=new ByteArrayOutputStream();
        FakeHttp() throws Exception { super(new URL(PcDisplaySettingsBridge.ENDPOINT)); }
        @Override public void connect() { }
        @Override public void disconnect() { disconnected=true; }
        @Override public boolean usingProxy() { return false; }
        @Override public int getResponseCode() { return status; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
        @Override public OutputStream getOutputStream() { return output; }
    }
    static void transport() throws Exception {
        FakeHttp fake = new FakeHttp();
        check(PcDisplaySettingsBridge.ENDPOINT.equals("http://127.0.0.1:17685/api/display"), "display endpoint must remain on device");
        PcDisplaySettingsBridge bridge = new PcDisplaySettingsBridge((url,proxy) -> {
            check(PcDisplaySettingsBridge.ENDPOINT.equals(url.toString()), "caller-selected PC endpoint");
            check(proxy == Proxy.NO_PROXY, "system HTTP proxy used"); return fake;
        });
        bridge.exchange(get(),System.nanoTime()+1600000000L);
        check(!fake.getInstanceFollowRedirects() && !fake.getUseCaches() && fake.disconnected, "redirect/cache/close policy");
        check(fake.getConnectTimeout()<=600 && fake.getReadTimeout()<=600 && fake.output.size()==0, "GET writes/deadline");
        JSONObject set=json("{\"op\":\"set_clock_size\",\"size\":\"large\"}");
        bridge.exchange(set,System.nanoTime()+1600000000L);
        check("POST".equals(fake.getRequestMethod()) && json(new String(fake.output.toByteArray(),StandardCharsets.UTF_8)).getString("size").equals("large"), "POST serialization");
        fake.status=302;
        try { bridge.exchange(get(),System.nanoTime()+1600000000L); throw new AssertionError("redirect accepted"); }
        catch(IllegalArgumentException expected){checks++;}
        fake.status=200;fake.body=new byte[4097];
        try { bridge.exchange(get(),System.nanoTime()+1600000000L); throw new AssertionError("oversize response accepted"); }
        catch(IllegalArgumentException expected){checks++;}
    }
    static final class Peer extends Socket {
        final ByteArrayInputStream input; final ByteArrayOutputStream output = new ByteArrayOutputStream(); final InetAddress peer;
        Peer(String raw,boolean local)throws Exception{input=new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8));peer=InetAddress.getByAddress(local?new byte[]{127,0,0,1}:new byte[]{10,0,0,2});}
        @Override public InputStream getInputStream(){return input;}
        @Override public OutputStream getOutputStream(){return output;}
        @Override public InetAddress getInetAddress(){return peer;}
        @Override public void setSoTimeout(int n){}
        @Override public void close(){}
    }
    static String http(AgentHttpServer server,String raw,boolean local)throws Exception{
        Peer peer=new Peer(raw,local);Method handle=AgentHttpServer.class.getDeclaredMethod("handle",Socket.class);handle.setAccessible(true);handle.invoke(server,peer);
        return new String(peer.output.toByteArray(),StandardCharsets.UTF_8);
    }
    static void http()throws Exception{
        AgentService service=new AgentService();AgentHttpServer server=new AgentHttpServer(new android.content.Context(),service);
        String response=http(server,"GET /api/display HTTP/1.1\r\nHost: localhost\r\n\r\n",true);
        check(response.startsWith("HTTP/1.1 200") && "get".equals(service.received.getString("op")),"DISPLAY GET route");
        String body="{\"op\":\"set_clock_size\",\"size\":\"large\"}";
        response=http(server,"POST /api/display HTTP/1.1\r\nContent-Length: "+body.length()+"\r\n\r\n"+body,true);
        check(response.startsWith("HTTP/1.1 200") && "large".equals(service.received.getString("size")),"DISPLAY POST JSON body");
        String[] rejected={"GET /api/display?request=%7B%7D HTTP/1.1\r\n\r\n", "POST /api/display HTTP/1.1\r\n\r\n",
                "POST /api/display HTTP/1.1\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\n{}",
                "POST /api/display HTTP/1.1\r\nContent-Length: 2\r\nTransfer-Encoding: chunked\r\n\r\n{}",
                "POST /api/display HTTP/1.1\r\nContent-Length: 513\r\n\r\n", "POST /api/display HTTP/1.1\r\nContent-Length: 5\r\n\r\n{} {}"};
        for(String raw:rejected){int calls=service.displayCalls;response=http(server,raw,true);check(response.startsWith("HTTP/1.1 400"),"bad DISPLAY HTTP admitted");check(calls==service.displayCalls,"invalid HTTP touched service");}
        check(http(server,"GET /api/display HTTP/1.1\r\n\r\n",false).startsWith("HTTP/1.1 403"),"LAN DISPLAY allowed");
        check(http(server,"PUT /api/display HTTP/1.1\r\n\r\n",true).startsWith("HTTP/1.1 405"),"wrong DISPLAY method");
        String localTrack="http://127.0.0.1:17686/audio/track-00000000000000000000000000000001.mp3";
        check(http(server,"GET /api/play?url="+URLEncoder.encode(localTrack,"UTF-8")+" HTTP/1.1\r\n\r\n",true).startsWith("HTTP/1.1 200")
                && service.playCalls==1,"exact local track HTTP playback");
        for(String invalid:new String[]{"http://192.168.77.1:8899/audio/a.mp3",localTrack+"?outside=1",localTrack.replace("17686","17687")}) {
            check(http(server,"GET /api/play?url="+URLEncoder.encode(invalid,"UTF-8")+" HTTP/1.1\r\n\r\n",true).startsWith("HTTP/1.1 400")
                    && service.playCalls==1,"HTTP playback cannot fall back to PC or arbitrary URLs");
        }
    }
    public static void main(String[] args)throws Exception{parser();controller();transport();http();System.out.println(checks+" display assertions passed; fake peers/transport only");}
}
