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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
    static final int MAX_OKKO_CARD = 2 * 1024 * 1024;
    private static final byte[] HEAD_END = "</head>".getBytes(StandardCharsets.US_ASCII);
    private static final OkkoIndexCache OKKO_MOVIES = new OkkoIndexCache(false, "sitemap_movie.xml");
    private static final OkkoIndexCache OKKO_SERIALS_FIRST = new OkkoIndexCache(true, "sitemap_serial_1.xml");
    private static final OkkoIndexCache OKKO_SERIALS_SECOND = new OkkoIndexCache(true, "sitemap_serial_2.xml");
    private static final Pattern OKKO_PAGE = Pattern.compile("/(movie|serial)/[a-z0-9][a-z0-9-]{0,159}/?");
    private static final Pattern META = Pattern.compile("<meta\\s+[^>]{0,2048}>", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_ATTR = Pattern.compile(
            "(?:^|\\s)(property|content)\\s*=\\s*(['\"])(.*?)\\2", Pattern.CASE_INSENSITIVE);
    private static final Pattern OKKO_LINK = Pattern.compile(
            "okko://(movie|serial|series)\\?uid=([a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12})"
                    + "&type=(movie|serial|series)(?:&[a-zA-Z]+=[a-zA-Z0-9_-]{1,60}){0,5}");
    private static final Pattern STRUCTURED_CARD = Pattern.compile(
            "<script\\s+[^>]{0,1024}type=['\"]application/ld\\+json['\"][^>]{0,1024}>(.*?)</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern VK_PAGE = Pattern.compile("/video(-?\\d{1,12}_\\d{1,12})/?");
    private static final Pattern RUTUBE_PAGE = Pattern.compile("/video/([0-9a-f]{32})/?");
    private static final Pattern YOUTUBE_ID = Pattern.compile("[a-zA-Z0-9_-]{11}");
    private static final String YEAR_NUMBER = "(?:18|19|20)\\d{2}";
    private static final Pattern YEAR = Pattern.compile("(?:^| )(" + YEAR_NUMBER + ")(?: |$)");
    private static final Pattern CARD_YEAR = Pattern.compile(
            "^(.+?)\\s+\\((" + YEAR_NUMBER + ")(?:\\s*[–—-]\\s*(?:" + YEAR_NUMBER + ")?)?\\)\\s*$");
    private static final Pattern LOCAL_PREMIERE = Pattern.compile(
            ">Премьера в России</div>\\s*\\d{1,2}\\s+[а-яё]+\\s+(" + YEAR_NUMBER + ") г\\.</div>");
    private static final String FULL_FILM = "(?:полный (?:мультфильм|фильм)|полнометражный (?:мультфильм|фильм)"
            + "|(?:мультфильм|фильм) целиком|full (?:movie|film))";
    private static final String SERIES = "(?:сериал|телесериал|мультсериал|сезон|серия|эпизод|series|season|episode)";
    private static final String FILM = "(?:" + FULL_FILM + "|фильм|мультфильм|художественный фильм|movie|film)";
    private static final Pattern FULL_FILM_LABEL = label(FULL_FILM);
    private static final Pattern SERIES_LABEL = label(SERIES);
    private static final Pattern FILM_LABEL = label(FILM);
    private static final Pattern ANIMATION_LABEL = label("(?:мультик|мультфильм|мультсериал|animation|cartoon)");
    private static final Pattern ANIMATION_GENRE = label("(?:мультфильмы?|мультсериалы?|анимация|animation)");
    private static final Pattern SHORT_CONTENT = label("(?:трейлер\\p{L}*|тизер\\p{L}*|обзор\\p{L}*|нарезк\\p{L}*"
            + "|реакци\\p{L}*|фрагмент\\p{L}*|пересказ\\p{L}*|анонс\\p{L}*|отзыв\\p{L}*|рецензи\\p{L}*"
            + "|trailer|teaser|review|reaction|clip)");
    private static final Pattern PROVIDER_TAIL = Pattern.compile("(?:^| )(?:смотреть(?: видео)? онлайн(?: |$).*"
            + "|(?:окко|okko|rutube|рутуб|youtube|vk видео|vk video))$");
    private static final Pattern VIDEO_METADATA = Pattern.compile("(?:"
            + YEAR_NUMBER + "(?: г(?:од(?:а|у)?)?)?|" + FILM + "|" + SERIES + "(?: \\d{1,3})?"
            + "|\\d{1,3} (?:сезон|серия|эпизод)|мультик|animation|cartoon|(?:full )?hd|uhd|[248]k|\\d{3,4}p|без рекламы)(?: |$)");

    static final class Hit {
        final TvVoiceCommand.Provider provider;
        final String page, target, displayTitle, mediaType;
        final int score, year;
        final long durationMs;

        Hit(TvVoiceCommand.Provider provider, String page, String target, String displayTitle, int score, int year) {
            this(provider, page, target, displayTitle, score, year, null);
        }

        Hit(TvVoiceCommand.Provider provider, String page, String target, String displayTitle, int score, int year,
            String mediaType) {
            this(provider, page, target, displayTitle, score, year, mediaType, -1);
        }

        Hit(TvVoiceCommand.Provider provider, String page, String target, String displayTitle, int score, int year,
            String mediaType, long durationMs) {
            this.provider = provider;
            this.page = page;
            this.target = target;
            this.displayTitle = displayTitle;
            this.score = score;
            this.year = year;
            this.mediaType = mediaType;
            this.durationMs = durationMs;
        }

        String launchTarget(boolean play) {
            if (!play || provider != TvVoiceCommand.Provider.OKKO || !"film".equals(mediaType) || target == null)
                return target;
            Matcher verifiedLink = OKKO_LINK.matcher(target);
            if (!verifiedLink.matches() || !"movie".equals(verifiedLink.group(1))
                    || !"movie".equals(verifiedLink.group(3))) return target;
            return target + "&extraElementToPlay=" + verifiedLink.group(2) + "&extraPlayVideo=true";
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
        if (domain(provider) == null || cancelled.getAsBoolean()) return Collections.emptyList();
        if (provider == TvVoiceCommand.Provider.OKKO)
            return searchOkko(title, mediaType, year, cancelled);
        if (provider == TvVoiceCommand.Provider.RUTUBE) {
            try {
                List<Hit> direct = rutube(title, mediaType, year, cancelled);
                if (!direct.isEmpty() || cancelled.getAsBoolean()) return direct;
            } catch (Exception unavailable) {
                // The public catalogue may be unavailable. Search the same provider's indexed pages.
            }
        }
        String hint = "film".equals(mediaType) ? " фильм"
                : "series".equals(mediaType) ? " сериал" : "animation".equals(mediaType) ? " мультфильм" : "";
        List<Hit> hits = indexedSearch(provider, title, mediaType, year, year, hint, cancelled);
        if (hits.isEmpty() && year != 0 && !cancelled.getAsBoolean())
            return indexedSearch(provider, title, mediaType, year, 0, hint, cancelled);
        return hits;
    }

    private static List<Hit> searchOkko(String title, String mediaType, int year, BooleanSupplier cancelled)
            throws Exception {
        boolean animation = "animation".equals(mediaType);
        boolean seriesFirst = animation || "series".equals(mediaType);
        boolean acceptsBothTypes = animation || mediaType == null || "video".equals(mediaType);
        List<Hit> hits;
        try {
            hits = searchOkkoType(title, seriesFirst, animation, year, cancelled);
        } catch (IOException unavailable) {
            if (!acceptsBothTypes || cancelled.getAsBoolean()) throw unavailable;
            hits = Collections.emptyList();
        }
        if (!acceptsBothTypes || !hits.isEmpty() || cancelled.getAsBoolean()) return hits;
        return searchOkkoType(title, !seriesFirst, animation, year, cancelled);
    }

    private static List<Hit> searchOkkoType(String title, boolean series, boolean animation, int year, BooleanSupplier cancelled)
            throws Exception {
        String type = series ? "series" : "film";
        String hint = series ? " сериал" : " фильм";
        long started = System.nanoTime();
        List<Hit> verified = new ArrayList<>();
        Set<String> checked = new HashSet<>();
        String slug = OkkoPublicIndex.simpleSlug(title);
        if (series && !slug.isEmpty()) {
            verifySlug(title, slug, type, animation, year, checked, verified, cancelled);
            if (!verified.isEmpty()) return verified;
        }
        Exception indexFailure = null;
        try {
            // Adding a year to the query can hide the correct card. The official card filters the requested year.
            List<Hit> indexed = indexedSearch(TvVoiceCommand.Provider.OKKO, title, type, 0, 0, hint, cancelled);
            indexed.sort(Comparator.comparingInt((Hit hit) -> hit.score).reversed());
            for (int i = 0; i < Math.min(3, indexed.size()) && !cancelled.getAsBoolean(); i++) {
                verifyCandidate(indexed.get(i), title, animation, year, checked, verified, cancelled);
                if (enough(verified, year, started)) return verified;
            }
        } catch (Exception unavailable) {
            indexFailure = unavailable;
        }
        if (cancelled.getAsBoolean() || series && !verified.isEmpty() || enough(verified, year, started))
            return verified;
        if (verified.isEmpty() && !series && !slug.isEmpty()) {
            verifySlug(title, slug, type, animation, year, checked, verified, cancelled);
            if (enough(verified, year, started)) return verified;
        }
        try {
            OkkoIndexCache[] catalogues = series ? new OkkoIndexCache[]{OKKO_SERIALS_FIRST, OKKO_SERIALS_SECOND}
                    : new OkkoIndexCache[]{OKKO_MOVIES};
            for (OkkoIndexCache catalogue : catalogues) {
                if (cancelled.getAsBoolean() || enough(verified, year, started)) break;
                for (String page : catalogue.candidates(title, cancelled)) {
                    if (checked.size() >= 8 || cancelled.getAsBoolean()) break;
                    Hit candidate = candidate(TvVoiceCommand.Provider.OKKO, title, type, 0, title, page);
                    if (candidate == null) continue;
                    verifyCandidate(candidate, title, animation, year, checked, verified, cancelled);
                    if (enough(verified, year, started)) return verified;
                }
            }
        } catch (Exception unavailable) {
            if (verified.isEmpty() && indexFailure != null) throw indexFailure;
        }
        if (verified.isEmpty() && year != 0 && !cancelled.getAsBoolean()) {
            for (Hit hit : indexedSearch(TvVoiceCommand.Provider.OKKO, title, type, 0, year, hint, cancelled)) {
                if (checked.size() >= 8 || cancelled.getAsBoolean()) break;
                verifyCandidate(hit, title, animation, year, checked, verified, cancelled);
                if (!verified.isEmpty()) break;
            }
        }
        return verified;
    }

    private static boolean enough(List<Hit> verified, int year, long started) {
        return !verified.isEmpty() && (year != 0 || verified.size() >= 3 || System.nanoTime() - started > 4_000_000_000L);
    }

    private static void verifySlug(String title, String slug, String type, boolean animation, int year,
                                   Set<String> checked, List<Hit> verified, BooleanSupplier cancelled) {
        String path = "series".equals(type) ? "/serial/" : "/movie/";
        Hit candidate = candidate(TvVoiceCommand.Provider.OKKO, title, type, 0, title, "https://okko.tv" + path + slug);
        if (candidate != null) verifyCandidate(candidate, title, animation, year, checked, verified, cancelled);
    }

    private static void verifyCandidate(Hit candidate, String title, boolean animation, int year, Set<String> checked,
                                        List<Hit> verified, BooleanSupplier cancelled) {
        if (!checked.add(candidate.page) || cancelled.getAsBoolean()) return;
        try {
            byte[] head = fetch(new URL(candidate.page), "html", "text/html", cancelled,
                    verified.isEmpty() ? 2200 : 1000);
            Hit confirmed = verifyOkko(head, candidate, title, 0, animation);
            if (confirmed == null) return;
            if (year == 0 || year == confirmed.year) {
                verified.add(confirmed);
            } else if (isEligiblePremiereYear(confirmed, year) && !cancelled.getAsBoolean()) {
                byte[] card = fetch(new URL(candidate.page), "html", "text/html", cancelled, 1700, MAX_OKKO_CARD, false);
                Hit matched = verifyOkko(card, candidate, title, year, animation);
                if (matched != null) verified.add(matched);
            }
        } catch (IOException unavailable) {
            // The indexed URL needs its own official confirmation.
        }
    }

    private static List<Hit> indexedSearch(TvVoiceCommand.Provider provider, String title, String mediaType,
                                           int filterYear, int queryYear, String hint, BooleanSupplier cancelled)
            throws Exception {
        String query = "\"" + title.replace('"', ' ') + "\"" + hint
                + (queryYear == 0 ? "" : " " + queryYear) + " site:" + domain(provider);
        URL url = new URL("https://www.bing.com/search?format=rss&cc=RU&setlang=ru&q="
                + URLEncoder.encode(query, "UTF-8"));
        return parse(fetch(url, "xml", "application/rss+xml, text/xml, application/xml", cancelled),
                provider, title, mediaType, filterYear);
    }

    private static final class OkkoIndexCache {
        private final boolean series;
        private final String fileName;
        private OkkoPublicIndex index;
        private long loadedAt, failedAt;

        OkkoIndexCache(boolean series, String fileName) {
            this.series = series;
            this.fileName = fileName;
        }

        synchronized List<String> candidates(String title, BooleanSupplier cancelled) throws IOException {
            long now = System.nanoTime();
            if ((index == null || now - loadedAt >= OkkoPublicIndex.TTL_NANOS)
                    && (failedAt == 0 || now - failedAt >= OkkoPublicIndex.RETRY_NANOS)) {
                try {
                    byte[] xml = fetch(new URL("https://okko.tv/" + fileName),
                            "xml", "application/xml, text/xml", cancelled, 2400, OkkoPublicIndex.MAX_XML_BYTES);
                    index = OkkoPublicIndex.parse(new String(xml, StandardCharsets.UTF_8), series);
                    loadedAt = System.nanoTime();
                    failedAt = 0;
                } catch (IOException | IllegalArgumentException unavailable) {
                    if (!cancelled.getAsBoolean()) failedAt = System.nanoTime();
                    if (index == null) throw new IOException("okko_sitemap_unavailable", unavailable);
                }
            }
            return index == null ? Collections.emptyList() : index.candidates(title);
        }
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
                    video.optString("title"), video.optString("video_url"), duration);
            if (hit != null)
                hits.add(new Hit(hit.provider, hit.page, hit.target, hit.displayTitle, hit.score + 4, hit.year,
                        hit.mediaType));
        }
        return hits;
    }

    private static byte[] fetch(URL url, String type, String accept, BooleanSupplier cancelled) throws IOException {
        return fetch(url, type, accept, cancelled, 2800);
    }

    static byte[] fetchOkkoHead(String page, BooleanSupplier cancelled) throws IOException {
        return fetch(new URL(page), "html", "text/html", cancelled, 2200);
    }

    private static byte[] fetch(URL url, String type, String accept, BooleanSupplier cancelled,
                                int timeoutMs) throws IOException {
        return fetch(url, type, accept, cancelled, timeoutMs, "html".equals(type) ? MAX_OKKO_CARD : MAX_BYTES);
    }

    private static byte[] fetch(URL url, String type, String accept, BooleanSupplier cancelled,
                                int timeoutMs, int limit) throws IOException {
        return fetch(url, type, accept, cancelled, timeoutMs, limit, "html".equals(type));
    }

    private static byte[] fetch(URL url, String type, String accept, BooleanSupplier cancelled,
                                int timeoutMs, int limit, boolean headOnly) throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("cancelled");
        HttpsURLConnection request = (HttpsURLConnection) url.openConnection(Proxy.NO_PROXY);
        request.setConnectTimeout(timeoutMs);
        request.setReadTimeout(Math.min(2300, timeoutMs));
        request.setInstanceFollowRedirects(false);
        request.setUseCaches(false);
        request.setRequestProperty("Accept", accept);
        long deadline = System.nanoTime() + 4_500_000_000L;
        try {
            if (request.getResponseCode() != 200) throw new IOException("search_http");
            String typeHeader = request.getContentType();
            if (typeHeader == null || !typeHeader.contains(type))
                throw new IOException("search_content_type");
            try (InputStream input = request.getInputStream()) {
                return readResponse(input, limit, headOnly, "html".equals(type) && !headOnly, cancelled, deadline);
            }
        } finally {
            request.disconnect();
        }
    }

    static byte[] readResponse(InputStream input, int limit, boolean headOnly, boolean allowPrefix,
                               BooleanSupplier cancelled, long deadline) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int closingTag = 0;
        for (int count; (count = input.read(buffer)) != -1; ) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted() || System.nanoTime() > deadline)
                throw new IOException("cancelled_or_timeout");
            int accepted = Math.min(count, limit - body.size());
            if (headOnly) {
                for (int i = 0; i < accepted; i++) {
                    byte next = buffer[i];
                    closingTag = next == HEAD_END[closingTag] ? closingTag + 1 : next == '<' ? 1 : 0;
                    if (closingTag == HEAD_END.length) {
                        body.write(buffer, 0, i + 1);
                        return body.toByteArray();
                    }
                }
            }
            body.write(buffer, 0, accepted);
            if (accepted != count) {
                if (allowPrefix) return body.toByteArray();
                throw new IOException("search_response_size");
            }
        }
        return body.toByteArray();
    }

    static Hit verifyOkko(byte[] body, Hit hit, String requestedTitle, int requestedYear) {
        return verifyOkko(body, hit, requestedTitle, requestedYear, false);
    }

    static Hit verifyOkko(byte[] body, Hit hit, String requestedTitle, int requestedYear, boolean animation) {
        String html = new String(body, StandardCharsets.UTF_8);
        int end = html.indexOf("</head>");
        if (end < 0) return null;
        String head = html.substring(0, end);
        java.util.Map<String, String> fields = new java.util.HashMap<>();
        Matcher meta = META.matcher(head);
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
        String name = fields.getOrDefault("og:title", "");
        String normalizedTitle = normalize(requestedTitle);
        int cardYear = 0;
        if (!normalizedTitle.equals(normalize(name))) {
            Matcher annotatedTitle = CARD_YEAR.matcher(name);
            if (!annotatedTitle.matches() || !normalizedTitle.equals(normalize(annotatedTitle.group(1)))) return null;
            name = annotatedTitle.group(1);
            cardYear = Integer.parseInt(annotatedTitle.group(2));
        }
        String canonical = fields.getOrDefault("og:url", "");
        if (!canonical.replaceFirst("/$", "").equals(hit.page.replaceFirst("/$", ""))) return null;
        Matcher link = OKKO_LINK.matcher(fields.getOrDefault("al:android:url", ""));
        if (!link.matches()) return null;
        boolean film = hit.page.startsWith("https://okko.tv/movie/");
        if (film && !("movie".equals(link.group(1)) && "movie".equals(link.group(3)))
                || !film && "movie".equals(link.group(3))) return null;
        if (animation && !isOfficialAnimation(head, name, hit.page)) return null;
        String target = "okko://" + link.group(1) + "?uid=" + link.group(2) + "&type=" + link.group(3);
        long duration = film ? officialFilmDuration(head, name) : -1;
        Hit confirmed = new Hit(hit.provider, hit.page, target, name, hit.score + 10, cardYear,
                film ? "film" : "series", duration);
        if (requestedYear == 0 || cardYear == requestedYear) return confirmed;
        if (isEligiblePremiereYear(confirmed, requestedYear)) {
            Matcher premiere = LOCAL_PREMIERE.matcher(html).region(end + "</head>".length(), html.length());
            if (premiere.find() && Integer.parseInt(premiere.group(1)) == requestedYear) return confirmed;
        }
        return null;
    }

    private static long officialFilmDuration(String head, String title) {
        String expected = normalize(title);
        Matcher structured = STRUCTURED_CARD.matcher(head);
        long verified = -1;
        for (int i = 0; i < 8 && structured.find(); i++) {
            if (structured.end(1) - structured.start(1) > 262144) continue;
            try {
                JSONObject item = new JSONObject(structured.group(1));
                if (!"VideoObject".equals(item.opt("@type"))
                        || !expected.equals(normalize(item.optString("name", "")))) continue;
                long duration = Duration.parse(item.optString("duration", "")).toMillis();
                if (duration <= 0 || duration > 24 * 60 * 60_000L) continue;
                if (verified != -1 && verified != duration) return -1;
                verified = duration;
            } catch (org.json.JSONException | java.time.format.DateTimeParseException | ArithmeticException invalid) {
                // Optional metadata. Title matching still uses the verified card above.
            }
        }
        return verified;
    }

    private static boolean isEligiblePremiereYear(Hit confirmed, int year) {
        return "film".equals(confirmed.mediaType) && confirmed.year != 0 && year == confirmed.year + 1;
    }

    private static boolean isOfficialAnimation(String head, String title, String page) {
        String path = URI.create(page).getPath();
        String normalizedTitle = normalize(title);
        Matcher structured = STRUCTURED_CARD.matcher(head);
        for (int i = 0; i < 8 && structured.find(); i++) {
            if (structured.end(1) - structured.start(1) > 262144) continue;
            try {
                JSONObject item = new JSONObject(structured.group(1));
                String declaredUrl = item.optString("url", "");
                String name = item.optString("name", "");
                if (!normalizedTitle.equals(normalize(name))
                        || !declaredUrl.isEmpty() && !declaredUrl.equals(path) && !declaredUrl.equals(page)) continue;
                String genre = normalize(item.optString("genre", ""));
                if (ANIMATION_GENRE.matcher(genre).find()) return true;
            } catch (org.json.JSONException ignored) {
                // Other metadata records may contain the description of the exact card.
            }
        }
        return false;
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
        return candidate(provider, title, mediaType, wantedYear, resultTitle, link, 0);
    }

    private static Hit candidate(TvVoiceCommand.Provider provider, String title, String mediaType, int wantedYear,
                                 String resultTitle, String link, int duration) {
        if (link.length() > 512 || resultTitle.length() > 300) return null;
        final URI uri;
        try { uri = URI.create(link); }
        catch (IllegalArgumentException malformed) { return null; }
        String host = uri.getHost();
        String path = uri.getRawPath();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || path == null
                || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getRawFragment() != null) return null;
        host = host.toLowerCase(Locale.ROOT);
        String wanted = normalize(title);
        String actual = normalize(resultTitle);
        if (wanted.isEmpty() || !(actual.equals(wanted) || actual.startsWith(wanted + " "))) return null;
        String suffix = actual.substring(wanted.length()).trim();
        if (SHORT_CONTENT.matcher(suffix).find()) return null;
        String metadata = PROVIDER_TAIL.matcher(suffix).replaceFirst("").trim();
        int year = releaseYear(metadata);
        String locatedType;
        if (provider == TvVoiceCommand.Provider.OKKO) {
            locatedType = path.startsWith("/movie/") ? "film" : "series";
        } else {
            if (!hasOnlyVideoMetadata(metadata)
                    || (wantedYear != 0 && year != wantedYear)) return null;
            boolean series = SERIES_LABEL.matcher(metadata).find();
            boolean fullFilm = FULL_FILM_LABEL.matcher(metadata).find()
                    || (duration >= 40 * 60 && FILM_LABEL.matcher(metadata).find());
            if ("film".equals(mediaType) && (series || !fullFilm)
                    || "series".equals(mediaType) && !series
                    || "animation".equals(mediaType) && !ANIMATION_LABEL.matcher(metadata).find()) return null;
            locatedType = series ? "series" : fullFilm ? "film" : "video";
        }
        String page;
        String target;
        Matcher matcher;
        int score = actual.equals(wanted) ? 12 : 8;
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
        return new Hit(provider, page, target, title, score, year, locatedType);
    }

    static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static Pattern label(String pattern) {
        return Pattern.compile("(?:^| )" + pattern + "(?= |$)");
    }

    private static int releaseYear(String metadata) {
        Matcher match = YEAR.matcher(metadata);
        return match.find() ? Integer.parseInt(match.group(1)) : 0;
    }

    private static boolean hasOnlyVideoMetadata(String suffix) {
        Matcher metadata = VIDEO_METADATA.matcher(suffix);
        for (int position = 0; position < suffix.length(); position = metadata.end()) {
            if (!metadata.region(position, suffix.length()).lookingAt()) return false;
        }
        return true;
    }
}
