"""T73 allows only its exact release task record among new task paths."""
import subprocess
import unittest
from unittest.mock import patch

import test_ignore_and_boundaries as boundaries


class T73TaskBoundaryTests(unittest.TestCase):
    TASK = "doc/tasks/T73-dual-platform-v1.3.0-release.md"
    README = "README.md"

    def check_paths(self, *paths):
        case = boundaries.IgnoreAndBoundaryTests()
        result = subprocess.CompletedProcess(
            ["git", "status"], 0, "".join("?? " + path + "\0" for path in paths), ""
        )
        with patch.object(case, "run_git", return_value=result):
            case.test_current_change_set_stays_inside_approved_android_task_boundaries()

    def test_exact_task_is_allowed(self):
        self.check_paths(self.TASK)

    def test_exact_public_readme_is_allowed(self):
        self.check_paths(self.TASK, self.README)

    def test_suffixes_and_neighbor_tasks_remain_denied(self):
        for path in (self.TASK + ".bak", self.TASK + "/child", "doc/tasks/T73-other.md"):
            with self.subTest(path=path), self.assertRaises(AssertionError):
                self.check_paths(path)

    def test_task_does_not_allow_unrelated_or_private_paths(self):
        for path in (
            "config.py",
            "webdav_sync.py",
            "data/config.json",
            "dist/data/clender.db",
            "dist/Clender.exe",
            "README.md.bak",
        ):
            with self.subTest(path=path), self.assertRaises(AssertionError):
                self.check_paths(self.TASK, path)
