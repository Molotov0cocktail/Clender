import os
import unittest
from unittest import mock

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PyQt5.QtWidgets import QApplication

from ui.ai_settings import SettingsDialog


class AISettingsModelFetchTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def _base_config(self):
        return {
            "theme": "light",
            "api_endpoint": "https://saved.invalid/v1",
            "api_key": "saved-placeholder",
            "model": "saved-model",
            "available_models": [],
            "temperature": 0.7,
            "max_tokens": 4096,
            "context_window": 128000,
            "thinking_enabled": True,
            "think_effort": "high",
            "system_prompt": "",
            "ai_personality": "",
        }

    def test_missing_endpoint_or_key_does_not_save_or_fetch(self):
        for endpoint, api_key in (
            ("", "form-placeholder"),
            ("https://form.invalid/v1", ""),
        ):
            with self.subTest(endpoint=endpoint, has_key=bool(api_key)), mock.patch(
                "ui.ai_settings.cfg_mod.load_config",
                return_value=self._base_config(),
            ), mock.patch(
                "ui.ai_settings.cfg_mod.save_config"
            ) as save_config, mock.patch(
                "ui.ai_settings.fetch_models_list"
            ) as fetch_models:
                dialog = SettingsDialog()
                self.addCleanup(dialog.close)
                dialog.show()
                self.app.processEvents()
                dialog._edit_ep.setText(endpoint)
                dialog._edit_key.setText(api_key)

                dialog._fetch_models()

                save_config.assert_not_called()
                fetch_models.assert_not_called()
                self.assertTrue(dialog._lbl_info.text())
                self.assertIn("API", dialog._lbl_info.text())
                self.assertTrue(dialog._lbl_info.isVisible())

    def test_current_form_connection_is_saved_before_fetching_models(self):
        operations = []
        base_config = self._base_config()

        def save_config(config):
            operations.append(("save", dict(config)))

        def fetch_models():
            operations.append(("fetch", None))
            return ["form-model"], ""

        with mock.patch(
            "ui.ai_settings.cfg_mod.load_config", return_value=base_config
        ), mock.patch(
            "ui.ai_settings.cfg_mod.save_config", side_effect=save_config
        ), mock.patch(
            "ui.ai_settings.fetch_models_list", side_effect=fetch_models
        ):
            dialog = SettingsDialog()
            self.addCleanup(dialog.close)
            dialog._edit_ep.setText("https://form.invalid/v1")
            dialog._edit_key.setText("form-placeholder")

            dialog._fetch_models()

        self.assertEqual([operation[0] for operation in operations], ["save", "fetch"])
        saved = operations[0][1]
        self.assertEqual(saved["api_endpoint"], "https://form.invalid/v1")
        self.assertEqual(saved["api_key"], "form-placeholder")


if __name__ == "__main__":
    unittest.main()
