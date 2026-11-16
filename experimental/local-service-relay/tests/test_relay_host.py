"""Compile Java 8 production sources; test parsers and loopback-only fake backends.

No Android, ADB, VPN, DNS queries, external targets, or device changes. The
RFC1918 address below appears only inside SOCKS bytes, never in connect().
All listeners use ephemeral loopback ports through the package-private test
seam. Existing applications and production fixed ports are never touched.
"""
from pathlib import Path
import json
import os
import shutil
import socket
import struct
import subprocess
import tempfile
import threading
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]
CONFIG_IP = '192.168.77.1'


def java_tool(name):
    configured = os.environ.get('STATION_JAVA_HOME') or os.environ.get('JAVA_HOME')
    if configured:
        candidate = Path(configured) / 'bin' / (name + ('.exe' if os.name == 'nt' else ''))
        if candidate.is_file():
            return str(candidate)
    candidate = shutil.which(name)
    if not candidate:
        raise RuntimeError(f'{name} is required for host tests')
    return candidate


def exact(sock, count):
    result = bytearray()
    while len(result) < count:
        chunk = sock.recv(count - len(result))
        if not chunk:
            raise EOFError(f'closed after {len(result)}/{count} bytes')
        result.extend(chunk)
    return bytes(result)


def tls_hello(name='quasar.yandex.net', pieces=()):
    """Independent TLS ClientHello encoder, not a copy of production parser."""
    entries = b'\0' + struct.pack('!H', len(name)) + name.encode('ascii')
    names = struct.pack('!H', len(entries)) + entries
    extensions = struct.pack('!HH', 0, len(names)) + names
    body = b'\x03\x03' + bytes(range(32)) + b'\0\0\x02\x13\x01\x01\0'
    body += struct.pack('!H', len(extensions)) + extensions
    handshake = b'\x01' + len(body).to_bytes(3, 'big') + body
    chunks = []
    offset = 0
    for size in pieces:
        chunks.append(handshake[offset:offset + size])
        offset += size
    if offset < len(handshake):
        chunks.append(handshake[offset:])
    return b''.join(b'\x16\x03\x03' + struct.pack('!H', len(chunk)) + chunk for chunk in chunks)


class FakeBackend:
    """Fixed loopback endpoint: HTTP204 or byte echo, with observed input only."""
    def __init__(self, http=False):
        self.http = http
        self.listener = socket.socket()
        self.listener.bind(('127.0.0.1', 0))
        self.port = self.listener.getsockname()[1]
        self.listener.listen(8)
        self.listener.settimeout(0.1)
        self.stop = threading.Event()
        self.lock = threading.Lock()
        self.connections = []
        self.sockets = []
        self.workers = []
        self.thread = threading.Thread(target=self._accept, daemon=True)
        self.thread.start()

    def _accept(self):
        while not self.stop.is_set():
            try:
                peer, _ = self.listener.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            record = {'received': bytearray(), 'closed': threading.Event()}
            with self.lock:
                self.connections.append(record)
                self.sockets.append(peer)
            worker = threading.Thread(target=self._handle, args=(peer, record), daemon=True)
            self.workers.append(worker)
            worker.start()

    def _handle(self, peer, record):
        sent_http = False
        peer.settimeout(0.1)
        try:
            while not self.stop.is_set():
                try:
                    data = peer.recv(4096)
                except socket.timeout:
                    continue
                if not data:
                    return
                with self.lock:
                    record['received'].extend(data)
                    complete = b'\r\n\r\n' in record['received']
                if self.http:
                    if complete and not sent_http:
                        peer.sendall(b'HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n')
                        peer.shutdown(socket.SHUT_WR)
                        sent_http = True
                else:
                    peer.sendall(data)
        except (ConnectionError, OSError):
            pass
        finally:
            peer.close()
            record['closed'].set()

    def count(self):
        with self.lock:
            return len(self.connections)

    def record(self, index):
        with self.lock:
            return bytes(self.connections[index]['received'])

    def close(self):
        self.stop.set()
        self.listener.close()
        with self.lock:
            peers = self.sockets[:]
        for peer in peers:
            try:
                peer.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            peer.close()
        self.thread.join(1)
        for worker in self.workers:
            worker.join(1)


class RelayHostTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='station-local-relay-host-')
        sources = sorted((ROOT / 'src/local/station/relay').glob('*.java'))
        sources += sorted((ROOT / 'tests').glob('*.java'))
        result = subprocess.run(
            [java_tool('javac'), '-encoding', 'UTF-8', '-source', '8', '-target', '8',
             '-Xlint:all', '-d', cls.temporary.name, *map(str, sources)],
            capture_output=True, text=True, encoding='utf8', errors='replace', timeout=30)
        if result.returncode:
            cls.temporary.cleanup()
            raise AssertionError(result.stdout + result.stderr)
        cls.java = [java_tool('java'), '-cp', cls.temporary.name]

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def test_parsers(self):
        result = subprocess.run([*self.java, 'local.station.relay.RelayProtocolHostTest'],
                                capture_output=True, text=True, encoding='utf8', errors='replace', timeout=15)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('assertions passed; production parsers, independent wire fixtures, no sockets', result.stdout)
        print(result.stdout.strip())

    def test_loopback_routing_denial_and_absolute_deadline(self):
        backends = []
        process = None
        open_clients = []
        try:
            for is_http in (False, False, True):
                backends.append(FakeBackend(http=is_http))
            config, push, plain = backends
            process = subprocess.Popen(
                [*self.java, 'local.station.relay.RelayHostServer', CONFIG_IP, '7000',
                 str(config.port), str(push.port), str(plain.port)],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding='utf8')
            ready = process.stdout.readline().strip()
            if ready == 'LOCAL_RELAY_READY':
                ready = process.stdout.readline().strip()
            self.assertTrue(ready.startswith('HOST_RELAY_READY='), ready)
            listen = ('127.0.0.1', int(ready.partition('=')[2]))
            ready_at = time.monotonic()

            def connect():
                peer = socket.create_connection(listen, timeout=1)
                peer.settimeout(2)
                open_clients.append(peer)
                return peer

            def negotiate(peer):
                peer.sendall(b'\x05\x01\0')
                self.assertEqual(exact(peer, 2), b'\x05\0')

            def socks(peer, port=443, address=CONFIG_IP, command=1, atyp=1, version=5, reserved=0):
                negotiate(peer)
                request = bytes((version, command, reserved, atyp))
                if atyp == 1:
                    request += socket.inet_aton(address) + struct.pack('!H', port)
                peer.sendall(request)
                return exact(peer, 10)

            def assert_closed(peer):
                try:
                    self.assertEqual(peer.recv(1), b'')
                except ConnectionResetError:
                    pass

            def backend_counts():
                return tuple(backend.count() for backend in backends)

            # Four authenticated-but-incomplete clients occupy the documented
            # flow budget; a fifth must close without creating any backend.
            held = [connect() for _ in range(4)]
            for peer in held:
                negotiate(peer)
            before_capacity = backend_counts()
            with connect() as fifth:
                assert_closed(fifth)
            self.assertEqual(backend_counts(), before_capacity)
            for peer in held:
                peer.close()
            # Allow the rejected/incomplete handlers to release permits before
            # assessing later independent routing cases.
            time.sleep(0.03)

            # Invalid SOCKS versions, auth choices and commands must never create a backend.
            before = backend_counts()
            for raw, expected in ((b'\x04', b''), (b'\x05\x01\x02', b'\x05\xff'), (b'\x05\x11', b'')):
                with connect() as peer:
                    peer.sendall(raw)
                    if expected:
                        self.assertEqual(exact(peer, len(expected)), expected)
                    assert_closed(peer)
            for kwargs, code in (({'address': '192.168.77.2'}, 2), ({'port': 22}, 2),
                                 ({'port': 17683}, 2), ({'command': 2}, 7), ({'command': 3}, 7),
                                 ({'atyp': 3}, 8), ({'atyp': 4}, 8), ({'version': 4}, 1), ({'reserved': 1}, 1)):
                with connect() as peer:
                    reply = socks(peer, **kwargs)
                    self.assertEqual(reply[:4], bytes((5, code, 0, 1)))
                    assert_closed(peer)
            self.assertEqual(backend_counts(), before, 'denied SOCKS target must open no local backend')

            # Backend selection waits for a valid hello, despite the SOCKS success reply.
            for bad_hello in (tls_hello('example.org'), b'\x16\x03\x03\0\x04\x01\0\0\0',
                              b'\x16\x03\x03\0\x02\x01\0'):
                with connect() as peer:
                    self.assertEqual(socks(peer)[1], 0)
                    peer.sendall(bad_hello)
                    peer.shutdown(socket.SHUT_WR)
                    assert_closed(peer)
            self.assertEqual(backend_counts(), before, 'unknown/missing/truncated SNI must not open a backend')

            # Fragmented TLS records and post-hello bytes are forwarded without rewriting.
            for host, backend in (('quasar.yandex.net', config), ('station-config.quasar.yandex.net', push)):
                wire = tls_hello(host, pieces=(1, 1, 1, 3, 7))
                later = b'\x17\x03\x03\0\x08\x00\xffopaque'
                index = backend.count()
                with connect() as peer:
                    self.assertEqual(socks(peer)[1], 0)
                    for offset in range(0, len(wire), 3):
                        peer.sendall(wire[offset:offset + 3])
                    self.assertEqual(exact(peer, len(wire)), wire)
                    peer.sendall(later)
                    self.assertEqual(exact(peer, len(later)), later)
                self.assertTrue(backend.connections[index]['closed'].wait(1))
                self.assertEqual(backend.record(index), wire + later)

            # A validated connectivity request may pass; display/control paths cannot.
            first = b'HEAD /generate_204 HTTP/1.1\r\nHost: scbh.yandex.net\r\n\r\n'
            forbidden = b'GET /api/display HTTP/1.1\r\nHost: scbh.yandex.net\r\n\r\n'
            before_plain = plain.count()
            with connect() as peer:
                self.assertEqual(socks(peer, port=80)[1], 0)
                peer.sendall(forbidden)
                assert_closed(peer)
            self.assertEqual(plain.count(), before_plain)
            with connect() as peer:
                self.assertEqual(socks(peer, port=80)[1], 0)
                peer.sendall(first + forbidden)
                response = bytearray()
                while True:
                    try:
                        chunk = peer.recv(4096)
                    except ConnectionResetError:
                        break
                    if not chunk:
                        break
                    response.extend(chunk)
                self.assertTrue(response.startswith(b'HTTP/1.1 204 '))
            self.assertTrue(plain.connections[before_plain]['closed'].wait(1))
            self.assertEqual(plain.record(before_plain), first, 'pipelined /api/display must never reach backend')

            # Constant traffic defeats the 1.5s idle timeout. Only the absolute 7s
            # service deadline should end this flow and close its backend socket.
            peer = connect()
            self.assertEqual(socks(peer)[1], 0)
            wire = tls_hello()
            peer.sendall(wire)
            self.assertEqual(exact(peer, len(wire)), wire)
            held_index = config.count() - 1
            ended = False
            while time.monotonic() - ready_at < 9:
                try:
                    peer.sendall(b'heartbeat')
                    self.assertEqual(exact(peer, 9), b'heartbeat')
                except (OSError, EOFError):
                    ended = True
                    break
                time.sleep(0.08)
            elapsed = time.monotonic() - ready_at
            self.assertTrue(ended, 'absolute deadline must close continuously active client')
            self.assertGreater(elapsed, 4.5, 'test must exercise absolute deadline, not only read timeout')
            self.assertLess(elapsed, 8.5)
            self.assertTrue(config.connections[held_index]['closed'].wait(1), 'deadline closes active backend')
            stdout, stderr = process.communicate(timeout=3)
            self.assertEqual(process.returncode, 0, stdout + stderr)
            summaries = [line.partition('=')[2] for line in stdout.splitlines() if line.startswith('LOCAL_RELAY_RESULT=')]
            self.assertEqual(len(summaries), 1, stdout + stderr)
            summary = json.loads(summaries[0])
            self.assertTrue(summary['closed'])
            self.assertEqual(summary['http'], 1)
            self.assertEqual(summary['configTls'], 2)
            self.assertEqual(summary['pushTls'], 1)
            self.assertEqual(summary['unfinishedUploads'], 0)
            self.assertEqual(summary['activeHandlers'], 0)
            self.assertGreaterEqual(summary['denied'], 17)
            self.assertNotIn('quasar.yandex.net', stdout + stderr, 'no request/SNI content in counters')
            self.assertNotIn('/api/display', stdout + stderr)
            print('Loopback routing/deny/pipeline/deadline checks passed; no device or WAN; elapsed %.3fs' % elapsed)
        finally:
            for peer in open_clients:
                peer.close()
            if process is not None and process.poll() is None:
                process.terminate()
                process.communicate(timeout=3)
            for backend in backends:
                backend.close()


if __name__ == '__main__':
    unittest.main()
