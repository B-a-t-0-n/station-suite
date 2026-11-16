"""Actual passive decoder tests. Synthetic wire fixtures; no device or accounts."""
import importlib.util
from pathlib import Path
import socket
import struct
import unittest

spec = importlib.util.spec_from_file_location('ring_state', Path(__file__).parents[1] / 'ring_state.py')
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


def vi(value):
    result = bytearray()
    while value > 127:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


def scalar(number, value):
    return vi(number * 8) + vi(value)


def msg(number, value):
    return vi(number * 8 + 2) + vi(len(value)) + value


def state(*pieces):
    return msg(85, msg(34, b''.join(pieces)))


def quiet(**changes):
    values = {'mics': 0, 'alice': 0, 'alarm': 0, 'timer': 0, 'config': 2,
              'update': 1, 'reminder': 0, 'notification': 0, 'call': 7}
    values.update(changes)
    return state(msg(1, scalar(1, values['mics'])), msg(2, scalar(1, values['alice'])),
                 msg(5, scalar(2, values['alarm']) + scalar(3, values['timer'])),
                 scalar(6, values['config']), msg(7, scalar(1, values['update'])),
                 msg(10, scalar(1, values['reminder'])), scalar(12, values['notification']),
                 msg(13, scalar(3, values['call'])))


class Clock:
    def __init__(self): self.now = 0.
    def __call__(self): return self.now


class ReceiveOnly:
    """No send/write method: observer fails if it tries to publish any IPC."""
    def __init__(self, chunks, clock): self.chunks, self.clock = list(chunks), clock
    def settimeout(self, value): self.timeout = value
    def recv(self, count):
        self.clock.now += .01
        if not self.chunks: return b''
        value = self.chunks.pop(0)
        if value == 'timeout': raise socket.timeout()
        if len(value) > count:
            self.chunks.insert(0, value[count:])
        return value[:count]


def frame(value): return struct.pack('<I', len(value)) + value


class StateTests(unittest.TestCase):
    def test_complete_quiet_is_only_candidate_never_permit(self):
        result = probe.summarize(quiet())
        self.assertTrue(result['quietCandidate'])
        self.assertFalse(result['renderPermit'])
        self.assertFalse(result['nativeOwnershipVerified'])

    def test_absent_fields_are_unknown_not_proto_defaults(self):
        result = probe.summarize(state(msg(2, scalar(1, 0))))
        self.assertEqual(result['fields']['alice'], 'idle')
        self.assertIn('mics', result['unknown'])
        self.assertIn('alarmPlaying', result['unknown'])
        self.assertFalse(result['quietCandidate'])

    def test_empty_nested_message_is_not_explicit_zero(self):
        result = probe.summarize(state(msg(1, b''), msg(2, b''), msg(7, b'')))
        self.assertIsNone(result['fields']['mics'])
        self.assertIsNone(result['fields']['alice'])
        self.assertIsNone(result['fields']['update'])

    def test_every_known_priority_blocks(self):
        for key, value in [('mics', 1), ('alice', 1), ('alice', 2), ('alice', 3),
                           ('alice', 4), ('alice', 5), ('alice', 6), ('alarm', 1),
                           ('timer', 1), ('config', 0), ('config', 1), ('config', 3),
                           ('config', 4), ('update', 2), ('update', 3), ('reminder', 1),
                           ('notification', 1), ('notification', 2), ('call', 5)]:
            with self.subTest(key=key, value=value):
                result = probe.summarize(quiet(**{key: value}))
                self.assertFalse(result['quietCandidate'])
                self.assertTrue(result['blockers'])

    def test_unknown_enum_fails_closed(self):
        for key in ('mics', 'alice', 'config', 'update', 'notification', 'call'):
            with self.subTest(key=key):
                result = probe.summarize(quiet(**{key: 255}))
                self.assertTrue(result['unknown'])
                self.assertFalse(result['quietCandidate'])

    def test_invalid_boolean_is_rejected(self):
        with self.assertRaises(probe.WireError): probe.summarize(quiet(alarm=2))

    def test_duplicate_singular_is_ambiguous(self):
        for value in [state(msg(2, scalar(1, 0) + scalar(1, 1))),
                      state(msg(2, scalar(1, 0)), msg(2, scalar(1, 1))),
                      msg(85, msg(34, b'') + msg(34, b'')), msg(85, b'') + msg(85, b'')]:
            with self.subTest(value=value):
                with self.assertRaises(probe.WireError): probe.summarize(value)

    def test_wrong_selected_wire_type_is_rejected(self):
        for value in [scalar(85, 1), msg(85, scalar(34, 0)), state(scalar(1, 0)),
                      state(msg(2, msg(1, b'wrong')))]:
            with self.subTest(value=value):
                with self.assertRaises(probe.WireError): probe.summarize(value)

    def test_other_frames_are_ignored(self):
        self.assertIsNone(probe.summarize(msg(33, b'other')))
        self.assertIsNone(probe.summarize(msg(85, scalar(1, 0))))

    def test_unknown_and_sensitive_fields_never_exported(self):
        value = state(msg(2, scalar(1, 0) + msg(2, b'SECRET PHRASE') + msg(3, b'SECRET VINS')),
                      msg(11, msg(5, b'SECRET NETWORK')), msg(99, b'SECRET ACCOUNT'))
        self.assertNotIn('SECRET', repr(probe.summarize(value)))

    def test_wire_truncation_overflow_field_zero_and_groups(self):
        for value in [b'\x80', b'\x80' * 10, b'\xff' * 9 + b'\x02',
                      b'\x00\x00', b'\x0b', b'\x0a\x02x', b'\x09x']:
            with self.subTest(value=value):
                with self.assertRaises(probe.WireError): probe.summarize(value)

    def test_max_frame_is_bounded(self):
        with self.assertRaises(probe.WireError): probe.summarize(b'x' * (probe.MAX_FRAME + 1))

    def test_framing_fragmentation_and_no_sends(self):
        raw = frame(quiet())
        clock = Clock()
        result = probe.observe(ReceiveOnly([raw[:1], raw[1:3], raw[3:7], raw[7:]], clock), 1, clock)
        self.assertEqual(result['nativeIpcBytesSent'], 0)
        self.assertEqual(result['deviceStateFrames'], 1)
        self.assertTrue(result['latest']['quietCandidate'])
        self.assertFalse(result['renderPermit'])

    def test_partial_later_snapshot_never_merges_earlier_quiet_fields(self):
        clock = Clock()
        result = probe.observe(ReceiveOnly([frame(quiet()), frame(state(msg(2, scalar(1, 0))))], clock), 1, clock)
        self.assertEqual(result['deviceStateFrames'], 2)
        self.assertIn('mics', result['latest']['unknown'])
        self.assertFalse(result['latest']['quietCandidate'])

    def test_no_snapshot_is_unknown(self):
        clock = Clock()
        result = probe.observe(ReceiveOnly([frame(msg(33, b''))], clock), 1, clock)
        self.assertIsNone(result['latest'])
        self.assertIsNone(result['arrivalAgeMs'])

    def test_partial_frame_is_discarded_and_marked(self):
        clock = Clock()
        result = probe.observe(ReceiveOnly([frame(quiet())[:-1]], clock), 1, clock)
        self.assertTrue(result['partialFrameDiscarded'])
        self.assertIsNone(result['latest'])

    def test_frame_header_limits(self):
        for count in (0, probe.MAX_FRAME + 1, 0xffffffff):
            clock = Clock()
            with self.subTest(count=count):
                with self.assertRaises(probe.WireError):
                    probe.observe(ReceiveOnly([struct.pack('<I', count)], clock), 1, clock)

    def test_deadline_and_arrival_age_are_local_only(self):
        clock = Clock()
        result = probe.observe(ReceiveOnly([frame(quiet())] + ['timeout'] * 100, clock), .1, clock)
        self.assertEqual(result['reason'], 'deadline')
        self.assertGreater(result['arrivalAgeMs'], 0)
        self.assertFalse(result['nativeTimestampAvailable'])
        self.assertFalse(result['renderPermit'])

    def test_frame_count_limit(self):
        clock = Clock()
        chunks = [frame(msg(33, b'')) * 200]
        result = probe.observe(ReceiveOnly(chunks, clock), 1, clock)
        self.assertEqual(result['receivedFrames'], probe.MAX_FRAMES)
        self.assertEqual(result['reason'], 'frame_limit')


if __name__ == '__main__': unittest.main()
