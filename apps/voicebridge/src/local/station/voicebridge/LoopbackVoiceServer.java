package local.station.voicebridge;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Explicitly started, bounded loopback protocol probe. This class neither captures a
 * microphone nor changes native configuration, performs ASR, or calls an upstream API.
 * Probe/canary modes count and discard audio. Handler modes hold one bounded selected
 * command in memory; this transport never saves or logs its contents.
 */
public final class LoopbackVoiceServer {
    public static final int PORT = 17682;
    public static final long MAX_LEASE_MILLIS = 120000L;
    public static final long MAX_VOICE_WINDOW_MILLIS = 300000L;
    public static final long MAX_RUNTIME_IDLE_MILLIS = 300000L;
    private static final int MAX_CONNECTIONS = 2;
    private static final int MAX_HEADER = 16 * 1024;
    private static final int MAX_FRAME = 4 * 1024 * 1024;
    private static final int MAX_TEXT = 64 * 1024;
    private static final long MAX_AUDIO_PER_SESSION = 8L * 1024 * 1024;
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private final long leaseMillis;
    private final boolean legacyMode;
    private final boolean manualVoiceWindow;
    private final boolean runtimeMode;
    private final CommandHandler commandHandler;
    private final ProgressiveInput progressiveInput;
    private Run current;

    /** Runs on the connection worker once. The writer can also be used by a bounded async worker. */
    public interface CommandHandler {
        void commandReady(byte[] audio, LegacyVoiceSession.Metadata metadata, ReplyWriter writer) throws Exception;
    }
    /**
     * Optional bounded queue adapter, never a recognizer. Each method must return without I/O,
     * decoding, waiting for capacity, or reentering the writer. Start/chunk/end are reader-ordered;
     * cancel may come from another thread. False/exception preserves the full buffered callback.
     */
    public interface ProgressiveInput {
        boolean offerStart(LegacyVoiceSession.Metadata metadata, ReplyWriter writer);
        /** Caller owns this independent selected-payload copy; it never contains the stream-ID prefix. */
        boolean offerChunk(ReplyWriter writer, byte[] selectedAudio);
        boolean offerEnd(LegacyVoiceSession.Metadata metadata, ReplyWriter writer);
        void cancel(ReplyWriter writer, CancelReason reason);
    }
    public enum CancelReason {
        SUPERSEDED, CONNECTION_CLOSED, CAPTURE_TIMEOUT, EMPTY_COMMAND, SESSION_RESET,
        INPUT_REJECTED, OBSERVER_BACKPRESSURE, OBSERVER_ERROR, WRITER_CLOSED
    }

    public LoopbackVoiceServer() { this(MAX_LEASE_MILLIS); }

    /** A shorter lease is useful for a controlled test; no caller can extend the cap. */
    public LoopbackVoiceServer(long leaseMillis) {
        this(leaseMillis, false, null);
    }

    /** Explicit legacy mode. A null handler is a canary that only counts completed commands. */
    public LoopbackVoiceServer(long leaseMillis, CommandHandler handler) {
        this(leaseMillis, true, handler);
    }

    private LoopbackVoiceServer(long leaseMillis, boolean legacyMode, CommandHandler handler) {
        this(leaseMillis, legacyMode, handler, false);
    }

    /** Explicit manual window only. Existing constructors and each session retain their 120 s cap. */
    public static LoopbackVoiceServer voiceWindow(long leaseMillis, CommandHandler handler) {
        if (handler == null) throw new IllegalArgumentException("voice_window_handler_required");
        return new LoopbackVoiceServer(leaseMillis, true, handler, true);
    }

    private LoopbackVoiceServer(long leaseMillis, boolean legacyMode, CommandHandler handler,
                                boolean manualVoiceWindow) {
        this(leaseMillis, legacyMode, handler, manualVoiceWindow, false);
    }

    /** Explicit persistent listener; each connection/capture and all buffers remain bounded. */
    public static LoopbackVoiceServer runtime(CommandHandler handler) {
        if (handler == null) throw new IllegalArgumentException("runtime_handler_required");
        return new LoopbackVoiceServer(0, true, handler, false, true);
    }
    /** Explicit progressive input opt-in. Null observer preserves the existing buffered runtime. */
    public static LoopbackVoiceServer runtime(CommandHandler handler, ProgressiveInput observer) {
        if (handler == null) throw new IllegalArgumentException("runtime_handler_required");
        return new LoopbackVoiceServer(0, true, handler, false, true, observer);
    }

    private LoopbackVoiceServer(long leaseMillis, boolean legacyMode, CommandHandler handler,
                                boolean manualVoiceWindow, boolean runtimeMode) {
        this(leaseMillis, legacyMode, handler, manualVoiceWindow, runtimeMode, null);
    }
    private LoopbackVoiceServer(long leaseMillis, boolean legacyMode, CommandHandler handler,
                                boolean manualVoiceWindow, boolean runtimeMode, ProgressiveInput observer) {
        long cap = manualVoiceWindow ? MAX_VOICE_WINDOW_MILLIS : MAX_LEASE_MILLIS;
        if (!runtimeMode && (leaseMillis < 1 || leaseMillis > cap)) {
            throw new IllegalArgumentException(manualVoiceWindow
                    ? "voice window lease must be 1..300000 milliseconds" : "lease must be 1..120000 milliseconds");
        }
        this.leaseMillis = leaseMillis;
        this.legacyMode = legacyMode;
        this.manualVoiceWindow = manualVoiceWindow;
        this.runtimeMode = runtimeMode;
        this.commandHandler = handler;
        this.progressiveInput = observer;
    }

    /** Binds synchronously. A bind failure is available as a fixed status reason. */
    public synchronized void start() {
        if (current != null && current.active) return;
        final Run run = new Run(runtimeMode);
        current = run;
        ServerSocket listener = null;
        try {
            listener = new ServerSocket();
            // Android/Linux needs reuse before bind for consecutive bounded windows after
            // accepted sockets enter TIME_WAIT. Windows gives this option different port
            // sharing semantics, so preserve exclusive host-test binding there.
            listener.setReuseAddress("Linux".equalsIgnoreCase(System.getProperty("os.name", "")));
            listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 2);
            listener.setSoTimeout(1000);
            run.listener = listener;
            run.startedAtMillis = System.currentTimeMillis();
            run.deadlineNanos = runtimeMode ? Long.MAX_VALUE
                    : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(leaseMillis);
            run.active = true;
            run.stopReason = "";
            ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, r -> {
                Thread thread = new Thread(r, "VoiceProbeLease");
                thread.setDaemon(true);
                return thread;
            });
            timer.setRemoveOnCancelPolicy(true);
            run.timer = timer;
            if (!runtimeMode) run.timer.schedule(() -> run.shutdown("lease_expired"), leaseMillis, TimeUnit.MILLISECONDS);
            Thread accept = new Thread(() -> acceptLoop(run), "VoiceProbeAccept");
            accept.setDaemon(true);
            accept.start();
        } catch (IOException | RuntimeException error) {
            closeQuietly(listener);
            run.shutdown("bind_or_start_failed");
        }
    }

    /** Cancels the lease and closes listener AND every active handshake/frame socket. */
    public synchronized void stop() {
        if (current != null) current.shutdown("manual_stop");
    }

    public synchronized JSONObject status() {
        JSONObject result = new JSONObject();
        Run run = current;
        try {
            result.put("mode", runtimeMode ? "legacy-runtime" : !legacyMode ? "protocol-probe"
                    : commandHandler == null ? "legacy-canary" : "legacy-handler");
            result.put("speechImplemented", false);
            result.put("commandHandlerConfigured", commandHandler != null);
            result.put("progressiveInputConfigured", progressiveInput != null);
            result.put("nativeReplyWriter", legacyMode);
            result.put("microphoneCapture", false);
            result.put("host", "127.0.0.1");
            result.put("port", PORT);
            result.put("reuseAddress", "Linux".equalsIgnoreCase(System.getProperty("os.name", "")));
            result.put("streamIdByteOrder", "big-endian");
            result.put("maxFrameBytes", MAX_FRAME);
            result.put("maxAudioBytesPerSession", MAX_AUDIO_PER_SESSION);
            result.put("maxConnections", MAX_CONNECTIONS);
            result.put("leaseMillis", leaseMillis);
            result.put("manualVoiceWindow", manualVoiceWindow);
            result.put("permanentVoiceRuntime", runtimeMode);
            result.put("maxLeaseMillis", runtimeMode ? 0 : manualVoiceWindow ? MAX_VOICE_WINDOW_MILLIS : MAX_LEASE_MILLIS);
            result.put("maxSessionMillis", MAX_LEASE_MILLIS);
            result.put("maxConnectionIdleMillis", runtimeMode ? MAX_RUNTIME_IDLE_MILLIS : 0);
            result.put("active", run != null && run.active);
            result.put("remainingMillis", runtimeMode || run == null || !run.active ? 0
                    : Math.max(0, TimeUnit.NANOSECONDS.toMillis(run.deadlineNanos - System.nanoTime())));
            result.put("startedAt", run == null ? 0 : run.startedAtMillis);
            result.put("stopReason", run == null ? "not_started" : run.stopReason);
            result.put("connections", run == null ? 0 : run.connections.get());
            result.put("rejectedConnections", run == null ? 0 : run.rejectedConnections.get());
            result.put("activeConnections", run == null ? 0 : run.activeCount());
            result.put("handshakes", run == null ? 0 : run.handshakes.get());
            result.put("synchronizations", run == null ? 0 : run.synchronizations.get());
            result.put("textFrames", run == null ? 0 : run.textFrames.get());
            result.put("binaryFrames", run == null ? 0 : run.binaryFrames.get());
            result.put("audioBytes", run == null ? 0 : run.audioBytes.get());
            result.put("pingFrames", run == null ? 0 : run.pingFrames.get());
            result.put("errors", run == null ? 0 : run.errors.get());
            result.put("lastError", run == null ? "" : run.lastError);
            result.put("lastNamespace", run == null ? "" : run.lastNamespace);
            result.put("lastEvent", run == null ? "" : run.lastEvent);
            result.put("lastStreamId", run == null ? -1 : run.lastStreamId.get());
            result.put("spotterEvents", run == null ? 0 : run.spotterEvents.get());
            result.put("voiceInputEvents", run == null ? 0 : run.voiceInputEvents.get());
            result.put("streamControls", run == null ? 0 : run.streamControls.get());
            result.put("commands", run == null ? 0 : run.commands.get());
            result.put("staleCommandCallbacks", run == null ? 0 : run.staleCommandCallbacks.get());
            result.put("commandBytes", run == null ? 0 : run.commandBytes.get());
            result.put("replies", run == null ? 0 : run.replies.get());
            result.put("replyBytes", run == null ? 0 : run.replyBytes.get());
            result.put("maxBufferedCommandBytes", run == null ? 0 : run.maxBufferedCommandBytes.get());
            result.put("lastLegacyState", run == null ? "" : run.lastLegacyState);
            result.put("cancelledReplies", run == null ? 0 : run.cancelledReplies.get());
            result.put("voiceGenerations", run == null ? 0 : run.generation.get());
            result.put("runtimeIdleClosures", run == null ? 0 : run.idleClosures.get());
            result.put("runtimeWriteTimeouts", run == null ? 0 : run.writeTimeouts.get());
            result.put("runtimeTurnExpirations", run == null ? 0 : run.turnExpirations.get());
            result.put("silentCompletions", run == null ? 0 : run.silentCompletions.get());
            result.put("streamingCompletions", run == null ? 0 : run.streamingCompletions.get());
            result.put("streamingAborts", run == null ? 0 : run.streamingAborts.get());
            result.put("partialStreamingAborts", run == null ? 0 : run.partialStreamingAborts.get());
            result.put("lastCaptureMs", run == null ? 0 : run.lastCaptureMs.get());
            result.put("lastCommandEndReason", run == null ? -1 : run.lastCommandEndReason.get());
            result.put("inputStopRequests", run == null ? 0 : run.inputStopRequests.get());
            result.put("inputStopSent", run == null ? 0 : run.inputStopSent.get());
            result.put("inputStopAcknowledgements", run == null ? 0 : run.inputStopAcknowledgements.get());
            result.put("inputStopCancellations", run == null ? 0 : run.inputStopCancellations.get());
            result.put("asrProgressRequests", run == null ? 0 : run.asrProgressRequests.get());
            result.put("asrProgressSent", run == null ? 0 : run.asrProgressSent.get());
            result.put("asrProgressCoalesced", run == null ? 0 : run.asrProgressCoalesced.get());
            result.put("asrProgressDiscarded", run == null ? 0 : run.asrProgressDiscarded.get());
            result.put("serverEndpointRequests", run == null ? 0 : run.serverEndpointRequests.get());
            result.put("serverEndpointCompletions", run == null ? 0 : run.serverEndpointCompletions.get());
            result.put("serverEndpointCancellations", run == null ? 0 : run.serverEndpointCancellations.get());
            result.put("inputFinalizations", run == null ? 0 : run.inputFinalizations.get());
            result.put("inputFinalizationFailures", run == null ? 0 : run.inputFinalizationFailures.get());
            result.put("inputFinalizationAborts", run == null ? 0 : run.inputFinalizationAborts.get());
            result.put("lastEndCaptureToFirstAudioMs", run == null ? -1 : run.lastFirstAudioMs.get());
            result.put("lastEndCaptureToOutputEndMs", run == null ? -1 : run.lastOutputEndMs.get());
            result.put("progressiveStarts", run == null ? 0 : run.progressiveStarts.get());
            result.put("progressiveChunks", run == null ? 0 : run.progressiveChunks.get());
            result.put("progressiveBytes", run == null ? 0 : run.progressiveBytes.get());
            result.put("progressiveEnds", run == null ? 0 : run.progressiveEnds.get());
            result.put("progressiveCancellations", run == null ? 0 : run.progressiveCancellations.get());
            result.put("progressiveFallbacks", run == null ? 0 : run.progressiveFallbacks.get());
            result.put("progressiveObserverErrors", run == null ? 0 : run.progressiveObserverErrors.get());
            result.put("nativeSpotter", run == null ? new NativeAudioStats().snapshot() : run.nativeSpotter.snapshot());
            result.put("nativeCommand", run == null ? new NativeAudioStats().snapshot() : run.nativeCommand.snapshot());
        } catch (JSONException impossibleForFixedKeys) {
            // All values above are finite primitives or bounded fixed metadata.
        }
        return result;
    }

    private void acceptLoop(Run run) {
        try {
            while (run.active) {
                Socket socket;
                try { socket = run.listener.accept(); }
                catch (SocketTimeoutException waitAgain) { continue; }
                if (!run.admit(socket)) {
                    run.rejectedConnections.incrementAndGet();
                    closeQuietly(socket);
                    continue;
                }
                run.connections.incrementAndGet();
                Thread worker = new Thread(() -> handle(run, socket), "VoiceProbeSession");
                worker.setDaemon(true);
                worker.start();
            }
        } catch (IOException | RuntimeException error) {
            if (run.active) run.recordError("listener_failed");
        } finally {
            run.shutdown(run.active ? "listener_stopped" : run.stopReason);
        }
    }

    private void handle(Run run, Socket socket) {
        OutputStream output = null;
        ReplyWriter writer = null;
        LegacyVoiceSession session = null;
        final NativeStreams nativeStreams = new NativeStreams();
        RuntimeTurns turns = null;
        ScheduledFuture<?> connectionExpiry = null;
        boolean upgraded = false;
        try {
            socket.setSoTimeout(1000);
            socket.setTcpNoDelay(true);
            InputStream input = socket.getInputStream();
            output = socket.getOutputStream();
            handshake(run, input, output);
            upgraded = true;
            run.handshakes.incrementAndGet();
            writer = new ReplyWriter(run, socket, output);
            if (runtimeMode) {
                final Connection connection = writer.connection;
                turns = new RuntimeTurns(writer);
                connectionExpiry = run.timer.scheduleWithFixedDelay(() -> {
                    if (System.nanoTime() - connection.lastActivityNanos >= TimeUnit.MILLISECONDS.toNanos(MAX_RUNTIME_IDLE_MILLIS)) {
                        run.idleClosures.incrementAndGet(); connection.close();
                    }
                }, 1000, 1000, TimeUnit.MILLISECONDS);
            }
            if (legacyMode) {
                final ReplyWriter reply = writer;
                final RuntimeTurns runtimeTurns = turns;
                session = new LegacyVoiceSession(new LegacyVoiceSession.Clock() {
                    @Override public long monotonicMillis() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()); }
                    @Override public long wallTimeMillis() { return System.currentTimeMillis(); }
                }, reply::sendJson, (audio, metadata) -> {
                    ReplyWriter commandWriter = runtimeMode ? runtimeTurns.current : reply;
                    if (commandWriter != null) commandWriter.inputCompleted(metadata);
                    if (commandWriter != null) commandWriter.commandReady(metadata);
                    if (runtimeMode && commandWriter != null) commandWriter.armExpiry();
                    run.commands.incrementAndGet();
                    run.commandBytes.addAndGet(metadata.audioBytes);
                    if (commandHandler != null) {
                        if (commandWriter != null && (!runtimeMode || commandWriter.isOpen()))
                            commandHandler.commandReady(audio, metadata, commandWriter);
                        else if (runtimeMode) run.staleCommandCallbacks.incrementAndGet();
                    }
                }, Math.max(1, Math.min(MAX_LEASE_MILLIS,
                        TimeUnit.NANOSECONDS.toMillis(run.deadlineNanos - System.nanoTime()))), commandHandler != null,
                        progressiveInput == null ? null : new LegacyVoiceSession.AudioObserver() {
                    private ProgressiveTurn observing;
                    @Override public boolean offerStart(LegacyVoiceSession.Metadata metadata) {
                        ReplyWriter selected = runtimeTurns.current;
                        if (selected == null || !selected.isOpen()) return false;
                        selected.inputStarted(metadata);
                        observing = new ProgressiveTurn(run, selected, progressiveInput);
                        selected.progressive = observing;
                        return observing.start(metadata);
                    }
                    @Override public boolean offerChunk(byte[] frame, int offset, int count) {
                        return observing != null && observing.chunk(frame, offset, count);
                    }
                    @Override public boolean offerEnd(LegacyVoiceSession.Metadata metadata) {
                        if (observing != null) observing.writer.inputCompleted(metadata);
                        if (observing != null) observing.writer.commandReady(metadata);
                        return observing != null && observing.end(metadata);
                    }
                    @Override public void cancel(LegacyVoiceSession.AudioCancelReason reason) {
                        if (observing == null) return;
                        switch (reason) {
                            case CANCELLED: observing.cancel(CancelReason.CONNECTION_CLOSED); break;
                            case TIMEOUT: observing.cancel(CancelReason.CAPTURE_TIMEOUT); break;
                            case RESET: observing.cancel(CancelReason.SESSION_RESET); break;
                            case REJECTED: observing.cancel(CancelReason.INPUT_REJECTED); break;
                            case EMPTY_COMMAND: observing.cancel(CancelReason.EMPTY_COMMAND); break;
                            case OBSERVER_BACKPRESSURE: observing.cancel(CancelReason.OBSERVER_BACKPRESSURE); break;
                            case OBSERVER_ERROR: observing.cancel(CancelReason.OBSERVER_ERROR); break;
                        }
                    }
                });
                if (runtimeMode) session.enableRuntimeReuse();
            }
            long sessionAudio = 0;
            while (run.active) {
                Frame frame = readFrame(run, input, runtimeMode ? 0 : sessionAudio, session, turns);
                writer.connection.lastActivityNanos = System.nanoTime();
                if (frame.opcode == 8) {
                    validateClose(frame.payload);
                    writer.send(8, frame.payload);
                    return;
                }
                if (frame.opcode == 9) {
                    run.pingFrames.incrementAndGet();
                    writer.send(10, frame.payload);
                } else if (frame.opcode == 1) {
                    run.textFrames.incrementAndGet();
                    if (legacyMode) onLegacyText(run, frame.payload, session, nativeStreams, turns);
                    else onText(run, frame.payload, output);
                } else if (frame.opcode == 2) {
                    if (frame.payload.length < 4) throw new ProtocolError(1002, "short_stream_header");
                    // Matches the working PC's struct.unpack('>I', payload[:4]).
                    long streamId = ((frame.payload[0] & 255L) << 24)
                            | ((frame.payload[1] & 255L) << 16)
                            | ((frame.payload[2] & 255L) << 8) | (frame.payload[3] & 255L);
                    long audioSize = frame.payload.length - 4L;
                    sessionAudio += audioSize;
                    run.lastStreamId.set(streamId);
                    run.binaryFrames.incrementAndGet();
                    run.audioBytes.addAndGet(audioSize);
                    if (session != null) {
                        nativeStreams.binary(run, streamId, frame.payload);
                        session.onBinary(frame.payload);
                        long buffered = session.bufferedBytes(), previous;
                        do { previous = run.maxBufferedCommandBytes.get(); }
                        while (buffered > previous && !run.maxBufferedCommandBytes.compareAndSet(previous, buffered));
                    }
                }
                if (session != null) run.lastLegacyState = session.state().name();
                // This probe discards the frame here, including all audio/text payloads.
            }
        } catch (LegacyVoiceSession.SessionException error) {
            run.recordError("legacy_" + error.code);
            if (writer != null) try { writer.send(8, new byte[]{3, (byte) 240}); } catch (IOException ignored) { }
        } catch (ProtocolError error) {
            run.recordError(error.reason);
            if (output != null) {
                try {
                    if (upgraded && writer != null) writer.send(8, new byte[]{(byte) (error.code >>> 8), (byte) error.code});
                    else sendHttpError(output, error.code);
                } catch (IOException ignored) { }
            }
        } catch (EOFException peerClosed) {
            // A clean TCP disconnect is metadata-free and is not an audio error.
        } catch (IOException | RuntimeException error) {
            if (run.active && (writer == null || writer.valid.get())) run.recordError("session_io_failed");
        } finally {
            if (connectionExpiry != null) connectionExpiry.cancel(false);
            if (session != null) session.cancel();
            if (writer != null) { writer.invalidate(); writer.connection.close(); }
            run.finishWriter(writer);
            closeQuietly(socket);
            run.release(socket);
        }
    }

    private static void handshake(Run run, InputStream input, OutputStream output)
            throws IOException, ProtocolError {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int tail = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            if (bytes.size() == MAX_HEADER) throw new ProtocolError(431, "header_too_large");
            int next = readByte(run, input, deadline);
            bytes.write(next);
            tail = (tail << 8) | next;
            if (tail == 0x0d0a0d0a) break;
        }
        String request = new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1);
        String[] lines = request.split("\r\n", -1);
        if (lines.length < 3 || !"GET /uni.ws HTTP/1.1".equals(lines[0])) {
            throw new ProtocolError(400, "bad_request_path_or_method");
        }
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length - 2; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon < 1 || Character.isWhitespace(line.charAt(0))) {
                throw new ProtocolError(400, "bad_header");
            }
            String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
            if (!name.matches("[!#$%&'*+.^_`|~0-9a-z-]+")) throw new ProtocolError(400, "bad_header");
            String value = line.substring(colon + 1).trim();
            for (int j = 0; j < value.length(); j++) {
                char c = value.charAt(j);
                if (c < 32 && c != '\t' || c == 127) throw new ProtocolError(400, "bad_header");
            }
            if (headers.put(name, value) != null) throw new ProtocolError(400, "duplicate_header");
        }
        if (!"13".equals(headers.get("sec-websocket-version"))) throw new ProtocolError(426, "bad_ws_version");
        if (!hasToken(headers.get("upgrade"), "websocket")
                || !hasToken(headers.get("connection"), "upgrade")
                || headers.get("host") == null || headers.get("host").isEmpty()) {
            throw new ProtocolError(400, "missing_upgrade_headers");
        }
        String key = headers.get("sec-websocket-key");
        try {
            if (key == null || Base64.getDecoder().decode(key).length != 16
                    || !Base64.getEncoder().encodeToString(Base64.getDecoder().decode(key)).equals(key)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalidKey) { throw new ProtocolError(400, "bad_ws_key"); }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(StandardCharsets.US_ASCII));
            String response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                    + "Connection: Upgrade\r\nSec-WebSocket-Accept: "
                    + Base64.getEncoder().encodeToString(digest) + "\r\n\r\n";
            output.write(response.getBytes(StandardCharsets.US_ASCII));
            output.flush();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException("sha1_unavailable"); }
    }

    private static boolean hasToken(String header, String token) {
        if (header == null) return false;
        for (String item : header.split(",")) if (token.equalsIgnoreCase(item.trim())) return true;
        return false;
    }

    private static Frame readFrame(Run run, InputStream input, long sessionAudio, LegacyVoiceSession session, RuntimeTurns turns)
            throws IOException, ProtocolError {
        int first = readByte(run, input, session == null ? System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                : run.deadlineNanos, session, turns);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int second = readByte(run, input, deadline);
        boolean fin = (first & 128) != 0;
        int opcode = first & 15;
        if ((first & 112) != 0) throw new ProtocolError(1002, "reserved_frame_bits");
        if (opcode != 0 && opcode != 1 && opcode != 2 && opcode != 8 && opcode != 9 && opcode != 10) {
            throw new ProtocolError(1002, "reserved_opcode");
        }
        if ((second & 128) == 0) throw new ProtocolError(1002, "client_frame_unmasked");
        int marker = second & 127;
        if (opcode >= 8 && (!fin || marker > 125)) throw new ProtocolError(1002, "invalid_control_frame");
        if (!fin || opcode == 0) throw new ProtocolError(1003, "fragmentation_unsupported");
        long length = marker;
        if (marker == 126) {
            length = (readByte(run, input, deadline) << 8) | readByte(run, input, deadline);
            if (length < 126) throw new ProtocolError(1002, "noncanonical_length");
        } else if (marker == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) {
                int next = readByte(run, input, deadline);
                if (i == 0 && (next & 128) != 0) throw new ProtocolError(1002, "invalid_frame_length");
                length = (length << 8) | next;
            }
            if (length <= 65535) throw new ProtocolError(1002, "noncanonical_length");
        }
        if (length > MAX_FRAME || opcode == 1 && length > MAX_TEXT) throw new ProtocolError(1009, "frame_too_large");
        if (opcode == 2 && length >= 4 && length - 4 > MAX_AUDIO_PER_SESSION - sessionAudio) {
            throw new ProtocolError(1009, "session_audio_limit");
        }
        byte[] mask = new byte[4];
        readFully(run, input, mask, deadline);
        byte[] payload = new byte[(int) length];
        readFully(run, input, payload, deadline);
        for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
        return new Frame(opcode, payload);
    }

    private static void onText(Run run, byte[] payload, OutputStream output)
            throws IOException, ProtocolError {
        String text = utf8(payload);
        // Android JSONTokener accepts non-standard quotes/unquoted values. Validate
        // strict JSON first so those extensions cannot conceal recursive nesting.
        if (!new StrictJson(text).valid()) throw new ProtocolError(1008, "invalid_or_deep_json");
        final JSONObject message;
        try { message = new JSONObject(text); }
        catch (JSONException invalidJson) { throw new ProtocolError(1007, "invalid_json"); }
        JSONObject event = message.optJSONObject("event");
        JSONObject header = event == null ? message.optJSONObject("header") : event.optJSONObject("header");
        if (header == null) throw new ProtocolError(1008, "missing_event_header");
        String namespace = header.optString("namespace", "");
        String name = header.optString("name", "");
        run.lastNamespace = knownNamespace(namespace);
        run.lastEvent = knownEvent(name);
        if (!"System".equals(namespace) || !"SynchronizeState".equals(name)) return;
        String reference = header.optString("messageId", "");
        if (!reference.matches("[A-Za-z0-9_.:$-]{1,160}")) throw new ProtocolError(1008, "bad_message_id");
        try {
            JSONObject responseHeader = new JSONObject().put("namespace", "System")
                    .put("name", "SynchronizeStateResponse").put("refMessageId", reference);
            JSONObject response = new JSONObject().put("directive", new JSONObject()
                    .put("header", responseHeader)
                    .put("payload", new JSONObject().put("SessionId", "probe-" + UUID.randomUUID())));
            sendFrame(output, 1, response.toString().getBytes(StandardCharsets.UTF_8));
            run.synchronizations.incrementAndGet();
        } catch (JSONException invalidResponse) { throw new ProtocolError(1011, "response_failed"); }
    }

    private static void onLegacyText(Run run, byte[] payload, LegacyVoiceSession session, NativeStreams streams,
                                     RuntimeTurns turns)
            throws ProtocolError, LegacyVoiceSession.SessionException {
        String text = utf8(payload);
        if (!new StrictJson(text).valid()) throw new ProtocolError(1008, "invalid_or_deep_json");
        final JSONObject message;
        try { message = new JSONObject(text); }
        catch (JSONException invalidJson) { throw new ProtocolError(1007, "invalid_json"); }
        JSONObject event = message.optJSONObject("event");
        JSONObject header = event == null ? null : event.optJSONObject("header");
        String namespace = header == null ? "" : header.optString("namespace");
        String name = header == null ? "" : header.optString("name");
        run.lastNamespace = knownNamespace(namespace);
        run.lastEvent = knownEvent(name);
        if (message.has("streamcontrol")) run.streamControls.incrementAndGet();
        else if ("System".equals(namespace) && "SynchronizeState".equals(name)) run.synchronizations.incrementAndGet();
        else if ("Log".equals(namespace) && "Spotter".equals(name)) run.spotterEvents.incrementAndGet();
        else if ("Vins".equals(namespace) && "VoiceInput".equals(name)
                || "ASR".equals(namespace) && "Recognize".equals(name)) run.voiceInputEvents.incrementAndGet();
        boolean spotter = "Log".equals(namespace) && "Spotter".equals(name);
        boolean command = "Vins".equals(namespace) && "VoiceInput".equals(name)
                || "ASR".equals(namespace) && "Recognize".equals(name);
        if (run.runtimeMode && session.state() == LegacyVoiceSession.State.COMPLETE
                && "System".equals(namespace) && "SynchronizeState".equals(name)) session.rearmRuntime();
        if (spotter || command) {
            Object rawStream = header.opt("streamId");
            long stream = rawStream == null && command ? 1
                    : rawStream instanceof Integer || rawStream instanceof Long ? ((Number) rawStream).longValue() : -1;
            if (stream >= 1 && stream <= 0xffffffffL) {
                Object rawReference = header.opt("messageId");
                if (run.runtimeMode && session.state() != LegacyVoiceSession.State.AWAIT_SYNC
                        && rawReference instanceof String
                        && ((String) rawReference).matches("[A-Za-z0-9_.:$-]{1,160}")) {
                    turns.activate(session, run, (String) rawReference, stream, spotter);
                }
                NativeAudioStats stats = spotter ? run.nativeSpotter : run.nativeCommand;
                if (spotter) streams.spotter = stream;
                else streams.command = stream;
                JSONObject body = event.optJSONObject("payload");
                stats.metadata(header);
                stats.metadata(body);
                if (body != null) {
                    stats.metadata(body.optJSONObject("header"));
                    stats.metadata(body.optJSONObject("extra"));
                }
            }
        }
        long previousEnds = session.commandEndEvents();
        try {
            if (run.runtimeMode && session.state() == LegacyVoiceSession.State.COMPLETE)
                session.onCompletedKeepalive(message);
            else session.onMessage(message);
            if (turns != null && turns.current != null && session.currentMetadata() != null)
                turns.current.inputStarted(session.currentMetadata());
        } finally {
            if (session.commandEndEvents() != previousEnds) {
                int reason = session.lastCommandEndReason();
                run.lastCommandEndReason.set(reason);
                if (turns != null && turns.current != null) turns.current.inputEnded(reason);
            }
        }
        run.lastLegacyState = session.state().name();
    }

    private static String knownNamespace(String value) {
        switch (value) {
            case "System": case "Vins": case "ASR": case "Spotter": case "Log": case "TTS": return value;
            default: return "other";
        }
    }

    private static String knownEvent(String value) {
        switch (value) {
            case "SynchronizeState": case "VoiceInput": case "Recognize": case "Spotter":
            case "Echo": case "Ping": case "Ack": case "RequestStat": case "Validation":
            case "InputStart": case "InputEnd": case "StreamControl": return value;
            default: return "other";
        }
    }

    /** Small validator with at most 32 recursive levels, before the platform parser. */
    private static final class StrictJson {
        final String text;
        int index;
        StrictJson(String text) { this.text = text; }
        boolean valid() {
            whitespace();
            if (index == text.length() || text.charAt(index) != '{' || !value(0)) return false;
            whitespace();
            return index == text.length();
        }
        boolean value(int depth) {
            whitespace();
            if (index == text.length()) return false;
            char c = text.charAt(index);
            if (c == '"') return string();
            if (c == '{' || c == '[') {
                if (depth >= 32) return false;
                index++;
                whitespace();
                char end = c == '{' ? '}' : ']';
                if (take(end)) return true;
                while (true) {
                    if (c == '{') {
                        whitespace();
                        if (!string()) return false;
                        whitespace();
                        if (!take(':')) return false;
                    }
                    if (!value(depth + 1)) return false;
                    whitespace();
                    if (take(end)) return true;
                    if (!take(',')) return false;
                }
            }
            if (c == 't') return literal("true");
            if (c == 'f') return literal("false");
            if (c == 'n') return literal("null");
            int start = index;
            take('-');
            if (!take('0')) {
                if (index == text.length() || text.charAt(index) < '1' || text.charAt(index) > '9') return false;
                while (digit()) index++;
            }
            if (take('.')) {
                if (!digit()) return false;
                while (digit()) index++;
            }
            if (take('e') || take('E')) {
                if (!take('+')) take('-');
                if (!digit()) return false;
                while (digit()) index++;
            }
            return index > start;
        }
        boolean string() {
            if (!take('"')) return false;
            while (index < text.length()) {
                char c = text.charAt(index++);
                if (c == '"') return true;
                if (c < 32) return false;
                if (c == '\\') {
                    if (index == text.length()) return false;
                    char escaped = text.charAt(index++);
                    if (escaped == 'u') {
                        for (int i = 0; i < 4; i++) {
                            if (index == text.length()) return false;
                            char hex = text.charAt(index++);
                            if (!(hex >= '0' && hex <= '9' || hex >= 'a' && hex <= 'f'
                                    || hex >= 'A' && hex <= 'F')) return false;
                        }
                    } else if ("\"\\/bfnrt".indexOf(escaped) < 0) return false;
                }
            }
            return false;
        }
        boolean literal(String expected) {
            if (!text.startsWith(expected, index)) return false;
            index += expected.length();
            return true;
        }
        boolean digit() { return index < text.length() && text.charAt(index) >= '0' && text.charAt(index) <= '9'; }
        boolean take(char c) {
            if (index == text.length() || text.charAt(index) != c) return false;
            index++;
            return true;
        }
        void whitespace() {
            while (index < text.length() && " \t\r\n".indexOf(text.charAt(index)) >= 0) index++;
        }
    }

    private static String utf8(byte[] payload) throws ProtocolError {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(payload)).toString();
        } catch (CharacterCodingException invalidUtf8) { throw new ProtocolError(1007, "invalid_utf8"); }
    }

    private static void validateClose(byte[] payload) throws ProtocolError {
        if (payload.length == 1) throw new ProtocolError(1002, "invalid_close_payload");
        if (payload.length < 2) return;
        int code = ((payload[0] & 255) << 8) | (payload[1] & 255);
        if (!(code >= 3000 && code <= 4999 || code >= 1000 && code <= 1014
                && code != 1004 && code != 1005 && code != 1006)) {
            throw new ProtocolError(1002, "invalid_close_code");
        }
        byte[] reason = new byte[payload.length - 2];
        System.arraycopy(payload, 2, reason, 0, reason.length);
        utf8(reason);
    }

    private static int readByte(Run run, InputStream input, long deadline) throws IOException, ProtocolError {
        return readByte(run, input, deadline, null);
    }

    private static int readByte(Run run, InputStream input, long deadline, LegacyVoiceSession session)
            throws IOException, ProtocolError {
        return readByte(run, input, deadline, session, null);
    }
    private static int readByte(Run run, InputStream input, long deadline, LegacyVoiceSession session, RuntimeTurns turns)
            throws IOException, ProtocolError {
        while (run.active) {
            if (session != null) {
                session.tick();
                run.lastLegacyState = session.state().name();
                if (session.state() == LegacyVoiceSession.State.EXPIRED)
                    if (run.runtimeMode) session.rearmRuntime();
                    else throw new ProtocolError(1008, "legacy_" + session.reason());
            }
            if (turns != null) turns.flushInputStop(session);
            if (System.nanoTime() >= deadline) throw new ProtocolError(1008, "read_timeout");
            try {
                int value = input.read();
                if (value < 0) throw new EOFException();
                return value;
            } catch (SocketTimeoutException waitAgain) { /* Recheck lease/deadline. */ }
        }
        throw new EOFException();
    }

    private static void readFully(Run run, InputStream input, byte[] bytes, long deadline)
            throws IOException, ProtocolError {
        int offset = 0;
        while (offset < bytes.length && run.active) {
            if (System.nanoTime() >= deadline) throw new ProtocolError(1008, "read_timeout");
            try {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) throw new EOFException();
                offset += read;
            } catch (SocketTimeoutException waitAgain) { /* Recheck lease/deadline. */ }
        }
        if (offset < bytes.length) throw new EOFException();
    }

    private static void sendFrame(OutputStream output, int opcode, byte[] payload) throws IOException {
        output.write(128 | opcode);
        int length = payload.length;
        if (length < 126) output.write(length);
        else if (length <= 65535) {
            output.write(126); output.write(length >>> 8); output.write(length);
        } else {
            output.write(127);
            long longLength = length;
            for (int shift = 56; shift >= 0; shift -= 8) output.write((int) (longLength >>> shift) & 255);
        }
        output.write(payload);
        output.flush();
    }

    private static void sendHttpError(OutputStream output, int status) throws IOException {
        // A frame-read timeout before upgrade is an HTTP request timeout, not a WS code.
        if (status < 400 || status > 599) status = 408;
        String extra = status == 426 ? "Sec-WebSocket-Version: 13\r\n" : "";
        output.write(("HTTP/1.1 " + status + " Rejected\r\nConnection: close\r\n"
                + extra + "Content-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    /** One observer lifetime per child token. Only bounded queue operations run under this private lock. */
    private static final class ProgressiveTurn {
        final Run run;
        final ReplyWriter writer;
        final ProgressiveInput input;
        private final Object lock = new Object();
        private boolean live = true, ended;
        ProgressiveTurn(Run run, ReplyWriter writer, ProgressiveInput input) {
            this.run = run; this.writer = writer; this.input = input;
        }
        boolean start(LegacyVoiceSession.Metadata metadata) {
            synchronized (lock) {
                if (!open()) return false;
                try {
                    if (!input.offerStart(metadata, writer)) return fallback(CancelReason.OBSERVER_BACKPRESSURE);
                    run.progressiveStarts.incrementAndGet(); return true;
                } catch (Exception ignored) {
                    run.progressiveObserverErrors.incrementAndGet(); return fallback(CancelReason.OBSERVER_ERROR);
                }
            }
        }
        boolean chunk(byte[] frame, int offset, int count) {
            synchronized (lock) {
                if (!open() || ended) return false;
                if (frame == null || offset < 0 || count < 1 || count > LegacyVoiceSession.MAX_FRAME_BYTES - 4
                        || offset > frame.length - count) return fallback(CancelReason.INPUT_REJECTED);
                // The primary buffer has already accepted these bytes. The observer owns an independent copy.
                byte[] copied = Arrays.copyOfRange(frame, offset, offset + count);
                try {
                    if (!input.offerChunk(writer, copied)) return fallback(CancelReason.OBSERVER_BACKPRESSURE);
                    run.progressiveChunks.incrementAndGet(); run.progressiveBytes.addAndGet(count); return true;
                } catch (Exception ignored) {
                    run.progressiveObserverErrors.incrementAndGet(); return fallback(CancelReason.OBSERVER_ERROR);
                }
            }
        }
        boolean end(LegacyVoiceSession.Metadata metadata) {
            synchronized (lock) {
                if (!open() || ended) return false;
                try {
                    if (!input.offerEnd(metadata, writer)) return fallback(CancelReason.OBSERVER_BACKPRESSURE);
                    ended = true; run.progressiveEnds.incrementAndGet(); return true;
                } catch (Exception ignored) {
                    run.progressiveObserverErrors.incrementAndGet(); return fallback(CancelReason.OBSERVER_ERROR);
                }
            }
        }
        private boolean open() {
            if (!live) return false;
            if (writer.isOpen()) return true;
            cancelLocked(CancelReason.WRITER_CLOSED); return false;
        }
        private boolean fallback(CancelReason reason) {
            if (live) { run.progressiveFallbacks.incrementAndGet(); cancelLocked(reason); }
            return false;
        }
        void cancel(CancelReason reason) { synchronized (lock) { cancelLocked(reason); } }
        private void cancelLocked(CancelReason reason) {
            if (!live) return;
            live = false; run.progressiveCancellations.incrementAndGet();
            try { input.cancel(writer, reason); }
            catch (Exception ignored) { run.progressiveObserverErrors.incrementAndGet(); }
        }
    }

    private static void closeQuietly(java.io.Closeable value) {
        if (value != null) try { value.close(); } catch (IOException ignored) { }
    }

    private static final class Frame {
        final int opcode;
        final byte[] payload;
        Frame(int opcode, byte[] payload) { this.opcode = opcode; this.payload = payload; }
    }

    private static final class ProtocolError extends Exception {
        private static final long serialVersionUID = 1L;
        final int code;
        final String reason;
        ProtocolError(int code, String reason) { this.code = code; this.reason = reason; }
    }

    /** Shared wire ownership; command cancellation never invalidates this healthy connection. */
    private static final class Connection {
        final Run run;
        final Socket socket;
        final OutputStream output;
        final Object writeLock = new Object();
        final AtomicBoolean open = new AtomicBoolean(true);
        volatile long lastActivityNanos = System.nanoTime();
        Connection(Run run, Socket socket, OutputStream output) {
            this.run = run; this.socket = socket; this.output = output;
        }
        void close() { if (open.getAndSet(false)) closeQuietly(socket); }
    }

    /** One reply per command. Runtime close/cancel affect this turn, never the next turn's TCP socket. */
    public static final class ReplyWriter {
        private final Run run;
        private final Connection connection;
        private final boolean commandToken;
        private final AtomicBoolean valid = new AtomicBoolean(true);
        private final long deadlineNanos;
        private ScheduledFuture<?> expiry;
        private boolean replied;
        private volatile LegacyVoiceReply.Streaming streaming;
        private volatile ProgressiveTurn progressive;
        private volatile long commandCompletedNanos, captureMillis, firstAudioMillis = -1, outputEndMillis = -1;
        private volatile long generation;
        private volatile LegacyVoiceSession.Metadata inputMetadata;
        private volatile boolean inputEnded;
        // 0=no request, 1=queued, 2=sent, 3=successful native close, 4=cancelled.
        private final java.util.concurrent.atomic.AtomicInteger inputStop = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicReference<AsrProgress> asrProgress = new java.util.concurrent.atomic.AtomicReference<>();
        private final java.util.concurrent.atomic.AtomicInteger asrProgressCount = new java.util.concurrent.atomic.AtomicInteger();
        private final AtomicLong lastAsrProgressNanos = new AtomicLong();
        private volatile String lastAsrProgressText;
        private final java.util.concurrent.atomic.AtomicInteger serverEndpoint = new java.util.concurrent.atomic.AtomicInteger();
        private volatile LegacyVoiceSession.Metadata completedMetadata;
        private boolean inputFinishStarted, inputFinished;
        private ReplyWriter(Run run, Socket socket, OutputStream output) {
            this(new Connection(run, socket, output), false);
        }
        private ReplyWriter(Connection connection, boolean commandToken) {
            this.connection = connection; this.run = connection.run; this.commandToken = commandToken;
            deadlineNanos = run.runtimeMode
                    ? commandToken ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MAX_LEASE_MILLIS) : Long.MAX_VALUE
                    : run.deadlineNanos;
        }
        private void commandReady(LegacyVoiceSession.Metadata metadata) {
            if (commandCompletedNanos != 0) return;
            commandCompletedNanos = System.nanoTime(); captureMillis = metadata.captureMillis;
            run.lastCaptureMs.set(captureMillis); run.lastFirstAudioMs.set(-1); run.lastOutputEndMs.set(-1);
        }
        private void inputStarted(LegacyVoiceSession.Metadata metadata) {
            if (inputMetadata == null) inputMetadata = metadata;
        }
        private void inputCompleted(LegacyVoiceSession.Metadata metadata) {
            completedMetadata = metadata;
            inputEnded(metadata.endReason);
        }
        /** Nonblocking opt-in server VAD boundary. No provisional transcript or native ACK is used. */
        public boolean requestServerEndpoint(LegacyVoiceSession.Metadata metadata) {
            if (!commandToken || !run.runtimeMode || metadata == null || inputMetadata != metadata
                    || inputEnded || !isOpen() || !serverEndpoint.compareAndSet(0, 1)) return false;
            run.serverEndpointRequests.incrementAndGet();
            if (inputEnded || !isOpen()) { cancelServerEndpoint(); return false; }
            return true;
        }
        private void cancelServerEndpoint() {
            int state;
            do {
                state = serverEndpoint.get();
                if (state != 1 && state != 2) return;
            } while (!serverEndpoint.compareAndSet(state, 4));
            run.serverEndpointCancellations.incrementAndGet();
        }
        private void flushServerEndpoint(LegacyVoiceSession session) throws IOException {
            if (serverEndpoint.get() != 1) return;
            if (inputEnded || !isOpen() || session.currentMetadata() != inputMetadata) { cancelServerEndpoint(); return; }
            if (!serverEndpoint.compareAndSet(1, 2)) return;
            try {
                if (session.completeFromServer(inputMetadata)) {
                    serverEndpoint.compareAndSet(2, 3);
                    run.serverEndpointCompletions.incrementAndGet();
                    run.lastCommandEndReason.set(LegacyVoiceSession.SERVER_ENDPOINT_REASON);
                    run.lastLegacyState = session.state().name();
                } else cancelServerEndpoint();
            } catch (LegacyVoiceSession.SessionException failure) {
                cancelServerEndpoint(); throw new IOException("server_endpoint_failed");
            }
        }
        /**
         * Real nonempty worker ASR partial only. Bounded CAS/coalescing, no I/O,
         * inference, final recognition, input stop, reply reservation or action.
         * First update is immediate; then 200 ms minimum, at most 16 per turn.
         */
        public boolean requestPartialAsr(LegacyVoiceSession.Metadata metadata, String text) {
            if (!commandToken || !run.runtimeMode || metadata == null || inputMetadata != metadata
                    || inputEnded || !isOpen() || text == null || text.length() == 0 || text.length() > 1024
                    || text.trim().length() == 0 || text.getBytes(StandardCharsets.UTF_8).length > 4096
                    || text.equals(lastAsrProgressText) || asrProgressCount.get() >= 16) return false;
            long now = System.nanoTime(), previous = lastAsrProgressNanos.get();
            if (previous != 0 && now - previous < TimeUnit.MILLISECONDS.toNanos(200)
                    || !lastAsrProgressNanos.compareAndSet(previous, now)) return false;
            int number = asrProgressCount.getAndIncrement();
            if (number >= 16) return false;
            AsrProgress update = new AsrProgress(text, number);
            lastAsrProgressText = text;
            if (asrProgress.getAndSet(update) != null) run.asrProgressCoalesced.incrementAndGet();
            run.asrProgressRequests.incrementAndGet();
            if (inputEnded || !isOpen()) { clearAsrProgress(); return false; }
            return true;
        }
        private void clearAsrProgress() {
            if (asrProgress.getAndSet(null) != null) run.asrProgressDiscarded.incrementAndGet();
            lastAsrProgressText = null;
        }
        private void flushAsrProgress(LegacyVoiceSession session) throws IOException {
            AsrProgress update = asrProgress.getAndSet(null);
            if (update == null) return;
            if (inputEnded || !isOpen() || session.currentMetadata() != inputMetadata) {
                run.asrProgressDiscarded.incrementAndGet(); return;
            }
            try {
                sendJson(LegacyVoiceReply.partialAsr(inputMetadata, update.text, update.number));
                run.asrProgressSent.incrementAndGet();
            } catch (IOException error) {
                run.asrProgressDiscarded.incrementAndGet();
                if (isOpen()) throw error;
            }
        }
        private static final class AsrProgress {
            final String text; final int number;
            AsrProgress(String text, int number) { this.text = text; this.number = number; }
        }
        /**
         * Bounded nonblocking opt-in: only queue a stop for this exact started command.
         * No wire I/O, endpoint inference, capture finalization or API is performed here.
         * The reader sends action=0/reason=0 and continues collecting until native close.
         */
        public boolean requestInputStop(LegacyVoiceSession.Metadata metadata) {
            if (!commandToken || !run.runtimeMode || metadata == null || inputMetadata != metadata
                    || inputEnded || !isOpen() || !inputStop.compareAndSet(0, 1)) return false;
            run.inputStopRequests.incrementAndGet();
            if (inputEnded || !isOpen()) { cancelInputStop(); return false; }
            return true;
        }
        private void cancelInputStop() {
            int state;
            do {
                state = inputStop.get();
                if (state != 1 && state != 2) return;
            } while (!inputStop.compareAndSet(state, 4));
            run.inputStopCancellations.incrementAndGet();
        }
        private void inputEnded(int reason) {
            inputEnded = true;
            clearAsrProgress();
            if (reason == LegacyVoiceSession.SERVER_ENDPOINT_REASON) serverEndpoint.compareAndSet(2, 3);
            else cancelServerEndpoint();
            if ((reason == 0 || reason == 5) && inputStop.compareAndSet(2, 3))
                run.inputStopAcknowledgements.incrementAndGet();
            else cancelInputStop();
            if (reason != 0 && reason != 5 && reason != LegacyVoiceSession.SERVER_ENDPOINT_REASON)
                cancelReply(CancelReason.INPUT_REJECTED);
        }
        private void flushInputStop(LegacyVoiceSession session) throws IOException {
            if (inputStop.get() != 1) return;
            if (inputEnded || !isOpen() || session.currentMetadata() != inputMetadata) { cancelInputStop(); return; }
            if (!inputStop.compareAndSet(1, 2)) return;
            try {
                JSONObject control = new JSONObject().put("streamcontrol", new JSONObject()
                        .put("messageId", UUID.randomUUID().toString()).put("action", 0).put("reason", 0)
                        .put("streamId", inputMetadata.streamId));
                sendJson(control);
                run.inputStopSent.incrementAndGet();
            } catch (JSONException impossible) { cancelInputStop(); throw new IOException("input_stop_schema");
            } catch (IOException error) {
                cancelInputStop();
                if (isOpen()) throw error;
            }
        }
        /** Numeric delivery timings, measured after socket flush, never actual speaker playback. */
        public JSONObject timingSummary() {
            JSONObject result = new JSONObject();
            try {
                result.put("captureMs", captureMillis).put("endCaptureToFirstAudioMs", firstAudioMillis)
                        .put("endCaptureToOutputEndMs", outputEndMillis);
            } catch (JSONException ignored) { }
            return result;
        }
        private void audioPacketSent() {
            if (firstAudioMillis < 0 && commandCompletedNanos != 0) {
                firstAudioMillis = Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - commandCompletedNanos));
                run.lastFirstAudioMs.set(firstAudioMillis);
            }
        }
        private void outputEnded() {
            if (commandCompletedNanos != 0) {
                outputEndMillis = Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - commandCompletedNanos));
                run.lastOutputEndMs.set(outputEndMillis);
            }
        }
        private void armExpiry() {
            if (!commandToken || expiry != null || !isOpen()) return;
            expiry = run.timer.schedule(this::expire, Math.max(1, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        }
        /** A real command deadline is exceptional; stale/cancelled deadlines never abort a newer turn. */
        private void expire() {
            // Activation holds the same lock: a newer generation cannot become current
            // between this check and physical close. No protocol writes occur on the timer.
            synchronized (run.lock) {
                if (valid.getAndSet(false)) {
                    run.turnExpirations.incrementAndGet();
                    if (generation == run.generation.get()) connection.close();
                }
            }
            cancelProgressive(CancelReason.CAPTURE_TIMEOUT);
        }
        public boolean isOpen() {
            return valid.get() && connection.open.get() && run.active && !connection.socket.isClosed()
                    && System.nanoTime() < deadlineNanos
                    && (!commandToken || generation == run.generation.get());
        }
        private void invalidate() {
            valid.set(false);
            cancelInputStop();
            if (expiry != null) expiry.cancel(false);
            cancelProgressive(CancelReason.WRITER_CLOSED);
        }
        /** Cancels stale output. A blocked physical write remains protected by its own deadline timer. */
        public void cancelReply() {
            cancelReply(CancelReason.SUPERSEDED);
        }
        private void cancelReply(CancelReason reason) {
            if (valid.getAndSet(false)) run.cancelledReplies.incrementAndGet();
            cancelInputStop();
            if (expiry != null) expiry.cancel(false);
            cancelProgressive(reason);
            if (!run.runtimeMode || !commandToken) connection.close();
        }
        private void cancelProgressive(CancelReason reason) {
            clearAsrProgress();
            cancelServerEndpoint();
            ProgressiveTurn observing = progressive;
            if (observing != null) observing.cancel(reason);
        }
        /** Runtime child tokens are isolated: an old worker's finally cannot close a newer command. */
        public void close() {
            LegacyVoiceReply.Streaming activeStream = streaming;
            if (activeStream != null && !activeStream.isFinished()) { abortStreaming(); return; }
            invalidate();
            if (!run.runtimeMode || !commandToken) connection.close();
        }
        private void send(int opcode, byte[] bytes) throws IOException {
            synchronized (connection.writeLock) {
                if (!isOpen()) throw new IOException("reply_closed");
                ScheduledFuture<?> writeDeadline = null;
                if (run.runtimeMode) {
                    long remaining = commandToken ? deadlineNanos - System.nanoTime()
                            : TimeUnit.MILLISECONDS.toNanos(MAX_LEASE_MILLIS);
                    writeDeadline = run.timer.schedule(() -> {
                        run.writeTimeouts.incrementAndGet(); connection.close();
                    }, Math.max(1, remaining), TimeUnit.NANOSECONDS);
                }
                try {
                    sendFrame(connection.output, opcode, bytes);
                    connection.lastActivityNanos = System.nanoTime();
                } finally { if (writeDeadline != null) writeDeadline.cancel(false); }
            }
        }
        private void sendJson(JSONObject object) throws IOException {
            send(1, object.toString().getBytes(StandardCharsets.UTF_8));
        }
        /**
         * Worker barrier after complete input validation and final local ASR. Emits only
         * real final ASR and input close, once; Vins/TTS/actions belong to the caller.
         */
        public synchronized void finishInput(LegacyVoiceSession.Metadata metadata, String finalizedTranscript)
                throws IOException {
            if (!commandToken || !run.runtimeMode || !isOpen()) throw new IOException("reply_closed");
            if (!inputEnded || metadata == null || completedMetadata != metadata)
                throw new IOException("input_not_completed");
            if (inputFinished) return;
            if (inputFinishStarted || replied) throw new IOException("input_already_finalizing");
            LegacyVoiceReply.validateInputFinal(metadata, finalizedTranscript);
            inputFinishStarted = true;
            try {
                LegacyVoiceReply.sendInputFinal(metadata, finalizedTranscript, new LegacyVoiceReply.Sink() {
                    @Override public void json(JSONObject object) throws IOException { sendJson(object); }
                    @Override public void binary(byte[] bytes) throws IOException { throw new IOException("unexpected_audio"); }
                });
                inputFinished = true;
                run.inputFinalizations.incrementAndGet();
            } catch (IOException failure) {
                replied = true;
                run.inputFinalizationFailures.incrementAndGet();
                synchronized (run.lock) {
                    valid.set(false);
                    if (generation == run.generation.get()) connection.close();
                }
                cancelProgressive(CancelReason.WRITER_CLOSED);
                throw failure;
            }
        }
        private boolean inputFinishedFor(LegacyVoiceSession.Metadata metadata) throws IOException {
            if (inputFinished && completedMetadata != metadata) throw new IOException("invalid_reply_metadata");
            if (!inputFinished && metadata != null && metadata.endReason == LegacyVoiceSession.SERVER_ENDPOINT_REASON)
                throw new IOException("input_not_finalized");
            return inputFinished;
        }
        /** Failed server endpoint only; no ASR placeholder, native ACK assumption or stale connection close. */
        public synchronized void abortInputFinalization(LegacyVoiceSession.Metadata metadata) {
            if (!commandToken || !run.runtimeMode || metadata == null || completedMetadata != metadata
                    || metadata.endReason != LegacyVoiceSession.SERVER_ENDPOINT_REASON || inputFinished) return;
            synchronized (run.lock) {
                valid.set(false);
                if (generation == run.generation.get() && connection.open.get()) {
                    run.inputFinalizationAborts.incrementAndGet(); connection.close();
                }
            }
            cancelProgressive(CancelReason.WRITER_CLOSED);
        }
        /** Bounded serialized frames, exactly one reply for this token. EOS leaves runtime TCP alive. */
        public synchronized void reply(LegacyVoiceSession.Metadata metadata, String transcript,
                                       String answer, byte[] oggOpus) throws IOException {
            if (!isOpen()) throw new IOException("reply_closed");
            if (replied) throw new IOException("reply_already_sent");
            LegacyVoiceReply.validate(metadata, transcript, answer, oggOpus);
            boolean skipInput = inputFinishedFor(metadata);
            replied = true;
            LegacyVoiceReply.send(metadata, transcript, answer, oggOpus, new LegacyVoiceReply.Sink() {
                @Override public void json(JSONObject object) throws IOException { sendJson(object); }
                @Override public void binary(byte[] bytes) throws IOException { send(2, bytes); }
                @Override public void audioPacketSent() { ReplyWriter.this.audioPacketSent(); }
            }, skipInput);
            run.replies.incrementAndGet();
            run.replyBytes.addAndGet(oggOpus.length);
            outputEnded();
        }
        /** Reserves one response. Wire metadata starts only with a validated audio packet page. */
        public synchronized void beginStreaming(LegacyVoiceSession.Metadata metadata, String transcript, String answer)
                throws IOException {
            if (!isOpen()) throw new IOException("reply_closed");
            if (replied) throw new IOException("reply_already_sent");
            LegacyVoiceReply.validateText(metadata, transcript, answer);
            boolean skipInput = inputFinishedFor(metadata);
            replied = true;
            streaming = new LegacyVoiceReply.Streaming(metadata, transcript, answer, new LegacyVoiceReply.Sink() {
                @Override public void json(JSONObject object) throws IOException { sendJson(object); }
                @Override public void binary(byte[] bytes) throws IOException {
                    send(2, bytes); run.replyBytes.addAndGet(bytes.length - 4);
                }
                @Override public void audioPacketSent() { ReplyWriter.this.audioPacketSent(); }
            }, skipInput);
        }
        /** Exactly one complete Ogg page. The caller's buffer is never retained without copying. */
        public synchronized void writeOggPage(byte[] page, int offset, int count) throws IOException {
            if (!isOpen()) throw new IOException("reply_closed");
            if (streaming == null) throw new IOException("stream_not_started");
            try { streaming.page(page, offset, count); }
            catch (IOException error) { abortStreaming(); throw error; }
        }
        /** Valid EOF commits the held EOS page and output streamcontrol; TCP stays alive. */
        public synchronized void finishStreaming() throws IOException {
            if (!isOpen()) throw new IOException("reply_closed");
            if (streaming == null) throw new IOException("stream_not_started");
            try { streaming.finish(); }
            catch (IOException error) { abortStreaming(); throw error; }
            run.replies.incrementAndGet(); run.streamingCompletions.incrementAndGet(); outputEnded();
        }
        /** No protocol writes. A stale token cannot close a newer turn's physical connection. */
        public void abortStreaming() {
            LegacyVoiceReply.Streaming activeStream = streaming;
            if (activeStream == null || activeStream.isFinished()) return;
            synchronized (run.lock) {
                if (valid.getAndSet(false)) {
                    if (expiry != null) expiry.cancel(false);
                    run.streamingAborts.incrementAndGet();
                    if (activeStream.hasOutput()) run.partialStreamingAborts.incrementAndGet();
                    if (!run.runtimeMode || !commandToken || generation == run.generation.get()) connection.close();
                }
            }
            cancelProgressive(CancelReason.WRITER_CLOSED);
        }
        /** Local error completion. No cloud, speech, arbitrary text or TCP close. */
        public synchronized void completeWithoutSpeech(LegacyVoiceSession.Metadata metadata) throws IOException {
            completeWithoutSpeech(metadata, "");
        }
        /** Final recognition of a validated local command, without a TTS response. */
        public synchronized void completeWithoutSpeech(LegacyVoiceSession.Metadata metadata, String finalizedTranscript)
                throws IOException {
            if (!commandToken || !run.runtimeMode) throw new IOException("completion_runtime_only");
            if (!isOpen()) throw new IOException("reply_closed");
            if (replied) throw new IOException("reply_already_sent");
            if (metadata == null) throw new IOException("invalid_reply_metadata");
            LegacyVoiceReply.validateQuietText(metadata, finalizedTranscript);
            boolean skipInput = inputFinishedFor(metadata);
            replied = true;
            LegacyVoiceReply.sendWithoutSpeech(metadata, finalizedTranscript, new LegacyVoiceReply.Sink() {
                @Override public void json(JSONObject object) throws IOException { sendJson(object); }
                @Override public void binary(byte[] bytes) throws IOException { throw new IOException("unexpected_audio"); }
            }, skipInput);
            run.silentCompletions.incrementAndGet();
        }
    }

    /** Serial reader-owned activation state. References are bounded identifiers and never leave memory. */
    private static final class RuntimeTurns {
        final ReplyWriter protocol;
        ReplyWriter current;
        String spotterReference, commandReference;
        long spotterStream = -1, commandStream = -1;
        RuntimeTurns(ReplyWriter protocol) { this.protocol = protocol; }
        void flushInputStop(LegacyVoiceSession session) throws IOException {
            if (current != null) {
                current.flushServerEndpoint(session);
                current.flushAsrProgress(session); current.flushInputStop(session);
            }
        }
        void activate(LegacyVoiceSession session, Run run, String reference, long stream, boolean spotter) {
            if (spotter) {
                if (stream == spotterStream && reference.equals(spotterReference)) return;
                spotterReference = reference; spotterStream = stream;
                // Native Spotter is also a validation side channel, including on a
                // sibling WS. Only a new VoiceInput supersedes a pending reply.
                // In-flight command capture follows the original session policy:
                // ignore the late Spotter without replacing its selected stream.
                if (session.state() != LegacyVoiceSession.State.COMMAND) session.rearmRuntime();
            } else {
                if (stream == commandStream && reference.equals(commandReference)) return;
                commandReference = reference; commandStream = stream;
                if (current != null) current.cancelReply();
                LegacyVoiceSession.State state = session.state();
                if (state != LegacyVoiceSession.State.WAKEWORD && state != LegacyVoiceSession.State.WAIT_COMMAND)
                    session.rearmRuntime();
                current = new ReplyWriter(protocol.connection, true);
                run.activate(current);
            }
        }
    }

    /** Only numeric stream selectors are retained per connection; never payload or arbitrary metadata. */
    private static final class NativeStreams {
        long spotter = -1, command = -1;
        boolean spotterPayloadSeen, commandPayloadSeen;
        void binary(Run run, long stream, byte[] frame) {
            if (stream == command) {
                run.nativeCommand.binary(frame, !commandPayloadSeen);
                if (frame.length > 4) commandPayloadSeen = true;
            } else if (stream == spotter) {
                run.nativeSpotter.binary(frame, !spotterPayloadSeen);
                if (frame.length > 4) spotterPayloadSeen = true;
            }
        }
    }

    /** Closed enum instrumentation. All keys below are fixed and all input strings are allowlisted. */
    private static final class NativeAudioStats {
        private final JSONObject formats = new JSONObject();
        private long frames, bytes;
        private String firstPayloadMagic = "unseen";
        synchronized void metadata(JSONObject source) {
            if (source == null) return;
            for (String key : new String[]{"codec", "encoding", "content_type", "audio_format", "format"}) {
                Object raw = source.opt(key);
                if (!(raw instanceof String) || ((String) raw).length() > 64) continue;
                String value = (String) raw;
                switch (value) {
                    case "pcm_s16le": case "pcm": case "audio/pcm": case "opus": case "ogg":
                    case "audio/opus": case "audio/ogg": case "audio/x-ogg": case "s16le":
                    case "audio/x-pcm;bit=16;rate=16000;channels=1":
                        try { formats.put(key, value); } catch (JSONException ignored) { }
                        break;
                    default: break;
                }
            }
            Object rate = source.opt("sample_rate");
            if (rate instanceof Integer || rate instanceof Long) {
                long value = ((Number) rate).longValue();
                if (value == 8000 || value == 12000 || value == 16000 || value == 22050 || value == 24000
                        || value == 32000 || value == 44100 || value == 48000 || value == 96000)
                    try { formats.put("sample_rate", value); } catch (JSONException ignored) { }
            }
            Object channels = source.opt("channels");
            if (channels instanceof Integer || channels instanceof Long) {
                long value = ((Number) channels).longValue();
                if (value == 1 || value == 2)
                    try { formats.put("channels", value); } catch (JSONException ignored) { }
            }
        }
        synchronized void binary(byte[] frame, boolean first) {
            frames++;
            bytes += frame.length - 4;
            if (first && frame.length > 4) {
                firstPayloadMagic = frame.length >= 8 && frame[4] == 'O' && frame[5] == 'g'
                        && frame[6] == 'g' && frame[7] == 'S' ? "ogg"
                        : frame.length >= 8 && frame[4] == 'R' && frame[5] == 'I'
                        && frame[6] == 'F' && frame[7] == 'F' ? "riff" : "other";
            }
        }
        synchronized JSONObject snapshot() {
            JSONObject result = new JSONObject();
            try {
                for (String key : new String[]{"codec", "encoding", "content_type", "audio_format", "format", "sample_rate", "channels"})
                    if (formats.has(key)) result.put(key, formats.get(key));
                result.put("firstPayloadMagic", firstPayloadMagic).put("frames", frames).put("bytes", bytes);
            } catch (JSONException ignored) { }
            return result;
        }
    }

    private static final class Run {
        final boolean runtimeMode;
        final Object lock = new Object();
        final Set<Socket> sockets = new HashSet<>();
        final AtomicLong connections = new AtomicLong(), rejectedConnections = new AtomicLong();
        final AtomicLong handshakes = new AtomicLong(), synchronizations = new AtomicLong();
        final AtomicLong textFrames = new AtomicLong(), binaryFrames = new AtomicLong();
        final AtomicLong audioBytes = new AtomicLong(), pingFrames = new AtomicLong(), errors = new AtomicLong();
        final AtomicLong lastStreamId = new AtomicLong(-1);
        final AtomicLong spotterEvents = new AtomicLong(), voiceInputEvents = new AtomicLong(), streamControls = new AtomicLong();
        final AtomicLong commands = new AtomicLong(), commandBytes = new AtomicLong(), replies = new AtomicLong(), replyBytes = new AtomicLong();
        final AtomicLong staleCommandCallbacks = new AtomicLong();
        final AtomicLong maxBufferedCommandBytes = new AtomicLong();
        final AtomicLong cancelledReplies = new AtomicLong(), generation = new AtomicLong();
        final AtomicLong idleClosures = new AtomicLong(), writeTimeouts = new AtomicLong(), turnExpirations = new AtomicLong();
        final AtomicLong silentCompletions = new AtomicLong();
        final AtomicLong streamingCompletions = new AtomicLong(), streamingAborts = new AtomicLong(), partialStreamingAborts = new AtomicLong();
        final AtomicLong lastCaptureMs = new AtomicLong(), lastFirstAudioMs = new AtomicLong(-1), lastOutputEndMs = new AtomicLong(-1);
        final AtomicLong lastCommandEndReason = new AtomicLong(-1);
        final AtomicLong inputStopRequests = new AtomicLong(), inputStopSent = new AtomicLong();
        final AtomicLong inputStopAcknowledgements = new AtomicLong(), inputStopCancellations = new AtomicLong();
        final AtomicLong asrProgressRequests = new AtomicLong(), asrProgressSent = new AtomicLong();
        final AtomicLong asrProgressCoalesced = new AtomicLong(), asrProgressDiscarded = new AtomicLong();
        final AtomicLong serverEndpointRequests = new AtomicLong(), serverEndpointCompletions = new AtomicLong();
        final AtomicLong serverEndpointCancellations = new AtomicLong(), inputFinalizations = new AtomicLong();
        final AtomicLong inputFinalizationFailures = new AtomicLong(), inputFinalizationAborts = new AtomicLong();
        final AtomicLong progressiveStarts = new AtomicLong(), progressiveChunks = new AtomicLong(), progressiveBytes = new AtomicLong();
        final AtomicLong progressiveEnds = new AtomicLong(), progressiveCancellations = new AtomicLong();
        final AtomicLong progressiveFallbacks = new AtomicLong(), progressiveObserverErrors = new AtomicLong();
        private ReplyWriter activeReply;
        final NativeAudioStats nativeSpotter = new NativeAudioStats(), nativeCommand = new NativeAudioStats();
        volatile boolean active;
        volatile long startedAtMillis, deadlineNanos;
        volatile String stopReason = "not_started", lastError = "", lastNamespace = "", lastEvent = "";
        volatile String lastLegacyState = "";
        ServerSocket listener;
        ScheduledExecutorService timer;

        Run(boolean runtimeMode) { this.runtimeMode = runtimeMode; }
        void activate(ReplyWriter writer) {
            ReplyWriter previous;
            synchronized (lock) {
                if (activeReply == writer) return;
                previous = activeReply;
                activeReply = writer;
                writer.generation = generation.incrementAndGet();
            }
            if (previous != null) previous.cancelReply();
        }
        void finishWriter(ReplyWriter writer) {
            ReplyWriter abandoned = null;
            synchronized (lock) {
                if (writer != null && activeReply != null && activeReply.connection == writer.connection) {
                    abandoned = activeReply; activeReply = null;
                }
            }
            if (abandoned != null) abandoned.cancelReply(CancelReason.CONNECTION_CLOSED);
        }

        boolean admit(Socket socket) {
            synchronized (lock) {
                if (!active || sockets.size() >= MAX_CONNECTIONS) return false;
                sockets.add(socket);
                return true;
            }
        }
        int activeCount() { synchronized (lock) { return sockets.size(); } }
        void release(Socket socket) { synchronized (lock) { sockets.remove(socket); } }
        void recordError(String reason) { errors.incrementAndGet(); lastError = reason; }
        void shutdown(String reason) {
            final ArrayList<Socket> closing;
            synchronized (lock) {
                if (!active && !"not_started".equals(stopReason)) return;
                active = false;
                stopReason = reason;
                closing = new ArrayList<>(sockets);
            }
            closeQuietly(listener);
            for (Socket socket : closing) closeQuietly(socket);
            if (timer != null) timer.shutdownNow();
        }
    }
}
