"""Collect bounded read-only facts for guard_plan; never installs or enables a guard."""
from __future__ import annotations

import argparse
import ipaddress
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys

FINGERPRINT = 'Yandex/yandexstation_2/yandexstation_2:9/PPR1.180610.011/20221212:user/release-keys'
BOOT_ID = re.compile(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\Z')
TARGET = re.compile(r'[A-Za-z0-9][A-Za-z0-9_.:\-]{0,127}\Z')


class ProbeError(ValueError):
    """Fixed diagnostics only: never include adb output or command arguments."""


class AdbReader:
    def __init__(self, executable: Path, target: str):
        if not executable.is_absolute() or not executable.is_file():
            raise ProbeError('ABSOLUTE_ADB_FILE_REQUIRED')
        if not isinstance(target, str) or not TARGET.fullmatch(target):
            raise ProbeError('INVALID_ADB_TARGET')
        self.executable, self.target = str(executable), target

    def read(self, *args: str) -> str:
        # Every caller below supplies fixed tokens. No user text becomes shell code.
        try:
            result = subprocess.run([self.executable, '-s', self.target, 'shell',
                '-T', shlex.join(args)], capture_output=True, timeout=10,
                creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        except Exception:
            raise ProbeError('ADB_READ_FAILED') from None
        if result.returncode or len(result.stdout) > 65536:
            raise ProbeError('ADB_READ_FAILED')
        try:
            return result.stdout.decode('ascii').strip()
        except UnicodeDecodeError:
            raise ProbeError('ADB_OUTPUT_INVALID') from None


def one_line(value: str, pattern: str, code: str) -> str:
    if not isinstance(value, str) or not re.fullmatch(pattern, value):
        raise ProbeError(code)
    return value


def collect(reader: AdbReader) -> dict:
    boot_before = one_line(reader.read('cat', '/proc/sys/kernel/random/boot_id'),
                           BOOT_ID.pattern, 'BOOT_ID_UNAVAILABLE')
    if reader.read('getprop', 'ro.build.fingerprint') != FINGERPRINT:
        raise ProbeError('FIRMWARE_NOT_PINNED')
    build = one_line(reader.read('getprop', 'ro.build.version.incremental'),
                     r'20221212', 'BUILD_NOT_PINNED')
    api = one_line(reader.read('getprop', 'ro.build.version.sdk'), r'28', 'API_NOT_PINNED')
    uid = int(one_line(reader.read('id', '-u'), r'0|[1-9][0-9]{0,9}', 'UID_INVALID'))
    status = reader.read('cat', '/proc/self/status')
    caps = re.findall(r'^CapEff:\s*([0-9a-fA-F]{16})\s*$', status, re.MULTILINE)
    if len(caps) != 1:
        raise ProbeError('CAPABILITIES_UNAVAILABLE')
    selinux = one_line(reader.read('getenforce'), r'Enforcing|Permissive|Disabled',
                        'SELINUX_STATE_INVALID')
    firewall = {}
    for family, binary in (('ipv4', 'iptables'), ('ipv6', 'ip6tables')):
        # Executable presence is deliberately not called rule-enforcement support.
        result = reader.read('sh', '-c',
            'if [ -x /system/bin/' + binary + ' ]; then echo binary_present; else echo missing; fi')
        firewall[family] = one_line(result, r'binary_present|missing', 'FIREWALL_PROBE_INVALID')
    management = 'unknown'
    try:
        host, port = reader.target.rsplit(':', 1)
        address = ipaddress.IPv4Address(host)
        if port != '5555':
            raise ValueError()
        ethernet = reader.read('ip', '-4', '-o', 'addr', 'show', 'dev', 'eth0')
        addresses = re.findall(r'\binet\s+(\d+\.\d+\.\d+\.\d+)/[0-9]+\b', ethernet)
        if str(address) in addresses:
            management = 'wired_ip'
    except ValueError:
        # A non-IP serial alone is insufficient proof of an independent USB path.
        pass
    boot_after = one_line(reader.read('cat', '/proc/sys/kernel/random/boot_id'),
                          BOOT_ID.pattern, 'BOOT_ID_UNAVAILABLE')
    if boot_before != boot_after:
        raise ProbeError('DEVICE_REBOOTED_DURING_PROBE')
    return {'schemaVersion': 1, 'device': {'build': build, 'api': int(api)},
        'principal': {'uid': uid, 'capEffHex': caps[0].lower(), 'selinux': selinux},
        'firewall': firewall, 'management': {'kind': management}}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', type=Path, required=True)
    parser.add_argument('--target', required=True)
    args = parser.parse_args(argv)
    try:
        result = collect(AdbReader(args.adb, args.target))
        print(json.dumps(result)); return 0
    except Exception as error:
        code = str(error) if isinstance(error, ProbeError) else 'PROBE_FAILED'
        print(json.dumps({'ok': False, 'code': code})); return 1


if __name__ == '__main__':
    sys.exit(main())
