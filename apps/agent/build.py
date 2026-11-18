"""Build the Station Agent APK locally. No ADB/device execution."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent
SUITE = ROOT.parents[1]
JAVA = Path(os.environ['STATION_JAVA_HOME']) / 'bin'
JAVAC = Path(os.environ.get('STATION_JAVAC_HOME', os.environ['STATION_JAVA_HOME'])) / 'bin/javac.exe'
SDK = Path(os.environ.get('STATION_ANDROID_PARTS', str(SUITE / '.deps/android')))
BT = SDK / 'build-tools'
ANDROID = SDK / 'platform/android.jar'
BUILD = ROOT / 'build'
PRIVATE = Path(os.environ['STATION_SIGNING_DIR'])
OUT = ROOT / 'dist'
VERSION, VERSION_CODE = '0.7.9', 23
commands = []


def run(argv, name, timeout=120):
    argv = [str(x) for x in argv]
    result = subprocess.run(argv, cwd=ROOT, capture_output=True, text=True,
                            encoding='utf8', errors='replace', timeout=timeout)
    (BUILD / f'{name}.log').write_text(result.stdout + result.stderr, encoding='utf8')
    commands.append({'step': name, 'argv': argv, 'exit_code': result.returncode})
    if result.returncode:
        raise RuntimeError(f'{name} failed, see build/{name}.log')
    print(name, 'OK', flush=True)
    return result.stdout + result.stderr


def write_zip(path, files):
    with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for source, name in sorted(files, key=lambda pair: pair[1]):
            entry = zipfile.ZipInfo(name, (2026, 11, 5, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, source.read_bytes())


def main():
    BUILD.mkdir(exist_ok=True)
    OUT.mkdir(exist_ok=True)
    classes = BUILD / 'classes'
    dex = BUILD / 'dex'
    for stale in (classes, dex):
        if stale.exists():
            shutil.rmtree(stale)
        stale.mkdir(parents=True)
    password = PRIVATE / 'password.txt'
    keystore = PRIVATE / os.environ.get('STATION_KEYSTORE_NAME', 'station-suite.p12')
    if not password.is_file() or not keystore.is_file():
        raise RuntimeError('Reviewed existing own signer is missing; no new key generated')
    env_name = 'AUDIOPROBE_BUILD_SIGNING_PASSWORD'
    os.environ[env_name] = password.read_text(encoding='ascii')
    try:
        sources = sorted((ROOT / 'src').rglob('*.java'))
        # This bundled D8 requires javac8 for inner listener synthetic accessors.
        run([JAVAC, '-encoding', 'UTF-8', '-source', '8', '-target', '8',
             '-classpath', ANDROID, '-Xlint:all', '-d', classes, *sources], 'javac')
        class_files = sorted(classes.rglob('*.class'))
        class_jar = BUILD / 'classes.jar'
        write_zip(class_jar, [(p, p.relative_to(classes).as_posix()) for p in class_files])
        run([JAVA / 'java.exe', '-cp', BT / 'd8.jar', 'com.android.tools.r8.D8',
             '--release', '--min-api', '28', '--lib', ANDROID, '--output', dex, class_jar], 'd8')
        run([BT / 'aapt2.exe', 'compile', '--dir', ROOT / 'res', '-o', BUILD / 'resources.zip'],
            'aapt2-compile')
        base = BUILD / 'base.apk'
        run([BT / 'aapt2.exe', 'link', '-I', ANDROID, '--manifest', ROOT / 'AndroidManifest.xml',
             '--min-sdk-version', '28', '--target-sdk-version', '28',
             '-A', ROOT / 'assets', '-R', BUILD / 'resources.zip', '-o', base], 'aapt2-link')
        unsigned = BUILD / 'unsigned.apk'
        with zipfile.ZipFile(base) as original:
            names = set(original.namelist())
            required = {'AndroidManifest.xml', 'resources.arsc', 'res/xml/network_security_config.xml',
                        'assets/index.html', 'assets/schedule-signal.wav',
                        'assets/schedule-alert.gif', 'assets/schedule-clear.gif'}
            if not required.issubset(names):
                raise RuntimeError('linked APK is missing required entries: ' + str(sorted(required - names)))
            if original.getinfo('assets/schedule-signal.wav').compress_type != zipfile.ZIP_STORED:
                raise RuntimeError('offline alarm WAV must allow AssetFileDescriptor access')
            with zipfile.ZipFile(unsigned, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
                for name in original.namelist():
                    archive.writestr(name, original.read(name),
                                     compress_type=zipfile.ZIP_STORED
                                     if name in ('resources.arsc', 'assets/schedule-signal.wav') else zipfile.ZIP_DEFLATED)
                archive.write(dex / 'classes.dex', 'classes.dex')
        aligned = BUILD / 'aligned.apk'
        run([BT / 'zipalign.exe', '-f', '4', unsigned, aligned], 'zipalign')
        apk = OUT / f'station-agent-{VERSION}.apk'
        run([JAVA / 'java.exe', '-jar', BT / 'apksigner.jar', 'sign', '--ks', keystore,
             '--ks-key-alias', os.environ.get('STATION_KEY_ALIAS', 'station-suite'), '--ks-pass', f'env:{env_name}',
             '--key-pass', f'env:{env_name}', '--min-sdk-version', '28',
             '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true',
             '--v3-signing-enabled', 'false', '--out', apk, aligned], 'sign')
        run([JAVA / 'java.exe', '-jar', BT / 'apksigner.jar', 'verify', '--verbose', '--print-certs', apk],
            'signature-verify')
        run([BT / 'zipalign.exe', '-c', '4', apk], 'alignment-verify')
        run([BT / 'aapt2.exe', 'dump', 'badging', apk], 'badging')
        run([BT / 'aapt2.exe', 'dump', 'permissions', apk], 'permissions')
        run([BT / 'aapt2.exe', 'dump', 'xmltree', '--file', 'AndroidManifest.xml', apk], 'manifest-tree')
        raw = apk.read_bytes()
        sources_record = [{'path': p.relative_to(ROOT).as_posix(), 'bytes': p.stat().st_size,
                           'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
                          for p in [ROOT / 'AndroidManifest.xml',
                                    ROOT / 'res/xml/network_security_config.xml',
                                    *sorted((ROOT / 'assets').rglob('*.*')), *sources, Path(__file__).resolve()]]
        record = {'package': 'local.station.agent', 'version': VERSION, 'version_code': VERSION_CODE, 'min_sdk': 28,
                  'target_sdk': 28, 'compile_sdk': 28, 'apk': str(apk), 'bytes': len(raw),
                  'sha256': hashlib.sha256(raw).hexdigest(), 'java_home': str(JAVA.parent),
                  'sources': sources_record, 'steps': commands,
                  'installed_on_station': False, 'started_on_station': False,
                  'device_tested': False}
        (OUT / 'BUILD-RECORD.json').write_text(json.dumps(record, indent=2) + '\n', encoding='utf8')
        apk.with_suffix('.apk.sha256').write_text(record['sha256'] + '  ' + apk.name + '\n', encoding='ascii')
        print(json.dumps({'apk': str(apk), 'bytes': len(raw), 'sha256': record['sha256']}), flush=True)
    finally:
        os.environ.pop(env_name, None)


if __name__ == '__main__':
    main()
