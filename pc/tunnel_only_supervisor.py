"""Explicit Station tunnel-only transport owner. Importing it performs no I/O.

The PC transports opaque bytes and checks pinned files; it never edits active
native payload/policy or starts PC voice/music/display business logic. Requires
LocalConfigServer --require-station-relay: an absent lease must close, not revert.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import time

import runtime_supervisor as runtime
import station_network_guard as network_guard
from panel_relay import FixedPanelRelay
from suite_paths import private_root

FILES = runtime.files
SERIAL, PACKAGE = runtime.SERIAL, runtime.PACKAGE
FORWARDS = {19680: 18080, 19683: 17683, 19684: 17684, 19685: 17685}
LEASE_MS, POLL_SECONDS = 30000, 2.0
PHASES = {'starting', 'ready', 'closed', 'drift', 'stopped', 'failed'}
REASONS = {'STARTING', 'READY', 'STOPPED', 'CHECK_FAILED', 'POLICY_HASH_MISMATCH',
           'ACTIVE_FILE_DRIFT', 'TRANSPORT_UNAVAILABLE', 'PHYSICAL_INTERFACE_UNAVAILABLE',
           'REVERSE_CONFLICT', 'REVERSE_NOT_READY', 'FORWARD_CONFLICT', 'FORWARD_NOT_READY',
           'LOCAL_STATUS_INVALID', 'LOCAL_STATUS_STALE', 'LOCAL_HEARTBEAT_PENDING',
           'LOCAL_HEARTBEAT_STALE', 'LOCAL_NOT_READY', 'APP_PROCESS_MISMATCH'} | runtime.REASONS
REASONS |= network_guard.ERROR_CODES


def store_path():
    return private_root() / 'tunnel-only-supervisor'


def relay_path():
    # Match the actual user's launcher outside an MSIX LOCALAPPDATA overlay.
    return private_root() / 'state/local-config-relay.json'


def active_path():
    return private_root() / 'pc/native-baseline.json'


def checked_hash(value):
    if type(value) is not str or re.fullmatch('[a-f0-9]{64}', value) is None:
        raise runtime.SupervisorError('INVALID_EXPECTED_HASH')
    return value


def check_pins(active, policy, active_sha, policy_sha):
    """Read-only pin checks. Never calls native_config_window.switch/prepare."""
    if FILES.digest(FILES.bounded_read(policy)) != policy_sha:
        raise runtime.SupervisorError('POLICY_HASH_MISMATCH')
    if FILES.digest(FILES.bounded_read(active)) != active_sha:
        raise runtime.SupervisorError('ACTIVE_FILE_DRIFT')


def check_legacy_stopped(store=None, alive=FILES.process_alive):
    """Refuse overlap with the old baseline-restoring controller or its guard."""
    store = runtime.store_path() if store is None else Path(store)
    owner = store / 'owner.json'
    if not owner.exists():
        return
    value = runtime.read_json(owner)
    pid, folder = value.get('supervisorPid'), value.get('folder')
    if type(pid) is not int or pid <= 0 or type(folder) is not str or re.fullmatch('[a-f0-9]{32}', folder) is None:
        raise runtime.SupervisorError('LEGACY_OWNER_INVALID')
    if alive(pid):
        raise runtime.SupervisorError('LEGACY_SUPERVISOR_RUNNING')
    status = store / folder / 'guard-status.json'
    if status.exists():
        guard_pid = runtime.read_json(status).get('guardPid')
        if type(guard_pid) is not int or guard_pid <= 0:
            raise runtime.SupervisorError('LEGACY_OWNER_INVALID')
        if alive(guard_pid):
            raise runtime.SupervisorError('LEGACY_GUARD_RUNNING')


def write_lease(path, enabled, *, now_ms=None):
    if type(enabled) is not bool:
        raise runtime.SupervisorError('INVALID_LEASE')
    current = time.time_ns() // 1000000 if now_ms is None else now_ms
    if type(current) is not int or not 0 <= current < 9007199254740991 - LEASE_MS:
        raise runtime.SupervisorError('INVALID_LEASE')
    runtime.save_json(path, {'version': 1, 'enabled': enabled,
                             'expiresAtMs': current + LEASE_MS if enabled else 0})


def forward_targets(raw):
    result = {}
    for line in raw.splitlines():
        parts = line.split()
        if len(parts) != 3 or parts[1] not in {f'tcp:{port}' for port in FORWARDS}:
            continue
        if parts[1] in result:
            raise runtime.SupervisorError('FORWARD_CONFLICT')
        result[parts[1]] = (parts[0], parts[2])
    return result


class Forwards:
    """Two fixed mappings. Existing mappings need recorded ownership to be reused."""
    def __init__(self, execute=None, ownership=None):
        self.execute = execute or runtime.adb
        self.ownership, self.owned = ownership, set()

    def _save(self):
        if self.ownership is not None:
            runtime.save_json(self.ownership, {'version': 1, 'supervisorPid': os.getpid(),
                                              'ports': sorted(self.owned)})

    def ensure(self):
        for local, remote in FORWARDS.items():
            current = forward_targets(self.execute('forward', '--list')).get(f'tcp:{local}')
            expected = (SERIAL, f'tcp:{remote}')
            if current is not None:
                if local not in self.owned or current != expected:
                    raise runtime.SupervisorError('FORWARD_CONFLICT')
                continue
            self.owned.discard(local)
            self.execute('forward', '--no-rebind', f'tcp:{local}', f'tcp:{remote}')
            self.owned.add(local); self._save()
            if forward_targets(self.execute('forward', '--list')).get(f'tcp:{local}') != expected:
                raise runtime.SupervisorError('FORWARD_NOT_READY')

    def close(self):
        for local in sorted(tuple(self.owned)):
            current = forward_targets(self.execute('forward', '--list')).get(f'tcp:{local}')
            if current is not None and current != (SERIAL, f'tcp:{FORWARDS[local]}'):
                raise runtime.SupervisorError('FORWARD_CONFLICT')
            if current is not None:
                self.execute('forward', '--remove', f'tcp:{local}')
            self.owned.discard(local); self._save()
        if self.ownership is not None:
            Path(self.ownership).unlink(missing_ok=True)


def recover_forwards(store, execute=None, alive=FILES.process_alive):
    marker = Path(store) / 'forward-owner.json'
    execute = execute or runtime.adb
    if not marker.exists():
        return
    value = runtime.read_json(marker)
    ports = value.get('ports')
    if (set(value) != {'version', 'supervisorPid', 'ports'} or value.get('version') != 1
            or type(value.get('supervisorPid')) is not int or value['supervisorPid'] <= 0
            or type(ports) is not list or any(type(port) is not int or port not in FORWARDS for port in ports)
            or len(set(ports)) != len(ports) or alive(value['supervisorPid'])):
        raise runtime.SupervisorError('FORWARD_CONFLICT')
    current = forward_targets(execute('forward', '--list'))
    for port in ports:
        if current.get(f'tcp:{port}') not in (None, (SERIAL, f'tcp:{FORWARDS[port]}')):
            raise runtime.SupervisorError('FORWARD_CONFLICT')
    for port in ports:
        if f'tcp:{port}' in current:
            execute('forward', '--remove', f'tcp:{port}')
    marker.unlink()


class LocalServicesHealth:
    def __init__(self):
        self.pid = self.sequence = self.progress_at = None
        self.progress_seen = False

    def inspect(self, value, *, now_wall=None, now_mono=None):
        now_wall = time.time() if now_wall is None else now_wall
        now_mono = time.monotonic() if now_mono is None else now_mono
        if type(value) is not dict or type(value.get('healthy')) is not bool:
            return False, 'LOCAL_STATUS_INVALID'
        for name in ('servicePid', 'heartbeatSeq', 'updatedAtMs'):
            if type(value.get(name)) is not int or value[name] < 0:
                return False, 'LOCAL_STATUS_INVALID'
        if value['servicePid'] == 0:
            return False, 'LOCAL_STATUS_INVALID'
        config, push = value.get('config'), value.get('push')
        if (type(config) is not dict or type(config.get('active')) is not bool
                or type(push) is not dict or type(push.get('listening')) is not bool):
            return False, 'LOCAL_STATUS_INVALID'
        if not -2 <= now_wall - value['updatedAtMs'] / 1000 <= runtime.MAX_STATUS_AGE:
            return False, 'LOCAL_STATUS_STALE'
        identity, sequence = value['servicePid'], value['heartbeatSeq']
        if self.pid != identity:
            self.pid, self.sequence, self.progress_at = identity, sequence, now_mono
            self.progress_seen = False
            return False, 'LOCAL_HEARTBEAT_PENDING'
        if sequence < self.sequence:
            return False, 'LOCAL_STATUS_INVALID'
        if sequence > self.sequence:
            self.sequence, self.progress_at, self.progress_seen = sequence, now_mono, True
        elif now_mono - self.progress_at >= runtime.MAX_STATUS_AGE:
            return False, 'LOCAL_HEARTBEAT_STALE'
        if not value['healthy'] or value.get('code') != 'ready' or not config['active'] or not push['listening']:
            return False, 'LOCAL_NOT_READY'
        return (True, 'READY') if self.progress_seen else (False, 'LOCAL_HEARTBEAT_PENDING')


def inspect_apps(local_health, voice_health, execute=None):
    """Independent local services admit config relay; voice readiness is diagnostic only."""
    execute = execute or runtime.adb
    local = FILES.parse(execute('exec-out', 'run-as', PACKAGE, 'cat', 'files/local-services-status.json').encode('utf8'))
    local_ok, local_reason = local_health.inspect(local)
    voice_ok = False
    try:
        voice = FILES.parse(execute('exec-out', 'run-as', PACKAGE, 'cat', 'files/runtime-status.json').encode('utf8'))
        voice_ok, _ = voice_health.inspect(voice)
        # Old/stopped voice status can belong to another app lifetime. It never
        # closes config; report it as not ready instead of trusting that old PID.
        voice_ok = bool(voice_ok and local.get('servicePid') == voice.get('servicePid'))
    except Exception:
        # Disabled voice may have no status file; quota/circuit failures and bad
        # diagnostics must not disable independent display and local services.
        pass
    return local_ok, local_reason, local_ok, voice_ok


def request_station_services(execute=None):
    """Fixed Android lifecycle bootstrap only; credentials/preferences stay on Station."""
    execute = execute or runtime.adb
    for operation in ('local_services_start', 'runtime_start'):
        execute('shell', 'am', 'broadcast', '-n', PACKAGE + '/.RuntimeReceiver',
                '--es', 'operation', operation)


def safe_status(store):
    try:
        raw = runtime.read_json(Path(store) / 'status.json')
    except (OSError, FILES.WindowError):
        return {'phase': 'stopped', 'running': False}
    result = {'phase': raw.get('phase') if raw.get('phase') in PHASES else 'failed'}
    for name in ('running', 'transportReady', 'reverseOwned', 'forwardsOwned', 'appReady',
                 'localServicesReady', 'internetSourceReady', 'panelRelayReady', 'policyIntact', 'activeIntact',
                 'wifiOffVerified'):
        if type(raw.get(name)) is bool:
            result[name] = raw[name]
    for name in ('supervisorPid', 'updatedAtMs', 'wifiCheckedAtMs', 'wifiRepairCount'):
        if type(raw.get(name)) is int and raw[name] >= 0:
            result[name] = raw[name]
    result['running'] = bool(result.get('running') and FILES.process_alive(result.get('supervisorPid')))
    result['reason'] = raw.get('reason') if raw.get('reason') in REASONS else 'CHECK_FAILED'
    result['networkGuardScope'] = 'reactive_pc_polling_with_adb'
    return result


def run(*, store=None, relay=None, active=None, policy=runtime.POLICY,
        active_sha=runtime.BASELINE_SHA, policy_sha=runtime.POLICY_SHA):
    store = store_path() if store is None else Path(store)
    relay = relay_path() if relay is None else Path(relay)
    active = active_path() if active is None else Path(active)
    policy = Path(policy)
    checked_hash(active_sha); checked_hash(policy_sha)
    for path in (store, relay, active, policy):
        if not path.is_absolute() or str(path).startswith('\\\\'):
            raise runtime.SupervisorError('INVALID_LOCAL_PATH')
    if relay.resolve() in (active.resolve(), policy.resolve()):
        raise runtime.SupervisorError('INVALID_LOCAL_PATH')
    check_legacy_stopped()
    check_pins(active, policy, active_sha, policy_sha)
    store.mkdir(parents=True, exist_ok=True); FILES.protect_directory(store)
    relay.parent.mkdir(parents=True, exist_ok=True); FILES.protect_directory(relay.parent)
    with FILES.mutation_lock(store, timeout=0):
        # A separate store prevents overlap with legacy ownership. The caller must
        # stop its old supervisor/guard before entering this explicit mode.
        if (store / 'owner.json').exists():
            old = runtime.read_json(store / 'owner.json')
            if type(old.get('supervisorPid')) is not int or FILES.process_alive(old['supervisorPid']):
                raise runtime.SupervisorError('ALREADY_RUNNING')
        (store / 'stop').unlink(missing_ok=True)
        runtime.save_json(store / 'owner.json', {'version': 1, 'supervisorPid': os.getpid()})
        reverse = runtime.Reverse(ownership=store / 'reverse-owner.json')
        forwards = Forwards(ownership=store / 'forward-owner.json')
        transport = None
        panel = FixedPanelRelay()
        next_recovery = 0.0
        local_health, voice_health = LocalServicesHealth(), runtime.Health()
        status = {'phase': 'starting', 'running': True, 'supervisorPid': os.getpid(),
                  'reason': 'STARTING', 'policyIntact': True, 'activeIntact': True,
                  'wifiOffVerified': False, 'wifiRepairCount': 0}
        try:
            write_lease(relay, False)
            runtime.recover_reverse(store)
            recover_forwards(store)
            transport = runtime.PersistentTransport(); transport.start()
            while not (store / 'stop').exists():
                ready, reason, local_ok, voice_ok = False, 'CHECK_FAILED', False, False
                internet_ok = False
                wifi_off = False
                fatal = False
                try:
                    check_pins(active, policy, active_sha, policy_sha)
                    repaired = network_guard.ensure_wifi_off(runtime.adb,
                        before_repair=lambda: write_lease(relay, False))
                    wifi_off = True
                    status['wifiCheckedAtMs'] = int(time.time() * 1000)
                    if repaired: status['wifiRepairCount'] += 1
                    if not transport.alive():
                        raise runtime.SupervisorError('TRANSPORT_UNAVAILABLE')
                    reverse.ensure(); forwards.ensure()
                    if not panel.alive():
                        try: panel.start()
                        except OSError: pass  # A busy optional PC panel cannot disable local config.
                    ready, reason, local_ok, voice_ok = inspect_apps(local_health, voice_health)
                    try:
                        runtime.physical_source()  # Diagnostic; each actual CONNECT still pins this source.
                        internet_ok = True
                    except Exception:
                        pass  # A lost WAN uplink must not close working local config/display.
                except network_guard.NetworkGuardError as error:
                    reason = str(error) if str(error) in REASONS else 'CHECK_FAILED'
                except runtime.SupervisorError as error:
                    reason = str(error) if str(error) in REASONS else 'CHECK_FAILED'
                    fatal = reason in {'POLICY_HASH_MISMATCH', 'ACTIVE_FILE_DRIFT', 'TRANSPORT_UNAVAILABLE'}
                    if reason == 'POLICY_HASH_MISMATCH': status['policyIntact'] = False
                    if reason == 'ACTIVE_FILE_DRIFT': status['activeIntact'] = False
                except Exception:
                    reason = 'CHECK_FAILED'
                if (wifi_off and not fatal and not local_ok and reverse.owned and forwards.owned == set(FORWARDS)
                        and time.monotonic() >= next_recovery):
                    next_recovery = time.monotonic() + 30
                    try: request_station_services()
                    except Exception: pass  # ADB/reboot outages keep the route closed.
                write_lease(relay, ready)
                status.update(phase='ready' if ready else 'drift' if fatal else 'closed', reason=reason,
                              transportReady=transport.alive(), reverseOwned=reverse.owned,
                              forwardsOwned=forwards.owned == set(FORWARDS), appReady=voice_ok,
                              localServicesReady=local_ok, internetSourceReady=internet_ok,
                              wifiOffVerified=wifi_off, networkGuardScope='reactive_pc_polling_with_adb',
                              panelRelayReady=panel.alive(),
                              updatedAtMs=int(time.time() * 1000))
                runtime.save_json(store / 'status.json', status)
                if fatal:
                    break
                until = time.monotonic() + POLL_SECONDS
                while time.monotonic() < until and not (store / 'stop').exists():
                    time.sleep(0.1)
        finally:
            try:
                write_lease(relay, False)
            finally:
                try: forwards.close()
                except Exception: pass
                try: reverse.close()
                except Exception: pass
                if transport is not None: transport.stop()
                panel.stop()
                status.update(phase='stopped', running=False, reason='STOPPED', appReady=False,
                              wifiOffVerified=False,
                              localServicesReady=False, internetSourceReady=False, transportReady=False, panelRelayReady=False, reverseOwned=reverse.owned,
                              forwardsOwned=bool(forwards.owned), updatedAtMs=int(time.time() * 1000))
                runtime.save_json(store / 'status.json', status)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('run', 'status', 'stop'))
    parser.add_argument('--active-sha256', default=runtime.BASELINE_SHA)
    parser.add_argument('--policy-sha256', default=runtime.POLICY_SHA)
    args = parser.parse_args()
    try:
        store = store_path()
        if args.command == 'run':
            run(active_sha=args.active_sha256, policy_sha=args.policy_sha256)
        elif args.command == 'stop':
            if store.exists(): (store / 'stop').touch()
            print(json.dumps({'stopRequested': True}))
        else:
            print(json.dumps(safe_status(store)))
    except (runtime.SupervisorError, FILES.WindowError) as error:
        # Errors carry fixed codes, never private config/subprocess contents.
        code = str(error)
        if re.fullmatch('[A-Z_]{1,64}', code) is None: code = 'LOCAL_OPERATION_FAILED'
        print(json.dumps({'ok': False, 'code': code}))
        raise SystemExit(1) from None
    except KeyboardInterrupt:
        pass
    except Exception:
        print(json.dumps({'ok': False, 'code': 'LOCAL_OPERATION_FAILED'}))
        raise SystemExit(1) from None


if __name__ == '__main__':
    main()
