"""Read-only probe of the Station's fixed local OTA responder. No WAN fallback."""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import socket
import ssl
import sys
import time


ADDRESS = ('127.0.0.1', 19683)
HOST = 'quasar.yandex.net'
# Leaf DER fingerprint of the existing reviewed lab fullchain.pem, not TOFU.
CERT_SHA256 = os.environ.get('STATION_TLS_LEAF_SHA256', '0' * 64)
TOTAL_SECONDS, IO_SECONDS = 4.0, 2.0
MAX_HEAD, MAX_BODY = 8192, 4096
REQUEST = b'GET /check_updates HTTP/1.1\r\nHost: quasar.yandex.net\r\nConnection: close\r\n\r\n'


class ProbeError(Exception):
    pass


def tls_context():
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    # Pin verification below is mandatory and precedes the HTTP request. This
    # monitor does not depend on the old native certificate's public-CA dates.
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    return context


def remaining(deadline, clock):
    seconds = deadline - clock()
    if seconds <= 0:
        raise ProbeError('TIMEOUT')
    return min(IO_SECONDS, seconds)


def read_response(stream, deadline, clock):
    data = bytearray()
    while b'\r\n\r\n' not in data:
        stream.settimeout(remaining(deadline, clock))
        block = stream.recv(min(1024, MAX_HEAD + 1 - len(data)))
        if not block:
            raise ProbeError('BAD_HTTP')
        data.extend(block)
        if b'\r\n\r\n' not in data and len(data) >= MAX_HEAD:
            raise ProbeError('BAD_HTTP')
    head, body = bytes(data).split(b'\r\n\r\n', 1)
    if len(head) + 4 > MAX_HEAD:
        raise ProbeError('BAD_HTTP')
    try:
        lines = head.decode('ascii').split('\r\n')
    except UnicodeError:
        raise ProbeError('BAD_HTTP') from None
    if not re.fullmatch(r'HTTP/1\.[01] 200 [\x20-\x7e]*', lines[0]):
        raise ProbeError('BAD_HTTP')
    headers = {}
    for line in lines[1:]:
        name, colon, value = line.partition(':')
        if not colon or not re.fullmatch(r"[!#$%&'*+.^_`|~0-9A-Za-z-]+", name):
            raise ProbeError('BAD_HTTP')
        name, value = name.lower(), value.strip(' \t')
        if name in headers or any(ord(char) < 32 and char != '\t' or ord(char) == 127 for char in value):
            raise ProbeError('BAD_HTTP')
        headers[name] = value
    size = headers.get('content-length', '')
    if 'transfer-encoding' in headers or not re.fullmatch(r'[0-9]{1,4}', size):
        raise ProbeError('BAD_HTTP')
    size = int(size)
    if size < 2 or size > MAX_BODY or len(body) > size:
        raise ProbeError('BAD_HTTP')
    while len(body) < size:
        stream.settimeout(remaining(deadline, clock))
        block = stream.recv(min(1024, size - len(body)))
        if not block:
            raise ProbeError('BAD_HTTP')
        body += block
    remaining(deadline, clock)
    return body


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ProbeError('BAD_JSON')
        result[key] = value
    return result


def parse_ota(body):
    try:
        value = json.loads(body.decode('utf-8'), object_pairs_hook=unique_object,
                           parse_constant=lambda _: (_ for _ in ()).throw(ProbeError('BAD_JSON')))
    except (ValueError, UnicodeError):
        raise ProbeError('BAD_JSON') from None
    if (type(value) is not dict or value.get('status') != 'ok'
            or type(value.get('hasUpdate')) is not bool
            or type(value.get('hasCriticalUpdate')) is not bool):
        raise ProbeError('BAD_JSON')
    return value['hasUpdate'], value['hasCriticalUpdate']


def probe(*, connect=socket.create_connection, context_factory=tls_context,
          clock=time.monotonic, wall=time.time):
    started = clock()
    result = {'scope': 'station_local_ota', 'ok': False, 'code': 'UNAVAILABLE',
              'tlsPinned': False, 'updatesBlocked': False}
    raw = secure = None
    try:
        deadline = started + TOTAL_SECONDS
        raw = connect(ADDRESS, timeout=remaining(deadline, clock))
        raw.settimeout(remaining(deadline, clock))
        secure = context_factory().wrap_socket(raw, server_hostname=HOST)
        peer = secure.getpeercert(binary_form=True)
        if not peer or not hmac.compare_digest(hashlib.sha256(peer).hexdigest(), CERT_SHA256):
            raise ProbeError('CERT_PIN_MISMATCH')
        result['tlsPinned'] = True
        secure.settimeout(remaining(deadline, clock))
        secure.sendall(REQUEST)
        has_update, critical = parse_ota(read_response(secure, deadline, clock))
        result.update(hasUpdate=has_update, hasCriticalUpdate=critical, httpStatus=200)
        blocked = has_update is False and critical is False
        result.update(ok=blocked, updatesBlocked=blocked, code='OTA_BLOCKED' if blocked else 'UPDATES_ENABLED')
    except ProbeError as error:
        result['code'] = str(error)
    except (TimeoutError, socket.timeout):
        result['code'] = 'TIMEOUT'
    except ssl.SSLError:
        result['code'] = 'TLS_ERROR'
    except Exception:
        # No exception messages, response fragments, certificate material or URLs.
        result['code'] = 'UNAVAILABLE'
    finally:
        for stream in (secure, raw):
            if stream is not None:
                try:
                    stream.close()
                except Exception:
                    pass
    result.update(checkedAtMs=int(wall() * 1000), elapsedMs=max(0, int((clock() - started) * 1000)))
    return result


def main():
    if len(sys.argv) != 1:
        print(json.dumps({'scope': 'station_local_ota', 'ok': False, 'code': 'INVALID_ARGUMENTS',
                          'tlsPinned': False, 'updatesBlocked': False}))
        return 1
    result = probe()
    print(json.dumps(result, separators=(',', ':')))
    return 0 if result['ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
