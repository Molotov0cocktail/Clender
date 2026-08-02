import unittest
from datetime import date

from calendar_logic import build_day_blocks, build_week_blocks, merge_ranges
from models import Event, EventType


class CalendarLogicTests(unittest.TestCase):
    def event(self, **changes):
        values = {
            "id": 1,
            "event_type": EventType.REMINDER,
            "title": "提醒",
            "start_time": "2026-08-02 10:00",
            "end_time": None,
            "estimated_duration": 0,
        }
        values.update(changes)
        return Event(**values)

    def test_day_blocks_cover_reminder_duration_and_timespan(self):
        events = [
            self.event(id=1),
            self.event(id=2, estimated_duration=60),
            self.event(
                id=3,
                event_type=EventType.TIMESPAN,
                title="会议",
                start_time="2026-08-02 11:00",
                end_time="2026-08-02 12:30",
            ),
        ]

        blocks = build_day_blocks(events, per_hour=30)

        self.assertEqual(len(blocks), 3)
        self.assertEqual(blocks[0]["top"], 300)
        self.assertEqual(blocks[1]["height"], 30)
        self.assertEqual(blocks[2]["height"], 45)
        self.assertTrue(all("overlap_ranges" in block for block in blocks))

    def test_week_blocks_map_columns_and_skip_invalid_data(self):
        events = [
            self.event(start_time="2026-07-27 00:00"),
            self.event(id=2, start_time="bad"),
            self.event(id=3, start_time="2026-08-03 10:00"),
        ]
        blocks = build_week_blocks(events, date(2026, 7, 27), per_hour=30)
        self.assertEqual([block["col"] for block in blocks], [0])

    def test_adjacent_events_do_not_overlap_but_triple_overlap_does(self):
        adjacent = [
            self.event(id=1, estimated_duration=60),
            self.event(id=2, start_time="2026-08-02 11:00", estimated_duration=60),
        ]
        self.assertTrue(all(not b["overlap_ranges"] for b in build_day_blocks(adjacent, 30)))

        triple = [
            self.event(id=1, estimated_duration=120),
            self.event(id=2, start_time="2026-08-02 10:30", estimated_duration=60),
            self.event(id=3, start_time="2026-08-02 10:45", estimated_duration=30),
        ]
        self.assertTrue(all(b["overlap_ranges"] for b in build_day_blocks(triple, 30)))

    def test_merge_ranges_handles_touching_ranges(self):
        self.assertEqual(merge_ranges([(1, 2), (2, 3), (5, 6)]), [(1, 3), (5, 6)])


if __name__ == "__main__":
    unittest.main()
