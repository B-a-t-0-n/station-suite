"""Station runtime supervisor. Importing this module performs no I/O or device work.

Only `run` opts into ADB, a loopback CONNECT listener and a guarded endpoint lease.
TLS remains end-to-end inside the Android clients. An independent process restores
the private, byte-exact PC baseline when the supervisor stops renewing its lease.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import math
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import sys
import threading
import time

import giga_tunnel as tunnel
import native_config_window as files
from windows_physical_source import WindowsPhysicalSource, PhysicalSourceError

from suite_paths import private_root, required_env, default_adb
BASELINE_SHA = os.environ.get('STATION_BASELINE_SHA256', '0' * 64)
POLICY_SHA = os.environ.get('STATION_POLICY_SHA256', '0' * 64)
POLICY = private_root() / 'pc/config-policy.json'
ADB = default_adb()
SERIAL, PACKAGE, PORT, INTERFACE = os.environ.get('STATION_SERIAL', '192.168.77.100:5555'), 'local.station.voicebridge', 18443, int(os.environ.get('STATION_WAN_INTERFACE', '0'))
PC_SCRIPT = str(Path(__file__).with_name('retired_pc_voice_server.py'))
MAX_LEASE, POLL_SECONDS, MAX_STATUS_AGE = 30.0, 2.0, 10.0
_PHYSICAL_SOURCE = WindowsPhysicalSource(interface_index=INTERFACE)  # Selected physical USB uplink, fresh IPv4 per connect.
HIDDEN = getattr(subprocess, 'CREATE_NO_WINDOW', 0)
SAFE_PHASES = {'starting', 'pc', 'local', 'stopping', 'stopped', 'drift', 'failed'}


class SupervisorError(Exception):
    """Only fixed codes are printed; subprocess output and configuration stay private."""


def store_path():
    # Store/MSIX processes may see different LOCALAPPDATA overlays. Keep the
    # controller, detached guard and external status/stop clients on one actual
    # per-user directory outside AppData and outside the workspace.
    return private_root() / 'runtime-supervisor'


def read_json(path):
    return files.parse(files.bounded_read(path))


def save_json(path, value):
    # All control/status parents are restricted before creation. Their temporary
    # files inherit that ACL; avoid launching whoami/icacls on every heartbeat.
    # Credential-bearing baseline/candidate/active writes use protect=True below.
    files.atomic_write(path, files.encoded(value))


def _command(arguments, timeout=8, limit=65536):
    result = subprocess.run([str(x) for x in arguments], capture_output=True,
                            timeout=timeout, creationflags=HIDDEN)
    if result.returncode or len(result.stdout) > limit:
        raise SupervisorError('LOCAL_COMMAND_FAILED')
    return result.stdout.decode('utf-8-sig', errors='strict').strip()


def adb(*arguments):
    return _command([ADB, '-s', SERIAL, *arguments])


def powershell(script):
    raw = _command(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command', script])
    try:
        return json.loads(raw)
    except (ValueError, UnicodeError):
        raise SupervisorError('INVALID_LOCAL_STATUS') from None


def physical_source():
    """Same pinned physical adapter; each call discovers fresh Preferred DHCP IPv4."""
    try:
        return _PHYSICAL_SOURCE()
    except PhysicalSourceError:
        raise SupervisorError('PHYSICAL_INTERFACE_UNAVAILABLE') from None


def pc_listener_ready():
    # Do not connect to the serial voice listener merely to test its health.
    script_path = PC_SCRIPT.replace('/', '\\').replace("'", "''")
    return powershell("$ErrorActionPreference='Stop'; $ok=$false; "
        "foreach($c in @(Get-NetTCPConnection -State Listen -LocalAddress '192.168.77.2' "
        "-LocalPort 443 -ErrorAction SilentlyContinue)){"
        "$p=Get-CimInstance Win32_Process -Filter ('ProcessId='+$c.OwningProcess);"
        "if($p -and $p.Name -in @('python.exe','pythonw.exe')){"
        "if(-not $p.CommandLine -or $p.CommandLine.Replace('/','\\').Contains('" + script_path + "')){$ok=$true}}};"
        "$ok|ConvertTo-Json -Compress") is True


class PersistentTransport:
    """Six fixed workers; each opaque connection has its own ten-minute cap."""
    def __init__(self, source=physical_source):
        self.source = source
        self.stop_event, self.ready = threading.Event(), threading.Event()
        self.lock = threading.Lock()
        self.sockets = set()
        self.slots = threading.BoundedSemaphore(tunnel.MAX_WORKERS)
        self.thread = None
        self.failed = False

    def start(self):
        self.thread = threading.Thread(target=self._run, name='RuntimeConnect', daemon=True)
        self.thread.start()
        if not self.ready.wait(5) or not self.alive():
            self.stop()
            raise SupervisorError('TRANSPORT_START_FAILED')

    def alive(self):
        return (self.ready.is_set() and self.thread is not None and self.thread.is_alive()
                and not self.failed and not self.stop_event.is_set())

    def _track(self, stream):
        with self.lock:
            if self.stop_event.is_set():
                stream.close()
                raise OSError('closed')
            self.sockets.add(stream)

    def _connection(self, client):
        upstream, established = None, False
        deadline = time.monotonic() + 600
        try:
            target, initial = tunnel.read_connect(client, min(deadline, time.monotonic() + tunnel.HEADER_TIMEOUT))
            index, address = self.source()  # Re-discover DHCP source before every connection.
            upstream = tunnel.connect_upstream(target, min(deadline, time.monotonic() + tunnel.CONNECT_TIMEOUT),
                                                interface_index=index, source_ip=address)
            self._track(upstream)
            tunnel._reply(client, '200 Connection Established', deadline)
            established = True
            tunnel.relay(client, upstream, deadline, initial)
        except tunnel.Denied:
            try: tunnel._reply(client, '403 Forbidden', deadline)
            except OSError: pass
        except (OSError, ValueError, SupervisorError, subprocess.SubprocessError):
            if not established:
                try: tunnel._reply(client, '502 Bad Gateway', deadline)
                except OSError: pass
        finally:
            for stream in (client, upstream):
                if stream is not None:
                    with self.lock:
                        self.sockets.discard(stream)
                    stream.close()
            self.slots.release()

    def _run(self):
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener, \
                    ThreadPoolExecutor(max_workers=tunnel.MAX_WORKERS, thread_name_prefix='RuntimeOpaque') as pool:
                if hasattr(socket, 'SO_EXCLUSIVEADDRUSE'):
                    listener.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
                listener.bind(('127.0.0.1', PORT)); listener.listen(tunnel.MAX_WORKERS)
                listener.settimeout(0.25); self.ready.set()
                while not self.stop_event.is_set():
                    try: client, _ = listener.accept()
                    except socket.timeout: continue
                    except OSError:
                        self.failed = True
                        raise
                    if not self.slots.acquire(blocking=False):
                        client.close(); continue
                    try:
                        self._track(client); pool.submit(self._connection, client)
                    except Exception:
                        client.close(); self.slots.release(); raise
        except Exception:
            self.failed = True
        finally:
            self.ready.set()

    def stop(self):
        self.stop_event.set()
        with self.lock:
            for stream in tuple(self.sockets):
                try: stream.shutdown(socket.SHUT_RDWR)
                except OSError: pass
                stream.close()
            self.sockets.clear()
        if self.thread is not None:
            self.thread.join(2)


def reverse_target(raw):
    targets = []
    for line in raw.splitlines():
        parts = line.split()
        if len(parts) == 3 and parts[1] == 'tcp:18443':
            targets.append(parts[2])
    if len(targets) > 1:
        raise SupervisorError('REVERSE_CONFLICT')
    return targets[0] if targets else None


class Reverse:
    def __init__(self, execute=None, ownership=None):
        self.execute, self.owned, self.ownership = execute or adb, False, ownership

    def ensure(self):
        target = reverse_target(self.execute('reverse', '--list'))
        if target is not None:
            if not self.owned or target != 'tcp:18443':
                raise SupervisorError('REVERSE_CONFLICT')
            return
        self.owned = False
        self.execute('reverse', '--no-rebind', 'tcp:18443', 'tcp:18443')
        self.owned = True  # Creation succeeded; keep ownership even if readback fails.
        if self.ownership is not None:
            save_json(self.ownership, {'version': 1, 'supervisorPid': os.getpid(), 'port': PORT})
        if reverse_target(self.execute('reverse', '--list')) != 'tcp:18443':
            raise SupervisorError('REVERSE_NOT_READY')

    def close(self):
        if self.owned:
            if reverse_target(self.execute('reverse', '--list')) == 'tcp:18443':
                self.execute('reverse', '--remove', 'tcp:18443')
            self.owned = False
            if self.ownership is not None:
                Path(self.ownership).unlink(missing_ok=True)


def recover_reverse(store, execute=None):
    """After a crash, remove only our recorded, still exact reverse mapping."""
    marker = Path(store) / 'reverse-owner.json'
    execute = execute or adb
    if not marker.exists():
        return
    value = read_json(marker)
    if (value.get('version') != 1 or value.get('port') != PORT
            or type(value.get('supervisorPid')) is not int or value['supervisorPid'] <= 0):
        raise SupervisorError('REVERSE_CONFLICT')
    if files.process_alive(value['supervisorPid']):
        raise SupervisorError('REVERSE_CONFLICT')
    target = reverse_target(execute('reverse', '--list'))
    if target not in (None, 'tcp:18443'):
        raise SupervisorError('REVERSE_CONFLICT')
    if target is not None:
        execute('reverse', '--remove', 'tcp:18443')
    marker.unlink()


class Health:
    """Only documented counters/booleans are accepted; text and unknown fields disappear."""
    def __init__(self):
        self.pid = self.sequence = None
        self.progress_at = None
        self.progress_seen = False

    def inspect(self, value, *, now_wall=None, now_mono=None):
        now_wall = time.time() if now_wall is None else now_wall
        now_mono = time.monotonic() if now_mono is None else now_mono
        if type(value) is not dict:
            return False, 'APP_STATUS_INVALID'
        for field in ('enabled', 'listenerReady', 'credentialsConfigured', 'circuitOpen', 'healthy'):
            if type(value.get(field)) is not bool:
                return False, 'APP_STATUS_INVALID'
        for field in ('updatedAtMs', 'heartbeatSeq', 'servicePid', 'activeWorkers'):
            if type(value.get(field)) is not int or value[field] < 0:
                return False, 'APP_STATUS_INVALID'
        if value['servicePid'] == 0 or value['activeWorkers'] > 1:
            return False, 'APP_STATUS_INVALID'
        age = now_wall - value['updatedAtMs'] / 1000
        if not -2 <= age <= MAX_STATUS_AGE:
            return False, 'APP_STATUS_STALE'
        identity, sequence = value['servicePid'], value['heartbeatSeq']
        if self.pid != identity:
            self.pid, self.sequence, self.progress_at = identity, sequence, now_mono
            self.progress_seen = False
            return False, 'APP_HEARTBEAT_PENDING'
        if sequence < self.sequence:
            return False, 'APP_HEARTBEAT_REGRESSED'
        if sequence > self.sequence:
            self.sequence, self.progress_at = sequence, now_mono
            self.progress_seen = True
        elif now_mono - self.progress_at >= MAX_STATUS_AGE:
            return False, 'APP_HEARTBEAT_STALE'
        if not value['enabled']:
            return False, 'APP_DISABLED'
        if value['circuitOpen']:
            return False, 'APP_CIRCUIT_OPEN'
        if not value['credentialsConfigured']:
            return False, 'APP_CREDENTIALS_MISSING'
        if not value['healthy'] or not value['listenerReady']:
            return False, 'APP_NOT_READY'
        # A first observation alone cannot establish heartbeat progression.
        if not self.progress_seen:
            return False, 'APP_HEARTBEAT_PENDING'
        return True, 'READY'


def prepare(active, store, baseline_sha, policy, policy_sha):
    active, store, policy = Path(active).resolve(), Path(store).resolve(), Path(policy).resolve()
    if files.digest(files.bounded_read(policy)) != policy_sha:
        raise SupervisorError('POLICY_HASH_MISMATCH')
    baseline = files.bounded_read(active)
    if files.digest(baseline) != baseline_sha:
        raise SupervisorError('BASELINE_HASH_MISMATCH')
    candidate = files.candidate_for(baseline)
    store.mkdir(parents=True, exist_ok=True); files.protect_directory(store)
    folder = store / secrets.token_hex(16)
    folder.mkdir(mode=0o700)
    files.protect_directory(folder)
    files.atomic_write(folder / 'baseline.json', baseline, protect=True)
    files.atomic_write(folder / 'candidate.json', candidate, protect=True)
    state = {'version': 1, 'token': secrets.token_hex(32), 'supervisorPid': os.getpid(),
             'active': str(active), 'policy': str(policy), 'baselineSha256': baseline_sha,
             'candidateSha256': files.digest(candidate), 'policySha256': policy_sha}
    save_json(folder / 'state.json', state)
    save_json(store / 'owner.json', {'folder': folder.name, 'supervisorPid': os.getpid()})
    return folder


def load(folder):
    folder = Path(folder)
    state = read_json(folder / 'state.json')
    if (state.get('version') != 1 or type(state.get('token')) is not str
            or not re.fullmatch('[a-f0-9]{64}', state['token'])
            or type(state.get('supervisorPid')) is not int or state['supervisorPid'] <= 0):
        raise SupervisorError('INVALID_GUARD_STATE')
    for field in ('active', 'policy'):
        if type(state.get(field)) is not str or not Path(state[field]).is_absolute():
            raise SupervisorError('INVALID_GUARD_STATE')
    for field in ('baselineSha256', 'candidateSha256', 'policySha256'):
        if type(state.get(field)) is not str or not re.fullmatch('[a-f0-9]{64}', state[field]):
            raise SupervisorError('INVALID_GUARD_STATE')
    baseline, candidate = files.bounded_read(folder / 'baseline.json'), files.bounded_read(folder / 'candidate.json')
    if (files.digest(baseline) != state['baselineSha256']
            or files.digest(candidate) != state['candidateSha256']
            or files.candidate_for(baseline) != candidate):
        raise SupervisorError('INVALID_GUARD_BACKUP')
    return state, baseline, candidate


def switch(state, baseline, candidate, local):
    target, desired = (candidate, state['candidateSha256']) if local else (baseline, state['baselineSha256'])
    known = {state['baselineSha256'], state['candidateSha256']}
    def guard():
        current = files.digest(files.bounded_read(state['active']))
        if current not in known:
            raise SupervisorError('ACTIVE_FILE_DRIFT')
        if local and files.digest(files.bounded_read(state['policy'])) != state['policySha256']:
            raise SupervisorError('POLICY_HASH_MISMATCH')
    guard()
    if files.digest(files.bounded_read(state['active'])) != desired:
        files.atomic_write(state['active'], target, protect=True, before_replace=guard)
    if files.digest(files.bounded_read(state['active'])) != desired:
        raise SupervisorError('ACTIVE_READBACK_MISMATCH')


def lease_valid(value, state, now):
    if (type(value) is not dict or value.get('token') != state['token']
            or value.get('supervisorPid') != state['supervisorPid']
            or value.get('desired') not in ('pc', 'local')):
        return False
    for field in ('heartbeat', 'deadline'):
        if type(value.get(field)) not in (int, float) or not math.isfinite(value[field]):
            return False
    return (0 <= now - value['heartbeat'] <= MAX_LEASE
            and value['heartbeat'] <= value['deadline'] <= value['heartbeat'] + MAX_LEASE
            and now < value['deadline'])


def renew(folder, state, desired, *, now=None):
    if desired not in ('pc', 'local'):
        raise SupervisorError('INVALID_DESIRED_MODE')
    now = time.monotonic() if now is None else now
    save_json(Path(folder) / 'lease.json', {'token': state['token'], 'supervisorPid': state['supervisorPid'],
                                         'heartbeat': now, 'deadline': now + MAX_LEASE, 'desired': desired})


def guard(folder, *, interval=0.25, alive=files.process_alive):
    folder = Path(folder)
    state, baseline, candidate = load(folder)
    last_phase, reason = 'pc', 'STARTING'
    try:
        switch(state, baseline, candidate, False)
        save_json(folder / 'guard-status.json', {'phase': 'pc', 'guardPid': os.getpid(), 'reason': 'READY'})
        published = ('pc', 'READY')
        while not (folder / 'stop').exists():
            now = time.monotonic()
            try: lease = read_json(folder / 'lease.json')
            except (OSError, ValueError, files.WindowError): lease = None
            valid = alive(state['supervisorPid']) and lease_valid(lease, state, now)
            local = bool(valid and lease['desired'] == 'local')
            try:
                switch(state, baseline, candidate, local)
                last_phase, reason = ('local', 'READY') if local else ('pc', 'LEASE_NOT_LOCAL')
            except SupervisorError as error:
                if str(error) == 'ACTIVE_FILE_DRIFT':
                    last_phase, reason = 'drift', 'ACTIVE_FILE_DRIFT'; break
                switch(state, baseline, candidate, False)
                last_phase, reason = 'pc', 'GUARD_CHECK_FAILED'
            if published != (last_phase, reason):
                save_json(folder / 'guard-status.json', {'phase': last_phase, 'guardPid': os.getpid(), 'reason': reason})
                published = (last_phase, reason)
            if not valid and not alive(state['supervisorPid']):
                break
            time.sleep(interval)
    finally:
        try:
            switch(state, baseline, candidate, False)
            last_phase = 'stopped'
        except SupervisorError as error:
            last_phase = 'drift' if str(error) == 'ACTIVE_FILE_DRIFT' else 'failed'
            reason = 'RESTORE_FAILED'
        save_json(folder / 'guard-status.json', {'phase': last_phase, 'guardPid': os.getpid(), 'reason': reason})


def start_guard(folder):
    options = {'stdin': subprocess.DEVNULL, 'stdout': subprocess.DEVNULL,
               'stderr': subprocess.DEVNULL, 'close_fds': True}
    if os.name == 'nt':
        options['creationflags'] = HIDDEN | subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        options['start_new_session'] = True
    child = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), 'guard', '--folder', str(folder)], **options)
    until = time.monotonic() + 8
    while time.monotonic() < until:
        if child.poll() is not None:
            raise SupervisorError('GUARD_START_FAILED')
        try: value = read_json(Path(folder) / 'guard-status.json')
        except OSError: value = {}
        if value.get('phase') == 'pc' and value.get('guardPid') == child.pid:
            return child
        time.sleep(0.05)
    raise SupervisorError('GUARD_START_FAILED')


def validated_guard_folder(value, store):
    """Require a direct private child by directory identity, including MSIX aliases."""
    folder = Path(value or '').resolve()
    if not re.fullmatch('[a-f0-9]{32}', folder.name):
        raise SupervisorError('INVALID_GUARD_FOLDER')
    try:
        # Compare directory identity, so aliases cannot relax the direct-parent
        # boundary. The default store itself is outside MSIX AppData overlays.
        direct_child = folder.is_dir() and folder.parent.samefile(Path(store))
    except OSError:
        direct_child = False
    if not direct_child:
        raise SupervisorError('INVALID_GUARD_FOLDER')
    return folder


def safe_status(store):
    try: raw = read_json(Path(store) / 'status.json')
    except (OSError, files.WindowError): return {'phase': 'stopped', 'running': False}
    result = {'phase': raw.get('phase') if raw.get('phase') in SAFE_PHASES else 'failed'}
    for name in ('running', 'transportReady', 'reverseOwned', 'appReady', 'pcFallbackReady', 'policyIntact'):
        if type(raw.get(name)) is bool: result[name] = raw[name]
    for name in ('supervisorPid', 'updatedAtMs'):
        if type(raw.get(name)) is int and raw[name] >= 0: result[name] = raw[name]
    result['running'] = bool(result.get('running') and files.process_alive(result.get('supervisorPid')))
    # Do not trust arbitrary app/status strings in output.
    result['reason'] = raw.get('reason') if raw.get('reason') in REASONS else 'UNKNOWN'
    return result


REASONS = {'STARTING', 'READY', 'STOPPED', 'CHECK_FAILED', 'PHYSICAL_INTERFACE_UNAVAILABLE',
           'APP_STATUS_INVALID', 'APP_STATUS_STALE', 'APP_HEARTBEAT_PENDING', 'APP_HEARTBEAT_REGRESSED',
           'APP_HEARTBEAT_STALE', 'APP_DISABLED', 'APP_CIRCUIT_OPEN', 'APP_CREDENTIALS_MISSING',
           'APP_NOT_READY', 'POLICY_HASH_MISMATCH', 'PC_FALLBACK_UNAVAILABLE', 'REVERSE_CONFLICT',
           'TRANSPORT_UNAVAILABLE', 'GUARD_UNAVAILABLE'}


def run(*, store=None, active=None, policy=POLICY, baseline_sha=BASELINE_SHA, policy_sha=POLICY_SHA):
    store = store_path() if store is None else Path(store)
    active = files.default_paths()[0] if active is None else Path(active)
    store.mkdir(parents=True, exist_ok=True); files.protect_directory(store)
    with files.mutation_lock(store, timeout=0):
        if (store / 'owner.json').exists():
            old = read_json(store / 'owner.json')
            if files.process_alive(old.get('supervisorPid')):
                raise SupervisorError('ALREADY_RUNNING')
            # A crash may leave its private known candidate. Restore only that hash.
            name = old.get('folder', '')
            if type(name) is not str or not re.fullmatch('[a-f0-9]{32}', name):
                raise SupervisorError('INVALID_OLD_OWNER')
            previous = store / name
            (previous / 'stop').touch()
            state, baseline, candidate = load(previous)
            # Old and new guards must never overlap: a late old finally could
            # otherwise restore the identical candidate used by the new owner.
            until = time.monotonic() + MAX_LEASE + 5
            while time.monotonic() < until:
                try: prior_guard = read_json(previous / 'guard-status.json')
                except OSError: prior_guard = {}
                pid = prior_guard.get('guardPid')
                if not files.process_alive(pid): break
                time.sleep(0.1)
            else:
                raise SupervisorError('OLD_GUARD_STILL_RUNNING')
            switch(state, baseline, candidate, False)
        (store / 'stop').unlink(missing_ok=True)
        folder = prepare(active, store, baseline_sha, policy, policy_sha)
        state, baseline, candidate = load(folder)
        guard_process, transport = None, None
        reverse, health = Reverse(ownership=store / 'reverse-owner.json'), Health()
        recovered_reverse = False
        last_restart = -float('inf')
        status = {'phase': 'starting', 'running': True, 'supervisorPid': os.getpid(), 'reason': 'STARTING'}
        try:
            guard_process = start_guard(folder)
            transport = PersistentTransport(); transport.start()
            while not (store / 'stop').exists():
                ready, reason = False, 'CHECK_FAILED'
                try:
                    if guard_process.poll() is not None: raise SupervisorError('GUARD_UNAVAILABLE')
                    if not transport.alive(): raise SupervisorError('TRANSPORT_UNAVAILABLE')
                    physical_source()
                    status['policyIntact'] = files.digest(files.bounded_read(policy)) == policy_sha
                    if not status['policyIntact']: raise SupervisorError('POLICY_HASH_MISMATCH')
                    status['pcFallbackReady'] = pc_listener_ready()
                    if not status['pcFallbackReady']: raise SupervisorError('PC_FALLBACK_UNAVAILABLE')
                    if not recovered_reverse:
                        recover_reverse(store)
                        recovered_reverse = True
                    reverse.ensure()
                    value = files.parse(adb('exec-out', 'run-as', PACKAGE, 'cat', 'files/runtime-status.json').encode('utf8'))
                    ready, reason = health.inspect(value)
                    if (value.get('enabled') is True and reason in {'APP_STATUS_STALE', 'APP_NOT_READY', 'APP_HEARTBEAT_STALE'}
                            and time.monotonic() - last_restart >= 30):
                        # Receiver itself re-checks the authoritative persisted enabled
                        # preference. This command never enables a user-disabled runtime.
                        last_restart = time.monotonic()
                        adb('shell', 'am', 'broadcast', '-n', PACKAGE + '/.RuntimeReceiver',
                            '--es', 'operation', 'runtime_start')
                except SupervisorError as error:
                    reason = str(error) if str(error) in REASONS else 'CHECK_FAILED'
                except Exception:
                    reason = 'CHECK_FAILED'
                renew(folder, state, 'local' if ready else 'pc')
                status.update(phase='local' if ready else 'pc', reason=reason,
                              transportReady=transport.alive(), reverseOwned=reverse.owned,
                              appReady=ready, updatedAtMs=int(time.time() * 1000))
                save_json(store / 'status.json', status)
                if reason == 'GUARD_UNAVAILABLE':
                    break  # No live guardian remains to consume further fallback leases.
                # Wait in short pieces so a stop request does not wait for a full health interval.
                until = time.monotonic() + POLL_SECONDS
                while time.monotonic() < until and not (store / 'stop').exists(): time.sleep(0.1)
        finally:
            # Explicit baseline restoration precedes transport teardown. The independent
            # guard also restores if this process is terminated before entering finally.
            try:
                renew(folder, state, 'pc')
                (folder / 'stop').touch()
                if guard_process is not None:
                    try: guard_process.wait(timeout=5)
                    except subprocess.TimeoutExpired: pass
            finally:
                restored = False
                try:
                    switch(state, baseline, candidate, False)
                    restored = True
                finally:
                    try: reverse.close()
                    except Exception: pass
                    if transport is not None: transport.stop()
                    status.update(phase='stopped' if restored else 'failed', running=False,
                                  reason='STOPPED' if restored else 'CHECK_FAILED', appReady=False,
                                  transportReady=False, reverseOwned=reverse.owned, updatedAtMs=int(time.time() * 1000))
                    save_json(store / 'status.json', status)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('run', 'status', 'stop', 'guard'))
    parser.add_argument('--folder')
    parser.add_argument('--baseline-sha256', default=BASELINE_SHA)
    parser.add_argument('--policy-sha256', default=POLICY_SHA)
    args = parser.parse_args()
    try:
        store = store_path()
        if args.command == 'guard':
            folder = validated_guard_folder(args.folder, store)
            guard(folder)
        elif args.command == 'run':
            for value in (args.baseline_sha256, args.policy_sha256):
                if not re.fullmatch('[a-f0-9]{64}', value): raise SupervisorError('INVALID_EXPECTED_HASH')
            run(baseline_sha=args.baseline_sha256, policy_sha=args.policy_sha256)
        elif args.command == 'stop':
            if store.exists(): (store / 'stop').touch()
            print(json.dumps({'stopRequested': True}))
        else:
            print(json.dumps(safe_status(store)))
    except (SupervisorError, files.WindowError) as error:
        print(json.dumps({'ok': False, 'code': str(error)}))
        raise SystemExit(1) from None
    except KeyboardInterrupt:
        pass
    except Exception:
        print(json.dumps({'ok': False, 'code': 'LOCAL_OPERATION_FAILED'}))
        raise SystemExit(1) from None


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] in {'guard', 'status', 'stop'}: main()
    else: raise SystemExit('Use pc/tunnel_only_supervisor.py; legacy run is not supported.')
