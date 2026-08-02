import os
import unittest
from datetime import date, timedelta
from unittest import mock

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PyQt5.QtCore import Qt, QTime
from PyQt5.QtTest import QTest
from PyQt5.QtWidgets import QApplication, QDialogButtonBox

from constants import DEFAULT_CONFIG
from floating_window_logic import FloatingWindowSettings
from models import Event, EventType
from ui.app_settings import AppSettingsDialog
from ui.daily_floating_window import DailyFloatingWindow
from ui.event_detail_dialog import EventDetailDialog


class FloatingWindowUITests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def setUp(self):
        patchers = [
            mock.patch("config.load_config", return_value=dict(DEFAULT_CONFIG)),
            mock.patch(
                "ui.daily_floating_window.EventService.get_events_overlapping_range",
                return_value=[],
            ),
        ]
        self._event_query = patchers[1].start()
        self.addCleanup(patchers[1].stop)
        patchers[0].start()
        self.addCleanup(patchers[0].stop)

    def _settings(self, **changes):
        values = {
            "floating_window_enabled": True,
            "floating_window_opacity": 65,
            "floating_window_start_time": "08:00",
            "floating_window_end_time": "22:00",
        }
        values.update(changes)
        return FloatingWindowSettings.from_config(values)

    def _event(self, event_id, title, start, end=None):
        return Event(
            id=event_id,
            event_type=EventType.TIMESPAN if end else EventType.REMINDER,
            title=title,
            start_time=start,
            end_time=end,
            description="详情",
            estimated_duration=25,
        )

    def test_window_applies_opacity_lists_events_and_emits_clicked_id(self):
        events = [
            self._event(1, "晨会", "2026-08-02 09:00"),
            self._event(2, "开发", "2026-08-02 10:00", "2026-08-02 12:00"),
            self._event(3, "坏数据", "bad"),
        ]
        self._event_query.return_value = events
        window = DailyFloatingWindow()
        self.addCleanup(window.shutdown)
        window.apply_settings(self._settings())
        self.app.processEvents()

        self.assertAlmostEqual(window.windowOpacity(), 0.65, places=2)
        self.assertTrue(window.isVisible())
        self.assertTrue(window.windowFlags() & Qt.Tool)
        self.assertTrue(window.windowFlags() & Qt.WindowStaysOnTopHint)
        self.assertEqual(window._event_list.count(), 2)
        self.assertIn("09:00", window._event_list.item(0).text())
        self.assertEqual(window._event_list.item(0).data(Qt.UserRole), 1)
        self._event_query.assert_called_with(
            mock.ANY,
            mock.ANY,
        )

        activated = []
        window.event_activated.connect(activated.append)
        window._event_list.itemClicked.emit(window._event_list.item(1))
        self.assertEqual(activated, [2])

    def test_disabled_window_hides_without_querying_and_shows_empty_state(self):
        window = DailyFloatingWindow()
        self.addCleanup(window.shutdown)
        window.show()
        disabled = FloatingWindowSettings.from_config({
            "floating_window_enabled": False,
        })
        window.apply_settings(disabled)
        self.app.processEvents()

        self.assertFalse(window.isVisible())
        self._event_query.assert_not_called()
        window.refresh(date(2026, 8, 2))
        self.assertEqual(window._event_list.count(), 0)
        self.assertFalse(window._empty_label.isHidden())

    def test_offscreen_geometry_is_restored_to_a_visible_screen(self):
        window = DailyFloatingWindow()
        self.addCleanup(window.shutdown)
        window.apply_settings(self._settings(
            floating_window_geometry=[100000, 100000, 2000, 2000]
        ))
        self.app.processEvents()

        available = self.app.primaryScreen().availableGeometry()
        self.assertTrue(window.frameGeometry().intersects(available))
        self.assertLessEqual(window.width(), available.width())
        self.assertLessEqual(window.height(), available.height())

    def test_geometry_changes_are_debounced_and_close_only_hides(self):
        window = DailyFloatingWindow()
        self.addCleanup(window.shutdown)
        window.apply_settings(self._settings())
        self.app.processEvents()
        QTest.qWait(300)

        geometries = []
        visibility = []
        window.geometry_changed.connect(geometries.append)
        window.visibility_change_requested.connect(visibility.append)
        window._schedule_geometry_save()
        window._schedule_geometry_save()
        window._schedule_geometry_save()
        QTest.qWait(300)
        self.assertEqual(len(geometries), 1)
        self.assertEqual(len(geometries[0]), 4)

        window.close()
        self.app.processEvents()
        self.assertFalse(window.isVisible())
        self.assertEqual(visibility, [False])

    def test_date_watchdog_refreshes_after_day_change(self):
        window = DailyFloatingWindow()
        self.addCleanup(window.shutdown)
        window._settings = self._settings()
        window._displayed_date = date.today() - timedelta(days=1)
        with mock.patch.object(window, "refresh") as refresh:
            window._check_date()
        refresh.assert_called_once_with(date.today())

    def test_date_watchdog_refreshes_visible_tray_window_when_setting_disabled(self):
        window = DailyFloatingWindow()
        self.addCleanup(window.shutdown)
        window.apply_settings(FloatingWindowSettings.from_config({
            "floating_window_enabled": False,
        }))
        window.show()
        self.app.processEvents()
        window._displayed_date = date.today() - timedelta(days=1)

        with mock.patch.object(window, "refresh") as refresh:
            window._check_date()

        refresh.assert_called_once_with(date.today())

    def test_event_detail_is_read_only_and_shows_timespan_fields(self):
        event = self._event(
            5,
            "跨日值班",
            "2026-08-01 23:00",
            "2026-08-02 09:00",
        )
        dialog = EventDetailDialog(event)
        self.addCleanup(dialog.close)

        self.assertIn("时间段", dialog._lbl_type.text())
        self.assertEqual(dialog._lbl_title.text(), "跨日值班")
        self.assertIn("2026-08-01 23:00", dialog._lbl_time.text())
        self.assertIn("2026-08-02 09:00", dialog._lbl_time.text())
        self.assertTrue(dialog._description.isReadOnly())
        buttons = dialog.findChild(QDialogButtonBox)
        self.assertEqual(buttons.standardButtons(), QDialogButtonBox.Close)

    def test_app_settings_save_failure_stays_open_and_invalid_range_is_blocked(self):
        base_config = {
            "theme": "light",
            "api_key": "",
            "close_to_tray": False,
            "floating_window_enabled": False,
            "floating_window_opacity": 90,
            "floating_window_start_time": "08:00",
            "floating_window_end_time": "22:00",
            "floating_window_geometry": None,
        }
        with mock.patch(
            "ui.app_settings.cfg_mod.load_config", return_value=dict(base_config)
        ), mock.patch("ui.app_settings.cfg_mod.save_config") as save_config:
            dialog = AppSettingsDialog()
            self.addCleanup(dialog.close)
            dialog.show()
            saved = []
            dialog.config_saved.connect(saved.append)
            dialog._start_time.setTime(QTime(18, 0))
            dialog._end_time.setTime(QTime(9, 0))
            with mock.patch("ui.app_settings.QMessageBox.warning") as warning:
                dialog._save()
            warning.assert_called_once()
            save_config.assert_not_called()
            self.assertTrue(dialog.isVisible())

            dialog._start_time.setTime(QTime(9, 0))
            dialog._end_time.setTime(QTime(18, 0))
            save_config.side_effect = OSError("disk full")
            with mock.patch("ui.app_settings.QMessageBox.critical") as critical:
                dialog._save()
            critical.assert_called_once()
            self.assertEqual(saved, [])
            self.assertTrue(dialog.isVisible())

    def test_app_settings_success_emits_complete_config_and_preserves_geometry(self):
        base_config = {
            "theme": "dark",
            "api_key": "",
            "close_to_tray": False,
            "floating_window_enabled": False,
            "floating_window_opacity": 90,
            "floating_window_start_time": "08:00",
            "floating_window_end_time": "22:00",
            "floating_window_geometry": [10, 20, 320, 400],
        }
        with mock.patch(
            "ui.app_settings.cfg_mod.load_config", return_value=dict(base_config)
        ), mock.patch("ui.app_settings.cfg_mod.save_config") as save_config:
            dialog = AppSettingsDialog()
            self.addCleanup(dialog.close)
            saved = []
            dialog.config_saved.connect(saved.append)
            dialog._chk_close_to_tray.setChecked(True)
            dialog._chk_floating_enabled.setChecked(True)
            dialog._opacity_slider.setValue(40)
            dialog._start_time.setTime(QTime(7, 30))
            dialog._end_time.setTime(QTime(19, 15))
            dialog._save()

        self.assertEqual(len(saved), 1)
        self.assertEqual(saved[0]["floating_window_opacity"], 40)
        self.assertEqual(saved[0]["floating_window_start_time"], "07:30")
        self.assertEqual(saved[0]["floating_window_end_time"], "19:15")
        self.assertEqual(saved[0]["floating_window_geometry"], [10, 20, 320, 400])
        save_config.assert_called_once_with(saved[0])


if __name__ == "__main__":
    unittest.main()
