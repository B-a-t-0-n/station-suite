"""Build the bounded local relay lab APK using a verified local native build. No deployment."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--javac-home', type=Path)
    parser.add_argument('--android-parts', type=Path, required=True)
    parser.add_argument('--signing-dir', type=Path, required=True)
    parser.add_argument('--keystore', default='station-suite.p12')
    parser.add_argument('--alias', default='station-suite')
    args = parser.parse_args()
    signer = args.signing_dir.resolve()
    suite = ROOT.parents[1]
    if signer == suite or suite in signer.parents:
        raise RuntimeError('Signing material must remain outside the public checkout')
    if Path(args.keystore).name != args.keystore:
        raise RuntimeError('Keystore argument must be a filename in signing-dir')
    password = signer / 'password.txt'
    keystore = signer / args.keystore
    if not password.is_file() or not keystore.is_file():
        raise RuntimeError('Existing external signer is missing; no new signer generated')
    java = args.java_home / 'bin/java.exe'
    javac = (args.javac_home or args.java_home) / 'bin/javac.exe'
    sdk = args.android_parts
    bt = sdk / 'build-tools'
    android = sdk / 'platform/android.jar'
    build = ROOT / 'build'
    dist = ROOT / 'dist'
    build.mkdir(exist_ok=True)
    dist.mkdir(exist_ok=True)
    classes = build / 'classes'
    dex = build / 'dex'
    for target in (classes, dex):
        target = target.resolve()
        if target.parent != build.resolve():
            raise RuntimeError('Build output escaped its fixed directory')
        if target.exists():
            shutil.rmtree(target)
        target.mkdir()
    commands = []

    def run(argv, step):
        result = subprocess.run([str(value) for value in argv], cwd=ROOT, capture_output=True,
                                text=True, encoding='utf8', errors='replace', timeout=120)
        (build / (step + '.log')).write_text(result.stdout + result.stderr, encoding='utf8')
        commands.append({'step': step, 'exit': result.returncode})
        if result.returncode:
            raise RuntimeError(step + ' failed; inspect build/' + step + '.log')
        print(step + ' OK', flush=True)
        return result.stdout

    env_name = 'VPN_PROBE_BUILD_KEY_PASSWORD'
    os.environ[env_name] = password.read_text(encoding='ascii').strip()
    try:
        native_dist = ROOT.parent / 'native-tun2socks/dist'
        native = native_dist / 'jniLibs/arm64-v8a/libhev-socks5-tunnel.so'
        native_record = json.loads((native_dist / 'BUILD-RECORD.json').read_text(encoding='utf8'))
        current_lock = ROOT.parent / 'native-tun2socks/source-lock.json'
        if (not native_record.get('builtFromSource') or native_record.get('androidApiBuildTarget') != 28
                or native_record.get('sourceLockSha256') != hashlib.sha256(current_lock.read_bytes()).hexdigest()
                or hashlib.sha256(native.read_bytes()).hexdigest() != native_record.get('sha256')):
            raise RuntimeError('Native source build provenance is missing or does not match API28/source lock')
        generated = build / 'generated/local/station/vpnprobe/RelayBuild.java'
        generated.parent.mkdir(parents=True, exist_ok=True)
        generated.write_text('package local.station.vpnprobe; final class RelayBuild { static final String NATIVE_SHA256 = "'
                             + native_record['sha256'] + '"; }\n', encoding='ascii')
        sources = sorted((ROOT / 'src').rglob('*.java'))
        sources += sorted((ROOT.parent / 'vpn-coverage/src').rglob('*.java'))
        sources += sorted((ROOT.parent / 'local-service-relay/src').rglob('*.java'))
        sources += [ROOT.parent / 'native-tun2socks/runtime-smoke/src/hev/htproxy/TProxyService.java', generated]
        required = {'ProbeSpec.java', 'PacketMetadata.java', 'ProbeService.java',
                    'ProbeActivity.java', 'MarkerService.java', 'ShellWatchdog.java',
                    'WatchdogProtocol.java', 'RelayService.java', 'RelayTransport.java', 'RelayPacketPolicy.java'}
        if not required.issubset({path.name for path in sources}):
            raise RuntimeError('A required probe/watchdog implementation is missing')
        run([javac, '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-Xlint:all',
             '-classpath', android, '-d', classes, *sources], 'javac')
        class_jar = build / 'classes.jar'
        with zipfile.ZipFile(class_jar, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            for source in sorted(classes.rglob('*.class')):
                archive.write(source, source.relative_to(classes).as_posix())
        run([java, '-cp', bt / 'd8.jar', 'com.android.tools.r8.D8', '--release', '--min-api', '28',
             '--lib', android, '--output', dex, class_jar], 'd8')
        base = build / 'base.apk'
        run([bt / 'aapt2.exe', 'link', '-I', android, '--manifest', ROOT / 'AndroidManifest.xml',
             '--min-sdk-version', '28', '--target-sdk-version', '28', '-o', base], 'aapt2-link')
        unsigned = build / 'unsigned.apk'
        with zipfile.ZipFile(base) as original, zipfile.ZipFile(unsigned, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            for name in original.namelist():
                archive.writestr(name, original.read(name), compress_type=zipfile.ZIP_STORED
                                 if name == 'resources.arsc' else zipfile.ZIP_DEFLATED)
            archive.write(dex / 'classes.dex', 'classes.dex')
            archive.write(native, 'lib/arm64-v8a/libhev-socks5-tunnel.so', compress_type=zipfile.ZIP_STORED)
            for notice in ('THIRD-PARTY-NOTICE-INPUTS.txt', 'SOURCE-NOTICE-INVENTORY.json', 'third-party-source-snapshot.zip'):
                if not (native_dist / notice).is_file():
                    raise RuntimeError('Native notice/source bundle is incomplete: ' + notice)
                archive.write(native_dist / notice, 'assets/native-source/' + notice)
        aligned = build / 'aligned.apk'
        run([bt / 'zipalign.exe', '-f', '4', unsigned, aligned], 'zipalign')
        apk = dist / 'station-vpn-local-relay-lab-0.2.apk'
        run([java, '-jar', bt / 'apksigner.jar', 'sign', '--ks', keystore,
             '--ks-key-alias', args.alias, '--ks-pass', 'env:' + env_name,
             '--key-pass', 'env:' + env_name, '--min-sdk-version', '28',
             '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true',
             '--v3-signing-enabled', 'false', '--out', apk, aligned], 'sign')
        run([java, '-jar', bt / 'apksigner.jar', 'verify', '--verbose', apk], 'signature-verify')
        run([bt / 'zipalign.exe', '-c', '4', apk], 'alignment-verify')
        permissions = run([bt / 'aapt2.exe', 'dump', 'permissions', apk], 'permissions')
        declared = {line.split("'")[1] for line in permissions.splitlines()
                    if line.strip().startswith('uses-permission:') and "'" in line}
        if declared != {'android.permission.INTERNET', 'android.permission.FOREGROUND_SERVICE',
                        'android.permission.ACCESS_NETWORK_STATE', 'android.permission.ACCESS_WIFI_STATE'}:
            raise RuntimeError('Unexpected probe permissions: ' + ', '.join(sorted(declared)))
        run([bt / 'aapt2.exe', 'dump', 'xmltree', '--file', 'AndroidManifest.xml', apk], 'manifest-tree')
        raw = apk.read_bytes()
        record = {'package': 'local.station.vpnprobe', 'version': '0.2-relay-lab', 'target_sdk': 28,
                  'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest(),
                  'device_installed': False, 'device_tested': False, 'nativeSha256': native_record['sha256'], 'publicRuntimeRelease': False, 'steps': commands,
                  'sources': [{'path': source.relative_to(ROOT.parent).as_posix(),
                               'sha256': hashlib.sha256(source.read_bytes()).hexdigest()}
                              for source in [ROOT / 'AndroidManifest.xml', *sources]]}
        (dist / 'BUILD-RECORD.json').write_text(json.dumps(record, indent=2) + '\n', encoding='utf8')
        (dist / (apk.name + '.sha256')).write_text(record['sha256'] + '  ' + apk.name + '\n', encoding='ascii')
        print(json.dumps({'apk': str(apk), 'bytes': len(raw), 'sha256': record['sha256']}), flush=True)
    finally:
        os.environ.pop(env_name, None)


if __name__ == '__main__':
    main()
