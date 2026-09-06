import os
import unittest
from datetime import date, datetime
from unittest import mock
os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
from PyQt5.QtCore import Qt
from PyQt5.QtTest import QTest
from PyQt5.QtWidgets import QApplication, QMenu, QLabel
import theme_manager
from typography import floating_scale_from_config
from models import Event, EventType
from ui.daily_floating_window import DailyFloatingWindow


class FloatingViewsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def setUp(self):
        self.config = mock.patch('config.load_config', return_value={})
        self.config.start()
        self.addCleanup(self.config.stop)
        patch = mock.patch('ui.daily_floating_window.EventService.get_events_overlapping_range', return_value=[
            Event(id=7, event_type=EventType.TIMESPAN, title='跨夜', start_time='2026-09-06 23:00', end_time='2026-09-07 01:00')])
        self.query = patch.start()
        self.addCleanup(patch.stop)
        self.window = DailyFloatingWindow()
        self.addCleanup(self.window.shutdown)
        self.window.refresh(date(2026, 9, 7))
        self.window.show()
        self.app.processEvents()

    def test_three_views_and_week_query(self):
        self.window.set_view_mode('day')
        self.assertEqual(self.window._canvas.blocks[0]['id'], 7)
        self.window.set_view_mode('week')
        self.assertEqual(self.query.call_args.args, (datetime(2026, 9, 7), datetime(2026, 9, 14)))
        self.assertEqual(self.window._canvas.blocks[0]['id'], 7)
        self.window.set_view_mode('events')
        self.assertTrue(self.window._event_list.isVisible())
        with self.assertRaises(ValueError):
            self.window.set_view_mode('invalid')

    def test_click_previews_double_click_only_edits(self):
        previews, edits = [], []
        self.window.event_activated.connect(previews.append)
        self.window.event_edit_requested.connect(edits.append)
        viewport = self.window._event_list.viewport()
        point = self.window._event_list.visualItemRect(self.window._event_list.item(0)).center()
        QTest.mouseClick(viewport, Qt.LeftButton, pos=point)
        QTest.qWait(QApplication.doubleClickInterval() + 40)
        self.assertEqual(previews, [7])
        previews.clear()
        QTest.mouseClick(viewport, Qt.LeftButton, pos=point)
        QTest.mouseDClick(viewport, Qt.LeftButton, pos=point)
        QTest.qWait(QApplication.doubleClickInterval() + 40)
        self.assertEqual(previews, [])
        self.assertEqual(edits, [7])

    def test_empty_invalid_and_aggregate_selection(self):
        self.query.return_value = []
        self.window.set_view_mode('week')
        self.assertEqual(self.window._canvas.blocks, [])
        self.assertTrue(self.window._empty_label.isVisible())
        previews = []
        self.window.event_activated.connect(previews.append)
        self.window._activate_canvas_ids((True, -1, '7'), False)
        self.assertEqual(previews, [])
        self.window._activate_canvas_ids((7, 8), False)
        menu = self.window.findChild(QMenu)
        self.assertEqual(previews, [])
        self.assertEqual(len(menu.actions()), 2)
        menu.actions()[1].trigger()
        self.assertEqual(previews, [8])
        menu.close()

    def test_light_dark_and_independent_fonts_render_all_views(self):
        for name in ('light', 'dark'):
            for size in (8, 20):
                with mock.patch('config.get_theme', return_value=name):
                    theme_manager.apply_theme(self.app, {'theme': name, 'app_font_size_px': 20 if size == 8 else 8})
                    self.window.apply_font_scale(floating_scale_from_config({'floating_font_size_px': size}))
                    for mode in ('events', 'day', 'week'):
                        self.window.set_view_mode(mode)
                        self.window.apply_theme()
                        self.app.processEvents()
                        self.assertFalse(self.window.grab().isNull())
                        if mode != 'events':
                            self.assertEqual(self.window._canvas.font().pixelSize(), size)
                            self.assertEqual(self.window._canvas.theme['frame_bg'], theme_manager.get_current_theme()['frame_bg'])

    def test_hiding_cancels_pending_preview(self):
        previews = []
        self.window.event_activated.connect(previews.append)
        self.window._on_item_clicked(self.window._event_list.item(0))
        self.window.hide()
        QTest.qWait(QApplication.doubleClickInterval() + 30)
        self.assertEqual(previews, [])
        self.assertIsNone(self.window._pending_preview_id)

    def test_week_header_stays_visible_updates_fonts_and_tracks_horizontal_scroll(self):
        self.window.refresh(date(2026, 9, 6))
        self.window.set_view_mode('week')
        self.window.apply_font_scale(floating_scale_from_config({'floating_font_size_px': 20}))
        self.app.processEvents()
        header = self.window._week_header
        labels = header.findChildren(QLabel)
        self.assertEqual(len(labels), 7)
        self.assertTrue(all(label.font().pixelSize() == self.window._font_scale.caption_px for label in labels))
        before = header.pos()
        self.window._timeline_scroll.verticalScrollBar().setValue(800)
        self.app.processEvents()
        self.assertEqual(header.pos(), before)
        self.assertTrue(header.isVisible())
        scrollbar = self.window._timeline_scroll.horizontalScrollBar()
        self.assertEqual(scrollbar.value(), scrollbar.maximum())
        self.assertEqual(header.horizontalScrollBar().value(), scrollbar.value())
