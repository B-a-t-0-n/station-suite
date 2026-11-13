"""Refresh the public source manifest without copying from a private workspace."""
import hashlib
import json
from pathlib import Path

from export_sources import EXCLUDED_NAMES, SOURCE_EXTENSIONS

ROOT = Path(__file__).resolve().parents[1]
files = []
for path in sorted((ROOT / 'apps').rglob('*')):
    if not path.is_file() or path.suffix.lower() not in SOURCE_EXTENSIONS: continue
    if set(path.relative_to(ROOT / 'apps').parts) & (EXCLUDED_NAMES | {'classes'}): continue
    data = path.read_bytes()
    if path.suffix.lower() not in {'.png', '.webp', '.gif', '.wav', '.ogg'}:
        data = data.replace(b'\r\n', b'\n')
    files.append({'path': path.relative_to(ROOT).as_posix(), 'sha256': hashlib.sha256(data).hexdigest()})
(ROOT / 'config/source-snapshot.json').write_text(json.dumps({
    'hashEncoding': 'Git-normalized LF for text; raw bytes for binary',
    'files': files}, indent=2) + '\n', encoding='utf-8', newline='\n')
print(json.dumps({'sourceFiles': len(files), 'buildArtifactsIncluded': False}))
