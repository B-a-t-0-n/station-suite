package local.station.voicebridge;

/** Runs the production parser with synthetic finalized speech and no Android or network calls. */
public final class TvVoiceCommandHostTest {
    private static int checked;

    private static void route(String text, TvVoiceCommand.Kind kind, TvVoiceCommand.Provider provider,
                              String title, String type, int year) {
        TvVoiceCommand actual = TvVoiceCommand.parse(text);
        require(actual != null && actual.kind == kind, text, "wrong kind");
        require(actual.provider == provider, text, "wrong provider");
        require(title.equals(actual.title), text, "wrong title");
        require(type == null ? actual.mediaType == null : type.equals(actual.mediaType), text, "wrong media type");
        require(actual.year == year, text, "wrong year");
    }

    private static void require(boolean good, String text, String issue) {
        checked++;
        if (!good) throw new AssertionError(issue + " for " + text);
    }

    public static void main(String[] args) {
        route("Алиса, включи мне фильм простаквашено.", TvVoiceCommand.Kind.PLAY,
                null, "простаквашено", "film", 0);
        route("найди мне на окко простаквашено", TvVoiceCommand.Kind.FIND,
                TvVoiceCommand.Provider.OKKO, "простаквашено", null, 0);
        route("найди в ок ко фильм Простоквашино 2026 года!", TvVoiceCommand.Kind.FIND,
                TvVoiceCommand.Provider.OKKO, "Простоквашино", "film", 2026);
        route("Алиса, найди, пожалуйста, мультфильм «Трое в Простоквашино» на Окко", TvVoiceCommand.Kind.FIND,
                TvVoiceCommand.Provider.OKKO, "Трое в Простоквашино", "film", 0);
        route("покажи фильм Кошмар на улице Вязов (1984)", TvVoiceCommand.Kind.PLAY,
                null, "Кошмар на улице Вязов", "film", 1984);
        route("найди фильм Один дома на ВК Видео", TvVoiceCommand.Kind.FIND,
                TvVoiceCommand.Provider.VK, "Один дома", "film", 0);
        route("поставь в Рутубе видео Группа крови", TvVoiceCommand.Kind.PLAY,
                TvVoiceCommand.Provider.RUTUBE, "Группа крови", "video", 0);
        route("хочу посмотреть сериал Друзья в лампе", TvVoiceCommand.Kind.PLAY,
                TvVoiceCommand.Provider.LAMPA, "Друзья", "series", 0);
        route("открой приложение ютуб", TvVoiceCommand.Kind.OPEN,
                TvVoiceCommand.Provider.YOUTUBE, "", null, 0);
        route("какие у меня приложения есть?", TvVoiceCommand.Kind.LIST, null, "", null, 0);
        route("найди фильм 1917", TvVoiceCommand.Kind.FIND, null, "1917", "film", 0);
        route("покажи фильм «Едем на Окко?»", TvVoiceCommand.Kind.PLAY, null, "Едем на Окко?", "film", 0);
        route("найди фильм Простоквашино на Кинопоиске", TvVoiceCommand.Kind.UNSUPPORTED_SOURCE, null, "", null, 0);
        route("найди мне в кинотеатре Новое Кино фильм Простоквашино", TvVoiceCommand.Kind.UNSUPPORTED_SOURCE,
                null, "", null, 0);
        route("найди фильм Простоквашино в Окко или на Рутубе", TvVoiceCommand.Kind.AMBIGUOUS,
                null, "", null, 0);
        route("найди в Окко или Рутубе Простоквашино", TvVoiceCommand.Kind.AMBIGUOUS,
                null, "", null, 0);
        route("найди на окко", TvVoiceCommand.Kind.AMBIGUOUS, TvVoiceCommand.Provider.OKKO, "", null, 0);
        for (String text : new String[] {
                "включи песню группа крови", "включи песню на ютубе", "найди на окко музыку группа крови",
                "алиса включи музыку", "поставь таймер на пять минут", "не включи фильм простоквашино",
                "включи фильм Простоквашино и поставь таймер", "покажи фильм простоквашино выключи звук",
                "что есть на окко", "найди фильм Дюна?", "найди фильм Дюна？", "можешь включить на окко дюну",
                "включи фильм https://okko.tv/movie/name", "включи фильм <html>Дюна</html>",
                "найди на окко okko.tv", "покажи фильм javascript:телевизор", "включи фильм Дюна\nсейчас"
        }) require(TvVoiceCommand.parse(text) == null, text, "unexpected TV action");
        require(TvVoiceCommand.Provider.OKKO.packageName.equals("tv.okko.androidtv"), "Okko", "wrong package");
        require(TvVoiceCommand.Provider.YOUTUBE.packageName.equals("com.google.android.youtube.tv"),
                "YouTube", "TV package must take precedence");
        System.out.println(checked + " TV admission assertions passed; no network or app launches");
    }
}
