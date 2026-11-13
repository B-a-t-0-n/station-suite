"""Isolated, experimental Station configuration responder; Python 3 stdlib only.

No OTA files, real registration, account credentials, or general proxy. An optional
policy permits an opaque TLS relay only to the fixed public www.google.com:443.
This module does not open sockets when imported. See LOCAL-CONFIG-SERVER.md.
"""
from __future__ import annotations

import argparse
import datetime
from dataclasses import dataclass
import importlib.util
import json
import ipaddress
import os
from pathlib import Path
import re
import select
import signal
import socket
import ssl
import struct
import threading
import time


# Resolve the sibling explicitly: the launcher uses Python isolated mode (-I).
_display_spec = importlib.util.spec_from_file_location(
    "station_display_overlay", Path(__file__).with_name("StationDisplayOverlay.py"))
if _display_spec is None or _display_spec.loader is None:
    raise ImportError("display overlay module unavailable")
_display_module = importlib.util.module_from_spec(_display_spec)
_display_spec.loader.exec_module(_display_module)
apply_display_overlay = _display_module.apply_overlay


BIND_IP = os.environ.get("STATION_LAB_IP", "192.168.77.1")
VOICE_IP = os.environ.get("STATION_VOICE_IP", "192.168.77.2")
CLIENT_IP = os.environ.get("STATION_IP", "192.168.77.100")
LBS_HOST = "api.lbs.yandex.net"
PUSH_HOST = "station-config.quasar.yandex.net"
PUSH_RELAY_SECONDS = 600.0
DNS_ANSWER_OVERRIDES = {"uniproxy.alice.yandex.net": VOICE_IP}
HOSTS = frozenset(("quasar.yandex.net", "quasar.yandex.ru", "scbh.yandex.net"))
OPTIONAL_HTTPS_HOSTS = frozenset(("startup.mobile.yandex.net", "uniproxy.alice.yandex.net", "clck.yandex.net", LBS_HOST))
OPTIONAL_HTTP_HOSTS = frozenset(("connectivitycheck.gstatic.com", "www.google.com"))
NTP_HOSTS = frozenset(("0.ru.pool.ntp.org", "1.ru.pool.ntp.org", "2.ru.pool.ntp.org",
                       "3.ru.pool.ntp.org", "2.android.pool.ntp.org"))
PATHS = frozenset(("/generate_204", "/check_updates", "/get_sync_info", "/get_user_details",
                    "/glagol/device_list", "/analytics/startup", "/get_timezone", "/push_subscribe"))
SHAPE_WORDS = frozenset(("v1", "v2", "api", "quasar", "register", "device", "get_sync_info",
                         "updates", "check_updates", "generate_204", "gen_204", "startup"))
SYNC_INFO = {
    "status": "ok",
    "config": {
        "account_config": {"spotter": "alisa"},
        "device_config": {"name": "quasar"},
        "system_config": {
            "adbd": {"remoteActivation": True, "network": True},
            # Candidate only: contrib/patch_max_adb_candidate.py, commit
            # 78bae04cfbc124779e9b5a3a3b9075b7487dbc2d. Not verified on 20221212.
            "com.yandex.tv.services.platform": {"adbEnabled": True, "developmentSettingsEnabled": True},
        },
    },
}
REGISTRATION_STUB = {"message": "allvalid", "status": "ok", "hasCriticalUpdate": False}
OAUTH_HOST = "oauth.yandex.ru"
# Explicitly fictional local tokens: never derived from a code, account, or body.
OAUTH_STUB = {"access_token": "lab-only-no-real-account-access-token",
              "token_type": "bearer", "expires_in": 3600,
              "refresh_token": "lab-only-no-real-account-refresh-token", "uid": 1,
              "x_token": "lab-only-no-real-x-token",
              "xtoken": "lab-only-no-real-x-token",
              "is_stub": True}
CONNECTION_SECONDS = 6.0
MAX_DNS_MESSAGE = 4096
MAX_NTP_MESSAGE = 512
NTP_EPOCH_OFFSET = 2208988800
MAX_HTTP_HEAD = 16384
MAX_STUB_BODY = 65536
MAX_POLICY_BYTES = 8192
MAX_SYNC_PAYLOAD_BYTES = 256 * 1024
GOOGLE_HOST = "www.google.com"
GOOGLE_PORT = 443
GOOGLE_TUNNEL_SECONDS = 15.0
# Both hosts use the PC's internet via an opaque TLS tunnel. LBS is needed by
# the native Quasar app for its location/internet check before it opens voice.
TUNNEL_HOSTS = frozenset((GOOGLE_HOST, LBS_HOST))
MAX_TLS_RECORDS = 8
MAX_CLIENT_HELLO = 16384
MAX_CLIENT_HELLO_PEEK = MAX_CLIENT_HELLO + 5 * MAX_TLS_RECORDS
MAX_RELAY_BUFFER = 65536


@dataclass(frozen=True)
class Policy:
    check_updates_payload: dict
    extra_hosts: frozenset = frozenset()
    http_connectivity_hosts: frozenset = frozenset()
    google_https_passthrough: bool = False
    local_registration_stub: bool = False
    local_oauth_stub: bool = False
    local_ntp_server: bool = False
    local_sync_payload: bool = False

    @property
    def https_hosts(self):
        return HOSTS | frozenset((PUSH_HOST,)) | self.extra_hosts | (frozenset((OAUTH_HOST,)) if self.local_oauth_stub else frozenset())

    @property
    def dns_hosts(self):
        return self.https_hosts | self.connectivity_hosts | (NTP_HOSTS if self.local_ntp_server else frozenset())

    @property
    def connectivity_hosts(self):
        return self.http_connectivity_hosts | (frozenset((GOOGLE_HOST,)) if self.google_https_passthrough else frozenset())


BASELINE_POLICY = Policy({"hasUpdate": False})


def validate_policy(value) -> Policy:
    """Only fixed false-update fields and preselected hosts can be changed."""
    if type(value) is not dict or set(value) - {"check_updates_payload", "extra_hosts", "http_connectivity_hosts", "google_https_passthrough", "local_registration_stub", "local_oauth_stub", "local_ntp_server", "local_sync_payload"}:
        raise ValueError("invalid policy")
    google_enabled = value.get("google_https_passthrough", False)
    if type(google_enabled) is not bool:
        raise ValueError("invalid policy")
    registration_enabled = value.get("local_registration_stub", False)
    if type(registration_enabled) is not bool:
        raise ValueError("invalid policy")
    oauth_enabled = value.get("local_oauth_stub", False)
    if type(oauth_enabled) is not bool:
        raise ValueError("invalid policy")
    ntp_enabled = value.get("local_ntp_server", False)
    if type(ntp_enabled) is not bool:
        raise ValueError("invalid policy")
    sync_enabled = value.get("local_sync_payload", False)
    if type(sync_enabled) is not bool:
        raise ValueError("invalid policy")
    payload = value.get("check_updates_payload", {})
    if type(payload) is not dict or set(payload) - {"status", "hasUpdate", "hasCriticalUpdate"}:
        raise ValueError("invalid policy")
    if ("status" in payload and payload["status"] != "ok") or any(
        key in payload and payload[key] is not False for key in ("hasUpdate", "hasCriticalUpdate")
    ):
        raise ValueError("invalid policy")
    selections = []
    for key, allowed in (("extra_hosts", OPTIONAL_HTTPS_HOSTS), ("http_connectivity_hosts", OPTIONAL_HTTP_HOSTS)):
        hosts = value.get(key, [])
        if type(hosts) is not list or any(type(host) is not str or host not in allowed for host in hosts):
            raise ValueError("invalid policy")
        if len(set(hosts)) != len(hosts):
            raise ValueError("invalid policy")
        selections.append(frozenset(hosts))
    # hasUpdate:false remains mandatory in the effective response even if the
    # policy supplies only an additional status/hasCriticalUpdate field.
    return Policy({"hasUpdate": False, **payload}, selections[0], selections[1], google_enabled, registration_enabled, oauth_enabled, ntp_enabled, sync_enabled)


def reject_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("invalid policy")
        result[key] = value
    return result


def load_policy(path: Path | None) -> tuple[Policy, str | None]:
    """Read a small local JSON snapshot. Failure restores the complete baseline."""
    if path is None:
        return BASELINE_POLICY, None
    try:
        with path.open("rb") as source:
            data = source.read(MAX_POLICY_BYTES + 1)
        if len(data) > MAX_POLICY_BYTES:
            raise ValueError("invalid policy")
        value = json.loads(data.decode("utf-8-sig"), object_pairs_hook=reject_duplicate_keys)
        return validate_policy(value), None
    except (OSError, ValueError, UnicodeError, RecursionError):
        return BASELINE_POLICY, "invalid_or_unreadable_policy"


def reject_json_constant(value):
    raise ValueError("invalid JSON constant")


def validate_sync_payload(value) -> dict:
    """Validate the fixed-file experiment envelope, without reading account data."""
    if type(value) is not dict or value.get("status") != "ok" or type(value.get("config")) is not dict:
        raise ValueError("invalid sync payload")
    return value


def load_sync_payload(path: Path | None) -> tuple[dict | None, str | None]:
    """Bounded data-only JSON loader. Never imports, executes or logs its values."""
    try:
        if path is None:
            raise ValueError("missing sync payload")
        with path.open("rb") as source:
            data = source.read(MAX_SYNC_PAYLOAD_BYTES + 1)
        if len(data) > MAX_SYNC_PAYLOAD_BYTES:
            raise ValueError("sync payload bound")
        value = json.loads(data.decode("utf-8-sig"), object_pairs_hook=reject_duplicate_keys,
                           parse_constant=reject_json_constant)
        return validate_sync_payload(value), None
    except (OSError, ValueError, UnicodeError, RecursionError):
        return None, "invalid_or_unreadable_payload"


def path_shape(path: str) -> str:
    """Keep only exact vocabulary words; never log arbitrary path segments."""
    path = path.split("?", 1)[0].split("#", 1)[0]
    segments = path.strip("/").split("/") if path.strip("/") else []
    safe = [segment if segment in SHAPE_WORDS else "<redacted>" for segment in segments[:12]]
    if len(segments) > 12:
        safe.append("<truncated>")
    return "/" + "/".join(safe)


class IncompleteClientHello(Exception):
    pass


class ClientHelloError(ValueError):
    pass


def parse_client_hello_sni(data: bytes) -> str | None:
    """Bounded TLS-record/ClientHello parser; never reads or decrypts application data."""
    if len(data) > MAX_CLIENT_HELLO_PEEK:
        raise ClientHelloError("ClientHello bound")
    handshake = bytearray()
    position = 0
    body = None
    for _ in range(MAX_TLS_RECORDS):
        if position + 5 > len(data):
            raise IncompleteClientHello()
        if data[position] != 22 or data[position + 1] != 3 or data[position + 2] > 4:
            raise ClientHelloError("not a TLS handshake record")
        size = int.from_bytes(data[position + 3:position + 5], "big")
        if not 1 <= size <= MAX_CLIENT_HELLO:
            raise ClientHelloError("TLS record bound")
        if position + 5 + size > len(data):
            raise IncompleteClientHello()
        handshake.extend(data[position + 5:position + 5 + size])
        position += 5 + size
        if len(handshake) < 4:
            continue
        declared = int.from_bytes(handshake[1:4], "big")
        if handshake[0] != 1 or declared > MAX_CLIENT_HELLO - 4:
            raise ClientHelloError("ClientHello bound or type")
        if len(handshake) >= declared + 4:
            body = bytes(handshake[4:declared + 4])
            break
    if body is None:
        raise ClientHelloError("TLS record count bound")
    if len(body) < 35:
        raise ClientHelloError("short ClientHello")
    offset = 34  # legacy_version and random
    session_length = body[offset]
    offset += 1
    if session_length > 32 or offset + session_length + 2 > len(body):
        raise ClientHelloError("invalid session length")
    offset += session_length
    cipher_length = int.from_bytes(body[offset:offset + 2], "big")
    offset += 2
    if cipher_length < 2 or cipher_length % 2 or offset + cipher_length + 1 > len(body):
        raise ClientHelloError("invalid cipher length")
    offset += cipher_length
    compression_length = body[offset]
    offset += 1
    if not compression_length or offset + compression_length > len(body):
        raise ClientHelloError("invalid compression length")
    offset += compression_length
    if offset == len(body):
        return None
    if offset + 2 > len(body) or int.from_bytes(body[offset:offset + 2], "big") != len(body) - offset - 2:
        raise ClientHelloError("invalid extensions length")
    offset += 2
    sni = None
    seen_sni = False
    while offset < len(body):
        if offset + 4 > len(body):
            raise ClientHelloError("short extension")
        extension_type, length = struct.unpack("!HH", body[offset:offset + 4])
        offset += 4
        if offset + length > len(body):
            raise ClientHelloError("invalid extension length")
        extension = body[offset:offset + length]
        offset += length
        if extension_type != 0:
            continue
        if seen_sni or len(extension) < 2 or int.from_bytes(extension[:2], "big") != len(extension) - 2:
            raise ClientHelloError("invalid SNI extension")
        seen_sni = True
        index = 2
        while index < len(extension):
            if index + 3 > len(extension):
                raise ClientHelloError("short SNI entry")
            name_type = extension[index]
            name_length = int.from_bytes(extension[index + 1:index + 3], "big")
            index += 3
            if not name_length or index + name_length > len(extension):
                raise ClientHelloError("invalid SNI length")
            name_bytes = extension[index:index + name_length]
            index += name_length
            if name_type == 0:
                if sni is not None:
                    raise ClientHelloError("duplicate SNI host")
                try:
                    sni = name_bytes.decode("ascii").lower()
                except UnicodeError:
                    raise ClientHelloError("non-ASCII SNI") from None
                if not re.fullmatch(r"[a-z0-9.-]{1,253}", sni):
                    raise ClientHelloError("invalid SNI host")
    return sni


def peek_client_hello_sni(connection: socket.socket, deadline: float) -> str | None:
    """MSG_PEEK retains every original byte for either OpenSSL or the opaque relay."""
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("ClientHello deadline")
        connection.settimeout(remaining)
        data = connection.recv(MAX_CLIENT_HELLO_PEEK, socket.MSG_PEEK)
        if not data:
            raise EOFError("connection closed")
        try:
            return parse_client_hello_sni(data)
        except IncompleteClientHello:
            if len(data) >= MAX_CLIENT_HELLO_PEEK:
                raise ClientHelloError("ClientHello peek bound") from None
            # A partial record remains readable under MSG_PEEK, so select alone
            # would spin. This bounded pause waits for additional TCP fragments.
            time.sleep(min(0.01, remaining))


def public_google_addresses(records) -> list[tuple[int, tuple]]:
    addresses = []
    for family, kind, protocol, _, address in records:
        if family not in (socket.AF_INET, socket.AF_INET6) or kind != socket.SOCK_STREAM or protocol not in (0, socket.IPPROTO_TCP):
            continue
        try:
            ip = ipaddress.ip_address(address[0])
        except ValueError:
            continue
        if (address[1] != GOOGLE_PORT or "%" in address[0] or not ip.is_global or ip.is_multicast or
                ip.is_reserved or ip.is_loopback or ip.is_link_local or ip.is_unspecified):
            continue
        if (family == socket.AF_INET) != (ip.version == 4):
            continue
        destination = (str(ip), GOOGLE_PORT) if family == socket.AF_INET else (str(ip), GOOGLE_PORT, 0, 0)
        item = (family, destination)
        if item not in addresses:
            addresses.append(item)
        if len(addresses) >= 32:
            break
    return sorted(addresses, key=lambda item: item[0] != socket.AF_INET)[:8]


class GoogleResolver:
    """At most one daemon OS-resolver job; no resolver work before explicit use."""
    def __init__(self):
        self.lock = threading.Lock()
        self.job = None

    def resolve(self, deadline: float) -> list[tuple[int, tuple]]:
        with self.lock:
            if self.job is None or (self.job["done"].is_set() and time.monotonic() >= self.job["expires"]):
                job = {"done": threading.Event(), "addresses": [], "expires": 0.0}
                self.job = job
                def lookup():
                    try:
                        records = socket.getaddrinfo(GOOGLE_HOST, GOOGLE_PORT, socket.AF_UNSPEC,
                                                     socket.SOCK_STREAM, socket.IPPROTO_TCP)
                        addresses = public_google_addresses(records)
                    except (OSError, ValueError):
                        addresses = []
                    with self.lock:
                        job["addresses"] = addresses
                        job["expires"] = time.monotonic() + (30 if addresses else 3)
                        job["done"].set()
                threading.Thread(target=lookup, name="fixed-google-resolver", daemon=True).start()
            job = self.job
        if not job["done"].wait(max(0.0, min(3.0, deadline - time.monotonic()))):
            raise TimeoutError("fixed Google resolver deadline")
        with self.lock:
            addresses = list(job["addresses"])
        if not addresses:
            raise OSError("no public Google address")
        return addresses


class PublicHostResolver:
    """Cached OS-resolver job for a fixed public host (LBS)."""
    def __init__(self, host: str):
        self.host = host
        self.lock = threading.Lock()
        self.job = None

    def resolve(self, deadline: float) -> list[tuple[int, tuple]]:
        with self.lock:
            if self.job is None or (self.job["done"].is_set() and time.monotonic() >= self.job["expires"]):
                job = {"done": threading.Event(), "addresses": [], "expires": 0.0}
                self.job = job
                def lookup():
                    try:
                        records = socket.getaddrinfo(self.host, GOOGLE_PORT, socket.AF_UNSPEC,
                                                     socket.SOCK_STREAM, socket.IPPROTO_TCP)
                        addresses = public_google_addresses(records)
                    except (OSError, ValueError):
                        addresses = []
                    with self.lock:
                        job["addresses"] = addresses
                        job["expires"] = time.monotonic() + (30 if addresses else 3)
                        job["done"].set()
                threading.Thread(target=lookup, name="fixed-public-resolver", daemon=True).start()
            job = self.job
        if not job["done"].wait(max(0.0, min(3.0, deadline - time.monotonic()))):
            raise TimeoutError("fixed public resolver deadline")
        with self.lock:
            addresses = list(job["addresses"])
        if not addresses:
            raise OSError("no public address for " + self.host)
        return addresses


def open_public_upstream(resolver: PublicHostResolver, deadline: float) -> socket.socket:
    """Connect only to resolved public addresses for the fixed host/443."""
    addresses = resolver.resolve(deadline)
    connect_deadline = min(deadline, time.monotonic() + 4.0)
    for family, address in addresses:
        if not public_google_addresses([(family, socket.SOCK_STREAM, socket.IPPROTO_TCP, "", address)]):
            continue
        remaining = connect_deadline - time.monotonic()
        if remaining <= 0:
            break
        endpoint = socket.socket(family, socket.SOCK_STREAM, socket.IPPROTO_TCP)
        try:
            endpoint.settimeout(min(2.0, remaining))
            endpoint.connect(address)
            return endpoint
        except OSError:
            endpoint.close()
    raise OSError("fixed public connection failed")


def open_google_upstream(resolver: GoogleResolver, deadline: float) -> socket.socket:
    """No destination argument: only public results for the fixed Google name/443."""
    addresses = resolver.resolve(deadline)
    connect_deadline = min(deadline, time.monotonic() + 4.0)
    for family, address in addresses:
        if not public_google_addresses([(family, socket.SOCK_STREAM, socket.IPPROTO_TCP, "", address)]):
            continue
        remaining = connect_deadline - time.monotonic()
        if remaining <= 0:
            break
        endpoint = socket.socket(family, socket.SOCK_STREAM, socket.IPPROTO_TCP)
        try:
            endpoint.settimeout(min(2.0, remaining))
            endpoint.connect(address)
            return endpoint
        except OSError:
            endpoint.close()
    raise OSError("fixed Google connection failed")


def relay_tls(client: socket.socket, upstream: socket.socket, deadline: float,
              stop: threading.Event) -> tuple[str, int, int]:
    """Nonblocking bounded buffers provide backpressure; payload bytes are opaque."""
    client.setblocking(False)
    upstream.setblocking(False)
    to_upstream, to_client = bytearray(), bytearray()
    client_eof = upstream_eof = client_shutdown = upstream_shutdown = False
    sent_upstream = sent_client = 0
    while not stop.is_set():
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return "timeout", sent_upstream, sent_client
        reads = ([] if client_eof or len(to_upstream) >= MAX_RELAY_BUFFER else [client])
        reads += ([] if upstream_eof or len(to_client) >= MAX_RELAY_BUFFER else [upstream])
        writes = ([upstream] if to_upstream else []) + ([client] if to_client else [])
        try:
            readable, writable, _ = select.select(reads, writes, [], min(0.25, remaining))
        except (OSError, ValueError):
            return "relay_failed", sent_upstream, sent_client
        for endpoint, buffer in ((client, to_upstream), (upstream, to_client)):
            if endpoint in readable:
                try:
                    data = endpoint.recv(min(16384, MAX_RELAY_BUFFER - len(buffer)))
                except BlockingIOError:
                    continue
                except OSError:
                    return "relay_failed", sent_upstream, sent_client
                if data:
                    buffer.extend(data)
                elif endpoint is client:
                    client_eof = True
                else:
                    upstream_eof = True
        for endpoint, buffer in ((upstream, to_upstream), (client, to_client)):
            if endpoint in writable:
                try:
                    count = endpoint.send(buffer)
                except BlockingIOError:
                    continue
                except OSError:
                    return "relay_failed", sent_upstream, sent_client
                if count <= 0:
                    return "relay_failed", sent_upstream, sent_client
                del buffer[:count]
                if endpoint is upstream:
                    sent_upstream += count
                else:
                    sent_client += count
        if client_eof and not to_upstream and not upstream_shutdown:
            try:
                upstream.shutdown(socket.SHUT_WR)
            except OSError:
                return "relay_failed", sent_upstream, sent_client
            upstream_shutdown = True
        if upstream_eof and not to_client and not client_shutdown:
            try:
                client.shutdown(socket.SHUT_WR)
            except OSError:
                return "relay_failed", sent_upstream, sent_client
            client_shutdown = True
        if client_eof and upstream_eof and not to_client and not to_upstream:
            return "closed", sent_upstream, sent_client
    return "stopped", sent_upstream, sent_client


def utc_now() -> str:
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def parse_dns_question(data: bytes) -> tuple[int, int, str, int, int, bytes]:
    """Parse one ordinary, uncompressed DNS question; no external lookups."""
    if len(data) < 12:
        raise ValueError("short DNS header")
    identifier, flags, questions, answers, authorities, _ = struct.unpack("!6H", data[:12])
    if flags & 0xF800 or questions != 1 or answers or authorities:
        raise ValueError("unsupported DNS query header")
    labels = []
    offset = 12
    while True:
        if offset >= len(data):
            raise ValueError("truncated DNS name")
        length = data[offset]
        offset += 1
        if length == 0:
            break
        # Compression in a query is deliberately rejected: copied pointers might
        # otherwise refer to bytes not present in our minimal answer.
        if length > 63 or offset + length > len(data):
            raise ValueError("invalid DNS label")
        label = data[offset:offset + length].decode("ascii")
        if not re.fullmatch(r"[A-Za-z0-9_-]+", label):
            raise ValueError("unsupported DNS label")
        labels.append(label.lower())
        offset += length
        if offset - 12 > 254:
            raise ValueError("DNS name too long")
    if offset + 4 > len(data):
        raise ValueError("truncated DNS question")
    qtype, qclass = struct.unpack("!HH", data[offset:offset + 4])
    return identifier, flags & 0x0100, ".".join(labels) or ".", qtype, qclass, data[12:offset + 4]


def build_dns_response(data: bytes, policy: Policy = BASELINE_POLICY) -> tuple[bytes, dict]:
    """Return response and safe metadata. AAAA for known names is NOERROR/NODATA."""
    try:
        identifier, rd, name, qtype, qclass, question = parse_dns_question(data)
    except (ValueError, UnicodeError, struct.error):
        identifier = struct.unpack("!H", data[:2])[0] if len(data) >= 2 else 0
        return struct.pack("!6H", identifier, 0x8001, 0, 0, 0, 0), {
            "name": "<invalid_dns>", "qtype": 0, "result": "FORMERR"
        }
    known = name in policy.dns_hosts and qclass == 1
    answer = b""
    if known and qtype == 1:
        answer_ip = DNS_ANSWER_OVERRIDES.get(name.lower(), BIND_IP)
        answer = struct.pack("!HHHIH", 0xC00C, 1, 1, 30, 4) + socket.inet_aton(answer_ip)
        result = "A"
    else:
        result = "NODATA" if known else "NXDOMAIN"
    flags = 0x8400 | rd | (0 if known else 3)  # QR, AA, original RD; no recursion available.
    header = struct.pack("!6H", identifier, flags, 1, int(bool(answer)), 0, 0)
    return header + question + answer, {"name": name, "qtype": qtype, "result": result}


def ntp_timestamp(unix_seconds: float) -> bytes:
    """Encode the standard 32-bit seconds/fraction timestamp, without a lookup."""
    seconds = int(unix_seconds)
    fraction = int((unix_seconds - seconds) * (1 << 32))
    return struct.pack("!II", (seconds + NTP_EPOCH_OFFSET) & 0xFFFFFFFF, fraction)


def build_ntp_response(data: bytes, received_at: float, transmitted_at: float,
                       policy: Policy = BASELINE_POLICY) -> tuple[bytes | None, dict]:
    """Small local SNTP responder using only the caller's PC wall clock.

    RFC5905 header; Android9 SntpClient accepts server mode, stratum1..15,
    LI!=3 and a nonzero transmit timestamp. No GPS/external synchronization is
    claimed: stratum10, reference label LOCL and 1-second root dispersion.
    """
    if not policy.local_ntp_server:
        return None, {"result": "disabled"}
    if not 48 <= len(data) <= MAX_NTP_MESSAGE:
        return None, {"result": "ignored_length"}
    version, mode = (data[0] >> 3) & 7, data[0] & 7
    if version not in (3, 4) or mode != 3:
        return None, {"result": "ignored_mode_version"}
    try:
        reference = ntp_timestamp(received_at - 1.0)
        receive = ntp_timestamp(received_at)
        transmit = ntp_timestamp(max(received_at, transmitted_at))
    except (ValueError, OverflowError, struct.error):
        return None, {"result": "invalid_clock"}
    if any(value == b"\0" * 8 for value in (reference, receive, transmit)):
        return None, {"result": "invalid_clock"}
    # LI=0, same NTP version, server mode4; poll64s, precision approximately16ms.
    header = struct.pack("!BBBbII4s", (version << 3) | 4, 10, 6, -6, 0, 1 << 16, b"LOCL")
    return header + reference + data[40:48] + receive + transmit, {
        "result": "reply", "version": version, "stratum": 10
    }


def parse_http_head(data: bytes) -> tuple[str, str, str]:
    """Inspect request line and Host only. Other header values are discarded."""
    if len(data) > MAX_HTTP_HEAD or not data.endswith(b"\r\n\r\n"):
        raise ValueError("invalid HTTP head")
    lines = data[:-4].split(b"\r\n")
    fields = lines[0].split(b" ")
    if len(fields) != 3 or fields[2] not in (b"HTTP/1.0", b"HTTP/1.1"):
        raise ValueError("invalid request line")
    method = fields[0].decode("ascii")
    if not re.fullmatch(r"[A-Z]{1,16}", method):
        raise ValueError("invalid method")
    target = fields[1]
    if not target.startswith(b"/") or target.startswith(b"//"):
        raise ValueError("origin-form target required")
    path = target.split(b"?", 1)[0].split(b"#", 1)[0].decode("ascii")
    if any(ord(char) < 0x21 or ord(char) > 0x7E for char in path):
        raise ValueError("invalid path")
    host = None
    for line in lines[1:]:
        if not line or b":" not in line or line[:1] in (b" ", b"\t"):
            raise ValueError("invalid header framing")
        if line[:5].lower() == b"host:":
            if host is not None:
                raise ValueError("duplicate Host")
            host = line[5:].strip().decode("ascii").lower()
    if host is None:
        raise ValueError("Host required")
    return method, path, host


def normalize_host(host: str, port: int, policy: Policy = BASELINE_POLICY) -> str:
    parts = host.split(":")
    if len(parts) > 2 or (len(parts) == 2 and parts[1] != str(port)):
        return ""
    name = parts[0].rstrip(".")
    allowed = policy.https_hosts if port == 443 else (policy.https_hosts | policy.connectivity_hosts) if port == 80 else frozenset()
    return name if name in allowed else ""


def build_http_response(method: str, path: str, host: str, port: int,
                        sni: str | None = None, policy: Policy = BASELINE_POLICY,
                        sync_payload: dict | None = None) -> tuple[bytes, dict]:
    """Pure response builder; unknown paths are never placed in event metadata."""
    name = normalize_host(host, port, policy)
    body = b""
    registration_stub = False
    oauth_stub = False
    google_fallback = name == GOOGLE_HOST and port == 80 and path == "/gen_204"
    if not name or (port == 443 and (sni not in policy.https_hosts or sni != name)):
        status = 421
    elif name == OAUTH_HOST:
        # This name never serves Quasar paths or HTTP OAuth, even if other
        # policy switches are enabled. There is no generic OAuth forwarding.
        if port != 443 or path != "/token":
            status = 404
        elif method != "POST":
            status = 405
        else:
            status = 200
            oauth_stub = True
            body = json.dumps(OAUTH_STUB, separators=(",", ":")).encode("ascii")
    elif google_fallback:
        status = 204 if method in ("GET", "HEAD") else 405
    elif name in policy.connectivity_hosts and (port != 80 or path != "/generate_204"):
        status = 404
    elif path == "/register" and policy.local_registration_stub:
        status = 200 if method in ("GET", "POST") else 405
        if status == 200:
            registration_stub = True
            body = json.dumps(REGISTRATION_STUB, separators=(",", ":")).encode("ascii")
    elif path.split("?", 1)[0] == "/get_timezone":
        status = 200 if method in ("GET", "HEAD") else 405
        if status == 200:
            body = json.dumps({"timezone": "Europe/Moscow", "offset": 10800},
                              ensure_ascii=True, separators=(",", ":")).encode("ascii")
    elif path.split("?", 1)[0] == "/analytics/startup":
        status = 200 if method in ("GET", "HEAD") else 405
        if status == 200:
            body = json.dumps({"status": "ok"}, separators=(",", ":")).encode("ascii")
    elif path.split("?", 1)[0] == "/push_subscribe":
        status = 200 if method in ("GET", "HEAD", "POST") else 405
        if status == 200:
            body = json.dumps({"status": "ok"}, separators=(",", ":")).encode("ascii")
    elif path.split("?", 1)[0] == "/glagol/device_list":
        status = 200 if method in ("GET", "HEAD", "POST") else 405
        if status == 200:
            glagol_security = (sync_payload or {}).get("glagol", {})
            device_id = "yandexstation_2"
            if "?" in path:
                for part in path.split("?", 1)[1].split("&"):
                    if part.startswith("device_id="):
                        device_id = part.split("=", 1)[1] or device_id
            device = {
                "activation_code": 0,
                "activation_region": "RU",
                "config": {
                    "locale": "ru-RU",
                    "name": "Станция Макс",
                    "location": {"latitude": 55.7558, "longitude": 37.6173},
                    "dndMode": {"enabled": False, "features": {"allowIncomingCalls": False}},
                    "standby": {"deepStandbyEnabled": True, "deepStandbyTimeoutMinutes": 240},
                },
                "glagol": glagol_security,
                "id": device_id,
                "name": "Станция Макс",
                "networkInfo": {},
                "platform": "yandexstation_2",
                "promocode_activated": False,
                "tags": [],
            }
            body = json.dumps({"devices": [device], "status": "ok"},
                              ensure_ascii=True, separators=(",", ":")).encode("ascii")
    elif path.split("?", 1)[0] == "/get_user_details":
        status = 200 if method in ("GET", "HEAD") else 405
        if status == 200:
            user = {
                "uid": "1000000001",
                "login": "station",
                "yandex_uid": "1000000001",
                "email": "station@local",
                "is_plus": False,
                "avatar": {"default": "0/0-0", "empty": True},
                "display_name": {
                    "avatar": {"default": "0/0-0", "empty": True},
                    "name": "Станция",
                },
            }
            body = json.dumps({
                "status": "ok",
                "user_details": user,
                "user": user,
            }, ensure_ascii=True, separators=(",", ":")).encode("ascii")
    elif path not in PATHS:
        status = 404
    elif method not in ("GET", "HEAD", "POST"):
        status = 405
    elif path == "/generate_204":
        status = 204
    else:
        status = 200
        value = policy.check_updates_payload if path == "/check_updates" else (
            sync_payload if policy.local_sync_payload and sync_payload is not None else SYNC_INFO)
        body = json.dumps(value, ensure_ascii=True, separators=(",", ":")).encode("ascii")
    reasons = {200: "OK", 204: "No Content", 400: "Bad Request", 404: "Not Found",
               405: "Method Not Allowed", 421: "Misdirected Request"}
    headers = [f"HTTP/1.1 {status} {reasons[status]}", "Connection: close", "Cache-Control: no-store"]
    if status != 204:
        headers.append(f"Content-Length: {len(body)}")
    if body:
        headers.append("Content-Type: application/json")
    response = ("\r\n".join(headers) + "\r\n\r\n").encode("ascii")
    if method != "HEAD":
        response += body
    metadata = {"method": method, "path": path if path in PATHS or google_fallback else "<unrecognized_path>",
                "host": name if name else "<unrecognized_host>", "status": status}
    if path == "/check_updates" and status == 200:
        metadata["has_update"] = policy.check_updates_payload.get("hasUpdate")
        metadata["has_critical_update"] = policy.check_updates_payload.get("hasCriticalUpdate")
    if path not in PATHS:
        metadata["shape"] = path_shape(path)
    if registration_stub:
        metadata["path"] = "/register"
        metadata["stub"] = True
    if oauth_stub:
        metadata["path"] = "/token"
        metadata["stub"] = True
    return response, metadata


def read_exact(connection: socket.socket, count: int, deadline: float) -> bytes:
    data = bytearray()
    while len(data) < count:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("connection deadline")
        connection.settimeout(remaining)
        chunk = connection.recv(count - len(data))
        if not chunk:
            raise EOFError("connection closed")
        data.extend(chunk)
    return bytes(data)


def read_http_head(connection: socket.socket, deadline: float) -> bytes:
    # One byte at a time intentionally avoids reading any request-body bytes.
    data = bytearray()
    while not data.endswith(b"\r\n\r\n"):
        if len(data) >= MAX_HTTP_HEAD:
            raise ValueError("HTTP head too large")
        data.extend(read_exact(connection, 1, deadline))
    return bytes(data)


def discard_stub_post_body(connection: socket.socket, head: bytes, deadline: float) -> None:
    """Discard a bounded, unambiguous transport body; never parse or retain it.

    Only successful local OAuth/register POST handlers call this. Consuming the
    known body before close avoids resetting the response for an unread POST.
    """
    lengths = []
    for line in head[:-4].split(b"\r\n")[1:]:
        header_name, separator, value = line.partition(b":")
        if not separator or re.fullmatch(rb"[A-Za-z0-9!#$%&'*+.^_`|~-]+", header_name) is None:
            raise ValueError("unsupported stub body framing")
        if header_name.lower() == b"transfer-encoding":
            raise ValueError("unsupported stub body framing")
        if header_name.lower() == b"content-length":
            lengths.append(value.strip())
    if len(lengths) != 1 or re.fullmatch(rb"[0-9]{1,5}", lengths[0]) is None:
        raise ValueError("unsupported stub body framing")
    remaining = int(lengths[0])
    if remaining > MAX_STUB_BODY:
        raise ValueError("stub body bound")
    while remaining:
        count = min(4096, remaining)
        read_exact(connection, count, deadline)
        remaining -= count


def station_relay_mode(path: Path | None, now_ms: int | None = None,
                       max_future_ms: int | None = None) -> str:
    """Opt-in raw relay lease. It cannot select a host or a destination port."""
    if path is None:
        return "disabled"
    try:
        with path.open("rb") as source:
            raw = source.read(513)
    except FileNotFoundError:
        return "disabled"
    except OSError:
        return "invalid"
    try:
        if len(raw) > 512:
            raise ValueError()
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=reject_duplicate_keys,
                           parse_constant=reject_json_constant)
        if (type(value) is not dict or set(value) != {"version", "enabled", "expiresAtMs"}
                or type(value["version"]) is not int or value["version"] != 1
                or type(value["enabled"]) is not bool or type(value["expiresAtMs"]) is not int
                or not 0 <= value["expiresAtMs"] <= 9007199254740991):
            raise ValueError()
        if not value["enabled"]:
            return "disabled"
        now_ms = time.time_ns() // 1000000 if now_ms is None else now_ms
        if max_future_ms is not None and (not value["expiresAtMs"] or value["expiresAtMs"] > now_ms + max_future_ms):
            return "invalid"
        return "expired" if value["expiresAtMs"] and value["expiresAtMs"] <= now_ms else "active"
    except (ValueError, UnicodeError, RecursionError):
        return "invalid"


class LocalServer:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.stop = threading.Event()
        self.lock = threading.Lock()
        self.capacity = threading.BoundedSemaphore(8)
        self.listeners: list[tuple[socket.socket, str]] = []
        self.connections: set[socket.socket] = set()
        self.workers: list[threading.Thread] = []
        self.sni: dict[int, str] = {}
        self.google_resolver = GoogleResolver()
        self.lbs_resolver = PublicHostResolver(LBS_HOST)
        self.events = None
        self.ready_written = False
        self.context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self.context.minimum_version = ssl.TLSVersion.TLSv1_2
        # An encrypted key fails instead of prompting for a password indefinitely.
        self.context.load_cert_chain(args.cert, args.key, password=lambda: "")
        self.context.set_servername_callback(self.check_sni)

    def emit(self, event: str, **fields) -> None:
        # Call sites supply only fixed lifecycle/TLS results and safe parser metadata.
        with self.lock:
            if self.events is not None:
                try:
                    self.events.write(json.dumps({"at": utc_now(), "event": event, **fields}, ensure_ascii=True) + "\n")
                    self.events.flush()
                except OSError:
                    self.stop.set()
                    raise

    def check_sni(self, connection: ssl.SSLSocket, name: str | None, context):
        normalized = name.lower().rstrip(".") if name else ""
        if normalized not in self.current_policy().https_hosts:
            return ssl.ALERT_DESCRIPTION_UNRECOGNIZED_NAME
        with self.lock:
            self.sni[id(connection)] = normalized
        return None

    def current_policy(self) -> Policy:
        policy, error = load_policy(self.args.policy)
        if error:
            self.emit("policy", result="baseline_fallback", reason=error)
        return policy

    def maybe_google_tunnel(self, connection: socket.socket, policy: Policy, deadline: float) -> bool:
        if not policy.google_https_passthrough:
            return False  # No peeking, resolver job, or outbound socket on this path.
        name = peek_client_hello_sni(connection, min(deadline, time.monotonic() + CONNECTION_SECONDS))
        if name not in TUNNEL_HOSTS:
            return False  # Leave the original ClientHello intact for ordinary Yandex TLS.
        upstream = None
        sent = received = 0
        result = "connect_failed"
        try:
            if self.stop.is_set():
                result = "stopped"
                return True
            if name == GOOGLE_HOST:
                upstream = open_google_upstream(self.google_resolver, deadline)
            else:
                upstream = open_public_upstream(self.lbs_resolver, deadline)
            with self.lock:
                self.connections.add(upstream)
            if self.stop.is_set():
                result = "stopped"
                return True
            self.emit("google_tls", host=name, result="connected", sent_bytes=0, received_bytes=0)
            result, sent, received = relay_tls(connection, upstream, deadline, self.stop)
        except (OSError, ValueError):
            result = "relay_failed" if upstream is not None else "connect_failed"
        finally:
            if upstream is not None:
                with self.lock:
                    self.connections.discard(upstream)
                upstream.close()
            self.emit("google_tls", host=name, result=result, sent_bytes=sent, received_bytes=received)
        return True

    def bind_all(self) -> None:
        bindings = [("udp", 53, "dns_udp"), ("tcp", 53, "dns_tcp"),
                    ("tcp", 80, "http"), ("tcp", 443, "https")]
        if self.current_policy().local_ntp_server:
            bindings.append(("udp", 123, "ntp_udp"))
        for protocol, port, kind in bindings:
            endpoint = socket.socket(socket.AF_INET, socket.SOCK_DGRAM if protocol == "udp" else socket.SOCK_STREAM)
            self.listeners.append((endpoint, kind))  # Includes partially initialized sockets for cleanup.
            if protocol == "udp" and port == 53:
                # ICS on this lab PC binds 0.0.0.0:53. A specific-address UDP
                # socket can share it with SO_REUSEADDR and still receive
                # traffic addressed to 192.168.77.1.
                endpoint.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            elif hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
                endpoint.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            # No wildcard binding. TCP keeps exclusive semantics; UDP DNS
            # shares port 53 with the Windows ICS listener.
            endpoint.bind((BIND_IP, port))
            if protocol == "tcp":
                endpoint.listen(8)
            endpoint.setblocking(False)

    def maybe_station_relay(self, connection: socket.socket, kind: str, deadline: float) -> bool:
        if kind not in ("http", "https"):
            return False
        required = bool(getattr(self.args, "require_station_relay", False))
        mode = station_relay_mode(getattr(self.args, "station_relay", None),
                                  max_future_ms=35000 if required else None)
        if mode in ("disabled", "expired") and not required:
            return False
        upstream = None
        result, sent, received = "denied", 0, 0
        try:
            if mode != "active":
                return True  # Malformed lease never opens a route or serves cached credentials.
            target_port = 19685
            relay_deadline = deadline
            if kind == "https":
                name = peek_client_hello_sni(connection, deadline)
                if name not in self.current_policy().https_hosts or name in TUNNEL_HOSTS:
                    return True
                target_port = 19684 if name == PUSH_HOST else 19683
                if name == PUSH_HOST:
                    relay_deadline = time.monotonic() + PUSH_RELAY_SECONDS
            upstream = socket.create_connection(("127.0.0.1", target_port),
                                                timeout=max(.001, min(2, deadline - time.monotonic())))
            with self.lock:
                self.connections.add(upstream)
            result, sent, received = relay_tls(connection, upstream, relay_deadline, self.stop)
        except (OSError, ValueError):
            result = "unavailable"
        finally:
            if upstream is not None:
                with self.lock:
                    self.connections.discard(upstream)
                upstream.close()
            self.emit("station_relay", transport=kind, result=result,
                      sent_bytes=sent, received_bytes=received)
        return True

    def handle_connection(self, connection: socket.socket, kind: str) -> None:
        active = connection
        started = time.monotonic()
        deadline = started + CONNECTION_SECONDS
        tls_ok = False
        try:
            connection.settimeout(CONNECTION_SECONDS)
            sni = None
            if kind == "https":
                if self.maybe_google_tunnel(connection, self.current_policy(), started + GOOGLE_TUNNEL_SECONDS):
                    return
            if self.maybe_station_relay(connection, kind, deadline):
                return
            if kind == "https":
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError("TLS connection deadline")
                connection.settimeout(remaining)
                active = self.context.wrap_socket(connection, server_side=True, do_handshake_on_connect=False)
                with self.lock:
                    self.connections.discard(connection)
                    self.connections.add(active)
                active.do_handshake()
                tls_ok = True
                with self.lock:
                    sni = self.sni.get(id(active))
                if sni is None:
                    raise ssl.SSLError("missing accepted SNI")
                self.emit("tls", result="accepted", host=sni, client=CLIENT_IP)
            if kind == "dns_tcp":
                size = struct.unpack("!H", read_exact(active, 2, deadline))[0]
                if size < 12 or size > MAX_DNS_MESSAGE:
                    raise ValueError("invalid DNS frame size")
                response, metadata = build_dns_response(read_exact(active, size, deadline), self.current_policy())
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError("connection deadline")
                active.settimeout(remaining)
                active.sendall(struct.pack("!H", len(response)) + response)
                self.emit("dns", transport="tcp", client=CLIENT_IP, **metadata)
            else:
                try:
                    head = read_http_head(active, deadline)
                    method, path, host = parse_http_head(head)
                    policy = self.current_policy()
                    response, metadata = build_http_response(method, path, host, 443 if kind == "https" else 80,
                                                             sni, policy)
                    # Only a request already admitted as exact get_sync_info may
                    # read the single CLI-selected local payload file.
                    if path == "/get_sync_info" and metadata["status"] == 200 and policy.local_sync_payload:
                        payload, error = load_sync_payload(self.args.sync_payload)
                        if error:
                            self.emit("sync_payload", result="baseline_fallback", reason=error)
                        else:
                            payload, overlay_result = apply_display_overlay(payload, getattr(self.args, "led_overlay", None))
                            self.emit("led_overlay", result=overlay_result)
                            response, metadata = build_http_response(method, path, host, 443 if kind == "https" else 80,
                                                                     sni, policy, payload)
                            self.emit("sync_payload", result="loaded")
                    if method == "POST" and metadata.get("stub") and metadata["status"] == 200:
                        discard_stub_post_body(active, head, deadline)
                except (ValueError, UnicodeError):
                    response = b"HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
                    metadata = {"method": "<invalid_method>", "path": "<unrecognized_path>",
                                "host": "<unrecognized_host>", "shape": "<invalid_path>", "status": 400}
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError("connection deadline")
                active.settimeout(remaining)
                active.sendall(response)
                self.emit("http", transport=kind, client=CLIENT_IP, **metadata)
        except (OSError, ValueError, EOFError) as error:
            if kind == "https" and not tls_ok:
                reason = str(getattr(error, "reason", None) or type(error).__name__)[:120]
                reason = re.sub(r"[^A-Za-z0-9_ .:-]", "?", reason)
                self.emit("tls", result="failed", reason=reason, client=CLIENT_IP)
            else:
                self.emit("connection", transport=kind, result=type(error).__name__, client=CLIENT_IP)
        finally:
            with self.lock:
                self.sni.pop(id(active), None)
                self.connections.discard(active)
                self.connections.discard(connection)
            active.close()
            if active is not connection:
                connection.close()
            self.capacity.release()

    def run(self) -> int:
        if self.args.stop_file.exists() or self.args.ready.exists():
            raise ValueError("stop-file or ready file already exists")
        self.events = self.args.events.open("a", encoding="utf-8", buffering=1)
        started = time.monotonic()
        try:
            self.bind_all()
            # Every requested listener exists before the ready marker is created.
            with self.args.ready.open("x", encoding="utf-8") as marker:
                self.ready_written = True
                json.dump({"state": "ready", "pid": os.getpid(), "bind": BIND_IP, "client": CLIENT_IP,
                           "requireStationRelay": bool(getattr(self.args, "require_station_relay", False)),
                           "listeners": [f"{'udp' if kind in ('dns_udp', 'ntp_udp') else 'tcp'}:{endpoint.getsockname()[1]}"
                                         for endpoint, kind in self.listeners]}, marker)
                marker.flush()
                os.fsync(marker.fileno())
            self.emit("lifecycle", result="ready")
            mapping = dict(self.listeners)
            while not self.stop.is_set() and not self.args.stop_file.exists() and time.monotonic() - started < self.args.max_seconds:
                readable, _, _ = select.select(list(mapping), [], [], 0.25)
                for endpoint in readable:
                    kind = mapping[endpoint]
                    if kind == "ntp_udp":
                        # A complete IPv4 UDP datagram fits this bounded read;
                        # the pure parser rejects anything beyond 512 bytes.
                        data, peer = endpoint.recvfrom(65535)
                        received_at = time.time()
                        if peer[0] != CLIENT_IP:
                            continue
                        response, metadata = build_ntp_response(data, received_at, time.time(), self.current_policy())
                        if response is not None:
                            endpoint.sendto(response, peer)
                        self.emit("ntp", client=CLIENT_IP, **metadata)
                    elif kind == "dns_udp":
                        data, peer = endpoint.recvfrom(MAX_DNS_MESSAGE)
                        if peer[0] != CLIENT_IP:
                            continue
                        response, metadata = build_dns_response(data, self.current_policy())
                        endpoint.sendto(response, peer)  # Reply only to the accepted Station source.
                        self.emit("dns", transport="udp", client=CLIENT_IP, **metadata)
                    else:
                        connection, peer = endpoint.accept()
                        if peer[0] != CLIENT_IP or not self.capacity.acquire(blocking=False):
                            connection.close()
                            continue
                        with self.lock:
                            self.connections.add(connection)
                        worker = threading.Thread(target=self.handle_connection, args=(connection, kind), daemon=True)
                        self.workers = [thread for thread in self.workers if thread.is_alive()]
                        self.workers.append(worker)
                        worker.start()
            return 0
        except (OSError, ValueError) as error:
            self.emit("lifecycle", result="failed", reason=type(error).__name__)
            return 1
        finally:
            self.stop.set()
            for endpoint, _ in self.listeners:
                endpoint.close()
            with self.lock:
                active = list(self.connections)
            for connection in active:
                try:
                    connection.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                connection.close()
            for worker in self.workers:
                worker.join(GOOGLE_TUNNEL_SECONDS + 0.5)
            if self.ready_written:
                self.args.ready.unlink(missing_ok=True)
            self.emit("lifecycle", result="stopped")
            self.events.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("cert", "key", "events", "ready", "stop-file"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--policy", type=Path, help="optional absolute local JSON file, reloaded for each request")
    parser.add_argument("--sync-payload", type=Path, help="optional fixed absolute local JSON file; requires local_sync_payload policy")
    parser.add_argument("--led-overlay", type=Path, help="optional absolute LED-only JSON overlay; never modifies sync-payload")
    parser.add_argument("--station-relay", type=Path, help="optional private raw-relay lease; fixed loopback destinations only")
    parser.add_argument("--require-station-relay", action="store_true",
                        help="HTTP/HTTPS never use legacy PC handlers; missing/expired/unavailable local relay fails closed")
    parser.add_argument("--max-seconds", type=int, default=1800)
    args = parser.parse_args()
    if args.require_station_relay and args.station_relay is None:
        parser.error("--require-station-relay requires --station-relay")
    file_paths = [args.cert, args.key, args.events, args.ready, args.stop_file]
    if args.policy is not None:
        file_paths.append(args.policy)
    if args.sync_payload is not None:
        file_paths.append(args.sync_payload)
    if args.led_overlay is not None:
        file_paths.append(args.led_overlay)
    if args.station_relay is not None:
        file_paths.append(args.station_relay)
    if not all(value.is_absolute() for value in file_paths):
        parser.error("all file paths must be absolute")
    if any(str(value).startswith("\\\\") for value in file_paths):
        parser.error("local file paths required; UNC paths are not accepted")
    if not 1 <= args.max_seconds <= 86400:
        parser.error("max-seconds must be between 1 and 86400")
    paths = [os.path.normcase(str(value.resolve())) for value in file_paths]
    if len(set(paths)) != len(paths):
        parser.error("all file paths must be different")
    try:
        server = LocalServer(args)
        def request_stop(signum, frame):
            server.stop.set()
        signal.signal(signal.SIGINT, request_stop)
        signal.signal(signal.SIGTERM, request_stop)
        return server.run()
    except (OSError, ValueError):
        # Do not print exception text: paths, request data, or key-loading detail
        # are unnecessary in the public console or structured events.
        print("LocalConfigServer failed before readiness; inspect file arguments and local port availability.")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
