#!/usr/bin/env python3
"""Temporary loopback-only CONNECT transport; TLS stays inside the client.

No credentials, HTTP API requests, TLS parsing, payload logs or third-party
dependencies. Six connection workers plus the accept loop cap threads at seven.
The process closes all sockets after at most ten minutes, including busy tunnels.
"""

from __future__ import annotations

import argparse
import ipaddress
import os
import queue
import re
import selectors
import socket
import threading
import time
from typing import NamedTuple


MUSIC_API_HOSTS = frozenset({"api.vk.com", "muzofond.fm"})
# Exact CDN hosts observed in the authenticated catalog; no suffix wildcard.
MUSIC_AUDIO_HOSTS = frozenset({
    "cs1-40v4.vkuseraudio.net", "cs1-43v4.vkuseraudio.net", "cs1-46v4.vkuseraudio.net",
    "cs1-53v4.vkuseraudio.net", "cs1-58v4.vkuseraudio.net", "cs1-59v4.vkuseraudio.net",
    "cs1-63v4.vkuseraudio.net", "cs1-64v4.vkuseraudio.net", "cs1-65v4.vkuseraudio.net",
    "cs1-66v4.vkuseraudio.net", "cs1-67v4.vkuseraudio.net", "cs1-72v4.vkuseraudio.net",
    "cs1-76v4.vkuseraudio.net", "cs1-77v4.vkuseraudio.net", "cs1-79v4.vkuseraudio.net",
    "cs1-80v4.vkuseraudio.net", "cs1-81v4.vkuseraudio.net", "cs1-82v4.vkuseraudio.net",
    "cs9-10v4.vkuseraudio.net", "cs9-11v4.vkuseraudio.net", "cs9-12v4.vkuseraudio.net",
    "cs9-13v4.vkuseraudio.net", "cs9-14v4.vkuseraudio.net", "cs9-1v4.vkuseraudio.net",
    "cs9-3v4.vkuseraudio.net", "cs9-4v4.vkuseraudio.net", "cs9-5v4.vkuseraudio.net",
    "cs9-6v4.vkuseraudio.net", "cs9-7v4.vkuseraudio.net", "cs9-8v4.vkuseraudio.net",
    "cs9-9v4.vkuseraudio.net", "dl3s4.muzofond.fm", "psv4.vkuseraudio.net",
})
ALLOWLIST = frozenset({("api.giga.chat", 443), ("ngw.devices.sberbank.ru", 9443),
                       ("api.speechify.ai", 443)}) | frozenset(
    (host, 443) for host in MUSIC_API_HOSTS | MUSIC_AUDIO_HOSTS)
BIND_ADDRESS = "127.0.0.1"
DEFAULT_PORT = 18443
MAX_SECONDS = 600
MAX_HEADER_BYTES = 8192
MAX_WORKERS = 6
HEADER_TIMEOUT = 10.0
CONNECT_TIMEOUT = 10.0
IDLE_TIMEOUT = 90.0
MAX_PENDING = 65536
# Windows SDK ws2ipdef.h; unlike IPV6_UNICAST_IF the value is network order.
# https://learn.microsoft.com/en-us/windows/win32/winsock/ipproto-ip-socket-options
IP_UNICAST_IF = 31
_TOKEN = re.compile(r"[!#$%&'*+.^_`|~0-9A-Za-z-]+\Z")
_HOST = re.compile(r"[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+\Z")


class Denied(ValueError):
    """Unacceptable CONNECT request; never include the request in an error."""


class Target(NamedTuple):
    host: str
    port: int


def validate_source_selection(interface_index: int | None,
                              source_ip: str | None) -> tuple[int, str] | None:
    """A pair explicitly requests Windows IPv4 routing; never infer an adapter."""
    if interface_index is None and source_ip is None:
        return None
    if interface_index is None or source_ip is None:
        raise ValueError("interface-index and source-ip must be supplied together")
    if os.name != "nt":
        raise ValueError("Explicit interface selection requires Windows")
    # Winsock represents the interface as a 24-bit index; zero means unspecified.
    if type(interface_index) is not int or not 1 <= interface_index <= 0xFFFFFF:
        raise ValueError("Invalid interface index")
    try:
        address = ipaddress.IPv4Address(source_ip)
    except (ValueError, TypeError):
        raise ValueError("source-ip must be an explicit unicast IPv4 address") from None
    if (address.is_unspecified or address.is_loopback or address.is_multicast
            or address.is_reserved or address.is_link_local
            or int(address) >> 24 == 0):
        raise ValueError("source-ip must be an explicit unicast IPv4 address")
    return interface_index, str(address)


def normalize_authority(authority: str) -> Target:
    """Accept only explicit, allowlisted DNS authorities; never IP literals."""
    if authority.count(":") != 1:
        raise Denied()
    host, port = authority.split(":")
    if not _HOST.fullmatch(host) or not re.fullmatch(r"[0-9]{1,5}", port):
        raise Denied()
    try:
        ipaddress.ip_address(host)
    except ValueError:
        pass
    else:
        raise Denied()
    target = Target(host.lower(), int(port))
    if target not in ALLOWLIST:
        raise Denied()
    return target


def parse_connect(header: bytes) -> Target:
    """Parse one complete header (no body); ambiguous framing is forbidden."""
    if len(header) > MAX_HEADER_BYTES or not header.endswith(b"\r\n\r\n"):
        raise Denied()
    try:
        lines = header[:-4].decode("ascii").split("\r\n")
    except UnicodeDecodeError:
        raise Denied() from None
    request = lines[0].split(" ")
    if len(request) != 3 or request[0] != "CONNECT" or request[2] not in (
        "HTTP/1.1", "HTTP/1.0"
    ):
        raise Denied()
    target = normalize_authority(request[1])
    fields: dict[str, str] = {}
    for line in lines[1:]:
        if ":" not in line:
            raise Denied()
        name, value = line.split(":", 1)
        if not _TOKEN.fullmatch(name) or any(
            ord(c) < 32 and c != "\t" or ord(c) == 127 for c in value
        ):
            raise Denied()
        name = name.lower()
        if name in fields:
            raise Denied()
        fields[name] = value.strip(" \t")
    if "host" in fields:
        if normalize_authority(fields["host"]) != target:
            raise Denied()
    elif request[2] == "HTTP/1.1":
        raise Denied()
    if "transfer-encoding" in fields or fields.get("content-length", "0") != "0":
        raise Denied()
    return target


def read_connect(client: socket.socket, deadline: float) -> tuple[Target, bytes]:
    """Bound the HTTP header, retaining any coalesced tunnel bytes unchanged."""
    data = bytearray()
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError()
        client.settimeout(remaining)
        chunk = client.recv(min(4096, MAX_HEADER_BYTES + 1 - len(data)))
        if not chunk:
            raise Denied()
        data.extend(chunk)
        end = data.find(b"\r\n\r\n")
        if end >= 0:
            end += 4
            return parse_connect(bytes(data[:end])), bytes(data[end:])
        if len(data) >= MAX_HEADER_BYTES:
            raise Denied()


def connect_upstream(target: Target, deadline: float, *,
                     interface_index: int | None = None,
                     source_ip: str | None = None) -> socket.socket:
    """Use the OS DNS resolver, not a proxy, override, DoH or supplied IP."""
    target = normalize_authority(f"{target.host}:{target.port}")
    selection = validate_source_selection(interface_index, source_ip)
    if deadline <= time.monotonic():
        raise TimeoutError()
    # getaddrinfo uses the OS resolver's own timeout. No extra resolver threads:
    # the main process lifetime still closes clients and ends daemon workers.
    resolver_options = {"type": socket.SOCK_STREAM, "proto": socket.IPPROTO_TCP}
    if selection is not None:
        resolver_options["family"] = socket.AF_INET
    addresses = socket.getaddrinfo(target.host, target.port, **resolver_options)
    for family, kind, proto, _, address in addresses:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError()
        # Keep the fixed mode IPv4-only even if a resolver/provider misbehaves.
        if selection is not None and family != socket.AF_INET:
            continue
        # Do not turn a compromised DNS answer into a route to local services.
        if not ipaddress.ip_address(address[0]).is_global:
            continue
        upstream = socket.socket(family, kind, proto)
        try:
            upstream.settimeout(remaining)
            if selection is not None:
                selected_index, selected_ip = selection
                network_index = socket.htonl(selected_index)
                # Python's integer setsockopt argument is a signed C int. Keep
                # the exact DWORD bit pattern for indices whose low byte >=128.
                signed_index = (network_index if network_index < 0x80000000
                                else network_index - 0x100000000)
                upstream.setsockopt(socket.IPPROTO_IP, IP_UNICAST_IF, signed_index)
                upstream.bind((selected_ip, 0))
            upstream.connect(address)
            return upstream
        except OSError:
            upstream.close()
    raise OSError("Upstream unavailable")


def relay(client: socket.socket, upstream: socket.socket, deadline: float,
          initial: bytes = b"", inactivity: float = IDLE_TIMEOUT) -> None:
    """Copy opaque bytes both ways, with bounded queues and proper half-close."""
    peers = {client: upstream, upstream: client}
    pending = {client: bytearray(), upstream: bytearray(initial)}
    readable = {client: True, upstream: True}
    writable = {client: True, upstream: True}
    last_activity = time.monotonic()
    for stream in peers:
        stream.setblocking(False)
    with selectors.DefaultSelector() as selector:
        while True:
            for stream, peer in peers.items():
                if writable[stream] and not readable[peer] and not pending[stream]:
                    try:
                        stream.shutdown(socket.SHUT_WR)
                    except OSError:
                        pass
                    writable[stream] = False
                mask = 0
                if readable[stream] and len(pending[peer]) < MAX_PENDING:
                    mask |= selectors.EVENT_READ
                if writable[stream] and pending[stream]:
                    mask |= selectors.EVENT_WRITE
                try:
                    selector.get_key(stream)
                except KeyError:
                    if mask:
                        selector.register(stream, mask)
                else:
                    if mask:
                        selector.modify(stream, mask)
                    else:
                        selector.unregister(stream)
            if not any(readable.values()) and not any(pending.values()):
                return
            wait = min(deadline, last_activity + inactivity) - time.monotonic()
            if wait <= 0:
                return
            for key, mask in selector.select(wait):
                stream = key.fileobj
                peer = peers[stream]
                if mask & selectors.EVENT_READ:
                    try:
                        chunk = stream.recv(min(16384, MAX_PENDING - len(pending[peer])))
                    except BlockingIOError:
                        chunk = None
                    if chunk == b"":
                        readable[stream] = False
                    elif chunk:
                        pending[peer].extend(chunk)
                        last_activity = time.monotonic()
                if mask & selectors.EVENT_WRITE:
                    try:
                        count = stream.send(pending[stream])
                    except BlockingIOError:
                        continue
                    if count == 0:
                        return
                    del pending[stream][:count]
                    last_activity = time.monotonic()


def _reply(client: socket.socket, status: str, deadline: float) -> None:
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        return
    client.settimeout(min(1.0, remaining))
    ending = "\r\n" if status.startswith("200 ") else "Connection: close\r\nContent-Length: 0\r\n\r\n"
    client.sendall(f"HTTP/1.1 {status}\r\n{ending}".encode("ascii"))


class TunnelServer:
    def __init__(self, port: int = DEFAULT_PORT, max_seconds: int = MAX_SECONDS, *,
                 interface_index: int | None = None, source_ip: str | None = None):
        if not 1 <= port <= 65535 or not 1 <= max_seconds <= MAX_SECONDS:
            raise ValueError("Invalid port or lifetime")
        self.port = port
        selection = validate_source_selection(interface_index, source_ip)
        self.interface_index = selection[0] if selection is not None else None
        self.source_ip = selection[1] if selection is not None else None
        self.deadline = time.monotonic() + max_seconds
        self.slots = threading.BoundedSemaphore(MAX_WORKERS)
        self.queue: queue.Queue[socket.socket] = queue.Queue(MAX_WORKERS)
        self.lock = threading.Lock()
        self.sockets: set[socket.socket] = set()
        self.stopping = False

    def _track(self, stream: socket.socket) -> None:
        with self.lock:
            if self.stopping or time.monotonic() >= self.deadline:
                stream.close()
                raise TimeoutError()
            self.sockets.add(stream)

    def _handle(self, client: socket.socket) -> None:
        upstream = None
        established = False
        try:
            target, initial = read_connect(client, min(self.deadline,
                                                      time.monotonic() + HEADER_TIMEOUT))
            upstream = connect_upstream(target, min(self.deadline,
                                                    time.monotonic() + CONNECT_TIMEOUT),
                                        interface_index=self.interface_index,
                                        source_ip=self.source_ip)
            self._track(upstream)
            _reply(client, "200 Connection Established", self.deadline)
            established = True
            relay(client, upstream, self.deadline, initial)
        except Denied:
            try:
                _reply(client, "403 Forbidden", self.deadline)
            except OSError:
                pass
        except (OSError, ValueError):
            if not established:
                try:
                    _reply(client, "502 Bad Gateway", self.deadline)
                except OSError:
                    pass
        finally:
            for stream in (client, upstream):
                if stream is not None:
                    with self.lock:
                        self.sockets.discard(stream)
                    stream.close()
            self.slots.release()

    def _worker(self) -> None:
        while not self.stopping:
            remaining = self.deadline - time.monotonic()
            if remaining <= 0:
                return
            try:
                client = self.queue.get(timeout=min(0.5, remaining))
            except queue.Empty:
                continue
            try:
                self._handle(client)
            finally:
                self.queue.task_done()

    def run(self) -> None:
        """Only the CLI calls this; importing/testing never opens a listener."""
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
                if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
                    listener.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
                listener.bind((BIND_ADDRESS, self.port))
                listener.listen(MAX_WORKERS)
                for _ in range(MAX_WORKERS):
                    threading.Thread(target=self._worker, daemon=True,
                                     name="giga-connect").start()
                print(f"CONNECT transport on {BIND_ADDRESS}:{self.port}; expires within 600s",
                      flush=True)
                while (remaining := self.deadline - time.monotonic()) > 0:
                    listener.settimeout(min(0.5, remaining))
                    try:
                        client, _ = listener.accept()
                    except socket.timeout:
                        continue
                    if not self.slots.acquire(blocking=False):
                        try:
                            _reply(client, "503 Service Unavailable", self.deadline)
                        except OSError:
                            pass
                        finally:
                            client.close()
                        continue
                    try:
                        self._track(client)
                        self.queue.put_nowait(client)
                    except (OSError, RuntimeError):
                        client.close()
                        with self.lock:
                            self.sockets.discard(client)
                        self.slots.release()
        finally:
            with self.lock:
                self.stopping = True
                for stream in self.sockets:
                    try:
                        stream.shutdown(socket.SHUT_RDWR)
                    except OSError:
                        pass
                    stream.close()
                self.sockets.clear()


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--max-seconds", type=int, default=MAX_SECONDS)
    parser.add_argument("--interface-index", type=int,
                        help="Explicit outgoing Windows IPv4 interface index (requires --source-ip)")
    parser.add_argument("--source-ip",
                        help="Explicit outgoing IPv4 source (requires --interface-index)")
    args = parser.parse_args(argv)
    if not 1 <= args.port <= 65535 or not 1 <= args.max_seconds <= MAX_SECONDS:
        parser.error("port must be 1..65535; max-seconds must be 1..600")
    try:
        validate_source_selection(args.interface_index, args.source_ip)
    except ValueError as error:
        parser.error(str(error))
    try:
        TunnelServer(args.port, args.max_seconds, interface_index=args.interface_index,
                     source_ip=args.source_ip).run()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
