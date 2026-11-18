package local.station.voicebridge;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class TvVideoSearchHostTest {
    private static final TvVoiceCommand.Provider OKKO = TvVoiceCommand.Provider.OKKO;
    private static final TvVoiceCommand.Provider RUTUBE = TvVoiceCommand.Provider.RUTUBE;
    private static final String TITLE = "Простоквашино";
    private static final String MOVIE = "https://okko.tv/movie/prostokvashino-test";
    private static final String SERIES = "https://okko.tv/serial/prostokvashino";
    private static final String VIDEO = "https://rutube.ru/video/0123456789abcdef0123456789abcdef/";
    private static final String UID = "01234567-89ab-4def-8123-456789abcdef";

    public static void main(String[] args) throws Exception {
        String rss = "<rss><channel>"
                + item(TITLE + " (фильм, 2026) смотреть онлайн", MOVIE)
                + item(TITLE + " (мультсериал, 2018)", SERIES)
                + item(TITLE + " (фильм, 2026)", "https://okko.tv.evil.test/movie/prostokvashino")
                + item(TITLE + " (фильм, 2026)", "https://okko.tv/movie/prostokvashino/reviews")
                + item(TITLE + " (фильм, 2026), трейлер", "https://okko.tv/movie/official-trailer")
                + item(TITLE + " (полный фильм, 2026)", VIDEO)
                + "</channel></rss>";
        List<TvVideoSearch.Hit> film = TvVideoSearch.parse(bytes(rss), OKKO, TITLE, "film", 2026);
        check(film.size() == 1, "only the requested provider and media type");
        check(film.get(0).target == null, "index cannot emit an Okko app link");
        check(TvVideoSearch.parse(bytes(rss), OKKO, TITLE, "film", 2024).size() == 1,
                "Okko year is determined from the official card, not the index");
        check(TvVideoSearch.parse(bytes(rss), RUTUBE, TITLE, "film", 2026).size() == 1, "separate provider");
        String card = "<html><head>" + meta("og:title", TITLE + " (2026)")
                + meta("og:url", MOVIE) + meta("al:android:url",
                "okko://movie?uid=" + UID + "&amp;type=movie&amp;referrer=okkoweb") + "</head></html>";
        TvVideoSearch.Hit validated = TvVideoSearch.verifyOkko(bytes(card), film.get(0), TITLE, 2026);
        check(validated != null && validated.target.equals("okko://movie?uid=" + UID + "&type=movie"), "exact Android link");
        check("film".equals(validated.mediaType) && validated.year == 2026, "official movie metadata");
        check(TvVideoSearch.verifyOkko(bytes(card), film.get(0), TITLE, 2024) == null, "official release year is strict");
        check(TvVideoSearch.verifyOkko(bytes(card.replace(TITLE + " (2026)", TITLE + ": Новый сезон (2026)")),
                film.get(0), TITLE, 2026) == null, "official title must match completely");
        String serialCard = card.replace(MOVIE, SERIES).replace("type=movie", "type=serial")
                .replace("(2026)", "(2018–2026)");
        TvVideoSearch.Hit series = TvVideoSearch.candidate(OKKO, TITLE, null, 0, TITLE + " (мультсериал)", SERIES);
        TvVideoSearch.Hit confirmedSeries = TvVideoSearch.verifyOkko(bytes(serialCard), series, TITLE, 0);
        check(confirmedSeries != null && "series".equals(confirmedSeries.mediaType) && confirmedSeries.year == 2018,
                "untyped same-name series remains distinguishable from the movie");
        check(TvVideoSearch.verifyOkko(bytes(serialCard), series, TITLE, 2026) == null,
                "the final season's year is not the release year of the series");
        TvVideoSearch.Hit numericTitle = TvVideoSearch.candidate(OKKO, "2012", "film", 2009,
                "2012 смотреть онлайн", MOVIE);
        check(numericTitle != null, "numeric title must not be mistaken for an indexed year");
        String numericCard = card.replace(TITLE + " (2026)", "2012 (2009)");
        check(TvVideoSearch.verifyOkko(bytes(numericCard), numericTitle, "2012", 2009).year == 2009,
                "year follows the numbered title");
        check(TvVideoSearch.verifyOkko(bytes(numericCard), numericTitle, "2012", 2012) == null,
                "a number in the work's title does not match a release year");
        check(TvVideoSearch.candidate(RUTUBE, TITLE, "film", 2026, TITLE + " (фильм, 2026)", VIDEO) == null,
                "a user-upload title and its year alone are not enough to identify a full movie");
        check(TvVideoSearch.candidate(RUTUBE, TITLE, "film", 2026, TITLE + ": Новые каникулы (полный фильм, 2026)",
                VIDEO) == null, "different work after colon");
        check(TvVideoSearch.candidate(RUTUBE, TITLE, "film", 2026, TITLE + " 2 (полный фильм, 2026)",
                VIDEO) == null, "different sequel");
        check(TvVideoSearch.candidate(RUTUBE, TITLE, "film", 2026, TITLE + " (полный фильм, 2026) 1 серия",
                VIDEO) == null, "episode cannot stand in for a film");
        for (String info : new String[]{"трейлер", "тизер", "пересказ", "обзор", "рецензия", "отзывы", "фрагмент"}) {
            check(TvVideoSearch.candidate(RUTUBE, TITLE, "film", 2026, TITLE + " (полный фильм, 2026) " + info,
                    VIDEO) == null, "short or promotional content " + info);
        }
        TvVideoSearch.Hit filmWithNumber = TvVideoSearch.candidate(RUTUBE, "2012", "film", 2009,
                "2012 (полный фильм, 2009) смотреть онлайн", VIDEO);
        check(filmWithNumber != null && filmWithNumber.year == 2009, "year on video host is parsed after exact title");
        check(TvVideoSearch.candidate(RUTUBE, "2012", "film", 2012,
                "2012 (полный фильм, 2009) смотреть онлайн", VIDEO) == null, "number within video title cannot supply year");
        check(TvVideoSearch.candidate(RUTUBE, "Трейлер", "film", 2026,
                "Трейлер (полный фильм, 2026) смотреть онлайн", VIDEO) != null, "media keyword may be the work's title");
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
