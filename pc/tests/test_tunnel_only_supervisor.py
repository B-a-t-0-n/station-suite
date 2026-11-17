"""Offline synthetic ownership/lease tests. Fake ADB/transport, no device/API."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import tunnel_only_supervisor as m


class FakeAdb:
    def __init__(self):
        self.forward, self.reverse, self.calls = {}, None, []
    def __call__(self, *args):
        self.calls.append(args)
        if args == ('forward', '--list'):
            return '\n'.join(f'{serial} {local} {remote}' for local, (serial, remote) in self.forward.items())
        if args[:2] == ('forward', '--no-rebind'):
            if args[2] in self.forward: raise AssertionError('would steal existing mapping')
            self.forward[args[2]] = (m.SERIAL, args[3]); return ''
        if args[:2] == ('forward', '--remove'):
            self.forward.pop(args[2]); return ''
        if args == ('reverse', '--list'):
            return f'{m.SERIAL} tcp:18443 {self.reverse}' if self.reverse else ''
        if args[:2] == ('reverse', '--no-rebind'):
            if self.reverse: raise AssertionError('would steal reverse')
            self.reverse = args[3]; return ''
        if args[:2] == ('reverse', '--remove'):
            self.reverse = None; return ''
        raise AssertionError('unexpected fake ADB operation')


class TunnelOnlyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.panel=Mock(); self.panel.alive.return_value=True
        panel_patch=patch.object(m,'FixedPanelRelay',return_value=self.panel)
        panel_patch.start(); self.addCleanup(panel_patch.stop)
        network_patch=patch.object(m.network_guard,'ensure_wifi_off',return_value=False)
        self.network_guard=network_patch.start();self.addCleanup(network_patch.stop)
        self.real_recovery=m.request_station_services
        recovery_patch=patch.object(m,'request_station_services')
        recovery_patch.start(); self.addCleanup(recovery_patch.stop)
    def local(self, seq=1):
        return {'healthy': True, 'code': 'ready', 'heartbeatSeq': seq, 'updatedAtMs': 100000,
                'servicePid': 12, 'config': {'active': True}, 'push': {'listening': True}}
    def test_import_paths_and_status_are_passive(self):
        with patch.dict(os.environ, USERPROFILE=str(self.root), LOCALAPPDATA=str(self.root/'wrong-overlay'), STATION_PRIVATE_DIR=str(self.root/'.stationlab')):
            self.assertEqual(m.store_path(), self.root/'.stationlab/tunnel-only-supervisor')
            self.assertEqual(m.relay_path(), self.root/'.stationlab/state/local-config-relay.json')
            self.assertEqual(m.safe_status(m.store_path()), {'phase':'stopped', 'running':False})
        self.assertEqual(list(self.root.iterdir()), [])
    def test_lease_is_bounded_atomic_and_never_changes_payload_or_policy(self):
        active, policy, relay = (self.root/name for name in ('active','policy','relay'))
        active.write_bytes(b'private synthetic payload'); policy.write_bytes(b'fixed synthetic policy')
        before = active.read_bytes(), policy.read_bytes()
        m.check_pins(active, policy, m.FILES.digest(before[0]), m.FILES.digest(before[1]))
        m.write_lease(relay, True, now_ms=1000)
        self.assertEqual(json.loads(relay.read_text()), {'version':1,'enabled':True,'expiresAtMs':31000})
        m.write_lease(relay, False, now_ms=1000)
        self.assertEqual(json.loads(relay.read_text()), {'version':1,'enabled':False,'expiresAtMs':0})
        self.assertEqual((active.read_bytes(),policy.read_bytes()),before)
        policy.write_bytes(b'drift')
        with self.assertRaisesRegex(m.runtime.SupervisorError,'^POLICY_HASH_MISMATCH$'):
            m.check_pins(active, policy, m.FILES.digest(before[0]), m.FILES.digest(before[1]))
        self.assertEqual(active.read_bytes(),before[0])
    def test_fixed_forwards_owned_checked_and_removed(self):
        adb=FakeAdb(); owner=self.root/'forward-owner.json'
        value=m.Forwards(adb,owner); value.ensure(); value.ensure()
        self.assertEqual(adb.forward, {f'tcp:{p}':(m.SERIAL,f'tcp:{r}') for p,r in m.FORWARDS.items()})
        self.assertEqual(json.loads(owner.read_text())['ports'],sorted(m.FORWARDS))
        self.assertEqual(sum(call[:2]==('forward','--no-rebind') for call in adb.calls),4)
        value.close(); self.assertFalse(adb.forward); self.assertFalse(owner.exists())
    def test_lifecycle_bootstrap_has_only_fixed_actions_and_no_enable_or_config(self):
        # Test the real function independently of the run-loop recovery stub.
        execute=Mock();self.real_recovery(execute)
        self.assertEqual([c.args for c in execute.call_args_list], [
            ('shell','am','broadcast','-n',m.PACKAGE+'/.RuntimeReceiver','--es','operation','local_services_start'),
            ('shell','am','broadcast','-n',m.PACKAGE+'/.RuntimeReceiver','--es','operation','runtime_start')])
    def test_mapping_conflict_is_never_stolen_or_removed(self):
        for mapping in ((m.SERIAL,'tcp:17683'),('other-device','tcp:17683'),(m.SERIAL,'tcp:9999')):
            adb=FakeAdb(); adb.forward['tcp:19683']=mapping
            with self.assertRaisesRegex(m.runtime.SupervisorError,'^FORWARD_CONFLICT$'):
                m.Forwards(adb).ensure()
            self.assertEqual(adb.forward['tcp:19683'],mapping)
            self.assertFalse(any(call[:2]==('forward','--remove') for call in adb.calls))
    def test_restart_recovers_only_exact_dead_owned_mappings(self):
        adb=FakeAdb(); owner=self.root/'forward-owner.json'
        m.runtime.save_json(owner,{'version':1,'supervisorPid':123,'ports':[19683]})
        adb.forward={'tcp:19683':(m.SERIAL,'tcp:17683'),'tcp:19999':('other','tcp:8')}
        m.recover_forwards(self.root,adb,alive=lambda pid:False)
        self.assertEqual(adb.forward,{'tcp:19999':('other','tcp:8')}); self.assertFalse(owner.exists())
        m.runtime.save_json(owner,{'version':1,'supervisorPid':123,'ports':[19683]})
        with self.assertRaisesRegex(m.runtime.SupervisorError,'^FORWARD_CONFLICT$'):
            m.recover_forwards(self.root,adb,alive=lambda pid:True)
        self.assertTrue(owner.exists())
    def test_local_health_requires_fresh_progress_and_actual_listeners(self):
        health=m.LocalServicesHealth()
        self.assertEqual(health.inspect(self.local(),now_wall=100,now_mono=0),(False,'LOCAL_HEARTBEAT_PENDING'))
        self.assertEqual(health.inspect(self.local(2),now_wall=100,now_mono=1),(True,'READY'))
        self.assertEqual(health.inspect(self.local(2),now_wall=100,now_mono=12),(False,'LOCAL_HEARTBEAT_STALE'))
        for change in ('healthy','config','push'):
            health=m.LocalServicesHealth(); health.inspect(self.local(),now_wall=100,now_mono=0)
            bad=self.local(2)
            if change=='healthy':bad['healthy']=False
            else:bad[change][{'config':'active','push':'listening'}[change]]=False
            self.assertEqual(health.inspect(bad,now_wall=100,now_mono=1),(False,'LOCAL_NOT_READY'))
        self.assertEqual(m.LocalServicesHealth().inspect(self.local(),now_wall=111,now_mono=1),(False,'LOCAL_STATUS_STALE'))
        bad=self.local();bad['config']['active']=1
        self.assertEqual(m.LocalServicesHealth().inspect(bad,now_wall=100,now_mono=1),(False,'LOCAL_STATUS_INVALID'))
    def test_voice_status_pid_mismatch_is_optional_diagnostic_only(self):
        local=self.local(); voice={'servicePid':13}
        execute=Mock(side_effect=[json.dumps(local),json.dumps(voice)])
        lh,vh=Mock(),Mock();lh.inspect.return_value=(True,'READY');vh.inspect.return_value=(True,'READY')
        self.assertEqual(m.inspect_apps(lh,vh,execute),(True,'READY',True,False))
        self.assertEqual([call.args[-1] for call in execute.call_args_list],['files/local-services-status.json','files/runtime-status.json'])
    def test_voice_disabled_quota_credentials_stale_or_missing_never_gate_config(self):
        for reason in ('APP_DISABLED','APP_CIRCUIT_OPEN','APP_CREDENTIALS_MISSING','APP_STATUS_STALE','APP_NOT_READY'):
            lh,vh=Mock(),Mock();lh.inspect.return_value=(True,'READY');vh.inspect.return_value=(False,reason)
            execute=Mock(side_effect=[json.dumps(self.local()),json.dumps({'servicePid':12})])
            self.assertEqual(m.inspect_apps(lh,vh,execute),(True,'READY',True,False))
        for unavailable in (OSError('synthetic missing file'),ValueError('synthetic malformed status')):
            lh,vh=Mock(),Mock();lh.inspect.return_value=(True,'READY')
            execute=Mock(side_effect=[json.dumps(self.local()),unavailable])
            self.assertEqual(m.inspect_apps(lh,vh,execute),(True,'READY',True,False))
            vh.inspect.assert_not_called()
        lh,vh=Mock(),Mock();lh.inspect.return_value=(False,'LOCAL_NOT_READY');vh.inspect.return_value=(True,'READY')
        execute=Mock(side_effect=[json.dumps(self.local()),json.dumps({'servicePid':12})])
        self.assertEqual(m.inspect_apps(lh,vh,execute),(False,'LOCAL_NOT_READY',False,True))
    def test_matching_fresh_healthy_voice_is_reported_ready_without_owning_config(self):
        lh,vh=Mock(),Mock();lh.inspect.return_value=(True,'READY');vh.inspect.return_value=(True,'READY')
        execute=Mock(side_effect=[json.dumps(self.local()),json.dumps({'servicePid':12})])
        self.assertEqual(m.inspect_apps(lh,vh,execute),(True,'READY',True,True))
    def test_live_old_supervisor_or_detached_guard_blocks_new_mode(self):
        old=self.root/'old';old.mkdir();folder='a'*32;(old/folder).mkdir()
        m.runtime.save_json(old/'owner.json',{'folder':folder,'supervisorPid':12})
        with self.assertRaisesRegex(m.runtime.SupervisorError,'^LEGACY_SUPERVISOR_RUNNING$'):
            m.check_legacy_stopped(old,alive=lambda pid:pid==12)
        m.runtime.save_json(old/folder/'guard-status.json',{'guardPid':13})
        with self.assertRaisesRegex(m.runtime.SupervisorError,'^LEGACY_GUARD_RUNNING$'):
            m.check_legacy_stopped(old,alive=lambda pid:pid==13)
        m.check_legacy_stopped(old,alive=lambda pid:False)
    def test_run_ready_then_outage_never_invokes_pc_fallback_or_writes_active(self):
        active,policy,relay,store=(self.root/name for name in ('active','policy','relay','store'))
        active.write_bytes(b'synthetic active');policy.write_bytes(b'synthetic policy')
        before=active.read_bytes(),policy.read_bytes()
        transport=Mock();transport.alive.return_value=True
        reverse=Mock();reverse.owned=True
        forwards=Mock();forwards.owned=set(m.FORWARDS)
        states=iter([(True,'READY',True,True),(False,'LOCAL_NOT_READY',False,True)])
        leases=[]
        def lease(path,enabled,**kwargs):
            leases.append(enabled)
            if len(leases)>=3:(store/'stop').touch()
        with patch.object(m.FILES,'protect_directory'),patch.object(m.runtime,'recover_reverse'),patch.object(m,'check_legacy_stopped'), \
             patch.object(m,'recover_forwards'),patch.object(m.runtime,'PersistentTransport',return_value=transport), \
             patch.object(m.runtime,'Reverse',return_value=reverse),patch.object(m,'Forwards',return_value=forwards), \
             patch.object(m.runtime,'physical_source'),patch.object(m,'inspect_apps',side_effect=lambda *a:next(states)), \
             patch.object(m,'write_lease',side_effect=lease),patch.object(m,'POLL_SECONDS',0), \
             patch.object(m.runtime,'pc_listener_ready',side_effect=AssertionError('PC health forbidden')), \
             patch.object(m.runtime,'switch',side_effect=AssertionError('active mutation forbidden')), \
             patch.object(m.runtime,'prepare',side_effect=AssertionError('active mutation forbidden')):
            m.run(store=store,relay=relay,active=active,policy=policy,
                  active_sha=m.FILES.digest(before[0]),policy_sha=m.FILES.digest(before[1]))
        self.assertEqual(leases,[False,True,False,False])
        self.assertEqual((active.read_bytes(),policy.read_bytes()),before)
        forwards.close.assert_called_once();reverse.close.assert_called_once();transport.stop.assert_called_once()
        status=m.safe_status(store);self.assertFalse(status['running']);self.assertNotIn('pcFallbackReady',status)
    def test_pin_drift_closes_lease_without_rewriting_drifted_file(self):
        active,policy,relay,store=(self.root/name for name in ('active','policy','relay','store'))
        active.write_bytes(b'synthetic active');policy.write_bytes(b'synthetic policy')
        before=active.read_bytes(),policy.read_bytes();transport=Mock();transport.alive.return_value=True
        reverse=Mock();reverse.owned=True;forwards=Mock();forwards.owned=set(m.FORWARDS)
        pins=Mock(side_effect=[None,m.runtime.SupervisorError('ACTIVE_FILE_DRIFT')])
        lease=Mock()
        with patch.object(m.FILES,'protect_directory'),patch.object(m,'check_legacy_stopped'), \
             patch.object(m,'check_pins',pins),patch.object(m.runtime,'recover_reverse'),patch.object(m,'recover_forwards'), \
             patch.object(m.runtime,'PersistentTransport',return_value=transport),patch.object(m.runtime,'Reverse',return_value=reverse), \
             patch.object(m,'Forwards',return_value=forwards),patch.object(m,'write_lease',lease):
            m.run(store=store,relay=relay,active=active,policy=policy,
                  active_sha=m.FILES.digest(before[0]),policy_sha=m.FILES.digest(before[1]))
        self.assertEqual([call.args[1] for call in lease.call_args_list],[False,False,False])
        self.assertEqual((active.read_bytes(),policy.read_bytes()),before)
        transport.stop.assert_called_once()
    def test_lost_wan_source_keeps_local_config_lease_ready(self):
        active,policy,relay,store=(self.root/name for name in ('active','policy','relay','store'))
        active.write_bytes(b'synthetic active');policy.write_bytes(b'synthetic policy')
        before=active.read_bytes(),policy.read_bytes();transport=Mock();transport.alive.return_value=True
        reverse=Mock();reverse.owned=True;forwards=Mock();forwards.owned=set(m.FORWARDS)
        leases=[];observed=[];save=m.runtime.save_json
        def lease(path,enabled,**kwargs):
            leases.append(enabled)
            if enabled:(store/'stop').touch()
        def record(path,value):
            if Path(path).name=='status.json':observed.append(copy.deepcopy(value))
            save(path,value)
        with patch.object(m.FILES,'protect_directory'),patch.object(m,'check_legacy_stopped'), \
             patch.object(m.runtime,'recover_reverse'),patch.object(m,'recover_forwards'), \
             patch.object(m.runtime,'PersistentTransport',return_value=transport),patch.object(m.runtime,'Reverse',return_value=reverse), \
             patch.object(m,'Forwards',return_value=forwards),patch.object(m,'inspect_apps',return_value=(True,'READY',True,False)), \
             patch.object(m.runtime,'physical_source',side_effect=m.runtime.SupervisorError('PHYSICAL_INTERFACE_UNAVAILABLE')), \
             patch.object(m,'write_lease',side_effect=lease),patch.object(m.runtime,'save_json',side_effect=record),patch.object(m,'POLL_SECONDS',0):
            m.run(store=store,relay=relay,active=active,policy=policy,
                  active_sha=m.FILES.digest(before[0]),policy_sha=m.FILES.digest(before[1]))
        self.assertEqual(leases,[False,True,False])
        ready=next(item for item in observed if item['phase']=='ready')
        self.assertTrue(ready['localServicesReady']);self.assertFalse(ready['internetSourceReady']);self.assertFalse(ready['appReady'])
        self.assertEqual((active.read_bytes(),policy.read_bytes()),before)

    def test_network_failure_closes_lease_even_when_apps_would_be_ready(self):
        active,policy,relay,store=(self.root/name for name in ('active','policy','relay','store'))
        active.write_bytes(b'active');policy.write_bytes(b'policy')
        transport=Mock();transport.alive.return_value=True
        reverse=Mock();reverse.owned=True;forwards=Mock();forwards.owned=set(m.FORWARDS)
        leases=[];observed=[];save=m.runtime.save_json
        self.network_guard.side_effect=m.network_guard.NetworkGuardError('WIFI_GUARD_COMMAND_FAILED')
        def lease(path,enabled,**kwargs):
            leases.append(enabled)
            if len(leases)==2:(store/'stop').touch()
        def record(path,value):
            if Path(path).name=='status.json':observed.append(copy.deepcopy(value))
            save(path,value)
        with patch.object(m.FILES,'protect_directory'),patch.object(m,'check_legacy_stopped'), \
             patch.object(m.runtime,'recover_reverse'),patch.object(m,'recover_forwards'), \
             patch.object(m.runtime,'PersistentTransport',return_value=transport), \
             patch.object(m.runtime,'Reverse',return_value=reverse),patch.object(m,'Forwards',return_value=forwards), \
             patch.object(m,'inspect_apps',return_value=(True,'READY',True,True)) as apps, \
             patch.object(m,'write_lease',side_effect=lease),patch.object(m.runtime,'save_json',side_effect=record):
            m.run(store=store,relay=relay,active=active,policy=policy,
                  active_sha=m.FILES.digest(b'active'),policy_sha=m.FILES.digest(b'policy'))
        self.assertEqual(leases,[False,False,False]);apps.assert_not_called()
        self.assertFalse(any(item.get('wifiOffVerified') for item in observed))
        self.assertTrue(any(item.get('phase')=='closed' for item in observed))
        self.assertTrue(any(item.get('reason')=='WIFI_GUARD_COMMAND_FAILED' for item in observed))
        m.request_station_services.assert_not_called()


if __name__=='__main__':unittest.main()
