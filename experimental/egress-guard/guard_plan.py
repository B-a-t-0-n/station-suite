"""Offline plan gate for Max 20221212. Never applies rules or certifies protection."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re


MAX_INPUT_BYTES = 32768
CAP_NET_ADMIN = 1 << 12
FAMILY_OBSERVATIONS = {"binary_present", "missing", "unknown"}
LOCAL_PORTS = {17682, 17683, 17684, 17685, 17686}


class InputError(ValueError):
    """Fixed diagnostic code; never includes input values, paths or secrets."""


def _object(value, keys, code):
    if type(value) is not dict or set(value) != set(keys):
        raise InputError(code)
    return value


def _int(value, low, high):
    return type(value) is int and low <= value <= high


def _enum(value, choices):
    return type(value) is str and value in choices


def validate_observations(value):
    _object(value, {"schemaVersion", "device", "principal", "firewall", "management"},
            "OBSERVATIONS_SCHEMA_INVALID")
    if type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1:
        raise InputError("OBSERVATIONS_VERSION_INVALID")
    device = _object(value["device"], {"api", "build"}, "DEVICE_SCHEMA_INVALID")
    if not _int(device["api"], 1, 100) or type(device["build"]) is not str or not re.fullmatch(
            r"[A-Za-z0-9._/-]{1,160}", device["build"]):
        raise InputError("DEVICE_VALUES_INVALID")
    principal = _object(value["principal"], {"uid", "capEffHex", "selinux"},
                        "PRINCIPAL_SCHEMA_INVALID")
    if not _int(principal["uid"], 0, 2147483647) or not _enum(
            principal["selinux"], {"Enforcing", "Permissive", "Disabled", "unknown"}):
        raise InputError("PRINCIPAL_VALUES_INVALID")
    capabilities = principal["capEffHex"]
    if capabilities is not None and (type(capabilities) is not str or not re.fullmatch(
            r"[0-9a-fA-F]{1,16}", capabilities)):
        raise InputError("CAPABILITIES_INVALID")
    firewall = _object(value["firewall"], {"ipv4", "ipv6"}, "FIREWALL_SCHEMA_INVALID")
    if any(not _enum(firewall[family], FAMILY_OBSERVATIONS) for family in ("ipv4", "ipv6")):
        raise InputError("FIREWALL_VALUES_INVALID")
    management = _object(value["management"], {"kind"}, "MANAGEMENT_SCHEMA_INVALID")
    if not _enum(management["kind"], {"wired_ip", "usb_adb", "uart", "unknown"}):
        raise InputError("MANAGEMENT_VALUES_INVALID")
    return value


def validate_policy(value):
    _object(value, {"schemaVersion", "blockedNativeUids", "apiBrokerUid",
                    "managementInterface", "wirelessInterface", "localTcpPorts"},
            "POLICY_SCHEMA_INVALID")
    if type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1:
        raise InputError("POLICY_VERSION_INVALID")
    uids = value["blockedNativeUids"]
    if (type(uids) is not list or not 2 <= len(uids) <= 16
            or any(not _int(uid, 0, 2147483647) for uid in uids)
            or len(set(uids)) != len(uids) or not {0, 10005}.issubset(uids)):
        raise InputError("POLICY_NATIVE_UIDS_INVALID")
    if not _int(value["apiBrokerUid"], 10000, 19999) or value["apiBrokerUid"] in uids:
        raise InputError("POLICY_BROKER_UID_INVALID")
    if value["managementInterface"] != "eth0" or value["wirelessInterface"] != "wlan0":
        raise InputError("POLICY_INTERFACES_INVALID")
    ports = value["localTcpPorts"]
    if (type(ports) is not list or not ports or len(ports) > len(LOCAL_PORTS)
            or any(not _int(port, 1, 65535) or port not in LOCAL_PORTS for port in ports)
            or len(set(ports)) != len(ports)):
        raise InputError("POLICY_LOCAL_PORTS_INVALID")
    return value


def assess(observations, policy):
    """Check declared facts. Input JSON, even privileged JSON, is never device proof."""
    validate_observations(observations)
    validate_policy(policy)
    blockers = []
    device, principal = observations["device"], observations["principal"]
    if device["api"] != 28 or device["build"] != "20221212":
        blockers.append("DEVICE_PROFILE_UNSUPPORTED")
    if principal["uid"] != 0:
        blockers.append("PRIVILEGED_PRINCIPAL_MISSING")
    capabilities = principal["capEffHex"]
    if capabilities is None:
        blockers.append("CAP_NET_ADMIN_UNKNOWN")
    elif not int(capabilities, 16) & CAP_NET_ADMIN:
        blockers.append("CAP_NET_ADMIN_MISSING")
    if principal["selinux"] != "Enforcing":
        blockers.append("SELINUX_ENFORCING_BASELINE_MISSING")
    for family in ("ipv4", "ipv6"):
        availability = observations["firewall"][family]
        if availability != "binary_present":
            blockers.append(f"{family.upper()}_FIREWALL_BINARY_{availability.upper()}")
    if observations["management"]["kind"] == "unknown":
        blockers.append("MANAGEMENT_CHANNEL_UNKNOWN")
    observed_blockers = list(blockers)
    # These cannot be satisfied by a boolean, enum or checksum in a user file.
    # This module has no hardware verifier or apply mode, so none is certified.
    required_verification = [
        "INDEPENDENT_RECOVERY_UNVERIFIED",
        "SELINUX_NETFILTER_AUTHORIZATION_UNVERIFIED",
        "KERNEL_IPV4_AND_IPV6_FILTER_SUPPORT_UNVERIFIED",
        "EARLY_BOOT_BARRIER_UNVERIFIED",
        "LOCAL_CONFIG_VOICE_DNS_TIME_READINESS_UNVERIFIED",
        "IPV4_AND_IPV6_DENY_CANARIES_UNVERIFIED",
        "PHYSICAL_NETWORK_BIND_CANARY_UNVERIFIED",
        "EXISTING_CONNECTION_DENY_CANARY_UNVERIFIED",
        "CRASH_AND_NETD_RECONNECT_RETENTION_UNVERIFIED",
        "COLD_BOOT_ENFORCEMENT_UNVERIFIED",
    ]
    return {
        "schemaVersion": 1,
        "scope": "offline_declared_plan_only",
        "planValid": True,
        "eligibility": "blocked_by_observed_facts" if observed_blockers else "needs_isolated_verification",
        "runtimeProtection": "unverified",
        "wifiMayBeEnabled": False,
        "deviceMutationsPerformed": False,
        "observedBlockers": observed_blockers,
        "requiredVerification": required_verification,
        "blockers": blockers + required_verification,
        "plannedStages": [
            {"name": "SEALED", "requires": [
                "independent recovery proven in isolated network",
                "both IP families closed before physical network can provide WAN",
                "policy present before native updater has an external route"]},
            {"name": "LOCAL_READY", "requires": [
                "local config/voice/music/DNS/time services measured ready",
                "package identities and current runtime UIDs independently verified",
                "native UIDs retain no direct external access"]},
            {"name": "GUARDED", "requires": [
                "effective IPv4 and IPv6 deny rules verified under enforcing SELinux",
                "deny checked before any general ESTABLISHED acceptance",
                "direct IP, cached DNS, physical bind and existing connections denied",
                "owned policy retained across helper crash, netd and network reconnect"]},
            {"name": "WIFI_ALLOWED", "requires": [
                "early bootstrap and fresh per-boot readiness barrier verified",
                "cold boot and failure matrix measured in isolated canary network",
                "only separately controlled API/TV principals receive planned WAN access"]},
        ],
    }


def _no_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise InputError("JSON_DUPLICATE_KEY")
        result[key] = value
    return result


def read_json(path):
    try:
        source = Path(path)
        if not source.is_file():
            raise InputError("INPUT_FILE_REQUIRED")
        with source.open("rb") as stream:
            raw = stream.read(MAX_INPUT_BYTES + 1)
        if len(raw) > MAX_INPUT_BYTES:
            raise InputError("INPUT_TOO_LARGE")
        return json.loads(raw.decode("utf-8-sig"), object_pairs_hook=_no_duplicate_keys,
                          parse_constant=lambda _: (_ for _ in ()).throw(InputError("JSON_NONFINITE_NUMBER")))
    except InputError:
        raise
    except (OSError, UnicodeError, ValueError, RecursionError):
        raise InputError("INPUT_UNREADABLE_OR_INVALID_JSON") from None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--observations", required=True)
    parser.add_argument("--policy", required=True)
    args = parser.parse_args(argv)
    try:
        result = assess(read_json(args.observations), read_json(args.policy))
    except InputError as error:
        print(json.dumps({"schemaVersion": 1, "planValid": False, "code": str(error),
                          "runtimeProtection": "unverified", "wifiMayBeEnabled": False}))
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2))
    # No successful assessment is permission to enable a real network.
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
