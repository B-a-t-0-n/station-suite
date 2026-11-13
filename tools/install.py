"""Install explicitly supplied own APKs and/or the pinned offline recognizer model."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
PACKAGES = {'agent': 'local.station.agent', 'voicebridge': 'local.station.voicebridge', 'phone': 'local.station.remote'}
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', required=True)
parser.add_argument('--station', default='192.168.77.100:5555')
parser.add_argument('--phone-serial')
parser.add_argument('--aapt2', help='Required when installing an APK; validates its package ID')
for name in PACKAGES: parser.add_argument('--' + name, type=Path)
parser.add_argument('--model', type=Path)
args = parser.parse_args()


def adb(serial, *command, payload=None):
    result = subprocess.run([args.adb, '-s', serial, *command], input=payload,
        capture_output=True, timeout=120, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    if result.returncode: raise SystemExit('ADB operation failed; device was not reset or uninstalled')


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
        model_files.append(path)
for name in PACKAGES:
    path = getattr(args, name)
    if path: adb(args.phone_serial if name == 'phone' else args.station, 'install', '-r', str(path.resolve()))
if model_files:
    adb(args.station, 'shell', 'am', 'force-stop', PACKAGES['voicebridge'])
    for path in model_files:
        # The filename is from the fixed publisher manifest, never user-supplied shell text.
        command = 'umask 077; mkdir -p files/local-sherpa-model; chmod 700 files/local-sherpa-model; cat > files/local-sherpa-model/' + path.name
        adb(args.station, 'shell', 'run-as', PACKAGES['voicebridge'], 'sh', '-c', "'" + command + "'", payload=path.read_bytes())
print(json.dumps({'ok': True, 'installedApps': sum(bool(getattr(args, name)) for name in PACKAGES), 'modelFiles': len(model_files), 'servicesStarted': False}))
