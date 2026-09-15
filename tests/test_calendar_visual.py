"""T74 calendar visual contracts: block styling, grid lines, markers, month/week chrome."""
import os
import unittest
from datetime import date, timedelta
from math import ceil
from unittest import mock

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PyQt5.QtCore import QEvent, QRect, QRectF, Qt
from PyQt5.QtGui import QBrush, QColor, QPen
from PyQt5.QtWidgets import QApplication, QLabel, QPushButton

import theme_manager
from calendar_logic import build_day_blocks
from constants import EVENT_COLORS, EVENT_COLORS_DARK, REMINDER_LINE_COLOR
from models import Event, EventType
from ui.calendar_widget import CalendarWidget
from ui.canvas import DayCanvas, WeekCanvas


VISUAL_THEME = {
    "muted_color": "#808080",
    "frame_bg": "#ffffff",
    "theme_name": "light",
    "grid_line_color": "#112233",
    "grid_line_minor_color": "#445566",
    "event_block_text": "#010203",
}

VISUAL_THEME_DARK = {
    "muted_color": "#8e9fb5",
    "frame_bg": "#192133",
    "theme_name": "dark",
    "grid_line_color": "#2b3345",
    "grid_line_minor_color": "#222a3a",
    "event_block_text": "#121826",
}


def rgb(color):
    return (color.red(), color.green(), color.blue())


def timespan_event(event_id=1, start="2026-09-15 10:00", end="2026-09-15 12:00"):
    return Event(
        id=event_id,
        event_type=EventType.TIMESPAN,
        title=f"会议 {event_id}",
        start_time=start,
        end_time=end,
    )


def reminder_event(event_id=9, start="2026-09-15 10:00"):
    return Event(
        id=event_id,
        event_type=EventType.REMINDER,
        title="提醒",
        start_time=start,
        estimated_duration=0,
    )


class RecordingPainter:
    """Fake painter capturing styled draw calls without pixel rendering."""

    def __init__(self, metrics):
        self._metrics = metrics
        self._state = {
            "pen_color": None,
            "pen_width": 1,
            "pen_style": Qt.NoPen,
            "pen_cap": Qt.SquareCap,
            "brush_color": None,
        }
        self._stack = []
        self.rounded_rects = []
        self.rects = []
        self.lines = []
        self.texts = []
        self.ellipses = []
        self.clip_path_count = 0

    def fontMetrics(self):
        return self._metrics

    def save(self):
        self._stack.append(dict(self._state))

    def restore(self):
        if self._stack:
            self._state = self._stack.pop()

    def setClipPath(self, path):
        del path
        self.clip_path_count += 1

    def setClipRect(self, rect):
        del rect

    def setPen(self, pen):
        if isinstance(pen, QPen):
            self._state["pen_color"] = QColor(pen.color())
            self._state["pen_width"] = pen.width()
            self._state["pen_style"] = pen.style()
            self._state["pen_cap"] = pen.capStyle()
        elif isinstance(pen, QColor):
            self._state["pen_color"] = QColor(pen)
            self._state["pen_width"] = 1
            self._state["pen_style"] = Qt.SolidLine
            self._state["pen_cap"] = Qt.SquareCap
        elif pen == Qt.NoPen:
            self._state["pen_color"] = None
            self._state["pen_style"] = Qt.NoPen

    def setBrush(self, brush):
        color = brush.color() if isinstance(brush, QBrush) else QColor(brush)
        self._state["brush_color"] = QColor(color)

    def _snapshot(self):
        return dict(self._state)

    def drawRoundedRect(self, rect, rx, ry, *rest):
        del rest
        self.rounded_rects.append(
            {"rect": QRectF(rect), "rx": float(rx), "ry": float(ry), **self._snapshot()}
        )

    def drawRect(self, rect):
        self.rects.append({"rect": QRectF(rect), **self._snapshot()})

    def drawLine(self, *args):
        self.lines.append({"args": args, **self._snapshot()})

    def drawText(self, *args):
        self.texts.append({"args": args, **self._snapshot()})

    def drawEllipse(self, rect):
        self.ellipses.append({"rect": QRectF(rect), **self._snapshot()})


class CanvasEntryTestBase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def draw_entries(self, theme, events, width=320, pick=None):
        canvas = DayCanvas(build_day_blocks(events, 30), 720, 30, theme)
        self.addCleanup(canvas.close)
        canvas._paint_regions = []
        entries = canvas._build_layout(width)
        self.assertTrue(entries)
        entry = pick(entries) if pick is not None else entries[0]
        recorder = RecordingPainter(canvas.fontMetrics())
        canvas._draw_entry(recorder, entry)
        return canvas, entry, recorder


class EventBlockVisualTests(CanvasEntryTestBase):
    def fill_calls(self, recorder, rect):
        return [
            call
            for call in recorder.rounded_rects
            if call["rx"] >= 5.0
            and call["ry"] >= 5.0
            and QRectF(call["rect"]) == QRectF(rect)
            and call["brush_color"] is not None
        ]

    def bar_calls(self, recorder, rect):
        return [
            call
            for call in recorder.rects
            if call["brush_color"] is not None
            and call["rect"].width() <= 5.0
            and abs(call["rect"].left() - rect.left()) <= 0.5
            and call["rect"].height() >= rect.height() - 0.5
        ]

    def test_timespan_block_has_rounded_soft_fill_and_full_accent_bar(self):
        canvas, entry, recorder = self.draw_entries(VISUAL_THEME, [timespan_event()])
        rect = entry["rect"]

        fills = self.fill_calls(recorder, rect)
        self.assertTrue(fills, "timespan block must use drawRoundedRect with radius >= 5")
        expected = QColor(EVENT_COLORS[entry["block"]["color_idx"] % len(EVENT_COLORS)])
        fill = fills[0]["brush_color"]
        self.assertEqual(rgb(fill), rgb(expected))
        self.assertLess(fill.alpha(), 255)
        self.assertGreaterEqual(fill.alpha(), 180)

        bars = self.bar_calls(recorder, rect)
        self.assertTrue(bars, "block must draw a narrow full-saturation left accent bar")
        bar = bars[0]["brush_color"]
        self.assertGreaterEqual(bars[0]["rect"].width(), 2.0)
        self.assertEqual(rgb(bar), rgb(expected))
        self.assertEqual(bar.alpha(), 255)
        self.assertGreaterEqual(recorder.clip_path_count, 1)

    def test_dark_theme_fill_uses_dark_palette(self):
        canvas, entry, recorder = self.draw_entries(VISUAL_THEME_DARK, [timespan_event()])
        fills = self.fill_calls(recorder, entry["rect"])
        self.assertTrue(fills)
        index = entry["block"]["color_idx"]
        expected_dark = QColor(EVENT_COLORS_DARK[index % len(EVENT_COLORS_DARK)])
        expected_light = QColor(EVENT_COLORS[index % len(EVENT_COLORS)])
        self.assertEqual(rgb(fills[0]["brush_color"]), rgb(expected_dark))
        self.assertNotEqual(rgb(fills[0]["brush_color"]), rgb(expected_light))

    def test_light_theme_fill_uses_light_palette(self):
        canvas, entry, recorder = self.draw_entries(VISUAL_THEME, [timespan_event()])
        fills = self.fill_calls(recorder, entry["rect"])
        self.assertTrue(fills)
        palette_rgbs = {rgb(QColor(color)) for color in EVENT_COLORS}
        self.assertIn(rgb(fills[0]["brush_color"]), palette_rgbs)

    def test_block_text_pen_uses_theme_event_block_text(self):
        for theme in (VISUAL_THEME, VISUAL_THEME_DARK):
            with self.subTest(theme=theme["theme_name"]):
                canvas, entry, recorder = self.draw_entries(theme, [timespan_event()])
                self.assertTrue(recorder.texts, "readable block must draw title text")
                expected = rgb(QColor(theme["event_block_text"]))
                for call in recorder.texts:
                    self.assertIsNotNone(call["pen_color"])
                    self.assertEqual(rgb(call["pen_color"]), expected)

    def test_overflow_aggregate_keeps_rounded_shape_and_plus_n_label(self):
        events = [timespan_event(event_id) for event_id in range(1, 7)]
        canvas, entry, recorder = self.draw_entries(
            VISUAL_THEME, events, width=90, pick=lambda entries: next(e for e in entries if e["overflow"])
        )
        self.assertTrue(self.fill_calls(recorder, entry["rect"]))
        expected_label = f'+{len(entry["event_ids"])}'
        labels = [call for call in recorder.texts if call["args"][-1] == expected_label]
        self.assertTrue(labels, "overflow block must keep the '+N' aggregate label")
        expected_pen = rgb(QColor(VISUAL_THEME["event_block_text"]))
        self.assertEqual(rgb(labels[0]["pen_color"]), expected_pen)
        self.assertEqual(len(canvas._hit_regions), 1)
        self.assertEqual(canvas._hit_regions[0][1], tuple(range(1, 7)))


class TimelineGridVisualTests(CanvasEntryTestBase):
    def snapshot(self, theme, width=320):
        canvas = DayCanvas([], 720, 30, theme)
        self.addCleanup(canvas.close)
        recorder = RecordingPainter(canvas.fontMetrics())
        canvas._draw_timeline(recorder, width)
        return canvas, recorder

    def test_hour_lines_and_new_half_hour_minor_lines_use_theme_colors(self):
        canvas, recorder = self.snapshot(VISUAL_THEME)
        major = [call for call in recorder.lines if call["args"][1] % 30 == 0]
        minor = [call for call in recorder.lines if call["args"][1] % 30 == 15]
        self.assertEqual(len(major), 24)
        self.assertEqual(len(minor), 24)
        self.assertEqual(len(recorder.lines), 48)
        major_rgb = rgb(QColor(VISUAL_THEME["grid_line_color"]))
        minor_rgb = rgb(QColor(VISUAL_THEME["grid_line_minor_color"]))
        for call in major:
            self.assertEqual(rgb(call["pen_color"]), major_rgb)
        for call in minor:
            self.assertEqual(rgb(call["pen_color"]), minor_rgb)
            self.assertEqual(call["pen_style"], Qt.DashLine)

    def test_dark_theme_grid_uses_dark_grid_tokens(self):
        canvas, recorder = self.snapshot(VISUAL_THEME_DARK)
        major_rgb = rgb(QColor(VISUAL_THEME_DARK["grid_line_color"]))
        major = [call for call in recorder.lines if call["args"][1] % 30 == 0]
        self.assertTrue(major)
        for call in major:
            self.assertEqual(rgb(call["pen_color"]), major_rgb)

    def test_labels_only_on_hours_with_alignment_and_gutter_contract(self):
        canvas, recorder = self.snapshot(VISUAL_THEME)
        metrics = canvas.fontMetrics()
        grid_start = float(ceil(4 + metrics.horizontalAdvance("00:00") + 8))
        self.assertEqual(len(recorder.texts), 25)
        muted = rgb(QColor(VISUAL_THEME["muted_color"]))
        for hour, call in enumerate(recorder.texts):
            args = call["args"]
            self.assertIsInstance(args[0], (QRect, QRectF))
            flags = int(args[1])
            self.assertTrue(flags & int(Qt.AlignRight))
            self.assertTrue(flags & int(Qt.AlignVCenter))
            self.assertEqual(args[2], f"{hour:02d}:00")
            self.assertEqual(rgb(call["pen_color"]), muted)
        label_right = max(QRectF(call["args"][0]).right() for call in recorder.texts)
        self.assertEqual(float(min(call["args"][0] for call in recorder.lines)), grid_start)
        self.assertLessEqual(label_right + 8.0, grid_start)


class ReminderMarkerVisualTests(CanvasEntryTestBase):
    def test_marker_keeps_three_px_round_cap_line_and_adds_soft_ring(self):
        canvas, entry, recorder = self.draw_entries(VISUAL_THEME, [reminder_event()])
        self.assertTrue(entry["marker"])
        marker_rgb = rgb(QColor(REMINDER_LINE_COLOR))

        self.assertEqual(len(recorder.lines), 1)
        line = recorder.lines[0]
        self.assertEqual(line["pen_width"], 3)
        self.assertEqual(line["pen_cap"], Qt.RoundCap)
        self.assertEqual(rgb(line["pen_color"]), marker_rgb)

        self.assertEqual(len(recorder.ellipses), 2)
        ring, dot = recorder.ellipses
        self.assertGreater(ring["rect"].width(), dot["rect"].width())
        self.assertEqual(rgb(ring["brush_color"]), marker_rgb)
        self.assertLess(ring["brush_color"].alpha(), 255)
        self.assertEqual(rgb(dot["brush_color"]), marker_rgb)
        self.assertEqual(dot["brush_color"].alpha(), 255)


class MonthViewVisualTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def render_month(self, theme):
        patcher = mock.patch(
            "ui.calendar_widget.theme_manager.get_current_theme", return_value=theme
        )
        patcher.start()
        self.addCleanup(patcher.stop)
        widget = CalendarWidget()
        self.addCleanup(widget.close)
        today = date.today()
        selected_day = 1 if today.day != 1 else 2
        widget._current_date = today
        widget._selected_date = today.replace(day=selected_day)
        widget._event_dates = {today.replace(day=selected_day): 3}
        widget._clear_content()
        widget._render_view()
        QApplication.sendPostedEvents(None, QEvent.DeferredDelete)
        return widget, today, selected_day

    def cell_buttons(self, widget):
        chrome = (
            widget._btn_prev,
            widget._btn_next,
            widget._btn_month,
            widget._btn_week,
            widget._btn_day,
            widget._btn_today,
        )
        cells = {}
        for button in widget.findChildren(QPushButton):
            if button in chrome:
                continue
            cells[int(button.text().split("\n")[0])] = button
        return cells

    def test_cells_are_raised_cards_with_soft_border_and_state_colors(self):
        for theme in (theme_manager.LIGHT_THEME, theme_manager.DARK_THEME):
            with self.subTest(theme=theme["theme_name"]):
                widget, today, selected_day = self.render_month(theme)
                cells = self.cell_buttons(widget)
                plain_day = next(
                    day for day in sorted(cells) if day not in (today.day, selected_day)
                )
                plain = cells[plain_day].styleSheet()
                self.assertIn(theme["surface_raised"], plain)
                self.assertIn(theme["border_soft"], plain)
                self.assertIn("border-radius:8px", plain)

                today_qss = cells[today.day].styleSheet()
                self.assertIn(theme["calendar_today_bg"], today_qss)
                self.assertIn(f"2px solid {theme['calendar_today_border']}", today_qss)
                self.assertIn(theme["calendar_today_text"], today_qss)

                selected_qss = cells[selected_day].styleSheet()
                self.assertIn(theme["calendar_selected_bg"], selected_qss)
                self.assertIn("● 3项", cells[selected_day].text())

    def test_panel_qss_keeps_frame_bg_literal_for_background_chain(self):
        widget, _, _ = self.render_month(theme_manager.LIGHT_THEME)
        self.assertIn(theme_manager.LIGHT_THEME["frame_bg"], widget.styleSheet())

    def test_header_navigation_switch_and_today_use_theme_tokens(self):
        for theme in (theme_manager.LIGHT_THEME, theme_manager.DARK_THEME):
            with self.subTest(theme=theme["theme_name"]):
                widget, _, _ = self.render_month(theme)
                headers = [
                    label
                    for label in widget.findChildren(QLabel)
                    if label.text() in ("一", "二", "三", "四", "五", "六", "日")
                ]
                self.assertEqual(len(headers), 7)
                for header in headers:
                    self.assertIn(theme["subtitle_color"], header.styleSheet())

                self.assertEqual(widget._btn_prev.accessibleName(), "上一页")
                self.assertEqual(widget._btn_prev.toolTip(), "上一页")
                self.assertEqual(widget._btn_next.accessibleName(), "下一页")
                self.assertEqual(widget._btn_next.toolTip(), "下一页")
                self.assertIn(theme["nav_btn_color"], widget._btn_prev.styleSheet())
                self.assertIn(theme["nav_btn_color"], widget._btn_next.styleSheet())

                for button in (widget._btn_month, widget._btn_week, widget._btn_day):
                    self.assertIn(theme["switch_btn_checked_bg"], button.styleSheet())
                    self.assertIn("border-radius:15px", button.styleSheet())
                self.assertIn(theme["info"], widget._btn_today.styleSheet())
                self.assertIn("border-radius:15px", widget._btn_today.styleSheet())


class WeekHeaderChipVisualTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def test_selected_and_today_chips_use_state_tokens_and_chip_shape(self):
        today = date.today()
        week_start = today - timedelta(days=today.weekday())
        selected = week_start + timedelta(
            days=1 if today != week_start + timedelta(days=1) else 2
        )
        for theme in (theme_manager.LIGHT_THEME, theme_manager.DARK_THEME):
            with self.subTest(theme=theme["theme_name"]), mock.patch(
                "ui.calendar_widget.theme_manager.get_current_theme", return_value=theme
            ), mock.patch(
                "ui.calendar_widget.EventService.get_events_date_range", return_value=[]
            ):
                widget = CalendarWidget()
                self.addCleanup(widget.close)
                widget._current_date = week_start
                widget._selected_date = selected
                widget._switch_view("week")
                self.app.processEvents()
                QApplication.sendPostedEvents(None, QEvent.DeferredDelete)

                container = widget._content_layout.itemAt(0).layout()
                header_row = container.itemAt(0).layout()
                spacer = header_row.itemAt(0).widget()
                self.assertAlmostEqual(
                    spacer.width(),
                    int(WeekCanvas.timeline_gutter_for_metrics(widget.fontMetrics())),
                    delta=1.0,
                )
                chips = {
                    week_start + timedelta(days=offset): header_row.itemAt(offset + 1).widget()
                    for offset in range(7)
                }

                selected_qss = chips[selected].styleSheet()
                self.assertIn(theme["calendar_selected_bg"], selected_qss)
                self.assertIn(theme["calendar_selected_text"], selected_qss)
                self.assertIn("border-radius:12px", selected_qss)

                today_qss = chips[today].styleSheet()
                self.assertIn(theme["calendar_today_bg"], today_qss)
                self.assertIn(theme["calendar_today_text"], today_qss)
                self.assertIn(theme["calendar_today_border"], today_qss)
                self.assertIn("border-radius:12px", today_qss)

                plain_day = next(day for day in chips if day not in (selected, today))
                plain_qss = chips[plain_day].styleSheet()
                self.assertIn(theme["title_color"], plain_qss)
                self.assertIn("border-radius:12px", plain_qss)


if __name__ == "__main__":
    unittest.main()
