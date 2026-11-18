package local.station.voicebridge;

import java.util.HashMap;
import java.util.Map;

/** Public channel IDs from the Okko TV guide. Launches through the installed Okko app. */
final class OkkoChannels {
    private static final Map<String, Channel> CHANNELS = new HashMap<>();

    static {
        add("Первый канал", 31266743);
        add("Россия 1", 31263664);
        add("Матч ТВ", 194696717);
        add("НТВ", 222903718);
        add("Пятый канал", 31263662);
        add("Россия Культура", 31263659);
        add("Россия 24", 31263665);
        add("Карусель", 413994390);
        add("ОТР", 374422719);
        add("ТВ Центр", 31263669);
        add("РЕН ТВ", 31263663);
        add("Спас", 31263666);
        add("Okko TV", 631975545);
        add("БубОкко", 644651263);
    }

    private OkkoChannels() { }

    static Channel find(String title) {
        return CHANNELS.get(TvChannelCommand.lookupKey(title));
    }

    private static void add(String title, int id) {
        CHANNELS.put(TvChannelCommand.lookupKey(title), new Channel(title, id));
    }

    static final class Channel {
        final String title;
        private final int id;

        private Channel(String title, int id) {
            this.title = title;
            this.id = id;
        }

        String uri() {
            return "okko://tvchannels?uid=" + id + "&type=tv_channel&play=true";
        }
    }
}
