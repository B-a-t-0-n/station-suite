"""Install explicitly supplied own APKs and/or the pinned offline recognizer model."""
import argparse
import hashlib
import json
from pathlib import Path
import secrets
import shlex
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PACKAGES = {'agent': 'local.station.agent', 'voicebridge': 'local.station.voicebridge', 'phone': 'local.station.remote'}

def adb(args, serial, *command):
    result = subprocess.run([args.adb, '-s', serial, *command],
        capture_output=True, timeout=120, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    if result.returncode: raise SystemExit('ADB operation failed; device was not reset or uninstalled')
    return result.stdout


def shell(args, *command):
    # ADB forwards a single POSIX command string, even when its host is Windows.
    return adb(args, args.station, 'shell', shlex.join(command))


def deploy_model(args, model_files):
    """Push public model files, verify private staged copies, then replace each file atomically."""
    suffix = secrets.token_hex(12)
    public_stage = '/data/local/tmp/station-model-' + suffix
    private_stage = 'files/.station-model-' + suffix
    destination = 'files/local-sherpa-model'
    package = PACKAGES['voicebridge']
    public_created = private_created = False
    adb(args, args.station, 'shell', shlex.join(['am', 'force-stop', package]))
    try:
        shell(args, 'mkdir', public_stage)
        public_created = True
        shell(args, 'chmod', '755', public_stage)
        shell(args, 'run-as', package, 'sh', '-c', 'umask 077 && mkdir -p files && chmod 700 files')
        shell(args, 'run-as', package, 'sh', '-c',
              'umask 077 && mkdir ' + shlex.quote(private_stage))
        private_created = True
        for path, expected_sha in model_files:
            remote = public_stage + '/' + path.name
            staged = private_stage + '/' + path.name
            # Never send binary ONNX through a Windows subprocess stdin pipe.
            adb(args, args.station, 'push', str(path.resolve()), remote)
            shell(args, 'chmod', '644', remote)
            shell(args, 'run-as', package, 'cp', remote, staged)
            shell(args, 'run-as', package, 'chmod', '600', staged)
            actual = shell(args, 'run-as', package, 'sha256sum', staged).split()
            if not actual or actual[0].decode('ascii', errors='replace') != expected_sha:
                raise SystemExit('Pinned model integrity check failed on the device before promotion')
        # No final file changes until every staged file passed its pinned hash.
        shell(args, 'run-as', package, 'sh', '-c',
              'umask 077 && mkdir -p ' + shlex.quote(destination) + ' && chmod 700 ' + shlex.quote(destination))
        for path, expected_sha in model_files:
            final = destination + '/' + path.name
            shell(args, 'run-as', package, 'mv', private_stage + '/' + path.name, final)
            actual = shell(args, 'run-as', package, 'sha256sum', final).split()
            if not actual or actual[0].decode('ascii', errors='replace') != expected_sha:
                raise SystemExit('Pinned model integrity check failed after promotion')
    finally:
        pending_error = sys.exc_info()[0] is not None
        cleanup_failed = False
        # Exact generated paths only; never recursively remove a model directory.
        commands = []
        for path, _ in model_files:
            if private_created: commands.append(('run-as', package, 'rm', '-f', private_stage + '/' + path.name))
            if public_created: commands.append(('rm', '-f', public_stage + '/' + path.name))
        if private_created: commands.append(('run-as', package, 'rmdir', private_stage))
        if public_created: commands.append(('rmdir', public_stage))
        for command in commands:
            try:
                shell(args, *command)
            except (SystemExit, OSError, subprocess.SubprocessError):
                cleanup_failed = True
        if cleanup_failed:
            if not pending_error: raise SystemExit('Model copied, but its temporary stage cleanup failed')
            print('Model temporary stage cleanup incomplete; no unrelated files were removed', file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--station', default='192.168.77.100:5555')
    parser.add_argument('--phone-serial')
    parser.add_argument('--aapt2', help='Required when installing an APK; validates its package ID')
    for name in PACKAGES: parser.add_argument('--' + name, type=Path)
    parser.add_argument('--model', type=Path)
    args = parser.parse_args()

    # Validate every input before the first device mutation.
    for name, package in PACKAGES.items():
        path = getattr(args, name)
        if not path: continue
        if not args.aapt2: raise SystemExit('--aapt2 is required to verify the APK package')
        result = subprocess.run([args.aapt2, 'dump', 'badging', str(path.resolve())], capture_output=True, timeout=20)
        if result.returncode or ("package: name='" + package + "'").encode() not in result.stdout:
            raise SystemExit('Supplied APK package does not match the selected app')
        if name == 'phone' and not args.phone_serial: raise SystemExit('--phone-serial is required for the phone APK')
    model_files = []
    if args.model:
        for entry in json.loads((ROOT / 'config/sherpa-downloads.json').read_text())['files']:
            if not entry['path'].startswith('model/'): continue
            path = args.model / Path(entry['path']).name
            if path.stat().st_size != entry['bytes'] or hashlib.sha256(path.read_bytes()).hexdigest() != entry['sha256']:
                raise SystemExit('Pinned model integrity check failed before deployment')
            model_files.append((path, entry['sha256']))
    for name in PACKAGES:
        path = getattr(args, name)
        if path: adb(args, args.phone_serial if name == 'phone' else args.station, 'install', '-r', str(path.resolve()))
    if model_files: deploy_model(args, model_files)
    print(json.dumps({'ok': True, 'installedApps': sum(bool(getattr(args, name)) for name in PACKAGES), 'modelFiles': len(model_files), 'servicesStarted': False}))


if __name__ == '__main__': main()
