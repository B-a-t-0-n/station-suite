"""Preserve notices from the pinned Android.mk translation-unit/header closure.

This inventories declared notices, not authorship or relicensing authority.
Public binary publication remains a separate unresolved gate.
"""
import hashlib
import json
from pathlib import Path
import re
import zipfile

MODULES = (
    ('tunnel+core', 'src', 'src', 'LICENSE', 31),
    ('yaml', 'third-part/yaml/src', 'third-part/yaml/include', 'third-part/yaml/LICENSE', 8),
    ('lwip', 'third-part/lwip/src', 'third-part/lwip/src', 'third-part/lwip/LICENSE', 95),
    ('task', 'third-part/hev-task-system/src', 'third-part/hev-task-system/include',
     'third-part/hev-task-system/LICENSE', 50),
)
GRAPH_INPUTS = ('Android.mk', 'Application.mk', 'build.mk',
                'third-part/yaml/Android.mk', 'third-part/yaml/build.mk', 'third-part/yaml/configs.mk',
                'third-part/lwip/Android.mk', 'third-part/lwip/build.mk',
                'third-part/hev-task-system/Android.mk', 'third-part/hev-task-system/build.mk',
                'third-part/hev-task-system/configs.mk')
EXPECTED_FILE_COUNT = 458
COMMENT = re.compile(r'/\*.*?\*/|(?m:^\s*//.*$)', re.S)
NOTICE = re.compile(r'copyright|SPDX-License|redistribution|permission is hereby|public domain|'
                    r'GNU General|Apache License|Lesser|licen[cs]ed under', re.I)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def checked(source, relative):
    path = source / relative
    if not path.is_file() or path.is_symlink() or not path.resolve().is_relative_to(source.resolve()):
        raise ValueError('Unsafe/missing notice input: ' + relative)
    return path


def collect(source):
    source = Path(source)
    files, notices, counts = {}, {}, {}
    for module, src, headers, inherited, expected in MODULES:
        units = sorted(p for p in (source / src).rglob('*')
                       if p.is_file() and p.name.endswith(('.c', '.S')))
        if len(units) != expected:
            raise ValueError('Android.mk translation-unit count differs: ' + module)
        counts[module] = len(units)
        inputs = {p for tree in {src, headers} for p in (source / tree).rglob('*')
                  if p.is_file() and p.suffix.lower() in {'.c', '.s', '.h', '.inc', '.inl'}}
        for path in sorted(inputs):
            relative = path.relative_to(source).as_posix()
            checked(source, relative)
            inherited_path = 'src/core/LICENSE' if relative.startswith('src/core/') else inherited
            notice_ids = []
            for match in COMMENT.finditer(path.read_text(encoding='utf8')):
                text = match.group(0)
                if not NOTICE.search(text):
                    continue
                key = hashlib.sha256(text.encode('utf8')).hexdigest()
                notice_ids.append(key)
                item = notices.setdefault(key, {'text': text, 'files': []})
                item['files'].append(relative)
            files[relative] = {'sha256': sha(path), 'module': module,
                               'translationUnit': path in units,
                               'inheritedLicense': inherited_path, 'noticeIds': notice_ids}
    if len(files) != EXPECTED_FILE_COUNT:
        raise ValueError('Pinned source/header closure differs from reviewed 458 files')
    licenses = sorted({row['inheritedLicense'] for row in files.values()})
    all_inputs = sorted(set(files) | set(licenses) | set(GRAPH_INPUTS))
    for relative in all_inputs:
        checked(source, relative)
    return {'scope': 'pinned Android.mk recursive *.c/*.S plus conservative source/header closure',
            'compiledCounts': counts, 'files': files, 'licenses': {p: sha(source / p) for p in licenses},
            'buildGraph': {p: sha(source / p) for p in GRAPH_INPUTS}, 'notices': notices,
            'declaredNoticeInventoryComplete': True, 'completeLicenseAudit': False,
            'unresolved': ['rbtree upstream provenance/relicensing clarification',
                           'libyaml upstream attribution reconciliation',
                           'NDK static runtime notice closure before public binary publication']}


def write_bundle(source, output, lock):
    source, output = Path(source), Path(output)
    inventory = collect(source)
    output.mkdir(parents=True, exist_ok=True)
    (output / 'SOURCE-NOTICE-INVENTORY.json').write_text(json.dumps(inventory, indent=2) + '\n', encoding='utf8')
    notice_text = ['Declared source notice inventory; public binary audit remains incomplete.\n',
                   'This preserves file notices in addition to inherited component licenses.\n']
    for path in inventory['licenses']:
        notice_text.extend(['\n===== ' + path + ' =====\n', (source / path).read_text(encoding='utf8')])
    for path in GRAPH_INPUTS:
        # Android make scripts have their own Apache notices. Preserve them verbatim.
        notice_text.extend(['\n===== Build script: ' + path + ' =====\n', (source / path).read_text(encoding='utf8')])
    for key, item in sorted(inventory['notices'].items()):
        notice_text.extend(['\n===== Source notice ' + key + ' =====\n',
                            '\n'.join(item['files']) + '\n', item['text'] + '\n'])
    (output / 'THIRD-PARTY-NOTICE-INPUTS.txt').write_text(''.join(notice_text), encoding='utf8')
    # Kept local, not uploaded by the public workflow: exact input bytes including
    # all file comments, even notices outside the inventory extraction pattern.
    paths = sorted(set(inventory['files']) | set(inventory['licenses']) | set(GRAPH_INPUTS))
    with zipfile.ZipFile(output / 'third-party-source-snapshot.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        for relative in paths:
            archive.write(checked(source, relative), 'hev-source/' + relative)
        archive.writestr('source-lock.json', json.dumps(lock, indent=2) + '\n')
        archive.writestr('SCOPE.txt', 'Pinned Android.mk input snapshot; no Git history or NDK runtime.\n'
                          'Not a complete public-release license review or complete corresponding-source claim.\n')
    return inventory
