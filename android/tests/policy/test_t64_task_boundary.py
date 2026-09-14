"""T64 adds one exact task path without granting any new PC source scope."""
import subprocess
import unittest
from unittest.mock import patch

import test_ignore_and_boundaries as boundaries

# T70 now explicitly authorizes database.py and event_service.py; use still-unapproved
# PC files below so these historical fixtures continue testing the current boundary.


class T64TaskBoundaryTests(unittest.TestCase):
    def check_paths(self, *paths):
        case = boundaries.IgnoreAndBoundaryTests()
        result = subprocess.CompletedProcess(
            ["git", "status"], 0, "".join("?? " + path + "\0" for path in paths), ""
        )
        with patch.object(case, "run_git", return_value=result):
            case.test_current_change_set_stays_inside_approved_android_task_boundaries()

    def test_exact_task_and_android_source_are_allowed(self):
        self.check_paths("doc/tasks/T64-background-calendar-navigation-integration.md",
                         "android/app/src/main/java/Example.kt")

    def test_other_task_names_and_suffixes_are_denied(self):
        for path in ("doc/tasks/T64-other.md",
                     "doc/tasks/T64-background-calendar-navigation-integration.md.bak",
                     "doc/tasks/T64-background-calendar-navigation-integration.md/child.md"):
            with self.subTest(path=path), self.assertRaises(AssertionError):
                self.check_paths(path)

    def test_task_does_not_authorize_additional_pc_paths(self):
        for path in ("webdav_sync.py", "calendar_logic.py", "ui/time_input.py",
                     "tests/test_chat_input.py", "config.py"):
            with self.subTest(path=path), self.assertRaises(AssertionError):
                self.check_paths("doc/tasks/T64-background-calendar-navigation-integration.md", path)

    def test_user_data_and_artifacts_remain_denied(self):
        for path in ("data/config.json", "dist/data/clender.db", "dist/Clender.exe"):
            with self.subTest(path=path), self.assertRaises(AssertionError):
                self.check_paths(path)
