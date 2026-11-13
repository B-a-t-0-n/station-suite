"""Run offline tests: fake peers + temporary loopback, no Station/phone/cloud API."""
import argparse
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--java-home', type=Path, required=True)
parser.add_argument('--javac-home', type=Path)
parser.add_argument('--android-parts', type=Path, default=ROOT / '.deps/android')
args = parser.parse_args()
environment = dict(os.environ, STATION_JAVA_HOME=str(args.java_home.resolve()),
    STATION_JAVAC_HOME=str((args.javac_home or args.java_home).resolve()),
    STATION_ANDROID_PARTS=str(args.android_parts.resolve()),
    # These addresses identify synthetic Windows adapter fixtures, not a real NIC.
    STATION_WAN_INTERFACE='10', STATION_WAN_NETWORK='10.131.146.0/24')
for folder in ['tools/tests', 'pc/tests', 'apps/agent/tests']:
    subprocess.run([sys.executable, '-m', 'unittest', 'discover', '-s', str(ROOT / folder), '-p', 'test_*.py'],
        env=environment, check=True)
