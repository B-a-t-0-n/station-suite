package local.station.voicebridge;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** The public guide's channel IDs; the installed Okko app handles access and playback. */
final class OkkoChannels {
    private static final Map<String, Channel> EXACT = new HashMap<>();
    private static final Map<String, Channel> SPOKEN = new HashMap<>();

    static {
        Set<String> ambiguous = new HashSet<>();
        for (String[] entry : OkkoPublicChannelSnapshot.ENTRIES) {
            Channel channel = new Channel(entry[0], entry[1]);
            EXACT.put(exactKey(channel.title), channel);
            addSpoken(channel.title, channel, ambiguous);
            if (channel.title.indexOf('+') >= 0)
                addSpoken(channel.title.replace("+", " плюс "), channel, ambiguous);
        }
        for (String key : ambiguous) SPOKEN.remove(key);

        preferredLabel("Телеканал «Россия»", "Россия 1");
        preferredLabel("Телекомпания НТВ", "НТВ");
        preferredLabel("Телеканал «Россия-Культура»", "Россия Культура");
        preferredLabel("Российский информационный канал «Россия-24»", "Россия 24");
        preferredLabel("Детско-юношеский телеканал «Карусель»", "Карусель");
        preferredLabel("Общественное телевидение России", "ОТР");
        preferredLabel("ТВ Центр - Москва", "ТВ Центр");
        preferredLabel("СПАС", "Спас");
        preferredLabel("БубOKKO тв", "БубОкко");
    }

    private OkkoChannels() { }

    static Channel find(String title) {
        if (title == null) return null;
        Channel exact = EXACT.get(exactKey(title));
        return exact != null ? exact : SPOKEN.get(TvChannelCommand.lookupKey(title));
    }

    static Channel find(String title, BooleanSupplier cancelled) {
        return cancelled.getAsBoolean() ? null : find(title);
    }

    private static String exactKey(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static void addSpoken(String title, Channel channel, Set<String> ambiguous) {
        String key = TvChannelCommand.lookupKey(title);
        Channel old = SPOKEN.putIfAbsent(key, channel);
        if (old != null && !old.id.equals(channel.id)) ambiguous.add(key);
    }

    private static void preferredLabel(String officialTitle, String spokenTitle) {
        Channel channel = EXACT.get(exactKey(officialTitle));
        if (channel != null)
            SPOKEN.put(TvChannelCommand.lookupKey(spokenTitle), new Channel(spokenTitle, channel.id));
    }

    static final class Channel {
        final String title;
        private final String id;

        private Channel(String title, String id) {
            this.title = title;
            this.id = id;
        }

        String uri() {
            return "okko://tvchannels?uid=" + id + "&type=tv_channel&play=true";
        }
    }
}
