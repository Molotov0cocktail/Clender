import os
import tempfile
import unittest
from datetime import date
from pathlib import Path
from unittest import mock


os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PyQt5.QtCore import Qt, pyqtSignal
from PyQt5.QtWidgets import (
    QApplication,
    QDialog,
    QFrame,
    QTextBrowser,
    QTextEdit,
    QWidget,
)

import database
from constants import DEFAULT_CONFIG
from event_service import EventService
from floating_window_logic import FloatingWindowSettings
from models import Conversation, Event, EventType
from ui.ai_chat_widget import AIChatWidget
from ui.daily_floating_window import DailyFloatingWindow
from ui.main_window import MainWindow


class SignalStub:
    def __init__(self):
        self._callbacks = []

    def connect(self, callback):
        self._callbacks.append(callback)

    def emit(self, *args):
        for callback in tuple(self._callbacks):
            callback(*args)


class EventTypeConversionTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.db_path = str(Path(self.temp_dir.name) / "clender.db")
        db_patcher = mock.patch.object(database, "DB_PATH", self.db_path)
        db_patcher.start()
        self.addCleanup(db_patcher.stop)
        database.init_db()

    def test_service_persists_reminder_to_timespan_and_back_clearing_end(self):
        event_id = EventService.add_event(
            title="规划",
            event_type="reminder",
            start_time="2026-08-02 09:00",
            estimated_duration=25,
        )

        self.assertEqual(
            EventService.update_event(
                event_id,
                event_type="timespan",
                end_time="2026-08-02 10:30",
                estimated_duration=0,
            ),
            1,
        )
        as_timespan = EventService.get_event_by_id(event_id)
        self.assertEqual(as_timespan.event_type, EventType.TIMESPAN)
        self.assertEqual(as_timespan.end_time, "2026-08-02 10:30")

        self.assertEqual(
            EventService.update_event(
                event_id,
                event_type="reminder",
                estimated_duration=15,
            ),
            1,
        )
        as_reminder = EventService.get_event_by_id(event_id)
        self.assertEqual(as_reminder.event_type, EventType.REMINDER)
        self.assertIsNone(as_reminder.end_time)
        self.assertEqual(as_reminder.estimated_duration, 15)

    def test_invalid_timespan_conversions_never_reach_database_update(self):
        event_id = EventService.add_event(
            title="提醒",
            event_type="reminder",
            start_time="2026-08-02 09:00",
        )
        invalid_cases = (
            ({"event_type": "timespan", "end_time": None}, "必须包含结束时间"),
            (
                {"event_type": "timespan", "end_time": "2026-08-02 08:59"},
                "结束时间必须晚于开始时间",
            ),
        )

        for fields, expected_error in invalid_cases:
            with self.subTest(fields=fields), mock.patch.object(
                database,
                "update_event",
                wraps=database.update_event,
            ) as update:
                with self.assertRaisesRegex(ValueError, expected_error):
                    EventService.update_event(event_id, **fields)
                update.assert_not_called()
                unchanged = EventService.get_event_by_id(event_id)
                self.assertEqual(unchanged.event_type, EventType.REMINDER)
                self.assertIsNone(unchanged.end_time)


class FloatingItemEditSignalTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def test_single_click_is_inert_and_double_click_requests_edit(self):
        event = Event(
            id=7,
            event_type=EventType.REMINDER,
            title="双击编辑",
            start_time="2026-08-02 09:00",
        )
        with mock.patch("config.load_config", return_value=dict(DEFAULT_CONFIG)), mock.patch(
            "ui.daily_floating_window.EventService.get_events_overlapping_range",
            return_value=[event],
        ):
            window = DailyFloatingWindow()
            self.addCleanup(window.shutdown)
            window.refresh(date(2026, 8, 2))

        requested = []
        window.event_edit_requested.connect(requested.append)
        legacy_activations = []
        if hasattr(window, "event_activated"):
            window.event_activated.connect(legacy_activations.append)
        item = window._event_list.item(0)

        window._event_list.itemClicked.emit(item)
        self.assertEqual(requested, [])
        self.assertEqual(legacy_activations, [])

        window._event_list.itemDoubleClicked.emit(item)
        self.assertEqual(requested, [7])


class StubCalendar(QWidget):
    date_selected = pyqtSignal(date)
    event_activated = pyqtSignal(object)

    def __init__(self):
        super().__init__()
        self.set_selected_date = mock.Mock()
        self.update_event_markers = mock.Mock()
        self.apply_theme = mock.Mock()


class StubEventManager(QWidget):
    data_changed = pyqtSignal()

    def __init__(self):
        super().__init__()
        self.set_date = mock.Mock()
        self.refresh = mock.Mock()
        self.apply_theme = mock.Mock()


class StubAIChat(QFrame):
    data_changed = pyqtSignal()
    external_request_status = pyqtSignal(str)

    def __init__(self):
        super().__init__()
        self.apply_theme = mock.Mock()
        self.submit_external_message = mock.Mock(return_value=True)


class StubFloatingWindow:
    def __init__(self):
        self.event_activated = SignalStub()
        self.event_edit_requested = SignalStub()
        self.ai_message_submitted = SignalStub()
        self.geometry_changed = SignalStub()
        self.visibility_change_requested = SignalStub()
        self._visible = False
        self.refresh = mock.Mock()
        self.apply_theme = mock.Mock()
        self.apply_settings = mock.Mock(side_effect=self._apply_settings)
        self.apply_font_scale = mock.Mock()
        self.set_ai_request_status = mock.Mock()
        self.shutdown = mock.Mock()
        self.show = mock.Mock(side_effect=lambda: self._set_visible(True))
        self.hide = mock.Mock(side_effect=lambda: self._set_visible(False))
        self.raise_ = mock.Mock()
        self.activateWindow = mock.Mock()

    def _set_visible(self, visible):
        self._visible = visible

    def _apply_settings(self, settings):
        self._visible = settings.enabled

    def isVisible(self):
        return self._visible


class EditDialogStub:
    def __init__(self, result, data=None):
        self.exec_ = mock.Mock(return_value=result)
        self.get_data = mock.Mock(return_value=dict(data or {}))


class MainWindowFloatingEditTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def setUp(self):
        self.config = dict(DEFAULT_CONFIG)
        self.floating = StubFloatingWindow()
        self.ai_chat = StubAIChat()
        self.event = Event(
            id=5,
            event_type=EventType.REMINDER,
            title="原事项",
            start_time="2026-08-02 09:00",
            description="旧备注",
        )

    def make_window(self):
        patchers = [
            mock.patch("ui.main_window.CalendarWidget", StubCalendar),
            mock.patch("ui.main_window.EventManager", StubEventManager),
            mock.patch("ui.main_window.AIChatWidget", return_value=self.ai_chat),
            mock.patch(
                "ui.main_window.DailyFloatingWindow",
                return_value=self.floating,
                create=True,
            ),
            mock.patch(
                "ui.main_window.cfg_mod.load_config",
                side_effect=lambda: dict(self.config),
            ),
            mock.patch("ui.main_window.cfg_mod.get_theme", return_value="light"),
            mock.patch("ui.main_window.cfg_mod.save_config"),
            mock.patch("ui.main_window.theme_manager.apply_theme"),
            mock.patch("ui.main_window.theme_manager.switch_theme"),
            mock.patch("ui.main_window.EventService.load_sample_data_if_empty"),
            mock.patch("ui.main_window.EventService.get_event_counts", return_value={}),
            mock.patch(
                "ui.main_window.EventService.get_event_by_id",
                side_effect=lambda event_id: self.event if event_id == self.event.id else None,
            ),
            mock.patch(
                "ui.main_window.QSystemTrayIcon.isSystemTrayAvailable",
                return_value=False,
            ),
        ]
        for patcher in patchers:
            patcher.start()
            self.addCleanup(patcher.stop)
        window = MainWindow(self.app)
        self.addCleanup(window.close)
        window._calendar.update_event_markers.reset_mock()
        window._event_mgr.refresh.reset_mock()
        self.floating.refresh.reset_mock()
        return window

    def test_accepted_edit_updates_through_service_and_refreshes_all_views_once(self):
        window = self.make_window()
        edited = {
            "event_type": "timespan",
            "title": "新事项",
            "start_time": "2026-08-02 10:00",
            "end_time": "2026-08-02 11:00",
            "description": "新备注",
            "estimated_duration": 0,
        }
        dialog = EditDialogStub(QDialog.Accepted, edited)

        with mock.patch(
            "ui.main_window.EventDialog",
            return_value=dialog,
            create=True,
        ) as dialog_class, mock.patch(
            "ui.main_window.EventService.update_event",
            return_value=1,
        ) as update:
            self.floating.event_edit_requested.emit(self.event.id)

        dialog_class.assert_called_once()
        dialog.exec_.assert_called_once_with()
        update.assert_called_once_with(self.event.id, **edited)
        window._calendar.update_event_markers.assert_called_once_with({})
        window._event_mgr.refresh.assert_called_once_with()
        self.floating.refresh.assert_called_once_with()

    def test_cancelled_edit_does_not_update_or_refresh(self):
        window = self.make_window()
        dialog = EditDialogStub(QDialog.Rejected)

        with mock.patch(
            "ui.main_window.EventDialog",
            return_value=dialog,
            create=True,
        ), mock.patch("ui.main_window.EventService.update_event") as update:
            self.floating.event_edit_requested.emit(self.event.id)

        dialog.exec_.assert_called_once_with()
        update.assert_not_called()
        window._calendar.update_event_markers.assert_not_called()
        window._event_mgr.refresh.assert_not_called()
        self.floating.refresh.assert_not_called()

    def test_failed_edit_reports_error_without_refresh(self):
        window = self.make_window()
        dialog = EditDialogStub(QDialog.Accepted, {
            "event_type": "timespan",
            "title": "坏事项",
            "start_time": "2026-08-02 10:00",
            "end_time": "2026-08-02 09:00",
            "description": "",
            "estimated_duration": 0,
        })

        with mock.patch(
            "ui.main_window.EventDialog",
            return_value=dialog,
            create=True,
        ), mock.patch(
            "ui.main_window.EventService.update_event",
            side_effect=ValueError("结束时间必须晚于开始时间"),
        ), mock.patch(
            "ui.main_window.QMessageBox.warning",
            create=True,
        ) as warning, mock.patch(
            "ui.main_window.QMessageBox.critical",
            create=True,
        ) as critical:
            self.floating.event_edit_requested.emit(self.event.id)

        self.assertEqual(warning.call_count + critical.call_count, 1)
        window._calendar.update_event_markers.assert_not_called()
        window._event_mgr.refresh.assert_not_called()
        self.floating.refresh.assert_not_called()

    def test_main_window_bridges_floating_ai_submission_and_short_status(self):
        self.make_window()

        self.floating.ai_message_submitted.emit("把会议改到下午")
        self.ai_chat.submit_external_message.assert_called_once_with("把会议改到下午")

        self.ai_chat.external_request_status.emit("changed")
        self.floating.set_ai_request_status.assert_called_once_with("changed")


class FakeAICallThread:
    instances = []

    def __init__(self, messages):
        self.messages = messages
        self.result_ready = SignalStub()
        self.error_occurred = SignalStub()
        self.finished = SignalStub()
        self.started = False
        self.running = False
        self.__class__.instances.append(self)

    def start(self):
        self.started = True
        self.running = True

    def isRunning(self):
        return self.running


class AIExternalSubmissionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def setUp(self):
        FakeAICallThread.instances.clear()
        self.first = Conversation(id="conversation-a", title="固定对话 A")
        self.second = Conversation(id="conversation-b", title="固定对话 B")
        self.conversations = {
            self.first.id: self.first,
            self.second.id: self.second,
        }
        self.save_conversations = mock.Mock()
        patchers = [
            mock.patch(
                "ui.ai_chat_widget.load_conversations",
                side_effect=lambda: self.conversations,
            ),
            mock.patch(
                "ui.ai_chat_widget.save_conversations",
                self.save_conversations,
            ),
            mock.patch(
                "ui.ai_chat_widget.cfg_mod.load_config",
                return_value={
                    "context_window": 128000,
                    "max_tokens": 4096,
                },
            ),
            mock.patch(
                "ui.ai_chat_widget.cfg_mod.is_api_configured",
                return_value=True,
            ),
            mock.patch(
                "ui.ai_chat_widget.AICallThread",
                FakeAICallThread,
            ),
            mock.patch(
                "ui.ai_chat_widget.AIService.build_request_messages",
                side_effect=lambda conversation, **_kwargs: [
                    {"role": "system", "content": conversation.id}
                ],
            ),
            mock.patch(
                "ui.ai_chat_widget.AIService.count_messages_tokens",
                return_value=1,
            ),
        ]
        for patcher in patchers:
            patcher.start()
            self.addCleanup(patcher.stop)

    def make_widget(self):
        widget = AIChatWidget()
        self.addCleanup(widget.close)
        return widget

    def test_external_submit_uses_existing_thread_and_binds_result_to_origin_conversation(self):
        widget = self.make_widget()
        statuses = []
        widget.external_request_status.connect(statuses.append)

        self.assertTrue(widget.submit_external_message("调整 A 的日程"))
        self.assertEqual(len(FakeAICallThread.instances), 1)
        thread = FakeAICallThread.instances[0]
        self.assertTrue(thread.started)
        self.assertEqual(
            thread.messages,
            [{"role": "system", "content": self.first.id}],
        )
        self.assertEqual(self.first.messages[-1].content, "调整 A 的日程")
        self.assertEqual(self.first.messages[-1].role, "user")

        widget._switch_conv(self.second.id)
        thread.running = False
        thread.result_ready.emit({
            "think": "",
            "operations": [],
            "reply_text": "A 的模型正文",
            "usage": {},
        })

        self.assertEqual(self.first.messages[-1].content, "A 的模型正文")
        self.assertEqual(self.first.messages[-1].role, "assistant")
        self.assertEqual(self.second.messages, [])
        self.assertEqual(statuses[0], "working")
        self.assertIn("unchanged", statuses)
        self.assertTrue(self.save_conversations.called)

    def test_external_request_blocks_both_external_and_main_entries_while_busy(self):
        widget = self.make_widget()
        self.assertTrue(widget.submit_external_message("第一条悬浮输入"))

        self.assertFalse(widget.submit_external_message("第二条悬浮输入"))
        widget._edit_input.setText("主输入也不应排队")
        widget._send_message()

        contents = [message.content for message in self.first.messages]
        self.assertEqual(contents, ["第一条悬浮输入"])
        self.assertEqual(len(FakeAICallThread.instances), 1)

    def test_main_request_blocks_external_entry_while_busy(self):
        widget = self.make_widget()
        widget._edit_input.setText("主输入先发起")
        widget._send_message()

        self.assertFalse(widget.submit_external_message("悬浮输入不得覆盖"))
        contents = [message.content for message in self.first.messages]
        self.assertEqual(contents, ["主输入先发起"])
        self.assertEqual(len(FakeAICallThread.instances), 1)

    def test_main_request_broadcasts_working_then_unchanged(self):
        widget = self.make_widget()
        statuses = []
        widget.external_request_status.connect(statuses.append)

        widget._edit_input.setText("main request without schedule changes")
        self.assertTrue(widget._send_message())
        self.assertEqual(statuses, ["working"])
        self.assertFalse(widget._edit_input.isEnabled())
        self.assertFalse(widget._btn_send.isEnabled())

        thread = FakeAICallThread.instances[0]
        thread.running = False
        thread.result_ready.emit({
            "think": "",
            "operations": [],
            "reply_text": "answer kept in the main conversation only",
            "usage": {},
        })

        self.assertEqual(statuses, ["working", "unchanged"])
        self.assertTrue(widget._edit_input.isEnabled())
        self.assertTrue(widget._btn_send.isEnabled())
        self.assertEqual(
            self.first.messages[-1].content,
            "answer kept in the main conversation only",
        )

    def test_main_changed_result_broadcasts_changed_and_refreshes_once(self):
        widget = self.make_widget()
        statuses = []
        changes = []
        widget.external_request_status.connect(statuses.append)
        widget.data_changed.connect(lambda: changes.append(True))

        widget._edit_input.setText("main request with a schedule change")
        self.assertTrue(widget._send_message())
        thread = FakeAICallThread.instances[0]

        with mock.patch(
            "ui.ai_chat_widget.AIService.execute_operations",
            return_value=["✅ 已添加事项 #1"],
        ):
            thread.running = False
            thread.result_ready.emit({
                "think": "",
                "operations": [{"action": "add"}],
                "reply_text": "",
                "usage": {},
            })

        self.assertEqual(statuses, ["working", "changed"])
        self.assertEqual(changes, [True])

    def test_main_request_error_broadcasts_bounded_error_and_restores_entries(self):
        widget = self.make_widget()
        statuses = []
        widget.external_request_status.connect(statuses.append)

        widget._edit_input.setText("main request that fails")
        self.assertTrue(widget._send_message())
        thread = FakeAICallThread.instances[0]
        thread.running = False
        thread.error_occurred.emit("request timed out " + "details " * 100)

        self.assertEqual(statuses[0], "working")
        self.assertEqual(len(statuses), 2)
        self.assertTrue(statuses[-1].startswith("error:"))
        self.assertLessEqual(len(statuses[-1]), 200)
        self.assertTrue(widget._edit_input.isEnabled())
        self.assertTrue(widget._btn_send.isEnabled())

    def test_changed_result_emits_short_status_and_single_data_refresh(self):
        widget = self.make_widget()
        statuses = []
        changes = []
        widget.external_request_status.connect(statuses.append)
        widget.data_changed.connect(lambda: changes.append(True))
        self.assertTrue(widget.submit_external_message("新增日程"))
        thread = FakeAICallThread.instances[0]

        with mock.patch(
            "ui.ai_chat_widget.AIService.execute_operations",
            return_value=["✅ 已添加事项 #1"],
        ):
            thread.running = False
            thread.result_ready.emit({
                "think": "内部推理",
                "operations": [{"action": "add"}],
                "reply_text": "不在悬浮显示的正文",
                "usage": {},
            })

        self.assertIn("changed", statuses)
        self.assertEqual(changes, [True])

    def test_error_emits_only_bounded_short_status(self):
        widget = self.make_widget()
        statuses = []
        widget.external_request_status.connect(statuses.append)
        self.assertTrue(widget.submit_external_message("触发错误"))
        thread = FakeAICallThread.instances[0]

        thread.running = False
        thread.error_occurred.emit("请求超时" + "详情" * 200)

        errors = [status for status in statuses if status.startswith("error:")]
        self.assertEqual(len(errors), 1)
        self.assertLessEqual(len(errors[0]), 200)
        self.assertNotIn("working", errors[0])


class FloatingAIVisibilityContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def test_floating_window_has_input_and_short_status_but_no_ai_body_widget(self):
        with mock.patch("config.load_config", return_value=dict(DEFAULT_CONFIG)), mock.patch(
            "ui.daily_floating_window.EventService.get_events_overlapping_range",
            return_value=[],
        ):
            window = DailyFloatingWindow()
            self.addCleanup(window.shutdown)

        self.assertTrue(hasattr(window, "_ai_input"))
        self.assertTrue(hasattr(window, "_ai_status"))
        self.assertEqual(window.findChildren(QTextBrowser), [])
        self.assertEqual(window.findChildren(QTextEdit), [])
        self.assertFalse(hasattr(window, "_chat_display"))
        self.assertFalse(hasattr(window, "_ai_output"))


if __name__ == "__main__":
    unittest.main()
