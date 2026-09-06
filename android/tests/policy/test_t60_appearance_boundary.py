"""Exact appearance scope; unknown paths and user data stay denied."""
import subprocess
import unittest
from unittest.mock import patch
import test_ignore_and_boundaries as boundaries


class AppearanceBoundaryTests(unittest.TestCase):
    def check_paths(self, *paths):
        case = boundaries.IgnoreAndBoundaryTests()
        result = subprocess.CompletedProcess(['git'], 0, ''.join('?? ' + p + '\0' for p in paths), '')
        with patch.object(case, 'run_git', return_value=result):
            case.test_current_change_set_stays_inside_approved_android_task_boundaries()

    def test_cross_platform_appearance_exact_files_allowed(self):
        self.check_paths('main.py', 'app_icon.py', 'background.py', 'constants.py',
                         'theme_manager.py', 'ui/main_window.py', 'ui/app_settings.py',
                         'tests/test_appearance.py', 'doc/appearance-design.md',
                         'doc/tasks/T60-appearance-integration.md',
                         'doc/tasks/T61-pc-appearance.md', 'doc/tasks/T62-android-appearance.md',
                         'doc/tasks/T63-brand-icons.md', 'icon.ico',
                         'assets/generate_icons.py', 'assets/clender-icon.svg',
                         'tests/test_app_icon.py')

    def test_unknown_business_files_and_suffixes_denied(self):
        for path in ('database.py', 'config.py', 'ui/unapproved.py', 'background.py.bak',
                     'doc/tasks/T60-unapproved.md', 'data/config.json', 'dist/Clender.exe',
                     'dist/data/clender.db', 'assets/private.jpg'):
            with self.subTest(path=path), self.assertRaises(AssertionError):
                self.check_paths(path)
