package local.station.voicebridge;

public final class TvPlaybackHostTest {
    public static void main(String[] args) {
        position("00:01", 1000);
        position("1:07", 67000);
        position("01:02:03", 3723000);
        position(" 00:00:00 ", 0);
        position("01:62", -1);
        position("1:02:60", -1);
        position("01:05 / 12:00", -1);
        position("Пауза", -1);
        position(null, -1);
        target(-1000, 3500, 0);
        target(1000, 3500, 1000);
        target(4000, 3500, 3500);
        target(4000, -1, 4000);
        TvPlaybackControl control = new TvPlaybackControl(null);
        if (control.hasVideo()) throw new AssertionError("unbound accessibility service");
        TvVoiceCommand.Playback pause = TvVoiceCommand.playback("поставь фильм на паузу");
        if (pause == null || control.execute(pause, () -> true) != TvPlaybackControl.Outcome.CANCELLED
                || control.execute(pause, () -> false) != TvPlaybackControl.Outcome.PERMISSION_REQUIRED)
            throw new AssertionError("no playback command without Android permission");
        TvPlaybackControl.Video launch = new TvPlaybackControl.Video("Фильм", null, 120_000, 12, 1000);
        if (launch.matchesLaunch(12, 8000, 2000) || !launch.matchesLaunch(13, 8000, 2500)
                || launch.matchesLaunch(14, 8000, 3000) || launch.matchesLaunch(13, 0, 3000))
            throw new AssertionError("catalogue duration is tied to the new player and continuous position");
        launch.confirmedPosition(0);
        if (!launch.matchesLaunch(13, 1000, 60_000)) throw new AssertionError("confirmed seek updates position");
        TvPlaybackControl.Video stale = new TvPlaybackControl.Video("Фильм", null, 120_000, null, 1000);
        if (stale.matchesLaunch(15, 8000, 47_000)) throw new AssertionError("expired launch is not bound");
        System.out.println("TV playback timestamp, bounds and permission checks passed");
    }

    private static void position(String value, long expected) {
        if (TvPlaybackAccessibilityService.parsePosition(value) != expected) throw new AssertionError(value);
    }

    private static void target(long value, long duration, long expected) {
        if (TvPlaybackAccessibilityService.clampTarget(value, duration) != expected) throw new AssertionError(value);
    }
}
