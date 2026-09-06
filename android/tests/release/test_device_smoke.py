"""T48 tests-only contract for scripts/device-smoke.py (stdlib; no live ADB).

API proposed for implementation:
  SmokeError(code): finite .code and sanitized message, never tool/XML contents.
  validate_target(serial, avd_name, *, allowed_targets): return serial.
  validate_artifact(package, *, debuggable, test_only): return release package.
  find_node(xml, *, package, text=None, description=None, resource_id=None,
            actionable=False): return selected node's attribute dict.
  bounds_center(bounds, *, width, height): return integer (x, y).
  run_adb(adb, serial, args, *, avd_name, allowed_targets, timeout, runner):
      validate target before runner; argv subprocess, bounded timeout; return stdout.
  wait_for_node(fetch, *, package, text, timeout, clock, sleep): bounded polling;
      retry NODE_NOT_FOUND only. Injected clock/sleep never wait in these tests.
  record_result(results, step, status): append finite {step, status}, no duplicates.
  summarize_results(results): {status, steps}; incomplete is BLOCKED, failure FAIL.

The orchestrator supplies an explicit serial -> AVD roster from emulators created
for THIS run, not adb auto-discovery. CLI must query each allowlisted serial's live
AVD name before mutation, match the roster, and recheck identity on reconnect.
No physical devices, existing personal AVDs, real provider credentials, or Windows
data. Only com.molotov.clender release artifacts may be installed/reset; APK
metadata must come from verified artifact inspection, never a filename or UI XML.

Device sequence: verify release/target, install and initialize empty sandbox,
Calendar empty state, Drawer destinations, create/read/edit/delete synthetic event,
unconfigured AI failure without network, Light/Dark/System and rotation, then real
Launcher Widget placement/configuration/Edit/Refresh/QuickAI. A missing launcher
capability yields BLOCKED; a broadcast alone is not Widget device acceptance.
Restore rotation in finally. Record finite step outcomes only, never XML, input,
stdout/stderr, credentials or UI text. Debug exploratory runs cannot certify release.

API26 observed UI has empty resource-id for Compose nodes. Drawer description is
on a nonclickable child of a clickable node: exact matching + nearest enabled
clickable ancestor is required. Synthetic XML below models this without copying
the local device dump. Main agent owns docs/production implementation/real devices.
"""

from __future__ import annotations

import importlib.util
import hashlib
import io
import json
from pathlib import Path
import subprocess
import struct
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
from xml.sax.saxutils import quoteattr
import zipfile
import zlib

from test_release_tools import fixture_dex


SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "device-smoke.py"
PACKAGE = "com.molotov.clender"
SERIAL = "emulator-5580"
AVD = "clender_api26_smoke"
TARGETS = {SERIAL: AVD}
STEPS = (
    "empty_database", "drawer", "create_event", "edit_event", "delete_event",
    "unconfigured_ai", "theme", "rotation", "widget",
)


def node(children="", **attributes):
    values = {
        "package": PACKAGE, "text": "", "content-desc": "", "resource-id": "",
        "enabled": "true", "clickable": "true", "bounds": "[10,20][110,80]",
    }
    values.update(attributes)
    wire = " ".join(f"{key}={quoteattr(value)}" for key, value in values.items())
    return f"<node {wire}>{children}</node>"


def hierarchy(*nodes):
    return '<?xml version="1.0"?><hierarchy rotation="0">' + "".join(nodes) + "</hierarchy>"


class DeviceSmokeTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SCRIPT.is_file(), "T48 device-smoke.py is not implemented")
        spec = importlib.util.spec_from_file_location("clender_device_smoke_contract", SCRIPT)
        self.tools = importlib.util.module_from_spec(spec)
        # Dataclasses may consult sys.modules while the module is being loaded.
        modules = patch.dict(sys.modules, {spec.name: self.tools})
        modules.start()
        self.addCleanup(modules.stop)
        # Even a faulty import must not execute ADB or another host process.
        with patch.object(subprocess, "run", side_effect=AssertionError("import executed process")):
            spec.loader.exec_module(self.tools)

    def assert_error(self, code, call):
        with self.assertRaises(self.tools.SmokeError) as caught:
            call()
        self.assertEqual(caught.exception.code, code)
        self.assertNotIn("PRIVATE-SENTINEL", str(caught.exception))

    def test_only_explicit_matching_new_emulator_roster_is_accepted(self):
        for name in (AVD, "clender_api36_phone", "clender_api36_tablet"):
            self.assertEqual(
                self.tools.validate_target(SERIAL, name, allowed_targets={SERIAL: name}), SERIAL
            )

    def test_physical_network_malformed_and_unlisted_serials_fail_closed(self):
        for serial in ("physical-device", "127.0.0.1:5555", "", "emulator-1;id",
                       " emulator-5580", "emulator-5580\n", "emulator-5582"):
            with self.subTest(serial=repr(serial)):
                self.assert_error("TARGET_REJECTED", lambda: self.tools.validate_target(
                    serial, AVD, allowed_targets=TARGETS
                ))

    def test_even_allowlisted_physical_serial_is_rejected(self):
        self.assert_error("TARGET_REJECTED", lambda: self.tools.validate_target(
            "physical-device", AVD, allowed_targets={"physical-device": AVD}
        ))

    def test_empty_roster_wrong_live_avd_and_lookalike_names_are_rejected(self):
        for name, roster in ((AVD, {}), ("clender_api36_phone", TARGETS),
                             ("personal_api26", {SERIAL: "personal_api26"}),
                             ("clender_api", {SERIAL: "clender_api"}),
                             ("clender_api26;id", {SERIAL: "clender_api26;id"})):
            with self.subTest(name=name):
                self.assert_error("TARGET_REJECTED", lambda: self.tools.validate_target(
                    SERIAL, name, allowed_targets=roster
                ))

    def test_release_package_and_boolean_manifest_flags_are_required(self):
        self.assertEqual(self.tools.validate_artifact(
            PACKAGE, debuggable=False, test_only=False
        ), PACKAGE)
        for package in (PACKAGE + ".debug", PACKAGE + ".test", "com.other.app", "", PACKAGE + " "):
            with self.subTest(package=package):
                self.assert_error("ARTIFACT_REJECTED", lambda: self.tools.validate_artifact(
                    package, debuggable=False, test_only=False
                ))
        for debug, test in ((True, False), (False, True), ("false", False), (False, None)):
            self.assert_error("ARTIFACT_REJECTED", lambda: self.tools.validate_artifact(
                PACKAGE, debuggable=debug, test_only=test
            ))

    def test_exact_text_description_and_resource_selectors(self):
        xml = hierarchy(node(text="Save", **{
            "content-desc": "Save event", "resource-id": PACKAGE + ":id/save"
        }))
        for selector in ({"text": "Save"}, {"description": "Save event"},
                         {"resource_id": PACKAGE + ":id/save"}):
            found = self.tools.find_node(xml, package=PACKAGE, **selector)
            self.assertEqual(found["bounds"], "[10,20][110,80]")
        self.assert_error("NODE_NOT_FOUND", lambda: self.tools.find_node(
            xml, package=PACKAGE, text="Sav"
        ))

    def test_multiple_selector_fields_are_conjunctive(self):
        xml = hierarchy(node(text="Save", **{"content-desc": "Save event"}))
        self.assert_error("NODE_NOT_FOUND", lambda: self.tools.find_node(
            xml, package=PACKAGE, text="Save", description="Delete event"
        ))

    def test_missing_or_empty_selector_is_not_a_match_all(self):
        for selector in ({}, {"text": ""}, {"description": ""}, {"resource_id": ""}):
            self.assert_error("SELECTOR_INVALID", lambda: self.tools.find_node(
                hierarchy(node()), package=PACKAGE, **selector
            ))

    def test_compose_drawer_label_uses_nearest_clickable_ancestor(self):
        label = node(clickable="false", bounds="[43,116][106,179]", **{
            "content-desc": "Open navigation drawer"
        })
        xml = hierarchy(node(label, bounds="[11,84][137,210]"))
        found = self.tools.find_node(
            xml, package=PACKAGE, description="Open navigation drawer", actionable=True
        )
        self.assertEqual(found["bounds"], "[11,84][137,210]")
        self.assertEqual(found["clickable"], "true")

    def test_disabled_node_or_ancestor_cannot_be_tapped(self):
        for xml in (hierarchy(node(text="Save", enabled="false")),
                    hierarchy(node(node(text="Save"), enabled="false")),
                    hierarchy(node(text="Save", enabled=""))):
            self.assert_error("NODE_DISABLED", lambda: self.tools.find_node(
                xml, package=PACKAGE, text="Save", actionable=True
            ))

    def test_read_only_text_is_locatable_but_not_actionable(self):
        xml = hierarchy(node(text="No events in this range", clickable="false"))
        self.assertEqual(self.tools.find_node(
            xml, package=PACKAGE, text="No events in this range"
        )["text"], "No events in this range")
        self.assert_error("NODE_NOT_ACTIONABLE", lambda: self.tools.find_node(
            xml, package=PACKAGE, text="No events in this range", actionable=True
        ))

    def test_duplicate_nodes_are_ambiguous_even_if_one_is_disabled(self):
        for enabled in ("true", "false"):
            xml = hierarchy(node(text="Save"), node(text="Save", enabled=enabled))
            self.assert_error("NODE_AMBIGUOUS", lambda: self.tools.find_node(
                xml, package=PACKAGE, text="Save", actionable=True
            ))

    def test_other_packages_are_not_selected_or_used_as_click_ancestors(self):
        xml = hierarchy(node(text="Save", package="com.android.launcher3"))
        self.assert_error("NODE_NOT_FOUND", lambda: self.tools.find_node(
            xml, package=PACKAGE, text="Save"
        ))
        xml = hierarchy(node(node(text="Save", clickable="false"), package="com.other.app"))
        self.assert_error("NODE_NOT_ACTIONABLE", lambda: self.tools.find_node(
            xml, package=PACKAGE, text="Save", actionable=True
        ))

    def test_malformed_xml_and_entity_declarations_fail_without_content_leaks(self):
        for xml in ("PRIVATE-SENTINEL", "<hierarchy><node>",
                    '<!DOCTYPE hierarchy [<!ENTITY x "PRIVATE-SENTINEL">]><hierarchy/>'):
            self.assert_error("XML_INVALID", lambda: self.tools.find_node(
                xml, package=PACKAGE, text="Save"
            ))

    def test_bounds_valid_edges_and_one_pixel_center(self):
        for bounds, point in (("[0,0][1080,1920]", (540, 960)),
                              ("[1079,1919][1080,1920]", (1079, 1919)),
                              ("[10,20][110,80]", (60, 50))):
            self.assertEqual(self.tools.bounds_center(bounds, width=1080, height=1920), point)

    def test_bad_bounds_never_produce_a_tap(self):
        for bounds in ("", "[0,0][0,5]", "[2,2][1,3]", "[-1,0][5,5]",
                       "[0,0][1081,1920]", "[0,0][1080,1921]", "[1.5,0][5,5]",
                       "[0,0][5,5]trailing", " [0,0][5,5]", "[0,0][5,5]\n"):
            with self.subTest(bounds=repr(bounds)):
                self.assert_error("BOUNDS_INVALID", lambda: self.tools.bounds_center(
                    bounds, width=1080, height=1920
                ))

    def test_invalid_display_dimensions_fail_closed(self):
        for width, height in ((0, 1920), (1080, -1), (True, 1920), (1080.0, 1920)):
            self.assert_error("BOUNDS_INVALID", lambda: self.tools.bounds_center(
                "[0,0][1,1]", width=width, height=height
            ))

    def adb(self, runner, **overrides):
        options = dict(avd_name=AVD, allowed_targets=TARGETS, timeout=3, runner=runner)
        options.update(overrides)
        return self.tools.run_adb("isolated/adb.exe", SERIAL, ["shell", "wm", "size"], **options)

    def test_adb_uses_explicit_serial_argv_timeout_and_no_shell(self):
        runner = Mock(return_value=subprocess.CompletedProcess([], 0, "fixture", ""))
        self.assertEqual(self.adb(runner), "fixture")
        args, kwargs = runner.call_args
        self.assertEqual(args[0], ["isolated/adb.exe", "-s", SERIAL, "shell", "wm", "size"])
        self.assertEqual(kwargs["timeout"], 3)
        self.assertFalse(kwargs.get("shell", False))
        self.assertTrue(kwargs["capture_output"])
        self.assertTrue(kwargs["text"])
        self.assertEqual(kwargs["encoding"], "utf-8")
        runner.assert_called_once()

    def test_rejected_target_never_executes_command(self):
        runner = Mock()
        self.assert_error("TARGET_REJECTED", lambda: self.adb(runner, allowed_targets={}))
        runner.assert_not_called()

    def test_timeout_failure_and_missing_adb_are_finite_and_not_retried(self):
        failures = (
            (subprocess.TimeoutExpired("PRIVATE-SENTINEL", 3, output="PRIVATE-SENTINEL"), "ADB_TIMEOUT"),
            (FileNotFoundError("PRIVATE-SENTINEL"), "ADB_UNAVAILABLE"),
            (subprocess.CalledProcessError(7, "PRIVATE-SENTINEL", stderr="PRIVATE-SENTINEL"), "ADB_FAILED"),
        )
        for failure, code in failures:
            runner = Mock(side_effect=failure)
            self.assert_error(code, lambda: self.adb(runner))
            runner.assert_called_once()

    def test_nonzero_completed_process_cannot_be_mistaken_for_success(self):
        runner = Mock(return_value=subprocess.CompletedProcess([], 9, "PRIVATE-SENTINEL", "PRIVATE-SENTINEL"))
        self.assert_error("ADB_FAILED", lambda: self.adb(runner))
        runner.assert_called_once()

    def test_invalid_timeout_rejected_before_command(self):
        for timeout in (0, -1, float("inf"), float("nan"), True):
            runner = Mock()
            self.assert_error("TIMEOUT_INVALID", lambda: self.adb(runner, timeout=timeout))
            runner.assert_not_called()

    def test_wait_for_node_polls_only_missing_state_with_injected_clock(self):
        fetch = Mock(side_effect=[hierarchy(), hierarchy(node(text="Save"))])
        sleep = Mock()
        found = self.tools.wait_for_node(
            fetch, package=PACKAGE, text="Save", timeout=2,
            clock=Mock(side_effect=[0, 0, 0.1, 0.2, 0.3, 0.4]), sleep=sleep,
        )
        self.assertEqual(found["text"], "Save")
        self.assertEqual(fetch.call_count, 2)
        self.assertGreaterEqual(sleep.call_count, 1)

    def test_wait_deadline_reports_timeout_instead_of_success_or_endless_retry(self):
        fetch = Mock(return_value=hierarchy())
        self.assert_error("UI_TIMEOUT", lambda: self.tools.wait_for_node(
            fetch, package=PACKAGE, text="Save", timeout=1,
            clock=Mock(side_effect=[0, 0.1, 2, 3, 4]), sleep=Mock(),
        ))
        self.assertLessEqual(fetch.call_count, 2)

    def test_wait_does_not_retry_ambiguous_ui_or_adb_failure(self):
        for fetch, code in (
            (Mock(return_value=hierarchy(node(text="Save"), node(text="Save"))), "NODE_AMBIGUOUS"),
            (Mock(side_effect=self.tools.SmokeError("ADB_FAILED")), "ADB_FAILED"),
        ):
            sleep = Mock()
            self.assert_error(code, lambda: self.tools.wait_for_node(
                fetch, package=PACKAGE, text="Save", timeout=1, clock=lambda: 0, sleep=sleep,
            ))
            fetch.assert_called_once()
            sleep.assert_not_called()

    def test_results_contain_only_finite_step_and_status_fields(self):
        results = []
        for step in STEPS:
            self.tools.record_result(results, step, "PASS")
        self.assertEqual(results, [{"step": step, "status": "PASS"} for step in STEPS])
        self.assertEqual(self.tools.summarize_results(results), {"status": "PASS", "steps": results})
        self.assertNotIn("PRIVATE-SENTINEL", json.dumps(results))

    def test_missing_widget_and_empty_results_cannot_pass(self):
        for steps in ((), STEPS[:-1]):
            results = [{"step": step, "status": "PASS"} for step in steps]
            self.assertEqual(self.tools.summarize_results(results)["status"], "BLOCKED")

    def test_failed_or_blocked_device_check_prevents_pass(self):
        for status in ("FAIL", "BLOCKED"):
            results = [{"step": step, "status": "PASS"} for step in STEPS]
            results[-1]["status"] = status
            self.assertEqual(self.tools.summarize_results(results)["status"], status)

    def test_unknown_result_fields_and_duplicates_cannot_hide_failures(self):
        for step, status in (("PRIVATE-SENTINEL", "PASS"), ("widget", "SKIP"),
                             ("widget", "PRIVATE-SENTINEL")):
            results = []
            self.assert_error("RESULT_INVALID", lambda: self.tools.record_result(results, step, status))
            self.assertEqual(results, [])
        results = [{"step": "widget", "status": "FAIL"}]
        self.assert_error("RESULT_INVALID", lambda: self.tools.record_result(results, "widget", "PASS"))
        self.assertEqual(results, [{"step": "widget", "status": "FAIL"}])
        self.assert_error("RESULT_INVALID", lambda: self.tools.summarize_results([
            {"step": "widget", "status": "PASS", "stdout": "PRIVATE-SENTINEL"}
        ]))

    def test_device_rechecks_live_name_and_qemu_before_each_command(self):
        runner = Mock(side_effect=[
            subprocess.CompletedProcess([], 0, AVD + "\nOK\n", ""),
            subprocess.CompletedProcess([], 0, "1\n", ""),
            subprocess.CompletedProcess([], 0, "fixture", ""),
            subprocess.CompletedProcess([], 0, "personal_avd\nOK\n", ""),
        ])
        device = self.tools.Device("fixture-adb", SERIAL, TARGETS, runner=runner)
        self.assertEqual(device.command(["shell", "wm", "size"]), "fixture")
        self.assert_error("TARGET_REJECTED", lambda: device.command(["shell", "pm", "clear", PACKAGE]))
        calls = [call.args[0][3:] for call in runner.call_args_list]
        self.assertEqual(calls, [["emu", "avd", "name"], ["shell", "getprop", "ro.kernel.qemu"],
                                 ["shell", "wm", "size"], ["emu", "avd", "name"]])

    def test_zero_qemu_property_prevents_mutation_even_with_matching_avd(self):
        runner = Mock(side_effect=[subprocess.CompletedProcess([], 0, AVD + "\nOK\n", ""),
                                   subprocess.CompletedProcess([], 0, "0", "")])
        device = self.tools.Device("fixture-adb", SERIAL, TARGETS, runner=runner)
        self.assert_error("TARGET_REJECTED", lambda: device.command(["install", "synthetic.apk"]))
        self.assertEqual(runner.call_count, 2)

    def test_windows_console_crcrlf_and_translated_blank_lines_preserve_exact_identity(self):
        # Observed on the real, explicitly allowlisted API26 emulator. Text-mode
        # universal newline translation also produces an empty line per CRCRLF.
        for ending in ("\r\r\n", "\n\n", "\r\n", "\n"):
            with self.subTest(ending=repr(ending)):
                runner = Mock(side_effect=[
                    subprocess.CompletedProcess([], 0, AVD + ending + "OK" + ending, ""),
                    subprocess.CompletedProcess([], 0, "1\r\r\n", ""),
                ])
                device = self.tools.Device("fixture-adb", SERIAL, TARGETS, runner=runner)
                device.verify_identity()
                self.assertEqual(runner.call_count, 2)

    def test_identity_requires_unique_exact_name_then_unique_ok_without_extra_content(self):
        outputs = (AVD, AVD + "\nOK\nOK", AVD + "\n" + AVD + "\nOK",
                   "OK\n" + AVD, AVD + "\nPRIVATE-SENTINEL\nOK",
                   " " + AVD + "\nOK", AVD + " \nOK", AVD + "\n OK",
                   "personal_avd\r\r\nOK\r\r\n", AVD + "\n \nOK")
        for output in outputs:
            with self.subTest(output=repr(output)):
                runner = Mock(return_value=subprocess.CompletedProcess([], 0, output, ""))
                device = self.tools.Device("fixture-adb", SERIAL, TARGETS, runner=runner)
                self.assert_error("TARGET_REJECTED", device.verify_identity)
                runner.assert_called_once()

    def scripted_ui(self):
        ui = Mock()
        ui.natural_size.return_value = (1080, 1920)
        ui.dump.return_value = hierarchy(*(node(text=name) for name in ("Light", "Dark", "System")))
        ui.screenshot.side_effect = [
            (1080, 1920, (250, 250, 250)), (1080, 1920, (20, 20, 20)),
            (1080, 1920, (250, 250, 250)), (1920, 1080, (250, 250, 250)),
            (1080, 1920, (250, 250, 250)),
        ]
        ui.device.command.return_value = "0"
        return ui

    def test_actual_ui_flow_performs_crud_and_never_certifies_manual_widget(self):
        ui = self.scripted_ui()
        report = self.tools.run_ui_flow(ui)
        self.assertEqual(report["status"], "BLOCKED")
        self.assertEqual(report["steps"], [{"step": step, "status": "PASS"} for step in STEPS[:-1]]
                         + [{"step": "widget", "status": "BLOCKED"}])
        ui.tap.assert_any_call(description="New event")
        ui.fill.assert_any_call("Title", "ClenderSmokeEvent")
        ui.fill.assert_any_call("Title", "ClenderSmokeEdited", previous="ClenderSmokeEvent")
        ui.tap.assert_any_call("Delete")
        ui.expect.assert_any_call("AI is not configured")
        self.assertEqual(report["widget_evidence"], "REQUIRES_LAUNCHER_DEVICE_TEST")
        ui.device.command.assert_any_call(["shell", "settings", "put", "system", "user_rotation", "0"])

    def test_ui_failure_stops_downstream_actions_and_is_recorded_without_text(self):
        ui = self.scripted_ui()
        ui.fill.side_effect = self.tools.SmokeError("UI_TIMEOUT")
        report = self.tools.run_ui_flow(ui)
        self.assertEqual(report["status"], "FAIL")
        self.assertEqual(report["steps"][2], {"step": "create_event", "status": "FAIL"})
        self.assertTrue(all(item["status"] == "BLOCKED" for item in report["steps"][3:]))
        self.assertEqual(ui.fill.call_count, 1)
        self.assertNotIn("ClenderSmoke", json.dumps(report))

    def test_rotation_cleanup_runs_when_orientation_assertion_fails(self):
        ui = self.scripted_ui()
        ui.screenshot.side_effect = [(1080, 1920, (250, 250, 250)), (1080, 1920, (20, 20, 20)),
                                     (1080, 1920, (250, 250, 250)), (1080, 1920, (250, 250, 250))]
        report = self.tools.run_ui_flow(ui)
        self.assertEqual(report["status"], "FAIL")
        self.assertEqual(report["steps"][-2], {"step": "rotation", "status": "FAIL"})
        ui.device.command.assert_any_call(["shell", "settings", "put", "system", "accelerometer_rotation", "0"])
        ui.device.command.assert_any_call(["shell", "settings", "put", "system", "user_rotation", "0"])

    def test_theme_without_actual_background_change_is_not_pass(self):
        ui = self.scripted_ui()
        ui.screenshot.side_effect = [(1080, 1920, (250, 250, 250))] * 3
        report = self.tools.run_ui_flow(ui)
        self.assertEqual(report["status"], "FAIL")
        self.assertEqual(report["steps"][6], {"step": "theme", "status": "FAIL"})

    def fixture_apk(self):
        scratch = SCRIPT.parents[1] / ".tmp"
        scratch.mkdir(exist_ok=True)
        temporary = tempfile.TemporaryDirectory(prefix="device-contract-", dir=scratch)
        self.addCleanup(temporary.cleanup)
        path = Path(temporary.name) / "release.apk"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("classes.dex", fixture_dex())
            archive.writestr("res/xml/network.xml", b"SYNTHETIC")
        return path

    def test_execute_audits_before_any_device_call_and_checks_apk_digest(self):
        path = self.fixture_apk()
        device = Mock()
        auditor = Mock(return_value={"package": PACKAGE, "sha256": "0" * 64})
        self.assert_error("ARTIFACT_REJECTED", lambda: self.tools.execute(path, device, auditor=auditor))
        self.assertEqual(device.mock_calls, [])
        auditor.assert_called_once_with(path)

    def test_release_audit_failure_never_reaches_device(self):
        device = Mock()
        auditor = Mock(side_effect=self.tools.SmokeError("ARTIFACT_REJECTED"))
        self.assert_error("ARTIFACT_REJECTED", lambda: self.tools.execute("synthetic.apk", device, auditor=auditor))
        self.assertEqual(device.mock_calls, [])

    def test_execute_installs_clears_exact_package_launches_and_cleans_dump(self):
        path = self.fixture_apk()
        device = Mock()
        outputs = ["en-US", "Success\n", "Success\n", "Status: ok\n", ""]
        device.command.side_effect = outputs
        evidence = {"package": PACKAGE, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
        with patch.object(self.tools, "run_ui_flow", return_value={"status": "BLOCKED", "steps": []}):
            result = self.tools.execute(path, device, auditor=lambda _: evidence, ui_factory=Mock())
        self.assertEqual(result["artifact_sha256"], evidence["sha256"])
        device.command.assert_any_call(["shell", "pm", "clear", PACKAGE])
        self.assertEqual(device.command.call_args.args[0], ["shell", "rm", "-f", self.tools.XML_REMOTE])

    def test_non_english_device_is_rejected_before_install(self):
        path = self.fixture_apk()
        device = Mock()
        device.command.return_value = "zh-CN"
        evidence = {"package": PACKAGE, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
        self.assert_error("LOCALE_UNSUPPORTED", lambda: self.tools.execute(path, device, auditor=lambda _: evidence))
        self.assertEqual(device.command.call_count, 1)

    def test_audit_apk_uses_real_release_manifest_policy_with_mocked_tools(self):
        path = self.fixture_apk()
        auditor = self.tools.load_release_auditor()
        manifest = """E: manifest
  A: package="com.molotov.clender"
  E: application
    A: android:debuggable=(type 0x12)0x0
    A: android:testOnly=(type 0x12)0x0
    A: android:usesCleartextTraffic=(type 0x12)0x0
"""
        network = """E: network-security-config
  E: base-config
    A: cleartextTrafficPermitted=(type 0x12)0x0
    E: trust-anchors
      E: certificates
        A: src="system"
"""
        def tool(args, **kwargs):
            wire = list(map(str, args))
            if "AndroidManifest.xml" in wire:
                output = manifest
            elif "res/xml/network.xml" in wire:
                output = network
            elif "--print-certs" in wire:
                output = "Signer #1 certificate SHA-256 digest: " + "a" * 64
            else:
                output = ""
            return subprocess.CompletedProcess(args, 0, output, "")
        with patch.object(auditor, "run_tool", side_effect=tool) as runner:
            result = self.tools.audit_apk(path, auditor=auditor, root=path.parent)
            self.assertEqual(result["package"], PACKAGE)
            self.assertEqual(result["sha256"], hashlib.sha256(path.read_bytes()).hexdigest())
            self.assertTrue(any("AndroidManifest.xml" in list(map(str, call.args[0])) for call in runner.call_args_list))
            manifest = manifest.replace(PACKAGE, PACKAGE + ".debug")
            self.assert_error("ARTIFACT_REJECTED", lambda: self.tools.audit_apk(path, auditor=auditor, root=path.parent))

    def test_png_background_decodes_pixels_and_rejects_corrupt_data(self):
        def chunk(kind, payload):
            return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload))
        data = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 2, 8, 2, 0, 0, 0))
        data += chunk(b"IDAT", zlib.compress((b"\0" + bytes((10, 20, 30)) * 2) * 2)) + chunk(b"IEND", b"")
        self.assertEqual(self.tools.png_background(data), (2, 2, (10, 20, 30)))
        self.assert_error("SCREENSHOT_INVALID", lambda: self.tools.png_background(data[:-3]))
        self.assert_error("SCREENSHOT_INVALID", lambda: self.tools.png_background(b"PRIVATE-SENTINEL"))

    def test_dump_removes_stale_xml_before_uiautomator_and_never_reads_on_command_failure(self):
        device = Mock()
        device.command.side_effect = ["", self.tools.SmokeError("ADB_FAILED")]
        ui = self.tools.Ui(device)
        self.assert_error("ADB_FAILED", ui.dump)
        self.assertEqual(device.command.call_args_list[0].args[0],
                         ["shell", "rm", "-f", self.tools.XML_REMOTE])
        self.assertEqual(device.command.call_count, 2)

    def test_cli_invalid_target_never_calls_execute_or_exposes_paths(self):
        with patch.object(self.tools, "execute") as execute, patch("sys.stdout", new_callable=io.StringIO) as output:
            code = self.tools.main(["--serial", "physical-device", "--allow-new-emulator",
                                    "physical-device=clender_api26_smoke", "--apk", "PRIVATE-SENTINEL.apk"])
        self.assertEqual(code, 1)
        execute.assert_not_called()
        self.assertEqual(json.loads(output.getvalue()), {"status": "FAIL", "code": "TARGET_REJECTED", "steps": []})

    def test_cli_core_success_is_exit_two_and_writes_finite_incomplete_report(self):
        path = self.fixture_apk()
        report = {"status": "BLOCKED", "steps": [{"step": "widget", "status": "BLOCKED"}]}
        with patch.object(self.tools, "execute", return_value=report), patch("sys.stdout", new_callable=io.StringIO):
            output = path.parent / "result.json"
            code = self.tools.main(["--serial", SERIAL, "--allow-new-emulator", SERIAL + "=" + AVD,
                                    "--apk", str(path), "--output", str(output)])
        self.assertEqual(code, 2)
        self.assertEqual(json.loads(output.read_text(encoding="utf-8")), report)

    def test_cli_report_path_escape_is_rejected_before_device_execution(self):
        with patch.object(self.tools, "execute") as execute, patch("sys.stdout", new_callable=io.StringIO) as output:
            code = self.tools.main(["--serial", SERIAL, "--allow-new-emulator", SERIAL + "=" + AVD,
                                    "--apk", "fixture.apk", "--output", "../outside.json"])
        self.assertEqual(code, 1)
        execute.assert_not_called()
        self.assertEqual(json.loads(output.getvalue())["code"], "REPORT_INVALID")

    def release_header(self, description):
        # Reduced from signed API26 XML: descriptions belong to nonclickable
        # icon children; coordinates are evidence, not hard-coded device taps.
        icon = node(clickable="false", bounds="[43,116][106,179]",
                    **{"content-desc": description})
        return hierarchy(node(icon, bounds="[11,84][137,210]"))

    def drawer_menu(self, selected):
        # API26 current destination has selected=true but clickable=false.
        item = node(node(text="Events", clickable="false"),
                    selected="true" if selected else "false", focusable="true",
                    clickable="false" if selected else "true", bounds="[0,210][945,357]")
        dismiss = node(**{"content-desc": "Close navigation menu"}, bounds="[945,63][1080,1794]")
        return hierarchy(item, dismiss)

    def test_create_flow_resolves_signed_home_description_not_editor_title(self):
        ui = self.scripted_ui()
        home = self.release_header("New event")
        def tap(text=None, **options):
            if text == "New event" or options.get("description") == "New event":
                self.tools.find_node(home, package=PACKAGE, text=text,
                                     description=options.get("description"), actionable=True)
        ui.tap.side_effect = tap
        report = self.tools.run_ui_flow(ui)
        self.assertEqual(report["steps"][2], {"step": "create_event", "status": "PASS"})

    def test_drawer_leaves_child_via_visible_navigate_back_before_opening(self):
        ui = self.tools.Ui(Mock())
        ui.dump = Mock(side_effect=[self.release_header("Navigate back"),
                                   self.release_header("Open navigation drawer"),
                                   self.drawer_menu(False)])
        ui.tap = Mock()
        ui.drawer("Events")
        self.assertEqual([(call.args, call.kwargs) for call in ui.tap.call_args_list], [
            ((), {"description": "Navigate back"}),
            ((), {"description": "Open navigation drawer"}), (("Events",), {}),
        ])

    def test_drawer_at_root_does_not_press_back_or_exit_activity(self):
        ui = self.tools.Ui(Mock())
        ui.dump = Mock(side_effect=[self.release_header("Open navigation drawer"), self.drawer_menu(False)])
        ui.tap = Mock()
        ui.drawer("Events")
        self.assertEqual([(call.args, call.kwargs) for call in ui.tap.call_args_list], [
            ((), {"description": "Open navigation drawer"}), (("Events",), {}),
        ])

    def test_drawer_missing_or_disabled_navigation_does_not_guess_keyevent(self):
        for xml, code in ((hierarchy(node(text="Discard changes?")), "NODE_NOT_FOUND"),
                          (hierarchy(node(enabled="false", **{"content-desc": "Navigate back"})),
                           "NODE_DISABLED")):
            ui = self.tools.Ui(Mock())
            ui.dump = Mock(return_value=xml)
            ui.tap = Mock()
            self.assert_error(code, lambda: ui.drawer("Events"))
            ui.tap.assert_not_called()
            self.assertEqual(ui.device.mock_calls, [])

    def test_current_drawer_destination_closes_menu_without_tapping_nonclickable_selected_row(self):
        ui = self.tools.Ui(Mock())
        ui.dump = Mock(side_effect=[self.release_header("Open navigation drawer"), self.drawer_menu(True)])
        ui.tap = Mock()
        ui.drawer("Events")
        self.assertEqual([(call.args, call.kwargs) for call in ui.tap.call_args_list], [
            ((), {"description": "Open navigation drawer"}),
            ((), {"description": "Close navigation menu"}),
        ])

    def test_selected_drawer_row_still_is_not_generally_actionable(self):
        self.assert_error("NODE_NOT_ACTIONABLE", lambda: self.tools.find_node(
            self.drawer_menu(True), package=PACKAGE, text="Events", actionable=True
        ))

    def test_rotation_uses_natural_phone_or_tablet_orientation_and_restores_original_settings(self):
        for width, height in ((1080, 1920), (2560, 1600)):
            with self.subTest(natural=(width, height)):
                ui = self.scripted_ui()
                ui.natural_size.return_value = (width, height)
                ui.screenshot.side_effect = [
                    (width, height, (250, 250, 250)), (width, height, (20, 20, 20)),
                    (width, height, (250, 250, 250)),
                    (height, width, (250, 250, 250)), (width, height, (250, 250, 250)),
                ]
                def command(args):
                    if args[:4] == ["shell", "settings", "get", "system"]:
                        return "1" if args[4] == "accelerometer_rotation" else "3"
                    return ""
                ui.device.command.side_effect = command
                report = self.tools.run_ui_flow(ui)
                self.assertEqual(report["steps"][-2], {"step": "rotation", "status": "PASS"})
                self.assertEqual([call.args[0] for call in ui.device.command.call_args_list[-2:]], [
                    ["shell", "settings", "put", "system", "accelerometer_rotation", "1"],
                    ["shell", "settings", "put", "system", "user_rotation", "3"],
                ])

    def test_tablet_that_does_not_physically_rotate_still_fails_and_restores(self):
        ui = self.scripted_ui()
        ui.natural_size.return_value = (2560, 1600)
        ui.screenshot.side_effect = [(2560, 1600, (250, 250, 250)), (2560, 1600, (20, 20, 20)),
                                     (2560, 1600, (250, 250, 250)), (2560, 1600, (250, 250, 250))]
        report = self.tools.run_ui_flow(ui)
        self.assertEqual(report["steps"][-2], {"step": "rotation", "status": "FAIL"})
        ui.device.command.assert_any_call(["shell", "settings", "put", "system", "user_rotation", "0"])

    def test_natural_size_reads_wm_without_using_rotated_window_dimensions(self):
        for output, size in (("Physical size: 2560x1600\r\r\n", (2560, 1600)),
                             ("Physical size: 1080x1920\nOverride size: 720x1280\n", (720, 1280))):
            device = Mock()
            device.command.return_value = output
            self.assertEqual(self.tools.Ui(device).natural_size(), size)
            device.command.assert_called_once_with(["shell", "wm", "size"])

    def test_invalid_natural_size_cannot_certify_orientation(self):
        for output in ("PRIVATE-SENTINEL", "Physical size: 0x1600", "Physical size: 100x100"):
            device = Mock()
            device.command.return_value = output
            self.assert_error("DISPLAY_INVALID", self.tools.Ui(device).natural_size)


if __name__ == "__main__":
    unittest.main(verbosity=2)
