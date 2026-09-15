"""T74 dialog visual language contracts; fixtures contain no application data."""
import os
os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
import unittest
from datetime import date
from unittest import mock

from PyQt5.QtWidgets import (
    QApplication,
    QCheckBox,
    QDialogButtonBox,
    QLabel,
    QLineEdit,
    QPushButton,
    QScrollArea,
    QTextBrowser,
    QTextEdit,
    QWidget,
)

import theme_manager
from constants import DEFAULT_CONFIG
from floating_window_logic import FloatingWindowSettings
from models import Event, EventType
from typography import app_scale_from_config, floating_scale_from_config
from ui.ai_settings import SettingsDialog
from ui.app_settings import AppSettingsDialog
from ui.daily_floating_window import DailyFloatingWindow
from ui.event_detail_dialog import EventDetailDialog
from ui.event_dialog import EventDialog

DARK = theme_manager.DARK_THEME


def base_config(**overrides):
    config = dict(DEFAULT_CONFIG)
    config.update({
        'theme': 'dark',
        'app_font_size_px': 13,
        'floating_font_size_px': 13,
        'floating_window_enabled': False,
    })
    config.update(overrides)
    return config


def compact(qss):
    return qss.replace(' ', '')


class DialogVisualTestBase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def patch_config(self, config):
        patcher = mock.patch('config.load_config', side_effect=lambda: dict(config))
        patcher.start()
        self.addCleanup(patcher.stop)
        return config

    def make_app_settings(self):
        with mock.patch('startup_manager.is_supported_runtime', return_value=False):
            dialog = AppSettingsDialog()
        self.addCleanup(dialog.close)
        return dialog


class EventDialogVisualTests(DialogVisualTestBase):
    def make_dialog(self, **overrides):
        self.patch_config(base_config(**overrides))
        dialog = EventDialog(date(2026, 9, 15))
        self.addCleanup(dialog.close)
        return dialog

    def test_ok_button_carries_primary_role_and_cancel_stays_neutral(self):
        dialog = self.make_dialog()
        box = dialog.findChild(QDialogButtonBox)
        ok = box.button(QDialogButtonBox.Ok)
        cancel = box.button(QDialogButtonBox.Cancel)
        self.assertEqual(ok.property('btnClass'), 'primary')
        self.assertNotEqual(cancel.property('btnClass'), 'primary')
        self.assertEqual(ok.text(), '确定')
        self.assertEqual(cancel.text(), '取消')

    def test_dark_input_qss_uses_input_surface_and_soft_border(self):
        dialog = self.make_dialog()
        qss = dialog.styleSheet()
        self.assertIn(DARK['border_soft'], qss)
        self.assertTrue(
            DARK['surface_sunken'] in qss or DARK['input_bg'] in qss,
            '输入区必须使用 surface_sunken 或 input_bg 令牌',
        )
        self.assertIn(DARK['primary'], qss)

    def test_section_labels_exist_with_object_name_and_role_style(self):
        dialog = self.make_dialog()
        labels = dialog.findChildren(QLabel, 'eventDialogSection')
        texts = {label.text() for label in labels}
        self.assertIn('基本信息', texts)
        self.assertIn('提醒策略', texts)
        scale = app_scale_from_config(base_config())
        qss = compact(dialog.styleSheet())
        self.assertIn('QLabel#eventDialogSection', qss)
        self.assertIn('font-weight:bold', qss)
        self.assertIn(DARK['primary'], qss)
        self.assertIn(f'font-size:{scale.section_title_px}px', qss)

    def test_form_keeps_single_checkbox_and_data_contract(self):
        dialog = self.make_dialog()
        self.assertEqual(len(dialog.findChildren(QCheckBox)), 1)
        self.assertTrue(hasattr(dialog, '_notification'))
        self.assertTrue(hasattr(dialog, '_timer_minutes'))
        data = dialog.get_data()
        self.assertEqual(data['event_type'], 'reminder')
        self.assertEqual(data['start_time'], '2026-09-15 09:00')
        self.assertIsNone(data['end_time'])


class AISettingsVisualTests(DialogVisualTestBase):
    def make_dialog(self, **overrides):
        self.patch_config(base_config(**overrides))
        dialog = SettingsDialog()
        self.addCleanup(dialog.close)
        return dialog

    def test_save_button_carries_primary_role(self):
        dialog = self.make_dialog()
        box = dialog.findChild(QDialogButtonBox)
        save = box.button(QDialogButtonBox.Save)
        cancel = box.button(QDialogButtonBox.Cancel)
        self.assertEqual(save.property('btnClass'), 'primary')
        self.assertNotEqual(cancel.property('btnClass'), 'primary')
        self.assertEqual(save.text(), '保存')

    def test_personality_title_object_name_and_single_editor_preserved(self):
        dialog = self.make_dialog()
        self.assertEqual(
            dialog._lbl_personality.objectName(), 'aiSettingsPersonalityTitle'
        )
        self.assertEqual(len(dialog.findChildren(QTextEdit)), 1)

    def test_section_label_matches_app_settings_section_pattern(self):
        dialog = self.make_dialog()
        self.assertTrue(dialog.findChildren(QLabel, 'aiSettingsSection'))
        scale = app_scale_from_config(base_config())
        qss = compact(dialog.styleSheet())
        self.assertIn('QLabel#aiSettingsSection', qss)
        self.assertIn('QLabel#aiSettingsPersonalityTitle', qss)
        self.assertIn('font-weight:bold', qss)
        self.assertIn(DARK['primary'], qss)
        self.assertIn(f'font-size:{scale.section_title_px}px', qss)

        app_dialog = self.make_app_settings()
        app_qss = compact(app_dialog.styleSheet())
        self.assertIn('QLabel#settingsSection', app_qss)
        self.assertIn('font-weight:bold', app_qss)
        self.assertIn(DARK['primary'], app_qss)
        self.assertIn(f'font-size:{scale.section_title_px}px', app_qss)


class AppSettingsVisualTests(DialogVisualTestBase):
    def make_dialog(self, **overrides):
        self.patch_config(base_config(**overrides))
        return self.make_app_settings()

    def test_settings_save_keeps_object_name_and_primary_role(self):
        dialog = self.make_dialog()
        self.assertEqual(dialog._btn_save.objectName(), 'settingsSave')
        self.assertEqual(dialog._btn_save.property('btnClass'), 'primary')
        self.assertEqual(dialog._btn_save.text(), '保存并关闭')

    def test_dialog_qss_styles_primary_role_instead_of_id_selector(self):
        dialog = self.make_dialog()
        qss = dialog.styleSheet()
        self.assertIn('btnClass="primary"', qss)
        self.assertNotIn('QPushButton#settingsSave', qss)
        self.assertIn(DARK['accent_gradient_start'], qss)
        self.assertIn(DARK['accent_gradient_end'], qss)

    def test_section_label_has_accent_separator_style(self):
        dialog = self.make_dialog()
        qss = compact(dialog.styleSheet())
        self.assertIn('QLabel#settingsSection', qss)
        self.assertIn(f'border-left:3pxsolid{DARK["primary"]}', qss)
        self.assertIn('font-weight:bold', qss)

    def test_scroll_footer_structure_and_object_names_preserved(self):
        dialog = self.make_dialog()
        scroll = dialog.findChild(QScrollArea, 'settingsScroll')
        self.assertIsNotNone(scroll)
        self.assertIsNotNone(dialog.findChild(QWidget, 'settingsContent'))
        self.assertFalse(scroll.isAncestorOf(dialog._btn_save))
        names = (
            'settingsTitle', 'settingsSection', 'startupEnabled',
            'appFontSlider', 'appFontSpin', 'floatingFontSlider', 'floatingFontSpin',
            'webdavTitle', 'webdavEnabled', 'webdavUrl', 'webdavUsername',
            'webdavPassword', 'webdavTestButton', 'webdavSyncButton',
            'webdavStatus', 'settingsSave',
        )
        for name in names:
            with self.subTest(name=name):
                self.assertTrue(dialog.findChildren(QWidget, name))


class EventDetailVisualTests(DialogVisualTestBase):
    def make_dialog(self):
        self.patch_config(base_config())
        event = Event(
            id=5,
            event_type=EventType.REMINDER,
            title='详情夹具',
            start_time='2026-09-15 09:00',
            description='备注',
        )
        dialog = EventDetailDialog(event)
        self.addCleanup(dialog.close)
        return dialog

    def test_close_button_is_ghost_and_read_only_kept(self):
        dialog = self.make_dialog()
        box = dialog.findChild(QDialogButtonBox)
        self.assertEqual(box.standardButtons(), QDialogButtonBox.Close)
        close = box.button(QDialogButtonBox.Close)
        self.assertEqual(close.property('btnClass'), 'ghost')
        self.assertTrue(dialog._description.isReadOnly())

    def test_dark_qss_uses_soft_border_and_section_label(self):
        dialog = self.make_dialog()
        qss = dialog.styleSheet()
        self.assertIn(DARK['border_soft'], qss)
        self.assertTrue(dialog.findChildren(QLabel, 'eventDetailSection'))


class FloatingWindowVisualTests(DialogVisualTestBase):
    def make_window(self, **overrides):
        values = base_config(**overrides)
        self.patch_config(values)
        with mock.patch(
            'ui.daily_floating_window.EventService.get_events_overlapping_range',
            return_value=[],
        ):
            window = DailyFloatingWindow()
        self.addCleanup(window.shutdown)
        window.apply_settings(FloatingWindowSettings.from_config(values))
        self.app.processEvents()
        return window, values

    def test_root_qss_uses_raised_surface_and_soft_border(self):
        window, _ = self.make_window()
        qss = window.styleSheet()
        self.assertIn(DARK['frame_bg'], qss)
        self.assertIn(DARK['surface_raised'], qss)
        self.assertIn(DARK['border_soft'], qss)

    def test_object_names_unique_and_no_text_widgets(self):
        window, _ = self.make_window()
        self.assertEqual(
            len(window.findChildren(QPushButton, 'floatingViewButton')), 3
        )
        self.assertEqual(len(window.findChildren(QLineEdit, 'floatingAiInput')), 1)
        self.assertEqual(window.findChildren(QTextEdit), [])
        self.assertEqual(window.findChildren(QTextBrowser), [])

    def test_active_view_property_mechanism_preserved(self):
        window, _ = self.make_window()
        with mock.patch(
            'ui.daily_floating_window.EventService.get_events_overlapping_range',
            return_value=[],
        ):
            window.set_view_mode('day')
            self.assertIs(window._view_buttons['day'].property('activeView'), True)
            self.assertIs(window._view_buttons['events'].property('activeView'), False)
            window.set_view_mode('events')
        self.assertIs(window._view_buttons['events'].property('activeView'), True)

    def test_floating_scale_bindings_are_untouched(self):
        window, values = self.make_window(
            app_font_size_px=8, floating_font_size_px=20
        )
        scale = floating_scale_from_config(values)
        self.assertEqual(window._event_list.font().pixelSize(), scale.body_px)
        self.assertEqual(window._ai_status.font().pixelSize(), scale.caption_px)
        self.assertEqual(window._ai_input.font().pixelSize(), scale.control_px)
        self.assertEqual(window._btn_pin.font().pixelSize(), scale.control_px)
        self.assertIn(
            f'font-size: {scale.control_px}px', window._ai_input.styleSheet()
        )


if __name__ == '__main__':
    unittest.main()
