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

    private static void part(String text, TvVoiceCommand.Kind kind, TvVoiceCommand.Provider provider,
                             String title, String type, int year, int season, int episode) {
        TvVoiceCommand value = TvVoiceCommand.parse(text);
        assertCommand(value, text, kind, provider, title, type, year);
        require(value.season == season && value.episode == episode, text, "wrong season or episode");
    }

    private static void episodeReply(String text, boolean allowBare, int season, int episode, boolean bare) {
        TvVoiceCommand.EpisodeChoice value = TvVoiceCommand.episodeReply(text, allowBare);
        if (season == 0 && episode == 0) {
            require(value == null, text, "unexpected episode reply");
        } else {
            require(value != null && value.season == season && value.episode == episode && value.bare == bare,
                    text, "wrong episode reply");
        }
    }

    private static void series() {
        part("включи мультсериал Смешарики 2 сезон 3 серия на Окко", TvVoiceCommand.Kind.PLAY,
                TvVoiceCommand.Provider.OKKO, "Смешарики", "series", 0, 2, 3);
        part("включи 2 серию Смешариков", TvVoiceCommand.Kind.PLAY,
                null, "Смешарики", "series", 0, 0, 2);
        part("включи на Окко третью серию второго сезона сериала Друзья", TvVoiceCommand.Kind.PLAY,
                TvVoiceCommand.Provider.OKKO, "Друзья", "series", 0, 2, 3);
        part("найди сериал «Эпизод 3» второй сезон 2023 года", TvVoiceCommand.Kind.FIND,
                null, "Эпизод 3", "series", 2023, 2, 0);
        part("поставь пятую серию на Окко", TvVoiceCommand.Kind.EPISODE,
                TvVoiceCommand.Provider.OKKO, "", "series", 0, 0, 5);
        part("найди пятую серию на Окко", TvVoiceCommand.Kind.FIND,
                TvVoiceCommand.Provider.OKKO, "", "series", 0, 0, 5);
        part("сколько серий доступно?", TvVoiceCommand.Kind.EPISODES, null, "", "series", 0, 0, 0);
        part("сколько тут серий?", TvVoiceCommand.Kind.EPISODES, null, "", "series", 0, 0, 0);
        part("сколько всего вышло серий?", TvVoiceCommand.Kind.EPISODES, null, "", "series", 0, 0, 0);
        part("сколько серий в этом сериале?", TvVoiceCommand.Kind.EPISODES, null, "", "series", 0, 0, 0);
        part("сколько всего серий у мультсериала Смешарики на Окко?", TvVoiceCommand.Kind.EPISODES,
                TvVoiceCommand.Provider.OKKO, "Смешарики", "series", 0, 0, 0);
        part("сколько эпизодов в первом сезоне?", TvVoiceCommand.Kind.EPISODES, null, "", "series", 0, 1, 0);
        part("какие серии доступны в Окко?", TvVoiceCommand.Kind.EPISODES,
                TvVoiceCommand.Provider.OKKO, "", "series", 0, 0, 0);
        part("есть ли в Окко пятая серия?", TvVoiceCommand.Kind.EPISODES,
                TvVoiceCommand.Provider.OKKO, "", "series", 0, 0, 5);
        part("вышла ли уже третья серия второго сезона мультсериала Смешарики?", TvVoiceCommand.Kind.EPISODES,
                null, "Смешарики", "series", 0, 2, 3);
        part("доступен ли десятый эпизод?", TvVoiceCommand.Kind.EPISODES, null, "", "series", 0, 0, 10);
        part("включи сериал, второй сезон", TvVoiceCommand.Kind.EPISODE, null, "", "series", 0, 2, 0);
        route("включи первую или вторую серию сериала Друзья", TvVoiceCommand.Kind.AMBIGUOUS, null, "", null, 0);
        route("включи сериал Друзья 2023 серия", TvVoiceCommand.Kind.AMBIGUOUS, null, "", null, 0);
        episodeReply("Алиса, пятая серия.", false, 0, 5, false);
        episodeReply("серия номер двадцать третья", false, 0, 23, false);
        episodeReply("сезон 2 серия 3", false, 2, 3, false);
        episodeReply("второй сезон, третья серия", true, 2, 3, false);
        episodeReply("второй сезон и третья серия", true, 2, 3, false);
        episodeReply("шестая серия в четвёртом сезоне", true, 4, 6, false);
        episodeReply("двадцать пятая", true, 0, 25, true);
        episodeReply("сто двадцать пятая серия", false, 0, 125, false);
        episodeReply("5", false, 0, 0, false);
        episodeReply("5", true, 0, 5, true);
        episodeReply("2023", true, 0, 0, false);
        episodeReply("3 серия, 4 серия", true, 0, 0, false);
        episodeReply("какая пятая серия?", true, 0, 0, false);
        episodeReply("2 или 3", true, 0, 0, false);
        episodeReply("семь минут", true, 0, 0, false);
    }

    private static void playback(String text, TvVoiceCommand.PlaybackAction action, long positionMs) {
        TvVoiceCommand.Playback value = TvVoiceCommand.playback(text);
        require(action == null ? value == null : value != null && value.action == action
                && value.positionMs == positionMs, text, "wrong playback command");
    }

    private static void playback() {
        playback("Алиса, поставь фильм на паузу.", TvVoiceCommand.PlaybackAction.PAUSE, 0);
        playback("Поставь на паузу видео, пожалуйста", TvVoiceCommand.PlaybackAction.PAUSE, 0);
        playback("продолжи просмотр фильма", TvVoiceCommand.PlaybackAction.RESUME, 0);
        playback("включи фильм сначала", TvVoiceCommand.PlaybackAction.RESTART, 0);
        playback("перемотай в начало фильма", TvVoiceCommand.PlaybackAction.RESTART, 0);
        playback("перемотай фильм вперёд на две минуты и тридцать секунд", TvVoiceCommand.PlaybackAction.SEEK_BY, 150_000);
        playback("перемотай фильм на тридцать секунд", TvVoiceCommand.PlaybackAction.SEEK_BY, 30_000);
        playback("перемотай на минуту назад", TvVoiceCommand.PlaybackAction.SEEK_BY, -60_000);
        playback("перемотай назад на 12 секунд", TvVoiceCommand.PlaybackAction.SEEK_BY, -12_000);
        playback("перемотай фильм на 23 минуту 10 секунду", TvVoiceCommand.PlaybackAction.SEEK_TO, 1_390_000);
        playback("поставь фильм на двадцать третьей минуте и десятой секунде", TvVoiceCommand.PlaybackAction.SEEK_TO, 1_390_000);
        playback("включи фильм с 23:10", TvVoiceCommand.PlaybackAction.SEEK_TO, 1_390_000);
        for (String text : new String[]{"Алиса, продолжи фильм", "Продолжи смотреть фильм",
                "Продолжить фильм", "Продолжи просмотр фильма", "Продолжи последний фильм"}) {
            TvVoiceCommand.Playback continuation = TvVoiceCommand.playback(text);
            require(continuation != null && continuation.action == TvVoiceCommand.PlaybackAction.RESUME
                    && continuation.resumeLastFilm, text, "movie resume keeps the last-film fallback");
        }
        require(!TvVoiceCommand.playback("сними с паузы фильм").resumeLastFilm,
                "сними с паузы фильм", "pause toggle never opens history");
        require(!TvVoiceCommand.playback("продолжи сериал").resumeLastFilm,
                "продолжи сериал", "series resume never opens the last movie");
        require(TvVoiceCommand.playback("включи фильм с 23:10").resumeAfterSeek,
                "включи фильм с 23:10", "play from a timestamp resumes playback");
        require(!TvVoiceCommand.playback("перейди на 23:10").resumeAfterSeek,
                "перейди на 23:10", "seek alone preserves pause state");
        playback("перемотай на 1 : 23 : 10", TvVoiceCommand.PlaybackAction.SEEK_TO, 4_990_000);
        playback("перейди на пять минут, девять секунд", TvVoiceCommand.PlaybackAction.SEEK_TO, 309_000);
        playback("перейди на 0:00", TvVoiceCommand.PlaybackAction.SEEK_TO, 0);
        for (String text : new String[] {"пауза", "поставь на паузу", "продолжи", "поставь музыку на паузу",
                "поставь таймер на 5 минут", "включи песню с 1:20", "перемотай песню назад на 12 секунд",
                "поставь фильм в 12:00",
                "не ставь фильм на паузу", "поставь фильм на паузу?", "перемотай на 15 минут 78 секунд",
                "перемотай на 12:72", "перемотай на 80:00:00", "перемотай на 5 секунд и включи свет",
                "перемотай на две минуты две минуты", "перемотай на 10 секунд назад вперед"})
            playback(text, null, 0);
    }

    private static void contextualPlayback() {
        String title = TvVoiceCommand.contextualTitle("Алиса, включи мне Время первых.");
        require("Время первых".equals(title), "Время первых", "wrong bare film title");
        assertCommand(TvVoiceCommand.contextualPlay(title, null), "Время первых",
                TvVoiceCommand.Kind.PLAY, null, title, "video", 0);
        assertCommand(TvVoiceCommand.contextualPlay(title, "film"), "current mode is film",
                TvVoiceCommand.Kind.PLAY, null, title, "film", 0);
        assertCommand(TvVoiceCommand.contextualPlay("Друзья", "series"), "current mode is series",
                TvVoiceCommand.Kind.PLAY, null, "Друзья", "series", 0);
        require("Катюша".equals(TvVoiceCommand.contextualTitle("включи Катюша")), "Катюша", "wrong bare song title");
        require("Катюшу".equals(TvVoiceCommand.contextualTitle("Алиса, включи, пожалуйста, Катюшу")),
                "Катюшу", "polite phrasing not stripped");
        require("1917".equals(TvVoiceCommand.contextualTitle("запусти 1917")), "1917", "numeric title lost");
        require("Брат 2".equals(TvVoiceCommand.contextualTitle("поставь мне «Брат 2», пожалуйста")),
                "Брат 2", "quoted title not stripped");
        require("В бой идут одни старики".equals(TvVoiceCommand.contextualTitle("включи В бой идут одни старики")),
                "В бой идут одни старики", "ordinary preposition mistaken for a provider");
        require("Что? Где? Когда?".equals(TvVoiceCommand.contextualTitle("включи «Что? Где? Когда?»")),
                "Что? Где? Когда?", "punctuation inside quoted title treated as a command");
        for (String text : new String[] {null, "", "включи", "включи мне", "включи мне пожалуйста",
                "включи фильм Время первых", "включи сериал Друзья", "поставь вторую серию",
                "включи песню Династия", "включи музыку", "включи альбом Кино", "включи радио",
                "включи на Окко Время первых", "включи Время первых на Окко", "включи Кинопоиск",
                "открой Окко", "включи Первый канал", "включи телеканал НТВ", "включи канал Север",
                "включи Чебурашку", "включи Смешарики", "найди Время первых", "не включи Дюну",
                "включи мне не Дюну", "включи Дюну?", "включи Дюну и поставь таймер",
                "включи Дюну выключи звук", "включи поставь таймер", "включи звук", "поставь на паузу",
                "включи свет в гостиной", "включи таймер на три минуты", "включи следующий трек",
                "включи будильник", "включи телевизор", "включи приложение", "включи https://okko.tv",
                "включи okko.tv", "включи Дюну\nсейчас", "включи ...", "продолжи фильм"}) {
            require(TvVoiceCommand.contextualTitle(text) == null, text, "explicit or malformed request admits context");
        }
        require(TvVoiceCommand.contextualPlay("javascript:open", "film") == null,
                "unsafe target", "ineligible title built a television request");
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
                "есть ли погода", "доступен ли фильм Дюна?", "есть ли 3 серия или 4 серия?",
                "включи фильм Простоквашино и поставь таймер", "покажи фильм простоквашино выключи звук",
                "что есть на окко", "найди фильм Дюна?", "найди фильм Дюна？", "можешь включить на окко дюну",
                "включи фильм https://okko.tv/movie/name", "включи фильм <html>Дюна</html>",
                "найди на окко okko.tv", "покажи фильм javascript:телевизор", "включи фильм Дюна\nсейчас"
        }) require(TvVoiceCommand.parse(text) == null, text, "unexpected TV action");
        require(TvVoiceCommand.Provider.OKKO.packageName.equals("tv.okko.androidtv"), "Okko", "wrong package");
        require(TvVoiceCommand.Provider.YOUTUBE.packageName.equals("com.google.android.youtube.tv"),
                "YouTube", "TV package must take precedence");
        yearFollowUp();
        series();
        playback();
        contextualPlayback();
        System.out.println(checked + " TV admission assertions passed; no network or app launches");
    }
}
