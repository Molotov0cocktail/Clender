"""Isolated appearance contracts; fixtures contain no application data."""
import os
os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from PyQt5.QtWidgets import QApplication, QScrollArea
from PyQt5.QtGui import QImage, QColor, QFontDatabase
from PyQt5.QtCore import QSize
from background import BackgroundSettings, BackgroundWidget, load_background
from ui.app_settings import AppSettingsDialog


class AppearanceTests(unittest.TestCase):
    def test_time_axis_muted_text_contrast_in_both_themes(self):
        import theme_manager
        def luminance(value):
            rgb = QColor(value).getRgbF()[:3]
            linear = [c / 12.92 if c <= .04045 else ((c + .055) / 1.055) ** 2.4 for c in rgb]
            return sum(c * w for c, w in zip(linear, (.2126, .7152, .0722)))
        for theme in (theme_manager.LIGHT_THEME, theme_manager.DARK_THEME):
            foreground, background = luminance(theme['muted_color']), luminance(theme['app_bg'])
            self.assertGreaterEqual((max(foreground, background) + .05) / (min(foreground, background) + .05), 4.5)

    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])
        font = Path('C:/Windows/Fonts/msyh.ttc')
        if font.exists():
            QFontDatabase.addApplicationFont(str(font))

    def test_config_defaults_invalid_and_boundaries(self):
        self.assertEqual(BackgroundSettings.from_config({}).path, '')
        for value in (None, True, '90', -1, 101):
            self.assertEqual(BackgroundSettings.from_config({'background_strength': value}).strength, 60)
        for value in (0, 100):
            self.assertEqual(BackgroundSettings.from_config({'background_strength': value}).strength, value)
        self.assertEqual(BackgroundSettings.from_config({'background_image': []}).path, '')

    def test_images_bounded_missing_corrupt_and_mask(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = str(Path(tmp) / 'fixture.png')
            img = QImage(3000, 100, QImage.Format_RGB32)
            img.fill(QColor('red'))
            self.assertTrue(img.save(path))
            result = load_background(path)
            self.assertLessEqual(result.width(), 1920)
            self.assertTrue(load_background(path + '.missing').isNull())
            bad = Path(tmp) / 'bad.png'
            bad.write_text('invalid image')
            self.assertTrue(load_background(str(bad)).isNull())
            widget = BackgroundWidget()
            widget.resize(160, 100)
            for theme in ('light', 'dark'):
                widget.apply_config({'background_image': path, 'background_strength': 100, 'theme': theme})
                color = widget.grab().toImage().pixelColor(40, 40)
                self.assertGreater(color.red(), color.blue())
                self.assertGreaterEqual(widget.settings.mask_opacity(theme), .55)
            widget.apply_config({})
            self.assertFalse(widget.has_background)
            widget.close()

    def test_settings_cancel_save_and_failure(self):
        with patch('config.load_config', return_value={}), patch('config.get_theme', return_value='light'), patch('startup_manager.is_supported_runtime', return_value=False):
            dialog = AppSettingsDialog()
            self.assertIsNotNone(dialog.findChild(QScrollArea, 'settingsScroll'))
            received = []
            dialog.config_saved.connect(received.append)
            dialog._background_path = '/example/local.png'
            dialog._background_strength.setValue(100)
            with patch('config.save_config', side_effect=OSError('fixture')), patch('ui.app_settings.QMessageBox.critical'):
                self.assertFalse(dialog._persist(close_after=False, sync_after=False))
            self.assertEqual(received, [])
            with patch('config.save_config') as save:
                self.assertTrue(dialog._persist(close_after=False, sync_after=False))
                self.assertEqual(save.call_args.args[0]['background_strength'], 100)
                self.assertEqual(save.call_args.args[0]['background_image'], '/example/local.png')
            dialog._remove_background()
            self.assertEqual(dialog._background_path, '')
            with patch('config.save_config') as save:
                dialog.reject()
                save.assert_not_called()
            dialog.close()

    def test_image_limits_and_one_image_cache(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = str(Path(tmp) / 'fixture.png')
            image = QImage(20, 20, QImage.Format_RGB32)
            image.fill(QColor('blue'))
            image.save(path)
            with patch('background.QImageReader') as reader:
                reader.return_value.size.return_value = QSize(10000, 10000)
                self.assertTrue(load_background(path).isNull())
                reader.return_value.read.assert_not_called()
            with patch('background.Path.stat') as stat:
                stat.return_value.st_mode = 0o100644
                stat.return_value.st_size = 32 * 1024 * 1024 + 1
                self.assertTrue(load_background(path).isNull())
            with patch('background.load_background', wraps=load_background) as decode:
                widget = BackgroundWidget()
                widget.apply_config({'background_image': path})
                widget.apply_config({'background_image': path, 'theme': 'dark'})
                widget.resize(500, 200)
                widget.grab()
                self.assertEqual(decode.call_count, 1)
                widget.apply_config({})
                self.assertFalse(widget.has_background)
                widget.close()

    def test_picker_cancel_bad_image_and_remove_do_not_persist(self):
        with patch('config.load_config', return_value={}), patch('config.get_theme', return_value='light'), patch('startup_manager.is_supported_runtime', return_value=False):
            dialog = AppSettingsDialog()
            with patch('config.save_config') as save, patch('ui.app_settings.QFileDialog.getOpenFileName', return_value=('', '')):
                dialog._choose_background()
                self.assertEqual(dialog._background_path, '')
                save.assert_not_called()
            with patch('ui.app_settings.QFileDialog.getOpenFileName', return_value=('/missing/image.png', '')), patch('ui.app_settings.QMessageBox.warning') as warning:
                dialog._choose_background()
                warning.assert_called_once()
                self.assertEqual(dialog._background_path, '')
            dialog.close()

    def test_settings_small_screen_font_theme_and_footer(self):
        import theme_manager
        for theme in ('light', 'dark'):
            for size in (8, 20):
                config = {'theme': theme, 'app_font_size_px': size}
                with patch('config.load_config', return_value=config), patch('config.get_theme', return_value=theme), patch('startup_manager.is_supported_runtime', return_value=False):
                    theme_manager.apply_theme(self.app, config)
                    dialog = AppSettingsDialog()
                    dialog.resize(560, 480)
                    dialog.show()
                    self.app.processEvents()
                    scroll = dialog.findChild(QScrollArea, 'settingsScroll')
                    self.assertGreater(scroll.verticalScrollBar().maximum(), 0)
                    self.assertTrue(dialog._btn_save.isVisible())
                    self.assertLessEqual(dialog._btn_save.mapTo(dialog, dialog._btn_save.rect().bottomRight()).y(), dialog.height())
                    self.assertFalse(dialog.grab().isNull())
                    dialog.close()

    def test_main_window_background_visible_and_remove_restores_surfaces(self):
        from tests import test_ui_smoke
        from ui.main_window import MainWindow
        import config
        # Reuse the existing isolated DB/config fixture, never the real data path.
        test_ui_smoke.UISmokeTests.setUp(self)
        image_path = str(Path(self.temp_dir.name) / 'background.png')
        image = QImage(600, 400, QImage.Format_RGB32)
        image.fill(QColor('#df5374'))
        image.save(image_path)
        values = config.load_config()
        values.update({'background_image': image_path, 'background_strength': 100})
        config.save_config(values)
        with patch('ui.main_window.QSystemTrayIcon.isSystemTrayAvailable', return_value=False):
            window = MainWindow(self.app)
            try:
                window.show()
                self.app.processEvents()
                for theme in ('light', 'dark'):
                    values['theme'] = theme
                    config.save_config(values)
                    window._apply_app_settings(values)
                    self.app.processEvents()
                    self.assertTrue(window.centralWidget().has_background)
                    self.assertEqual(window._appearance_btn.text(), '外观与设置')
                    self.assertEqual(window._calendar._btn_prev.accessibleName(), '上一页')
                    for mode in ('week', 'day', 'month'):
                        window._calendar._switch_view(mode)
                        self.assertIn('rgba', window._calendar.styleSheet())
                    self.assertIn('rgba', window._event_mgr.styleSheet())
                    self.assertIn('transparent', window._ai_chat._chat_display.styleSheet())
                    self.assertFalse(window.grab().isNull())
                values['background_image'] = ''
                config.save_config(values)
                window._apply_app_settings(values)
                self.assertFalse(window.centralWidget().has_background)
                self.assertNotIn('rgba', window._event_mgr.styleSheet())
            finally:
                window.close()
