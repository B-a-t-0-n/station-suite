package local.station.voicebridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static local.station.voicebridge.TvPlaybackControl.Outcome;

/** The system binds this service after accessibility consent. No notification or audio access. */
public final class TvPlaybackAccessibilityService extends AccessibilityService {
    private static final String OKKO = "tv.okko.androidtv";
    private static final String ID = OKKO + ":id/";
    private static final String PLAYER = "playerFragmentContainer";
    private static final String SDK_CONTROLS = "ps_root_control_layout";
    private static final String SDK_TIMELINE = "ps_exo_progress";
    private static final String OLD_TIMELINE = "exo_progress";
    private static final String SDK_VIDEO = "ps_exo_player_view";
    private static final long POSITION_TOLERANCE_MS = 2_000;
    private static final long MAX_DURATION_MS = 24 * 60 * 60_000L;
    private static final int OKKO_SCROLL_STEPS = 20;
    private static volatile TvPlaybackAccessibilityService live;
    private Integer measuredPlayer;
    private long measuredDuration, measuredUntil, measuredPosition;

    static TvPlaybackAccessibilityService connected() { return live; }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        live = this;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        if (live == this) live = null;
        super.onDestroy();
    }

    boolean hasVideo() {
        AccessibilityNodeInfo root = playerRoot();
        if (root == null) return false;
        root.recycle();
        return true;
    }

    Integer playerIdentity() {
        AccessibilityNodeInfo root = playerRoot();
        if (root == null) return null;
        try { return playerIdentity(root); }
        finally { root.recycle(); }
    }

    private static Integer playerIdentity(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo view = find(root, true, SDK_VIDEO);
        if (view == null) return null;
        try { return view.hashCode(); }
        finally { view.recycle(); }
    }

    Outcome execute(TvVoiceCommand.Playback command, TvPlaybackControl.Video video, BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) return Outcome.CANCELLED;
        AccessibilityNodeInfo root = playerRoot();
        if (root == null) return Outcome.NO_VIDEO;
        int window = root.getWindowId();
        if (!present(root, SDK_TIMELINE, OLD_TIMELINE)) {
            try {
                if (!supportedTimelineVersion() || !click(root, cancelled, SDK_VIDEO)) {
                    return cancelled.getAsBoolean() ? Outcome.CANCELLED : Outcome.UNSUPPORTED;
                }
            } finally { root.recycle(); }
            root = awaitTimeline(window, cancelled);
            if (root == null) return cancelled.getAsBoolean() ? Outcome.CANCELLED : Outcome.UNCONFIRMED;
        }
        try {
            switch (command.action) {
                case PAUSE:
                case RESUME:
                    return changePlayState(root, window, command.action == TvVoiceCommand.PlaybackAction.RESUME, cancelled);
                case SEEK_TO:
                case SEEK_BY:
                case RESTART:
                    return seek(root, window, command, video, cancelled);
                default:
                    return Outcome.UNSUPPORTED;
            }
        } finally { root.recycle(); }
    }

    private Outcome changePlayState(AccessibilityNodeInfo root, int window, boolean play, BooleanSupplier cancelled) {
        if (present(root, play ? "ps_exo_pause" : "ps_exo_play", play ? "exo_pause" : "exo_play")) {
            return Outcome.APPLIED;
        }
        if (click(root, cancelled, play ? "ps_exo_play" : "ps_exo_pause", play ? "exo_play" : "exo_pause")) {
            return verify(window, cancelled,
                    current -> present(current, play ? "ps_exo_pause" : "ps_exo_play", play ? "exo_pause" : "exo_play"));
        }
        if (!supportedTimelineVersion()) return Outcome.UNSUPPORTED;
        long before = timelinePosition(root);
        if (before < 0) return Outcome.UNSUPPORTED;
        Boolean playing = watchProgress(window, before, cancelled);
        if (playing == null) return cancelled.getAsBoolean() ? Outcome.CANCELLED : Outcome.UNCONFIRMED;
        if (playing == play) return Outcome.APPLIED;
        AccessibilityNodeInfo current = playerRoot();
        if (current == null) return Outcome.UNCONFIRMED;
        try {
            long position = timelinePosition(current);
            if (current.getWindowId() != window || position < 0 || Math.abs(position - before) > 2500
                    || !click(current, cancelled, SDK_TIMELINE)) return Outcome.UNCONFIRMED;
            Boolean changed = watchProgress(window, position, cancelled);
            return changed != null && changed == play ? Outcome.APPLIED
                    : cancelled.getAsBoolean() ? Outcome.CANCELLED : Outcome.UNCONFIRMED;
        } finally { current.recycle(); }
    }

    private Outcome seek(AccessibilityNodeInfo root, int window, TvVoiceCommand.Playback command,
                         TvPlaybackControl.Video video, BooleanSupplier cancelled) {
        AccessibilityNodeInfo bar = find(root, SDK_TIMELINE, OLD_TIMELINE);
        if (bar == null) return Outcome.UNSUPPORTED;
        long target;
        boolean moved;
        boolean start = command.action == TvVoiceCommand.PlaybackAction.RESTART;
        try {
            boolean sdk = (ID + SDK_TIMELINE).equals(bar.getViewIdResourceName());
            long current = position(root, bar);
            if (current < 0) return Outcome.UNSUPPORTED;
            long duration = clockText(root, sdk ? "ps_exo_duration" : "exo_duration");
            if (sdk && duration >= 0) duration += current;
            if (duration <= 0 && sdk) {
                Integer identity = playerIdentity(root);
                long now = SystemClock.elapsedRealtime();
                if (video != null && (matchingTitle(root, video)
                        || identity != null && video.matchesLaunch(identity, current, now))) duration = video.durationMs;
                else if (identity != null && identity.equals(measuredPlayer) && now < measuredUntil
                        && current + 5_000 >= measuredPosition) duration = measuredDuration;
            }
            boolean relative = command.action == TvVoiceCommand.PlaybackAction.SEEK_BY;
            target = start ? 0 : relative ? current + command.positionMs : command.positionMs;
            if (target < 0 && !relative || duration > 0 && target > duration && !relative) return Outcome.UNSUPPORTED;
            target = clampTarget(target, duration);
            if (cancelled.getAsBoolean()) return Outcome.CANCELLED;
            if (current == target) moved = true;
            else if (duration > 0 && supports(bar, AccessibilityAction.ACTION_SET_PROGRESS))
                moved = setProgress(bar, target, duration);
            else if (sdk && (duration > 0 || start)) {
                if (!touchVodTimeline(root, bar, target, duration, current, cancelled))
                    return cancelled.getAsBoolean() ? Outcome.CANCELLED : Outcome.UNSUPPORTED;
                if (!start) {
                    Outcome verified = verifySdkSeek(window, target, duration, current, cancelled);
                    if (verified == Outcome.APPLIED) storePosition(video, target);
                    return finishSeek(window, command.resumeAfterSeek, verified, cancelled);
                }
                moved = true;
            }
            else if (sdk) {
                Outcome verified = seekWithMeasuredDuration(root, bar, window, current, target, cancelled);
                return finishSeek(window, command.resumeAfterSeek, verified, cancelled);
            }
            else return Outcome.UNSUPPORTED;
        } finally { bar.recycle(); }
        if (!moved) return cancelled.getAsBoolean() ? Outcome.CANCELLED : Outcome.UNSUPPORTED;
        Outcome position = verifyPosition(window, target, cancelled);
        if (position == Outcome.APPLIED) storePosition(video, target);
        return finishSeek(window, start || command.resumeAfterSeek, position, cancelled);
    }

    private Outcome finishSeek(int window, boolean resume, Outcome position, BooleanSupplier cancelled) {
        if (!resume || position != Outcome.APPLIED) return position;
        AccessibilityNodeInfo current = playerRoot();
        if (current == null) return Outcome.UNCONFIRMED;
        try {
            if (current.getWindowId() != window) return Outcome.UNCONFIRMED;
            return changePlayState(current, window, true, cancelled);
        } finally { current.recycle(); }
    }

    private void storePosition(TvPlaybackControl.Video video, long target) {
        if (video != null) video.confirmedPosition(target);
        measuredPosition = target;
    }

    private Outcome seekWithMeasuredDuration(AccessibilityNodeInfo root, AccessibilityNodeInfo bar, int window,
                                              long before, long target, BooleanSupplier cancelled) {
        if (!supportedTimelineVersion() || !present(root, SDK_CONTROLS) || !focusTimeline(bar, cancelled))
            return Outcome.UNSUPPORTED;
        boolean backward = before >= 600_000;
        AccessibilityAction step = backward ? AccessibilityAction.ACTION_SCROLL_BACKWARD
                : AccessibilityAction.ACTION_SCROLL_FORWARD;
        if (cancelled.getAsBoolean()) return Outcome.CANCELLED;
        if (!supports(bar, step) || !bar.performAction(step.getId())) return Outcome.UNSUPPORTED;
        for (int attempt = 0; attempt < 6 && !cancelled.getAsBoolean(); attempt++) {
            SystemClock.sleep(130);
            AccessibilityNodeInfo fresh = playerRoot();
            if (fresh == null) break;
            try {
                if (fresh.getWindowId() != window) break;
                AccessibilityNodeInfo updated = find(fresh, SDK_TIMELINE);
                if (updated == null) continue;
                try {
                    if (!bar.equals(updated)) return Outcome.UNCONFIRMED;
                    long after = position(fresh, updated);
                    long delta = backward ? before - after : after - before;
                    if (after < 2000 || delta < 3000) continue;
                    long duration = delta * OKKO_SCROLL_STEPS;
                    if (duration <= Math.max(before, after) || duration > MAX_DURATION_MS || target > duration)
                        return Outcome.UNCONFIRMED;
                    if (Math.abs(after - target) <= POSITION_TOLERANCE_MS) return Outcome.APPLIED;
                    if (!touchVodTimeline(fresh, updated, target, duration, after, cancelled))
                        return cancelled.getAsBoolean() ? Outcome.CANCELLED : Outcome.UNCONFIRMED;
                    measuredPlayer = playerIdentity(fresh);
                    measuredDuration = duration;
                    measuredPosition = after;
                    measuredUntil = SystemClock.elapsedRealtime() + 60_000;
                    Outcome verified = verifySdkSeek(window, target, duration, after, cancelled);
                    if (verified == Outcome.APPLIED) measuredPosition = target;
                    return verified;
                } finally { updated.recycle(); }
            } finally { fresh.recycle(); }
        }
        return cancelled.getAsBoolean() ? Outcome.CANCELLED : Outcome.UNCONFIRMED;
    }

    private Outcome verifyPosition(int window, long expected, BooleanSupplier cancelled) {
        return verifySdkSeek(window, expected, -1, -1, cancelled);
    }

    private Outcome verifySdkSeek(int window, long expected, long duration, long before, BooleanSupplier cancelled) {
        boolean revealed = false;
        boolean corrected = false;
        for (int attempt = 0; attempt < 8; attempt++) {
            if (cancelled.getAsBoolean()) return Outcome.CANCELLED;
            SystemClock.sleep(140);
            AccessibilityNodeInfo root = playerRoot();
            if (root == null) return Outcome.UNCONFIRMED;
            try {
                if (root.getWindowId() != window) return Outcome.UNCONFIRMED;
                AccessibilityNodeInfo bar = find(root, SDK_TIMELINE, OLD_TIMELINE);
                if (bar == null) {
                    if (!revealed && supportedTimelineVersion()) revealed = click(root, cancelled, SDK_VIDEO);
                    continue;
                }
                try {
                    long readback = position(root, bar);
                    if (readback < 0) continue;
                    long error = expected - readback;
                    if (Math.abs(error) <= POSITION_TOLERANCE_MS) return Outcome.APPLIED;
                    if (!corrected && duration > 0 && (attempt >= 1 || Math.abs(readback - before) >= 2_000)
                            && Math.abs(error) <= Math.max(5_000, expected / 40)
                            && (ID + SDK_TIMELINE).equals(bar.getViewIdResourceName())) {
                        long adjusted = clampTarget(expected + error, duration);
                        corrected = touchVodTimeline(root, bar, adjusted, duration, readback, cancelled);
                    }
                } finally { bar.recycle(); }
            } finally { root.recycle(); }
        }
        return Outcome.UNCONFIRMED;
    }

    private static boolean setProgress(AccessibilityNodeInfo bar, long target, long duration) {
        AccessibilityNodeInfo.RangeInfo range = bar.getRangeInfo();
        if (range == null) return false;
        float min = range.getMin(), max = range.getMax();
        if (!Float.isFinite(min) || !Float.isFinite(max) || max <= min) return false;
        Bundle args = new Bundle();
        args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,
                min + (float) ((max - min) * (double) target / duration));
        return bar.performAction(AccessibilityAction.ACTION_SET_PROGRESS.getId(), args);
    }

    private boolean touchVodTimeline(AccessibilityNodeInfo root, AccessibilityNodeInfo bar, long target, long duration,
                                     long current, BooleanSupplier cancelled) {
        if (!sameText("android.widget.SeekBar", bar.getClassName())
                || !present(root, SDK_CONTROLS) || !supportedTimelineVersion() || !focusTimeline(bar, cancelled)) return false;
        Rect bounds = new Rect(), window = new Rect();
        bar.getBoundsInScreen(bounds);
        root.getBoundsInScreen(window);
        if (!window.contains(bounds) || bounds.width() < 240 || bounds.height() < 10) return false;
        try {
            Resources resources = getPackageManager().getResourcesForApplication(OKKO);
            int edge = dimension(resources, "ps_dp72"), gap = dimension(resources, "ps_dp48");
            int labelSize = dimension(resources, "ps_sp24");
            int font = resources.getIdentifier("okko_sans_medium", "font", OKKO);
            if (edge <= 0 || gap <= 0 || labelSize <= 0 || font == 0) return false;
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setTypeface(resources.getFont(font));
            paint.setTextSize(labelSize);
            long remaining = Math.max(0, duration / 1000 - current / 1000);
            String label = formatTime(remaining);
            int left = bounds.left + edge;
            int right = bounds.right - edge - gap - (int) paint.measureText(label);
            if (right <= left || cancelled.getAsBoolean()) return false;
            float x = Math.min(right - 1, Math.max(left, left + Math.round((right - left)
                    * (duration > 0 ? (double) target / duration : 0))));
            return tap(x, bounds.bottom - 3, cancelled);
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException unavailable) {
            return false;
        }
    }

    private static boolean focusTimeline(AccessibilityNodeInfo bar, BooleanSupplier cancelled) {
        if (bar.isFocused()) return true;
        if (cancelled.getAsBoolean() || !supports(bar, AccessibilityAction.ACTION_FOCUS)) return false;
        if (!bar.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) return false;
        return bar.refresh() && bar.isFocused();
    }

    private boolean supportedTimelineVersion() {
        try {
            return "3.196.0".equals(getPackageManager().getPackageInfo(OKKO, 0).versionName);
        } catch (PackageManager.NameNotFoundException unavailable) { return false; }
    }

    private static int dimension(Resources resources, String name) {
        int id = resources.getIdentifier(name, "dimen", OKKO);
        return id == 0 ? 0 : resources.getDimensionPixelSize(id);
    }

    private boolean tap(float x, float y, BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) return false;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 55)).build();
        CountDownLatch done = new CountDownLatch(1);
        boolean[] accepted = {false};
        if (!dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription ignored) { accepted[0] = true; done.countDown(); }
            @Override public void onCancelled(GestureDescription ignored) { done.countDown(); }
        }, null)) return false;
        try { return done.await(400, TimeUnit.MILLISECONDS) && accepted[0]; }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private AccessibilityNodeInfo playerRoot() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (isPlayer(root)) return root;
        if (root != null) root.recycle();
        AccessibilityNodeInfo selected = null;
        List<AccessibilityWindowInfo> windows = getWindows();
        for (AccessibilityWindowInfo window : windows) {
            try {
                if (selected != null || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION
                        || !window.isActive() || !window.isFocused()) continue;
                AccessibilityNodeInfo candidate = window.getRoot();
                if (isPlayer(candidate)) selected = candidate;
                else if (candidate != null) candidate.recycle();
            } finally { window.recycle(); }
        }
        return selected;
    }

    private static boolean isPlayer(AccessibilityNodeInfo root) {
        if (root == null || !sameText(OKKO, root.getPackageName())
                || present(root, "contentCardRootView", "ps_ads_exo_player_view",
                        "ps_avod_ad_player_view", "ps_live_indicator_title")) return false;
        if (present(root, SDK_VIDEO)
                && present(root, SDK_CONTROLS, SDK_TIMELINE, PLAYER)) return true;
        return present(root, PLAYER) && present(root, OLD_TIMELINE);
    }

    private AccessibilityNodeInfo awaitTimeline(int window, BooleanSupplier cancelled) {
        for (int attempt = 0; attempt < 5 && !cancelled.getAsBoolean(); attempt++) {
            AccessibilityNodeInfo root = playerRoot();
            if (root == null) return null;
            if (root.getWindowId() == window && present(root, SDK_TIMELINE, OLD_TIMELINE)) return root;
            root.recycle();
            SystemClock.sleep(100);
        }
        return null;
    }

    private Boolean watchProgress(int window, long before, BooleanSupplier cancelled) {
        boolean observed = false;
        boolean revealed = false;
        for (int attempt = 0; attempt < 8 && !cancelled.getAsBoolean(); attempt++) {
            SystemClock.sleep(180);
            AccessibilityNodeInfo root = playerRoot();
            if (root == null) return null;
            try {
                if (root.getWindowId() != window) return null;
                long current = timelinePosition(root);
                if (current < 0) {
                    if (revealed || !supportedTimelineVersion() || !click(root, cancelled, SDK_VIDEO)) return null;
                    revealed = true;
                    continue;
                }
                if (current > before && current - before <= 2500) return true;
                if (current != before) return null;
                observed = true;
            } finally { root.recycle(); }
        }
        return observed && !cancelled.getAsBoolean() ? Boolean.FALSE : null;
    }

    private static long timelinePosition(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo bar = find(root, SDK_TIMELINE, OLD_TIMELINE);
        if (bar == null) return -1;
        try { return position(root, bar); }
        finally { bar.recycle(); }
    }

    private Outcome verify(int window, BooleanSupplier cancelled, Observation target) {
        for (int attempt = 0; attempt < 9; attempt++) {
            if (cancelled.getAsBoolean()) return Outcome.CANCELLED;
            AccessibilityNodeInfo root = playerRoot();
            if (root == null) return Outcome.UNCONFIRMED;
            try {
                if (root.getWindowId() != window) return Outcome.UNCONFIRMED;
                if (target.reached(root)) return Outcome.APPLIED;
            } finally { root.recycle(); }
            SystemClock.sleep(110);
        }
        return Outcome.UNCONFIRMED;
    }

    private interface Observation { boolean reached(AccessibilityNodeInfo root); }

    private static boolean matchingTitle(AccessibilityNodeInfo root, TvPlaybackControl.Video video) {
        String title = nodeTitle(root, "ps_video_title");
        String expected = canonicalTitle(video.title);
        String episode = canonicalTitle(video.episodeTitle);
        if (episode.isEmpty()) return title.equals(expected);
        String subtitle = nodeTitle(root, "ps_video_subtitle");
        return title.equals(episode) || title.equals(expected + " " + episode)
                || title.equals(expected) && subtitle.equals(episode);
    }

    private static String nodeTitle(AccessibilityNodeInfo root, String id) {
        AccessibilityNodeInfo element = find(root, id);
        if (element == null) return "";
        try { return canonicalTitle(element.getText()); }
        finally { element.recycle(); }
    }

    private static String canonicalTitle(CharSequence text) {
        return text == null ? "" : text.toString().toLowerCase(Locale.ROOT).replace('ё', 'е')
                .replaceAll("[^\\p{L}0-9]+", " ").trim();
    }

    private static boolean present(AccessibilityNodeInfo parent, String... ids) {
        AccessibilityNodeInfo node = find(parent, ids);
        if (node == null) return false;
        node.recycle();
        return true;
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo parent, String... ids) {
        return find(parent, false, ids);
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo parent, boolean clickable, String... ids) {
        for (String id : ids) {
            List<AccessibilityNodeInfo> nodes = parent.findAccessibilityNodeInfosByViewId(ID + id);
            AccessibilityNodeInfo match = null;
            for (AccessibilityNodeInfo node : nodes) {
                if (match == null && node.isVisibleToUser() && node.isEnabled()
                        && sameText(OKKO, node.getPackageName())
                        && (!clickable || supports(node, AccessibilityAction.ACTION_CLICK))) match = node;
                else node.recycle();
            }
            if (match != null) return match;
        }
        return null;
    }

    private static boolean supports(AccessibilityNodeInfo node, AccessibilityAction action) {
        return node.getActionList().contains(action);
    }

    private static boolean click(AccessibilityNodeInfo root, BooleanSupplier cancelled, String... ids) {
        AccessibilityNodeInfo node = find(root, true, ids);
        if (node == null) return false;
        try { return !cancelled.getAsBoolean() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK); }
        finally { node.recycle(); }
    }

    private static boolean sameText(String expected, CharSequence actual) {
        return actual != null && expected.contentEquals(actual);
    }

    private static long position(AccessibilityNodeInfo root, AccessibilityNodeInfo bar) {
        if (!bar.refresh()) return -1;
        long value = parsePosition(bar.getContentDescription());
        if (value >= 0) return value;
        return clockText(root, "ps_exo_position", "exo_position");
    }

    private static long clockText(AccessibilityNodeInfo parent, String... ids) {
        AccessibilityNodeInfo text = find(parent, ids);
        if (text == null) return -1;
        try { return parsePosition(text.getText()); }
        finally { text.recycle(); }
    }

    static long parsePosition(CharSequence value) {
        if (value == null) return -1;
        String text = value.toString().trim();
        if (!text.matches("[0-9]{1,2}:[0-5][0-9](?::[0-5][0-9])?")) return -1;
        long seconds = 0;
        for (String part : text.split(":")) seconds = seconds * 60 + Integer.parseInt(part);
        return seconds * 1000;
    }

    private static String formatTime(long seconds) {
        return seconds >= 3600 ? String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    static long clampTarget(long target, long duration) {
        return Math.max(0, duration > 0 ? Math.min(target, duration) : target);
    }
}
