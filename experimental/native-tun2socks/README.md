# API28 native TCP-stack build candidate

This scaffold builds **only** the upstream hev-socks5-tunnel shared library from
reviewed source. It does not create an APK, run a VPN, sign, install, invoke ADB,
modify networking, or publish a release. No TCP implementation is added here.

The source lock pins upstream 2.20.0 and every recursive submodule. Upstream uses
Android API29; this experiment explicitly compiles and links against API28 for
Station Max Android9/arm64. A passing build is not proof that loading/start/stop
works on the Station, or that native traffic cannot bypass an Android VPN.

Supply an existing clean recursive checkout (LF source files, `core.autocrlf=false`) and Android NDK r29
`29.0.14206865`. The builder itself has no network downloader. Verify first:

```text
python experimental/native-tun2socks/build.py --source PATH_TO_HEV --ndk-home PATH_TO_NDK --verify-source-only
python experimental/native-tun2socks/build.py --source PATH_TO_HEV --ndk-home PATH_TO_NDK
```

`source-lock.json` contains the exact commit/submodule hashes and known license
input hashes. Source acquisition is a separate action. A dedicated manual
GitHub Actions workflow can fetch those public sources and the pinned NDK on a
build runner; it has no deployment step or device credentials.

Build output is ignored in `build/` and `dist/`. The local build creates the `.so`,
SHA256, ELF imports/needed libraries, full declared-notice inputs, source/header
inventory and a local source snapshot ZIP. CI uploads only provenance JSON,
ELF report, declared-notice inputs and source/header inventory, using four
explicit filenames. It does not upload the `.so`, source ZIP or any wildcard directory
while `completeLicenseAudit:false`. The
build rejects an unexpected source revision, dirty/missing submodules, wrong
NDK version, wrong API compiler macro, wrong ELF architecture, unexpected native
libraries and explicitly newer Android symbol versions. Linking uses the API28
sysroot; an exhaustive runtime symbol audit and loading test remain separate gates.

The produced JNI contract is **upstream** `hev.htproxy.TProxyService`. It must not
be imported blindly into an existing app: upstream registers those exact class
and method names. A future adapter needs explicit readiness and fd ownership;
upstream start success means a worker was created, not that relay init succeeded.
Caller-supplied TUN fds remain caller-owned in this pinned upstream revision.

The root licenses of core/tunnel/task/yaml declare MIT; lwIP and several files
have distinct inherited or file-specific notices. The Android make graph contains
184 recursive C/assembly translation units: tunnel+core31, yaml8, lwIP95, task50.
The conservative header closure has 458 files and 302 distinct notice comments.
`notice_bundle.py` preserves all those full comments and five inherited licenses,
rather than treating lwIP's `init.h` notice as a substitute for its other authors.
Every source/header has a hash and inherited-license mapping; the local source
ZIP additionally retains its exact full bytes and the build makefiles.

Declared-notice coverage is complete for this pinned source graph, while
`completeLicenseAudit:false` remains a **public binary publication** gate.
[The audit](NOTICE-AUDIT.md) records concrete provenance/attribution and NDK
static-runtime questions. It does not prevent building or testing the library
locally on the owner's Station. It does not establish runtime compatibility or
make home Wi-Fi safe. No `.so` is published by this workflow.

The source-lock verification has passed on a fresh recursive checkout. Local
ELF refusal tests and Python syntax/help checks pass. The pinned Windows NDK
was subsequently acquired and verified; local API28/ARM64 compilation passed.

## Verified CI source build, 2026-11-16

The [manual Linux build](https://github.com/B-a-t-0-n/station-suite/actions/runs/43866423108)
passed at commit `4d232da`. Its library is ELF64/AArch64; Android ELF ident
records API28, and DT_NEEDED contains only `libc.so`, `libdl.so`, `libm.so`.
The output was 355480 bytes, SHA256
`895568b1e4f495f5b03f5038866f3b0b8fd52e04ac3d88696cebf23c1c458359`.
The build report's builder hash matches the committed script.
[Sanitized provenance](results/api28-ci-20261116.json) preserves the source lock.

The initial run stopped before native compilation because the API macro check
assumed a numeric `-dM` definition. The builder now checks the preprocessor's
expanded `__ANDROID_API__` value against exactly 28; all refusal checks remain.

That CI run published three reports and no `.so`; the current workflow retains
four reports including the expanded notice inventory. The build alone proves
compilation/linking for the selected target, not Android9 runtime behavior or
native OTA coverage.

## Bounded Station runtime, 2026-11-16

The local Windows build's SHA256 is
`061d1452c1392e71cd6f4df91b504290de6ef646855f32035d398e8e46c56c83`.
Its exact JNI methods loaded on Android9 in a disposable app process. The
[no-VPN smoke](runtime-smoke/README.md) validated a synthetic echo reply and
TCP SYNACK, then joined/stopped the native worker. This tested library loading
and supplied-fd packet I/O without creating a network interface.

A separate [ten-second `/32` relay experiment](../vpn-relay/README.md) then
returned a managed HTTP204 through TUN → hev/lwIP → loopback SOCKS → local
Station services. Several captured TCP tuples matched established UID0 sockets.
Native stop/join and independent package termination completed, and the working
Station settings/processes were preserved. This establishes a limited working
transport, not complete native-client coverage or protection against OTA bypass.
The lab package and temporary files were removed; no native binary is published.

Sources: [hev 2.20.0](https://github.com/heiher/hev-socks5-tunnel/tree/75dbc1e9ace6c70d3d1b94df7c283efe1de7effc),
[Android API29 change](https://github.com/heiher/hev-socks5-tunnel/commit/d9d67110bd77f469e293ad29fc56b77071714549),
[NDK API rules](https://developer.android.com/ndk/guides/sdk-versions).
