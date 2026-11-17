"""Reactive PC check for wired mode. It is not an early-boot or autonomous firewall.

The caller supplies a bounded fixed-target ADB executor. No device operations
occur on import. Disabling Wi-Fi after drift leaves a polling window and cannot
establish protection while the PC/ADB is absent.
"""
from __future__ import annotations

import ipaddress
import re
import time


WIFI_STATE = ('shell', 'settings', 'get', 'global', 'wifi_on')
WIFI_DISABLE = ('shell', 'svc', 'wifi', 'disable')
WLAN_IPV4 = ('shell', 'ip', '-4', '-o', 'addr', 'show', 'dev', 'wlan0')
WLAN_IPV6 = ('shell', 'ip', '-6', '-o', 'addr', 'show', 'dev', 'wlan0')
MAX_OUTPUT = 16384
REPAIR_READS, REPAIR_PAUSE_SECONDS = 8, 0.2
ERROR_CODES = frozenset({'WIFI_GUARD_COMMAND_FAILED', 'WIFI_GUARD_STATE_INVALID',
                         'WIFI_GUARD_ADDRESS_INVALID', 'WIFI_GUARD_REPAIR_FAILED',
                         'WIFI_GUARD_CALLBACK_FAILED'})


class NetworkGuardError(ValueError):
    """Only fixed diagnostic codes; never expose raw command output or secrets."""


def _read(execute, arguments):
    try:
        value = execute(*arguments)
    except Exception:
        raise NetworkGuardError('WIFI_GUARD_COMMAND_FAILED') from None
    if type(value) is not str or len(value) > MAX_OUTPUT:
        raise NetworkGuardError('WIFI_GUARD_COMMAND_FAILED')
    return value.strip()


def _wifi_state(execute):
    value = _read(execute, WIFI_STATE)
    if value not in {'0', '1', '2', '3'}:
        raise NetworkGuardError('WIFI_GUARD_STATE_INVALID')
    return value


def _has_address(value, family):
    """Parse complete fixed `ip -o addr` records; unknown text is never 'no address'."""
    found = False
    for line in value.splitlines():
        parts = line.split()
        if (len(parts) < 6 or re.fullmatch(r'[0-9]+:', parts[0]) is None
                or parts[1] != 'wlan0' or parts[2] != ('inet' if family == 4 else 'inet6')
                or 'scope' not in parts[4:]):
            raise NetworkGuardError('WIFI_GUARD_ADDRESS_INVALID')
        try:
            address = ipaddress.ip_interface(parts[3])
        except ValueError:
            raise NetworkGuardError('WIFI_GUARD_ADDRESS_INVALID') from None
        if address.version != family:
            raise NetworkGuardError('WIFI_GUARD_ADDRESS_INVALID')
        scope_index = parts.index('scope', 4)
        if scope_index + 1 >= len(parts) or parts[scope_index + 1] not in {'global', 'link', 'host'}:
            raise NetworkGuardError('WIFI_GUARD_ADDRESS_INVALID')
        # All assigned IPv4 is unexpected in wired-only mode. For IPv6, reject
        # global-scope addresses and anything outside link-local/loopback even
        # if a malformed record falsely labels it link/host.
        if family == 4 or parts[scope_index + 1] == 'global' or not (
                address.ip.is_link_local or address.ip.is_loopback):
            found = True
    return found


def _wlan_has_addresses(execute):
    # Read both families even when IPv4 is present; this diagnostic must not
    # silently infer IPv6 state from the first check.
    ipv4 = _has_address(_read(execute, WLAN_IPV4), 4)
    ipv6 = _has_address(_read(execute, WLAN_IPV6), 6)
    return ipv4 or ipv6


def ensure_wifi_off(execute, before_repair=lambda: None):
    """Return whether drift was corrected; raise on unknown or uncorrected state.

    `execute(*fixed_adb_arguments)` must target the configured Station and have
    its own bounded timeout. before_repair closes the caller's relay lease before
    repair. Even if that callback fails, attempt the fixed Wi-Fi-disable command.
    This only measures current wired-mode state, never continuous OTA protection.
    """
    state = _wifi_state(execute)
    if state == '0' and not _wlan_has_addresses(execute):
        return False
    callback_failed = False
    try:
        before_repair()
    except Exception:
        callback_failed = True
    finally:
        try:
            _read(execute, WIFI_DISABLE)
        except NetworkGuardError:
            raise NetworkGuardError('WIFI_GUARD_REPAIR_FAILED') from None
    for attempt in range(REPAIR_READS):
        if _wifi_state(execute) == '0' and not _wlan_has_addresses(execute):
            if callback_failed:
                raise NetworkGuardError('WIFI_GUARD_CALLBACK_FAILED')
            return True
        if attempt + 1 < REPAIR_READS:
            time.sleep(REPAIR_PAUSE_SECONDS)
    raise NetworkGuardError('WIFI_GUARD_REPAIR_FAILED')
