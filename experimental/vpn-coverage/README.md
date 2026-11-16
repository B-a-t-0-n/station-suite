# Bounded VPN coverage probe

Offline experiment for Station Max Android9. **Not a production Wi-Fi or OTA guard.**
No installation or device test is performed by the build. Do not add this APK to
normal Station startup or use it on a network with unrestricted WAN.

The package is `local.station.vpnprobe`, version0.1. It has three entry points:

- `ProbeActivity`, protected by `android.permission.DUMP`: a default/PREPARE
  request only obtains Android VPN consent and exits without creating a TUN.
- An explicit `local.station.vpnprobe.PROBE` Activity request requires
  `config_ip` (canonical RFC1918 IPv4 host) and `nonce` (32hex). It can establish
  a TUN only after independent shell-watchdog authorization.
- `MarkerService`, also DUMP-protected: a foreground marker for testing that the
  independent watchdog actually kills the APK PID. It has no sockets or VPN.

There are no boot receivers or always-on support. The service is not sticky.
There are no DNS settings, default routes, forwarding, TLS interception or
`allowBypass` calls. One IPv4 `/32` destination is routed to a drop-only TUN for
at most10seconds. Wi-Fi must be off, the active physical network must be
Ethernet, and no other physical/VPN networks may be present. The external lab
must independently keep direct WAN closed: the APK cannot verify Windows NAT.

`com.android.shell` (checked UID2000), `local.station.agent`,
`local.station.voicebridge` and this package must all exist and are excluded
before establish. Unexpected/missing packages abort. The probe explicitly
allows the IPv6 family **without capturing it**; otherwise Android9 can add a
global unreachable IPv6 route even for a narrow IPv4 test. This is not an IPv6
security policy for future Wi-Fi.

The independent watchdog runs under shell UID2000, outside the Android app
process. Its contract is an authenticated loopback TCP connection to
`127.0.0.1:17692`. A fresh random challenge is authenticated by
`WatchdogProtocol.armRequest(secretNonce, challenge)`; the secret nonce is
never sent over TCP or written to logs. One bounded JSON response contains
`ready:true`, `challenge`, `uid:2000`, `remainingMs` and an HMAC `proof`
covering the challenge and remaining deadline. The app verifies this proof
using the one-time secret delivered only to the protected Activity and shell
watchdog. The JSON UID is informational, not TCP peer-credential evidence.

The app requires at least12seconds remaining after conservatively subtracting
the exchange time, and accepts no lifetime above15seconds. The watchdog's fixed
15second deadline cannot be renewed. Any failed/expired handshake aborts
before VPN creation. The app's own10second deadline starts before establish.
The earlier abstract Unix-socket design is not used: the saved enforcing
SELinux policy blocks ordinary-app to shell-domain `connectto`. No SELinux
relaxation is part of this experiment.

Metadata is limited to512 IPv4/transport header records: source/destination
IP, protocol, ports and count. Non-initial fragments have unknown ports `-1`.
No packet bodies, audio, DNS names, credentials or UID attribution are logged.
The last run overwrites one app-private `metadata-last.jsonl`; status and the
same metadata also use logcat tag `StationVpnProbe`.

The kernel may emit IPv6 interface-up control inside a new TUN. The reader
counts and discards only exact-length IPv6 packets with unspecified/link-local
source, `ff02::/16` destination, ICMPv6 code0 and one of types131/132/133/135/136/143.
It checks the minimum control header and hop limit (MLD1, neighbor/router
solicitation255); a single initial Hop-by-Hop header is permitted only for MLD
and must lead directly to ICMPv6. This does not add IPv6 routes or forwarding.
All other unexpected frames abort the probe with a bounded header-only
diagnostic (version, actual/declared length, next header and ICMP type). The
512packet processing cap includes ignored controls.

Build with the existing external signer and local Android parts using
`build.py --java-home ... --javac-home ... --android-parts ... --signing-dir ...`.
The script compiles Java8/API28, verifies signatures/alignment and checks the
exact four-permission allowlist. Signing material must stay outside this
checkout. Outputs in `build/` and `dist/` are ignored; `BUILD-RECORD.json`
records source hashes and explicitly marks device installation/testing false.

The required sequence is offline tests/review, then a real Marker PID kill
test without VPN, prepare-only OS consent, and only then an independently
authorized bounded canary in the isolated wired lab. TCP/UDP capture proves
only an observed path. Idle/bound/reused sockets and native UID0 bypass need
separate attribution. A successful canary or rollback does not make OTA guard
ready, prove fail-closed, or authorize enabling home Wi-Fi.

`ManagedCanary` is an explicit app_process control client for a non-excluded
test UID. It requires app UID >=10000, a numeric RFC1918 destination and source
port46080–46099; it issues only HEAD `/generate_204` to port80 and prints the
HTTP status line. It is not an Android component or an upstream proxy.

The isolated-device sequence was exercised on build20221212 on16November2026.
See [observations and remaining gates](../../docs/research/wifi-autonomy.md).
Build records describe only build actions; the separate research report records
installation, device observations and cleanup. Do not infer a production-ready
guard from build or host-test success.
