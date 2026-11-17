"""Reactive wired-mode safety tests: fake ADB only; never toggle real Wi-Fi."""
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import station_network_guard as m


IPV4 = '6: wlan0    inet 192.0.2.10/24 brd 192.0.2.255 scope global wlan0\\ valid_lft forever preferred_lft forever'
IPV6 = '6: wlan0    inet6 2001:db8::123/64 scope global dynamic \\ valid_lft 20sec preferred_lft 10sec'
LINK_LOCAL = '6: wlan0    inet6 fe80::123/64 scope link \\ valid_lft forever preferred_lft forever'


class FakeAdb:
    def __init__(self, state='0', ipv4='', ipv6=''):
        self.state, self.ipv4, self.ipv6 = state, ipv4, ipv6
        self.calls, self.repairs = [], 0
        self.disable_error = self.read_error = None
        self.repair_works = True
    def __call__(self, *arguments):
        self.calls.append(arguments)
        if arguments == m.WIFI_STATE:
            if self.read_error is not None: raise self.read_error
            return self.state
        if arguments == m.WLAN_IPV4: return self.ipv4
        if arguments == m.WLAN_IPV6: return self.ipv6
        if arguments == m.WIFI_DISABLE:
            self.repairs += 1
            if self.disable_error is not None: raise self.disable_error
            if self.repair_works:
                self.state, self.ipv4, self.ipv6 = '0', '', ''
            return ''
        raise AssertionError('Non-fixed ADB command')


class StationNetworkGuardTests(unittest.TestCase):
    def setUp(self):
        self.pause = patch.object(m.time, 'sleep').start()
        self.addCleanup(patch.stopall)

    def test_disabled_clean_state_reads_both_families_without_mutation(self):
        adb = FakeAdb(ipv6=LINK_LOCAL)
        callback = Mock()
        self.assertIs(m.ensure_wifi_off(adb, callback), False)
        self.assertEqual(adb.calls, [m.WIFI_STATE, m.WLAN_IPV4, m.WLAN_IPV6])
        callback.assert_not_called()
        self.assertEqual(adb.repairs, 0)

    def test_each_enabled_setting_closes_lease_before_fixed_disable(self):
        for state in ('1', '2', '3'):
            with self.subTest(state=state):
                adb = FakeAdb(state=state)
                order = []
                def execute(*args):
                    order.append(args); return adb(*args)
                def close(): order.append('lease_closed')
                self.assertIs(m.ensure_wifi_off(execute, close), True)
                self.assertLess(order.index('lease_closed'), order.index(m.WIFI_DISABLE))
                self.assertEqual(adb.repairs, 1)

    def test_zero_setting_with_ipv4_or_global_ipv6_still_requires_repair(self):
        for ipv4, ipv6 in ((IPV4, ''), ('', IPV6), (IPV4, IPV6)):
            with self.subTest(ipv4=bool(ipv4), ipv6=bool(ipv6)):
                adb = FakeAdb(ipv4=ipv4, ipv6=ipv6); callback = Mock()
                self.assertIs(m.ensure_wifi_off(adb, callback), True)
                callback.assert_called_once_with()
                self.assertEqual(adb.repairs, 1)

    def test_unknown_settings_never_mean_off_and_do_not_echo_input(self):
        for raw in ('', 'null', '4', '01', '0\nPRIVATE_SENTINEL', 'false'):
            with self.subTest(raw=raw):
                adb = FakeAdb(state=raw)
                with self.assertRaisesRegex(m.NetworkGuardError, '^WIFI_GUARD_STATE_INVALID$') as error:
                    m.ensure_wifi_off(adb)
                self.assertNotIn('PRIVATE_SENTINEL', str(error.exception))
                self.assertEqual(adb.repairs, 0)

    def test_timeout_is_unknown_state_and_never_echoes_private_exception(self):
        adb = FakeAdb(); adb.read_error = TimeoutError('PRIVATE_SENTINEL')
        with self.assertRaisesRegex(m.NetworkGuardError, '^WIFI_GUARD_COMMAND_FAILED$') as error:
            m.ensure_wifi_off(adb)
        self.assertNotIn('PRIVATE_SENTINEL', str(error.exception))
        self.assertEqual(adb.repairs, 0)

    def test_failed_disable_and_lying_success_never_return_corrected(self):
        adb = FakeAdb(state='1'); adb.disable_error = OSError('PRIVATE_SENTINEL')
        with self.assertRaisesRegex(m.NetworkGuardError, '^WIFI_GUARD_REPAIR_FAILED$'):
            m.ensure_wifi_off(adb)
        adb = FakeAdb(state='1'); adb.repair_works = False
        with self.assertRaisesRegex(m.NetworkGuardError, '^WIFI_GUARD_REPAIR_FAILED$'):
            m.ensure_wifi_off(adb)
        self.assertEqual(adb.calls.count(m.WIFI_STATE), 1 + m.REPAIR_READS)
        self.assertEqual(self.pause.call_count, m.REPAIR_READS - 1)

    def test_failed_lease_callback_still_disables_but_never_returns_success(self):
        adb = FakeAdb(state='1')
        def fail(): raise RuntimeError('PRIVATE_SENTINEL')
        with self.assertRaisesRegex(m.NetworkGuardError, '^WIFI_GUARD_CALLBACK_FAILED$') as error:
            m.ensure_wifi_off(adb, fail)
        self.assertEqual(adb.repairs, 1)
        self.assertEqual(adb.state, '0')
        self.assertNotIn('PRIVATE_SENTINEL', str(error.exception))

    def test_async_disable_retries_with_finite_budget_and_checks_addresses(self):
        adb = FakeAdb(state='1'); real = adb.__call__
        reads_after_disable = 0
        def execute(*args):
            nonlocal reads_after_disable
            if args == m.WIFI_STATE and adb.repairs:
                reads_after_disable += 1
                if reads_after_disable == 1: return '1'
                if reads_after_disable == 2:
                    adb.state, adb.ipv4 = '0', IPV4
                else: adb.state, adb.ipv4 = '0', ''
            return real(*args)
        self.assertIs(m.ensure_wifi_off(execute), True)
        self.assertEqual(reads_after_disable, 3)
        self.assertEqual(self.pause.call_count, 2)

    def test_unknown_address_output_cannot_be_accepted_as_absence(self):
        bad = ['permission denied PRIVATE_SENTINEL',
               '6: wlan0 inet BAD scope global wlan0',
               '6: eth0 inet 192.0.2.1/24 scope global eth0',
               '6: wlan0 inet6 2001:db8::1/64 scope',
               '6: wlan0 inet 192.0.2.1/24 scope UNKNOWN wlan0']
        for raw in bad:
            with self.subTest(raw=raw):
                adb = FakeAdb(ipv4=raw)
                with self.assertRaisesRegex(m.NetworkGuardError, '^WIFI_GUARD_ADDRESS_INVALID$') as error:
                    m.ensure_wifi_off(adb)
                self.assertNotIn('PRIVATE_SENTINEL', str(error.exception))

    def test_wrong_output_types_and_excessive_output_are_rejected(self):
        for raw in (None, b'0', True, '0' * (m.MAX_OUTPUT + 1)):
            with self.subTest(kind=type(raw).__name__):
                with self.assertRaisesRegex(m.NetworkGuardError, '^WIFI_GUARD_COMMAND_FAILED$'):
                    m.ensure_wifi_off(lambda *args: raw)

    def test_runtime_recheck_detects_later_drift_and_result_is_only_boolean(self):
        adb = FakeAdb()
        first = m.ensure_wifi_off(adb)
        adb.state = '1'
        second = m.ensure_wifi_off(adb)
        self.assertIs(type(first), bool); self.assertIs(type(second), bool)
        self.assertEqual((first, second), (False, True))
        self.assertTrue(set(adb.calls).issubset({m.WIFI_STATE, m.WIFI_DISABLE, m.WLAN_IPV4, m.WLAN_IPV6}))


if __name__ == '__main__': unittest.main()
