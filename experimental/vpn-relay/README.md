# Bounded native-to-local relay lab

Separate Android9/API28 diagnostic APK for Station Max `20221212`. It joins
the earlier packet-capture probe, a locally built hev/lwIP TCP stack and the
[loopback service relay](../local-service-relay/README.md). It is not included
in Agent, Voicebridge, phone apps or production startup.

The offline `build.py` uses existing Android tools, a verified local API28/ARM64
native build and an external signing directory. It performs no downloads, ADB,
installation or device execution. The APK and `.so` remain private lab outputs:
the [declared notice inventory](../native-tun2socks/NOTICE-AUDIT.md) is bundled,
but public binary provenance/notice questions remain open.

**Follow-up guard result:** an included Quasar system UID reached the isolated
physical endpoint outside this active VPN by selecting the Ethernet `Network`.
Default and source-bound requests were captured. The working relay therefore
must not be treated as complete network enforcement.
[Socket test and scope](SOCKET-ROUTE-CANARY.md) ·
[Sanitized evidence](results/socket-route-device-20221212.json).

Build the parent native experiment first. Then use Windows Java/Android tools
and your existing external signer (the repository's builders create no signer):

```console
python experimental/vpn-relay/build.py --java-home PATH_TO_JAVA21 --javac-home PATH_TO_JAVA8 --android-parts PATH_TO_ANDROID_PARTS --signing-dir PATH_OUTSIDE_REPO --keystore YOUR_SIGNER.p12 --alias YOUR_ALIAS
```

The Android-parts directory needs `platform/android.jar` for API28 and the same
`build-tools` layout described in [BUILD.md](../../docs/BUILD.md). The external
signing directory contains the chosen keystore and `password.txt`. The local
`dist/BUILD-RECORD.json` records source/native hashes; it is not a deployment
authorization. Output stays in ignored `build/` and `dist/` directories.

## Exact experiment scope

The disposable package is `local.station.vpnprobe`, version `0.2-relay-lab`.
It shares the old capture probe's package identity; it is not an independent
second always-on VPN. An explicit shell-authorized activity, Android VPN
consent and a fresh authenticated watchdog handshake are required.

- Physical connection must be the isolated Ethernet lab: Station
  `192.168.77.100`, config target `192.168.77.1`. Wi-Fi must be disabled before
  establishment and is checked throughout the packet pump.
- TUN address is `10.111.77.2/32`; the only captured route is config IP `/32`.
  Capture lasts at most 10 seconds. Existing management UID exclusions retain
  shell/ADB, Agent, Voicebridge and the lab package outside this narrow TUN.
  These exclusions are a lab-access measure; they would not imply a bypass
  allowance in a future Android lockdown mode.
- Only fresh IPv4 TCP flows to the config IP on ports 80/443 are admitted to
  lwIP. Replies must match admitted reverse tuples. Existing keepalives without
  a fresh SYN, UDP and ICMP are dropped. Fragments, unexpected destinations,
  malformed packets and resource-budget violations stop the experiment.
- TUN IPv6 interface-control packets are classified and dropped separately.
  There is no IPv6 internet interception or forwarding.

The Java pump is the sole TUN consumer. A separate datagram socketpair gives
lwIP packet input/output. The native stack's SOCKS client connects only to the
loopback relay at `127.0.0.1:17694`:

| Validated stream | Fixed local service |
| --- | --- |
| Exact connectivity HTTP Host/path | `127.0.0.1:17685` |
| Known config TLS SNI | `127.0.0.1:17683` |
| `station-config.quasar.yandex.net` TLS SNI | `127.0.0.1:17684` |

The local relay forwards TLS bytes unchanged after bounded ClientHello/SNI
validation. Other IPs/ports, unknown SNI, internal `/api/display` access and
HTTP ambiguity are denied. It has no WAN backend or upstream fallback.

## Shutdown and independence

The service schedules its own monotonic 10-second deadline before `establish()`.
Closing the experiment closes TUN, local sockets and packet-pump descriptors,
then stops/joins the native worker before releasing its supplied fd. A failed
native stop leaves fd ownership with that disposable process until it exits.

Before establishment, the independent shell-UID `app_process` watchdog must
authenticate a one-use challenge/HMAC and report sufficient remaining lifetime.
It owns a fixed deadline and force-stops only `local.station.vpnprobe`; it does
not require a later PC, network or Agent heartbeat. Its armed limit is 15 seconds,
with a 30-second limit if no valid ARM arrives. The activity also exposes a stop
button. Process shutdown and network-state read-back are checked separately.

The experiment changes no Wi-Fi configuration, default route, DNS server,
global HTTP proxy, always-on/lockdown setting or boot receiver. It is
`START_NOT_STICKY`, opts out of always-on and has no autostart. It must not be
used as a production guard: after its deadline the temporary capture ends.

## Verified isolated device pass, 2026-11-16

The final 10-second pass on `20221212` succeeded. A controlled HTTP request
returned 204 **while the VPN was active**, and both request and reply packets
were observed through the relay. During that window the local relay also
handled config and push TLS connections from traffic using the captured path.

| Observation | Result |
| --- | --- |
| Packet records / bytes | 148 / 40726 |
| Forwarded to native stack / injected replies / dropped | 80 / 58 / 3 |
| TUN IPv6 controls / native-stack IPv6 controls dropped | 6 / 1 |
| Local relay streams | 9 accepted; HTTP 2, config TLS 4, push TLS 3 |
| Local relay failures / active handlers at completion | 0 / 0 |
| Own deadline | TUN closed and native worker stopped |
| Independent watchdog | Lab process killed by 16.302 seconds |
| Baseline after cleanup | Exact state comparison preserved |

The final watchdog time is the observed lab-process termination time from the
test harness, not the configured 15-second interval measured from ARM.

APK SHA256:
`e88cc204756814fe4d26d138f84ceb4038f45a05ea21a7f776839d32948e6041`.
Native library SHA256:
`061d1452c1392e71cd6f4df91b504290de6ef646855f32035d398e8e46c56c83`.

[Sanitized device evidence](results/device-20221212.json) retains the counters,
hashes and cleanup checks. The lab package, shell copy and temporary artifacts
were removed after the pass; Agent, the panel and local services remained healthy.

This pass exercises the real on-device TUN → native TCP → local SOCKS → local
service path. Packet/socket metadata can establish UID/tuple correlation;
without root-process fd access it does not identify every root-owned socket's
PID or prove a particular OTA URL was intercepted.

The bounded header-only JSONL journal survives logcat eviction and records
admission before packet writes, not independent delivery confirmation. The
managed HTTP response supplies that separate confirmation. Known native-stack
IPv6 interface-control packets are counted and discarded before TUN injection.
Java push idle timeout is 12 seconds, while native TCP idle timeout remains
3 seconds; this pass does not establish durable long-lived push connections.

## Host checks and limits

Java 21 CI runs packet-policy and packet-fixture checks without installing the
APK or native library. The reviewed host results include 2277 packet-policy
assertions, 300 socket-client admission assertions, 269 local-relay parser
assertions plus socket integration, 2 runtime
fixture tests and 6 native builder/notice tests. The runtime-smoke fixtures do
not stand in for loading/JNI validation on the Station.

```console
python -m unittest discover -s experimental/vpn-relay/tests -p "test_*.py" -v
python -m unittest discover -s experimental/native-tun2socks/runtime-smoke/tests -p "test_*.py" -v
```

Success of this narrow lab path does **not** prove fail-closed OTA protection,
coverage of every UID0/native service, resistance to protected/interface-bound
sockets, IPv6 protection, cold-boot safety or safe home Wi-Fi. There is no WAN
allowlist tunnel here. Wi-Fi remains off; the current isolated PC transport
continues to provide production networking. See the
[autonomy research](../../docs/research/wifi-autonomy.md) for the remaining gates.
