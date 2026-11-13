"""Own DHCP + fixed native relay + allowlisted tunnel; no PC voice processing.

First configure the dedicated cable with Install-StationLab.ps1. This entry point
does not configure NICs, NAT, Wi-Fi, factory firmware or native certificate trust.
"""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import time

from deployment import SUITE, environment, load

HIDDEN = getattr(subprocess, 'CREATE_NO_WINDOW', 0)


def run(value):
    private = Path(value['privateDir']).resolve()
    state = private / 'state'; state.mkdir(parents=True, exist_ok=True)
    env = environment(value)
    children, handles = [], []
    stop = state / 'stop'
    if stop.exists(): raise ValueError('REMOVE_EXPLICIT_STOP_FILE_BEFORE_START')
    # Local relay starts closed; only the transport supervisor can lease it open.
    (state / 'local-config-relay.json').write_text('{"version":1,"enabled":false,"expiresAtMs":0}', encoding='ascii')
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
    # Duplicate listeners fail instead of reusing/stealing a running installation.
    for address, port in [(value['labIp'], 443), (value['voiceIp'], 443), ('127.0.0.1', 18443)]:
        with socket.socket() as probe:
            if probe.connect_ex((address, port)) == 0: raise ValueError('PORT_ALREADY_IN_USE')
    (state / 'config-stop').unlink(missing_ok=True)
    runtime_dir = private / 'dhcp'; runtime_dir.mkdir(exist_ok=True)
    template = (SUITE / 'config/tftpd32.template.ini').read_text(encoding='ascii')
    ini = runtime_dir / 'tftpd32.ini'
    ini.write_text(template.replace('{LAB_IP}', value['labIp']).replace('{STATION_IP}', value['stationIp']), encoding='ascii')
    try:
        spawn([value['dhcpExecutable']], 'dhcp', dict(env, TFTP_INI=str(ini)), runtime_dir)
        adb('connect', value['serial'], required=False)
        adb('shell', 'svc', 'wifi', 'disable')
        adb('shell', 'am', 'start', '-n', 'local.station.agent/.MainActivity')
        adb('shell', 'am', 'broadcast', '-n', 'local.station.voicebridge/.RuntimeReceiver', '--es', 'operation', 'local_services_start')
        adb('shell', 'am', 'broadcast', '-n', 'local.station.voicebridge/.RuntimeReceiver', '--es', 'operation', 'runtime_enable')
        adb('shell', 'am', 'start', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
        helper = ['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
            str(SUITE / 'pc/Start-StationInputHelper.ps1'), '-Adb', value['adb'],
            '-Target', value['serial'], '-Apk', value['agentApk']]
        subprocess.run(helper, capture_output=True, timeout=30, check=True, creationflags=HIDDEN)
        spawn([value['python'], '-B', str(SUITE / 'pc/tunnel_only_supervisor.py'), 'run'], 'tunnel')
        arguments = [value['python'], '-B', str(SUITE / 'pc/LocalConfigServer.py')]
        for flag, path in [('cert', private / 'pc/fullchain.pem'), ('key', private / 'pc/quasar-all.key'),
            ('events', state / 'config-events.jsonl'), ('ready', state / 'config-ready.json'),
            ('stop-file', state / 'config-stop'), ('policy', private / 'pc/config-policy.json'),
            ('sync-payload', private / 'pc/native-baseline.json'), ('station-relay', state / 'local-config-relay.json')]:
            arguments += ['--' + flag, str(path)]
        arguments += ['--require-station-relay', '--max-seconds', '86400']
        spawn(arguments, 'config')
        next_guard = time.monotonic() + 15
        while not stop.exists():
            if any(child.poll() is not None for child in children): raise ValueError('OWNED_PROCESS_EXITED')
            if time.monotonic() >= next_guard:
                next_guard = time.monotonic() + 30
                # Any OTA response/pin mismatch closes the whole relay. No WAN fallback.
                result = subprocess.run([value['python'], '-B', str(SUITE / 'pc/probe_local_ota.py')],
                    env=env, capture_output=True, timeout=8, creationflags=HIDDEN)
                if result.returncode: raise ValueError('OTA_GUARD_FAILED')
                agent = adb('shell', 'pidof', 'local.station.agent', required=False)
                if agent.returncode:
                    adb('shell', 'am', 'start', '-n', 'local.station.agent/.MainActivity', required=False)
                    adb('shell', 'am', 'start', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME', required=False)
            time.sleep(0.5)
    finally:
        (state / 'local-config-relay.json').write_text('{"version":1,"enabled":false,"expiresAtMs":0}', encoding='ascii')
        (state / 'config-stop').touch()
        tunnel_stop = private / 'tunnel-only-supervisor/stop'
        tunnel_stop.parent.mkdir(parents=True, exist_ok=True); tunnel_stop.touch()
        for child in reversed(children):
            try: child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                child.terminate()
                try: child.wait(timeout=3)
                except subprocess.TimeoutExpired: child.kill()
        for handle in handles: handle.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--deployment', type=Path, required=True)
    parser.add_argument('--check', action='store_true', help='Validate files/settings only; start nothing')
    args = parser.parse_args()
    try:
        value = load(args.deployment)
        if args.check: print('{"ok":true,"configurationValidated":true,"servicesStarted":false}')
        else: run(value)
    except KeyboardInterrupt: pass
    except Exception as error:
        code = str(error) if isinstance(error, ValueError) and str(error).isupper() else 'STARTUP_FAILED'
        print(json.dumps({'ok': False, 'code': code})); raise SystemExit(1) from None


if __name__ == '__main__': main()
