"""Opaque, fixed local panel transport. Import and construction perform no I/O.

The controller owns lifecycle and ADB forward127.0.0.1:19680 -> device18080.
There is no HTTP parsing, destination selection, payload logging or app logic.
"""
from __future__ import annotations

import math
import select
import socket
import threading
import time


class FixedPanelRelay:
    LISTEN_PORT = 8900
    TARGET_PORT = 19680
    MAX_CLIENTS = 3
    MAX_BUFFER = 65536

    def __init__(self):
        self._listen_port = self.LISTEN_PORT
        self._target_port = self.TARGET_PORT
        self._idle_seconds = 30.0
        self._absolute_seconds = 60.0
        self._connect_seconds = 0.75
        self._lock = threading.RLock()
        self._running = False
        self._listener = None
        self._accept_thread = None
        self._workers = set()
        self._sockets = set()
        self._accepted = self._completed = self._rejected = self._upstream_failures = 0
        self._buffer_peak = 0
        self._last_code = "not_started"

    @classmethod
    def _for_test(cls, listen_port, target_port, *, idle_seconds=30.0, absolute_seconds=60.0):
        """Private host-test seam: ports vary, both addresses remain literal loopback."""
        if type(listen_port) is not int or not 0 <= listen_port <= 65535 or type(target_port) is not int or not 1 <= target_port <= 65535:
            raise ValueError("panel_test_ports")
        if any(type(value) not in (int, float) or not math.isfinite(value) or value <= 0
               for value in (idle_seconds, absolute_seconds)):
            raise ValueError("panel_test_deadlines")
        relay = cls()
        relay._listen_port, relay._target_port = listen_port, target_port
        relay._idle_seconds, relay._absolute_seconds = float(idle_seconds), float(absolute_seconds)
        return relay

    def start(self):
        with self._lock:
            if self._running:
                return
            listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            try:
                if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
                    listener.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
                else:
                    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                listener.bind(("127.0.0.1", self._listen_port))
                listener.listen(self.MAX_CLIENTS)
                listener.settimeout(0.25)
            except Exception:
                listener.close()
                self._last_code = "bind_failed"
                raise OSError("panel_relay_bind_failed") from None
            self._listener, self._running = listener, True
            self._last_code = "ready"
            self._accept_thread = threading.Thread(target=self._accept, args=(listener,), name="StationPanelRelay", daemon=True)
            self._accept_thread.start()

    def alive(self):
        with self._lock:
            return bool(self._running and self._listener is not None and self._listener.fileno() >= 0
                        and self._accept_thread is not None and self._accept_thread.is_alive())

    def status(self):
        with self._lock:
            return {"active": self.alive(), "connections": len(self._workers), "accepted": self._accepted,
                    "completed": self._completed, "rejected": self._rejected, "upstreamUnavailable": self._upstream_failures,
                    "bufferPeak": self._buffer_peak, "lastCode": self._last_code}

    def stop(self):
        with self._lock:
            self._running = False
            sockets = list(self._sockets)
            if self._listener is not None:
                sockets.append(self._listener)
            self._listener = None
            threads = list(self._workers)
            if self._accept_thread is not None:
                threads.append(self._accept_thread)
            self._last_code = "stopped"
        for current in sockets:
            self._close(current)
        deadline = time.monotonic() + 2.0
        for current in threads:
            if current is not threading.current_thread():
                current.join(max(0.0, deadline - time.monotonic()))

    def _accept(self, listener):
        while True:
            with self._lock:
                if not self._running or self._listener is not listener:
                    return
            try:
                client, peer = listener.accept()
            except socket.timeout:
                continue
            except OSError:
                with self._lock:
                    if self._running and self._listener is listener:
                        self._running = False
                        self._last_code = "listener_failed"
                return
            with self._lock:
                self._accepted += 1
                if not self._running or self._listener is not listener or peer[0] != "127.0.0.1" or len(self._workers) >= self.MAX_CLIENTS:
                    self._rejected += 1
                    self._close(client)
                    continue
                thread = threading.Thread(target=self._client, args=(client, time.monotonic()), name="StationPanelRelayClient", daemon=True)
                self._sockets.add(client)
                self._workers.add(thread)
                thread.start()

    def _client(self, client, began):
        upstream = None
        code = "connection_closed"
        try:
            try:
                upstream = socket.create_connection(("127.0.0.1", self._target_port), self._connect_seconds)
            except OSError:
                with self._lock:
                    self._upstream_failures += 1
                code = "upstream_unavailable"
                return
            with self._lock:
                if not self._running:
                    return
                self._sockets.add(upstream)
            for current in (client, upstream):
                current.setblocking(False)
            sockets = (client, upstream)
            pending = [bytearray(), bytearray()]  # bytes waiting to be written TO socket0/socket1
            read_closed, write_closed = [False, False], [False, False]
            last_io = time.monotonic()
            while True:
                with self._lock:
                    if not self._running:
                        code = "stopped"
                        return
                now = time.monotonic()
                if now - began >= self._absolute_seconds:
                    code = "connection_deadline"
                    return
                if now - last_io >= self._idle_seconds:
                    code = "idle_deadline"
                    return
                for destination in (0, 1):
                    if read_closed[1 - destination] and not pending[destination] and not write_closed[destination]:
                        try:
                            sockets[destination].shutdown(socket.SHUT_WR)
                        except OSError:
                            pass
                        write_closed[destination] = True
                if all(read_closed) and not any(pending):
                    return
                readers = [sockets[index] for index in (0, 1) if not read_closed[index] and len(pending[1 - index]) < self.MAX_BUFFER]
                writers = [sockets[index] for index in (0, 1) if pending[index]]
                timeout = max(0.001, min(0.25, self._idle_seconds - (now - last_io), self._absolute_seconds - (now - began)))
                readable, writable, _ = select.select(readers, writers, (), timeout)
                for current in readable:
                    source = 0 if current is client else 1
                    destination = 1 - source
                    try:
                        block = current.recv(min(16384, self.MAX_BUFFER - len(pending[destination])))
                    except BlockingIOError:
                        continue
                    if block:
                        pending[destination].extend(block)
                        last_io = time.monotonic()
                        with self._lock:
                            self._buffer_peak = max(self._buffer_peak, len(pending[destination]))
                    else:
                        read_closed[source] = True
                for current in writable:
                    destination = 0 if current is client else 1
                    try:
                        sent = current.send(pending[destination])
                    except BlockingIOError:
                        continue
                    if sent <= 0:
                        raise OSError("panel_write_closed")
                    del pending[destination][:sent]
                    last_io = time.monotonic()
        except (OSError, ValueError):
            code = "connection_failed"
        finally:
            self._close(client)
            self._close(upstream)
            with self._lock:
                self._sockets.discard(client)
                self._sockets.discard(upstream)
                self._workers.discard(threading.current_thread())
                self._completed += 1
                if self._running:
                    self._last_code = code

    @staticmethod
    def _close(current):
        if current is not None:
            try:
                current.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                current.close()
            except OSError:
                pass
