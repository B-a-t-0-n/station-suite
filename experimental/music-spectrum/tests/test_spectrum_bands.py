"""Compile and run actual pure Java spectrum math; no device, audio or network."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


def java_tool(name):
    override = os.environ.get('SPECTRUM_TEST_' + name.upper())
    if override:
        return Path(override)
    java_home = os.environ.get('JAVA_HOME')
    if java_home:
        candidate = Path(java_home) / 'bin' / (name + ('.exe' if os.name == 'nt' else ''))
        if candidate.is_file():
            return candidate
    return Path(shutil.which(name) or '__missing_java_tool__')


JAVAC = java_tool('javac')
JAVA = java_tool('java')


@unittest.skipUnless(JAVAC.is_file() and JAVA.is_file(), 'Requires host JDK; no device fallback')
class SpectrumBandsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='station-spectrum-math-')
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.classes = Path(cls.temporary.name)
        result = subprocess.run([str(JAVAC), '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                                 '-Xlint:all', '-d', str(cls.classes),
                                 str(ROOT / 'src/local/station/spectrum/SpectrumBands.java'),
                                 str(ROOT / 'tests/SpectrumBandsHostTest.java')],
                                capture_output=True, text=True, timeout=30)
        if result.returncode:
            raise AssertionError(result.stdout + result.stderr)

    def run_case(self, name):
        result = subprocess.run([str(JAVA), '-cp', str(self.classes),
                                 'local.station.spectrum.SpectrumBandsHostTest', name],
                                capture_output=True, text=True, timeout=8)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(result.stdout.strip(), 'OK ' + name)

    def test_silence(self): self.run_case('silence')
    def test_fixed_frequency(self): self.run_case('fixed_frequency')
    def test_signed_complex(self): self.run_case('signed_complex')
    def test_dc_excluded(self): self.run_case('dc_excluded')
    def test_nyquist(self): self.run_case('nyquist')
    def test_frequency_limits(self): self.run_case('frequency_limits')
    def test_band_peak(self): self.run_case('band_peak')
    def test_separate_bands(self): self.run_case('separate_bands')
    def test_malformed_fft(self): self.run_case('malformed_fft')
    def test_sample_bounds(self): self.run_case('sample_bounds')
    def test_smallest_fft(self): self.run_case('smallest_fft')
    def test_no_retained_data(self): self.run_case('no_retained_data')
    def test_finite_range(self): self.run_case('finite_range')


if __name__ == '__main__':
    unittest.main()
