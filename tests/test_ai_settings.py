import os
import unittest
from types import SimpleNamespace
from unittest import mock

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PyQt5.QtWidgets import QApplication, QTextEdit, QDialogButtonBox
from PyQt5.QtCore import QPoint
from PyQt5.QtTest import QSignalSpy

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

    def test_large_font_hint_and_personality_fit_with_reachable_buttons(self):
        import theme_manager
        old_font = self.app.font()
        old_style = self.app.styleSheet()
        old_palette = self.app.palette()
        self.addCleanup(lambda: self.app.setFont(old_font))
        self.addCleanup(lambda: self.app.setStyleSheet(old_style))
        self.addCleanup(lambda: self.app.setPalette(old_palette))
        config = {**self._base_config(), 'app_font_size_px': 20,
                  'system_prompt': '旧风格第一段', 'ai_personality': '人格第二段'}
        with mock.patch('config.load_config', return_value=config):
            theme_manager.apply_theme(self.app, config)
            dialog = SettingsDialog()
            self.addCleanup(dialog.close)
            dialog.show()
            self.app.processEvents()
            hint = dialog._personality_hint
            self.assertGreaterEqual(hint.height(), hint.heightForWidth(hint.width()))
            self.assertGreaterEqual(dialog._edit_personality.height(), 100)
            self.assertLessEqual(
                dialog._edit_personality.document().size().height(),
                dialog._edit_personality.viewport().height(),
            )
            self.assertLessEqual(dialog.height(), 800)
            for button in dialog.findChild(QDialogButtonBox).buttons():
                bottom = button.mapTo(dialog, QPoint(0, button.height())).y()
                self.assertLessEqual(bottom, dialog.height())
                self.assertTrue(button.isVisible())

    def test_personality_is_single_editor_and_cancel_keeps_legacy_config(self):
        original = {**self._base_config(), 'system_prompt': 'legacy style', 'ai_personality': 'persona'}
        with mock.patch('config.load_config', return_value=dict(original)), mock.patch('config.save_config') as save:
            dialog = SettingsDialog()
            self.addCleanup(dialog.close)
            self.assertEqual(1, len(dialog.findChildren(QTextEdit)))
            self.assertEqual('legacy style\n\npersona', dialog._edit_personality.toPlainText())
            for theme in ('light', 'dark'):
                for size in (8, 20):
                    with mock.patch('config.load_config', return_value={**original, 'theme': theme, 'app_font_size_px': size}):
                        dialog.apply_theme()
                        self.assertTrue(dialog._personality_hint.wordWrap())
                        self.assertIn('内置日程指令', dialog._personality_hint.text())
            dialog.reject()
            save.assert_not_called()

    def test_personality_save_clears_hidden_legacy_and_repeated_save_does_not_duplicate(self):
        stored = {**self._base_config(), 'system_prompt': 'legacy style', 'ai_personality': 'persona'}
        def save(value):
            stored.clear()
            stored.update(value)
        with mock.patch('config.load_config', side_effect=lambda: dict(stored)), mock.patch('config.save_config', side_effect=save), mock.patch('ui.ai_settings.QMessageBox.information'):
            for _ in range(2):
                dialog = SettingsDialog()
                self.addCleanup(dialog.close)
                spy = QSignalSpy(dialog.config_saved)
                dialog._save()
                self.assertEqual(1, len(spy))
                self.assertEqual('', stored['system_prompt'])
                self.assertEqual('legacy style\n\npersona', stored['ai_personality'])
            dialog = SettingsDialog()
            self.addCleanup(dialog.close)
            dialog._edit_personality.clear()
            dialog._save()
            self.assertEqual('', stored['system_prompt'])
            self.assertEqual('', stored['ai_personality'])

    def test_personality_save_failure_retains_draft_and_does_not_emit(self):
        original = {**self._base_config(), 'system_prompt': 'legacy', 'ai_personality': 'persona'}
        with mock.patch('config.load_config', return_value=original), mock.patch('config.save_config', side_effect=OSError('synthetic')), mock.patch('ui.ai_settings.QMessageBox.critical'):
            dialog = SettingsDialog()
            self.addCleanup(dialog.close)
            spy = QSignalSpy(dialog.config_saved)
            dialog._edit_personality.setPlainText('edited')
            dialog._save()
            self.assertEqual('edited', dialog._edit_personality.toPlainText())
            self.assertEqual('persona', original['ai_personality'])
            self.assertEqual('legacy', original['system_prompt'])
            self.assertEqual(0, len(spy))
            self.assertEqual(dialog.Rejected, dialog.result())

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

    def test_large_exact_token_values_round_trip_without_clipping(self):
        config = {**self._base_config(), 'max_tokens': 393216, 'context_window': 2097152}
        with mock.patch('config.load_config', return_value=config), mock.patch('config.save_config') as save, mock.patch('ui.ai_settings.QMessageBox.information'):
            dialog = SettingsDialog()
            self.addCleanup(dialog.close)
            self.assertEqual(393216, dialog._spin_max_tok.value())
            self.assertEqual(2097152, dialog._spin_ctx.value())
            dialog._spin_max_tok.setValue(1000001)
            dialog._spin_ctx.setValue(10000001)
            dialog._save()
            self.assertEqual(1000001, save.call_args.args[0]['max_tokens'])
            self.assertEqual(10000001, save.call_args.args[0]['context_window'])

    def test_output_cannot_consume_entire_context(self):
        with mock.patch('config.load_config', return_value=self._base_config()), mock.patch('config.save_config') as save, mock.patch('ui.ai_settings.QMessageBox.information'), mock.patch('ui.ai_settings.QMessageBox.warning') as warning:
            dialog = SettingsDialog()
            self.addCleanup(dialog.close)
            dialog._spin_ctx.setValue(4096)
            dialog._spin_max_tok.setValue(4096)
            dialog._save()
            save.assert_not_called()
            warning.assert_called_once()

    def _capability(self, context=None, output=None, source='unknown'):
        return SimpleNamespace(context_window=context, max_output_tokens=output,
                               source=source, source_url='https://docs.invalid/model' if source != 'unknown' else '')

    def test_fetch_uses_visible_model_and_reports_exact_official_limits(self):
        config = self._base_config()
        with mock.patch('config.load_config', return_value=config), mock.patch('config.save_config'), mock.patch('ui.ai_settings.fetch_models_list', return_value=(['first-model', 'selected-model'], None)), mock.patch('ui.ai_settings.resolve_model_capabilities') as resolve:
            resolve.side_effect = lambda model, *_: self._capability(1048576, 393216, 'official') if model == 'selected-model' else self._capability()
            dialog = SettingsDialog()
            self.addCleanup(dialog.close)
            dialog._cmb_model.setEditText('selected-model')
            dialog._fetch_models()
            self.assertEqual('selected-model', dialog._cmb_model.currentText())
            self.assertEqual(1048576, dialog._spin_ctx.value())
            self.assertEqual(393216, dialog._spin_max_tok.value())
            self.assertIn('官方文档', dialog._lbl_info.text())
            self.assertIn('1,048,576', dialog._lbl_info.text())
            self.assertNotIn('API 元数据', dialog._lbl_info.text())

    def test_fetch_missing_saved_model_uses_first_actual_model(self):
        with mock.patch('config.load_config', return_value=self._base_config()), mock.patch('config.save_config'), mock.patch('ui.ai_settings.fetch_models_list', return_value=(['first-model'], None)), mock.patch('ui.ai_settings.resolve_model_capabilities') as resolve:
            resolve.side_effect = lambda model, *_: self._capability(2000000, 500000, 'metadata') if model == 'first-model' else self._capability()
            dialog = SettingsDialog()
            self.addCleanup(dialog.close)
            dialog._fetch_models()
            self.assertEqual('first-model', dialog._cmb_model.currentText())
            self.assertEqual(2000000, dialog._spin_ctx.value())
            self.assertEqual(500000, dialog._spin_max_tok.value())

    def test_unknown_model_preserves_manual_values_without_inventing_limits(self):
        with mock.patch('config.load_config', return_value=self._base_config()), mock.patch('config.save_config'), mock.patch('ui.ai_settings.fetch_models_list', return_value=(['unknown-new-model'], None)), mock.patch('ui.ai_settings.resolve_model_capabilities', return_value=self._capability()):
            dialog = SettingsDialog()
            self.addCleanup(dialog.close)
            dialog._spin_ctx.setValue(3000000)
            dialog._spin_max_tok.setValue(333333)
            dialog._fetch_models()
            self.assertEqual(3000000, dialog._spin_ctx.value())
            self.assertEqual(333333, dialog._spin_max_tok.value())
            self.assertIn('未知', dialog._lbl_info.text())

    def test_fetch_only_output_limit_does_not_overrun_unknown_context(self):
        with mock.patch('config.load_config', return_value=self._base_config()), mock.patch('config.save_config'), mock.patch('ui.ai_settings.fetch_models_list', return_value=(['partial-model'], None)), mock.patch('ui.ai_settings.resolve_model_capabilities', return_value=self._capability(None, 131072, 'official')):
            dialog = SettingsDialog()
            self.addCleanup(dialog.close)
            dialog._fetch_models()
            self.assertEqual(128000, dialog._spin_ctx.value())
            self.assertEqual(4096, dialog._spin_max_tok.value())
            self.assertIn('131,072', dialog._lbl_info.text())

    def test_real_fetch_independent_ceilings_preserve_valid_output_budget(self):
        from tests.test_ai_client import FakeResponse
        for context, previous_output in ((65536, 4096), (8192, 8192)):
            stored = {**self._base_config(), 'max_tokens': previous_output}
            def save(value):
                stored.clear()
                stored.update(value)
            with self.subTest(context=context), mock.patch('config.load_config', side_effect=lambda: dict(stored)), mock.patch('config.save_config', side_effect=save), mock.patch('ai_client.requests.get', return_value=FakeResponse(200, {'data': [{'id': 'independent', 'context_window': context, 'max_output_tokens': context}]})), mock.patch('ui.ai_settings.QMessageBox.information'), mock.patch('ui.ai_settings.QMessageBox.warning') as warning:
                dialog = SettingsDialog()
                self.addCleanup(dialog.close)
                dialog._fetch_models()
                self.assertEqual(context, dialog._spin_ctx.value())
                self.assertLessEqual(dialog._spin_max_tok.value(), previous_output)
                self.assertLess(dialog._spin_max_tok.value() + max(32, int(context * 0.1)), context)
                dialog._save()
                warning.assert_not_called()
                self.assertEqual('independent', stored['model'])

    def test_endpoint_change_invalidates_cached_metadata(self):
        record = {'context_window': 1048576, 'max_output_tokens': 393216}
        config = {**self._base_config(), 'available_models_endpoint': 'https://saved.invalid/v1', 'model_capabilities': {'saved-model': record}}
        with mock.patch('config.load_config', return_value=config), mock.patch('ui.ai_settings.resolve_model_capabilities', return_value=self._capability()) as resolve:
            dialog = SettingsDialog()
            self.addCleanup(dialog.close)
            self.assertEqual(record, resolve.call_args.args[2])
            dialog._edit_ep.setText('https://different.invalid/v1')
            self.assertIsNone(resolve.call_args.args[2])
            dialog._edit_ep.setText('https://saved.invalid/v1')
            self.assertIsNone(resolve.call_args.args[2])

    def test_switch_model_updates_context_and_does_not_raise_output_budget(self):
        with mock.patch('config.load_config', return_value=self._base_config()), mock.patch('ui.ai_settings.resolve_model_capabilities') as resolve:
            resolve.side_effect = lambda model, *_: self._capability(2000000, 500000, 'official') if model == 'new-model' else self._capability()
            dialog = SettingsDialog()
            self.addCleanup(dialog.close)
            dialog._cmb_model.setEditText('new-model')
            self.assertEqual(2000000, dialog._spin_ctx.value())
            self.assertEqual(4096, dialog._spin_max_tok.value())

    def test_known_limit_save_rejects_excess_without_clipping_draft(self):
        for field, value in (('_spin_ctx', 2000001), ('_spin_max_tok', 500001)):
            with self.subTest(field=field), mock.patch('config.load_config', return_value=self._base_config()), mock.patch('config.save_config') as save, mock.patch('ui.ai_settings.QMessageBox.warning') as warning, mock.patch('ui.ai_settings.resolve_model_capabilities', return_value=self._capability(2000000, 500000, 'official')):
                dialog = SettingsDialog()
                self.addCleanup(dialog.close)
                dialog._spin_ctx.setValue(2000000)
                getattr(dialog, field).setValue(value)
                dialog._save()
                save.assert_not_called()
                warning.assert_called_once()
                self.assertEqual(value, getattr(dialog, field).value())

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
