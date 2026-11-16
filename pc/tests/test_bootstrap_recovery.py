"""Factory-reset bootstrap tests; fake processes/listeners, no device or NIC actions."""
from contextlib import redirect_stdout
from datetime import datetime, timedelta, timezone
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import bootstrap_recovery as m


class Child:
    def __init__(self, arguments, output, pid):
        self.arguments, self.output, self.pid = arguments, output, pid
        self.waited = self.terminated = self.killed = False
        self.returncode = None
    def poll(self): return self.returncode
    def wait(self, timeout=None): self.waited = True; return 0
    def terminate(self): self.terminated = True
    def kill(self): self.killed = True


class BootstrapTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from cryptography import x509
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        name = x509.Name([x509.NameAttribute(x509.NameOID.COMMON_NAME, 'bootstrap-fixture')])
        now = datetime.now(timezone.utc)
        cert = x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key()).serial_number(1).not_valid_before(now - timedelta(days=1)).not_valid_after(now + timedelta(days=1)).sign(key, hashes.SHA256())
        cls.cert = cert.public_bytes(serialization.Encoding.PEM)
        cls.key = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
        cls.cert_pin = m.sha(cert.public_bytes(serialization.Encoding.DER))

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.private = Path(temporary.name); pc = self.private / 'pc'; pc.mkdir()
        self.source = self.private / 'deployment.json'
        self.python = self.private / 'python.exe'; self.python.write_bytes(b'fixture-python')
        self.dhcp = self.private / 'dhcp.exe'; self.dhcp.write_bytes(b'fixture-dhcp')
        self.value = dict(format=1, privateDir=str(self.private), python=str(self.python),
            labIp='192.168.77.1', voiceIp='192.168.77.2', stationIp='192.168.77.100',
            dhcpExecutable=str(self.dhcp), dhcpSha256=m.sha(self.dhcp.read_bytes()), tlsLeafSha256=self.cert_pin)
        self.policy = {'check_updates_payload': {'status':'ok', 'hasUpdate':False, 'hasCriticalUpdate':False}}
        self.baseline = {'status':'ok', 'config':{'account_config':{'fixture':'private'}, 'device_config':{},
            'system_config':{'uniProxyUrl':'ws://127.0.0.1:17682/uni.ws', 'adbd':{'remoteActivation':True,'network':False}}}}
        (pc / 'fullchain.pem').write_bytes(self.cert); (pc / 'quasar-all.key').write_bytes(self.key)
        self.write_inputs()
        self.children = []; self.clock = 0
        self.addCleanup(patch.stopall)
        patch.object(m.lifecycle, 'protect_directory').start()
        patch.object(m.lifecycle, '_restrict_file').start()
        self.processes = patch.object(m.subprocess, 'Popen', side_effect=self.spawn).start()
        self.listeners = patch.object(m.production, 'check_listeners').start()
        self.output = io.StringIO()
        self.store = self.private / 'state/recovery-bootstrap'

    def write_inputs(self):
        for name, value, pin in [('config-policy.json', self.policy, 'policySha256'), ('native-baseline.json', self.baseline, 'baselineSha256')]:
            data = m.lifecycle.encoded(value); (self.private / 'pc' / name).write_bytes(data)
            self.value[pin] = m.sha(data)
        self.source.write_bytes(m.lifecycle.encoded(self.value))

    def spawn(self, arguments, **kwargs):
        child = Child(arguments, kwargs['stdout'], 1000 + len(self.children))
        self.children.append(child)
        if '--ready' in arguments:
            ready = Path(arguments[arguments.index('--ready') + 1])
            ready.write_text(json.dumps({'state':'ready', 'pid':child.pid, 'requireStationRelay':False}))
        return child

    def stop(self, seconds): (self.store / 'stop').touch()

    def launch(self, *, external=True, seconds=1800, sleep=None):
        value, policy, baseline = m.load(self.source, external_dhcp=external)
        with redirect_stdout(self.output), patch.object(m.time, 'sleep', side_effect=sleep or self.stop):
            m.run(value, policy, baseline, external_dhcp=external, seconds=seconds)

    def test_check_needs_no_adb_or_apks_and_is_read_only(self):
        before = {p.relative_to(self.private):p.read_bytes() for p in self.private.rglob('*') if p.is_file()}
        with redirect_stdout(self.output):
            result = m.main(['--deployment', str(self.source), '--check'])
        self.assertEqual(result, 0); self.processes.assert_not_called(); self.listeners.assert_not_called()
        self.assertEqual(before, {p.relative_to(self.private):p.read_bytes() for p in self.private.rglob('*') if p.is_file()})
        self.assertNotIn('private', self.output.getvalue()); self.assertNotIn('BEGIN', self.output.getvalue())

    def test_all_pins_and_native_adb_profile_validated_before_spawn(self):
        (self.private / 'pc/native-baseline.json').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, '^PRIVATE_CONFIG_HASH_MISMATCH$'): self.launch()
        self.write_inputs(); self.baseline['config']['system_config']['adbd']['network'] = True; self.write_inputs()
        with self.assertRaisesRegex(ValueError, '^BOOTSTRAP_NATIVE_PROFILE_INVALID$'): self.launch()
        self.baseline['config']['system_config']['adbd']['network'] = False
        self.baseline['config']['system_config']['uniProxyUrl'] = 'wss://uniproxy.alice.yandex.net/uni.ws'; self.write_inputs()
        with self.assertRaisesRegex(ValueError, '^BOOTSTRAP_NATIVE_PROFILE_INVALID$'): self.launch()
        self.processes.assert_not_called()

    def test_tls_mismatch_and_unpinned_dhcp_refused_but_external_dhcp_skips_tool(self):
        self.value['tlsLeafSha256'] = '0' * 64; self.write_inputs()
        with self.assertRaisesRegex(ValueError, '^PRIVATE_TLS_PIN_MISMATCH$'): self.launch()
        self.value['tlsLeafSha256'] = self.cert_pin; self.write_inputs(); self.dhcp.unlink()
        with self.assertRaisesRegex(ValueError, '^DHCP_HASH_MISMATCH$'): m.load(self.source)
        m.load(self.source, external_dhcp=True); self.processes.assert_not_called()

    def test_external_dhcp_has_only_local_server_and_preserves_original_policy(self):
        self.launch()
        self.assertEqual(len(self.children), 1)
        arguments = self.children[0].arguments
        self.assertIn('LocalConfigServer.py', ' '.join(arguments))
        self.assertFalse(any(flag in arguments for flag in ['--require-station-relay', '--station-relay']))
        self.assertFalse(any('adb' in word.lower() or 'supervisor' in word.lower() for word in arguments))
        policy = json.loads((self.store / 'policy.json').read_bytes())
        for key in ['local_registration_stub', 'local_oauth_stub', 'local_ntp_server', 'local_sync_payload']: self.assertIs(policy[key], True)
        self.assertIs(policy['google_https_passthrough'], False)
        self.assertEqual(json.loads((self.private / 'pc/config-policy.json').read_bytes()), self.policy)
        self.assertTrue(self.children[0].waited); self.assertFalse(self.children[0].terminated)
        self.assertTrue(self.children[0].output.closed); self.assertTrue((self.store / 'stop').exists())

    def test_default_dhcp_is_owned_and_bounded_deadline_stops_all_children(self):
        def advance(seconds): self.clock += seconds
        with patch.object(m.time, 'monotonic', side_effect=lambda:self.clock):
            self.launch(external=False, seconds=1, sleep=advance)
        self.assertEqual(len(self.children), 2)
        self.assertEqual(self.children[0].arguments, [str(self.dhcp)])
        self.assertIn('192.168.77.100', (self.store / 'dhcp/tftpd32.ini').read_text())
        self.assertTrue(all(c.waited and c.output.closed for c in self.children))
        self.assertGreaterEqual(self.clock, 1); self.assertLess(self.clock, 2)

    def test_production_lifetime_lock_and_foreign_listener_are_refused_without_spawn(self):
        shared = m.production.store_path(self.value); shared.mkdir(parents=True)
        with m.lifecycle.mutation_lock(shared, timeout=0):
            with self.assertRaisesRegex(ValueError, '^ANOTHER_PC_OWNER_IS_RUNNING$'): self.launch()
        self.listeners.side_effect = ValueError('PORT_ALREADY_IN_USE')
        with self.assertRaisesRegex(ValueError, '^PORT_ALREADY_IN_USE$'): self.launch()
        self.processes.assert_not_called(); self.assertFalse((self.store / 'stop').exists())

    def test_readiness_mismatch_and_ctrl_c_clean_only_owned_children(self):
        original = self.spawn
        def mismatch(arguments, **kwargs):
            child = original(arguments, **kwargs)
            if '--ready' in arguments: (self.store / 'ready.json').write_text('{"state":"ready","pid":999,"requireStationRelay":false}')
            return child
        self.processes.side_effect = mismatch
        with self.assertRaisesRegex(ValueError, '^BOOTSTRAP_READINESS_INVALID$'): self.launch()
        self.assertTrue(self.children[0].waited); self.assertTrue(self.children[0].output.closed)
        (self.store / 'stop').unlink(); (self.store / 'ready.json').unlink()
        self.processes.side_effect = original
        def interrupted(_): raise KeyboardInterrupt()
        with self.assertRaises(KeyboardInterrupt): self.launch(sleep=interrupted)
        self.assertTrue(all(c.waited and c.output.closed for c in self.children))

    def test_existing_stop_and_invalid_duration_cannot_start_children(self):
        self.store.mkdir(parents=True); (self.store / 'stop').touch()
        with self.assertRaisesRegex(ValueError, '^REMOVE_BOOTSTRAP_STOP_FILE_BEFORE_START$'): self.launch()
        with self.assertRaisesRegex(ValueError, '^DURATION_MUST_BE_1_TO_3600_SECONDS$'): self.launch(seconds=3601)
        self.processes.assert_not_called()


if __name__ == '__main__': unittest.main()
