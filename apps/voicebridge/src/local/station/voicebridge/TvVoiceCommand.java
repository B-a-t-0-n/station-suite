package local.station.voicebridge;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Routes only finalized, explicit TV requests. A named source never expands to all providers. */
final class TvVoiceCommand {
    static final int MAX_TITLE_CHARS = 160;
    private static final int MAX_UTTERANCE_CHARS = 320;
    private static final String VIDEO_TYPES = "фильм|кино|мультфильм|мультик|сериал|видео|ролик";
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
    private static final Pattern YEAR = Pattern.compile("^(.*?)[ ,]+(?:\\((18[0-9]{2}|19[0-9]{2}|20[0-9]{2})\\)"
            + "|(18[0-9]{2}|19[0-9]{2}|20[0-9]{2})(?: +год(?:а|у)?)?)$");
    private static final Pattern DOMAIN = words("[\\p{L}0-9-]+\\.(?:ru|com|net|org|рф|io|dev|tv|me|biz|online)(?![\\p{L}0-9])");
    private static final Pattern URI_SCHEME = words("(?:^|[ ,])(?:https?|ftp|file|intent|javascript|data|content|"
            + "okko|vkvideo|rutube|youtube|lampa):");

    enum Kind { FIND, PLAY, OPEN, LIST, UNSUPPORTED_SOURCE, AMBIGUOUS }

    enum Provider {
        OKKO("Окко", "tv.okko.androidtv", "ок ?ко|okko"),
        VK("VK Видео", "com.vk.tv", "в[эк] ?ка ?видео|вк ?видео|в ?к ?видео|вконтакте|в контакте|vk ?video|vk|вк"),
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

    private TvVoiceCommand(Kind kind, Provider provider, String title, String mediaType, int year) {
        this.kind = kind;
        this.provider = provider;
        this.title = title;
        this.mediaType = mediaType;
        this.year = year;
    }

    static TvVoiceCommand parse(String transcript) {
        if (transcript == null || transcript.length() > MAX_UTTERANCE_CHARS) return null;
        String text = Normalizer.normalize(transcript, Normalizer.Form.NFKC);
        if (text.length() > MAX_UTTERANCE_CHARS || !safeText(text)) return null;
        boolean questionMark = maskQuotes(text).indexOf('?') >= 0;
        text = text.replaceAll("[ \\p{Zs}]+", " ").trim().replaceFirst("[.!?]+$", "").trim();
        text = WAKE.matcher(text).replaceFirst("");
        if (LIST_APPS.matcher(text).matches()) return command(Kind.LIST, null);
        String unquotedText = maskQuotes(text);
        if (questionMark || QUESTION.matcher(unquotedText).find() || MULTIPLE_ACTIONS.matcher(unquotedText).find())
            return null;
        Matcher verb = VERB.matcher(text);
        if (!verb.matches()) return null;
        String action = verb.group(1).toLowerCase(java.util.Locale.ROOT);
        String body = verb.group(2).trim();
        if (MUSIC.matcher(body).find() || NEGATIVE.matcher(body).find()) return null;
        boolean find = action.equals("найди") || action.equals("отыщи") || action.equals("поищи") || action.equals("ищи");
        boolean open = action.equals("открой") || action.equals("запусти") || action.equals("включи");
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
            } else if (UNKNOWN_SOURCE.matcher(unquoted).region(end, unquoted.length()).lookingAt()
                    || SOURCE_WORD.matcher(unquoted.substring(start, end)).find()
                    || unquoted.substring(start, end).trim().regionMatches(true, 0, "через ", 0, 6)
                    || start == 0 && find) {
                return command(Kind.UNSUPPORTED_SOURCE, null);
            }
        }
        if (source != null && hasSourceAlternative(unquoted)) return command(Kind.AMBIGUOUS, null);
        if (source != null) body = (body.substring(0, sourceStart) + " " + body.substring(sourceEnd)).trim();
        body = body.replaceFirst("^[ ,:]+", "").replaceFirst("[ ,:]+$", "").replaceAll(" {2,}", " ");
        if (MUSIC.matcher(body).find()) return null;

        String mediaType = null;
        Matcher media = MEDIA.matcher(body);
        if (media.matches()) {
            mediaType = type(media.group(1));
            body = media.group(2).trim();
        }
        if (source == null && mediaType == null) return null;
        if (EXTRA_ACTION.matcher(maskQuotes(body)).find()) return null;
        if (body.isEmpty() || body.matches("(?iu)" + VIDEO_TYPES)) return command(Kind.AMBIGUOUS, source);
        int year = 0;
        Matcher yearSuffix = YEAR.matcher(body);
        if (yearSuffix.matches() && !yearSuffix.group(1).trim().isEmpty()) {
            year = Integer.parseInt(yearSuffix.group(2) == null ? yearSuffix.group(3) : yearSuffix.group(2));
            body = yearSuffix.group(1).trim();
        }
        String title = stripTitleQuotes(body);
        if (title.isEmpty() || title.length() > MAX_TITLE_CHARS || !title.codePoints().anyMatch(Character::isLetterOrDigit))
            return command(Kind.AMBIGUOUS, source);
        return new TvVoiceCommand(find ? Kind.FIND : Kind.PLAY, source, title, mediaType, year);
    }

    private static TvVoiceCommand command(Kind kind, Provider provider) {
        return new TvVoiceCommand(kind, provider, "", null, 0);
    }

    private static String type(String value) {
        if (value.equalsIgnoreCase("сериал")) return "series";
        if (value.equalsIgnoreCase("видео") || value.equalsIgnoreCase("ролик")) return "video";
        return "film";
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

    /** Hide quoted titles while finding clauses and source names, preserving string offsets. */
    private static String maskQuotes(String text) {
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

    private static String stripTitleQuotes(String text) {
        if (text.length() >= 2 && (text.startsWith("\"") && text.endsWith("\"")
                || text.startsWith("«") && text.endsWith("»"))) return text.substring(1, text.length() - 1).trim();
        return text;
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
