"""T66 authorizes one exact task record without broadening unrelated paths."""
import subprocess
import unittest
from unittest.mock import patch

import test_ignore_and_boundaries as boundaries


class T66TaskBoundaryTests(unittest.TestCase):
    def check_paths(self, *paths):
        case = boundaries.IgnoreAndBoundaryTests()
        result = subprocess.CompletedProcess(
            ["git", "status"], 0, "".join("?? " + path + "\0" for path in paths), ""
        )
        with patch.object(case, "run_git", return_value=result):
            case.test_current_change_set_stays_inside_approved_android_task_boundaries()

    def test_exact_task_and_android_source_are_allowed(self):
        self.check_paths("doc/tasks/T66-android-usability-ai-alerts.md",
                         "android/app/src/main/java/Example.kt")

    def test_other_task_names_and_suffixes_are_denied(self):
        for path in ("doc/tasks/T66-other.md",
                     "doc/tasks/T66-android-usability-ai-alerts.md.bak",
                     "doc/tasks/T66-android-usability-ai-alerts.md/child.md"):
            with self.subTest(path=path), self.assertRaises(AssertionError):
                self.check_paths(path)

    def test_unrelated_pc_paths_data_and_artifacts_are_denied(self):
        for path in ("event_service.py", "calendar_logic.py", "database.py",
                     "data/config.json", "dist/data/clender.db", "dist/Clender.exe"):
            with self.subTest(path=path), self.assertRaises(AssertionError):
                self.check_paths(path)
