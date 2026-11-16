"""Host safety contracts only; does not invoke NDK, networking or a device."""
import importlib.util
from pathlib import Path
import unittest

MODULE = Path(__file__).resolve().parents[1] / 'build.py'
SPEC = importlib.util.spec_from_file_location('native_tun_source_build', MODULE)
BUILD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILD)


class ElfImportContract(unittest.TestCase):
    def setUp(self):
        self.valid = '''ELF Header:
  Class:                             ELF64
  Machine:                           AArch64
  0x0000000000000001 (NEEDED) Shared library: [libc.so]
  0x0000000000000001 (NEEDED) Shared library: [libm.so]
    1: 0000000000000000 0 FUNC GLOBAL DEFAULT UND poll@LIBC (2)
'''

    def test_only_expected_android_system_libraries(self):
        self.assertEqual(BUILD.inspect_elf(self.valid), ['libc.so', 'libm.so'])
        with self.assertRaises(ValueError):
            BUILD.inspect_elf(self.valid + 'Shared library: [libunknown.so]\n')
        with self.assertRaises(ValueError):
            BUILD.inspect_elf(self.valid.replace('Shared library:', 'Not a needed library:'))

    def test_wrong_architecture_and_runtime_rejected(self):
        for invalid in [
            self.valid.replace('ELF64', 'ELF32'),
            self.valid.replace('AArch64', 'ARM'),
            self.valid.replace('AArch64', 'Advanced Micro Devices X86-64'),
            self.valid.replace('@LIBC', '@GLIBC_2.17'),
            self.valid.replace('@LIBC', '@LIBC_Q'),
            self.valid.replace('@LIBC', '@LIBC_R'),
            '',
        ]:
            with self.subTest(header=invalid[-80:]), self.assertRaises(ValueError):
                BUILD.inspect_elf(invalid)


class CompilerApiContract(unittest.TestCase):
    def test_requires_preprocessor_expanded_api28(self):
        BUILD.verify_api_expansion('\n28\n')
        for invalid in ('29', '__ANDROID_API__', '__ANDROID_MIN_SDK_VERSION__', '', '28\n29'):
            with self.subTest(expansion=invalid), self.assertRaises(ValueError):
                BUILD.verify_api_expansion(invalid)


class PublicationGate(unittest.TestCase):
    def test_ci_uploads_reports_only_until_complete_notice_audit(self):
        workflow = MODULE.parents[2] / '.github/workflows/native-tun2socks.yml'
        lines = workflow.read_text(encoding='utf8').splitlines()
        self.assertEqual(sum('uses: actions/upload-artifact@' in line for line in lines), 1)
        starts = [index for index, line in enumerate(lines) if line.strip() == 'path: |']
        self.assertEqual(len(starts), 1)
        start = starts[0]
        indent = len(lines[start]) - len(lines[start].lstrip())
        uploaded = []
        for line in lines[start + 1:]:
            if line.strip() and len(line) - len(line.lstrip()) <= indent:
                break
            if line.strip():
                uploaded.append(line.strip())
        self.assertEqual(set(uploaded), {
            'experimental/native-tun2socks/dist/BUILD-RECORD.json',
            'experimental/native-tun2socks/dist/elf-imports.txt',
            'experimental/native-tun2socks/dist/THIRD-PARTY-NOTICE-INPUTS.txt',
        })
        self.assertEqual(len(uploaded), 3)
        self.assertTrue(all('*' not in path and not path.endswith(('/', '.so')) for path in uploaded))


if __name__ == '__main__':
    unittest.main()
