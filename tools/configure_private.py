"""Write a private deployment file, pinning restored inputs without printing them.

Fill NIC/tool fields in your own copy of deployment.example.json before running.
This deliberate enrollment step trusts only the explicit local backup you chose.
"""
import argparse
import hashlib
import json
from pathlib import Path
from cryptography import x509
from cryptography.hazmat.primitives.serialization import Encoding

from private_backup import outside, private_write

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--template', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
value = json.loads(outside(args.template).read_text(encoding='utf-8-sig'))
private = outside(value['privateDir'])
for name, relative in [('baselineSha256', 'pc/native-baseline.json'), ('policySha256', 'pc/config-policy.json')]:
    value[name] = hashlib.sha256((private / relative).read_bytes()).hexdigest()
certificate = x509.load_pem_x509_certificate((private / 'pc/fullchain.pem').read_bytes())
value['tlsLeafSha256'] = hashlib.sha256(certificate.public_bytes(Encoding.DER)).hexdigest()
private_write(args.output, (json.dumps(value, indent=2) + '\n').encode('utf-8'))
print('{"ok":true,"privateDeploymentWritten":true,"secretsPrinted":false}')
