"""Version, repository, wrapper, and dependency verification policy."""

from __future__ import annotations

import re

from _support import PolicyTestCase


_DYNAMIC_VERSION = re.compile(r"(?:\+|latest(?:\.|$)|snapshot|[\[(].*[,;].*[\])])", re.IGNORECASE)
_INLINE_COORDINATE = re.compile(r"['\"][A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[^'\"]+['\"]")


class GradleVersionPolicyTests(PolicyTestCase):
    def test_android_sdk_namespace_and_java17_are_explicit(self) -> None:
        text = self.read_text("app/build.gradle.kts")
        contracts = {
            "namespace": r'namespace\s*=\s*"com\.molotov\.clender"',
            "applicationId": r'applicationId\s*=\s*"com\.molotov\.clender"',
            "minSdk 26": r"minSdk\s*=\s*26\b",
            "compileSdk 36": r"compileSdk\s*=\s*36\b",
            "targetSdk 36": r"targetSdk\s*=\s*36\b",
            "Java 17 source": r"sourceCompatibility\s*=\s*JavaVersion\.VERSION_17",
            "Java 17 target": r"targetCompatibility\s*=\s*JavaVersion\.VERSION_17",
            "Kotlin JVM 17": r"(?:jvmToolchain\s*\(\s*17\s*\)|jvmTarget\s*=\s*(?:JvmTarget\.JVM_17|\"17\"))",
        }
        for name, pattern in contracts.items():
            with self.subTest(contract=name):
                self.assertRegex(text, pattern, f"missing exact build contract: {name}")

    def test_wrapper_version_and_checksum_are_pinned(self) -> None:
        text = self.read_text("gradle/wrapper/gradle-wrapper.properties")
        match = re.search(r"^distributionUrl=(.+)$", text, re.MULTILINE)
        self.assertIsNotNone(match, "wrapper distributionUrl must be explicit")
        url = match.group(1).strip() if match else ""
        self.assertRegex(url, r"gradle-[0-9]+\.[0-9]+(?:\.[0-9]+)?-bin\.zip$", "wrapper must use an exact -bin distribution")
        self.assertNotRegex(url, _DYNAMIC_VERSION, "wrapper version must not be dynamic")
        self.assertRegex(text, r"(?m)^distributionSha256Sum=[0-9a-fA-F]{64}$", "wrapper SHA-256 must be pinned")
        jar = self.require_file("gradle/wrapper/gradle-wrapper.jar")
        self.assertGreater(jar.stat().st_size, 0, "gradle-wrapper.jar must not be empty")

    def test_downloaded_toolchain_archives_and_sdk_packages_are_locked(self) -> None:
        lock = self.read_toml("toolchain.lock.toml")
        archives = lock.get("archives", {})
        self.assertEqual(
            {"jdk17", "jdk21_tests", "android_command_line_tools", "gradle"},
            set(archives),
            "every bootstrap archive must have an exact lock entry",
        )
        for name, item in archives.items():
            with self.subTest(archive=name):
                self.assertRegex(item.get("url", ""), r"^https://", "toolchain archives require HTTPS")
                self.assertRegex(item.get("sha256", ""), r"^[0-9a-f]{64}$", "toolchain archive SHA-256 must be exact")
        packages = lock.get("android_sdk_packages", {})
        self.assertEqual("36", packages.get("platform"))
        self.assertRegex(packages.get("platform_revision", ""), r"^\d+$")
        self.assertRegex(packages.get("platform_extension", ""), r"^\d+$")
        self.assertEqual("36.0.0", packages.get("build_tools"))
        self.assertNotIn("platform_tools", packages, "rolling platform-tools cannot be claimed as reproducibly installed")
        self.assertNotIn("emulator", packages, "rolling emulator cannot be claimed as reproducibly installed")
        observed = lock.get("observed_rolling_packages", {})
        self.assertRegex(observed.get("platform_tools", ""), r"^\d+\.\d+\.\d+$")
        self.assertRegex(observed.get("emulator", ""), r"^\d+\.\d+\.\d+$")

    def test_bootstrap_consumes_the_lock_and_verifies_downloads(self) -> None:
        text = self.read_text("scripts/bootstrap-toolchain.py")
        for marker in (
            "toolchain.lock.toml",
            "tomllib",
            "sha256",
            "android_sdk_packages",
            "sdkmanager",
            "source.properties",
            "ANDROID_USER_HOME",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, text, f"bootstrap must consume and validate {marker}")

    def test_version_catalog_has_no_dynamic_versions_or_missing_refs(self) -> None:
        catalog = self.read_toml("gradle/libs.versions.toml")
        versions = catalog.get("versions")
        self.assertIsInstance(versions, dict, "version catalog must define [versions]")
        versions = versions if isinstance(versions, dict) else {}
        self.assertTrue(versions, "version catalog [versions] must not be empty")
        for key, value in versions.items():
            with self.subTest(version=key):
                self.assertIsInstance(value, str, f"version {key} must be a string")
                self.assertTrue(value.strip(), f"version {key} must not be empty")
                self.assertNotRegex(value, _DYNAMIC_VERSION, f"version {key} must be exact")
        for section_name in ("libraries", "plugins"):
            section = catalog.get(section_name, {})
            self.assertIsInstance(section, dict, f"[{section_name}] must be a table")
            for alias, entry in section.items():
                if not isinstance(entry, dict):
                    continue
                ref = entry.get("version", {}).get("ref") if isinstance(entry.get("version"), dict) else entry.get("version.ref")
                if ref is not None:
                    with self.subTest(alias=alias, ref=ref):
                        self.assertIn(ref, versions, f"{section_name}.{alias} references missing version {ref}")

    def test_build_scripts_use_catalog_and_safe_repositories(self) -> None:
        settings = self.read_text("settings.gradle.kts")
        self.assertIn("RepositoriesMode.FAIL_ON_PROJECT_REPOS", settings, "project repositories must be rejected")
        combined = self.combined_text(self.gradle_sources())
        self.assertNotRegex(combined, r"\bjcenter\s*\(", "jcenter is forbidden")
        self.assertNotRegex(combined, r"\bmavenLocal\s*\(", "mavenLocal is forbidden")
        self.assertNotRegex(combined, r"maven\s*\{[^}]*url\s*=\s*uri\s*\(\s*['\"]http://", "cleartext Maven repositories are forbidden")
        self.assertNotRegex(combined, _INLINE_COORDINATE, "direct versioned coordinates are forbidden; use the catalog")

    def test_dependency_verification_is_strict_and_checksum_backed(self) -> None:
        root = self.read_xml("gradle/verification-metadata.xml")
        xml_text = self.read_text("gradle/verification-metadata.xml")
        self.assertTrue(root.tag.endswith("verification-metadata"), "verification metadata root is invalid")
        self.assertRegex(xml_text, r"<sha256\s+value=['\"][0-9a-fA-F]{64}['\"]", "verification metadata needs SHA-256 entries")
        self.assertRegex(xml_text, r"<verify-metadata>\s*true\s*</verify-metadata>", "artifact metadata verification must be enabled")
        self.assertNotRegex(xml_text, r"(?i)trusted-artifacts[^>]*(?:group|name)=['\"]\*", "global trusted-artifact wildcards are forbidden")
        self.assertNotRegex(xml_text, r"(?i)<(?:trusted-artifacts|ignored-keys|md5|sha1)\b", "verification exceptions and weak hashes are forbidden")
        all_gradle = self.combined_text(self.gradle_sources()) + "\n" + self.read_text("gradle.properties")
        self.assertNotRegex(all_gradle, r"(?i)(verification[-_. ]?mode\s*[=:]\s*(?:off|lenient)|--no-verify-metadata)", "dependency verification must not be weakened")

    def test_dependency_locking_is_strict_and_lockfiles_are_committed(self) -> None:
        build = self.read_text("build.gradle.kts")
        self.assertIn("LockMode.STRICT", build, "dependency locking must fail on missing lock entries")
        self.assertIn("lockAllConfigurations", build, "all configurations must participate in dependency locking")
        for relative in ("app/gradle.lockfile", "settings-gradle.lockfile"):
            with self.subTest(lockfile=relative):
                text = self.read_text(relative)
                self.assertTrue(text.strip(), f"{relative} must not be empty")
                self.assertNotRegex(text, _DYNAMIC_VERSION, f"{relative} must not contain dynamic versions")


if __name__ == "__main__":
    import unittest

    unittest.main()
