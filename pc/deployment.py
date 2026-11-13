"""Validate private portable deployment settings; import performs no actions."""
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import uuid

SUITE = Path(__file__).resolve().parents[1]


def load(path):
    path = Path(path).resolve()
    if SUITE == path or SUITE in path.parents: raise ValueError('DEPLOYMENT_MUST_BE_PRIVATE')
    value = json.loads(path.read_text(encoding='utf-8-sig'))
    if value.get('format') != 1: raise ValueError('DEPLOYMENT_FORMAT_INVALID')
    private = Path(value['privateDir']).resolve()
    if SUITE == private or SUITE in private.parents: raise ValueError('PRIVATE_DIR_INSIDE_CHECKOUT')
    for name in ('labInterfaceIndex', 'wanInterfaceIndex'):
        if type(value[name]) is not int or value[name] < 1: raise ValueError('INTERFACE_REQUIRED')
    if value['labInterfaceIndex'] == value['wanInterfaceIndex']: raise ValueError('INTERFACES_MUST_DIFFER')
    uuid.UUID(value['labInterfaceGuid'])
    network = ipaddress.IPv4Network(value['wanNetwork'])
    if network.prefixlen == 0: raise ValueError('WAN_SUBNET_REQUIRED')
    for name in ('labIp', 'voiceIp', 'stationIp'): ipaddress.IPv4Address(value[name])
    # Android/native endpoint fields are pinned to this /24 in the current native
    # provisioning. A different topology requires deliberate native config edits.
    if (value['labIp'], value['voiceIp'], value['stationIp']) != ('192.168.77.1', '192.168.77.2', '192.168.77.100'):
        raise ValueError('CURRENT_NATIVE_TOPOLOGY_REQUIRED')
    for name in ('baselineSha256', 'policySha256', 'tlsLeafSha256', 'dhcpSha256'):
        if re.fullmatch('[a-f0-9]{64}', value[name]) is None or value[name] == '0' * 64: raise ValueError('PIN_REQUIRED')
    for name in ('python', 'adb', 'dhcpExecutable', 'agentApk'):
        if not Path(value[name]).is_file(): raise ValueError('LOCAL_TOOL_MISSING')
    for relative, field in [('pc/config-policy.json', 'policySha256'), ('pc/native-baseline.json', 'baselineSha256')]:
        if hashlib.sha256((private / relative).read_bytes()).hexdigest() != value[field]: raise ValueError('PRIVATE_CONFIG_HASH_MISMATCH')
    if hashlib.sha256(Path(value['dhcpExecutable']).read_bytes()).hexdigest() != value['dhcpSha256']: raise ValueError('DHCP_HASH_MISMATCH')
    for relative in ('pc/fullchain.pem', 'pc/quasar-all.key'):
        if not (private / relative).is_file(): raise ValueError('PRIVATE_TLS_MISSING')
    return value


def environment(value):
    return dict(os.environ, STATION_PRIVATE_DIR=value['privateDir'], STATION_ADB=value['adb'],
        STATION_SERIAL=value['serial'], STATION_WAN_INTERFACE=str(value['wanInterfaceIndex']),
        STATION_WAN_NETWORK=value['wanNetwork'], STATION_LAB_IP=value['labIp'],
        STATION_VOICE_IP=value['voiceIp'], STATION_IP=value['stationIp'],
        STATION_BASELINE_SHA256=value['baselineSha256'], STATION_POLICY_SHA256=value['policySha256'],
        STATION_TLS_LEAF_SHA256=value['tlsLeafSha256'])
