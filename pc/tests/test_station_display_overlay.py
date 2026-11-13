"""Offline LED overlay and responder checks; no listeners or device access."""
import copy
import importlib.util
import io
import json
from pathlib import Path
import socket
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest import mock


def import_sibling(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).resolve().parents[1] / filename)
    result = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = result
    spec.loader.exec_module(result)
    return result


with mock.patch.object(socket, "socket", side_effect=AssertionError("offline tests forbid sockets")):
    overlay = import_sibling("display_overlay_test", "StationDisplayOverlay.py")
    server = import_sibling("display_server_test", "LocalConfigServer.py")


def sample_payload():
    return {"status": "ok", "config": {
        "account_config": {"spotter": "alisa"},
        "system_config": {"uniProxyUrl": "wss://synthetic.invalid/voice", "updates": {"disabled": True}},
        "device_config": {"name": "synthetic", "led": {
            "time_visualization": {"size": "middle", "format": "24"},
            "brightness": {"auto": True, "value": 0.5, "syntheticSibling": 1},
            "music_visualization": {"theme": "untouched"}}}},
        "glagol": {"security": {"server_private_key": "SYNTHETIC_SECRET_DO_NOT_LOG"}}}


def document(settings=None, expiry=0):
    return {"version": 1, "expiresAtMs": expiry,
            "settings": {"clockSize": "large"} if settings is None else settings}


class OverlayTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="station-display-offline-")
        self.path = Path(self.directory.name) / "DO_NOT_LOG_PATH.json"
        self.addCleanup(self.directory.cleanup)

    def write(self, value):
        self.path.write_text(json.dumps(value, allow_nan=False), encoding="utf-8")

    def test_each_clock_size_only_changes_requested_leaf(self):
        for size in sorted(overlay.CLOCK_SIZES):
            source = sample_payload()
            before = copy.deepcopy(source)
            self.write(document({"clockSize": size}))
            result, status = overlay.apply_overlay(source, self.path, now_ms=10_000)
            expected = copy.deepcopy(before)
            expected["config"]["device_config"]["led"]["time_visualization"]["size"] = size
            self.assertEqual(status, "applied")
            self.assertEqual(result, expected)
            self.assertEqual(source, before)
            self.assertIsNot(result, source)

    def test_brightness_merge_and_combined_settings_preserve_siblings(self):
        source = sample_payload()
        for settings in ({"brightness": {"auto": False, "value": 0}},
                         {"brightness": {"auto": True, "value": 1}, "clockSize": "small"}):
            self.write(document(settings))
            result, status = overlay.apply_overlay(source, self.path)
            led = result["config"]["device_config"]["led"]
            self.assertEqual(status, "applied")
            self.assertEqual(led["brightness"]["syntheticSibling"], 1)
            for key, value in settings["brightness"].items():
                self.assertEqual(led["brightness"][key], value)
            self.assertEqual(result["config"]["system_config"], source["config"]["system_config"])
            self.assertEqual(result["glagol"], source["glagol"])

    def test_absent_led_objects_are_created_without_touching_other_fields(self):
        for source in ({"status": "ok", "config": {}},
                       {"status": "ok", "config": {"device_config": {"led": {}}}}):
            before = copy.deepcopy(source)
            self.write(document())
            result, status = overlay.apply_overlay(source, self.path)
            self.assertEqual(status, "applied")
            self.assertEqual(result["config"]["device_config"]["led"]["time_visualization"], {"size": "large"})
            self.assertEqual(source, before)

    def test_music_only_fixed_catalog_and_selector_preserve_all_siblings(self):
        for style in sorted(overlay.MUSIC_STYLES):
            source = sample_payload()
            source["config"]["system_config"]["spectrogramAnimation"] = {
                "enabled": False, "other": "preserve", "presets": {"existing": {"type": "wave"}}}
            source["config"]["device_config"]["led"]["music_equalizer_visualization"] = {"other": 3}
            before = copy.deepcopy(source)
            self.write(document({"musicStyle": style}))
            result, status = overlay.apply_overlay(source, self.path)
            self.assertEqual(status, "applied")
            self.assertEqual(source, before)
            expected = copy.deepcopy(before)
            expected["config"]["device_config"]["led"]["music_equalizer_visualization"].update({"auto": style == "random", "style": style})
            expected["config"]["system_config"]["spectrogramAnimation"]["enabled"] = style != "clock"
            expected["config"]["system_config"]["spectrogramAnimation"]["presets"].update(overlay.MUSIC_PRESETS)
            self.assertEqual(result, expected)
            self.assertEqual(result["config"]["device_config"]["led"]["time_visualization"], before["config"]["device_config"]["led"]["time_visualization"])
            # Expanded factory defaults never enter the sidecar or permit custom parameters.
            self.assertEqual(set(json.loads(self.path.read_text())["settings"]), {"musicStyle"})
        self.assertEqual(overlay.MUSIC_PRESETS, {
            "wave": {"type": "wave"}, "pads": {"type": "pads"}, "bricks": {"type": "bricks"}, "fluid": {"type": "fluid"},
            "random": {"type": "random", "presets": ["wave", "pads", "bricks", "fluid"], "interval": 30}})

    def test_invalid_music_container_fails_closed_without_mutation(self):
        self.write(document({"musicStyle": "clock"}))
        for config in ({"system_config": []}, {"system_config": {"spectrogramAnimation": None}},
                       {"system_config": {"spectrogramAnimation": {"presets": []}}},
                       {"device_config": {"led": {"music_equalizer_visualization": False}}}):
            source = {"status": "ok", "config": config}
            result, status = overlay.apply_overlay(source, self.path)
            self.assertIs(result, source)
            self.assertEqual(status, "invalid")

    def test_expiry_is_inclusive_and_zero_is_persistent(self):
        source = sample_payload()
        for expiry, now, expected in ((100, 99, "applied"), (100, 100, "expired"),
                                      (100, 101, "expired"), (0, 10**15, "applied")):
            self.write(document(expiry=expiry))
            result, status = overlay.apply_overlay(source, self.path, now_ms=now)
            self.assertEqual(status, expected)
            if status == "expired":
                self.assertIs(result, source)

    def test_missing_disabled_and_invalid_leave_exact_original(self):
        source = sample_payload()
        for path, expected in ((None, "disabled"), (self.path, "missing")):
            result, status = overlay.apply_overlay(source, path)
            self.assertIs(result, source)
            self.assertEqual(status, expected)
        self.path.write_bytes(b"[")
        result, status = overlay.apply_overlay(source, self.path)
        self.assertIs(result, source)
        self.assertEqual(status, "invalid")

    def test_schema_rejects_extra_fields_wrong_types_and_unsafe_ranges(self):
        invalid = [None, [], {}, dict(document(), other=True),
                   dict(document(), version=True), dict(document(), version=2),
                   dict(document(), expiresAtMs=True), dict(document(), expiresAtMs=-1),
                   dict(document(), expiresAtMs=2**63), dict(document(), expiresAtMs=1.0)]
        invalid += [document(value) for value in (
            {}, [], {"uniProxyUrl": "forbidden"}, {"clockSize": "huge"}, {"clockSize": []},
            {"clockSize": True}, {"musicStyle": "alien"}, {"musicStyle": True}, {"musicStyle": []},
            {"musicStyle": "wave", "presets": {}}, {"brightness": 1}, {"brightness": {}},
            {"brightness": {"auto": 1, "value": 0.5}},
            {"brightness": {"auto": True, "value": True}},
            {"brightness": {"auto": False, "value": "0.5"}},
            {"brightness": {"auto": False, "value": -0.01}},
            {"brightness": {"auto": False, "value": 1.01}},
            {"brightness": {"auto": False, "value": 0.5, "extra": 1}})]
        source = sample_payload()
        for value in invalid:
            self.write(value)
            with self.subTest(value=value):
                result, status = overlay.apply_overlay(source, self.path)
                self.assertIs(result, source)
                self.assertEqual(status, "invalid")

    def test_decoder_rejects_duplicate_keys_nonfinite_utf8_and_oversize(self):
        source = sample_payload()
        bad = [b'{"version":1,"expiresAtMs":0,"settings":{"clockSize":"small","clockSize":"large"}}',
               b'{"version":1,"version":1,"expiresAtMs":0,"settings":{"clockSize":"small"}}',
               b'{"version":1,"expiresAtMs":0,"settings":{"brightness":{"auto":false,"value":NaN}}}',
               b'{"version":1,"expiresAtMs":0,"settings":{"brightness":{"auto":false,"value":1e999}}}',
               b'\xff', json.dumps(document()).encode() + b' ' * overlay.MAX_OVERLAY_BYTES]
        for value in bad:
            self.path.write_bytes(value)
            result, status = overlay.apply_overlay(source, self.path)
            self.assertIs(result, source)
            self.assertEqual(status, "invalid")

    def test_conflicting_payload_shapes_are_preserved(self):
        self.write(document({"clockSize": "small", "brightness": {"auto": True, "value": 0.5}}))
        sources = [None, {"config": []}, {"config": {"device_config": None}},
                   {"config": {"device_config": {"led": []}}},
                   {"config": {"device_config": {"led": {"time_visualization": "old"}}}},
                   {"config": {"device_config": {"led": {"brightness": "old"}}}}]
        for source in sources:
            result, status = overlay.apply_overlay(source, self.path)
            self.assertIs(result, source)
            self.assertEqual(status, "invalid")


class ResponderIntegrationTests(unittest.TestCase):
    def dispatch(self, request_path, sync_path, overlay_path, policy=None):
        class Client:
            def __init__(self):
                self.incoming = io.BytesIO(("GET " + request_path + " HTTP/1.1\r\nHost: quasar.yandex.net\r\n\r\n").encode())
            def recv(self, size):
                return self.incoming.read(size)
            def settimeout(self, timeout):
                assert timeout > 0
            def sendall(self, value):
                self.sent = value
            def close(self):
                pass
        client = Client()
        runtime = server.LocalServer.__new__(server.LocalServer)
        runtime.args = SimpleNamespace(sync_payload=sync_path, led_overlay=overlay_path)
        if not hasattr(runtime, "args"): runtime.args = SimpleNamespace(station_relay=None, require_station_relay=False)
        runtime.lock = threading.Lock()
        runtime.connections = {client}
        runtime.sni = {}
        runtime.capacity = threading.BoundedSemaphore(1)
        runtime.capacity.acquire()
        runtime.current_policy = mock.Mock(return_value=policy or server.validate_policy({"local_sync_payload": True}))
        runtime.emit = mock.Mock()
        runtime.handle_connection(client, "http")
        return json.loads(client.sent.split(b"\r\n\r\n", 1)[1]), runtime.emit

    def test_handler_overlay_preserves_source_file_voice_and_ota(self):
        with tempfile.TemporaryDirectory(prefix="station-display-handler-") as directory:
            sync = Path(directory) / "sync.json"
            sidecar = Path(directory) / "DO_NOT_LOG_overlay.json"
            source = sample_payload()
            source_bytes = json.dumps(source).encode()
            sync.write_bytes(source_bytes)
            sidecar.write_text(json.dumps(document()), encoding="utf-8")
            result, emit = self.dispatch("/get_sync_info", sync, sidecar)
            expected = copy.deepcopy(source)
            expected["config"]["device_config"]["led"]["time_visualization"]["size"] = "large"
            self.assertEqual(result, expected)
            self.assertEqual(sync.read_bytes(), source_bytes)
            self.assertIn(mock.call("led_overlay", result="applied"), emit.call_args_list)
            self.assertNotIn("SYNTHETIC_SECRET", str(emit.call_args_list))
            self.assertNotIn("DO_NOT_LOG", str(emit.call_args_list))
            result, _ = self.dispatch("/check_updates", sync, sidecar)
            self.assertEqual(result, {"hasUpdate": False})

    def test_invalid_and_expired_overlay_keep_loaded_source_not_minimal_baseline(self):
        with tempfile.TemporaryDirectory(prefix="station-display-fallback-") as directory:
            sync = Path(directory) / "sync.json"
            sidecar = Path(directory) / "overlay.json"
            source = sample_payload()
            sync.write_text(json.dumps(source), encoding="utf-8")
            for raw, status in ((b"[", "invalid"), (json.dumps(document(expiry=1)).encode(), "expired")):
                sidecar.write_bytes(raw)
                result, emit = self.dispatch("/get_sync_info", sync, sidecar)
                self.assertEqual(result, source)
                self.assertIn(mock.call("led_overlay", result=status), emit.call_args_list)

    def test_overlay_is_never_read_for_other_paths_disabled_policy_or_invalid_source(self):
        with tempfile.TemporaryDirectory(prefix="station-display-admission-") as directory:
            sync = Path(directory) / "sync.json"
            sync.write_text(json.dumps(sample_payload()), encoding="utf-8")
            with mock.patch.object(server, "apply_display_overlay", side_effect=AssertionError("overlay must not be read")):
                self.dispatch("/get_sync_info", sync, None, server.BASELINE_POLICY)
                self.dispatch("/check_updates", sync, None)
                sync.write_text("{}", encoding="utf-8")
                result, _ = self.dispatch("/get_sync_info", sync, None)
                self.assertEqual(result, server.SYNC_INFO)


if __name__ == "__main__":
    with mock.patch.object(socket, "socket", side_effect=AssertionError("offline tests forbid sockets")):
        unittest.main(verbosity=2)
