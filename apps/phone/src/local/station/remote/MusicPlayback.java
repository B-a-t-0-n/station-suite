package local.station.remote;

/** Playback clock and slider arithmetic, independent of Android views and Bluetooth. */
final class MusicPlayback {
    private long positionMs, durationMs, receivedAtMs;
    private boolean playing;

    void update(long position, long duration, boolean active, long now) {
        durationMs = Math.max(0, duration);
        positionMs = Math.max(0, position);
        if (durationMs > 0) positionMs = Math.min(positionMs, durationMs);
        receivedAtMs = now;
        playing = active;
    }

    long position(long now) {
        // Never predict beyond the next few polls when the Station stops responding.
        long elapsed = playing ? Math.min(5000, Math.max(0, now - receivedAtMs)) : 0;
        long value = positionMs > Long.MAX_VALUE - elapsed ? Long.MAX_VALUE : positionMs + elapsed;
        return durationMs > 0 ? Math.min(value, durationMs) : value;
    }

    long duration() { return durationMs; }

    static int slider(long position, long duration) {
        if (duration <= 0) return 0;
        return (int) Math.round(Math.max(0d, Math.min(1d, (double) position / duration)) * 1000d);
    }

    static long fromSlider(int progress, long duration) {
        if (duration <= 0) return 0;
        return Math.round((Math.max(0, Math.min(1000, progress)) / 1000d) * duration);
    }

    static String time(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        return seconds >= 3600
                ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
