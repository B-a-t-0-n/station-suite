"""Offline: synthetic files only; integration redirects LOCALAPPDATA to a temp dir."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import native_config_window as window


SYNTHETIC_SECRET = 'synthetic-private-value-never-in-reports'
BASELINE = (json.dumps({'status': 'ok', 'config': {'system_config': {
    'uniProxyUrl': window.PC_URL, 'voiceDialogSettings': {'useProtoAliceApi': False}},
    'private': SYNTHETIC_SECRET}, 'other': [1, True, 'test']}, separators=(',', ':'))
    + '\n').encode('ascii')


class WindowTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.active = self.root / 'active.json'
        self.active.write_bytes(BASELINE)
        self.store = self.root / 'private'
        self.directory_acl = patch.object(window, 'protect_directory').start()
        self.file_acl = patch.object(window, '_restrict_file').start()
        self.addCleanup(patch.stopall)
        self.addCleanup(self.temporary.cleanup)

    def prepare(self, lease=120):
        return window.prepare(self.active, self.store, window.digest(BASELINE), lease)

    def arm(self, folder):
        state, _, _ = window.load(folder)
        now = time.monotonic()
        state.update(phase='armed', outcome='WATCHDOG_READY', watchPid=os.getpid(),
                     heartbeatMonotonic=now, deadlineMonotonic=now + 120)
        window.save_state(folder, state)
        return state

    def assert_code(self, code, function, *args):
        with self.assertRaisesRegex(window.WindowError, '^' + code + '$'):
            function(*args)

    def test_candidate_changes_only_endpoint_and_restores_exact_bytes(self):
        folder = self.prepare()
        self.assertEqual(BASELINE, self.active.read_bytes())
        self.assertEqual(BASELINE, (folder / 'baseline.json').read_bytes())
        self.arm(folder)
        result = window.apply(folder)
        candidate = window.parse(self.active.read_bytes())
        self.assertEqual(window.LOCAL_URL, candidate['config']['system_config']['uniProxyUrl'])
        candidate['config']['system_config']['uniProxyUrl'] = window.PC_URL
        self.assertEqual(window.parse(BASELINE), candidate)
        self.assertEqual('applied', result['phase'])
        restored = window.restore(folder)
        self.assertEqual('restored', restored['phase'])
        self.assertEqual(BASELINE, self.active.read_bytes())
        self.assertFalse((self.store / 'owner.json').exists())

    def test_prepare_refuses_wrong_hash_without_backup_or_write(self):
        self.assert_code('BASELINE_HASH_MISMATCH', window.prepare,
                         self.active, self.store, '0' * 64)
        self.assertEqual(BASELINE, self.active.read_bytes())
        self.assertFalse(self.store.exists())

    def test_wrong_baseline_url_is_refused(self):
        other = BASELINE.replace(window.PC_URL.encode(), b'https://example.invalid/')
        self.active.write_bytes(other)
        self.assert_code('BASELINE_URL_MISMATCH', window.prepare,
                         self.active, self.store, window.digest(other))
        self.assertEqual(other, self.active.read_bytes())

    def test_duplicate_keys_nonfinite_and_nonobject_json_are_refused(self):
        for value in (b'{"x":1,"x":2}', b'{"x":NaN}', b'[]', b'\xff'):
            with self.subTest(value=value):
                self.assert_code('INVALID_JSON', window.parse, value)

    def test_oversized_baseline_is_refused(self):
        self.active.write_bytes(b' ' * (window.MAX_BYTES + 1))
        self.assert_code('FILE_TOO_LARGE', window.bounded_read, self.active)

    def test_lease_cannot_exceed_300_or_be_zero(self):
        for lease in (0, 301, True):
            self.assert_code('INVALID_LEASE', window.prepare,
                             self.active, self.store, window.digest(BASELINE), lease)

    def test_second_window_is_refused(self):
        first = self.prepare()
        self.assert_code('WINDOW_ALREADY_OWNED', self.prepare)
        window.restore(first)
        second = self.prepare()
        self.assertNotEqual(first, second)

    def test_unarmed_window_never_changes_config(self):
        folder = self.prepare()
        self.assert_code('WATCHDOG_NOT_READY', window.apply, folder)
        self.assertEqual(BASELINE, self.active.read_bytes())

    def test_dead_stale_expired_or_future_watchdog_refuses_apply(self):
        folder = self.prepare()
        for field, value in (('heartbeatMonotonic', time.monotonic() - 10),
                             ('deadlineMonotonic', time.monotonic() - 1),
                             ('heartbeatMonotonic', time.monotonic() + 100)):
            state = self.arm(folder)
            state[field] = value
            window.save_state(folder, state)
            self.assert_code('WATCHDOG_NOT_READY', window.apply, folder)
        self.arm(folder)
        with patch.object(window, 'process_alive', return_value=False):
            self.assert_code('WATCHDOG_NOT_READY', window.apply, folder)
        self.assertEqual(BASELINE, self.active.read_bytes())

    def test_apply_refuses_active_drift(self):
        folder = self.prepare()
        self.arm(folder)
        other = b'{"unrelated":"new synthetic file"}'
        self.active.write_bytes(other)
        self.assert_code('ACTIVE_FILE_DRIFT', window.apply, folder)
        self.assertEqual(other, self.active.read_bytes())

    def test_restore_does_not_overwrite_unknown_hash(self):
        folder = self.prepare()
        self.arm(folder)
        window.apply(folder)
        other = b'{"unrelated":"new synthetic file"}'
        self.active.write_bytes(other)
        result = window.restore(folder)
        self.assertEqual('drift', result['phase'])
        self.assertEqual(other, self.active.read_bytes())

    def test_restore_rechecks_drift_after_acl_before_replace(self):
        folder = self.prepare()
        self.arm(folder)
        window.apply(folder)
        other = b'{"unrelated":"changed during temporary file ACL"}'
        self.file_acl.side_effect = lambda _: self.active.write_bytes(other)
        result = window.restore(folder)
        self.assertEqual('drift', result['phase'])
        self.assertEqual(other, self.active.read_bytes())

    def test_deadline_is_rechecked_after_acl_before_apply(self):
        folder = self.prepare()
        state = self.arm(folder)
        now = [100.0]
        state.update(heartbeatMonotonic=100.0, deadlineMonotonic=101.0)
        window.save_state(folder, state)
        self.file_acl.side_effect = lambda _: now.__setitem__(0, 102.0)
        with patch.object(window.time, 'monotonic', side_effect=lambda: now[0]):
            self.assert_code('WATCHDOG_NOT_READY', window.apply, folder)
        self.assertEqual(BASELINE, self.active.read_bytes())

    def test_atomic_replace_failure_preserves_active_and_cleans_temp(self):
        with patch.object(window.os, 'replace', side_effect=OSError('synthetic failure')):
            with self.assertRaises(OSError):
                window.atomic_write(self.active, b'new synthetic data', protect=True)
        self.assertEqual(BASELINE, self.active.read_bytes())
        self.assertEqual([], list(self.root.glob('.native-window-*.tmp')))

    def test_transient_windows_sharing_error_retries_with_guard(self):
        actual = window.os.replace
        calls = []
        def replace(source, destination):
            calls.append('replace')
            if calls.count('replace') == 1:
                raise PermissionError('synthetic Windows sharing conflict')
            return actual(source, destination)
        with patch.object(window.os, 'replace', side_effect=replace):
            window.atomic_write(self.active, b'synthetic replacement',
                                before_replace=lambda: calls.append('guard'))
        self.assertEqual(['guard', 'replace', 'guard', 'replace'], calls)
        self.assertEqual(b'synthetic replacement', self.active.read_bytes())

    def test_candidate_is_restored_even_if_apply_died_before_state_commit(self):
        folder = self.prepare()
        self.arm(folder)
        _, _, candidate = window.load(folder)
        self.active.write_bytes(candidate)
        self.assertEqual('armed', window.load(folder)[0]['phase'])
        self.assertEqual('restored', window.restore(folder)['phase'])
        self.assertEqual(BASELINE, self.active.read_bytes())

    def test_failed_acl_happens_before_secret_bytes_and_preserves_active(self):
        def fail_acl(path):
            self.assertEqual(b'', Path(path).read_bytes())
            raise OSError('synthetic ACL failure')
        self.file_acl.side_effect = fail_acl
        with self.assertRaises(OSError):
            window.atomic_write(self.active, BASELINE, protect=True)
        self.assertEqual(BASELINE, self.active.read_bytes())
        self.assertEqual([], list(self.root.glob('.native-window-*.tmp')))

    def test_tampered_backup_and_state_are_refused(self):
        folder = self.prepare()
        candidate = folder / 'candidate.json'
        original = candidate.read_bytes()
        candidate.write_bytes(b'{}')
        self.assert_code('BACKUP_HASH_MISMATCH', window.load, folder)
        candidate.write_bytes(original)
        state, _, _ = window.load(folder)
        state['outcome'] = SYNTHETIC_SECRET
        window.save_state(folder, state)
        self.assert_code('INVALID_STATE', window.safe_status, folder)

    def test_reports_contain_no_private_values_or_sync_content(self):
        folder = self.prepare()
        report = json.dumps(window.safe_status(folder))
        for private in (SYNTHETIC_SECRET, window.PC_URL, window.LOCAL_URL, 'uniProxyUrl'):
            self.assertNotIn(private, report)
        self.assertFalse(window.safe_status(folder)['deliveryVerified'])
        self.assertFalse(window.safe_status(folder)['nativeReconnectVerified'])

    def test_changed_helper_blocks_apply_but_not_restore(self):
        folder = self.prepare()
        state = self.arm(folder)
        state['helperSha256'] = '0' * 64
        window.save_state(folder, state)
        self.assert_code('HELPER_CHANGED', window.apply, folder)
        self.assertEqual('restored', window.restore(folder)['phase'])

    def test_process_liveness_does_not_signal_current_windows_process(self):
        if os.name == 'nt':
            with patch.object(window.os, 'kill', side_effect=AssertionError('unsafe Windows kill')):
                self.assertTrue(window.process_alive(os.getpid()))
        else:
            self.assertTrue(window.process_alive(os.getpid()))


class IndependentProcessTest(unittest.TestCase):
    def test_watchdog_restores_after_controller_exits(self):
        # Real child process + real ACLs, but every path is synthetic and local.
        # No device, socket, production sync path or production private store.
        with tempfile.TemporaryDirectory() as directory:
            local = Path(directory)
            active = local / 'pc/native-baseline.json'
            active.parent.mkdir(parents=True)
            active.write_bytes(BASELINE)
            environment = dict(os.environ, LOCALAPPDATA=str(local), STATION_PRIVATE_DIR=str(local))
            script = str(Path(window.__file__).resolve())
            def cli(*arguments):
                result = subprocess.run([sys.executable, script, *arguments], env=environment,
                                        capture_output=True, timeout=12, check=False)
                self.assertNotIn(SYNTHETIC_SECRET.encode(), result.stdout + result.stderr)
                self.assertEqual(0, result.returncode, result.stdout.decode())
                return json.loads(result.stdout)
            started = cli('start', '--expected-sha256', window.digest(BASELINE),
                          '--lease-seconds', '4')
            folder = Path(started['windowDirectory'])
            try:
                self.assertEqual('armed', started['phase'])
                state, _, _ = window.load(folder)
                self.assertTrue(window.process_alive(state['watchPid']))
                # The CLI/controller has exited; only the independent child remains.
                self.assertEqual('applied', cli('apply', '--window', str(folder))['phase'])
                self.assertNotEqual(BASELINE, active.read_bytes())
                deadline = time.monotonic() + 8
                while time.monotonic() < deadline:
                    state, _, _ = window.load(folder)
                    if state['phase'] == 'restored':
                        break
                    time.sleep(0.05)
                self.assertEqual('restored', state['phase'])
                self.assertEqual(BASELINE, active.read_bytes())
                self.assertFalse((folder.parent / 'owner.json').exists())
                # Let the child leave finally/close handles before temp cleanup.
                end = time.monotonic() + 3
                while window.process_alive(state['watchPid']) and time.monotonic() < end:
                    time.sleep(0.05)
                self.assertFalse(window.process_alive(state['watchPid']))
            finally:
                cli('restore', '--window', str(folder))


if __name__ == '__main__':
    unittest.main()
