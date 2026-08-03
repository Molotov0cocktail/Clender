import unittest
from unittest import mock

import startup_manager


class StartupManagerTests(unittest.TestCase):
    def test_build_command_quotes_frozen_executable_and_adds_silent_flag(self):
        command = startup_manager.build_startup_command(
            r"C:\Program Files\Clender\Clender.exe"
        )
        self.assertEqual(command, '"C:\\Program Files\\Clender\\Clender.exe" --silent')

    def test_enable_and_disable_current_user_run_value(self):
        key = mock.MagicMock()
        context = mock.MagicMock()
        context.__enter__.return_value = key
        with mock.patch.object(startup_manager, "is_supported_runtime", return_value=True), mock.patch.object(
            startup_manager.winreg, "OpenKey", return_value=context
        ), mock.patch.object(
            startup_manager, "build_startup_command", return_value='"C:\\Clender.exe" --silent'
        ), mock.patch.object(startup_manager.winreg, "SetValueEx") as set_value, mock.patch.object(
            startup_manager.winreg, "DeleteValue"
        ) as delete_value:
            startup_manager.set_startup_enabled(True)
            startup_manager.set_startup_enabled(False)

        set_value.assert_called_once_with(
            key, startup_manager.VALUE_NAME, 0, startup_manager.winreg.REG_SZ,
            '"C:\\Clender.exe" --silent',
        )
        delete_value.assert_called_once_with(key, startup_manager.VALUE_NAME)

    def test_enable_is_rejected_outside_frozen_windows_runtime(self):
        with mock.patch.object(startup_manager, "is_supported_runtime", return_value=False):
            with self.assertRaises(startup_manager.StartupError):
                startup_manager.set_startup_enabled(True)

    def test_missing_value_is_disabled_without_error(self):
        context = mock.MagicMock()
        context.__enter__.return_value = mock.Mock()
        with mock.patch.object(startup_manager.winreg, "OpenKey", return_value=context), mock.patch.object(
            startup_manager.winreg, "DeleteValue", side_effect=FileNotFoundError()
        ):
            startup_manager.set_startup_enabled(False)


if __name__ == "__main__":
    unittest.main()
