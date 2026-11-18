package local.station.voicebridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;
import javax.xml.parsers.DocumentBuilderFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.helpers.DefaultHandler;

/** Bounded public web search. Only results with a known provider's content URL leave this class. */
final class TvVideoSearch {
    private static final int MAX_BYTES = 192 * 1024;
    private static final int MAX_OKKO_HEAD = 640 * 1024;
    private static final byte[] HEAD_END = "</head>".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern OKKO_PAGE = Pattern.compile("/(movie|serial)/[a-z0-9][a-z0-9-]{0,159}/?");
    private static final Pattern META = Pattern.compile("<meta\\s+[^>]{0,2048}>", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_ATTR = Pattern.compile(
            "(?:^|\\s)(property|content)\\s*=\\s*(['\"])(.*?)\\2", Pattern.CASE_INSENSITIVE);
    private static final Pattern OKKO_LINK = Pattern.compile(
            "okko://(movie|serial|series)\\?uid=([a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12})"
                    + "&type=(movie|serial|series)(?:&[a-zA-Z]+=[a-zA-Z0-9_-]{1,60}){0,5}");
    private static final Pattern VK_PAGE = Pattern.compile("/video(-?\\d{1,12}_\\d{1,12})/?");
    private static final Pattern RUTUBE_PAGE = Pattern.compile("/video/([0-9a-f]{32})/?");
    private static final Pattern YOUTUBE_ID = Pattern.compile("[a-zA-Z0-9_-]{11}");
    private static final Pattern YEAR = Pattern.compile("\\b(?:19|20)\\d{2}\\b");
    private static final Pattern SHORT_CONTENT = Pattern.compile(
            "(?:^|[^\\p{L}\\p{N}])(?:трейлер|тизер|обзор|нарезк|реакция|фрагмент|trailer|teaser|review)");

    static final class Hit {
        final TvVoiceCommand.Provider provider;
        final String page, target, displayTitle;
        final int score, year;

        Hit(TvVoiceCommand.Provider provider, String page, String target, String displayTitle, int score, int year) {
            this.provider = provider;
            this.page = page;
            this.target = target;
            this.displayTitle = displayTitle;
            this.score = score;
            this.year = year;
        }
    }

    static String domain(TvVoiceCommand.Provider provider) {
        switch (provider) {
            case OKKO: return "okko.tv";
            case VK: return "vkvideo.ru";
            case RUTUBE: return "rutube.ru";
            case YOUTUBE: return "youtube.com";
            default: return null;
        }
    }

    static List<Hit> search(TvVoiceCommand.Provider provider, String title, String mediaType, int year,
                            BooleanSupplier cancelled) throws Exception {
        String domain = domain(provider);
        if (domain == null || cancelled.getAsBoolean()) return new ArrayList<>();
        long started = System.nanoTime();
        if (provider == TvVoiceCommand.Provider.RUTUBE) {
            try {
                List<Hit> direct = rutube(title, mediaType, year, cancelled);
                if (!direct.isEmpty() || cancelled.getAsBoolean()) return direct;
            } catch (Exception unavailable) {
                // The public catalogue may be unavailable. Search the same provider's indexed pages.
            }
        }
        boolean unspecifiedOkko = provider == TvVoiceCommand.Provider.OKKO && mediaType == null;
        String hint = "film".equals(mediaType) || unspecifiedOkko ? " фильм"
                : "series".equals(mediaType) ? " сериал" : "";
        List<Hit> hits = indexedSearch(provider, title, mediaType, year, hint, cancelled);
        if (unspecifiedOkko && hits.isEmpty())
            hits = indexedSearch(provider, title, mediaType, year, " сериал", cancelled);
        if (provider != TvVoiceCommand.Provider.OKKO) return hits;
        List<Hit> verified = new ArrayList<>();
        for (int i = 0; i < Math.min(2, hits.size()) && !cancelled.getAsBoolean(); i++) {
            if (!verified.isEmpty() && System.nanoTime() - started > 2_500_000_000L) break;
            Hit hit = hits.get(i);
            try {
                byte[] head = fetch(new URL(hit.page), "html", "text/html", cancelled, verified.isEmpty() ? 2800 : 1400);
                Hit confirmed = verifyOkko(head, hit, title, year);
                if (confirmed != null) verified.add(confirmed);
            } catch (IOException unavailable) {
                // A search listing alone is not enough to open an Okko card.
            }
        }
        return verified;
    }

    private static List<Hit> indexedSearch(TvVoiceCommand.Provider provider, String title, String mediaType, int year,
                                           String hint, BooleanSupplier cancelled) throws Exception {
        String query = "\"" + title + "\"" + hint + (year == 0 ? "" : " " + year) + " site:" + domain(provider);
        URL url = new URL("https://www.bing.com/search?format=rss&cc=RU&setlang=ru&q="
                + URLEncoder.encode(query, "UTF-8"));
        return parse(fetch(url, "xml", "application/rss+xml, text/xml, application/xml", cancelled),
                provider, title, mediaType, year);
    }

    private static List<Hit> rutube(String title, String mediaType, int year, BooleanSupplier cancelled) throws Exception {
        URL url = new URL("https://rutube.ru/api/search/video/?limit=8&query="
                + URLEncoder.encode(title + (year == 0 ? "" : " " + year), "UTF-8"));
        byte[] body = fetch(url, "json", "application/json", cancelled);
        List<Hit> hits = new ArrayList<>();
        JSONArray videos = new JSONObject(new String(body, StandardCharsets.UTF_8)).optJSONArray("results");
        if (videos == null) throw new IOException("search_missing_results");
        for (int i = 0; i < Math.min(16, videos.length()); i++) {
            JSONObject video = videos.optJSONObject(i);
            if (video == null) continue;
            int duration = video.optInt("duration", 0);
            if ("film".equals(mediaType) && duration < 40 * 60 || "series".equals(mediaType) && duration < 4 * 60)
                continue;
            Hit hit = candidate(TvVoiceCommand.Provider.RUTUBE, title, mediaType, year,
                    video.optString("title"), video.optString("video_url"));
            if (hit != null)
                hits.add(new Hit(hit.provider, hit.page, hit.target, hit.displayTitle, hit.score + 4, hit.year));
        }
        return hits;
    }

    private static byte[] fetch(URL url, String type, String accept, BooleanSupplier cancelled) throws Exception {
        return fetch(url, type, accept, cancelled, 2800);
    }

    private static byte[] fetch(URL url, String type, String accept, BooleanSupplier cancelled,
                                int timeoutMs) throws Exception {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("cancelled");
        HttpsURLConnection request = (HttpsURLConnection) url.openConnection(Proxy.NO_PROXY);
        request.setConnectTimeout(timeoutMs);
        request.setReadTimeout(Math.min(2300, timeoutMs));
        request.setInstanceFollowRedirects(false);
        request.setUseCaches(false);
        request.setRequestProperty("Accept", accept);
        long deadline = System.nanoTime() + 4_500_000_000L;
        boolean headOnly = "html".equals(type);
        int limit = headOnly ? MAX_OKKO_HEAD : MAX_BYTES;
        try {
            if (request.getResponseCode() != 200) throw new IOException("search_http");
            String typeHeader = request.getContentType();
            if (typeHeader == null || !typeHeader.contains(type))
                throw new IOException("search_content_type");
            try (InputStream input = request.getInputStream()) {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int closingTag = 0;
                for (int n; (n = input.read(buffer)) != -1; ) {
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted() || System.nanoTime() > deadline)
                        throw new IOException("cancelled_or_timeout");
                    if (n > limit - body.size()) throw new IOException("search_response_size");
                    body.write(buffer, 0, n);
                    if (headOnly) {
                        for (int i = 0; i < n; i++) {
                            byte next = buffer[i];
                            closingTag = next == HEAD_END[closingTag] ? closingTag + 1 : next == '<' ? 1 : 0;
                            if (closingTag == HEAD_END.length) return body.toByteArray();
                        }
                    }
                }
                return body.toByteArray();
            }
        } finally {
            request.disconnect();
        }
    }

    static Hit verifyOkko(byte[] body, Hit hit, String requestedTitle, int requestedYear) {
        String html = new String(body, StandardCharsets.UTF_8);
        int end = html.indexOf("</head>");
        if (end < 0) return null;
        java.util.Map<String, String> fields = new java.util.HashMap<>();
        Matcher meta = META.matcher(html.substring(0, end));
        while (meta.find()) {
            String property = null;
            String content = null;
            Matcher attrs = META_ATTR.matcher(meta.group());
            while (attrs.find()) {
                if ("property".equalsIgnoreCase(attrs.group(1))) property = attrs.group(3);
                else content = attrs.group(3);
            }
            if (property != null && content != null && (property.startsWith("og:") || "al:android:url".equals(property)))
                fields.putIfAbsent(property, content.replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&"));
        }
        String cardTitle = fields.getOrDefault("og:title", "");
        String name = cardTitle.replaceFirst("\\s+\\((?:19|20)\\d{2}(?:[–-](?:19|20)\\d{2})?\\)\\s*$", "");
        int cardYear = year(cardTitle, requestedYear);
        if (!normalize(requestedTitle).equals(normalize(name)) || requestedYear != 0 && cardYear != requestedYear)
            return null;
        String canonical = fields.getOrDefault("og:url", "");
        if (!canonical.replaceFirst("/$", "").equals(hit.page.replaceFirst("/$", ""))) return null;
        Matcher link = OKKO_LINK.matcher(fields.getOrDefault("al:android:url", ""));
        if (!link.matches()) return null;
        boolean film = hit.page.startsWith("https://okko.tv/movie/");
        if (film && !("movie".equals(link.group(1)) && "movie".equals(link.group(3)))
                || !film && "movie".equals(link.group(3))) return null;
        String target = "okko://" + link.group(1) + "?uid=" + link.group(2) + "&type=" + link.group(3);
        return new Hit(hit.provider, hit.page, target, name, hit.score + 10, cardYear);
    }

    static List<Hit> parse(byte[] xml, TvVoiceCommand.Provider provider, String title, String mediaType, int year)
            throws Exception {
        if (xml.length == 0 || xml.length > MAX_BYTES) throw new IOException("search_response_size");
        String utf8 = new String(xml, StandardCharsets.UTF_8);
        if (utf8.indexOf(0) >= 0 || utf8.contains("<!DOCTYPE") || utf8.contains("<!ENTITY"))
            throw new IOException("search_unsafe_xml");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setExpandEntityReferences(false);
        javax.xml.parsers.DocumentBuilder parser = factory.newDocumentBuilder();
        parser.setErrorHandler(new DefaultHandler());
        parser.setEntityResolver((id, system) -> { throw new org.xml.sax.SAXException("external_entity"); });
        org.xml.sax.InputSource input = new org.xml.sax.InputSource(new ByteArrayInputStream(xml));
        input.setEncoding("UTF-8");
        Element root = parser.parse(input).getDocumentElement();
        if (!"rss".equals(root.getTagName())) throw new IOException("search_not_rss");
        List<Hit> hits = new ArrayList<>();
        NodeList items = root.getElementsByTagName("item");
        for (int i = 0; i < Math.min(items.getLength(), 16); i++) {
            Element item = (Element) items.item(i);
            Hit hit = candidate(provider, title, mediaType, year, field(item, "title"), field(item, "link"));
            if (hit != null) hits.add(hit);
        }
        return hits;
    }

    private static String field(Element item, String name) {
        for (Node node = item.getFirstChild(); node != null; node = node.getNextSibling())
            if (name.equals(node.getNodeName())) return node.getTextContent().trim();
        return "";
    }

    static Hit candidate(TvVoiceCommand.Provider provider, String title, String mediaType, int wantedYear,
                         String resultTitle, String link) {
        if (link.length() > 512 || resultTitle.length() > 300) return null;
        final URI uri;
        try { uri = URI.create(link); }
        catch (IllegalArgumentException malformed) { return null; }
        String host = uri.getHost();
        String path = uri.getRawPath();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || path == null
                || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getRawFragment() != null) return null;
        host = host.toLowerCase(Locale.ROOT);
        String labelled = resultTitle.toLowerCase(Locale.ROOT);
        if (SHORT_CONTENT.matcher(labelled).find()) return null;
        String wanted = normalize(title);
        String actual = normalize(resultTitle);
        if (wanted.isEmpty() || !(actual.equals(wanted) || actual.startsWith(wanted + " "))) return null;
        int year = year(resultTitle, wantedYear);
        if (wantedYear != 0 && year != wantedYear) return null;
        String page;
        String target;
        Matcher matcher;
        int score = actual.equals(wanted) ? 12 : 8;
        if (provider != TvVoiceCommand.Provider.OKKO && !actual.equals(wanted)
                && !metadataFollowsTitle(title, resultTitle)) return null;
        switch (provider) {
            case OKKO:
                matcher = OKKO_PAGE.matcher(path);
                if (!"okko.tv".equals(host) || !matcher.matches()
                        || "film".equals(mediaType) && !"movie".equals(matcher.group(1))
                        || "series".equals(mediaType) && !"serial".equals(matcher.group(1))) return null;
                page = "https://okko.tv" + path;
                target = null; // Filled from the verified official card's Android link.
                score += 20;
                break;
            case VK:
                matcher = VK_PAGE.matcher(path);
                if (!(host.equals("vkvideo.ru") || host.equals("vkvideo.vk.ru") || host.equals("vk.com")
                        || host.equals("m.vkvideo.ru")) || !matcher.matches()) return null;
                page = "https://vkvideo.ru/video" + matcher.group(1);
                target = "https://vk.com/video" + matcher.group(1);
                break;
            case RUTUBE:
                matcher = RUTUBE_PAGE.matcher(path);
                if (!host.equals("rutube.ru") || !matcher.matches()) return null;
                page = "https://rutube.ru/video/" + matcher.group(1) + "/";
                target = "rutube://rutube.ru/video/" + matcher.group(1) + "/";
                break;
            case YOUTUBE:
                if (!(host.equals("www.youtube.com") || host.equals("youtube.com") || host.equals("m.youtube.com"))
                        || !path.equals("/watch") || uri.getRawQuery() == null) return null;
                String id = null;
                for (String parameter : uri.getRawQuery().split("&"))
                    if (parameter.startsWith("v=") && id == null) id = parameter.substring(2);
                if (id == null || !YOUTUBE_ID.matcher(id).matches()) return null;
                page = "https://www.youtube.com/watch?v=" + id;
                target = page;
                break;
            default: return null;
        }
        if (provider != TvVoiceCommand.Provider.OKKO && ("film".equals(mediaType) || "series".equals(mediaType))) {
            if ("film".equals(mediaType) && !(labelled.contains("полный фильм") || labelled.contains("фильм целиком")
                    || labelled.contains("полнометраж") || (year != 0 && labelled.contains("фильм")))) return null;
            if ("series".equals(mediaType) && !(labelled.contains("сериал") || labelled.contains("сезон"))) return null;
        }
        return new Hit(provider, page, target, title, score, year);
    }

    static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static boolean metadataFollowsTitle(String title, String resultTitle) {
        Matcher separator = Pattern.compile("[,(|:–—]|\\s-\\s").matcher(resultTitle);
        while (separator.find()) {
            if (normalize(resultTitle.substring(0, separator.start())).equals(normalize(title))) return true;
        }
        return false;
    }

    private static int year(String title, int wanted) {
        Matcher match = YEAR.matcher(title);
        int first = 0;
        while (match.find()) {
            int result = Integer.parseInt(match.group());
            if (result == wanted) return result;
            if (first == 0) first = result;
        }
        return first;
    }
}
