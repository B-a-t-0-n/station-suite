"""Offline native-layout/selection tests; no adapter reads, sockets, or PowerShell."""
import ctypes as C
from dataclasses import replace
import importlib
from pathlib import Path
import os
import sys
import threading
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import windows_physical_source as source

GUID = '01234567-89ab-cdef-0123-456789abcdef'
OTHER_GUID = 'fedcba98-7654-3210-fedc-ba9876543210'


def snapshot(**changes):
    return replace(source._Snapshot(10, GUID, 6, 1, 0x84,
        (source._Address('10.131.146.23'),)), **changes)


def fixture(buffer=None):
    """Build one real-layout GAA response entirely in one synthetic allocation."""
    buffer = buffer if buffer is not None else C.create_string_buffer(1024)
    base = C.addressof(buffer)
    C.memset(base, 0, C.sizeof(buffer))
    adapter = source._Adapter.from_buffer(buffer)
    adapter.Header.Fields.Length = C.sizeof(source._Adapter)
    adapter.Header.Fields.IndexOrFlags = 10
    adapter.AdapterName = base + 128
    adapter.FirstUnicastAddress = base + 256
    adapter.Flags, adapter.IfType, adapter.OperStatus = 0x84, 6, 1
    C.memmove(base + 128, ('{' + GUID + '}').encode() + b'\0', 39)
    unicast = source._Unicast.from_buffer(buffer, 256)
    unicast.Header.Fields.Length = C.sizeof(source._Unicast)
    unicast.Address.Sockaddr, unicast.Address.Length = base + 384, 16
    unicast.PrefixOrigin, unicast.SuffixOrigin, unicast.DadState = 3, 3, 4
    unicast.ValidLifetime = unicast.PreferredLifetime = unicast.LeaseLifetime = 120
    unicast.OnLinkPrefixLength = 24
    C.memmove(base + 384, b'\x02\0\0\0\x0a\x83\x92\x17' + b'\0' * 8, 16)
    return buffer, adapter, unicast


class PhysicalSourceTests(unittest.TestCase):
    def test_explicit_reconnected_interface_keeps_identity_and_no_fallback(self):
        reader = Mock(side_effect=[(snapshot(), snapshot(index=11)), (snapshot(),)])
        selected = source.WindowsPhysicalSource(interface_index=11,
            _pin_provider=lambda: GUID, _snapshot_reader=reader)
        self.assertEqual(selected(), (11, '10.131.146.23'))
        self.assert_code('ADAPTER_MISSING', selected)

    def test_native_parser_accepts_only_explicit_selected_index(self):
        buffer, adapter, _ = fixture()
        adapter.Header.Fields.IndexOrFlags = 11
        self.assertEqual(source._parse_buffer(buffer, C.sizeof(buffer)), ())
        self.assertEqual(source._parse_buffer(buffer, C.sizeof(buffer), 11)[0].index, 11)

    def test_bad_selected_interface_rejected_before_discovery(self):
        for value in (None, True, '11', 0, -1, 2147483648):
            self.assert_code('IDENTITY_INVALID', lambda: source.WindowsPhysicalSource(interface_index=value))

    def assert_code(self, code, action):
        with self.assertRaises(source.PhysicalSourceError) as caught:
            action()
        self.assertEqual(caught.exception.code, code)
        self.assertEqual(str(caught.exception), code)

    def test_import_and_construction_inert(self):
        with patch('subprocess.run', side_effect=AssertionError('I/O')), \
                patch('ctypes.WinDLL', create=True, side_effect=AssertionError('DLL I/O')):
            importlib.reload(source)
            source.WindowsPhysicalSource()

    def test_pin_once_but_read_current_dhcp_address_on_every_call(self):
        pin = Mock(return_value='{' + GUID.upper() + '}')
        read = Mock(side_effect=[(snapshot(),),
            (snapshot(addresses=(source._Address('10.131.146.42'),)),)])
        select = source.WindowsPhysicalSource(_pin_provider=pin, _snapshot_reader=read)
        self.assertEqual(select(), (10, '10.131.146.23'))
        self.assertEqual(select(), (10, '10.131.146.42'))
        self.assertEqual(pin.call_count, 1)
        self.assertEqual(read.call_count, 2)

    def test_concurrent_initial_calls_only_pin_once(self):
        pin, read = Mock(return_value=GUID), Mock(return_value=(snapshot(),))
        select = source.WindowsPhysicalSource(_pin_provider=pin, _snapshot_reader=read)
        results = []
        threads = [threading.Thread(target=lambda: results.append(select())) for _ in range(8)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=2)
        self.assertEqual(len(results), 8)
        self.assertEqual(pin.call_count, 1)
        self.assertEqual(read.call_count, 8)

    def test_no_other_adapter_or_tunnel_fallback(self):
        cases = [((), 'ADAPTER_MISSING'), ((snapshot(index=11),), 'ADAPTER_MISSING'),
            ((snapshot(), snapshot()), 'ADAPTER_AMBIGUOUS'),
            ((snapshot(guid=OTHER_GUID),), 'ADAPTER_CHANGED'),
            ((snapshot(if_type=131),), 'ADAPTER_NOT_PHYSICAL'),
            ((snapshot(oper_status=2),), 'ADAPTER_DOWN'),
            ((snapshot(flags=0x80),), 'DHCP_DISABLED'),
            ((snapshot(flags=0x04),), 'IPV4_DISABLED')]
        for values, code in cases:
            with self.subTest(code=code):
                self.assert_code(code, source.WindowsPhysicalSource(
                    _pin_provider=lambda: GUID, _snapshot_reader=lambda: values))

    def test_type_change_after_pin_rejected(self):
        read = Mock(side_effect=[(snapshot(),), (snapshot(if_type=71),)])
        select = source.WindowsPhysicalSource(_pin_provider=lambda: GUID, _snapshot_reader=read)
        select()
        self.assert_code('ADAPTER_CHANGED', select)

    def test_no_stale_address_when_adapter_goes_away(self):
        read = Mock(side_effect=[(snapshot(),), ()])
        select = source.WindowsPhysicalSource(_pin_provider=lambda: GUID, _snapshot_reader=read)
        select()
        self.assert_code('ADAPTER_MISSING', select)

    def test_only_preferred_live_dhcp_ipv4_in_specific_subnet(self):
        valid = source._Address('10.131.146.23')
        rejected = [replace(valid, address='192.168.1.2'), replace(valid, address='10.131.146.0'),
            replace(valid, address='10.131.146.255'), replace(valid, prefix_origin=1),
            replace(valid, dad_state=3), replace(valid, valid_lifetime=0),
            replace(valid, preferred_lifetime=0), replace(valid, prefix_length=33)]
        for address in rejected:
            with self.subTest(case=address):
                self.assert_code('ADDRESS_UNAVAILABLE', source.WindowsPhysicalSource(
                    _pin_provider=lambda: GUID,
                    _snapshot_reader=lambda: (snapshot(addresses=(address,)),)))
        select = source.WindowsPhysicalSource(_pin_provider=lambda: GUID,
            _snapshot_reader=lambda: (snapshot(addresses=tuple(rejected) + (valid,)),))
        self.assertEqual(select(), (10, '10.131.146.23'))

    def test_ambiguous_addresses_including_duplicate_fail_closed(self):
        for second in ('10.131.146.23', '10.131.146.24'):
            self.assert_code('ADDRESS_AMBIGUOUS', source.WindowsPhysicalSource(
                _pin_provider=lambda: GUID, _snapshot_reader=lambda: (snapshot(addresses=(
                    source._Address('10.131.146.23'), source._Address(second))),)))

    def test_safe_exception_never_exposes_provider_text(self):
        private = GUID + ' 10.131.146.23'
        self.assert_code('DISCOVERY_FAILED', source.WindowsPhysicalSource(
            _pin_provider=Mock(side_effect=RuntimeError(private))))
        self.assertEqual(str(source.PhysicalSourceError(private)), 'DISCOVERY_FAILED')

    def test_native_layout_prefix_and_single_snapshot(self):
        # Explicit offsets catch accidental Linux c_ulong or incorrect union alignment.
        if C.sizeof(C.c_void_p) == 8:
            self.assertEqual(source._Adapter.OperStatus.offset, 104)
            self.assertEqual(source._Unicast.OnLinkPrefixLength.offset, 56)
        buffer, _, _ = fixture()
        expected = snapshot(addresses=(source._Address('10.131.146.23',
            valid_lifetime=120, preferred_lifetime=120),))
        self.assertEqual(source._parse_buffer(buffer, C.sizeof(buffer)), (expected,))

    def test_rejects_out_of_buffer_pointers_and_short_structs(self):
        mutators = [lambda b, a, u: setattr(a, 'Next', C.addressof(b) + C.sizeof(b)),
            lambda b, a, u: setattr(a, 'AdapterName', 1),
            lambda b, a, u: setattr(a.Header.Fields, 'Length', 8),
            lambda b, a, u: setattr(u.Header.Fields, 'Length', 8),
            lambda b, a, u: setattr(u.Address, 'Sockaddr', 1),
            lambda b, a, u: setattr(u.Address, 'Length', -1),
            lambda b, a, u: setattr(u.Address, 'Length', 8)]
        for mutate in mutators:
            buffer, adapter, unicast = fixture()
            mutate(buffer, adapter, unicast)
            self.assert_code('NATIVE_BUFFER_INVALID', lambda: source._parse_buffer(buffer, 1024))

    def test_rejects_adapter_and_unicast_cycles(self):
        buffer, adapter, _ = fixture()
        adapter.Next = C.addressof(buffer)
        self.assert_code('NATIVE_BUFFER_INVALID', lambda: source._parse_buffer(buffer, 1024))
        buffer, _, unicast = fixture()
        unicast.Next = C.addressof(buffer) + 256
        self.assert_code('NATIVE_BUFFER_INVALID', lambda: source._parse_buffer(buffer, 1024))

    def test_rejects_ipv6_and_unterminated_name(self):
        buffer, _, _ = fixture()
        C.memmove(C.addressof(buffer) + 384, b'\x17\0', 2)
        self.assert_code('NATIVE_BUFFER_INVALID', lambda: source._parse_buffer(buffer, 1024))
        buffer, _, _ = fixture()
        C.memset(C.addressof(buffer) + 128, 65, 128)
        self.assert_code('NATIVE_BUFFER_INVALID', lambda: source._parse_buffer(buffer, 1024))

    def test_native_api_flags_and_bounded_resize(self):
        calls = []
        def api(family, flags, reserved, buffer, size):
            self.assertEqual((family, flags, reserved), (2, 0x2e, None))
            size = C.cast(size, C.POINTER(source.U32))
            calls.append(size.contents.value)
            if len(calls) == 1:
                size.contents.value = 20000
                return 111
            fixture(buffer)
            size.contents.value = 1024
            return 0
        expected = snapshot(addresses=(source._Address('10.131.146.23',
            valid_lifetime=120, preferred_lifetime=120),))
        self.assertEqual(source._read_adapters(api), (expected,))
        self.assertEqual(calls, [15360, 20000])

    def test_native_api_rejects_no_data_error_and_unbounded_resize(self):
        self.assert_code('NATIVE_API_FAILED', lambda: source._read_adapters(lambda *args: 232))
        def bad_size(*args):
            C.cast(args[-1], C.POINTER(source.U32)).contents.value = source.MAX_BUFFER + 1
            return 111
        self.assert_code('NATIVE_BUFFER_INVALID', lambda: source._read_adapters(bad_size))
        def repeated_resize(*args):
            value = C.cast(args[-1], C.POINTER(source.U32))
            value.contents.value += 1
            return 111
        self.assert_code('NATIVE_API_FAILED', lambda: source._read_adapters(repeated_resize))

    def test_power_shell_pin_rejects_nonhardware_and_fixed_output_contract(self):
        import json
        def output(**changes):
            value = dict(index=10, hardware=True, up=True, guid=GUID)
            value.update(changes)
            return Mock(returncode=0, stdout=json.dumps(value).encode())
        with patch.object(source.os, 'name', 'nt'), patch.object(source.subprocess, 'run') as run:
            run.return_value = output()
            self.assertEqual(source._hardware_pin(), GUID)
            for changes in (dict(hardware=False), dict(up=False), dict(index=11), dict(index=True)):
                run.return_value = output(**changes)
                self.assert_code('PIN_FAILED', source._hardware_pin)
            run.return_value = output(guid='invalid')
            self.assert_code('IDENTITY_INVALID', source._hardware_pin)


if __name__ == '__main__':
    unittest.main()
