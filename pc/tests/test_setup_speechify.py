"""Local helper regression tests: no server, browser, API, ADB or real keys."""

from contextlib import redirect_stdout
from email.message import Message
import importlib.util
import io
import json
from pathlib import Path
import os
import subprocess
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest import mock
from urllib.parse import urlencode


spec = importlib.util.spec_from_file_location(
    "setup_speechify", Path(__file__).resolve().parents[1] / "setup_speechify.py")
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)

ORIGIN = "http://127.0.0.1:54321"
TOKEN = "synthetic-csrf-token"
KEY = "synthetic_key_not_a_real_credential_123456"


def handler(origin=ORIGIN, referer=ORIGIN + "/"):
    """Construct the real request handler without opening a listening socket."""
    request = object.__new__(setup.SetupHandler)
    request.server = SimpleNamespace(
        origin=ORIGIN, token=TOKEN, nonce="synthetic-style-nonce",
        deadline=time.monotonic() + 30, saved=False,
        target=Path("synthetic-unused-key.json"))
    body = urlencode({"csrf": TOKEN, "api_key": KEY}).encode("ascii")
    request.path = "/save"
    request.headers = Message()
    request.headers["Host"] = "127.0.0.1:54321"
    request.headers["Origin"] = origin
    if referer is not None:
        request.headers["Referer"] = referer
    request.headers["Sec-Fetch-Site"] = "same-origin"
    request.headers["Content-Type"] = "application/x-www-form-urlencoded"
    request.headers["Content-Length"] = str(len(body))
    request.rfile = io.BytesIO(body)
    request.wfile = io.BytesIO()
    request.send_response = mock.Mock()
    request.send_header = mock.Mock()
    request.end_headers = mock.Mock()
    return request


class HeaderPolicyTests(unittest.TestCase):
    def test_response_preserves_same_origin_form_post(self):
        request = handler()
        request._reply(200, "Synthetic form", "<p>Only test data.</p>")
        headers = dict(call.args for call in request.send_header.call_args_list)
        # Fetch's append-Origin algorithm turns navigate POST Origin into null
        # under no-referrer. same-origin preserves it for our form action.
        self.assertEqual(headers["Referrer-Policy"], "same-origin")
        self.assertEqual(headers["Cache-Control"], "no-store, max-age=0")
        setup.validate_headers(request.headers, ORIGIN, post=True)

    def test_real_post_handler_succeeds_with_browser_same_origin_headers(self):
        request = handler()
        output = io.StringIO()
        with mock.patch.object(setup, "save_key") as save, redirect_stdout(output):
            request.do_POST()
        save.assert_called_once_with(KEY, request.server.target)
        request.send_response.assert_called_once_with(200)
        self.assertTrue(request.server.saved)
        self.assertNotIn(KEY, request.wfile.getvalue().decode("utf-8"))
        self.assertEqual(output.getvalue(), "")

    def test_null_and_cross_origin_posts_stay_rejected(self):
        for origin, referer in (("null", None), ("http://example.invalid", None)):
            with self.subTest(origin=origin):
                request = handler(origin, referer)
                with mock.patch.object(setup, "save_key") as save:
                    request.do_POST()
                save.assert_not_called()
                request.send_response.assert_called_once_with(403)
                self.assertFalse(request.server.saved)


class SaveFailureTests(unittest.TestCase):
    def test_save_failure_reports_only_closed_code_in_page_and_stdout(self):
        private_detail = "private-path-and-provider-body-do-not-emit"
        failures = (
            (PermissionError(private_detail + KEY), "SAVE_PERMISSION_DENIED"),
            (subprocess.TimeoutExpired([private_detail, KEY], 1), "SAVE_ACCESS_SETUP_TIMEOUT"),
            (subprocess.CalledProcessError(1, [private_detail, KEY], output=KEY),
             "SAVE_ACCESS_SETUP_FAILED"),
            (OSError(private_detail + KEY), "SAVE_IO_FAILED"),
            (RuntimeError(private_detail + KEY), "SAVE_FAILED"),
        )
        for error, code in failures:
            with self.subTest(code=code):
                request = handler()
                output = io.StringIO()
                with mock.patch.object(setup, "save_key", side_effect=error), redirect_stdout(output):
                    request.do_POST()
                request.send_response.assert_called_once_with(500)
                self.assertFalse(request.server.saved)
                self.assertEqual(json.loads(output.getvalue()), {"event": "save_failed", "error": code})
                page = request.wfile.getvalue().decode("utf-8")
                self.assertIn(code, page)
                for forbidden in (KEY, private_detail):
                    self.assertNotIn(forbidden, page)
                    self.assertNotIn(forbidden, output.getvalue())

    def test_acl_failure_keeps_previous_file_and_never_writes_key(self):
        with tempfile.TemporaryDirectory(prefix="speechify-helper-test-") as temporary:
            target = Path(temporary) / "synthetic-key.json"
            target.write_bytes(b"previous synthetic value")

            def denied(path):
                self.assertEqual(path.read_bytes(), b"")
                raise PermissionError("synthetic ACL failure")

            with mock.patch.object(setup, "_restrict_file", side_effect=denied):
                with self.assertRaises(PermissionError):
                    setup.save_key(KEY, target)
            self.assertEqual(target.read_bytes(), b"previous synthetic value")
            self.assertEqual(list(Path(temporary).iterdir()), [target])

    def test_atomic_replace_failure_cleans_own_temporary_file(self):
        with tempfile.TemporaryDirectory(prefix="speechify-helper-test-") as temporary:
            target = Path(temporary) / "synthetic-key.json"
            target.write_bytes(b"previous synthetic value")
            with mock.patch.object(setup, "_restrict_file"), \
                    mock.patch.object(setup.os, "replace", side_effect=OSError("synthetic IO failure")):
                with self.assertRaises(OSError):
                    setup.save_key(KEY, target)
            self.assertEqual(target.read_bytes(), b"previous synthetic value")
            self.assertEqual(list(Path(temporary).iterdir()), [target])


if __name__ == "__main__":
    unittest.main()
