"""No-Google-services, no-native, and resolved-policy task contracts."""

from __future__ import annotations

import hashlib
import os
import re
import zipfile
from pathlib import Path

from _support import ANDROID_ROOT, GENERATED_PARTS, PolicyTestCase, rel


FORBIDDEN_GOOGLE = re.compile(
    r"(?i)(com\.google\.gms|com\.google\.android\.gms|com\.google\.firebase|"
    r"com\.google\.android\.play|com\.google\.mlkit|google-services|"
    r"firebase-(?:bom|analytics|crashlytics|auth|messaging)|"
    r"play-services-(?:ads|auth|base|maps|location|wallet)|play-integrity)"
)
FORBIDDEN_NATIVE_CONFIG = re.compile(
    r"(?i)\b(?:externalNativeBuild|ndkBuild|abiFilters|kotlin\s*\{[^}]*linux|"
    r"androidNativeArm|androidNativeX64|cmake\s*\{)"
)

EXPECTED_RUNTIME_MODULES = {
    "runtime",
    "runtime-android",
    "runtime-annotation",
    "runtime-annotation-android",
    "runtime-saveable",
    "runtime-saveable-android",
}
EXPECTED_LIFECYCLE_MODULES = {
    "lifecycle-common",
    "lifecycle-common-java8",
    "lifecycle-common-jvm",
    "lifecycle-livedata-core",
    "lifecycle-livedata-core-ktx",
    "lifecycle-livedata",
    "lifecycle-process",
    "lifecycle-runtime",
    "lifecycle-runtime-android",
    "lifecycle-runtime-compose",
    "lifecycle-runtime-compose-android",
    "lifecycle-runtime-ktx",
    "lifecycle-runtime-ktx-android",
    "lifecycle-service",
    "lifecycle-viewmodel",
    "lifecycle-viewmodel-android",
    "lifecycle-viewmodel-ktx",
    "lifecycle-viewmodel-savedstate",
    "lifecycle-viewmodel-savedstate-android",
}
EXPECTED_SAVEDSTATE_MODULES = {
    "savedstate",
    "savedstate-android",
    "savedstate-compose",
    "savedstate-compose-android",
    "savedstate-ktx",
}
F_COMPILE_CONFIGURATIONS = {
    "debugAndroidTestCompileClasspath",
    "debugCompileClasspath",
    "debugUnitTestCompileClasspath",
    "releaseCompileClasspath",
    "releaseUnitTestCompileClasspath",
}
F_RUNTIME_CONFIGURATIONS = {
    "debugRuntimeClasspath",
    "debugUnitTestRuntimeClasspath",
    "releaseRuntimeClasspath",
    "releaseUnitTestRuntimeClasspath",
}
F_ATOMIC_GROUP_CONFIGURATIONS = F_COMPILE_CONFIGURATIONS | F_RUNTIME_CONFIGURATIONS
P0R_UNIT_TEST_CONFIGURATIONS = {
    "debugUnitTestCompileClasspath",
    "debugUnitTestRuntimeClasspath",
    "releaseUnitTestCompileClasspath",
    "releaseUnitTestRuntimeClasspath",
}
P0R_RUNTIME_AND_UNIT_TEST_CONFIGURATIONS = F_RUNTIME_CONFIGURATIONS | P0R_UNIT_TEST_CONFIGURATIONS
P0R_MAIN_COMPILE_RUNTIME_CONFIGURATIONS = {
    "debugAndroidTestCompileClasspath",
    "debugCompileClasspath",
    "debugRuntimeClasspath",
    "releaseCompileClasspath",
    "releaseRuntimeClasspath",
}
AUTHORIZED_F_GROUPS = {
    "androidx.compose.runtime",
    "androidx.lifecycle",
    "androidx.savedstate",
}
AUTHORIZED_F_COMPONENTS = {
    ("androidx.annotation", "annotation-experimental"),
    ("androidx.core", "core-viewtree"),
    ("androidx.profileinstaller", "profileinstaller"),
}
AUTHORIZED_P0R_COMPONENTS = {
    ("androidx.arch.core", "core-runtime"),
    ("androidx.concurrent", "concurrent-futures-ktx"),
    ("androidx.tracing", "tracing"),
    ("androidx.tracing", "tracing-ktx"),
    ("androidx.work", "work-runtime"),
    ("androidx.work", "work-runtime-ktx"),
    ("androidx.work", "work-testing"),
}
AUTHORIZED_P0R_COORDINATES = {
    ("com.google.guava", "listenablefuture", "1.0"),
}
AUTHORIZED_T62_COORDINATES = {
    ("androidx.exifinterface", "exifinterface", "1.4.2"),
}
# Hashes freeze every unaffected coordinate/version/configuration line and the complete
# lock configuration universe. It contains 84 active canBeResolved configurations plus
# the known stale lock-only androidApis entry. Only the earlier F deltas and the exact
# WorkManager 2.11.2 producer-attributed P0R delta are removed.
FROZEN_UNAFFECTED_LOCK_SHA256 = "71be16b27fb8c1febb163302d482cc289456701362539089118e461769adaf5a"
FROZEN_LOCK_CONFIGURATION_UNIVERSE_SHA256 = "327385ec3272aa0888516cf857c5986497dceed33008b7cb96749bc608848812"
STALE_LOCK_ONLY_CONFIGURATIONS = {"androidApis"}


class DependencyPolicyTests(PolicyTestCase):
    def test_background_exif_uses_exact_androidx_dependency(self) -> None:
        self._assert_locked_component(
            "androidx.exifinterface", "exifinterface", "1.4.2",
            F_COMPILE_CONFIGURATIONS | F_RUNTIME_CONFIGURATIONS,
        )
        decoder = self.read_text("app/src/main/java/com/molotov/clender/data/settings/BackgroundImageDecoder.kt")
        self.assertIn("import androidx.exifinterface.media.ExifInterface", decoder)
        self.assertNotIn("import android.media.ExifInterface", decoder)

    def _required_build_text(self) -> str:
        self.require_file("settings.gradle.kts")
        self.require_file("build.gradle.kts")
        self.require_file("app/build.gradle.kts")
        self.require_file("gradle/libs.versions.toml")
        return self.combined_text(self.gradle_sources()) + "\n" + self.read_text("gradle/libs.versions.toml")

    def _locked_components(self) -> dict[tuple[str, str], dict[str, set[str]]]:
        components: dict[tuple[str, str], dict[str, set[str]]] = {}
        for line in self.read_text("app/gradle.lockfile").splitlines():
            if not line or line.startswith("#") or line.startswith("empty="):
                continue
            coordinate, separator, configurations = line.partition("=")
            self.assertEqual("=", separator, f"malformed lock entry: {coordinate}")
            try:
                group, module, version = coordinate.rsplit(":", 2)
            except ValueError:
                self.fail(f"malformed locked coordinate: {coordinate}")
            versions = components.setdefault((group, module), {})
            self.assertNotIn(version, versions, f"duplicate lock entry: {coordinate}")
            versions[version] = set(configurations.split(","))
        return components

    def _assert_atomic_group(
        self,
        group: str,
        expected_modules: set[str],
        expected_version: str,
        configuration_overrides: dict[str, set[str]] | None = None,
    ) -> None:
        locked = self._locked_components()
        actual = {
            module: set(versions)
            for (component_group, module), versions in locked.items()
            if component_group == group
        }
        self.assertEqual(
            {module: {expected_version} for module in sorted(expected_modules)},
            actual,
            f"{group} artifact set and versions must resolve atomically to {expected_version}",
        )
        overrides = configuration_overrides or {}
        for module in sorted(expected_modules):
            with self.subTest(group=group, module=module):
                self.assertEqual(
                    overrides.get(module, F_ATOMIC_GROUP_CONFIGURATIONS),
                    locked[(group, module)][expected_version],
                    f"unexpected F configuration set for {group}:{module}:{expected_version}",
                )

    def _assert_locked_component(
        self,
        group: str,
        module: str,
        version: str,
        expected_configurations: set[str] | None = None,
    ) -> None:
        versions = self._locked_components().get((group, module), {})
        self.assertEqual({version}, set(versions), f"unexpected locked version for {group}:{module}")
        if expected_configurations is not None:
            self.assertEqual(
                expected_configurations,
                versions.get(version, set()),
                f"unexpected configuration set for {group}:{module}:{version}",
            )

    def test_google_services_and_firebase_are_not_declared(self) -> None:
        text = self._required_build_text()
        match = FORBIDDEN_GOOGLE.search(text)
        self.assertIsNone(match, f"Google service dependency/plugin is forbidden: {match.group(0) if match else ''}")

    def test_native_build_configuration_and_binaries_are_absent(self) -> None:
        text = self._required_build_text()
        self.assertNotRegex(text, FORBIDDEN_NATIVE_CONFIG, "native/NDK configuration is forbidden")
        forbidden_parts = {"jni", "jniLibs"}
        forbidden_suffixes = {".so", ".a", ".dll", ".dylib"}
        offenders = []
        for current, directories, names in os.walk(ANDROID_ROOT):
            directories[:] = [item for item in directories if item not in GENERATED_PARTS]
            current_path = Path(current)
            for name in names:
                path = current_path / name
                parts = set(path.relative_to(ANDROID_ROOT).parts)
                if parts & forbidden_parts or path.suffix.lower() in forbidden_suffixes:
                    offenders.append(rel(path))
        self.assertEqual([], offenders, "native sources or binaries are forbidden")

        graph_text = "\n".join(
            (
                self.read_text("app/gradle.lockfile"),
                self.read_text("app/build.gradle.kts"),
                self.read_text("gradle/libs.versions.toml"),
            )
        )
        for forbidden in (
            r"(?i)androidx\.glance:",
            r"(?i)androidx\.datastore:datastore-[^:\s=]*android",
            r"(?i)androidx\.work:work-(?:multiprocess|gcm|rxjava\d*)",
            r"(?i)libdatastore_shared_counter|(?:^|[/\\])(?:jni|lib)(?:[/\\])|\.(?:so|a|dll|dylib)\b",
        ):
            with self.subTest(forbidden=forbidden):
                self.assertNotRegex(graph_text, forbidden)

        forbidden_work_api = re.compile(
            r"\b(?:PeriodicWorkRequest|ExistingPeriodicWorkPolicy|OutOfQuotaPolicy|ForegroundInfo)\b|"
            r"\b(?:enqueueUniquePeriodicWork|getForegroundInfo(?:Async)?|setExpedited|setForegroundAsync|setForeground)\s*\("
        )
        work_api_offenders = []
        for path in sorted(self.path("app/src/main/java").rglob("*.kt")):
            if forbidden_work_api.search(path.read_text(encoding="utf-8")):
                work_api_offenders.append(rel(path))
        self.assertEqual(
            [],
            work_api_offenders,
            "periodic, expedited, foreground, and long-running WorkManager APIs are forbidden",
        )

        apk = self.require_file("app/build/outputs/apk/debug/app-debug.apk")
        with zipfile.ZipFile(apk) as archive:
            apk_entries = [name.replace("\\", "/").lower() for name in archive.namelist()]
        apk_native = [
            name
            for name in apk_entries
            if name.startswith(("jni/", "lib/")) or name.endswith((".so", ".a", ".dll", ".dylib"))
        ]
        self.assertEqual([], apk_native, "debug APK must not contain native payloads")
        self.assertFalse(any("libdatastore_shared_counter.so" in name for name in apk_entries))

    def test_runtime_dependency_audit_tasks_are_declared(self) -> None:
        text = self._required_build_text()
        for task in (
            "verifyNoGoogleServices",
            "verifyNoNativeRuntimeArtifacts",
            "verifyDebugApkNoNativeArtifacts",
            "verifyResolvedVersionsLocked",
        ):
            with self.subTest(task=task):
                self.assertIn(task, text, f"required dependency audit task missing: {task}")

    def test_completed_foundation_layers_do_not_import_later_feature_adapters(self) -> None:
        boundaries = {
            "app/src/main/java/com/molotov/clender/data/local/RoomEventRepository.kt": (
                "com.molotov.clender.domain.sync",
            ),
            "app/src/main/java/com/molotov/clender/data/settings/AppPreferences.kt": (
                "com.molotov.clender.data.network",
            ),
        }
        for path, forbidden_imports in boundaries.items():
            with self.subTest(path=path):
                self.require_file(path)
                source = self.read_text(path)
                offenders = [item for item in forbidden_imports if f"import {item}" in source]
                self.assertEqual(
                    [],
                    offenders,
                    f"completed lower layer imports a later/concrete feature adapter: {path}",
                )

    def test_compose_runtime_group_matches_f_atomically(self) -> None:
        self._assert_atomic_group(
            "androidx.compose.runtime",
            EXPECTED_RUNTIME_MODULES,
            "1.11.3",
        )

    def test_lifecycle_group_matches_f_atomically(self) -> None:
        self._assert_atomic_group(
            "androidx.lifecycle",
            EXPECTED_LIFECYCLE_MODULES,
            "2.9.4",
            {
                "lifecycle-common-java8": F_RUNTIME_CONFIGURATIONS,
                "lifecycle-service": F_RUNTIME_CONFIGURATIONS,
            },
        )

    def test_savedstate_group_matches_f_atomically(self) -> None:
        self._assert_atomic_group(
            "androidx.savedstate",
            EXPECTED_SAVEDSTATE_MODULES,
            "1.3.2",
        )

    def test_profileinstaller_matches_f_transitive_delta(self) -> None:
        self._assert_locked_component(
            "androidx.profileinstaller",
            "profileinstaller",
            "1.4.0",
            F_RUNTIME_CONFIGURATIONS,
        )

    def test_core_viewtree_matches_f_transitive_delta(self) -> None:
        self._assert_locked_component(
            "androidx.core",
            "core-viewtree",
            "1.0.0",
            F_RUNTIME_CONFIGURATIONS,
        )

    def test_annotation_experimental_matches_f_transitive_delta(self) -> None:
        versions = self._locked_components().get(
            ("androidx.annotation", "annotation-experimental"),
            {},
        )
        self.assertEqual(
            {"1.4.1", "1.5.0"},
            set(versions),
            "the debug AndroidTest producer edge must move from 1.4.0 to 1.4.1 while 1.5.0 stays frozen",
        )
        self.assertEqual(
            {"debugAndroidTestCompileClasspath"},
            versions.get("1.4.1", set()),
            "annotation-experimental:1.4.1 must remain scoped to the producer-selected AndroidTest configuration",
        )
        self.assertEqual(
            (F_COMPILE_CONFIGURATIONS | F_RUNTIME_CONFIGURATIONS) - {"debugAndroidTestCompileClasspath"},
            versions.get("1.5.0", set()),
            "annotation-experimental:1.5.0 must remain frozen on the other eight app classpaths",
        )

    def test_annotation_experimental_is_not_declared_directly(self) -> None:
        catalog = self.read_text("gradle/libs.versions.toml")
        app_build = self.read_text("app/build.gradle.kts")
        self.assertNotIn("annotation-experimental", catalog)
        self.assertNotIn("androidx.annotation:annotation-experimental", app_build)

    def test_dependency_alignment_has_no_resolution_bypass(self) -> None:
        build_text = self.combined_text(
            path for path in self.gradle_sources() if path.name.endswith((".gradle", ".gradle.kts"))
        )
        for forbidden in (r"\bforce\s*\(", r"\bstrictly\s*\(", r"\bresolutionStrategy\b"):
            with self.subTest(forbidden=forbidden):
                self.assertNotRegex(build_text, re.compile(forbidden))
        exclusions = re.findall(r"\bexclude\s*\([^\n]*\)", build_text)
        self.assertEqual(
            ['exclude(group = "androidx.datastore", module = "datastore-core")'],
            exclusions,
            "only the pre-existing DataStore JVM variant exclusion is allowed",
        )

        catalog = self.read_toml("gradle/libs.versions.toml")
        versions = catalog.get("versions", {})
        libraries = catalog.get("libraries", {})
        self.assertEqual("2.11.2", versions.get("work"))
        expected_work_aliases = {
            "androidx-work-runtime-ktx": "androidx.work:work-runtime-ktx",
            "androidx-work-testing": "androidx.work:work-testing",
        }
        actual_work_aliases = {
            alias: entry.get("module")
            for alias, entry in libraries.items()
            if isinstance(entry, dict) and str(entry.get("module", "")).startswith("androidx.work:")
        }
        self.assertEqual(expected_work_aliases, actual_work_aliases)
        for alias in expected_work_aliases:
            self.assertEqual({"ref": "work"}, libraries[alias].get("version"), alias)

        app_build = self.read_text("app/build.gradle.kts")
        declarations = re.findall(
            r"(?m)^\s*(implementation|testImplementation)\(libs\.([a-zA-Z0-9_.]+)\)\s*$",
            app_build,
        )
        work_declarations = [item for item in declarations if ".work." in f".{item[1]}."]
        self.assertEqual(
            [
                ("implementation", "androidx.work.runtime.ktx"),
                ("testImplementation", "androidx.work.testing"),
            ],
            work_declarations,
            "runtime must be implementation-scoped and testing must be testImplementation-scoped",
        )
        self.assertNotRegex(app_build, r"androidx\.work:[A-Za-z0-9_.-]+")

        direct_modules = {
            str(entry.get("module"))
            for entry in libraries.values()
            if isinstance(entry, dict) and entry.get("module")
        }
        for module in {
            "androidx.work:work-runtime",
            "androidx.tracing:tracing",
            "androidx.tracing:tracing-ktx",
            "androidx.lifecycle:lifecycle-livedata",
            "androidx.lifecycle:lifecycle-livedata-core-ktx",
            "androidx.lifecycle:lifecycle-service",
            "androidx.concurrent:concurrent-futures-ktx",
        }:
            with self.subTest(transitive_module=module):
                self.assertNotIn(module, direct_modules, "producer-attributed transitive component became direct")
                self.assertNotIn(module, app_build, "producer-attributed transitive component became inline direct")

    def test_lint_quality_and_annotation_opt_in_rules_remain_enabled(self) -> None:
        app_build = self.read_text("app/build.gradle.kts")
        for setting in (
            "abortOnError = true",
            "checkReleaseBuilds = true",
            "warningsAsErrors = true",
        ):
            with self.subTest(setting=setting):
                self.assertIn(setting, app_build)
        self.assertNotRegex(app_build, re.compile(r"(?i)baseline\s*="))
        disabled = re.search(r"disable\s*\+=\s*setOf\((.*?)\)", app_build, re.DOTALL)
        disabled_text = disabled.group(1) if disabled else ""
        for issue in ("UnsafeOptInUsageError", "UnsafeOptInUsageWarning"):
            with self.subTest(issue=issue):
                self.assertNotIn(issue, disabled_text)

    def test_complete_lock_universe_allowlist_stays_frozen_outside_f(self) -> None:
        locked = self._locked_components()
        expected_work = {
            ("androidx.work", "work-runtime-ktx"): F_ATOMIC_GROUP_CONFIGURATIONS,
            ("androidx.work", "work-runtime"): F_ATOMIC_GROUP_CONFIGURATIONS,
            ("androidx.work", "work-testing"): P0R_UNIT_TEST_CONFIGURATIONS,
        }
        self.assertEqual(
            {module for group, module in expected_work if group == "androidx.work"},
            {module for group, module in locked if group == "androidx.work"},
        )
        for (group, module), configurations in expected_work.items():
            with self.subTest(group=group, module=module):
                self._assert_locked_component(group, module, "2.11.2", configurations)

        producer_delta = {
            ("androidx.arch.core", "core-runtime"): ("2.2.0", F_ATOMIC_GROUP_CONFIGURATIONS),
            ("androidx.concurrent", "concurrent-futures-ktx"): (
                "1.1.0",
                P0R_RUNTIME_AND_UNIT_TEST_CONFIGURATIONS,
            ),
            ("androidx.tracing", "tracing"): (
                "1.2.0",
                P0R_RUNTIME_AND_UNIT_TEST_CONFIGURATIONS,
            ),
            ("androidx.tracing", "tracing-ktx"): ("1.2.0", F_RUNTIME_CONFIGURATIONS),
        }
        for (group, module), (version, configurations) in producer_delta.items():
            with self.subTest(group=group, module=module):
                self._assert_locked_component(group, module, version, configurations)

        listenablefuture = locked.get(("com.google.guava", "listenablefuture"), {})
        self.assertEqual(
            {"1.0", "9999.0-empty-to-avoid-conflict-with-guava"},
            set(listenablefuture),
            "WorkManager may only extend the pre-existing listenablefuture version set",
        )
        self.assertEqual(
            P0R_MAIN_COMPILE_RUNTIME_CONFIGURATIONS,
            listenablefuture.get("1.0", set()),
            "WorkManager producer configurations for listenablefuture:1.0 changed",
        )

        unaffected: list[str] = []
        configurations: set[str] = set()
        for line in self.read_text("app/gradle.lockfile").splitlines():
            if not line or line.startswith("#"):
                continue
            coordinate, separator, configuration_text = line.partition("=")
            self.assertEqual("=", separator, f"malformed lock entry: {coordinate}")
            configurations.update(configuration_text.split(","))
            if coordinate == "empty":
                unaffected.append(line)
                continue
            group, module, version = coordinate.rsplit(":", 2)
            if (
                group in AUTHORIZED_F_GROUPS
                or (group, module) in AUTHORIZED_F_COMPONENTS
                or (group, module) in AUTHORIZED_P0R_COMPONENTS
                or (group, module, version) in AUTHORIZED_P0R_COORDINATES
                or (group, module, version) in AUTHORIZED_T62_COORDINATES
            ):
                continue
            unaffected.append(line)

        unaffected_digest = hashlib.sha256(
            ("\n".join(unaffected) + "\n").encode("utf-8")
        ).hexdigest()
        configuration_digest = hashlib.sha256(
            ("\n".join(sorted(configurations)) + "\n").encode("utf-8")
        ).hexdigest()
        self.assertEqual(
            FROZEN_UNAFFECTED_LOCK_SHA256,
            unaffected_digest,
            "a coordinate, version, or configuration outside the authorized F/P0R/T62 deltas changed",
        )
        self.assertEqual(
            FROZEN_LOCK_CONFIGURATION_UNIVERSE_SHA256,
            configuration_digest,
            "the complete set of locked configurations changed",
        )
        self.assertEqual(85, len(configurations), "the lock configuration universe must stay frozen")
        self.assertEqual(
            84,
            len(configurations - STALE_LOCK_ONLY_CONFIGURATIONS),
            "expected 84 active canBeResolved configurations plus one stale lock-only entry",
        )
        self.assertTrue(
            STALE_LOCK_ONLY_CONFIGURATIONS <= configurations,
            "androidApis must remain documented as a stale lock-only configuration",
        )


if __name__ == "__main__":
    import unittest

    unittest.main()
