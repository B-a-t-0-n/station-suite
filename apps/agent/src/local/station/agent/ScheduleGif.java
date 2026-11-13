package local.station.agent;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/** Finite, self-contained 25x16 LED countdown. No Android, network, or persistent state. */
public final class ScheduleGif {
    private static final int WIDTH = 25, HEIGHT = 16, DIGIT_WIDTH = 3, DIGIT_HEIGHT = 7;
    private static final String[][] DIGITS = {
            {"111", "101", "101", "101", "101", "101", "111"},
            {"010", "110", "010", "010", "010", "010", "111"},
            {"111", "001", "001", "111", "100", "100", "111"},
            {"111", "001", "001", "111", "001", "001", "111"},
            {"101", "101", "101", "111", "001", "001", "001"},
            {"111", "100", "100", "111", "001", "001", "111"},
            {"111", "100", "100", "111", "101", "101", "111"},
            {"111", "001", "001", "010", "010", "010", "010"},
            {"111", "101", "101", "111", "101", "101", "111"},
            {"111", "101", "101", "111", "001", "001", "111"}
    };

    private ScheduleGif() { }

    /**
     * Shows MM:SS below one hour, H:MM/HH:MM from one hour onward.
     * At most ten one-second full-image frames, without a looping extension.
     * Brightness is capped at 64/255; no transparent pixels or partial images.
     */
    public static byte[] countdown(int seconds) {
        if (seconds < 1 || seconds > 86400) throw new IllegalArgumentException("seconds_out_of_range");
        ByteArrayOutputStream output = new ByteArrayOutputStream(3000);
        writeAscii(output, "GIF89a");
        littleEndian(output, WIDTH); littleEndian(output, HEIGHT);
        output.write(0xf0); // Global two-color table, 8-bit color resolution.
        output.write(0); output.write(0); // Black background, square pixels.
        output.write(0); output.write(0); output.write(0);
        output.write(64); output.write(64); output.write(64);
        int frames = Math.min(10, seconds);
        for (int frame = 0; frame < frames; frame++) {
            // Graphic control extension: retain complete frame, no transparency.
            output.write(0x21); output.write(0xf9); output.write(4); output.write(4);
            littleEndian(output, 100); output.write(0); output.write(0);
            output.write(0x2c);
            littleEndian(output, 0); littleEndian(output, 0);
            littleEndian(output, WIDTH); littleEndian(output, HEIGHT); output.write(0);
            output.write(2); // GIF minimum LZW code size.
            byte[] compressed = lzw(pixels(seconds - frame));
            for (int offset = 0; offset < compressed.length; offset += 255) {
                int size = Math.min(255, compressed.length - offset);
                output.write(size); output.write(compressed, offset, size);
            }
            output.write(0);
        }
        output.write(0x3b);
        return output.toByteArray();
    }

    private static byte[] pixels(int seconds) {
        String text = seconds < 3600
                ? String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 3600, seconds / 60 % 60);
        int width = text.length() - 1;
        for (int i = 0; i < text.length(); i++) width += text.charAt(i) == ':' ? 1 : DIGIT_WIDTH;
        int x = (WIDTH - width) / 2, y = (HEIGHT - DIGIT_HEIGHT) / 2;
        byte[] image = new byte[WIDTH * HEIGHT];
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == ':') {
                image[(y + 2) * WIDTH + x] = 1;
                image[(y + 4) * WIDTH + x] = 1;
                x += 2;
            } else {
                String[] digit = DIGITS[character - '0'];
                for (int row = 0; row < DIGIT_HEIGHT; row++) {
                    for (int column = 0; column < DIGIT_WIDTH; column++) {
                        if (digit[row].charAt(column) == '1') image[(y + row) * WIDTH + x + column] = 1;
                    }
                }
                x += DIGIT_WIDTH + 1;
            }
        }
        return image;
    }

    /** Frequent clear codes keep all symbols at three bits, independent of dictionary growth. */
    private static byte[] lzw(byte[] pixels) {
        Bits bits = new Bits();
        for (int offset = 0; offset < pixels.length; offset += 2) {
            bits.code(4); // Clear dictionary before each pair of literal pixels.
            bits.code(pixels[offset]);
            if (offset + 1 < pixels.length) bits.code(pixels[offset + 1]);
        }
        bits.code(5); // End of information.
        return bits.finish();
    }

    private static void littleEndian(ByteArrayOutputStream output, int value) {
        output.write(value & 255); output.write((value >>> 8) & 255);
    }

    private static void writeAscii(ByteArrayOutputStream output, String text) {
        for (int i = 0; i < text.length(); i++) output.write(text.charAt(i));
    }

    private static final class Bits {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream(256);
        private int pending, count;
        Bits() { }
        void code(int value) {
            pending |= value << count; count += 3;
            while (count >= 8) {
                output.write(pending & 255); pending >>>= 8; count -= 8;
            }
        }
        byte[] finish() {
            if (count > 0) output.write(pending & 255);
            return output.toByteArray();
        }
    }
}
