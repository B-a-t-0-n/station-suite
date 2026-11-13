"""Bounded, independently watched uniProxyUrl window. No ADB or network actions.

The CLI uses only the fixed StationLab sync path. Backups may contain credentials:
they live under the user's protected LOCALAPPDATA, never in a report/workspace.
start arms a separate process BEFORE apply can change the active file. This does
not prove delivery to Quasar or native reconnect; the operator checks those.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import copy
import hashlib
import json
import math
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time

from setup_speechify import _current_user_sid, _restrict_file
from suite_paths import private_root

PC_URL = 'wss://uniproxy.alice.yandex.net/uni.ws'
LOCAL_URL = 'ws://127.0.0.1:17682/uni.ws'
MAX_BYTES = 65536
MAX_LEASE_SECONDS = 300
PHASES = {'prepared', 'armed', 'applied', 'restored', 'drift', 'failed'}
TERMINAL = {'restored', 'drift', 'failed'}
OUTCOMES = {'PREPARED', 'WATCHDOG_READY', 'CANDIDATE_APPLIED',
            'BASELINE_RESTORED', 'BASELINE_ALREADY_PRESENT',
            'ACTIVE_FILE_DRIFT_NOT_OVERWRITTEN'}


class WindowError(Exception):
    """Fixed codes only; exception bodies and configuration never reach output."""


def default_paths():
    return (private_root() / 'pc/native-baseline.json', private_root() / 'native-config-windows')


def digest(data):
    return hashlib.sha256(data).hexdigest()


def bounded_read(path):
    # Windows readers/replace can briefly conflict on sharing permissions.
    # Retry only that transient OS error, never malformed data or drift.
    until = time.monotonic() + 1
    while True:
        try:
            with Path(path).open('rb') as stream:
                data = stream.read(MAX_BYTES + 1)
            break
        except PermissionError:
            if time.monotonic() >= until:
                raise
            time.sleep(0.01)
    if len(data) > MAX_BYTES:
        raise WindowError('FILE_TOO_LARGE')
    return data


def parse(data):
    def pairs(values):
        result = {}
        for key, value in values:
            if key in result:
                raise WindowError('INVALID_JSON')
            result[key] = value
        return result
    def constant(_):
        raise WindowError('INVALID_JSON')
    try:
        value = json.loads(data.decode('utf-8-sig'), object_pairs_hook=pairs, parse_constant=constant)
        if type(value) is not dict:
            raise WindowError('INVALID_JSON')
        return value
    except WindowError:
        raise
    except (ValueError, UnicodeError, RecursionError):
        raise WindowError('INVALID_JSON') from None


def encoded(value):
    return (json.dumps(value, ensure_ascii=True, indent=2, allow_nan=False) + '\n').encode('ascii')


def candidate_for(baseline):
    value = parse(baseline)
    try:
        if value.get('status') != 'ok' or value['config']['system_config']['uniProxyUrl'] != PC_URL:
            raise WindowError('BASELINE_URL_MISMATCH')
        candidate = copy.deepcopy(value)
        candidate['config']['system_config']['uniProxyUrl'] = LOCAL_URL
    except (KeyError, TypeError):
        raise WindowError('BASELINE_SCHEMA_MISMATCH') from None
    # The generated candidate has exactly one semantic change.
    check = copy.deepcopy(candidate)
    check['config']['system_config']['uniProxyUrl'] = PC_URL
    if check != value:
        raise WindowError('NON_VOICE_CHANGE')
    return encoded(candidate)


def protect_directory(path):
    if os.name == 'nt':
        sid = _current_user_sid()
        subprocess.run(['icacls.exe', str(path), '/inheritance:r', '/grant:r',
                        f'*{sid}:(OI)(CI)F', '*S-1-5-18:(OI)(CI)F'],
                       capture_output=True, timeout=15, check=True,
                       creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    else:
        os.chmod(path, 0o700)


def atomic_write(path, data, *, protect=False, before_replace=None):
    """Never truncate active config; protect an empty temp before secret bytes."""
    path = Path(path)
    temporary = path.parent / ('.native-window-' + secrets.token_hex(16) + '.tmp')
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        if protect:
            _restrict_file(temporary)
        with os.fdopen(descriptor, 'wb') as stream:
            descriptor = None
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        until = time.monotonic() + 1
        while True:
            if before_replace is not None:
                before_replace()
            try:
                os.replace(temporary, path)
                break
            except PermissionError:
                if time.monotonic() >= until:
                    raise
                time.sleep(0.01)
    finally:
        if descriptor is not None:
            os.close(descriptor)
        temporary.unlink(missing_ok=True)


@contextmanager
def mutation_lock(window, timeout=5):
    """OS lock is released on process death; no orphaned mutation sentinel."""
    lock = (Path(window) / 'mutation.lock').open('a+b')
    acquired = False
    try:
        if lock.seek(0, 2) == 0:
            lock.write(b'0'); lock.flush()
        deadline = time.monotonic() + timeout
        while True:
            try:
                lock.seek(0)
                if os.name == 'nt':
                    import msvcrt
                    msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                acquired = True
                break
            except (OSError, BlockingIOError):
                if time.monotonic() >= deadline:
                    raise WindowError('MUTATION_BUSY') from None
                time.sleep(0.025)
        yield
    finally:
        if acquired:
            lock.seek(0)
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(lock.fileno(), fcntl.LOCK_UN)
        lock.close()


def process_alive(pid):
    if type(pid) is not int or pid <= 0:
        return False
    if os.name == 'nt':
        # os.kill(pid, 0) is not a safe Windows liveness primitive.
        import ctypes
        from ctypes import wintypes
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        kernel.OpenProcess.argtypes = (wintypes.DWORD, wintypes.BOOL, wintypes.DWORD)
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.GetExitCodeProcess.argtypes = (wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD))
        kernel.CloseHandle.argtypes = (wintypes.HANDLE,)
        handle = kernel.OpenProcess(0x1000, False, pid)
        if not handle:
            return False
        try:
            code = wintypes.DWORD()
            return bool(kernel.GetExitCodeProcess(handle, ctypes.byref(code))) and code.value == 259
        finally:
            kernel.CloseHandle(handle)
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def load(window):
    window = Path(window)
    state = parse(bounded_read(window / 'state.json'))
    if (state.get('phase') not in PHASES or state.get('version') != 1
            or state.get('outcome') not in OUTCOMES
            or type(state.get('active')) is not str
            or not Path(state['active']).is_absolute()
            or type(state.get('watchPid')) is not int or state['watchPid'] < 0):
        raise WindowError('INVALID_STATE')
    for key in ('baselineSha256', 'candidateSha256', 'helperSha256'):
        value = state.get(key)
        if type(value) is not str or len(value) != 64 or any(c not in '0123456789abcdef' for c in value):
            raise WindowError('INVALID_STATE')
    if type(state.get('leaseSeconds')) is not int or not 1 <= state['leaseSeconds'] <= MAX_LEASE_SECONDS:
        raise WindowError('INVALID_STATE')
    for key in ('heartbeatMonotonic', 'deadlineMonotonic'):
        value = state.get(key)
        if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
            raise WindowError('INVALID_STATE')
    baseline = bounded_read(window / 'baseline.json')
    candidate = bounded_read(window / 'candidate.json')
    if digest(baseline) != state['baselineSha256'] or digest(candidate) != state['candidateSha256']:
        raise WindowError('BACKUP_HASH_MISMATCH')
    if candidate_for(baseline) != candidate:
        raise WindowError('BACKUP_DIFF_MISMATCH')
    return state, baseline, candidate


def save_state(window, state):
    atomic_write(Path(window) / 'state.json', encoded(state))


def release_owner(window):
    window = Path(window)
    owner = window.parent / 'owner.json'
    try:
        value = parse(bounded_read(owner))
        if value.get('window') == window.name:
            owner.unlink()
    except FileNotFoundError:
        pass


def prepare(active, store, expected_sha256, lease_seconds=120):
    if type(lease_seconds) is not int or not 1 <= lease_seconds <= MAX_LEASE_SECONDS:
        raise WindowError('INVALID_LEASE')
    if Path(active).is_symlink() or Path(store).is_symlink():
        raise WindowError('INVALID_ACTIVE_PATH')
    active, store = Path(active).resolve(), Path(store).resolve()
    if active.is_relative_to(store):
        raise WindowError('INVALID_ACTIVE_PATH')
    baseline = bounded_read(active)
    if digest(baseline) != expected_sha256:
        raise WindowError('BASELINE_HASH_MISMATCH')
    candidate = candidate_for(baseline)
    store.mkdir(parents=True, exist_ok=True)
    protect_directory(store)
    name = secrets.token_hex(16)
    window = store / name
    # Exclusive global ownership prevents two cooperating windows on one config.
    owner = store / 'owner.json'
    try:
        fd = os.open(owner, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        raise WindowError('WINDOW_ALREADY_OWNED') from None
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(encoded({'window': name})); stream.flush(); os.fsync(stream.fileno())
        window.mkdir(mode=0o700)
        atomic_write(window / 'baseline.json', baseline, protect=True)
        atomic_write(window / 'candidate.json', candidate, protect=True)
        save_state(window, {'version': 1, 'phase': 'prepared', 'active': str(active),
                            'baselineSha256': digest(baseline), 'candidateSha256': digest(candidate),
                            'helperSha256': digest(Path(__file__).read_bytes()),
                            'leaseSeconds': lease_seconds, 'watchPid': 0,
                            'heartbeatMonotonic': 0, 'deadlineMonotonic': 0,
                            'outcome': 'PREPARED'})
        return window
    except Exception:
        # No active-file change has occurred. Preserve partial backup, release ownership.
        release_owner(window)
        raise


def restore(window):
    with mutation_lock(window):
        state, baseline, _ = load(window)
        current = digest(bounded_read(state['active']))
        if current == state['candidateSha256']:
            def guard():
                if digest(bounded_read(state['active'])) != state['candidateSha256']:
                    raise WindowError('ACTIVE_FILE_DRIFT')
            try:
                atomic_write(state['active'], baseline, protect=True, before_replace=guard)
            except WindowError as error:
                if str(error) != 'ACTIVE_FILE_DRIFT':
                    raise
                state.update(phase='drift', outcome='ACTIVE_FILE_DRIFT_NOT_OVERWRITTEN')
                save_state(window, state)
                release_owner(window)
                return safe_status(window)
            if digest(bounded_read(state['active'])) != state['baselineSha256']:
                raise WindowError('RESTORE_READBACK_MISMATCH')
            state.update(phase='restored', outcome='BASELINE_RESTORED')
        elif current == state['baselineSha256']:
            state.update(phase='restored', outcome='BASELINE_ALREADY_PRESENT')
        else:
            state.update(phase='drift', outcome='ACTIVE_FILE_DRIFT_NOT_OVERWRITTEN')
        save_state(window, state)
        release_owner(window)
    return safe_status(window)


def apply(window):
    with mutation_lock(window):
        state, _, candidate = load(window)
        def guard():
            now = time.monotonic()
            deadline, heartbeat = state['deadlineMonotonic'], state['heartbeatMonotonic']
            if (state['phase'] != 'armed' or now >= deadline or now - heartbeat > 5
                    or heartbeat > now + 1 or not process_alive(state['watchPid'])):
                raise WindowError('WATCHDOG_NOT_READY')
            if digest(bounded_read(state['active'])) != state['baselineSha256']:
                raise WindowError('ACTIVE_FILE_DRIFT')
        guard()
        if digest(Path(__file__).read_bytes()) != state.get('helperSha256'):
            raise WindowError('HELPER_CHANGED')
        atomic_write(state['active'], candidate, protect=True, before_replace=guard)
        if digest(bounded_read(state['active'])) != state['candidateSha256']:
            raise WindowError('APPLY_READBACK_MISMATCH')
        state.update(phase='applied', outcome='CANDIDATE_APPLIED')
        save_state(window, state)
    return safe_status(window)


def watch(window):
    """Independent process; expiry restores even if apply's process disappears."""
    with mutation_lock(window):
        state, _, _ = load(window)
        if state['phase'] != 'prepared':
            raise WindowError('WINDOW_NOT_PREPARED')
        now = time.monotonic()
        state.update(phase='armed', watchPid=os.getpid(), heartbeatMonotonic=now,
                     deadlineMonotonic=now + state['leaseSeconds'], outcome='WATCHDOG_READY')
        save_state(window, state)
    try:
        while True:
            with mutation_lock(window):
                state, _, _ = load(window)
                if state['phase'] in TERMINAL:
                    return safe_status(window)
                now = time.monotonic()
                if now >= state['deadlineMonotonic']:
                    break
                state['heartbeatMonotonic'] = now
                save_state(window, state)
            time.sleep(min(0.25, max(0, state['deadlineMonotonic'] - time.monotonic())))
    finally:
        # This also handles KeyboardInterrupt and errors; hashes still guard drift.
        restore(window)
    return safe_status(window)


def start(active, store, expected_sha256, lease_seconds=120):
    window = prepare(active, store, expected_sha256, lease_seconds)
    options = {'stdin': subprocess.DEVNULL, 'stdout': subprocess.DEVNULL,
               'stderr': subprocess.DEVNULL, 'close_fds': True}
    if os.name == 'nt':
        options['creationflags'] = (subprocess.CREATE_NO_WINDOW
                                    | subprocess.CREATE_NEW_PROCESS_GROUP)
    else:
        options['start_new_session'] = True
    process = None
    try:
        process = subprocess.Popen([sys.executable, str(Path(__file__).resolve()),
                                    'watch', '--window', str(window)], **options)
        until = time.monotonic() + 8
        while time.monotonic() < until:
            state, _, _ = load(window)
            if state['phase'] == 'armed' and state['watchPid'] == process.pid and process.poll() is None:
                return window
            if process.poll() is not None:
                raise WindowError('WATCHDOG_EXITED_BEFORE_READY')
            time.sleep(0.05)
        raise WindowError('WATCHDOG_READY_TIMEOUT')
    except Exception:
        # Never apply if child readiness is unproven; prepared config is still baseline.
        restore(window)
        raise


def safe_status(window):
    state, _, _ = load(window)
    remaining = state.get('deadlineMonotonic', 0) - time.monotonic()
    return {'phase': state['phase'], 'outcome': state['outcome'],
            'baselineSha256': state['baselineSha256'], 'candidateSha256': state['candidateSha256'],
            'activeSha256': digest(bounded_read(state['active'])),
            'watchdogAlive': process_alive(state.get('watchPid')),
            'remainingSeconds': round(max(0, remaining), 3) if state['phase'] in {'armed', 'applied'} else 0,
            'deliveryVerified': False, 'nativeReconnectVerified': False}


def validated_cli_window(value, store):
    path = Path(value).resolve()
    if path.parent != store.resolve() or len(path.name) != 32 or any(c not in '0123456789abcdef' for c in path.name):
        raise WindowError('INVALID_WINDOW_PATH')
    state, _, _ = load(path)
    if Path(state['active']).resolve() != default_paths()[0].resolve():
        raise WindowError('INVALID_ACTIVE_PATH')
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['prepare', 'start', 'watch', 'apply', 'restore', 'status'])
    parser.add_argument('--window')
    parser.add_argument('--expected-sha256')
    parser.add_argument('--lease-seconds', type=int, default=120)
    args = parser.parse_args()
    try:
        active, store = default_paths()
        if args.command in {'prepare', 'start'}:
            if (args.expected_sha256 is None or len(args.expected_sha256) != 64
                    or any(c not in '0123456789abcdef' for c in args.expected_sha256)):
                raise WindowError('EXPECTED_SHA256_REQUIRED')
            function = prepare if args.command == 'prepare' else start
            window = function(active, store, args.expected_sha256, args.lease_seconds)
            result = safe_status(window)
            result['windowDirectory'] = str(window)
        else:
            if not args.window:
                raise WindowError('WINDOW_REQUIRED')
            window = validated_cli_window(args.window, store)
            result = {'watch': watch, 'apply': apply, 'restore': restore, 'status': safe_status}[args.command](window)
        print(json.dumps(result, ensure_ascii=True), flush=True)
    except WindowError as error:
        print(json.dumps({'ok': False, 'code': str(error)}), flush=True)
        raise SystemExit(1) from None
    except Exception:
        print(json.dumps({'ok': False, 'code': 'LOCAL_OPERATION_FAILED'}), flush=True)
        raise SystemExit(1) from None


if __name__ == '__main__':
    main()
