"""T48 executable contracts; synthetic files only, never Gradle or real keys.

Implementation API in scripts/verify-release.py:
resolve_inside(root, path), validate_signing(root, values, *, is_ignored),
create_release_key(root, key, properties, *, runner),
audit_archive(path), audit_manifest(xml), artifact_metadata(root, path),
write_metadata(root, destination, artifacts), run_tool(argv, *, cwd).
All rejected inputs raise ReleaseError; tool errors retain an exit_code.
PowerShell entrypoints accept -PythonExecutable and use the existing wrapper.
"""

from __future__ import annotations

import hashlib
import base64
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import struct
import tempfile
import unittest
from unittest.mock import Mock, patch
import zipfile
import zlib


ANDROID_ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ANDROID_ROOT / "scripts"
SIGNING_NAMES = (
    "CLENDER_ANDROID_KEYSTORE_FILE",
    "CLENDER_ANDROID_KEYSTORE_PASSWORD",
    "CLENDER_ANDROID_KEY_ALIAS",
    "CLENDER_ANDROID_KEY_PASSWORD",
)
MANIFEST = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
package="com.molotov.clender"><application android:debuggable="false"
android:testOnly="false" android:usesCleartextTraffic="false"/></manifest>'''


def fixture_dex(classes=("Lcom/molotov/clender/MainActivity;",), strings=(), referenced_types=()):
    """Minimal DEX 038 with actual string/type/class_def tables, map and checksums.

    Classes have no members. Ordinary string constants are deliberately separate
    from type references and class definitions; no Android compiler is invoked.
    """
    types = sorted(set(classes) | set(referenced_types) | {"Ljava/lang/Object;"})
    constants = sorted(set(types) | set(strings))
    string_off = 112
    type_off = string_off + 4 * len(constants)
    class_off = type_off + 4 * len(types)
    data_off = class_off + 32 * len(classes)
    data = bytearray(data_off)
    string_offsets = []
    for value in constants:
        string_offsets.append(len(data))
        length = len(value)
        while length >= 128:
            data.append((length & 127) | 128)
            length >>= 7
        data.append(length)
        data.extend(value.encode("ascii") + b"\x00")
    while len(data) % 4:
        data.append(0)
    map_off = len(data)
    sections = [(0, 1, 0), (1, len(constants), string_off), (2, len(types), type_off)]
    if classes:
        sections.append((6, len(classes), class_off))
    sections += [(0x2002, len(constants), data_off), (0x1000, 1, map_off)]
    data.extend(struct.pack("<I", len(sections)))
    for kind, count, offset in sections:
        data.extend(struct.pack("<HHII", kind, 0, count, offset))
    data[:8] = b"dex\n038\x00"
    struct.pack_into("<20I", data, 32, len(data), 112, 0x12345678, 0, 0, map_off,
                     len(constants), string_off, len(types), type_off, 0, 0, 0, 0, 0, 0,
                     len(classes), class_off if classes else 0, len(data) - data_off, data_off)
    for index, offset in enumerate(string_offsets):
        struct.pack_into("<I", data, string_off + 4 * index, offset)
    for index, descriptor in enumerate(types):
        struct.pack_into("<I", data, type_off + 4 * index, constants.index(descriptor))
    for index, descriptor in enumerate(sorted(classes)):
        struct.pack_into("<8I", data, class_off + 32 * index, types.index(descriptor), 1,
                         types.index("Ljava/lang/Object;"), 0, 0xFFFFFFFF, 0, 0, 0)
    return seal_fixture_dex(data)


def seal_fixture_dex(data):
    data = bytearray(data)
    data[12:32] = hashlib.sha1(data[32:]).digest()
    struct.pack_into("<I", data, 8, zlib.adler32(data[12:]) & 0xFFFFFFFF)
    return bytes(data)


class ReleaseToolsTests(unittest.TestCase):
    def setUp(self):
        # No import fallback or skip: missing implementation is the expected RED.
        implementation = SCRIPTS / "verify-release.py"
        self.assertTrue(implementation.is_file(), "T48 verify-release.py is not implemented")
        spec = importlib.util.spec_from_file_location("clender_release_tools", implementation)
        self.tools = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.tools)
        scratch = ANDROID_ROOT / ".tmp"
        scratch.mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="release-contract-", dir=scratch)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "android"
        self.root.mkdir()

    def archive(self, name="release.apk", entries=None):
        path = self.root / name
        with zipfile.ZipFile(path, "w") as archive:
            for entry, data in (entries or {"classes.dex": fixture_dex()}).items():
                archive.writestr(entry, data)
        return path

    def signing(self):
        key = self.root / "release" / "synthetic.jks"
        key.parent.mkdir(exist_ok=True)
        key.write_bytes(b"SYNTHETIC-NOT-A-KEY")
        return dict(zip(SIGNING_NAMES, (str(key), "fixture-password", "fixture-alias", "fixture-key-password")))

    def test_inside_path_accepts_relative_and_absolute(self):
        for path in ("release/app.apk", self.root / "release/app.apk"):
            self.assertEqual(self.tools.resolve_inside(self.root, path), self.root / "release/app.apk")

    def test_outside_paths_and_prefix_sibling_rejected(self):
        for path in ("../outside.apk", self.root.parent / "android-other/file.apk", self.root.parent):
            with self.subTest(path=str(path)), self.assertRaises(self.tools.ReleaseError):
                self.tools.resolve_inside(self.root, path)

    def test_symlink_escape_rejected_without_following_target(self):
        outside = self.root.parent / "outside"
        outside.mkdir()
        link = self.root / "redirect"
        # Simulate canonical resolution without requiring Windows symlink privilege.
        original = Path.resolve
        def canonical(path, *args, **kwargs):
            if path == link / "key.jks":
                return outside / "key.jks"
            return original(path, *args, **kwargs)
        with patch.object(Path, "resolve", canonical):
            with self.assertRaises(self.tools.ReleaseError):
                self.tools.resolve_inside(self.root, link / "key.jks")

    def test_signing_accepts_complete_ignored_independent_fixture(self):
        self.tools.validate_signing(self.root, self.signing(), is_ignored=lambda _: True)

    def test_signing_missing_or_blank_each_input_fails_closed(self):
        valid = self.signing()
        for name in SIGNING_NAMES:
            for value in (None, "", "  "):
                inputs = dict(valid)
                inputs[name] = value
                with self.subTest(name=name, blank=repr(value)), self.assertRaises(self.tools.ReleaseError):
                    self.tools.validate_signing(self.root, inputs, is_ignored=lambda _: True)

    def test_signing_rejects_missing_outside_debug_and_unignored_key(self):
        valid = self.signing()
        debug = self.root / "debug.keystore"
        debug.write_bytes(b"SYNTHETIC")
        for path, ignored in ((self.root / "missing.jks", True), (self.root.parent / "key.jks", True),
                              (debug, True), (Path(valid[SIGNING_NAMES[0]]), False)):
            inputs = dict(valid, CLENDER_ANDROID_KEYSTORE_FILE=str(path))
            with self.subTest(case=path.name, ignored=ignored), self.assertRaises(self.tools.ReleaseError):
                self.tools.validate_signing(self.root, inputs, is_ignored=lambda _: ignored)

    def test_signing_error_does_not_disclose_values(self):
        values = self.signing()
        with self.assertRaises(self.tools.ReleaseError) as result:
            self.tools.validate_signing(self.root, values, is_ignored=lambda _: False)
        for secret in values.values():
            self.assertNotIn(secret, str(result.exception))

    def test_new_key_never_overwrites_key_or_properties(self):
        for existing in ("new.jks", "keystore.properties"):
            destination = self.root / existing
            destination.write_bytes(b"KEEP-EXACTLY")
            runner = Mock()
            with self.subTest(existing=existing), self.assertRaises(self.tools.ReleaseError):
                self.tools.create_release_key(self.root, self.root / "new.jks", self.root / "keystore.properties", runner=runner)
            runner.assert_not_called()
            self.assertEqual(destination.read_bytes(), b"KEEP-EXACTLY")
            destination.unlink()

    def test_new_key_rejects_outside_and_debug_destinations_before_tool(self):
        for key, properties in ((self.root.parent / "new.jks", self.root / "keystore.properties"),
                                (self.root / "debug.keystore", self.root / "keystore.properties"),
                                (self.root / "new.jks", self.root.parent / "keystore.properties")):
            runner = Mock()
            with self.subTest(key=key.name, config=properties.parent.name), self.assertRaises(self.tools.ReleaseError):
                self.tools.create_release_key(self.root, key, properties, runner=runner)
            runner.assert_not_called()

    def test_failed_key_tool_does_not_publish_properties(self):
        runner = Mock(side_effect=self.tools.ReleaseError("Tool failed", exit_code=23))
        with self.assertRaises(self.tools.ReleaseError) as result:
            self.tools.create_release_key(self.root, self.root / "new.jks", self.root / "keystore.properties", runner=runner)
        self.assertEqual(result.exception.exit_code, 23)
        self.assertFalse((self.root / "keystore.properties").exists())

    def test_new_independent_key_uses_secret_indirection_and_publishes_config(self):
        captured = []
        def keytool(argv, **kwargs):
            captured.append((argv, kwargs))
            # The fake only creates a synthetic output; no keytool is executed.
            Path(argv[argv.index("-keystore") + 1]).write_bytes(b"SYNTHETIC-NEW-KEY")
            return subprocess.CompletedProcess(argv, 0, "", "")
        key = self.root / "new.jks"
        properties = self.root / "keystore.properties"
        self.tools.create_release_key(self.root, key, properties, runner=keytool)
        self.assertEqual(key.read_bytes(), b"SYNTHETIC-NEW-KEY")
        values = dict(line.split("=", 1) for line in properties.read_text().splitlines()
                      if line.strip() and not line.startswith("#"))
        self.assertEqual(set(values), {"storeFile", "storePassword", "keyAlias", "keyPassword"})
        self.assertGreaterEqual(len(values["storePassword"]), 32)
        self.assertGreaterEqual(len(values["keyPassword"]), 32)
        self.assertNotEqual(values["keyAlias"].lower(), "androiddebugkey")
        self.assertEqual(len(captured), 1)
        argv, _ = captured[0]
        self.assertIn("-genkeypair", argv)
        for field in ("storePassword", "keyPassword"):
            self.assertNotIn(values[field], " ".join(map(str, argv)))

    def test_new_key_java_properties_unicode_path(self):
        def keytool(argv, **kwargs):
            Path(argv[argv.index("-keystore") + 1]).write_bytes(b"SYNTHETIC-NEW-KEY")
            return subprocess.CompletedProcess(argv, 0, "", "")

        cases = (
            ("plain.jks", "plain.jks"),
            ("space name.jks", r"space\ name.jks"),
            ("\u4e2d.jks", r"\u4e2d.jks"),
            ("\U0001f600.jks", r"\ud83d\ude00.jks"),
            ("\u4e2d \U0001f600\U0001f680.jks", r"\u4e2d\ \ud83d\ude00\ud83d\ude80.jks"),
        )
        for index, (name, escaped) in enumerate(cases):
            with self.subTest(case=index):
                key = self.root / name
                properties = self.root / f"fixture-{index}.properties"
                self.tools.create_release_key(self.root, key, properties, runner=keytool)
                store_file = properties.read_text(encoding="ascii").splitlines()[0]
                self.assertEqual(store_file, "storeFile=" + escaped)

    def test_new_key_java_properties_unicode_path_readback(self):
        def keytool(argv, **kwargs):
            Path(argv[argv.index("-keystore") + 1]).write_bytes(b"SYNTHETIC-NEW-KEY")
            return subprocess.CompletedProcess(argv, 0, "", "")

        name = "\u4e2d \U0001f600.jks"
        self.tools.create_release_key(
            self.root, self.root / name, self.root / "keystore.properties", runner=keytool
        )
        with patch.object(self.tools, "ignored", return_value=True), patch.dict(os.environ, {}, clear=True):
            self.assertEqual(self.tools.read_signing(self.root)[SIGNING_NAMES[0]], name)

    def test_clean_apk_and_aab_archives_accepted(self):
        for name, dex in (("release.apk", "classes.dex"), ("release.aab", "base/dex/classes.dex")):
            self.tools.audit_archive(self.archive(name, {dex: fixture_dex()}))

    def test_okhttp_logger_strings_are_not_dex_types(self):
        dex = fixture_dex(strings=("okhttp.MockWebServer", "okhttp3.mockwebserver.MockWebServer"))
        for name, entry in (("release.apk", "classes.dex"), ("release.aab", "base/dex/classes.dex")):
            with self.subTest(artifact=name):
                self.tools.audit_archive(self.archive(name, {entry: dex}))

    def test_unused_descriptor_string_is_not_a_class_or_type_reference(self):
        dex = fixture_dex(strings=("Lokhttp3/mockwebserver/MockWebServer;",))
        self.tools.audit_archive(self.archive(entries={"classes.dex": dex}))

    def test_logger_strings_do_not_hide_forbidden_dex_definitions(self):
        forbidden = ("Lokhttp3/mockwebserver/MockWebServer;", "Lmockwebserver3/MockWebServer;",
                     "Lcom/molotov/clender/FakeTransport;", "Lcom/molotov/clender/ReleaseTestHook;",
                     "Landroidx/test/runner/AndroidJUnitRunner;", "Lorg/robolectric/Robolectric;",
                     "Lcom/google/firebase/FirebaseApp;", "Landroidx/glance/GlanceId;")
        for name, entry in (("release.apk", "classes2.dex"), ("release.aab", "base/dex/classes2.dex")):
            for descriptor in forbidden:
                dex = fixture_dex(classes=(descriptor,), strings=("okhttp3.mockwebserver.MockWebServer",))
                with self.subTest(artifact=name, descriptor=descriptor), self.assertRaises(self.tools.ReleaseError):
                    self.tools.audit_archive(self.archive(name, {entry: dex}))

    def test_forbidden_type_reference_without_definition_is_rejected(self):
        for descriptor in ("Lokhttp3/mockwebserver/MockWebServer;", "[Lcom/molotov/clender/FakeTransport;"):
            dex = fixture_dex(referenced_types=(descriptor,))
            with self.subTest(descriptor=descriptor), self.assertRaises(self.tools.ReleaseError):
                self.tools.audit_archive(self.archive(entries={"classes.dex": dex}))

    def test_dex_class_definitions_need_not_follow_type_index_order(self):
        dex = bytearray(fixture_dex(classes=("Lexample/Alpha;", "Lexample/Beta;")))
        offset = struct.unpack_from("<I", dex, 100)[0]
        alpha, beta = bytes(dex[offset:offset + 32]), bytes(dex[offset + 32:offset + 64])
        # Both classes extend external java.lang.Object, so either order is valid.
        dex[offset:offset + 64] = beta + alpha
        self.tools.audit_archive(self.archive(entries={"classes.dex": seal_fixture_dex(dex)}))

    def test_dex_duplicate_class_definition_is_rejected(self):
        dex = bytearray(fixture_dex(classes=("Lexample/Alpha;", "Lexample/Beta;")))
        offset = struct.unpack_from("<I", dex, 100)[0]
        dex[offset + 32:offset + 64] = dex[offset:offset + 32]
        with self.assertRaises(self.tools.ReleaseError):
            self.tools.audit_archive(self.archive(entries={"classes.dex": seal_fixture_dex(dex)}))

    def test_dex_bounds_and_indices_fail_closed_even_with_valid_checksums(self):
        original = fixture_dex()
        type_off = struct.unpack_from("<I", original, 68)[0]
        class_off = struct.unpack_from("<I", original, 100)[0]
        # Mutate independent fields and re-seal, requiring structural validation.
        corruptions = ((32, len(original) + 4), (36, 0), (40, 0), (60, len(original) + 4),
                       (64, 0xFFFFFFFF), (100, len(original) - 4), (type_off, 0xFFFFFFFF),
                       (class_off, 0xFFFFFFFF))
        for offset, value in corruptions:
            dex = bytearray(original)
            struct.pack_into("<I", dex, offset, value)
            with self.subTest(offset=offset), self.assertRaises(self.tools.ReleaseError):
                self.tools.audit_archive(self.archive(entries={"classes.dex": seal_fixture_dex(dex)}))

    def test_malformed_dex_header_and_checksum_are_rejected(self):
        dex = fixture_dex()
        for data in (b"production", dex[:100], b"dex\n999\x00" + dex[8:], dex[:8] + b"\x00" * 4 + dex[12:]):
            with self.subTest(length=len(data)), self.assertRaises(self.tools.ReleaseError):
                self.tools.audit_archive(self.archive(entries={"classes.dex": data}))

    def test_logger_exception_does_not_allow_forbidden_non_dex_resources(self):
        for entry, data in (("assets/mock.txt", b"okhttp3.mockwebserver.MockWebServer"),
                            ("assets/transport.txt", b"FakeTransport"),
                            ("res/raw/test_ca.pem", b"fixture")):
            with self.subTest(entry=entry), self.assertRaises(self.tools.ReleaseError):
                self.tools.audit_archive(self.archive(entries={"classes.dex": fixture_dex(), entry: data}))

    def test_native_rejected_anywhere_in_apk_or_aab(self):
        for suffix in ("apk", "aab"):
            for entry in ("lib/arm64-v8a/libbad.so", "assets/hidden.SO", "base/lib/x86_64/libbad.so"):
                with self.subTest(suffix=suffix, entry=entry), self.assertRaises(self.tools.ReleaseError):
                    self.tools.audit_archive(self.archive(f"release.{suffix}", {entry: b"ELF"}))

    def test_archive_traversal_entries_rejected(self):
        for entry in ("../outside", "/absolute", "assets/../../outside", "assets\\..\\..\\outside"):
            with self.subTest(entry=entry), self.assertRaises(self.tools.ReleaseError):
                self.tools.audit_archive(self.archive(entries={entry: b"bad"}))

    def test_duplicate_archive_names_rejected(self):
        path = self.archive()
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(path, "a") as archive:
                archive.writestr("classes.dex", b"second")
        with self.assertRaises(self.tools.ReleaseError):
            self.tools.audit_archive(path)

    def test_test_hook_transport_and_test_ca_rejected(self):
        for suffix in ("apk", "aab"):
            for entry, content in (("classes.dex", fixture_dex(classes=("Lcom/molotov/clender/FakeTransport;",))),
                                   ("classes.dex", fixture_dex(classes=("Lokhttp3/mockwebserver/MockWebServer;",))),
                                   ("classes.dex", fixture_dex(classes=("Lcom/molotov/clender/ReleaseTestHook;",))),
                                   ("res/raw/test_ca.pem", b"SYNTHETIC-TEST-CA")):
                with self.subTest(suffix=suffix, entry=entry, content=content), self.assertRaises(self.tools.ReleaseError):
                    self.tools.audit_archive(self.archive(f"release.{suffix}", {entry: content}))

    def test_corrupt_or_missing_archive_fails_closed(self):
        bad = self.root / "bad.apk"
        bad.write_bytes(b"not a zip")
        for path in (bad, self.root / "missing.aab"):
            with self.subTest(path=path.name), self.assertRaises(self.tools.ReleaseError):
                self.tools.audit_archive(path)

    def test_hardened_decoded_manifest_accepted(self):
        self.tools.audit_manifest(MANIFEST)

    @staticmethod
    def manifest_xmltree(namespace="http://schemas.android.com/apk/res/android"):
        return ("E: manifest (line=1)\n"
                "  A: package=\"com.molotov.clender\" (Raw: \"com.molotov.clender\")\n"
                "  E: application (line=2)\n"
                f"    A: {namespace}:allowBackup(0x01010280)=false\n"
                f"    A: {namespace}:usesCleartextTraffic(0x010104ec)=false\n")

    def test_xmltree_accepts_full_android_uri_and_legacy_prefix(self):
        for namespace in ("http://schemas.android.com/apk/res/android", "android"):
            with self.subTest(namespace=namespace):
                root = self.tools.audit_manifest(self.tools.decode_xmltree(self.manifest_xmltree(namespace)))
                application = root.find("application")
                self.assertEqual("false", application.get(self.tools.NS + "usesCleartextTraffic"))
                self.assertEqual("false", application.get(self.tools.NS + "allowBackup"))

    def test_xmltree_does_not_promote_unknown_namespace_to_android(self):
        for namespace in ("unknown", "http://example.invalid/android", "https://schemas.android.com/apk/res/android",
                          "http://schemas.android.com/apk/res/android-extra"):
            with self.subTest(namespace=namespace), self.assertRaises(self.tools.ReleaseError):
                self.tools.audit_manifest(self.tools.decode_xmltree(self.manifest_xmltree(namespace)))

    def test_xmltree_missing_required_android_attribute_still_fails(self):
        tree = self.manifest_xmltree()
        tree = "\n".join(line for line in tree.splitlines() if "usesCleartextTraffic" not in line)
        with self.assertRaises(self.tools.ReleaseError):
            self.tools.audit_manifest(self.tools.decode_xmltree(tree))

    def test_xmltree_true_flags_are_rejected_in_full_and_legacy_namespaces(self):
        for namespace in ("http://schemas.android.com/apk/res/android", "android"):
            for flag in ("usesCleartextTraffic", "debuggable", "testOnly"):
                for value in ("true", "(type 0x12)0xffffffff"):
                    tree = self.manifest_xmltree(namespace)
                    if flag == "usesCleartextTraffic":
                        tree = tree.replace("usesCleartextTraffic(0x010104ec)=false", f"usesCleartextTraffic={value}")
                    else:
                        tree += f"    A: {namespace}:{flag}={value}\n"
                    with self.subTest(namespace=namespace, flag=flag, value=value), self.assertRaises(self.tools.ReleaseError):
                        self.tools.audit_manifest(self.tools.decode_xmltree(tree))

    def test_xmltree_duplicate_namespace_aliases_cannot_overwrite_flags(self):
        tree = self.manifest_xmltree() + "    A: android:usesCleartextTraffic=false\n"
        with self.assertRaises(self.tools.ReleaseError):
            self.tools.decode_xmltree(tree)

    def test_xmltree_rebound_android_prefix_is_rejected(self):
        tree = "N: android=http://example.invalid/android\n" + self.manifest_xmltree("android")
        with self.assertRaises(self.tools.ReleaseError):
            self.tools.decode_xmltree(tree)

    def test_manifest_rejects_debuggable_testonly_and_cleartext(self):
        for attribute in ("debuggable", "testOnly", "usesCleartextTraffic"):
            for value in ("true", "1", "@bool/runtime_flag"):
                with self.subTest(attribute=attribute, value=value), self.assertRaises(self.tools.ReleaseError):
                    self.tools.audit_manifest(MANIFEST.replace(f'{attribute}="false"', f'{attribute}="{value}"'))

    def test_manifest_malformed_missing_application_or_cleartext_policy_rejected(self):
        for xml in ("not xml", "<manifest/>", MANIFEST.replace(' android:usesCleartextTraffic="false"', "")):
            with self.subTest(xml=xml), self.assertRaises(self.tools.ReleaseError):
                self.tools.audit_manifest(xml)

    def test_badging_accepts_actual_and_legacy_min_sdk_26(self):
        package = "package: name='com.molotov.clender' versionCode='1' versionName='1.0.0'"
        for key in ("minSdkVersion", "sdkVersion"):
            with self.subTest(key=key):
                self.assertEqual(26, self.tools.audit_badging(package + f"\n{key}:'26'\ntargetSdkVersion:'36'\n"))

    def test_badging_rejects_wrong_missing_or_malformed_min_sdk(self):
        package = "package: name='com.molotov.clender'\n"
        invalid = ["", "targetSdkVersion:'26'", "application-label:'sdkVersion:26'"]
        invalid += [f"{key}:'{value}'" for key in ("minSdkVersion", "sdkVersion")
                    for value in ("25", "27", "026", "26extra", "", "O")]
        invalid += ["minSdkVersion:26", "minSdkVersion:'26' trailing"]
        for value in invalid:
            with self.subTest(value=value), self.assertRaises(self.tools.ReleaseError):
                self.tools.audit_badging(package + value)

    def test_badging_rejects_duplicate_and_conflicting_sdk_records(self):
        for records in ("minSdkVersion:'26'\nminSdkVersion:'25'", "sdkVersion:'26'\nsdkVersion:'25'",
                        "sdkVersion:'25'\nminSdkVersion:'26'", "sdkVersion:'26'\nminSdkVersion:'25'",
                        "minSdkVersion:'26'\nminSdkVersion:'26'", "sdkVersion:'26'\nminSdkVersion:'26'"):
            with self.subTest(records=records), self.assertRaises(self.tools.ReleaseError):
                self.tools.audit_badging("package: name='com.molotov.clender'\n" + records)

    def test_badging_rejects_wrong_missing_or_duplicate_package(self):
        for package in ("", "package: name='com.molotov.clender.debug'", "package: name='com.molotov.clender.extra'",
                        "package: name='com.molotov.clender'\npackage: name='example.other'",
                        "package: name='com.molotov.clender' name='example.other'"):
            with self.subTest(package=package), self.assertRaises(self.tools.ReleaseError):
                self.tools.audit_badging(package + "\nminSdkVersion:'26'")

    def test_metadata_contains_actual_hash_size_and_relative_path(self):
        path = self.archive()
        result = self.tools.artifact_metadata(self.root, path)
        self.assertEqual(result["path"], "release.apk")
        self.assertEqual(result["size_bytes"], path.stat().st_size)
        self.assertEqual(result["sha256"].lower(), hashlib.sha256(path.read_bytes()).hexdigest())
        self.assertNotIn(str(self.root), json.dumps(result))

    def test_metadata_hash_changes_when_artifact_changes(self):
        path = self.archive()
        before = self.tools.artifact_metadata(self.root, path)
        with zipfile.ZipFile(path, "a") as archive:
            archive.writestr("assets/version", b"2")
        self.assertNotEqual(before["sha256"], self.tools.artifact_metadata(self.root, path)["sha256"])

    def test_metadata_publication_and_outside_path_rejection(self):
        artifact = self.archive()
        output = self.root / "metadata.json"
        self.tools.write_metadata(self.root, output, [artifact])
        self.assertIn(hashlib.sha256(artifact.read_bytes()).hexdigest(), output.read_text().lower())
        with self.assertRaises(self.tools.ReleaseError):
            self.tools.write_metadata(self.root, self.root.parent / "metadata.json", [artifact])

    def test_tool_failure_preserves_exit_code_but_not_output(self):
        completed = subprocess.CompletedProcess(["fixture-tool"], 37, "FIXTURE-SECRET", "FIXTURE-PRIVATE-PATH")
        with patch.object(self.tools.subprocess, "run", return_value=completed):
            with self.assertRaises(self.tools.ReleaseError) as result:
                self.tools.run_tool(["fixture-tool", "check"], cwd=self.root)
        self.assertEqual(result.exception.exit_code, 37)
        self.assertNotIn("FIXTURE-SECRET", str(result.exception))
        self.assertNotIn("FIXTURE-PRIVATE-PATH", str(result.exception))

    def test_missing_tool_is_sanitized_failure(self):
        with patch.object(self.tools.subprocess, "run", side_effect=FileNotFoundError("FIXTURE-PRIVATE-PATH")):
            with self.assertRaises(self.tools.ReleaseError) as result:
                self.tools.run_tool(["missing"], cwd=self.root)
        self.assertNotIn("FIXTURE-PRIVATE-PATH", str(result.exception))

    def test_tool_success_uses_argv_cwd_and_no_shell(self):
        completed = subprocess.CompletedProcess(["fixture-tool"], 0, "safe", "")
        with patch.object(self.tools.subprocess, "run", return_value=completed) as run:
            self.tools.run_tool(["fixture-tool", "path with spaces"], cwd=self.root)
        args, kwargs = run.call_args
        self.assertEqual(args[0], ["fixture-tool", "path with spaces"])
        self.assertEqual(Path(kwargs["cwd"]), self.root)
        self.assertFalse(kwargs.get("shell", False))

    def test_metadata_cannot_overwrite_artifact_or_signing_config(self):
        path = self.archive()
        before = path.read_bytes()
        for target in (path, self.root / "keystore.properties"):
            with self.assertRaises(self.tools.ReleaseError):
                self.tools.write_metadata(self.root, target, [path])
        self.assertEqual(path.read_bytes(), before)

    def test_key_publish_race_preserves_competing_file(self):
        key = self.root / "new.jks"
        def race(argv, **kwargs):
            Path(argv[argv.index("-keystore") + 1]).write_bytes(b"OUR-SYNTHETIC")
            key.write_bytes(b"COMPETING-SYNTHETIC")
        with self.assertRaises(self.tools.ReleaseError):
            self.tools.create_release_key(self.root, key, self.root / "keystore.properties", runner=race)
        self.assertEqual(key.read_bytes(), b"COMPETING-SYNTHETIC")
        self.assertFalse((self.root / "keystore.properties").exists())

    @staticmethod
    def proto_network(cleartext="false"):
        # AAPT Resources.proto XmlNode/XmlElement/XmlAttribute, synthetic binary XML.
        def field(number, value):
            value = value.encode() if isinstance(value, str) else value
            size, encoded = len(value), bytearray()
            while size >= 128:
                encoded.append((size & 127) | 128)
                size >>= 7
            encoded.append(size)
            return bytes([number * 8 + 2]) + bytes(encoded) + value
        def node(name, attrs=(), children=()):
            return field(1, field(3, name) + b"".join(field(4, field(2, k) + field(3, v)) for k, v in attrs)
                         + b"".join(field(5, child) for child in children))
        return node("network-security-config", children=[node("base-config", [("cleartextTrafficPermitted", cleartext)],
                    [node("trust-anchors", children=[node("certificates", [("src", "system")])])])])

    def test_aab_proto_network_is_decoded_and_cleartext_rejected(self):
        self.tools.audit_network(self.tools.decode_proto_xml(self.proto_network()))
        with self.assertRaises(self.tools.ReleaseError):
            self.tools.audit_network(self.tools.decode_proto_xml(self.proto_network("true")))
        for malformed in (b"", b"\x0a\xff", b"\x0a\x7fshort"):
            with self.assertRaises(self.tools.ReleaseError):
                self.tools.decode_proto_xml(malformed)

    def release_fixture(self):
        for name in (".toolchain/jdk-17.0.20+8/bin/java.exe", ".toolchain/jdk-17.0.20+8/bin/jarsigner.exe",
                     ".toolchain/jdk-17.0.20+8/bin/keytool.exe", ".sdk/build-tools/36.0.0/aapt2.exe",
                     ".sdk/build-tools/36.0.0/zipalign.exe", ".sdk/build-tools/36.0.0/lib/apksigner.jar",
                     ".toolchain/bundletool-all.jar", "app/build/outputs/mapping/release/mapping.txt"):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"SYNTHETIC")
        apk = self.archive("release.apk", {"classes.dex": fixture_dex(), "res/xml/network_security_config.xml": b"binary"})
        aab = self.archive("release.aab", {"base/dex/classes.dex": fixture_dex(),
                           "base/res/xml/network_security_config.xml": self.proto_network()})
        certificate = b"SYNTHETIC-CERTIFICATE"
        commands = []
        def runner(argv, **kwargs):
            argv = list(map(str, argv))
            commands.append(argv)
            if "--print-certs" in argv:
                output = "Signer #1 certificate SHA-256 digest: " + hashlib.sha256(certificate).hexdigest()
            elif "badging" in argv:
                output = "package: name='com.molotov.clender'\nminSdkVersion:'26'\ntargetSdkVersion:'36'"
            elif "xmltree" in argv:
                if "AndroidManifest.xml" in argv:
                    output = self.manifest_xmltree()
                else:
                    output = 'E: network-security-config\n  E: base-config\n    A: cleartextTrafficPermitted=(type 0x12)0x0\n    E: trust-anchors\n      E: certificates\n        A: src="system"'
            elif "manifest" in argv:
                output = MANIFEST
            elif "-verify" in argv:
                output = "jar verified."
            elif "-printcert" in argv:
                output = "-----BEGIN CERTIFICATE-----\n" + base64.b64encode(certificate).decode() + "\n-----END CERTIFICATE-----"
            else:
                output = ""
            return subprocess.CompletedProcess(argv, 0, output, "")
        return apk, aab, commands, runner

    def test_full_artifact_audit_invokes_real_tool_contracts(self):
        apk, aab, commands, runner = self.release_fixture()
        output = self.root / "metadata.json"
        self.tools.audit_release(self.root, apk, aab, ".toolchain/bundletool-all.jar", output, runner=runner)
        self.assertTrue(output.is_file())
        flattened = "\n".join(" ".join(command) for command in commands)
        for marker in ("apksigner.jar verify", "--min-sdk-version 26", "zipalign.exe -c", "aapt2.exe dump xmltree",
                       "validate --bundle=", "dump manifest --bundle=", "jarsigner.exe", "-printcert"):
            self.assertIn(marker, flattened)
        self.assertNotIn("src/main", flattened)

    def test_each_audit_tool_failure_prevents_success_metadata(self):
        apk, aab, commands, runner = self.release_fixture()
        output = self.root / "metadata.json"
        self.tools.audit_release(self.root, apk, aab, ".toolchain/bundletool-all.jar", output, runner=runner)
        output.unlink()
        count = len(commands)
        for fail_at in range(count):
            invoked = []
            def fail(argv, **kwargs):
                invoked.append(argv)
                if len(invoked) == fail_at + 1:
                    raise self.tools.ReleaseError("Tool failed", exit_code=41)
                return runner(argv, **kwargs)
            with self.subTest(fail_at=fail_at), self.assertRaises(self.tools.ReleaseError) as result:
                self.tools.audit_release(self.root, apk, aab, ".toolchain/bundletool-all.jar", output, runner=fail)
            self.assertEqual(result.exception.exit_code, 41)
            self.assertFalse(output.exists())


class ReleaseEntrypointTests(unittest.TestCase):
    def test_release_version_metadata_is_v130_with_monotonic_code(self):
        source = (ANDROID_ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        expected_fields = (
            ("versionCode", r'^\s*versionCode\s*=\s*(\d+)\s*$', "2"),
            ("versionName", r'^\s*versionName\s*=\s*"([^"]+)"\s*$', "1.3.0"),
            ("applicationId", r'^\s*applicationId\s*=\s*"([^"]+)"\s*$', "com.molotov.clender"),
        )
        for field, pattern, expected in expected_fields:
            with self.subTest(field=field):
                self.assertEqual([expected], re.findall(pattern, source, re.MULTILINE))

    def test_entrypoints_reuse_isolation_and_offline_single_worker(self):
        for name in ("verify-all.ps1", "build-release.ps1"):
            with self.subTest(script=name):
                path = SCRIPTS / name
                self.assertTrue(path.is_file(), f"T48 {name} is not implemented")
                source = path.read_text(encoding="utf-8-sig")
                self.assertIn("env.ps1", source)
                self.assertIn("gradle.ps1", source)
                for option in ("--offline", "--no-daemon", "--max-workers=1", "--dependency-verification", "strict"):
                    self.assertIn(option, source)
                self.assertNotIn("--write-locks", source)

    def test_wrapper_failure_propagates_without_invoking_real_gradle(self):
        for name in ("verify-all.ps1", "build-release.ps1"):
            with self.subTest(script=name):
                source = SCRIPTS / name
                self.assertTrue(source.is_file(), f"T48 {name} is not implemented")
                scratch = ANDROID_ROOT / ".tmp"
                scratch.mkdir(exist_ok=True)
                with tempfile.TemporaryDirectory(prefix="release-wrapper-", dir=scratch) as temporary:
                    root = Path(temporary)
                    scripts = root / "scripts"
                    scripts.mkdir()
                    (scripts / name).write_bytes(source.read_bytes())
                    (scripts / "env.ps1").write_text("$AndroidRoot = Split-Path -Parent $PSScriptRoot\n")
                    (scripts / "gradle.ps1").write_text("[Console]::Error.WriteLine('FIXTURE-PRIVATE-ERROR')\nexit 37\n")
                    # The only executable acting as Python is a fixture, never the real auditor.
                    python = root / "fixture-python.cmd"
                    python.write_text("@exit /b 0\n")
                    environment = {k: v for k, v in os.environ.items() if k not in SIGNING_NAMES}
                    result = subprocess.run(
                        ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                         str(scripts / name), "-PythonExecutable", str(python)],
                        cwd=root, env=environment, capture_output=True, text=True, timeout=20,
                    )
                    self.assertEqual(result.returncode, 37, "entrypoint must preserve failed wrapper exit code")
                    if name == "build-release.ps1":
                        self.assertNotIn("FIXTURE-PRIVATE-ERROR", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
