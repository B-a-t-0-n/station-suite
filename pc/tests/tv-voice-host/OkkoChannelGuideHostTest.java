package local.station.voicebridge;

import java.util.HashSet;
import java.util.Set;

/** The generated public guide should preserve the exact, unique identity of every channel. */
final class OkkoChannelGuideHostTest {
    public static void main(String[] args) {
        Set<String> identities = new HashSet<>();
        Set<String> titles = new HashSet<>();
        for (String[] entry : OkkoPublicChannelSnapshot.ENTRIES) {
            check(entry.length == 2 && titles.add(entry[0]) && entry[1].matches("[1-9][0-9]{0,17}")
                    && identities.add(entry[1]), "Invalid public guide entry");
            matches(entry[0], entry[1]);
        }
        check(identities.size() > 14, "Expected the complete guide rather than only the original shortcuts");
        matches("тнт", "194708036");
        matches("эс тэ эс", "31263667");
        matches("пятница!", "197184468");
        matches("Домашний", "31263656");
        matches("Ю", "31263671");
        matches("okko спорт", "223878119");
        matches("Киноман", "300003663653");
        matches("viju+ premiere", "654193");
        matches("viju плюс premiere", "654193");
        matches("Иллюзион плюс", "568562080");
        check(OkkoChannels.find("viju") == null, "Accepted only a title prefix");
        check(OkkoChannels.find("194708036&play=true") == null, "Accepted an untrusted target");
        check(OkkoChannels.find("ТНТ", () -> true) == null, "Ignored cancellation");
        System.out.println("Verified exact lookup of " + identities.size() + " public Okko channels");
    }

    private static void matches(String title, String id) {
        OkkoChannels.Channel channel = OkkoChannels.find(title, () -> false);
        check(channel != null && channel.uri().equals("okko://tvchannels?uid=" + id + "&type=tv_channel&play=true"),
                "Wrong channel target for " + title);
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
}
