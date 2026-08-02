import unittest
from datetime import date, datetime, time

import floating_window_logic as floating_logic

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
        for invalid_opacity in (True, -1, 101, "90"):
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

        for boundary in (0, 100):
            with self.subTest(valid_opacity=boundary):
                self.assertEqual(
                    FloatingWindowSettings.from_config({
                        "floating_window_opacity": boundary
                    }).opacity_percent,
                    boundary,
                )

    def test_opacity_rejects_bool_non_integer_and_values_outside_zero_to_one_hundred(self):
        default_opacity = FloatingWindowSettings.from_config({}).opacity_percent

        for invalid_opacity in (True, False, -1, 101, "0", 3.5, None):
            with self.subTest(opacity=invalid_opacity):
                settings = FloatingWindowSettings.from_config({
                    "floating_window_opacity": invalid_opacity,
                })
                self.assertEqual(settings.opacity_percent, default_opacity)

    def _event_state_contract(self):
        return (
            getattr(floating_logic, "FloatingEventState"),
            getattr(floating_logic, "classify_event_states"),
        )

    @staticmethod
    def _state_event(
        event_id,
        event_type,
        start,
        end=None,
        estimated_duration=0,
    ):
        return Event(
            id=event_id,
            event_type=event_type,
            title=f"事项 {event_id}",
            start_time=start,
            end_time=end,
            estimated_duration=estimated_duration,
        )

    def test_timespan_current_uses_half_open_endpoints_and_supports_cross_day(self):
        state, classify = self._event_state_contract()
        now = datetime(2026, 8, 2, 10, 0)
        events = [
            self._state_event(
                1,
                EventType.TIMESPAN,
                "2026-08-02 10:00",
                "2026-08-02 11:00",
            ),
            self._state_event(
                2,
                EventType.TIMESPAN,
                "2026-08-02 09:00",
                "2026-08-02 10:00",
            ),
            self._state_event(
                3,
                EventType.TIMESPAN,
                "2026-08-01 23:00",
                "2026-08-02 10:30",
            ),
            self._state_event(
                4,
                EventType.TIMESPAN,
                "2026-08-02 10:30",
                "2026-08-02 11:30",
            ),
        ]

        states = classify(events, now)

        self.assertEqual(states[1], state.CURRENT)
        self.assertEqual(states[2], state.NORMAL)
        self.assertEqual(states[3], state.CURRENT)
        self.assertEqual(states[4], state.NEXT)

    def test_reminder_current_uses_duration_or_exact_start_minute(self):
        state, classify = self._event_state_contract()
        events = [
            self._state_event(
                1,
                EventType.REMINDER,
                "2026-08-02 09:45",
                estimated_duration=30,
            ),
            self._state_event(
                2,
                EventType.REMINDER,
                "2026-08-02 10:00",
                estimated_duration=0,
            ),
            self._state_event(
                3,
                EventType.REMINDER,
                "2026-08-02 09:30",
                estimated_duration=30,
            ),
        ]

        during_start_minute = classify(events, datetime(2026, 8, 2, 10, 0, 59))
        self.assertEqual(during_start_minute[1], state.CURRENT)
        self.assertEqual(during_start_minute[2], state.CURRENT)
        self.assertEqual(during_start_minute[3], state.NORMAL)

        at_duration_endpoint = classify(events, datetime(2026, 8, 2, 10, 0))
        self.assertEqual(at_duration_endpoint[2], state.CURRENT)
        self.assertEqual(at_duration_endpoint[3], state.NORMAL)

        after_start_minute = classify(events, datetime(2026, 8, 2, 10, 1))
        self.assertEqual(after_start_minute[1], state.CURRENT)
        self.assertEqual(after_start_minute[2], state.NORMAL)

    def test_all_current_and_earliest_future_ties_are_classified_together(self):
        state, classify = self._event_state_contract()
        now = datetime(2026, 8, 2, 10, 0)
        events = [
            self._state_event(
                1,
                EventType.TIMESPAN,
                "2026-08-02 09:00",
                "2026-08-02 11:00",
            ),
            self._state_event(
                2,
                EventType.TIMESPAN,
                "2026-08-02 09:30",
                "2026-08-02 10:30",
            ),
            self._state_event(3, EventType.REMINDER, "2026-08-02 11:00"),
            self._state_event(
                4,
                EventType.TIMESPAN,
                "2026-08-02 11:00",
                "2026-08-02 12:00",
            ),
            self._state_event(5, EventType.REMINDER, "2026-08-02 12:00"),
        ]

        states = classify(events, now)

        self.assertEqual({event_id for event_id, value in states.items() if value == state.CURRENT}, {1, 2})
        self.assertEqual({event_id for event_id, value in states.items() if value == state.NEXT}, {3, 4})
        self.assertEqual(states[5], state.NORMAL)

    def test_no_future_event_yields_no_next_and_bad_legacy_rows_are_safe(self):
        state, classify = self._event_state_contract()
        now = datetime(2026, 8, 2, 10, 0)
        events = [
            self._state_event(1, EventType.REMINDER, "2026-08-02 09:00"),
            self._state_event(2, EventType.REMINDER, "bad"),
            self._state_event(
                0,
                EventType.TIMESPAN,
                "2026-08-02 11:00",
                "2026-08-02 12:00",
            ),
            self._state_event(
                3,
                EventType.TIMESPAN,
                "2026-08-02 09:30",
                "2026-08-02 09:00",
            ),
            self._state_event(4, "unknown", "2026-08-02 11:00"),
            self._state_event(
                5,
                EventType.REMINDER,
                "2026-08-02 11:30",
                estimated_duration=-1,
            ),
        ]

        states = classify(events, now)

        self.assertEqual(states[1], state.NORMAL)
        self.assertFalse(any(value == state.NEXT for value in states.values()))
        for invalid_id in (0, 2, 3, 4, 5):
            if invalid_id in states:
                self.assertEqual(states[invalid_id], state.NORMAL)

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
