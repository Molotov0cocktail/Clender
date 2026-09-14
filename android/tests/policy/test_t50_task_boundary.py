"""T50 adds exactly one task document exception; no real files are inspected."""

import subprocess
import unittest
from unittest.mock import patch

import test_ignore_and_boundaries as boundaries

# T70 now explicitly authorizes database.py and event_service.py; use still-unapproved
# PC files below so these historical fixtures continue testing the current boundary.


class T50TaskBoundaryTests(unittest.TestCase):
    def check_paths(self, *paths):
        case = boundaries.IgnoreAndBoundaryTests()
        result = subprocess.CompletedProcess(
            ["git", "status"], 0, "".join("?? " + path + "\0" for path in paths), ""
        )
        with patch.object(case, "run_git", return_value=result):
            case.test_current_change_set_stays_inside_approved_android_task_boundaries()

    def test_exact_t50_task_document_is_allowed(self):
        self.check_paths("doc/tasks/T50-ai-conversation-bugfix.md")

    def test_other_t50_names_and_suffixes_remain_forbidden(self):
        for path in (
            "doc/tasks/T50-other.md",
            "doc/tasks/T50-ai-conversation-bugfix.md.bak",
            "doc/tasks/T50-ai-conversation-bugfix.md/child.md",
            "doc/tasks/T50-ai-conversation-bugfix-extra.md",
            "doc/tasks/t50-ai-conversation-bugfix.md",
        ):
            with self.subTest(path=path), self.assertRaises(AssertionError):
                self.check_paths(path)

    def test_authorized_document_does_not_hide_windows_or_data_changes(self):
        for path in ("config.py", "config.py", "data/config.json", "dist/data/clender.db"):
            with self.subTest(path=path), self.assertRaises(AssertionError):
                self.check_paths("doc/tasks/T50-ai-conversation-bugfix.md", path)

    def test_existing_exact_exceptions_and_android_source_still_allowed(self):
        self.check_paths(
            "doc/tasks/T49-android-layout-api-key-bugfix.md",
            "doc/tasks/T49-windows-verification.md",
            "scripts/verify_frozen_single_instance.py",
            "tests/test_frozen_single_instance_smoke.py",
            "android/app/src/main/java/Example.kt",
        )

    def test_adjacent_windows_harness_paths_remain_forbidden(self):
        for path in ("scripts/verify_frozen_single_instance_extra.py",
                     "tests/test_frozen_single_instance_smoke_extra.py"):
            with self.subTest(path=path), self.assertRaises(AssertionError):
                self.check_paths(path)
