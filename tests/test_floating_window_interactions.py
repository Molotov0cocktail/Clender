import os
import unittest
from datetime import date, datetime
from unittest import mock

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PyQt5.QtCore import QEvent, QPoint, QPointF, Qt
from PyQt5.QtGui import QMouseEvent
from PyQt5.QtTest import QTest
from PyQt5.QtWidgets import (
    QAbstractButton,
    QApplication,
    QLabel,
    QLineEdit,
)

from constants import DEFAULT_CONFIG
from floating_window_logic import FloatingWindowSettings
from models import Event, EventType
from ui.daily_floating_window import DailyFloatingWindow


class FloatingWindowInteractionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def setUp(self):
        self._events = []
        patchers = [
            mock.patch("config.load_config", return_value=dict(DEFAULT_CONFIG)),
            mock.patch(
                "ui.daily_floating_window.EventService.get_events_overlapping_range",
                side_effect=lambda _start, _end: list(self._events),
            ),
        ]
        for patcher in patchers:
            patcher.start()
            self.addCleanup(patcher.stop)

    @staticmethod
    def _settings(opacity=90, enabled=True):
        return FloatingWindowSettings.from_config({
            "floating_window_enabled": enabled,
            "floating_window_opacity": opacity,
            "floating_window_start_time": "00:00",
            "floating_window_end_time": "23:59",
        })

    def _window(self, opacity=90):
        window = DailyFloatingWindow()
        window.resize(360, 420)
        window.move(180, 140)
        window.apply_settings(self._settings(opacity=opacity))
        window.show()
        self.app.processEvents()
        self.addCleanup(window.shutdown)
        return window

    @staticmethod
    def _send_mouse(receiver, event_type, local_pos, global_pos, button, buttons):
        event = QMouseEvent(
            event_type,
            QPointF(local_pos),
            QPointF(global_pos),
            button,
            buttons,
            Qt.NoModifier,
        )
        QApplication.sendEvent(receiver, event)

    def _drag_gesture(self, receiver, local_pos, delta):
        start_global = receiver.mapToGlobal(local_pos)
        self._send_mouse(
            receiver,
            QEvent.MouseButtonPress,
            local_pos,
            start_global,
            Qt.LeftButton,
            Qt.LeftButton,
        )
        self._send_mouse(
            receiver,
            QEvent.MouseMove,
            local_pos + delta,
            start_global + delta,
            Qt.NoButton,
            Qt.LeftButton,
        )
        self._send_mouse(
            receiver,
            QEvent.MouseButtonRelease,
            local_pos + delta,
            start_global + delta,
            Qt.LeftButton,
            Qt.NoButton,
        )
        self.app.processEvents()

    @staticmethod
    def _pin_button(window):
        for button in window.findChildren(QAbstractButton):
            description = f"{button.text()} {button.toolTip()}".lower()
            if button.isCheckable() or "置顶" in description or "pin" in description:
                return button
        return None

    def test_shell_is_frameless_bottom_by_default_and_has_no_native_or_date_title(self):
        window = self._window()
        window.refresh(date(2026, 8, 2))
        flags = window.windowFlags()
        label_texts = [label.text() for label in window.findChildren(QLabel)]

        self.assertTrue(flags & Qt.Tool)
        self.assertTrue(flags & Qt.FramelessWindowHint)
        self.assertTrue(flags & Qt.WindowStaysOnBottomHint)
        self.assertFalse(flags & Qt.WindowStaysOnTopHint)
        self.assertEqual(
            (window.windowTitle(), any("今日事项" in text for text in label_texts)),
            ("", False),
        )

    def test_pin_switches_top_and_bottom_without_persisting_or_losing_window_state(self):
        window = self._window(opacity=65)
        original_geometry = window.geometry()
        original_opacity = window.windowOpacity()

        with mock.patch("config.save_config") as save_config:
            window._set_pinned(True)
            self.app.processEvents()
            top_flags = window.windowFlags()
            self.assertTrue(top_flags & Qt.WindowStaysOnTopHint)
            self.assertFalse(top_flags & Qt.WindowStaysOnBottomHint)
            self.assertTrue(window.isVisible())
            self.assertEqual(window.geometry(), original_geometry)
            self.assertAlmostEqual(window.windowOpacity(), original_opacity, places=2)

            window._set_pinned(False)
            self.app.processEvents()
            bottom_flags = window.windowFlags()
            self.assertTrue(bottom_flags & Qt.WindowStaysOnBottomHint)
            self.assertFalse(bottom_flags & Qt.WindowStaysOnTopHint)
            self.assertTrue(window.isVisible())
            self.assertEqual(window.geometry(), original_geometry)
            save_config.assert_not_called()

        restarted = self._window()
        self.assertTrue(restarted.windowFlags() & Qt.WindowStaysOnBottomHint)
        self.assertFalse(restarted.windowFlags() & Qt.WindowStaysOnTopHint)

    def test_window_applies_true_zero_and_full_opacity(self):
        window = self._window(opacity=0)
        self.assertAlmostEqual(window.windowOpacity(), 0.0, places=2)

        window.apply_settings(self._settings(opacity=100))
        self.app.processEvents()
        self.assertAlmostEqual(window.windowOpacity(), 1.0, places=2)

    def test_list_drag_waits_for_threshold_then_moves_window(self):
        window = self._window()
        viewport = window._event_list.viewport()
        local = QPoint(max(20, viewport.width() // 2), max(20, viewport.height() // 2))
        threshold = QApplication.startDragDistance()
        start_window_pos = window.pos()
        start_global = viewport.mapToGlobal(local)

        self._send_mouse(
            viewport,
            QEvent.MouseButtonPress,
            local,
            start_global,
            Qt.LeftButton,
            Qt.LeftButton,
        )
        small_delta = QPoint(max(1, threshold - 1), 0)
        self._send_mouse(
            viewport,
            QEvent.MouseMove,
            local + small_delta,
            start_global + small_delta,
            Qt.NoButton,
            Qt.LeftButton,
        )
        self.assertEqual(window.pos(), start_window_pos)

        large_delta = QPoint(threshold + 24, threshold + 16)
        self._send_mouse(
            viewport,
            QEvent.MouseMove,
            local + large_delta,
            start_global + large_delta,
            Qt.NoButton,
            Qt.LeftButton,
        )
        self._send_mouse(
            viewport,
            QEvent.MouseButtonRelease,
            local + large_delta,
            start_global + large_delta,
            Qt.LeftButton,
            Qt.NoButton,
        )
        self.app.processEvents()

        self.assertEqual(window.pos(), start_window_pos + large_delta)

    def test_input_and_pin_button_are_excluded_from_dragging(self):
        window = self._window()
        input_widget = window.findChild(QLineEdit)
        pin_button = self._pin_button(window)
        self.assertIsNotNone(input_widget, "悬浮窗必须保留底部 AI 输入框")
        self.assertIsNotNone(pin_button, "悬浮窗必须提供置顶切换按钮")

        for receiver in (input_widget, pin_button):
            with self.subTest(widget=type(receiver).__name__):
                start = window.pos()
                local = receiver.rect().center()
                self._drag_gesture(receiver, local, QPoint(60, 45))
                self.assertEqual(window.pos(), start)

    def test_right_edge_and_bottom_right_corner_resize(self):
        window = self._window()

        before_edge = window.geometry()
        edge_local = QPoint(window.width() - 2, window.height() // 2)
        self._drag_gesture(window, edge_local, QPoint(36, 0))
        after_edge = window.geometry()
        self.assertEqual(after_edge.topLeft(), before_edge.topLeft())
        self.assertGreaterEqual(after_edge.width(), before_edge.width() + 30)
        self.assertEqual(after_edge.height(), before_edge.height())

        before_corner = window.geometry()
        corner_local = QPoint(window.width() - 2, window.height() - 2)
        self._drag_gesture(window, corner_local, QPoint(28, 24))
        after_corner = window.geometry()
        self.assertEqual(after_corner.topLeft(), before_corner.topLeft())
        self.assertGreaterEqual(after_corner.width(), before_corner.width() + 22)
        self.assertGreaterEqual(after_corner.height(), before_corner.height() + 18)

    def test_single_click_does_not_activate_or_request_edit(self):
        self._events = [Event(
            id=7,
            event_type=EventType.REMINDER,
            title="单击不打开",
            start_time="2026-08-02 10:00",
        )]
        window = self._window()
        window.refresh(date(2026, 8, 2))
        self.app.processEvents()
        activated = []
        edit_requests = []
        if hasattr(window, "event_activated"):
            window.event_activated.connect(activated.append)
        if hasattr(window, "event_edit_requested"):
            window.event_edit_requested.connect(edit_requests.append)

        item_rect = window._event_list.visualItemRect(window._event_list.item(0))
        QTest.mouseClick(window._event_list.viewport(), Qt.LeftButton, pos=item_rect.center())
        self.app.processEvents()

        self.assertEqual(activated, [])
        self.assertEqual(edit_requests, [])

    def test_double_click_emits_exactly_one_edit_request_for_valid_event(self):
        self._events = [Event(
            id=9,
            event_type=EventType.TIMESPAN,
            title="双击编辑",
            start_time="2026-08-02 10:00",
            end_time="2026-08-02 11:00",
        )]
        window = self._window()
        window.refresh(date(2026, 8, 2))
        self.app.processEvents()
        requested = []
        window.event_edit_requested.connect(requested.append)

        item_rect = window._event_list.visualItemRect(window._event_list.item(0))
        QTest.mouseDClick(window._event_list.viewport(), Qt.LeftButton, pos=item_rect.center())
        self.app.processEvents()

        self.assertEqual(requested, [9])

    def test_same_day_minute_tick_recomputes_event_states(self):
        self._events = [Event(
            id=11,
            event_type=EventType.REMINDER,
            title="分钟刷新",
            start_time="2026-08-02 10:00",
        )]
        window = self._window()
        window._displayed_date = date.today()

        with mock.patch(
            "ui.daily_floating_window.classify_event_states",
            create=True,
            return_value={},
        ) as classify:
            window._check_date()

        classify.assert_called_once()
        args = classify.call_args.args
        self.assertIsInstance(args[1], datetime)


if __name__ == "__main__":
    unittest.main()
