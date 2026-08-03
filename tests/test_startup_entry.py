import unittest
from unittest import mock

import main


class StartupEntryTests(unittest.TestCase):
    def run_main(self, argv, *, tray_available=True):
        app = mock.Mock()
        app.exec_.return_value = 0
        coordinator = mock.Mock()
        coordinator.acquire.return_value = True
        window = mock.Mock()
        window.has_system_tray.return_value = tray_available
        patches = [
            mock.patch.object(main, "QApplication", return_value=app),
            mock.patch.object(main, "SingleInstanceCoordinator", return_value=coordinator),
            mock.patch.object(main.config, "ensure_app_data_dir"),
            mock.patch.object(main, "configure_logging"),
            mock.patch.object(main.database, "init_db"),
            mock.patch.object(main.theme_manager, "apply_theme"),
            mock.patch.object(main, "MainWindow", return_value=window),
        ]
        for patcher in patches:
            patcher.start()
            self.addCleanup(patcher.stop)
        result = main.main(argv)
        return result, app, coordinator, window

    def test_silent_start_hides_main_window_but_keeps_event_loop(self):
        result, _app, coordinator, window = self.run_main(
            ["Clender.exe", "--silent"], tray_available=True
        )
        self.assertEqual(result, 0)
        coordinator.acquire.assert_called_once_with(activate_existing=False)
        window.show.assert_not_called()

    def test_silent_start_without_tray_falls_back_to_visible_window(self):
        _result, _app, _coordinator, window = self.run_main(
            ["Clender.exe", "--silent"], tray_available=False
        )
        window.show.assert_called_once_with()

    def test_normal_start_shows_window_and_activates_existing_instance(self):
        _result, _app, coordinator, window = self.run_main(["Clender.exe"])
        coordinator.acquire.assert_called_once_with(activate_existing=True)
        window.show.assert_called_once_with()


if __name__ == "__main__":
    unittest.main()
