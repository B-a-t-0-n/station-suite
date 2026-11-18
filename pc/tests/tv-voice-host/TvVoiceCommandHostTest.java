package local.station.voicebridge;

/** Runs the production parser with synthetic finalized speech and no Android or network calls. */
public final class TvVoiceCommandHostTest {
    private static int checked;

    private static void route(String text, TvVoiceCommand.Kind kind, TvVoiceCommand.Provider provider,
                              String title, String type, int year) {
        assertCommand(TvVoiceCommand.parse(text), text, kind, provider, title, type, year);
    }

    private static void assertCommand(TvVoiceCommand actual, String text, TvVoiceCommand.Kind kind,
                                      TvVoiceCommand.Provider provider, String title, String type, int year) {
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

    private static void checkYear(String reply, int year) {
        require(TvVoiceCommand.followUpYear(reply) == year, reply, "wrong standalone year");
    }

    private static void yearFollowUp() {
        checkYear("2023", 2023);
        checkYear("2023 года", 2023);
        checkYear("Алиса, в 2023-м году.", 2023);
        checkYear("(2023)", 2023);
        checkYear("２０２３", 2023);
        checkYear("две тысячи двадцать третьего года", 2023);
        checkYear("две тысячи двадцать третий", 2023);
        checkYear("тысяча девятьсот восемьдесят четвертого года", 1984);
        checkYear("двухтысячного года", 2000);
        for (String text : new String[] {null, "", "включи фильм 2023 года", "2023 или 1969", "2023?",
                "2023 года и включи музыку", "двухтысячный или другой", "2023\n", "сказка 2023", "1234"})
            checkYear(text, 0);

        TvVoiceCommand.YearFollowUp pending = new TvVoiceCommand.YearFollowUp();
        require(pending.resolve("2023", 1) == null, "2023", "year treated as a new TV action");
        TvVoiceCommand play = TvVoiceCommand.parse("Алиса, включи на Окко мультик Чебурашку");
        pending.remember(play, 10);
        require(pending.resolve("какая погода", 20) == null, "weather", "unrelated utterance was hijacked");
        assertCommand(pending.resolve("2023 года", 30), "play continuation", TvVoiceCommand.Kind.PLAY,
                TvVoiceCommand.Provider.OKKO, "Чебурашка", "animation", 2023);
        require(play.year == 0, "original command", "year continuation mutated the original command");
        require(pending.resolve("2023", 40) == null, "repeat year", "year continuation was replayed");

        TvVoiceCommand find = TvVoiceCommand.parse("найди Чебурашку");
        pending.remember(find, 50);
        assertCommand(pending.resolve("1969", 180_049), "find continuation", TvVoiceCommand.Kind.FIND,
                null, "Чебурашка", null, 1969);
        pending.remember(find, 200_000);
        require(pending.resolve("2023", 380_000) == null, "expired year", "expired continuation was accepted");
        pending.remember(find, 400_000);
        assertCommand(pending.resolve("найди фильм Дюна 1984", 400_001), "new TV request",
                TvVoiceCommand.Kind.FIND, null, "Дюна", "film", 1984);
        require(pending.resolve("2023", 400_002) == null, "year after new TV request",
                "pending original request was not cleared");
        pending.remember(play, 400_003);
        pending.clear();
        require(pending.resolve("2023", 400_004) == null, "cleared year", "explicit clear lost");
    }

    public static void main(String[] args) {
        route("Алиса, включи мне фильм простаквашено.", TvVoiceCommand.Kind.PLAY,
                null, "простаквашено", "film", 0);
        route("найди мне на окко простаквашено", TvVoiceCommand.Kind.FIND,
                TvVoiceCommand.Provider.OKKO, "простаквашено", null, 0);
        route("найди в ок ко фильм Простоквашино 2026 года!", TvVoiceCommand.Kind.FIND,
                TvVoiceCommand.Provider.OKKO, "Простоквашино", "film", 2026);
        route("найди на Окко фильм Простоквашино две тысячи двадцать шестого года", TvVoiceCommand.Kind.FIND,
                TvVoiceCommand.Provider.OKKO, "Простоквашино", "film", 2026);
        route("найди фильм Простоквашино 2026", TvVoiceCommand.Kind.FIND,
                null, "Простоквашино", "film", 2026);
        route("включи фильм «Интерстеллар» 2014-го года на Окко", TvVoiceCommand.Kind.PLAY,
                TvVoiceCommand.Provider.OKKO, "Интерстеллар", "film", 2014);
        route("найди фильм Дюна тысяча девятьсот восемьдесят четвёртого года", TvVoiceCommand.Kind.FIND,
                null, "Дюна", "film", 1984);
        route("найди фильм Дюна в тысяча девятьсот восемьдесят четвёртом году", TvVoiceCommand.Kind.FIND,
                null, "Дюна", "film", 1984);
        route("найди фильм Гладиатор двухтысячного года", TvVoiceCommand.Kind.FIND,
                null, "Гладиатор", "film", 2000);
        route("найди фильм Ирония судьбы тысяча девятьсот семьдесят пять года", TvVoiceCommand.Kind.FIND,
                null, "Ирония судьбы", "film", 1975);
        route("найди фильм «Простоквашино две тысячи двадцать шестого года»", TvVoiceCommand.Kind.FIND,
                null, "Простоквашино две тысячи двадцать шестого года", "film", 0);
        route("найди фильм «Дюна 1984»", TvVoiceCommand.Kind.FIND, null, "Дюна 1984", "film", 0);
        route("найди фильм Бегущий по лезвию 2049", TvVoiceCommand.Kind.FIND,
                null, "Бегущий по лезвию 2049", "film", 0);
        route("найди фильм Космическая одиссея две тысячи один", TvVoiceCommand.Kind.FIND,
                null, "Космическая одиссея две тысячи один", "film", 0);
        route("найди фильм Пример две тысячи кошачьего года", TvVoiceCommand.Kind.FIND,
                null, "Пример две тысячи кошачьего года", "film", 0);
        route("Алиса, найди, пожалуйста, мультфильм «Трое в Простоквашино» на Окко", TvVoiceCommand.Kind.FIND,
                TvVoiceCommand.Provider.OKKO, "Трое в Простоквашино", "animation", 0);
        route("Алиса, найди Чебурашку", TvVoiceCommand.Kind.FIND, null, "Чебурашка", null, 0);
        route("включи Чебурашку 2023 года", TvVoiceCommand.Kind.PLAY, null, "Чебурашка", null, 2023);
        route("найди смешарики", TvVoiceCommand.Kind.FIND, null, "Смешарики", "animation", 0);
        route("включи смешариков", TvVoiceCommand.Kind.PLAY, null, "Смешарики", "animation", 0);
        route("найди смешариков на Окко", TvVoiceCommand.Kind.FIND,
                TvVoiceCommand.Provider.OKKO, "Смешарики", "animation", 0);
        route("найди мультик Смешарики", TvVoiceCommand.Kind.FIND, null, "Смешарики", "animation", 0);
        route("найди фильм Смешарики", TvVoiceCommand.Kind.FIND, null, "Смешарики", "film", 0);
        route("включи мультсериал Смешарики в Окко", TvVoiceCommand.Kind.PLAY,
                TvVoiceCommand.Provider.OKKO, "Смешарики", "series", 0);
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
        route("найди фильм «1917» две тысячи девятнадцатого года",
                TvVoiceCommand.Kind.FIND, null, "1917", "film", 2019);
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
                "поставь песню Смешарики", "включи Чебурашку и останови музыку", "найди Чебурашку песню",
                "найди Дюну", "алиса включи группу Кино", "2023",
                "алиса включи музыку", "поставь таймер на пять минут", "не включи фильм простоквашино",
                "включи фильм Простоквашино и поставь таймер", "покажи фильм простоквашино выключи звук",
                "что есть на окко", "найди фильм Дюна?", "найди фильм Дюна？", "можешь включить на окко дюну",
                "включи фильм https://okko.tv/movie/name", "включи фильм <html>Дюна</html>",
                "найди на окко okko.tv", "покажи фильм javascript:телевизор", "включи фильм Дюна\nсейчас"
        }) require(TvVoiceCommand.parse(text) == null, text, "unexpected TV action");
        require(TvVoiceCommand.Provider.OKKO.packageName.equals("tv.okko.androidtv"), "Okko", "wrong package");
        require(TvVoiceCommand.Provider.YOUTUBE.packageName.equals("com.google.android.youtube.tv"),
                "YouTube", "TV package must take precedence");
        yearFollowUp();
        System.out.println(checked + " TV admission assertions passed; no network or app launches");
    }
}
