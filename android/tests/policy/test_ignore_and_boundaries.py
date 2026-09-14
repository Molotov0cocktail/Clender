"""Git ignore, path isolation, tracked artifact, and diff-boundary policy."""

from __future__ import annotations

import re
from pathlib import PurePosixPath

from _support import ANDROID_ROOT, PolicyTestCase, rel


class IgnoreAndBoundaryTests(PolicyTestCase):
    MUST_IGNORE = (
        "android/.gradle/caches/example.bin",
        "android/.toolchain/jdk/example.bin",
        "android/.android/avd/device.img",
        "android/.sdk/platforms/example.bin",
        "android/local.properties",
        "android/analytics.settings",
        "android/keystore.properties",
        "android/release-key.jks",
        "android/release-key.keystore",
        "android/.idea/workspace.xml",
        "android/.kotlin/sessions/example.bin",
        "android/app/build/outputs/apk/debug/app-debug.apk",
        "android/build/report.txt",
        "android/release/app-release.aab",
        "android/app/reports/lint-results.html",
    )
    MUST_TRACK = (
        "android/settings.gradle.kts",
        "android/gradle/libs.versions.toml",
        "android/gradle/wrapper/gradle-wrapper.properties",
        "android/gradle/verification-metadata.xml",
        "android/app/src/main/java/com/molotov/clender/data/local/EventEntity.kt",
        "android/app/src/test/java/com/molotov/clender/data/local/EventLocalDataTest.kt",
        "android/scripts/verify-boundaries.ps1",
        "android/tests/policy/test_required_scaffold.py",
    )

    def _is_ignored(self, path: str) -> bool:
        result = self.run_git(["check-ignore", "-q", "--no-index", path])
        if result.returncode not in (0, 1):
            self.fail("git check-ignore policy command failed")
        return result.returncode == 0

    def test_generated_artifacts_and_secrets_are_ignored(self) -> None:
        self.require_file(".gitignore")
        for path in self.MUST_IGNORE:
            with self.subTest(path=path):
                self.assertTrue(self._is_ignored(path), f"sensitive/generated path is not ignored: {path}")

    def test_source_and_reproducibility_files_are_not_ignored(self) -> None:
        self.require_file(".gitignore")
        for path in self.MUST_TRACK:
            with self.subTest(path=path):
                self.assertFalse(self._is_ignored(path), f"required source file is unexpectedly ignored: {path}")

    def test_tracked_files_contain_no_android_generated_or_secret_artifacts(self) -> None:
        result = self.run_git(["ls-files", "-z"])
        self.assertEqual(0, result.returncode, "git ls-files policy command failed")
        tracked = [item for item in result.stdout.split("\0") if item]
        forbidden = re.compile(
            r"(?i)(?:^android/(?:\.gradle|\.toolchain|\.android|\.sdk|\.idea|release|.*?/build|.*?/reports)(?:/|$)|"
            r"^android/(?:local\.properties|analytics\.settings|keystore\.properties)$|"
            r"^android/.*\.(?:jks|keystore|apk|aab|db|sqlite|so)$)"
        )
        offenders = [path for path in tracked if forbidden.search(path)]
        self.assertEqual([], offenders, "tracked Android generated/secret artifacts are forbidden")

    def test_current_change_set_stays_inside_approved_android_task_boundaries(self) -> None:
        result = self.run_git(["status", "--porcelain=v1", "-z", "--untracked-files=all"])
        self.assertEqual(0, result.returncode, "git status policy command failed")
        entries = [item for item in result.stdout.split("\0") if item]
        paths = []
        for entry in entries:
            path = entry[3:] if len(entry) >= 4 else entry
            if " -> " in path:
                path = path.split(" -> ", 1)[1]
            paths.append(path.replace("\\", "/"))

        allowed_exact = {
            # T73: public release download links, checksums, and notes.
            "README.md",
            # T73: exact dual-platform v1.3.0 release task record.
            "doc/tasks/T73-dual-platform-v1.3.0-release.md",
            # T72: exact date-picker and AI-time repair task record.
            "doc/tasks/T72-android-date-picker-and-ai-time.md",
            # T71: one reviewed task record and the existing Windows personality tests.
            "doc/tasks/T71-personality-and-selected-alarm-sound.md",
            "tests/test_ai_settings.py",
            # T70: explicit user-authorized AI and system reminder alignment.
            "doc/tasks/T70-dual-platform-ai-alert-repair.md",
            "doc/tasks/T70-system-prompt-review.md",
            "models.py",
            "database.py",
            "event_service.py",
            "ai_service.py",
            "event_alerts.py",
            "ui/event_dialog.py",
            "ui/event_detail_dialog.py",
            "tests/test_event_alerts.py",
            "ai_client.py",
            "tests/test_ai_client.py",
            "tests/test_ai_service.py",
            "ui/ai_settings.py",
            "doc/tasks/T68-android-mixed-alert-ai.md",
            "doc/tasks/T67-android-ai-provider-repair.md",
            "doc/tasks/T66-android-usability-ai-alerts.md",
            "doc/tasks/T52-android-scheduling-calendar.md",
            "doc/tasks/T64-background-calendar-navigation-integration.md",
            # T60: explicit user-authorized cross-platform appearance scope.
            "main.py",
            "app_icon.py",
            "background.py",
            "constants.py",
            "theme_manager.py",
            "ui/main_window.py",
            "ui/app_settings.py",
            "tests/test_appearance.py",
            "doc/appearance-design.md",
            "doc/tasks/T60-appearance-integration.md",
            "doc/tasks/T61-pc-appearance.md",
            "doc/tasks/T62-android-appearance.md",
            "doc/tasks/T63-brand-icons.md",
            "icon.ico",
            "assets/generate_icons.py",
            "assets/clender-icon.svg",
            "tests/test_app_icon.py",
            "tests/test_startup_entry.py",
            "ui/calendar_widget.py",
            "ui/event_manager.py",
            "ui/ai_chat_widget.py",
            "ui/sidebar.py",
            "tests/test_ui_action_labels.py",
            "AGENTS.md",
            ".gitignore",
            "doc/tasks/progress.md",
            "doc/tasks/T49-android-layout-api-key-bugfix.md",
            "doc/tasks/T49-api-key-persistence.md",
            "doc/tasks/T49-calendar-layout.md",
            "doc/tasks/T49-windows-verification.md",
            "doc/tasks/T50-ai-conversation-bugfix.md",
            "doc/tasks/T51-android-local-time-bugfix.md",
            ".github/workflows/android.yml",
            "scripts/verify_frozen_single_instance.py",
            "tests/test_frozen_single_instance_smoke.py",
        }
        allowed_prefixes = (
            "android/",
            "doc/android-",
            "doc/tasks/T41-",
            "doc/tasks/T42-",
            "doc/tasks/T43-",
            "doc/tasks/T44-",
            "doc/tasks/T45-",
            "doc/tasks/T46-",
            "doc/tasks/T47-",
            "doc/tasks/T48-",
        )
        offenders = [
            path for path in paths
            if path not in allowed_exact and not path.startswith(allowed_prefixes)
        ]
        self.assertEqual([], offenders, "changes escaped the approved Android task boundary")

    def test_android_text_has_no_real_secrets_or_host_specific_paths(self) -> None:
        offenders = []
        patterns = (
            re.compile(r"(?i)[A-Z]:[/\\]Users[/\\][^/\\\s]+"),
            re.compile(r"(?i)(?:^|[/\\])dist[/\\]data(?:[/\\]|$)"),
            re.compile(r"(?i)^sdk\.dir\s*=", re.MULTILINE),
            re.compile(r"(?i)(?:storePassword|keyPassword)\s*[=:]\s*(?!<|\$|providers\.|System\.getenv)[^\s]+"),
            re.compile(r"\bsk-[A-Za-z0-9_-]{8,}"),
            re.compile(r"(?i)Authorization\s*[:=]\s*['\"](?:Bearer|Basic)\s+[A-Za-z0-9+/=_-]+"),
        )
        for path in self.production_text_files():
            text = path.read_text(encoding="utf-8")
            if any(pattern.search(text) for pattern in patterns):
                offenders.append(rel(path))
        self.assertEqual([], offenders, "Android source/config contains a secret or host-specific path")

    def test_toolchain_environment_is_confined_to_android_directory(self) -> None:
        env_script = self.read_text("scripts/env.ps1")
        gradle_script = self.read_text("scripts/gradle.ps1")
        combined = env_script + "\n" + gradle_script
        for variable in ("GRADLE_USER_HOME", "ANDROID_USER_HOME", "ANDROID_SDK_ROOT", "JAVA_HOME"):
            with self.subTest(variable=variable):
                self.assertIn(variable, combined, f"isolated toolchain variable missing: {variable}")
        self.assertNotRegex(combined, r"(?i)\$(?:HOME|CODEX_HOME)\b", "shared home variables must not be repurposed")
        self.assertRegex(combined, r"(?i)(Resolve-Path|GetFullPath|StartsWith)", "scripts must canonicalize and verify toolchain paths")


if __name__ == "__main__":
    import unittest

    unittest.main()
