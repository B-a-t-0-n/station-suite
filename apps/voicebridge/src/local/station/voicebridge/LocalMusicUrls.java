package local.station.voicebridge;

import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Pure policy for future on-device downloads. No DNS, sockets, or automatic CDN enrollment. */
final class LocalMusicUrls {
    enum Source { VK, MUZOFOND }
    static final int MAX_URL_CHARS = 8192;
    private final Set<String> enrolledAudioHosts;

    LocalMusicUrls(Set<String> hosts) {
        if (hosts == null || hosts.size() > 64) throw invalid();
        HashSet<String> copy = new HashSet<>();
        for (String host : hosts) {
            if (!canonicalHost(host) || !knownAudioHost(host)) throw invalid();
            copy.add(host);
        }
        enrolledAudioHosts = Collections.unmodifiableSet(copy);
    }

    URI api(String address) {
        URI uri = https(address);
        String path = uri.getRawPath();
        if (!"api.vk.com".equals(lower(uri.getHost())) || uri.getRawQuery()!=null || path == null
                || !path.matches("/method/(?:audio\\.(?:search|get|getById|getPopular|getRecommendations|getPlaylists)|users\\.get)"))
            throw invalid();
        return uri;
    }

    URI page(String address) {
        URI uri = https(address);
        String path = uri.getRawPath();
        if (!"muzofond.fm".equals(lower(uri.getHost())) || path == null
                || !("/popular".equals(path) || "/search".equals(path) || path.startsWith("/search/")))
            throw invalid();
        return uri;
    }

    URI audio(Source source, String address) {
        if (source == null) throw invalid();
        URI uri = https(address);
        String host = lower(uri.getHost());
        if (!enrolledAudioHosts.contains(host)
                || source == Source.VK && !host.endsWith(".vkuseraudio.net")
                || source == Source.MUZOFOND && !host.endsWith(".muzofond.fm")) throw invalid();
        if (uri.getRawPath() == null || uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath())) throw invalid();
        return uri;
    }

    /** Returns a syntactically safe candidate hostname, never permission to open it. */
    static String candidateAudioHost(Source source, String address) {
        if (source == null) throw invalid();
        String host = lower(https(address).getHost());
        if (source == Source.VK && host.endsWith(".vkuseraudio.net")
                || source == Source.MUZOFOND && host.endsWith(".muzofond.fm")) return host;
        throw invalid();
    }

    private static URI https(String address) {
        if (address == null || address.isEmpty() || address.length() > MAX_URL_CHARS) throw invalid();
        for (int i = 0; i < address.length(); i++) {
            char c = address.charAt(i);
            if (c <= 32 || c >= 127 || c == '\\') throw invalid();
        }
        try {
            URI uri = new URI(address);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null || uri.getHost() == null
                    || uri.getPort() != -1 && uri.getPort() != 443 || !canonicalHost(lower(uri.getHost()))) throw invalid();
            return uri;
        } catch (java.net.URISyntaxException bad) { throw invalid(); }
    }

    private static boolean knownAudioHost(String host) {
        return host.endsWith(".vkuseraudio.net") || host.endsWith(".muzofond.fm");
    }
    private static boolean canonicalHost(String host) {
        if (host == null || host.length() > 253 || !host.equals(lower(host)) || host.matches("[0-9.]+")) return false;
        return host.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+");
    }
    private static String lower(String text) { return text == null ? "" : text.toLowerCase(Locale.ROOT); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("music_endpoint_rejected"); }
}
