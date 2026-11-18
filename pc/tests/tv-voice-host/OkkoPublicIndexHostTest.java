package local.station.voicebridge;

import java.util.Arrays;

public final class OkkoPublicIndexHostTest {
    private static final String OKKO = "https://okko.tv/";
    private static final String FILM = OKKO + "movie/prostokvashino-646943751";
    private static final String SERIES = OKKO + "serial/prostokvashino";
    private static final String IVAN = OKKO + "movie/ivan-vasilevich-menjaet-professiju";

    public static void main(String[] args) {
        String xml = "<urlset>" + loc(FILM) + loc(FILM) + loc(SERIES) + loc(IVAN)
                + loc(OKKO + "movie/prostokvashino-odni-doma")
                + loc(OKKO + "movie/prostokvashino-1234") + loc(OKKO + "movie/1917")
                + loc(OKKO + "movie/cheburashka") + loc(OKKO + "movie/cheburashka-123456")
                + loc(OKKO + "movie/novyjj-vykhodnojj")
                + loc("https://okko.tv.example/movie/prostokvashino")
                + loc(OKKO + "movie/prostokvashino/reviews") + "</urlset>";
        OkkoPublicIndex films = OkkoPublicIndex.parse(xml, false);
        check(films.candidates("Простоквашино").equals(Arrays.asList(FILM)), "deduplicate and reject sequel, clips, series");
        check(films.candidates("Иван Васильевич меняет профессию").equals(Arrays.asList(IVAN)), "provider transliteration");
        check(films.candidates("1917").equals(Arrays.asList(OKKO + "movie/1917")), "numeric title");
        check(films.candidates("Чебурашка").size() == 2, "preserve distinct titles with the same name");
        check(films.candidates("Новый выходной").equals(Arrays.asList(OKKO + "movie/novyjj-vykhodnojj")),
                "provider spelling for й and х");
        check(OkkoPublicIndex.parse(xml, true).candidates("Простоквашино").equals(Arrays.asList(SERIES)), "media type");
        try {
            OkkoPublicIndex.parse(xml.replace("</urlset>", ""), false);
            throw new AssertionError("truncated index accepted");
        } catch (IllegalArgumentException expected) { }
        System.out.println("Okko public index tests passed");
    }

    private static String loc(String link) { return "<url><loc>" + link + "</loc></url>"; }
    private static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
}
