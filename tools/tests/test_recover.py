"""Exercise pinned recovery against fake ADB only; no Station, subprocess or network runs."""
import base64
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import shlex
import sys
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import recover
from test_install_model import FakeAdb


class RecoveryAdb(FakeAdb):
    def __init__(self, apk_identities, corrupt_private=False, corrupt_launcher=False):
        super().__init__()
        self.files = {}; self.dirs = {'/data', '/data/local', '/data/local/tmp'}
        self.identities = apk_identities
        self.corrupt_private = corrupt_private
        self.corrupt_launcher = corrupt_launcher
        self.operations, self.private_uploads = [], []
        self.fingerprint = recover.FINGERPRINT
        self.system_packages = 'package:ru.yandex.quasar.app\n'

    def run(self, args, **kwargs):
        self.operations.append(list(args))
        if args[1:3] == ['dump', 'badging']:
            name, version, split = self.identities[args[-1]]
            text = "package: name='%s' versionCode='%s'" % (name, version)
            if split: text += " split='%s'" % split
            return SimpleNamespace(returncode=0, stdout=text.encode())
        if args[3] in ('install', 'install-multiple'):
            return SimpleNamespace(returncode=0, stdout=b'Success')
        if args[3:5] == ['shell', '-T']:
            payload = kwargs['input']
            self.private_uploads.append(payload)
            assert all(byte < 128 and byte != 26 for byte in payload)
            command = shlex.split(args[5])
            assert command[:4] == ['run-as', recover.VOICE, 'sh', '-c']
            script = shlex.split(command[4])
            assert script[:7] == ['umask', '077', '&&', 'base64', '-d', '>', script[6]]
            target = script[6]
            assert target.rsplit('/', 1)[0] in self.dirs
            self.files[target] = base64.b64decode(payload, validate=True) + (b'bad' if self.corrupt_private else b'')
            return SimpleNamespace(returncode=0, stdout=b'')
        command = shlex.split(args[4]) if args[3] == 'shell' else []
        if command[:1] == ['getprop']:
            value = {'ro.build.fingerprint': self.fingerprint, 'ro.build.version.sdk': '28', 'ro.product.cpu.abi': 'arm64-v8a'}[command[1]]
            return SimpleNamespace(returncode=0, stdout=(value + '\n').encode())
        if command == ['id']: return SimpleNamespace(returncode=0, stdout=b'uid=2000(shell) gid=2000(shell)\n')
        if command == ['getenforce']: return SimpleNamespace(returncode=0, stdout=b'Enforcing\n')
        if command == ['pm', 'list', 'packages', '-s']: return SimpleNamespace(returncode=0, stdout=self.system_packages.encode())
        if command == ['cmd', 'package', 'set-home-activity', recover.LAUNCHER + '/.MainActivity']:
            return SimpleNamespace(returncode=0, stdout=b'Success\n')
        if command == ['run-as', recover.VOICE, 'pwd']: return SimpleNamespace(returncode=0, stdout=b'/data/user/0/local.station.voicebridge\n')
        normalized = ['fake-adb', '-s', 'station', *args[3:]]
        if kwargs.get('input') is None: kwargs.pop('input', None)
        result = super().run(normalized, **kwargs)
        if self.corrupt_launcher and args[3] == 'push' and args[-1].startswith(recover.LAUNCHER_ROOT): self.files[args[-1]] += b'bad'
        return result


class RecoverTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'offline-fixture')])
        now = datetime.now(timezone.utc)
        certificate = x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key()).serial_number(1).not_valid_before(now - timedelta(days=1)).not_valid_after(now + timedelta(days=1)).sign(key, hashes.SHA256())
        cls.password = b'fixture-password'
        cls.p12 = pkcs12.serialize_key_and_certificates(b'fixture', key, certificate, None, serialization.BestAvailableEncryption(cls.password))

    def setUp(self):
        self.temp = TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name); self.checkout = self.home / 'checkout'; self.checkout.mkdir()
        (self.checkout / 'config').mkdir()
        self.model = self.home / 'model'; self.model.mkdir()
        model = self.model / 'encoder.int8.onnx'; model.write_bytes(b'model\x1a\xff\x00end')
        manifest = {'files': [{'path': 'model/' + model.name, 'bytes': model.stat().st_size, 'sha256': self.sha(model)}]}
        (self.checkout / 'config/sherpa-downloads.json').write_text(json.dumps(manifest))
        self.adb = self.home / 'adb.exe'; self.adb.write_bytes(b'fixture')
        self.aapt = self.home / 'aapt2.exe'; self.aapt.write_bytes(b'fixture')
        self.identities = {}; self.apps = []
        for role, package, version in [('agent', 'local.station.agent', 18), ('voicebridge', recover.VOICE, 22)]:
            path = self.home / (role + '.apk'); path.write_bytes(role.encode())
            self.identities[str(path)] = (package, version, '')
            self.apps.append({'role': role, 'package': package, 'versionCode': version, 'apk': str(path), 'sha256': self.sha(path)})
        data = {'files/config.json': b'{"lab_proxy_port":18443,"gigachat_authorization_key":"fixture","speechify_api_key":"fixture"}',
                'files/native-sync.json': b'{"status":"ok","config":{"account_config":{},"device_config":{},"system_config":{"uniProxyUrl":"ws://127.0.0.1:17682/uni.ws","adbd":{"remoteActivation":true,"network":false}}}}',
                'files/music-config.json': b'{"version":1,"vk":{"access_token":"fixture"},"audioHosts":[],"transport":"tunnel"}',
                'files/native-tls.p12': self.p12, 'files/native-tls-password.txt': self.password}
        self.private_files = []
        for index, (target, payload) in enumerate(data.items()):
            source = self.home / ('private-' + str(index)); source.write_bytes(payload)
            self.private_files.append({'target': target, 'source': str(source), 'sha256': self.sha(source)})
        self.value = {'format': 1, 'adb': str(self.adb), 'aapt2': str(self.aapt), 'serial': 'station',
            'firmware': {'fingerprint': recover.FINGERPRINT, 'sdk': 28, 'abi': 'arm64-v8a'},
            'apps': self.apps, 'modelDir': str(self.model), 'privateFiles': self.private_files, 'startServices': True}
        self.profile = self.home / 'recovery.json'

    @staticmethod
    def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()

    def run_recovery(self, fake, apply=False):
        self.profile.write_text(json.dumps(self.value))
        with patch.object(recover, 'ROOT', self.checkout), patch.object(recover.subprocess, 'run', side_effect=fake.run), patch('builtins.print') as output:
            result = recover.main(['--profile', str(self.profile)] + (['--apply'] if apply else []))
        return result, output

    def test_default_preflight_reads_only_and_prints_no_private_material(self):
        fake = RecoveryAdb(self.identities); result, output = self.run_recovery(fake)
        self.assertEqual(result, 0)
        for args in fake.operations:
            if args[0] == str(self.adb):
                self.assertIn(shlex.split(args[4])[0], ('getprop', 'id', 'getenforce', 'pm'))
        self.assertEqual(fake.private_uploads, [])
        text = str(output.call_args_list)
        self.assertNotIn(str(self.profile), text); self.assertNotIn(self.password.decode(), text)

    def test_bad_private_hash_blocks_all_device_calls(self):
        self.private_files[-1]['sha256'] = '0' * 64
        fake = RecoveryAdb(self.identities); result, output = self.run_recovery(fake, True)
        self.assertEqual(result, 1)
        self.assertFalse(any(args[0] == str(self.adb) for args in fake.operations))
        self.assertIn('LOCAL_FILE_HASH_MISMATCH', str(output.call_args_list))

    def test_unknown_firmware_or_system_apk_blocks_apply(self):
        for mode in ('firmware', 'system'):
            fake = RecoveryAdb(self.identities)
            if mode == 'firmware': fake.fingerprint = 'new firmware'
            else: fake.system_packages += 'package:local.station.agent\n'
            result, _ = self.run_recovery(fake, True)
            self.assertEqual(result, 1)
            self.assertFalse(any(args[3] in ('install', 'install-multiple') for args in fake.operations if args[0] == str(self.adb)))

    def test_experimental_wifi_and_embedded_profile_secrets_are_rejected(self):
        for change in ('wifi', 'secret'):
            original = json.loads(json.dumps(self.value))
            if change == 'wifi':
                self.value['apps'][1]['package'] = 'local.station.wifi'
            else:
                self.value['apiKey'] = 'must-not-print-this'
            fake = RecoveryAdb(self.identities); result, output = self.run_recovery(fake, True)
            self.assertEqual(result, 1)
            self.assertFalse(any(args[0] == str(self.adb) for args in fake.operations))
            self.assertNotIn('must-not-print-this', str(output.call_args_list))
            self.value = original

    def test_apply_preserves_binary_secrets_and_starts_only_after_provision(self):
        fake = RecoveryAdb(self.identities); result, output = self.run_recovery(fake, True)
        self.assertEqual(result, 0)
        for entry in self.private_files: self.assertEqual(fake.files[entry['target']], Path(entry['source']).read_bytes())
        self.assertEqual(fake.files['files/local-sherpa-model/encoder.int8.onnx'], (self.model / 'encoder.int8.onnx').read_bytes())
        self.assertFalse(any('.station-' in name or '/station-model-' in name for name in fake.files))
        starts = [i for i, args in enumerate(fake.operations) if args[3] == 'shell' and shlex.split(args[4])[:2] in (['am', 'broadcast'], ['am', 'start'])]
        last_private_upload = max(i for i, args in enumerate(fake.operations) if args[3:5] == ['shell', '-T'])
        self.assertTrue(starts and min(starts) > last_private_upload)
        self.assertTrue(all('svc' not in args and 'reboot' not in args and 'uninstall' not in args for args in fake.operations))
        self.assertNotIn(self.password.decode(), str(output.call_args_list))

    def test_private_hash_failure_cleans_stage_without_starting_services(self):
        fake = RecoveryAdb(self.identities, corrupt_private=True); result, output = self.run_recovery(fake, True)
        self.assertEqual(result, 1)
        self.assertIn('PRIVATE_REMOTE_HASH_MISMATCH', str(output.call_args_list))
        self.assertFalse(any('.station-recovery-' in path for path in fake.files | {d: b'' for d in fake.dirs}))
        self.assertFalse(any(args[3] == 'shell' and shlex.split(args[4])[:2] in (['am', 'broadcast'], ['am', 'start']) for args in fake.operations if args[0] == str(self.adb)))

    def test_tv_splits_require_unique_names_and_matching_pins(self):
        entries = []
        for name, split in [('base', ''), ('arm64', 'config.arm64_v8a')]:
            path = self.home / (name + '.apk'); path.write_bytes(name.encode())
            self.identities[str(path)] = ('example.video.tv', 12, split)
            entries.append({'path': str(path), 'sha256': self.sha(path)})
        self.apps.append({'role': 'tv', 'package': 'example.video.tv', 'versionCode': 12, 'apks': entries})
        fake = RecoveryAdb(self.identities); result, _ = self.run_recovery(fake, True)
        self.assertEqual(result, 0)
        self.assertTrue(any(args[3] == 'install-multiple' for args in fake.operations if args[0] == str(self.adb)))
        self.identities[entries[1]['path']] = ('example.video.tv', 12, '')
        result, _ = self.run_recovery(RecoveryAdb(self.identities), True)
        self.assertEqual(result, 1)

    def add_launcher(self):
        path = self.home / 'launcher.apk'; path.write_bytes(b'launcher')
        self.identities[str(path)] = (recover.LAUNCHER, 13, '')
        self.apps.append({'role': 'launcher', 'package': recover.LAUNCHER, 'versionCode': 13, 'apk': str(path), 'sha256': self.sha(path)})
        self.value['launcherFiles'] = []
        for index, (target, payload) in enumerate([
            ('config.json', b'{}'), ('themes/index.json', b'{"themes":[]}'),
            ('themes/.profiles/p1/config.json', b'{}'), ('themes/stockholm/config.json', b'{}'),
            ('themes/stockholm/wallpapers/night.jpg', b'picture\x1a\x00\xff'), ('themes/active', b'p1\nstockholm\n')]):
            source = self.home / ('launcher-asset-' + str(index)); source.write_bytes(payload)
            self.value['launcherFiles'].append({'target': target, 'source': str(source), 'sha256': self.sha(source)})

    def test_launcher_assets_are_pinned_and_published_only_below_fixed_root(self):
        self.add_launcher()
        fake = RecoveryAdb(self.identities); result, _ = self.run_recovery(fake, True)
        self.assertEqual(result, 0)
        for entry in self.value['launcherFiles']:
            self.assertEqual(fake.files[recover.LAUNCHER_ROOT + '/' + entry['target']], Path(entry['source']).read_bytes())
        moves = [c for c in fake.commands if c[0] == 'mv' and c[-1].startswith(recover.LAUNCHER_ROOT)]
        self.assertEqual(moves[-1][-1], recover.LAUNCHER_ROOT + '/themes/active')
        self.assertFalse(any('/.station-recovery-' in name for name in fake.files))
        commands = [shlex.split(args[4]) for args in fake.operations if args[0] == str(self.adb) and args[3] == 'shell' and args[4] != '-T']
        self.assertIn(['cmd', 'package', 'set-home-activity', recover.LAUNCHER + '/.MainActivity'], commands)
        self.assertEqual(commands[-1], ['am', 'start', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME'])

    def test_launcher_traversal_or_hash_mismatch_blocks_all_device_changes(self):
        self.add_launcher(); original = json.loads(json.dumps(self.value['launcherFiles']))
        for mode in ('traversal', 'hash', 'missing-profile'):
            self.value['launcherFiles'] = json.loads(json.dumps(original))
            if mode == 'traversal': self.value['launcherFiles'][0]['target'] = 'themes/../../elsewhere'
            elif mode == 'hash': self.value['launcherFiles'][0]['sha256'] = '0' * 64
            else: self.value['launcherFiles'] = [v for v in self.value['launcherFiles'] if '/.profiles/' not in v['target']]
            fake = RecoveryAdb(self.identities); result, _ = self.run_recovery(fake, True)
            self.assertEqual(result, 1)
            self.assertFalse(any(args[0] == str(self.adb) for args in fake.operations))

    def test_launcher_remote_corruption_cleans_stage_and_does_not_publish_pointer(self):
        self.add_launcher(); fake = RecoveryAdb(self.identities, corrupt_launcher=True)
        result, output = self.run_recovery(fake, True)
        self.assertEqual(result, 1)
        self.assertIn('LAUNCHER_REMOTE_HASH_MISMATCH', str(output.call_args_list))
        self.assertFalse(any('.station-recovery-' in name for name in fake.files | {d: b'' for d in fake.dirs}))
        self.assertNotIn(recover.LAUNCHER_ROOT + '/themes/active', fake.files)


if __name__ == '__main__': unittest.main()
