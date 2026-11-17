"""PC owner/keepalive tests: fake child processes, ADB and sockets; no Station or NIC changes."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import start as m


class FakeChild:
    def __init__(self, arguments, output=None):
        self.arguments, self.output = arguments, output
        self.waited = self.terminated = self.killed = False
    def poll(self): return None
    def wait(self, timeout=None): self.waited = True; return 0
    def terminate(self): self.terminated = True
    def kill(self): self.killed = True


class PcStartTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.private = Path(self.temp.name)
        self.profile = self.private / 'deployment.json'; self.profile.write_text('{"fixture":true}')
        self.python = self.private / 'python.exe'; self.python.write_bytes(b'fixture-python')
        self.value = {'privateDir': str(self.private), 'python': str(self.python),
                      'dhcpExecutable': 'fixture-dhcp.exe', 'labIp': '192.0.2.1',
                      'voiceIp': '192.0.2.2', 'stationIp': '192.0.2.100', 'adb': 'fake-adb',
                      'serial': 'fixture', 'agentApk': 'fixture-agent.apk'}
        self.store = m.store_path(self.value)
        self.children = []
        self.popen = patch.object(m.subprocess, 'Popen', side_effect=self.spawn).start()
        self.addCleanup(patch.stopall)
        patch.object(m, 'environment', return_value={'FIXTURE': 'yes'}).start()
        patch.object(m.network_guard, 'ensure_wifi_off', return_value=False).start()
        patch.object(m, 'check_listeners').start()
        patch.object(m.lifecycle, 'protect_directory').start()
        patch.object(m.subprocess, 'run', return_value=SimpleNamespace(returncode=0)).start()
    def spawn(self, arguments, **kwargs):
        child = FakeChild(arguments, kwargs.get('stdout')); self.children.append(child); return child
    def stop_loop(self, seconds):
        (self.private / 'state/stop').touch()
    def publish(self, *, external=True, pid=123):
        self.store.mkdir(parents=True, exist_ok=True)
        owner = dict(m.identity(self.value, self.profile, external), ownerPid=pid)
        m.lifecycle.atomic_write(self.store / 'owner.json', m.lifecycle.encoded(owner))
        m.write_status(self.store, pid, 'ready', 'READY', 2)
    def test_external_dhcp_skips_only_dhcp_and_never_touches_external_process(self):
        with patch.object(m.time, 'sleep', side_effect=self.stop_loop):
            m.run(self.value, self.profile, external_dhcp=True)
        self.assertEqual(len(self.children), 2)
        self.assertFalse((self.private / 'dhcp').exists())
        self.assertTrue(all(c.waited and not c.terminated and not c.killed for c in self.children))
        self.assertTrue(all(c.output.closed for c in self.children))
        self.assertFalse(any('fixture-dhcp.exe' in c.arguments for c in self.children))
        self.assertEqual(json.loads((self.store / 'status.json').read_text())['phase'], 'stopped')
        self.assertFalse(m.owner_status(self.value, self.profile, external_dhcp=True)['running'])
    def test_default_still_launches_and_owns_dhcp(self):
        with patch.object(m.time, 'sleep', side_effect=self.stop_loop): m.run(self.value, self.profile)
        self.assertEqual(self.children[0].arguments, ['fixture-dhcp.exe'])
        self.assertEqual(len(self.children), 3)
        self.assertTrue((self.private / 'dhcp/tftpd32.ini').is_file())
        self.assertTrue(all(c.waited for c in self.children))
    def test_port_collision_cannot_touch_shared_lease_stop_or_process(self):
        state = self.private / 'state'; state.mkdir()
        lease = state / 'local-config-relay.json'; lease.write_bytes(b'foreign lease')
        stop = state / 'config-stop'; stop.write_bytes(b'foreign stop')
        with patch.object(m, 'check_listeners', side_effect=ValueError('PORT_ALREADY_IN_USE')):
            with self.assertRaisesRegex(ValueError, '^PORT_ALREADY_IN_USE$'):
                m.run(self.value, self.profile, external_dhcp=True)
        self.assertEqual(lease.read_bytes(), b'foreign lease')
        self.assertEqual(stop.read_bytes(), b'foreign stop')
        self.assertFalse(self.children)
        self.assertEqual(json.loads((self.store / 'status.json').read_text())['reason'], 'PORT_ALREADY_IN_USE')
    def test_live_shared_owner_is_refused_before_start(self):
        marker = self.private / 'tunnel-only-supervisor/owner.json'; marker.parent.mkdir()
        marker.write_text('{"supervisorPid":888}')
        with patch.object(m.lifecycle, 'process_alive', return_value=True):
            with self.assertRaisesRegex(ValueError, '^SHARED_TUNNEL_OWNER_RUNNING$'):
                m.run(self.value, self.profile, external_dhcp=True)
        self.assertFalse(self.children)
        self.assertFalse((self.private / 'state/config-stop').exists())
    def test_lifetime_lock_prevents_duplicate_and_stale_pid_alone_is_not_ownership(self):
        self.publish()
        with patch.object(m.lifecycle, 'process_alive', return_value=True):
            self.assertFalse(m.owner_status(self.value, self.profile, external_dhcp=True)['running'])
            with m.lifecycle.mutation_lock(self.store, timeout=0):
                self.assertTrue(m.owner_status(self.value, self.profile, external_dhcp=True)['running'])
                with self.assertRaisesRegex(ValueError, '^PC_OWNER_ALREADY_RUNNING$'):
                    m.run(self.value, self.profile, external_dhcp=True)
        self.assertFalse(self.children)
    def test_busy_owner_requires_exact_deployment_code_python_and_dhcp_mode_pins(self):
        self.publish()
        with m.lifecycle.mutation_lock(self.store, timeout=0), patch.object(m.lifecycle, 'process_alive', return_value=True):
            with self.assertRaisesRegex(ValueError, '^PC_OWNER_PIN_MISMATCH$'):
                m.owner_status(self.value, self.profile, external_dhcp=False)
            self.profile.write_text('{"fixture":"changed"}')
            with self.assertRaisesRegex(ValueError, '^PC_OWNER_PIN_MISMATCH$'):
                m.owner_status(self.value, self.profile, external_dhcp=True)
    def test_ensure_existing_owner_is_idempotent_and_does_not_probe_its_ports(self):
        self.publish()
        with m.lifecycle.mutation_lock(self.store, timeout=0), patch.object(m.lifecycle, 'process_alive', return_value=True):
            result = m.ensure(self.value, self.profile, external_dhcp=True)
        self.assertFalse(result['started']); self.assertEqual(result['ownerPid'], 123)
        m.check_listeners.assert_not_called(); self.assertFalse(self.children)
    def test_ensure_spawns_hidden_single_child_then_observes_owner(self):
        with patch.object(m, 'owner_status', side_effect=[{'running':False},
                {'running':True, 'ownerPid':123, 'phase':'starting'}]):
            result = m.ensure(self.value, self.profile, external_dhcp=True)
        self.assertTrue(result['started']); self.assertEqual(len(self.children), 1)
        args, kwargs = self.popen.call_args
        self.assertEqual(args[0][-1], '--external-dhcp')
        self.assertIn('--deployment', args[0]); self.assertNotIn('--ensure', args[0])
        self.assertEqual(kwargs['creationflags'], m.HIDDEN)
        self.assertEqual(kwargs['stdin'], subprocess.DEVNULL)
        self.assertTrue(self.children[0].output.closed)
    def test_explicit_stop_marker_blocks_ensure_without_deletion(self):
        state = self.private / 'state'; state.mkdir(); (state / 'stop').touch()
        with self.assertRaisesRegex(ValueError, '^REMOVE_EXPLICIT_STOP_FILE_BEFORE_START$'):
            m.ensure(self.value, self.profile, external_dhcp=True)
        self.assertTrue((state / 'stop').exists()); self.assertFalse(self.children)
    def test_startup_failure_cleans_only_owned_child_and_releases_lifetime_lock(self):
        def fail_second(arguments, **kwargs):
            if self.children: raise OSError('synthetic private path not printed')
            return self.spawn(arguments, **kwargs)
        self.popen.side_effect = fail_second
        with self.assertRaises(OSError): m.run(self.value, self.profile, external_dhcp=True)
        self.assertEqual(len(self.children), 1); self.assertTrue(self.children[0].waited)
        with m.lifecycle.mutation_lock(self.store, timeout=0): pass
        self.assertEqual(json.loads((self.store / 'status.json').read_text())['reason'], 'STARTUP_FAILED')
    def test_unverified_wired_network_cannot_start_tunnel_or_publish_ready(self):
        m.network_guard.ensure_wifi_off.side_effect=m.network_guard.NetworkGuardError('WIFI_GUARD_COMMAND_FAILED')
        with self.assertRaisesRegex(ValueError, '^WIFI_GUARD_COMMAND_FAILED$'):
            m.run(self.value,self.profile,external_dhcp=True)
        self.assertFalse(self.children)
        status=json.loads((self.store / 'status.json').read_text())
        self.assertEqual(status['phase'],'failed')
        self.assertEqual(status['reason'],'WIFI_GUARD_COMMAND_FAILED')
    def test_runtime_probe_cannot_compete_with_child_wifi_repair_lease_writer(self):
        ticks=iter([0,16,16])
        clock=SimpleNamespace(monotonic=lambda:next(ticks),sleep=self.stop_loop,time=m.time.time)
        with patch.object(m, 'time', clock):
            m.run(self.value,self.profile,external_dhcp=True)
        # Startup enforces Wi-Fi before the child exists. Even when the timed
        # runtime probe runs, only the child may repair Wi-Fi and revoke its lease.
        m.network_guard.ensure_wifi_off.assert_called_once()
        self.assertTrue(any('probe_local_ota.py' in str(call.args)
                            for call in m.subprocess.run.call_args_list))
    def test_input_helper_uses_builtin_windows_modules_without_mutating_host_environment(self):
        inherited={'FIXTURE':'yes','PSModulePath':'fixture-pwsh7-modules','PSMODULEPATH':'other-fixture'}
        before=dict(inherited)
        with patch.object(m, 'environment', return_value=inherited), \
             patch.dict(m.os.environ, {'SystemRoot':str(self.private/'Windows')}), \
             patch.object(m.time, 'sleep', side_effect=self.stop_loop):
            m.run(self.value,self.profile,external_dhcp=True)
        helper=next(call for call in m.subprocess.run.call_args_list
                    if call.args[0][0]=='powershell.exe')
        self.assertEqual(helper.kwargs['env'],{
            'FIXTURE':'yes','PSMODULEPATH':str(self.private/'Windows/System32/WindowsPowerShell/v1.0/Modules')})
        self.assertEqual(inherited,before)
    def test_preflight_and_status_validate_profile_without_starting_or_printing_paths(self):
        with patch.object(m, 'load', return_value=self.value) as validate, patch('builtins.print') as output:
            self.assertEqual(m.main(['--deployment', str(self.profile), '--check', '--external-dhcp']), 0)
            self.assertEqual(m.main(['--deployment', str(self.profile), '--status', '--external-dhcp']), 0)
        self.assertEqual(validate.call_count, 2); self.assertFalse(self.children)
        self.assertNotIn(str(self.private), str(output.call_args_list))


if __name__ == '__main__': unittest.main()
