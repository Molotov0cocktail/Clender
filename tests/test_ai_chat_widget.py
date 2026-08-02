import os
import unittest
from unittest import mock

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PyQt5.QtCore import QUrl
from PyQt5.QtWidgets import QApplication

from models import Conversation
from ui.ai_chat_widget import AIChatWidget


class AIChatWidgetThinkingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def setUp(self):
        self.conversations = {}
        patchers = [
            mock.patch(
                "ui.ai_chat_widget.load_conversations",
                side_effect=lambda: self.conversations,
            ),
            mock.patch("ui.ai_chat_widget.save_conversations"),
            mock.patch(
                "ui.ai_chat_widget.cfg_mod.load_config",
                return_value={"context_window": 128000},
            ),
            mock.patch(
                "ui.ai_chat_widget.cfg_mod.is_api_configured",
                return_value=False,
            ),
        ]
        for patcher in patchers:
            patcher.start()
            self.addCleanup(patcher.stop)

    def _conversation(self, conversation_id, before=0, after=0, think_size=20):
        conversation = Conversation(id=conversation_id, title=conversation_id)
        for index in range(before):
            conversation.add_message("user", f"历史消息 {index} " + "前" * 80)
        conversation.add_message("think", "\n".join(["推理正文 " + "长" * 60] * think_size))
        for index in range(after):
            conversation.add_message("assistant", f"后续消息 {index} " + "后" * 80)
        return conversation

    def _widget(self, *conversations):
        self.conversations.update(
            (conversation.id, conversation) for conversation in conversations
        )
        widget = AIChatWidget()
        widget.resize(520, 360)
        widget.show()
        self.app.processEvents()
        self.addCleanup(widget.close)
        return widget

    def _think_title_document_y(self, widget, link_text):
        cursor = widget._chat_display.document().find(link_text)
        self.assertFalse(cursor.isNull(), f"未找到 Thinking 链接文本: {link_text}")
        rect = widget._chat_display.document().documentLayout().blockBoundingRect(
            cursor.block()
        )
        return int(rect.top())

    def test_middle_thinking_expands_downward_without_moving_clicked_title(self):
        conversation = self._conversation("conversation-a", before=8, after=8, think_size=30)
        widget = self._widget(conversation)
        scroll_bar = widget._chat_display.verticalScrollBar()

        title_document_y = self._think_title_document_y(widget, "展开 ▼")
        self.assertIsNotNone(widget._anchor_document_y("think_anchor_0"))
        scroll_bar.setValue(title_document_y - 60)
        self.app.processEvents()
        before_viewport_y = title_document_y - scroll_bar.value()
        before_height = widget._chat_display.document().size().height()

        widget._on_think_toggle(QUrl("toggle_think_0"))
        self.app.processEvents()

        expanded_document_y = self._think_title_document_y(widget, "收起 ▲")
        after_viewport_y = expanded_document_y - scroll_bar.value()
        after_height = widget._chat_display.document().size().height()
        self.assertGreater(after_height, before_height)
        self.assertLessEqual(abs(after_viewport_y - before_viewport_y), 2)

        widget._on_think_toggle(QUrl("toggle_think_0"))
        self.app.processEvents()
        collapsed_document_y = self._think_title_document_y(widget, "展开 ▼")
        collapsed_viewport_y = collapsed_document_y - scroll_bar.value()
        collapsed_height = widget._chat_display.document().size().height()
        self.assertLess(collapsed_height, after_height)
        self.assertLessEqual(abs(collapsed_viewport_y - after_viewport_y), 2)

    def test_expansion_state_is_isolated_by_conversation(self):
        first = self._conversation("conversation-a")
        second = self._conversation("conversation-b")
        widget = self._widget(first, second)

        widget._on_think_toggle(QUrl("toggle_think_0"))
        self.assertIn("收起 ▲", widget._chat_display.toPlainText())

        widget._switch_conv(second.id)
        self.app.processEvents()
        self.assertIn("展开 ▼", widget._chat_display.toPlainText())
        self.assertNotIn("收起 ▲", widget._chat_display.toPlainText())

    def test_malformed_negative_and_out_of_range_toggle_urls_are_ignored(self):
        widget = self._widget(self._conversation("conversation-a"))
        original_text = widget._chat_display.toPlainText()

        for href in (
            "toggle_think_bad",
            "toggle_think_-1",
            "toggle_think_1",
            "other_0",
        ):
            with self.subTest(href=href):
                widget._on_think_toggle(QUrl(href))
                self.assertEqual(widget._chat_display.toPlainText(), original_text)

    def test_clearing_conversation_resets_its_thinking_expansion_state(self):
        conversation = self._conversation("conversation-a")
        widget = self._widget(conversation)
        widget._on_think_toggle(QUrl("toggle_think_0"))
        self.assertIn("收起 ▲", widget._chat_display.toPlainText())

        widget._clear_chat()
        conversation.add_message("think", "新的推理")
        widget._render_conv_messages()
        self.app.processEvents()
        self.assertIn("展开 ▼", widget._chat_display.toPlainText())
        self.assertNotIn("收起 ▲", widget._chat_display.toPlainText())

    def test_default_render_and_conversation_switch_still_scroll_to_bottom(self):
        first = self._conversation("conversation-a", before=8, after=8)
        second = self._conversation("conversation-b", before=8, after=8)
        widget = self._widget(first, second)
        scroll_bar = widget._chat_display.verticalScrollBar()

        scroll_bar.setValue(scroll_bar.minimum())
        first.add_message("assistant", "新消息 " + "新" * 200)
        widget._render_conv_messages()
        self.app.processEvents()
        self.assertEqual(scroll_bar.value(), scroll_bar.maximum())

        scroll_bar.setValue(scroll_bar.minimum())
        widget._switch_conv(second.id)
        self.app.processEvents()
        self.assertEqual(scroll_bar.value(), scroll_bar.maximum())

        widget.apply_theme()
        self.app.processEvents()
        self.assertLessEqual(scroll_bar.maximum() - scroll_bar.value(), 5)


if __name__ == "__main__":
    unittest.main()
