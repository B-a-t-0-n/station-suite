"""Real ephemeral loopback peers only; never bind production panel/ADB ports."""
from pathlib import Path
import os
import contextlib
import importlib.util
import socket
import threading
import time
import unittest
from unittest.mock import patch

SOURCE = Path(__file__).resolve().parents[1] / "panel_relay.py"
SPEC = importlib.util.spec_from_file_location("panel_relay_under_test", SOURCE)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
FixedPanelRelay = MODULE.FixedPanelRelay


class Backend:
    def __init__(self, handler):
        self.handler, self.sockets, self.workers = handler, [], []
        self.lock = threading.Lock()
        self.listener = socket.socket()
        self.listener.bind(("127.0.0.1", 0)); self.listener.listen(8); self.listener.settimeout(.1)
        self.port, self.running = self.listener.getsockname()[1], True
        self.thread = threading.Thread(target=self._accept, daemon=True); self.thread.start()

    def _accept(self):
        while self.running:
            try:
                client, _ = self.listener.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            with self.lock:
                self.sockets.append(client)
            def run(peer=client):
                try:
                    self.handler(peer)
                except OSError:
                    pass
                finally:
                    peer.close()
            worker = threading.Thread(target=run, daemon=True)
            self.workers.append(worker); worker.start()

    def close(self):
        self.running = False; self.listener.close()
        with self.lock:
            for client in self.sockets:
                with contextlib.suppress(OSError): client.shutdown(socket.SHUT_RDWR)
                client.close()
        self.thread.join(1)
        for worker in self.workers:
            worker.join(1)

    def __enter__(self):
        return self

    def __exit__(self, *unused):
        self.close()


def receive_all(client):
    data = bytearray()
    while True:
        block = client.recv(65536)
        if not block:
            return bytes(data)
        data.extend(block)


class PanelRelayTests(unittest.TestCase):
    def relay(self, backend, **timeouts):
        relay = FixedPanelRelay._for_test(0, backend.port, **timeouts)
        relay.start(); self.addCleanup(relay.stop)
        self.assertTrue(relay.alive())
        return relay, relay._listener.getsockname()[1]

    def until(self, predicate, timeout=2):
        deadline = time.monotonic() + timeout
        while not predicate() and time.monotonic() < deadline:
            time.sleep(.005)
        self.assertTrue(predicate(), "bounded condition did not occur")

    def test_import_constructor_and_public_fixed_destination(self):
        with patch.object(socket, "socket", side_effect=AssertionError("network during import")):
            spec = importlib.util.spec_from_file_location("panel_relay_passive", SOURCE)
            module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
            relay = module.FixedPanelRelay()
            self.assertFalse(relay.alive()); self.assertEqual(relay.status()["lastCode"], "not_started")
            relay.stop()
        with self.assertRaises(TypeError):
            FixedPanelRelay(target="outside.invalid")
        relay = FixedPanelRelay()
        self.assertEqual((relay._listen_port, relay._target_port), (8900, 19680))
        self.assertEqual((relay._idle_seconds, relay._absolute_seconds), (30, 60))

    def test_http_bytes_halfclose_and_binary_are_opaque(self):
        request = b"GET /api/tv/status HTTP/1.1\r\nHost: 127.0.0.1:8900\r\nConnection: keep-alive\r\n\r\n"
        response = b"HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\n\x00\xffok"
        received = []
        def handler(peer):
            received.append(receive_all(peer)); peer.sendall(response)
        with Backend(handler) as backend:
            relay, port = self.relay(backend)
            with socket.create_connection(("127.0.0.1", port), 1) as client:
                client.settimeout(2); client.sendall(request); client.shutdown(socket.SHUT_WR)
                self.assertEqual(receive_all(client), response)
            self.until(lambda: relay.status()["connections"] == 0)
            self.assertEqual(received, [request])
            self.assertNotIn("GET", str(relay.status()))

    def test_keepalive_preserves_multiple_messages_no_target_selection(self):
        def echo(peer):
            while True:
                block = peer.recv(1024)
                if not block: return
                peer.sendall(block)
        with Backend(echo) as backend:
            _, port = self.relay(backend)
            with socket.create_connection(("127.0.0.1", port), 1) as client:
                client.settimeout(1)
                for message in (b"CONNECT outside.invalid:443 HTTP/1.1\r\n\r\n", b"\x00\xffsecond-message\n"):
                    client.sendall(message)
                    actual = bytearray()
                    while len(actual) < len(message): actual.extend(client.recv(len(message) - len(actual)))
                    self.assertEqual(bytes(actual), message)

    def test_backpressure_stays_bounded_and_stop_owns_every_peer(self):
        hold = threading.Event()
        def slow(peer):
            hold.wait(2)
            peer.recv(1)
        with Backend(slow) as backend:
            relay, port = self.relay(backend)
            clients = [socket.create_connection(("127.0.0.1", port), 1) for _ in range(3)]
            for client in clients: self.addCleanup(client.close)
            self.until(lambda: relay.status()["connections"] == 3)
            with socket.create_connection(("127.0.0.1", port), 1) as excess:
                excess.settimeout(1); self.assertEqual(excess.recv(1), b"")
            self.until(lambda: relay.status()["rejected"] == 1)
            clients[0].settimeout(.1)
            with contextlib.suppress(socket.timeout, OSError): clients[0].sendall(b"x" * (8 * 1024 * 1024))
            self.assertLessEqual(relay.status()["bufferPeak"], relay.MAX_BUFFER)
            started = time.monotonic(); relay.stop(); hold.set()
            self.assertLess(time.monotonic() - started, 2.1)
            self.assertFalse(relay.alive()); self.assertEqual(relay.status()["connections"], 0)
            self.assertFalse(relay._sockets)
            for client in clients[1:]:
                client.settimeout(1)
                with contextlib.suppress(ConnectionResetError): self.assertEqual(client.recv(1), b"")

    def test_upstream_absent_fails_fast_and_closes_client(self):
        with socket.socket() as unavailable:
            unavailable.bind(("127.0.0.1", 0))
            backend = type("Address", (), {"port": unavailable.getsockname()[1]})()
            relay, port = self.relay(backend)
            with socket.create_connection(("127.0.0.1", port), 1) as client:
                client.settimeout(2); started = time.monotonic()
                self.assertEqual(client.recv(1), b""); self.assertLess(time.monotonic() - started, 1.5)
            self.until(lambda: relay.status()["upstreamUnavailable"] == 1)
            self.assertEqual(relay.status()["lastCode"], "upstream_unavailable")

    def test_idle_and_absolute_deadlines(self):
        def echo(peer):
            while True:
                block = peer.recv(1024)
                if not block: return
                peer.sendall(block)
        with Backend(echo) as backend:
            relay, port = self.relay(backend, idle_seconds=.15, absolute_seconds=1)
            with socket.create_connection(("127.0.0.1", port), 1) as client:
                client.settimeout(1); self.assertEqual(client.recv(1), b"")
            self.until(lambda: relay.status()["lastCode"] == "idle_deadline")
            absolute, port = self.relay(backend, idle_seconds=1, absolute_seconds=.35)
            with socket.create_connection(("127.0.0.1", port), 1) as client:
                client.settimeout(1); started = time.monotonic()
                while True:
                    try:
                        client.sendall(b"x")
                        if not client.recv(1): break
                    except OSError: break
                    time.sleep(.03)
                self.assertLess(time.monotonic() - started, 1)
            self.until(lambda: absolute.status()["lastCode"] == "connection_deadline")

    def test_start_conflict_is_honest_and_can_restart_after_stop(self):
        with Backend(lambda peer: peer.recv(1)) as backend:
            relay, port = self.relay(backend)
            conflict = FixedPanelRelay._for_test(port, backend.port)
            with self.assertRaisesRegex(OSError, "panel_relay_bind_failed"): conflict.start()
            self.assertFalse(conflict.alive()); conflict.stop()
            relay.stop(); relay.start(); self.assertTrue(relay.alive()); relay.stop()


if __name__ == "__main__":
    unittest.main()
