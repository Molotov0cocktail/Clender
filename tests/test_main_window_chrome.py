"""T75 main window chrome contracts; fixtures contain no application data."""
import os
import sqlite3
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')

from PyQt5.QtGui import QFontMetrics
from PyQt5.QtWidgets import QApplication, QStatusBar

import config
import theme_manager
from typography import app_scale_from_config, qfont_for
from ui.main_window import MainWindow


class MainWindowChromeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def _make_window(self):
        from tests import test_ui_smoke
        test_ui_smoke.UISmokeTests.setUp(self)
        patcher = patch(
            'ui.main_window.QSystemTrayIcon.isSystemTrayAvailable', return_value=False
        )
        patcher.start()
        self.addCleanup(patcher.stop)
        window = MainWindow(self.app)
        self.addCleanup(window.close)
        return window

    def test_window_has_no_status_bar_or_status_widgets(self):
        window = self._make_window()
        self.assertIsNone(window.findChild(QStatusBar))
        self.assertFalse(hasattr(window, '_status_bar'))
        self.assertFalse(hasattr(window, '_status_label'))
        self.assertFalse(hasattr(window, '_btn_settings'))
        self.assertFalse(hasattr(window, '_resize_status_buttons'))

    def test_header_theme_button_left_of_appearance_and_toggles_theme(self):
        window = self._make_window()
        window.show()
        self.app.processEvents()
        self.assertEqual(window._theme_btn.property('btnClass'), 'ghost')
        self.assertEqual(window._theme_btn.toolTip(), '切换日间/夜间主题')
        self.assertIn(window._theme_btn.text(), ('日间', '夜间'))
        self.assertEqual(window._appearance_btn.text(), '外观与设置')
        self.assertEqual(window._theme_btn.parent(), window._appearance_btn.parent())
        self.assertLess(window._theme_btn.x(), window._appearance_btn.x())
        before = config.get_theme()
        window._theme_btn.click()
        self.assertNotEqual(before, config.get_theme())
        window._theme_btn.click()
        self.assertEqual(before, config.get_theme())

    def test_header_buttons_fit_text_across_font_range(self):
        self.addCleanup(
            self.app.setFont, qfont_for(app_scale_from_config({}), 'body')
        )
        for size in (8, 20):
            with self.subTest(size=size):
                values = {'app_font_size_px': size, 'theme': 'light'}
                window = self._make_window()
                window._apply_app_settings(values)
                window.show()
                self.app.processEvents()
                metrics = QFontMetrics(qfont_for(app_scale_from_config(values), 'control'))
                for button in (window._theme_btn, window._appearance_btn):
                    self.assertGreaterEqual(
                        button.width(),
                        metrics.horizontalAdvance(button.text()) + 16,
                    )
                    self.assertGreaterEqual(button.height(), metrics.height() + 8)

    def test_sync_status_routes_to_settings_dialog_and_logs(self):
        window = self._make_window()
        dialog = Mock()
        window._settings_dialog = dialog
        with patch('ui.main_window._log') as log:
            window._on_sync_status('sync:done')
        dialog.set_webdav_status.assert_called_once_with('sync:done')
        log.info.assert_called_once()
        window._settings_dialog = None
        with patch('ui.main_window._log'):
            window._on_sync_status('sync:idle')

    def test_deliver_event_alert_without_tray_logs_and_returns_false(self):
        window = SimpleNamespace(has_system_tray=lambda: False, _tray_icon=Mock())
        with patch('ui.main_window._log') as log:
            delivered = MainWindow._deliver_event_alert(window, 'synthetic', 'body')
        self.assertFalse(delivered)
        window._tray_icon.showMessage.assert_not_called()
        log.warning.assert_called_once()

    def test_poll_event_alerts_sqlite_error_logs_warning(self):
        dispatcher = Mock()
        dispatcher.poll.side_effect = sqlite3.Error('locked')
        window = SimpleNamespace(
            _shutting_down=False,
            has_system_tray=lambda: True,
            _alert_dispatcher=dispatcher,
        )
        with patch('ui.main_window._log') as log:
            MainWindow._poll_event_alerts(window)
        dispatcher.poll.assert_called_once_with()
        log.warning.assert_called_once()


if __name__ == '__main__':
    unittest.main()
