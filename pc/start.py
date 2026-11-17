"""Own DHCP + fixed native relay + allowlisted tunnel; no PC voice processing.

First configure the dedicated cable with Install-StationLab.ps1. This entry point
does not configure NICs, NAT, Wi-Fi credentials, factory firmware or native trust.
It enforces Wi-Fi off while the managed wired transport is available.
"""
from __future__ import annotations
import argparse
import json
import hashlib
import re
import os
from pathlib import Path
import socket
import subprocess
import time

from deployment import SUITE, environment, load
import native_config_window as lifecycle
import station_network_guard as network_guard

HIDDEN = getattr(subprocess, 'CREATE_NO_WINDOW', 0)


def _run_owned(value, status, *, external_dhcp=False):
    private = Path(value['privateDir']).resolve()
    state = private / 'state'; state.mkdir(parents=True, exist_ok=True)
    env = environment(value)
    children, handles = [], []
    stop = state / 'stop'
    if stop.exists(): raise ValueError('REMOVE_EXPLICIT_STOP_FILE_BEFORE_START')
    def spawn(arguments, name, extra_env=None, cwd=None):
        output = (state / (name + '.log')).open('ab'); handles.append(output)
        child = subprocess.Popen(arguments, env=extra_env or env, cwd=cwd, stdout=output,
            stderr=subprocess.STDOUT, creationflags=HIDDEN)
        children.append(child)
        return child
    def adb(*arguments, required=True):
        result = subprocess.run([value['adb'], '-s', value['serial'], *arguments],
            capture_output=True, timeout=15, creationflags=HIDDEN)
        if required and result.returncode: raise ValueError('ADB_OPERATION_FAILED')
        return result
    def enforce_wired():
        def close_relay():
            status('starting', 'WIRED_SAFETY_REPAIR', len(children))
            (state / 'local-config-relay.json').write_text(
                '{"version":1,"enabled":false,"expiresAtMs":0}', encoding='ascii')
        return network_guard.ensure_wifi_off(
            lambda *arguments: adb(*arguments).stdout.decode('ascii'),
            before_repair=close_relay)
    # Claim no shared stop/lease files before listener collision checks have passed.
    stale_ready = check_shared_owners(value)
    check_listeners(value)
    if stale_ready is not None: stale_ready.unlink()
    (state / 'local-config-relay.json').write_text('{"version":1,"enabled":false,"expiresAtMs":0}', encoding='ascii')
    (state / 'config-stop').unlink(missing_ok=True)
    try:
        if not external_dhcp:
            runtime_dir = private / 'dhcp'; runtime_dir.mkdir(exist_ok=True)
            template = (SUITE / 'config/tftpd32.template.ini').read_text(encoding='ascii')
            ini = runtime_dir / 'tftpd32.ini'
            ini.write_text(template.replace('{LAB_IP}', value['labIp']).replace('{STATION_IP}', value['stationIp']), encoding='ascii')
            spawn([value['dhcpExecutable']], 'dhcp', dict(env, TFTP_INI=str(ini)), runtime_dir)
        adb('connect', value['serial'], required=False)
        enforce_wired()
        adb('shell', 'am', 'start', '-n', 'local.station.agent/.MainActivity')
        adb('shell', 'am', 'broadcast', '-n', 'local.station.voicebridge/.RuntimeReceiver', '--es', 'operation', 'local_services_start')
        adb('shell', 'am', 'broadcast', '-n', 'local.station.voicebridge/.RuntimeReceiver', '--es', 'operation', 'runtime_enable')
        adb('shell', 'am', 'start', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
        helper = ['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
            str(SUITE / 'pc/Start-StationInputHelper.ps1'), '-Adb', value['adb'],
            '-Target', value['serial'], '-Apk', value['agentApk']]
        # A launcher running under PowerShell 7 can export a PSModulePath that
        # prevents Windows PowerShell 5 from finding Get-FileHash/ConvertFrom-Json.
        # Scope its built-in module path to this child without changing the host.
        helper_env = {key: val for key, val in env.items() if key.upper() != 'PSMODULEPATH'}
        helper_env['PSMODULEPATH'] = str(Path(os.environ['SystemRoot']) / 'System32/WindowsPowerShell/v1.0/Modules')
        subprocess.run(helper, env=helper_env, capture_output=True, timeout=30, check=True, creationflags=HIDDEN)
        spawn([value['python'], '-B', str(SUITE / 'pc/tunnel_only_supervisor.py'), 'run'], 'tunnel')
        arguments = [value['python'], '-B', str(SUITE / 'pc/LocalConfigServer.py')]
        for flag, path in [('cert', private / 'pc/fullchain.pem'), ('key', private / 'pc/quasar-all.key'),
            ('events', state / 'config-events.jsonl'), ('ready', state / 'config-ready.json'),
            ('stop-file', state / 'config-stop'), ('policy', private / 'pc/config-policy.json'),
            ('sync-payload', private / 'pc/native-baseline.json'), ('station-relay', state / 'local-config-relay.json')]:
            arguments += ['--' + flag, str(path)]
        arguments += ['--require-station-relay', '--max-seconds', '86400']
        spawn(arguments, 'config')
        status('starting', 'STARTING', len(children))
        next_guard = time.monotonic() + 15
        while not stop.exists():
            if any(child.poll() is not None for child in children): raise ValueError('OWNED_PROCESS_EXITED')
            if time.monotonic() >= next_guard:
                next_guard = time.monotonic() + 30
                # The child owns all runtime Wi-Fi repair/lease writes. A second
                # repair writer here could race a child publishing a fresh lease.
                # Any OTA response/pin mismatch closes the whole relay. No WAN fallback.
                result = subprocess.run([value['python'], '-B', str(SUITE / 'pc/probe_local_ota.py')],
                    env=env, capture_output=True, timeout=8, creationflags=HIDDEN)
                if result.returncode: raise ValueError('OTA_GUARD_FAILED')
                status('ready', 'READY', len(children))
                agent = adb('shell', 'pidof', 'local.station.agent', required=False)
                if agent.returncode:
                    adb('shell', 'am', 'start', '-n', 'local.station.agent/.MainActivity', required=False)
                    adb('shell', 'am', 'start', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME', required=False)
            time.sleep(0.5)
    finally:
        for action in (
            lambda: (state / 'local-config-relay.json').write_text('{"version":1,"enabled":false,"expiresAtMs":0}', encoding='ascii'),
            lambda: (state / 'config-stop').touch(),
            lambda: request_tunnel_stop(private),
        ):
            try: action()
            except OSError: pass
        for child in reversed(children):
            try: child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                child.terminate()
                try: child.wait(timeout=3)
                except subprocess.TimeoutExpired: child.kill()
        for handle in handles: handle.close()


def request_tunnel_stop(private):
    target = private / 'tunnel-only-supervisor/stop'
    target.parent.mkdir(parents=True, exist_ok=True); target.touch()


def check_listeners(value):
    # Never attach to, stop, or replace an unknown listener. DHCP is deliberately
    # separate: external mode delegates that process to its existing owner.
    for address, port in [(value['labIp'], 443), (value['voiceIp'], 443), ('127.0.0.1', 18443)]:
        with socket.socket() as probe:
            probe.settimeout(0.3)
            if probe.connect_ex((address, port)) == 0: raise ValueError('PORT_ALREADY_IN_USE')


def check_shared_owners(value):
    private = Path(value['privateDir']).resolve()
    for relative in ('tunnel-only-supervisor/owner.json', 'runtime-supervisor/owner.json'):
        marker = private / relative
        if not marker.exists(): continue
        try:
            pid = lifecycle.parse(lifecycle.bounded_read(marker)).get('supervisorPid')
            if type(pid) is not int or pid <= 0: raise ValueError()
        except Exception: raise ValueError('SHARED_OWNER_UNVERIFIABLE') from None
        if lifecycle.process_alive(pid): raise ValueError('SHARED_TUNNEL_OWNER_RUNNING')
    marker = private / 'state/config-ready.json'
    if not marker.exists(): return None
    try:
        pid = lifecycle.parse(lifecycle.bounded_read(marker)).get('pid')
        if type(pid) is not int or pid <= 0: raise ValueError()
    except Exception: raise ValueError('SHARED_OWNER_UNVERIFIABLE') from None
    if lifecycle.process_alive(pid): raise ValueError('SHARED_CONFIG_OWNER_RUNNING')
    return marker  # Only a dead, recorded config process may leave this stale marker.


def store_path(value):
    return Path(value['privateDir']).resolve() / 'state/pc-runtime'


def identity(value, deployment, external_dhcp):
    def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()
    return {'version': 1, 'deploymentSha256': sha(deployment),
            'entrypointSha256': sha(__file__), 'pythonSha256': sha(value['python']),
            'externalDhcp': bool(external_dhcp)}


def write_status(store, pid, phase, reason, child_count=0):
    lifecycle.atomic_write(store / 'status.json', lifecycle.encoded({
        'version': 1, 'ownerPid': pid, 'phase': phase, 'reason': reason,
        'childCount': child_count, 'updatedAtMs': int(time.time() * 1000)}))


def owner_status(value, deployment, *, external_dhcp=False):
    """A held OS lifetime lock + matching pins identify our owner; PID alone never does."""
    store = store_path(value)
    if not (store / 'mutation.lock').exists():
        return {'running': False, 'phase': 'stopped'}
    try:
        with lifecycle.mutation_lock(store, timeout=0):
            return {'running': False, 'phase': 'stopped'}
    except lifecycle.WindowError as error:
        if str(error) != 'MUTATION_BUSY': raise
    try:
        owner = lifecycle.parse(lifecycle.bounded_read(store / 'owner.json'))
    except Exception: raise ValueError('PC_OWNER_UNVERIFIABLE') from None
    expected = identity(value, deployment, external_dhcp)
    if set(owner) != set(expected) | {'ownerPid'} or any(owner.get(key) != val for key, val in expected.items()):
        raise ValueError('PC_OWNER_PIN_MISMATCH')
    pid = owner['ownerPid']
    if type(pid) is not int or pid <= 0 or not lifecycle.process_alive(pid):
        raise ValueError('PC_OWNER_UNVERIFIABLE')
    phase, reason = 'starting', 'STARTING'
    try:
        status = lifecycle.parse(lifecycle.bounded_read(store / 'status.json'))
        if status.get('ownerPid') == pid and status.get('phase') in ('starting', 'ready', 'failed', 'stopped'):
            phase = status['phase']
            raw = status.get('reason')
            if isinstance(raw, str) and re.fullmatch('[A-Z_]{1,64}', raw): reason = raw
    except Exception: pass
    return {'running': True, 'ownerPid': pid, 'phase': phase, 'reason': reason,
            'externalDhcp': external_dhcp}


def run(value, deployment, *, external_dhcp=False):
    store = store_path(value); store.mkdir(parents=True, exist_ok=True)
    lifecycle.protect_directory(store)
    if (store.parent / 'stop').exists(): raise ValueError('REMOVE_EXPLICIT_STOP_FILE_BEFORE_START')
    try:
        with lifecycle.mutation_lock(store, timeout=0):
            owner = dict(identity(value, deployment, external_dhcp), ownerPid=os.getpid())
            lifecycle.atomic_write(store / 'owner.json', lifecycle.encoded(owner))
            write_status(store, os.getpid(), 'starting', 'STARTING')
            update = lambda phase, reason, count=0: write_status(store, os.getpid(), phase, reason, count)
            try:
                _run_owned(value, update, external_dhcp=external_dhcp)
            except BaseException as error:
                reason = str(error) if isinstance(error, ValueError) and re.fullmatch('[A-Z_]{1,64}', str(error)) else 'STARTUP_FAILED'
                update('failed', reason)
                raise
            else: update('stopped', 'STOPPED')
    except lifecycle.WindowError as error:
        if str(error) == 'MUTATION_BUSY': raise ValueError('PC_OWNER_ALREADY_RUNNING') from None
        raise


def ensure(value, deployment, *, external_dhcp=False):
    """One scheduled invocation starts at most one hidden owner; does not remove stop markers."""
    store = store_path(value); store.mkdir(parents=True, exist_ok=True)
    launcher = store / 'launcher'; launcher.mkdir(exist_ok=True)
    lifecycle.protect_directory(store)
    with lifecycle.mutation_lock(launcher, timeout=0):
        if (store.parent / 'stop').exists(): raise ValueError('REMOVE_EXPLICIT_STOP_FILE_BEFORE_START')
        previous = owner_status(value, deployment, external_dhcp=external_dhcp)
        if previous['running']: return dict(previous, started=False)
        check_shared_owners(value)
        check_listeners(value)
        arguments = [value['python'], '-B', str(Path(__file__).resolve()), '--deployment', str(Path(deployment).resolve())]
        if external_dhcp: arguments.append('--external-dhcp')
        with (store / 'launcher.log').open('ab') as output:
            child = subprocess.Popen(arguments, cwd=SUITE, stdout=output, stderr=subprocess.STDOUT,
                                     stdin=subprocess.DEVNULL, creationflags=HIDDEN)
        until = time.monotonic() + 10
        while time.monotonic() < until:
            if child.poll() is not None: raise ValueError('PC_OWNER_START_FAILED')
            try: current = owner_status(value, deployment, external_dhcp=external_dhcp)
            except ValueError as error:
                # A new child can acquire its lock just before publishing owner.json.
                if str(error) != 'PC_OWNER_UNVERIFIABLE': raise
                time.sleep(0.1); continue
            if current['running']:
                if current['phase'] in ('failed', 'stopped'): raise ValueError('PC_OWNER_START_FAILED')
                return dict(current, started=True)
            time.sleep(0.1)
        # The child is ours, but its unpublished state is unknown: do not kill other processes.
        raise ValueError('PC_OWNER_START_NOT_CONFIRMED')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--deployment', type=Path, required=True)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--check', action='store_true', help='Validate files/settings only; start nothing')
    mode.add_argument('--status', action='store_true', help='Report the matching PC owner; start nothing')
    mode.add_argument('--ensure', action='store_true', help='Start a hidden owner only when absent')
    parser.add_argument('--external-dhcp', action='store_true', help='Leave DHCP to its existing external owner')
    args = parser.parse_args(argv)
    try:
        value = load(args.deployment)
        if args.check: result = {'ok': True, 'configurationValidated': True, 'servicesStarted': False}
        elif args.status: result = dict(owner_status(value, args.deployment, external_dhcp=args.external_dhcp), ok=True)
        elif args.ensure: result = dict(ensure(value, args.deployment, external_dhcp=args.external_dhcp), ok=True)
        else:
            run(value, args.deployment, external_dhcp=args.external_dhcp)
            result = {'ok': True, 'stopped': True}
        print(json.dumps(result)); return 0
    except KeyboardInterrupt: return 0
    except Exception as error:
        code = str(error) if isinstance(error, (ValueError, lifecycle.WindowError)) and re.fullmatch('[A-Z_]{1,64}', str(error)) else 'STARTUP_FAILED'
        print(json.dumps({'ok': False, 'code': code})); return 1


if __name__ == '__main__': raise SystemExit(main())
