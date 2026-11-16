"""Compile and execute the ACTUAL reflection backend against in-memory HIDL fixtures.

No source rewriting, ADB, Android runtime, sysfs, or device fallback is used.
"""
from __future__ import annotations

import os
from pathlib import Path
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parents[1]
FIXTURES = Path(__file__).resolve().parent / "backend-fixtures"
def java_tool(name):
    override = os.environ.get("RING_TEST_" + name.upper())
    if override:
        return Path(override)
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / (name + (".exe" if os.name == "nt" else ""))
        if candidate.is_file():
            return candidate
    return Path(shutil.which(name) or "__missing_java_tool__")


JAVAC = java_tool("javac")
JAVA = java_tool("java")
READ = ["service:false", "read:max", "read:name", "read:brightness"]


@unittest.skipUnless(JAVAC.is_file() and JAVA.is_file(), "Requires host Java; no device fallback")
class BackendTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="station-ring-backend-host-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.classes = Path(cls.temporary.name)
        sources = [HERE / "backend" / "RingBrightnessBackend.java", *FIXTURES.rglob("*.java")]
        result = subprocess.run([str(JAVAC), "-encoding", "UTF-8", "-source", "8", "-target", "8",
                                 "-d", str(cls.classes), *map(str, sources)],
                                capture_output=True, text=True, timeout=30,
                                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        if result.returncode:
            raise AssertionError(result.stdout + result.stderr)

    def invoke(self, *commands, mode="normal", initial="255"):
        result = subprocess.run([str(JAVA), "-cp", str(self.classes), "BackendHarness", mode, initial,
                                 *commands], capture_output=True, text=True, timeout=8,
                                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        lines = result.stdout.splitlines()
        traces = [line[6:] for line in lines if line.startswith("TRACE ")]
        states = [line[6:] for line in lines if line.startswith("STATE ")]
        self.assertEqual(len(traces), 1, result.stdout + result.stderr)
        self.assertEqual(len(states), 1, result.stdout + result.stderr)
        calls = traces[0].split("|") if traces[0] else []
        messages = [line for line in lines if not line.startswith(("TRACE ", "STATE "))]
        return result.returncode, messages, calls, states[0]

    def test_current_uses_inherited_callback_and_no_write(self):
        self.assertEqual(self.invoke("current"), (0, ["CURRENT 255"], READ, "255"))

    def test_monotonic_ticks_do_not_access_hidl(self):
        code, messages, calls, state = self.invoke("ticks", mode="no-service")
        self.assertEqual((code, calls, state), (0, [], "255"))
        self.assertEqual(len(messages), 1)
        self.assertRegex(messages[0], r"^TICKS [0-9]+$")
        code, messages, calls, state = self.invoke("ticks", "12")
        self.assertEqual((code, messages, calls, state), (1, ["USAGE"], [], "255"))

    def test_actual_contents_override_unreliable_result_code(self):
        self.assertEqual(self.invoke("current", mode="bad-result"),
                         (0, ["CURRENT 255"], READ, "255"))
        self.assertEqual(self.invoke("current", mode="whitespace")[:3],
                         (0, ["CURRENT 255"], READ))

    def test_dim_is_exactly_one_fixed_path_write_with_readback(self):
        self.assertEqual(self.invoke("dim", "255"),
                         (0, ["DIMMED 255 191"], READ + ["write:brightness:191", "read:brightness"], "191"))

    def test_false_write_result_can_succeed_only_with_matching_readback(self):
        self.assertEqual(self.invoke("dim", "255", mode="false-write-applied"),
                         (0, ["DIMMED 255 191"], READ + ["write:brightness:191", "read:brightness"], "191"))

    def test_true_write_result_does_not_hide_unapplied_write(self):
        self.assertEqual(self.invoke("dim", "255", mode="unapplied-write"),
                         (1, ["WRITE_READBACK_FAILED"], READ + ["write:brightness:191", "read:brightness"], "255"))

    def test_readback_must_be_numeric(self):
        code, messages, calls, state = self.invoke("dim", "255", mode="bad-readback")
        self.assertEqual((code, messages), (1, ["INVALID_BRIGHTNESS"]))
        self.assertEqual(calls, READ + ["write:brightness:191", "read:brightness"])

    def test_missing_duplicate_and_null_callbacks_never_write(self):
        for mode, error in [("missing-callback", "NO_CALLBACK"),
                            ("duplicate-callback", "INVALID_CALLBACK"),
                            ("null-callback", "INVALID_CALLBACK")]:
            with self.subTest(mode=mode):
                code, messages, calls, state = self.invoke("dim", "255", mode=mode)
                self.assertEqual((code, messages), (1, [error]))
                self.assertEqual(calls, READ)

    def test_bad_current_values_never_write(self):
        for initial in ["NaN", "256", "-1", "01", "1.0", "9999999999999999999"]:
            with self.subTest(initial=initial):
                code, messages, calls, state = self.invoke("current", initial=initial)
                self.assertEqual((code, messages, calls), (1, ["INVALID_BRIGHTNESS"], READ))

    def test_hardware_mismatch_short_circuits_before_brightness(self):
        self.assertEqual(self.invoke("dim", "255", mode="wrong-max")[:3],
                         (1, ["HARDWARE_MISMATCH"], READ[:2]))
        self.assertEqual(self.invoke("restore", "255", mode="wrong-name")[:3],
                         (1, ["HARDWARE_MISMATCH"], READ[:3]))

    def test_service_unavailable_never_reads_or_writes(self):
        self.assertEqual(self.invoke("current", mode="no-service")[:3],
                         (1, ["SERVICE_UNAVAILABLE"], ["service:false"]))

    def test_external_owner_before_dim_or_restore_is_preserved(self):
        for command in ["dim", "restore"]:
            with self.subTest(command=command):
                self.assertEqual(self.invoke(command, "255", initial="200"),
                                 (1, ["OWNER_CHANGED"], READ, "200"))

    def test_restore_writes_original_only_when_current_is_our_target(self):
        self.assertEqual(self.invoke("restore", "255", initial="191"),
                         (0, ["RESTORED"], READ + ["write:brightness:255", "read:brightness"], "255"))
        self.assertEqual(self.invoke("restore", "255"), (0, ["RESTORED"], READ, "255"))

    def test_dim_restore_and_repeated_restore_share_actual_transport_state(self):
        code, messages, calls, state = self.invoke("round-trip")
        self.assertEqual((code, messages, state),
                         (0, ["DIMMED 255 191", "RESTORED", "RESTORED"], "255"))
        self.assertEqual(calls, READ + ["write:brightness:191", "read:brightness"]
                         + READ + ["write:brightness:255", "read:brightness"] + READ)

    def test_restore_readback_failure_is_visible(self):
        self.assertEqual(self.invoke("restore", "255", initial="191", mode="unapplied-write"),
                         (1, ["WRITE_READBACK_FAILED"], READ + ["write:brightness:255", "read:brightness"], "191"))

    def test_invalid_or_out_of_range_arguments_never_acquire_service(self):
        for commands in [(), ("unknown",), ("current", "255"), ("dim",),
                         ("dim", "256"), ("restore", "-1"), ("dim", "01"),
                         ("dim", "31"), ("restore", "0"), ("dim", "255", "extra")]:
            with self.subTest(commands=commands):
                code, messages, calls, state = self.invoke(*commands)
                self.assertEqual(code, 1)
                self.assertEqual(calls, [])
                self.assertEqual(state, "255")

    def test_minimum_accepted_original_and_zero_current(self):
        self.assertEqual(self.invoke("dim", "32", initial="32"),
                         (0, ["DIMMED 32 24"], READ + ["write:brightness:24", "read:brightness"], "24"))
        self.assertEqual(self.invoke("current", initial="0"), (0, ["CURRENT 0"], READ, "0"))


if __name__ == "__main__":
    unittest.main()
