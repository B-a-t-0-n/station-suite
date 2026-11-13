"""Prepare the minimal Android tool layout from an installed official Windows SDK."""
import argparse
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--sdk', type=Path, required=True)
parser.add_argument('--build-tools', default='36.0.0')
parser.add_argument('--destination', type=Path, default=ROOT / '.deps/android')
args = parser.parse_args()
sources = {'build-tools/aapt2.exe': args.sdk / 'build-tools' / args.build_tools / 'aapt2.exe',
    'build-tools/zipalign.exe': args.sdk / 'build-tools' / args.build_tools / 'zipalign.exe',
    'build-tools/apksigner.jar': args.sdk / 'build-tools' / args.build_tools / 'lib/apksigner.jar',
    'build-tools/d8.jar': args.sdk / 'build-tools' / args.build_tools / 'lib/d8.jar',
    'platform/android.jar': args.sdk / 'platforms/android-28/android.jar'}
for relative, source in sources.items():
    if not source.is_file(): raise SystemExit('Missing official SDK component: ' + str(source))
for relative, source in sources.items():
    output = args.destination / relative
    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, output)
print('Android tool layout prepared; SDK license remains with its publisher.')
