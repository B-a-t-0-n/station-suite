package local.station.spectrum;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.audiofx.Visualizer;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit, bounded output-mix experiment. Never captures microphone PCM. */
public final class SpectrumProbeService extends Service {
    public static final String START = "local.station.spectrum.START";
    public static final String STOP = "local.station.spectrum.STOP";
    private static final String CHANNEL = "spectrum_diagnostic";
    private static final long LIMIT_MS = 30000L;
    private static final int BANDS = 24;
    private static final int REPORT_LIMIT = 32768;
    private final Object stateLock = new Object();
    private final Object reportLock = new Object();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();
    private Visualizer visualizer;
    private AudioTrack tone;
    private String runId;
    private String mode;
    private long began;
    private long ended;
    private long deadline;
    private String reason = "running";
    private String stage = "created";
    private int captureSize;
    private int captureRateMilliHz;
    private int samplingRateHz;
    private int frames;
    private int nonzeroFrames;
    private int aboveFloorFrames;
    private int invalidFrames;
    private int sampleRateChanges;
    private double peak;
    private final double[] sums = new double[BANDS];
    private final double[] peaks = new double[BANDS];
    private final int[] secondFrames = new int[30];
    private final double[] secondPeaks = new double[30];
    private boolean tonePlayed;
    private boolean toneCreated;
    private boolean visualizerCreated;
    private boolean toneReleased;
    private boolean visualizerReleased;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Spectrum diagnostic",
                NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false);
        manager.createNotificationChannel(channel);
        Intent stop = new Intent(this, SpectrumProbeService.class).setAction(STOP);
        PendingIntent pending = PendingIntent.getService(this, 0, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("Station spectrum test")
                .setContentText("Stops automatically within 30 seconds")
                .setOnlyAlertOnce(true).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", pending).build())
                .build();
        startForeground(7421, notification);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (finished.get()) { stopSelf(); return START_NOT_STICKY; }
        if (intent != null && STOP.equals(intent.getAction())) {
            finish("cancelled");
            return START_NOT_STICKY;
        }
        if (intent == null || !START.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // A repeated explicit start never extends or replaces a running window.
        if (!started.compareAndSet(false, true)) return START_NOT_STICKY;
        mode = intent.getStringExtra("mode");
        if (!("idle".equals(mode) || "music".equals(mode) || "paused".equals(mode)
                || "voice".equals(mode) || "control".equals(mode))) {
            mode = "invalid";
            finish("invalid_mode");
            return START_NOT_STICKY;
        }
        runId = UUID.randomUUID().toString();
        began = SystemClock.elapsedRealtime();
        deadline = began + LIMIT_MS;
        clock.schedule(new Runnable() {
            @Override public void run() { finish("deadline"); }
        }, LIMIT_MS, TimeUnit.MILLISECONDS);
        writeReport();
        new Thread(new Runnable() {
            @Override public void run() { initializeCapture(); }
        }, "SpectrumInit").start();
        return START_NOT_STICKY;
    }

    private void initializeCapture() {
        Visualizer candidate = null;
        try {
            if (finished.get()) return;
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                finish("permission_denied"); return;
            }
            synchronized (stateLock) { stage = "initializing"; }
            candidate = new Visualizer(0);
            boolean late;
            synchronized (stateLock) {
                late = finished.get();
                if (!late) { visualizer = candidate; visualizerCreated = true; }
            }
            if (late) { releaseVisualizer(candidate); return; }
            if (finished.get()) return;
            int[] range = Visualizer.getCaptureSizeRange();
            if (range.length != 2 || range[0] < 2 || range[1] < range[0]) {
                throw new IllegalStateException("capture_range");
            }
            int size = 1024;
            while (size > range[1] && size > 2) size /= 2;
            if (size < range[0]) throw new IllegalStateException("capture_range");
            require(candidate.setCaptureSize(size));
            if (finished.get()) return;
            require(candidate.setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED));
            if (finished.get()) return;
            int rate = Math.min(10000, Visualizer.getMaxCaptureRate());
            if (rate <= 0) throw new IllegalStateException("capture_rate");
            require(candidate.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onWaveFormDataCapture(Visualizer source, byte[] data, int rate) {
                    // Waveform capture is disabled; never retain unexpected waveform data.
                }
                @Override public void onFftDataCapture(Visualizer source, byte[] fft, int rate) {
                    captureFft(fft, rate);
                }
            }, rate, false, true));
            synchronized (stateLock) { captureSize = size; captureRateMilliHz = rate; }
            if (finished.get()) return;
            require(candidate.setEnabled(true));
            if (finished.get()) return;
            synchronized (stateLock) { stage = "enabled"; }
            if ("control".equals(mode)) {
                clock.schedule(new Runnable() {
                    @Override public void run() {
                        new Thread(new Runnable() {
                            @Override public void run() { playControlTone(); }
                        }, "SpectrumTone").start();
                    }
                }, 3, TimeUnit.SECONDS);
            }
        } catch (Exception unavailable) {
            finish("setup_failed_" + unavailable.getClass().getSimpleName());
        }
    }

    private static void require(int status) {
        if (status != Visualizer.SUCCESS) throw new IllegalStateException("visualizer_status");
    }

    private void captureFft(byte[] fft, int samplingRateMilliHz) {
        if (finished.get()) return;
        long now = SystemClock.elapsedRealtime();
        if (now >= deadline) { finish("deadline"); return; }
        try {
            if (samplingRateMilliHz <= 0 || samplingRateMilliHz % 1000 != 0) {
                throw new IllegalArgumentException("sampling_rate");
            }
            int rate = samplingRateMilliHz / 1000;
            double[] bands = SpectrumBands.magnitudes(fft, rate);
            if (bands.length != BANDS) throw new IllegalArgumentException("band_count");
            double framePeak = 0;
            for (double value : bands) {
                if (Double.isNaN(value) || Double.isInfinite(value) || value < 0 || value > 1) {
                    throw new IllegalArgumentException("band_value");
                }
                framePeak = Math.max(framePeak, value);
            }
            synchronized (stateLock) {
                if (finished.get()) return;
                if (samplingRateHz != 0 && samplingRateHz != rate) sampleRateChanges++;
                samplingRateHz = rate;
                frames++;
                if (framePeak > 0) nonzeroFrames++;
                if (framePeak >= 0.02) aboveFloorFrames++;
                peak = Math.max(peak, framePeak);
                int second = (int) Math.min(29, Math.max(0, (now - began) / 1000));
                secondFrames[second]++;
                secondPeaks[second] = Math.max(secondPeaks[second], framePeak);
                for (int i = 0; i < BANDS; i++) {
                    sums[i] += bands[i]; peaks[i] = Math.max(peaks[i], bands[i]);
                }
            }
        } catch (RuntimeException invalid) {
            synchronized (stateLock) { invalidFrames++; }
            finish("invalid_fft");
        }
    }

    private void playControlTone() {
        AudioTrack candidate = null;
        try {
            if (finished.get()) return;
            candidate = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(64000).build();
            boolean late;
            synchronized (stateLock) {
                late = finished.get();
                if (!late) { tone = candidate; toneCreated = true; }
            }
            if (late) { releaseTone(candidate); return; }
            short[] pcm = new short[32000];
            for (int i = 0; i < pcm.length; i++) {
                pcm[i] = (short) Math.round(655.0 * Math.sin(2 * Math.PI * 880 * i / 16000));
            }
            if (finished.get()) return;
            if (candidate.write(pcm, 0, pcm.length, AudioTrack.WRITE_NON_BLOCKING) != pcm.length) {
                throw new IllegalStateException("tone_write");
            }
            if (finished.get()) return;
            candidate.play();
            synchronized (stateLock) { tonePlayed = true; }
            clock.schedule(new Runnable() {
                @Override public void run() { detachTone(); }
            }, 2, TimeUnit.SECONDS);
        } catch (Exception failure) {
            finish("control_failed_" + failure.getClass().getSimpleName());
        }
    }

    private void detachTone() {
        final AudioTrack old;
        synchronized (stateLock) { old = tone; tone = null; }
        if (old != null) new Thread(new Runnable() {
            @Override public void run() { releaseTone(old); }
        }, "SpectrumToneStop").start();
    }

    private void finish(String why) {
        if (!finished.compareAndSet(false, true)) return;
        final Visualizer oldVisualizer;
        final AudioTrack oldTone;
        synchronized (stateLock) {
            reason = why; ended = SystemClock.elapsedRealtime();
            oldVisualizer = visualizer; visualizer = null;
            oldTone = tone; tone = null;
        }
        clock.shutdownNow();
        stopSelf();
        // Native release and filesystem IO never run on the independent watchdog.
        writeReportAsync();
        if (oldVisualizer != null) new Thread(new Runnable() {
            @Override public void run() { releaseVisualizer(oldVisualizer); }
        }, "SpectrumVisualizerCleanup").start();
        if (oldTone != null) new Thread(new Runnable() {
            @Override public void run() { releaseTone(oldTone); }
        }, "SpectrumTrackCleanup").start();
    }

    private void releaseVisualizer(Visualizer value) {
        try { value.setEnabled(false); } catch (RuntimeException ignored) { }
        try {
            value.release();
            synchronized (stateLock) { visualizerReleased = true; }
            if (finished.get()) writeReportAsync();
        } catch (RuntimeException ignored) { }
    }

    private void releaseTone(AudioTrack value) {
        try { value.stop(); } catch (RuntimeException ignored) { }
        try {
            value.release();
            synchronized (stateLock) { toneReleased = true; }
            if (finished.get()) writeReportAsync();
        } catch (RuntimeException ignored) { }
    }

    private JSONObject snapshot() {
        synchronized (stateLock) {
            JSONObject data = new JSONObject();
            try {
                data.put("schema", 1).put("runId", runId == null ? "none" : runId)
                        .put("mode", mode == null ? "none" : mode).put("reason", reason)
                        .put("result", "deadline".equals(reason) || "cancelled".equals(reason)
                                ? (frames > 0 ? "ok" : "no_frames") : reason)
                        .put("stage", stage).put("session", 0).put("limitMs", LIMIT_MS)
                        .put("elapsedMs", began == 0 ? 0 : (ended == 0 ? SystemClock.elapsedRealtime() : ended) - began)
                        .put("captureSize", captureSize).put("captureRateMilliHz", captureRateMilliHz)
                        .put("samplingRateHz", samplingRateHz).put("sampleRateChanges", sampleRateChanges)
                        .put("frames", frames).put("nonzeroFrames", nonzeroFrames)
                        .put("aboveFloorFrames", aboveFloorFrames).put("aboveFloorThreshold", 0.02)
                        .put("invalidFrames", invalidFrames).put("peak", peak)
                        .put("visualizerCreated", visualizerCreated).put("toneCreated", toneCreated)
                        .put("visualizerReleased", visualizerReleased).put("tonePlayed", tonePlayed)
                        .put("toneReleased", toneReleased).put("finished", finished.get());
                JSONArray meanBands = new JSONArray(); JSONArray peakBands = new JSONArray();
                JSONArray seconds = new JSONArray();
                for (int i = 0; i < BANDS; i++) {
                    meanBands.put(frames == 0 ? 0 : sums[i] / frames); peakBands.put(peaks[i]);
                }
                for (int i = 0; i < secondFrames.length; i++) {
                    seconds.put(new JSONObject().put("second", i).put("frames", secondFrames[i])
                            .put("peak", secondPeaks[i]));
                }
                data.put("meanBands", meanBands).put("peakBands", peakBands).put("seconds", seconds);
            } catch (org.json.JSONException invalid) {
                throw new IllegalStateException("aggregate_json");
            }
            return data;
        }
    }

    private void writeReport() {
        if (runId == null) return;
        synchronized (reportLock) {
        try {
            byte[] payload = (snapshot().toString() + "\n").getBytes(StandardCharsets.UTF_8);
            if (payload.length > REPORT_LIMIT) throw new IllegalStateException("report_size");
            File directory = new File(getFilesDir(), "spectrum-probe");
            if (!directory.isDirectory() && !directory.mkdir()) throw new IllegalStateException("report_dir");
            writeAtomic(new File(directory, "probe-" + runId + ".json"), payload);
            writeAtomic(new File(directory, "latest.json"), payload);
            writeAtomic(new File(getFilesDir(), "report-" + mode + ".json"), payload);
            File[] reports = directory.listFiles();
            if (reports != null) {
                Arrays.sort(reports, new Comparator<File>() {
                    @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
                });
                int kept = 0;
                for (File report : reports) {
                    if (report.getName().matches("probe-[0-9a-f-]{36}\\.json") && ++kept > 8) {
                        if (!report.delete()) Log.w("SpectrumProbe", "OLD_REPORT_NOT_REMOVED");
                    }
                }
            }
        } catch (Exception failure) {
            Log.w("SpectrumProbe", "REPORT_WRITE_FAILED");
        }
        }
    }

    private void writeReportAsync() {
        new Thread(new Runnable() {
            @Override public void run() { writeReport(); }
        }, "SpectrumReport").start();
    }

    private static void writeAtomic(File target, byte[] payload) throws Exception {
        AtomicFile file = new AtomicFile(target);
        FileOutputStream output = null;
        try { output = file.startWrite(); output.write(payload); file.finishWrite(output); }
        catch (Exception failure) { if (output != null) file.failWrite(output); throw failure; }
    }

    @Override public void onDestroy() {
        finish("destroyed");
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
