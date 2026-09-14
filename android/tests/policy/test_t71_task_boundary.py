"""T71 admits exact task paths without broadening PC or user-data access."""
import subprocess
import unittest
from unittest.mock import patch

import test_ignore_and_boundaries as boundaries


class T71TaskBoundaryTests(unittest.TestCase):
    TASK = "doc/tasks/T71-personality-and-selected-alarm-sound.md"

    def check_paths(self, *paths):
        case = boundaries.IgnoreAndBoundaryTests()
        result = subprocess.CompletedProcess(
            ["git", "status"], 0, "".join("?? " + path + "\0" for path in paths), ""
        )
        with patch.object(case, "run_git", return_value=result):
            case.test_current_change_set_stays_inside_approved_android_task_boundaries()

    def test_exact_task_and_existing_personality_scope_are_allowed(self):
        self.check_paths(self.TASK, "ui/ai_settings.py", "ai_service.py", "tests/test_ai_service.py")

    def test_exact_settings_test_is_allowed(self):
        self.check_paths("tests/test_ai_settings.py")

    def test_task_suffixes_and_nearby_names_remain_denied(self):
        for path in (self.TASK + ".bak", self.TASK + "/child", "doc/tasks/T71-other.md"):
            with self.subTest(path=path), self.assertRaises(AssertionError):
                self.check_paths(path)

    def test_task_cannot_hide_unrelated_pc_or_private_data(self):
        for path in ("config.py", "webdav_sync.py", "ui/ai_settings.py.bak",
                     "tests/test_ai_service_other.py", "tests/test_ai_settings.py.bak",
                     "tests/test_ai_settings.py/child", "tests/test_ai_settings_other.py",
                     "data/config.json",
                     "dist/data/clender.db", "dist/Clender.exe"):
            with self.subTest(path=path), self.assertRaises(AssertionError):
                self.check_paths(path)
