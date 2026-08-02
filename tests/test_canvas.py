import os
import unittest
from datetime import date
from unittest import mock

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PyQt5.QtCore import QPoint, QPointF, Qt
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
}


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

    def test_adjacent_short_markers_choose_the_visually_clicked_event(self):
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
        QTest.mouseClick(canvas, Qt.LeftButton, pos=QPoint(x, 305))

        self.assertEqual(activated, [(1,), (2,)])
        tied_entry = canvas._entry_at(QPointF(x, 302.5))
        self.assertEqual(tied_entry["event_ids"], (2,))

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
