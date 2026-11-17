"""Bound one scheduler check; never terminate the independent runtime owner."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import sys
import tempfile

CHECK_SECONDS = 25
MAX_RESULT_BYTES = 16384
HIDDEN = getattr(subprocess, 'CREATE_NO_WINDOW', 0)


def check(arguments, *, timeout=CHECK_SECONDS):
    # Files keep inherited stdout handles from extending a PIPE communicate wait.
    # Only the immediate --ensure check is owned here, never its runtime child.
    with tempfile.TemporaryFile() as output, tempfile.TemporaryFile() as errors:
        try:
            child = subprocess.Popen(arguments, stdin=subprocess.DEVNULL,
                                     stdout=output, stderr=errors, creationflags=HIDDEN)
        except OSError:
            return {'ok': False, 'code': 'KEEPALIVE_CHECK_START_FAILED'}, 1
        try:
            child.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            try:
                child.kill()
                child.wait(timeout=2)
            except (OSError, subprocess.TimeoutExpired):
                return {'ok': False, 'code': 'KEEPALIVE_CHECK_STOP_UNCONFIRMED'}, 1
            return {'ok': False, 'code': 'KEEPALIVE_CHECK_TIMEOUT'}, 1
        output.seek(0)
        raw = output.read(MAX_RESULT_BYTES + 1)
        try:
            if len(raw) > MAX_RESULT_BYTES:
                raise ValueError()
            result = json.loads(raw)
            if type(result) is not dict or type(result.get('ok')) is not bool:
                raise ValueError()
        except (ValueError, UnicodeError):
            return {'ok': False, 'code': 'KEEPALIVE_CHECK_RESULT_INVALID'}, 1
        return result, 0 if child.returncode == 0 and result['ok'] else 1


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--deployment', type=Path, required=True)
    parser.add_argument('--external-dhcp', action='store_true')
    args = parser.parse_args(argv)
    command = [sys.executable, '-B', str(Path(__file__).with_name('start.py')),
               '--deployment', str(args.deployment.resolve()), '--ensure']
    if args.external_dhcp:
        command.append('--external-dhcp')
    result, code = check(command)
    print(json.dumps(result))
    return code


if __name__ == '__main__':
    raise SystemExit(main())
