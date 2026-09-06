"""Readable built-in labels without changing user text or action wiring."""
import os
import unittest
from unittest.mock import patch

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
from PyQt5.QtCore import Qt
from PyQt5.QtGui import QFontMetrics
from PyQt5.QtTest import QSignalSpy
from PyQt5.QtWidgets import QApplication, QLabel, QPushButton
from models import Conversation
import theme_manager
from ui.ai_chat_widget import AIChatWidget
from ui.event_manager import EventManager
from ui.sidebar import ConversationSidebar
from typography import app_scale_from_config, qfont_for


class ActionLabelTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def setUp(self):
        for patcher in (
            patch("config.load_config", return_value={}),
            patch("config.is_api_configured", return_value=False),
            patch("ui.ai_chat_widget.load_conversations", return_value={}),
            patch("ui.ai_chat_widget.save_conversations"),
        ):
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_event_actions_keep_connections_and_user_title(self):
        with patch.object(EventManager, "_on_add_clicked") as add, patch.object(EventManager, "_on_edit_clicked") as edit:
            widget = EventManager()
            self.addCleanup(widget.close)
            self.assertEqual(widget._title_label.text(), "日程安排")
            self.assertEqual(widget._btn_edit.text(), "编辑选中")
            self.assertEqual(widget._btn_delete.text(), "删除选中")
            widget._btn_add.click()
            widget._btn_edit.click()
            add.assert_called_once()
            edit.assert_called_once()
        events = [dict(id=7, event_type="reminder", title="🎉 用户标题", start_time="2026-09-06 09:00", end_time=None)]
        with patch("ui.event_manager.EventService.get_events_by_date", return_value=events):
            widget.refresh()
        item = widget._list_widget.item(0)
        self.assertEqual(item.text(), "提醒 09:00  🎉 用户标题")
        self.assertEqual(item.data(Qt.UserRole), 7)
        widget._list_widget.clear()
        with patch("ui.event_manager.QMessageBox.information") as warning:
            widget._on_delete_clicked()
        warning.assert_called_once()

    def test_sidebar_new_and_selection_signals(self):
        widget = ConversationSidebar()
        self.addCleanup(widget.close)
        self.assertEqual(widget._lbl_title.text(), "对话")
        new = QSignalSpy(widget.new_conversation)
        selected = QSignalSpy(widget.conversation_selected)
        widget._btn_new.click()
        self.assertEqual(len(new), 1)
        conv = Conversation.new("🎉 用户对话")
        widget.refresh({conv.id: conv}, conv.id)
        widget._list.itemClicked.emit(widget._list.item(0))
        self.assertEqual(selected[0], [conv.id])
        self.assertIn("🎉 用户对话", widget._list.item(0).text())

    def test_event_theme_recolors_items_without_losing_selection(self):
        widget = EventManager()
        self.addCleanup(widget.close)
        events = [
            dict(id=7, event_type="reminder", title="提醒", start_time="2026-09-06 09:00", end_time=None),
            dict(id=8, event_type="timespan", title="时间段", start_time="2026-09-06 10:00", end_time="2026-09-06 11:00"),
        ]
        with patch("theme_manager.get_current_theme", return_value=theme_manager.LIGHT_THEME), patch("ui.event_manager.EventService.get_events_by_date", return_value=events):
            widget.refresh()
        widget._list_widget.setCurrentRow(1)
        with patch("theme_manager.get_current_theme", return_value=theme_manager.DARK_THEME), patch("ui.event_manager.EventService.get_events_by_date", side_effect=AssertionError("theme must not query events")):
            widget.apply_theme()
        self.assertEqual(widget._list_widget.currentItem().data(Qt.UserRole), 8)
        for row, name in enumerate(("event_reminder_color", "event_timespan_color")):
            self.assertEqual(widget._list_widget.item(row).foreground().color().name(), theme_manager.DARK_THEME[name].lower())
        with patch("ui.event_manager.EventService.get_events_by_date", return_value=[]):
            widget.refresh()
        with patch("theme_manager.get_current_theme", return_value=theme_manager.DARK_THEME):
            widget.apply_theme()
        self.assertEqual(widget._list_widget.item(0).foreground().color().name(), theme_manager.DARK_THEME["muted_color"].lower())

    def test_chat_static_labels_geometry_and_user_content(self):
        widget = AIChatWidget()
        self.addCleanup(widget.close)
        self.assertEqual(widget._btn_settings.text(), "设置")
        self.assertEqual(widget._btn_clear.text(), "清空")
        self.assertEqual(widget._btn_toggle_sidebar.text(), "对话")
        self.assertEqual(widget._lbl_status.text(), "请先配置 API")
        self.assertFalse(widget._btn_send.isEnabled())
        widget._btn_toggle_sidebar.click()
        self.assertTrue(widget._sidebar_visible)
        for theme in (theme_manager.LIGHT_THEME, theme_manager.DARK_THEME):
            for size in (8, 20):
                settings = {"app_font_size_px": size}
                with patch("config.load_config", return_value=settings), patch("theme_manager.get_current_theme", return_value=theme):
                    widget.apply_theme()
                    metrics = QFontMetrics(qfont_for(app_scale_from_config(settings), "control"))
                    for button in (widget._btn_settings, widget._btn_clear, widget._btn_toggle_sidebar):
                        self.assertGreaterEqual(button.width(), metrics.horizontalAdvance(button.text()) + 16)
        text = "🎉 用户内容 <原样>"
        widget._active_conv.add_message("user", text)
        widget._render_conv_messages()
        self.assertIn(text, widget._chat_display.toPlainText())


if __name__ == "__main__":
    unittest.main()
