package local.station.voicebridge;

import java.util.ArrayList;
import java.util.Arrays;
import org.json.JSONObject;

/** Fake-clock progressive input lifecycle, no threads, socket, files, microphone or API. */
public final class ProgressiveSessionHostTest {
    private static int assertions;
    private static void check(boolean value, String label) {
        assertions++; if (!value) throw new AssertionError(label);
    }
    private static final class Clock implements LegacyVoiceSession.Clock {
        long now = 1;
        public long monotonicMillis() { return now; }
        public long wallTimeMillis() { return 1000000 + now; }
    }
    private static JSONObject event(String namespace, String name, String reference, long stream) throws Exception {
        JSONObject header = new JSONObject().put("namespace", namespace).put("name", name).put("messageId", reference);
        if (stream > 0) header.put("streamId", stream);
        return new JSONObject().put("event", new JSONObject().put("header", header).put("payload", new JSONObject()));
    }
    private static JSONObject end(long stream) throws Exception {
        return new JSONObject().put("streamcontrol", new JSONObject().put("streamId", stream).put("action", 0));
    }
    private static final class Fixture {
        final Clock clock = new Clock(); final ArrayList<String> order = new ArrayList<>();
        int starts, chunks, ends, primary, cancellations, observerBytes;
        LegacyVoiceSession.AudioCancelReason cancelled;
        LegacyVoiceSession.Metadata completed;
        byte[] full;
        final LegacyVoiceSession session;
        Fixture(boolean failAck) throws Exception {
            session = new LegacyVoiceSession(clock, value -> {
                String name = value.getJSONObject("directive").getJSONObject("header").getString("name");
                if (name.equals("InputStartAck")) {
                    if (failAck) throw new IllegalStateException("synthetic sink failure");
                    order.add("ack");
                }
            }, (audio, metadata) -> {
                order.add("primary"); primary++; full = audio; completed = metadata;
            }, 120000, true, new LegacyVoiceSession.AudioObserver() {
                public boolean offerStart(LegacyVoiceSession.Metadata metadata) {
                    check(metadata.audioBytes == 0, "start metadata has no completed audio");
                    starts++; observerBytes = 0; order.add("start"); return true;
                }
                public boolean offerChunk(byte[] frame, int offset, int count) {
                    check(offset == 4 && count == frame.length - 4, "only selected payload range");
                    observerBytes += count; chunks++; order.add("chunk"); return true;
                }
                public boolean offerEnd(LegacyVoiceSession.Metadata metadata) {
                    check(metadata.audioBytes == observerBytes, "end metadata matches offered bytes");
                    ends++; order.add("end"); return true;
                }
                public void cancel(LegacyVoiceSession.AudioCancelReason reason) {
                    cancellations++; cancelled = reason;
                }
            });
            session.enableRuntimeReuse();
            session.onMessage(event("System", "SynchronizeState", "synthetic-sync", 0));
        }
        void start() throws Exception { session.onMessage(event("Vins", "VoiceInput", "synthetic-command", 1)); }
        void audio() throws Exception { session.onBinary(new byte[]{0,0,0,1,5,6,7}); }
    }
    public static void main(String[] args) throws Exception {
        Fixture valid = new Fixture(false);
        valid.session.onMessage(event("Log", "Spotter", "synthetic-spotter", 9));
        valid.session.onBinary(new byte[]{0,0,0,9,8,8,8}); valid.session.onMessage(end(9));
        check(valid.starts == 0 && valid.chunks == 0, "spotter audio not observed");
        valid.start();
        check(valid.order.equals(Arrays.asList("ack", "start")), "ACK precedes start callback");
        valid.session.onBinary(new byte[]{0,0,0,99,8,8,8}); valid.audio();
        valid.session.onMessage(end(99)); check(valid.ends == 0, "foreign end cannot finalize");
        valid.clock.now += 123; valid.session.onMessage(end(1));
        check(valid.order.equals(Arrays.asList("ack", "start", "chunk", "end", "primary")), "end before primary");
        check(valid.primary == 1 && valid.completed.captureMillis == 123, "full primary and capture timing");
        check(Arrays.equals(valid.full, new byte[]{5,6,7}), "primary selected bytes preserved");
        valid.session.onMessage(end(1)); valid.session.onBinary(new byte[]{0,0,0,1,0});
        check(valid.ends == 1 && valid.primary == 1 && valid.chunks == 1, "late data/end cannot duplicate");
        valid.session.rearmRuntime(); check(valid.cancellations == 0, "normal end not reset-cancelled by late Spotter rearm");

        Fixture expired = new Fixture(false); expired.start(); expired.audio();
        expired.clock.now += 15001; expired.session.tick();
        check(expired.cancelled == LegacyVoiceSession.AudioCancelReason.TIMEOUT, "capture timeout cancels observer");
        check(expired.primary == 0 && expired.ends == 0 && expired.session.bufferedBytes() == 0, "timeout releases primary");
        expired.session.rearmRuntime(); expired.start(); expired.audio(); expired.session.onMessage(end(1));
        check(expired.primary == 1 && expired.starts == 2, "fresh turn after timeout");

        Fixture reset = new Fixture(false); reset.start(); reset.audio(); reset.session.rearmRuntime();
        check(reset.cancelled == LegacyVoiceSession.AudioCancelReason.RESET && reset.primary == 0, "reset cleanup");
        Fixture close = new Fixture(false); close.start(); close.audio(); close.session.cancel(); close.session.cancel();
        check(close.cancelled == LegacyVoiceSession.AudioCancelReason.CANCELLED && close.cancellations == 1, "close cancel once");
        Fixture empty = new Fixture(false); empty.start(); empty.session.onMessage(end(1));
        check(empty.cancelled == LegacyVoiceSession.AudioCancelReason.EMPTY_COMMAND && empty.primary == 0, "empty cleanup");

        Fixture bounded = new Fixture(false); bounded.start();
        byte[] large = new byte[LegacyVoiceSession.MAX_FRAME_BYTES]; large[3] = 1;
        bounded.session.onBinary(large); bounded.session.onBinary(large);
        try { bounded.session.onBinary(large); throw new AssertionError("accepted oversized command"); }
        catch (LegacyVoiceSession.SessionException expected) {
            check(expected.code.equals("command_byte_limit"), "selected command cap remains enforced");
        }
        check(bounded.observerBytes == LegacyVoiceSession.MAX_COMMAND_BYTES && bounded.chunks == 2,
                "rejected bytes never reach observer");
        check(bounded.cancelled == LegacyVoiceSession.AudioCancelReason.REJECTED && bounded.primary == 0,
                "rejected command cleanup");
        Fixture failedAck = new Fixture(true);
        try { failedAck.start(); throw new AssertionError("accepted failed ACK"); }
        catch (LegacyVoiceSession.SessionException expected) { check(failedAck.starts == 0, "no start before successful ACK"); }
        System.out.println("PASS progressive session " + assertions + " assertions; synthetic only");
    }
}
