"""T67 authorizes one exact task record without broadening unrelated paths."""
import subprocess
import unittest
from unittest.mock import patch

import test_ignore_and_boundaries as boundaries
import _support


class T67TaskBoundaryTests(unittest.TestCase):
    def test_live_kotlin_is_test_code_but_its_build_scripts_stay_scanned(self):
        root = _support.ANDROID_ROOT
        live = root / "app/src/liveTest/java"
        other = root / "app/src/liveTestOther/java"
        fixture = [
            (str(live), [], ["Synthetic.kt", "build.gradle", "build.gradle.kts"]),
            (str(other), [], ["Production.kt"]),
            (str(root / "tests/live"), [], ["live.init.gradle"]),
        ]
        with patch.object(_support.os, "walk", return_value=fixture):
            paths = boundaries.IgnoreAndBoundaryTests().gradle_sources()
        self.assertNotIn(live / "Synthetic.kt", paths)
        self.assertIn(live / "build.gradle", paths)
        self.assertIn(live / "build.gradle.kts", paths)
        self.assertIn(other / "Production.kt", paths)
        self.assertIn(root / "tests/live/live.init.gradle", paths)

    def check_paths(self, *paths):
        case = boundaries.IgnoreAndBoundaryTests()
        result = subprocess.CompletedProcess(
            ["git", "status"], 0, "".join("?? " + path + "\0" for path in paths), ""
        )
        with patch.object(case, "run_git", return_value=result):
            case.test_current_change_set_stays_inside_approved_android_task_boundaries()

    def test_exact_task_and_android_source_are_allowed(self):
        self.check_paths("doc/tasks/T67-android-ai-provider-repair.md",
                         "android/app/src/main/java/Example.kt")

    def test_other_task_names_and_suffixes_are_denied(self):
        for path in ("doc/tasks/T67-other.md",
                     "doc/tasks/T67-android-ai-provider-repair.md.bak",
                     "doc/tasks/T67-android-ai-provider-repair.md/child.md"):
            with self.subTest(path=path), self.assertRaises(AssertionError):
                self.check_paths(path)

    def test_unrelated_pc_paths_data_and_artifacts_are_denied(self):
        for path in ("event_service.py", "calendar_logic.py", "database.py",
                     "data/config.json", "dist/data/clender.db", "dist/Clender.exe"):
            with self.subTest(path=path), self.assertRaises(AssertionError):
                self.check_paths(path)
