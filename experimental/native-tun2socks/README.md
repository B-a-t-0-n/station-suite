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
SHA256, ELF imports/needed libraries, notices input and provenance record. CI
uploads only the provenance JSON, ELF report and checked notice inputs, using
three explicit filenames. It does not upload the `.so` or any wildcard directory
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

Core/tunnel/task/yaml use MIT notices; lwIP has BSD notices in its source headers.
The bundle contains checked notice inputs, with `completeLicenseAudit:false` until
all compiled-file notices are reviewed. Lifting the artifact gate requires an
inventory of every compiled source and linked static-library notice, its review,
and an explicit change to the CI publication allowlist. It is a build experiment, not a public
runtime release. Do not add the library to production startup or enable home
Wi-Fi because a build completed.

The source-lock verification has passed on a fresh recursive checkout. Local
ELF refusal tests and Python syntax/help checks pass. Native compilation has
not been performed locally because the pinned NDK is unavailable; the manual
Linux CI workflow has not been dispatched as part of adding this scaffold.

Sources: [hev 2.20.0](https://github.com/heiher/hev-socks5-tunnel/tree/75dbc1e9ace6c70d3d1b94df7c283efe1de7effc),
[Android API29 change](https://github.com/heiher/hev-socks5-tunnel/commit/d9d67110bd77f469e293ad29fc56b77071714549),
[NDK API rules](https://developer.android.com/ndk/guides/sdk-versions).
