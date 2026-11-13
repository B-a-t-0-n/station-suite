package local.station.voicebridge;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;

/** Bounded legacy reply schema from the working PC send_reply implementation. No files or API calls. */
final class LegacyVoiceReply {
    private static final int MAX_AUDIO = 4 * 1024 * 1024;
    private static final int MAX_TEXT = 16384;
    private static final int OUTPUT_STREAM = 2;
    interface Sink {
        void json(JSONObject object) throws IOException;
        void binary(byte[] bytes) throws IOException;
        default void audioPacketSent() { }
    }
    private LegacyVoiceReply() { }

    static void validate(LegacyVoiceSession.Metadata meta, String transcript, String answer, byte[] ogg)
            throws IOException {
        validateText(meta, transcript, answer);
        if (ogg == null || ogg.length < 47 || ogg.length > MAX_AUDIO) throw new IOException("invalid_reply_audio");
        int offset = 0, pages = 0; long serial = -1, sequence = 0;
        boolean eos = false;
        while (offset < ogg.length) {
            if (++pages > 4096 || eos || offset + 27 > ogg.length
                    || ogg[offset] != 'O' || ogg[offset + 1] != 'g' || ogg[offset + 2] != 'g'
                    || ogg[offset + 3] != 'S' || ogg[offset + 4] != 0)
                throw new IOException("invalid_ogg_page");
            int flags = ogg[offset + 5] & 255, segments = ogg[offset + 26] & 255;
            if ((flags & ~7) != 0 || offset + 27 + segments > ogg.length) throw new IOException("invalid_ogg_page");
            int length = 27 + segments;
            for (int i = 0; i < segments; i++) length += ogg[offset + 27 + i] & 255;
            if (offset + length > ogg.length) throw new IOException("truncated_ogg_page");
            long pageSerial = little(ogg, offset + 14), pageSequence = little(ogg, offset + 18);
            if (pages == 1) {
                serial = pageSerial;
                int body = offset + 27 + segments;
                if (flags != 2 || pageSequence != 0 || segments != 1 || (ogg[offset + 27] & 255) != 19
                        || length < 47 || !ascii(ogg, body, "OpusHead") || (ogg[body + 8] & 255) != 1
                        || (ogg[body + 9] & 255) != 1 || ogg[body + 18] != 0)
                    throw new IOException("invalid_opus_head");
            } else if ((flags & 2) != 0 || pageSerial != serial || pageSequence != sequence)
                throw new IOException("invalid_ogg_sequence");
            sequence = pageSequence + 1;
            if ((crc(ogg, offset, length) & 0xffffffffL) != little(ogg, offset + 22))
                throw new IOException("invalid_ogg_crc");
            eos = (flags & 4) != 0;
            offset += length;
        }
        if (!eos || pages < 2) throw new IOException("missing_ogg_eos");
    }

    static void validateText(LegacyVoiceSession.Metadata meta, String transcript, String answer) throws IOException {
        if (meta == null || transcript == null || answer == null || answer.trim().isEmpty()
                || transcript.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT
                || answer.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT)
            throw new IOException("invalid_reply_text");
    }

    static void send(LegacyVoiceSession.Metadata meta, String transcript, String answer, byte[] ogg, Sink sink)
            throws IOException {
        send(meta, transcript, answer, ogg, sink, false);
    }
    static void send(LegacyVoiceSession.Metadata meta, String transcript, String answer, byte[] ogg, Sink sink,
                     boolean inputAlreadyFinished) throws IOException {
        try {
            sendStart(meta, transcript, answer, sink, inputAlreadyFinished);
            int completedPackets = 0; boolean audioSent = false;
            for (int offset = 0; offset < ogg.length;) {
                int segments = ogg[offset + 26] & 255, length = 27 + segments;
                for (int i = 0; i < segments; i++) {
                    int lace = ogg[offset + 27 + i] & 255;
                    length += lace;
                    if (lace < 255) completedPackets++;
                }
                byte[] bytes = new byte[length + 4];
                bytes[3] = OUTPUT_STREAM;
                System.arraycopy(ogg, offset, bytes, 4, length);
                sink.binary(bytes); offset += length;
                if (!audioSent && completedPackets > 2) { audioSent = true; sink.audioPacketSent(); }
            }
            sink.json(control(OUTPUT_STREAM));
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("reply_schema_failed"); }
    }

    private static void sendStart(LegacyVoiceSession.Metadata meta, String transcript, String answer, Sink sink,
                                  boolean inputAlreadyFinished)
            throws Exception {
        if (!inputAlreadyFinished) sendInputFinal(meta, transcript, sink);
        if (meta.requiresSpotterValidation) sink.json(directive("Spotter", "Validation", new JSONObject()
                .put("allow_activation_by_unvalidated_spotter", 0)
                .put("multiactivation_id", System.currentTimeMillis() + "local")
                .put("valid", 1).put("result", 1).put("canceled_cause_of_multiactivation", 0), meta.reference, 0));
        sink.json(directive("Vins", "VinsResponse", vins(meta, answer), meta.reference, 0));
        sink.json(directive("TTS", "Speak", new JSONObject().put("from_cache", false)
                .put("enable_bargin", false).put("lazy_tts_streaming", false)
                .put("disableInterruptionSpotter", false).put("format", "audio/opus"), meta.reference, OUTPUT_STREAM));
    }

    /** Complete-page incremental output. Header prefix <=128KiB; one EOS page <=65307 bytes. */
    static final class Streaming {
        private static final int MAX_PAGE = 65307, MAX_PREFIX = 128 * 1024, MAX_TAGS = 65536;
        private final Sink sink;
        private final LegacyVoiceSession.Metadata meta;
        private final boolean inputAlreadyFinished;
        private String transcript, answer;
        private final ArrayList<byte[]> prefix = new ArrayList<>();
        private final ByteArrayOutputStream tags = new ByteArrayOutputStream();
        private byte[] finalPage;
        private int pages, bytes, prefixBytes, packetIndex, packetBytes;
        private long serial, sequence, previousGranule = -1;
        private boolean packetOpen, eos, audioSent;
        private volatile boolean started, finished;

        Streaming(LegacyVoiceSession.Metadata meta, String transcript, String answer, Sink sink) {
            this(meta, transcript, answer, sink, false);
        }
        Streaming(LegacyVoiceSession.Metadata meta, String transcript, String answer, Sink sink, boolean inputAlreadyFinished) {
            this.meta = meta; this.transcript = transcript; this.answer = answer; this.sink = sink;
            this.inputAlreadyFinished = inputAlreadyFinished;
        }
        boolean hasOutput() { return started; }
        boolean isFinished() { return finished; }

        void page(byte[] source, int offset, int count) throws IOException {
            if (finished || eos) throw new IOException("stream_already_ended");
            if (source == null || offset < 0 || count < 27 || count > MAX_PAGE
                    || offset > source.length - count || pages >= 4096 || count > MAX_AUDIO - bytes)
                throw new IOException("invalid_stream_page");
            byte[] page = Arrays.copyOfRange(source, offset, offset + count);
            if (!ascii(page, 0, "OggS") || page[4] != 0) throw new IOException("invalid_ogg_page");
            int flags = page[5] & 255, segments = page[26] & 255, body = 27 + segments, length = body;
            if ((flags & ~7) != 0 || body > count) throw new IOException("invalid_ogg_page");
            for (int i = 0; i < segments; i++) length += page[27 + i] & 255;
            if (length != count) throw new IOException("invalid_stream_page");
            if ((crc(page, 0, count) & 0xffffffffL) != little(page, 22)) throw new IOException("invalid_ogg_crc");
            long pageSerial = little(page, 14), pageSequence = little(page, 18), granule = little64(page, 6);
            int beforeAudio = packetIndex;
            if (pages == 0) {
                if (flags != 2 || pageSequence != 0 || granule != 0 || segments != 1
                        || (page[27] & 255) != 19 || count != 47 || !ascii(page, body, "OpusHead")
                        || page[body + 8] != 1 || page[body + 9] != 1 || page[body + 18] != 0)
                    throw new IOException("invalid_opus_head");
                serial = pageSerial; packetIndex = 1;
            } else {
                if ((flags & 2) != 0 || pageSerial != serial || pageSequence != sequence)
                    throw new IOException("invalid_ogg_sequence");
                if (((flags & 1) != 0) != packetOpen) throw new IOException("invalid_ogg_continuation");
                int position = body;
                for (int i = 0; i < segments; i++) {
                    int lace = page[27 + i] & 255;
                    if (lace > MAX_AUDIO - packetBytes) throw new IOException("invalid_opus_packet");
                    packetBytes += lace;
                    if (packetIndex == 1) {
                        if (lace > MAX_TAGS - tags.size()) throw new IOException("invalid_opus_tags");
                        tags.write(page, position, lace);
                    }
                    position += lace; packetOpen = lace == 255;
                    if (!packetOpen) {
                        if (packetIndex == 1) validateTags(tags.toByteArray());
                        else if (packetBytes == 0) throw new IOException("invalid_opus_packet");
                        packetIndex++; packetBytes = 0;
                    }
                }
            }
            boolean completedAudio = packetIndex > Math.max(2, beforeAudio);
            if (granule < -1 || granule == -1 && completedAudio
                    || granule >= 0 && previousGranule >= 0 && granule < previousGranule)
                throw new IOException("invalid_ogg_granule");
            if (granule >= 0) previousGranule = granule;
            if ((flags & 4) != 0 && (packetOpen || packetIndex < 3 || granule < 0))
                throw new IOException("invalid_ogg_eos");
            sequence = pageSequence + 1; pages++; bytes += count; eos = (flags & 4) != 0;
            if (eos) { finalPage = page; return; }
            if (!started && packetIndex < 3) {
                if (count > MAX_PREFIX - prefixBytes) throw new IOException("stream_header_limit");
                prefix.add(page); prefixBytes += count; return;
            }
            start(); emitPage(page, completedAudio);
        }

        void finish() throws IOException {
            if (finished || !eos || finalPage == null || packetOpen || packetIndex < 3)
                throw new IOException("missing_ogg_eos");
            start(); emitPage(finalPage, !audioSent);
            try { sink.json(control(OUTPUT_STREAM)); }
            catch (IOException error) { throw error; }
            catch (Exception error) { throw new IOException("reply_schema_failed"); }
            finished = true; finalPage = null; prefix.clear(); tags.reset(); transcript = answer = null;
        }

        private void start() throws IOException {
            if (started) return;
            // Mark before the first write so partial metadata output is also an abortable reply.
            started = true;
            try { sendStart(meta, transcript, answer, sink, inputAlreadyFinished); }
            catch (IOException error) { throw error; }
            catch (Exception error) { throw new IOException("reply_schema_failed"); }
            for (byte[] page : prefix) emitPage(page, false);
            prefix.clear(); prefixBytes = 0;
        }
        private void emitPage(byte[] page, boolean completedAudio) throws IOException {
            byte[] frame = new byte[page.length + 4]; frame[3] = OUTPUT_STREAM;
            System.arraycopy(page, 0, frame, 4, page.length); sink.binary(frame);
            if (!audioSent && completedAudio) { audioSent = true; sink.audioPacketSent(); }
        }
        private static void validateTags(byte[] packet) throws IOException {
            if (packet.length < 16 || !ascii(packet, 0, "OpusTags")) throw new IOException("invalid_opus_tags");
            long vendor = little(packet, 8); long position = 12L + vendor;
            if (position > packet.length - 4) throw new IOException("invalid_opus_tags");
            long comments = little(packet, (int) position); position += 4;
            if (comments > 1024) throw new IOException("invalid_opus_tags");
            for (long i = 0; i < comments; i++) {
                if (position > packet.length - 4) throw new IOException("invalid_opus_tags");
                long size = little(packet, (int) position); position += 4L + size;
                if (position > packet.length) throw new IOException("invalid_opus_tags");
            }
        }
    }

    private static JSONObject asrDirective(LegacyVoiceSession.Metadata meta, String transcript) throws Exception {
        JSONObject result = directive("ASR", "Result", asr(transcript), meta.reference, 0);
        result.getJSONObject("directive").getJSONObject("header").put("eventId", meta.reference);
        return result;
    }
    /** Genuine interim recognition only; never closes input, reserves a reply, or emits an action. */
    static JSONObject partialAsr(LegacyVoiceSession.Metadata meta, String text, int number) throws IOException {
        if (meta == null || text == null || text.length() == 0 || text.length() > 1024
                || text.trim().length() == 0 || text.getBytes(StandardCharsets.UTF_8).length > 4096
                || number < 0 || number >= 16) throw new IOException("invalid_asr_progress");
        try {
            JSONObject result = asrDirective(meta, text);
            result.getJSONObject("directive").getJSONObject("payload")
                    .put("endOfUtt", false).put("continue_streaming", true)
                    .put("asr_partial_number", number).put("metainfo", new JSONObject());
            return result;
        } catch (Exception error) { throw new IOException("asr_progress_schema"); }
    }
    /** Same non-speaking fallback schema as the working PC send_reply error path. */
    static void sendWithoutSpeech(LegacyVoiceSession.Metadata meta, Sink sink) throws IOException {
        sendWithoutSpeech(meta, "", sink);
    }
    /** A validated local command can finish recognition without synthesizing speech. */
    static void sendWithoutSpeech(LegacyVoiceSession.Metadata meta, String transcript, Sink sink) throws IOException {
        sendWithoutSpeech(meta, transcript, sink, false);
    }
    static void sendWithoutSpeech(LegacyVoiceSession.Metadata meta, String transcript, Sink sink,
                                  boolean inputAlreadyFinished) throws IOException {
        validateQuietText(meta, transcript);
        try {
            if (!inputAlreadyFinished) sendInputFinal(meta, transcript, sink);
            if (meta.requiresSpotterValidation) sink.json(directive("Spotter", "Validation", new JSONObject()
                    .put("allow_activation_by_unvalidated_spotter", 0)
                    .put("multiactivation_id", System.currentTimeMillis() + "local")
                    .put("valid", 1).put("result", 1).put("canceled_cause_of_multiactivation", 0), meta.reference, 0));
            JSONObject payload = vins(meta, "Слушаю.");
            payload.getJSONObject("response").put("directives", new JSONArray());
            JSONObject voice = payload.getJSONObject("voice_response");
            voice.put("has_voice_response", false);
            voice.remove("output_speech");
            sink.json(directive("Vins", "VinsResponse", payload, meta.reference, 0));
        } catch (IOException error) { throw error; }
        catch (Exception invalid) { throw new IOException("reply_schema_failed"); }
    }
    static void validateQuietText(LegacyVoiceSession.Metadata meta, String transcript) throws IOException {
        if (meta == null || transcript == null || transcript.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT)
            throw new IOException("invalid_reply_text");
    }
    static void validateInputFinal(LegacyVoiceSession.Metadata meta, String transcript) throws IOException {
        validateQuietText(meta, transcript);
        if (transcript.trim().length() == 0) throw new IOException("empty_input_final");
    }
    static void sendInputFinal(LegacyVoiceSession.Metadata meta, String transcript, Sink sink) throws IOException {
        try { sink.json(asrDirective(meta, transcript)); sink.json(control(meta.streamId)); }
        catch (IOException failure) { throw failure; }
        catch (Exception failure) { throw new IOException("input_final_schema"); }
    }
    private static JSONObject asr(String text) throws Exception {
        JSONArray words = new JSONArray();
        if (!text.trim().isEmpty()) for (String word : text.trim().split("\\s+"))
            words.put(new JSONObject().put("value", word).put("confidence", 1));
        JSONArray recognition = new JSONArray().put(new JSONObject().put("normalized", text).put("words", words)
                .put("confidence", text.isEmpty() ? -0.001 : -0.5).put("parentModel", ""));
        return new JSONObject().put("recognition", recognition).put("messagesCount", 1)
                .put("metainfo", new JSONObject().put("version", "aqw").put("topic", "gigaam")
                        .put("serverHostname", "local").put("serverVersion", "1"))
                .put("responseCode", "OK").put("doNotSendToClient", false).put("asr_partial_number", 0)
                .put("endOfUtt", true).put("bioResult", new JSONArray()).put("contextRef", new JSONArray())
                .put("durationProcessedAudio", "0").put("whisperInfo", new JSONObject().put("isWhisper", false));
    }
    private static JSONObject vins(LegacyVoiceSession.Metadata meta, String text) throws Exception {
        String cardId = id();
        JSONObject card = new JSONObject().put("card_id", cardId).put("type", "simple_text").put("text", text);
        JSONArray directives = new JSONArray().put(new JSONObject().put("name", "tts_play_placeholder")
                .put("payload", new JSONObject().put("channel", "Dialog")).put("sub_name", "tts_play_placeholder")
                .put("type", "client_action").put("is_led_silent", true));
        return new JSONObject().put("version", "aqw").put("header", new JSONObject().put("response_id", id())
                .put("ref_message_id", meta.reference).put("request_id", meta.requestId)
                .put("sequence_number", meta.sequenceNumber).put("parent_request_id", meta.requestId)
                .put("session_id", meta.sessionId).put("dialog_id", JSONObject.NULL))
                .put("response", new JSONObject().put("experiments", new JSONObject())
                        .put("directives_execution_policy", "BeforeSpeech").put("directives", directives)
                        .put("card", card).put("cards", new JSONArray().put(card)))
                .put("voice_response", new JSONObject().put("has_voice_response", true)
                        .put("tts_voice_settings", new JSONObject().put("effective_language", "L_RUS")
                                .put("main_request_language", "L_RUS"))
                        .put("should_listen", false).put("output_speech", new JSONObject().put("type", "simple").put("text", text)));
    }
    private static JSONObject directive(String namespace, String name, JSONObject payload, String ref, long stream)
            throws Exception {
        JSONObject header = new JSONObject().put("namespace", namespace).put("name", name).put("messageId", id());
        if (ref != null) header.put("refMessageId", ref);
        if (stream != 0) header.put("streamId", stream);
        return new JSONObject().put("directive", new JSONObject().put("header", header).put("payload", payload));
    }
    private static JSONObject control(long stream) throws Exception {
        return new JSONObject().put("streamcontrol", new JSONObject().put("messageId", id()).put("reason", 0)
                .put("action", 0).put("streamId", stream));
    }
    private static String id() { return UUID.randomUUID().toString(); }
    private static boolean ascii(byte[] bytes, int offset, String text) {
        if (offset + text.length() > bytes.length) return false;
        for (int i = 0; i < text.length(); i++) if (bytes[offset + i] != text.charAt(i)) return false;
        return true;
    }
    private static long little(byte[] data, int offset) {
        return (data[offset] & 255L) | ((data[offset + 1] & 255L) << 8)
                | ((data[offset + 2] & 255L) << 16) | ((data[offset + 3] & 255L) << 24);
    }
    private static long little64(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) value |= (bytes[offset + i] & 255L) << (i * 8);
        return value;
    }
    private static int crc(byte[] data, int offset, int length) {
        int crc = 0;
        for (int i = 0; i < length; i++) {
            int value = i >= 22 && i < 26 ? 0 : data[offset + i] & 255;
            crc ^= value << 24;
            for (int j = 0; j < 8; j++) crc = (crc << 1) ^ (crc < 0 ? 0x04c11db7 : 0);
        }
        return crc;
    }
}
