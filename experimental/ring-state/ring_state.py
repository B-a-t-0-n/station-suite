"""Bounded, receive-only view of native 20221212 DeviceState. No RGB permit."""
import argparse
import json
import socket
import struct
import time

PROFILE = 'station-max-20221212'
MAIND_SHA256 = '08a0050f4ad26e1054321c46d2e3631a6b02805e5e0355c10cbcbf53ea82ea6d'
MAX_FRAME = 65536
MAX_BYTES = 262144
MAX_FRAMES = 128

MICS = {0: 'on', 1: 'off'}
ALICE = {0: 'idle', 1: 'listening', 2: 'speaking', 3: 'busy',
         4: 'long_listening_idle', 5: 'shazam', 6: 'none'}
CONFIG = {0: 'unknown', 1: 'configuring', 2: 'configured',
          3: 'reset_wait_confirm', 4: 'reset_in_progress'}
UPDATE = {1: 'none', 2: 'downloading', 3: 'applying'}
NOTIFICATIONS = {0: 'none', 1: 'available', 2: 'passive'}
CALL = {0: 'new', 1: 'dialing', 2: 'ringing', 3: 'accepting', 4: 'connecting',
        5: 'connected', 6: 'ended', 7: 'no_call'}


class WireError(ValueError):
    pass


def _varint(raw, offset):
    value = 0
    for index in range(10):
        if offset >= len(raw):
            raise WireError('truncated_varint')
        item = raw[offset]
        offset += 1
        if index == 9 and item > 1:
            raise WireError('varint_overflow')
        value |= (item & 127) << (7 * index)
        if item < 128:
            return value, offset
    raise WireError('varint_overflow')


def _fields(raw):
    if not isinstance(raw, bytes) or len(raw) > MAX_FRAME:
        raise WireError('frame_size')
    offset = 0
    result = {}
    while offset < len(raw):
        tag, offset = _varint(raw, offset)
        number, kind = tag >> 3, tag & 7
        if not 1 <= number <= 536870911:
            raise WireError('field_number')
        if kind == 0:
            value, offset = _varint(raw, offset)
        elif kind in (1, 2, 5):
            if kind == 2:
                count, offset = _varint(raw, offset)
            else:
                count = 8 if kind == 1 else 4
            if count > len(raw) - offset:
                raise WireError('field_size')
            value = raw[offset:offset + count]
            offset += count
        else:
            raise WireError('unsupported_wire_type')
        result.setdefault(number, []).append((kind, value))
    return result


def _one(fields, number, kind):
    values = fields.get(number)
    if values is None:
        return None
    if len(values) != 1 or values[0][0] != kind:
        raise WireError('ambiguous_selected_field')
    return values[0][1]


def _message(fields, number):
    raw = _one(fields, number, 2)
    return {} if raw is None else _fields(raw)


def _enum(fields, number, names):
    value = _one(fields, number, 0)
    return names.get(value)


def _boolean(fields, number):
    value = _one(fields, number, 0)
    if value is None:
        return None
    if value not in (0, 1):
        raise WireError('invalid_boolean')
    return bool(value)


def summarize(message):
    """Return a whitelist summary, or None. Each DeviceState stands alone.

    Optional protobuf defaults are NOT observations. Absent fields and unknown
    enums remain None. Never merge successive partial DeviceState messages.
    """
    root = _fields(message)
    event = _message(root, 85)
    raw_state = _one(event, 34, 2)
    if raw_state is None:
        return None
    state = _fields(raw_state)
    mics, alice, alarms = _message(state, 1), _message(state, 2), _message(state, 5)
    update, reminder, call = _message(state, 7), _message(state, 10), _message(state, 13)
    selected = {
        'mics': _enum(mics, 1, MICS), 'alice': _enum(alice, 1, ALICE),
        'alarmPlaying': _boolean(alarms, 2), 'timerPlaying': _boolean(alarms, 3),
        'configuration': _enum(state, 6, CONFIG), 'update': _enum(update, 1, UPDATE),
        'reminderPlaying': _boolean(reminder, 1),
        'notification': _enum(state, 12, NOTIFICATIONS), 'call': _enum(call, 3, CALL),
    }
    # Candidate means only these whitelisted state fields are quiet. It is NOT
    # proof of LED-owner handoff, complete native error coverage, or RGB access.
    expected = {'mics': 'on', 'alice': 'idle', 'alarmPlaying': False,
                'timerPlaying': False, 'configuration': 'configured', 'update': 'none',
                'reminderPlaying': False, 'notification': 'none', 'call': 'no_call'}
    unknown = [key for key, value in selected.items() if value is None]
    blockers = [key for key, value in selected.items() if value is not None and value != expected[key]]
    return {'profile': PROFILE, 'deviceStateObserved': True, 'fields': selected,
            'unknown': unknown, 'blockers': blockers,
            'quietCandidate': not unknown and not blockers,
            'renderPermit': False, 'nativeOwnershipVerified': False}


def observe(stream, seconds, clock=time.monotonic):
    """Only recv/settimeout; sends zero bytes and retains no native payload."""
    began = clock()
    deadline = began + seconds
    pending = bytearray()
    received = frames = selected_frames = 0
    latest = None
    observed_at = None
    reason = 'deadline'
    while clock() < deadline and received < MAX_BYTES and frames < MAX_FRAMES:
        stream.settimeout(max(.001, min(.25, deadline - clock())))
        try:
            data = stream.recv(min(4096, MAX_BYTES - received))
        except socket.timeout:
            continue
        if not data:
            reason = 'eof'
            break
        received += len(data)
        pending.extend(data)
        while len(pending) >= 4 and frames < MAX_FRAMES:
            count = struct.unpack_from('<I', pending)[0]
            if not 1 <= count <= MAX_FRAME:
                raise WireError('frame_size')
            if len(pending) < count + 4:
                break
            message = bytes(pending[4:count + 4])
            del pending[:count + 4]
            frames += 1
            value = summarize(message)
            if value is not None:
                latest = value
                selected_frames += 1
                observed_at = clock()
    if received == MAX_BYTES:
        reason = 'byte_limit'
    elif frames == MAX_FRAMES:
        reason = 'frame_limit'
    # Age describes arrival only. There is no native timestamp/version in the
    # inspected DeviceState; an open TCP connection cannot prove current state.
    age_ms = None if observed_at is None else max(0, round((clock() - observed_at) * 1000))
    return {'profile': PROFILE, 'profileMaindSha256': MAIND_SHA256,
            'declaredProfileOnly': True, 'nativeIpcBytesSent': 0,
            'receivedBytes': received, 'receivedFrames': frames,
            'deviceStateFrames': selected_frames, 'reason': reason,
            'partialFrameDiscarded': bool(pending), 'arrivalAgeMs': age_ms,
            'nativeTimestampAvailable': False, 'renderPermit': False,
            'latest': latest}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', choices=[PROFILE], required=True)
    parser.add_argument('--port', type=int, required=True,
                        help='Existing authorized host forward to native loopback 9910')
    parser.add_argument('--seconds', type=float, default=3)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535 or not .1 <= args.seconds <= 30:
        parser.error('Port or duration out of bounds')
    try:
        with socket.create_connection(('127.0.0.1', args.port), timeout=2) as stream:
            result = observe(stream, args.seconds)
    except (WireError, OSError) as error:
        print(json.dumps({'profile': PROFILE, 'renderPermit': False,
                          'error': str(error) if isinstance(error, WireError) else type(error).__name__}))
        return 1
    print(json.dumps(result, ensure_ascii=True, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
