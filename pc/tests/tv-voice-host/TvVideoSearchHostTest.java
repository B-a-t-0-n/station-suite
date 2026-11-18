package local.station.voicebridge;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class TvVideoSearchHostTest {
    private static final TvVoiceCommand.Provider OKKO = TvVoiceCommand.Provider.OKKO;
    private static final TvVoiceCommand.Provider RUTUBE = TvVoiceCommand.Provider.RUTUBE;
    private static final String TITLE = "Простоквашино";
    private static final String MOVIE = "https://okko.tv/movie/prostokvashino-test";
    private static final String UID = "01234567-89ab-4def-8123-456789abcdef";

    public static void main(String[] args) throws Exception {
        String rss = "<rss><channel>"
                + item(TITLE + " (фильм, 2026) смотреть онлайн", MOVIE)
                + item(TITLE + " (мультсериал, 2018)", "https://okko.tv/serial/prostokvashino")
                + item(TITLE + " (фильм, 2026)", "https://okko.tv.evil.test/movie/prostokvashino")
                + item(TITLE + " (фильм, 2026)", "https://okko.tv/movie/prostokvashino/reviews")
                + item(TITLE + " (фильм, 2026), трейлер", "https://okko.tv/movie/official-trailer")
                + item(TITLE + " (фильм, 2026)", "https://rutube.ru/video/0123456789abcdef0123456789abcdef/")
                + "</channel></rss>";
        List<TvVideoSearch.Hit> film = TvVideoSearch.parse(bytes(rss), OKKO, TITLE, "film", 2026);
        check(film.size() == 1, "only the requested provider and media type");
        check(film.get(0).target == null, "index cannot emit an Okko app link");
        check(TvVideoSearch.parse(bytes(rss), OKKO, TITLE, "film", 2024).isEmpty(), "year is strict");
        check(TvVideoSearch.parse(bytes(rss), RUTUBE, TITLE, "film", 2026).size() == 1, "separate provider");
        String card = "<html><head>" + meta("og:title", TITLE + " (2026)")
                + meta("og:url", MOVIE) + meta("al:android:url",
                "okko://movie?uid=" + UID + "&amp;type=movie&amp;referrer=okkoweb") + "</head></html>";
        TvVideoSearch.Hit validated = TvVideoSearch.verifyOkko(bytes(card), film.get(0), TITLE, 2026);
        check(validated != null && validated.target.equals("okko://movie?uid=" + UID + "&type=movie"), "exact Android link");
        check(TvVideoSearch.verifyOkko(bytes(card.replace("uid=" + UID, "uid=fake")), film.get(0), TITLE, 0) == null,
                "malformed content ID");
        check(TvVideoSearch.verifyOkko(bytes(card.replace(TITLE, "Другое")), film.get(0), TITLE, 0) == null, "card identity");
        check(TvVideoSearch.verifyOkko(bytes(card.replace("https://okko.tv", "https://evil.test")), film.get(0), TITLE, 0) == null,
                "canonical page identity");
        check(TvVideoSearch.verifyOkko(bytes(card.replace("okko://movie", "okko://serial")), film.get(0), TITLE, 0) == null,
                "external deep link is checked");
        try {
            TvVideoSearch.parse(bytes("<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///test'>]><rss/>"), OKKO, TITLE, "film", 0);
            throw new AssertionError("doctype accepted");
        } catch (java.io.IOException expected) { }
        System.out.println("TV catalogue and deep-link tests passed");
    }

    private static String item(String title, String link) {
        return "<item><title>" + title + "</title><link>" + link + "</link></item>";
    }
    private static String meta(String field, String value) {
        return "<meta content=\"" + value + "\" property=\"" + field + "\"/>";
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
}
