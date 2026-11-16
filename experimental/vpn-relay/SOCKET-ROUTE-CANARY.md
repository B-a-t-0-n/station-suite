# One-socket route canary

`SocketRouteCanary` is a disposable `app_process` entry point for the isolated
Station laboratory. It is not started by the APK, a boot receiver or a service.
The builder only compiles it. It creates one nonblocking IPv4 TCP socket and
sends at most one fixed `HEAD /generate_204` to `192.168.77.1:80` with
`Host: scbh.yandex.net`. There is no DNS, arbitrary host/port, account request,
system setter, firewall mutation or permission escalation.

Arguments, not an installation/execution script:

```text
<default|network|source|device> <46100..46115> <observed-network-handle|->
```

UID must be exactly `10005` (Quasar's captured UID on this Station) or
`2000` (the intentionally excluded shell control). Quasar is a **system and
privileged package**; its non-root UID must not be treated as an ordinary
unprivileged app UID. The separate client has a
three-second monotonic I/O deadline and a four-second process hard deadline.
The latter halts only this disposable process, not Quasar or the relay package.
The socket is closed after each invocation; no library or persistent config is
loaded. Network effects are limited to this one socket and its one HTTP request.

| Mode | Operation before connecting |
| --- | --- |
| `default` | Bind only its unique source port to IPv4 ANY |
| `network` | Public `Network.fromNetworkHandle(handle).bindSocket(fd)`, then bind its source port |
| `source` | Public `Os.bind(fd, 192.168.77.100, sourcePort)` |
| `device` | One exact `Os.setsockoptIfreq(fd, SOL_SOCKET, SO_BINDTODEVICE, "eth0")` lookup/invocation, then bind its source port |

Only `network` accepts a positive handle. The harness must obtain that handle
from `Network.getNetworkHandle()` on the verified physical Ethernet network;
it must not substitute a raw netId, construct the handle from constants or pick
the active VPN network. Other modes require the literal `-` argument. The SDK
validates the handle and the client checks the round trip before socket binding.

`Network.fromNetworkHandle` is public in API28; `Network.bindSocket(fd)` is
public in API23. `Os.setsockoptIfreq` is hidden in Android9. Its exact method
signature is verified in the pinned AOSP9 libcore source. The client performs
one reflective call with no `setAccessible`, alternate overload or fallback.
A hidden-API, SELinux, capability or ordinary permission denial is recorded as
the observed result. No attempt is made to relax that denial.

The one-line result starts with `SOCKET_ROUTE_RESULT=` and includes `uid`,
`mode`, `sourcePort`, binding/connect flags, `http204`, stage, exception class,
errno, chosen local IPv4 and elapsed milliseconds. It does not print server
headers, payloads, exception messages or credentials. The four-second hard
deadline emits a separate `hardTimeout` result before process termination.

## Evidence needed and interpretation

Give each before/during invocation a fresh source port. Observe the relay's
bounded packet metadata and, independently, receipt at the isolated physical
server. An HTTP204 proves a reply, but cannot on its own tell whether the
request used TUN or Ethernet. A successful binding API likewise is not proof
of bypass. A complete TCP tuple captured through TUN can positively establish
capture. Absence from an incomplete/evicted journal is not bypass evidence.

For a captured socket **without a protected mark or applicable system
exception**, the AOSP9 secure-VPN rule at priority12000 takes precedence over
explicit-network rule13000 and ordinary output-interface rule14000. A binding
API can then succeed while packets still traverse the VPN. This conditional
rule does not establish coverage of Quasar's privileged UID. In particular,
AOSP9 `PermissionMonitor` assigns netd `SYSTEM` network permission to
preinstalled system packages, including those with numeric UIDs above 10000.
`SELECT_NETWORK` then sets `protectedFromVpn` according to `canProtect(uid)`,
which accepts that system network permission or an explicitly protectable UID.
This source path together with Quasar's observed system-package flags explains
why its non-root UID is eligible for the exception. The protected socket mark
is inferred from that platform path and the observed routing result, not read
directly from the device socket. Source-address binding alone does not prove
an interface or change netd policy.

The distinct root output-interface rule10500 precedes secure VPN and applies
to UID0. This client refuses UID0, so its result does not test or disprove the
root exception. UID2000 is an excluded lab control; its physical path is
expected and says nothing about a captured UID. The client does not explicitly
invoke `protectFromVpn` or write a fwmark; `Network.bindSocket` can itself cause
netd to select a protected mark for an eligible UID. IPv6 and updatesd attribution
are outside this test.

## Device result: physical-network binding bypassed the narrow VPN

On Station Max Android9 firmware `20221212`, the bounded ten-second pass on
2026-11-16 found a bypass for **Quasar's system/privileged UID**. This is a
negative result for using this VPN alone as a complete network guard.
It is not a finding about an arbitrary third-party app UID.

Tested APK SHA256:
`568b12c7bbdc982eb4236cd3142727539322234a856310a9e07dd7ffc83e70d8`.
Native SHA256:
`061d1452c1392e71cd6f4df91b504290de6ef646855f32035d398e8e46c56c83`.
The client source and its tested build are unchanged by this documentation.

| Mode during the active VPN window | Observed result |
| --- | --- |
| `default` | HTTP204; unique request/reply tuple recorded through TUN |
| `network` | Physical-network binding succeeded and HTTP204 returned; the socket chose the physical source address and its unique tuple was absent from the complete bounded TUN journal |
| `source` | Source-address binding succeeded and HTTP204 returned; unique request/reply tuple still recorded through TUN |
| `device` | `SO_BINDTODEVICE` denied with `EPERM`; no connect or HTTP request attempted |

The `network` invocation was wholly inside the conservative active-VPN
observation window, from 2.700 to 3.756 seconds after launch. The `default`
and `source` positive capture controls bracketed it. Journal metadata was
retained independently of logcat eviction: 162 header entries reconcile with
94 forwarded, 67 injected and one dropped IPv4 packet; seven separately
counted IPv6 interface-control packets account for all 169 packet records.
Thus the missing network-bound tuple is assessed with a successful HTTP reply,
chosen physical source and complete capture controls, rather than absence
from a truncated log alone.

The relay reached its own ten-second deadline, native stop/join completed,
and the independent watchdog was observed to terminate the disposable lab
process by 16.391 seconds after launch. Before/after network settings, policy
rules and working management/audio process identities matched exactly. Wi-Fi
remained disabled; all requests targeted the isolated laboratory server.

Read-only package evidence reports Quasar as `SYSTEM` and `PRIVILEGED`, with
`INTERNET` and `CHANGE_NETWORK_STATE` granted. In upstream Android9 the
preinstalled-system flag is sufficient for `PermissionMonitor` to assign
netd `SYSTEM` network permission; a low numeric system UID is not required.
Combined with `SELECT_NETWORK` and `canProtect`, this provides a concrete
source-level explanation for the bypass without root. The actual socket
fwmark was not read, so that marking mechanism remains an inference from
upstream source, package evidence and the routing result. The experiment does not show whether updatesd
actually chooses this API, attribute an OTA URL, test the distinct root OIF
exception, or establish behavior with lockdown, IPv6, reboot or home Wi-Fi.
The positive bypass is enough to leave safe autonomous Wi-Fi **unproven**.

Host validation tests cover fixed UID, mode, port and handle admission,
including malformed/overflow arguments. Full Android-facing compilation is
checked against API28. Neither check substitutes for the device socket test.

Sources: [Android Network reference](https://developer.android.com/reference/android/net/Network),
[VPN builder bypass contract](https://developer.android.com/reference/android/net/VpnService.Builder#allowBypass()),
[AOSP9 Network.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-9.0.0_r1/core/java/android/net/Network.java),
[AOSP9 Os.java signature](https://android.googlesource.com/platform/libcore/+/refs/tags/android-9.0.0_r1/luni/src/main/java/android/system/Os.java),
[AOSP9 routing rules](https://android.googlesource.com/platform/system/netd/+/refs/tags/android-9.0.0_r1/server/RouteController.cpp),
[AOSP9 SELECT_NETWORK handling](https://android.googlesource.com/platform/system/netd/+/refs/tags/android-9.0.0_r1/server/FwmarkServer.cpp),
[AOSP9 canProtect gate](https://android.googlesource.com/platform/system/netd/+/refs/tags/android-9.0.0_r1/server/NetworkController.cpp),
[AOSP9 PermissionMonitor](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-9.0.0_r1/services/core/java/com/android/server/connectivity/PermissionMonitor.java).
