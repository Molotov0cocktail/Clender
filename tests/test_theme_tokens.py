"""Design token contracts for the T74 visual upgrade; no application data."""
import os
import re
import unittest

os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')

from pathlib import Path

from PyQt5.QtGui import QColor
from PyQt5.QtWidgets import QApplication

import constants
import theme_manager
from background import BackgroundWidget
from theme_manager import DARK_THEME, LIGHT_THEME, ThemeColors

REPO_ROOT = Path(__file__).resolve().parents[1]
HEX_PATTERN = re.compile(r'^#[0-9a-fA-F]{6}$')

BASE_THEME_KEYS = (
    'app_bg', 'frame_bg', 'frame_border', 'title_color', 'subtitle_color',
    'text_color', 'muted_color', 'primary', 'primary_hover', 'primary_text',
    'danger', 'danger_hover', 'info', 'info_hover', 'success', 'warning_text',
    'header_bg', 'input_bg', 'input_border', 'list_bg', 'list_item_hover',
    'scrollbar_bg', 'chat_user_color', 'chat_ai_color', 'chat_system_color',
    'calendar_today_border', 'calendar_today_bg', 'calendar_today_text',
    'calendar_selected_border', 'calendar_selected_bg', 'calendar_selected_text',
    'calendar_cell_bg', 'calendar_cell_border', 'calendar_cell_text',
    'event_reminder_color', 'event_timespan_color', 'statusbar_bg',
    'nav_btn_color', 'switch_btn_text', 'switch_btn_checked_bg',
    'slot_bg', 'slot_border',
)

NEW_TOKEN_KEYS = (
    'accent_gradient_start', 'accent_gradient_end', 'primary_pressed',
    'surface_raised', 'surface_sunken', 'border_soft',
    'grid_line_color', 'grid_line_minor_color', 'event_block_text',
    'chat_user_bubble_bg', 'chat_ai_bubble_bg', 'chat_think_bubble_bg',
    'success_soft', 'warning_soft', 'danger_soft',
)

THEME_PAIRS = ((LIGHT_THEME, 'light'), (DARK_THEME, 'dark'))


def luminance(value: str) -> float:
    rgb = QColor(value).getRgbF()[:3]
    linear = [c / 12.92 if c <= .04045 else ((c + .055) / 1.055) ** 2.4 for c in rgb]
    return sum(c * w for c, w in zip(linear, (.2126, .7152, .0722)))


def contrast(first: str, second: str) -> float:
    a, b = luminance(first), luminance(second)
    return (max(a, b) + .05) / (min(a, b) + .05)


class ThemeTokenContractTests(unittest.TestCase):
    def test_new_tokens_present_with_valid_hex_and_theme_names(self):
        for theme, name in THEME_PAIRS:
            with self.subTest(theme=name):
                self.assertEqual(theme['theme_name'], name)
                for key in NEW_TOKEN_KEYS:
                    self.assertIn(key, theme)
                    self.assertRegex(theme[key], HEX_PATTERN, f'{name}.{key}')

    def test_base_keys_preserved_and_theme_key_sets_match(self):
        for theme, name in THEME_PAIRS:
            with self.subTest(theme=name):
                for key in BASE_THEME_KEYS:
                    self.assertIn(key, theme)
                    self.assertRegex(theme[key], HEX_PATTERN, f'{name}.{key}')
        self.assertEqual(set(LIGHT_THEME.keys()), set(DARK_THEME.keys()))

    def test_theme_colors_wrapper_still_raises_for_missing_key(self):
        wrapper = ThemeColors(dict(LIGHT_THEME))
        with self.assertRaises(AttributeError):
            wrapper.no_such_token_key
        self.assertEqual(wrapper.get('no_such_token_key', '#abcdef'), '#abcdef')
        self.assertEqual(wrapper['theme_name'], 'light')
        self.assertIn('accent_gradient_start', wrapper)
        self.assertEqual(wrapper.accent_gradient_start, LIGHT_THEME['accent_gradient_start'])


class ThemeTokenContrastTests(unittest.TestCase):
    def test_muted_text_contrast_kept_in_both_themes(self):
        for theme, name in THEME_PAIRS:
            with self.subTest(theme=name):
                self.assertGreaterEqual(contrast(theme['muted_color'], theme['app_bg']), 4.5)

    def test_chat_bubble_backgrounds_readable_with_text_color(self):
        for theme, name in THEME_PAIRS:
            for key in ('chat_user_bubble_bg', 'chat_ai_bubble_bg'):
                with self.subTest(theme=name, key=key):
                    self.assertGreaterEqual(contrast(theme[key], theme['text_color']), 4.5)

    def test_event_block_text_readable_on_both_palettes(self):
        palettes = (
            (LIGHT_THEME, constants.EVENT_COLORS),
            (DARK_THEME, constants.EVENT_COLORS_DARK),
        )
        for theme, palette in palettes:
            for color in palette:
                with self.subTest(theme=theme['theme_name'], color=color):
                    self.assertGreaterEqual(contrast(theme['event_block_text'], color), 3.0)


class EventPaletteTests(unittest.TestCase):
    def test_palettes_have_twelve_unique_valid_hex_colors(self):
        for palette in (constants.EVENT_COLORS, constants.EVENT_COLORS_DARK):
            self.assertEqual(len(palette), 12)
            self.assertEqual(len(set(palette)), 12)
            for color in palette:
                self.assertRegex(color, HEX_PATTERN)
                self.assertTrue(QColor(color).isValid())

    def test_marker_constants_keep_their_types(self):
        self.assertRegex(constants.REMINDER_LINE_COLOR, HEX_PATTERN)
        marker = constants.OVERLAP_MARKER_COLOR
        self.assertIsInstance(marker, tuple)
        self.assertEqual(len(marker), 4)
        self.assertTrue(all(isinstance(v, int) and 0 <= v <= 255 for v in marker))


class AccentGradientTests(unittest.TestCase):
    def test_gradient_endpoints_differ(self):
        for theme, name in THEME_PAIRS:
            with self.subTest(theme=name):
                self.assertNotEqual(theme['accent_gradient_start'], theme['accent_gradient_end'])


class GlobalStyleSheetTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def test_apply_theme_qss_contains_role_selectors_and_gradient(self):
        old_font = self.app.font()
        old_style = self.app.styleSheet()
        old_palette = self.app.palette()
        self.addCleanup(lambda: self.app.setFont(old_font))
        self.addCleanup(lambda: self.app.setStyleSheet(old_style))
        self.addCleanup(lambda: self.app.setPalette(old_palette))
        required = (
            'QPushButton[btnClass="primary"]',
            'QPushButton[btnClass="danger"]',
            'QPushButton[btnClass="info"]',
            'QPushButton[btnClass="ghost"]',
            'qlineargradient',
            'QCheckBox::indicator',
            'QRadioButton::indicator',
            'QScrollBar::handle:vertical:hover',
        )
        for name in ('light', 'dark'):
            with self.subTest(theme=name):
                theme_manager.apply_theme(self.app, {'theme': name})
                style = self.app.styleSheet()
                for token in required:
                    self.assertIn(token, style)
                theme = LIGHT_THEME if name == 'light' else DARK_THEME
                self.assertIn(theme['accent_gradient_start'], style)
                self.assertIn(theme['accent_gradient_end'], style)
                self.assertIn(theme['primary_pressed'], style)


class BackgroundBaseColorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def test_background_source_has_no_hardcoded_base_colors(self):
        source = (REPO_ROOT / 'background.py').read_text(encoding='utf-8')
        lowered = source.lower()
        self.assertNotIn('#101522', lowered)
        self.assertNotIn('#f0f3fa', lowered)

    def test_background_fills_theme_app_bg(self):
        widget = BackgroundWidget()
        self.addCleanup(widget.close)
        widget.resize(120, 80)
        for theme, name in THEME_PAIRS:
            with self.subTest(theme=name):
                widget.apply_config({'theme': name})
                expected = QColor(theme['app_bg'])
                pixel = widget.grab().toImage().pixelColor(30, 30)
                self.assertEqual((pixel.red(), pixel.green(), pixel.blue()),
                                 (expected.red(), expected.green(), expected.blue()))


if __name__ == '__main__':
    unittest.main()
