package local.station.voicebridge;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** One explicit ASR -> answer -> Ogg/Opus transaction. Runtime may supply a finalized TV command handler. */
public final class VoicePipeline {
    public static final int MAX_AUDIO_INPUT_BYTES = 2 * 1024 * 1024;
    public static final int MAX_TRANSCRIPT_CHARS = 1000;
    public static final int MAX_ANSWER_CHARS = 1000;
    public static final int MAX_AUDIO_OUTPUT_BYTES = 4 * 1024 * 1024;
    /** Fixed local feedback only. Never accepts an echoed query, URL or arbitrary answer. */
    public enum LocalFeedback {
        MUSIC_UNAVAILABLE("Сейчас не получается включить музыку."),
        MUSIC_NOT_FOUND("Не удалось найти эту музыку."),
        COMMAND_UNAVAILABLE("Сейчас не получается выполнить эту команду."),
        TIMER_CREATED("Таймер установлен."),
        TIMER_UNAVAILABLE("Не удалось подтвердить установку таймера. Проверь раздел Часы в пульте."),
        TIMER_NOT_RUNNING("Активного таймера нет."),
        TIMER_AMBIGUOUS("Сейчас несколько таймеров. Скажи: останови все таймеры."),
        TIMER_STOP_UNAVAILABLE("Не удалось подтвердить остановку таймера. Проверь раздел Часы в пульте.");
        private final String answer;
        LocalFeedback(String answer) { this.answer = answer; }
    }
    private static final String INSTRUCTION =
            "Ответь на следующую реплику по-русски одним коротким предложением без Markdown, "
            + "не длиннее 300 символов. Ты только формулируешь ответ и не управляешь устройствами. "
            + "Не утверждай, что выполнил действие, включил музыку или поставил таймер. "
            + "Если просят действие, кратко поясни, что этот тест пока не выполняет команды.";
    private static final int CONVERSATION_TURNS = 3;
    private static final long CONVERSATION_TTL_MS = 5 * 60 * 1000L;
    private final Backend backend;
    private final Clock clock;
    private final TvHandler tv;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<CloudClient.Warmup> warming = new AtomicReference<>();
    private final ArrayDeque<Exchange> conversation = new ArrayDeque<>(CONVERSATION_TURNS);
    private long conversationRevision;

    /** No configuration reads or network activity occur in the constructor. */
    public VoicePipeline(Context context) {
        this(context, null);
    }

    VoicePipeline(Context context, TvHandler tv) {
        this(new RealBackend(context), () -> System.nanoTime() / 1000000L, tv);
    }

    /** Package-private seam: host tests do not construct Android clients or open sockets. */
    VoicePipeline(Backend backend, Clock clock) {
        this(backend, clock, null);
    }

    VoicePipeline(Backend backend, Clock clock, TvHandler tv) {
        if (backend == null || clock == null) throw new IllegalArgumentException("pipeline_dependencies_required");
        this.backend = backend;
        this.clock = clock;
        this.tv = tv;
    }

    interface Clock { long millis(); }
    interface TvHandler {
        TvResult handle(String transcript, Chat chat, BooleanSupplier cancelled) throws Exception;
    }
    interface Chat { JSONObject ask(String prompt) throws Exception; }
    static final class TvResult {
        final String answer, code;
        final boolean launched;
        TvResult(String answer, String code, boolean launched) {
            this.answer = answer; this.code = code; this.launched = launched;
        }
    }
    /** A current native reply. The first decoder page starts it; audio is never persisted. */
    public interface Output {
        void begin(String transcript, String answer) throws IOException;
        void write(byte[] page, int offset, int count) throws IOException;
        void finish() throws IOException;
        boolean isCancelled();
    }
    interface AudioSink {
        void write(byte[] page, int offset, int count) throws IOException;
        boolean isCancelled();
    }
    interface Backend {
        void validate(JSONObject config) throws Exception;
        default JSONObject prewarm(JSONObject config, CloudClient.Warmup budget) throws Exception {
            return new JSONObject().put("ok", false).put("code", "UNSUPPORTED");
        }
        JSONObject transcribe(JSONObject config, byte[] audio) throws Exception;
        default JSONObject understandAudio(JSONObject config, byte[] audio) throws Exception {
            throw new IOException("combined_audio_not_implemented");
        }
        JSONObject ask(JSONObject config, String prompt) throws Exception;
        default JSONObject askConversation(JSONObject config, JSONArray messages) throws Exception {
            return ask(config, INSTRUCTION + " Реплика:\n" + messages.getJSONObject(messages.length() - 1).getString("content"));
        }
        Speech synthesize(JSONObject config, String answer) throws Exception;
        default StreamSpeech synthesizeStreaming(JSONObject config, String answer, AudioSink sink) throws Exception {
            // Offline/fallback backends retain their old implementation. Production overrides this
            // with raw HTTP streaming, without retaining the whole output in memory.
            Speech speech = synthesize(config, answer);
            if (speech == null || speech.audio == null || !"ogg".equals(speech.format))
                throw new IOException("invalid_stream_audio");
            int offset = 0;
            while (offset < speech.audio.length) {
                if (sink.isCancelled()) throw new IOException("cancelled");
                if (offset + 27 > speech.audio.length) throw new IOException("invalid_stream_audio");
                int segments = speech.audio[offset + 26] & 255, length = 27 + segments;
                if (offset + length > speech.audio.length) throw new IOException("invalid_stream_audio");
                for (int i = 0; i < segments; i++) length += speech.audio[offset + 27 + i] & 255;
                if (offset + length > speech.audio.length) throw new IOException("invalid_stream_audio");
                sink.write(speech.audio, offset, length); offset += length;
            }
            return new StreamSpeech(speech.audio.length, speech.billableCharacters, "ogg", -1, -1);
        }
    }
    static final class StreamSpeech {
        final int audioBytes, billableCharacters;
        final String format;
        final long firstByteMs, firstAudioMs;
        StreamSpeech(int audioBytes, int billableCharacters, String format, long firstByteMs, long firstAudioMs) {
            this.audioBytes = audioBytes; this.billableCharacters = billableCharacters; this.format = format;
            this.firstByteMs = firstByteMs; this.firstAudioMs = firstAudioMs;
        }
    }
    static final class Speech {
        final byte[] audio;
        final int billableCharacters;
        final String format;
        Speech(byte[] audio, int billableCharacters, String format) {
            this.audio = audio; this.billableCharacters = billableCharacters; this.format = format;
        }
    }
    private static final class RealBackend implements Backend {
        private final CloudClient cloud;
        private final SpeechifyClient speechify = new SpeechifyClient();
        RealBackend(Context context) { cloud = new CloudClient(context); }
        @Override public JSONObject prewarm(JSONObject config, CloudClient.Warmup budget) {
            return cloud.prewarm(config, budget);
        }
        @Override public void validate(JSONObject config) throws Exception {
            speechify.requireSynthesisConfigured(config);
            JSONObject giga = cloud.preflight(config).optJSONObject("gigachat");
            if (giga == null || !giga.optBoolean("ready")) throw new IOException("invalid_config");
        }
        @Override public JSONObject transcribe(JSONObject config, byte[] audio) throws Exception {
            return cloud.transcribeGigaChat(config, audio);
        }
        @Override public JSONObject understandAudio(JSONObject config, byte[] audio) throws Exception {
            return cloud.understandAudio(config, audio);
        }
        @Override public JSONObject ask(JSONObject config, String prompt) throws Exception {
            return cloud.ask(config, prompt);
        }
        @Override public JSONObject askConversation(JSONObject config, JSONArray messages) throws Exception {
            return cloud.askConversation(config, messages);
        }
        @Override public Speech synthesize(JSONObject config, String answer) throws Exception {
            SpeechifyClient.Result result = speechify.synthesizeOgg(config, answer);
            return new Speech(result.audio, result.billableCharacters, result.audioFormat);
        }
        @Override public StreamSpeech synthesizeStreaming(JSONObject config, String answer, AudioSink sink) throws Exception {
            SpeechifyClient.StreamResult result = speechify.streamOgg(config, answer, new SpeechifyClient.AudioSink() {
                @Override public void write(byte[] page, int offset, int count) throws IOException { sink.write(page, offset, count); }
                @Override public boolean isCancelled() { return sink.isCancelled(); }
            });
            return new StreamSpeech(result.audioBytes, result.billableCharacters, result.audioFormat,
                    result.firstByteMs, result.firstAudioMs);
        }
    }

    /** Optional startup metadata traffic on this backend's existing OAuth cache. */
    JSONObject prewarm(JSONObject config, CloudClient.Warmup budget) {
        if (budget == null || !warming.compareAndSet(null, budget)) {
            JSONObject result = new JSONObject(); put(result, "ok", false); put(result, "code", "BUSY");
            return result;
        }
        try {
            if (running.get() || budget.isCancelled()) {
                budget.cancel();
                JSONObject result = new JSONObject(); put(result, "ok", false); put(result, "code", "CANCELLED");
                return result;
            }
            return backend.prewarm(config, budget);
        } catch (Exception ignored) {
            JSONObject result = new JSONObject(); put(result, "ok", false); put(result, "code", "WARMUP_FAILED");
            return result;
        } finally {
            warming.compareAndSet(budget, null); budget.close();
        }
    }

    void cancelWarmup() {
        CloudClient.Warmup budget = warming.get();
        if (budget != null) budget.cancel();
    }

    /** Must be called on a worker. Text is held in the result only, never copied into summary. */
    public Result run(JSONObject config, byte[] audio) throws PipelineException {
        return run(config, audio, null, null, null);
    }

    /** Same providers and answer, but complete Ogg pages reach the native decoder before HTTP EOF. */
    public Result runStreaming(JSONObject config, byte[] audio, Output output) throws PipelineException {
        if (output == null) throw failure("input", "INVALID_CONFIG", new JSONObject());
        return run(config, audio, output, null, null);
    }

    /** Final on-device ASR only. No audio upload, transcription API or remote file is created. */
    public Result runTranscript(JSONObject config, String transcript, Output output) throws PipelineException {
        return runTranscript(config, transcript, output, () -> output != null && output.isCancelled());
    }

    Result runTranscript(JSONObject config, String transcript, Output output, BooleanSupplier cancelled) throws PipelineException {
        if (transcript == null) throw failure("input", "INVALID_TRANSCRIPT", new JSONObject());
        return run(config, null, output, transcript, null, cancelled);
    }

    /** A finalized local result. Only the fixed feedback enters synthesis; no ASR or LLM. */
    public Result runFeedback(JSONObject config, String transcript, LocalFeedback feedback, Output output)
            throws PipelineException {
        if (transcript == null) throw failure("input", "INVALID_TRANSCRIPT", new JSONObject());
        if (feedback == null) throw failure("input", "INVALID_FEEDBACK", new JSONObject());
        return run(config, null, output, transcript, feedback);
    }

    private Result run(JSONObject config, byte[] audio, Output output, String localText, LocalFeedback feedback)
            throws PipelineException {
        return run(config, audio, output, localText, feedback, () -> output != null && output.isCancelled());
    }

    private Result run(JSONObject config, byte[] audio, Output output, String localText, LocalFeedback feedback,
                       BooleanSupplier cancelled) throws PipelineException {
        JSONObject summary = new JSONObject();
        put(summary, "pipeline", feedback != null ? "local_command_speechify_ogg"
                : localText == null ? "gigachat_asr_answer_speechify_ogg" : "local_asr_gigachat_speechify_ogg");
        put(summary, "controlActionsExecuted", false);
        put(summary, "audioPlayed", false);
        put(summary, "fileDeleted", false);
        cancelWarmup();
        if (!running.compareAndSet(false, true)) throw failure("input", "BUSY", summary);
        cancelWarmup();
        long started = clock.millis();
        String stage = "input";
        try {
            checkInterrupted(stage, summary);
            if (config == null) throw failure(stage, "INVALID_CONFIG", summary);
            if (localText == null && !validAudio(audio)) throw failure(stage, "INVALID_AUDIO", summary);
            put(summary, "inputBytes", audio == null ? 0 : audio.length);
            put(summary, "localAsr", localText != null);
            put(summary, "cloudAudioUploaded", localText == null);
            put(summary, "fileDeletionRequired", localText == null);
            String validatedLocal = localText == null ? null : text(localText, MAX_TRANSCRIPT_CHARS,
                    stage, "INVALID_TRANSCRIPT", summary);
            if (feedback != null) checkOutput(output, stage, summary);
            // Snapshot mutable settings before the first request. Nothing is persisted or logged.
            JSONObject snapshot;
            try {
                String serialized = config.toString();
                if (serialized == null || serialized.length() > 262144)
                    throw failure(stage, "INVALID_CONFIG", summary);
                snapshot = new JSONObject(serialized);
            } catch (PipelineException error) { throw error; }
            catch (Exception ignored) { throw failure(stage, "INVALID_CONFIG", summary); }
            stage = "config";
            backend.validate(snapshot);
            checkInterrupted(stage, summary);
            final String transcript, answer;
            long replyRevision = -1;
            if (feedback != null) {
                transcript = validatedLocal;
                answer = feedback.answer;
                put(summary, "localFeedback", feedback.name());
                put(summary, "combinedAudioAnswer", false);
                put(summary, "asrMs", 0); put(summary, "llmMs", 0);
            } else {
                stage = "asr";
                long asrStarted = clock.millis();
                boolean directAudio = localText == null && Boolean.TRUE.equals(snapshot.opt("voice_direct_audio"));
                JSONObject asr = localText == null
                        ? directAudio ? backend.understandAudio(snapshot, audio) : backend.transcribe(snapshot, audio)
                        : new JSONObject().put("ok", true).put("text", validatedLocal);
                put(summary, "asrMs", elapsed(asrStarted));
                put(summary, "combinedAudioAnswer", directAudio);
                JSONObject asrTimings = asr == null ? null : asr.optJSONObject("timings");
                if (asrTimings != null) for (String key : new String[]{"oauthMs", "uploadMs", "audioChatMs", "deleteMs"}) {
                    Object value = asrTimings.opt(key);
                    if ((value instanceof Long || value instanceof Integer) && ((Number) value).longValue() >= 0)
                        put(summary, "asr" + Character.toUpperCase(key.charAt(0)) + key.substring(1), value);
                }
                if (asr == null || !Boolean.TRUE.equals(asr.opt("ok")))
                    throw failure(stage, "BAD_ASR_RESPONSE", summary);
                boolean deleted = Boolean.TRUE.equals(asr.opt("fileDeleted"));
                put(summary, "fileDeleted", deleted);
                if (localText == null && !deleted) throw failure(stage, "ASR_FILE_NOT_DELETED", summary);
                transcript = text(asr.opt("text"), MAX_TRANSCRIPT_CHARS,
                        stage, "INVALID_TRANSCRIPT", summary);
                put(summary, "transcriptChars", transcript.length());
                put(summary, directAudio ? "combinedUsage" : "asrUsage", usage(asr.optJSONObject("usage")));
                checkInterrupted(stage, summary);
                checkOutput(output, stage, summary);
                long llmStarted = clock.millis();
                stage = "tv";
                TvResult television = tv == null || localText == null ? null
                        : tv.handle(transcript, prompt -> backend.ask(snapshot, prompt), cancelled);
                if (cancelled.getAsBoolean()) throw failure(stage, "CANCELLED", summary);
                if (television != null) {
                    put(summary, "pipeline", "local_asr_tv_search_speechify_ogg");
                    put(summary, "tvCode", television.code);
                    put(summary, "tvMs", elapsed(llmStarted));
                    put(summary, "controlActionsExecuted", television.launched);
                    answer = text(television.answer, MAX_ANSWER_CHARS, stage, "INVALID_ANSWER", summary);
                } else {
                    stage = "llm";
                    JSONObject response;
                    if (localText != null) {
                        JSONArray messages = new JSONArray();
                        replyRevision = addConversation(messages, transcript);
                        response = backend.askConversation(snapshot, messages);
                    } else {
                        response = directAudio ? asr : backend.ask(snapshot, INSTRUCTION + " Реплика:\n" + transcript);
                    }
                    put(summary, "llmMs", elapsed(llmStarted));
                    if (response == null || !Boolean.TRUE.equals(response.opt("ok")))
                        throw failure(stage, "BAD_ANSWER_RESPONSE", summary);
                    answer = text(response.opt(directAudio ? "answer" : "text"), MAX_ANSWER_CHARS,
                            stage, "INVALID_ANSWER", summary);
                    if (!directAudio) put(summary, "llmUsage", usage(response.optJSONObject("usage")));
                }
            }
            put(summary, "transcriptChars", transcript.length());
            put(summary, "answerChars", answer.length());
            checkInterrupted(stage, summary);
            checkOutput(output, stage, summary);
            if (cancelled.getAsBoolean()) throw failure(stage, "CANCELLED", summary);
            stage = "tts";
            long stepStarted = clock.millis();
            if (output != null) {
                final boolean[] begun = {false};
                final long[] emitted = {0};
                StreamSpeech speech = backend.synthesizeStreaming(snapshot, answer, new AudioSink() {
                    @Override public boolean isCancelled() {
                        return Thread.currentThread().isInterrupted() || output.isCancelled();
                    }
                    @Override public void write(byte[] page, int offset, int count) throws IOException {
                        if (isCancelled()) throw new IOException("cancelled");
                        if (page == null || offset < 0 || count < 1 || offset > page.length - count
                                || count > MAX_AUDIO_OUTPUT_BYTES - emitted[0])
                            throw new IOException("invalid_stream_audio");
                        if (!begun[0]) { output.begin(transcript, answer); begun[0] = true; }
                        output.write(page, offset, count); emitted[0] += count;
                    }
                });
                put(summary, "ttsMs", elapsed(stepStarted));
                if (speech == null || !"ogg".equals(speech.format) || !begun[0]
                        || speech.audioBytes < 47 || speech.audioBytes != emitted[0]
                        || speech.audioBytes > MAX_AUDIO_OUTPUT_BYTES || speech.billableCharacters < -1
                        || speech.billableCharacters > 2000)
                    throw failure(stage, "BAD_AUDIO_RESPONSE", summary);
                checkOutput(output, stage, summary);
                output.finish();
                put(summary, "ttsFirstByteMs", speech.firstByteMs);
                put(summary, "ttsFirstAudioMs", speech.firstAudioMs);
                if (speech.firstAudioMs >= 0) put(summary, "firstAudioReadyMs", Math.max(0, stepStarted - started) + speech.firstAudioMs);
                put(summary, "streamingAudio", true);
                put(summary, "audioFormat", "ogg"); put(summary, "audioBytes", speech.audioBytes);
                put(summary, "billableCharacters", speech.billableCharacters);
                put(summary, "elapsedMs", elapsed(started)); put(summary, "ok", true);
                return new Result(summary, null, transcript, answer, replyRevision);
            }
            Speech speech = backend.synthesize(snapshot, answer);
            put(summary, "ttsMs", elapsed(stepStarted));
            if (speech == null || !"ogg".equals(speech.format) || speech.audio == null
                    || speech.audio.length < 4 || speech.audio.length > MAX_AUDIO_OUTPUT_BYTES
                    || speech.audio[0] != 'O' || speech.audio[1] != 'g'
                    || speech.audio[2] != 'g' || speech.audio[3] != 'S'
                    || speech.billableCharacters < 0 || speech.billableCharacters > 2000)
                throw failure(stage, "BAD_AUDIO_RESPONSE", summary);
            checkInterrupted(stage, summary);
            put(summary, "audioFormat", "ogg");
            put(summary, "audioBytes", speech.audio.length);
            put(summary, "billableCharacters", speech.billableCharacters);
            put(summary, "elapsedMs", elapsed(started));
            put(summary, "ok", true);
            return new Result(summary, speech.audio, transcript, answer, replyRevision);
        } catch (PipelineException error) {
            put(error.summary, "elapsedMs", elapsed(started));
            throw error;
        } catch (CloudClient.CloudException error) {
            throw upstream(stage, error.provider, error.code, error.httpStatus, -1, summary, started);
        } catch (SpeechifyClient.SpeechifyException error) {
            throw upstream(stage, error.provider, error.code, error.httpStatus,
                    error.retryAfterSeconds, summary, started);
        } catch (Exception ignored) {
            put(summary, "elapsedMs", elapsed(started));
            throw failure(stage, Thread.currentThread().isInterrupted() || output != null && output.isCancelled()
                    ? "CANCELLED" : "STAGE_FAILED", summary);
        } finally {
            running.set(false);
        }
    }

    private synchronized long addConversation(JSONArray messages, String transcript) throws JSONException {
        expireConversation(clock.millis());
        messages.put(message("system", INSTRUCTION));
        for (Exchange exchange : conversation) {
            messages.put(message("user", exchange.transcript));
            messages.put(message("assistant", exchange.answer));
        }
        messages.put(message("user", transcript));
        return conversationRevision;
    }

    /** Called by the live runtime after the complete reply reached the native output. */
    synchronized void rememberCompletedConversation(Result result) {
        if (result.conversationRevision != conversationRevision) return;
        result.conversationRevision = -1;
        long now = clock.millis();
        expireConversation(now);
        if (conversation.size() == CONVERSATION_TURNS) conversation.removeFirst();
        conversation.addLast(new Exchange(result.transcript, result.answer, now));
    }

    synchronized void clearConversation() {
        conversation.clear();
        conversationRevision++;
    }

    private void expireConversation(long now) {
        while (!conversation.isEmpty() && now - conversation.getFirst().completedAt >= CONVERSATION_TTL_MS)
            conversation.removeFirst();
    }

    private static JSONObject message(String role, String content) throws JSONException {
        return new JSONObject().put("role", role).put("content", content);
    }

    private static final class Exchange {
        final String transcript, answer;
        final long completedAt;

        Exchange(String transcript, String answer, long completedAt) {
            this.transcript = transcript;
            this.answer = answer;
            this.completedAt = completedAt;
        }
    }

    private PipelineException upstream(String stage, String provider, String code, int status,
                                       int retry, JSONObject summary, long started) {
        put(summary, "elapsedMs", elapsed(started));
        return new PipelineException(stage, code, provider, status, retry, summary);
    }
    private long elapsed(long started) { return Math.max(0, clock.millis() - started); }
    private static boolean validAudio(byte[] audio) {
        if (audio == null || audio.length > MAX_AUDIO_INPUT_BYTES) return false;
        boolean ogg = audio.length >= 27 && audio[0] == 'O' && audio[1] == 'g'
                && audio[2] == 'g' && audio[3] == 'S';
        boolean wav = audio.length >= 44 && audio[0] == 'R' && audio[1] == 'I'
                && audio[2] == 'F' && audio[3] == 'F' && audio[8] == 'W'
                && audio[9] == 'A' && audio[10] == 'V' && audio[11] == 'E';
        return ogg || wav;
    }
    private static String text(Object raw, int maximum, String stage, String code, JSONObject summary)
            throws PipelineException {
        if (!(raw instanceof String)) throw failure(stage, code, summary);
        String value = (String) raw;
        if (value.length() > maximum) throw failure(stage, code, summary);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    throw failure(stage, code, summary);
            } else if (Character.isLowSurrogate(c) || c < 32 && c != '\n' && c != '\r' && c != '\t')
                throw failure(stage, code, summary);
        }
        value = value.trim();
        if (value.isEmpty()) throw failure(stage, code, summary);
        return value;
    }
    private static JSONObject usage(JSONObject source) {
        JSONObject safe = new JSONObject();
        if (source == null) return safe;
        for (String field : new String[]{"prompt_tokens", "completion_tokens", "total_tokens", "precached_prompt_tokens"}) {
            Object value = source.opt(field);
            if (!(value instanceof Integer || value instanceof Long)) continue;
            long count = ((Number) value).longValue();
            if (count >= 0 && count <= 1000000000L) put(safe, field, count);
        }
        return safe;
    }
    private static void checkInterrupted(String stage, JSONObject summary) throws PipelineException {
        if (Thread.currentThread().isInterrupted()) throw failure(stage, "CANCELLED", summary);
    }
    private static void checkOutput(Output output, String stage, JSONObject summary) throws PipelineException {
        if (output != null && output.isCancelled()) throw failure(stage, "CANCELLED", summary);
    }
    private static PipelineException failure(String stage, String code, JSONObject summary) {
        return new PipelineException(stage, code, "pipeline", 0, -1, summary);
    }
    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); } catch (Exception ignored) { }
    }

    public static final class Result {
        public final JSONObject summary;
        public final byte[] audio;
        /** In-memory reply data only; callers must not write these into logs or reports. */
        public final String transcript, answer;
        private long conversationRevision;
        private Result(JSONObject summary, byte[] audio, String transcript, String answer, long conversationRevision) {
            this.summary = summary; this.audio = audio; this.transcript = transcript; this.answer = answer;
            this.conversationRevision = conversationRevision;
        }
    }
    public static final class PipelineException extends IOException {
        private static final long serialVersionUID = 1L;
        public final String stage, code, provider;
        public final int httpStatus, retryAfterSeconds;
        public final JSONObject summary;
        private PipelineException(String stage, String code, String provider, int status,
                                  int retry, JSONObject summary) {
            super("voice_pipeline: " + stage + ": " + code);
            this.stage = stage; this.code = code; this.provider = provider;
            this.httpStatus = status; this.retryAfterSeconds = retry; this.summary = summary;
            put(summary, "ok", false);
            put(summary, "failedStage", stage);
        }
    }
}
