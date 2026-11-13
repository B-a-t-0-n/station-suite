package local.station.voicebridge;

import org.json.JSONObject;

public final class CommandEndReasonHostTest {
    static int checks;
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    static JSONObject event(String namespace, String name, String id) throws Exception {
        return new JSONObject().put("event", new JSONObject().put("header", new JSONObject()
                .put("namespace", namespace).put("name", name).put("messageId", id).put("streamId", 1))
                .put("payload", new JSONObject()));
    }
    static final class Fixture {
        int callbacks, cancellations; LegacyVoiceSession.Metadata completed;
        final LegacyVoiceSession session = new LegacyVoiceSession(new LegacyVoiceSession.Clock() {
            public long monotonicMillis() { return 100; }
            public long wallTimeMillis() { return 100; }
        }, value -> { }, (audio, metadata) -> { callbacks++; completed = metadata; }, 120000, true,
                new LegacyVoiceSession.AudioObserver() {
                    public boolean offerStart(LegacyVoiceSession.Metadata meta) { return true; }
                    public boolean offerChunk(byte[] data, int off, int count) { return true; }
                    public boolean offerEnd(LegacyVoiceSession.Metadata meta) { return true; }
                    public void cancel(LegacyVoiceSession.AudioCancelReason reason) { cancellations++; }
                });
        Fixture() throws Exception {
            session.onMessage(event("System", "SynchronizeState", "sync"));
            session.onMessage(event("Vins", "VoiceInput", "input"));
            session.onBinary(new byte[] {0, 0, 0, 1, 10, 20});
        }
        void close(Object reason, boolean omit) throws Exception {
            JSONObject control = new JSONObject().put("streamId", 1).put("action", 0);
            if (!omit) control.put("reason", reason);
            session.onMessage(new JSONObject().put("streamcontrol", control));
        }
    }
    public static void main(String[] args) throws Exception {
        for (Object value : new Object[] {null, 0, 5, 7, 8, 6, 2, 999, Integer.MAX_VALUE}) {
            Fixture f = new Fixture(); boolean success = value == null || value.equals(0) || value.equals(5);
            f.close(value, value == null);
            check(f.callbacks == (success ? 1 : 0));
            check(f.session.lastCommandEndReason() == (value == null ? 0 : ((Integer) value).intValue()));
            check(f.session.bufferedBytes() == 0); check(f.session.state() == LegacyVoiceSession.State.COMPLETE);
            check(f.cancellations == (success ? 0 : 1));
            if (success) check(f.completed.endReason == (value == null ? 0 : ((Integer) value).intValue()));
            f.close(0, false); check(f.callbacks == (success ? 1 : 0));
        }
        for (Object invalid : new Object[] {"0", true, 0.5, -1, JSONObject.NULL, Long.MAX_VALUE}) {
            Fixture f = new Fixture();
            try { f.close(invalid, false); throw new AssertionError("invalid reason accepted"); }
            catch (LegacyVoiceSession.SessionException expected) { check(f.callbacks == 0); }
            check(f.session.lastCommandEndReason() == -2); check(f.session.bufferedBytes() == 0);
            check(f.cancellations == 1);
        }
        Fixture f = new Fixture();
        f.session.onMessage(new JSONObject().put("streamcontrol", new JSONObject()
                .put("streamId", 2).put("action", 0).put("reason", 8)));
        check(f.session.state() == LegacyVoiceSession.State.COMMAND); check(f.session.lastCommandEndReason() == -1);
        f.close(5, false); check(f.callbacks == 1);
        System.out.println("Command end reason PASS checks=" + checks);
    }
}
