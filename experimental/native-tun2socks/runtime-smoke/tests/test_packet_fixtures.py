"""Independent Python packet fixtures for the disposable no-network runtime smoke."""
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = bytes([10, 111, 77, 2])
DESTINATION = bytes([198, 18, 0, 1])


def checksum(data):
    if len(data) % 2:
        data += b'\0'
    total = sum(struct.unpack('!' + 'H' * (len(data) // 2), data))
    while total >> 16:
        total = (total & 65535) + (total >> 16)
    return (~total) & 65535


def packet(protocol, payload, reverse=False):
    source, destination = (DESTINATION, SOURCE) if reverse else (SOURCE, DESTINATION)
    header = struct.pack('!BBHHHBBH4s4s', 0x45, 0, 20 + len(payload), 0x7374, 0, 64, protocol, 0, source, destination)
    header = header[:10] + struct.pack('!H', checksum(header)) + header[12:]
    return header + payload


def echo(reply=False):
    payload = struct.pack('!BBHHH4s', 0 if reply else 8, 0, 0, 0x7374, 1, b'HEV!')
    payload = payload[:2] + struct.pack('!H', checksum(payload)) + payload[4:]
    return packet(1, payload, reverse=reply)


def tcp(reply=False):
    source, destination = (DESTINATION, SOURCE) if reply else (SOURCE, DESTINATION)
    # Reply deliberately includes an independently built TCP MSS option.
    options = bytes.fromhex('020405b4') if reply else b''
    payload = struct.pack('!HHIIBBHHH', 80 if reply else 46090, 46090 if reply else 80,
                          0x55667788 if reply else 0x11223344, 0x11223345 if reply else 0,
                          0x60 if reply else 0x50, 0x12 if reply else 2, 4096, 0, 0) + options
    pseudo = source + destination + struct.pack('!BBH', 0, 6, len(payload))
    payload = payload[:16] + struct.pack('!H', checksum(pseudo + payload)) + payload[18:]
    return packet(6, payload, reverse=reply)


class PacketFixtures(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        java_home = os.environ.get('JAVA_HOME')
        executable = '.exe' if os.name == 'nt' else ''
        candidates = []
        if java_home:
            candidates.append((Path(java_home) / ('bin/javac' + executable), Path(java_home) / ('bin/java' + executable)))
        if shutil.which('javac') and shutil.which('java'):
            candidates.append((Path(shutil.which('javac')), Path(shutil.which('java'))))
        fallback = Path(r'C:\Program Files\Android\jdk\jdk-8.0.302.8-hotspot\jdk8u302-b08\bin')
        candidates.append((fallback / 'javac.exe', fallback / 'java.exe'))
        available = [(compiler, runtime) for compiler, runtime in candidates if compiler.is_file() and runtime.is_file()]
        if not available:
            raise RuntimeError('Java compiler and runtime are required for packet fixture tests')
        cls.javac, cls.java = (str(path) for path in available[0])
        cls.temp = tempfile.TemporaryDirectory()
        cls.classes = Path(cls.temp.name)
        subprocess.run([cls.javac, '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-d', str(cls.classes),
                        str(ROOT / 'src/local/station/hevsmoke/SyntheticPackets.java'),
                        str(ROOT / 'tests/PacketFixtureHarness.java')], capture_output=True, check=True, timeout=30)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def invoke(self, *args):
        return subprocess.run([self.java, '-cp', str(self.classes), 'local.station.hevsmoke.PacketFixtureHarness', *args],
                              capture_output=True, text=True, check=True, timeout=10).stdout.splitlines()

    def test_requests_match_independent_python_packets(self):
        self.assertEqual(self.invoke(), [echo().hex(), tcp().hex()])

    def test_independent_replies_accepted_and_corruption_denied(self):
        good_echo, good_tcp = echo(True), tcp(True)
        self.assertEqual(self.invoke(good_echo.hex(), good_tcp.hex()), ['true', 'true'])
        for i in (0, 2, 6, 8, 9, 10, 12, 16, 20, 24, 28, 31):
            bad = bytearray(good_echo)
            bad[i] ^= 1
            self.assertEqual(self.invoke(bad.hex(), good_tcp.hex())[0], 'false')
        for i in (0, 2, 6, 8, 9, 10, 12, 16, 20, 22, 28, 32, 33, 36, 43):
            bad = bytearray(good_tcp)
            bad[i] ^= 1
            self.assertEqual(self.invoke(good_echo.hex(), bad.hex())[1], 'false')
        for length in (0, 1, 19, 20, 31):
            self.assertEqual(self.invoke(good_echo[:length].hex(), good_tcp.hex())[0], 'false')
        for length in (0, 1, 19, 20, 39, 43):
            self.assertEqual(self.invoke(good_echo.hex(), good_tcp[:length].hex())[1], 'false')


if __name__ == '__main__':
    unittest.main()
