"""Offline parser/response checks: no listeners, real credentials, or Station I/O."""
import importlib.util
import io
import json
from pathlib import Path
import socket
import struct
import sys
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest import mock


spec = importlib.util.spec_from_file_location("local_config_review", Path(__file__).resolve().parents[1] / "LocalConfigServer.py")
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
with mock.patch.object(socket, "socket", side_effect=AssertionError("socket creation forbidden in offline tests")):
    spec.loader.exec_module(module)


def dns_query(name, qtype=1, qclass=1):
    encoded = b"".join(bytes((len(part),)) + part.encode("ascii") for part in name.split(".")) + b"\0"
    return struct.pack("!6H", 0x1234, 0x0100, 1, 0, 0, 0) + encoded + struct.pack("!HH", qtype, qclass)


def client_hello(name="www.google.com", fragments=()):
    encoded = name.encode("ascii")
    names = b"\x00" + struct.pack("!H", len(encoded)) + encoded
    sni = struct.pack("!H", len(names)) + names
    extensions = struct.pack("!HH", 0, len(sni)) + sni
    body = b"\x03\x03" + b"\x00" * 32 + b"\x00\x00\x02\x00\x2f\x01\x00" + struct.pack("!H", len(extensions)) + extensions
    handshake = b"\x01" + len(body).to_bytes(3, "big") + body
    offsets = (0, *fragments, len(handshake))
    return b"".join(b"\x16\x03\x01" + struct.pack("!H", len(handshake[start:end])) + handshake[start:end]
                    for start, end in zip(offsets, offsets[1:]))


class MockConnection:
    """In-memory client; 127.0.0.1 is metadata only, never a socket."""
    peer = ("127.0.0.1", 12345)

    def __init__(self, data):
        self.incoming = io.BytesIO(data)

    def settimeout(self, timeout):
        assert timeout > 0

    def recv(self, count):
        return self.incoming.read(count)


class ProtocolTests(unittest.TestCase):
    def test_all_dns_allowlist_A(self):
        for name in module.HOSTS:
            response, metadata = module.build_dns_response(dns_query(name))
            identifier, flags, qd, answers, _, _ = struct.unpack("!6H", response[:12])
            self.assertEqual((identifier, flags & 15, qd, answers), (0x1234, 0, 1, 1))
            self.assertEqual(response[-4:], bytes((192, 168, 77, 1)))
            self.assertEqual(metadata["result"], "A")

    def test_AAAA_nodata_and_unknown_nxdomain(self):
        response, metadata = module.build_dns_response(dns_query("quasar.yandex.net", 28))
        self.assertEqual((struct.unpack("!6H", response[:12])[1] & 15, metadata["result"]), (0, "NODATA"))
        response, metadata = module.build_dns_response(dns_query("not-allowed.example"))
        self.assertEqual((struct.unpack("!6H", response[:12])[1] & 15, metadata["result"]), (3, "NXDOMAIN"))

    def test_malformed_and_compressed_queries_fail_bounded(self):
        for data in (b"", b"\x12\x34", struct.pack("!6H", 0x1234, 0x100, 1, 0, 0, 0) + b"\xc0\x0c\0\1\0\1"):
            response, metadata = module.build_dns_response(data)
            self.assertEqual(struct.unpack("!6H", response[:12])[1] & 15, 1)
            self.assertEqual(metadata["name"], "<invalid_dns>")

    def test_http_query_headers_and_body_are_absent_from_metadata(self):
        client = MockConnection(b"GET /get_sync_info?oauth=DO_NOT_LOG HTTP/1.1\r\nHost: quasar.yandex.net\r\nAuthorization: Bearer DO_NOT_LOG\r\nContent-Length: 15\r\n\r\nDO_NOT_READ_BODY")
        head = module.read_http_head(client, time.monotonic() + 1)
        self.assertEqual(client.incoming.read(), b"DO_NOT_READ_BODY")
        method, path, host = module.parse_http_head(head)
        response, metadata = module.build_http_response(method, path, host, 80)
        self.assertNotIn("DO_NOT_LOG", json.dumps(metadata))
        self.assertNotIn(b"DO_NOT_LOG", response)
        payload = json.loads(response.split(b"\r\n\r\n", 1)[1])
        self.assertEqual(payload, module.SYNC_INFO)
        self.assertNotIn("subscription", payload)
        self.assertEqual(payload["config"]["system_config"]["com.yandex.tv.services.platform"],
                         {"adbEnabled": True, "developmentSettingsEnabled": True})

    def test_updates_and_registration(self):
        response, metadata = module.build_http_response("GET", "/check_updates", "scbh.yandex.net", 80)
        self.assertEqual(json.loads(response.split(b"\r\n\r\n", 1)[1]), {"hasUpdate": False})
        for path in ("/register", "/device/private-id-DO_NOT_LOG"):
            response, metadata = module.build_http_response("POST", path, "scbh.yandex.net", 80)
            self.assertEqual(metadata["status"], 404)
            self.assertEqual(metadata["path"], "<unrecognized_path>")
            self.assertNotIn("DO_NOT_LOG", json.dumps(metadata))

    def test_POST_sync_and_registration_without_consuming_body(self):
        for path, expected in (("/get_sync_info", 200), ("/register", 404)):
            body = b"UNREAD-SECRET-BODY"
            client = MockConnection(("POST " + path + "?secret=DO_NOT_LOG HTTP/1.1\r\nHost: quasar.yandex.net\r\nContent-Length: " + str(len(body)) + "\r\n\r\n").encode("ascii") + body)
            method, parsed_path, host = module.parse_http_head(module.read_http_head(client, time.monotonic() + 1))
            response, metadata = module.build_http_response(method, parsed_path, host, 80)
            self.assertEqual(metadata["status"], expected)
            self.assertEqual(client.incoming.read(), body)
            self.assertNotIn("DO_NOT_LOG", json.dumps(metadata))
            self.assertNotIn(body, response)

    def test_Host_SNI_and_methods(self):
        for host, sni, expected in (("quasar.yandex.net", "quasar.yandex.net", 200),
                                    ("quasar.yandex.net", None, 421),
                                    ("quasar.yandex.net", "scbh.yandex.net", 421),
                                    ("evil.example", "quasar.yandex.net", 421)):
            _, metadata = module.build_http_response("GET", "/check_updates", host, 443, sni)
            self.assertEqual(metadata["status"], expected)
        response, metadata = module.build_http_response("HEAD", "/check_updates", "quasar.yandex.net", 80)
        self.assertEqual(response.split(b"\r\n\r\n", 1)[1], b"")
        _, metadata = module.build_http_response("DELETE", "/check_updates", "quasar.yandex.net", 80)
        self.assertEqual(metadata["status"], 405)
        response, metadata = module.build_http_response("GET", "/generate_204", "quasar.yandex.net", 80)
        self.assertEqual(metadata["status"], 204)
        self.assertNotIn(b"Content-Length", response)

    def test_duplicate_Host_and_bad_frames_rejected(self):
        for data in (b"GET / HTTP/1.1\r\nHost: quasar.yandex.net\r\nHost: scbh.yandex.net\r\n\r\n",
                     b"GET http://quasar.yandex.net/check_updates HTTP/1.1\r\nHost: quasar.yandex.net\r\n\r\n",
                     b"GET / HTTP/1.1\r\n\r\n"):
            with self.assertRaises(ValueError):
                module.parse_http_head(data)


class PolicyTests(unittest.TestCase):
    def test_registration_stub_is_explicit_exact_and_discards_transport_body(self):
        for value in (None, 1, "true", []):
            with self.assertRaises(ValueError):
                module.validate_policy({"local_registration_stub": value})
        policy = module.validate_policy({"local_registration_stub": True,
                                         "http_connectivity_hosts": ["www.google.com"]})
        self.assertFalse(module.BASELINE_POLICY.local_registration_stub)
        for method in ("GET", "POST"):
            client = MockConnection((method + " /register?secret=DO_NOT_LOG HTTP/1.1\r\nHost: quasar.yandex.net\r\nContent-Length: 11\r\n\r\n").encode("ascii") + b"SECRET_BODY")
            head = module.read_http_head(client, time.monotonic() + 1)
            parsed = module.parse_http_head(head)
            response, metadata = module.build_http_response(*parsed, 80, policy=policy)
            if method == "POST":
                module.discard_stub_post_body(client, head, time.monotonic() + 1)
                self.assertEqual(client.incoming.read(), b"")
            else:
                self.assertEqual(client.incoming.read(), b"SECRET_BODY")
            self.assertEqual(json.loads(response.split(b"\r\n\r\n", 1)[1]), {"message": "allvalid", "status": "ok", "hasCriticalUpdate": False})
            self.assertEqual((metadata["status"], metadata["stub"], metadata["path"]), (200, True, "/register"))
            self.assertNotIn("DO_NOT_LOG", json.dumps(metadata))
            self.assertNotIn(b"SECRET_BODY", response)
        for path in ("/register/", "/api/register", "/v1/register"):
            _, metadata = module.build_http_response("POST", path, "quasar.yandex.net", 80, policy=policy)
            self.assertEqual(metadata["status"], 404)
            self.assertNotIn("stub", metadata)
        _, metadata = module.build_http_response("HEAD", "/register", "quasar.yandex.net", 80, policy=policy)
        self.assertEqual(metadata["status"], 405)
        _, metadata = module.build_http_response("POST", "/register", "www.google.com", 80, policy=policy)
        self.assertEqual(metadata["status"], 404)
        self.assertNotIn("stub", metadata)

    def test_oauth_stub_is_strict_opt_in_and_adds_only_its_own_name(self):
        self.assertFalse(module.BASELINE_POLICY.local_oauth_stub)
        for value in (None, 0, 1, "true", [], {}):
            with self.assertRaises(ValueError):
                module.validate_policy({"local_oauth_stub": value})
        enabled = module.validate_policy({"local_oauth_stub": True})
        disabled = module.validate_policy({"local_oauth_stub": False})
        for policy, expected in ((module.BASELINE_POLICY, "NXDOMAIN"), (disabled, "NXDOMAIN"), (enabled, "A")):
            _, metadata = module.build_dns_response(dns_query("oauth.yandex.ru"), policy)
            self.assertEqual(metadata["result"], expected)
        self.assertIn("oauth.yandex.ru", enabled.https_hosts)
        self.assertEqual(enabled.https_hosts - module.BASELINE_POLICY.https_hosts, {"oauth.yandex.ru"})
        for name in ("login.yandex.ru", "mobileproxy.passport.yandex.net", "oauth.yandex.ru.evil.example"):
            _, metadata = module.build_dns_response(dns_query(name), enabled)
            self.assertEqual(metadata["result"], "NXDOMAIN")

    def test_oauth_route_is_exact_HTTPS_POST_host_SNI_and_returns_fictional_tokens(self):
        policy = module.validate_policy({"local_oauth_stub": True, "local_registration_stub": True})
        response, metadata = module.build_http_response("POST", "/token", "oauth.yandex.ru", 443, "oauth.yandex.ru", policy)
        payload = json.loads(response.split(b"\r\n\r\n", 1)[1])
        self.assertEqual(payload, module.OAUTH_STUB)
        self.assertEqual(set(payload), {"access_token", "expires_in", "refresh_token", "token_type", "uid", "x_token", "xtoken", "is_stub"})
        self.assertEqual(payload["uid"], 1)
        self.assertEqual((metadata["status"], metadata["path"], metadata["host"], metadata["stub"]),
                         (200, "/token", "oauth.yandex.ru", True))
        for key in ("access_token", "refresh_token"):
            self.assertTrue(payload[key].startswith("lab-only-no-real-account-"))
            self.assertNotIn(payload[key], json.dumps(metadata))
        for host, sni, port, path, method, expected in (
            ("oauth.yandex.ru", "oauth.yandex.ru", 443, "/token", "GET", 405),
            ("oauth.yandex.ru", "oauth.yandex.ru", 443, "/token", "HEAD", 405),
            ("oauth.yandex.ru", None, 80, "/token", "POST", 404),
            ("oauth.yandex.ru", "quasar.yandex.net", 443, "/token", "POST", 421),
            ("quasar.yandex.net", "quasar.yandex.net", 443, "/token", "POST", 404),
            ("scbh.yandex.net", "scbh.yandex.net", 443, "/token", "POST", 404),
            ("oauth.yandex.ru", "oauth.yandex.ru", 443, "/1/token", "POST", 404),
            ("oauth.yandex.ru", "oauth.yandex.ru", 443, "/token/", "POST", 404),
            ("oauth.yandex.ru", "oauth.yandex.ru", 443, "/get_sync_info", "POST", 404),
            ("oauth.yandex.ru", "oauth.yandex.ru", 443, "/check_updates", "POST", 404),
            ("oauth.yandex.ru", "oauth.yandex.ru", 443, "/register", "POST", 404)):
            response, metadata = module.build_http_response(method, path, host, port, sni, policy)
            self.assertEqual(metadata["status"], expected)
            self.assertNotIn("stub", metadata)
            self.assertNotIn(b"lab-only", response)
        _, metadata = module.build_http_response("POST", "/token", "oauth.yandex.ru", 443, "oauth.yandex.ru")
        self.assertEqual(metadata["status"], 421)

    def test_stub_body_discard_has_bounded_unambiguous_framing_and_deadline(self):
        body = b"NOT_PARSED_OR_LOGGED" * 300
        head = ("POST /token?DO_NOT_LOG_QUERY HTTP/1.1\r\nHost: oauth.yandex.ru\r\nAuthorization: DO_NOT_LOG_HEADER\r\nContent-Length: " + str(len(body)) + "\r\n\r\n").encode("ascii")
        client = MockConnection(head + body + b"NEXT_REQUEST_NOT_READ")
        parsed_head = module.read_http_head(client, time.monotonic() + 1)
        policy = module.validate_policy({"local_oauth_stub": True})
        response, metadata = module.build_http_response(*module.parse_http_head(parsed_head), 443, "oauth.yandex.ru", policy)
        self.assertIsNone(module.discard_stub_post_body(client, parsed_head, time.monotonic() + 1))
        self.assertEqual(client.incoming.read(), b"NEXT_REQUEST_NOT_READ")
        self.assertNotIn("DO_NOT_LOG", json.dumps(metadata))
        self.assertNotIn(b"NOT_PARSED", response)
        for framing in (b"", b"Content-Length: 1\r\nContent-Length: 1\r\n", b"Content-Length: -1\r\n",
                        b"Content-Length: 65537\r\n", b"Content-Length: 1,1\r\n",
                        b"Content-Length: 1\r\nTransfer-Encoding: chunked\r\n", b"Transfer-Encoding:\r\n",
                        b"Content-Length: 1\r\nTransfer-Encoding : chunked\r\n"):
            with self.assertRaises(ValueError):
                module.discard_stub_post_body(MockConnection(b"secret"), b"POST /token HTTP/1.1\r\n" + framing + b"\r\n", time.monotonic() + 1)
        with self.assertRaises(TimeoutError):
            module.discard_stub_post_body(MockConnection(b"secret"), b"POST /token HTTP/1.1\r\nContent-Length: 6\r\n\r\n", time.monotonic() - 1)
        with self.assertRaises(EOFError):
            module.discard_stub_post_body(MockConnection(b"short"), b"POST /token HTTP/1.1\r\nContent-Length: 6\r\n\r\n", time.monotonic() + 1)

    def test_HTTPS_handler_discards_only_successful_stub_POST_before_response(self):
        class TLSClient(MockConnection):
            def do_handshake(self):
                pass
            def sendall(self, response):
                self.sent = response
                self.body_consumed_before_response = self.incoming.read() == b""
            def close(self):
                pass
        body = b"PRIVATE_AUTH_CODE_NOT_LOGGED"
        client = TLSClient(("POST /token?SECRET_QUERY HTTP/1.1\r\nHost: oauth.yandex.ru\r\nAuthorization: SECRET_HEADER\r\nContent-Length: " + str(len(body)) + "\r\n\r\n").encode("ascii") + body)
        runtime = module.LocalServer.__new__(module.LocalServer)
        if not hasattr(runtime, "args"): runtime.args = SimpleNamespace(station_relay=None, require_station_relay=False)
        runtime.lock = threading.Lock()
        runtime.connections = {client}
        runtime.sni = {id(client): "oauth.yandex.ru"}
        runtime.capacity = threading.BoundedSemaphore(1)
        runtime.capacity.acquire()
        runtime.context = SimpleNamespace(wrap_socket=mock.Mock(return_value=client))
        runtime.maybe_google_tunnel = mock.Mock(return_value=False)
        runtime.current_policy = mock.Mock(return_value=module.validate_policy({"local_oauth_stub": True}))
        runtime.emit = mock.Mock()
        runtime.handle_connection(client, "https")
        self.assertTrue(client.body_consumed_before_response)
        self.assertTrue(client.sent.startswith(b"HTTP/1.1 200 OK\r\n"))
        self.assertNotIn(body, client.sent)
        self.assertNotIn("SECRET_", str(runtime.emit.call_args_list))
        self.assertNotIn("PRIVATE_AUTH", str(runtime.emit.call_args_list))
        self.assertEqual(runtime.connections, set())

    def test_false_updates_fields_only(self):
        policy = module.validate_policy({"check_updates_payload": {"status": "ok", "hasCriticalUpdate": False}})
        response, metadata = module.build_http_response("GET", "/check_updates", "quasar.yandex.net", 80, policy=policy)
        self.assertEqual(json.loads(response.split(b"\r\n\r\n", 1)[1]),
                         {"hasUpdate": False, "status": "ok", "hasCriticalUpdate": False})
        for value in ({"check_updates_payload": {"hasUpdate": True}},
                      {"check_updates_payload": {"hasUpdate": 0}},
                      {"check_updates_payload": {"hasCriticalUpdate": True}},
                      {"check_updates_payload": {"status": "error"}},
                      {"check_updates_payload": {"url": "https://DO_NOT_LOG.example"}},
                      {"bind": "127.0.0.1"},
                      {"extra_hosts": ["www.google.com"]},
                      {"extra_hosts": ["startup.mobile.yandex.net.evil.example"]},
                      {"http_connectivity_hosts": ["evil.example"]}):
            with self.assertRaises(ValueError):
                module.validate_policy(value)

    def test_google_flag_is_strict_bool_and_HTTP_remains_connectivity_only(self):
        for value in (None, 1, 0, "true", [], {}):
            with self.assertRaises(ValueError):
                module.validate_policy({"google_https_passthrough": value})
        policy = module.validate_policy({"google_https_passthrough": True})
        self.assertIn("www.google.com", policy.dns_hosts)
        self.assertNotIn("www.google.com", policy.https_hosts)
        self.assertFalse(module.BASELINE_POLICY.google_https_passthrough)
        _, metadata = module.build_http_response("GET", "/generate_204", "www.google.com", 80, policy=policy)
        self.assertEqual(metadata["status"], 204)
        _, metadata = module.build_http_response("GET", "/get_sync_info", "www.google.com", 80, policy=policy)
        self.assertEqual(metadata["status"], 404)

    def test_google_gen_204_fallback_is_exact_host_HTTP_GET_HEAD_only(self):
        policy = module.validate_policy({"google_https_passthrough": True,
                                         "http_connectivity_hosts": ["connectivitycheck.gstatic.com"]})
        for method in ("GET", "HEAD"):
            response, metadata = module.build_http_response(method, "/gen_204", "www.google.com", 80, policy=policy)
            self.assertEqual((metadata["status"], metadata["path"], metadata["host"]),
                             (204, "/gen_204", "www.google.com"))
            self.assertEqual(response.split(b"\r\n\r\n", 1)[1], b"")
        for host, port, expected in (("quasar.yandex.net", 80, 404), ("connectivitycheck.gstatic.com", 80, 404),
                                     ("www.google.com", 443, 421)):
            _, metadata = module.build_http_response("GET", "/gen_204", host, port, host, policy)
            self.assertEqual(metadata["status"], expected)
        _, metadata = module.build_http_response("POST", "/gen_204", "www.google.com", 80, policy=policy)
        self.assertEqual(metadata["status"], 405)
        _, metadata = module.build_http_response("GET", "/gen_204", "www.google.com", 80)
        self.assertEqual(metadata["status"], 421)

    def test_extra_and_connectivity_hosts_are_separate(self):
        policy = module.validate_policy({"extra_hosts": ["startup.mobile.yandex.net"],
                                         "http_connectivity_hosts": ["connectivitycheck.gstatic.com", "www.google.com"]})
        for name in ("startup.mobile.yandex.net", "connectivitycheck.gstatic.com", "www.google.com"):
            _, baseline = module.build_dns_response(dns_query(name))
            _, enabled = module.build_dns_response(dns_query(name), policy)
            self.assertEqual((baseline["result"], enabled["result"]), ("NXDOMAIN", "A"))
        for name in ("connectivitycheck.gstatic.com", "www.google.com"):
            _, metadata = module.build_http_response("GET", "/generate_204", name, 80, policy=policy)
            self.assertEqual(metadata["status"], 204)
            _, metadata = module.build_http_response("GET", "/check_updates", name, 80, policy=policy)
            self.assertEqual(metadata["status"], 404)
            _, metadata = module.build_http_response("GET", "/generate_204", name, 443, name, policy)
            self.assertEqual(metadata["status"], 421)
        _, metadata = module.build_http_response("GET", "/get_sync_info", "startup.mobile.yandex.net", 443,
                                                  "startup.mobile.yandex.net", policy)
        self.assertEqual(metadata["status"], 200)

    def test_reload_and_invalid_fallback_without_logging_contents(self):
        with tempfile.TemporaryDirectory(prefix="station-policy-offline-") as directory:
            path = Path(directory) / "synthetic-policy.json"
            runtime = module.LocalServer.__new__(module.LocalServer)
            runtime.args = SimpleNamespace(policy=path)
            runtime.emit = mock.Mock()
            if not hasattr(runtime, "args"): runtime.args = SimpleNamespace(station_relay=None, require_station_relay=False)
            runtime.lock = threading.Lock()
            runtime.sni = {}
            path.write_text('{"extra_hosts":["startup.mobile.yandex.net"]}', encoding="utf-8-sig")
            self.assertIn("startup.mobile.yandex.net", runtime.current_policy().https_hosts)
            client = object()
            self.assertIsNone(runtime.check_sni(client, "startup.mobile.yandex.net", None))
            self.assertEqual(runtime.check_sni(client, "www.google.com", None), module.ssl.ALERT_DESCRIPTION_UNRECOGNIZED_NAME)
            # Reload sees the new snapshot without restarting a server.
            path.write_text("{}", encoding="utf-8")
            self.assertNotIn("startup.mobile.yandex.net", runtime.current_policy().https_hosts)
            path.write_text('{"DO_NOT_LOG_SECRET":"DO_NOT_LOG_VALUE"}', encoding="utf-8")
            fallback = runtime.current_policy()
            self.assertEqual(fallback, module.BASELINE_POLICY)
            self.assertNotIn("DO_NOT_LOG", str(runtime.emit.call_args))
            self.assertEqual(runtime.emit.call_args.kwargs["reason"], "invalid_or_unreadable_policy")
            for invalid in ('{"extra_hosts":[],"extra_hosts":[]}', "[", "x" * (module.MAX_POLICY_BYTES + 1)):
                path.write_text(invalid, encoding="utf-8")
                fallback, reason = module.load_policy(path)
                self.assertEqual((fallback, reason), (module.BASELINE_POLICY, "invalid_or_unreadable_policy"))
            path.unlink()
            self.assertEqual(module.load_policy(path)[0], module.BASELINE_POLICY)

    def test_unknown_path_shape_is_a_closed_vocabulary(self):
        method, path, host = module.parse_http_head(
            b"GET /api/v1/quasar/device/DO_NOT_LOG_ID/get_sync_info?DO_NOT_LOG_QUERY HTTP/1.1\r\nHost: quasar.yandex.net\r\n\r\n")
        response, metadata = module.build_http_response(method, path, host, 80)
        self.assertEqual(metadata["status"], 404)  # No basename routing.
        self.assertEqual(metadata["shape"], "/api/v1/quasar/device/<redacted>/get_sync_info")
        self.assertEqual(metadata["path"], "<unrecognized_path>")
        self.assertNotIn("DO_NOT_LOG", json.dumps(metadata))
        self.assertNotIn(b"DO_NOT_LOG", response)


class GoogleRelayTests(unittest.TestCase):
    def test_ClientHello_TCP_and_TLS_record_fragments(self):
        raw = client_hello(fragments=(2, 17, 43))
        self.assertEqual(module.parse_client_hello_sni(raw), "www.google.com")
        for size in range(len(raw)):
            with self.assertRaises(module.IncompleteClientHello):
                module.parse_client_hello_sni(raw[:size])
        class PeekClient:
            def __init__(self):
                self.pieces = iter((raw[:8], raw[:31], raw))
            def settimeout(self, seconds):
                assert seconds > 0
            def recv(self, count, flags):
                self.test_count = count
                assert flags == socket.MSG_PEEK
                return next(self.pieces)
        client = PeekClient()
        self.assertEqual(module.peek_client_hello_sni(client, time.monotonic() + 1), "www.google.com")
        self.assertEqual(client.test_count, module.MAX_CLIENT_HELLO_PEEK)

    def test_unknown_SNI_and_bounds(self):
        for name in ("quasar.yandex.net", "www.google.com.evil.example", "www.google.com."):
            self.assertEqual(module.parse_client_hello_sni(client_hello(name)), name)
        for data in (b"x" * (module.MAX_CLIENT_HELLO_PEEK + 1),
                     b"\x16\x03\x03" + (module.MAX_CLIENT_HELLO + 1).to_bytes(2, "big"),
                     b"\x17\x03\x03\x00\x01x",
                     b"\x16\x03\x03\x00\x04\x01\xff\xff\xff"):
            with self.assertRaises(module.ClientHelloError):
                module.parse_client_hello_sni(data)

    def test_disabled_or_other_SNI_never_opens_upstream(self):
        runtime = module.LocalServer.__new__(module.LocalServer)
        with mock.patch.object(module, "peek_client_hello_sni", side_effect=AssertionError("must not peek")), \
             mock.patch.object(module, "open_google_upstream", side_effect=AssertionError("outbound forbidden")):
            self.assertFalse(runtime.maybe_google_tunnel(object(), module.BASELINE_POLICY, time.monotonic() + 1))
        enabled = module.validate_policy({"google_https_passthrough": True})
        for name in ("quasar.yandex.net", "www.google.com.evil.example", "www.google.com.", None):
            with mock.patch.object(module, "peek_client_hello_sni", return_value=name), \
                 mock.patch.object(module, "open_google_upstream", side_effect=AssertionError("outbound forbidden")):
                self.assertFalse(runtime.maybe_google_tunnel(object(), enabled, time.monotonic() + 1))

    def test_resolver_has_one_fixed_destination_and_public_only(self):
        records = [(socket.AF_INET, socket.SOCK_STREAM, socket.IPPROTO_TCP, "", (address, 443))
                   for address in ("127.0.0.1", "192.168.77.1", "169.254.1.1", "224.0.0.1", "100.64.0.1", "142.250.74.68")]
        with mock.patch.object(socket, "getaddrinfo", return_value=records) as resolver_call:
            addresses = module.GoogleResolver().resolve(time.monotonic() + 1)
            resolver_call.assert_called_once_with("www.google.com", 443, socket.AF_UNSPEC, socket.SOCK_STREAM, socket.IPPROTO_TCP)
        self.assertEqual(addresses, [(socket.AF_INET, ("142.250.74.68", 443))])
        mixed = [(socket.AF_INET6, socket.SOCK_STREAM, socket.IPPROTO_TCP, "", ("2607:f8b0:4007:80e::2004", 443, 0, 0)),
                 (socket.AF_INET, socket.SOCK_STREAM, socket.IPPROTO_TCP, "", ("142.250.74.68", 443))]
        self.assertEqual(module.public_google_addresses(mixed)[0][0], socket.AF_INET)
        endpoint = mock.Mock()
        resolver = mock.Mock()
        resolver.resolve.return_value = [(socket.AF_INET, ("127.0.0.1", 443)), (socket.AF_INET, ("142.250.74.68", 443))]
        with mock.patch.object(socket, "socket", return_value=endpoint) as socket_factory:
            self.assertIs(module.open_google_upstream(resolver, time.monotonic() + 1), endpoint)
        socket_factory.assert_called_once_with(socket.AF_INET, socket.SOCK_STREAM, socket.IPPROTO_TCP)
        endpoint.connect.assert_called_once_with(("142.250.74.68", 443))

    def test_opaque_relay_partial_sends_and_cleanup(self):
        class Duplex:
            def __init__(self, incoming):
                self.incoming = io.BytesIO(incoming)
                self.sent = bytearray()
                self.shutdowns = []
            def setblocking(self, value):
                assert value is False
            def recv(self, count):
                assert 0 < count <= 16384
                return self.incoming.read(count)
            def send(self, data):
                count = min(3, len(data))
                self.sent.extend(data[:count])
                return count
            def shutdown(self, how):
                self.shutdowns.append(how)
        client, upstream = Duplex(b"opaque-client-TLS"), Duplex(b"opaque-server-TLS")
        with mock.patch.object(module.select, "select", side_effect=lambda reads, writes, errors, timeout: (reads, writes, [])):
            result, sent, received = module.relay_tls(client, upstream, time.monotonic() + 1, threading.Event())
        self.assertEqual((result, sent, received), ("closed", 17, 17))
        self.assertEqual(upstream.sent, b"opaque-client-TLS")
        self.assertEqual(client.sent, b"opaque-server-TLS")
        self.assertEqual((client.shutdowns, upstream.shutdowns), ([socket.SHUT_WR], [socket.SHUT_WR]))
        runtime = module.LocalServer.__new__(module.LocalServer)
        runtime.stop, runtime.lock = threading.Event(), threading.Lock()
        runtime.connections = set()
        runtime.google_resolver = object()
        runtime.emit = mock.Mock()
        endpoint = mock.Mock()
        enabled = module.validate_policy({"google_https_passthrough": True})
        with mock.patch.object(module, "peek_client_hello_sni", return_value="www.google.com"), \
             mock.patch.object(module, "open_google_upstream", return_value=endpoint), \
             mock.patch.object(module, "relay_tls", return_value=("closed", 17, 17)):
            self.assertTrue(runtime.maybe_google_tunnel(object(), enabled, time.monotonic() + 1))
        endpoint.close.assert_called_once()
        self.assertEqual(runtime.connections, set())
        self.assertEqual(runtime.emit.call_args.kwargs, {"host": "www.google.com", "result": "closed", "sent_bytes": 17, "received_bytes": 17})


class LocalNtpTests(unittest.TestCase):
    def test_NTP_policy_is_strict_and_DNS_names_do_not_become_HTTP_hosts(self):
        self.assertFalse(module.BASELINE_POLICY.local_ntp_server)
        for value in (None, 0, 1, "true", [], {}):
            with self.assertRaises(ValueError):
                module.validate_policy({"local_ntp_server": value})
        enabled = module.validate_policy({"local_ntp_server": True})
        self.assertEqual(enabled.dns_hosts - module.BASELINE_POLICY.dns_hosts, module.NTP_HOSTS)
        self.assertEqual(enabled.https_hosts, module.BASELINE_POLICY.https_hosts)
        for name in module.NTP_HOSTS:
            _, baseline = module.build_dns_response(dns_query(name))
            _, active = module.build_dns_response(dns_query(name), enabled)
            self.assertEqual((baseline["result"], active["result"]), ("NXDOMAIN", "A"))
            _, ipv6 = module.build_dns_response(dns_query(name, 28), enabled)
            self.assertEqual(ipv6["result"], "NODATA")
            for port in (80, 443):
                _, metadata = module.build_http_response("GET", "/check_updates", name, port, name, enabled)
                self.assertEqual(metadata["status"], 421)
        _, metadata = module.build_dns_response(dns_query("0.android.pool.ntp.org"), enabled)
        self.assertEqual(metadata["result"], "NXDOMAIN")

    def test_NTP_reply_passes_Android9_checks_and_preserves_originate_timestamp(self):
        policy = module.validate_policy({"local_ntp_server": True})
        # Client clock can be years behind. Its opaque randomized timestamp must
        # be copied verbatim, while receive/transmit use the current PC clock.
        originate = bytes.fromhex("E700000012345678")
        now = 1793055600.25
        def decoded_timestamp(data):
            seconds, fraction = struct.unpack("!II", data)
            return seconds - 2208988800 + fraction / (1 << 32)
        for version in (3, 4):
            request = bytes(((version << 3) | 3,)) + bytes(39) + originate
            response, metadata = module.build_ntp_response(request, now, now + 0.125, policy)
            self.assertEqual(len(response), 48)
            leap, mode, stratum = response[0] >> 6, response[0] & 7, response[1]
            self.assertNotEqual(leap, 3)
            self.assertEqual(mode, 4)
            self.assertTrue(1 <= stratum <= 15)
            self.assertNotEqual(response[40:48], bytes(8))
            self.assertEqual((response[0] >> 3) & 7, version)
            self.assertEqual(response[12:16], b"LOCL")
            self.assertEqual(response[24:32], originate)
            self.assertAlmostEqual(decoded_timestamp(response[16:24]), now - 1, places=6)
            self.assertAlmostEqual(decoded_timestamp(response[32:40]), now, places=6)
            self.assertAlmostEqual(decoded_timestamp(response[40:48]), now + 0.125, places=6)
            self.assertEqual(metadata, {"result": "reply", "version": version, "stratum": 10})
            self.assertNotIn(originate.hex(), json.dumps(metadata))

    def test_NTP_rejects_oversize_wrong_mode_version_and_disabled_policy(self):
        policy = module.validate_policy({"local_ntp_server": True})
        request = b"\x1b" + bytes(47)
        self.assertIsNone(module.build_ntp_response(request, 1793055600, 1793055600)[0])
        for data in (b"", bytes(47), request + bytes(module.MAX_NTP_MESSAGE - 47)):
            response, metadata = module.build_ntp_response(data, 1793055600, 1793055600, policy)
            self.assertIsNone(response)
            self.assertEqual(metadata["result"], "ignored_length")
        for version in range(8):
            for mode in range(8):
                if version in (3, 4) and mode == 3:
                    continue
                response, metadata = module.build_ntp_response(bytes(((version << 3) | mode,)) + bytes(47),
                                                               1793055600, 1793055600, policy)
                self.assertIsNone(response)
                self.assertEqual(metadata["result"], "ignored_mode_version")
        for invalid_time in (float("nan"), float("inf")):
            self.assertEqual(module.build_ntp_response(request, invalid_time, invalid_time, policy),
                             (None, {"result": "invalid_clock"}))

    def test_UDP123_bind_is_optional_exclusive_and_only_on_fixed_interface(self):
        for enabled in (False, True):
            runtime = module.LocalServer.__new__(module.LocalServer)
            runtime.listeners = []
            runtime.current_policy = mock.Mock(return_value=module.validate_policy({"local_ntp_server": enabled}))
            endpoints = [mock.Mock() for _ in range(5 if enabled else 4)]
            with mock.patch.object(socket, "socket", side_effect=endpoints):
                runtime.bind_all()
            expected_ports = [53, 53, 80, 443] + ([123] if enabled else [])
            for endpoint, port in zip(endpoints, expected_ports):
                endpoint.bind.assert_called_once_with(("192.168.77.1", port))
                for call in endpoint.setsockopt.call_args_list:
                    if port == 123: self.assertNotEqual(call.args[1], socket.SO_REUSEADDR)
            self.assertEqual(any(kind == "ntp_udp" for _, kind in runtime.listeners), enabled)


class SyncPayloadTests(unittest.TestCase):
    def test_opt_in_envelope_and_metadata_never_include_private_values(self):
        self.assertFalse(module.BASELINE_POLICY.local_sync_payload)
        for value in (None, 0, 1, "true", [], {}):
            with self.assertRaises(ValueError):
                module.validate_policy({"local_sync_payload": value})
        policy = module.validate_policy({"local_sync_payload": True})
        payload = {"status": "ok", "config": {"device_config": {"name": "synthetic"}},
                   "glagol": {"security": {"server_private_key": "SYNTHETIC_PRIVATE_KEY_DO_NOT_LOG"}}}
        response, metadata = module.build_http_response("GET", "/get_sync_info", "quasar.yandex.net", 80,
                                                        policy=policy, sync_payload=payload)
        self.assertEqual(json.loads(response.split(b"\r\n\r\n", 1)[1]), payload)
        self.assertNotIn("SYNTHETIC_PRIVATE_KEY", json.dumps(metadata))
        response, _ = module.build_http_response("GET", "/get_sync_info", "quasar.yandex.net", 80,
                                                 sync_payload=payload)
        self.assertEqual(json.loads(response.split(b"\r\n\r\n", 1)[1]), module.SYNC_INFO)
        response, metadata = module.build_http_response("GET", "/check_updates", "quasar.yandex.net", 80,
                                                        policy=policy, sync_payload=payload)
        self.assertEqual(json.loads(response.split(b"\r\n\r\n", 1)[1]), {"hasUpdate": False})
        self.assertNotIn("SYNTHETIC_PRIVATE_KEY", json.dumps(metadata))

    def test_fixed_file_reload_bounds_duplicates_and_fail_closed(self):
        with tempfile.TemporaryDirectory(prefix="station-sync-offline-") as directory:
            path = Path(directory) / "synthetic-sync.json"
            value = {"status": "ok", "config": {"name": "one"}}
            path.write_text(json.dumps(value), encoding="utf-8-sig")
            self.assertEqual(module.load_sync_payload(path), (value, None))
            value["config"]["name"] = "two"
            path.write_text(json.dumps(value), encoding="utf-8")
            self.assertEqual(module.load_sync_payload(path), (value, None))
            valid = json.dumps(value).encode("utf-8")
            path.write_bytes(valid + b" " * (module.MAX_SYNC_PAYLOAD_BYTES - len(valid)))
            self.assertEqual(module.load_sync_payload(path), (value, None))
            path.write_bytes(valid + b" " * (module.MAX_SYNC_PAYLOAD_BYTES - len(valid) + 1))
            self.assertEqual(module.load_sync_payload(path), (None, "invalid_or_unreadable_payload"))
            invalid_values = ('[]', '{}', '{"status":"error","config":{}}', '{"status":"ok","config":[]}',
                              '{"status":"ok","config":{},"config":{"SECRET":"DO_NOT_LOG"}}',
                              '{"status":"ok","config":{"invalid":NaN}}', '[', '\udcff')
            for invalid in invalid_values:
                path.write_bytes(invalid.encode("utf-8", errors="surrogatepass"))
                result, reason = module.load_sync_payload(path)
                self.assertEqual((result, reason), (None, "invalid_or_unreadable_payload"))
                self.assertNotIn("DO_NOT_LOG", reason)
            path.unlink()
            self.assertEqual(module.load_sync_payload(path), (None, "invalid_or_unreadable_payload"))
            self.assertEqual(module.load_sync_payload(None), (None, "invalid_or_unreadable_payload"))

    def test_handler_reads_only_admitted_sync_and_logs_fixed_results(self):
        class HTTPClient(MockConnection):
            def sendall(self, data):
                self.sent = data
            def close(self):
                pass
        def dispatch(path, policy, payload_path):
            client = HTTPClient(("GET " + path + "?SECRET_QUERY HTTP/1.1\r\nHost: quasar.yandex.net\r\n\r\n").encode("ascii"))
            runtime = module.LocalServer.__new__(module.LocalServer)
            runtime.args = SimpleNamespace(sync_payload=payload_path)
            if not hasattr(runtime, "args"): runtime.args = SimpleNamespace(station_relay=None, require_station_relay=False)
            runtime.lock = threading.Lock()
            runtime.connections = {client}
            runtime.sni = {}
            runtime.capacity = threading.BoundedSemaphore(1)
            runtime.capacity.acquire()
            runtime.current_policy = mock.Mock(return_value=policy)
            runtime.emit = mock.Mock()
            runtime.handle_connection(client, "http")
            return client.sent, runtime.emit
        with tempfile.TemporaryDirectory(prefix="station-sync-handler-offline-") as directory:
            path = Path(directory) / "DO_NOT_LOG_PATH.json"
            payload = {"status": "ok", "config": {}, "glagol": {"security": {"server_private_key": "SECRET_KEY"}}}
            path.write_text(json.dumps(payload), encoding="utf-8")
            policy = module.validate_policy({"local_sync_payload": True})
            response, emit = dispatch("/get_sync_info", policy, path)
            self.assertEqual(json.loads(response.split(b"\r\n\r\n", 1)[1]), payload)
            self.assertIn(mock.call("sync_payload", result="loaded"), emit.call_args_list)
            self.assertNotIn("SECRET", str(emit.call_args_list))
            self.assertNotIn("DO_NOT_LOG", str(emit.call_args_list))
            path.write_text('{"DO_NOT_LOG_SECRET":"PRIVATE"}', encoding="utf-8")
            response, emit = dispatch("/get_sync_info", policy, path)
            self.assertEqual(json.loads(response.split(b"\r\n\r\n", 1)[1]), module.SYNC_INFO)
            self.assertIn(mock.call("sync_payload", result="baseline_fallback", reason="invalid_or_unreadable_payload"), emit.call_args_list)
            self.assertNotIn("DO_NOT_LOG", str(emit.call_args_list))
            with mock.patch.object(module, "load_sync_payload", side_effect=AssertionError("payload must not be read")):
                dispatch("/get_sync_info", module.BASELINE_POLICY, path)
                dispatch("/api/get_sync_info", policy, path)
                dispatch("/check_updates", policy, path)
                dispatch("/register", policy, path)


if __name__ == "__main__":
    with mock.patch.object(socket, "socket", side_effect=AssertionError("socket creation forbidden in offline tests")):
        unittest.main(verbosity=2)
