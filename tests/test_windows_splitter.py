"""T77 splitter pixels and mouse interaction using isolated synthetic data."""
import os
from pathlib import Path
import unittest
from unittest.mock import patch

os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')

from PyQt5.QtCore import QPoint, QPointF, QEvent, Qt
from PyQt5.QtGui import QColor, QImage, QMouseEvent
from PyQt5.QtTest import QTest
from PyQt5.QtWidgets import QApplication

import config
import theme_manager
from background import BackgroundWidget
from ui.main_window import MainWindow


class WindowsSplitterTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def setUp(self):
        from tests.test_ui_smoke import UISmokeTests
        UISmokeTests.setUp(self)
        self.addCleanup(theme_manager.apply_theme, self.app, {})
        self.image_path = str(Path(self.temp_dir.name) / 'synthetic-background.png')
        image = QImage(600, 400, QImage.Format_RGB32)
        image.fill(QColor('#d45583'))
        self.assertTrue(image.save(self.image_path))
        with patch('ui.main_window.QSystemTrayIcon.isSystemTrayAvailable', return_value=False):
            self.window = MainWindow(self.app)
        self.addCleanup(self.window.close)
        self.window.resize(1800, 950)
        self.window.show()
        self.app.processEvents()

    def _apply(self, theme, size=13, background=''):
        values = config.load_config()
        values.update({
            'theme': theme,
            'app_font_size_px': size,
            'background_image': background,
            'background_strength': 100,
        })
        config.save_config(values)
        self.window._apply_app_settings(values)
        self.app.processEvents()
        return values

    def _assert_gap_pixels(self, values):
        central = self.window.centralWidget()
        reference = BackgroundWidget()
        try:
            reference.resize(central.size())
            reference.apply_config(values)
            expected = reference.grab().toImage()
            actual = central.grab().toImage()
            for index in (1, 2):
                handle = self.window._splitter.handle(index)
                self.assertEqual(handle.width(), 10)
                for fraction in (0.1, 0.5, 0.9):
                    for x in range(handle.width()):
                        point = handle.mapTo(central, QPoint(x, int(handle.height() * fraction)))
                        self.assertEqual(
                            actual.pixelColor(point).name(), expected.pixelColor(point).name(),
                            f'handle={index}, local x={x}, y fraction={fraction}',
                        )
        finally:
            reference.close()

    def test_handles_show_continuous_background_across_theme_font_and_resize(self):
        for background in ('', self.image_path):
            for size in (8, 13, 20):
                for theme in ('light', 'dark', 'light'):
                    with self.subTest(background=bool(background), size=size, theme=theme):
                        values = self._apply(theme, size, background)
                        self.window.resize(1800 if size == 20 else 1600, 950)
                        self.app.processEvents()
                        self._assert_gap_pixels(values)

    def test_missing_background_fallback_has_no_separator_lines(self):
        for theme in ('light', 'dark'):
            with self.subTest(theme=theme):
                values = self._apply(theme, background=self.image_path + '.missing')
                self.assertFalse(self.window.centralWidget().has_background)
                self._assert_gap_pixels(values)

    def test_both_ten_pixel_handles_still_resize_panels_by_mouse_drag(self):
        splitter = self.window._splitter
        for theme in ('light', 'dark', 'light'):
            self._apply(theme, background=self.image_path)
            for index in (1, 2):
                with self.subTest(theme=theme, handle=index):
                    splitter.setSizes([350, 600, 700])
                    self.app.processEvents()
                    handle = splitter.handle(index)
                    self.assertEqual(splitter.handleWidth(), 10)
                    self.assertEqual(handle.width(), 10)
                    before = splitter.sizes()
                    start = handle.rect().center()
                    end = start + QPoint(40, 0)
                    QTest.mousePress(handle, Qt.LeftButton, Qt.NoModifier, start)
                    move = QMouseEvent(
                        QEvent.MouseMove, QPointF(end), QPointF(handle.mapToGlobal(end)),
                        Qt.NoButton, Qt.LeftButton, Qt.NoModifier,
                    )
                    QApplication.sendEvent(handle, move)
                    QTest.mouseRelease(handle, Qt.LeftButton, Qt.NoModifier, end)
                    self.app.processEvents()
                    after = splitter.sizes()
                    self.assertGreater(after[index - 1], before[index - 1])
                    self.assertLess(after[index], before[index])
                    self.assertEqual(sum(before), sum(after))


if __name__ == '__main__':
    unittest.main()
