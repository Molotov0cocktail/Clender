"""Android CI must enforce, rather than weaken, the foundation gates."""

from __future__ import annotations

import re

from _support import PolicyTestCase


class AndroidCiPolicyTests(PolicyTestCase):
    WORKFLOW = "../.github/workflows/android.yml"

    def _workflow(self) -> str:
        path = self.path(self.WORKFLOW).resolve()
        if not path.is_file():
            self.fail("required file missing: .github/workflows/android.yml")
        try:
            return path.read_text(encoding="utf-8")
        except (OSError, UnicodeError) as exc:
            self.fail(f"cannot read Android CI workflow: {type(exc).__name__}")
        raise AssertionError("unreachable")

    def test_ci_has_read_only_permissions_and_wrapper_validation(self) -> None:
        text = self._workflow()
        self.assertRegex(text, r"(?m)^permissions:\s*\n(?:^[ \t]+.*\n)*?^[ \t]+contents:\s*read\s*$", "CI permissions must be contents: read")
        self.assertRegex(text, r"(?i)(gradle/actions/wrapper-validation|wrapper-validation-action)", "Gradle wrapper validation is required")
        self.assertRegex(text, r"(?i)(java-version:\s*['\"]?17|jdk\s*17)", "CI must use Java 17")
        self.assertRegex(text, r"(?m)^\s*timeout-minutes:\s*\d+\s*$", "CI requires a bounded job timeout")
        for action in re.findall(r"(?m)^\s*uses:\s*([^\s#]+)", text):
            with self.subTest(action=action):
                self.assertRegex(action, r"@[0-9a-f]{40}$", "third-party actions must be pinned to immutable commits")

    def test_ci_runs_all_device_free_foundation_gates(self) -> None:
        text = self._workflow()
        required_markers = (
            "verify-generated.ps1",
            "testDebugUnitTest",
            "lintDebug",
            "detekt",
            "ktlintCheck",
            "verify-boundaries.ps1",
            "verifyReleaseSigningPolicy",
            "verifyNoGoogleServices",
            "verifyNoNativeRuntimeArtifacts",
            "verifyResolvedVersionsLocked",
            "assembleDebug",
        )
        for marker in required_markers:
            with self.subTest(marker=marker):
                self.assertIn(marker, text, f"Android CI gate missing: {marker}")

    def test_ci_proves_unsigned_release_fails_closed(self) -> None:
        text = self._workflow()
        self.assertIn("assembleRelease", text, "CI must execute the real unsigned release packaging entry")
        self.assertRegex(text, r"(?is)(unset|Remove-Item|env:)[\s\S]{0,1800}CLENDER_ANDROID_KEYSTORE", "CI must explicitly clear release signing inputs")
        self.assertRegex(text, r"(?is)(validateReleaseSigning|assembleRelease)[\s\S]{0,1200}(expected|must fail|exit 1|throw)", "CI must fail if unsigned release unexpectedly succeeds")
        self.assertRegex(text, r"(?is)assembleRelease[\s\S]{0,1200}Release signing credentials are required", "CI must verify the exact safe signing failure marker")

    def test_ci_has_no_gate_bypasses_or_secret_artifact_uploads(self) -> None:
        text = self._workflow()
        forbidden = (
            r"(?i)continue-on-error:\s*true",
            r"--no-verify-metadata",
            r"(?:^|\s)-x\s+(?:test|lint|detekt|ktlintCheck)",
            r"\|\|\s*true",
            r"(?i)path:\s*.*(?:keystore|\.jks|\.keystore|keystore\.properties)",
        )
        for pattern in forbidden:
            with self.subTest(pattern=pattern):
                self.assertNotRegex(text, pattern, "Android CI weakens or leaks a required gate")


if __name__ == "__main__":
    import unittest

    unittest.main()
