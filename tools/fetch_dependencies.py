"""Fetch pinned public ASR/runtime dependencies. No credentials or device access."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def fetch(url, destination, sha, size=None):
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.is_file() and hashlib.sha256(destination.read_bytes()).hexdigest() == sha:
        return
    temporary = destination.with_suffix(destination.suffix + '.download')
    request = urllib.request.Request(url, headers={'User-Agent': 'station-suite-dependency-fetch/1'})
    with urllib.request.urlopen(request, timeout=120) as response, temporary.open('wb') as out:
        shutil.copyfileobj(response, out, 1024 * 1024)
    if (size and temporary.stat().st_size != size) or hashlib.sha256(temporary.read_bytes()).hexdigest() != sha:
        temporary.unlink(missing_ok=True)
        raise ValueError('DEPENDENCY_HASH_MISMATCH')
    temporary.replace(destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--destination', type=Path, default=ROOT / '.deps')
    parser.add_argument('--without-model', action='store_true')
    args = parser.parse_args()
    target = args.destination.resolve()
    asr = json.loads((ROOT / 'config/asr-dependencies.json').read_text())
    for entry in asr['artifacts']:
        if not entry['file'].endswith('.aar'):
            continue  # Retired Vosk model is not the current recognizer.
        path = target / 'asr' / entry['file']
        fetch(entry['primary_url'], path, entry['sha256'], entry['bytes'])
        name = path.stem
        with zipfile.ZipFile(path) as archive:
            # Extract only build-time inputs; never follow archive paths.
            for item in archive.infolist():
                if item.filename == 'classes.jar' or item.filename.startswith('jni/arm64-v8a/') and item.filename.endswith('.so'):
                    relative = Path(item.filename)
                    if relative.is_absolute() or '..' in relative.parts: raise ValueError('INVALID_ARCHIVE_PATH')
                    output = target / 'asr/extracted' / name / relative
                    output.parent.mkdir(parents=True, exist_ok=True)
                    output.write_bytes(archive.read(item))
    sherpa = json.loads((ROOT / 'config/sherpa-downloads.json').read_text())
    for entry in sherpa['files']:
        if args.without_model and entry['path'].startswith('model/'): continue
        fetch(entry['url'], target / 'sherpa' / entry['path'], entry['sha256'], entry['bytes'])
    with tarfile.open(target / 'sherpa/downloads/sherpa-onnx-v2.0.1-android.tar.bz2') as archive:
        for member in archive.getmembers():
            if member.isfile() and '/arm64-v8a/' in member.name and member.name.endswith(('/libsherpa-onnx-jni.so', '/libonnxruntime.so')):
                output = target / 'sherpa/jniLibs/arm64-v8a' / Path(member.name).name
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_bytes(archive.extractfile(member).read())
    r8 = json.loads((ROOT / 'config/r8-download.json').read_text())
    fetch(r8['url'], target / 'asr/build-tools/r8-9.5.32.jar', r8['sha256'], r8['bytes'])
    # Android's org.json is replaced with this pinned host-test implementation.
    fetch('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',
        ROOT / 'pc/tests/speechify-host/deps/json-20240303.jar',
        '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed')
    print(json.dumps({'ok': True, 'modelIncluded': not args.without_model}))


if __name__ == '__main__': main()
