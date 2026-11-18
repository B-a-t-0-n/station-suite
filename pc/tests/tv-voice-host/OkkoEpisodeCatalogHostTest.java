package local.station.voicebridge;

import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

public final class OkkoEpisodeCatalogHostTest {
    private static final String SERIES = "11111111-2222-3333-4444-555555555555";
    private static final String FREE = "11111111-2222-3333-4444-666666666666";
    private static final String SUBSCRIPTION = "11111111-2222-3333-4444-777777777777";
    private static final String SOON = "11111111-2222-3333-4444-888888888888";
    private static final String PAGE = "https://okko.tv/serial/example";
    private static final String BASE_LINK = "okko://movie?uid=" + SERIES + "&type=serial";
    private static final TvVideoSearch.Hit HIT = new TvVideoSearch.Hit(TvVoiceCommand.Provider.OKKO,
            PAGE, BASE_LINK, "Пример", 100, 2026, "series");

    private static void check(boolean okay, String label) {
        if (!okay) throw new AssertionError(label);
    }

    private static JSONObject episode(int number, String id, JSONObject content) {
        return new JSONObject().put("type", "EPISODE").put("seqNo", number).put("id", id)
                .put("duration", 400_000).put("contentState", content);
    }

    private static JSONObject season(int number, int total, JSONObject... episodes) {
        return new JSONObject().put("type", "SEASON").put("seqNo", number)
                .put("children", children(total, episodes));
    }

    private static JSONObject children(int total, JSONObject... elements) {
        JSONArray items = new JSONArray();
        for (JSONObject element : elements) items.put(new JSONObject().put("element", element));
        return new JSONObject().put("totalSize", total).put("items", items);
    }

    private static byte[] page(String slug, String id, int totalSeasons, JSONObject... seasons) {
        JSONObject element = new JSONObject().put("id", id).put("type", "SERIAL")
                .put("alias", slug).put("children", children(totalSeasons, seasons));
        JSONObject response = new JSONObject().put("status", 0).put("element", element);
        JSONObject state = new JSONObject().put("responseCache", new JSONObject().put("element", response));
        String html = "<html><head><script id=\"__PRELOADED_DATA__\">window.__PRELOADED_DATA__ = "
                + state + "</script></head></html>";
        return html.getBytes(StandardCharsets.UTF_8);
    }

    public static void main(String[] arguments) {
        JSONObject free = episode(3, FREE, new JSONObject().put("freeContentStates",
                new JSONArray().put("AVOD_AUTH").put("AVOD_NOAUTH"))).put("title", "Третья серия");
        JSONObject subscription = episode(4, SUBSCRIPTION, new JSONObject().put("purchaseRequiredStates",
                new JSONArray().put("SVOD")));
        JSONObject announcement = episode(5, SOON, new JSONObject().put("contentUnavailableState", "ANNOUNCE"));
        JSONObject first = season(7, 3, subscription, free, announcement);
        JSONObject second = season(8, 1, subscription);
        byte[] publicPage = page("example", SERIES, 2, second, first);
        OkkoEpisodeCatalog catalog = OkkoEpisodeCatalog.parse(publicPage, HIT);
        check(catalog != null, "public server-side series card");
        check(catalog.seasonCount == 2 && catalog.defaultSeason() == 0, "two season choices");
        check(catalog.hasSeason(7) && !catalog.hasSeason(1), "use published season sequence numbers");
        check(catalog.listedEpisodes(7) == 2 && catalog.listedEpisodes(0) == 3, "exclude announced episodes");
        check(catalog.freeEpisodes(7) == 1 && catalog.freeEpisodes(8) == 0 && catalog.freeEpisodes(0) == 1,
                "count anonymous not subscription offers");
        check(("okko://movie?uid=" + FREE + "&type=episode&extraElementToPlay=" + FREE + "&extraPlayVideo=true")
                .equals(catalog.episodeTarget(7, 3, () -> false)), "exact episode deep link");
        check(catalog.episodeTarget(7, 4, () -> false).contains(SUBSCRIPTION), "let Okko check subscription");
        check(catalog.episodeTarget(7, 5, () -> false) == null, "announced episode cannot launch");
        check(catalog.durationMs(7, 3) == 400_000 && catalog.durationMs(7, 5) == -1, "exact verified episode duration");
        check("Третья серия".equals(catalog.episodeTitle(7, 3)) && catalog.episodeTitle(7, 5) == null,
                "exact verified episode title");
        check(catalog.episodeTarget(7, 2, () -> false) == null, "episode number not inferred from array index");
        check(catalog.episodeTarget(7, 3, () -> true) == null, "cancelled playback");

        OkkoEpisodeCatalog partial = OkkoEpisodeCatalog.parse(page("example", SERIES, 2, first), HIT);
        check(partial.defaultSeason() == 0 && partial.listedEpisodes(0) == -1, "incomplete season list");
        check(partial.listedEpisodes(7) == 2 && partial.freeEpisodes(7) == 1, "known selection still works");
        OkkoEpisodeCatalog single = OkkoEpisodeCatalog.parse(page("example", SERIES, 1, season(7, 8, free)), HIT);
        check(single.defaultSeason() == 7 && single.listedEpisodes(7) == -1, "incomplete episode list");
        check(single.episodeTarget(7, 3, () -> false) != null, "validated listed episode");
        OkkoEpisodeCatalog duplicate = OkkoEpisodeCatalog.parse(page("example", SERIES, 1, season(1, 2, free, free)), HIT);
        check(duplicate.listedEpisodes(1) == -1 && duplicate.episodeTarget(1, 3, () -> false) == null,
                "ambiguous duplicate episode not launched");
        check(OkkoEpisodeCatalog.parse(page("wrong-slug", SERIES, 1, first), HIT) == null, "require exact public slug");
        check(OkkoEpisodeCatalog.parse(page("example", FREE, 1, first), HIT) == null, "require exact public series UUID");
        TvVideoSearch.Hit wrongHost = new TvVideoSearch.Hit(TvVoiceCommand.Provider.OKKO,
                "https://example.com/serial/example", BASE_LINK, "Пример", 100, 2026, "series");
        check(OkkoEpisodeCatalog.parse(publicPage, wrongHost) == null, "reject other catalogue origin");
        System.out.println("Okko episode catalogue and verified link assertions passed");
    }
}
