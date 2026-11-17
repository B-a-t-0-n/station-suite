"""Safety-gate tests use only local JSON; no device, root or network access."""
import contextlib
from copy import deepcopy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("station_guard_plan", ROOT / "guard_plan.py")
guard = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(guard)


class GuardPlanTests(unittest.TestCase):
    def setUp(self):
        self.observations = json.loads((ROOT / "observations.example.json").read_text())
        self.policy = json.loads((ROOT / "policy.example.json").read_text())

    def test_current_shell_cannot_be_mistaken_for_runtime_guard(self):
        result = guard.assess(self.observations, self.policy)
        self.assertEqual(result["eligibility"], "blocked_by_observed_facts")
        self.assertIn("PRIVILEGED_PRINCIPAL_MISSING", result["blockers"])
        self.assertIn("CAP_NET_ADMIN_MISSING", result["blockers"])
        self.assertEqual(result["runtimeProtection"], "unverified")
        self.assertIs(result["wifiMayBeEnabled"], False)

    def test_declared_root_and_binaries_still_do_not_certify_hardware(self):
        self.observations["principal"].update(uid=0, capEffHex="1000")
        self.observations["firewall"] = dict(ipv4="binary_present", ipv6="binary_present")
        result = guard.assess(self.observations, self.policy)
        self.assertEqual(result["eligibility"], "needs_isolated_verification")
        self.assertEqual(result["observedBlockers"], [])
        for code in ("EARLY_BOOT_BARRIER_UNVERIFIED", "INDEPENDENT_RECOVERY_UNVERIFIED",
                     "PHYSICAL_NETWORK_BIND_CANARY_UNVERIFIED", "COLD_BOOT_ENFORCEMENT_UNVERIFIED"):
            self.assertIn(code, result["blockers"])
        self.assertIs(result["wifiMayBeEnabled"], False)

    def test_net_admin_is_not_inferred_from_uid_or_wrong_capability(self):
        for uid, mask in ((0, "0"), (0, "400"), (2000, "1000"), (0, None)):
            with self.subTest(uid=uid, mask=mask):
                self.observations["principal"].update(uid=uid, capEffHex=mask)
                result = guard.assess(self.observations, self.policy)
                self.assertTrue(set(result["observedBlockers"]) & {
                    "CAP_NET_ADMIN_MISSING", "CAP_NET_ADMIN_UNKNOWN", "PRIVILEGED_PRINCIPAL_MISSING"})

    def test_ipv6_missing_and_permissive_baseline_are_explicit_blockers(self):
        self.observations["principal"]["selinux"] = "Permissive"
        self.observations["firewall"]["ipv6"] = "missing"
        result = guard.assess(self.observations, self.policy)
        self.assertIn("IPV6_FIREWALL_BINARY_MISSING", result["blockers"])
        self.assertIn("SELINUX_ENFORCING_BASELINE_MISSING", result["blockers"])

    def test_unsupported_build_or_api_never_uses_exact_device_plan(self):
        for api, build in ((27, "20221212"), (28, "20220415")):
            self.observations["device"].update(api=api, build=build)
            self.assertIn("DEVICE_PROFILE_UNSUPPORTED", guard.assess(
                self.observations, self.policy)["blockers"])

    def test_unknown_or_self_asserted_proof_fields_are_rejected(self):
        for target, key in ((self.observations, "earlyBootVerified"),
                            (self.observations["management"], "independent"),
                            (self.policy, "otaProtected"),
                            (self.policy, "applyCommand")):
            target[key] = True
            with self.assertRaises(guard.InputError):
                guard.assess(self.observations, self.policy)
            del target[key]

    def test_policy_cannot_omit_root_or_quasar_or_use_broker_as_native(self):
        for uids, broker in (([10005, 10006], 10046), ([0, 10006], 10046),
                             ([0, 10005, 10046], 10046), ([0, 10005], 0),
                             ([0, 10005, 10005], 10046)):
            with self.subTest(uids=uids, broker=broker):
                policy = deepcopy(self.policy)
                policy.update(blockedNativeUids=uids, apiBrokerUid=broker)
                with self.assertRaises(guard.InputError):
                    guard.assess(self.observations, policy)

    def test_booleans_negative_ranges_and_command_interfaces_rejected(self):
        changes = [("apiBrokerUid", True), ("apiBrokerUid", -1),
                   ("managementInterface", "eth0; anything"),
                   ("wirelessInterface", "eth0"), ("localTcpPorts", [True]),
                   ("localTcpPorts", [80]), ("localTcpPorts", [17683, 17683])]
        for key, value in changes:
            with self.subTest(key=key, value=value):
                policy = deepcopy(self.policy); policy[key] = value
                with self.assertRaises(guard.InputError):
                    guard.assess(self.observations, policy)
        self.observations["principal"]["uid"] = False
        with self.assertRaises(guard.InputError):
            guard.assess(self.observations, self.policy)

    def test_json_duplicate_keys_oversize_and_nonfinite_are_rejected(self):
        cases = [(b'{"schemaVersion":1,"schemaVersion":1}', "JSON_DUPLICATE_KEY"),
                 (b" " * (guard.MAX_INPUT_BYTES + 1), "INPUT_TOO_LARGE"),
                 (b'{"number":NaN}', "JSON_NONFINITE_NUMBER")]
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "input.json"
            for raw, code in cases:
                path.write_bytes(raw)
                with self.subTest(code=code), self.assertRaises(guard.InputError) as error:
                    guard.read_json(path)
                self.assertEqual(str(error.exception), code)

    def test_cli_never_prints_unknown_private_value_or_returns_ready(self):
        with tempfile.TemporaryDirectory() as folder:
            obs, policy = Path(folder) / "obs.json", Path(folder) / "policy.json"
            obs.write_text(json.dumps(self.observations))
            policy.write_text(json.dumps(self.policy))
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                code = guard.main(["--observations", str(obs), "--policy", str(policy)])
            self.assertEqual(code, 1)
            self.assertIs(json.loads(output.getvalue())["wifiMayBeEnabled"], False)
            self.policy["secret"] = "PRIVATE_SENTINEL"
            policy.write_text(json.dumps(self.policy))
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                code = guard.main(["--observations", str(obs), "--policy", str(policy)])
            self.assertEqual(code, 2)
            self.assertNotIn("PRIVATE_SENTINEL", output.getvalue())
            self.assertEqual(json.loads(output.getvalue())["code"], "POLICY_SCHEMA_INVALID")

    def test_assessment_does_not_mutate_inputs_or_advance_planned_stages(self):
        before = deepcopy((self.observations, self.policy))
        result = guard.assess(self.observations, self.policy)
        self.assertEqual((self.observations, self.policy), before)
        self.assertEqual([stage["name"] for stage in result["plannedStages"]],
                         ["SEALED", "LOCAL_READY", "GUARDED", "WIFI_ALLOWED"])
        self.assertNotIn("currentStage", result)
        self.assertIs(result["deviceMutationsPerformed"], False)


if __name__ == "__main__":
    unittest.main()
