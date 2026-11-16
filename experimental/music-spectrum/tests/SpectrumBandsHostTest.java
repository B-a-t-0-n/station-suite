package local.station.spectrum;

import java.util.Arrays;
import java.util.Random;

/** Deterministic synthetic FFT fixtures; runs the actual production class. */
public final class SpectrumBandsHostTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void near(double expected, double actual) {
        check(Math.abs(expected - actual) < 1e-12, "expected " + expected + ", got " + actual);
    }

    private static void onlyBand(double[] values, int selected, double expected) {
        check(values.length == 24, "exactly 24 bands");
        for (int i = 0; i < values.length; i++) near(i == selected ? expected : 0.0, values[i]);
    }

    private static byte[] tone(int size, int bin, int real, int imaginary) {
        byte[] fft = new byte[size];
        fft[bin * 2] = (byte) real;
        fft[bin * 2 + 1] = (byte) imaginary;
        return fft;
    }

    private static void rejected(byte[] fft, int sampleRateHz) {
        try {
            SpectrumBands.magnitudes(fft, sampleRateHz);
            throw new AssertionError("invalid data accepted");
        } catch (IllegalArgumentException expected) { }
    }

    private static void run(String name) {
        if ("silence".equals(name)) {
            onlyBand(SpectrumBands.magnitudes(new byte[1024], 48000), -1, 0.0);
        } else if ("fixed_frequency".equals(name)) {
            // 32 * 32000 / 1024 = 1000 Hz, in logarithmic band 12.
            onlyBand(SpectrumBands.magnitudes(tone(1024, 32, 64, 0), 32000), 12, 0.5);
        } else if ("signed_complex".equals(name)) {
            onlyBand(SpectrumBands.magnitudes(tone(1024, 32, -3, 4), 32000), 12, 5.0 / 128.0);
            onlyBand(SpectrumBands.magnitudes(tone(1024, 32, -3, -4), 32000), 12, 5.0 / 128.0);
            onlyBand(SpectrumBands.magnitudes(tone(1024, 32, -128, 0), 32000), 12, 1.0);
            onlyBand(SpectrumBands.magnitudes(tone(1024, 32, -128, -128), 32000), 12, 1.0);
        } else if ("dc_excluded".equals(name)) {
            byte[] fft = new byte[1024];
            fft[0] = -128;
            onlyBand(SpectrumBands.magnitudes(fft, 32000), -1, 0.0);
        } else if ("nyquist".equals(name)) {
            byte[] fft = new byte[1024];
            fft[1] = -128;
            onlyBand(SpectrumBands.magnitudes(fft, 32000), 23, 1.0); // 16 kHz.
            onlyBand(SpectrumBands.magnitudes(fft, 8000), 23, 1.0); // 4 kHz.
            onlyBand(SpectrumBands.magnitudes(fft, 48000), -1, 0.0); // 24 kHz excluded.
        } else if ("frequency_limits".equals(name)) {
            onlyBand(SpectrumBands.magnitudes(tone(256, 1, 64, 0), 12800), 0, 0.5); // 50 Hz.
            onlyBand(SpectrumBands.magnitudes(tone(1024, 1, 64, 0), 48000), -1, 0.0); // 46.875 Hz.
            onlyBand(SpectrumBands.magnitudes(tone(256, 64, 64, 0), 64000), 23, 0.5); // 16 kHz.
            onlyBand(SpectrumBands.magnitudes(tone(256, 65, 64, 0), 64000), -1, 0.0); // 16.25 kHz.
        } else if ("band_peak".equals(name)) {
            byte[] fft = tone(1024, 31, 32, 0);
            fft[64] = 96; // 1000 Hz, same band as 968.75 and 1031.25 Hz.
            fft[66] = 64;
            onlyBand(SpectrumBands.magnitudes(fft, 32000), 12, 0.75);
        } else if ("separate_bands".equals(name)) {
            byte[] fft = tone(1024, 2, 32, 0); // 62.5 Hz => band 0.
            fft[16] = 64; // 250 Hz => band 6.
            fft[64] = 96; // 1000 Hz => band 12.
            double[] result = SpectrumBands.magnitudes(fft, 32000);
            for (int i = 0; i < 24; i++) near(i == 0 ? 0.25 : i == 6 ? 0.5 : i == 12 ? 0.75 : 0.0, result[i]);
        } else if ("malformed_fft".equals(name)) {
            rejected(null, 48000);
            for (int size : new int[] {0, 1, 3, 6, 8193, 16384}) rejected(new byte[size], 48000);
        } else if ("sample_bounds".equals(name)) {
            for (int rate : new int[] {-1, 0, 7999, 192001, Integer.MAX_VALUE, 48000000}) {
                rejected(new byte[1024], rate);
            }
            onlyBand(SpectrumBands.magnitudes(new byte[1024], 8000), -1, 0.0);
            onlyBand(SpectrumBands.magnitudes(new byte[1024], 192000), -1, 0.0);
        } else if ("smallest_fft".equals(name)) {
            onlyBand(SpectrumBands.magnitudes(new byte[] {-128, 64}, 8000), 23, 0.5);
        } else if ("no_retained_data".equals(name)) {
            byte[] fft = tone(1024, 32, 64, 0);
            byte[] original = fft.clone();
            double[] first = SpectrumBands.magnitudes(fft, 32000);
            check(Arrays.equals(original, fft), "input changed");
            first[12] = 999;
            onlyBand(SpectrumBands.magnitudes(fft, 32000), 12, 0.5);
            Arrays.fill(fft, (byte) 0);
            onlyBand(SpectrumBands.magnitudes(fft, 32000), -1, 0.0);
        } else if ("finite_range".equals(name)) {
            Random random = new Random(1473);
            for (int size = 2; size <= 8192; size *= 2) {
                byte[] fft = new byte[size];
                random.nextBytes(fft);
                byte[] original = fft.clone();
                for (int rate : new int[] {8000, 44100, 48000, 192000}) {
                    double[] result = SpectrumBands.magnitudes(fft, rate);
                    check(result.length == 24, "band count");
                    for (double value : result) check(Double.isFinite(value) && value >= 0.0 && value <= 1.0, "invalid level");
                    check(Arrays.equals(original, fft), "input changed");
                }
            }
        } else {
            throw new IllegalArgumentException("unknown test");
        }
    }

    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("one test required");
        run(args[0]);
        System.out.println("OK " + args[0]);
    }
}
