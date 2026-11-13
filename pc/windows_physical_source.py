"""Fresh, fail-closed physical IPv4 selection. Import and construction perform no I/O.

The first call pins the hardware adapter identity using PowerShell. Every call,
including the first, reads current addresses with Windows GetAdaptersAddresses.
No addresses, GUIDs, subprocess output, or native error text are logged.
"""
from __future__ import annotations

import ctypes as C
from dataclasses import dataclass, field
import ipaddress
import json
import os
import subprocess
import threading
import uuid

INTERFACE = int(os.environ.get('STATION_WAN_INTERFACE', '0'))
NETWORK = ipaddress.IPv4Network(os.environ.get('STATION_WAN_NETWORK', '192.0.2.0/24'))
AF_INET, ERROR_BUFFER_OVERFLOW = 2, 111
GAA_FLAGS = 0x02 | 0x04 | 0x08 | 0x20
MAX_BUFFER, MAX_ADAPTERS, MAX_UNICAST = 1024 * 1024, 256, 1024
_CODES = frozenset({'WINDOWS_REQUIRED', 'PIN_FAILED', 'IDENTITY_INVALID',
    'ADAPTER_MISSING', 'ADAPTER_AMBIGUOUS', 'ADAPTER_CHANGED', 'ADAPTER_DOWN',
    'ADAPTER_NOT_PHYSICAL', 'DHCP_DISABLED', 'IPV4_DISABLED', 'ADDRESS_UNAVAILABLE',
    'ADDRESS_AMBIGUOUS', 'NATIVE_API_FAILED', 'NATIVE_BUFFER_INVALID', 'DISCOVERY_FAILED'})


class PhysicalSourceError(Exception):
    """A fixed safe code; never includes IPs, GUIDs, or command output."""
    def __init__(self, code):
        self.code = code if code in _CODES else 'DISCOVERY_FAILED'
        super().__init__(self.code)


# Windows ULONG stays 32-bit on both supported Windows pointer widths.
U32, I32 = C.c_uint32, C.c_int32


class _HeaderFields(C.Structure):
    _fields_ = [('Length', U32), ('IndexOrFlags', U32)]


class _Header(C.Union):
    _fields_ = [('Alignment', C.c_uint64), ('Fields', _HeaderFields)]


class _SocketAddress(C.Structure):
    _fields_ = [('Sockaddr', C.c_void_p), ('Length', I32)]


class _Unicast(C.Structure):
    _fields_ = [('Header', _Header), ('Next', C.c_void_p),
        ('Address', _SocketAddress), ('PrefixOrigin', I32), ('SuffixOrigin', I32),
        ('DadState', I32), ('ValidLifetime', U32), ('PreferredLifetime', U32),
        ('LeaseLifetime', U32), ('OnLinkPrefixLength', C.c_ubyte)]


class _Adapter(C.Structure):
    # Prefix of IP_ADAPTER_ADDRESSES_LH through OperStatus, not a full SDK struct.
    _fields_ = [('Header', _Header), ('Next', C.c_void_p), ('AdapterName', C.c_void_p),
        ('FirstUnicastAddress', C.c_void_p), ('FirstAnycastAddress', C.c_void_p),
        ('FirstMulticastAddress', C.c_void_p), ('FirstDnsServerAddress', C.c_void_p),
        ('DnsSuffix', C.c_void_p), ('Description', C.c_void_p), ('FriendlyName', C.c_void_p),
        ('PhysicalAddress', C.c_ubyte * 8), ('PhysicalAddressLength', U32),
        ('Flags', U32), ('Mtu', U32), ('IfType', U32), ('OperStatus', I32)]


@dataclass(frozen=True)
class _Address:
    address: str = field(repr=False)
    prefix_origin: int = 3
    dad_state: int = 4
    valid_lifetime: int = 1
    preferred_lifetime: int = 1
    prefix_length: int = 24


@dataclass(frozen=True)
class _Snapshot:
    index: int
    guid: str = field(repr=False)
    if_type: int
    oper_status: int
    flags: int
    addresses: tuple[_Address, ...] = field(repr=False)


def _guid(value):
    try:
        if not isinstance(value, str) or len(value) > 40:
            raise ValueError()
        return str(uuid.UUID(value.strip('{}')))
    except (ValueError, AttributeError):
        raise PhysicalSourceError('IDENTITY_INVALID') from None


def _hardware_pin(interface_index=INTERFACE):
    if os.name != 'nt':
        raise PhysicalSourceError('WINDOWS_REQUIRED')
    script = ("$ErrorActionPreference='Stop'; $a=Get-NetAdapter -InterfaceIndex " + str(interface_index) + "; "
        "[pscustomobject]@{index=[int]$a.ifIndex;hardware=[bool]$a.HardwareInterface;"
        "up=($a.Status -eq 'Up');guid=([string]$a.InterfaceGuid)}|ConvertTo-Json -Compress")
    try:
        result = subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive',
            '-Command', script], capture_output=True, timeout=10,
            creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0), check=False)
        if result.returncode or len(result.stdout) > 4096:
            raise ValueError()
        value = json.loads(result.stdout.decode('utf-8-sig'))
        if (not isinstance(value, dict) or type(value.get('index')) is not int
                or value['index'] != interface_index or value.get('hardware') is not True
                or value.get('up') is not True):
            raise ValueError()
        return _guid(value.get('guid'))
    except PhysicalSourceError:
        raise
    except (OSError, ValueError, UnicodeError, subprocess.SubprocessError):
        raise PhysicalSourceError('PIN_FAILED') from None


def _parse_buffer(buffer, size, interface_index=INTERFACE):
    """Parse only in-buffer pointers. This also makes malformed native fixtures safe."""
    base = C.addressof(buffer)
    if not 8 <= size <= C.sizeof(buffer):
        raise PhysicalSourceError('NATIVE_BUFFER_INVALID')
    end = base + size

    def checked(pointer, length):
        if not isinstance(pointer, int) or length < 0 or not base <= pointer <= end - length:
            raise PhysicalSourceError('NATIVE_BUFFER_INVALID')
        return pointer

    def structure(pointer, kind, minimum):
        checked(pointer, 4)
        declared = U32.from_address(pointer).value
        checked(pointer, declared)
        if declared < minimum:
            raise PhysicalSourceError('NATIVE_BUFFER_INVALID')
        # Copy only known fields, never dereference typed pointers recursively.
        return kind.from_buffer_copy(C.string_at(pointer, minimum).ljust(C.sizeof(kind), b'\0'))

    def name(pointer):
        checked(pointer, 1)
        value = C.string_at(pointer, min(128, end - pointer))
        if b'\0' not in value:
            raise PhysicalSourceError('NATIVE_BUFFER_INVALID')
        try:
            return _guid(value.split(b'\0', 1)[0].decode('ascii'))
        except UnicodeError:
            raise PhysicalSourceError('IDENTITY_INVALID') from None

    adapters, seen, pointer = [], set(), base
    while pointer:
        if pointer in seen or len(seen) >= MAX_ADAPTERS:
            raise PhysicalSourceError('NATIVE_BUFFER_INVALID')
        seen.add(pointer)
        adapter = structure(pointer, _Adapter, _Adapter.OperStatus.offset + 4)
        if adapter.Header.Fields.IndexOrFlags == interface_index:
            addresses, unicast_seen, current = [], set(), adapter.FirstUnicastAddress
            while current:
                if current in unicast_seen or len(unicast_seen) >= MAX_UNICAST:
                    raise PhysicalSourceError('NATIVE_BUFFER_INVALID')
                unicast_seen.add(current)
                entry = structure(current, _Unicast, _Unicast.OnLinkPrefixLength.offset + 1)
                address_ptr, address_len = entry.Address.Sockaddr, entry.Address.Length
                checked(address_ptr, address_len)
                if address_len < 16:
                    raise PhysicalSourceError('NATIVE_BUFFER_INVALID')
                address = C.string_at(address_ptr, 16)
                # AF_INET was requested. An unexpected family is rejected, never used.
                if int.from_bytes(address[:2], 'little') != AF_INET:
                    raise PhysicalSourceError('NATIVE_BUFFER_INVALID')
                addresses.append(_Address(str(ipaddress.IPv4Address(address[4:8])),
                    entry.PrefixOrigin, entry.DadState, entry.ValidLifetime,
                    entry.PreferredLifetime, entry.OnLinkPrefixLength))
                current = entry.Next
            adapters.append(_Snapshot(interface_index, name(adapter.AdapterName),
                adapter.IfType, adapter.OperStatus, adapter.Flags, tuple(addresses)))
        pointer = adapter.Next
    return tuple(adapters)


def _read_adapters(api=None, interface_index=INTERFACE):
    if api is None:
        if os.name != 'nt':
            raise PhysicalSourceError('WINDOWS_REQUIRED')
        try:
            # LOAD_LIBRARY_SEARCH_SYSTEM32: no DLL search through the current directory.
            library = C.WinDLL('iphlpapi.dll', winmode=0x00000800)
            api = library.GetAdaptersAddresses
            api.argtypes = [U32, U32, C.c_void_p, C.c_void_p, C.POINTER(U32)]
            api.restype = U32
        except (OSError, AttributeError):
            raise PhysicalSourceError('NATIVE_API_FAILED') from None
    capacity = 15 * 1024
    for _ in range(3):
        buffer, size = C.create_string_buffer(capacity), U32(capacity)
        result = api(AF_INET, GAA_FLAGS, None, buffer, C.byref(size))
        if result == 0:
            return _parse_buffer(buffer, size.value, interface_index)
        if result != ERROR_BUFFER_OVERFLOW:
            raise PhysicalSourceError('NATIVE_API_FAILED')
        if not capacity < size.value <= MAX_BUFFER:
            raise PhysicalSourceError('NATIVE_BUFFER_INVALID')
        capacity = size.value
    raise PhysicalSourceError('NATIVE_API_FAILED')


class WindowsPhysicalSource:
    """Callable -> (10, current IPv4). One hardware pin; no address caching.

    Each upstream connect must call this immediately before binding its socket.
    A GUID/type/index change fails closed until an explicit new instance is created.
    Private injectable providers are for offline tests, not a runtime fallback.
    """
    def __init__(self, *, interface_index=INTERFACE, _pin_provider=None, _snapshot_reader=None):
        if type(interface_index) is not int or not 1 <= interface_index <= 2147483647:
            raise PhysicalSourceError('IDENTITY_INVALID')
        self._interface_index = interface_index
        self._pin_provider = _pin_provider or (lambda: _hardware_pin(interface_index))
        self._snapshot_reader = _snapshot_reader or (lambda: _read_adapters(interface_index=interface_index))
        self._lock = threading.Lock()
        self._pinned_guid = None
        self._pinned_type = None

    def __call__(self):
        try:
            # Native snapshots are fast; serializing prevents first-pin races.
            with self._lock:
                if self._pinned_guid is None:
                    self._pinned_guid = _guid(self._pin_provider())
                matches = [a for a in self._snapshot_reader() if a.index == self._interface_index]
                if not matches:
                    raise PhysicalSourceError('ADAPTER_MISSING')
                if len(matches) != 1:
                    raise PhysicalSourceError('ADAPTER_AMBIGUOUS')
                adapter = matches[0]
                if _guid(adapter.guid) != self._pinned_guid:
                    raise PhysicalSourceError('ADAPTER_CHANGED')
                if adapter.if_type not in (6, 71):
                    raise PhysicalSourceError('ADAPTER_NOT_PHYSICAL')
                if self._pinned_type is not None and adapter.if_type != self._pinned_type:
                    raise PhysicalSourceError('ADAPTER_CHANGED')
                if adapter.oper_status != 1:
                    raise PhysicalSourceError('ADAPTER_DOWN')
                if not adapter.flags & 0x04:
                    raise PhysicalSourceError('DHCP_DISABLED')
                if not adapter.flags & 0x80:
                    raise PhysicalSourceError('IPV4_DISABLED')
                # Pin type before address selection, including transient address loss.
                self._pinned_type = adapter.if_type
                candidates = []
                for address in adapter.addresses:
                    if (address.prefix_origin != 3 or address.dad_state != 4
                            or address.valid_lifetime == 0 or address.preferred_lifetime == 0
                            or not 0 <= address.prefix_length <= 32):
                        continue
                    ip = ipaddress.IPv4Address(address.address)
                    if ip in NETWORK and ip not in (NETWORK.network_address, NETWORK.broadcast_address):
                        candidates.append(str(ip))
                if not candidates:
                    raise PhysicalSourceError('ADDRESS_UNAVAILABLE')
                if len(candidates) != 1:
                    raise PhysicalSourceError('ADDRESS_AMBIGUOUS')
                return self._interface_index, candidates[0]
        except PhysicalSourceError:
            raise
        except Exception:
            raise PhysicalSourceError('DISCOVERY_FAILED') from None
