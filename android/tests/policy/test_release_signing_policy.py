"""Release signing must be independent, mandatory, and secret-free."""

from __future__ import annotations

import re

from _support import PolicyTestCase, SIGNING_ENV_NAMES


class ReleaseSigningPolicyTests(PolicyTestCase):
    def _build_text(self) -> str:
        self.require_file("build.gradle.kts")
        app = self.read_text("app/build.gradle.kts")
        return app + "\n" + self.combined_text(self.gradle_sources())

    def test_release_uses_an_independent_non_debug_signing_config(self) -> None:
        text = self._build_text()
        self.assertRegex(text, r"signingConfigs\s*\{[\s\S]*?(?:create\s*\(\s*\"release\"\s*\)|getByName\s*\(\s*\"release\"\s*\))", "independent release signing config is required")
        self.assertRegex(text, r"(?:getByName\s*\(\s*\"release\"\s*\)|release\s*\{)[\s\S]*?signingConfig\s*=\s*signingConfigs\.(?:getByName\s*\(\s*\"release\"\s*\)|named\s*\(\s*\"release\"\s*\)\.get\s*\(\s*\))", "release build type must use release signing config")
        self.assertNotRegex(text, r"(?is)(?:release\s*\{|getByName\s*\(\s*\"release\"\s*\)).{0,1200}?(?:signingConfigs\.debug|signingConfigs\.getByName\s*\(\s*\"debug\"\s*\)|initWith\s*\([^)]*debug)", "release must never inherit or use debug signing")
        self.assertNotRegex(text, r"(?i)(?:\.android[/\\]debug\.keystore|debug\.keystore)", "debug keystore fallback is forbidden")

    def test_release_signing_inputs_are_external_and_complete(self) -> None:
        text = self._build_text()
        for name in SIGNING_ENV_NAMES:
            with self.subTest(environment=name):
                self.assertIn(name, text, f"release signing input is missing: {name}")
        self.assertIn("keystore.properties", text, "ignored keystore.properties fallback contract is required")
        self.assertNotRegex(text, r"(?i)(storePassword|keyPassword)\s*=\s*['\"][^'\"]+['\"]", "signing passwords must not be literals")
        self.assertNotRegex(text, r"(?i)storeFile\s*=\s*file\s*\(\s*['\"][^'\"]+\.(?:jks|keystore)['\"]", "keystore path must not be hard-coded")
        for marker in ("rootProject::file", "canonicalFile", "startsWith(androidRoot)", "check-ignore"):
            with self.subTest(marker=marker):
                self.assertIn(marker, text, "release keystore must remain inside an ignored Android path")

    def test_release_validation_and_policy_tasks_are_mandatory(self) -> None:
        text = self._build_text()
        for marker in (
            "validateReleaseSigning",
            "verifyReleaseSigningPolicy",
            "Release signing credentials are required",
            "isDebuggable = false",
            "isMinifyEnabled = true",
            "isShrinkResources = true",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, text, f"release policy marker missing: {marker}")
        self.assertRegex(text, r"(?is)(assembleRelease|bundleRelease|packageRelease|configureEach).{0,1600}(dependsOn|finalizedBy)\s*\([^)]*validateReleaseSigning", "release packaging tasks must depend on signing validation")
        self.assertNotRegex(text, r"(?is)(lintRelease|packageReleaseResources).{0,300}(dependsOn|finalizedBy)\s*\([^)]*validateReleaseSigning", "non-packaging release checks must not require signing credentials")


if __name__ == "__main__":
    import unittest

    unittest.main()
