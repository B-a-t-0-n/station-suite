"""Offline lifecycle tests: fake tools/processes, no Station or private configuration."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('recovery_transport', ROOT / 'tools/check_recovery_transport.py')
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)


class TransportCheckTests(unittest.TestCase):
    def test_idle_checks_fixed_owners_and_listeners_without_mutation(self):
        with patch.object(m.transport, 'owner_status', return_value={'running': False}) as status, \
                patch.object(m.transport, 'check_shared_owners') as shared, \
                patch.object(m.transport, 'check_listeners') as listeners:
            value = {'fixture': True}
            m.check(value, 'fixture-profile', external_dhcp=True)
        status.assert_called_once_with(value, 'fixture-profile', external_dhcp=True)
        shared.assert_called_once_with(value); listeners.assert_called_once_with(value)

    def test_running_owner_and_foreign_collisions_are_refused(self):
        with patch.object(m.transport, 'owner_status', return_value={'running': True}), \
                patch.object(m.transport, 'check_shared_owners') as shared:
            with self.assertRaisesRegex(ValueError, 'RECOVERY_PC_OWNER_STILL_RUNNING'):
                m.check({}, 'fixture')
            shared.assert_not_called()
        with patch.object(m.transport, 'owner_status', return_value={'running': False}), \
                patch.object(m.transport, 'check_shared_owners', side_effect=ValueError('SHARED_CONFIG_OWNER_RUNNING')), \
                patch.object(m.transport, 'check_listeners') as listeners:
            with self.assertRaisesRegex(ValueError, 'SHARED_CONFIG_OWNER_RUNNING'):
                m.check({}, 'fixture')
            listeners.assert_not_called()


@unittest.skipUnless(os.name == 'nt' and shutil.which('powershell.exe'), 'Windows PowerShell wrapper')
class WrapperTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); (self.root / 'tools').mkdir(); (self.root / 'pc').mkdir()
        shutil.copy(ROOT / 'tools/Restore-Station.ps1', self.root / 'tools/Restore-Station.ps1')
        self.private = self.root / 'private'; self.private.mkdir()
        self.stop = self.private / 'state/stop'
        self.log = self.root / 'trace.jsonl'
        fake = self.root / 'fake-python.ps1'
        fake.write_text(r"""
$global:LASTEXITCODE = 0
$values = @($args)
$stage = if ($values -contains '--status') { 'status' } elseif ($values -contains '--ensure') { 'ensure' }
    elseif ($values[1] -like '*check_recovery_transport.py') { 'idle' }
    elseif ($values[1] -like '*recover.py' -and $values -contains '--apply') { 'apply' }
    elseif ($values[1] -like '*recover.py') { 'recovery-check' } else { 'pc-check' }
$hasStop = Test-Path -LiteralPath $env:RECOVERY_TEST_STOP
@{stage=$stage; stop=$hasStop; external=($values -contains '--external-dhcp')} | ConvertTo-Json -Compress | Add-Content -LiteralPath $env:RECOVERY_TEST_LOG
if ($stage -eq 'status') {
    if ($env:RECOVERY_TEST_SCENARIO -eq 'pin-mismatch') { '{"ok":false,"code":"PC_OWNER_PIN_MISMATCH"}'; $global:LASTEXITCODE=1; return }
    $running = ($env:RECOVERY_TEST_SCENARIO -in @('owner','changed-owner') -and -not $hasStop) -or
        $env:RECOVERY_TEST_SCENARIO -eq 'never-stops'
    $owner = if ($env:RECOVERY_TEST_SCENARIO -eq 'changed-owner' -and $hasStop) { 456 } else { 123 }
    if ($env:RECOVERY_TEST_SCENARIO -eq 'changed-owner') { $running = $true }
    @{ok=$true;running=$running;ownerPid=$owner;phase='ready'} | ConvertTo-Json -Compress; return
}
if ($stage -eq 'idle' -and $env:RECOVERY_TEST_SCENARIO -eq 'foreign') {
    '{"ok":false,"code":"PORT_ALREADY_IN_USE"}'; $global:LASTEXITCODE=1; return
}
if ($stage -eq 'apply' -and (-not $hasStop -or $env:RECOVERY_TEST_SCENARIO -eq 'apply-failure')) {
    '{"ok":false,"code":"FIXTURE_APPLY_FAILED"}'; $global:LASTEXITCODE=1; return
}
if ($stage -eq 'ensure' -and $hasStop) {
    '{"ok":false,"code":"FIXTURE_STOP_STILL_PRESENT"}'; $global:LASTEXITCODE=1; return
}
'{"ok":true}'
""", encoding='utf-8')
        adb = self.root / 'fake-adb.exe'; adb.write_bytes(b'fixture')
        self.deployment = self.root / 'deployment.json'
        self.profile = self.root / 'recovery.json'
        self.deployment.write_text(json.dumps({'python': str(fake), 'adb': str(adb),
            'serial': 'fixture-station', 'privateDir': str(self.private)}))
        self.profile.write_text(json.dumps({'adb': str(adb), 'serial': 'fixture-station', 'apps': []}))

    def run_wrapper(self, *, apply=True, scenario='owner'):
        arguments = ['powershell.exe', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File',
            str(self.root / 'tools/Restore-Station.ps1'), '-Profile', str(self.profile),
            '-Deployment', str(self.deployment), '-ExternalDhcp']
        if apply: arguments.append('-Apply')
        env = dict(os.environ, RECOVERY_TEST_LOG=str(self.log), RECOVERY_TEST_STOP=str(self.stop),
                   RECOVERY_TEST_SCENARIO=scenario)
        result = subprocess.run(arguments, env=env, capture_output=True, text=True, timeout=20)
        records = [json.loads(line) for line in self.log.read_text(encoding='utf-8-sig').splitlines()] if self.log.exists() else []
        return result, records

    def test_preflight_starts_nothing_and_creates_no_stop(self):
        result, records = self.run_wrapper(apply=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([r['stage'] for r in records], ['pc-check', 'recovery-check'])
        self.assertFalse(self.stop.exists())

    def test_matching_owner_stops_before_apply_and_ensure_after_marker_removal(self):
        result, records = self.run_wrapper()
        self.assertEqual(result.returncode, 0, result.stderr)
        stages = [r['stage'] for r in records]
        self.assertEqual(stages, ['pc-check', 'recovery-check', 'status', 'status', 'idle', 'apply', 'ensure'])
        self.assertTrue(next(r for r in records if r['stage'] == 'apply')['stop'])
        self.assertFalse(records[-1]['stop']); self.assertTrue(records[-1]['external'])
        self.assertTrue(all(r['external'] for r in records if r['stage'] in ('status', 'idle')))
        self.assertFalse(self.stop.exists())

    def test_failed_apply_leaves_owned_stop_and_never_restarts(self):
        result, records = self.run_wrapper(scenario='apply-failure')
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue(self.stop.read_text().startswith('station-recovery:'))
        self.assertNotIn('ensure', [r['stage'] for r in records])
        self.assertIn('restart remains blocked', result.stdout)

    def test_existing_stop_is_preserved_and_no_status_or_mutation_follows(self):
        self.stop.parent.mkdir(); self.stop.write_text('explicit-user-stop')
        result, records = self.run_wrapper()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.stop.read_text(), 'explicit-user-stop')
        self.assertEqual([r['stage'] for r in records], ['pc-check', 'recovery-check'])

    def test_foreign_listener_and_owner_pin_mismatch_block_before_apply(self):
        for scenario in ('foreign', 'pin-mismatch'):
            with self.subTest(scenario=scenario):
                self.log.unlink(missing_ok=True)
                result, records = self.run_wrapper(scenario=scenario)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn('apply', [r['stage'] for r in records])
                self.assertNotIn('ensure', [r['stage'] for r in records])
                self.assertFalse(self.stop.exists())

    def test_shutdown_timeout_keeps_marker_without_apply_or_process_kill(self):
        wrapper = self.root / 'tools/Restore-Station.ps1'
        source = wrapper.read_text(encoding='utf-8')
        self.assertIn('$deadline.Elapsed.TotalSeconds -ge 30', source)
        # Exercise the same timeout branch immediately; no slow sleeps or real processes.
        wrapper.write_text(source.replace('$deadline.Elapsed.TotalSeconds -ge 30',
                                          '$deadline.Elapsed.TotalSeconds -ge 0'), encoding='utf-8-sig')
        result, records = self.run_wrapper(scenario='never-stops')
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue(self.stop.exists())
        self.assertNotIn('apply', [r['stage'] for r in records])
        self.assertIn('no process was killed', result.stderr)

    def test_changed_owner_is_refused_and_stop_is_kept(self):
        result, records = self.run_wrapper(scenario='changed-owner')
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue(self.stop.exists())
        self.assertNotIn('apply', [r['stage'] for r in records])
        self.assertIn('owner changed', result.stderr)


if __name__ == '__main__': unittest.main()
