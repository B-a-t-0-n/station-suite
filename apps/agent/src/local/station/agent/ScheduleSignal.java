package local.station.agent;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

/** One offline alarm signal. All player/audio operations are confined to the main thread. */
final class ScheduleSignal implements AudioManager.OnAudioFocusChangeListener,
        MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener, Runnable {
    interface Listener { void stopped(String reason); }
    static final long MAX_RING_MS = 120000;
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final PowerManager.WakeLock wake;
    private MediaPlayer player;
    private AudioFocusRequest focus;
    private boolean focusHeld, volumeOwned, originalMuted;
    private long focusGeneration;
    private int originalVolume, ourVolume, targetVolume;
    private long deadline;
    private volatile boolean running;
    private volatile String lastCode = "idle";
    private volatile String lastError = "none";

    ScheduleSignal(Context context, Listener listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
        audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StationAgent:ScheduleSignal");
        wake.setReferenceCounted(false);
    }

    boolean isRunning() { return running; }
    String lastCode() { return lastCode; }
    String lastError() { return lastError; }

    /** A concurrent alarm can change the capped target, but cannot extend this signal's lease. */
    void start(int volumePercent) {
        requireMain();
        try {
            int maximum = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            int percent = Math.max(5, Math.min(80, volumePercent));
            targetVolume = Math.max(1, (int) Math.floor(maximum * percent / 100d));
            targetVolume = Math.min(maximum, targetVolume);
            if (running) return;
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(new FocusListener(this, ++focusGeneration), main).build();
            focusHeld = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if (!focusHeld || maximum <= 0) { finish("focus_unavailable", true); return; }
            originalVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM);
            originalMuted = audio.isStreamMute(AudioManager.STREAM_ALARM);
            ourVolume = Math.max(1, Math.min(targetVolume, (int) Math.floor(maximum * .1d)));
            volumeOwned = true;
            audio.setStreamVolume(AudioManager.STREAM_ALARM, ourVolume, 0);
            audio.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_UNMUTE, 0);
            wake.acquire(MAX_RING_MS + 5000);
            deadline = SystemClock.elapsedRealtime() + MAX_RING_MS;
            running = true; lastCode = "playing";
            MediaPlayer next = new MediaPlayer(); player = next;
            next.setAudioAttributes(attributes); next.setLooping(true);
            next.setOnPreparedListener(this); next.setOnErrorListener(this);
            for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    next.setPreferredDevice(device); break;
                }
            }
            try (AssetFileDescriptor asset = context.getAssets().openFd("schedule-signal.wav")) {
                next.setDataSource(asset.getFileDescriptor(), asset.getStartOffset(), asset.getLength());
            }
            next.prepareAsync(); main.postDelayed(this, 1000);
        } catch (Exception unavailable) { finish("signal_unavailable", true); }
    }

    @Override public void onPrepared(MediaPlayer prepared) {
        if (prepared != player || !running) return;
        try { prepared.start(); }
        catch (RuntimeException unavailable) { finish("signal_unavailable", true); }
    }

    @Override public boolean onError(MediaPlayer failed, int what, int extra) {
        if (failed == player) finish("signal_unavailable", true);
        return true;
    }

    @Override public void onAudioFocusChange(int change) {
        if (change == AudioManager.AUDIOFOCUS_LOSS
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            finish("focus_lost", true);
    }

    @Override public void run() {
        if (!running) return;
        if (SystemClock.elapsedRealtime() >= deadline) { finish("expired", true); return; }
        try {
            if (volumeOwned && (audio.getStreamVolume(AudioManager.STREAM_ALARM) != ourVolume
                    || audio.isStreamMute(AudioManager.STREAM_ALARM)))
                volumeOwned = false; // A user/external change owns the stream from now on.
            if (volumeOwned) {
                int next = ourVolume < targetVolume ? ourVolume + 1 : targetVolume;
                if (next != ourVolume) {
                    audio.setStreamVolume(AudioManager.STREAM_ALARM, next, 0); ourVolume = next;
                }
            }
            main.postDelayed(this, 1000);
        } catch (RuntimeException unavailable) { finish("signal_unavailable", true); }
    }

    void stop() { requireMain(); finish("dismissed", false); }

    private void finish(String reason, boolean notify) {
        main.removeCallbacks(this);
        ++focusGeneration;
        running = false; lastCode = reason;
        if ("signal_unavailable".equals(reason) || "focus_unavailable".equals(reason)
                || "focus_lost".equals(reason)) lastError = reason;
        MediaPlayer old = player; player = null;
        if (old != null) {
            try { old.setOnPreparedListener(null); old.setOnErrorListener(null); old.release(); }
            catch (RuntimeException ignored) { }
        }
        try {
            if (volumeOwned && audio.getStreamVolume(AudioManager.STREAM_ALARM) == ourVolume
                    && !audio.isStreamMute(AudioManager.STREAM_ALARM)) {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0);
                audio.adjustStreamVolume(AudioManager.STREAM_ALARM,
                        originalMuted ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
            }
        } catch (RuntimeException ignored) { }
        volumeOwned = false;
        if (focusHeld) {
            focusHeld = false;
            try { audio.abandonAudioFocusRequest(focus); } catch (RuntimeException ignored) { }
        }
        try { if (wake.isHeld()) wake.release(); } catch (RuntimeException ignored) { }
        if (notify) listener.stopped(reason);
    }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("signal_thread");
    }

    private static final class FocusListener implements AudioManager.OnAudioFocusChangeListener {
        private final ScheduleSignal owner; private final long generation;
        FocusListener(ScheduleSignal owner, long generation) { this.owner = owner; this.generation = generation; }
        @Override public void onAudioFocusChange(int change) {
            if (generation == owner.focusGeneration) owner.onAudioFocusChange(change);
        }
    }
}
