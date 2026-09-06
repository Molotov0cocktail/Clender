import os
import tempfile
import unittest
from datetime import date, datetime
from pathlib import Path
from unittest import mock
os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
from PyQt5.QtCore import Qt, QPoint
from PyQt5.QtTest import QTest
from PyQt5.QtWidgets import QApplication
import database
import theme_manager
from calendar_logic import build_day_blocks, build_week_blocks
from event_service import EventService
from models import Event, EventType
from ui.canvas import DayCanvas
from ui.calendar_widget import CalendarWidget

class CalendarExperienceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def event(self, start='2026-09-06 23:30', end='2026-09-07 00:30', **extra):
        return Event(id=42, event_type=EventType.TIMESPAN, title='测试', start_time=start, end_time=end, **extra)

    def test_short_timespans_are_blocks_and_reminders_are_lines(self):
        for minutes in (1, 30, 59):
            blocks = build_day_blocks([self.event('2026-09-06 10:00', f'2026-09-06 10:{minutes:02}')], 30)
            canvas = DayCanvas(blocks, 720, 30, theme_manager.LIGHT_THEME)
            self.assertFalse(canvas._build_layout(300)[0]['marker'])
            canvas.close()
        reminder = Event(id=1, event_type=EventType.REMINDER, title='提醒', start_time='2026-09-06 10:00', estimated_duration=120)
        canvas = DayCanvas(build_day_blocks([reminder], 30), 720, 30, theme_manager.LIGHT_THEME)
        self.assertTrue(canvas._build_layout(300)[0]['marker'])
        canvas.close()

    def test_cross_week_and_midnight_slice_preserves_one_id(self):
        blocks = build_week_blocks([self.event()], date(2026, 9, 7), 30)
        self.assertEqual([(b['id'], b['col'], b['top'], b['duration_minutes']) for b in blocks], [(42, 0, 0, 30)])
        blocks = build_day_blocks([self.event()], 30, target_date=date(2026, 9, 7))
        self.assertEqual(blocks[0]['tlabel'], '00:00-00:30')
        self.assertEqual(build_day_blocks([self.event(end='2026-09-07 00:00')], 30, target_date=date(2026, 9, 7)), [])

    def test_bad_dates_and_reversed_ranges_skip(self):
        self.assertEqual(build_week_blocks([self.event('bad', 'bad'), self.event('2026-09-06 10:00', '2026-09-06 09:00')], date(2026,9,7),30), [])

    def test_cross_day_queries_and_month_counts(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(database, 'DB_PATH', str(Path(tmp)/'test.db')):
            database.init_db()
            eid = EventService.add_event('跨月', 'timespan', '2026-08-31 23:30', '2026-09-02 00:00')
            self.assertEqual([e.id for e in EventService.get_events_by_date(date(2026,9,1))], [eid])
            self.assertEqual([e.id for e in EventService.get_events_date_range(date(2026,9,1),date(2026,9,2))], [eid])
            self.assertEqual(EventService.get_event_counts(), {date(2026,8,31):1,date(2026,9,1):1})
            self.assertEqual(EventService.get_events_by_date(date(2026,9,2)), [])

    def test_double_click_cancels_delayed_preview_and_emits_edit(self):
        canvas = DayCanvas(build_day_blocks([self.event('2026-09-06 10:00','2026-09-06 11:00')],30),720,30,theme_manager.LIGHT_THEME)
        canvas.resize(300,720)
        preview, edits = [], []
        canvas.event_activated.connect(preview.append)
        canvas.event_edit_requested.connect(edits.append)
        pos = canvas._build_layout()[0]['rect'].center().toPoint()
        QTest.mouseClick(canvas,Qt.LeftButton,pos=pos)
        self.assertEqual(preview, [])
        QTest.mouseDClick(canvas,Qt.LeftButton,pos=pos)
        QTest.qWait(QApplication.doubleClickInterval()+30)
        self.assertEqual(preview, [])
        self.assertEqual(edits, [(42,)])
        QTest.mouseClick(canvas,Qt.LeftButton,pos=pos)
        QTest.qWait(QApplication.doubleClickInterval()+30)
        self.assertEqual(preview, [(42,)])
        canvas.close()

    def test_long_event_is_clipped_to_seven_days_and_same_id(self):
        blocks = build_week_blocks([self.event('2026-01-01 00:00', '2027-01-01 00:00')],date(2026,9,7),30)
        self.assertEqual(len(blocks), 7)
        self.assertEqual({b['id'] for b in blocks}, {42})
        self.assertTrue(all(b['top'] == 0 and b['height'] == 720 for b in blocks))
        self.assertTrue(all(b['tlabel'] == '00:00-24:00' for b in blocks))

    def test_invalid_ids_are_not_clickable(self):
        for event_id in (True, 0, -1, 'bad', []):
            event = self.event().to_dict(); event['id'] = event_id
            self.assertEqual(build_day_blocks([event],30), [])

    def test_light_dark_small_blocks_render_without_text(self):
        from PyQt5.QtGui import QImage, QFont
        blocks = build_day_blocks([self.event('2026-09-06 10:00','2026-09-06 10:01')],30)
        for theme in (theme_manager.LIGHT_THEME, theme_manager.DARK_THEME):
            for size in (8,20):
                canvas=DayCanvas(blocks,720,30,theme); canvas.resize(240,720)
                font=QFont(); font.setPixelSize(size); canvas.setFont(font)
                image=QImage(240,720,QImage.Format_ARGB32); image.fill(Qt.transparent)
                with mock.patch.object(canvas, '_draw_marker') as marker:
                    canvas.render(image)
                marker.assert_not_called()
                self.assertTrue(canvas._paint_regions)
                canvas.close()

    def test_max_date_query_and_canvas_do_not_overflow(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(database, 'DB_PATH', str(Path(tmp)/'test.db')):
            database.init_db()
            eid=EventService.add_event('最后一分钟','reminder','9999-12-31 23:59')
            events=EventService.get_events_by_date(date.max)
            self.assertEqual([event.id for event in events],[eid])
            blocks=build_day_blocks(events,30,target_date=date.max)
            self.assertEqual(blocks[0]['id'],eid)
            self.assertEqual(len(build_week_blocks(events,date(9999,12,27),30)),1)

    def test_date_range_rejects_datetime_and_counts_clip_to_visible_month(self):
        with self.assertRaises(TypeError):
            EventService.get_events_date_range(datetime(2026,9,1), date(2026,9,2))
        event=self.event('1900-01-01 00:00','9999-12-31 23:59')
        with mock.patch.object(database,'get_all_events',return_value=[event]):
            counts=EventService.get_event_counts(date(2026,9,1),date(2026,9,30))
        self.assertEqual(len(counts),30)
        self.assertEqual(set(counts.values()),{1})

    def test_calendar_navigation_clamps_supported_date_boundaries(self):
        with mock.patch('ui.calendar_widget.EventService.get_events_by_date', return_value=[]), mock.patch('ui.calendar_widget.EventService.get_events_date_range',return_value=[]):
            widget=CalendarWidget()
            for bound, action in ((date.max,widget._go_next),(date.min,widget._go_prev)):
                for mode in ('day','week','month'):
                    widget.set_selected_date(bound); widget._switch_view(mode); action()
                    self.assertLessEqual(widget.get_selected_date(),date.max)
                    self.assertGreaterEqual(widget.get_selected_date(),date.min)
            widget.close()

    def test_navigation_updates_focused_day(self):
        with mock.patch('ui.calendar_widget.EventService.get_events_by_date', return_value=[]), mock.patch('ui.calendar_widget.EventService.get_events_date_range',return_value=[]):
            widget=CalendarWidget(); widget.set_selected_date(date(2026,9,6)); widget._switch_view('day')
            selected=[]; widget.date_selected.connect(selected.append)
            widget._go_next()
            self.assertEqual(widget.get_selected_date(), date(2026,9,7))
            self.assertEqual(selected, [date(2026,9,7)])
            widget.close()
