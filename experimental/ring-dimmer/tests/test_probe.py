"""Run the actual shell helper on a private fake filesystem. Never uses ADB/sysfs."""
from __future__ import annotations
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest

HERE = Path(__file__).resolve().parents[1]
BASH = Path('C:/Program Files/Git/bin/bash.exe')
SOURCE = (HERE / 'ring-dimmer.sh').read_text(encoding='utf-8')


def posix(path: Path) -> str:
    value = path.resolve().as_posix()
    return '/' + value[0].lower() + value[2:] if len(value) > 1 and value[1] == ':' else value


@unittest.skipUnless(BASH.is_file(), 'Requires installed Git Bash; no device fallback')
class ShellProbeTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='station-ring-offdevice-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bin = self.root / 'bin'; self.bin.mkdir()
        self.dev = self.root / 'dev'; self.dev.mkdir()
        self.lock = self.dev / '.station-ring-dimmer-probe'
        self.light = self.root / 'brightness'; self.light.write_text('200\n')
        self.maximum = self.root / 'max_brightness'; self.maximum.write_text('255\n')
        self.name = self.root / 'name'; self.name.write_text('YNDX LED ring\n')
        self.model = self.root / 'model'; self.model.write_text('yandexstation_2\n')
        self.device = self.root / 'device'; self.device.write_text('yandexstation_2\n')
        self.firmware = self.root / 'firmware'; self.firmware.write_text('PPR1.180610.011.20221212\n')
        self.uid = self.root / 'uid'; self.uid.write_text('0\n')
        self.backlight = self.root / 'backlight'
        self.expected = self.root / 'canonical'
        fake = {
            'id': f'#!/bin/sh\ncat "{posix(self.uid)}"\n',
            'getprop': '#!/bin/sh\ncase "$1" in\n'
                       f'ro.product.model) cat "{posix(self.model)}";;\n'
                       f'ro.product.device) cat "{posix(self.device)}";;\n'
                       f'ro.build.display.id) cat "{posix(self.firmware)}";;\nesac\n',
            'readlink': f'#!/bin/sh\nprintf "%s\\n" "{posix(self.expected)}"\n',
            'stat': '#!/bin/sh\ncase "$3" in\n'
                    f'"{posix(self.dev)}") printf "0:755\\n";;\n'
                    f'"{posix(self.lock)}") printf "0:700\\n";;\n'
                    '*) exit 1;;\nesac\n',
        }
        for name, value in fake.items():
            (self.bin / name).write_text(value, encoding='utf-8', newline='\n')
        changes = {
            'PATH=/system/bin:/system/xbin': f'PATH="{posix(self.bin)}:/usr/bin:/bin"',
            'BRIGHTNESS=/sys/class/backlight/fb5/brightness': f'BRIGHTNESS="{posix(self.light)}"',
            'MAX_BRIGHTNESS=/sys/class/backlight/fb5/max_brightness': f'MAX_BRIGHTNESS="{posix(self.maximum)}"',
            'FB_NAME=/sys/class/graphics/fb5/name': f'FB_NAME="{posix(self.name)}"',
            'BACKLIGHT=/sys/class/backlight/fb5': f'BACKLIGHT="{posix(self.backlight)}"',
            'EXPECTED_BACKLIGHT=/sys/devices/virtual/graphics/fb5/fb5': f'EXPECTED_BACKLIGHT="{posix(self.expected)}"',
            'LOCK_PARENT=/dev': f'LOCK_PARENT="{posix(self.dev)}"',
            'LOCK=/dev/.station-ring-dimmer-probe': f'LOCK="{posix(self.lock)}"',
        }
        script = SOURCE
        for old, new in changes.items():
            self.assertEqual(script.count(old), 1, old)
            script = script.replace(old, new)
        self.script = self.root / 'ring-dimmer.sh'
        self.script.write_text(script, encoding='utf-8', newline='\n')
        subprocess.run([str(BASH), '-c', 'chmod +x "$1"/*', '_', posix(self.bin)], check=True,
                       capture_output=True, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        self.children = []
        self.addCleanup(self.stop_children)

    def stop_children(self):
        for process in self.children:
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=4)
                except subprocess.TimeoutExpired: process.kill()
        # A still-running watchdog must finish before removing its fake filesystem.
        time.sleep(.12)

    def run_helper(self, *args, timeout=5):
        return subprocess.run([str(BASH), posix(self.script), *args], capture_output=True,
                              text=True, timeout=timeout, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))

    def spawn(self):
        process = subprocess.Popen([str(BASH), posix(self.script), 'probe'], stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, text=True,
                                   creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        self.children.append(process)
        return process

    def wait_value(self, value, timeout=4):
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            if self.light.read_text().strip() == str(value): return
            time.sleep(.01)
        self.fail(f'Brightness did not become {value}')

    def wait_unlock(self, timeout=4):
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            if not self.lock.exists(): return
            time.sleep(.01)
        self.fail('Watchdog did not release its lock')

    def signal_owner(self, signal):
        owner = (self.lock / 'owner').read_text().split(':')[0]
        self.assertTrue(owner.isdigit())
        subprocess.run([str(BASH), '-c', 'kill -' + signal + ' "$1"', '_', owner], check=True,
                       capture_output=True, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))

    def test_default_status_has_no_writes_or_lock(self):
        before = self.light.read_bytes()
        result = self.run_helper()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('HARDWARE_MATCH_READABLE', result.stdout)
        self.assertEqual(self.light.read_bytes(), before)
        self.assertFalse(self.lock.exists())

    def test_probe_dim_then_restores_exact_value(self):
        process = self.spawn(); self.wait_value(150)
        output, errors = process.communicate(timeout=5)
        self.assertEqual(process.returncode, 0, errors)
        self.assertEqual(output.splitlines(), ['DIMMED', 'RESTORED'])
        self.assertEqual(self.light.read_text().strip(), '200')
        self.wait_unlock()

    def test_parent_sigkill_restored_by_watchdog(self):
        process = self.spawn(); self.wait_value(150)
        self.signal_owner('KILL')
        self.wait_value(200)
        process.communicate(timeout=5)
        self.wait_unlock()
        self.assertNotEqual(process.returncode, 0)

    def test_parent_sigterm_restored_by_trap(self):
        process = self.spawn(); self.wait_value(150)
        self.signal_owner('TERM')
        output, errors = process.communicate(timeout=5)
        self.assertEqual(self.light.read_text().strip(), '200', errors)
        self.assertIn('RESTORED', output)
        self.assertEqual(process.returncode, 143)
        self.wait_unlock()

    def test_stalled_parent_cannot_dim_after_watchdog_timeout(self):
        # Git Bash accepts STOP but does not reliably suspend the process. Instead
        # stall this test copy in a real foreground sleep immediately before the
        # write. The parent cannot execute the following write while waiting.
        marker = self.root / 'stalled'
        source = self.script.read_text()
        needle = 'printf \'%s\\n\' "$TARGET" > "$BRIGHTNESS" 2>/dev/null'
        self.assertEqual(source.count(needle), 1)
        source = source.replace(needle, f': > "{posix(marker)}"\nsleep 8 >/dev/null 2>&1\n' + needle)
        self.script.write_text(source, encoding='utf-8', newline='\n')
        before = self.light.read_bytes()
        process = self.spawn()
        until = time.monotonic() + 3
        while not marker.exists() and time.monotonic() < until: time.sleep(.01)
        self.assertTrue(marker.exists())
        process.communicate(timeout=5)
        self.assertNotEqual(process.returncode, 0)
        self.assertEqual(self.light.read_text().strip(), before.decode().strip())
        self.wait_unlock()

    def test_stalled_dimmed_parent_is_killed_and_restored(self):
        source = self.script.read_text()
        self.assertEqual(source.count('sleep 0.75'), 1)
        self.script.write_text(source.replace('sleep 0.75', 'sleep 8 >/dev/null 2>&1'), encoding='utf-8', newline='\n')
        process = self.spawn(); self.wait_value(150)
        self.wait_value(200)
        process.communicate(timeout=5)
        self.assertNotEqual(process.returncode, 0)
        self.wait_unlock()

    def test_concurrent_probe_cannot_take_owner(self):
        process = self.spawn(); self.wait_value(150)
        result = self.run_helper('probe')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('BUSY_OR_STALE_LOCK', result.stdout)
        self.assertEqual(self.light.read_text().strip(), '150')
        process.communicate(timeout=5); self.wait_value(200); self.wait_unlock()

    def test_external_brightness_change_not_overwritten(self):
        process = self.spawn(); self.wait_value(150)
        self.light.write_text('177\n')
        output, errors = process.communicate(timeout=5)
        self.assertNotEqual(process.returncode, 0)
        self.assertIn('OWNER_CHANGED', output)
        self.assertEqual(self.light.read_text().strip(), '177', errors)
        self.wait_unlock()

    def test_wrong_hardware_range_and_nonroot_do_not_write(self):
        for path, invalid, expected in [
            (self.uid, '2000', 'ROOT_REQUIRED'),
            (self.model, 'station-midi', 'WRONG_MODEL'),
            (self.device, 'station-midi', 'WRONG_MODEL'),
            (self.firmware, '20261116', 'WRONG_FIRMWARE'),
            (self.name, 'YNDX LED screen', 'WRONG_FRAMEBUFFER'),
            (self.maximum, '100', 'WRONG_RANGE'),
        ]:
            with self.subTest(expected=expected):
                original = path.read_bytes(); path.write_text(invalid+'\n')
                result = self.run_helper('probe')
                path.write_bytes(original)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(expected, result.stdout)
                self.assertEqual(self.light.read_text().strip(), '200')
                self.assertFalse(self.lock.exists())

    def test_invalid_brightness_and_already_dim_never_write(self):
        for value in ['garbage', '-1', '256', '080', '0', '32', '63']:
            with self.subTest(value=value):
                self.light.write_text(value+'\n'); before=self.light.read_bytes()
                result = self.run_helper('probe')
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(self.light.read_bytes(), before)
                self.assertFalse(self.lock.exists())

    def test_unknown_arguments_fail_before_preflight(self):
        for args in [('probe', '255'), ('/tmp/anything',), ('',), ('--help',)]:
            with self.subTest(args=args):
                result = self.run_helper(*args)
                self.assertEqual(result.returncode, 64)
                self.assertEqual(result.stdout.strip(), 'INVALID_ARGUMENTS')
                self.assertFalse(self.lock.exists())
                self.assertEqual(self.light.read_text().strip(), '200')

    def test_existing_lock_or_symlink_is_never_removed(self):
        self.lock.write_text('unowned marker')
        result = self.run_helper('probe')
        self.assertIn('BUSY_OR_STALE_LOCK', result.stdout)
        self.assertEqual(self.lock.read_text(), 'unowned marker')
        self.assertEqual(self.light.read_text().strip(), '200')


if __name__ == '__main__':
    unittest.main()
