package local.station.voicebridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Numbered episodes from the exact, public Okko series card. Playback is resolved by Okko TV. */
final class OkkoEpisodeCatalog {
    private static final int MAX_SEASONS = 100;
    private static final int MAX_EPISODES_PER_SEASON = 1000;
    private static final String UUID = "[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}";
    private static final Pattern ID = Pattern.compile(UUID);
    private static final Pattern CARD = Pattern.compile("https://okko\\.tv/serial/([a-z0-9][a-z0-9-]{0,159})/?");
    private static final Pattern LINK = Pattern.compile("okko://(?:movie|serial|series)\\?uid=("
            + UUID + ")&type=(?:serial|series)");
    private static final Pattern SCRIPT = Pattern.compile(
            "<script\\s+id=(['\"])__PRELOADED_DATA__\\1>\\s*window\\.__PRELOADED_DATA__\\s*=\\s*");
    private final Map<Integer, Season> seasons;
    final int seasonCount;

    private OkkoEpisodeCatalog(Map<Integer, Season> seasons, boolean complete) {
        this.seasons = seasons;
        seasonCount = complete ? seasons.size() : 0;
    }

    /** Returns null when the TV hit and public card cannot both be verified. */
    static OkkoEpisodeCatalog load(TvVideoSearch.Hit hit, BooleanSupplier cancelled) throws IOException {
        Identity identity = Identity.from(hit);
        if (identity == null || cancelled.getAsBoolean()) return null;
        return parse(TvVideoSearch.fetchOkkoHead(hit.page, cancelled), identity, cancelled);
    }

    boolean hasSeason(int season) { return seasons.containsKey(season); }

    int defaultSeason() { return seasonCount == 1 ? seasons.keySet().iterator().next() : 0; }

    /** Published catalogue entries, including those offered by subscription. 0 means all seasons. */
    int listedEpisodes(int season) { return count(season, false); }

    /** Public catalogue entries offered without sign-in, including with ads. */
    int freeEpisodes(int season) { return count(season, true); }

    private int count(int season, boolean free) {
        if (season != 0) {
            Season selected = seasons.get(season);
            return selected == null ? -1 : selected.count(free);
        }
        if (seasonCount == 0) return -1;
        int total = 0;
        for (Season selected : seasons.values()) {
            int count = selected.count(free);
            if (count < 0) return -1;
            total += count;
        }
        return total;
    }

    String episodeTarget(int season, int episode, BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) return null;
        Episode published = episode(season, episode);
        if (published == null) return null;
        // The native movie route resolves the exact episode as an EPISODE, using the current Okko profile.
        return "okko://movie?uid=" + published.id + "&type=episode&extraElementToPlay="
                + published.id + "&extraPlayVideo=true";
    }

    long durationMs(int season, int episode) {
        Episode published = episode(season, episode);
        return published == null ? -1 : published.durationMs;
    }

    String episodeTitle(int season, int episode) {
        Episode published = episode(season, episode);
        return published == null ? null : published.title;
    }

    private Episode episode(int season, int episode) {
        Season selected = seasons.get(season);
        return selected == null ? null : selected.published.get(episode);
    }

    static OkkoEpisodeCatalog parse(byte[] body, TvVideoSearch.Hit hit) {
        Identity identity = Identity.from(hit);
        return identity == null ? null : parse(body, identity, () -> false);
    }

    private static OkkoEpisodeCatalog parse(byte[] body, Identity identity, BooleanSupplier cancelled) {
        if (body.length == 0 || body.length > TvVideoSearch.MAX_OKKO_CARD) return null;
        String html = new String(body, StandardCharsets.UTF_8);
        Matcher script = SCRIPT.matcher(html);
        int endOfHead = html.indexOf("</head>");
        if (endOfHead < 0 || !script.region(0, endOfHead).find()) return null;
        int end = html.indexOf("</script>", script.end());
        if (end < script.end() || end > endOfHead) return null;
        try {
            JSONObject state = new JSONObject(html.substring(script.end(), end).trim());
            JSONObject response = object(state.optJSONObject("responseCache"), "element");
            JSONObject series = object(response, "element");
            if (series == null || response.optInt("status", -1) != 0 || !"SERIAL".equals(series.opt("type"))
                    || !identity.slug.equals(series.opt("alias"))
                    || !identity.id.equalsIgnoreCase(series.optString("id", ""))) return null;
            return readSeasons(series, cancelled);
        } catch (JSONException malformed) {
            return null;
        }
    }

    private static OkkoEpisodeCatalog readSeasons(JSONObject series, BooleanSupplier cancelled) {
        JSONObject children = series.optJSONObject("children");
        JSONArray entries = items(children);
        if (entries == null || entries.length() == 0 || entries.length() > MAX_SEASONS) return null;
        Map<Integer, Season> seasons = new HashMap<>();
        boolean complete = hasAll(children, entries);
        for (int index = 0; index < entries.length(); index++) {
            if (cancelled.getAsBoolean()) return null;
            JSONObject item = object(entries.optJSONObject(index), "element");
            int number = sequence(item, "SEASON");
            if (number == 0) { complete = false; continue; }
            if (seasons.containsKey(number)) return null;
            seasons.put(number, readSeason(item));
        }
        return seasons.isEmpty() ? null : new OkkoEpisodeCatalog(seasons, complete);
    }

    private static Season readSeason(JSONObject season) {
        JSONObject children = season.optJSONObject("children");
        JSONArray entries = items(children);
        if (entries == null || entries.length() > MAX_EPISODES_PER_SEASON) return new Season(false);
        Season result = new Season(hasAll(children, entries));
        Set<Integer> seen = new HashSet<>();
        Set<Integer> duplicate = new HashSet<>();
        for (int index = 0; index < entries.length(); index++) {
            JSONObject item = object(entries.optJSONObject(index), "element");
            int number = sequence(item, "EPISODE");
            if (number == 0) { result.complete = false; continue; }
            if (!seen.add(number)) {
                result.complete = false;
                duplicate.add(number);
                continue;
            }
            JSONObject content = item.optJSONObject("contentState");
            if (content == null) { result.complete = false; continue; }
            if (content.opt("contentUnavailableState") instanceof String) continue;
            String id = item.optString("id", "");
            long durationMs = item.optLong("duration", 0);
            if (!ID.matcher(id).matches() || durationMs <= 0 || !offered(content)) {
                result.complete = false;
                continue;
            }
            JSONArray freeStates = content.optJSONArray("freeContentStates");
            boolean free = contains(freeStates, "AVOD_NOAUTH") || contains(freeStates, "FREE_NOAUTH");
            result.published.put(number, new Episode(id, free, durationMs, title(item)));
        }
        for (int number : duplicate) result.published.remove(number);
        return result;
    }

    private static boolean offered(JSONObject content) {
        String watch = content.optString("playbackWatchState", "");
        return "WATCH".equals(watch) || "START_WATCHING".equals(watch) || "CONTINUE_WATCHING".equals(watch)
                || hasValues(content.optJSONArray("purchaseRequiredStates"))
                || hasValues(content.optJSONArray("freeContentStates"));
    }

    private static boolean hasValues(JSONArray values) { return values != null && values.length() > 0; }

    private static String title(JSONObject item) {
        Object raw = item.opt("title");
        if (!(raw instanceof String)) return null;
        String title = ((String) raw).trim();
        if (title.isEmpty() || title.length() > 120
                || title.indexOf('<') >= 0 || title.indexOf('>') >= 0
                || title.codePoints().anyMatch(Character::isISOControl)) return null;
        return title;
    }

    private static boolean contains(JSONArray values, String expected) {
        if (values == null) return false;
        for (int index = 0; index < values.length(); index++)
            if (expected.equals(values.opt(index))) return true;
        return false;
    }

    private static int sequence(JSONObject item, String type) {
        if (item == null || !type.equals(item.opt("type"))) return 0;
        Object value = item.opt("seqNo");
        if (!(value instanceof Integer) && !(value instanceof Long)) return 0;
        long number = ((Number) value).longValue();
        return number > 0 && number <= MAX_EPISODES_PER_SEASON ? (int) number : 0;
    }

    private static JSONObject object(JSONObject parent, String key) {
        return parent == null ? null : parent.optJSONObject(key);
    }

    private static JSONArray items(JSONObject children) {
        return children == null ? null : children.optJSONArray("items");
    }

    private static boolean hasAll(JSONObject children, JSONArray entries) {
        return children.optInt("totalSize", -1) == entries.length();
    }

    private static final class Identity {
        final String slug, id;

        Identity(String slug, String id) { this.slug = slug; this.id = id; }

        static Identity from(TvVideoSearch.Hit hit) {
            if (hit == null || hit.provider != TvVoiceCommand.Provider.OKKO || !"series".equals(hit.mediaType)
                    || hit.page == null || hit.target == null) return null;
            Matcher card = CARD.matcher(hit.page);
            Matcher link = LINK.matcher(hit.target);
            return card.matches() && link.matches() ? new Identity(card.group(1), link.group(1)) : null;
        }
    }

    private static final class Episode {
        final String id;
        final boolean free;
        final long durationMs;
        final String title;

        Episode(String id, boolean free, long durationMs, String title) {
            this.id = id;
            this.free = free;
            this.durationMs = durationMs;
            this.title = title;
        }
    }

    private static final class Season {
        boolean complete;
        final Map<Integer, Episode> published = new HashMap<>();

        Season(boolean complete) { this.complete = complete; }

        int count(boolean freeOnly) {
            if (!complete) return -1;
            if (!freeOnly) return published.size();
            int count = 0;
            for (Episode episode : published.values()) if (episode.free) count++;
            return count;
        }
    }
}
