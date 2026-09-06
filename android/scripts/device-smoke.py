"""English release UI smoke on explicitly attested NEW isolated emulators only.

Example (run after env.ps1, with the environment's Python executable):
  python scripts/device-smoke.py --serial emulator-5580 \
    --allow-new-emulator emulator-5580=clender_api26_smoke \
    --apk app/build/outputs/apk/release/app-release.apk

--allow-new-emulator attests that the caller created this AVD for this run with
empty userdata. Never supply an existing personal AVD. Live identity is checked
before every command; APK identity is audited before install or pm clear.
Widget is deliberately BLOCKED pending independent Launcher device acceptance.
Exit 0 = complete, 1 = failure, 2 = incomplete (including manual Widget evidence).
No code executes devices, tools, or artifact reads on import.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import re
import struct
import subprocess
import time
import xml.etree.ElementTree as ET
import zipfile
import zlib


ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.molotov.clender"
STEPS = (
    "empty_database", "drawer", "create_event", "edit_event", "delete_event",
    "unconfigured_ai", "theme", "rotation", "widget",
)
CODES = frozenset((
    "TARGET_REJECTED", "ARTIFACT_REJECTED", "SELECTOR_INVALID", "NODE_NOT_FOUND",
    "NODE_DISABLED", "NODE_NOT_ACTIONABLE", "NODE_AMBIGUOUS", "XML_INVALID",
    "BOUNDS_INVALID", "TIMEOUT_INVALID", "ADB_TIMEOUT", "ADB_UNAVAILABLE",
    "ADB_FAILED", "UI_TIMEOUT", "RESULT_INVALID", "UI_FAILED", "DISPLAY_INVALID",
    "SCREENSHOT_INVALID", "LOCALE_UNSUPPORTED", "REPORT_INVALID", "INTERNAL",
))
XML_REMOTE = "/data/local/tmp/clender-device-smoke.xml"


class SmokeError(Exception):
    def __init__(self, code):
        self.code = code if code in CODES else "INTERNAL"
        super().__init__(self.code)


def validate_target(serial, avd_name, *, allowed_targets):
    if (not isinstance(serial, str) or not re.fullmatch(r"emulator-[0-9]{4,5}", serial)
            or not isinstance(avd_name, str)
            or not re.fullmatch(r"clender_api[0-9]{2}(?:_[A-Za-z0-9]+)*", avd_name)
            or allowed_targets.get(serial) != avd_name):
        raise SmokeError("TARGET_REJECTED")
    return serial


def validate_artifact(package, *, debuggable, test_only):
    if package != PACKAGE or debuggable is not False or test_only is not False:
        raise SmokeError("ARTIFACT_REJECTED")
    return package


def parse_ui(xml):
    if (not isinstance(xml, str) or len(xml) > 4 * 1024 * 1024
            or "<!DOCTYPE" in xml.upper() or "<!ENTITY" in xml.upper()):
        raise SmokeError("XML_INVALID")
    try:
        root = ET.fromstring(xml)
    except (ET.ParseError, ValueError):
        raise SmokeError("XML_INVALID") from None
    if root.tag != "hierarchy":
        raise SmokeError("XML_INVALID")
    return root


def find_node(xml, *, package, text=None, description=None, resource_id=None, actionable=False):
    selectors = {key: value for key, value in (
        ("text", text), ("content-desc", description), ("resource-id", resource_id)
    ) if value is not None}
    if not selectors or any(not isinstance(value, str) or not value for value in selectors.values()):
        raise SmokeError("SELECTOR_INVALID")
    root = parse_ui(xml)
    parents = {child: parent for parent in root.iter() for child in parent}
    matches = [node for node in root.iter("node") if node.get("package") == package
               and all(node.get(key) == value for key, value in selectors.items())]
    if not matches:
        raise SmokeError("NODE_NOT_FOUND")
    if len(matches) != 1:
        raise SmokeError("NODE_AMBIGUOUS")
    match = matches[0]
    if not actionable:
        return dict(match.attrib)
    ancestor, clickable = match, None
    while ancestor is not None and ancestor.tag == "node":
        if ancestor.get("package") != package:
            break
        if ancestor.get("enabled") != "true":
            raise SmokeError("NODE_DISABLED")
        if clickable is None and ancestor.get("clickable") == "true":
            clickable = ancestor
        ancestor = parents.get(ancestor)
    if clickable is None:
        raise SmokeError("NODE_NOT_ACTIONABLE")
    return dict(clickable.attrib)


def bounds_center(bounds, *, width, height):
    if any(type(size) is not int or size <= 0 for size in (width, height)):
        raise SmokeError("BOUNDS_INVALID")
    match = re.fullmatch(r"\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]", bounds or "")
    if not match:
        raise SmokeError("BOUNDS_INVALID")
    left, top, right, bottom = map(int, match.groups())
    if not (0 <= left < right <= width and 0 <= top < bottom <= height):
        raise SmokeError("BOUNDS_INVALID")
    return (left + right) // 2, (top + bottom) // 2


def drawer_destination_selected(xml, destination):
    """Recognize the current drawer row without treating it as clickable."""
    find_node(xml, package=PACKAGE, text=destination)
    root = parse_ui(xml)
    parents = {child: parent for parent in root.iter() for child in parent}
    label = next(node for node in root.iter("node")
                 if node.get("package") == PACKAGE and node.get("text") == destination)
    row = parents.get(label)
    selected = (row is not None and row.get("package") == PACKAGE
                and row.get("selected") == "true" and row.get("focusable") == "true")
    if selected:
        if row.get("enabled") != "true" or label.get("enabled") != "true":
            raise SmokeError("NODE_DISABLED")
        find_node(xml, package=PACKAGE, description="Close navigation menu", actionable=True)
    return selected


def validate_timeout(timeout):
    if type(timeout) not in (int, float) or not math.isfinite(timeout) or timeout <= 0:
        raise SmokeError("TIMEOUT_INVALID")


def run_adb(adb, serial, args, *, avd_name, allowed_targets, timeout=15,
            runner=subprocess.run, binary=False):
    validate_target(serial, avd_name, allowed_targets=allowed_targets)
    validate_timeout(timeout)
    try:
        decoding = {} if binary else {"encoding": "utf-8", "errors": "strict"}
        result = runner([str(adb), "-s", serial, *map(str, args)], shell=False,
                        capture_output=True, text=not binary, timeout=timeout, **decoding)
    except subprocess.TimeoutExpired:
        raise SmokeError("ADB_TIMEOUT") from None
    except subprocess.CalledProcessError:
        raise SmokeError("ADB_FAILED") from None
    except OSError:
        raise SmokeError("ADB_UNAVAILABLE") from None
    except UnicodeError:
        raise SmokeError("ADB_FAILED") from None
    if result.returncode:
        raise SmokeError("ADB_FAILED")
    return result.stdout


def wait_for_node(fetch, *, package, text, timeout, clock=time.monotonic, sleep=time.sleep):
    validate_timeout(timeout)
    deadline = clock() + timeout
    while clock() < deadline:
        try:
            return find_node(fetch(), package=package, text=text)
        except SmokeError as error:
            if error.code != "NODE_NOT_FOUND":
                raise
        remaining = deadline - clock()
        if remaining > 0:
            sleep(min(0.25, remaining))
    raise SmokeError("UI_TIMEOUT")


def record_result(results, step, status):
    if (step not in STEPS or status not in ("PASS", "FAIL", "BLOCKED")
            or any(item.get("step") == step for item in results)):
        raise SmokeError("RESULT_INVALID")
    results.append({"step": step, "status": status})


def summarize_results(results):
    checked = []
    for item in results:
        if not isinstance(item, dict) or set(item) != {"step", "status"}:
            raise SmokeError("RESULT_INVALID")
        record_result(checked, item["step"], item["status"])
    statuses = [item["status"] for item in checked]
    status = "FAIL" if "FAIL" in statuses else "BLOCKED" if (
        "BLOCKED" in statuses or len(checked) != len(STEPS)
    ) else "PASS"
    return {"status": status, "steps": checked}


def load_release_auditor():
    spec = importlib.util.spec_from_file_location("clender_release_auditor", ROOT / "scripts/verify-release.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def audit_apk(apk, *, auditor=None, root=ROOT):
    """Reuse release policy against actual APK bytes and aapt2 decoded manifest."""
    auditor = auditor or load_release_auditor()
    try:
        path = auditor.resolve_inside(root, apk)
        if path.suffix.lower() != ".apk" or not path.is_file():
            raise SmokeError("ARTIFACT_REJECTED")
        before = auditor.artifact_metadata(root, path)
        auditor.audit_archive(path)
        build = auditor.resolve_inside(root, ".sdk/build-tools/36.0.0")
        java = auditor.resolve_inside(root, ".toolchain/jdk-17.0.20+8/bin/java.exe")
        def call(args):
            return auditor.run_tool(args, cwd=root).stdout
        signature = call([java, "-Duser.language=en", "-Duser.country=US", "-jar",
                          build / "lib/apksigner.jar", "verify", "--verbose", "--print-certs",
                          "--min-sdk-version", "26", path])
        signers = re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})", signature)
        if len(signers) != 1 or "android debug" in signature.lower():
            raise SmokeError("ARTIFACT_REJECTED")
        call([build / "zipalign.exe", "-c", "4", path])
        manifest_xml = auditor.decode_xmltree(call([
            build / "aapt2.exe", "dump", "xmltree", "--file", "AndroidManifest.xml", path
        ]))
        manifest = auditor.audit_manifest(manifest_xml)
        app = manifest.find("application")
        ns = "{http://schemas.android.com/apk/res/android}"
        package = validate_artifact(manifest.get("package"),
                                    debuggable=app.get(ns + "debuggable", "false") != "false",
                                    test_only=app.get(ns + "testOnly", "false") != "false")
        networks = 0
        with zipfile.ZipFile(path) as archive:
            resources = [name for name in archive.namelist() if name.startswith("res/") and name.endswith(".xml")]
        for name in resources:
            xml = auditor.decode_xmltree(call([build / "aapt2.exe", "dump", "xmltree", "--file", name, path]))
            if ET.fromstring(xml).tag == "network-security-config":
                auditor.audit_network(xml)
                networks += 1
        if not networks or before != auditor.artifact_metadata(root, path):
            raise SmokeError("ARTIFACT_REJECTED")
        return {"package": package, "sha256": before["sha256"]}
    except SmokeError:
        raise
    except Exception:
        raise SmokeError("ARTIFACT_REJECTED") from None


class Device:
    def __init__(self, adb, serial, allowed_targets, *, runner=subprocess.run):
        self.adb, self.serial, self.targets, self.runner = adb, serial, allowed_targets, runner
        self.avd = allowed_targets.get(serial)
        validate_target(serial, self.avd, allowed_targets=allowed_targets)

    def raw(self, args, *, timeout=15, binary=False):
        return run_adb(self.adb, self.serial, args, avd_name=self.avd,
                       allowed_targets=self.targets, timeout=timeout, runner=self.runner, binary=binary)

    def verify_identity(self):
        # Windows console CRCRLF becomes blank lines even in text mode. Ignore
        # only empty lines; do not trim or discard any nonempty protocol token.
        lines = [line for line in self.raw(["emu", "avd", "name"]).splitlines() if line != ""]
        if lines != [self.avd, "OK"]:
            raise SmokeError("TARGET_REJECTED")
        if self.raw(["shell", "getprop", "ro.kernel.qemu"]).strip() != "1":
            raise SmokeError("TARGET_REJECTED")

    def command(self, args, *, timeout=15, binary=False):
        self.verify_identity()
        return self.raw(args, timeout=timeout, binary=binary)


def png_background(data):
    """Decode bounded RGB/RGBA screencap PNG and sample app's left interior margin."""
    try:
        if not isinstance(data, bytes) or not data.startswith(b"\x89PNG\r\n\x1a\n"):
            raise ValueError
        offset, compressed, header, ended = 8, bytearray(), None, False
        while offset + 12 <= len(data):
            size = struct.unpack_from(">I", data, offset)[0]
            kind = data[offset + 4:offset + 8]
            payload = data[offset + 8:offset + 8 + size]
            if len(payload) != size or offset + size + 12 > len(data):
                raise ValueError
            if zlib.crc32(kind + payload) & 0xffffffff != struct.unpack_from(">I", data, offset + 8 + size)[0]:
                raise ValueError
            if kind == b"IHDR":
                header = struct.unpack(">IIBBBBB", payload)
            elif kind == b"IDAT":
                compressed.extend(payload)
            elif kind == b"IEND":
                if size != 0 or offset + 12 != len(data):
                    raise ValueError
                ended = True
                break
            offset += size + 12
        if not ended:
            raise ValueError
        width, height, depth, color, compression, filtering, interlace = header
        if (not 0 < width <= 4096 or not 0 < height <= 4096 or depth != 8
                or color not in (2, 6) or (compression, filtering, interlace) != (0, 0, 0)):
            raise ValueError
        channels = 3 if color == 2 else 4
        stride = width * channels
        decoder = zlib.decompressobj()
        raw = decoder.decompress(compressed, (stride + 1) * height + 1)
        if len(raw) != (stride + 1) * height or not decoder.eof:
            raise ValueError
        previous = bytearray(stride)
        for y in range(height // 2 + 1):
            start = y * (stride + 1)
            mode, row = raw[start], bytearray(raw[start + 1:start + 1 + stride])
            if mode > 4:
                raise ValueError
            for x in range(stride):
                left = row[x - channels] if x >= channels else 0
                above = previous[x]
                corner = previous[x - channels] if x >= channels else 0
                if mode == 1:
                    predictor = left
                elif mode == 2:
                    predictor = above
                elif mode == 3:
                    predictor = (left + above) // 2
                elif mode == 4:
                    p = left + above - corner
                    distances = (abs(p - left), abs(p - above), abs(p - corner))
                    predictor = (left, above, corner)[distances.index(min(distances))]
                else:
                    predictor = 0
                row[x] = (row[x] + predictor) & 255
            previous = row
        x = max(1, width // 100) if width > 1 else 0
        return width, height, tuple(previous[x * channels:x * channels + 3])
    except (ValueError, TypeError, struct.error, zlib.error, IndexError):
        raise SmokeError("SCREENSHOT_INVALID") from None


class Ui:
    def __init__(self, device):
        self.device = device

    def dump(self):
        # Some uiautomator failures return zero: remove the previous dump first.
        self.device.command(["shell", "rm", "-f", XML_REMOTE])
        self.device.command(["shell", "uiautomator", "dump", XML_REMOTE], timeout=20)
        xml = self.device.command(["exec-out", "cat", XML_REMOTE])
        parse_ui(xml)
        return xml

    def size(self):
        text = self.device.command(["shell", "dumpsys", "window", "displays"])
        current = re.findall(r"\bcur=(\d+)x(\d+)\b", text)
        if not current:
            text = self.device.command(["shell", "wm", "size"])
            current = re.findall(r"(?:Physical|Override) size: (\d+)x(\d+)", text)
        if not current:
            raise SmokeError("DISPLAY_INVALID")
        width, height = map(int, current[-1])
        if min(width, height) <= 0:
            raise SmokeError("DISPLAY_INVALID")
        return width, height

    def expect(self, text):
        return wait_for_node(self.dump, package=PACKAGE, text=text, timeout=15)

    def natural_size(self):
        # wm size is expressed in the display's natural orientation, unlike cur=
        # in dumpsys window. An explicit size override takes precedence.
        text = self.device.command(["shell", "wm", "size"])
        sizes = re.findall(r"(?:Physical|Override) size: (\d+)x(\d+)", text)
        if not sizes:
            raise SmokeError("DISPLAY_INVALID")
        width, height = map(int, sizes[-1])
        if min(width, height) <= 0 or width == height:
            raise SmokeError("DISPLAY_INVALID")
        return width, height

    def tap(self, text=None, *, description=None, scroll=False):
        # Retry only absence; disabled/ambiguous nodes never turn into guessed taps.
        deadline, swipes = time.monotonic() + 15, 0
        while True:
            xml = self.dump()
            try:
                selected = find_node(xml, package=PACKAGE, text=text,
                                     description=description, actionable=True)
                width, height = self.size()
                x, y = bounds_center(selected.get("bounds"), width=width, height=height)
                self.device.command(["shell", "input", "tap", x, y])
                return
            except SmokeError as error:
                if error.code != "NODE_NOT_FOUND":
                    raise
            if time.monotonic() >= deadline:
                raise SmokeError("UI_TIMEOUT")
            if scroll and swipes < 5:
                self.scroll(xml, up=scroll == "up")
                swipes += 1
            else:
                time.sleep(0.25)

    def scroll(self, xml, *, up=False):
        nodes = [node for node in parse_ui(xml).iter("node") if node.get("package") == PACKAGE
                 and node.get("scrollable") == "true" and node.get("enabled") == "true"]
        if len(nodes) != 1:
            raise SmokeError("NODE_AMBIGUOUS" if nodes else "NODE_NOT_FOUND")
        width, height = self.size()
        x, _ = bounds_center(nodes[0].get("bounds"), width=width, height=height)
        _, top, _, bottom = map(int, re.findall(r"\d+", nodes[0].get("bounds")))
        a, b = top + (bottom - top) * 3 // 4, top + (bottom - top) // 4
        self.device.command(["shell", "input", "swipe", x, b if up else a, x, a if up else b, 300])

    def drawer(self, destination):
        # Child routes replace the drawer icon with Navigate back. Inspect each
        # resulting screen, never send blind BACK or accept a discard dialog.
        for _ in range(4):
            xml = self.dump()
            try:
                find_node(xml, package=PACKAGE, description="Open navigation drawer", actionable=True)
            except SmokeError as error:
                if error.code != "NODE_NOT_FOUND":
                    raise
                find_node(xml, package=PACKAGE, description="Navigate back", actionable=True)
                self.tap(description="Navigate back")
            else:
                self.tap(description="Open navigation drawer")
                if drawer_destination_selected(self.dump(), destination):
                    self.tap(description="Close navigation menu")
                else:
                    self.tap(destination)
                return
        raise SmokeError("UI_TIMEOUT")

    def fill(self, label, value, *, previous=""):
        if not re.fullmatch(r"[A-Za-z0-9]+", value) or (previous and not re.fullmatch(r"[A-Za-z0-9]+", previous)):
            raise SmokeError("UI_FAILED")
        self.tap(label, scroll="up")
        self.device.command(["shell", "input", "keyevent", "KEYCODE_MOVE_END"])
        if previous:
            self.device.command(["shell", "input", "keyevent", *(["KEYCODE_DEL"] * len(previous))])
        self.device.command(["shell", "input", "text", value])
        keyboard = self.device.command(["shell", "dumpsys", "input_method"])
        if re.search(r"(?:mInputShown|isInputViewShown)=true", keyboard):
            self.device.command(["shell", "input", "keyevent", "KEYCODE_BACK"])

    def screenshot(self):
        return png_background(self.device.command(["exec-out", "screencap", "-p"], binary=True))


def run_ui_flow(ui):
    """Run actual gestures/assertions; never elevate a manual Widget check to PASS."""
    results, failure = [], None
    original_rotation = {}
    def empty():
        ui.expect("No events in this range")
    def drawer():
        ui.tap(description="Open navigation drawer")
        for name in ("Calendar", "Events", "AI Assistant", "Settings", "About"):
            ui.expect(name)
        ui.tap("Events")
        ui.expect("No events on the selected date")
    def create():
        ui.tap(description="New event")
        ui.fill("Title", "ClenderSmokeEvent")
        ui.tap("Save", scroll=True)
        ui.expect("ClenderSmokeEvent")
        ui.drawer("Events")
        ui.expect("ClenderSmokeEvent")
        ui.tap("ClenderSmokeEvent")
        ui.expect("Edit")
    def edit():
        ui.tap("Edit")
        ui.fill("Title", "ClenderSmokeEdited", previous="ClenderSmokeEvent")
        ui.tap("Save", scroll=True)
        ui.expect("ClenderSmokeEdited")
    def delete():
        ui.tap("Delete", scroll=True)
        ui.expect("Delete event?")
        ui.tap("Delete")
        ui.expect("No events on the selected date")
    def unconfigured():
        ui.drawer("AI Assistant")
        ui.fill("Message for AI", "ClenderSmokePrompt")
        ui.tap("Send message", scroll=True)
        ui.expect("AI is not configured")
        ui.expect("No messages in this conversation")
    def theme():
        ui.drawer("Settings")
        samples = {}
        for name in ("Light", "Dark", "System"):
            # Saved page scroll may be at the bottom after the preceding save.
            ui.tap(name, scroll="up")
            ui.tap("Save application settings", scroll=True)
            ui.expect("Save application settings")
            samples[name] = ui.screenshot()[2]
        if sum(samples["Dark"]) + 90 >= sum(samples["Light"]):
            raise SmokeError("UI_FAILED")
    def rotation():
        natural_width, natural_height = ui.natural_size()
        natural_landscape = natural_width > natural_height
        for key in ("accelerometer_rotation", "user_rotation"):
            value = ui.device.command(["shell", "settings", "get", "system", key]).strip()
            if value not in ("0", "1", "2", "3", "null"):
                raise SmokeError("DISPLAY_INVALID")
            original_rotation[key] = value
        ui.device.command(["shell", "settings", "put", "system", "accelerometer_rotation", "0"])
        for value in ("1", "0"):
            landscape = natural_landscape != (int(value) % 2 == 1)
            ui.device.command(["shell", "settings", "put", "system", "user_rotation", value])
            ui.drawer("About")
            ui.expect("About")
            width, height, _ = ui.screenshot()
            if width == height or (width > height) != landscape:
                raise SmokeError("UI_FAILED")
    actions = (empty, drawer, create, edit, delete, unconfigured, theme, rotation)
    try:
        for step, action in zip(STEPS, actions):
            try:
                action()
                record_result(results, step, "PASS")
            except SmokeError as error:
                record_result(results, step, "FAIL")
                failure = error.code
                break
    finally:
        for key, value in original_rotation.items():
            try:
                args = ["shell", "settings", "delete", "system", key] if value == "null" else [
                    "shell", "settings", "put", "system", key, value
                ]
                ui.device.command(args)
            except SmokeError as error:
                failure = error.code
    for step in STEPS:
        if not any(item["step"] == step for item in results):
            record_result(results, step, "BLOCKED")
    report = summarize_results(results)
    if failure:
        report.update(status="FAIL", code=failure)
    report["widget_evidence"] = "REQUIRES_LAUNCHER_DEVICE_TEST"
    return report


def execute(apk, device, *, auditor=audit_apk, ui_factory=Ui):
    evidence = auditor(apk)
    validate_artifact(evidence["package"], debuggable=False, test_only=False)
    if hashlib.sha256(Path(apk).read_bytes()).hexdigest() != evidence["sha256"]:
        raise SmokeError("ARTIFACT_REJECTED")
    device.verify_identity()
    locale = device.command(["shell", "getprop", "persist.sys.locale"]).strip()
    if not locale:
        locale = device.command(["shell", "getprop", "ro.product.locale"]).strip()
    if not re.fullmatch(r"en(?:[-_][A-Za-z0-9]+)*", locale):
        raise SmokeError("LOCALE_UNSUPPORTED")
    if "Success" not in device.command(["install", "-r", str(apk)], timeout=120).splitlines():
        raise SmokeError("ADB_FAILED")
    if device.command(["shell", "pm", "clear", PACKAGE]).strip() != "Success":
        raise SmokeError("ADB_FAILED")
    ui = ui_factory(device)
    try:
        started = device.command(["shell", "am", "start", "-W", "-n",
                                  PACKAGE + "/com.molotov.clender.app.MainActivity"], timeout=30)
        if not re.search(r"(?m)^Status:\s*ok\s*$", started):
            raise SmokeError("ADB_FAILED")
        report = run_ui_flow(ui)
        report["artifact_sha256"] = evidence["sha256"]
        return report
    finally:
        device.command(["shell", "rm", "-f", XML_REMOTE])


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--allow-new-emulator", action="append", required=True, metavar="SERIAL=AVD")
    parser.add_argument("--apk", required=True)
    parser.add_argument("--output", default=".tmp/device-smoke-result.json")
    args = parser.parse_args(argv)
    try:
        targets = {}
        for value in args.allow_new_emulator:
            serial, separator, avd = value.partition("=")
            if not separator or serial in targets:
                raise SmokeError("TARGET_REJECTED")
            validate_target(serial, avd, allowed_targets={serial: avd})
            targets[serial] = avd
        output = (ROOT / args.output).resolve()
        allowed_output_roots = ((ROOT / ".tmp").resolve(), (ROOT / "release").resolve())
        if output.suffix != ".json" or not any(output.is_relative_to(root) for root in allowed_output_roots):
            raise SmokeError("REPORT_INVALID")
        apk = (ROOT / args.apk).resolve()
        if not apk.is_relative_to(ROOT.resolve()):
            raise SmokeError("ARTIFACT_REJECTED")
        device = Device(ROOT / ".sdk/platform-tools/adb.exe", args.serial, targets)
        report = execute(apk, device)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report))
        return {"PASS": 0, "FAIL": 1, "BLOCKED": 2}[report["status"]]
    except SmokeError as error:
        print(json.dumps({"status": "FAIL", "code": error.code, "steps": []}))
        return 1
    except Exception:
        print(json.dumps({"status": "FAIL", "code": "INTERNAL", "steps": []}))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
