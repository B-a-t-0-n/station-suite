"""Probe failure/cross-boot tests; no device or network is contacted."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('egress_probe', Path(__file__).resolve().parents[1] / 'probe_device.py')
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)
BOOT = '01234567-89ab-cdef-0123-456789abcdef'


class Reader:
    target = '192.168.77.100:5555'

    def __init__(self, overrides=None, reboot=False):
        self.overrides, self.reboot, self.boot_reads = overrides or {}, reboot, 0
        self.calls = []

    def read(self, *args):
        self.calls.append(args)
        if args == ('cat', '/proc/sys/kernel/random/boot_id'):
            self.boot_reads += 1
            return 'ffffffff-89ab-cdef-0123-456789abcdef' if self.reboot and self.boot_reads == 2 else BOOT
        values = {('getprop', 'ro.build.fingerprint'): probe.FINGERPRINT,
            ('getprop', 'ro.build.version.incremental'): '20221212',
            ('getprop', 'ro.build.version.sdk'): '28', ('id', '-u'): '2000',
            ('cat', '/proc/self/status'): 'Name:\tsh\nCapEff:\t0000000000000000\n',
            ('getenforce',): 'Enforcing',
            ('ip', '-4', '-o', 'addr', 'show', 'dev', 'eth0'): '2: eth0 inet 192.168.77.100/24 scope global eth0'}
        if args in self.overrides:
            return self.overrides[args]
        if args[:2] == ('sh', '-c'):
            return 'binary_present'
        return values[args]


class ProbeTests(unittest.TestCase):
    def test_shell_snapshot_never_contains_protection_claims_or_device_id(self):
        r = Reader(); result = probe.collect(r)
        self.assertEqual(result['principal']['uid'], 2000)
        self.assertEqual(result['principal']['capEffHex'], '0000000000000000')
        self.assertEqual(result['management']['kind'], 'wired_ip')
        self.assertEqual(set(result), {'schemaVersion', 'device', 'principal', 'firewall', 'management'})
        self.assertNotIn('target', result)
        self.assertNotIn('otaProtected', result)

    def test_unknown_firmware_aborts_before_privilege_or_network_reads(self):
        r = Reader({('getprop', 'ro.build.fingerprint'): 'other-device'})
        with self.assertRaisesRegex(probe.ProbeError, '^FIRMWARE_NOT_PINNED$'):
            probe.collect(r)
        self.assertEqual(len(r.calls), 2)

    def test_reboot_during_collection_rejects_mixed_state(self):
        with self.assertRaisesRegex(probe.ProbeError, '^DEVICE_REBOOTED_DURING_PROBE$'):
            probe.collect(Reader(reboot=True))

    def test_missing_or_duplicate_capability_line_cannot_become_zero(self):
        for raw in ('', 'CapEff:\t0000000000000000\nCapEff:\t0000000000001000'):
            with self.subTest(raw=raw), self.assertRaisesRegex(probe.ProbeError, '^CAPABILITIES_UNAVAILABLE$'):
                probe.collect(Reader({('cat', '/proc/self/status'): raw}))

    def test_wifi_tcp_and_opaque_serial_are_not_claimed_independent(self):
        for target in ('192.168.0.10:5555', 'PHONE123', '192.168.77.100:22'):
            r = Reader(); r.target = target
            self.assertEqual(probe.collect(r)['management']['kind'], 'unknown')

    def test_adb_errors_do_not_expose_captured_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            adb = Path(tmp) / 'adb.exe'; adb.write_bytes(b'')
            reader = probe.AdbReader(adb, '192.168.77.100:5555')
            with patch.object(probe.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, b'PRIVATE_SENTINEL', b'PRIVATE_SENTINEL')):
                with self.assertRaisesRegex(probe.ProbeError, '^ADB_READ_FAILED$'):
                    reader.read('id', '-u')

    def test_adb_shell_receives_one_quoted_fixed_command_and_deadline(self):
        with tempfile.TemporaryDirectory() as tmp:
            adb = Path(tmp) / 'adb.exe'; adb.write_bytes(b'')
            reader = probe.AdbReader(adb, '192.168.77.100:5555')
            with patch.object(probe.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, b'2000\r\n', b'')) as run:
                self.assertEqual(reader.read('id', '-u'), '2000')
            self.assertEqual(run.call_args.args[0], [str(adb), '-s', reader.target, 'shell', '-T', 'id -u'])
            self.assertEqual(run.call_args.kwargs['timeout'], 10)

    def test_target_cannot_add_adb_option_or_shell_command(self):
        with tempfile.TemporaryDirectory() as tmp:
            adb = Path(tmp) / 'adb.exe'; adb.write_bytes(b'')
            for value in ('--help', 'x;svc wifi enable', 'x\ny', 'x y'):
                with self.subTest(value=value), self.assertRaises(probe.ProbeError):
                    probe.AdbReader(adb, value)


if __name__ == '__main__':
    unittest.main()
