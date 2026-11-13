"""Offline supervisor tests. Synthetic configs, fake ADB/PowerShell, no sockets/API/device.

The independent-process case redirects USERPROFILE/LOCALAPPDATA into temporary directories;
its controller dies deliberately and only synthetic endpoint files are restored.
"""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import runtime_supervisor as runtime


BASELINE = (json.dumps({'status': 'ok', 'config': {'system_config': {
    'uniProxyUrl': runtime.files.PC_URL}, 'synthetic_private': 'not-for-safe-status'}}) + '\n').encode()
POLICY = b'{"synthetic_guard":true}\n'


class SupervisorTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.active, self.policy, self.store = self.root / 'active.json', self.root / 'policy.json', self.root / 'private'
        self.active.write_bytes(BASELINE); self.policy.write_bytes(POLICY)
        self.acl_directory = patch.object(runtime.files, 'protect_directory').start()
        self.acl_file = patch.object(runtime.files, '_restrict_file').start()
        self.addCleanup(patch.stopall)

    def prepared(self):
        folder = runtime.prepare(self.active, self.store, runtime.files.digest(BASELINE),
                                 self.policy, runtime.files.digest(POLICY))
        return folder, *runtime.load(folder)

    def test_default_store_is_one_userprofile_location_despite_appdata_overlays(self):
        profile = self.root / 'synthetic-userprofile'
        with patch.dict(os.environ, USERPROFILE=str(profile), LOCALAPPDATA=str(self.root / 'overlay-a'), STATION_PRIVATE_DIR=str(profile / '.stationlab')):
            first = runtime.store_path()
            self.assertEqual(first, profile / '.stationlab/runtime-supervisor')
            with patch.dict(os.environ, LOCALAPPDATA=str(self.root / 'overlay-b')):
                self.assertEqual(runtime.store_path(), first)
        self.assertFalse(profile.exists(), 'path discovery must not create or read the real store')

    def test_preparation_changes_only_endpoint_and_keeps_secret_out_of_status(self):
        folder, state, baseline, candidate = self.prepared()
        self.assertEqual(self.active.read_bytes(), BASELINE)
        value = runtime.files.parse(candidate)
        self.assertEqual(value['config']['system_config']['uniProxyUrl'], runtime.files.LOCAL_URL)
        value['config']['system_config']['uniProxyUrl'] = runtime.files.PC_URL
        self.assertEqual(value, runtime.files.parse(BASELINE))
        runtime.switch(state, baseline, candidate, True)
        self.assertEqual(self.active.read_bytes(), candidate)
        runtime.switch(state, baseline, candidate, False)
        self.assertEqual(self.active.read_bytes(), BASELINE)
        runtime.save_json(self.store / 'status.json', {'phase': 'pc', 'running': True,
            'supervisorPid': os.getpid(), 'reason': 'not-for-safe-status', 'token': state['token'],
            'audio': 'not-for-safe-status', 'credentialsConfigured': 'secret'})
        safe = json.dumps(runtime.safe_status(self.store))
        self.assertNotIn('not-for-safe-status', safe)
        self.assertNotIn(state['token'], safe)
        self.assertNotIn('audio', safe)

    def test_expected_hashes_and_private_backups_are_enforced(self):
        with self.assertRaisesRegex(runtime.SupervisorError, '^BASELINE_HASH_MISMATCH$'):
            runtime.prepare(self.active, self.store, '0' * 64, self.policy, runtime.files.digest(POLICY))
        self.assertFalse(self.store.exists())
        folder, state, baseline, candidate = self.prepared()
        (folder / 'baseline.json').write_bytes(BASELINE + b' ')
        with self.assertRaisesRegex(runtime.SupervisorError, '^INVALID_GUARD_BACKUP$'):
            runtime.load(folder)
        self.assertEqual(BASELINE, self.active.read_bytes())

    def test_drift_is_never_overwritten_and_policy_change_allows_only_pc_restore(self):
        folder, state, baseline, candidate = self.prepared()
        runtime.switch(state, baseline, candidate, True)
        self.policy.write_bytes(b'changed')
        with self.assertRaisesRegex(runtime.SupervisorError, '^POLICY_HASH_MISMATCH$'):
            runtime.switch(state, baseline, candidate, True)
        runtime.switch(state, baseline, candidate, False)
        self.assertEqual(BASELINE, self.active.read_bytes())
        foreign = BASELINE + b' '
        self.active.write_bytes(foreign)
        for local in (True, False):
            with self.assertRaisesRegex(runtime.SupervisorError, '^ACTIVE_FILE_DRIFT$'):
                runtime.switch(state, baseline, candidate, local)
        self.assertEqual(foreign, self.active.read_bytes())

    def test_replace_rechecks_hash_after_temp_write(self):
        folder, state, baseline, candidate = self.prepared()
        original = runtime.files.atomic_write
        def race(path, data, **kwargs):
            before = kwargs['before_replace']
            def drift():
                self.active.write_bytes(b'foreign')
                before()
            kwargs['before_replace'] = drift
            return original(path, data, **kwargs)
        with patch.object(runtime.files, 'atomic_write', side_effect=race):
            with self.assertRaisesRegex(runtime.SupervisorError, '^ACTIVE_FILE_DRIFT$'):
                runtime.switch(state, baseline, candidate, True)
        self.assertEqual(b'foreign', self.active.read_bytes())

    def test_lease_rejects_future_expired_forged_and_unbounded_values(self):
        folder, state, *_ = self.prepared()
        runtime.renew(folder, state, 'local', now=100)
        lease = runtime.read_json(folder / 'lease.json')
        self.assertTrue(runtime.lease_valid(lease, state, 101))
        for changes in ({'heartbeat': 102}, {'deadline': 131}, {'deadline': 100},
                        {'token': '0' * 64}, {'desired': 'internet'}, {'heartbeat': True},
                        {'deadline': float('inf')}, {'supervisorPid': 999}):
            with self.subTest(changes=changes):
                self.assertFalse(runtime.lease_valid(dict(lease, **changes), state, 101))
        self.assertFalse(runtime.lease_valid(lease, state, 130))

    def test_guard_local_lease_then_expiry_restores_without_supervisor_action(self):
        folder, state, baseline, candidate = self.prepared()
        runtime.renew(folder, state, 'local')
        observations = []
        def tick(_):
            observations.append(self.active.read_bytes())
            if len(observations) == 1:
                runtime.renew(folder, state, 'local', now=time.monotonic() - 31)
            else:
                (folder / 'stop').touch()
        with patch.object(runtime.time, 'sleep', side_effect=tick):
            runtime.guard(folder, alive=lambda _: True)
        self.assertEqual(observations, [candidate, BASELINE])
        self.assertEqual(self.active.read_bytes(), BASELINE)
        self.assertEqual(runtime.read_json(folder / 'guard-status.json')['phase'], 'stopped')

    def test_guard_policy_change_restores_and_drift_survives(self):
        folder, state, baseline, candidate = self.prepared()
        runtime.renew(folder, state, 'local')
        observed = []
        def tick(_):
            observed.append(self.active.read_bytes())
            if len(observed) == 1: self.policy.write_bytes(b'foreign policy')
            else: (folder / 'stop').touch()
        with patch.object(runtime.time, 'sleep', side_effect=tick):
            runtime.guard(folder, alive=lambda _: True)
        self.assertEqual(observed, [candidate, BASELINE])
        self.policy.write_bytes(POLICY)
        (folder / 'stop').unlink()
        self.active.write_bytes(b'foreign edit')
        with self.assertRaisesRegex(runtime.SupervisorError, '^ACTIVE_FILE_DRIFT$'):
            runtime.guard(folder, alive=lambda _: True)
        self.assertEqual(self.active.read_bytes(), b'foreign edit')

    def test_physical_adapter_refresh_and_fixed_error_without_fallback(self):
        source = Mock(side_effect=[(10, '10.131.146.7'), (10, '10.131.146.8'),
                                   runtime.PhysicalSourceError('ADAPTER_CHANGED')])
        with patch.object(runtime, '_PHYSICAL_SOURCE', source), patch.object(runtime, 'powershell') as shell:
            self.assertEqual(runtime.physical_source(), (10, '10.131.146.7'))
            self.assertEqual(runtime.physical_source(), (10, '10.131.146.8'))
            with self.assertRaisesRegex(runtime.SupervisorError, '^PHYSICAL_INTERFACE_UNAVAILABLE$'):
                runtime.physical_source()
            self.assertEqual(source.call_count, 3)
            shell.assert_not_called()

    def test_transport_discovers_source_for_each_connection_and_never_falls_back(self):
        source = Mock(side_effect=[(10, '10.131.146.7'), (10, '10.131.146.8'),
                                   runtime.SupervisorError('PHYSICAL_INTERFACE_UNAVAILABLE')])
        transport = runtime.PersistentTransport(source=source)
        with patch.object(runtime.tunnel, 'read_connect', return_value=(runtime.tunnel.Target('api.giga.chat', 443), b'')), \
             patch.object(runtime.tunnel, 'connect_upstream', side_effect=lambda *a, **k: Mock()) as connect, \
             patch.object(runtime.tunnel, '_reply') as reply, patch.object(runtime.tunnel, 'relay'):
            for _ in range(3):
                transport.slots.acquire(); transport._connection(Mock())
            self.assertEqual(connect.call_count, 2)
            self.assertEqual([call.kwargs['source_ip'] for call in connect.call_args_list],
                             ['10.131.146.7', '10.131.146.8'])
            self.assertTrue(all(call.kwargs['interface_index'] == 10 for call in connect.call_args_list))
            self.assertEqual(reply.call_args.args[1], '502 Bad Gateway')

    def test_reverse_never_replaces_foreign_mapping_and_only_removes_ours(self):
        existing = ['serial tcp:18443 tcp:18443']
        calls = []
        def execute(*args):
            calls.append(args)
            if args == ('reverse', '--list'): return '\n'.join(existing)
            if args == ('reverse', '--no-rebind', 'tcp:18443', 'tcp:18443'):
                existing.append('serial tcp:18443 tcp:18443'); return ''
            if args == ('reverse', '--remove', 'tcp:18443'):
                existing.clear(); return ''
            raise AssertionError(args)
        reverse = runtime.Reverse(execute, self.root / 'reverse.json')
        with self.assertRaisesRegex(runtime.SupervisorError, '^REVERSE_CONFLICT$'): reverse.ensure()
        reverse.close()
        self.assertFalse(any('--remove' in call for call in calls))
        existing.clear(); reverse.ensure()
        self.assertTrue(reverse.owned)
        self.assertTrue((self.root / 'reverse.json').exists())
        existing[:] = ['serial tcp:18443 tcp:19999']
        reverse.close()
        self.assertEqual(existing, ['serial tcp:18443 tcp:19999'])
        self.assertFalse(any('--remove' in call for call in calls))

    def test_reverse_crash_recovery_requires_private_record_and_dead_owner(self):
        marker = self.store / 'reverse-owner.json'
        self.store.mkdir()
        runtime.save_json(marker, {'version': 1, 'supervisorPid': 999, 'port': runtime.PORT})
        execute = Mock(return_value='serial tcp:18443 tcp:18443')
        with patch.object(runtime.files, 'process_alive', return_value=True):
            with self.assertRaisesRegex(runtime.SupervisorError, '^REVERSE_CONFLICT$'):
                runtime.recover_reverse(self.store, execute)
        with patch.object(runtime.files, 'process_alive', return_value=False):
            runtime.recover_reverse(self.store, execute)
        self.assertFalse(marker.exists())
        self.assertIn(('reverse', '--remove', 'tcp:18443'), [call.args for call in execute.call_args_list])

    def test_app_health_needs_real_progress_and_stays_healthy_during_worker(self):
        health = runtime.Health()
        value = dict(enabled=True, listenerReady=True, credentialsConfigured=True, circuitOpen=False,
                     healthy=True, updatedAtMs=100000, heartbeatSeq=7, servicePid=11, activeWorkers=0,
                     failedCode='never-output-this-arbitrary-value')
        self.assertEqual(health.inspect(value, now_wall=100, now_mono=1), (False, 'APP_HEARTBEAT_PENDING'))
        self.assertEqual(health.inspect(value, now_wall=101, now_mono=2), (False, 'APP_HEARTBEAT_PENDING'))
        value.update(heartbeatSeq=8, activeWorkers=1)
        self.assertEqual(health.inspect(value, now_wall=102, now_mono=3), (True, 'READY'))
        self.assertEqual(health.inspect(value, now_wall=102, now_mono=13), (False, 'APP_HEARTBEAT_STALE'))
        value.update(heartbeatSeq=9, circuitOpen=True)
        self.assertEqual(health.inspect(value, now_wall=102, now_mono=14), (False, 'APP_CIRCUIT_OPEN'))
        value.update(circuitOpen=False, enabled=False)
        self.assertEqual(health.inspect(value, now_wall=102, now_mono=15), (False, 'APP_DISABLED'))
        self.assertEqual(health.inspect(value, now_wall=111, now_mono=15), (False, 'APP_STATUS_STALE'))

    def test_app_status_malformed_values_never_become_healthy(self):
        for value in ({}, [], {'updatedAtMs': True}, {'enabled': 'yes'}):
            self.assertEqual(runtime.Health().inspect(value), (False, 'APP_STATUS_INVALID'))

    def test_pc_health_uses_passive_listener_pid_check(self):
        with patch.object(runtime, 'powershell', return_value=True) as call, \
             patch.object(runtime.socket, 'create_connection', side_effect=AssertionError('no probe connection')):
            self.assertTrue(runtime.pc_listener_ready())
            script = call.call_args.args[0]
            self.assertIn('Get-NetTCPConnection -State Listen', script)
            self.assertIn('OwningProcess', script)
            self.assertIn('retired_pc_voice_server.py', script)

    def test_guard_folder_accepts_msix_directory_alias_but_stays_direct_child(self):
        self.store.mkdir()
        alias = self.root / 'msix-localcache' / 'runtime-supervisor'
        folder = alias / ('a' * 32)
        folder.mkdir(parents=True)
        self.assertNotEqual(folder.resolve().parent, self.store.resolve())
        # Reproduce Store virtualization: spellings differ but Windows reports
        # the child's actual parent and the ordinary store as the same directory.
        def same_directory(path, other):
            return path == alias.resolve() and Path(other) == self.store
        with patch.object(Path, 'samefile', autospec=True, side_effect=same_directory):
            self.assertEqual(runtime.validated_guard_folder(str(folder), self.store), folder.resolve())
            nested = folder / ('b' * 32)
            nested.mkdir()
            with self.assertRaisesRegex(runtime.SupervisorError, '^INVALID_GUARD_FOLDER$'):
                runtime.validated_guard_folder(str(nested), self.store)
        with self.assertRaisesRegex(runtime.SupervisorError, '^INVALID_GUARD_FOLDER$'):
            runtime.validated_guard_folder(str(folder), self.store)
        invalid_name = self.store / 'not-a-run'
        invalid_name.mkdir()
        with self.assertRaisesRegex(runtime.SupervisorError, '^INVALID_GUARD_FOLDER$'):
            runtime.validated_guard_folder(str(invalid_name), self.store)
        valid = self.store / ('c' * 32)
        valid.mkdir()
        self.assertEqual(runtime.validated_guard_folder(str(valid), self.store), valid.resolve())

    def test_independent_guard_restores_after_controller_process_dies(self):
        # Child controller/guard operate only on this test's synthetic paths.
        # Use actual OS ACLs in this isolated process, matching production startup.
        profile = self.root / 'userprofile'; profile.mkdir()
        local = self.root / 'localappdata'; local.mkdir()
        environment = dict(os.environ, USERPROFILE=str(profile), LOCALAPPDATA=str(local), STATION_PRIVATE_DIR=str(profile / '.stationlab'))
        script = '''
import pathlib,sys,time,os
sys.path.insert(0,sys.argv[1]);import runtime_supervisor as r
active,policy=pathlib.Path(sys.argv[2]),pathlib.Path(sys.argv[3]);store=r.store_path()
folder=r.prepare(active,store,r.files.digest(active.read_bytes()),policy,r.files.digest(policy.read_bytes()))
state,_,_=r.load(folder);child=r.start_guard(folder);r.renew(folder,state,'local')
until=time.monotonic()+8
while time.monotonic()<until:
 if r.read_json(folder/'guard-status.json').get('phase')=='local':break
 time.sleep(.05)
else:raise SystemExit(3)
print(folder.name,flush=True);os._exit(0)
'''
        child = subprocess.run([sys.executable, '-c', script, str(Path(runtime.__file__).parent),
                                str(self.active), str(self.policy)], env=environment,
                               capture_output=True, text=True, timeout=20)
        self.assertEqual(child.returncode, 0, child.stderr)
        name = child.stdout.strip()
        self.assertRegex(name, '^[a-f0-9]{32}$')
        until = time.monotonic() + 8
        status = {}
        while time.monotonic() < until:
            status = runtime.read_json(profile / '.stationlab/runtime-supervisor' / name / 'guard-status.json')
            if (self.active.read_bytes() == BASELINE and status.get('phase') == 'stopped'
                    and not runtime.files.process_alive(status.get('guardPid'))): break
            time.sleep(.05)
        self.assertEqual(self.active.read_bytes(), BASELINE)
        self.assertEqual(status['phase'], 'stopped')


if __name__ == '__main__':
    unittest.main()
