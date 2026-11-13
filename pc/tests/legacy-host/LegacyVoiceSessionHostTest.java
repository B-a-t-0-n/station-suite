package local.station.voicebridge;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Synthetic legacy messages only. No sockets, microphone, real speech, APIs or Android runtime. */
public final class LegacyVoiceSessionHostTest {
    private static int assertions;
    private static final class Time implements LegacyVoiceSession.Clock {
        long now = 100, wall = 1794489600123L;
        public long monotonicMillis() { return now; }
        public long wallTimeMillis() { return wall; }
    }
    private static final class Test {
        final Time clock = new Time();
        final List<JSONObject> output = new ArrayList<>();
        final List<byte[]> commands = new ArrayList<>();
        LegacyVoiceSession.Metadata meta;
        int id;
        final LegacyVoiceSession session;
        Test() { this(false, false); }
        Test(boolean brokenSink, boolean brokenCallback) {
            session = new LegacyVoiceSession(clock, value -> {
                if (brokenSink) throw new IllegalStateException("SECRET-SINK-CONTENT");
                output.add(value);
            }, (audio, metadata) -> {
                commands.add(audio); meta = metadata;
                if (brokenCallback) throw new IllegalStateException("SECRET-CALLBACK-CONTENT");
            }, 30000, () -> "test-" + ++id);
        }
        void sync() throws Exception { session.onMessage(event("System", "SynchronizeState", "sync-1", 0, new JSONObject().put("secret", "DO-NOT-KEEP"))); }
        void command(long stream, String reference) throws Exception {
            session.onMessage(event("Vins", "VoiceInput", reference, stream,
                    new JSONObject().put("header", new JSONObject().put("request_id", "request-7").put("sequence_number", 7))
                            .put("enable_spotter_validation", true)));
        }
        JSONObject last() { return output.get(output.size() - 1); }
    }
    private interface Action { void run() throws Exception; }
    private static void check(boolean value, String label) {
        assertions++; if (!value) throw new AssertionError(label);
    }
    private static void error(Test test, String code, Action action) throws Exception {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (LegacyVoiceSession.SessionException expected) {
            check(code.equals(expected.code), "closed error code: " + code + " actual " + expected.code);
            check(expected.getMessage().equals(code) && expected.getCause() == null, "no raw exception content");
        }
        check(test.session.state() == LegacyVoiceSession.State.FAILED && test.session.bufferedBytes() == 0,
                "failure releases buffer and ends session");
    }
    private static JSONObject event(String namespace, String name, String ref, long stream, JSONObject payload) throws Exception {
        JSONObject header = new JSONObject().put("namespace", namespace).put("name", name).put("messageId", ref);
        if (stream != 0) header.put("streamId", stream);
        return new JSONObject().put("event", new JSONObject().put("header", header).put("payload", payload));
    }
    private static JSONObject control(long stream) throws Exception {
        return new JSONObject().put("streamcontrol", new JSONObject().put("messageId", "end-1")
                .put("reason", 0).put("action", 0).put("streamId", stream));
    }
    private static byte[] binary(long stream, byte... payload) {
        byte[] frame = new byte[payload.length + 4];
        frame[0] = (byte) (stream >>> 24); frame[1] = (byte) (stream >>> 16);
        frame[2] = (byte) (stream >>> 8); frame[3] = (byte) stream;
        System.arraycopy(payload, 0, frame, 4, payload.length); return frame;
    }
    private static JSONObject header(JSONObject message) throws Exception { return message.getJSONObject("directive").getJSONObject("header"); }
    private static JSONObject payload(JSONObject message) throws Exception { return message.getJSONObject("directive").getJSONObject("payload"); }
    private static void named(JSONObject message, String namespace, String name, String ref) throws Exception {
        JSONObject h = header(message);
        check(namespace.equals(h.getString("namespace")) && name.equals(h.getString("name")), "reply namespace/name");
        check(ref == null ? !h.has("refMessageId") : ref.equals(h.getString("refMessageId")), "reply reference");
        check(message.length() == 1 && message.getJSONObject("directive").length() == 2, "directive envelope");
    }

    private static void schemas() throws Exception {
        Test t = new Test(); t.sync();
        check(t.session.state() == LegacyVoiceSession.State.IDLE, "sync enters idle");
        check(t.output.size() == 2, "sync response plus push");
        named(t.output.get(0), "System", "SynchronizeStateResponse", "sync-1");
        check(!header(t.output.get(0)).has("messageId"), "legacy sync omits messageId");
        check(payload(t.output.get(0)).getString("SessionId").equals("lab-test-1"), "session ID retained");
        named(t.output.get(1), "System", "Push", null);
        check(header(t.output.get(1)).getLong("ack") == t.clock.wall, "push wall-clock ack");
        check(payload(t.output.get(1)).getJSONObject("analytics_meta_info").getString("request_id")
                .equals(t.clock.wall + "000$sync-1$test-2"), "PC analytics request shape");
        JSONArray directives = payload(t.output.get(1)).getJSONArray("directives");
        check(directives.length() == 1 && directives.getJSONObject(0).getString("name").equals("notify")
                && directives.getJSONObject(0).getJSONObject("payload").getString("version_id").equals("0"), "PC notify schema");
        check(!t.output.toString().contains("DO-NOT-KEEP"), "sync state not reflected into output");
        JSONObject echo = new JSONObject().put("probe", "opaque").put("values", new JSONArray().put(1).put(true));
        t.session.onMessage(event("System", "EchoRequest", "echo-1", 0, echo));
        named(t.last(), "System", "EchoResponse", "echo-1");
        echo.put("probe", "changed");
        check(payload(t.last()).getString("probe").equals("opaque"), "echo snapshot not caller-owned JSON");
        t.session.onMessage(event("Log", "RequestStat", "stat-1", 0, new JSONObject().put("unneeded", "discard")));
        named(t.last(), "Log", "RequestStatAck", "stat-1");
        check(payload(t.last()).length() == 0, "stat payload empty");
        t.command(1, "voice-1");
        named(t.last(), "System", "InputStartAck", "voice-1");
        check(payload(t.last()).getJSONObject("input_start_ack").getLong("request_start_time") == t.clock.wall * 1000,
                "InputStartAck is wall-clock microseconds");
    }

    private static void separatedStreams() throws Exception {
        Test t = new Test(); t.sync();
        JSONObject wake = event("Log", "Spotter", "spot-1", 0x01020304L, new JSONObject());
        t.session.onMessage(wake);
        JSONObject validation = t.output.get(2);
        named(validation, "Spotter", "Validation", "spot-1");
        check(header(validation).getLong("streamId") == 0x01020304L, "validation correct stream");
        JSONObject p = payload(validation);
        check(p.length() == 5 && p.getInt("allow_activation_by_unvalidated_spotter") == 0
                && p.getInt("valid") == 1 && p.getInt("result") == 1
                && p.getInt("canceled_cause_of_multiactivation") == 0
                && p.getString("multiactivation_id").equals(t.clock.wall + "local"), "exact PC Spotter.Validation schema");
        named(t.last(), "Log", "Ack", "spot-1");
        t.session.onMessage(wake);
        check(t.output.size() == 5, "duplicate spotter sends Ack, not duplicate validation");
        t.session.onBinary(binary(0x01020304L, (byte) 99, (byte) 98));
        check(t.session.bufferedBytes() == 0, "wakeword audio discarded");
        t.session.onMessage(control(0x01020304L));
        check(t.session.state() == LegacyVoiceSession.State.WAIT_COMMAND && t.commands.isEmpty(), "wake stream end is not command end");
        t.command(0x11223344L, "voice-1");
        t.session.onBinary(binary(0x01020304L, (byte) 55));
        t.session.onBinary(binary(0x44332211L, (byte) 66));
        t.session.onBinary(binary(0x11223344L, (byte) 1, (byte) 2, (byte) 3));
        t.session.onMessage(control(0x01020304L));
        check(t.commands.isEmpty(), "old wake end cannot finalize command");
        t.session.onMessage(control(0x11223344L));
        check(t.commands.size() == 1 && Arrays.equals(t.commands.get(0), new byte[]{1, 2, 3}), "only BE-ID matched command audio emitted");
        check(t.meta.streamId == 0x11223344L && t.meta.reference.equals("voice-1")
                && t.meta.requestId.equals("request-7") && t.meta.sequenceNumber == 7
                && t.meta.sessionId.equals("lab-test-1") && !t.meta.requiresSpotterValidation, "command metadata preserves identity");
        check(t.session.state() == LegacyVoiceSession.State.COMPLETE && t.session.bufferedBytes() == 0, "complete releases private buffer");
        t.session.onMessage(control(0x11223344L)); t.session.onBinary(binary(0x11223344L, (byte) 9));
        t.session.cancel(); t.session.tick();
        check(t.commands.size() == 1 && t.session.state() == LegacyVoiceSession.State.COMPLETE, "once finalize across duplicate end/data/cancel");
    }

    private static void directAndReentrant() throws Exception {
        Test t = new Test(); t.sync();
        t.session.onMessage(event("ASR", "Recognize", "recognize-1", 0, new JSONObject().put("enable_spotter_validation", true)));
        t.session.onBinary(binary(1, (byte) 8)); t.session.onMessage(control(1));
        check(t.meta.streamId == 1 && t.meta.requiresSpotterValidation && t.meta.sequenceNumber == 0, "direct ASR fallback and validation requirement");
        t = new Test(); t.sync(); t.command(0xffffffffL, "unsigned-1");
        t.session.onBinary(binary(0xffffffffL, (byte) 4)); t.session.onMessage(control(0xffffffffL));
        check(t.meta.streamId == 0xffffffffL && t.commands.size() == 1, "unsigned stream ID supported");
        final Test empty = new Test(); empty.sync(); empty.command(2, "empty"); empty.session.onMessage(control(2));
        check(empty.commands.isEmpty() && empty.session.reason().equals("empty_command"), "empty audio never invokes API callback");
        final Test duplicate = new Test(); duplicate.sync(); duplicate.command(3, "same"); duplicate.session.onBinary(binary(3, (byte) 1));
        int replies = duplicate.output.size(); duplicate.command(3, "same");
        check(duplicate.output.size() == replies && duplicate.session.bufferedBytes() == 1, "duplicate command start neither resets nor re-acks");
        error(duplicate, "overlapping_command", () -> duplicate.command(4, "different"));
        final Test callback = new Test(false, true); callback.sync(); callback.command(3, "one"); callback.session.onBinary(binary(3, (byte) 1));
        error(callback, "callback_failed", () -> callback.session.onMessage(control(3)));
        callback.session.onMessage(control(3));
        check(callback.commands.size() == 1, "failing callback never retried");
        final Test sink = new Test(true, false);
        error(sink, "sink_failed", sink::sync);
    }

    private static void timeouts() throws Exception {
        Test t = new Test(); t.clock.now += 30000; t.session.tick();
        check(t.session.reason().equals("lease_expired"), "idle lease expires without incoming data");
        t = new Test(); t.sync(); t.session.onMessage(event("Log", "Spotter", "wake", 9, new JSONObject()));
        t.session.onMessage(control(9)); t.clock.now += 2000;
        t.session.onMessage(control(9)); t.session.onMessage(event("Log", "Spotter", "wake", 9, new JSONObject()));
        t.clock.now += 500; t.session.tick();
        check(t.session.reason().equals("command_not_started") && t.commands.isEmpty(), "duplicate wake/end cannot prolong grace");
        t = new Test(); t.sync(); t.command(3, "timeout"); t.session.onBinary(binary(3, (byte) 1));
        t.clock.now += 15000; t.session.tick();
        check(t.session.reason().equals("capture_timeout") && t.session.bufferedBytes() == 0 && t.commands.isEmpty(), "timeout discards incomplete command");
        t = new Test(); t.sync(); t.command(3, "cancel"); t.session.onBinary(binary(3, (byte) 1)); t.session.cancel();
        t.session.onMessage(control(3));
        check(t.session.reason().equals("cancelled") && t.session.bufferedBytes() == 0 && t.commands.isEmpty(), "cancel stops delayed end from triggering callback");
        t = new Test(); t.sync(); t.command(3, "late"); t.clock.now += 30000; t.session.onMessage(control(3));
        check(t.commands.isEmpty() && t.session.reason().equals("lease_expired"), "deadline checked before consuming incoming end");
    }

    private static void limits() throws Exception {
        final Test early = new Test();
        error(early, "voice_before_sync", () -> early.command(1, "early"));
        final Test invalidId = new Test(); invalidId.sync();
        JSONObject bad = event("ASR", "Recognize", "id", 1, new JSONObject());
        bad.getJSONObject("event").getJSONObject("header").put("streamId", 1.0);
        error(invalidId, "invalid_integer", () -> invalidId.session.onMessage(bad));
        final Test shortBinary = new Test();
        error(shortBinary, "invalid_binary_frame", () -> shortBinary.session.onBinary(new byte[3]));
        final Test hugeFrame = new Test();
        error(hugeFrame, "invalid_binary_frame", () -> hugeFrame.session.onBinary(new byte[LegacyVoiceSession.MAX_FRAME_BYTES + 1]));
        final Test exact = new Test(); exact.sync(); exact.command(1, "exact");
        byte[] chunk = new byte[256 * 1024];
        exact.session.onBinary(binary(1, chunk)); exact.session.onBinary(binary(1, chunk));
        check(exact.session.bufferedBytes() == LegacyVoiceSession.MAX_COMMAND_BYTES, "exact command byte limit accepted");
        error(exact, "command_byte_limit", () -> exact.session.onBinary(binary(1, (byte) 1)));
        final Test total = new Test();
        for (int i = 0; i < 3; i++) total.session.onBinary(binary(99, chunk));
        error(total, "binary_limit", () -> total.session.onBinary(binary(99, chunk)));
        final Test frames = new Test();
        for (int i = 0; i < LegacyVoiceSession.MAX_BINARY_FRAMES; i++) frames.session.onBinary(binary(77));
        error(frames, "binary_limit", () -> frames.session.onBinary(binary(77)));
        final Test events = new Test();
        JSONObject ping = event("System", "Ping", "ping", 0, new JSONObject());
        for (int i = 0; i < LegacyVoiceSession.MAX_EVENTS; i++) events.session.onMessage(ping);
        error(events, "event_limit", () -> events.session.onMessage(ping));
        final Test largeJson = new Test();
        char[] huge = new char[LegacyVoiceSession.MAX_JSON_BYTES]; Arrays.fill(huge, 'x');
        error(largeJson, "json_byte_limit", () -> largeJson.session.onMessage(event("System", "Ping", "ping", 0, new JSONObject().put("data", new String(huge)))));
        final Test escapedKey = new Test();
        char[] key = new char[12000]; Arrays.fill(key, '\u0001');
        error(escapedKey, "json_byte_limit", () -> escapedKey.session.onMessage(new JSONObject().put(new String(key), 1)));
        final Test deep = new Test(); JSONObject root = new JSONObject(), current = root;
        for (int i = 0; i < 18; i++) { JSONObject child = new JSONObject(); current.put("child", child); current = child; }
        error(deep, "json_structure_limit", () -> deep.session.onMessage(root));
        final Test ambiguous = new Test();
        JSONObject mixed = control(1).put("event", new JSONObject());
        error(ambiguous, "ambiguous_message", () -> ambiguous.session.onMessage(mixed));
        final Test missingAction = new Test();
        JSONObject missing = control(1); missing.getJSONObject("streamcontrol").remove("action");
        error(missingAction, "invalid_message", () -> missingAction.session.onMessage(missing));
        final Test changedSync = new Test(); changedSync.sync(); changedSync.command(1, "active");
        error(changedSync, "sync_during_capture", changedSync::sync);
        final Test invalidFlag = new Test(); invalidFlag.sync();
        error(invalidFlag, "invalid_validation_flag", () -> invalidFlag.session.onMessage(event("ASR", "Recognize", "a", 1, new JSONObject().put("enable_spotter_validation", "true"))));
        Time nearMax = new Time(); nearMax.now = Long.MAX_VALUE - 1000;
        LegacyVoiceSession nearDeadline = new LegacyVoiceSession(nearMax, message -> {}, (audio, metadata) -> {}, 100);
        nearDeadline.onMessage(event("System", "SynchronizeState", "sync", 0, new JSONObject()));
        nearDeadline.onMessage(event("ASR", "Recognize", "command", 1, new JSONObject()));
        check(nearDeadline.state() == LegacyVoiceSession.State.COMMAND, "phase deadline does not overflow near monotonic long bound");
        nearMax.now += 100; nearDeadline.tick();
        check(nearDeadline.reason().equals("lease_expired"), "phase cap obeys absolute lease near long bound");
    }
    public static void main(String[] args) throws Exception {
        schemas(); separatedStreams(); directAndReentrant(); timeouts(); limits();
        System.out.println(assertions + " assertions passed; synthetic messages only, no sockets/API requests");
    }
}
