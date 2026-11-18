package local.station.voicebridge;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;

/** Bounded blocking and incremental TTS calls. Use a worker; no playback or automatic retries. */
public final class SpeechifyClient {
    private static final String ENDPOINT = "https://api.speechify.ai/v1/audio/speech";
    private static final String STREAM_ENDPOINT = "https://api.speechify.ai/v1/audio/stream";
    private static final String MODEL = "simba-3.5";
    private static final String LANGUAGE = "ru-RU";
    private static final String MP3 = "mp3";
    private static final String OGG = "ogg";
    private static final String OGG_OUTPUT = "ogg_24000";
    static final String VOICE_GAIN_KEY = "speechify_gain_db";
    static final int DEFAULT_VOICE_GAIN_DB = 4;
    static final int MIN_VOICE_GAIN_DB = -6, MAX_VOICE_GAIN_DB = 6;
    private static final int MAX_INPUT_CODEPOINTS = 2000;
    private static final int MAX_AUDIO_BYTES = 4 * 1024 * 1024;
    private static final int MAX_JSON_BYTES = 6 * 1024 * 1024;
    private static final int MAX_ERROR_BYTES = 16384;
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int TOTAL_TIMEOUT_MS = 90000;
    private static final Semaphore SLOT = new Semaphore(1);
    private static final ScheduledExecutorService DEADLINES =
            Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "speechify-deadline");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    /** Package-private transport seam; production never accepts an endpoint or TLS override. */
    interface ConnectionFactory {
        HttpsURLConnection open(URL address, Proxy proxy) throws IOException;
    }

    /** Package-private timer seam for deterministic late-callback lifecycle checks. */
    interface Timers {
        ScheduledFuture<?> once(Runnable action, long delayMillis);
        ScheduledFuture<?> repeating(Runnable action, long delayMillis, long periodMillis);
    }
    private static final Timers DEFAULT_TIMERS = new Timers() {
        @Override public ScheduledFuture<?> once(Runnable action, long delayMillis) {
            return DEADLINES.schedule(action, delayMillis, TimeUnit.MILLISECONDS);
        }
        @Override public ScheduledFuture<?> repeating(Runnable action, long delayMillis, long periodMillis) {
            return DEADLINES.scheduleAtFixedRate(action, delayMillis, periodMillis, TimeUnit.MILLISECONDS);
        }
    };

    private final ConnectionFactory connections;
    private final Timers timers;

    /** No network activity or credential reads occur here. Uses platform TLS and hostname checks. */
    public SpeechifyClient() {
        this(new ConnectionFactory() {
            @Override public HttpsURLConnection open(URL address, Proxy proxy) throws IOException {
                return (HttpsURLConnection) address.openConnection(proxy);
            }
        });
    }

    SpeechifyClient(ConnectionFactory connections) {
        this(connections, DEFAULT_TIMERS);
    }

    SpeechifyClient(ConnectionFactory connections, Timers timers) {
        if (connections == null || timers == null) throw new IllegalArgumentException("Client dependencies required");
        this.connections = connections;
        this.timers = timers;
    }

    /** Configuration validation only; does not contact the service or verify its quota. */
    public void requireSynthesisConfigured(JSONObject config) throws SpeechifyException {
        key(config);
        voice(config);
        proxy(config);
    }

    /** One bounded synthesis. The caller owns the returned MP3 and decides whether to play it. */
    public Result synthesize(JSONObject config, String text) throws SpeechifyException {
        return synthesize(config, text, MP3);
    }

    /** One bounded Ogg/Opus trial at the explicit API format; no playback or container remuxing. */
    public Result synthesizeOgg(JSONObject config, String text) throws SpeechifyException {
        return synthesize(config, text, OGG);
    }

    /** One complete CRC-valid Ogg page per callback, on the calling worker. No callback after cancellation. */
    public interface AudioSink {
        void write(byte[] bytes, int offset, int count) throws IOException;
        boolean isCancelled();
    }

    /**
     * One raw Ogg/Opus stream. Keeps at most one page and bounded tags, never the whole clip.
     * A successful return confirms EOS; callers must abort partial output on any exception.
     */
    public StreamResult streamOgg(JSONObject config, String text, final AudioSink sink) throws SpeechifyException {
        String apiKey = key(config);
        String voiceId = voice(config);
        Proxy selectedProxy = proxy(config);
        validateInput(text);
        if (sink == null) throw failure("INVALID_INPUT");
        final byte[] body;
        try {
            body = new JSONObject().put("input", voiceInput(config, text)).put("voice_id", voiceId)
                    .put("model", MODEL).put("language", LANGUAGE).put("output_format", OGG_OUTPUT)
                    .toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception ignored) { throw failure("INVALID_INPUT"); }
        HttpsURLConnection connection = null;
        InputStream input = null;
        ScheduledFuture<?> monitor = null;
        final AtomicBoolean expired = new AtomicBoolean(false), cancelled = new AtomicBoolean(false);
        final AtomicBoolean completed = new AtomicBoolean(false);
        final Object lifecycle = new Object();
        final long started = System.nanoTime();
        boolean acquired = false;
        try {
            checkStreaming(expired, cancelled, sink);
            if (!SLOT.tryAcquire()) throw failure("BUSY");
            acquired = true;
            connection = connections.open(new URL(STREAM_ENDPOINT), selectedProxy);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setAllowUserInteraction(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "audio/ogg");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "StationVoiceBridge/SpeechifyStream");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            final HttpsURLConnection active = connection;
            // Cancellation also interrupts a blocked provider read, rather than waiting for its timeout.
            monitor = timers.repeating(new Runnable() {
                @Override public void run() {
                    synchronized (lifecycle) {
                        if (completed.get()) return;
                        boolean stop = false;
                        if (elapsed(started) >= TOTAL_TIMEOUT_MS) { expired.set(true); stop = true; }
                        try { if (sink.isCancelled()) { cancelled.set(true); stop = true; } }
                        catch (RuntimeException ignored) { cancelled.set(true); stop = true; }
                        if (stop) active.disconnect();
                    }
                }
            }, 100, 100);
            OutputStream output = connection.getOutputStream();
            try { output.write(body); }
            finally { try { output.close(); } catch (Exception ignored) { } }
            checkStreaming(expired, cancelled, sink);
            int status = connection.getResponseCode();
            checkStreaming(expired, cancelled, sink);
            if (status >= 300 && status < 400) throw new SpeechifyException("REDIRECT_BLOCKED", status, -1);
            if (status != 200) {
                String providerCode = "";
                try {
                    JSONObject envelope = parse(read(connection.getErrorStream(), MAX_ERROR_BYTES, expired));
                    JSONObject error = envelope.optJSONObject("error");
                    Object raw = error == null ? null : error.opt("code");
                    if (raw instanceof String) providerCode = (String) raw;
                } catch (SpeechifyException ignored) { checkStreaming(expired, cancelled, sink); }
                throw httpFailure(status, providerCode, connection.getHeaderField("Retry-After"));
            }
            String type = connection.getContentType();
            if (type == null || !"audio/ogg".equalsIgnoreCase(type.split(";", 2)[0].trim()))
                throw failure("BAD_RESPONSE");
            if (connection.getContentLengthLong() > MAX_AUDIO_BYTES) throw failure("RESPONSE_TOO_LARGE");
            input = connection.getInputStream();
            if (input == null) throw failure("BAD_RESPONSE");
            StreamReader reader = new StreamReader(input, sink, expired, cancelled, started);
            reader.run();
            checkStreaming(expired, cancelled, sink);
            return new StreamResult(reader.bytes, reader.pages, reader.firstByteMs, reader.firstAudioMs,
                    elapsed(started), text.codePointCount(0, text.length()), voiceId);
        } catch (SpeechifyException error) { throw error; }
        catch (SocketTimeoutException ignored) { checkStreaming(expired, cancelled, sink); throw failure("TIMEOUT"); }
        catch (SSLException ignored) { checkStreaming(expired, cancelled, sink); throw failure("TLS_ERROR"); }
        catch (Exception ignored) { checkStreaming(expired, cancelled, sink); throw failure("NETWORK_ERROR"); }
        finally {
            // cancel(false) alone cannot stop an already dequeued monitor. Serialize the
            // completed marker with disconnect so an old callback cannot touch a pooled socket.
            synchronized (lifecycle) { completed.set(true); }
            if (monitor != null) monitor.cancel(false);
            if (input != null) try { input.close(); } catch (Exception ignored) { }
            if (connection != null) try { connection.disconnect(); } catch (Exception ignored) { }
            if (acquired) SLOT.release();
        }
    }

    private static long elapsed(long started) { return Math.max(0, (System.nanoTime() - started) / 1000000L); }

    private static void checkStreaming(AtomicBoolean expired, AtomicBoolean cancelled, AudioSink sink)
            throws SpeechifyException {
        checkRunning(expired);
        boolean stop;
        try { stop = sink.isCancelled(); }
        catch (RuntimeException ignored) { stop = true; }
        if (cancelled.get() || stop) { cancelled.set(true); throw failure("CANCELLED"); }
    }

    /** Strict single-stream Opus framing. HTTP boundaries need not coincide with Ogg pages. */
    private static final class StreamReader {
        private static final int MAX_PAGE_BYTES = 65307, MAX_TAG_BYTES = 65536, MAX_PAGES = 4096;
        private static final int[] CRC_TABLE = crcTable();
        final InputStream input;
        final AudioSink sink;
        final AtomicBoolean expired, cancelled;
        final long started;
        int bytes, pages, audioPackets, packetBytes;
        long serial, nextSequence, lastGranule, firstByteMs = -1, firstAudioMs = -1;
        boolean packetOpen, tagsComplete, eos;
        ByteArrayOutputStream tags = new ByteArrayOutputStream();
        StreamReader(InputStream input, AudioSink sink, AtomicBoolean expired, AtomicBoolean cancelled, long started) {
            this.input = input; this.sink = sink; this.expired = expired; this.cancelled = cancelled; this.started = started;
        }
        void run() throws IOException {
            while (true) {
                byte[] header = new byte[27];
                if (!readFully(header, 0, header.length, true)) {
                    if (!eos || !tagsComplete || packetOpen || audioPackets == 0) throw failure("BAD_RESPONSE");
                    return;
                }
                if (eos || pages == MAX_PAGES || !ascii(header, 0, "OggS") || header[4] != 0)
                    throw failure("BAD_RESPONSE");
                int count = header[26] & 255, flags = header[5] & 255;
                if (count == 0 || (flags & ~7) != 0) throw failure("BAD_RESPONSE");
                byte[] lacing = new byte[count];
                readFully(lacing, 0, count, false);
                int size = 27 + count;
                for (byte lace : lacing) size += lace & 255;
                if (size > MAX_PAGE_BYTES || size > MAX_AUDIO_BYTES - bytes) throw failure("RESPONSE_TOO_LARGE");
                byte[] page = new byte[size];
                System.arraycopy(header, 0, page, 0, 27);
                System.arraycopy(lacing, 0, page, 27, count);
                readFully(page, 27 + count, size - 27 - count, false);
                validate(page, flags, count);
                bytes += size; pages++;
                checkStreaming(expired, cancelled, sink);
                try { sink.write(page, 0, page.length); }
                catch (IOException ignored) { cancelled.set(true); throw failure("CANCELLED"); }
                catch (RuntimeException ignored) { cancelled.set(true); throw failure("CANCELLED"); }
                checkStreaming(expired, cancelled, sink);
            }
        }
        boolean readFully(byte[] destination, int offset, int length, boolean eofAllowed) throws IOException {
            int done = 0;
            while (done < length) {
                checkStreaming(expired, cancelled, sink);
                int read = input.read(destination, offset + done, Math.min(8192, length - done));
                checkStreaming(expired, cancelled, sink);
                if (read < 0) {
                    if (eofAllowed && done == 0) return false;
                    throw failure("BAD_RESPONSE");
                }
                if (read == 0) continue;
                if (firstByteMs < 0) firstByteMs = elapsed(started);
                done += read;
            }
            return true;
        }
        void validate(byte[] page, int flags, int count) throws SpeechifyException {
            if ((crc(page) & 0xffffffffL) != little(page, 22)) throw failure("BAD_RESPONSE");
            long pageSerial = little(page, 14), sequence = little(page, 18), granule = granule(page);
            if (granule < -1) throw failure("BAD_RESPONSE");
            int body = 27 + count;
            if (pages == 0) {
                if (flags != 2 || sequence != 0 || granule != 0 || count != 1 || (page[27] & 255) != 19
                        || !ascii(page, body, "OpusHead") || (page[body + 8] & 255) != 1
                        || (page[body + 9] & 255) != 1 || page[body + 18] != 0)
                    throw failure("BAD_RESPONSE");
                serial = pageSerial; nextSequence = 1;
                return;
            }
            if ((flags & 2) != 0 || pageSerial != serial || sequence != nextSequence
                    || ((flags & 1) != 0) != packetOpen) throw failure("BAD_RESPONSE");
            nextSequence = (nextSequence + 1) & 0xffffffffL;
            boolean tagsPage = !tagsComplete;
            int complete = 0;
            for (int i = 0; i < count; i++) {
                int size = page[27 + i] & 255;
                packetBytes += size;
                if (tagsPage) {
                    if (packetBytes > MAX_TAG_BYTES) throw failure("BAD_RESPONSE");
                    tags.write(page, body, size);
                }
                body += size; packetOpen = size == 255;
                if (!packetOpen) {
                    if (packetBytes == 0) throw failure("BAD_RESPONSE");
                    complete++;
                    if (tagsPage) {
                        if (i != count - 1) throw failure("BAD_RESPONSE");
                        validateTags(tags.toByteArray()); tags = null; tagsComplete = true;
                    } else audioPackets++;
                    packetBytes = 0;
                }
            }
            if (tagsPage) {
                if (granule != (complete == 0 ? -1 : 0)) throw failure("BAD_RESPONSE");
            } else if (complete == 0) {
                if (granule != -1) throw failure("BAD_RESPONSE");
            } else {
                if (granule < 0 || granule < lastGranule) throw failure("BAD_RESPONSE");
                lastGranule = granule;
                if (firstAudioMs < 0) firstAudioMs = elapsed(started);
            }
            eos = (flags & 4) != 0;
            if (eos && (packetOpen || tagsPage || complete == 0)) throw failure("BAD_RESPONSE");
        }
        private static void validateTags(byte[] data) throws SpeechifyException {
            if (data.length < 16 || !ascii(data, 0, "OpusTags")) throw failure("BAD_RESPONSE");
            long vendor = little(data, 8);
            if (vendor > data.length - 16) throw failure("BAD_RESPONSE");
            int offset = 12 + (int) vendor;
            long count = little(data, offset); offset += 4;
            if (count > (data.length - offset) / 4) throw failure("BAD_RESPONSE");
            for (long i = 0; i < count; i++) {
                if (data.length - offset < 4) throw failure("BAD_RESPONSE");
                long length = little(data, offset); offset += 4;
                if (length > data.length - offset) throw failure("BAD_RESPONSE");
                offset += (int) length;
            }
        }
        private static boolean ascii(byte[] data, int offset, String text) {
            if (data.length - offset < text.length()) return false;
            for (int i = 0; i < text.length(); i++) if (data[offset + i] != text.charAt(i)) return false;
            return true;
        }
        private static long little(byte[] data, int offset) {
            return (data[offset] & 255L) | ((data[offset + 1] & 255L) << 8)
                    | ((data[offset + 2] & 255L) << 16) | ((data[offset + 3] & 255L) << 24);
        }
        private static long granule(byte[] page) {
            long result = 0;
            for (int i = 7; i >= 0; i--) result = (result << 8) | (page[6 + i] & 255L);
            return result;
        }
        private static int crc(byte[] page) {
            int result = 0;
            for (int i = 0; i < page.length; i++) {
                int value = i >= 22 && i < 26 ? 0 : page[i] & 255;
                result = (result << 8) ^ CRC_TABLE[((result >>> 24) ^ value) & 255];
            }
            return result;
        }
        private static int[] crcTable() {
            int[] table = new int[256];
            for (int i = 0; i < 256; i++) {
                int value = i << 24;
                for (int j = 0; j < 8; j++) value = (value << 1) ^ (value < 0 ? 0x04c11db7 : 0);
                table[i] = value;
            }
            return table;
        }
    }

    private Result synthesize(JSONObject config, String text, String format) throws SpeechifyException {
        String apiKey = key(config);
        String voiceId = voice(config);
        Proxy selectedProxy = proxy(config);
        validateInput(text);
        byte[] body;
        try {
            JSONObject request = new JSONObject().put("input", OGG.equals(format) ? voiceInput(config, text) : text)
                    .put("voice_id", voiceId)
                    .put("model", MODEL).put("language", LANGUAGE).put("audio_format", format);
            if (OGG.equals(format)) request.put("output_format", OGG_OUTPUT);
            body = request.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            throw failure("INVALID_INPUT");
        }
        return decode(request(apiKey, selectedProxy, body), voiceId, format);
    }

    private byte[] request(String apiKey, Proxy proxy, byte[] body) throws SpeechifyException {
        HttpsURLConnection connection = null;
        ScheduledFuture<?> deadline = null;
        AtomicBoolean expired = new AtomicBoolean(false);
        final AtomicBoolean completed = new AtomicBoolean(false);
        final Object lifecycle = new Object();
        boolean acquired = false;
        try {
            checkRunning(expired);
            if (!SLOT.tryAcquire()) throw failure("BUSY");
            acquired = true;
            connection = connections.open(new URL(ENDPOINT), proxy);
            // Deliberately do not replace the default SSLSocketFactory or HostnameVerifier.
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setAllowUserInteraction(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Connection", "close");
            connection.setRequestProperty("User-Agent", "StationVoiceBridge/SpeechifyLab");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            final HttpsURLConnection active = connection;
            deadline = timers.once(new Runnable() {
                @Override public void run() {
                    synchronized (lifecycle) {
                        if (completed.get()) return;
                        expired.set(true);
                        active.disconnect();
                    }
                }
            }, TOTAL_TIMEOUT_MS);
            OutputStream output = connection.getOutputStream();
            try { output.write(body); }
            finally { try { output.close(); } catch (Exception ignored) { } }
            checkRunning(expired);
            int status = connection.getResponseCode();
            checkRunning(expired);
            if (status >= 300 && status < 400) {
                throw new SpeechifyException("REDIRECT_BLOCKED", status, -1);
            }
            if (status != 200) {
                // Inspect only a bounded error code; never expose message, fields, URLs or body.
                String providerCode = "";
                try {
                    byte[] errorBody = read(connection.getErrorStream(), MAX_ERROR_BYTES, expired);
                    JSONObject envelope = parse(errorBody);
                    JSONObject error = envelope.optJSONObject("error");
                    Object raw = error == null ? null : error.opt("code");
                    if (raw instanceof String) providerCode = (String) raw;
                } catch (SpeechifyException ignored) {
                    checkRunning(expired);
                }
                throw httpFailure(status, providerCode, connection.getHeaderField("Retry-After"));
            }
            String contentType = connection.getContentType();
            if (contentType == null || !"application/json".equalsIgnoreCase(contentType.split(";", 2)[0].trim())) {
                throw failure("BAD_RESPONSE");
            }
            if (connection.getContentLengthLong() > MAX_JSON_BYTES) throw failure("RESPONSE_TOO_LARGE");
            byte[] response = read(connection.getInputStream(), MAX_JSON_BYTES, expired);
            checkRunning(expired);
            return response;
        } catch (SpeechifyException e) {
            throw e;
        } catch (SocketTimeoutException ignored) {
            throw failure("TIMEOUT");
        } catch (SSLException ignored) {
            throw failure("TLS_ERROR");
        } catch (Exception ignored) {
            checkRunning(expired);
            throw failure("NETWORK_ERROR");
        } finally {
            synchronized (lifecycle) { completed.set(true); }
            if (deadline != null) deadline.cancel(false);
            if (connection != null) {
                try { connection.disconnect(); } catch (Exception ignored) { }
            }
            if (acquired) SLOT.release();
        }
    }

    private static byte[] read(InputStream input, int maximum, AtomicBoolean expired)
            throws SpeechifyException {
        if (input == null) throw failure("BAD_RESPONSE");
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkRunning(expired);
                if (count > maximum - output.size()) throw failure("RESPONSE_TOO_LARGE");
                output.write(buffer, 0, count);
            }
            checkRunning(expired);
            return output.toByteArray();
        } catch (SpeechifyException e) {
            throw e;
        } catch (SocketTimeoutException ignored) {
            throw failure("TIMEOUT");
        } catch (SSLException ignored) {
            throw failure("TLS_ERROR");
        } catch (Exception ignored) {
            checkRunning(expired);
            throw failure("NETWORK_ERROR");
        } finally {
            try { input.close(); } catch (Exception ignored) { }
        }
    }

    private static Result decode(byte[] bytes, String voiceId, String expectedFormat) throws SpeechifyException {
        JSONObject object = parse(bytes);
        Object format = object.opt("audio_format");
        Object payload = object.opt("audio_data");
        Object billable = object.opt("billable_characters_count");
        if (!expectedFormat.equals(format) || !(payload instanceof String)
                || !(billable instanceof Integer || billable instanceof Long)) throw failure("BAD_RESPONSE");
        long count = ((Number) billable).longValue();
        if (count < 0 || count > MAX_INPUT_CODEPOINTS) throw failure("BAD_RESPONSE");
        String base64 = (String) payload;
        if (base64.isEmpty() || base64.length() > ((MAX_AUDIO_BYTES + 2) / 3) * 4) {
            throw failure(base64.isEmpty() ? "BAD_RESPONSE" : "RESPONSE_TOO_LARGE");
        }
        // A complete Base64 payload only. The decoder rejects punctuation and whitespace.
        if (base64.length() % 4 != 0) throw failure("BAD_RESPONSE");
        byte[] audio;
        try { audio = Base64.getDecoder().decode(base64); }
        catch (IllegalArgumentException ignored) { throw failure("BAD_RESPONSE"); }
        if (audio.length > MAX_AUDIO_BYTES) throw failure("RESPONSE_TOO_LARGE");
        // Container sanity checks only; Ogg page CRCs, duration and complete decode need ffprobe.
        if (OGG.equals(expectedFormat)) {
            if (!hasOpusHeader(audio)) throw failure("BAD_RESPONSE");
        } else if (audio.length < 4 || !(audio[0] == 'I' && audio[1] == 'D' && audio[2] == '3'
                || (audio[0] & 0xff) == 0xff && (audio[1] & 0xe0) == 0xe0)) {
            throw failure("BAD_RESPONSE");
        }
        return new Result(audio, (int) count, voiceId, expectedFormat);
    }

    private static boolean hasOpusHeader(byte[] audio) {
        if (audio.length < 47 || audio[0] != 'O' || audio[1] != 'g' || audio[2] != 'g'
                || audio[3] != 'S' || audio[4] != 0) return false;
        int segments = audio[26] & 0xff;
        int firstPacket = 27 + segments;
        if (segments == 0 || firstPacket + 19 > audio.length || (audio[27] & 0xff) < 19) return false;
        byte[] signature = new byte[]{'O','p','u','s','H','e','a','d'};
        for (int i = 0; i < signature.length; i++) {
            if (audio[firstPacket + i] != signature[i]) return false;
        }
        int version = audio[firstPacket + 8] & 0xff;
        return version >= 1 && version <= 15 && audio[firstPacket + 9] != 0;
    }

    private static JSONObject parse(byte[] bytes) throws SpeechifyException {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            JSONTokener tokener = new JSONTokener(text);
            Object object = tokener.nextValue();
            if (!(object instanceof JSONObject) || tokener.nextClean() != 0) throw failure("BAD_RESPONSE");
            return (JSONObject) object;
        } catch (Exception ignored) { throw failure("BAD_RESPONSE"); }
    }

    private static String key(JSONObject config) throws SpeechifyException {
        if (config == null) throw failure("INVALID_CONFIG");
        Object raw = config.opt("speechify_api_key");
        if (raw == null || raw == JSONObject.NULL || raw instanceof String && ((String) raw).trim().isEmpty()) {
            throw failure("MISSING_KEY");
        }
        if (!(raw instanceof String)) throw failure("INVALID_CONFIG");
        String value = ((String) raw).trim();
        if (value.length() > 4096) throw failure("INVALID_KEY_FORMAT");
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < 33 || value.charAt(i) > 126) throw failure("INVALID_KEY_FORMAT");
        }
        return value;
    }

    private static String voice(JSONObject config) throws SpeechifyException {
        Object raw = config.opt("speechify_voice_id");
        if (raw == null || raw == JSONObject.NULL) return "vasilisa";
        if (!(raw instanceof String) || !((String) raw).matches("[A-Za-z0-9_-]{1,128}")) {
            throw failure("INVALID_VOICE_ID");
        }
        return (String) raw;
    }

    private static Proxy proxy(JSONObject config) throws SpeechifyException {
        Object raw = config.opt("lab_proxy_port");
        if (raw == null || raw == JSONObject.NULL) return Proxy.NO_PROXY;
        String value = String.valueOf(raw);
        if (!value.matches("[0-9]{1,5}")) throw failure("INVALID_PROXY_PORT");
        int port = Integer.parseInt(value);
        if (port == 0) return Proxy.NO_PROXY;
        if (port > 65535) throw failure("INVALID_PROXY_PORT");
        return new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", port));
    }

    private static void validateInput(String text) throws SpeechifyException {
        if (text == null || text.trim().isEmpty() || text.codePointCount(0, text.length()) > MAX_INPUT_CODEPOINTS) {
            throw failure("INVALID_INPUT");
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) throw failure("INVALID_INPUT");
            } else if (Character.isLowSurrogate(c) || c < 32 && c != '\n' && c != '\r' && c != '\t') {
                throw failure("INVALID_INPUT");
            }
        }
    }

    static int voiceGainDb(JSONObject config) {
        Object raw = config.opt(VOICE_GAIN_KEY);
        if (raw instanceof Integer || raw instanceof Long) {
            long number = ((Number) raw).longValue();
            if (number >= MIN_VOICE_GAIN_DB && number <= MAX_VOICE_GAIN_DB) return (int) number;
        }
        return DEFAULT_VOICE_GAIN_DB;
    }

    private static String voiceInput(JSONObject config, String text) {
        int gain = voiceGainDb(config);
        String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        if (gain == 0) return "<speak>" + escaped + "</speak>";
        return "<speak><prosody volume=\"" + (gain > 0 ? "+" : "") + gain + "dB\">"
                + escaped + "</prosody></speak>";
    }

    private static SpeechifyException httpFailure(int status, String providerCode, String retryAfter) {
        String code = "HTTP_ERROR";
        if (status == 400 || status == 413) code = "REQUEST_REJECTED";
        else if (status == 401) code = "AUTHENTICATION_FAILED";
        else if (status == 403) code = "ACCESS_DENIED";
        else if (status == 402) code = "QUOTA_OR_BILLING_LIMIT";
        else if (status == 429) code = "RATE_LIMITED";
        else if (status >= 500) code = "SERVICE_UNAVAILABLE";
        if (status == 429 && "concurrency_limit_reached".equals(providerCode)) code = "CONCURRENCY_LIMIT";
        if (status == 402 && ("spend_cap_exceeded".equals(providerCode)
                || "spend_budget_exceeded".equals(providerCode)
                || "project_spend_limit_exceeded".equals(providerCode))) code = "SPEND_LIMIT";
        int seconds = -1;
        if (retryAfter != null && retryAfter.matches("[0-9]{1,5}")) {
            int candidate = Integer.parseInt(retryAfter);
            if (candidate <= 86400) seconds = candidate;
        }
        return new SpeechifyException(code, status, seconds);
    }

    private static void checkRunning(AtomicBoolean expired) throws SpeechifyException {
        if (expired.get()) throw failure("TIMEOUT");
        if (Thread.currentThread().isInterrupted()) throw failure("CANCELLED");
    }

    private static SpeechifyException failure(String code) { return new SpeechifyException(code, 0, -1); }

    /** Numerical stream metadata only. Raw streaming does not report billable character usage. */
    public static final class StreamResult {
        public final int audioBytes, pages, inputCharacters;
        public final long firstByteMs, firstAudioMs, elapsedMs;
        public final int billableCharacters = -1;
        public final String audioFormat = OGG, model = MODEL, language = LANGUAGE, voiceId;
        private StreamResult(int audioBytes, int pages, long firstByteMs, long firstAudioMs,
                             long elapsedMs, int inputCharacters, String voiceId) {
            this.audioBytes = audioBytes; this.pages = pages; this.firstByteMs = firstByteMs;
            this.firstAudioMs = firstAudioMs; this.elapsedMs = elapsedMs;
            this.inputCharacters = inputCharacters; this.voiceId = voiceId;
        }
    }

    public static final class Result {
        public final byte[] audio;
        public final int billableCharacters;
        public final String model = MODEL;
        public final String voiceId;
        public final String language = LANGUAGE;
        public final String audioFormat;

        private Result(byte[] audio, int billableCharacters, String voiceId, String audioFormat) {
            this.audio = audio;
            this.billableCharacters = billableCharacters;
            this.voiceId = voiceId;
            this.audioFormat = audioFormat;
        }
    }

    /** Safe for UI/reporting: no provider body, credentials, URL, input text or underlying cause. */
    public static final class SpeechifyException extends IOException {
        private static final long serialVersionUID = 1L;
        public final String provider = "speechify";
        public final String code;
        public final int httpStatus;
        public final int retryAfterSeconds;

        private SpeechifyException(String code, int httpStatus, int retryAfterSeconds) {
            super("speechify: " + code + (httpStatus == 0 ? "" : " (HTTP " + httpStatus + ")"));
            this.code = code;
            this.httpStatus = httpStatus;
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }
}
