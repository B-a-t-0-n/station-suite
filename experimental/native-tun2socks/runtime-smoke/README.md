# Disposable native runtime smoke, no VPN

This DEX jar tests the pinned hev 2.20.0 JNI contract in a **separate disposable
`app_process` under an app UID**. It does not create an APK, VpnService, real TUN,
network interface, route, DNS setting, firewall rule or Wi-Fi connection.
The builder never invokes ADB or executes the jar. No native binary is bundled.
The isolated runtime checks below passed on Station Max Android9 build
`PPR1.180610.011.20221212`; real-TUN and network enforcement are separate tests.

## Station result, 2026-11-16

Both explicit modes passed in disposable processes under UID10005:

| Mode | Result | Runtime reported by the smoke |
| --- | --- | ---: |
| `--load-only` | Hash-pinned library load, JNI registration, initial stopped state and stats shape | 60 ms |
| `--packet-pair` | Validated synthetic echo reply and TCP SYNACK, worker running after replies, successful stop/join and stopped state | 63 ms |

Wi-Fi/proxy/always-on/lockdown, both policy-rule families and the working core
process PIDs matched their baseline. Temporary native library, DEX, generated
config and compilation cache were removed after the checks. No VPN, real TUN,
IP connection or Station service restart was needed.

[Sanitized results](results/device-20221212.json) record the native and DEX
SHA256 values without private addresses, PIDs, routes, filesystem paths or
keys. The native file was compiled for the owner's isolated local test and
was not published. The public binary distribution gate remains **false**;
the full notice/licensing audit is not complete.

## Exact JNI contract and lifecycle

Pinned upstream commit: `75dbc1e9ace6c70d3d1b94df7c283efe1de7effc`.
[hev-jni.c](https://github.com/heiher/hev-socks5-tunnel/blob/75dbc1e9ace6c70d3d1b94df7c283efe1de7effc/src/hev-jni.c)
registers against **`hev.htproxy.TProxyService`** in `JNI_OnLoad`:

| Static Java method | JNI descriptor | Meaning |
| --- | --- | --- |
| `TProxyStartService(String,int)` | `(Ljava/lang/String;I)Z` | Copies config path and supplied fd number; creates one native pthread |
| `TProxyStopService()` | `()Z` | Signals stop if running, then synchronously `pthread_join`s the native worker |
| `TProxyIsRunning()` | `()Z` | Atomic flag set before `pthread_create`, cleared after worker returns |
| `TProxyGetStats()` | `()[J` | `[txPackets,txBytes,rxPackets,rxBytes]`, not an initialization acknowledgement |

`start=true` proves thread creation, **not configuration parsing, initialization
or packet readiness**. The upstream worker discards `hev_socks5_tunnel_main`'s
return code. A failed asynchronous init can therefore produce start=true and
then running=false. This smoke requires a correctly checksummed synthetic
reply before declaring packet initialization successful.

The adapter retains exact upstream class/method names, but replaces automatic
`loadLibrary` with explicit canonical-path/SHA256 checked `System.load`.
It performs one load and at most one native start per process. Use only a
private app-owned library path, not a shared writable directory: file hashing
is not a defense against replacement by another process sharing the same UID.

## Modes

Entry point: `local.station.hevsmoke.NativeRuntimeSmoke`.
These are Java main arguments, not a deployment script:

```text
--load-only ABSOLUTE_LIBRARY_PATH EXPECTED_SHA256
--packet-pair ABSOLUTE_LIBRARY_PATH EXPECTED_SHA256 ABSOLUTE_EXISTING_WORK_DIRECTORY
```

Both modes require UID >=10000. The first verifies load/JNI registration,
initial `running=false`, a four-zero stats array and idempotent stop before
any start. Only exception classes and controlled result fields are printed;
there are no account keys or device traffic payloads.

The packet-pair mode creates an **AF_UNIX SOCK_DGRAM socketpair**, with
nonblocking/close-on-exec flags on both endpoints. A supplied-fd native instance
reads one endpoint. Java writes fixed synthetic ICMP echo and TCP SYN packets
to the peer, then validates the complete echo reply and SYNACK, including IPv4,
tuple, lengths, checksums, ICMP marker and TCP acknowledgement. No kernel IP
network is involved and Java never completes the TCP handshake, so this smoke
does not initiate a SOCKS connection. It does **not** prove SOCKS delivery,
TLS behavior, native-client coverage, or fail-closed OTA protection.

This no-connect property is also checked against pinned lwIP's
`tcp_in.c:SYN_RCVD`: the accept callback that creates a SOCKS session is called
only after the final TCP ACK. The smoke deliberately sends no such ACK.

The fixed config disables TSO/USO/jumbo, scripts, daemon mode and map-DNS. It
limits sessions and timeouts, and names only numeric loopback SOCKS. Config is
created with an exclusive filename and mode0600 in the explicit working
directory; an existing file is never replaced. The file is deleted on orderly
completion. A hard process termination can leave it behind; inspect/remove
only that known lab file before a deliberate retry.

## Why a socketpair is compatible

[Supplied-fd tunnel initialization](https://github.com/heiher/hev-socks5-tunnel/blob/75dbc1e9ace6c70d3d1b94df7c283efe1de7effc/src/hev-socks5-tunnel.c#L505)
sets FIONBIO on the supplied descriptor and borrows it. It does not open
`/dev/net/tun`, configure interface addresses/state or run interface scripts.
`hev_tunnel_set_offload` probes TUNGETIFF, but an unrecognized fd **explicitly
falls back to plain packets**; failure of that probe is not an init error.
[Linux fd I/O](https://github.com/heiher/hev-socks5-tunnel/blob/75dbc1e9ace6c70d3d1b94df7c283efe1de7effc/src/hev-tunnel-linux.c)
then uses `read(mtu)` and `write`/`writev` for complete packets. Datagram
boundaries suit this interface; there must be exactly one reader per endpoint.

API28's public OsConstants does not expose `SOCK_NONBLOCK`/`SOCK_CLOEXEC` names.
Bionic's Android9 [sys/socket.h](https://android.googlesource.com/platform/bionic/+/refs/tags/android-9.0.0_r1/libc/include/sys/socket.h)
defines them as `O_NONBLOCK`/`O_CLOEXEC`; those public API28 constants are used
without hidden APIs or guessed numeric flags. Os.poll bounds reads/writes,
EAGAIN retries are explicit, and short datagram writes are failures.

## Descriptor ownership and stopping

Upstream supplied descriptors are **caller-owned** and are not duped or closed
by the library. The smoke owns a ParcelFileDescriptor duplicate, closes its
unused original, and retains the borrowed descriptor until native stop/join
returns. Closing/reusing it while a native read/poll can still run is unsafe.
For a future real-TUN pump, Java alone reads real TUN, native alone reads its
socketpair endpoint, and each reverse writer must preserve packet boundaries.

JNI stop can block in `pthread_join`, including error/race paths. The smoke has
a separate Java deadline thread that halts **only this disposable process**
after six seconds, and packet I/O has a shorter monotonic deadline. Process
death closes Unix fds automatically. This is a local smoke timeout, not the
independent shell/OS rollback required for a VPN experiment: a process frozen
by SIGSTOP or a VM-wide failure cannot execute its Java deadline. A package
`am force-stop` also does not automatically target an unrelated app_process.

For first load, the operator should retain a separately verified per-PID
watchdog on the Station or an equivalent out-of-band recovery. Verify PID
starttime/UID and the unique smoke command before any kill action to avoid
PID-reuse mistakes. Do not point a package-stop watchdog at Quasar to stop
this disposable child; that would interrupt working Station services.

The native stack changes RLIMIT_NOFILE and SIGPIPE disposition in its process.
Never run this smoke inside the working Agent, Voicebridge or Quasar VM; a
separate app_process does not change those other processes even under the same
UID. Stats are only observations and are not a synchronized completion signal.

## Offline build and checks

`build.py` accepts existing `--java-home`, optional `--javac-home` and
`--android-parts` (`platform/android.jar`, `build-tools/d8.jar`). It compiles
Java8/API28 into a jar containing only `classes.dex`. Output and build records
are ignored in `build/`/`dist/`.

```console
python -m unittest discover -s experimental/native-tun2socks/runtime-smoke/tests -p "test_*.py" -v
```

Two host cases passed: independently generated Python request/reply packets
match Java fixtures, and corrupt/truncated reply variants are rejected. The
complete Android-facing Java sources also compiled and D8 produced an API28
DEX jar. Host compilation is not Android native execution.

## Local runtime and public binary gates

For an owner's private lab test, compile the pinned source with the selected
API28/ARM64 toolchain, preserve the declared-notice inventory and provenance,
verify the ELF and exact SHA, and use disposable processes. The successful
local checks above followed that path. An incomplete public licensing audit
does not itself prohibit this private runtime test.

Public binary redistribution remains a separate gate. The parent
[notice audit](../NOTICE-AUDIT.md) records unresolved provenance/attribution
and toolchain notice questions. Do not publish the `.so` or bundle it in a
public APK/AAR until that gate is resolved; the workflow publishes reports
only. Neither private testing nor runtime success establishes redistribution
permission or complete notice coverage.

Any changed native source, toolchain or adapter needs renewed hash/provenance
checks and the two disposable smoke modes. Do not replace the pinned source
build with an arbitrary prebuilt AAR. A separately reviewed bounded `/32` VPN
experiment still needs its independent watchdog and packet policy; successful
JNI/socketpair tests alone do not prove native-client capture or OTA safety.
