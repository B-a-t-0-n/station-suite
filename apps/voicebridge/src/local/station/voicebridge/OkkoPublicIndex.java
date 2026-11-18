package local.station.voicebridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Matches plausible slugs from Okko's public sitemap. A card still has to verify the title and year. */
final class OkkoPublicIndex {
    private static final int MAX_LINKS = 70_000;
    static final int MAX_XML_BYTES = 5 * 1024 * 1024;
    private static final int MAX_MATCHES = 6;
    static final long TTL_NANOS = java.util.concurrent.TimeUnit.HOURS.toNanos(6);
    static final long RETRY_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(2);
    private static final Pattern LOC = Pattern.compile("<loc>\\s*(https://okko\\.tv/(movie|serial)/"
            + "([a-z0-9][a-z0-9-]{0,159}))/?" + "\\s*</loc>");
    private static final Pattern CATALOGUE_SUFFIX = Pattern.compile("-\\d{5,}$");
    private static final String[] SPECIAL = {
        "a", "b", "v", "g", "d", "e", "zh", "z", "i", "i", "k", "l", "m", "n", "o", "p",
        "r", "s", "t", "u", "f", "h", "c", "ch", "sh", "sch", "j", "y", "j", "e", "yu", "ya"
    };
    private final Map<String, List<String>> bySlug;

    private OkkoPublicIndex(Map<String, List<String>> bySlug) { this.bySlug = bySlug; }

    static OkkoPublicIndex parse(String xml, boolean series) {
        if (xml.length() > MAX_XML_BYTES || !xml.contains("<urlset") || !xml.contains("</urlset>")
                || xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY"))
            throw new IllegalArgumentException("invalid_okko_sitemap");
        Map<String, List<String>> index = new LinkedHashMap<>();
        Matcher entry = LOC.matcher(xml);
        int count = 0;
        while (entry.find()) {
            if (series != "serial".equals(entry.group(2))) continue;
            if (++count > MAX_LINKS) throw new IllegalArgumentException("okko_sitemap_too_many_links");
            String slug = entry.group(3);
            String key = CATALOGUE_SUFFIX.matcher(slug).replaceFirst("");
            List<String> pages = index.get(key);
            if (pages == null) {
                pages = new ArrayList<>(1);
                index.put(key, pages);
            }
            if (pages.size() < MAX_MATCHES && !pages.contains(entry.group(1))) pages.add(entry.group(1));
        }
        if (index.isEmpty()) throw new IllegalArgumentException("no_okko_sitemap_links");
        return new OkkoPublicIndex(index);
    }

    List<String> candidates(String title) {
        List<String> pages = new ArrayList<>(MAX_MATCHES);
        for (int variant = 0; variant <= 4; variant++) {
            List<String> matches = bySlug.get(transliterate(title, variant));
            if (matches == null) continue;
            for (String page : matches) {
                if (!pages.contains(page)) pages.add(page);
                if (pages.size() == MAX_MATCHES) return pages;
            }
        }
        return pages.isEmpty() ? Collections.emptyList() : pages;
    }

    static String simpleSlug(String title) {
        String slug = transliterate(title, 0);
        return slug.matches("[a-z0-9][a-z0-9-]{0,159}") ? slug : "";
    }

    private static String transliterate(String title, int variant) {
        String normalized = TvVideoSearch.normalize(title);
        StringBuilder slug = new StringBuilder(normalized.length() + 8);
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c == ' ') slug.append('-');
            else if (c >= 'а' && c <= 'я') {
                String chars = SPECIAL[c - 'а'];
                if (variant == 1) {
                    if (c == 'й') chars = "y";
                    if (c == 'х') chars = "kh";
                    if (c == 'ц') chars = "ts";
                    if (c == 'щ') chars = "shch";
                    if (c == 'ь' || c == 'ъ') chars = "";
                } else if (variant == 2 || variant == 4) {
                    if (c == 'й') chars = variant == 4 ? "jj" : "j";
                    if (c == 'х') chars = "kh";
                    if (c == 'ю') chars = "ju";
                    if (c == 'я') chars = "ja";
                    if (c == 'ь' || c == 'ъ') chars = "";
                } else if (variant == 3 && (c == 'ь' || c == 'ъ')) chars = "";
                slug.append(chars);
            } else if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') slug.append(c);
        }
        return slug.toString();
    }
}
