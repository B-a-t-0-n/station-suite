"""Offline API28 smoke DEX jar only; no library import, downloads, APK, ADB or execution."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--javac-home', type=Path)
    parser.add_argument('--android-parts', type=Path, required=True)
    args = parser.parse_args()
    exe = '.exe' if os.name == 'nt' else ''
    java = args.java_home / ('bin/java' + exe)
    javac = (args.javac_home or args.java_home) / ('bin/javac' + exe)
    sdk = args.android_parts
    android = sdk / 'platform/android.jar'
    build = ROOT / 'build'
    dist = ROOT / 'dist'
    build.mkdir(exist_ok=True)
    dist.mkdir(exist_ok=True)
    # Each build gets a fresh child; never deletes caller-selected directories.
    current = Path(tempfile.mkdtemp(prefix='smoke-', dir=build))
    classes = current / 'classes'
    dex = current / 'dex'
    classes.mkdir()
    dex.mkdir()

    def run(argv):
        result = subprocess.run([str(value) for value in argv], cwd=ROOT, capture_output=True,
                                text=True, encoding='utf8', errors='replace', timeout=90)
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)

    sources = sorted((ROOT / 'src').rglob('*.java'))
    run([javac, '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-Xlint:all',
         '-classpath', android, '-d', classes, *sources])
    classjar = current / 'classes.jar'
    with zipfile.ZipFile(classjar, 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(classes.rglob('*.class')):
            archive.write(path, path.relative_to(classes).as_posix())
    run([java, '-cp', sdk / 'build-tools/d8.jar', 'com.android.tools.r8.D8', '--release', '--min-api', '28',
         '--lib', android, '--output', dex, classjar])
    result = dist / 'station-hev-runtime-smoke.dex.jar'
    with zipfile.ZipFile(result, 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.write(dex / 'classes.dex', 'classes.dex')
    record = {'minApi': 28, 'bytes': result.stat().st_size,
              'sha256': hashlib.sha256(result.read_bytes()).hexdigest(), 'nativeLibraryBundled': False,
              'deviceInstalled': False, 'deviceTested': False,
              'sources': [{'path': p.relative_to(ROOT).as_posix(), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in sources]}
    (dist / 'BUILD-RECORD.json').write_text(json.dumps(record, indent=2) + '\n', encoding='utf8')
    print(json.dumps(record, indent=2))


if __name__ == '__main__':
    main()
