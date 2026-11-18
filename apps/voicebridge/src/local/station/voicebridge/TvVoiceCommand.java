package local.station.voicebridge;

import java.text.Normalizer;
import java.time.Year;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Routes only finalized, explicit TV requests. A named source never expands to all providers. */
final class TvVoiceCommand {
    static final int MAX_TITLE_CHARS = 160;
    private static final int MAX_UTTERANCE_CHARS = 320;
    private static final String VIDEO_TYPES = "фильм|кино|мультфильм|мультик|мультсериал|сериал|видео|ролик";
    private static final String UNSUPPORTED_NAMES = "кино ?поиск(?:е|а)?|netflix|нетфликс(?:е)?|иви|ivi|"
            + "kion|кион(?:е)?|премьер(?:е)?|premier|more ?tv|море ?тв|wink|винк(?:е)?|"
            + "start|старт(?:е)?|amediateka|амедиатек[ае]|apple ?tv|эпл ?тв|prime ?video|"
            + "happ|хапп(?:е)?|hbo|max|дисней|disney(?: plus)?|смотрешк[ае]|смотрим|megogo|мегого|"
            + "сбер ?тв|кинопаб(?:е)?|hdrezka|хдрезк[ае]|фильм(?:икс|екс)|filmix|(?:веб[- ]?)?браузере";
    private static final Pattern WAKE = words("^(?:алиса[, ]+|пожалуйста[, ]+){0,2}");
    private static final Pattern LIST_APPS = words("^(?:какие (?:видео ?сервисы|приложения|кинотеатры)"
            + " (?:у меня )?(?:еще )?(?:есть|установлены)(?: (?:на телевизоре|на тв|на станции|у меня))?|"
            + "(?:назови|покажи|перечисли) (?:мне )?(?:список )?(?:установленные |установленных )?"
            + "(?:приложения|приложений|кинотеатры|кинотеатров|видео ?сервисы|видео ?сервисов)|"
            + "(?:список приложений|какие у меня (?:приложения|кинотеатры|видео ?сервисы)(?: (?:есть|установлены))?|"
            + "что (?:у меня )?установлено (?:на телевизоре|на тв|на станции)))$");
    private static final Pattern VERB = words("^(найди|отыщи|поищи|ищи|включи|поставь|запусти|покажи|открой|"
            + "хочу посмотреть|давай посмотрим)(?: мне)?(?:,? пожалуйста,?)? +(.+)$");
    private static final Pattern MEDIA = words("^(" + VIDEO_TYPES + ") +(.*)$");
    private static final String SOURCE_WORDS = "видео ?сервисе|кинотеатре|сервисе|приложении|источнике|сайте";
    private static final Pattern SOURCE_PREFIX = words("(?:^|[ ,])(?:на|в|через|из) +(?:(?:" + SOURCE_WORDS + ") +)?");
    private static final Pattern SOURCE_WORD = words("(?:^|[ ,])(?:" + SOURCE_WORDS + ")(?: |$)");
    private static final Pattern SOURCE_CONJUNCTION = words("(?:^|[ ,])(?:и|или|либо|а также) +");
    private static final Pattern UNKNOWN_SOURCE = words("(?:" + UNSUPPORTED_NAMES + ")(?=$|[ ,:])");
    private static final Pattern BARE_UNSUPPORTED = words("(?:приложение +)?(?:" + UNSUPPORTED_NAMES + ")");
    private static final Pattern QUESTION = words("(?:^| )(?:ли|можешь|можно|нельзя)(?=$|[ ,])");
    private static final Pattern MULTIPLE_ACTIONS = words("(?:^|[ ,:])(?:затем|потом|сначала|одновременно|заодно|"
            + "а не|не включай|не открывай|не ищи)(?=$|[ ,:])|"
            + "(?:^|[ ,:])(?:и|а|но) +(?:не +)?(?:включи|выключи|поставь|запусти|останови|найди|"
            + "поищи|ищи|открой|закрой|покажи|переключи|сделай|установи|прибавь|убавь|скажи)(?=$|[ ,:])");
    private static final Pattern EXTRA_ACTION = words("[ ,:!.]+(?:включи|выключи|поставь|запусти|останови|найди|"
            + "поищи|открой|закрой|покажи|переключи|установи|прибавь|убавь)(?=$|[ ,:])");
    private static final Pattern NEGATIVE = words("^не(?: |$)");
    private static final Pattern MUSIC = words("^(?:песн[яюи]|трек|альбом|музык[ау]|клип|плейлист|радио)(?: |$)");
    private static final Pattern CONTEXTUAL_PLAY = words("^(?:включи|поставь|запусти)"
            + "(?: +мне)?(?:,? +пожалуйста,?)? +(.+)$");
    private static final Pattern NON_TITLE = words("^(?:(?:теле ?канал|(?:тв|tv)[ -]?канал|канал|прямой эфир"
            + "|сезон|серию|эпизод|фильм|кино|мультфильм|мультик|мультсериал|сериал|видео|ролик"
            + "|таймер|будильник|громкость|настройки|приложение|режим|свет|лампу|лампочку"
            + "|розетку|кондиционер|обогреватель|светильник|чайник|блютуз|bluetooth|wifi|wi fi|вай фай"
            + "|интернет|vpn|впн|колонку|станцию|камеру|микрофон|запись|игру|питание"
            + "|следующ(?:ий|ую|ее)|предыдущ(?:ий|ую|ее))(?=$|[ ,:])"
            + "|звук$|(?:тв|телевизор|телек|экран|мне|пожалуйста)$"
            + "|на паузу(?=$|[ ,:])|с начала(?=$|[ ,:]))");
    private static final Pattern NOT_A_PLAY_REQUEST = words("(?:^|[ ,:])(?:не|нельзя|можно|можешь|"
            + "включить|поставить|запустить|что|как|почему)(?=$|[ ,:])");
    private static final String YEAR_DIGITS = "(?:18|19|20)[0-9]{2}";
    private static final Pattern EXPLICIT_DIGIT_YEAR = words("^(.*?)[ ,]+(?:\\((" + YEAR_DIGITS + ")\\)"
            + "|(?:(?:в|от) +)?(" + YEAR_DIGITS + ")(?:-?(?:го|м))? +год(?:а|у)?)$");
    private static final Pattern BARE_DIGIT_YEAR = Pattern.compile("^(.*?)[ ,]+(" + YEAR_DIGITS + ")$");
    private static final Pattern YEAR_REPLY = words("^(?:(?:в|от) +)?(.+?)(?: +год(?:а|у)?)?$");
    private static final Pattern DIGIT_YEAR_REPLY = words("^(?:\\((" + YEAR_DIGITS + ")\\)|(" + YEAR_DIGITS
            + ")(?:[-–—]?(?:й|го|м))?)$");
    private static final Pattern SPOKEN_YEAR = words("^(.*?)[ ,]+(?:(?:в|от) +)?("
            + "тысяча (?:восемьсот|девятьсот)(?: [а-яё]+){0,2}"
            + "|тысяча (?:восьмисот|девятисот|восемьсот|девятьсот)(?:ый|ого|ом)"
            + "|две тысячи(?: [а-яё]+){0,2}|двухтысячн(?:ый|ого|ом)) +год(?:а|у)$");
    private static final String[] YEAR_SMALL = {
            "", "один|перв(?:ый|ого|ом)", "два|втор(?:ой|ого|ом)", "три|трет(?:ий|ьего|ьем)",
            "четыре|четверт(?:ый|ого|ом)", "пять|пят(?:ый|ого|ом)", "шесть|шест(?:ой|ого|ом)",
            "семь|седьм(?:ой|ого|ом)", "восемь|восьм(?:ой|ого|ом)", "девять|девят(?:ый|ого|ом)",
            "десять|десят(?:ый|ого|ом)", "одиннадцать|одиннадцат(?:ый|ого|ом)",
            "двенадцать|двенадцат(?:ый|ого|ом)", "тринадцать|тринадцат(?:ый|ого|ом)",
            "четырнадцать|четырнадцат(?:ый|ого|ом)", "пятнадцать|пятнадцат(?:ый|ого|ом)",
            "шестнадцать|шестнадцат(?:ый|ого|ом)", "семнадцать|семнадцат(?:ый|ого|ом)",
            "восемнадцать|восемнадцат(?:ый|ого|ом)", "девятнадцать|девятнадцат(?:ый|ого|ом)"
    };
    private static final String[] YEAR_CARDINAL_TENS = {
            "", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят",
            "семьдесят", "восемьдесят", "девяносто"
    };
    private static final String[] YEAR_ORDINAL_TENS = {
            "", "", "двадцат", "тридцат", "сороков", "пятидесят", "шестидесят",
            "семидесят", "восьмидесят", "девяност"
    };
    private static final Pattern DOMAIN = words("[\\p{L}0-9-]+\\.(?:ru|com|net|org|рф|io|dev|tv|me|biz|online)(?![\\p{L}0-9])");
    private static final Pattern URI_SCHEME = words("(?:^|[ ,])(?:https?|ftp|file|intent|javascript|data|content|"
            + "okko|vkvideo|rutube|youtube|lampa):");
    private static final String[] HUNDREDS = {"", "сто", "двести", "триста", "четыреста", "пятьсот",
            "шестьсот", "семьсот", "восемьсот", "девятьсот"};
    private static final String QUANTITY_SUFFIX = "(?:[-–—]?(?:й|ая|ую|ой|го|м|ю))?";
    private static final String FEMININE_ORDINAL = "(?:ая|ую|ой)";
    private static final String COUNT_WORD = numberWords();
    private static final String QUANTITY = "(?:[0-9]{1,4}" + QUANTITY_SUFFIX
            + "|" + COUNT_WORD + "(?: " + COUNT_WORD + "){0,3})";
    private static final String SEASON_WORD = "сезон(?:а|е|ом|у)?";
    private static final String EPISODE_WORD = "сери(?:ю|я|и|е|ей)|эпизод(?:а|е|ом|у)?";
    private static final String SERIES_UNIT = "(?:" + SEASON_WORD + "|" + EPISODE_WORD + ")";
    private static final Pattern SERIES_SELECTOR = words("(?:^|(?<=[ ,:(]))(?:(?<before>" + QUANTITY
            + ") +(?<afterunit>" + SERIES_UNIT + ")|(?<beforeunit>" + SERIES_UNIT
            + ") +(?:номер +)?(?<after>" + QUANTITY + "))(?=$|[ ,:)])");
    private static final Pattern UNQUALIFIED_SELECTOR = words("^(?:" + QUANTITY + ")$");
    private static final Pattern EPISODES_QUESTION = words("^(?:(?:сколько (?:(?:всего|там|тут|в н[её]м|доступно|вышло) ){0,2}"
            + "(?:серий|эпизодов)(?: (?:всего|доступно|есть|вышло)){0,2})"
            + "|(?:какие (?:серии|эпизоды) (?:доступны|есть|вышли)))"
            + "(?: +(.*))?$");
    private static final Pattern EPISODE_AVAILABILITY = words("^(?:есть|доступен|доступн[аоы]|выш(?:ла|ел)) ли (?:уже )?(.+)$");
    private static final Pattern EPISODES_CONTEXT = words("^(?:там|тут|у него|у не[её]|в н[её]м|в ней"
            + "|в (?:этом|текущем) (?:мульт)?сериале)$");
    private static final Pattern EPISODES_TOPIC = words("^(?:у|в) +(?:(?:мульт)?сериал(?:е|а)? +)?");
    private static final Pattern SERIES_TITLE_LABEL = words("^(?:мульт)?сериала +");
    private static final Pattern SERIES_TARGET = words("^(?:этот |текущий )?(?:мульт)?сериал$");
    private static final Pattern SERIES_PREPOSITION = words("(?:^| )(?:(?:в|из|к|на|с) +)$");
    private static final Pattern SERIES_ALTERNATIVE = words("(?:^|[ ,])(?:или|либо)(?=$|[ ,:])");
    private static final Pattern SERIES_CONJUNCTION = words("^[ ,:]*и[ ,:]*$");
    private static final Pattern PLAYBACK_NEGATION = words("(?:^| )не(?: |$)");
    private static final String VIDEO_TARGET = "(?:этот |текущий )?(?:фильм|сериал|мультсериал|мультфильм|"
            + "мультик|видео|ролик|кино|серию|эпизод)";
    private static final String PLAYBACK_TARGET = "(?:" + VIDEO_TARGET + "|(?:этот |текущий )?(?:телеканал|тв-канал|прямой эфир))";
    private static final Pattern VIDEO_PAUSE = words("^(?:(?:поставь|поставить) +" + PLAYBACK_TARGET + " +на паузу?"
            + "|(?:поставь|поставить) +на паузу? +" + PLAYBACK_TARGET + "|приостанови +" + PLAYBACK_TARGET
            + "|пауза (?:в фильме|в сериале|на видео))$");
    private static final String CONTINUE = "(?:продолжи(?:ть)?|продолжай|возобнови(?:ть)?)";
    private static final Pattern RESUME_LAST_FILM = words("^" + CONTINUE
            + " +(?:(?:смотреть|просмотр) +)?(?:(?:этот|текущий|последний) +)?(?:фильм|фильма|кино)$");
    private static final Pattern VIDEO_RESUME = words("^(?:" + CONTINUE + "|сними с паузы) +"
            + "(?:(?:смотреть|просмотр) +)?(?:" + PLAYBACK_TARGET + "|фильма|сериала|видео)$");
    private static final Pattern VIDEO_RESTART = words("^(?:(?:включи|поставь|запусти|воспроизведи|смотри) +"
            + VIDEO_TARGET + " +(?:сначала|с начала|с самого начала)|(?:включи|запусти|поставь) +"
            + "(?:сначала|с начала) +" + VIDEO_TARGET + "|(?:перезапусти|начни заново) +" + VIDEO_TARGET
            + "|(?:перемотай|верни(?:сь)?) +(?:" + VIDEO_TARGET + " +)?(?:в начало|на начало|к началу)"
            + "(?: +(?:фильма|видео|серии|сериала))?)$");
    private static final Pattern SEEK = words("^(?<verb>перемотай|мотай|вернись|перейди|переключи|включи"
            + "|запусти|поставь|продолжи)(?: +" + VIDEO_TARGET + ")?"
            + "(?: +(?<direction>назад|впер[её]д))? +(?<preposition>на|к|с) +(?<time>.+?)"
            + "(?: +(?<trailing>назад|впер[её]д))?$");
    private static final Pattern PLAYBACK_CLOCK = Pattern.compile("^(?:([0-9]{1,2}):)?([0-9]{1,4}):([0-5]?[0-9])$");
    private static final Pattern PLAYBACK_UNIT = words("(?:(?<amount>" + QUANTITY + ") +)?"
            + "(?<unit>час(?:а|ов|е|у)?|минут(?:а|у|ы|е)?|секунд(?:а|у|ы|е)?)(?=$|[ ,])");
    private static final Pattern PLAYBACK_POINT = words("(?:^| )" + QUANTITY
            + " +(?:минут[уе]|секунд[уе]|часе)(?=$|[ ,])");
    private static final Pattern PLAYBACK_DELIMITER = words("(?: +и +| *, *| +)");
    private static final Pattern NUMBER_DIGITS = words("^([0-9]{1,4})" + QUANTITY_SUFFIX + "$");
    private static final int MAX_SEASON = 100, MAX_EPISODE = 999;
    private static final long MAX_POSITION_MS = 24L * 60 * 60 * 1000;

    enum Kind { FIND, PLAY, OPEN, LIST, EPISODE, EPISODES, UNSUPPORTED_SOURCE, AMBIGUOUS }
    enum PlaybackAction { PAUSE, RESUME, SEEK_TO, SEEK_BY, RESTART }

    static final class Playback {
        final PlaybackAction action;
        final long positionMs;
        final boolean resumeAfterSeek;
        final boolean resumeLastFilm;

        private Playback(PlaybackAction action, long positionMs) {
            this(action, positionMs, false);
        }

        private Playback(PlaybackAction action, long positionMs, boolean resumeAfterSeek) {
            this(action, positionMs, resumeAfterSeek, false);
        }

        private Playback(PlaybackAction action, long positionMs, boolean resumeAfterSeek, boolean resumeLastFilm) {
            this.action = action;
            this.positionMs = positionMs;
            this.resumeAfterSeek = resumeAfterSeek;
            this.resumeLastFilm = resumeLastFilm;
        }
    }

    static final class EpisodeChoice {
        final int season, episode;
        final boolean bare;

        private EpisodeChoice(int season, int episode, boolean bare) {
            this.season = season;
            this.episode = episode;
            this.bare = bare;
        }
    }

    enum Provider {
        OKKO("Окко", "tv.okko.androidtv", "ок ?ко|okko"),
        VK("VK Видео", "com.vk.tv", "в[еэк] ?ка ?видео|вк ?видео|в ?к ?видео|вконтакте|в контакте|vk ?video|vk|вк"),
        RUTUBE("RUTUBE", "ru.rutube.app", "ру ?туб(?:е)?|rutube"),
        YOUTUBE("YouTube", "com.google.android.youtube.tv", "ю ?т[ую]б(?:е)?|ютюб(?:е)?|you ?tube"),
        LAMPA("Lampa", "top.rootu.lampa", "ламп[аеу]|lampa");

        final String label;
        final String packageName;
        private final Pattern sourceName;
        private final Pattern standalone;

        Provider(String label, String packageName, String aliases) {
            this.label = label;
            this.packageName = packageName;
            sourceName = words("(?:" + aliases + ")(?=$|[ ,:])");
            standalone = words("(?:приложение +)?(?:" + aliases + ")");
        }
    }

    final String title;
    final Provider provider;
    final Kind kind;
    final String mediaType;
    final int year;
    final int season, episode;

    private TvVoiceCommand(Kind kind, Provider provider, String title, String mediaType, int year) {
        this(kind, provider, title, mediaType, year, 0, 0);
    }

    private TvVoiceCommand(Kind kind, Provider provider, String title, String mediaType, int year, int season,
                           int episode) {
        this.kind = kind;
        this.provider = provider;
        this.title = title;
        this.mediaType = mediaType;
        this.year = year;
        this.season = season;
        this.episode = episode;
    }

    TvVoiceCommand withYear(int year) {
        return new TvVoiceCommand(kind, provider, title, mediaType, year, season, episode);
    }

    TvVoiceCommand withEpisode(int season, int episode) {
        return new TvVoiceCommand(kind, provider, title, mediaType, year, season, episode);
    }

    static int followUpYear(String transcript) {
        String text = normalizeUtterance(transcript);
        if (text == null) return 0;
        text = text.trim().replaceFirst("[.!]+$", "").trim();
        text = WAKE.matcher(text).replaceFirst("");
        Matcher reply = YEAR_REPLY.matcher(text);
        if (!reply.matches()) return 0;
        String value = reply.group(1);
        Matcher digits = DIGIT_YEAR_REPLY.matcher(value);
        if (digits.matches()) {
            return Integer.parseInt(digits.group(1) == null ? digits.group(2) : digits.group(1));
        }
        return spokenReleaseYear(value);
    }

    static final class YearFollowUp {
        private static final long REPLY_TIMEOUT_MS = 180_000;
        private TvVoiceCommand pending;
        private long expiresAt;

        synchronized TvVoiceCommand resolve(String transcript, long now) {
            TvVoiceCommand command = parse(transcript);
            if (command != null) {
                clear();
                return command;
            }
            if (now >= expiresAt) clear();
            if (pending == null) return null;
            int year = followUpYear(transcript);
            if (year == 0) return null;
            command = pending.withYear(year);
            clear();
            return command;
        }

        synchronized void remember(TvVoiceCommand command, long now) {
            pending = command;
            expiresAt = now + REPLY_TIMEOUT_MS;
        }

        synchronized void clear() {
            pending = null;
        }
    }

    static TvVoiceCommand parse(String transcript) {
        String text = normalizeUtterance(transcript);
        if (text == null) return null;
        boolean questionMark = maskQuotes(text).indexOf('?') >= 0;
        text = text.trim().replaceFirst("[.!?]+$", "").trim();
        text = WAKE.matcher(text).replaceFirst("");
        if (LIST_APPS.matcher(text).matches()) return command(Kind.LIST, null);
        String unquotedText = maskQuotes(text);
        if (MULTIPLE_ACTIONS.matcher(unquotedText).find()) return null;
        Matcher count = EPISODES_QUESTION.matcher(text);
        if (count.matches()) return parseRequest(Kind.EPISODES, count.group(1) == null ? "" : count.group(1).trim(), false);
        Matcher availability = EPISODE_AVAILABILITY.matcher(text);
        if (availability.matches()) {
            String body = availability.group(1);
            SeriesSelection selection = seriesSelection(body);
            if (selection != null && selection.episode > 0) return parseRequest(Kind.EPISODES, body, false);
        }
        if (QUESTION.matcher(unquotedText).find()) return null;
        if (questionMark) return null;
        Matcher verb = VERB.matcher(text);
        if (!verb.matches()) return null;
        String action = verb.group(1).toLowerCase(Locale.ROOT);
        String body = verb.group(2).trim();
        if (MUSIC.matcher(body).find() || NEGATIVE.matcher(body).find()) return null;
        boolean find = action.equals("найди") || action.equals("отыщи") || action.equals("поищи") || action.equals("ищи");
        boolean open = action.equals("открой") || action.equals("запусти") || action.equals("включи");
        return parseRequest(find ? Kind.FIND : Kind.PLAY, body, open);
    }

    static boolean contextQuestion(String transcript) {
        String text = commandText(transcript);
        if (text == null) return false;
        switch (text.replaceFirst("[?!.]+$", "").replaceFirst("^скажи, +", "скажи ").trim()) {
            case "какой сейчас контекст": case "какой контекст сейчас": case "какой контекст":
            case "в каком ты сейчас контексте": case "в каком контексте мы сейчас":
            case "скажи какой сейчас контекст": case "скажи текущий контекст":
            case "какой сейчас режим": case "в каком ты сейчас режиме":
                return true;
            default:
                return false;
        }
    }

    /** A bare playback title can follow the currently active media mode. */
    static String contextualTitle(String transcript) {
        String text = normalizeUtterance(transcript);
        if (text == null) return null;
        text = WAKE.matcher(text.trim().replaceFirst("[.!]+$", "").trim()).replaceFirst("")
                .replaceFirst("(?iu),? +пожалуйста$", "");
        Matcher play = CONTEXTUAL_PLAY.matcher(text);
        if (!play.matches()) return null;
        String body = cleanTitle(play.group(1));
        String unquoted = maskQuotes(body);
        if (unquoted.indexOf('?') >= 0 || hasSeveralActions(maskQuotes(text))
                || NOT_A_PLAY_REQUEST.matcher(unquoted).find() || QUESTION.matcher(unquoted).find()
                || MUSIC.matcher(unquoted).find() || NON_TITLE.matcher(unquoted).find()
                || playback(transcript) != null || TvChannelCommand.parse(transcript) != null || parse(transcript) != null)
            return null;
        String title = stripTitleQuotes(body);
        if (title.isEmpty() || title.length() > MAX_TITLE_CHARS || !title.codePoints().anyMatch(Character::isLetterOrDigit))
            return null;
        return title;
    }

    static TvVoiceCommand contextualPlay(String title, String preferredType) {
        if (title == null || title.isEmpty() || title.length() > MAX_TITLE_CHARS || !safeText(title)
                || !title.codePoints().anyMatch(Character::isLetterOrDigit)) return null;
        String mediaType = "film".equals(preferredType) || "series".equals(preferredType) ? preferredType : "video";
        return new TvVoiceCommand(Kind.PLAY, null, title, mediaType, 0);
    }

    private static TvVoiceCommand parseRequest(Kind kind, String body, boolean open) {
        boolean find = kind == Kind.FIND;
        boolean count = kind == Kind.EPISODES;
        if (open || find) {
            for (Provider provider : Provider.values()) {
                if (provider.standalone.matcher(body).matches()) return command(open ? Kind.OPEN : Kind.AMBIGUOUS, provider);
            }
            if (BARE_UNSUPPORTED.matcher(body).matches()) return command(Kind.UNSUPPORTED_SOURCE, null);
        }

        Provider source = null;
        int sourceStart = -1;
        int sourceEnd = -1;
        String unquoted = maskQuotes(body);
        Matcher prefixes = SOURCE_PREFIX.matcher(unquoted);
        while (prefixes.find()) {
            int start = prefixes.start(), end = prefixes.end();
            Provider matched = null;
            for (Provider provider : Provider.values()) {
                Matcher name = provider.sourceName.matcher(unquoted).region(end, unquoted.length());
                if (name.lookingAt()) { matched = provider; end = name.end(); break; }
            }
            if (matched != null) {
                if (source != null) return command(Kind.AMBIGUOUS, null);
                source = matched;
                sourceStart = start;
                sourceEnd = end;
            } else if (explicitUnknownSource(unquoted, start, end) || start == 0 && find) {
                return command(Kind.UNSUPPORTED_SOURCE, null);
            }
        }
        if (source != null && hasSourceAlternative(unquoted)) return command(Kind.AMBIGUOUS, null);
        if (source != null) body = (body.substring(0, sourceStart) + " " + body.substring(sourceEnd)).trim();
        body = cleanTitle(body);
        if (MUSIC.matcher(body).find()) return null;

        SeriesSelection selection = seriesSelection(body);
        if (selection == null) return command(Kind.AMBIGUOUS, source);
        body = selection.title;
        if (count) body = EPISODES_CONTEXT.matcher(body).matches() ? "" : EPISODES_TOPIC.matcher(body).replaceFirst("");
        body = SERIES_TITLE_LABEL.matcher(body).replaceFirst("");
        String mediaType = null;
        Matcher media = MEDIA.matcher(body);
        if (media.matches()) {
            mediaType = type(media.group(1));
            body = media.group(2).trim();
        }
        if (EXTRA_ACTION.matcher(maskQuotes(body)).find()) return null;
        boolean selectedPart = selection.season != 0 || selection.episode != 0;
        if (selectedPart || count) {
            if (mediaType != null && !"series".equals(mediaType) && !"animation".equals(mediaType))
                return command(Kind.AMBIGUOUS, source);
            if (mediaType == null) mediaType = "series";
            if (body.isEmpty() || SERIES_TARGET.matcher(body).matches())
                return new TvVoiceCommand(count || find ? kind : Kind.EPISODE,
                    source, "", mediaType, 0, selection.season, selection.episode);
        }
        if (body.isEmpty() || body.matches("(?iu)" + VIDEO_TYPES))
            return source == null && mediaType == null ? null : command(Kind.AMBIGUOUS, source);
        int year = 0;
        String unquotedTitle = maskQuotes(body);
        Matcher explicitYear = EXPLICIT_DIGIT_YEAR.matcher(unquotedTitle);
        Matcher spokenYear = SPOKEN_YEAR.matcher(unquotedTitle);
        Matcher bareYear = BARE_DIGIT_YEAR.matcher(unquotedTitle);
        int titleEnd = body.length();
        if (explicitYear.matches()) {
            year = Integer.parseInt(explicitYear.group(2) == null ? explicitYear.group(3) : explicitYear.group(2));
            titleEnd = explicitYear.end(1);
        } else if (spokenYear.matches()) {
            year = spokenReleaseYear(spokenYear.group(2));
            if (year != 0) titleEnd = spokenYear.end(1);
        } else if (bareYear.matches()) {
            int guessed = Integer.parseInt(bareYear.group(2));
            // An unqualified far-future number is likely part of a title such as Blade Runner 2049.
            if (guessed <= Year.now().getValue() + 2) {
                year = guessed;
                titleEnd = bareYear.end(1);
            }
        }
        String title = stripTitleQuotes(body.substring(0, titleEnd).trim());
        String canonicalFranchise = franchiseTitle(title);
        if (source == null && mediaType == null && canonicalFranchise == null) return null;
        if (canonicalFranchise != null) title = canonicalFranchise;
        if (mediaType == null && "Смешарики".equals(canonicalFranchise)) mediaType = "animation";
        if (title.isEmpty() || title.length() > MAX_TITLE_CHARS || !title.codePoints().anyMatch(Character::isLetterOrDigit))
            return command(Kind.AMBIGUOUS, source);
        return new TvVoiceCommand(kind, source, title, mediaType, year, selection.season, selection.episode);
    }

    static EpisodeChoice episodeReply(String transcript, boolean allowBareNumber) {
        String text = commandText(transcript);
        if (text == null || text.indexOf('?') >= 0) return null;
        SeriesSelection selection = seriesSelection(text);
        if (selection == null) return null;
        if (selection.title.isEmpty() && (selection.season > 0 || selection.episode > 0))
            return new EpisodeChoice(selection.season, selection.episode, false);
        if (!allowBareNumber || !UNQUALIFIED_SELECTOR.matcher(text).matches()) return null;
        int episode = quantity(text);
        return episode > 0 && episode <= MAX_EPISODE ? new EpisodeChoice(0, episode, true) : null;
    }

    static Playback playback(String transcript) {
        String text = commandText(transcript);
        if (text == null || text.indexOf('?') >= 0 || PLAYBACK_NEGATION.matcher(text).find()) return null;
        if (VIDEO_PAUSE.matcher(text).matches()) return new Playback(PlaybackAction.PAUSE, 0);
        if (RESUME_LAST_FILM.matcher(text).matches()) return new Playback(PlaybackAction.RESUME, 0, false, true);
        if (VIDEO_RESUME.matcher(text).matches()) return new Playback(PlaybackAction.RESUME, 0);
        if (VIDEO_RESTART.matcher(text).matches()) return new Playback(PlaybackAction.RESTART, 0);
        Matcher seek = SEEK.matcher(text);
        if (!seek.matches()) return null;
        String direction = seek.group("direction");
        String trailing = seek.group("trailing");
        if (direction != null && trailing != null) return null;
        if (direction == null) direction = trailing;
        String time = seek.group("time").replaceAll(" *: *", ":");
        long positionMs = playbackTime(time);
        if (positionMs < 0) return null;
        String verb = seek.group("verb").toLowerCase(Locale.ROOT);
        boolean rewind = verb.equals("перемотай") || verb.equals("мотай");
        String preposition = seek.group("preposition").toLowerCase(Locale.ROOT);
        boolean relative = direction != null || rewind && preposition.equals("на")
                && time.indexOf(':') < 0 && !PLAYBACK_POINT.matcher(time).find();
        if (relative && (positionMs == 0 || !preposition.equals("на"))) return null;
        if ("назад".equalsIgnoreCase(direction)) positionMs = -positionMs;
        boolean resume = !relative && (verb.equals("включи") || verb.equals("запусти") || verb.equals("продолжи"));
        return new Playback(relative ? PlaybackAction.SEEK_BY : PlaybackAction.SEEK_TO, positionMs, resume);
    }

    static Playback contextualPlayback(String transcript) {
        String text = commandText(transcript);
        if (text == null) return null;
        switch (text) {
            case "пауза": case "поставь на паузу": case "поставь на пауз":
                return new Playback(PlaybackAction.PAUSE, 0);
            case "продолжи": case "продолжи воспроизведение": case "возобнови воспроизведение":
                return new Playback(PlaybackAction.RESUME, 0);
            default:
                return null;
        }
    }

    private static long playbackTime(String text) {
        Matcher clock = PLAYBACK_CLOCK.matcher(text);
        if (clock.matches()) {
            int hours = clock.group(1) == null ? 0 : Integer.parseInt(clock.group(1));
            int minutes = Integer.parseInt(clock.group(2));
            int seconds = Integer.parseInt(clock.group(3));
            if (clock.group(1) != null && minutes >= 60) return -1;
            long ms = (hours * 3600L + minutes * 60L + seconds) * 1000;
            return ms <= MAX_POSITION_MS ? ms : -1;
        }
        Matcher component = PLAYBACK_UNIT.matcher(text);
        Matcher delimiter = PLAYBACK_DELIMITER.matcher(text);
        long total = 0;
        int offset = 0, previousUnit = 0;
        while (component.region(offset, text.length()).lookingAt()) {
            String unit = component.group("unit").toLowerCase(Locale.ROOT);
            int seconds = unit.startsWith("час") ? 3600 : unit.startsWith("минут") ? 60 : 1;
            String amount = component.group("amount");
            int value = amount == null ? singleUnit(unit) : quantity(amount);
            if (value < 0 || previousUnit != 0 && (seconds >= previousUnit || value >= 60)) return -1;
            total += value * seconds * 1000L;
            if (total > MAX_POSITION_MS) return -1;
            previousUnit = seconds;
            offset = component.end();
            if (offset == text.length()) return total;
            if (!delimiter.region(offset, text.length()).lookingAt()) return -1;
            offset = delimiter.end();
        }
        return -1;
    }

    private static int singleUnit(String unit) {
        return "час".equals(unit) || "минуту".equals(unit) || "секунду".equals(unit) ? 1 : -1;
    }

    private static SeriesSelection seriesSelection(String body) {
        String unquoted = maskQuotes(body).replace('ё', 'е').replace('Ё', 'Е');
        Matcher selector = SERIES_SELECTOR.matcher(unquoted);
        StringBuilder title = new StringBuilder();
        int cursor = 0, season = 0, episode = 0;
        while (selector.find()) {
            String before = selector.group("before");
            int number = quantity(before == null ? selector.group("after") : before);
            String unit = before == null ? selector.group("beforeunit") : selector.group("afterunit");
            if (number < 1) return null;
            if (unit.regionMatches(true, 0, "сезон", 0, 5)) {
                if (season != 0 || number > MAX_SEASON) return null;
                season = number;
            } else {
                if (episode != 0 || number > MAX_EPISODE) return null;
                episode = number;
            }
            String remainder = body.substring(cursor, selector.start());
            if (cursor == 0 || !SERIES_CONJUNCTION.matcher(remainder).matches())
                title.append(SERIES_PREPOSITION.matcher(remainder).replaceFirst("")).append(' ');
            cursor = selector.end();
        }
        if (cursor == 0) return new SeriesSelection(body, 0, 0);
        if (SERIES_ALTERNATIVE.matcher(unquoted).find()) return null;
        title.append(body.substring(cursor));
        String remaining = cleanTitle(title.toString().replaceAll("\\( *\\)", " "));
        return new SeriesSelection(remaining, season, episode);
    }

    private static final class SeriesSelection {
        final String title;
        final int season, episode;

        SeriesSelection(String title, int season, int episode) {
            this.title = title;
            this.season = season;
            this.episode = episode;
        }
    }

    static String commandText(String transcript) {
        String text = normalizeUtterance(transcript);
        if (text == null) return null;
        return WAKE.matcher(text.trim().replaceFirst("[.!]+$", "").trim()).replaceFirst("")
                .replaceFirst("(?iu),? +пожалуйста$", "").toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    static String cleanTitle(String title) {
        return title.trim().replaceFirst("^[ ,:]+", "").replaceFirst("[ ,:]+$", "").replaceAll(" {2,}", " ");
    }

    private static int quantity(String phrase) {
        String value = phrase.toLowerCase(Locale.ROOT).replace('ё', 'е');
        Matcher digits = NUMBER_DIGITS.matcher(value);
        if (digits.matches()) return Integer.parseInt(digits.group(1));
        if (value.equals("ноль") || value.equals("нуль")) return 0;
        int separator = value.indexOf(' ');
        String first = separator < 0 ? value : value.substring(0, separator);
        for (int i = 1; i < HUNDREDS.length; i++) {
            if (first.equals(HUNDREDS[i])) {
                if (separator < 0) return i * 100;
                int remainder = quantityBelowHundred(value.substring(separator + 1));
                return remainder < 0 ? -1 : i * 100 + remainder;
            }
        }
        return quantityBelowHundred(value);
    }

    private static int quantityBelowHundred(String value) {
        int known = yearBelowHundred(value);
        if (known > 0) return known;
        int small = feminineSmall(value);
        if (small > 0) return small;
        for (int i = 2; i < YEAR_CARDINAL_TENS.length; i++) {
            if (value.matches(YEAR_ORDINAL_TENS[i] + FEMININE_ORDINAL)) return i * 10;
            if (value.startsWith(YEAR_CARDINAL_TENS[i] + " ")) {
                int units = feminineSmall(value.substring(YEAR_CARDINAL_TENS[i].length() + 1));
                if (units > 0 && units < 10) return i * 10 + units;
            }
        }
        return -1;
    }

    private static int feminineSmall(String value) {
        if (value.matches("одн(?:а|у|ой)|перв" + FEMININE_ORDINAL)) return 1;
        if (value.matches("две|втор" + FEMININE_ORDINAL)) return 2;
        if (value.matches("треть(?:я|ю|ей)")) return 3;
        for (int i = 4; i < YEAR_SMALL.length; i++) {
            if (value.matches(ordinalStem(i) + FEMININE_ORDINAL)) return i;
        }
        return -1;
    }

    private static String ordinalStem(int value) {
        String pattern = YEAR_SMALL[value];
        return pattern.substring(pattern.indexOf('|') + 1, pattern.indexOf('('));
    }

    private static String numberWords() {
        StringBuilder expression = new StringBuilder("(?:ноль|нуль|одн(?:а|у|ой)|две|перв" + FEMININE_ORDINAL
                + "|втор" + FEMININE_ORDINAL + "|треть(?:я|ю|ей)");
        for (int i = 1; i < YEAR_SMALL.length; i++) {
            expression.append('|').append(YEAR_SMALL[i]);
            if (i >= 4) expression.append('|').append(ordinalStem(i)).append(FEMININE_ORDINAL);
        }
        for (int i = 2; i < YEAR_CARDINAL_TENS.length; i++)
            expression.append('|').append(YEAR_CARDINAL_TENS[i]).append('|').append(YEAR_ORDINAL_TENS[i])
                    .append("(?:ый|ой|ого|ом|ая|ую)");
        for (int i = 1; i < HUNDREDS.length; i++) expression.append('|').append(HUNDREDS[i]);
        return expression.append(')').toString();
    }

    private static int spokenReleaseYear(String phrase) {
        String normalized = phrase.toLowerCase(Locale.ROOT).replace('ё', 'е');
        if (normalized.matches("двухтысячн(?:ый|ого|ом)")) return 2000;
        if (normalized.matches("тысяча (?:восьмисот|восемьсот)(?:ый|ого|ом)")) return 1800;
        if (normalized.matches("тысяча (?:девятисот|девятьсот)(?:ый|ого|ом)")) return 1900;
        String[] centuries = {"тысяча восемьсот", "тысяча девятьсот", "две тысячи"};
        for (int i = 0; i < centuries.length; i++) {
            String prefix = centuries[i];
            if (normalized.equals(prefix)) return 1800 + i * 100;
            if (!normalized.startsWith(prefix + " ")) continue;
            int number = yearBelowHundred(normalized.substring(prefix.length() + 1));
            if (number > 0) return 1800 + i * 100 + number;
        }
        return 0;
    }

    private static int yearBelowHundred(String phrase) {
        int small = yearSmall(phrase);
        if (small > 0) return small;
        for (int i = 2; i < YEAR_CARDINAL_TENS.length; i++) {
            String tens = YEAR_CARDINAL_TENS[i];
            if (phrase.equals(tens) || phrase.matches(YEAR_ORDINAL_TENS[i] + "(?:ый|ой|ого|ом)")) return i * 10;
            if (phrase.startsWith(tens + " ")) {
                int unit = yearSmall(phrase.substring(tens.length() + 1));
                if (unit > 0 && unit < 10) return i * 10 + unit;
            }
        }
        return 0;
    }

    private static int yearSmall(String phrase) {
        for (int i = 1; i < YEAR_SMALL.length; i++)
            if (phrase.matches(YEAR_SMALL[i])) return i;
        return 0;
    }

    private static TvVoiceCommand command(Kind kind, Provider provider) {
        return new TvVoiceCommand(kind, provider, "", null, 0);
    }

    private static String type(String value) {
        if (value.equalsIgnoreCase("сериал") || value.equalsIgnoreCase("мультсериал")) return "series";
        if (value.equalsIgnoreCase("мультфильм") || value.equalsIgnoreCase("мультик")) return "animation";
        if (value.equalsIgnoreCase("видео") || value.equalsIgnoreCase("ролик")) return "video";
        return "film";
    }

    private static String franchiseTitle(String title) {
        switch (title.toLowerCase(Locale.ROOT)) {
            case "смешарики":
            case "смешариков":
                return "Смешарики";
            case "чебурашка":
            case "чебурашку":
                return "Чебурашка";
            default:
                return null;
        }
    }

    private static boolean hasSourceAlternative(String text) {
        Matcher conjunction = SOURCE_CONJUNCTION.matcher(text);
        while (conjunction.find()) {
            int end = conjunction.end();
            if (UNKNOWN_SOURCE.matcher(text).region(end, text.length()).lookingAt()) return true;
            for (Provider provider : Provider.values()) {
                if (provider.sourceName.matcher(text).region(end, text.length()).lookingAt()) return true;
            }
        }
        return false;
    }

    static boolean hasOtherVideoSource(String text) {
        Matcher prefixes = SOURCE_PREFIX.matcher(text);
        while (prefixes.find()) {
            int start = prefixes.start(), end = prefixes.end();
            if (otherNamedVideoSourceEnd(text, end) >= 0 || explicitUnknownSource(text, start, end)) return true;
        }
        return false;
    }

    static String afterLeadingOtherVideoSource(String text) {
        Matcher prefix = SOURCE_PREFIX.matcher(text);
        int end = prefix.lookingAt() ? otherNamedVideoSourceEnd(text, prefix.end()) : -1;
        return end < 0 ? text : cleanTitle(text.substring(end));
    }

    private static int otherNamedVideoSourceEnd(String text, int from) {
        for (Provider provider : Provider.values()) {
            if (provider == Provider.OKKO) continue;
            Matcher source = provider.sourceName.matcher(text).region(from, text.length());
            if (source.lookingAt()) return source.end();
        }
        Matcher unknown = UNKNOWN_SOURCE.matcher(text).region(from, text.length());
        return unknown.lookingAt() ? unknown.end() : -1;
    }

    private static boolean explicitUnknownSource(String text, int start, int end) {
        String prefix = text.substring(start, end);
        return UNKNOWN_SOURCE.matcher(text).region(end, text.length()).lookingAt()
                || SOURCE_WORD.matcher(prefix).find()
                || prefix.trim().regionMatches(true, 0, "через ", 0, 6);
    }

    static boolean hasSeveralActions(String text) {
        return MULTIPLE_ACTIONS.matcher(text).find() || EXTRA_ACTION.matcher(text).find();
    }

    /** Hide quoted titles while finding clauses and source names, preserving string offsets. */
    static String maskQuotes(String text) {
        StringBuilder masked = new StringBuilder(text);
        for (int i = 0; i < text.length(); i++) {
            char first = text.charAt(i);
            int end = first == '"' ? text.indexOf('"', i + 1) : first == '«' ? text.indexOf('»', i + 1) : -1;
            if (end < 0) continue;
            for (int j = i; j <= end; j++) masked.setCharAt(j, '\uffff');
            i = end;
        }
        return masked.toString();
    }

    static String stripTitleQuotes(String text) {
        if (text.length() >= 2 && (text.startsWith("\"") && text.endsWith("\"")
                || text.startsWith("«") && text.endsWith("»"))) return text.substring(1, text.length() - 1).trim();
        return text;
    }

    private static String normalizeUtterance(String transcript) {
        if (transcript == null || transcript.length() > MAX_UTTERANCE_CHARS) return null;
        String text = Normalizer.normalize(transcript, Normalizer.Form.NFKC);
        if (text.length() > MAX_UTTERANCE_CHARS || !safeText(text)) return null;
        return text.replaceAll("[ \\p{Zs}]+", " ");
    }

    private static boolean safeText(String text) {
        if (DOMAIN.matcher(text).find() || URI_SCHEME.matcher(text).find()) return false;
        for (int i = 0; i < text.length();) {
            int point = text.codePointAt(i);
            if (!Character.isLetterOrDigit(point) && Character.getType(point) != Character.NON_SPACING_MARK
                    && Character.getType(point) != Character.SPACE_SEPARATOR
                    && "-'’–—.,:!?()«»\"".indexOf(point) < 0) return false;
            i += Character.charCount(point);
        }
        return true;
    }

    private static Pattern words(String expression) {
        return Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
