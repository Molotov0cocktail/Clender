import os
import unittest
from datetime import date
from unittest import mock

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PyQt5.QtCore import QPoint, QPointF, QRect, QRectF, Qt
from PyQt5.QtGui import QFont
from PyQt5.QtTest import QTest
from PyQt5.QtWidgets import QApplication

from calendar_logic import build_day_blocks, build_week_blocks
from models import Event, EventType
from ui.calendar_widget import CalendarWidget
from ui.canvas import DayCanvas, WeekCanvas


THEME = {
    "muted_color": "#808080",
    "frame_bg": "#ffffff",
    "title_color": "#202020",
    "subtitle_color": "#606060",
    "nav_btn_color": "#404040",
    "primary": "#6c5ce7",
    "primary_hover": "#a29bfe",
    "primary_text": "#ffffff",
    "input_border": "#aaaaaa",
    "switch_btn_text": "#505050",
    "switch_btn_checked_bg": "#6c5ce7",
    "info": "#0984e3",
    "info_hover": "#74b9ff",
    "header_bg": "#eeeeee",
    "calendar_selected_bg": "#a29bfe",
    "calendar_selected_border": "#6c5ce7",
    "calendar_selected_text": "#ffffff",
    "calendar_today_bg": "#eeeeee",
    "calendar_today_border": "#0984e3",
    "calendar_today_text": "#0984e3",
    "calendar_cell_bg": "#ffffff",
    "calendar_cell_border": "#dddddd",
    "calendar_cell_text": "#202020",
    "theme_name": "light",
    "surface_raised": "#ffffff",
    "border_soft": "#e8ebf5",
    "grid_line_color": "#d0d7e5",
    "grid_line_minor_color": "#e8ecf4",
    "event_block_text": "#ffffff",
}


class _TimelineRecordingPainter:
    """Record the timeline geometry without relying on pixel screenshots."""

    def __init__(self, metrics):
        self._metrics = metrics
        self.lines = []
        self.text_calls = []

    def setPen(self, pen):
        del pen

    def fontMetrics(self):
        return self._metrics

    def drawLine(self, *args):
        self.lines.append(args)

    def drawText(self, *args):
        self.text_calls.append(args)


class CanvasTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def event(self, event_id, start="2026-08-02 10:00", duration=60):
        return Event(
            id=event_id,
            event_type=EventType.REMINDER,
            title=f"事项 {event_id}",
            start_time=start,
            estimated_duration=duration,
        )

    def render(self, canvas, width):
        canvas.resize(width, 720)
        canvas.show()
        self.addCleanup(canvas.close)
        self.app.processEvents()
        canvas.grab()
        self.app.processEvents()

    def click_region(self, canvas, region):
        rect, _ = region
        center = rect.center()
        QTest.mouseClick(canvas, Qt.LeftButton, pos=QPoint(int(center.x()), int(center.y())))
        QTest.qWait(QApplication.doubleClickInterval() + 30)

    def set_pixel_font(self, widget, pixel_size):
        font = QFont(widget.font())
        font.setPixelSize(pixel_size)
        widget.setFont(font)

    def timeline_snapshot(self, canvas, width):
        canvas.resize(width, 720)
        recorder = _TimelineRecordingPainter(canvas.fontMetrics())
        canvas._draw_timeline(recorder, width)

        self.assertTrue(recorder.lines, "timeline must draw horizontal grid lines")
        grid_starts = []
        for args in recorder.lines:
            if len(args) == 4:
                grid_starts.append(float(args[0]))
            elif len(args) == 1 and hasattr(args[0], "x1"):
                grid_starts.append(float(args[0].x1()))
            else:
                self.fail(f"unsupported timeline line geometry: {args!r}")
        grid_start = min(grid_starts)

        self.assertTrue(recorder.text_calls, "timeline must expose time-label rectangles")
        label_rects = []
        for args in recorder.text_calls:
            self.assertGreaterEqual(len(args), 3)
            self.assertIsInstance(
                args[0],
                (QRect, QRectF),
                "time labels must use a measured QRect/QRectF, not fixed point coordinates",
            )
            alignment = int(args[1])
            self.assertTrue(alignment & int(Qt.AlignRight))
            self.assertTrue(alignment & int(Qt.AlignVCenter))
            label_rects.append(QRectF(args[0]))

        label_right = max(rect.right() for rect in label_rects)
        self.assertLessEqual(
            label_right + 8.0,
            grid_start,
            "time-label right edge must stay at least 8px left of the grid",
        )
        return {
            "grid_start": grid_start,
            "label_right": label_right,
            "label_rects": label_rects,
        }

    def test_day_and_week_timeline_gutter_tracks_8_and_20px_fonts(self):
        canvas_types = (
            (DayCanvas, lambda: build_day_blocks([self.event(1)], 30)),
            (
                WeekCanvas,
                lambda: build_week_blocks(
                    [self.event(1, start="2026-07-27 10:00")],
                    date(2026, 7, 27),
                    30,
                ),
            ),
        )

        for canvas_type, blocks_factory in canvas_types:
            for width in (90, 520):
                grid_starts = {}
                for pixel_size in (8, 20):
                    with self.subTest(
                        canvas=canvas_type.__name__,
                        width=width,
                        pixel_size=pixel_size,
                    ):
                        canvas = canvas_type(blocks_factory(), 720, 30, THEME)
                        self.addCleanup(canvas.close)
                        self.set_pixel_font(canvas, pixel_size)
                        snapshot = self.timeline_snapshot(canvas, width)
                        grid_starts[pixel_size] = snapshot["grid_start"]

                        entries = canvas._build_layout(width)
                        self.assertTrue(entries)
                        for entry in entries:
                            event_left = entry["rect"].left()
                            self.assertGreaterEqual(event_left, snapshot["grid_start"])
                            self.assertLessEqual(
                                snapshot["label_right"] + 8.0,
                                event_left,
                                "event/marker geometry must share the 8px gutter contract",
                            )

                if set(grid_starts) == {8, 20}:
                    self.assertGreater(
                        grid_starts[20],
                        grid_starts[8],
                        f"{canvas_type.__name__} gutter must grow with measured font width",
                    )

    def test_week_header_spacer_matches_dynamic_canvas_gutter(self):
        target = date(2026, 7, 27)
        spacer_widths = {}

        for pixel_size in (8, 20):
            with self.subTest(pixel_size=pixel_size), mock.patch(
                "ui.calendar_widget.EventService.get_events_date_range",
                return_value=[self.event(1, start="2026-07-27 10:00")],
            ), mock.patch(
                "ui.calendar_widget.theme_manager.get_current_theme",
                return_value=THEME,
            ):
                widget = CalendarWidget()
                self.addCleanup(widget.close)
                self.set_pixel_font(widget, pixel_size)
                widget._current_date = target
                widget._switch_view("week")
                self.app.processEvents()

                container = widget._content_layout.itemAt(0).layout()
                header_row = container.itemAt(0).layout()
                spacer = header_row.itemAt(0).widget()
                canvas = widget._active_canvas
                snapshot = self.timeline_snapshot(canvas, 320)

                spacer_widths[pixel_size] = spacer.width()
                self.assertAlmostEqual(
                    spacer.width(),
                    snapshot["grid_start"],
                    delta=1.0,
                    msg="week header spacer and Canvas must use one gutter calculation",
                )

        if set(spacer_widths) == {8, 20}:
            self.assertGreater(
                spacer_widths[20],
                spacer_widths[8],
                "week header spacer must grow with the 20px time-label font",
            )

    def test_dynamic_gutter_preserves_lane_marker_and_overflow_hit_regions(self):
        for pixel_size in (8, 20):
            with self.subTest(pixel_size=pixel_size, behavior="marker"):
                marker = DayCanvas(
                    build_day_blocks(
                        [self.event(7, start="2026-08-02 23:59", duration=0)],
                        30,
                    ),
                    720,
                    30,
                    THEME,
                )
                self.set_pixel_font(marker, pixel_size)
                self.render(marker, 90)
                self.assertEqual(marker._hit_regions[0][1], (7,))
                self.assertGreaterEqual(
                    marker._hit_regions[0][0].height(), marker.MIN_HIT_HEIGHT
                )

            with self.subTest(pixel_size=pixel_size, behavior="lanes"):
                lane_events = [
                    self.event(1, start="2026-07-27 10:00", duration=120),
                    self.event(2, start="2026-07-27 11:00", duration=120),
                ]
                lanes = WeekCanvas(
                    build_week_blocks(lane_events, date(2026, 7, 27), 30),
                    720,
                    30,
                    THEME,
                )
                self.set_pixel_font(lanes, pixel_size)
                self.render(lanes, 520)
                lane_regions = {ids: rect for rect, ids in lanes._hit_regions}
                self.assertLess(
                    lane_regions[(1,)].right(), lane_regions[(2,)].left()
                )

            with self.subTest(pixel_size=pixel_size, behavior="overflow"):
                overflow = WeekCanvas(
                    build_week_blocks(
                        [
                            self.event(index, start="2026-07-27 10:00")
                            for index in range(1, 5)
                        ],
                        date(2026, 7, 27),
                        30,
                    ),
                    720,
                    30,
                    THEME,
                )
                self.set_pixel_font(overflow, pixel_size)
                self.render(overflow, 170)
                self.assertEqual(len(overflow._hit_regions), 1)
                self.assertEqual(overflow._hit_regions[0][1], (1, 2, 3, 4))

    def test_short_reminder_has_minimum_hit_region_and_emits_tuple(self):
        blocks = build_day_blocks(
            [self.event(7, start="2026-08-02 23:59", duration=0)],
            30,
        )
        canvas = DayCanvas(blocks, 720, 30, THEME)
        activated = []
        canvas.event_activated.connect(activated.append)

        self.render(canvas, 240)

        self.assertEqual(len(canvas._hit_regions), 1)
        rect, event_ids = canvas._hit_regions[0]
        self.assertGreaterEqual(rect.height(), canvas.MIN_HIT_HEIGHT)
        self.assertEqual(event_ids, (7,))
        self.click_region(canvas, canvas._hit_regions[0])
        self.assertEqual(activated, [(7,)])
        QTest.mouseClick(
            canvas,
            Qt.RightButton,
            pos=QPoint(int(rect.center().x()), int(rect.center().y())),
        )
        self.assertEqual(activated, [(7,)])

    def test_adjacent_short_blocks_choose_the_visually_clicked_event(self):
        events = [
            Event(
                id=1,
                event_type=EventType.TIMESPAN,
                title="短事项 1",
                start_time="2026-08-02 10:00",
                end_time="2026-08-02 10:10",
            ),
            Event(
                id=2,
                event_type=EventType.TIMESPAN,
                title="短事项 2",
                start_time="2026-08-02 10:10",
                end_time="2026-08-02 10:20",
            ),
        ]
        canvas = DayCanvas(build_day_blocks(events, 30), 720, 30, THEME)
        activated = []
        canvas.event_activated.connect(activated.append)

        self.render(canvas, 320)

        first_hit, second_hit = (region[0] for region in canvas._hit_regions)
        self.assertTrue(first_hit.intersects(second_hit))
        x = int(first_hit.center().x())
        QTest.mouseClick(canvas, Qt.LeftButton, pos=QPoint(x, 300))
        QTest.qWait(QApplication.doubleClickInterval() + 30)
        QTest.mouseClick(canvas, Qt.LeftButton, pos=QPoint(x, 305))
        QTest.qWait(QApplication.doubleClickInterval() + 30)

        self.assertEqual(activated, [(1,), (2,)])
        tied_entry = canvas._entry_at(QPointF(x, 302.5))
        self.assertEqual(tied_entry["event_ids"], (1,))

    def test_narrow_day_canvas_aggregates_overflow_ids_and_keeps_all_clickable(self):
        blocks = build_day_blocks([self.event(index) for index in range(1, 7)], 30)
        canvas = DayCanvas(blocks, 720, 30, THEME)
        activated = []
        canvas.event_activated.connect(activated.append)

        self.render(canvas, 90)

        region_ids = [event_ids for _, event_ids in canvas._hit_regions]
        self.assertTrue(any(len(event_ids) > 1 for event_ids in region_ids))
        self.assertEqual(set().union(*(set(ids) for ids in region_ids)), set(range(1, 7)))
        overflow_region = next(region for region in canvas._hit_regions if len(region[1]) > 1)
        self.click_region(canvas, overflow_region)
        self.assertEqual(activated[-1], overflow_region[1])

    def test_week_canvas_uses_distinct_horizontal_lane_regions(self):
        events = [
            self.event(1, start="2026-07-27 10:00", duration=120),
            self.event(2, start="2026-07-27 11:00", duration=120),
        ]
        blocks = build_week_blocks(events, date(2026, 7, 27), 30)
        canvas = WeekCanvas(blocks, 720, 30, THEME)

        self.render(canvas, 520)

        regions = {ids: rect for rect, ids in canvas._hit_regions}
        self.assertLess(regions[(1,)].right(), regions[(2,)].left())

    def test_narrow_week_column_aggregates_overflow_ids(self):
        events = [
            self.event(index, start="2026-07-27 10:00")
            for index in range(1, 5)
        ]
        blocks = build_week_blocks(events, date(2026, 7, 27), 30)
        canvas = WeekCanvas(blocks, 720, 30, THEME)

        self.render(canvas, 170)

        self.assertEqual(len(canvas._hit_regions), 1)
        self.assertEqual(canvas._hit_regions[0][1], (1, 2, 3, 4))

    def test_overlap_texture_band_does_not_intersect_text_rect(self):
        events = [
            Event(
                id=1,
                event_type=EventType.TIMESPAN,
                title="很长的第一个事项标题用于验证省略与纹理隔离",
                start_time="2026-08-02 10:00",
                end_time="2026-08-02 12:00",
            ),
            Event(
                id=2,
                event_type=EventType.TIMESPAN,
                title="第二个事项",
                start_time="2026-08-02 11:00",
                end_time="2026-08-02 13:00",
            ),
        ]
        canvas = DayCanvas(build_day_blocks(events, 30), 720, 30, THEME)

        self.render(canvas, 320)

        textured = [region for region in canvas._paint_regions if region["texture_rects"]]
        self.assertTrue(textured)
        for region in textured:
            self.assertIsNotNone(region["text_rect"])
            self.assertTrue(all(
                not region["text_rect"].intersects(texture_rect)
                for texture_rect in region["texture_rects"]
            ))

    def test_calendar_widget_forwards_canvas_activation(self):
        target = date(2026, 8, 2)
        event = self.event(42)
        with mock.patch(
            "ui.calendar_widget.EventService.get_events_by_date", return_value=[event]
        ), mock.patch(
            "ui.calendar_widget.theme_manager.get_current_theme", return_value=THEME
        ):
            widget = CalendarWidget()
            self.addCleanup(widget.close)
            widget._current_date = target
            activated = []
            widget.event_activated.connect(activated.append)
            widget._switch_view("day")

            widget._active_canvas.event_activated.emit((42,))

        self.assertEqual(activated, [(42,)])


if __name__ == "__main__":
    unittest.main()
