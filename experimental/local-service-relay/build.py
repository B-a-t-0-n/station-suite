"""Build a standalone API28 DEX jar offline; no APK, downloads, ADB or deployment."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--javac-home', type=Path)
    parser.add_argument('--android-parts', type=Path, required=True)
    args = parser.parse_args()
    java = args.java_home / 'bin/java.exe'
    javac = (args.javac_home or args.java_home) / 'bin/javac.exe'
    android = args.android_parts / 'platform/android.jar'
    build = ROOT / 'build'
    classes = build / 'classes'
    dex = build / 'dex'
    dist = ROOT / 'dist'
    for path in (classes, dex, dist):
        path.mkdir(parents=True, exist_ok=True)
    sources = sorted((ROOT / 'src').rglob('*.java'))
    # An explicit fresh source list is archived, so stale .class files cannot enter the jar.
    expected = {'local/station/relay/LocalServiceRelay.class', 'local/station/relay/RelayProtocol.class',
                'local/station/relay/RelayProtocol$FirstFlight.class', 'local/station/relay/RelayProtocol$Cursor.class'}

    def run(argv):
        result = subprocess.run([str(v) for v in argv], cwd=ROOT, capture_output=True, text=True, encoding='utf8', errors='replace', timeout=90)
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)

    run([javac, '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-Xlint:all', '-d', classes, *sources])
    jar = build / 'classes.jar'
    with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name in sorted(expected):
            archive.write(classes / name, name)
    run([java, '-cp', args.android_parts / 'build-tools/d8.jar', 'com.android.tools.r8.D8', '--release', '--min-api', '28',
         '--lib', android, '--output', dex, jar])
    result = dist / 'station-local-service-relay.dex.jar'
    with zipfile.ZipFile(result, 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.write(dex / 'classes.dex', 'classes.dex')
    record = {'min_api': 28, 'sha256': hashlib.sha256(result.read_bytes()).hexdigest(), 'bytes': result.stat().st_size,
              'deployed': False, 'sources': [{'path': s.relative_to(ROOT).as_posix(), 'sha256': hashlib.sha256(s.read_bytes()).hexdigest()} for s in sources]}
    (dist / 'BUILD-RECORD.json').write_text(json.dumps(record, indent=2) + '\n', encoding='utf8')
    print(json.dumps(record, indent=2))


if __name__ == '__main__':
    main()
