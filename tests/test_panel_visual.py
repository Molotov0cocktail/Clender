"""T74 panel visual contracts; fixtures contain no application data."""
import os
import unittest
from pathlib import Path
from unittest.mock import patch

os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')

from PyQt5.QtCore import QUrl
from PyQt5.QtGui import QColor, QImage
from PyQt5.QtWidgets import QApplication, QLabel, QStatusBar

import theme_manager
from models import Conversation
from ui.ai_chat_widget import AIChatWidget
from ui.event_manager import EventManager
from ui.main_window import MainWindow
from ui.sidebar import ConversationSidebar

REPO_ROOT = Path(__file__).resolve().parents[1]
LEGACY_CHUNK_COLORS = ('#e74c3c', '#d29922', '#3fb950')


class PanelWidgetTestCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def setUp(self):
        for patcher in (
            patch('config.load_config', return_value={}),
            patch('config.is_api_configured', return_value=False),
            patch('ui.ai_chat_widget.load_conversations', return_value={}),
            patch('ui.ai_chat_widget.save_conversations'),
        ):
            patcher.start()
            self.addCleanup(patcher.stop)


class ButtonRoleTests(PanelWidgetTestCase):
    def test_event_manager_buttons_expose_role_properties(self):
        widget = EventManager()
        self.addCleanup(widget.close)
        self.assertEqual(widget._btn_add.property('btnClass'), 'primary')
        self.assertEqual(widget._btn_delete.property('btnClass'), 'danger')
        self.assertEqual(widget._btn_edit.property('btnClass'), 'info')
        self.assertEqual(widget._btn_edit.text(), '编辑选中')
        self.assertEqual(widget._btn_delete.text(), '删除选中')
        self.assertIn('添加事项', widget._btn_add.text())

    def test_chat_header_and_send_buttons_expose_role_properties(self):
        widget = AIChatWidget()
        self.addCleanup(widget.close)
        for button in (widget._btn_toggle_sidebar, widget._btn_settings, widget._btn_clear):
            self.assertTrue(button.property('btnClass'))
        self.assertEqual(widget._btn_send.property('btnClass'), 'primary')
        self.assertEqual(widget._btn_toggle_sidebar.text(), '对话')
        self.assertEqual(widget._btn_settings.text(), '设置')
        self.assertEqual(widget._btn_clear.text(), '清空')
        self.assertEqual(widget._btn_send.text(), '发送')


class ChatBubbleTests(PanelWidgetTestCase):
    def _widget_with_messages(self):
        widget = AIChatWidget()
        self.addCleanup(widget.close)
        conv = widget._active_conv
        conv.messages.clear()
        conv.add_message('think', '先确认日期再创建事项')
        conv.add_message('user', '明天上午十点开会')
        conv.add_message('assistant', '好的，已为你创建日程')
        return widget

    def test_bubbles_use_theme_backgrounds_in_both_themes(self):
        widget = self._widget_with_messages()
        for theme in (theme_manager.LIGHT_THEME, theme_manager.DARK_THEME):
            with self.subTest(theme=theme['theme_name']), patch(
                'theme_manager.get_current_theme', return_value=theme
            ):
                widget._render_conv_messages()
                html = widget._chat_display.toHtml().lower()
                for key in ('chat_user_bubble_bg', 'chat_ai_bubble_bg', 'chat_think_bubble_bg'):
                    self.assertIn(theme[key].lower(), html)
                plain = widget._chat_display.toPlainText()
                self.assertIn('明天上午十点开会', plain)
                self.assertIn('好的，已为你创建日程', plain)
                self.assertIn('展开 ▼', plain)

    def test_think_toggle_keeps_anchor_contract_inside_bubble(self):
        widget = self._widget_with_messages()
        with patch('theme_manager.get_current_theme', return_value=theme_manager.LIGHT_THEME):
            widget._render_conv_messages()
            self.assertIsNotNone(widget._anchor_document_y('think_anchor_0'))
            widget._on_think_toggle(QUrl('toggle_think_0'))
            self.assertIn('收起 ▲', widget._chat_display.toPlainText())
            self.assertIn('先确认日期再创建事项', widget._chat_display.toPlainText())


class TokenBarThemeTests(PanelWidgetTestCase):
    def test_source_has_no_hardcoded_chunk_colors(self):
        source = (REPO_ROOT / 'ui' / 'ai_chat_widget.py').read_text(encoding='utf-8').lower()
        for color in LEGACY_CHUNK_COLORS:
            self.assertNotIn(color, source)

    def test_chunk_color_follows_theme_keys_and_thresholds(self):
        widget = AIChatWidget()
        self.addCleanup(widget.close)
        cases = ((900, 'danger'), (600, 'warning_text'), (100, 'success'))
        for theme in (theme_manager.LIGHT_THEME, theme_manager.DARK_THEME):
            for used, key in cases:
                with self.subTest(theme=theme['theme_name'], used=used):
                    widget._active_conv.token_count = used
                    with patch('config.load_config', return_value={'context_window': 1000}), patch(
                        'theme_manager.get_current_theme', return_value=theme
                    ):
                        widget._update_token_bar()
                    style = widget._token_bar.styleSheet()
                    self.assertIn(theme[key], style)
                    self.assertIn(theme['list_bg'], style)
                    for other_key in ('danger', 'warning_text', 'success'):
                        if other_key != key:
                            self.assertNotIn(theme[other_key], style)


class SidebarVisualTests(PanelWidgetTestCase):
    def test_list_selected_state_and_active_foreground(self):
        with patch('config.load_config', return_value={}), patch(
            'theme_manager.get_current_theme', return_value=theme_manager.LIGHT_THEME
        ):
            widget = ConversationSidebar()
        self.addCleanup(widget.close)
        qss = widget._list.styleSheet()
        self.assertIn('::item:selected', qss)
        primary = QColor(theme_manager.LIGHT_THEME['primary'])
        self.assertIn(f'rgba({primary.red()}, {primary.green()}, {primary.blue()}', qss)
        self.assertIn(theme_manager.LIGHT_THEME['primary'], qss)
        self.assertEqual(widget._lbl_title.text(), '对话')
        self.assertEqual(widget._lbl_title.objectName(), 'conversationSidebarTitle')
        self.assertEqual(widget._btn_new.text(), '＋')
        conv = Conversation.new('活跃对话')
        with patch('theme_manager.get_current_theme', return_value=theme_manager.LIGHT_THEME):
            widget.refresh({conv.id: conv}, conv.id)
        self.assertEqual(
            widget._list.item(0).foreground().color().name(),
            theme_manager.LIGHT_THEME['primary'].lower(),
        )


class MainWindowChromeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def _make_window(self):
        from tests import test_ui_smoke
        test_ui_smoke.UISmokeTests.setUp(self)
        patcher = patch('ui.main_window.QSystemTrayIcon.isSystemTrayAvailable', return_value=False)
        patcher.start()
        self.addCleanup(patcher.stop)
        window = MainWindow(self.app)
        self.addCleanup(window.close)
        return window

    def test_brand_bar_icon_title_and_settings_role(self):
        window = self._make_window()
        self.assertIsInstance(window._brand_icon, QLabel)
        pixmap = window._brand_icon.pixmap()
        self.assertIsNotNone(pixmap)
        self.assertFalse(pixmap.isNull())
        self.assertIn('Clender', window._brand_label.text())
        self.assertEqual(window._appearance_btn.text(), '外观与设置')
        self.assertTrue(window._appearance_btn.property('btnClass'))
        self.assertEqual(window._theme_btn.property('btnClass'), 'ghost')
        self.assertIsNone(window.findChild(QStatusBar))
        self.assertEqual(window._splitter.handleWidth(), 10)
        self.assertIn('QSplitter::handle', window._splitter.styleSheet())

    def test_background_surface_chain_and_theme_switch_refresh(self):
        window = self._make_window()
        image_path = str(Path(self.temp_dir.name) / 'panel-background.png')
        image = QImage(600, 400, QImage.Format_RGB32)
        image.fill(QColor('#3f7fbf'))
        self.assertTrue(image.save(image_path))
        window.centralWidget().apply_config({'background_image': image_path})
        self.assertTrue(window.centralWidget().has_background)
        window._apply_background_surfaces()
        self.assertIn('rgba', window._event_mgr.styleSheet())
        self.assertIn('transparent', window._ai_chat._chat_display.styleSheet())

        window.centralWidget().apply_config({})
        window._apply_component_styles()
        self.assertFalse(window.centralWidget().has_background)
        self.assertNotIn('rgba', window._event_mgr.styleSheet())
        theme = theme_manager.get_current_theme()
        self.assertIn(theme['frame_bg'], window._event_mgr.styleSheet())
        self.assertIn(theme['list_bg'], window._ai_chat._chat_display.styleSheet())

        window._toggle_theme()
        switched = theme_manager.get_current_theme()
        self.assertNotEqual(theme['theme_name'], switched['theme_name'])
        self.assertIn(switched['frame_bg'], window._event_mgr.styleSheet())
        self.assertIn(switched['list_bg'], window._ai_chat._chat_display.styleSheet())
        window._toggle_theme()


if __name__ == '__main__':
    unittest.main()
