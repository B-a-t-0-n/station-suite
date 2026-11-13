"""Fixed fake socket/TLS tests. No listener, device, WAN or private files."""
import hashlib
import importlib.util
import json
from pathlib import Path
import os
import socket
import ssl
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('probe_local_ota', Path(__file__).resolve().parents[1] / 'probe_local_ota.py')
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)
PEER = b'reviewed-test-certificate'


def http(body=None):
    if body is None:
        body = b'{"status":"ok","hasUpdate":false,"hasCriticalUpdate":false}'
    return b'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ' + str(len(body)).encode() + b'\r\n\r\n' + body


class Stream:
    def __init__(self, response, peer=PEER, read_error=None):
        self.response, self.peer, self.read_error = response, peer, read_error
        self.sent, self.timeouts, self.closed = [], [], False

    def settimeout(self, value):
        self.timeouts.append(value)

    def sendall(self, data):
        self.sent.append(data)

    def recv(self, count):
        if self.read_error:
            raise self.read_error
        result, self.response = self.response[:count], self.response[count:]
        return result

    def getpeercert(self, binary_form):
        assert binary_form is True
        return self.peer

    def close(self):
        self.closed = True


class Context:
    def __init__(self, stream, error=None):
        self.stream, self.error, self.hosts = stream, error, []

    def wrap_socket(self, raw, *, server_hostname):
        self.hosts.append(server_hostname)
        if self.error:
            raise self.error
        return self.stream


class ProbeTests(unittest.TestCase):
    def run_probe(self, response=None, *, peer=PEER, read_error=None, tls_error=None, clock=lambda: 10):
        stream = Stream(http() if response is None else response, peer, read_error)
        context = Context(stream, tls_error)
        calls = []
        def connect(address, *, timeout):
            calls.append((address, timeout))
            return stream
        with patch.object(probe, 'CERT_SHA256', hashlib.sha256(PEER).hexdigest()):
            result = probe.probe(connect=connect, context_factory=lambda: context, clock=clock, wall=lambda: 123)
        self.assertEqual([call[0] for call in calls], [('127.0.0.1', 19683)])
        self.assertEqual(context.hosts, ['quasar.yandex.net'])
        self.assertTrue(stream.closed)
        self.assertTrue(all(0 < timeout <= 2 for timeout in stream.timeouts))
        self.assertEqual(result['checkedAtMs'], 123000)
        return result, stream

    def test_success_uses_exact_request_and_pinned_peer(self):
        result, stream = self.run_probe()
        self.assertTrue(result['ok'])
        self.assertTrue(result['tlsPinned'])
        self.assertTrue(result['updatesBlocked'])
        self.assertIs(result['hasUpdate'], False)
        self.assertIs(result['hasCriticalUpdate'], False)
        self.assertEqual(result['code'], 'OTA_BLOCKED')
        self.assertEqual(stream.sent, [probe.REQUEST])

    def test_pin_mismatch_sends_no_http_request(self):
        result, stream = self.run_probe(peer=b'other certificate')
        self.assertFalse(result['ok'])
        self.assertFalse(result['tlsPinned'])
        self.assertFalse(result['updatesBlocked'])
        self.assertEqual(result['code'], 'CERT_PIN_MISMATCH')
        self.assertEqual(stream.sent, [])

    def test_tls_failure_never_retries_or_leaks_exception(self):
        result, stream = self.run_probe(tls_error=ssl.SSLError('private response material'))
        self.assertEqual(result['code'], 'TLS_ERROR')
        self.assertFalse(result['ok'])
        self.assertNotIn('private', json.dumps(result))
        self.assertEqual(stream.sent, [])

    def test_read_timeout_fails_closed(self):
        result, _ = self.run_probe(read_error=socket.timeout('private data'))
        self.assertEqual(result['code'], 'TIMEOUT')
        self.assertFalse(result['ok'])

    def test_total_deadline_stops_before_http_after_slow_handshake(self):
        values = iter([0, 0, 0])
        def clock():
            return next(values, 5)
        result, stream = self.run_probe(clock=clock)
        self.assertEqual(result['code'], 'TIMEOUT')
        self.assertEqual(stream.sent, [])

    def test_either_update_flag_true_is_failure(self):
        for has_update, critical in ((True, False), (False, True), (True, True)):
            with self.subTest(flags=(has_update, critical)):
                body = json.dumps({'status': 'ok', 'hasUpdate': has_update, 'hasCriticalUpdate': critical}).encode()
                result, _ = self.run_probe(http(body))
                self.assertFalse(result['ok'])
                self.assertFalse(result['updatesBlocked'])
                self.assertEqual(result['code'], 'UPDATES_ENABLED')

    def test_bad_json_and_nonboolean_flags_never_mean_blocked(self):
        bodies = [b'{}', b'[]', b'null', b'bad-json', b'\xff\xff',
                  b'{"status":"ok","hasUpdate":false,"hasUpdate":true,"hasCriticalUpdate":false}',
                  b'{"status":"ok","hasUpdate":0,"hasCriticalUpdate":false}',
                  b'{"status":"ok","hasUpdate":"false","hasCriticalUpdate":false}',
                  b'{"status":"ok","hasUpdate":null,"hasCriticalUpdate":false}',
                  b'{"status":"error","hasUpdate":false,"hasCriticalUpdate":false}',
                  b'{"status":"ok","hasUpdate":NaN,"hasCriticalUpdate":false}']
        for body in bodies:
            with self.subTest(body=body):
                result, _ = self.run_probe(http(body))
                self.assertEqual(result['code'], 'BAD_JSON')
                self.assertFalse(result['ok'])
                self.assertFalse(result['updatesBlocked'])

    def test_http_framing_status_and_bounds(self):
        responses = [http().replace(b'200 OK', b'302 Found'),
                     http().replace(b'Content-Length:', b'Invalid-Length:'),
                     http().replace(b'Content-Length:', b'Transfer-Encoding: chunked\r\nContent-Length:'),
                     http().replace(b'Content-Length:', b'Content-Length: 2\r\nContent-Length:'),
                     http()[:-2],
                     b'HTTP/1.1 200 OK\r\nContent-Length: 4097\r\n\r\n',
                     b'HTTP/1.1 200 OK\r\nX-Pad: ' + b'x' * 9000 + b'\r\nContent-Length: 2\r\n\r\n{}']
        for response in responses:
            with self.subTest(size=len(response)):
                result, _ = self.run_probe(response)
                self.assertEqual(result['code'], 'BAD_HTTP')
                self.assertFalse(result['ok'])

    def test_connect_failure_is_single_fixed_attempt(self):
        calls = []
        def connect(address, *, timeout):
            calls.append(address)
            raise OSError('private upstream data')
        result = probe.probe(connect=connect)
        self.assertEqual(calls, [('127.0.0.1', 19683)])
        self.assertEqual(result['code'], 'UNAVAILABLE')
        self.assertFalse(result['ok'])
        self.assertNotIn('private', json.dumps(result))

    def test_context_requires_modern_tls_and_pin_is_not_tofu(self):
        self.assertEqual(probe.tls_context().minimum_version, ssl.TLSVersion.TLSv1_2)
        self.assertEqual(len(probe.CERT_SHA256), 64)
        self.assertNotEqual(probe.CERT_SHA256, hashlib.sha256(PEER).hexdigest())


if __name__ == '__main__':
    unittest.main()
