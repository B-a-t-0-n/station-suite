package local.station.voicebridge;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Combines successful voice selections with the players' current state. */
final class MediaVoiceContext {
    enum Mode {
        NONE(null), MUSIC(null), VIDEO("video"), FILM("film"), SERIES("series"), TV("video");

        final String videoType;

        Mode(String videoType) { this.videoType = videoType; }
    }

    private static final long SWITCH_GRACE_MS = TimeUnit.SECONDS.toMillis(8);
    private static final long MEMORY_MS = TimeUnit.MINUTES.toMillis(20);
    private final Supplier<Mode> foreground;
    private final LongSupplier clock;
    private Mode last = Mode.NONE;
    private long changedAt;

    MediaVoiceContext(Supplier<Mode> foreground) {
        this(foreground, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    MediaVoiceContext(Supplier<Mode> foreground, LongSupplier clock) {
        this.foreground = foreground;
        this.clock = clock;
    }

    synchronized void selected(Mode mode) {
        last = mode;
        changedAt = clock.getAsLong();
    }

    synchronized Mode choose(boolean musicPlaying) {
        long age = clock.getAsLong() - changedAt;
        Mode recent = age < MEMORY_MS ? last : Mode.NONE;
        if (recent != Mode.NONE && age < SWITCH_GRACE_MS) return recent;
        Mode screen = foreground.get();
        if (musicPlaying && (recent == Mode.MUSIC || screen == Mode.NONE)) return Mode.MUSIC;
        if (screen == Mode.TV || screen == Mode.SERIES) return screen;
        if (screen == Mode.VIDEO && (recent == Mode.FILM || recent == Mode.SERIES || recent == Mode.TV))
            return recent;
        return screen != Mode.NONE ? screen : recent;
    }

    synchronized String preferredVideoType() {
        Mode mode = choose(false);
        return mode.videoType == null ? Mode.VIDEO.videoType : mode.videoType;
    }

}
