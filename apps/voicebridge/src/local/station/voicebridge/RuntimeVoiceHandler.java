package local.station.voicebridge;

import android.content.Context;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;

/** Repeated explicit utterances, one async transaction and no backlog or automatic API retry. */
public final class RuntimeVoiceHandler implements LoopbackVoiceServer.CommandHandler {
    public interface ConfigSource { JSONObject snapshot() throws Exception; }
    interface Validator { void validate(JSONObject config) throws Exception; }
    interface Clock { long millis(); }
    private final VoicePipeline pipeline;
    private final ConfigSource source;
    private final Validator validator;
    private final Clock clock;
    private final Runnable observer;
    private final VoiceHistory history;
    private boolean closed, credentialsReady, lastFileDeleted;
    private int workers, failures, status, retryAfter = -1;
    private long generation, backoffUntil, accepted, rejected, completed, discarded, failed, transactions;
    private long lastElapsedMs = -1, lastAsrMs = -1, lastLlmMs = -1, lastTtsMs = -1;
    private long lastTvMs = -1;
    private String lastTvCode = "";
    private long lastTtsFirstByteMs = -1, lastTtsFirstAudioMs = -1, lastFirstAudioReadyMs = -1;
    private long lastAsrOauthMs = -1, lastAsrUploadMs = -1, lastAsrAudioChatMs = -1, lastAsrDeleteMs = -1;
    private boolean lastCombinedAudioAnswer, lastStreamingAudio;
    private String configIdentity, state = "idle", stage = "", code = "", provider = "";
    private LoopbackVoiceServer.ReplyWriter currentWriter;
    private final LinkedHashSet<String> recentCommands = new LinkedHashSet<>();
    private boolean warmupStarted, warming, warmupOauthReady, warmupGigaReady, warmupSpeechifyReady;
    private long warmedMs = -1;
    private String warmupCode = "";
    private CloudClient.Warmup warmup;

    /** Validates local settings, then optionally warms catalogs while the local ASR model loads. */
    public RuntimeVoiceHandler(Context context, ConfigSource source, Runnable observer) {
        this(context, source, observer, null);
    }

    RuntimeVoiceHandler(Context context, ConfigSource source, Runnable observer, VoicePipeline.TvHandler tv) {
        this(context, source, observer, tv, null);
    }

    RuntimeVoiceHandler(Context context, ConfigSource source, Runnable observer, VoicePipeline.TvHandler tv,
                        VoiceHistory history) {
        this(new VoicePipeline(application(context), tv), source, realValidator(application(context)),
                () -> System.nanoTime() / 1000000L, observer, history);
        startWarmup();
    }

    RuntimeVoiceHandler(VoicePipeline pipeline, ConfigSource source, Validator validator, Clock clock, Runnable observer) {
        this(pipeline, source, validator, clock, observer, null);
    }

    RuntimeVoiceHandler(VoicePipeline pipeline, ConfigSource source, Validator validator, Clock clock, Runnable observer,
                        VoiceHistory history) {
        if (pipeline == null || source == null || validator == null || clock == null)
            throw new IllegalArgumentException("runtime_dependencies_required");
        this.pipeline = pipeline; this.source = source; this.validator = validator;
        this.clock = clock; this.observer = observer; this.history = history;
        refreshConfiguration();
    }

    /** Once per handler, only for local ASR startup. Refresh never restarts this attempt. */
    void startWarmup() {
        final Settings selected;
        try { selected = settings(); }
        catch (Exception ignored) { return; }
        if (!Boolean.TRUE.equals(selected.config.opt("voice_local_asr"))) return;
        final CloudClient.Warmup budget;
        synchronized (this) {
            if (closed || workers != 0 || warmupStarted) return;
            warmupStarted = true; warming = true;
            budget = new CloudClient.Warmup(); warmup = budget;
        }
        try {
            Thread worker = new Thread(() -> {
                long started = System.nanoTime();
                JSONObject result = null;
                try { result = pipeline.prewarm(selected.config, budget); }
                finally {
                    budget.close();
                    synchronized (RuntimeVoiceHandler.this) {
                        warming = false; warmup = null;
                        warmedMs = Math.max(0, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                        warmupOauthReady = result != null && result.optBoolean("oauthReady");
                        warmupGigaReady = result != null && result.optBoolean("gigachatReady");
                        warmupSpeechifyReady = result != null && result.optBoolean("speechifyReady");
                        warmupCode = result != null && result.optBoolean("ok") ? "READY"
                                : budget.isCancelled() ? "CANCELLED" : "UNAVAILABLE";
                    }
                    changed();
                }
            }, "VoiceRuntimeWarmup");
            worker.setDaemon(true); worker.start();
        } catch (RuntimeException startFailure) {
            budget.close();
            synchronized (this) { warming = false; warmup = null; warmupCode = "UNAVAILABLE"; }
        }
    }

    private void cancelWarmup() {
        CloudClient.Warmup budget;
        synchronized (this) { budget = warmup; }
        if (budget != null) budget.cancel();
        pipeline.cancelWarmup();
    }

    private static Context application(Context context) {
        if (context == null) throw new IllegalArgumentException("context_required");
        Context app = context.getApplicationContext(); return app == null ? context : app;
    }
    private static Validator realValidator(Context context) {
        CloudClient cloud = new CloudClient(context);
        SpeechifyClient speech = new SpeechifyClient();
        return config -> {
            JSONObject giga = cloud.preflight(config).optJSONObject("gigachat");
            if (giga == null || !giga.optBoolean("ready")) throw new IOException("config_required");
            speech.requireSynthesisConfigured(config);
        };
    }

    /** Local key/proxy refresh can close a configuration circuit without making an API request. */
    public boolean refreshConfiguration() {
        try { settings(); changed(); return true; }
        catch (Exception ignored) {
            synchronized (this) { credentialsReady = false; pipeline.clearConversation(); }
            changed(); return false;
        }
    }

    @Override public void commandReady(byte[] audio, LegacyVoiceSession.Metadata metadata,
                                       LoopbackVoiceServer.ReplyWriter writer) {
        submit(audio, null, null, metadata, writer);
    }

    /** Receives only a finalized local recognizer result, never a partial hypothesis. */
    public void transcriptReady(String transcript, LegacyVoiceSession.Metadata metadata,
                                LoopbackVoiceServer.ReplyWriter writer) {
        if (writer == null) return;
        if (transcript == null || transcript.trim().isEmpty() || transcript.length() > VoicePipeline.MAX_TRANSCRIPT_CHARS) {
            completeWithoutSpeech(metadata, writer); writer.close(); return;
        }
        submit(null, transcript, null, metadata, writer);
    }

    /** Fixed feedback for an actually failed finalized local command, never a partial hypothesis. */
    public void commandFailed(String transcript, VoicePipeline.LocalFeedback feedback,
                              LegacyVoiceSession.Metadata metadata, LoopbackVoiceServer.ReplyWriter writer) {
        commandFeedback(transcript, feedback, metadata, writer);
    }

    /** Confirmation only after an actuator ACK, or fixed failure; no LLM/command replay. */
    public void commandFeedback(String transcript, VoicePipeline.LocalFeedback feedback,
                              LegacyVoiceSession.Metadata metadata, LoopbackVoiceServer.ReplyWriter writer) {
        if (writer == null) return;
        if (feedback == null || transcript == null || transcript.trim().isEmpty()
                || transcript.length() > VoicePipeline.MAX_TRANSCRIPT_CHARS) {
            completeWithoutSpeech(metadata, writer); writer.close(); return;
        }
        submit(null, transcript, feedback, metadata, writer);
    }

    private void submit(byte[] audio, String transcript, VoicePipeline.LocalFeedback feedback,
                        LegacyVoiceSession.Metadata metadata,
                        LoopbackVoiceServer.ReplyWriter writer) {
        if (writer == null) return;
        cancelWarmup();
        if (metadata == null || transcript == null && (audio == null || audio.length == 0
                || audio.length > NativeOggInput.MAX_BYTES || metadata.audioBytes != audio.length)
                || metadata.streamId <= 0 || metadata.streamId > 0xffffffffL
                || metadata.reference == null || metadata.reference.length() > 256
                || metadata.requestId == null || metadata.requestId.length() > 256) {
            synchronized (this) { rejected++; code = "INVALID_AUDIO"; }
            completeWithoutSpeech(metadata, writer); writer.close(); changed(); return;
        }
        final Object ticket = new Object();
        final long turn;
        LoopbackVoiceServer.ReplyWriter cancel = null;
        boolean admit = false;
        boolean duplicate;
        String command = metadata.reference + '\u0000' + metadata.requestId + '\u0000' + metadata.sequenceNumber;
        synchronized (this) {
            duplicate = recentCommands.contains(command);
            if (duplicate) {
                turn = 0; rejected++;
            } else if (!closed && workers == 0 && VoiceCloudGate.tryAcquire(ticket)) {
                recentCommands.add(command);
                if (recentCommands.size() > 64) recentCommands.remove(recentCommands.iterator().next());
                workers = 1; accepted++; turn = ++generation;
                currentWriter = writer; state = "validating"; admit = true;
            } else {
                turn = 0; rejected++;
                if (workers != 0) { generation++; cancel = currentWriter; }
            }
        }
        if (cancel != null) cancel.cancelReply();
        if (!admit) {
            completeWithoutSpeech(metadata, writer); writer.close(); changed();
            if (!duplicate) recordLocal(transcript, "BUSY", false, -1);
            return;
        }
        final byte[] selected = audio == null ? null : audio.clone();
        changed();
        try {
            Thread worker = new Thread(() -> run(selected, transcript, feedback, metadata, writer, ticket, turn), "VoiceRuntimeRequest");
            worker.setDaemon(true); worker.start();
        } catch (RuntimeException startFailure) {
            synchronized (this) {
                workers = 0; currentWriter = null; state = "idle"; failed++; code = "WORKER_START_FAILED";
                VoiceCloudGate.release(ticket);
            }
            completeWithoutSpeech(metadata, writer); writer.close(); changed();
            recordLocal(transcript, "WORKER_START_FAILED", false, -1);
        }
    }

    private void run(byte[] audio, String localText, VoicePipeline.LocalFeedback feedback,
                     LegacyVoiceSession.Metadata metadata, LoopbackVoiceServer.ReplyWriter writer,
                     Object ticket, long turn) {
        String identity = null;
        boolean replySent = false;
        long started = clock.millis();
        String[] resolvedText = {localText};
        String historyCode = "CANCELLED", historyOutcome = "cancelled", historyAnswer = "";
        try {
            NativeOggInput.Result input = localText == null ? NativeOggInput.normalize(audio) : null;
            Settings settings = settings(); identity = settings.identity;
            synchronized (this) {
                if (closed || generation != turn || !writer.isOpen()) { discarded++; return; }
                if (backoffUntil > clock.millis()) {
                    rejected++; historyCode = "API_BACKOFF"; historyOutcome = "error"; return;
                }
                state = "pipeline"; transactions++;
            }
            changed();
            boolean streaming = !Boolean.FALSE.equals(settings.config.opt("voice_streaming_tts"));
            VoicePipeline.Output output = streaming ? new VoicePipeline.Output() {
                @Override public boolean isCancelled() {
                    synchronized (RuntimeVoiceHandler.this) {
                        return closed || generation != turn || !writer.isOpen();
                    }
                }
                @Override public void begin(String transcript, String answer) throws IOException {
                    if (isCancelled()) throw new IOException("cancelled");
                    resolvedText[0] = transcript;
                    writer.beginStreaming(metadata, transcript, answer);
                    synchronized (RuntimeVoiceHandler.this) { state = "reply"; }
                }
                @Override public void write(byte[] page, int offset, int count) throws IOException {
                    if (isCancelled()) throw new IOException("cancelled");
                    writer.writeOggPage(page, offset, count);
                }
                @Override public void finish() throws IOException {
                    if (isCancelled()) throw new IOException("cancelled");
                    writer.finishStreaming();
                }
            } : null;
            VoicePipeline.Result result = feedback != null ? pipeline.runFeedback(settings.config, localText, feedback, output)
                    : localText != null ? pipeline.runTranscript(settings.config, localText, output, () -> {
                        synchronized (RuntimeVoiceHandler.this) {
                            return closed || generation != turn || !writer.isOpen();
                        }
                    })
                    : streaming ? pipeline.runStreaming(settings.config, input.audio, output) : pipeline.run(settings.config, input.audio);
            resolvedText[0] = result.transcript;
            synchronized (this) {
                timings(result.summary);
                lastFileDeleted = result.summary.optBoolean("fileDeleted");
                if (closed || generation != turn || !writer.isOpen()) { discarded++; return; }
                state = "reply";
            }
            if (!streaming) writer.reply(metadata, result.transcript, result.answer, result.audio);
            replySent = true;
            historyAnswer = result.answer;
            String television = result.summary.optString("tvCode");
            historyCode = !television.isEmpty() ? television : feedback == null ? "answered" : feedback.name();
            historyOutcome = outcome(historyCode, result.summary.optBoolean("controlActionsExecuted"),
                    feedback == null && television.isEmpty());
            synchronized (this) {
                if (!closed && generation == turn && settings.identity.equals(configIdentity))
                    pipeline.rememberCompletedConversation(result);
                completed++; failures = 0; backoffUntil = 0; code = ""; stage = ""; provider = ""; status = 0; retryAfter = -1;
            }
        } catch (NativeOggInput.InputException invalid) {
            historyCode = "INVALID_AUDIO"; historyOutcome = "error";
            synchronized (this) { rejected++; code = "INVALID_AUDIO"; stage = "input"; }
        } catch (VoicePipeline.PipelineException failure) {
            historyCode = failure.code;
            historyOutcome = "CANCELLED".equals(historyCode) ? "cancelled" : "error";
            synchronized (this) {
                timings(failure.summary);
                lastFileDeleted = failure.summary.optBoolean("fileDeleted");
                if ("CANCELLED".equals(failure.code) && (closed || generation != turn || !writer.isOpen())) {
                    discarded++;
                } else {
                    failed++;
                    if (identity != null && identity.equals(configIdentity)) circuit(failure);
                }
            }
        } catch (Exception ignored) {
            historyCode = identity == null ? "INVALID_CONFIG" : "LOCAL_FAILURE";
            historyOutcome = "error";
            synchronized (this) {
                failed++;
                if (identity == null) { credentialsReady = false; code = "INVALID_CONFIG"; stage = "config"; }
                else { code = "LOCAL_FAILURE"; stage = "reply"; }
            }
        } finally {
            if (!replySent) { writer.abortStreaming(); completeWithoutSpeech(metadata, writer); }
            writer.close();
            synchronized (this) {
                workers = 0; currentWriter = null; state = closed ? "closed" : "idle";
                VoiceCloudGate.release(ticket);
            }
            recordHistory(resolvedText[0], historyAnswer, historyCode, historyOutcome,
                    Math.max(0, clock.millis() - started));
            changed();
        }
    }

    /** Local commands that complete without a synthesized response. */
    void recordLocal(String transcript, String code, boolean executed, long elapsedMs) {
        recordHistory(transcript, "", code, outcome(code, executed, false), elapsedMs);
    }

    private void recordHistory(String transcript, String answer, String code, String outcome, long elapsedMs) {
        if (history != null) history.record(transcript, answer, code, outcome, elapsedMs);
    }

    private static String outcome(String code, boolean executed, boolean answered) {
        if (code == null) return "unknown";
        switch (code) {
            case "cancelled": case "CANCELLED": return "cancelled";
            case "season_requested": case "episode_requested": case "year_ambiguous":
            case "episode_count_season_needed": case "series_context_missing":
            case "unsupported_or_ambiguous": case "TIMER_AMBIGUOUS": case "timer_ambiguous":
                return "clarification";
            case "no_match": case "year_no_match": case "episode_not_found": case "episode_not_listed":
            case "episodes_not_found": case "season_not_found": case "MUSIC_NOT_FOUND": case "music_not_found":
            case "TIMER_NOT_RUNNING": case "timer_not_running": case "resume_history_missing":
                return "no_match";
            case "episode_count": case "episode_listed": case "apps_listed": return "answer";
            case "TIMER_CREATED": case "video_control_applied": return "action";
            case "playback_requested": case "episode_playback_requested": case "movie_resume_requested":
            case "provider_opened_no_match": case "provider_search_opened": case "title_opened":
            case "series_card_opened": case "app_opened": return "requested";
            default: return executed ? "action" : answered ? "answer" : "error";
        }
    }

    /** Terminate a current failed turn locally; superseded writers cannot emit into a newer turn. */
    private static void completeWithoutSpeech(LegacyVoiceSession.Metadata metadata,
                                              LoopbackVoiceServer.ReplyWriter writer) {
        if (metadata == null || metadata.reference == null || metadata.reference.length() > 256
                || metadata.requestId == null || metadata.requestId.length() > 256
                || metadata.streamId <= 0 || metadata.streamId > 0xffffffffL) return;
        try { if (writer.isOpen()) writer.completeWithoutSpeech(metadata); }
        catch (Exception ignored) { /* Partial replies, stale turns and closed transports need no second reply. */ }
    }

    private Settings settings() throws Exception {
        JSONObject original = source.snapshot();
        if (original == null) throw new IOException("config_required");
        String encoded = original.toString();
        if (encoded == null || encoded.length() > 262144) throw new IOException("invalid_config");
        JSONObject config = new JSONObject(encoded);
        validator.validate(config);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(encoded.getBytes(StandardCharsets.UTF_8));
        StringBuilder identity = new StringBuilder();
        for (byte value : digest) identity.append(Integer.toHexString((value & 255) | 256).substring(1));
        String key = identity.toString();
        synchronized (this) {
            credentialsReady = true;
            if (!key.equals(configIdentity)) {
                pipeline.clearConversation();
                configIdentity = key; failures = 0; backoffUntil = 0; code = ""; stage = ""; provider = ""; status = 0; retryAfter = -1;
            }
        }
        return new Settings(config, key);
    }

    private void circuit(VoicePipeline.PipelineException failure) {
        stage = allowed(failure.stage, new String[]{"input", "config", "asr", "llm", "tts"}, "unknown");
        provider = allowed(failure.provider, new String[]{"gigachat", "speechify", "pipeline"}, "unknown");
        code = allowed(failure.code, new String[]{"NETWORK_ERROR", "TIMEOUT", "TLS_ERROR", "TLS_CONFIG",
                "RATE_LIMITED", "CONCURRENCY_LIMIT", "AUTHENTICATION_FAILED", "ACCESS_DENIED", "HTTP_ERROR",
                "QUOTA_OR_BILLING_LIMIT", "SPEND_LIMIT", "SERVICE_UNAVAILABLE", "REQUEST_REJECTED", "MISSING_KEY",
                "INVALID_KEY_FORMAT", "INVALID_CONFIG", "INVALID_PROXY_PORT", "INVALID_SCOPE", "INVALID_VOICE_ID",
                "BAD_RESPONSE", "RESPONSE_TOO_LARGE", "REDIRECT_BLOCKED", "ENDPOINT_BLOCKED", "NO_SPEECH",
                "TRANSCRIPTION_TRUNCATED", "ASR_FILE_NOT_DELETED", "BAD_ASR_RESPONSE", "BAD_ANSWER_RESPONSE",
                "BAD_AUDIO_RESPONSE", "INVALID_TRANSCRIPT", "INVALID_FEEDBACK", "INVALID_ANSWER", "INVALID_AUDIO", "STAGE_FAILED",
                "BUSY", "CANCELLED"}, "UPSTREAM_FAILURE");
        status = failure.httpStatus >= 100 && failure.httpStatus <= 599 ? failure.httpStatus : 0;
        retryAfter = Math.max(-1, Math.min(3600, failure.retryAfterSeconds));
        failures = Math.min(10, failures + 1);
        long delay = Math.min(60000L, 2000L << Math.min(5, failures - 1));
        if (status == 401 || status == 403 || "config".equals(stage)) delay = 300000;
        if ("SPEND_LIMIT".equals(code) || "QUOTA_OR_BILLING_LIMIT".equals(code)) delay = 1800000;
        if ("ASR_FILE_NOT_DELETED".equals(code)) delay = 300000;
        if (retryAfter >= 0) delay = Math.max(delay, retryAfter * 1000L);
        backoffUntil = clock.millis() + delay;
    }

    /** Copy only fixed numeric metrics; absent unfinished stages remain explicitly unknown. */
    private void timings(JSONObject summary) {
        lastElapsedMs = timing(summary, "elapsedMs"); lastAsrMs = timing(summary, "asrMs");
        lastLlmMs = timing(summary, "llmMs"); lastTtsMs = timing(summary, "ttsMs");
        lastTvMs = timing(summary, "tvMs"); lastTvCode = summary.optString("tvCode");
        lastTtsFirstByteMs = timing(summary, "ttsFirstByteMs"); lastTtsFirstAudioMs = timing(summary, "ttsFirstAudioMs");
        lastFirstAudioReadyMs = timing(summary, "firstAudioReadyMs");
        lastAsrOauthMs = timing(summary, "asrOauthMs"); lastAsrUploadMs = timing(summary, "asrUploadMs");
        lastAsrAudioChatMs = timing(summary, "asrAudioChatMs"); lastAsrDeleteMs = timing(summary, "asrDeleteMs");
        lastCombinedAudioAnswer = summary != null && summary.optBoolean("combinedAudioAnswer");
        lastStreamingAudio = summary != null && summary.optBoolean("streamingAudio");
    }
    private static long timing(JSONObject summary, String key) {
        Object value = summary == null ? null : summary.opt(key);
        if (!(value instanceof Long) && !(value instanceof Integer)) return -1;
        long millis = ((Number) value).longValue(); return millis >= 0 ? millis : -1;
    }

    /** Stops admission and reply only. Never interrupt upload/delete cleanup. */
    public void close() {
        LoopbackVoiceServer.ReplyWriter writer;
        synchronized (this) {
            closed = true; generation++; writer = currentWriter;
            pipeline.clearConversation();
        }
        cancelWarmup();
        if (writer != null) writer.cancelReply();
        changed();
    }
    public synchronized int activeWorkers() { return workers; }
    public synchronized boolean canRelease() { return closed && workers == 0 && !warming; }
    public synchronized JSONObject snapshot() {
        JSONObject result = new JSONObject();
        long remaining = Math.max(0, backoffUntil - clock.millis());
        put(result, "enabled", !closed); put(result, "closed", closed); put(result, "credentialsReady", credentialsReady);
        put(result, "activeWorkers", workers); put(result, "canRelease", closed && workers == 0 && !warming);
        put(result, "warming", warming); put(result, "warmedMs", warmedMs);
        put(result, "warmupOauthReady", warmupOauthReady); put(result, "warmupGigaReady", warmupGigaReady);
        put(result, "warmupSpeechifyReady", warmupSpeechifyReady); put(result, "warmupCode", warmupCode);
        put(result, "cloudBusy", VoiceCloudGate.isBusy()); put(result, "circuitOpen", remaining > 0);
        put(result, "backoffRemainingMs", remaining); put(result, "healthy", !closed && credentialsReady && remaining == 0);
        put(result, "healthReason", closed ? workers > 0 || warming ? "CLEANUP" : "STOPPED" : !credentialsReady ? "CONFIG_REQUIRED"
                : remaining > 0 ? "API_BACKOFF" : workers > 0 ? "PROCESSING" : "READY");
        put(result, "state", state); put(result, "accepted", accepted); put(result, "rejected", rejected);
        put(result, "completed", completed); put(result, "discarded", discarded); put(result, "failed", failed);
        put(result, "transactions", transactions); put(result, "lastStage", stage); put(result, "lastCode", code);
        put(result, "provider", provider); put(result, "httpStatus", status); put(result, "retryAfterSeconds", retryAfter);
        put(result, "lastFileDeleted", lastFileDeleted);
        put(result, "lastElapsedMs", lastElapsedMs); put(result, "lastAsrMs", lastAsrMs);
        put(result, "lastLlmMs", lastLlmMs); put(result, "lastTtsMs", lastTtsMs);
        put(result, "lastTvMs", lastTvMs); put(result, "lastTvCode", lastTvCode);
        put(result, "lastTtsFirstByteMs", lastTtsFirstByteMs); put(result, "lastTtsFirstAudioMs", lastTtsFirstAudioMs);
        put(result, "lastFirstAudioReadyMs", lastFirstAudioReadyMs);
        put(result, "lastAsrOauthMs", lastAsrOauthMs); put(result, "lastAsrUploadMs", lastAsrUploadMs);
        put(result, "lastAsrAudioChatMs", lastAsrAudioChatMs); put(result, "lastAsrDeleteMs", lastAsrDeleteMs);
        put(result, "lastCombinedAudioAnswer", lastCombinedAudioAnswer); put(result, "lastStreamingAudio", lastStreamingAudio);
        return result;
    }
    private void changed() { if (observer != null) try { observer.run(); } catch (RuntimeException ignored) { } }
    private static void put(JSONObject object, String key, Object value) { try { object.put(key, value); } catch (Exception ignored) { } }
    private static String allowed(String value, String[] values, String fallback) {
        for (String allowed : values) if (allowed.equals(value)) return allowed; return fallback;
    }
    private static final class Settings {
        final JSONObject config; final String identity;
        Settings(JSONObject config, String identity) { this.config = config; this.identity = identity; }
    }
}
