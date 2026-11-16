"""Bounded local wired onboarding before ADB/apps exist. Does not configure network interfaces."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time

from deployment import SUITE
import LocalConfigServer as config_server
import native_config_window as lifecycle
import start as production

HIDDEN = getattr(subprocess, 'CREATE_NO_WINDOW', 0)


def path(value):
    if not isinstance(value, str) or '://' in value or value.startswith(('\\\\', '//')) or not Path(value).is_absolute():
        raise ValueError('ABSOLUTE_LOCAL_PATH_REQUIRED')
    return Path(value).resolve()


def sha(data): return hashlib.sha256(data).hexdigest()


def load(deployment, *, external_dhcp=False):
    source = path(str(deployment))
    if source == SUITE or SUITE in source.parents: raise ValueError('DEPLOYMENT_MUST_BE_PRIVATE')
    value = lifecycle.parse(lifecycle.bounded_read(source))
    private = path(value['privateDir'])
    if private == SUITE or SUITE in private.parents: raise ValueError('PRIVATE_DIR_INSIDE_CHECKOUT')
    if value.get('format') != 1 or (value.get('labIp'), value.get('voiceIp'), value.get('stationIp')) != ('192.168.77.1', '192.168.77.2', '192.168.77.100'):
        raise ValueError('CURRENT_NATIVE_TOPOLOGY_REQUIRED')
    if not path(value['python']).is_file(): raise ValueError('LOCAL_PYTHON_MISSING')
    inputs = {}
    for relative, pin in [('pc/config-policy.json', 'policySha256'), ('pc/native-baseline.json', 'baselineSha256')]:
        data = lifecycle.bounded_read(private / relative)
        if not isinstance(value.get(pin), str) or not re.fullmatch('[a-f0-9]{64}', value[pin]) or sha(data) != value[pin]:
            raise ValueError('PRIVATE_CONFIG_HASH_MISMATCH')
        inputs[relative] = data
    policy = lifecycle.parse(inputs['pc/config-policy.json'])
    config_server.validate_policy(policy)
    baseline = lifecycle.parse(inputs['pc/native-baseline.json'])
    try:
        system = baseline['config']['system_config']; adb = system['adbd']
        valid = (baseline['status'] == 'ok' and isinstance(baseline['config']['account_config'], dict)
            and isinstance(baseline['config']['device_config'], dict)
            and system['uniProxyUrl'] == 'ws://127.0.0.1:17682/uni.ws'
            and adb['remoteActivation'] is True and adb['network'] is False and adb.get('forceDisable', False) is False
            and all(key not in baseline or baseline[key] is False for key in ('hasUpdate', 'hasCriticalUpdate')))
    except (KeyError, TypeError, AttributeError): valid = False
    if not valid: raise ValueError('BOOTSTRAP_NATIVE_PROFILE_INVALID')
    from cryptography import x509
    from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat, load_pem_private_key
    cert = x509.load_pem_x509_certificate(lifecycle.bounded_read(private / 'pc/fullchain.pem'))
    key = load_pem_private_key(lifecycle.bounded_read(private / 'pc/quasar-all.key'), password=None)
    if sha(cert.public_bytes(Encoding.DER)) != value.get('tlsLeafSha256') or cert.public_key().public_bytes(Encoding.DER, PublicFormat.SubjectPublicKeyInfo) != key.public_key().public_bytes(Encoding.DER, PublicFormat.SubjectPublicKeyInfo):
        raise ValueError('PRIVATE_TLS_PIN_MISMATCH')
    if not external_dhcp:
        executable = path(value['dhcpExecutable'])
        if not executable.is_file() or sha(executable.read_bytes()) != value.get('dhcpSha256'): raise ValueError('DHCP_HASH_MISMATCH')
    # Own a local-only policy copy; never broaden/edit the pinned production file.
    policy.update(local_registration_stub=True, local_oauth_stub=True, local_ntp_server=True,
        local_sync_payload=True, google_https_passthrough=False)
    policy['check_updates_payload'] = {'status': 'ok', 'hasUpdate': False, 'hasCriticalUpdate': False}
    config_server.validate_policy(policy)
    return value, lifecycle.encoded(policy), inputs['pc/native-baseline.json']


def arguments(value, store, seconds):
    private = Path(value['privateDir'])
    result = [value['python'], '-B', str(SUITE / 'pc/LocalConfigServer.py')]
    for name, file in [('cert', private / 'pc/fullchain.pem'), ('key', private / 'pc/quasar-all.key'),
        ('events', store / 'events.jsonl'), ('ready', store / 'ready.json'), ('stop-file', store / 'stop'),
        ('policy', store / 'policy.json'), ('sync-payload', store / 'native-baseline.json')]:
        result += ['--' + name, str(file)]
    return result + ['--max-seconds', str(seconds)]


def run(value, policy, baseline, *, external_dhcp=False, seconds=1800):
    if type(seconds) is not int or not 1 <= seconds <= 3600: raise ValueError('DURATION_MUST_BE_1_TO_3600_SECONDS')
    private = Path(value['privateDir']); store = private / 'state/recovery-bootstrap'
    store.mkdir(parents=True, exist_ok=True); lifecycle.protect_directory(store)
    shared = production.store_path(value); shared.mkdir(parents=True, exist_ok=True)
    children, handles = [], []
    def spawn(command, name, env=None, cwd=None):
        output = (store / (name + '.log')).open('ab'); handles.append(output)
        child = subprocess.Popen(command, env=env, cwd=cwd, stdin=subprocess.DEVNULL,
            stdout=output, stderr=subprocess.STDOUT, creationflags=HIDDEN)
        children.append(child); return child
    try:
        # Production and bootstrap cannot own the wired listeners simultaneously.
        with lifecycle.mutation_lock(shared, timeout=0), lifecycle.mutation_lock(store, timeout=0):
            production.check_shared_owners(value); production.check_listeners(value)
            if (store / 'stop').exists(): raise ValueError('REMOVE_BOOTSTRAP_STOP_FILE_BEFORE_START')
            ready = store / 'ready.json'
            if ready.exists():
                record = lifecycle.parse(lifecycle.bounded_read(ready))
                if lifecycle.process_alive(record.get('pid')): raise ValueError('BOOTSTRAP_CONFIG_ALREADY_RUNNING')
                ready.unlink()
            lifecycle.atomic_write(store / 'policy.json', policy, protect=True)
            lifecycle.atomic_write(store / 'native-baseline.json', baseline, protect=True)
            deadline = time.monotonic() + seconds
            try:
                if not external_dhcp:
                    dhcp = store / 'dhcp'; dhcp.mkdir(exist_ok=True)
                    template = (SUITE / 'config/tftpd32.template.ini').read_text(encoding='ascii')
                    ini = dhcp / 'tftpd32.ini'
                    ini.write_text(template.replace('{LAB_IP}', value['labIp']).replace('{STATION_IP}', value['stationIp']), encoding='ascii')
                    spawn([value['dhcpExecutable']], 'dhcp', dict(os.environ, TFTP_INI=str(ini)), dhcp)
                server = spawn(arguments(value, store, seconds), 'config')
                wait_until = min(deadline, time.monotonic() + 10)
                while not ready.exists():
                    if any(child.poll() is not None for child in children): raise ValueError('BOOTSTRAP_CHILD_EXITED')
                    if (store / 'stop').exists(): return
                    if time.monotonic() >= wait_until: raise ValueError('BOOTSTRAP_READINESS_TIMEOUT')
                    time.sleep(0.1)
                record = lifecycle.parse(lifecycle.bounded_read(ready))
                if record.get('pid') != server.pid or record.get('state') != 'ready' or record.get('requireStationRelay') is not False:
                    raise ValueError('BOOTSTRAP_READINESS_INVALID')
                print(json.dumps({'ok': True, 'phase': 'ready', 'maxSeconds': seconds,
                    'stopFile': str(store / 'stop'), 'adbRequired': False, 'appsStarted': False,
                    'next': 'Complete official wired setup and play a fresh sound code manually; stop bootstrap before production.'}), flush=True)
                while not (store / 'stop').exists() and time.monotonic() < deadline:
                    if any(child.poll() is not None for child in children): raise ValueError('BOOTSTRAP_CHILD_EXITED')
                    time.sleep(0.25)
            finally:
                (store / 'stop').touch()
                for child in reversed(children):
                    try: child.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        child.terminate()
                        try: child.wait(timeout=3)
                        except subprocess.TimeoutExpired: child.kill(); child.wait(timeout=3)
    except lifecycle.WindowError as error:
        if str(error) == 'MUTATION_BUSY': raise ValueError('ANOTHER_PC_OWNER_IS_RUNNING') from None
        raise
    finally:
        for handle in handles: handle.close()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--deployment', type=Path, required=True)
    parser.add_argument('--external-dhcp', action='store_true')
    parser.add_argument('--max-seconds', type=int, default=1800)
    parser.add_argument('--check', action='store_true', help='Validate local inputs only; do not start listeners')
    args = parser.parse_args(argv)
    try:
        value, policy, baseline = load(args.deployment, external_dhcp=args.external_dhcp)
        if not 1 <= args.max_seconds <= 3600: raise ValueError('DURATION_MUST_BE_1_TO_3600_SECONDS')
        if not args.check: run(value, policy, baseline, external_dhcp=args.external_dhcp, seconds=args.max_seconds)
        print(json.dumps({'ok': True, 'phase': 'checked' if args.check else 'stopped', 'networkChanged': False, 'appsStarted': False}))
    except KeyboardInterrupt:
        print(json.dumps({'ok': True, 'phase': 'stopped', 'networkChanged': False, 'appsStarted': False}))
    except Exception as error:
        reason = str(error) if isinstance(error, ValueError) and re.fullmatch('[A-Z0-9_]{1,80}', str(error)) else 'BOOTSTRAP_FAILED'
        print(json.dumps({'ok': False, 'code': reason})); return 1
    return 0


if __name__ == '__main__': raise SystemExit(main())
