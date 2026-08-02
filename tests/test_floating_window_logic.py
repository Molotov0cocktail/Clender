import unittest
from datetime import date, datetime, time

from floating_window_logic import (
    FloatingWindowSettings,
    day_window,
    format_event_time,
)
from models import Event, EventType


class FloatingWindowLogicTests(unittest.TestCase):
    def test_valid_settings_day_window_and_geometry_round_trip(self):
        settings = FloatingWindowSettings.from_config({
            "floating_window_enabled": True,
            "floating_window_opacity": 65,
            "floating_window_start_time": "09:15",
            "floating_window_end_time": "18:45",
            "floating_window_geometry": [-1200, 40, 360, 480],
        })

        self.assertTrue(settings.enabled)
        self.assertEqual(settings.opacity_percent, 65)
        self.assertEqual(settings.start_time, time(9, 15))
        self.assertEqual(settings.end_time, time(18, 45))
        self.assertEqual(settings.geometry, (-1200, 40, 360, 480))
        self.assertEqual(
            day_window(date(2026, 8, 2), settings),
            (datetime(2026, 8, 2, 9, 15), datetime(2026, 8, 2, 18, 45)),
        )

    def test_invalid_settings_fall_back_without_accepting_bool_as_integer(self):
        defaults = FloatingWindowSettings.from_config({})
        for invalid_opacity in (True, 29, 101, "90"):
            with self.subTest(opacity=invalid_opacity):
                settings = FloatingWindowSettings.from_config({
                    "floating_window_enabled": 1,
                    "floating_window_opacity": invalid_opacity,
                    "floating_window_start_time": "22:00",
                    "floating_window_end_time": "08:00",
                    "floating_window_geometry": [1, 2, True, 400],
                })
                self.assertFalse(settings.enabled)
                self.assertEqual(settings.opacity_percent, 90)
                self.assertEqual(settings.start_time, defaults.start_time)
                self.assertEqual(settings.end_time, defaults.end_time)
                self.assertIsNone(settings.geometry)

        equal_range = FloatingWindowSettings.from_config({
            "floating_window_start_time": "10:00",
            "floating_window_end_time": "10:00",
        })
        malformed_range = FloatingWindowSettings.from_config({
            "floating_window_start_time": "8:00",
            "floating_window_end_time": "bad",
        })
        self.assertEqual(equal_range.start_time, defaults.start_time)
        self.assertEqual(equal_range.end_time, defaults.end_time)
        self.assertEqual(malformed_range.start_time, defaults.start_time)
        self.assertEqual(malformed_range.end_time, defaults.end_time)
        self.assertIsNone(FloatingWindowSettings.from_config({
            "floating_window_geometry": [2 ** 40, 0, 320, 400]
        }).geometry)

        for boundary in (30, 100):
            with self.subTest(valid_opacity=boundary):
                self.assertEqual(
                    FloatingWindowSettings.from_config({
                        "floating_window_opacity": boundary
                    }).opacity_percent,
                    boundary,
                )

    def test_format_event_time_handles_reminders_same_day_and_cross_day_spans(self):
        reminder = Event(
            id=1,
            event_type=EventType.REMINDER,
            title="提醒",
            start_time="2026-08-02 09:30",
        )
        same_day = Event(
            id=2,
            event_type=EventType.TIMESPAN,
            title="会议",
            start_time="2026-08-02 10:00",
            end_time="2026-08-02 11:30",
        )
        cross_day = Event(
            id=3,
            event_type=EventType.TIMESPAN,
            title="值班",
            start_time="2026-08-01 23:00",
            end_time="2026-08-02 09:00",
        )

        self.assertEqual(format_event_time(reminder), "09:30")
        self.assertEqual(format_event_time(same_day), "10:00–11:30")
        self.assertEqual(format_event_time(cross_day), "08-01 23:00–08-02 09:00")

        with self.assertRaises(ValueError):
            format_event_time(Event(start_time="bad"))


if __name__ == "__main__":
    unittest.main()
