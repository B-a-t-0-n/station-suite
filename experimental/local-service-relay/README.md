# Local service transport experiment

An isolated step towards removing the PC relay on Station Max / Android 9.
This is **not a VPN, firewall, autonomous Wi-Fi mode or OTA guard**. It does
not intercept native clients. It is a short-lived local SOCKS5 server for
testing delivery to services already running on the Station.

The server listens only on `127.0.0.1:17694`, for at most 20 seconds. It accepts
only SOCKS5 CONNECT to the explicitly configured private IPv4 and ports 80/443.
Domain-name destinations, other IPs/ports, BIND and UDP are refused. It never
resolves a destination or opens a WAN socket.

| Accepted first request | Fixed local destination |
| --- | --- |
| GET/HEAD connectivity checks, exact native Host and path | `127.0.0.1:17685` |
| TLS ClientHello with known native config SNI | `127.0.0.1:17683` |
| TLS ClientHello with `station-config.quasar.yandex.net` SNI | `127.0.0.1:17684` |

HTTP has no body or pipelining; `/api/display`, loopback Host, credentials,
CONNECT and absolute-form requests are rejected. TLS is opaque: bounded
ClientHello records are examined for exact SNI and then copied unchanged.
Unknown, missing, duplicate or malformed SNI closes the flow. The local TLS
server retains certificate verification and exact HTTP Host/path validation.
No TLS keys are included in this experiment. Counters contain no payloads.

An independent local deadline closes the listener and all client/backend
sockets. Connection count, reads and bytes are bounded. There is no receiver,
APK installation, autostart, background persistence or production integration.
Build output is a DEX jar; execution on a device is a separate explicit lab step.

## Offline build and host tests

`build.py` takes existing `--java-home`, optional `--javac-home`, and
`--android-parts` (API28 `platform/android.jar`, `build-tools/d8.jar`). It does
not download dependencies, run ADB or deploy anything. Java source targets 8.

```console
python -m unittest discover -s experimental/local-service-relay/tests -p "test_*.py" -v
```

The host tests and isolated loopback tests are not proof of native TUN
coverage, fail-closed networking, or complete OTA prevention. See
[Wi-Fi autonomy research](../../docs/research/wifi-autonomy.md) for those gates.

## Isolated Station result, 2026-11-16

On Android9 build `20221212`, the relay ran as a temporary standalone
`app_process` under UID10005. A PC test client reached it through an ADB
forward; all service backends were on the Station. No VPN or native client
interception was used. The client pinned the exact public leaf certificate
read from the existing local TLS listener before sending the test request.

- Connectivity returned HTTP204.
- Local config returned HTTP200 with `hasUpdate:false` and `hasCriticalUpdate:false`.
- The push listener completed TLS; push application messages were not tested.
- Other IP/port, unknown SNI and the internal display API were rejected.
- The 12-second local deadline closed every socket; the process exited.
- Seven connections: three routed, four denied, zero I/O failures,
  zero unfinished upload threads and zero active handlers at completion.
- Wi-Fi/proxy/always-on/lockdown, both policy-rule families and working core
  PIDs matched the baseline. Temporary jar, log and ADB forward were removed.

[Sanitized evidence](results/device-20221212.json) records the DEX hash and
scope. The first pass reached all endpoints but its harness mishandled the
expected nonzero exit of `test -d /proc/<exited-pid>`; a later complete pass
confirmed actual exit. An immediate retry failed to reach readiness; no
error log was retained from that attempt, so its cause is unproven. The relay
does not reuse an occupied listen port; retries should allow TCP cleanup.
These lab results do not promote the component into production or make
home Wi-Fi safe.
