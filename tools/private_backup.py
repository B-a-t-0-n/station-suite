"""Encrypted, allowlisted Station backup/restore. Secrets never enter CLI/output.

The encrypted archive and password file MUST live outside the public checkout.
Restore accepts only manifest names defined here; no archive-supplied commands or paths.
"""
from __future__ import annotations
import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC

SUITE = Path(__file__).resolve().parents[1]
PACKAGE = 'local.station.voicebridge'
MAGIC = b'STATIONSUITE-BACKUP-1\n'
ITERATIONS = 600000
MAX_BYTES = 16 * 1024 * 1024
DEVICE_FILES = ('files/config.json', 'files/native-sync.json', 'files/display-settings.json',
    'files/music-config.json', 'files/native-tls.p12', 'files/native-tls-password.txt',
    'shared_prefs/voice_runtime.xml')
PC_FILES = {'pc/config-policy.json': 'policy', 'pc/native-baseline.json': 'baseline',
    'pc/fullchain.pem': 'tls_cert', 'pc/quasar-all.key': 'tls_key'}
ALL_FILES = {'voice/' + name for name in DEVICE_FILES} | set(PC_FILES)


def outside(path):
    path = Path(path).expanduser().resolve()
    if path == SUITE or SUITE in path.parents:
        raise ValueError('PRIVATE_PATH_INSIDE_PUBLIC_CHECKOUT')
    return path


def protect(path):
    if os.name == 'nt':
        sys.path.insert(0, str(SUITE / 'pc'))
        from setup_speechify import _restrict_file
        _restrict_file(path)
    else:
        os.chmod(path, 0o600)


def private_write(path, data):
    path = outside(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    # Refuse overwrite; an earlier recoverable copy must survive.
    with path.open('xb') as stream:
        protect(path)
        stream.write(data)


def read_bounded(path):
    with Path(path).open('rb') as stream:
        data = stream.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        raise ValueError('INPUT_TOO_LARGE')
    return data


def password(path):
    data = read_bounded(outside(path)).strip()
    if len(data) < 16 or len(data) > 4096:
        raise ValueError('PASSWORD_FILE_REQUIRES_16_TO_4096_BYTES')
    return data


def key(passphrase, salt):
    return PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=salt,
                     iterations=ITERATIONS).derive(passphrase)


def adb(args, *command, payload=None, check=True):
    process = subprocess.run([args.adb, '-s', args.serial, *command], input=payload,
        capture_output=True, timeout=30, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    if check and process.returncode:
        raise ValueError('ADB_OPERATION_FAILED')
    return process


def backup(args):
    entries, missing = {}, []
    # Fixed paths only. run-as reads app-private credentials without root.
    for name in DEVICE_FILES:
        result = adb(args, 'exec-out', 'run-as', PACKAGE, 'cat', name, check=False)
        if result.returncode:
            missing.append('voice/' + name)
        else:
            if len(result.stdout) > MAX_BYTES:
                raise ValueError('INPUT_TOO_LARGE')
            entries['voice/' + name] = result.stdout
    for name, argument in PC_FILES.items():
        source = getattr(args, argument)
        if source:
            entries[name] = read_bounded(outside(source))
        else:
            missing.append(name)
    # These are mandatory for restoring the current native TLS/config path.
    mandatory = {'voice/files/config.json', 'voice/files/native-sync.json',
        'voice/files/native-tls.p12', 'voice/files/native-tls-password.txt', *PC_FILES}
    if mandatory - set(entries):
        raise ValueError('REQUIRED_BACKUP_INPUT_MISSING')
    manifest = {'format': 1, 'createdUtc': datetime.now(timezone.utc).isoformat(),
        'package': PACKAGE, 'files': [{'path': name, 'bytes': len(data),
        'sha256': hashlib.sha256(data).hexdigest()} for name, data in sorted(entries.items())],
        'missingOptional': missing,
        'notIncluded': ['Agent private preferences (not debuggable)', 'phone Bluetooth pairing',
                        'ASR model (download by pinned manifest)', 'firmware', 'device jailbreak/trust modification']}
    plain = json.dumps({'manifest': manifest, 'files': {name: base64.b64encode(data).decode('ascii')
        for name, data in entries.items()}}, separators=(',', ':')).encode('utf-8')
    salt, nonce = secrets.token_bytes(16), secrets.token_bytes(12)
    encrypted = AESGCM(key(password(args.password_file), salt)).encrypt(nonce, plain, MAGIC)
    private_write(args.archive, MAGIC + salt + nonce + encrypted)
    print(json.dumps({'ok': True, 'encrypted': True, 'files': len(entries), 'optionalMissing': len(missing)}))


def decode(args):
    blob = read_bounded(outside(args.archive))
    if not blob.startswith(MAGIC) or len(blob) < len(MAGIC) + 44:
        raise ValueError('INVALID_ARCHIVE')
    index = len(MAGIC)
    plain = AESGCM(key(password(args.password_file), blob[index:index+16])).decrypt(
        blob[index+16:index+28], blob[index+28:], MAGIC)
    value = json.loads(plain)
    manifest, encoded = value['manifest'], value['files']
    if manifest['format'] != 1 or manifest['package'] != PACKAGE or set(encoded) - ALL_FILES:
        raise ValueError('INVALID_MANIFEST')
    expected = {row['path']: row for row in manifest['files']}
    if set(expected) != set(encoded) or len(expected) != len(manifest['files']):
        raise ValueError('INVALID_MANIFEST')
    entries = {}
    for name, payload in encoded.items():
        data = base64.b64decode(payload, validate=True)
        if len(data) != expected[name]['bytes'] or hashlib.sha256(data).hexdigest() != expected[name]['sha256']:
            raise ValueError('ARCHIVE_HASH_MISMATCH')
        entries[name] = data
    return manifest, entries


def restore(args):
    manifest, entries = decode(args)
    if not args.apply:
        print(json.dumps({'ok': True, 'verified': True, 'files': len(entries), 'deviceChanged': False}))
        return
    destination = outside(args.private_dir)
    if not destination.is_dir():
        destination.mkdir(parents=True)
    # Refuse partial overwrite of an existing deployment.
    for name in PC_FILES:
        if (destination / name).exists():
            raise ValueError('PRIVATE_DESTINATION_ALREADY_HAS_CONFIGURATION')
    # App must already be installed with compatible signer and run-as available.
    adb(args, 'shell', 'run-as', PACKAGE, 'pwd')
    adb(args, 'shell', 'am', 'force-stop', PACKAGE)
    for name in DEVICE_FILES:
        archive_name = 'voice/' + name
        if archive_name not in entries:
            continue
        directory = name.split('/')[0]
        command = f'umask 077; mkdir -p {directory}; chmod 700 {directory}; cat > {name}; chmod 600 {name}'
        adb(args, 'shell', 'run-as', PACKAGE, 'sh', '-c', "'" + command + "'", payload=entries[archive_name])
    for name in PC_FILES:
        if name in entries:
            private_write(destination / name, entries[name])
    print(json.dumps({'ok': True, 'restored': True, 'files': len(entries),
        'startedServices': False, 'next': 'Configure deployment.json, install model, then start PC tunnel.'}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['backup', 'verify', 'restore'])
    parser.add_argument('--adb', default='adb.exe')
    parser.add_argument('--serial', default='192.168.77.100:5555')
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--password-file', type=Path, required=True)
    parser.add_argument('--policy', type=Path)
    parser.add_argument('--baseline', type=Path)
    parser.add_argument('--tls-cert', type=Path)
    parser.add_argument('--tls-key', type=Path)
    parser.add_argument('--private-dir', type=Path)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    try:
        if args.command == 'backup': backup(args)
        elif args.command == 'verify':
            manifest, entries = decode(args)
            print(json.dumps({'ok': True, 'verified': True, 'files': len(entries)}))
        else:
            if args.apply and not args.private_dir: raise ValueError('PRIVATE_DIR_REQUIRED')
            restore(args)
    except Exception as error:
        code = str(error) if isinstance(error, ValueError) and str(error).isupper() else 'PRIVATE_OPERATION_FAILED'
        print(json.dumps({'ok': False, 'code': code})); raise SystemExit(1) from None


if __name__ == '__main__':
    main()
