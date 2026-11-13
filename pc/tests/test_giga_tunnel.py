"""Offline tests only: socketpair, mocked DNS, no listener or API requests."""

import importlib.util
from pathlib import Path
import os
import socket
import threading
import time
import unittest
from unittest import mock


spec = importlib.util.spec_from_file_location(
    "giga_tunnel", Path(__file__).resolve().parents[1] / "giga_tunnel.py")
tunnel = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tunnel)


def request(authority="api.giga.chat:443", extra=""):
    return (f"CONNECT {authority} HTTP/1.1\r\nHost: {authority}\r\n"
            f"{extra}\r\n").encode("ascii")


class ParserTests(unittest.TestCase):
    def test_exact_destinations_and_case_normalization(self):
        self.assertEqual(tunnel.parse_connect(request()), ("api.giga.chat", 443))
        self.assertEqual(tunnel.parse_connect(request("API.GIGA.CHAT:00443")),
                         ("api.giga.chat", 443))
        self.assertEqual(tunnel.parse_connect(request("ngw.devices.sberbank.ru:9443")),
                         ("ngw.devices.sberbank.ru", 9443))
        self.assertEqual(tunnel.parse_connect(request("api.speechify.ai:443")),
                         ("api.speechify.ai", 443))

    def test_other_authorities_are_denied(self):
        authorities = ["example.com:443", "api.giga.chat:9443", "api.giga.chat:80",
                       "api.speechify.ai:80", "api.speechify.ai.evil.org:443",
                       "ngw.devices.sberbank.ru:443", "api.giga.chat.evil.org:443",
                       "127.0.0.1:443", "1.1.1.1:443", "[::1]:443",
                       "api.giga.chat", "api.giga.chat.:443", "user@api.giga.chat:443",
                       "https://api.giga.chat:443", "api.giga.chat:443/x",
                       "api.giga.chat:+443", "api.giga.chat:0", "api.giga.chat:65536"]
        for authority in authorities:
            with self.subTest(authority=authority), self.assertRaises(tunnel.Denied):
                tunnel.parse_connect(request(authority))

    def test_music_hosts_are_exact_enrollment_not_a_suffix_rule(self):
        for host in tunnel.MUSIC_API_HOSTS | tunnel.MUSIC_AUDIO_HOSTS:
            self.assertEqual(tunnel.parse_connect(request(host + ':443')), (host, 443))
            for denied in (host + ':80', host + '.evil.invalid:443'):
                with self.assertRaises(tunnel.Denied): tunnel.parse_connect(request(denied))
        for denied in ('unseen.vkuseraudio.net:443', 'quasar.yandex.net:443',
                       'storage.yandex.net:443', 'dl3s999.muzofond.fm:443'):
            with self.assertRaises(tunnel.Denied): tunnel.parse_connect(request(denied))

    def test_methods_and_bad_framing_are_denied(self):
        invalid = [request().replace(b"CONNECT", b"GET", 1),
                   request().replace(b"CONNECT", b"connect", 1),
                   request().replace(b"HTTP/1.1", b"HTTP/2", 1),
                   request().replace(b"CONNECT ", b"CONNECT  ", 1),
                   request().replace(b"\r\n", b"\n"), request()[:-1],
                   request().replace(b"Host: api.giga.chat:443\r\n", b""),
                   request(extra="Host: api.giga.chat:443\r\n"),
                   request(extra="Content-Length: 1\r\n"),
                   request(extra="Transfer-Encoding: chunked\r\n"),
                   request(extra="X-Test: x\r\n folded\r\n"),
                   request(extra="Bad Header: x\r\n"),
                   request(extra="X-Test: x\x00y\r\n"),
                   request().replace(b"Host: api.giga.chat:443", b"Host: example.com:443"),
                   request(extra="X-Test: x\r\n\r\nsmuggled"),
                   request().replace(b"Host:", b"H\xffst:")]
        for header in invalid:
            with self.subTest(header=header), self.assertRaises(tunnel.Denied):
                tunnel.parse_connect(header)

    def test_header_size_boundary(self):
        base = request(extra="X-Fill: \r\n")
        exact = request(extra="X-Fill: " + "a" * (8192 - len(base)) + "\r\n")
        self.assertEqual(len(exact), 8192)
        self.assertEqual(tunnel.parse_connect(exact), ("api.giga.chat", 443))
        with self.assertRaises(tunnel.Denied):
            tunnel.parse_connect(exact.replace(b"X-Fill: ", b"X-Fill: a", 1))

    def test_read_preserves_initial_opaque_bytes(self):
        sender, receiver = socket.socketpair()
        try:
            initial = b"\x16\x03\x03\x00\x04\x00\xff\r\n"
            sender.sendall(request() + initial)
            target, tail = tunnel.read_connect(receiver, time.monotonic() + 1)
            self.assertEqual(target, ("api.giga.chat", 443))
            self.assertEqual(tail, initial)
        finally:
            sender.close()
            receiver.close()

    def test_unterminated_oversized_header_is_denied(self):
        sender, receiver = socket.socketpair()
        try:
            sender.sendall(b"a" * 8192)
            with self.assertRaises(tunnel.Denied):
                tunnel.read_connect(receiver, time.monotonic() + 1)
        finally:
            sender.close()
            receiver.close()

    def test_slow_header_has_deadline(self):
        sender, receiver = socket.socketpair()
        try:
            with self.assertRaises(TimeoutError):
                tunnel.read_connect(receiver, time.monotonic() + 0.03)
        finally:
            sender.close()
            receiver.close()

    def test_denied_target_never_resolves(self):
        with mock.patch.object(tunnel.socket, "getaddrinfo") as dns:
            with self.assertRaises(tunnel.Denied):
                tunnel.connect_upstream(tunnel.Target("example.com", 443),
                                        time.monotonic() + 1)
            dns.assert_not_called()

    def test_private_dns_answer_never_connects(self):
        answer = [(socket.AF_INET, socket.SOCK_STREAM, socket.IPPROTO_TCP, "",
                   ("127.0.0.1", 443))]
        with mock.patch.object(tunnel.socket, "getaddrinfo", return_value=answer) as dns:
            with mock.patch.object(tunnel.socket, "socket") as create_socket:
                with self.assertRaises(OSError):
                    tunnel.connect_upstream(tunnel.Target("api.giga.chat", 443),
                                            time.monotonic() + 1)
                create_socket.assert_not_called()
            dns.assert_called_once_with("api.giga.chat", 443, type=socket.SOCK_STREAM,
                                        proto=socket.IPPROTO_TCP)


class RelayTests(unittest.TestCase):
    def test_bidirectional_binary_with_backpressure_and_half_close(self):
        client, client_relay = socket.socketpair()
        upstream_relay, upstream = socket.socketpair()
        sockets = (client, client_relay, upstream_relay, upstream)
        for stream in sockets:
            stream.settimeout(3)
            stream.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4096)
        payload = bytes(range(256)) * 4096
        reply = b"\x00\xffopaque-response" * 8192
        initial = b"coalesced\x00"
        errors = []

        def relay_worker():
            try:
                tunnel.relay(client_relay, upstream_relay, time.monotonic() + 5,
                             initial=initial, inactivity=2)
            except BaseException as error:
                errors.append(error)

        def remote_worker():
            try:
                received = bytearray()
                while chunk := upstream.recv(16384):
                    received.extend(chunk)
                self.assertEqual(received, initial + payload)
                # This response starts only after the relay forwards client EOF.
                upstream.sendall(reply)
                upstream.shutdown(socket.SHUT_WR)
            except BaseException as error:
                errors.append(error)

        workers = [threading.Thread(target=relay_worker, daemon=True),
                   threading.Thread(target=remote_worker, daemon=True)]
        try:
            for worker in workers:
                worker.start()
            client.sendall(payload)
            client.shutdown(socket.SHUT_WR)
            received = bytearray()
            while chunk := client.recv(16384):
                received.extend(chunk)
            self.assertEqual(received, reply)
            for worker in workers:
                worker.join(3)
                self.assertFalse(worker.is_alive(), "Relay failed to finish after EOF")
            self.assertEqual(errors, [])
        finally:
            for stream in sockets:
                stream.close()

    def test_idle_timeout_and_absolute_deadline(self):
        for lifetime, idle in ((0.04, 1), (1, 0.04)):
            with self.subTest(lifetime=lifetime, idle=idle):
                client, client_relay = socket.socketpair()
                upstream_relay, upstream = socket.socketpair()
                try:
                    started = time.monotonic()
                    tunnel.relay(client_relay, upstream_relay, started + lifetime,
                                 inactivity=idle)
                    self.assertLess(time.monotonic() - started, 0.5)
                finally:
                    for stream in (client, client_relay, upstream_relay, upstream):
                        stream.close()


class FixedInterfaceTests(unittest.TestCase):
    def setUp(self):
        self.windows = mock.patch.object(tunnel.os, "name", "nt")
        self.windows.start()
        self.addCleanup(self.windows.stop)
        self.target = tunnel.Target("api.speechify.ai", 443)
        self.address = ("34.49.245.64", 443)
        self.answer = [(socket.AF_INET, socket.SOCK_STREAM, socket.IPPROTO_TCP,
                        "", self.address)]

    def test_explicit_pair_validates_private_source(self):
        self.assertEqual(tunnel.validate_source_selection(10, "10.131.146.72"),
                         (10, "10.131.146.72"))
        self.assertIsNone(tunnel.validate_source_selection(None, None))

    def test_invalid_or_incomplete_pair_has_no_dns_or_socket(self):
        invalid = [(10, None), (None, "10.131.146.72"),
                   (0, "10.131.146.72"), (-1, "10.131.146.72"),
                   (0x1000000, "10.131.146.72"), (True, "10.131.146.72"),
                   (10, "0.0.0.0"), (10, "127.0.0.1"), (10, "224.0.0.1"),
                   (10, "255.255.255.255"), (10, "::1"), (10, "example.com"),
                   (10, "169.254.1.2"), (10, "0.1.2.3")]
        with mock.patch.object(tunnel.socket, "getaddrinfo") as dns, \
                mock.patch.object(tunnel.socket, "socket") as create:
            for index, source in invalid:
                with self.subTest(index=index, source=source), self.assertRaises(ValueError):
                    tunnel.connect_upstream(self.target, time.monotonic() + 1,
                                            interface_index=index, source_ip=source)
            dns.assert_not_called()
            create.assert_not_called()

    def test_fixed_mode_rejects_non_windows(self):
        with mock.patch.object(tunnel.os, "name", "posix"), self.assertRaises(ValueError):
            tunnel.validate_source_selection(10, "10.131.146.72")

    def test_ipv4_resolution_option_binding_and_connect_order(self):
        upstream = mock.Mock()
        with mock.patch.object(tunnel.socket, "getaddrinfo", return_value=self.answer) as dns, \
                mock.patch.object(tunnel.socket, "socket", return_value=upstream) as create:
            self.assertIs(tunnel.connect_upstream(self.target, time.monotonic() + 1,
                          interface_index=10, source_ip="10.131.146.72"), upstream)
            dns.assert_called_once_with("api.speechify.ai", 443,
                                        type=socket.SOCK_STREAM, proto=socket.IPPROTO_TCP,
                                        family=socket.AF_INET)
            create.assert_called_once_with(socket.AF_INET, socket.SOCK_STREAM, socket.IPPROTO_TCP)
            self.assertEqual(upstream.mock_calls,
                             [mock.call.settimeout(mock.ANY),
                              mock.call.setsockopt(socket.IPPROTO_IP, 31, socket.htonl(10)),
                              mock.call.bind(("10.131.146.72", 0)),
                              mock.call.connect(self.address)])

    def test_fixed_mode_never_uses_ipv6_or_private_destination(self):
        answers = [(socket.AF_INET6, socket.SOCK_STREAM, socket.IPPROTO_TCP, "",
                    ("2606:4700:4700::1111", 443, 0, 0)),
                   (socket.AF_INET, socket.SOCK_STREAM, socket.IPPROTO_TCP, "",
                    ("192.168.0.1", 443))]
        with mock.patch.object(tunnel.socket, "getaddrinfo", return_value=answers), \
                mock.patch.object(tunnel.socket, "socket") as create, self.assertRaises(OSError):
            tunnel.connect_upstream(self.target, time.monotonic() + 1,
                                    interface_index=10, source_ip="10.131.146.72")
        create.assert_not_called()

    def test_each_failure_closes_and_never_falls_back_to_unbound_socket(self):
        for failure in ("setsockopt", "bind", "connect"):
            with self.subTest(failure=failure):
                upstream = mock.Mock()
                getattr(upstream, failure).side_effect = OSError("Test-only failure")
                with mock.patch.object(tunnel.socket, "getaddrinfo", return_value=self.answer) as dns, \
                        mock.patch.object(tunnel.socket, "socket", return_value=upstream) as create, \
                        self.assertRaises(OSError):
                    tunnel.connect_upstream(self.target, time.monotonic() + 1,
                                            interface_index=10, source_ip="10.131.146.72")
                self.assertEqual(create.call_count, 1)
                upstream.close.assert_called_once_with()
                if failure == "setsockopt":
                    upstream.bind.assert_not_called()
                    upstream.connect.assert_not_called()
                elif failure == "bind":
                    upstream.connect.assert_not_called()
                dns.assert_called_once()

    def test_all_address_attempts_keep_same_binding(self):
        answer = self.answer + [(socket.AF_INET, socket.SOCK_STREAM, socket.IPPROTO_TCP,
                                 "", ("8.8.8.8", 443))]
        first, second = mock.Mock(), mock.Mock()
        first.connect.side_effect = OSError("Test-only refusal")
        with mock.patch.object(tunnel.socket, "getaddrinfo", return_value=answer), \
                mock.patch.object(tunnel.socket, "socket", side_effect=[first, second]):
            self.assertIs(tunnel.connect_upstream(self.target, time.monotonic() + 1,
                          interface_index=10, source_ip="10.131.146.72"), second)
        for stream in (first, second):
            stream.setsockopt.assert_called_once_with(socket.IPPROTO_IP, 31, socket.htonl(10))
            stream.bind.assert_called_once_with(("10.131.146.72", 0))
        first.close.assert_called_once_with()

    def test_interface_index_dword_bit_pattern_survives_signed_int(self):
        upstream = mock.Mock()
        with mock.patch.object(tunnel.socket, "getaddrinfo", return_value=self.answer), \
                mock.patch.object(tunnel.socket, "socket", return_value=upstream):
            tunnel.connect_upstream(self.target, time.monotonic() + 1,
                                    interface_index=128, source_ip="10.131.146.72")
        value = upstream.setsockopt.call_args.args[2]
        self.assertEqual(value & 0xFFFFFFFF, socket.htonl(128))
        self.assertTrue(-0x80000000 <= value <= 0x7FFFFFFF)

    def test_default_mode_preserves_existing_resolver_and_no_binding(self):
        upstream = mock.Mock()
        with mock.patch.object(tunnel.socket, "getaddrinfo", return_value=self.answer) as dns, \
                mock.patch.object(tunnel.socket, "socket", return_value=upstream):
            tunnel.connect_upstream(self.target, time.monotonic() + 1)
        dns.assert_called_once_with("api.speechify.ai", 443,
                                    type=socket.SOCK_STREAM, proto=socket.IPPROTO_TCP)
        upstream.setsockopt.assert_not_called()
        upstream.bind.assert_not_called()

    def test_cli_and_server_pass_the_pair_without_interface_discovery(self):
        with mock.patch.object(tunnel, "TunnelServer") as server:
            tunnel.main(["--interface-index", "10", "--source-ip", "10.131.146.72"])
        server.assert_called_once_with(tunnel.DEFAULT_PORT, tunnel.MAX_SECONDS,
                                       interface_index=10, source_ip="10.131.146.72")
        server.return_value.run.assert_called_once_with()
        with mock.patch("sys.stderr"), self.assertRaises(SystemExit):
            tunnel.main(["--interface-index", "10"])


class LifecycleTests(unittest.TestCase):
    def test_cli_rejects_unbounded_lifetime_and_invalid_port(self):
        for args in (["--max-seconds", "601"], ["--max-seconds", "0"],
                     ["--port", "0"], ["--port", "65536"]):
            with self.subTest(args=args), mock.patch("sys.stderr"), self.assertRaises(SystemExit):
                tunnel.main(args)

    def test_global_lifetime_and_worker_budget(self):
        server = tunnel.TunnelServer(max_seconds=600)
        self.assertLessEqual(server.deadline, time.monotonic() + 600)
        self.assertEqual(tunnel.MAX_WORKERS, 6)  # + one accept/main thread
        for _ in range(6):
            self.assertTrue(server.slots.acquire(blocking=False))
        self.assertFalse(server.slots.acquire(blocking=False))

    def test_music_connect_succeeds_with_three_warm_origins_and_budget_stays_bounded(self):
        server = tunnel.TunnelServer(max_seconds=10)
        # Simulate held LLM, TTS and catalog CONNECTs without closing their sockets.
        for _ in range(3):
            self.assertTrue(server.slots.acquire(blocking=False))
        client, server_side = socket.socketpair()
        upstream_side, upstream = socket.socketpair()
        self.assertTrue(server.slots.acquire(blocking=False))
        try:
            client.sendall(request('cs1-77v4.vkuseraudio.net:443'))
            with mock.patch.object(tunnel, 'connect_upstream', return_value=upstream_side) as connect, \
                 mock.patch.object(tunnel, 'relay') as relay:
                server._handle(server_side)
                self.assertEqual(connect.call_args.args[0], ('cs1-77v4.vkuseraudio.net', 443))
                relay.assert_called_once()
            client.settimeout(1)
            self.assertTrue(client.recv(1024).startswith(b'HTTP/1.1 200 Connection Established\r\n'))
            # The completed music CONNECT released its slot. Six held slots are
            # still the hard limit; no unbounded queue/thread growth was added.
            for _ in range(3):
                self.assertTrue(server.slots.acquire(blocking=False))
            self.assertFalse(server.slots.acquire(blocking=False))
        finally:
            for stream in (client, server_side, upstream_side, upstream):
                stream.close()

    def test_rejected_request_returns_403_without_dns_or_api(self):
        client, server_side = socket.socketpair()
        server = tunnel.TunnelServer(max_seconds=10)
        self.assertTrue(server.slots.acquire(blocking=False))
        try:
            client.sendall(b"GET / HTTP/1.1\r\nHost: api.giga.chat:443\r\n\r\n")
            with mock.patch.object(tunnel.socket, "getaddrinfo") as dns:
                server._handle(server_side)
                dns.assert_not_called()
            client.settimeout(1)
            self.assertEqual(client.recv(1024),
                             b"HTTP/1.1 403 Forbidden\r\nConnection: close\r\n"
                             b"Content-Length: 0\r\n\r\n")
        finally:
            client.close()
            server_side.close()


if __name__ == "__main__":
    unittest.main()
