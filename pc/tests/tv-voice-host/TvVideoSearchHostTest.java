package local.station.voicebridge;

import java.io.ByteArrayInputStream;
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
        check(validated.launchTarget(true).equals(validated.target + "&extraElementToPlay=" + UID + "&extraPlayVideo=true"),
                "autoplay asks for the exact verified movie");
        check(validated.launchTarget(false).equals(validated.target), "find opens a movie's card");
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
        check(confirmedSeries.launchTarget(true).equals(confirmedSeries.target), "series opens its card");
        officialAnimationAndPremiere();
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

    private static void officialAnimationAndPremiere() throws Exception {
        String page = "https://okko.tv/serial/smeshariki";
        String title = "Смешарики";
        TvVideoSearch.Hit hit = TvVideoSearch.candidate(OKKO, title, "animation", 0, title, page);
        String structuredGenre = "<script type=\"application/ld+json\">{\"@type\":\"VideoObject\","
                + "\"name\":\"Смешарики\",\"genre\":\"Мультфильмы, Семейное\"}</script>";
        String serial = "<html><head>" + structuredGenre
                + meta("og:title", "Смешарики (2004-2011)") + meta("og:type", "video.tv_show")
                + meta("og:url", page) + meta("al:android:url", "okko://movie?uid=" + UID + "&amp;type=serial")
                + "</head></html>";
        TvVideoSearch.Hit animated = TvVideoSearch.verifyOkko(bytes(serial), hit, title, 2004, true);
        check(animated != null && "series".equals(animated.mediaType), "animated TV series matches animation query");
        String largeHead = serial.replace("</head>", new String(new char[1_050_000]).replace('\0', ' ') + "</head>");
        byte[] payload = TvVideoSearch.readResponse(new ByteArrayInputStream(bytes(largeHead)),
                TvVideoSearch.MAX_OKKO_CARD, true, false, () -> false, Long.MAX_VALUE);
        check(payload.length > 1_048_576 && new String(payload, StandardCharsets.UTF_8).endsWith("</head>"),
                "a long official head is read through its closing tag");
        check(TvVideoSearch.verifyOkko(payload, hit, title, 2004, true) != null,
                "large animated series card still provides a verified link");
        check(TvVideoSearch.verifyOkko(bytes(serial.replace(structuredGenre, "")), hit, title, 2004, true) == null,
                "matching title is insufficient without animation evidence");
        check(TvVideoSearch.verifyOkko(bytes(serial.replace("Смешарики\",\"genre", "Пин-код\",\"genre")),
                hit, title, 0, true) == null, "another title's genre is ignored");
        check(TvVideoSearch.candidate(OKKO, title, "film", 0, title, page) == null,
                "an explicit feature film remains a film-only search");
        check(TvVideoSearch.candidate(RUTUBE, title, "animation", 0, "Смешарики (мультсериал, 2004)", VIDEO) != null,
                "video host must describe the candidate as animation");
        check(TvVideoSearch.candidate(RUTUBE, title, "animation", 0, "Смешарики (фильм, 2004)", VIDEO) == null,
                "regular videos do not stand in for animation");

        String movie = "https://okko.tv/movie/cheburashka-93168361";
        TvVideoSearch.Hit firstFilm = TvVideoSearch.candidate(OKKO, "Чебурашка", "film", 0, "Чебурашка (2022)", movie);
        String feature = "<html><head>" + meta("og:title", "Чебурашка (2022)") + meta("og:url", movie)
                + meta("al:android:url", "okko://movie?uid=" + UID + "&amp;type=movie") + "</head>"
                + "<body><div><div class=\"info-label\">Премьера в России</div>1 января 2023 г.</div></body></html>";
        TvVideoSearch.Hit premiere = TvVideoSearch.verifyOkko(bytes(feature), firstFilm, "Чебурашка", 2023);
        check(premiere != null && premiere.year == 2022, "official next-year local premiere is a release-year alias");
        check(TvVideoSearch.verifyOkko(bytes(feature.replace("Премьера в России", "Премьера в мире")),
                firstFilm, "Чебурашка", 2023) == null, "local premiere needs an explicit official label");
        check(TvVideoSearch.verifyOkko(bytes(feature.replace("2023 г.", "2026 г.")),
                firstFilm, "Чебурашка", 2026) == null, "a later year cannot substitute a different release");
    }
}
