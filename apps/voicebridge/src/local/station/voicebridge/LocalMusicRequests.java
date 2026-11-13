package local.station.voicebridge;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fixed endpoint request descriptions. Credentials are in POST bodies, never URL/toString. No IO. */
final class LocalMusicRequests {
    static final String DEFAULT_USER_AGENT = "KateMobileAndroid/56 lite-460 (Android 4.4.2; SDK 19; x86; unknown Android SDK built for x86; en)";
    private static final LocalMusicUrls ENDPOINTS = new LocalMusicUrls(Collections.emptySet());
    static final class Credentials {
        private final String token;
        final String userAgent, version;
        Credentials(String token, String userAgent, String version) {
            if (token == null || token.isEmpty() || token.length() > 4096 || controls(token)) throw invalid();
            if (userAgent == null || userAgent.isEmpty() || userAgent.length() > 512 || controls(userAgent)) throw invalid();
            if (version == null || !version.matches("[0-9]{1,3}\\.[0-9]{1,3}")) throw invalid();
            this.token = token; this.userAgent = userAgent; this.version = version;
        }
        @Override public String toString() { return "MusicCredentials{redacted}"; }
    }
    static final class Request {
        final URI endpoint;
        final Map<String, String> headers;
        private final byte[] body;
        private Request(URI endpoint, Map<String, String> headers, byte[] body) {
            this.endpoint = endpoint; this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
            this.body = body.clone();
        }
        byte[] body() { return body.clone(); }
        @Override public String toString() { return "MusicRequest{" + endpoint.getRawPath() + ",body:redacted}"; }
    }
    static Request library(Credentials credentials, int count, int offset) {
        return vk(credentials, "audio.get", paging(count, offset));
    }
    static Request search(Credentials credentials, String query, int count) {
        String text = query(query);
        if (text.isEmpty()) throw invalid();
        Map<String,String> fields = paging(count, 0); fields.remove("offset");
        fields.put("q", text); fields.put("sort", "2"); fields.put("autocomplete", "1");
        fields.put("lyrics", "0"); fields.put("performer_only", "0");
        return vk(credentials, "audio.search", fields);
    }
    static Request popular(Credentials credentials, int count, boolean recommendations) {
        Map<String,String> fields = paging(count, 0); fields.remove("offset");
        if (!recommendations) fields.put("only_original", "0");
        return vk(credentials, recommendations ? "audio.getRecommendations" : "audio.getPopular", fields);
    }
    static Request currentUser(Credentials credentials) { return vk(credentials, "users.get", Collections.emptyMap()); }
    static Request playlists(Credentials credentials, String owner, int count) {
        Map<String,String> fields = paging(count, 0); fields.remove("offset");
        fields.put("owner_id", positiveId(owner)); fields.put("extended", "1");
        return vk(credentials, "audio.getPlaylists", fields);
    }
    static Request playlist(Credentials credentials, String owner, String playlist, int count, int offset) {
        Map<String,String> fields = paging(count, offset);
        fields.put("owner_id", ownerId(owner)); fields.put("playlist_id", positiveId(playlist));
        return vk(credentials, "audio.get", fields);
    }
    static Request resolve(Credentials credentials, String key) {
        if (key == null || !key.matches("-?[1-9][0-9]{0,18}_[1-9][0-9]{0,18}")) throw invalid();
        return vk(credentials, "audio.getById", Collections.singletonMap("audios", key));
    }
    static URI muzofond(String query, boolean alternate) {
        String text = query(query);
        if (text.isEmpty()) return ENDPOINTS.page("https://muzofond.fm/popular");
        String encoded = encode(text).replace("+", "%20");
        return ENDPOINTS.page("https://muzofond.fm/" + (alternate ? "search?q=" : "search/") + encoded);
    }
    private static Request vk(Credentials credentials, String method, Map<String,String> fields) {
        if (credentials == null) throw invalid();
        URI endpoint = ENDPOINTS.api("https://api.vk.com/method/" + method);
        StringBuilder body = new StringBuilder("access_token=").append(encode(credentials.token))
                .append("&v=").append(encode(credentials.version));
        for (Map.Entry<String,String> field : fields.entrySet())
            body.append('&').append(encode(field.getKey())).append('=').append(encode(field.getValue()));
        Map<String,String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", credentials.userAgent); headers.put("Accept", "application/json");
        headers.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        return new Request(endpoint, headers, body.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static Map<String,String> paging(int count, int offset) {
        if (count < 1 || count > 200 || offset < 0 || offset > 100000) throw invalid();
        Map<String,String> fields = new LinkedHashMap<>();
        fields.put("count", Integer.toString(count)); fields.put("offset", Integer.toString(offset)); return fields;
    }
    static String positiveId(String text) {
        if (text == null || !text.matches("[1-9][0-9]{0,18}")) throw invalid(); return text;
    }
    static String ownerId(String text) {
        if (text == null || !text.matches("-?[1-9][0-9]{0,18}")) throw invalid(); return text;
    }
    private static String query(String text) {
        if (text == null || text.length() > 512 || controls(text)) throw invalid(); return text.trim();
    }
    private static boolean controls(String text) {
        for (int i=0;i<text.length();i++) if (Character.isISOControl(text.charAt(i))) return true; return false;
    }
    private static String encode(String text) {
        try { return URLEncoder.encode(text, "UTF-8"); } catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("music_request_rejected"); }
}
