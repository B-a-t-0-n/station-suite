"""Real bounded child-process checks; no Station, network or scheduler access."""
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import keepalive


class KeepaliveTests(unittest.TestCase):
    def test_completed_check_preserves_structured_status(self):
        result, code = keepalive.check([sys.executable, '-c',
            'print(\'{"ok":true,"running":true,"started":false}\')'], timeout=3)
        self.assertEqual(code, 0)
        self.assertTrue(result['running'])
        self.assertFalse(result['started'])

    def test_hung_check_is_stopped_with_bounded_private_error(self):
        started = time.monotonic()
        result, code = keepalive.check([sys.executable, '-c',
            'import time; print("private diagnostic",flush=True); time.sleep(30)'], timeout=.2)
        self.assertLess(time.monotonic() - started, 4)
        self.assertEqual((result, code), ({'ok': False, 'code': 'KEEPALIVE_CHECK_TIMEOUT'}, 1))

    def test_detached_owner_survives_expired_check(self):
        with tempfile.TemporaryDirectory() as temporary:
            marker = Path(temporary) / 'owner-alive'
            stop = Path(temporary) / 'stop-owner'
            finished = Path(temporary) / 'owner-finished'
            owner_code = ('import pathlib,sys,time; time.sleep(.5); '
                'pathlib.Path(sys.argv[1]).write_text("alive"); '
                '\nfor n in range(100):\n'
                ' if pathlib.Path(sys.argv[2]).exists(): break\n'
                ' time.sleep(.05)\n'
                'pathlib.Path(sys.argv[3]).write_text("finished")')
            parent_code = ('import subprocess,sys,time; '
                'subprocess.Popen([sys.executable,"-c",sys.argv[1],*sys.argv[2:]],'
                'stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL); '
                'time.sleep(30)')
            try:
                result, code = keepalive.check([sys.executable, '-c', parent_code,
                    owner_code, str(marker), str(stop), str(finished)], timeout=.3)
                self.assertEqual(result['code'], 'KEEPALIVE_CHECK_TIMEOUT')
                until = time.monotonic() + 3
                while not marker.exists() and time.monotonic() < until:
                    time.sleep(.05)
                self.assertTrue(marker.exists())
                self.assertFalse(finished.exists())
            finally:
                stop.touch()
                until = time.monotonic() + 3
                while not finished.exists() and time.monotonic() < until:
                    time.sleep(.05)
                self.assertTrue(finished.exists())

    def test_invalid_result_does_not_echo_output_or_stderr(self):
        result, code = keepalive.check([sys.executable, '-c',
            'import sys; print("private output"); print("private error",file=sys.stderr)'], timeout=3)
        self.assertEqual((result, code), ({'ok': False, 'code': 'KEEPALIVE_CHECK_RESULT_INVALID'}, 1))


if __name__ == '__main__':
    unittest.main()
