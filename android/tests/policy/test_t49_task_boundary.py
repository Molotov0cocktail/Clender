"""Exact task-document admission without widening production or data boundaries."""

from types import SimpleNamespace
import unittest
from unittest.mock import patch

import test_ignore_and_boundaries as boundary_module


class T49TaskBoundaryTests(unittest.TestCase):
    def check_paths(self, paths):
        case = boundary_module.IgnoreAndBoundaryTests(
            "test_current_change_set_stays_inside_approved_android_task_boundaries"
        )
        result = SimpleNamespace(
            returncode=0, stdout="".join(" M " + path + "\0" for path in paths)
        )
        with patch.object(case, "run_git", return_value=result):
            case.test_current_change_set_stays_inside_approved_android_task_boundaries()

    def test_four_exact_task_documents_are_accepted(self):
        self.check_paths([
            "doc/tasks/T49-android-layout-api-key-bugfix.md",
            "doc/tasks/T49-api-key-persistence.md",
            "doc/tasks/T49-calendar-layout.md",
            "doc/tasks/T49-windows-verification.md",
        ])

    def test_unknown_tasks_data_and_windows_source_are_still_rejected(self):
        for path in (
            "doc/tasks/T49-unapproved.md",
            "doc/tasks/T49-calendar-layout.md/extra",
            "database.py",
            "scripts/unapproved.py",
            "tests/unapproved.py",
            "data/config.json",
            "dist/Clender.exe",
        ):
            with self.subTest(path=path), self.assertRaises(AssertionError):
                self.check_paths([path])

    def test_existing_two_windows_harness_exceptions_remain_exact(self):
        self.check_paths([
            "scripts/verify_frozen_single_instance.py",
            "tests/test_frozen_single_instance_smoke.py",
        ])
        with self.assertRaises(AssertionError):
            self.check_paths(["scripts/verify_frozen_single_instance.py.extra"])
