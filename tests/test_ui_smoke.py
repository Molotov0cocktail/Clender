import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

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
            mock.patch("config.secret_store.get_api_key", return_value=""),
            mock.patch("config.secret_store.set_api_key"),
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
            side_effect=config.secret_store.SecretStoreError("backend unavailable"),
        ), mock.patch("ui.ai_settings.QMessageBox.critical") as critical:
            dialog._save()

        self.assertTrue(dialog.isVisible())
        self.assertEqual(saved, [])
        critical.assert_called_once()


if __name__ == "__main__":
    unittest.main()
