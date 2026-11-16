"""Build only the fixed-path experimental backend DEX. Does not deploy or run it."""
import argparse
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--javac-home', type=Path)
    parser.add_argument('--android-parts', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    output = root.parent / 'build/backend'
    classes = output / 'classes'
    dex = output / 'dex'
    classes.mkdir(parents=True, exist_ok=True)
    dex.mkdir(parents=True, exist_ok=True)
    java = args.java_home / 'bin/java.exe'
    javac = (args.javac_home or args.java_home) / 'bin/javac.exe'
    subprocess.run([str(javac), '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                    '-Xlint:all', '-d', str(classes), str(root / 'RingBrightnessBackend.java')],
                   check=True)
    subprocess.run([str(java), '-cp', str(args.android_parts / 'build-tools/d8.jar'),
                    'com.android.tools.r8.D8', '--min-api', '28', '--release',
                    '--lib', str(args.android_parts / 'platform/android.jar'),
                    '--output', str(dex),
                    *[str(path) for path in sorted(classes.glob('*.class'))]], check=True)
    print(dex / 'classes.dex')


if __name__ == '__main__':
    main()
