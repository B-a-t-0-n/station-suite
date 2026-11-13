"""Build selected own APKs using explicit local toolchains and private signer."""
import argparse
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--app', choices=['phone', 'agent', 'voicebridge', 'all'], default='all')
parser.add_argument('--java-home', type=Path, required=True)
parser.add_argument('--javac-home', type=Path)
parser.add_argument('--android-parts', type=Path, default=ROOT / '.deps/android')
parser.add_argument('--signing-dir', type=Path, required=True)
parser.add_argument('--keystore', default='station-suite.p12')
parser.add_argument('--alias', default='station-suite')
parser.add_argument('--asr-deps', type=Path, default=ROOT / '.deps/asr')
parser.add_argument('--sherpa-deps', type=Path, default=ROOT / '.deps/sherpa')
args = parser.parse_args()
signer = args.signing_dir.resolve()
if ROOT == signer or ROOT in signer.parents:
    raise SystemExit('Signer must remain outside this public checkout')
environment = dict(os.environ, STATION_JAVA_HOME=str(args.java_home.resolve()),
    STATION_JAVAC_HOME=str((args.javac_home or args.java_home).resolve()),
    STATION_ANDROID_PARTS=str(args.android_parts.resolve()), STATION_SIGNING_DIR=str(signer),
    STATION_KEYSTORE_NAME=args.keystore, STATION_KEY_ALIAS=args.alias,
    STATION_ASR_DEPS=str(args.asr_deps.resolve()), STATION_SHERPA_DEPS=str(args.sherpa_deps.resolve()),
    STATION_R8_JAR=str(args.asr_deps.resolve() / 'build-tools/r8-9.5.32.jar'))
for app in (['phone', 'agent', 'voicebridge'] if args.app == 'all' else [args.app]):
    subprocess.run([sys.executable, str(ROOT / 'apps' / app / 'build.py')], env=environment, check=True)
