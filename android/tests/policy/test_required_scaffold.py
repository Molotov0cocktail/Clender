"""Required-file contract for the minimal Android foundation."""

from __future__ import annotations

from _support import PolicyTestCase


class RequiredScaffoldTests(PolicyTestCase):
    REQUIRED_FILES = (
        "settings.gradle.kts",
        "build.gradle.kts",
        "gradle.properties",
        "gradle/libs.versions.toml",
        "toolchain.lock.toml",
        "gradle/wrapper/gradle-wrapper.properties",
        "gradle/wrapper/gradle-wrapper.jar",
        "gradle/verification-metadata.xml",
        "app/build.gradle.kts",
        "app/proguard-rules.pro",
        "app/src/main/AndroidManifest.xml",
        "app/src/main/java/com/molotov/clender/app/ClenderApplication.kt",
        "app/src/main/java/com/molotov/clender/app/MainActivity.kt",
        "app/src/main/res/xml/backup_rules.xml",
        "app/src/main/res/xml/data_extraction_rules.xml",
        "config/detekt/detekt.yml",
        ".gitignore",
        "AGENTS.md",
        "README.md",
        "scripts/env.ps1",
        "scripts/bootstrap-toolchain.py",
        "scripts/gradle.ps1",
        "scripts/verify-boundaries.ps1",
        "scripts/verify-generated.ps1",
    )

    def test_all_required_foundation_files_exist(self) -> None:
        for relative in self.REQUIRED_FILES:
            with self.subTest(contract=relative):
                self.require_file(relative)

    def test_android_ci_workflow_exists_at_repository_boundary(self) -> None:
        workflow = self.path("../.github/workflows/android.yml").resolve()
        if not workflow.is_file():
            self.fail("required file missing: .github/workflows/android.yml")


if __name__ == "__main__":
    import unittest

    unittest.main()
