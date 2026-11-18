package local.station.voicebridge;

final class TvChannelCommandHostTest {
    private static int checks;

    public static void main(String[] args) {
        plays("Алиса, включи Первый канал", "Первый канал");
        plays("Алиса, включи телеканал «Первый» на Окко!", "Первый канал");
        plays("Включи на Окко телеканал Матч ТВ", "Матч ТВ");
        plays("Включи на Окко канал «Матч! ТВ»", "Матч ТВ");
        plays("Включи канал ТНТ на Око", "ТНТ");
        plays("Включи мне НТВ в приложении Okko, пожалуйста", "НТВ");
        plays("Включи мне, пожалуйста, телеканал НТВ", "НТВ");
        plays("Алиса включи эн тэ вэ", "НТВ");
        plays("Переключи на Россию 1", "Россия 1");
        plays("Переключись на Россию двадцать четыре", "Россия 24");
        plays("Включи Россия один", "Россия 1");
        plays("Включи телеканал Культуру", "Культура");
        plays("Включи тв-канал ОТР", "ОТР");
        plays("Включи прямой эфир телеканала Карусель", "Карусель");
        plays("Включи телеканал на Окко БубОкко", "БубOKKO");
        plays("Запусти в Окко канал РЕН ТВ", "РЕН ТВ");
        plays("Покажи на Окко прямой эфир НТВ", "НТВ");
        plays("Включи телеканал Окко ТВ", "Okko TV");
        plays("Включи канал 5", "Пятый канал");
        plays("Включи телеканал Дом Кино", "дом кино");
        plays("Включи канал Дом Кино через Окко", "дом кино");
        plays("Включи телеканал «Сделано на Окко»", "сделано на окко");
        expects("Включи телеканал", TvChannelCommand.Kind.ASK_NAME, "");
        expects("Включи телеканал на Окко", TvChannelCommand.Kind.ASK_NAME, "");
        expects("Включи телеканал НТВ на Рутубе", TvChannelCommand.Kind.UNSUPPORTED_SOURCE, "");
        expects("Включи на Wink телеканал Россия 1", TvChannelCommand.Kind.UNSUPPORTED_SOURCE, "");
        expects("Включи телеканал НТВ в приложении Вещатель", TvChannelCommand.Kind.UNSUPPORTED_SOURCE, "");
        expects("Включи на Окко телеканал НТВ на YouTube", TvChannelCommand.Kind.UNSUPPORTED_SOURCE, "");

        for (String phrase : new String[]{
                "включи Простоквашино на Окко", "открой Окко", "включи фильм Первый канал",
                "включи сериал Матч", "включи первый", "включи 1 на Окко", "включи пятницу",
                "включи первую серию на Окко", "включи песню НТВ", "включи радио Карусель",
                "продолжи фильм", "включи канал блогера", "включи канал Матч ТВ на YouTube",
                "можешь включить НТВ", "включи НТВ?", "какие телеканалы доступны?",
                "не включай телеканал НТВ", "включи канал не НТВ", "включи телеканал НТВ, а не Матч ТВ",
                "включи телеканал НТВ и включи музыку", "включи телеканал https://okko.tv/",
                "включи телеканал нтв\nзапусти", "включи телеканал на Окко НТВ на Окко"
        }) {
            check(TvChannelCommand.parse(phrase) == null, "overbroad match for " + phrase);
        }

        sameChannel("Россия Культура", "культуру");
        sameChannel(" БубOKKO ", "БубОкко");
        sameChannel("Okko TV", "Окко ТВ");
        sameChannel("Телеканал Россия", "Россия 1");
        sameChannel("Страна ТВ", "страна-тв");
        check(!TvChannelCommand.lookupKey("Кино ТВ").equals(TvChannelCommand.lookupKey("Кинохит")),
                "distinct channels conflated");
        catalog();
        System.out.println(checks + " exact TV-channel parser and Okko guide assertions passed");
    }

    private static void catalog() {
        channel("Первый", "Первый канал", 31266743);
        channel("Россия один", "Россия 1", 31263664);
        channel("Матч ти ви", "Матч ТВ", 194696717);
        channel("эн тэ вэ", "НТВ", 222903718);
        channel("Пятый канал", "Пятый канал", 31263662);
        channel("Культура", "Россия Культура", 31263659);
        channel("Россия двадцать четыре", "Россия 24", 31263665);
        channel("Карусель", "Карусель", 413994390);
        channel("ОТР", "ОТР", 374422719);
        channel("ТВЦ", "ТВ Центр", 31263669);
        channel("РЕН ТВ", "РЕН ТВ", 31263663);
        channel("Спас ТВ", "Спас", 31263666);
        channel("Окко ТВ", "Okko TV", 631975545);
        channel("БубOKKO", "БубОкко", 644651263);
        for (String unlisted : new String[]{"Несуществующий канал", "НТВ Плюс", "31266743",
                "НТВ&uid=31266743", "okko://tvchannels?uid=31266743", "Первый канал HD", null}) {
            check(OkkoChannels.find(unlisted) == null, "unexpected official channel match for " + unlisted);
        }
    }

    private static void channel(String alias, String officialTitle, int id) {
        OkkoChannels.Channel found = OkkoChannels.find(alias);
        check(found != null, "catalog missing " + alias);
        check(officialTitle.equals(found.title), "different channel name for " + alias);
        check(("okko://tvchannels?uid=" + id + "&type=tv_channel&play=true").equals(found.uri()),
                "different Okko target for " + alias);
    }

    private static void plays(String phrase, String expectedName) {
        expects(phrase, TvChannelCommand.Kind.PLAY, expectedName);
    }

    private static void expects(String phrase, TvChannelCommand.Kind kind, String name) {
        TvChannelCommand command = TvChannelCommand.parse(phrase);
        check(command != null, "missing match for " + phrase);
        check(command.kind == kind, "wrong type for " + phrase + ": " + command.kind);
        check(command.name.equals(name), "wrong name for " + phrase + ": " + command.name);
    }

    private static void sameChannel(String actual, String alternative) {
        check(TvChannelCommand.lookupKey(actual).equals(TvChannelCommand.lookupKey(alternative)),
                "channel aliases not equivalent: " + actual + ", " + alternative);
    }

    private static void check(boolean pass, String message) {
        checks++;
        if (!pass) throw new AssertionError(message);
    }
}
