import re
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path
from unittest import mock

import database
from event_service import EventService
from datetime import datetime


class SyncDatabaseTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.db_path = str(Path(self.temp_dir.name) / "clender.db")
        patcher = mock.patch.object(database, "DB_PATH", self.db_path)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_legacy_database_migration_backfills_sync_metadata_idempotently(self):
        with closing(sqlite3.connect(self.db_path)) as conn, conn:
            conn.execute('''
                CREATE TABLE events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    event_type TEXT NOT NULL,
                    title TEXT NOT NULL,
                    start_time TEXT NOT NULL,
                    end_time TEXT,
                    description TEXT DEFAULT '',
                    estimated_duration INTEGER DEFAULT 0,
                    created_at TEXT DEFAULT CURRENT_TIMESTAMP
                )
            ''')
            conn.execute(
                "INSERT INTO events(event_type,title,start_time) VALUES('reminder','旧事项','2026-08-03 09:00')"
            )

        database.init_db()
        database.init_db()

        with closing(sqlite3.connect(self.db_path)) as conn:
            conn.row_factory = sqlite3.Row
            columns = {row[1] for row in conn.execute("PRAGMA table_info(events)")}
            row = conn.execute("SELECT * FROM events").fetchone()
            indexes = {item[1] for item in conn.execute("PRAGMA index_list(events)")}
        self.assertTrue({"sync_uid", "updated_at", "deleted_at"} <= columns)
        self.assertRegex(row["sync_uid"], re.compile(r"^[0-9a-f]{32}$"))
        self.assertTrue(row["updated_at"].endswith("Z"))
        self.assertIsNone(row["deleted_at"])
        self.assertIn("idx_events_sync_uid", indexes)

    def test_local_crud_updates_metadata_and_soft_delete_is_filtered(self):
        database.init_db()
        stamps = iter([
            "2026-08-03T01:00:00.000000Z",
            "2026-08-03T02:00:00.000000Z",
            "2026-08-03T03:00:00.000000Z",
        ])
        with mock.patch.object(database, "_utc_now_text", side_effect=lambda: next(stamps), create=True):
            event_id = EventService.add_event(
                title="会议", event_type="reminder", start_time="2026-08-03 09:00"
            )
            EventService.update_event(event_id, title="新会议")
            EventService.delete_event(event_id)

        self.assertIsNone(EventService.get_event_by_id(event_id))
        self.assertEqual(EventService.get_all_events(), [])
        records = database.get_sync_records()
        self.assertEqual(len(records), 1)
        self.assertEqual(records[0]["title"], "新会议")
        self.assertEqual(records[0]["updated_at"], "2026-08-03T03:00:00.000000Z")
        self.assertEqual(records[0]["deleted_at"], records[0]["updated_at"])

    def test_apply_sync_records_preserves_local_integer_id(self):
        database.init_db()
        event_id = EventService.add_event(
            title="本地", event_type="reminder", start_time="2026-08-03 09:00"
        )
        local = database.get_sync_records()[0]
        incoming = dict(local)
        incoming.update({"title": "远端较新", "updated_at": "2099-01-01T00:00:00.000000Z"})

        changed = database.apply_sync_records([incoming])

        self.assertTrue(changed)
        self.assertEqual(EventService.get_event_by_id(event_id).title, "远端较新")
        self.assertEqual(database.get_sync_records()[0]["sync_uid"], local["sync_uid"])

    def test_older_incoming_record_cannot_overwrite_newer_local_change(self):
        database.init_db()
        with mock.patch.object(
            database, "_utc_now_text", return_value="2099-01-01T00:00:00.000000Z", create=True
        ):
            EventService.add_event(
                title="本地较新", event_type="reminder", start_time="2026-08-03 09:00"
            )
        incoming = dict(database.get_sync_records()[0])
        incoming.update({"title": "远端较旧", "updated_at": "2026-01-01T00:00:00.000000Z"})

        self.assertFalse(database.apply_sync_records([incoming]))
        self.assertEqual(EventService.get_all_events()[0].title, "本地较新")

    def test_deleted_timespan_is_excluded_from_overlap_queries(self):
        database.init_db()
        event_id = EventService.add_event(
            title="跨区间", event_type="timespan",
            start_time="2026-08-03 09:00", end_time="2026-08-03 11:00",
        )
        self.assertTrue(EventService.delete_event(event_id))

        events = EventService.get_events_overlapping_range(
            datetime(2026, 8, 3, 10, 0), datetime(2026, 8, 3, 12, 0)
        )

        self.assertEqual(events, [])

    def test_apply_sync_records_rolls_back_the_whole_batch_on_error(self):
        database.init_db()
        EventService.add_event(
            title="原值", event_type="reminder", start_time="2026-08-03 09:00"
        )
        good = dict(database.get_sync_records()[0])
        good.update({"title": "不应提交", "updated_at": "2099-01-01T00:00:00.000000Z"})
        bad = dict(good)
        bad["sync_uid"] = "f" * 32
        bad.pop("title")

        with self.assertRaises(KeyError):
            database.apply_sync_records([good, bad])

        self.assertEqual(EventService.get_all_events()[0].title, "原值")


if __name__ == "__main__":
    unittest.main()
