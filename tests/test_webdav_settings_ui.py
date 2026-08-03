import os
import unittest
from unittest import mock

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PyQt5.QtWidgets import QApplication, QLineEdit

from constants import DEFAULT_CONFIG
from ui.app_settings import AppSettingsDialog


class WebDAVSettingsDialogTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def setUp(self):
        self.config = dict(DEFAULT_CONFIG)
        self.config.update({
            "webdav_enabled": True,
            "webdav_url": "https://dav.example.test/root",
            "webdav_username": "user",
            "webdav_password": "password",
            "startup_enabled": False,
        })

    def make_dialog(self, *, startup_supported=True):
        patches = [
            mock.patch("ui.app_settings.cfg_mod.load_config", side_effect=lambda: dict(self.config)),
            mock.patch("ui.app_settings.theme_manager.get_current_theme", return_value={
                "frame_bg": "#fff", "text_color": "#111", "title_color": "#222",
                "primary": "#333", "primary_text": "#fff", "primary_hover": "#444",
            }),
            mock.patch("ui.app_settings.startup_manager.is_supported_runtime", return_value=startup_supported),
        ]
        for patcher in patches:
            patcher.start()
            self.addCleanup(patcher.stop)
        dialog = AppSettingsDialog()
        self.addCleanup(dialog.close)
        return dialog

    def test_fields_are_loaded_and_password_is_masked(self):
        dialog = self.make_dialog()
        self.assertEqual(dialog._webdav_url.objectName(), "webdavUrl")
        self.assertEqual(dialog._webdav_username.text(), "user")
        self.assertEqual(dialog._webdav_password.text(), "password")
        self.assertEqual(dialog._webdav_password.echoMode(), QLineEdit.Password)
        self.assertEqual(dialog._startup_enabled.objectName(), "startupEnabled")

    def test_connection_test_uses_unsaved_form_values(self):
        dialog = self.make_dialog()
        requested = []
        dialog.webdav_test_requested.connect(requested.append)
        dialog._webdav_url.setText("https://dav.example.test/new")
        dialog._webdav_username.setText("new-user")
        dialog._webdav_password.setText("new-password")

        dialog._request_webdav_test()

        self.assertEqual(requested[0]["webdav_url"], "https://dav.example.test/new")
        self.assertEqual(requested[0]["webdav_password"], "new-password")

    def test_save_and_sync_persists_complete_config_then_emits(self):
        dialog = self.make_dialog()
        saved_signals = []
        sync_signals = []
        dialog.config_saved.connect(saved_signals.append)
        dialog.webdav_sync_requested.connect(lambda: sync_signals.append(True))

        with mock.patch("ui.app_settings.startup_manager.set_startup_enabled") as set_startup, mock.patch(
            "ui.app_settings.cfg_mod.save_config"
        ) as save_config:
            dialog._save_and_sync()

        set_startup.assert_called_once_with(False)
        self.assertEqual(save_config.call_args.args[0]["webdav_password"], "password")
        self.assertEqual(len(saved_signals), 1)
        self.assertEqual(sync_signals, [True])

    def test_config_failure_rolls_back_startup_and_keeps_dialog_open(self):
        dialog = self.make_dialog()
        dialog.show()
        dialog._startup_enabled.setChecked(True)
        emitted = []
        dialog.config_saved.connect(emitted.append)

        with mock.patch("ui.app_settings.startup_manager.set_startup_enabled") as set_startup, mock.patch(
            "ui.app_settings.cfg_mod.save_config", side_effect=OSError("disk failed")
        ), mock.patch("ui.app_settings.QMessageBox.critical"):
            dialog._save()

        self.assertEqual(set_startup.call_args_list, [mock.call(True), mock.call(False)])
        self.assertEqual(emitted, [])
        self.assertTrue(dialog.isVisible())

    def test_startup_control_is_disabled_in_development_runtime(self):
        dialog = self.make_dialog(startup_supported=False)
        self.assertFalse(dialog._startup_enabled.isEnabled())
        self.assertIn("打包", dialog._startup_enabled.toolTip())


if __name__ == "__main__":
    unittest.main()
