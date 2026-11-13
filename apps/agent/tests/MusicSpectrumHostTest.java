package local.station.agent;

import android.media.audiofx.Visualizer;
import java.util.Arrays;
import org.json.JSONObject;

public final class MusicSpectrumHostTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static void close(float actual, double expected, String message) {
        check(Math.abs(actual - expected) < .0001d, message + ": " + actual);
    }
    private static void reject(byte[] value) {
        try { MusicSpectrumFrames.convert(value); throw new AssertionError("invalid FFT accepted"); }
        catch (IllegalArgumentException expected) { assertions++; }
    }
    private static void conversion() {
        byte[] fft = new byte[1024];
        float[] silent = MusicSpectrumFrames.convert(fft);
        check(silent.length == 513, "native bin count");
        for (float value : silent) close(value, -80, "silence floor");
        fft[0] = (byte) -128; fft[1] = 64;
        fft[2] = -64; fft[3] = 0;
        fft[4] = -64; fft[5] = 64;
        fft[1022] = 0; fft[1023] = -128;
        float[] values = MusicSpectrumFrames.convert(fft);
        close(values[0], 0, "signed DC full scale");
        close(values[512], 20 * Math.log10(.5), "Nyquist stored at index1");
        close(values[1], 20 * Math.log10(.5), "signed real");
        close(values[2], 20 * Math.log10(Math.sqrt(.5)), "complex hypot");
        close(values[511], 0, "final complex pair");
        fft[2] = -128; fft[3] = -128;
        close(MusicSpectrumFrames.convert(fft)[1], 0, "overload clamp");
        byte[] tiny = new byte[] {0, 64};
        values = MusicSpectrumFrames.convert(tiny);
        close(values[256], 20 * Math.log10(.25), "interpolate amplitude");
        close(values[512], 20 * Math.log10(.5), "resampled Nyquist");
        for (int size = 2; size <= 8192; size *= 2) {
            byte[] raw = new byte[size];
            Arrays.fill(raw, (byte) 127);
            for (float value : MusicSpectrumFrames.convert(raw))
                check(!Float.isNaN(value) && !Float.isInfinite(value) && value >= -80 && value <= 0,
                        "bounded finite spectrum");
        }
        reject(null); reject(new byte[0]); reject(new byte[1]); reject(new byte[3]); reject(new byte[16384]);
        check(MusicSpectrumFrames.captureSize(128, 1024) == 1024, "preferred size");
        check(MusicSpectrumFrames.captureSize(128, 512) == 512, "smaller device");
        check(MusicSpectrumFrames.captureSize(2048, 8192) == 2048, "larger minimum");
        try { MusicSpectrumFrames.captureSize(8193, 65536); throw new AssertionError("oversized capture"); }
        catch (IllegalArgumentException expected) { assertions++; }
    }

    private static void lifecycle() throws Exception {
        Visualizer.reset();
        final int[] received = {0};
        final long[] lastTime = {-1};
        MusicSpectrumSource source = new MusicSpectrumSource((values, rate, time) -> {
            check(values.length == 513, "callback bins");
            check(rate == 48000, "milliHz to Hz");
            check(time >= 0 && time >= lastTime[0], "monotonic running time");
            received[0]++; lastTime[0] = time;
        });
        check(!source.attach(0), "global session refused");
        check(!source.start(), "global start refused");
        check(!source.attach(-4), "negative session refused");
        check(Visualizer.creations == 0, "invalid session never reaches engine");
        check(source.attach(17), "own session attached");
        check(Visualizer.lastSession == 17, "exact session");
        Visualizer old = Visualizer.last;
        check(old.size == 1024 && old.rate == 25000 && !old.waveform && old.fft, "capture configuration");
        check(old.scaling == Visualizer.SCALING_MODE_NORMALIZED, "normalized spectrum");
        old.emit(new byte[1024], 48000000);
        check(received[0] == 0, "no frames before start");
        check(source.start(), "capture started");
        old.emit(new byte[1024], 48000000);
        old.emit(new byte[1024], 48000000);
        check(received[0] == 2, "playing frames delivered");
        JSONObject status = source.status();
        check(status.getBoolean("available") && status.getBoolean("capturing") && status.getLong("frames") == 2,
                "playing status");
        source.pause();
        check(old.released && !source.status().getBoolean("capturing"), "pause releases effect");
        old.emitRetained(new byte[1024], 48000000);
        check(received[0] == 2, "stale callback after pause ignored");
        lastTime[0] = -1;
        check(source.start(), "resume recreates own effect");
        check(Visualizer.creations == 2 && Visualizer.lastSession == 17, "resume keeps session");
        Visualizer latest = Visualizer.last;
        old.emitRetained(new byte[1024], 48000000);
        latest.emit(new byte[1024], 48000000);
        check(received[0] == 3, "only current generation delivered");
        check(source.attach(23), "replace session");
        check(latest.released && Visualizer.lastSession == 23, "replacement releases old");
        source.detach(); source.release();
        check(!source.start() && !source.status().getBoolean("available"), "detached source cannot resume");
        check(Visualizer.creations == Visualizer.releases, "all effects released once");

        Visualizer.fail = "permission";
        check(!source.attach(24), "permission failure nonfatal");
        check("permission_required".equals(source.status().getString("error")), "fixed permission code");
        Visualizer.fail = "setup";
        check(!source.attach(25), "setup failure nonfatal");
        check(Visualizer.creations == Visualizer.releases, "partial initialization released");
        Visualizer.fail = "";
        Visualizer.maximumRate = 20000;
        check(source.attach(26), "limited rate device");
        check(Visualizer.last.rate == 20000, "rate never exceeds engine maximum");
        check(source.start(), "limited rate starts");
        Visualizer.last.emit(new byte[1024], 0);
        check("invalid_capture_frame".equals(source.status().getString("error"))
                && !source.status().getBoolean("capturing"), "invalid rate shuts capture down");
        check(Visualizer.creations == Visualizer.releases, "bad callback releases effect");
        Visualizer.fail = "enable";
        check(source.attach(27) && !source.start(), "enable failure nonfatal");
        check(Visualizer.creations == Visualizer.releases, "failed enable releases effect");
        Visualizer.fail = "";
        MusicSpectrumSource badSink = new MusicSpectrumSource((values, rate, time) -> {
            throw new IllegalStateException("must not expose this text");
        });
        check(badSink.attach(28) && badSink.start(), "bad sink setup");
        Visualizer.last.emit(new byte[1024], 48000000);
        check("spectrum_sink_failed".equals(badSink.status().getString("error")), "sink error fixed code");
        check(!badSink.status().getBoolean("capturing"), "sink failure stops capture");
        check(Visualizer.creations == Visualizer.releases, "sink failure cleanup");
    }

    public static void main(String[] args) throws Exception {
        conversion(); lifecycle();
        System.out.println(assertions + " music spectrum assertions passed");
    }
}
