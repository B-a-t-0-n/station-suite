package local.station.voicebridge;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * One bounded legacy uniproxy utterance. No Android, sockets, API calls or logging.
 * The transport must validate strict JSON and WebSocket framing before onMessage.
 * All calls, including tick/cancel, belong to the same serial executor. The owner
 * must call tick while idle; this class deliberately creates no timer/thread.
 */
public final class LegacyVoiceSession {
    public interface Clock {
        long monotonicMillis();
        long wallTimeMillis();
    }
    public interface Sink { void send(JSONObject directive) throws Exception; }
    public interface CommandListener { void commandReady(byte[] audio, Metadata metadata) throws Exception; }
    /** Optional transport observer. Calls must only offer to a bounded queue; never perform I/O or decode. */
    public interface AudioObserver {
        boolean offerStart(Metadata metadata);
        /** Borrowed frame range, valid only during this call. The transport copies before handing it off. */
        boolean offerChunk(byte[] frame, int offset, int count);
        boolean offerEnd(Metadata metadata);
        void cancel(AudioCancelReason reason);
    }
    public enum AudioCancelReason {
        CANCELLED, TIMEOUT, RESET, REJECTED, EMPTY_COMMAND, OBSERVER_BACKPRESSURE, OBSERVER_ERROR
    }
    public interface IdSource { String next(); }
    public enum State { AWAIT_SYNC, IDLE, WAKEWORD, WAIT_COMMAND, COMMAND, COMPLETE, CANCELLED, EXPIRED, FAILED }

    public static final class Metadata {
        public final long streamId, sequenceNumber;
        public final String reference, requestId, sessionId;
        public final boolean requiresSpotterValidation;
        public final int audioBytes;
        /** VoiceInput to matching end control; no wall clock, identifiers or content. */
        public final long captureMillis;
        /** Native 0/5 close, or internal SERVER_ENDPOINT_REASON. Negative JSON reasons are rejected. */
        public final int endReason;
        private Metadata(long streamId, String reference, String requestId, long sequenceNumber,
                         String sessionId, boolean requiresSpotterValidation) {
            this(streamId, reference, requestId, sequenceNumber, sessionId, requiresSpotterValidation, 0);
        }
        private Metadata(long streamId, String reference, String requestId, long sequenceNumber,
                         String sessionId, boolean requiresSpotterValidation, int audioBytes) {
            this(streamId, reference, requestId, sequenceNumber, sessionId, requiresSpotterValidation, audioBytes, 0);
        }
        private Metadata(long streamId, String reference, String requestId, long sequenceNumber,
                         String sessionId, boolean requiresSpotterValidation, int audioBytes, long captureMillis) {
            this(streamId, reference, requestId, sequenceNumber, sessionId, requiresSpotterValidation,
                    audioBytes, captureMillis, 0);
        }
        private Metadata(long streamId, String reference, String requestId, long sequenceNumber,
                         String sessionId, boolean requiresSpotterValidation, int audioBytes, long captureMillis, int endReason) {
            this.streamId = streamId; this.reference = reference; this.requestId = requestId;
            this.sequenceNumber = sequenceNumber; this.sessionId = sessionId;
            this.requiresSpotterValidation = requiresSpotterValidation;
            this.audioBytes = audioBytes;
            this.captureMillis = captureMillis;
            this.endReason = endReason;
        }
    }
    public static final class SessionException extends Exception {
        private static final long serialVersionUID = 1L;
        public final String code;
        private SessionException(String code) { super(code); this.code = code; }
    }

    public static final int MAX_JSON_BYTES = 64 * 1024;
    public static final int MAX_COMMAND_BYTES = 512 * 1024;
    public static final int MAX_FRAME_BYTES = 256 * 1024 + 4;
    public static final long MAX_BINARY_BYTES = 1024 * 1024;
    public static final int MAX_EVENTS = 512, MAX_BINARY_FRAMES = 2048;
    public static final long MAX_LEASE_MILLIS = 120000, CAPTURE_MILLIS = 15000, SPOTTER_GRACE_MILLIS = 2500;
    public static final int SERVER_ENDPOINT_REASON = -3;
    private final Clock clock;
    private final Sink sink;
    private final CommandListener listener;
    private final IdSource ids;
    private long deadline;
    private boolean runtimeReusable;
    private final boolean captureAudio;
    private final AudioObserver audioObserver;
    private boolean observingAudio;
    private final Set<Long> validated = new HashSet<>();
    private ByteArrayOutputStream command = new ByteArrayOutputStream();
    private State state = State.AWAIT_SYNC;
    private String reason = "", sessionId;
    private long streamId, phaseDeadline, totalBinary, commandStartedMillis;
    private int events, frames;
    private int commandBytes;
    private Metadata metadata;
    private boolean spotterValidated;
    private long commandEndEvents;
    private int lastCommandEndReason = -1;

    public LegacyVoiceSession(Clock clock, Sink sink, CommandListener listener, long leaseMillis) {
        this(clock, sink, listener, leaseMillis, () -> UUID.randomUUID().toString());
    }
    /** Canary mode tracks byte counts without accumulating command audio. */
    public LegacyVoiceSession(Clock clock, Sink sink, CommandListener listener, long leaseMillis, boolean captureAudio) {
        this(clock, sink, listener, leaseMillis, () -> UUID.randomUUID().toString(), captureAudio);
    }
    public LegacyVoiceSession(Clock clock, Sink sink, CommandListener listener, long leaseMillis,
                              boolean captureAudio, AudioObserver observer) {
        this(clock, sink, listener, leaseMillis, () -> UUID.randomUUID().toString(), captureAudio, observer);
    }
    public LegacyVoiceSession(Clock clock, Sink sink, CommandListener listener, long leaseMillis, IdSource ids) {
        this(clock, sink, listener, leaseMillis, ids, true);
    }
    private LegacyVoiceSession(Clock clock, Sink sink, CommandListener listener, long leaseMillis,
                               IdSource ids, boolean captureAudio) {
        this(clock, sink, listener, leaseMillis, ids, captureAudio, null);
    }
    private LegacyVoiceSession(Clock clock, Sink sink, CommandListener listener, long leaseMillis,
                               IdSource ids, boolean captureAudio, AudioObserver observer) {
        if (clock == null || sink == null || listener == null || ids == null
                || leaseMillis < 1 || leaseMillis > MAX_LEASE_MILLIS)
            throw new IllegalArgumentException("invalid_session_configuration");
        this.clock = clock; this.sink = sink; this.listener = listener; this.ids = ids;
        this.captureAudio = captureAudio;
        this.audioObserver = observer;
        long now = clock.monotonicMillis();
        if (now < 0 || now > Long.MAX_VALUE - leaseMillis) throw new IllegalArgumentException("invalid_clock");
        deadline = now + leaseMillis;
    }

    public State state() { return state; }
    /** Closed reason codes only; no input payload, IDs, audio or credentials. */
    public String reason() { return reason; }
    public boolean terminal() { return state.ordinal() >= State.COMPLETE.ordinal(); }
    public int bufferedBytes() { return command.size(); }
    public int lastCommandEndReason() { return lastCommandEndReason; }
    long commandEndEvents() { return commandEndEvents; }
    Metadata currentMetadata() { return state == State.COMMAND ? metadata : null; }
    /** Reader-only server VAD boundary; never interprets a native cancellation as success. */
    boolean completeFromServer(Metadata expected) throws SessionException {
        tick();
        if (!runtimeReusable || state != State.COMMAND || expected == null || metadata != expected || commandBytes == 0)
            return false;
        commandEndEvents++;
        lastCommandEndReason = SERVER_ENDPOINT_REASON;
        completeCommand(SERVER_ENDPOINT_REASON);
        return true;
    }
    public void cancel() { if (!terminal()) end(State.CANCELLED, "cancelled"); }
    /** Transport opt-in only; default/probe sessions keep their original single-utterance lease. */
    void enableRuntimeReuse() { runtimeReusable = true; }
    /** A new explicit activation resets turn budgets but preserves the native session identity. */
    void rearmRuntime() {
        if (!runtimeReusable) throw new IllegalStateException("session_not_reusable");
        end(sessionId == null ? State.AWAIT_SYNC : State.IDLE, "");
        events = frames = 0; totalBinary = 0; spotterValidated = false;
        deadline = clock.monotonicMillis() + MAX_LEASE_MILLIS;
    }
    public void tick() {
        if (terminal()) return;
        if (runtimeReusable && (state == State.IDLE || state == State.AWAIT_SYNC)) return;
        long now = clock.monotonicMillis();
        if (now >= deadline) end(State.EXPIRED, "lease_expired");
        else if (phaseDeadline != 0 && now >= phaseDeadline)
            end(State.EXPIRED, state == State.WAIT_COMMAND ? "command_not_started" : "capture_timeout");
    }

    /** Accept parsed strict JSON. Performs independent structure/size bounds before inspecting fields. */
    public void onMessage(JSONObject message) throws SessionException {
        tick(); if (terminal()) return;
        if (runtimeReusable && (state == State.IDLE || state == State.AWAIT_SYNC)) events = 0;
        if (++events > MAX_EVENTS) throw fail("event_limit");
        try {
            Budget budget = new Budget(); budget.visit(message, 0);
            if (message.has("streamcontrol")) {
                if (message.length() != 1) throw fail("ambiguous_message");
                onControl(object(message, "streamcontrol")); return;
            }
            JSONObject event = object(message, "event"), header = object(event, "header");
            JSONObject payload = event.has("payload") ? object(event, "payload") : new JSONObject();
            String namespace = identifier(header, "namespace"), name = identifier(header, "name");
            String reference = identifier(header, "messageId");
            if ("System".equals(namespace) && "SynchronizeState".equals(name)) {
                if (state != State.AWAIT_SYNC && state != State.IDLE) throw fail("sync_during_capture");
                if (sessionId == null) sessionId = "lab-" + newId();
                send("System", "SynchronizeStateResponse", new JSONObject().put("SessionId", sessionId),
                        reference, false, 0);
                long ack = wall();
                JSONObject push = new JSONObject()
                        .put("analytics_meta_info", new JSONObject().put("request_id", ack + "000$" + reference + "$" + newId()))
                        .put("directives", new JSONArray().put(new JSONObject().put("name", "notify")
                                .put("payload", new JSONObject().put("version_id", "0"))));
                JSONObject response = directive("System", "Push", push, null, true, 0);
                response.getJSONObject("directive").getJSONObject("header").put("ack", ack);
                emit(response); state = State.IDLE; return;
            }
            if ("System".equals(namespace) && "EchoRequest".equals(name)) {
                send("System", "EchoResponse", new JSONObject(payload.toString()), reference, true, 0); return;
            }
            if ("Log".equals(namespace) && "RequestStat".equals(name)) {
                send("Log", "RequestStatAck", new JSONObject(), reference, true, 0); return;
            }
            boolean spotter = "Log".equals(namespace) && "Spotter".equals(name);
            boolean voice = ("Vins".equals(namespace) && "VoiceInput".equals(name))
                    || ("ASR".equals(namespace) && "Recognize".equals(name));
            if (!spotter && !voice) return;
            if (state == State.AWAIT_SYNC) throw fail("voice_before_sync");
            if (spotter) {
                long sid = stream(header, false);
                // A second activation cannot replace the command currently being collected.
                if (state == State.COMMAND) return;
                if (!validated.contains(sid)) {
                    if (validated.size() >= 32) throw fail("spotter_limit");
                    JSONObject validation = new JSONObject().put("allow_activation_by_unvalidated_spotter", 0)
                            .put("multiactivation_id", wall() + "local").put("valid", 1).put("result", 1)
                            .put("canceled_cause_of_multiactivation", 0);
                    send("Spotter", "Validation", validation, reference, true, sid);
                    validated.add(sid); spotterValidated = true;
                }
                send("Log", "Ack", new JSONObject(), reference, true, 0);
                // Duplicate wakeword events may be acknowledged but cannot extend a grace deadline.
                if (state != State.WAIT_COMMAND || streamId != sid) {
                    streamId = sid; state = State.WAKEWORD;
                    phaseDeadline = phaseDeadline(CAPTURE_MILLIS);
                }
                return;
            }
            long sid = stream(header, true);
            if (state == State.COMMAND) {
                if (metadata.streamId == sid && metadata.reference.equals(reference)) return;
                throw fail("overlapping_command");
            }
            String requestId = newId(); long sequence = 0;
            if (payload.has("header")) {
                JSONObject request = object(payload, "header");
                if (request.has("request_id")) requestId = identifier(request, "request_id");
                if (request.has("sequence_number")) sequence = integer(request, "sequence_number", 0, Integer.MAX_VALUE);
            }
            boolean requiresValidation = false;
            if (payload.has("enable_spotter_validation")) {
                Object value = payload.get("enable_spotter_validation");
                if (!(value instanceof Boolean)) throw fail("invalid_validation_flag");
                requiresValidation = (Boolean) value && !spotterValidated;
            }
            metadata = new Metadata(sid, reference, requestId, sequence, sessionId, requiresValidation);
            commandStartedMillis = clock.monotonicMillis();
            streamId = sid; command.reset(); commandBytes = 0; state = State.COMMAND; phaseDeadline = phaseDeadline(CAPTURE_MILLIS);
            JSONObject ack = new JSONObject().put("input_start_ack", new JSONObject().put("request_start_time", wall() * 1000L));
            send("System", "InputStartAck", ack, reference, true, 0);
            observeStart(metadata);
        } catch (SessionException failure) { throw failure; }
        catch (Exception failure) { throw fail("invalid_message"); }
    }

    /** The complete decoded binary frame, including its unsigned four-byte BIG-endian stream ID. */
    public void onBinary(byte[] frame) throws SessionException {
        tick(); if (terminal()) return;
        if (frame == null || frame.length < 4 || frame.length > MAX_FRAME_BYTES) throw fail("invalid_binary_frame");
        if (++frames > MAX_BINARY_FRAMES || (totalBinary += frame.length) > MAX_BINARY_BYTES) throw fail("binary_limit");
        long sid = ((frame[0] & 255L) << 24) | ((frame[1] & 255L) << 16)
                | ((frame[2] & 255L) << 8) | (frame[3] & 255L);
        // Wakeword, unknown and late streams consume the common budget but are never stored.
        if (state != State.COMMAND || sid != streamId) return;
        int size = frame.length - 4;
        if (commandBytes + size > MAX_COMMAND_BYTES) throw fail("command_byte_limit");
        commandBytes += size;
        if (captureAudio) command.write(frame, 4, size);
        if (size > 0 && observingAudio) {
            try {
                if (!audioObserver.offerChunk(frame, 4, size)) cancelObserver(AudioCancelReason.OBSERVER_BACKPRESSURE, false);
            } catch (Exception ignored) { cancelObserver(AudioCancelReason.OBSERVER_ERROR, false); }
        }
    }

    /** Runtime-only control while an async reply is pending. Never rearms or repeats a command. */
    void onCompletedKeepalive(JSONObject message) throws SessionException {
        if (state != State.COMPLETE && state != State.IDLE) return;
        try {
            Budget budget = new Budget(); budget.visit(message, 0);
            JSONObject event = message.optJSONObject("event");
            if (event == null) return;
            JSONObject header = object(event, "header");
            String namespace = identifier(header, "namespace"), name = identifier(header, "name");
            String reference = identifier(header, "messageId");
            if ("System".equals(namespace) && "EchoRequest".equals(name)) {
                JSONObject payload = event.has("payload") ? object(event, "payload") : new JSONObject();
                send("System", "EchoResponse", new JSONObject(payload.toString()), reference, true, 0);
            } else if ("Log".equals(namespace) && "RequestStat".equals(name))
                send("Log", "RequestStatAck", new JSONObject(), reference, true, 0);
        } catch (SessionException failure) { throw failure; }
        catch (Exception invalid) { throw fail("invalid_message"); }
    }

    private void onControl(JSONObject control) throws Exception {
        long sid = stream(control, false), action = integer(control, "action", 0, Integer.MAX_VALUE);
        if (action != 0 || sid != streamId) return;
        if (state == State.WAKEWORD) {
            state = State.WAIT_COMMAND; phaseDeadline = phaseDeadline(SPOTTER_GRACE_MILLIS); return;
        }
        if (state != State.COMMAND) return;
        commandEndEvents++;
        lastCommandEndReason = -2; // Fixed sentinel for a malformed reason.
        int nativeReason = control.has("reason") ? (int) integer(control, "reason", 0, Integer.MAX_VALUE) : 0;
        lastCommandEndReason = nativeReason;
        // Actual 20221212 maind: 5=recordingTimer/onStopRecording, 8=startingSilenceTimer.
        // Other meanings are deliberately not inferred; none may execute a command.
        if (nativeReason != 0 && nativeReason != 5) {
            cancelObserver(AudioCancelReason.REJECTED, false);
            end(State.COMPLETE, nativeReason == 8 ? "input_no_speech" : "input_rejected");
            return;
        }
        if (commandBytes == 0) {
            cancelObserver(AudioCancelReason.EMPTY_COMMAND, false);
            end(State.COMPLETE, "empty_command"); return;
        }
        completeCommand(nativeReason);
    }

    private void completeCommand(int inputEndReason) throws SessionException {
        byte[] bytes = command.toByteArray();
        Metadata completed = new Metadata(metadata.streamId, metadata.reference, metadata.requestId,
                metadata.sequenceNumber, metadata.sessionId, metadata.requiresSpotterValidation, commandBytes,
                Math.max(0, Math.min(CAPTURE_MILLIS, clock.monotonicMillis() - commandStartedMillis)), inputEndReason);
        // The queue sees end before the primary handler can choose progressive ownership or buffered fallback.
        observeEnd(completed);
        // Finalize BEFORE invoking user code: duplicate end/cancel or callback failure cannot repeat an API call.
        end(State.COMPLETE, "command_ready");
        try { listener.commandReady(bytes, completed); }
        catch (Exception failure) { state = State.FAILED; reason = "callback_failed"; throw new SessionException(reason); }
    }

    private long phaseDeadline(long duration) {
        long now = clock.monotonicMillis();
        return now >= deadline - duration ? deadline : now + duration;
    }
    private long wall() throws SessionException {
        long value = clock.wallTimeMillis();
        if (value < 0 || value > Long.MAX_VALUE / 1000L) throw fail("invalid_wall_clock");
        return value;
    }
    private String newId() throws SessionException {
        String value = ids.next();
        if (value == null || !value.matches("[A-Za-z0-9_.:$-]{1,160}")) throw fail("invalid_generated_id");
        return value;
    }
    private void send(String namespace, String name, JSONObject payload, String ref,
                      boolean withMessageId, long sid) throws Exception {
        emit(directive(namespace, name, payload, ref, withMessageId, sid));
    }
    private JSONObject directive(String namespace, String name, JSONObject payload, String ref,
                                 boolean withMessageId, long sid) throws Exception {
        JSONObject header = new JSONObject().put("namespace", namespace).put("name", name);
        if (withMessageId) header.put("messageId", newId());
        if (ref != null) header.put("refMessageId", ref);
        if (sid != 0) header.put("streamId", sid);
        return new JSONObject().put("directive", new JSONObject().put("header", header).put("payload", payload));
    }
    private void emit(JSONObject message) throws SessionException {
        try { sink.send(message); } catch (Exception failure) { throw fail("sink_failed"); }
    }
    private JSONObject object(JSONObject parent, String key) throws Exception {
        Object value = parent.get(key);
        if (!(value instanceof JSONObject)) throw fail("invalid_object");
        return (JSONObject) value;
    }
    private String identifier(JSONObject object, String key) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof String) || !((String) value).matches("[A-Za-z0-9_.:$-]{1,160}"))
            throw fail("invalid_identifier");
        return (String) value;
    }
    private long stream(JSONObject header, boolean defaultOne) throws Exception {
        return !header.has("streamId") && defaultOne ? 1 : integer(header, "streamId", 1, 0xffffffffL);
    }
    private long integer(JSONObject object, String key, long min, long max) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw fail("invalid_integer");
        long number = ((Number) value).longValue();
        if (number < min || number > max) throw fail("invalid_integer");
        return number;
    }
    private SessionException fail(String code) { end(State.FAILED, code); return new SessionException(code); }
    private void end(State finalState, String code) {
        if (observingAudio) cancelObserver(finalState == State.EXPIRED ? AudioCancelReason.TIMEOUT
                : finalState == State.FAILED ? AudioCancelReason.REJECTED
                : finalState == State.CANCELLED ? AudioCancelReason.CANCELLED : AudioCancelReason.RESET, false);
        state = finalState; reason = code; phaseDeadline = 0; metadata = null;
        command = new ByteArrayOutputStream(); commandBytes = 0; validated.clear();
    }

    private void observeStart(Metadata started) {
        if (audioObserver == null) return;
        try {
            observingAudio = audioObserver.offerStart(started);
            if (!observingAudio) cancelObserver(AudioCancelReason.OBSERVER_BACKPRESSURE, true);
        } catch (Exception ignored) { cancelObserver(AudioCancelReason.OBSERVER_ERROR, true); }
    }
    private void observeEnd(Metadata completed) {
        if (!observingAudio) return;
        observingAudio = false;
        try {
            if (!audioObserver.offerEnd(completed)) cancelObserver(AudioCancelReason.OBSERVER_BACKPRESSURE, true);
        } catch (Exception ignored) { cancelObserver(AudioCancelReason.OBSERVER_ERROR, true); }
    }
    private void cancelObserver(AudioCancelReason reason, boolean force) {
        boolean notify = observingAudio || force;
        observingAudio = false;
        if (notify && audioObserver != null) try { audioObserver.cancel(reason); } catch (Exception ignored) { }
    }

    /** Bounded walk before field access. The caller still owns strict text parsing and frame limits. */
    private final class Budget {
        private int bytes, entries;
        private void add(int count) throws SessionException {
            bytes += count; if (bytes > MAX_JSON_BYTES) throw fail("json_byte_limit");
        }
        void visit(Object value, int depth) throws Exception {
            if (depth > 16 || ++entries > 2048) throw fail("json_structure_limit");
            if (value instanceof JSONObject) {
                JSONObject object = (JSONObject) value; add(2);
                java.util.Iterator<String> keys = object.keys();
                while (keys.hasNext()) { String key = keys.next(); string(key); add(2); visit(object.get(key), depth + 1); }
            } else if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value; add(2);
                for (int i = 0; i < array.length(); i++) { add(1); visit(array.get(i), depth + 1); }
            } else if (value instanceof String) {
                string((String) value);
            } else if (value == null || value == JSONObject.NULL) add(4);
            else if (value instanceof Boolean) add(5);
            else if (value instanceof Number) {
                double number = ((Number) value).doubleValue();
                if (Double.isNaN(number) || Double.isInfinite(number)) throw fail("invalid_number");
                add(value.toString().length());
            } else throw fail("invalid_json_type");
        }
        private void string(String value) throws SessionException {
            if (value.length() > MAX_JSON_BYTES) throw fail("json_byte_limit");
            // Escape expansion is bounded too, without serializing the entire object.
            add(value.getBytes(StandardCharsets.UTF_8).length + 2);
            for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 32) add(5);
                else if (value.charAt(i) == '"' || value.charAt(i) == '\\') add(1);
        }
    }
}
