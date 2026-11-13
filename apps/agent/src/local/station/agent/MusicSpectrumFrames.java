package local.station.agent;

/** Android's signed 8-bit FFT layout converted to the native 513-bin dB scale. */
final class MusicSpectrumFrames {
    static final int BINS = 513;
    static final float FLOOR_DB = -80f;
    private MusicSpectrumFrames() { }

    static float[] convert(byte[] fft) {
        if (fft == null || fft.length < 2 || fft.length > 8192
                || (fft.length & (fft.length - 1)) != 0)
            throw new IllegalArgumentException("invalid_fft");
        int half = fft.length / 2;
        float[] result = new float[BINS];
        for (int bin = 0; bin < BINS; bin++) {
            double source = bin * (double) half / (BINS - 1);
            int left = (int) source;
            int right = Math.min(half, left + 1);
            double amount = source - left;
            double amplitude = amplitude(fft, left, half) * (1d - amount)
                    + amplitude(fft, right, half) * amount;
            double db = amplitude <= 0d ? FLOOR_DB : 20d * Math.log10(amplitude / 128d);
            result[bin] = (float) Math.max(FLOOR_DB, Math.min(0d, db));
        }
        return result;
    }

    private static double amplitude(byte[] fft, int bin, int half) {
        if (bin == 0) return Math.abs((int) fft[0]);
        if (bin == half) return Math.abs((int) fft[1]);
        return Math.hypot((int) fft[bin * 2], (int) fft[bin * 2 + 1]);
    }

    static int captureSize(int minimum, int maximum) {
        int best = 0;
        for (int size = 2; size <= 8192; size *= 2) {
            if (size < minimum || size > maximum) continue;
            if (best == 0 || Math.abs(size - 1024) < Math.abs(best - 1024)) best = size;
        }
        if (best == 0) throw new IllegalArgumentException("capture_size_unsupported");
        return best;
    }
}
