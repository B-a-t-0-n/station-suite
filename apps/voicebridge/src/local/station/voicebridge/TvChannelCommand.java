package local.station.voicebridge;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recognizes explicit television tuning requests before movie, series and music commands. */
final class TvChannelCommand {
    private static final Pattern ACTION = Pattern.compile("^(?:включи|поставь|открой|запусти|покажи|"
            + "переключи(?:сь)?(?: на)?|хочу посмотреть|давай посмотрим)"
            + "(?: +мне)?(?:,? +пожалуйста,?)? +(.+)$");
    private static final Pattern OKKO_SOURCE = Pattern.compile("(?:^|[ ,:]+)(?:на|в|через) +"
            + "(?:(?:приложении|кинотеатре|сервисе|видео ?сервисе) +)?"
            + "(?:ок ?ко|око|okko)(?=$|[ ,:])");
    private static final Pattern OPEN_CHANNELS = Pattern.compile(
            "^(?:открой|покажи) +(?:(?:в|на) (?:ок ?ко|око|okko) +)?(?:раздел +)?"
            + "(?:все +)?(?:теле ?каналы|(?:тв|tv)[ -]?каналы|список телеканалов)"
            + "(?: +(?:(?:в|на) (?:ок ?ко|око|okko)|вок))?$");
    private static final Pattern CHANNEL_LABEL = Pattern.compile("^(?:(теле ?канал|(?:тв|tv)[ -]?канал|"
            + "прямой эфир(?: телеканала| канала)?|эфир телеканала)|канал)(?=$|[ ,:])[ ,:]*");
    private static final Pattern CLIPPED_CHANNEL_LABEL = Pattern.compile("теле ?кан?");
    private static final Pattern PLAYBACK_SUFFIX = Pattern.compile("(?:^| )на паузу?$");
    private static final Pattern NEEDS_QUALIFIER = Pattern.compile("первый|пятый|матч|пятница"
            + "|культура|культуру|карусель|спас|звезда|звезду|мир|еда|еду|солнце");
    private static final Pattern NEGATIVE = Pattern.compile("^не(?: |$)");
    private static final int MAX_CHANNEL_NAME = 80;
    private static final Map<String, String> ALIASES = channelAliases();

    enum Kind { PLAY, OPEN_LIST, ASK_NAME, UNSUPPORTED_SOURCE }

    final Kind kind;
    final String name;

    private TvChannelCommand(Kind kind, String name) {
        this.kind = kind;
        this.name = name;
    }

    static TvChannelCommand parse(String transcript) {
        String text = TvVoiceCommand.commandText(transcript);
        if (text == null) return null;
        String unquotedText = TvVoiceCommand.maskQuotes(text);
        if (unquotedText.indexOf('?') >= 0 || TvVoiceCommand.hasSeveralActions(unquotedText)) return null;
        if (OPEN_CHANNELS.matcher(text).matches()) return new TvChannelCommand(Kind.OPEN_LIST, "");
        Matcher action = ACTION.matcher(text);
        if (!action.matches()) return null;
        String body = action.group(1);
        Matcher source = OKKO_SOURCE.matcher(TvVoiceCommand.maskQuotes(body));
        boolean onOkko = source.find();
        if (onOkko) {
            int start = source.start(), end = source.end();
            if (source.find()) return null;
            body = TvVoiceCommand.cleanTitle(body.substring(0, start) + " " + body.substring(end));
        }

        String unquotedBody = TvVoiceCommand.maskQuotes(body);
        Matcher label = CHANNEL_LABEL.matcher(unquotedBody);
        boolean hasLabel = label.lookingAt();
        boolean television = hasLabel && label.group(1) != null;
        String unquotedName = TvVoiceCommand.cleanTitle(hasLabel ? unquotedBody.substring(label.end()) : unquotedBody);
        if (PLAYBACK_SUFFIX.matcher(unquotedName).find()) return null;
        if (!hasLabel && CLIPPED_CHANNEL_LABEL.matcher(unquotedName).matches())
            return new TvChannelCommand(Kind.ASK_NAME, "");
        String name = TvVoiceCommand.stripTitleQuotes(
                TvVoiceCommand.cleanTitle(hasLabel ? body.substring(label.end()) : body));
        if (name.length() > MAX_CHANNEL_NAME || NEGATIVE.matcher(name).find()) return null;

        String key = normalize(name);
        String alias = ALIASES.get(key);
        if (alias == null && hasLabel) alias = ALIASES.get("канал " + key);
        if (TvVoiceCommand.hasOtherVideoSource(unquotedBody)) {
            Matcher afterSource = CHANNEL_LABEL.matcher(TvVoiceCommand.afterLeadingOtherVideoSource(unquotedBody));
            boolean explicitTelevision = television || afterSource.lookingAt() && afterSource.group(1) != null;
            return explicitTelevision || onOkko && (hasLabel || alias != null)
                    ? new TvChannelCommand(Kind.UNSUPPORTED_SOURCE, "") : null;
        }
        if (!hasLabel && (alias == null || !onOkko && NEEDS_QUALIFIER.matcher(key).matches())) return null;
        if (key.isEmpty()) return new TvChannelCommand(Kind.ASK_NAME, "");
        if (!television && !onOkko && alias == null) return null;
        return new TvChannelCommand(Kind.PLAY, alias == null ? name : alias);
    }

    static String canonicalName(String name) {
        if (name == null) return "";
        String trimmed = name.trim();
        String alias = ALIASES.get(normalize(trimmed));
        return alias == null ? trimmed : alias;
    }

    static String lookupKey(String name) {
        return normalize(canonicalName(name));
    }

    private static String normalize(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static Map<String, String> channelAliases() {
        Map<String, String> aliases = new HashMap<>();
        add(aliases, "Первый канал", "первый", "первый телеканал", "1 канал", "1-й канал", "канал 1", "ОРТ");
        add(aliases, "Россия 1", "Россия один", "Россия первая", "Россию 1", "Россию один", "РТР", "Телеканал Россия");
        add(aliases, "Россия 24", "Россию 24", "Россия двадцать четыре", "Россию двадцать четыре",
                "Вести 24", "Вести двадцать четыре");
        add(aliases, "Культура", "Культуру", "Россия К", "Россия Культура", "Россия Культура ТВ");
        add(aliases, "Матч ТВ", "Матч", "Матч т в", "Матч ти ви", "Матч тэвэ");
        add(aliases, "НТВ", "эн тэ вэ", "эн те ве", "н т в");
        add(aliases, "Пятый канал", "пятый", "пятый телеканал", "5 канал", "5-й канал", "канал 5");
        add(aliases, "Карусель", "Карусель ТВ");
        add(aliases, "ОТР", "о тэ эр", "о т р", "Общественное телевидение России");
        add(aliases, "ТВ Центр", "ТВЦ", "ТВЦентр", "тэ вэ центр");
        add(aliases, "РЕН ТВ", "РенТВ", "РенТВи", "Рэн ТВ", "рен тэ вэ");
        add(aliases, "Спас", "Спас ТВ");
        add(aliases, "Okko TV", "Okko ТВ", "Окко ТВ", "Око ТВ", "Окко ти ви");
        add(aliases, "БубOKKO", "БубОкко", "Буб Окко", "Бубоко", "Буба Окко");
        add(aliases, "ТНТ", "т э н т э", "тэ эн тэ", "т н т");
        add(aliases, "СТС", "эс тэ эс", "с т с");
        add(aliases, "Ю");
        add(aliases, "Пятница!", "Пятница ТВ");
        add(aliases, "Домашний");
        add(aliases, "Муз-ТВ", "МузТВ", "Муз тэ вэ");
        add(aliases, "Звезда", "Звезду", "Звезда ТВ");
        add(aliases, "Мир", "Мир ТВ");
        add(aliases, "Еда", "Еду", "Еда ТВ");
        add(aliases, "Солнце", "Солнце ТВ");
        return aliases;
    }

    private static void add(Map<String, String> aliases, String name, String... spokenNames) {
        aliases.put(normalize(name), name);
        for (String spokenName : spokenNames) aliases.put(normalize(spokenName), name);
    }
}
