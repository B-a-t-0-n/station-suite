"""Offline source build/import check only. Requires an existing checkout and NDK; no deployment."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parent


def run(argv, cwd, timeout=30, input_text=None):
    result = subprocess.run([str(value) for value in argv], cwd=cwd, input=input_text,
                            capture_output=True, text=True, encoding='utf8', errors='replace',
                            timeout=timeout)
    if result.returncode:
        raise RuntimeError(f'{Path(str(argv[0])).name} failed ({result.returncode}):\n'
                           + result.stdout + result.stderr)
    return result.stdout


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_api_expansion(text):
    # Recent Clang can define __ANDROID_API__ as another predefined macro.
    # Check its preprocessor-expanded value, rather than assuming a numeric -dM line.
    if text.strip() != '28':
        raise ValueError('Compiler is not targeting Android API28')


def verify_source(source, lock):
    if run(['git', 'rev-parse', 'HEAD'], source).strip() != lock['commit']:
        raise ValueError('Upstream commit differs from source-lock')
    if run(['git', 'status', '--porcelain', '--untracked-files=normal'], source).strip():
        raise ValueError('Source checkout must be clean, including submodules')
    observed = {}
    for line in run(['git', 'submodule', 'status', '--recursive'], source).splitlines():
        if not line.startswith(' '):
            raise ValueError('A recursive submodule is missing or has a different revision')
        fields = line[1:].split()
        observed[fields[1]] = fields[0]
    if observed != lock['submodules']:
        raise ValueError('Recursive submodule inventory differs from source-lock')
    for relative, expected in lock['notice_inputs'].items():
        path = source / relative
        if not path.is_file() or digest(path) != expected:
            raise ValueError('Notice input hash differs: ' + relative)


def inspect_elf(text):
    if not re.search(r'Class:\s+ELF64\b', text) or not re.search(r'Machine:\s+AArch64\b', text):
        raise ValueError('Native output must be ELF64 AArch64')
    needed = sorted(set(re.findall(r'Shared library: \[([^\]]+)\]', text)))
    if not needed or set(needed) - {'libc.so', 'libm.so', 'libdl.so', 'liblog.so'}:
        raise ValueError('Unexpected ELF DT_NEEDED: ' + ', '.join(needed))
    # Bionic Q+ versioned imports contradict API28 even if a toolchain accepted them.
    if re.search(r'@LIBC_(?:Q|R|S|T|U|V|W|X|Y|Z)\b', text) or 'GLIBC_' in text:
        raise ValueError('ELF contains a newer Android or non-Android runtime import')
    return needed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--ndk-home', type=Path, required=True)
    parser.add_argument('--verify-source-only', action='store_true')
    args = parser.parse_args()
    source = args.source.resolve()
    ndk = args.ndk_home.resolve()
    lock = json.loads((ROOT / 'source-lock.json').read_text(encoding='utf8'))
    verify_source(source, lock)
    if args.verify_source_only:
        print(json.dumps({'sourceVerified': True, 'commit': lock['commit'],
                          'submodules': lock['submodules'], 'nativeBuilt': False}))
        return
    revision_file = ndk / 'source.properties'
    revision = re.search(r'^Pkg.Revision\s*=\s*(\S+)\s*$',
                         revision_file.read_text(encoding='utf8'), re.MULTILINE)
    if not revision or revision.group(1) != lock['ndk_revision']:
        raise ValueError('NDK revision differs from source-lock')
    host = 'windows-x86_64' if os.name == 'nt' else 'darwin-x86_64' if sys.platform == 'darwin' else 'linux-x86_64'
    bin_dir = ndk / 'toolchains/llvm/prebuilt' / host / 'bin'
    readelf = bin_dir / ('llvm-readelf.exe' if os.name == 'nt' else 'llvm-readelf')
    compiler = bin_dir / ('aarch64-linux-android28-clang.cmd' if os.name == 'nt' else 'aarch64-linux-android28-clang')
    ndk_build = ndk / ('ndk-build.cmd' if os.name == 'nt' else 'ndk-build')
    for tool in (readelf, compiler, ndk_build):
        if not tool.is_file():
            raise ValueError('Expected NDK tool missing: ' + tool.name)
    build = ROOT / 'build'
    build.mkdir(exist_ok=True)
    # Never delete user-selected paths; all intermediates stay in a fresh, fixed workspace child.
    current = Path(tempfile.mkdtemp(prefix='api28-', dir=build))
    application = current / 'Application.mk'
    application.write_text('APP_PLATFORM := android-28\nAPP_ABI := arm64-v8a\n'
                           'APP_OPTIM := release\nAPP_CFLAGS := -O3\n'
                           'APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true\n'
                           'APP_MODULES := hev-socks5-tunnel\n', encoding='ascii')
    macros = run([compiler, '-dM', '-E', '-x', 'c', '-'], current, input_text='')
    (current / 'compiler-macros.txt').write_text(macros, encoding='utf8')
    expanded = run([compiler, '-E', '-P', '-x', 'c', '-'], current, input_text='__ANDROID_API__\n')
    verify_api_expansion(expanded)
    command = [ndk_build, 'NDK_PROJECT_PATH=null', f'APP_BUILD_SCRIPT={source / "Android.mk"}',
               f'NDK_APPLICATION_MK={application}', f'NDK_OUT={current / "obj"}',
               f'NDK_LIBS_OUT={current / "libs"}', 'APP_PLATFORM=android-28',
               'APP_ABI=arm64-v8a', 'APP_MODULES=hev-socks5-tunnel',
               'APP_LDFLAGS=-Wl,--no-undefined', '-j2', 'V=1']
    log = run(command, source, timeout=600)
    (current / 'ndk-build.txt').write_text(log, encoding='utf8')
    native = current / 'libs/arm64-v8a/libhev-socks5-tunnel.so'
    elf = run([readelf, '--file-header', '--dynamic', '--dyn-syms', '--notes', native], source)
    needed = inspect_elf(elf)
    (current / 'elf-imports.txt').write_text(elf, encoding='utf8')
    dist = ROOT / 'dist'
    output = dist / 'jniLibs/arm64-v8a'
    output.mkdir(parents=True, exist_ok=True)
    target = output / native.name
    shutil.copyfile(native, target)
    notices = ['Experimental source build only. Full compiled-file notice audit remains pending.\n']
    for relative in lock['notice_inputs']:
        notices.extend(['\n===== ' + relative + ' =====\n', (source / relative).read_text(encoding='utf8')])
    (dist / 'THIRD-PARTY-NOTICE-INPUTS.txt').write_text(''.join(notices), encoding='utf8')
    shutil.copyfile(current / 'elf-imports.txt', dist / 'elf-imports.txt')
    record = {'builtFromSource': True, 'source': lock, 'androidApiBuildTarget': 28,
              'needed': needed, 'bytes': target.stat().st_size, 'sha256': digest(target),
              'sourceLockSha256': digest(ROOT / 'source-lock.json'), 'builderSha256': digest(Path(__file__)),
              'command': [str(value) for value in command], 'deviceInstalled': False,
              'deviceTested': False, 'jniContract': 'upstream hev.htproxy.TProxyService',
              'runtimeCompatibilityVerified': False, 'completeLicenseAudit': False}
    (dist / 'BUILD-RECORD.json').write_text(json.dumps(record, indent=2) + '\n', encoding='utf8')
    (dist / (native.name + '.sha256')).write_text(record['sha256'] + '  ' + native.name + '\n', encoding='ascii')
    print(json.dumps({'ok': True, 'sha256': record['sha256'], 'deviceInstalled': False,
                      'runtimeCompatibilityVerified': False}))


if __name__ == '__main__':
    main()
