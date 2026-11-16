package local.station.spectrum;

/** Stateless reduction of Android Visualizer's signed FFT bytes; never retains audio. */
public final class SpectrumBands {
    public static final int BAND_COUNT = 24;
    private static final double LOW_HZ = 50.0;
    private static final double HIGH_HZ = 16000.0;

    private SpectrumBands() { }

    /**
     * Returns the peak relative magnitude in each logarithmic frequency band.
     * The frequency range is 50 Hz through min(16 kHz, Nyquist), inclusive.
     * DC is excluded; the separate Nyquist byte is included only within range.
     *
     * Each signed complex magnitude is divided by 128 and clipped to [0, 1].
     * This is a relative Visualizer level, not calibrated loudness or band power.
     * Empty bands remain zero. The caller must convert callback milliHz to Hz.
     */
    public static double[] magnitudes(byte[] fft, int sampleRateHz) {
        if (fft == null || fft.length < 2 || fft.length > 8192
                || (fft.length & (fft.length - 1)) != 0) {
            throw new IllegalArgumentException("invalid_fft");
        }
        if (sampleRateHz < 8000 || sampleRateHz > 192000) {
            throw new IllegalArgumentException("invalid_sample_rate_hz");
        }
        double[] bands = new double[BAND_COUNT];
        int half = fft.length / 2;
        double upperHz = Math.min(HIGH_HZ, sampleRateHz / 2.0);
        double logSpan = Math.log(upperHz / LOW_HZ);
        for (int bin = 1; bin <= half; bin++) {
            double frequencyHz = bin * (double) sampleRateHz / fft.length;
            if (frequencyHz < LOW_HZ) continue;
            if (frequencyHz > upperHz) break;
            int band = (int) Math.floor(Math.log(frequencyHz / LOW_HZ) * BAND_COUNT / logSpan);
            // The inclusive upper endpoint maps mathematically to BAND_COUNT.
            band = Math.max(0, Math.min(BAND_COUNT - 1, band));
            double magnitude = bin == half
                    ? Math.abs((int) fft[1]) / 128.0
                    : Math.hypot((int) fft[2 * bin], (int) fft[2 * bin + 1]) / 128.0;
            bands[band] = Math.max(bands[band], Math.min(1.0, magnitude));
        }
        return bands;
    }
}
