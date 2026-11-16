"""Build the standalone bounded spectrum APK. No downloads, ADB or deployment."""
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

    env_name = 'SPECTRUM_BUILD_KEY_PASSWORD'
    os.environ[env_name] = password.read_text(encoding='ascii').strip()
    try:
        sources = sorted((ROOT / 'src').rglob('*.java'))
        if not any(path.name == 'SpectrumBands.java' for path in sources):
            raise RuntimeError('The independent SpectrumBands implementation is missing')
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
        aligned = build / 'aligned.apk'
        run([bt / 'zipalign.exe', '-f', '4', unsigned, aligned], 'zipalign')
        apk = dist / 'station-spectrum-probe-0.1.apk'
        run([java, '-jar', bt / 'apksigner.jar', 'sign', '--ks', keystore,
             '--ks-key-alias', args.alias, '--ks-pass', 'env:' + env_name,
             '--key-pass', 'env:' + env_name, '--min-sdk-version', '28',
             '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true',
             '--v3-signing-enabled', 'false', '--out', apk, aligned], 'sign')
        run([java, '-jar', bt / 'apksigner.jar', 'verify', '--verbose', apk], 'signature-verify')
        run([bt / 'zipalign.exe', '-c', '4', apk], 'alignment-verify')
        permissions = run([bt / 'aapt2.exe', 'dump', 'permissions', apk], 'permissions')
        if 'android.permission.INTERNET' in permissions:
            raise RuntimeError('Diagnostic APK unexpectedly requests network access')
        run([bt / 'aapt2.exe', 'dump', 'xmltree', '--file', 'AndroidManifest.xml', apk], 'manifest-tree')
        raw = apk.read_bytes()
        record = {'package': 'local.station.spectrum', 'version': '0.1', 'target_sdk': 28,
                  'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest(),
                  'device_installed': False, 'device_tested': False, 'steps': commands,
                  'sources': [{'path': source.relative_to(ROOT).as_posix(),
                               'sha256': hashlib.sha256(source.read_bytes()).hexdigest()}
                              for source in [ROOT / 'AndroidManifest.xml', *sources]]}
        (dist / 'BUILD-RECORD.json').write_text(json.dumps(record, indent=2) + '\n', encoding='utf8')
        (dist / (apk.name + '.sha256')).write_text(record['sha256'] + '  ' + apk.name + '\n', encoding='ascii')
        print(json.dumps({'apk': str(apk), 'bytes': len(raw), 'sha256': record['sha256']}), flush=True)
    finally:
        os.environ.pop(env_name, None)


if __name__ == '__main__':
    main()
