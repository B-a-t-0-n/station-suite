package local.station.agent;

import android.media.audiofx.Visualizer;
import org.json.JSONObject;

/** Captures only an explicitly owned player session. It never opens the microphone. */
final class MusicSpectrumSource {
    /** Must be bounded and nonblocking: enqueue/coalesce the frame, never perform network I/O. */
    interface Sink { void frame(float[] magnitudes, int rate, long runningNs); }

    private final Sink sink;
    private Visualizer effect;
    private int sessionId, captureSize, captureRateMilliHz, sampleRateHz;
    private boolean available, capturing;
    private long frames, startedNs;
    private String error = "";

    MusicSpectrumSource(Sink sink) {
        if (sink == null) throw new IllegalArgumentException("sink_required");
        this.sink = sink;
    }

    synchronized boolean attach(int ownedSessionId) {
        releaseEffect();
        sessionId = ownedSessionId > 0 ? ownedSessionId : 0;
        available = false; frames = 0; sampleRateHz = 0;
        captureSize = 0; captureRateMilliHz = 0; error = "";
        if (sessionId == 0) { error = "owned_session_required"; return false; }
        return prepare();
    }

    private boolean prepare() {
        available = false;
        Visualizer candidate = null;
        try {
            int[] range = Visualizer.getCaptureSizeRange();
            if (range == null || range.length < 2) {
                error = "capture_size_unsupported"; return false;
            }
            captureSize = MusicSpectrumFrames.captureSize(range[0], range[1]);
            captureRateMilliHz = Math.min(25000, Visualizer.getMaxCaptureRate());
            if (captureRateMilliHz <= 0) { error = "capture_rate_unavailable"; return false; }
            candidate = new Visualizer(sessionId);
            if (candidate.setCaptureSize(captureSize) != Visualizer.SUCCESS
                    || candidate.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED) != Visualizer.SUCCESS) {
                error = "capture_setup_failed"; return false;
            }
            final Visualizer owner = candidate;
            if (candidate.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) { }
                @Override public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
                    accept(owner, fft, samplingRate);
                }
            }, captureRateMilliHz, false, true) != Visualizer.SUCCESS) {
                error = "capture_setup_failed"; return false;
            }
            effect = candidate; candidate = null; available = true; error = "";
            return true;
        } catch (SecurityException denied) { error = "permission_required"; }
        catch (UnsupportedOperationException unsupported) { error = "capture_unsupported"; }
        catch (IllegalArgumentException invalid) { error = "capture_size_unsupported"; }
        catch (RuntimeException failed) { error = "capture_setup_failed"; }
        finally { if (candidate != null) release(candidate); }
        available = false;
        return false;
    }

    synchronized boolean start() {
        if (capturing) return true;
        if (sessionId <= 0) { error = "owned_session_required"; return false; }
        if (effect == null && !prepare()) return false;
        try {
            Visualizer owner = effect;
            startedNs = System.nanoTime();
            // Mark it ready first: some engines can deliver the first callback during enable.
            capturing = true;
            if (owner.setEnabled(true) != Visualizer.SUCCESS) {
                capturing = false; error = "capture_enable_failed";
                available = false; releaseEffect(); return false;
            }
            if (effect != owner || !capturing) return false;
            error = "";
            return true;
        } catch (SecurityException denied) { error = "permission_required"; }
        catch (RuntimeException failed) { error = "capture_enable_failed"; }
        available = false; releaseEffect(); return false;
    }

    private synchronized void accept(Visualizer owner, byte[] fft, int samplingRateMilliHz) {
        if (owner != effect || !capturing) return;
        if (samplingRateMilliHz < 1000 || samplingRateMilliHz > 384000000
                || fft == null || fft.length != captureSize) {
            error = "invalid_capture_frame"; available = false; releaseEffect(); return;
        }
        try {
            float[] magnitudes = MusicSpectrumFrames.convert(fft);
            sampleRateHz = samplingRateMilliHz / 1000;
            // The sink contract is deliberately constant-time; this lock prevents late frames after pause.
            sink.frame(magnitudes, sampleRateHz, Math.max(0L, System.nanoTime() - startedNs));
            frames++;
        } catch (RuntimeException failed) {
            error = "spectrum_sink_failed"; available = false; releaseEffect();
        }
    }

    /** Frees the effect while retaining the owned session for a later start/resume. */
    synchronized void pause() { releaseEffect(); }

    synchronized void detach() {
        releaseEffect(); sessionId = 0; available = false;
    }

    synchronized void release() { detach(); }

    synchronized JSONObject status() throws Exception {
        return new JSONObject().put("available", available).put("capturing", capturing)
                .put("frames", frames).put("error", error).put("captureSize", captureSize)
                .put("captureRateMilliHz", captureRateMilliHz).put("sampleRateHz", sampleRateHz);
    }

    private void releaseEffect() {
        capturing = false;
        Visualizer old = effect; effect = null;
        if (old != null) release(old);
    }

    private static void release(Visualizer effect) {
        try { effect.setDataCaptureListener(null, 0, false, false); } catch (RuntimeException ignored) { }
        try { effect.setEnabled(false); } catch (RuntimeException ignored) { }
        try { effect.release(); } catch (RuntimeException ignored) { }
    }
}
