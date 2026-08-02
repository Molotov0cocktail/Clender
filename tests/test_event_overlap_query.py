import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from unittest import mock

import database
from event_service import EventService


class EventOverlapQueryTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        db_path = str(Path(self.temp_dir.name) / "clender.db")
        patcher = mock.patch.object(database, "DB_PATH", db_path)
        patcher.start()
        self.addCleanup(patcher.stop)
        database.init_db()

    def _add(self, event_type, title, start, end=None):
        return database.add_event(
            event_type=event_type,
            title=title,
            start_time=start,
            end_time=end,
        )

    def test_half_open_query_includes_cross_day_intersections_and_sorts(self):
        self._add("timespan", "结束等于起点", "2026-08-01 07:00", "2026-08-02 08:00")
        self._add("timespan", "跨日值班", "2026-08-01 23:00", "2026-08-02 09:00")
        self._add("reminder", "起点提醒", "2026-08-02 08:00")
        self._add("timespan", "跨过起点", "2026-08-02 07:59", "2026-08-02 08:01")
        self._add("reminder", "终点前提醒", "2026-08-02 21:59")
        self._add("timespan", "终点前结束", "2026-08-02 21:59", "2026-08-02 22:00")
        self._add("reminder", "终点提醒", "2026-08-02 22:00")
        self._add("timespan", "起于终点", "2026-08-02 22:00", "2026-08-02 23:00")

        events = EventService.get_events_overlapping_range(
            datetime(2026, 8, 2, 8, 0),
            datetime(2026, 8, 2, 22, 0),
        )

        self.assertEqual(
            [event.title for event in events],
            ["跨日值班", "跨过起点", "起点提醒", "终点前提醒", "终点前结束"],
        )

    def test_invalid_or_empty_ranges_are_rejected_before_database_access(self):
        with mock.patch(
            "event_service.database.get_events_overlapping_range"
        ) as query:
            for start, end in (
                (datetime(2026, 8, 2, 9), datetime(2026, 8, 2, 9)),
                (datetime(2026, 8, 2, 10), datetime(2026, 8, 2, 9)),
                ("2026-08-02 08:00", datetime(2026, 8, 2, 9)),
            ):
                with self.subTest(start=start, end=end), self.assertRaises(
                    (TypeError, ValueError)
                ):
                    EventService.get_events_overlapping_range(start, end)
            query.assert_not_called()


if __name__ == "__main__":
    unittest.main()
