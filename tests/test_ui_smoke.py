import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PyQt5.QtCore import Qt
from PyQt5.QtWidgets import QApplication

import config
import database
from ui.main_window import MainWindow
from ui.ai_settings import SettingsDialog
from models import Conversation


class UISmokeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        data_dir = Path(self.temp_dir.name) / "data"
        patches = [
            mock.patch.object(config, "APP_DATA_DIR", str(data_dir)),
            mock.patch.object(config, "CONFIG_FILE", str(data_dir / "config.json")),
            mock.patch.object(config, "DB_PATH", str(data_dir / "clender.db")),
            mock.patch.object(database, "DB_PATH", str(data_dir / "clender.db")),
            mock.patch.object(database, "SAMPLE_FLAG_FILE", str(data_dir / "sample.flag")),
        ]
        for patcher in patches:
            patcher.start()
            self.addCleanup(patcher.stop)
        config.ensure_app_data_dir()
        database.init_db()

    def test_main_window_constructs_and_switches_views(self):
        window = MainWindow(self.app)
        self.addCleanup(window.close)
        self.assertTrue(
            all(isinstance(conv, Conversation) for conv in window._ai_chat._convs.values())
        )

        for mode in ("month", "week", "day"):
            window._calendar._switch_view(mode)
            self.assertEqual(window._calendar._view_mode, mode)
        window._toggle_theme()
        self.assertIn(config.get_theme(), ("light", "dark"))

    def test_settings_save_failure_stays_open_and_reports_error(self):
        dialog = SettingsDialog()
        self.addCleanup(dialog.close)
        dialog.show()
        saved = []
        dialog.config_saved.connect(lambda: saved.append(True))
        dialog._edit_key.setText("new-secret")

        with mock.patch(
            "ui.ai_settings.cfg_mod.save_config",
            side_effect=OSError("disk unavailable"),
        ), mock.patch("ui.ai_settings.QMessageBox.critical") as critical:
            dialog._save()

        self.assertTrue(dialog.isVisible())
        self.assertEqual(saved, [])
        critical.assert_called_once()

    def test_settings_save_round_trips_api_key_in_json(self):
        dialog = SettingsDialog()
        self.addCleanup(dialog.close)
        dialog.show()
        saved = []
        dialog.config_saved.connect(lambda: saved.append(True))
        dialog._edit_key.setText("json-secret")

        with mock.patch("ui.ai_settings.QMessageBox.information"):
            dialog._save()

        self.assertEqual(saved, [True])
        self.assertEqual(config.load_config()["api_key"], "json-secret")

    def test_existing_instance_activation_restores_without_losing_maximized_state(self):
        window = MainWindow(self.app)
        self.addCleanup(window.close)
        window.setWindowState(Qt.WindowMaximized | Qt.WindowMinimized)
        window.hide()

        with mock.patch(
            "ui.main_window.QApplication.activeModalWidget", return_value=None
        ), mock.patch.object(window, "show") as show, mock.patch.object(
            window, "raise_"
        ) as raise_window, mock.patch.object(
            window, "activateWindow"
        ) as activate_window:
            window.activate_existing_instance()

        state = window.windowState()
        self.assertTrue(state & Qt.WindowMaximized)
        self.assertFalse(state & Qt.WindowMinimized)
        show.assert_called_once_with()
        raise_window.assert_called_once_with()
        activate_window.assert_called_once_with()

    def test_existing_instance_activation_prefers_active_modal(self):
        window = MainWindow(self.app)
        self.addCleanup(window.close)
        modal = mock.Mock()

        with mock.patch(
            "ui.main_window.QApplication.activeModalWidget", return_value=modal
        ), mock.patch.object(window, "show") as show:
            window.activate_existing_instance()

        show.assert_not_called()
        modal.show.assert_called_once_with()
        modal.raise_.assert_called_once_with()
        modal.activateWindow.assert_called_once_with()

    def test_tray_restore_uses_shared_activation_path(self):
        window = MainWindow(self.app)
        self.addCleanup(window.close)

        with mock.patch.object(window, "activate_existing_instance") as activate:
            window._show_from_tray()

        activate.assert_called_once_with()


if __name__ == "__main__":
    unittest.main()
