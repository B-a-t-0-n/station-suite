package local.station.voicebridge;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Fake clock only: no sleeps, sockets or providers. */
public final class RuntimeSessionHostTest {
    private static final class Clock implements LegacyVoiceSession.Clock {
        long now = 1;
        public long monotonicMillis() { return now; }
        public long wallTimeMillis() { return 1000000 + now; }
    }
    private static JSONObject event(String namespace, String name, String ref, long stream) throws Exception {
        JSONObject header = new JSONObject().put("namespace", namespace).put("name", name).put("messageId", ref);
        if (stream > 0) header.put("streamId", stream);
        return new JSONObject().put("event", new JSONObject().put("header", header).put("payload", new JSONObject()));
    }
    private static void check(boolean condition, String name) { if (!condition) throw new AssertionError(name); }
    public static void main(String[] args) throws Exception {
        Clock clock = new Clock(); ArrayList<JSONObject> sent = new ArrayList<>(); AtomicInteger callbacks = new AtomicInteger();
        LegacyVoiceSession session = new LegacyVoiceSession(clock, sent::add,
                (audio, metadata) -> callbacks.incrementAndGet(), 120000);
        session.enableRuntimeReuse();
        session.onMessage(event("System", "SynchronizeState", "sync", 0));
        String identity = sent.get(0).getJSONObject("directive").getJSONObject("payload").getString("SessionId");
        clock.now += 900000; session.tick();
        check(session.state() == LegacyVoiceSession.State.IDLE, "idle socket has no absolute 120s death");
        session.rearmRuntime(); session.onMessage(event("Vins", "VoiceInput", "first", 1));
        session.onBinary(new byte[]{0,0,0,1,1,2,3});
        clock.now += 15001; session.tick();
        check(session.state() == LegacyVoiceSession.State.EXPIRED && session.bufferedBytes() == 0,
                "15s capture expiry releases its buffer");
        check(callbacks.get() == 0, "expired input cannot reach callback");
        session.rearmRuntime(); session.onMessage(event("Vins", "VoiceInput", "second", 2));
        session.onBinary(new byte[]{0,0,0,2,4,5,6});
        session.onMessage(new JSONObject().put("streamcontrol",new JSONObject().put("streamId",2).put("action",0)));
        check(callbacks.get() == 1 && session.state() == LegacyVoiceSession.State.COMPLETE, "fresh turn after timeout");
        for (int i=0;i<600;i++) session.onCompletedKeepalive(event("System","EchoRequest","echo",0));
        session.rearmRuntime(); session.onMessage(event("System", "SynchronizeState", "sync2", 0));
        JSONObject sync = sent.get(sent.size()-2).getJSONObject("directive").getJSONObject("payload");
        check(identity.equals(sync.getString("SessionId")), "session identity preserved after reset");
        check(LegacyVoiceSession.CAPTURE_MILLIS == 15000 && LegacyVoiceSession.MAX_LEASE_MILLIS == 120000,
                "capture/command caps preserved");
        System.out.println("PASS runtime session idle, 15s capture, fresh budgets, keepalive and persistent identity");
    }
}
