"""Actual HIDL shell state machine with a fake app_process; no Android calls."""
from pathlib import Path
import subprocess
import time
import unittest
import test_probe as fixtures

BASH, HERE, posix = fixtures.BASH, fixtures.HERE, fixtures.posix


@unittest.skipUnless(BASH.is_file(), 'Requires installed Git Bash')
class HidlShellTests(unittest.TestCase):
    stop_children = fixtures.ShellProbeTests.stop_children
    run_helper = fixtures.ShellProbeTests.run_helper
    spawn = fixtures.ShellProbeTests.spawn
    wait_value = fixtures.ShellProbeTests.wait_value
    wait_unlock = fixtures.ShellProbeTests.wait_unlock
    signal_owner = fixtures.ShellProbeTests.signal_owner

    def setUp(self):
        fixtures.ShellProbeTests.setUp(self)
        self.uid.write_text('10005\n')
        self.dex = self.dev / 'classes.dex'; self.dex.write_text('fixture-not-a-real-dex')
        self.calls = self.root / 'backend-calls'
        self.hang = self.root / 'hang-dim'
        self.entered = self.root / 'entered-dim'
        self.clock_reply = self.root / 'clock-reply'
        self.clock_hang = self.root / 'clock-hang'
        self.clock_failure_on_dim = self.root / 'clock-failure-on-dim'
        stat = '#!/bin/sh\ncase "$3" in\n' + \
            f'"{posix(self.dev)}"|"{posix(self.lock)}") printf "10005:700\\n";;\n' + \
            f'"{posix(self.dex)}") printf "10005:600\\n";;\n*) exit 1;;\nesac\n'
        (self.bin / 'stat').write_text(stat, encoding='utf-8', newline='\n')
        app = f'''#!/bin/sh
[ "$1:$2" = /system/bin:RingBrightnessBackend ] || exit 1
shift 2
if [ "$1" = ticks ]; then
  if [ -f "{posix(self.clock_hang)}" ]; then sleep 8 >/dev/null 2>&1; fi
  if [ -f "{posix(self.clock_reply)}" ]; then cat "{posix(self.clock_reply)}"; exit 0; fi
  if [ -f "{posix(self.clock_failure_on_dim)}" ] && [ "$(cat "{posix(self.light)}")" = 150 ]; then
    printf 'CLOCK_FAULT\\n'; exit 1
  fi
  IFS=' ' read -r uptime rest < /proc/uptime || exit 1
  seconds=${{uptime%%.*}}; fraction=${{uptime#*.}}
  printf 'TICKS %s\\n' "$((seconds * 100 + 1$fraction - 100))"
  exit 0
fi
[ "$(cat "{posix(self.name)}")" = 'YNDX LED ring' ] || exit 1
[ "$(cat "{posix(self.maximum)}")" = 255 ] || exit 1
printf '%s\\n' "$1" >> "{posix(self.calls)}"
current=$(cat "{posix(self.light)}")
case "$1" in
current) printf 'CURRENT %s\\n' "$current";;
dim)
  [ "$current" = "$2" ] || {{ printf 'OWNER_CHANGED\\n'; exit 1; }}
  if [ -f "{posix(self.hang)}" ]; then
    : > "{posix(self.entered)}"
    sleep 8 >/dev/null 2>&1
  fi
  target=$(($2 * 3 / 4))
  printf '%s\\n' "$target" > "{posix(self.light)}"
  printf 'DIMMED %s %s\\n' "$2" "$target";;
restore)
  target=$(($2 * 3 / 4))
  if [ "$current" = "$target" ]; then printf '%s\\n' "$2" > "{posix(self.light)}";
  elif [ "$current" != "$2" ]; then printf 'OWNER_CHANGED\\n'; exit 1; fi
  printf 'RESTORED\\n';;
*) exit 1;;
esac
'''
        (self.bin / 'app_process').write_text(app, encoding='utf-8', newline='\n')
        subprocess.run([str(BASH), '-c', 'chmod +x "$1"/*', '_', posix(self.bin)], check=True, capture_output=True)
        source = (HERE / 'hidl-ring-dimmer.sh').read_text(encoding='utf-8')
        changes = {
            'PATH=/system/bin:/system/xbin': f'PATH="{posix(self.bin)}:/usr/bin:/bin"',
            'APP_DIR=/data/user/0/ru.yandex.quasar.app/files/station-ring-dimmer': f'APP_DIR="{posix(self.dev)}"',
            'DEX=/data/user/0/ru.yandex.quasar.app/files/station-ring-dimmer/classes.dex': f'DEX="{posix(self.dex)}"',
            'CLASSPATH=/vendor/framework/droidlogic.jar:/data/user/0/ru.yandex.quasar.app/files/station-ring-dimmer/classes.dex': 'CLASSPATH=offdevice-fixture-only',
            'LOCK=/data/user/0/ru.yandex.quasar.app/files/station-ring-dimmer/probe.lock': f'LOCK="{posix(self.lock)}"',
        }
        for old, new in changes.items():
            self.assertEqual(source.count(old), 1); source = source.replace(old,new)
        self.script = self.root / 'hidl-ring-dimmer.sh'
        self.script.write_text(source, encoding='utf-8', newline='\n')

    def test_status_is_read_only(self):
        result=self.run_helper()
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(result.stdout.strip(),'CURRENT 200')
        self.assertFalse(self.lock.exists())
        self.assertEqual(self.light.read_text().strip(),'200')

    def test_normal_probe_and_restore(self):
        process=self.spawn();self.wait_value(150)
        out,err=process.communicate(timeout=10)
        self.assertEqual(process.returncode,0,(out,err))
        self.assertEqual(out.splitlines(),['DIMMED','RESTORED'])
        self.assertEqual(self.light.read_text().strip(),'200')
        self.wait_unlock()

    def test_sigkill_parent_restores(self):
        process=self.spawn();self.wait_value(150);self.signal_owner('KILL')
        self.wait_value(200);process.communicate(timeout=8);self.wait_unlock()
        self.assertNotEqual(process.returncode,0)

    def test_concurrent_request_does_not_take_ownership(self):
        process=self.spawn();self.wait_value(150)
        result=self.run_helper('probe')
        self.assertIn('BUSY_OR_STALE_LOCK',result.stdout)
        process.communicate(timeout=8);self.wait_value(200);self.wait_unlock()

    def test_other_writer_is_preserved(self):
        process=self.spawn();self.wait_value(150);self.light.write_text('177\n')
        out,err=process.communicate(timeout=10)
        self.assertNotEqual(process.returncode,0,(out,err))
        self.assertIn('OWNER_CHANGED',out)
        self.assertEqual(self.light.read_text().strip(),'177')
        self.wait_unlock()

    def test_hung_inflight_client_is_killed_before_restore(self):
        self.hang.touch();process=self.spawn()
        until=time.monotonic()+5
        while not self.entered.exists() and time.monotonic()<until:time.sleep(.01)
        self.assertTrue(self.entered.exists())
        out,err=process.communicate(timeout=10)
        self.assertNotEqual(process.returncode,0,(out,err))
        self.assertEqual(self.light.read_text().strip(),'200')
        self.wait_unlock()
        # A later wake of the fake command's sleep cannot resume the killed shell.
        self.assertEqual(self.calls.read_text().splitlines().count('restore'),1)

    def test_orphan_without_registration_does_not_use_restore_gate(self):
        marker=self.root/'unregistered'
        source=self.script.read_text()
        needle='    BACKEND_PID=$!\n'
        self.assertEqual(source.count(needle),1)
        source=source.replace(needle,needle+f'    if [ "$1" = dim ]; then : > "{posix(marker)}"; sleep 8 >/dev/null 2>&1; fi\n')
        self.script.write_text(source,encoding='utf-8',newline='\n')
        process=self.spawn();until=time.monotonic()+5
        while not marker.exists() and time.monotonic()<until:time.sleep(.01)
        self.assertTrue(marker.exists())
        out,err=process.communicate(timeout=10)
        self.assertNotEqual(process.returncode,0,(out,err))
        self.assertEqual(self.light.read_text().strip(),'200')
        self.wait_unlock()
        self.assertNotIn('dim',self.calls.read_text().splitlines())

    def test_wrong_uid_and_backend_hardware_do_not_write(self):
        self.uid.write_text('2000\n');result=self.run_helper('probe')
        self.assertIn('QUASAR_UID_REQUIRED',result.stdout);self.assertFalse(self.lock.exists())
        self.uid.write_text('10005\n')
        for field in [self.model, self.device]:
            field.write_text('station-midi\n');result=self.run_helper('probe')
            self.assertIn('WRONG_MODEL',result.stdout);self.assertFalse(self.lock.exists())
            field.write_text('yandexstation_2\n')
        self.name.write_text('YNDX LED screen\n')
        result=self.run_helper('probe')
        self.assertIn('BACKEND_PREFLIGHT_FAILED',result.stdout)
        self.assertEqual(self.light.read_text().strip(),'200');self.wait_unlock()

    def test_invalid_arguments_do_not_call_backend(self):
        result=self.run_helper('probe','200')
        self.assertEqual(result.returncode,64)
        self.assertFalse(self.calls.exists());self.assertFalse(self.lock.exists())

    def test_malformed_clock_fails_before_any_hardware_call(self):
        for reply in ['TICKS -1', 'TICKS 001', 'TICKS 1234567890123', 'TICKS 1\n2', 'CURRENT 200']:
            with self.subTest(reply=reply):
                self.clock_reply.write_text(reply+'\n')
                result=self.run_helper('probe')
                self.assertNotEqual(result.returncode,0)
                self.assertIn('INVALID_CLOCK_REPLY',result.stderr)
                self.assertIn('MONOTONIC_CLOCK_UNAVAILABLE',result.stdout)
                self.assertFalse(self.calls.exists());self.assertFalse(self.lock.exists())
                self.assertEqual(self.light.read_text().strip(),'200')

    def test_hung_clock_is_bounded_before_any_hardware_call(self):
        self.clock_hang.touch()
        result=self.run_helper('probe',timeout=5)
        self.assertNotEqual(result.returncode,0)
        self.assertIn('CLOCK_BACKEND_FAILED',result.stderr)
        self.assertFalse(self.calls.exists());self.assertFalse(self.lock.exists())
        self.assertEqual(self.light.read_text().strip(),'200')

    def test_clock_failure_after_dim_does_not_block_restore(self):
        self.clock_failure_on_dim.touch()
        # Keep the owner alive long enough for the watchdog's next clock read.
        source=self.script.read_text()
        self.assertEqual(source.count('sleep 0.75'),1)
        self.script.write_text(source.replace('sleep 0.75','sleep 8 >/dev/null 2>&1'),encoding='utf-8',newline='\n')
        process=self.spawn();self.wait_value(150)
        out,err=process.communicate(timeout=10)
        self.assertNotEqual(process.returncode,0,(out,err))
        self.assertIn('CLOCK_BACKEND_FAILED',err)
        self.assertEqual(self.light.read_text().strip(),'200');self.wait_unlock()
        self.assertEqual(self.calls.read_text().splitlines().count('restore'),1)


if __name__=='__main__':unittest.main()
