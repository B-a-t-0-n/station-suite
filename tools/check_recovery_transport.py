"""Read-only ownership/listener check before restoring Station applications."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'pc'))
import start as transport


def check(value, deployment, *, external_dhcp=False):
    if transport.owner_status(value, deployment, external_dhcp=external_dhcp)['running']:
        raise ValueError('RECOVERY_PC_OWNER_STILL_RUNNING')
    transport.check_shared_owners(value)
    transport.check_listeners(value)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--deployment', type=Path, required=True)
    parser.add_argument('--external-dhcp', action='store_true')
    args = parser.parse_args(argv)
    try:
        value = transport.load(args.deployment)
        check(value, args.deployment, external_dhcp=args.external_dhcp)
        print(json.dumps({'ok': True, 'transportIdle': True})); return 0
    except Exception as error:
        code = str(error) if isinstance(error, ValueError) and re.fullmatch('[A-Z_]{1,64}', str(error)) else 'RECOVERY_TRANSPORT_CHECK_FAILED'
        print(json.dumps({'ok': False, 'code': code})); return 1


if __name__ == '__main__': raise SystemExit(main())
