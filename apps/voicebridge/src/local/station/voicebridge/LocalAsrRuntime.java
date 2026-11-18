package local.station.voicebridge;

import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;
import java.io.File;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** One warm on-device model. Reader callbacks only offer to bounded queues; all decoding is local. */
public final class LocalAsrRuntime implements LoopbackVoiceServer.ProgressiveInput,
        LoopbackVoiceServer.CommandHandler, AutoCloseable {
    private static final byte[] END = new byte[0];
    private final File modelDirectory;
    private final String engine;
    private final int threads;
    private final boolean observeEndpoint;
    private final boolean serverEndpoint;
    private final RuntimeVoiceHandler answers;
    private final LocalVoiceCommands.HomeAction home;
    private final Runnable changed;
    private final ArrayBlockingQueue<Turn> pending = new ArrayBlockingQueue<>(1);
    private final AtomicReference<Turn> current = new AtomicReference<>();
    private final java.util.IdentityHashMap<LoopbackVoiceServer.ReplyWriter, Turn> ownership = new java.util.IdentityHashMap<>();
    // At most the two transport readers can still deliver their primary callback after
    // a progressive worker has finished. Never retain completed Turn/audio ownership.
    private final java.util.ArrayDeque<LoopbackVoiceServer.ReplyWriter> completedPrimary = new java.util.ArrayDeque<>(2);
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean ready, released;
    private volatile String state = "loading", lastCode = "";
    private volatile long loadMs = -1, accepted, completed, failed, cancelled, localCommands;
    private volatile long lastDecodeMs = -1, lastTailMs = -1, lastPcmBytes, lastTextChars;
    private volatile long lastCaptureMs = -1, lastEndpointMs = -1, lastEndpointToInputEndMs = -1;
    private volatile long partialUpdates, lastFirstPartialMs = -1;
    private volatile long lastLocalCommandMs = -1, lastInputEndToLocalActionMs = -1;
    private volatile String lastLocalAction = "", lastLocalResultCode = "", lastLocalDecision = "";
    private volatile boolean lastLocalAccepted;
    private volatile long lastFirstPcmMs = -1, lastObservedPcmBytes, lastObservedDecodeMs = -1;
    private volatile int lastPcmPeak;
    private volatile long lastInputBytes, lastParsedBytes, lastInputPages, lastInputPackets;
    private volatile long lastPendingPageBytes, lastExpectedPageBytes = -1;

    private static final class Turn {
        final LoopbackVoiceServer.ReplyWriter writer;
        final LegacyVoiceSession.Metadata startMetadata;
        final ArrayBlockingQueue<byte[]> chunks = new ArrayBlockingQueue<>(128);
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile LegacyVoiceSession.Metadata metadata;
        volatile OnDeviceRecognizer.Session session;
        volatile long endedAt;
        final long startedAt = millis();
        volatile long endpointAt;
        long firstPartialAt, partialAt;
        int partialCount;
        String partialText = "";
        volatile boolean primarySeen, finished;
        private int inputBytes;
        Turn(LegacyVoiceSession.Metadata metadata, LoopbackVoiceServer.ReplyWriter writer) {
            this.metadata = metadata; this.startMetadata = metadata; this.writer = writer;
        }
        synchronized boolean offer(byte[] audio) {
            if (cancelled.get() || inputBytes > NativeOggInput.MAX_BYTES - audio.length) return false;
            if (!chunks.offer(audio)) return false;
            inputBytes += audio.length; return true;
        }
        void cancel() {
            cancelled.set(true);
            OnDeviceRecognizer.Session stream = session;
            if (stream != null) stream.cancel();
            chunks.clear(); chunks.offer(END);
        }
        boolean stale() { return cancelled.get() || !writer.isOpen(); }
    }

    public LocalAsrRuntime(Context context, RuntimeVoiceHandler answers, Runnable changed) {
        this(context, answers, changed, "vosk", 1);
    }
    public LocalAsrRuntime(Context context, RuntimeVoiceHandler answers, Runnable changed, String engine, int threads) {
        this(context, answers, changed, engine, threads, false);
    }
    public LocalAsrRuntime(Context context, RuntimeVoiceHandler answers, Runnable changed, String engine,
                           int threads, boolean observeEndpoint) {
        this(context, answers, changed, engine, threads, observeEndpoint, false);
    }
    public LocalAsrRuntime(Context context, RuntimeVoiceHandler answers, Runnable changed, String engine,
                           int threads, boolean observeEndpoint, boolean serverEndpoint) {
        Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        if (!("vosk".equals(engine) || "sherpa".equals(engine)) || !(threads == 1 || threads == 2 || threads == 4))
            throw new IllegalArgumentException("LOCAL_ASR_CONFIG");
        this.engine = engine; this.threads = threads; this.observeEndpoint = observeEndpoint;
        if (serverEndpoint && (!"sherpa".equals(engine) || !observeEndpoint))
            throw new IllegalArgumentException("LOCAL_ASR_CONFIG");
        this.serverEndpoint = serverEndpoint;
        this.modelDirectory = new File(app.getFilesDir(), "sherpa".equals(engine) ? "local-sherpa-model" : "local-asr-model");
        this.answers = answers; this.changed = changed;
        home = () -> {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(home); return true;
        };
        Thread worker = new Thread(this::work, "StationLocalASR");
        worker.setDaemon(true); worker.start();
    }

    @Override public boolean offerStart(LegacyVoiceSession.Metadata metadata, LoopbackVoiceServer.ReplyWriter writer) {
        if (closed.get() || !ready || metadata == null || writer == null || !writer.isOpen()) return false;
        synchronized (ownership) {
            // close() shares this short admission lock: no turn can be published after
            // its queues/ownership were cleared. Callbacks only enqueue/cancel here.
            if (closed.get() || !ready || !writer.isOpen()) return false;
            Turn turn = new Turn(metadata, writer), previous = current.getAndSet(turn);
            lastFirstPartialMs = lastLocalCommandMs = lastInputEndToLocalActionMs = -1;
            lastLocalAction = lastLocalResultCode = lastLocalDecision = ""; lastLocalAccepted = false;
            lastEndpointMs = lastEndpointToInputEndMs = lastCaptureMs = -1;
            lastFirstPcmMs = lastObservedDecodeMs = -1;
            lastObservedPcmBytes = 0; lastPcmPeak = 0;
            lastInputBytes = lastParsedBytes = lastInputPages = lastInputPackets = lastPendingPageBytes = 0;
            lastExpectedPageBytes = -1;
            if (previous != null) previous.cancel();
            if (previous != null) ownership.remove(previous.writer);
            ownership.put(writer, turn);
            pending.clear();
            if (!pending.offer(turn)) {
                current.compareAndSet(turn, null); ownership.remove(writer); turn.cancel(); return false;
            }
            accepted++; return true;
        }
    }
    @Override public boolean offerChunk(LoopbackVoiceServer.ReplyWriter writer, byte[] selectedAudio) {
        Turn turn = current.get();
        if (turn == null || turn.writer != writer || selectedAudio == null || selectedAudio.length == 0 || turn.stale()) return false;
        return turn.offer(selectedAudio);
    }
    @Override public boolean offerEnd(LegacyVoiceSession.Metadata metadata, LoopbackVoiceServer.ReplyWriter writer) {
        Turn turn = current.get();
        if (turn == null || turn.writer != writer || turn.stale() || metadata == null) return false;
        turn.metadata = metadata; turn.endedAt = millis();
        return turn.chunks.offer(END);
    }
    @Override public void cancel(LoopbackVoiceServer.ReplyWriter writer, LoopbackVoiceServer.CancelReason reason) {
        synchronized (ownership) {
            Turn turn = ownership.remove(writer);
            completedPrimary.remove(writer);
            if (turn == null) {
                Turn active = current.get();
                if (active != null && active.writer == writer) turn = active;
            }
            if (turn != null) turn.cancel();
        }
    }
    /** Progressive input owns local mode. A failed observer never silently uploads speech. */
    @Override public void commandReady(byte[] audio, LegacyVoiceSession.Metadata metadata, LoopbackVoiceServer.ReplyWriter writer) {
        Turn turn;
        synchronized (ownership) {
            turn = ownership.get(writer);
            if (turn == null && completedPrimary.remove(writer)) return;
            if (turn != null) {
                turn.primarySeen = true;
                if (turn.finished) ownership.remove(writer);
            }
        }
        if (turn == null || turn.cancelled.get()) quiet(metadata, writer);
    }

    private void work() {
        OnDeviceRecognizer model = null;
        try {
            model = "sherpa".equals(engine) ? new SherpaSpeechRecognizer(modelDirectory, threads, observeEndpoint)
                    : new LocalSpeechRecognizer(modelDirectory);
            loadMs = model.loadMillis();
            ready = !closed.get(); state = ready ? "ready" : "closed"; notifyChanged();
            while (!closed.get()) {
                Turn turn = pending.poll(500, TimeUnit.MILLISECONDS);
                if (turn != null) recognize(model, turn);
            }
        } catch (LocalSpeechRecognizer.RecognitionException error) {
            lastCode = error.code; state = "failed";
        } catch (Exception | LinkageError error) {
            lastCode = "LOCAL_ASR_SETUP"; state = "failed";
        } finally {
            ready = false;
            Turn turn = current.getAndSet(null);
            if (turn != null) { turn.cancel(); quiet(turn.metadata, turn.writer); }
            if (model != null) model.close();
            released = true; notifyChanged();
        }
    }

    private void recognize(OnDeviceRecognizer model, Turn turn) {
        boolean transferred = false;
        OnDeviceRecognizer.Session session = null;
        StreamingOpusInput input = null;
        try {
            if (turn.stale() || closed.get()) return;
            state = "recognizing"; notifyChanged();
            session = model.newSession(); turn.session = session;
            final OnDeviceRecognizer.Session sinkSession = session;
            input = new StreamingOpusInput(new AndroidOpusDecoder(new AndroidOpusDecoder.PcmSink() {
                @Override public void accept(byte[] pcm, int offset, int count) throws Exception {
                    if (lastFirstPcmMs < 0) lastFirstPcmMs = Math.max(0, millis() - turn.startedAt);
                    lastObservedPcmBytes += count;
                    int peak = lastPcmPeak;
                    for (int i = offset; i + 1 < offset + count; i += 2) {
                        int sample = (short) ((pcm[i] & 255) | ((pcm[i + 1] & 255) << 8));
                        peak = Math.max(peak, Math.abs(sample));
                    }
                    lastPcmPeak = peak;
                    sinkSession.acceptPcm16(pcm, offset, count);
                    lastObservedDecodeMs = sinkSession.decodeMillis();
                    // Native's initial-silence timer needs a real nonempty ASR
                    // hypothesis. This is transport progress only, never a command.
                    long now = millis();
                    if (turn.endedAt == 0 && !turn.stale() && !closed.get()
                            && turn.partialCount < 16 && (turn.partialAt == 0 || now - turn.partialAt >= 200)) {
                        String preview = sinkSession.previewText();
                        if (preview != null && !preview.isEmpty() && !preview.equals(turn.partialText)
                                && turn.writer.requestPartialAsr(turn.startMetadata, preview)) {
                            turn.partialText = preview; turn.partialAt = now; turn.partialCount++;
                            if (turn.firstPartialAt == 0) {
                                turn.firstPartialAt = now;
                                lastFirstPartialMs = Math.max(0, now - turn.startedAt);
                            }
                            partialUpdates++;
                        }
                    }
                    if (observeEndpoint && turn.endpointAt == 0 && sinkSession.endpointReady()) {
                        turn.endpointAt = millis();
                        if (current.get() == turn) lastEndpointMs = Math.max(0, turn.endpointAt - turn.startedAt);
                    }
                }
                @Override public boolean cancelled() { return closed.get() || turn.stale(); }
            }));
            long deadline = millis() + 20000;
            while (!closed.get() && !turn.stale()) {
                byte[] chunk = turn.chunks.poll(20, TimeUnit.MILLISECONDS);
                if (chunk == END) break;
                if (millis() > deadline) throw new java.io.IOException("CAPTURE_TIMEOUT");
                if (chunk != null) {
                    input.accept(chunk, 0, chunk.length);
                    lastInputBytes = input.inputBytes(); lastParsedBytes = input.parsedBytes();
                    lastInputPages = input.pages(); lastInputPackets = input.audioPackets();
                    lastPendingPageBytes = input.pendingPageBytes(); lastExpectedPageBytes = input.expectedPageBytes();
                } else input.poll();
                if (serverEndpoint && turn.endedAt == 0 && input.completeBoundary()
                        && session.endpointReady() && !turn.stale() && !closed.get())
                    turn.writer.requestServerEndpoint(turn.startMetadata);
            }
            if (closed.get() || turn.stale()) { cancelled++; return; }
            if (turn.endedAt == 0 || turn.metadata.audioBytes != input.inputBytes())
                throw new java.io.IOException("UNFINALIZED_INPUT");
            input.finish();
            String transcript = session.finish();
            lastDecodeMs = session.decodeMillis(); lastPcmBytes = session.pcmBytes(); lastTextChars = transcript.length();
            lastTailMs = Math.max(0, millis() - turn.endedAt);
            lastCaptureMs = turn.metadata.captureMillis;
            lastFirstPartialMs = turn.firstPartialAt == 0 ? -1 : turn.firstPartialAt - turn.startedAt;
            lastLocalCommandMs = lastInputEndToLocalActionMs = -1;
            lastEndpointMs = turn.endpointAt == 0 ? -1 : turn.endpointAt - turn.startedAt;
            lastEndpointToInputEndMs = turn.endpointAt == 0 ? -1 : turn.endedAt - turn.endpointAt;
            if (closed.get() || turn.stale() || current.get() != turn) { cancelled++; return; }
            if (transcript.trim().isEmpty()) { lastCode = "NO_SPEECH"; return; }
            // Server VAD completes a fully validated prefix, not a native ACK.
            // Publish the actual finalized ASR before any action or cloud request.
            if (turn.metadata.endReason == LegacyVoiceSession.SERVER_ENDPOINT_REASON) {
                turn.writer.finishInput(turn.metadata, transcript);
                if (closed.get() || turn.stale() || current.get() != turn) { cancelled++; return; }
            }
            if (TvChannelCommand.parse(transcript) != null || TvVoiceCommand.parse(transcript) != null
                    || TvVoiceCommand.playback(transcript) != null) {
                answers.transcriptReady(transcript, turn.metadata, turn.writer);
                transferred = true; completed++; lastCode = "";
                return;
            }
            LocalVoiceCommands.Decision decision = LocalVoiceCommands.parseFinal(transcript);
            if (decision.handled()) {
                if (turn.stale()) return;
                lastLocalDecision = decision.kind.name();
                long commandStarted = millis();
                LocalVoiceCommands.Executor commands = new LocalVoiceCommands.AgentExecutor(home,
                        () -> closed.get() || turn.stale() || current.get() != turn);
                LocalVoiceCommands.Result result = LocalVoiceCommands.execute(decision,
                        command -> turn.stale() || closed.get() ? LocalVoiceCommands.Result.unavailable(command) : commands.apply(command));
                if (current.get() == turn) {
                    lastLocalCommandMs = Math.max(0, millis() - commandStarted);
                    if (result.accepted) lastInputEndToLocalActionMs = Math.max(0, millis() - turn.endedAt);
                    lastLocalAction = result.action == null ? "" : result.action.name();
                    lastLocalResultCode = result.code; lastLocalAccepted = result.accepted;
                    localCommands++; lastCode = result.accepted ? "" : "LOCAL_COMMAND_UNAVAILABLE";
                }
                if (result.action == LocalVoiceCommands.Action.CREATE_TIMER
                        && !turn.stale() && !closed.get() && current.get() == turn) {
                    answers.commandFeedback(transcript, result.accepted ? VoicePipeline.LocalFeedback.TIMER_CREATED
                            : VoicePipeline.LocalFeedback.TIMER_UNAVAILABLE, turn.metadata, turn.writer);
                    transferred = true;
                } else if (!result.accepted && (result.action == LocalVoiceCommands.Action.STOP_TIMER
                        || result.action == LocalVoiceCommands.Action.STOP_ALL_TIMERS
                        || result.action == LocalVoiceCommands.Action.SMART_STOP)
                        && !turn.stale() && !closed.get() && current.get() == turn) {
                    VoicePipeline.LocalFeedback feedback = "timer_not_running".equals(result.code)
                            ? VoicePipeline.LocalFeedback.TIMER_NOT_RUNNING : "timer_ambiguous".equals(result.code)
                            ? VoicePipeline.LocalFeedback.TIMER_AMBIGUOUS : VoicePipeline.LocalFeedback.TIMER_STOP_UNAVAILABLE;
                    answers.commandFeedback(transcript, feedback, turn.metadata, turn.writer);
                    transferred = true;
                } else if (!result.accepted && result.action == LocalVoiceCommands.Action.PLAY_QUERY
                        && !turn.stale() && !closed.get() && current.get() == turn) {
                    VoicePipeline.LocalFeedback feedback = "music_not_found".equals(result.code)
                            ? VoicePipeline.LocalFeedback.MUSIC_NOT_FOUND : VoicePipeline.LocalFeedback.MUSIC_UNAVAILABLE;
                    answers.commandFailed(transcript, feedback, turn.metadata, turn.writer);
                    transferred = true;
                } else {
                    if (!turn.stale() && !closed.get() && current.get() == turn) {
                        answers.recordLocal(transcript, result.code, result.accepted, Math.max(0, millis() - turn.endedAt));
                    }
                    quiet(turn.metadata, turn.writer, transcript);
                }
            } else {
                answers.transcriptReady(transcript, turn.metadata, turn.writer); transferred = true; lastCode = "";
            }
            completed++;
        } catch (Exception | LinkageError error) {
            if (turn.stale() || closed.get()) cancelled++;
            else { failed++; lastCode = "LOCAL_ASR_INPUT"; }
        } finally {
            turn.partialText = "";
            turn.session = null;
            if (turn.metadata.endReason == LegacyVoiceSession.SERVER_ENDPOINT_REASON)
                turn.writer.abortInputFinalization(turn.metadata);
            if (input != null && current.get() == turn) {
                lastInputBytes = input.inputBytes(); lastParsedBytes = input.parsedBytes();
                lastInputPages = input.pages(); lastInputPackets = input.audioPackets();
                lastPendingPageBytes = input.pendingPageBytes(); lastExpectedPageBytes = input.expectedPageBytes();
            }
            if (input != null) input.close();
            if (session != null) session.close();
            if (!transferred) quiet(turn.metadata, turn.writer);
            current.compareAndSet(turn, null);
            synchronized (ownership) {
                turn.finished = true;
                ownership.remove(turn.writer);
                if (!turn.primarySeen && !turn.cancelled.get() && !closed.get()) {
                    completedPrimary.addLast(turn.writer);
                    while (completedPrimary.size() > 2) completedPrimary.removeFirst();
                }
            }
            state = closed.get() ? "closed" : "ready"; notifyChanged();
        }
    }
    private static void quiet(LegacyVoiceSession.Metadata metadata, LoopbackVoiceServer.ReplyWriter writer) {
        quiet(metadata, writer, "");
    }
    private static void quiet(LegacyVoiceSession.Metadata metadata, LoopbackVoiceServer.ReplyWriter writer, String finalText) {
        if (writer == null) return;
        try { if (writer.isOpen()) writer.completeWithoutSpeech(metadata, finalText); } catch (Exception ignored) { }
        writer.close();
    }
    private static long millis() { return System.nanoTime() / 1000000L; }
    private void notifyChanged() { if (changed != null) try { changed.run(); } catch (Exception ignored) { } }
    public boolean canRelease() { return released; }
    public boolean isReady() { return ready && !closed.get(); }
    public JSONObject snapshot() {
        JSONObject result = new JSONObject();
        try {
            result.put("engine", "sherpa".equals(engine) ? "sherpa_zipformer_small_ru_int8" : "vosk_small_ru_0.22")
                    .put("threads", threads).put("execution", "on_station")
                    .put("ready", isReady()).put("state", state).put("lastCode", lastCode)
                    .put("loadMs", loadMs).put("accepted", accepted).put("completed", completed)
                    .put("failed", failed).put("cancelled", cancelled).put("localCommands", localCommands)
                    .put("lastDecodeMs", lastDecodeMs).put("lastTailMs", lastTailMs)
                    .put("lastPcmBytes", lastPcmBytes).put("lastTextChars", lastTextChars)
                    .put("endpointObservation", observeEndpoint).put("earlyInputStopEnabled", false)
                    .put("serverEndpointEnabled", serverEndpoint)
                    .put("lastCaptureMs", lastCaptureMs).put("lastEndpointMs", lastEndpointMs)
                    .put("lastEndpointToInputEndMs", lastEndpointToInputEndMs)
                    .put("partialUpdates", partialUpdates).put("lastFirstPartialMs", lastFirstPartialMs)
                    .put("lastLocalCommandMs", lastLocalCommandMs)
                    .put("lastInputEndToLocalActionMs", lastInputEndToLocalActionMs)
                    .put("lastLocalDecision", lastLocalDecision).put("lastLocalAction", lastLocalAction)
                    .put("lastLocalResultCode", lastLocalResultCode).put("lastLocalAccepted", lastLocalAccepted)
                    .put("lastFirstPcmMs", lastFirstPcmMs).put("lastObservedPcmBytes", lastObservedPcmBytes)
                    .put("lastObservedDecodeMs", lastObservedDecodeMs).put("lastPcmPeak", lastPcmPeak)
                    .put("lastInputBytes", lastInputBytes).put("lastParsedBytes", lastParsedBytes)
                    .put("lastInputPages", lastInputPages).put("lastInputPackets", lastInputPackets)
                    .put("lastPendingPageBytes", lastPendingPageBytes).put("lastExpectedPageBytes", lastExpectedPageBytes)
                    .put("cloudAsrFallback", false);
        } catch (Exception ignored) { }
        return result;
    }
    @Override public void close() {
        synchronized (ownership) {
            closed.set(true); ready = false;
            Turn turn = current.get(); if (turn != null) turn.cancel();
            pending.clear(); ownership.clear(); completedPrimary.clear();
        }
    }
}
