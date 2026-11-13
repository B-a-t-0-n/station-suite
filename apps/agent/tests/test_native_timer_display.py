"""Independently decode actual Java timer IPC; no device, networking, or native timer mutation."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path(os.environ['STATION_JAVA_HOME']) / 'bin'
OWNED_ID = b'station-agent-timer-01234567-89ab-cdef-0123-456789abcdef'


def read_varint(raw, offset):
    number = 0
    for shift in range(0, 70, 7):
        if offset >= len(raw):
            raise ValueError('truncated varint')
        value = raw[offset]
        offset += 1
        number |= (value & 127) << shift
        if value < 128:
            return number, offset
    raise ValueError('overlong varint')


def decode(raw):
    """Generic protobuf wire reader; intentionally independent of production encoding helpers."""
    offset, fields = 0, {}
    while offset < len(raw):
        key, offset = read_varint(raw, offset)
        field, wire = key >> 3, key & 7
        if field == 0:
            raise ValueError('zero field')
        if wire == 0:
            value, offset = read_varint(raw, offset)
        elif wire == 2:
            length, offset = read_varint(raw, offset)
            if length > len(raw) - offset:
                raise ValueError('truncated field')
            value, offset = raw[offset:offset + length], offset + length
        else:
            raise ValueError('unexpected wire type')
        fields.setdefault(field, []).append((wire, value))
    return fields


class NativeTimerDisplayTests(unittest.TestCase):
    def test_real_packets_are_incremental_native_and_owned(self):
        with tempfile.TemporaryDirectory(prefix='native-timer-test-') as directory:
            temporary = Path(directory)
            classes = temporary / 'classes'
            classes.mkdir()
            sources = [ROOT / 'src/local/station/agent/NativeTimerDisplay.java',
                       *sorted((ROOT / 'tests/native-timer-host').rglob('*.java'))]
            commands = [
                [JAVA / 'javac.exe', '-encoding', 'UTF-8', '--release', '8', '-Xlint:all',
                 '-d', classes, *sources],
                [JAVA / 'java.exe', '-cp', classes,
                 'local.station.agent.NativeTimerDisplayHostTest', temporary],
            ]
            for command in commands:
                result = subprocess.run([str(value) for value in command], capture_output=True,
                                        text=True, timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            paths = sorted(temporary.glob('*.bin'))
            self.assertEqual(len(paths), 18)
            request_ids = set()
            for path in paths:
                raw = path.read_bytes()
                self.assertLess(len(raw), 512)
                quasar = decode(raw)
                # No synthetic global timers_state, stop_alarm, voice or other device state.
                self.assertEqual(set(quasar), {1, 23})
                self.assertEqual(len(quasar[1]), 1)
                request_id = quasar[1][0]
                self.assertEqual(request_id[0], 2)
                self.assertEqual(str(uuid.UUID(request_id[1].decode('ascii'))),
                                 request_id[1].decode('ascii'))
                self.assertNotIn(request_id[1], request_ids)
                request_ids.add(request_id[1])
                self.assertEqual(len(quasar[23]), 1)
                self.assertEqual(quasar[23][0][0], 2)
                message = decode(quasar[23][0][1])
                if path.name.startswith('remove-'):
                    self.assertEqual(message, {2: [(2, OWNED_ID)]})
                    continue
                self.assertEqual(set(message), {1})
                self.assertEqual(len(message[1]), 1)
                self.assertEqual(message[1][0][0], 2)
                alarm = decode(message[1][0][1])
                # Type2 owns both stock LED countdown and native sound; Agent audio is separate.
                self.assertEqual(set(alarm), {1, 2, 3, 4})
                self.assertEqual(alarm[1], [(2, OWNED_ID)])
                self.assertEqual(alarm[2], [(0, 2)])
                _, wall, remaining = path.stem.split('-')
                wall, remaining = int(wall), int(remaining)
                start, duration = alarm[3][0][1], alarm[4][0][1]
                self.assertEqual(alarm[3][0][0], 0)
                self.assertEqual(alarm[4][0][0], 0)
                self.assertEqual(len(alarm[3]), 1)
                self.assertEqual(len(alarm[4]), 1)
                self.assertGreaterEqual(duration, 1)
                self.assertLessEqual(duration, 86400)
                self.assertLessEqual(start, wall)
                self.assertLess(wall - start, 1000)
                # Native expiry must match the model deadline even for fractional seconds.
                self.assertEqual(start + duration * 1000, wall + remaining)
            print('18 real native timer packets independently decoded; 9 invalid duration/wall bounds rejected; no sockets')


if __name__ == '__main__':
    unittest.main()
