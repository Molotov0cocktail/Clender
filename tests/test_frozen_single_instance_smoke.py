"""Contract tests for the frozen onefile single-instance smoke harness."""
from __future__ import annotations

import importlib.util
import io
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


ROOT = pathlib.Path(__file__).resolve().parents[1]
HARNESS_PATH = ROOT / "scripts" / "verify_frozen_single_instance.py"


def _load_harness():
    if not HARNESS_PATH.is_file():
        raise FileNotFoundError(
            "required frozen smoke harness is missing: "
            "scripts/verify_frozen_single_instance.py"
        )
    spec = importlib.util.spec_from_file_location("frozen_smoke_harness", HARNESS_PATH)
    if spec is None or spec.loader is None:
        raise ImportError("unable to load frozen smoke harness")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class FrozenSingleInstanceSmokeContractTests(unittest.TestCase):
    def process(self, module, pid, parent_pid, path, created=100):
        return module.ProcessRecord(pid, parent_pid, str(path), created)

    def test_normalize_executable_path_is_absolute_and_case_insensitive(self):
        module = _load_harness()
        with tempfile.TemporaryDirectory() as directory:
            exe = pathlib.Path(directory) / "Clender.exe"
            lower = module.normalize_executable_path(exe)
            upper = module.normalize_executable_path(
                pathlib.Path(str(exe).upper())
            )
        self.assertEqual(lower, upper)
        self.assertTrue(pathlib.PureWindowsPath(lower).is_absolute())

    def test_exact_path_cohort_includes_onefile_parent_and_child(self):
        module = _load_harness()
        target = pathlib.Path("C:/smoke-a/Clender.exe")
        records = [
            self.process(module, 10, 1, target),
            self.process(module, 11, 10, target),
            self.process(module, 12, 10, "C:/elsewhere/Clender.exe"),
        ]
        cohort = module.find_exact_path_cohort(records, target)
        self.assertEqual([item.pid for item in cohort], [10, 11])

    def test_exact_path_cohort_rejects_same_name_at_different_path(self):
        module = _load_harness()
        records = [self.process(module, 12, 1, "C:/user/Clender.exe")]
        self.assertEqual(
            module.find_exact_path_cohort(records, "C:/smoke/Clender.exe"), []
        )

    def test_preflight_fails_closed_for_existing_clender_process(self):
        module = _load_harness()
        records = [self.process(module, 99, 1, "C:/user/Clender.exe")]
        with self.assertRaises(module.EnvironmentalBlocker):
            module.validate_clean_preflight(records, endpoint_reachable=False)

    def test_preflight_fails_closed_for_reachable_default_endpoint(self):
        module = _load_harness()
        with self.assertRaises(module.EnvironmentalBlocker):
            module.validate_clean_preflight([], endpoint_reachable=True)

    def test_preflight_never_terminates_existing_user_process(self):
        module = _load_harness()
        terminator = mock.Mock()
        records = [self.process(module, 99, 1, "C:/user/Clender.exe")]
        with self.assertRaises(module.EnvironmentalBlocker):
            module.validate_clean_preflight(
                records, endpoint_reachable=False, terminate=terminator
            )
        terminator.assert_not_called()

    def test_scenario_workspace_copies_only_executable_not_dist_data(self):
        module = _load_harness()
        with tempfile.TemporaryDirectory() as directory:
            source = pathlib.Path(directory) / "source" / "Clender.exe"
            source.parent.mkdir()
            source.write_bytes(b"exe")
            scenario = pathlib.Path(directory) / "root" / "scene"
            copied = module.prepare_scenario_workspace(source, scenario)
            self.assertEqual(copied.read_bytes(), b"exe")
            self.assertFalse((scenario / "data").exists())

    def test_scenario_workspace_must_be_new_and_empty(self):
        module = _load_harness()
        with tempfile.TemporaryDirectory() as directory:
            source = pathlib.Path(directory) / "Clender.exe"
            source.write_bytes(b"exe")
            scenario = pathlib.Path(directory) / "scene"
            scenario.mkdir()
            (scenario / "sentinel").write_text("keep", encoding="utf-8")
            with self.assertRaises(module.HarnessSafetyError):
                module.prepare_scenario_workspace(source, scenario)

    def test_readiness_requires_live_popen_owner(self):
        module = _load_harness()
        signals = module.ReadinessSignals(False, True, True, True)
        self.assertFalse(signals.ready)

    def test_readiness_requires_exact_path_cohort(self):
        module = _load_harness()
        signals = module.ReadinessSignals(True, False, True, True)
        self.assertFalse(signals.ready)

    def test_readiness_requires_fresh_sandbox_database(self):
        module = _load_harness()
        signals = module.ReadinessSignals(True, True, False, True)
        self.assertFalse(signals.ready)

    def test_readiness_requires_default_local_server_probe(self):
        module = _load_harness()
        signals = module.ReadinessSignals(True, True, True, False)
        self.assertFalse(signals.ready)

    def test_readiness_requires_consecutive_stable_samples(self):
        module = _load_harness()
        ready = module.ReadinessSignals(True, True, True, True)
        not_ready = module.ReadinessSignals(True, True, True, False)
        self.assertFalse(module.has_stable_readiness([ready, not_ready, ready], 2))
        self.assertTrue(module.has_stable_readiness([not_ready, ready, ready], 2))

    def test_normal_primary_to_silent_secondary_argument_order(self):
        module = _load_harness()
        exe = pathlib.Path("C:/smoke/Clender.exe")
        self.assertEqual(module.launch_arguments(exe, silent=False), [str(exe)])
        self.assertEqual(
            module.launch_arguments(exe, silent=True), [str(exe), "--silent"]
        )

    def test_silent_primary_to_normal_secondary_argument_order(self):
        module = _load_harness()
        exe = pathlib.Path("C:/smoke/Clender.exe")
        directions = module.scenario_directions(exe)
        self.assertEqual(
            directions,
            [
                ([str(exe)], [str(exe), "--silent"]),
                ([str(exe), "--silent"], [str(exe)]),
            ],
        )

    def test_secondary_must_exit_zero_before_timeout(self):
        module = _load_harness()
        secondary = mock.Mock()
        secondary.wait.return_value = 0
        self.assertEqual(module.wait_for_secondary(secondary, 3.0), 0)
        secondary.wait.assert_called_once_with(timeout=3.0)

    def test_secondary_timeout_is_a_harness_failure(self):
        module = _load_harness()
        secondary = mock.Mock()
        secondary.wait.side_effect = subprocess.TimeoutExpired("Clender.exe", 3.0)
        with self.assertRaises(module.ScenarioFailure):
            module.wait_for_secondary(secondary, 3.0)

    def test_cleanup_revalidates_pid_creation_time_and_exact_path(self):
        module = _load_harness()
        expected = self.process(module, 20, 10, "C:/smoke/Clender.exe", 100)
        reused = self.process(module, 20, 1, "C:/smoke/Clender.exe", 200)
        with self.assertRaises(module.HarnessSafetyError):
            module.validate_cleanup_process(expected, reused, "C:/smoke")
        moved = self.process(module, 20, 10, "C:/other/Clender.exe", 100)
        with self.assertRaises(module.HarnessSafetyError):
            module.validate_cleanup_process(expected, moved, "C:/smoke")
        unknown_time = self.process(module, 20, 10, "C:/smoke/Clender.exe", 0)
        with self.assertRaises(module.HarnessSafetyError):
            module.validate_cleanup_process(expected, unknown_time, "C:/smoke")

    def test_cleanup_rejects_directory_outside_smoke_root(self):
        module = _load_harness()
        record = self.process(module, 20, 10, "C:/other/Clender.exe", 100)
        with self.assertRaises(module.HarnessSafetyError):
            module.validate_cleanup_process(record, record, "C:/smoke")

    def test_cleanup_rejects_rootless_late_exact_path_process(self):
        module = _load_harness()
        with tempfile.TemporaryDirectory() as directory:
            smoke_root = pathlib.Path(directory)
            executable = smoke_root / "scene" / "Clender.exe"
            primary = self.process(module, 100, 1, executable, 1000)
            rootless = self.process(module, 300, 999, executable, 3000)
            terminator = mock.Mock()
            with mock.patch.object(
                module, "enumerate_processes", return_value=[primary, rootless]
            ), mock.patch.object(module, "_terminate_record", terminator):
                with self.assertRaises(module.HarnessSafetyError):
                    module._cleanup_owned(
                        {100: primary}, executable, smoke_root, 1.0
                    )
            terminator.assert_not_called()

    def test_quiescence_requires_no_cohort_endpoint_or_clender_process(self):
        module = _load_harness()
        self.assertTrue(module.is_quiescent([], False, []))
        self.assertFalse(module.is_quiescent([object()], False, []))
        self.assertFalse(module.is_quiescent([], True, []))
        self.assertFalse(module.is_quiescent([], False, [object()]))

    def test_original_failure_is_preserved_when_cleanup_also_fails(self):
        module = _load_harness()
        original = module.ScenarioFailure("secondary failed")
        cleanup = module.HarnessSafetyError("cleanup refused")
        combined = module.combine_failures(original, [cleanup])
        self.assertIs(combined.__cause__, original)
        self.assertIn("secondary failed", str(combined))
        self.assertIn("cleanup refused", str(combined))

    def test_summary_is_machine_readable_bounded_and_flushes(self):
        module = _load_harness()
        output = mock.Mock(spec=io.TextIOBase)
        summary = {
            "scenario": "normal_to_silent",
            "primary_pid": 10,
            "secondary_pid": 12,
            "cohort": [{"pid": 10, "parent_pid": 1}],
            "readiness": True,
            "secondary_exit_code": 0,
            "primary_survival": True,
            "cleanup": True,
            "quiescence": True,
        }
        module.emit_summary(summary, output)
        payload = json.loads(output.write.call_args.args[0])
        self.assertEqual(set(payload), set(summary))
        output.flush.assert_called_once_with()

    def test_toolhelp_backend_uses_only_stdlib_winapi(self):
        module = _load_harness()
        source = HARNESS_PATH.read_text(encoding="utf-8").casefold()
        self.assertNotIn("import wmi", source)
        self.assertNotIn("import win32", source)
        self.assertIn("createtoolhelp32snapshot", source)
        self.assertIn("queryfullprocessimagenamew", source)
        self.assertIn("getprocesstimes", source)
        self.assertIn("terminateprocess", source)

    def test_timed_out_live_secondary_and_child_are_owned_and_cleaned(self):
        module = _load_harness()
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            source = root / "source.exe"
            source.write_bytes(b"exe")
            smoke_root = root / "smoke"
            smoke_root.mkdir()
            target = smoke_root / "scene" / "Clender.exe"
            primary_record = self.process(module, 100, 1, target, 1000)
            secondary_record = self.process(module, 200, 1, target, 2000)
            child_record = self.process(module, 201, 200, target, 2001)
            primary = mock.Mock(pid=100)
            primary.poll.return_value = None
            secondary = mock.Mock(pid=200)
            secondary.poll.return_value = None
            secondary.wait.side_effect = subprocess.TimeoutExpired("secondary", 1)
            terminated = []
            output = io.StringIO()

            def prepare(_source, scene):
                scene.mkdir()
                target.write_bytes(b"exe")
                return target

            def ready(_owner, _exe, _db, _deadline, observed):
                observed[100] = primary_record
                return [primary_record]

            patches = [
                mock.patch.object(module, "prepare_scenario_workspace", side_effect=prepare),
                mock.patch.object(module.subprocess, "Popen", side_effect=[primary, secondary]),
                mock.patch.object(module, "_wait_for_readiness", side_effect=ready),
                mock.patch.object(
                    module,
                    "enumerate_processes",
                    side_effect=[
                        [primary_record, secondary_record],
                        [primary_record, secondary_record],
                        [primary_record, secondary_record, child_record],
                    ],
                ),
                mock.patch.object(module, "_terminate_record", side_effect=terminated.append),
                mock.patch.object(module, "_wait_until_quiescent", return_value=True),
                mock.patch.object(module, "_safe_remove_scenario"),
            ]
            for patcher in patches:
                patcher.start()
                self.addCleanup(patcher.stop)

            with self.assertRaises(module.ScenarioFailure):
                module.run_scenario(
                    source, smoke_root, "scene", False, 1.0, output
                )

        summary = json.loads(output.getvalue())
        self.assertTrue(summary["cleanup"])
        self.assertTrue(summary["quiescence"])
        self.assertEqual({record.pid for record in terminated}, {100, 200, 201})
        self.assertEqual(terminated[0].pid, 201)

    def test_partial_workspace_failure_is_summarized_and_safely_removed(self):
        module = _load_harness()
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            source = root / "source.exe"
            source.write_bytes(b"exe")
            smoke_root = root / "smoke"
            smoke_root.mkdir()
            scene = smoke_root / "scene"
            output = io.StringIO()

            def partial_failure(_source, scenario):
                scenario.mkdir()
                (scenario / "partial.exe").write_bytes(b"partial")
                raise OSError("C:/secret/source.exe could not be copied")

            with mock.patch.object(
                module,
                "prepare_scenario_workspace",
                side_effect=partial_failure,
            ):
                with self.assertRaises(OSError):
                    module.run_scenario(
                        source, smoke_root, "scene", False, 1.0, output
                    )

            self.assertFalse(scene.exists())

        summary_text = output.getvalue()
        summary = json.loads(summary_text)
        self.assertTrue(summary["cleanup"])
        self.assertTrue(summary["quiescence"])
        self.assertNotIn("secret", summary_text.casefold())

    def test_cli_maps_unexpected_path_error_to_finite_machine_code(self):
        module = _load_harness()
        output = io.StringIO()
        secret = "C:/Users/Alice/private/Clender.exe"
        with mock.patch.object(module, "parse_args") as parse_args, mock.patch.object(
            module, "run", side_effect=OSError(secret)
        ), mock.patch.object(sys, "stderr", output):
            parse_args.return_value = mock.Mock(
                executable=pathlib.Path("Clender.exe"),
                cycles=1,
                timeout_seconds=1.0,
            )
            result = module.main([])

        self.assertEqual(result, 1)
        self.assertEqual(
            json.loads(output.getvalue()),
            {"error": "UNEXPECTED_FAILURE", "result": "failed"},
        )
        self.assertNotIn(secret, output.getvalue())
        self.assertNotIn("traceback", output.getvalue().casefold())


if __name__ == "__main__":
    unittest.main()
