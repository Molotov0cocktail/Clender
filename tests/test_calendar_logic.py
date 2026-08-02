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
        adjacent_blocks = build_day_blocks(adjacent, 30)
        self.assertTrue(all(not b["overlap_ranges"] for b in adjacent_blocks))
        self.assertEqual([b["lane"] for b in adjacent_blocks], [0, 0])
        self.assertEqual([b["lane_count"] for b in adjacent_blocks], [1, 1])
        self.assertNotEqual(adjacent_blocks[0]["cluster_id"], adjacent_blocks[1]["cluster_id"])

        triple = [
            self.event(id=1, estimated_duration=120),
            self.event(id=2, start_time="2026-08-02 10:30", estimated_duration=60),
            self.event(id=3, start_time="2026-08-02 10:45", estimated_duration=30),
        ]
        triple_blocks = build_day_blocks(triple, 30)
        self.assertTrue(all(b["overlap_ranges"] for b in triple_blocks))
        self.assertEqual([b["lane"] for b in triple_blocks], [0, 1, 2])
        self.assertEqual({b["lane_count"] for b in triple_blocks}, {3})
        self.assertEqual(len({b["cluster_id"] for b in triple_blocks}), 1)

    def test_two_overlapping_events_share_cluster_and_use_two_lanes(self):
        events = [
            self.event(id=1, estimated_duration=120),
            self.event(id=2, start_time="2026-08-02 11:00", estimated_duration=120),
        ]

        blocks = build_day_blocks(events, 30)

        self.assertEqual([b["lane"] for b in blocks], [0, 1])
        self.assertEqual({b["lane_count"] for b in blocks}, {2})
        self.assertEqual(len({b["cluster_id"] for b in blocks}), 1)

    def test_many_simultaneous_events_receive_distinct_lanes(self):
        events = [self.event(id=index, estimated_duration=60) for index in range(1, 7)]

        blocks = build_day_blocks(events, 30)

        self.assertEqual([b["lane"] for b in blocks], list(range(6)))
        self.assertEqual({b["lane_count"] for b in blocks}, {6})
        self.assertEqual(len({b["cluster_id"] for b in blocks}), 1)

    def test_connected_cluster_reuses_lane_and_keeps_peak_lane_count(self):
        events = [
            self.event(id=1, estimated_duration=60),
            self.event(id=2, start_time="2026-08-02 10:30", estimated_duration=90),
            self.event(id=3, start_time="2026-08-02 11:00", estimated_duration=30),
        ]

        blocks = build_day_blocks(events, 30)

        self.assertEqual([b["lane"] for b in blocks], [0, 1, 0])
        self.assertEqual({b["lane_count"] for b in blocks}, {2})
        self.assertEqual(len({b["cluster_id"] for b in blocks}), 1)

    def test_adjacent_short_timespans_do_not_overlap_despite_minimum_visual_height(self):
        events = [
            self.event(
                id=1,
                event_type=EventType.TIMESPAN,
                start_time="2026-08-02 10:00",
                end_time="2026-08-02 10:10",
            ),
            self.event(
                id=2,
                event_type=EventType.TIMESPAN,
                start_time="2026-08-02 10:10",
                end_time="2026-08-02 10:20",
            ),
        ]

        blocks = build_day_blocks(events, 30)

        self.assertEqual([block["lane"] for block in blocks], [0, 0])
        self.assertEqual([block["lane_count"] for block in blocks], [1, 1])
        self.assertTrue(all(not block["overlap_ranges"] for block in blocks))

    def test_short_reminder_keeps_lane_contract(self):
        block = build_week_blocks(
            [self.event(start_time="2026-07-27 23:59")],
            date(2026, 7, 27),
            30,
        )[0]

        self.assertEqual(block["lane"], 0)
        self.assertEqual(block["lane_count"], 1)
        self.assertIsInstance(block["cluster_id"], int)

    def test_invalid_duration_and_reversed_timespan_are_skipped(self):
        events = [
            self.event(id=1, estimated_duration=-1),
            self.event(
                id=2,
                event_type=EventType.TIMESPAN,
                start_time="2026-08-02 12:00",
                end_time="2026-08-02 11:00",
            ),
        ]

        self.assertEqual(build_day_blocks(events, 30), [])

    def test_merge_ranges_handles_touching_ranges(self):
        self.assertEqual(merge_ranges([(1, 2), (2, 3), (5, 6)]), [(1, 3), (5, 6)])


if __name__ == "__main__":
    unittest.main()
