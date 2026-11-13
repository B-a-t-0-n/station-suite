"""Decode actual Java GIF bytes with Pillow and inspect their wire-level frame bounds."""
from io import BytesIO
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path(os.environ['STATION_JAVA_HOME']) / 'bin'

# Independent expected 3x7 typeface, row-major. The palette is the only brightness source.
DIGITS = [
    ('111', '101', '101', '101', '101', '101', '111'),
    ('010', '110', '010', '010', '010', '010', '111'),
    ('111', '001', '001', '111', '100', '100', '111'),
    ('111', '001', '001', '111', '001', '001', '111'),
    ('101', '101', '101', '111', '001', '001', '001'),
    ('111', '100', '100', '111', '001', '001', '111'),
    ('111', '100', '100', '111', '101', '101', '111'),
    ('111', '001', '001', '010', '010', '010', '010'),
    ('111', '101', '101', '111', '101', '101', '111'),
    ('111', '101', '101', '111', '001', '001', '111'),
]


def expected_pixels(seconds):
    text = (f'{seconds // 60:02}:{seconds % 60:02}' if seconds < 3600
            else f'{seconds // 3600}:{seconds // 60 % 60:02}')
    width = sum(1 if char == ':' else 3 for char in text) + len(text) - 1
    x, y = (25 - width) // 2, (16 - 7) // 2
    pixels = [(0, 0, 0)] * (25 * 16)
    for char in text:
        if char == ':':
            for row in (2, 4):
                pixels[(y + row) * 25 + x] = (64, 64, 64)
            x += 2
        else:
            for row, data in enumerate(DIGITS[int(char)]):
                for column, bit in enumerate(data):
                    if bit == '1':
                        pixels[(y + row) * 25 + x + column] = (64, 64, 64)
            x += 4
    return pixels


class ScheduleGifTests(unittest.TestCase):
    def test_finite_full_frames_pixels_palette_and_bounds(self):
        with tempfile.TemporaryDirectory(prefix='schedule-gif-test-') as directory:
            temporary = Path(directory)
            classes = temporary / 'classes'
            classes.mkdir()
            compile_result = subprocess.run([
                str(JAVA / 'javac.exe'), '-encoding', 'UTF-8', '--release', '8', '-Xlint:all',
                '-d', str(classes), str(ROOT / 'src/local/station/agent/ScheduleGif.java'),
                *map(str, (ROOT / 'tests/gif-host').rglob('*.java')),
            ], capture_output=True, text=True, timeout=30)
            self.assertEqual(compile_result.returncode, 0, compile_result.stderr)
            host = subprocess.run([
                str(JAVA / 'java.exe'), '-cp', str(classes),
                'local.station.agent.ScheduleGifHostTest', str(temporary),
            ], capture_output=True, text=True, timeout=30)
            self.assertEqual(host.returncode, 0, host.stderr)
            self.assertFalse(any(classes.rglob('*$[0-9]*.class')))
            gifs = sorted(temporary.glob('*.gif'))
            self.assertEqual(len(gifs), 18)
            total_frames = 0
            for path in gifs:
                seconds = int(path.stem)
                raw = path.read_bytes()
                self.assertEqual(raw[:6], b'GIF89a')
                self.assertEqual(raw[6:10], b'\x19\0\x10\0')
                self.assertTrue(raw[10] & 0x80)
                self.assertEqual(raw[10] & 7, 0)
                self.assertEqual(raw[13:19], b'\0\0\0\x40\x40\x40')
                wire_frames = self.check_wire(raw)
                with Image.open(BytesIO(raw)) as gif:
                    self.assertEqual(gif.size, (25, 16))
                    self.assertEqual(gif.n_frames, min(10, seconds))
                    self.assertEqual(wire_frames, gif.n_frames)
                    self.assertNotIn('loop', gif.info)
                    self.assertNotIn('transparency', gif.info)
                    for frame in range(gif.n_frames):
                        gif.seek(frame)
                        self.assertEqual(gif.info['duration'], 1000)
                        self.assertEqual(gif.disposal_method, 1)
                        rgb = gif.convert('RGB').tobytes()
                        actual = [tuple(rgb[offset:offset + 3]) for offset in range(0, len(rgb), 3)]
                        self.assertEqual(actual, expected_pixels(seconds - frame))
                        self.assertEqual(set(gif.convert('L').tobytes()), {0, 64})
                        total_frames += 1
            print(host.stdout.strip())
            print(f'{total_frames} complete GIF frames independently decoded with Pillow')

    def check_wire(self, raw):
        position, frames = 19, 0
        while raw[position] != 0x3b:
            self.assertEqual(raw[position:position + 8], b'\x21\xf9\x04\x04\x64\x00\x00\x00')
            position += 8
            # Exact descriptor proves full-size frames, no offset/transparency/local palette.
            self.assertEqual(raw[position:position + 10], b'\x2c\0\0\0\0\x19\0\x10\0\0')
            position += 10
            self.assertEqual(raw[position], 2)
            position += 1
            while raw[position]:
                size = raw[position]
                position += 1 + size
                self.assertLess(position, len(raw))
            position += 1
            frames += 1
        self.assertEqual(position, len(raw) - 1)
        return frames


if __name__ == '__main__':
    unittest.main()
