package local.station.voicebridge;

import android.content.Context;
import android.os.SystemClock;
import java.util.function.BooleanSupplier;

/** Routes playback only to an on-screen Okko player and only after Android grants access. */
final class TvPlaybackControl {
    enum Outcome { APPLIED, CANCELLED, NO_VIDEO, UNSUPPORTED, PERMISSION_REQUIRED, UNCONFIRMED }
    private volatile Video video;

    TvPlaybackControl(Context context) { }

    Integer currentOkkoPlayer() {
        TvPlaybackAccessibilityService service = TvPlaybackAccessibilityService.connected();
        return service == null ? null : service.playerIdentity();
    }

    void rememberOkkoVideo(String title, String episodeTitle, long durationMs, Integer previousPlayer) {
        video = durationMs <= 0 || title == null || title.trim().isEmpty() ? null
                : new Video(title, episodeTitle, durationMs, previousPlayer, SystemClock.elapsedRealtime());
    }

    boolean hasVideo() {
        TvPlaybackAccessibilityService service = TvPlaybackAccessibilityService.connected();
        return service != null && service.hasVideo();
    }

    Outcome execute(TvVoiceCommand.Playback playback, BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) return Outcome.CANCELLED;
        TvPlaybackAccessibilityService service = TvPlaybackAccessibilityService.connected();
        if (service == null) return Outcome.PERMISSION_REQUIRED;
        return service.execute(playback, video, cancelled);
    }

    static final class Video {
        final String title;
        final String episodeTitle;
        final long durationMs;
        private final Integer previousPlayer;
        private final long launchedMs;
        private Integer matchedPlayer;
        private long lastPositionMs = -1;
        Video(String title, String episodeTitle, long durationMs, Integer previousPlayer, long launchedMs) {
            this.title = title;
            this.episodeTitle = episodeTitle;
            this.durationMs = durationMs;
            this.previousPlayer = previousPlayer;
            this.launchedMs = launchedMs;
        }

        synchronized boolean matchesLaunch(int player, long positionMs, long nowMs) {
            if (matchedPlayer == null && nowMs - launchedMs <= 45_000
                    && (previousPlayer == null || previousPlayer != player)) matchedPlayer = player;
            if (matchedPlayer == null || matchedPlayer != player) return false;
            if (lastPositionMs >= 0 && positionMs + 5_000 < lastPositionMs) return false;
            lastPositionMs = positionMs;
            return true;
        }

        synchronized void confirmedPosition(long positionMs) {
            lastPositionMs = positionMs;
        }
    }
}
