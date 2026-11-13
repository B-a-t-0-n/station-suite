package local.station.voicebridge;

import android.content.Context;
import org.json.JSONObject;
import java.io.IOException;

/** One explicitly armed native command window. No threads, files, logging or configuration reads. */
public final class LegacyPipelineHandler implements LoopbackVoiceServer.CommandHandler {
    private final VoicePipeline pipeline;
    private final JSONObject config;
    private final Runnable onWorkerFinished;
    private boolean closed, attempted, apiStarted, replySent;
    private int workers, inputBytes;
    private String state = "idle", code = "";
    private JSONObject pipelineSummary = new JSONObject();
    private JSONObject inputSummary = new JSONObject();
    private JSONObject pipelineError = new JSONObject();

    /** Snapshot credentials in memory before opening the listener. This constructor performs no I/O. */
    public LegacyPipelineHandler(Context context, JSONObject config, Runnable onWorkerFinished) {
        this(new VoicePipeline(applicationContext(context)), config, onWorkerFinished);
    }

    /** Host seam; production always constructs the actual pipeline above. */
    LegacyPipelineHandler(VoicePipeline pipeline, JSONObject settings, Runnable onWorkerFinished) {
        if (pipeline == null || settings == null) throw new IllegalArgumentException("invalid_handler_config");
        try {
            String serialized = settings.toString();
            if (serialized == null || serialized.length() > 262144)
                throw new IllegalArgumentException("invalid_handler_config");
            this.config = new JSONObject(serialized);
        } catch (Exception ignored) { throw new IllegalArgumentException("invalid_handler_config"); }
        this.pipeline = pipeline;
        this.onWorkerFinished = onWorkerFinished;
    }

    private static Context applicationContext(Context context) {
        if (context == null) throw new IllegalArgumentException("invalid_handler_context");
        Context application = context.getApplicationContext();
        return application == null ? context : application;
    }

    /** Runs synchronously on the admitted connection worker, at most once for this handler. */
    @Override public void commandReady(byte[] audio, LegacyVoiceSession.Metadata metadata,
                                       LoopbackVoiceServer.ReplyWriter writer) throws IOException {
        synchronized (this) {
            if (closed) throw rejected("CLOSED");
            if (attempted) throw rejected("ALREADY_USED");
            attempted = true;
            if (!VoiceCloudGate.tryAcquire(this)) throw rejected("CLOUD_BUSY");
            workers = 1;
            state = "validating";
        }
        try {
            if (metadata == null || audio == null || audio.length == 0
                    || audio.length > LegacyVoiceSession.MAX_COMMAND_BYTES
                    || metadata.audioBytes != audio.length || metadata.streamId <= 0
                    || metadata.streamId > 0xffffffffL || writer == null)
                throw rejected("INVALID_AUDIO");
            // Matching streamcontrol already finalized this selected command. A live
            // Ogg input may omit EOS or restart its encoder; output validation stays strict.
            NativeOggInput.Result input;
            try { input = NativeOggInput.normalize(audio); }
            catch (NativeOggInput.InputException invalid) { throw rejected("INVALID_AUDIO"); }
            synchronized (this) {
                inputBytes = audio.length;
                put(inputSummary, "segments", input.segments);
                put(inputSummary, "pages", input.pages);
                put(inputSummary, "eosRepairs", input.eosRepairs);
                put(inputSummary, "serialRewrites", input.serialRewrites);
                put(inputSummary, "inputBytes", input.inputBytes);
                put(inputSummary, "outputBytes", input.outputBytes);
                if (closed || !writer.isOpen()) throw rejected("WRITER_CLOSED");
                state = "pipeline";
                apiStarted = true;
            }
            VoicePipeline.Result result;
            try { result = pipeline.run(config, input.audio); }
            catch (VoicePipeline.PipelineException failure) {
                synchronized (this) {
                    pipelineSummary = safeSummary(failure.summary);
                    put(pipelineError, "stage", failure.stage);
                    put(pipelineError, "code", failure.code);
                    put(pipelineError, "provider", failure.provider);
                    put(pipelineError, "httpStatus", failure.httpStatus);
                    put(pipelineError, "retryAfterSeconds", failure.retryAfterSeconds);
                }
                throw rejected("PIPELINE_FAILED");
            }
            synchronized (this) {
                pipelineSummary = safeSummary(result.summary);
                if (closed || !writer.isOpen()) {
                    state = "discarded";
                    code = "WRITER_CLOSED";
                    return;
                }
                state = "reply";
            }
            try { writer.reply(metadata, result.transcript, result.answer, result.audio); }
            catch (IOException failed) { throw rejected("REPLY_FAILED"); }
            synchronized (this) { replySent = true; state = "complete"; code = ""; }
        } finally {
            // VoicePipeline returns only after CloudClient's upload/delete finally has completed.
            // Closing the service or writer must never release global ownership before this point.
            synchronized (this) {
                workers = 0;
                VoiceCloudGate.release(this);
            }
            if (onWorkerFinished != null) {
                try { onWorkerFinished.run(); } catch (RuntimeException ignored) { }
            }
        }
    }

    /** Race-free admission stop. Existing HTTP work finishes cleanup; it is not interrupted. */
    public synchronized void close() { closed = true; }
    public synchronized int activeWorkers() { return workers; }
    /** Close before consulting this during service destruction or BUSY ownership release. */
    public synchronized boolean canRelease() { return closed && workers == 0; }

    /** Fixed state/codes and allowlisted counters only; no audio, text, identifiers or settings. */
    public synchronized JSONObject snapshot() {
        JSONObject result = new JSONObject();
        put(result, "state", state); put(result, "code", code);
        put(result, "closed", closed); put(result, "attempted", attempted);
        put(result, "activeWorkers", workers); put(result, "canRelease", closed && workers == 0);
        put(result, "apiStarted", apiStarted); put(result, "replySent", replySent);
        put(result, "inputBytes", inputBytes); put(result, "pipeline", safeSummary(pipelineSummary));
        try { put(result, "inputContainer", new JSONObject(inputSummary.toString())); }
        catch (Exception ignored) { }
        try { put(result, "pipelineError", new JSONObject(pipelineError.toString())); }
        catch (Exception ignored) { }
        return result;
    }

    private synchronized IOException rejected(String fixedCode) {
        state = "failed"; code = fixedCode;
        return new IOException("legacy_pipeline_" + fixedCode);
    }

    private static JSONObject safeSummary(JSONObject source) {
        JSONObject result = new JSONObject();
        if (source == null) return result;
        for (String field : new String[]{"ok", "controlActionsExecuted", "audioPlayed", "fileDeleted"}) {
            Object value = source.opt(field);
            if (value instanceof Boolean) put(result, field, value);
        }
        for (String field : new String[]{"inputBytes", "asrMs", "llmMs", "ttsMs", "elapsedMs",
                "transcriptChars", "answerChars", "audioBytes", "billableCharacters"}) {
            Object value = source.opt(field);
            if (value instanceof Integer || value instanceof Long) {
                long count = ((Number) value).longValue();
                if (count >= 0 && count <= 1000000000L) put(result, field, count);
            }
        }
        Object stage = source.opt("failedStage");
        for (String allowed : new String[]{"input", "config", "asr", "llm", "tts"})
            if (allowed.equals(stage)) put(result, "failedStage", allowed);
        for (String field : new String[]{"asrUsage", "llmUsage"}) {
            JSONObject usage = source.optJSONObject(field);
            if (usage == null) continue;
            JSONObject safe = new JSONObject();
            for (String token : new String[]{"prompt_tokens", "completion_tokens", "total_tokens", "precached_prompt_tokens"}) {
                Object value = usage.opt(token);
                if (value instanceof Integer || value instanceof Long) {
                    long count = ((Number) value).longValue();
                    if (count >= 0 && count <= 1000000000L) put(safe, token, count);
                }
            }
            put(result, field, safe);
        }
        return result;
    }

    private static void put(JSONObject object, String field, Object value) {
        try { object.put(field, value); } catch (Exception ignored) { }
    }
}
