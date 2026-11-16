"""Notice retention and refusal contracts; only temporary source fixtures."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

MODULE = Path(__file__).resolve().parents[1] / 'notice_bundle.py'
SPEC = importlib.util.spec_from_file_location('source_notice_bundle', MODULE)
BUNDLE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUNDLE)


class NoticeBundleContract(unittest.TestCase):
    def test_retains_all_file_notices_inheritance_and_exact_source_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            source, output = base / 'source', base / 'out'
            (source / 'src').mkdir(parents=True)
            (source / 'include').mkdir()
            first = b'/* Copyright (c) Fixture One. Redistribution permitted. */\nint value;\n'
            second = b'/* Copyright (c) Fixture Two. */\nint api;\n/* Copyright (c) Fixture Three. */\n'
            (source / 'src/file.c').write_bytes(first)
            (source / 'include/file.h').write_bytes(second)
            (source / 'LICENSE').write_text('Fixture inherited license terms\n', encoding='utf8')
            (source / 'Android.mk').write_text('# Fixture build input\n', encoding='utf8')
            with patch.object(BUNDLE, 'MODULES', (('fixture', 'src', 'include', 'LICENSE', 1),)), \
                    patch.object(BUNDLE, 'GRAPH_INPUTS', ('Android.mk',)), \
                    patch.object(BUNDLE, 'EXPECTED_FILE_COUNT', 2):
                result = BUNDLE.write_bundle(source, output, {'fixture': True})
            self.assertEqual(result['compiledCounts'], {'fixture': 1})
            self.assertEqual(len(result['notices']), 3)
            self.assertFalse(result['completeLicenseAudit'])
            self.assertEqual(result['files']['include/file.h']['inheritedLicense'], 'LICENSE')
            notices = (output / 'THIRD-PARTY-NOTICE-INPUTS.txt').read_text(encoding='utf8')
            for expected in ('Fixture One', 'Fixture Two', 'Fixture Three', 'Fixture inherited license terms'):
                self.assertIn(expected, notices)
            with zipfile.ZipFile(output / 'third-party-source-snapshot.zip') as archive:
                self.assertEqual(archive.read('hev-source/src/file.c'), first)
                self.assertEqual(archive.read('hev-source/include/file.h'), second)
                self.assertEqual(json.loads(archive.read('source-lock.json')), {'fixture': True})

    def test_rejects_changed_translation_unit_scope_and_escaping_paths(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary)
            (source / 'src').mkdir()
            with patch.object(BUNDLE, 'MODULES', (('fixture', 'src', 'src', 'LICENSE', 1),)):
                with self.assertRaisesRegex(ValueError, 'translation-unit count'):
                    BUNDLE.collect(source)
            with self.assertRaises(ValueError):
                BUNDLE.checked(source, '../outside-source.txt')


if __name__ == '__main__':
    unittest.main()
