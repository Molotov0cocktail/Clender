import sqlite3
import tempfile
import unittest
from contextlib import closing
from datetime import date
from pathlib import Path
from unittest import mock

import database
from event_service import EventService


class DatabaseServiceTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.db_path = str(Path(self.temp_dir.name) / "clender.db")
        self.db_patch = mock.patch.object(database, "DB_PATH", self.db_path)
        self.db_patch.start()
        self.addCleanup(self.db_patch.stop)
        database.init_db()

    def test_init_creates_date_index(self):
        with closing(sqlite3.connect(self.db_path)) as conn:
            indexes = {row[1] for row in conn.execute("PRAGMA index_list(events)")}
        self.assertIn("idx_events_start_time", indexes)

    def test_init_migrates_legacy_schema(self):
        Path(self.db_path).unlink()
        with closing(sqlite3.connect(self.db_path)) as conn, conn:
            conn.execute('''
                CREATE TABLE events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    event_type TEXT NOT NULL,
                    title TEXT NOT NULL,
                    start_time TEXT NOT NULL,
                    end_time TEXT,
                    description TEXT DEFAULT '',
                    created_at TEXT DEFAULT CURRENT_TIMESTAMP
                )
            ''')

        database.init_db()

        with closing(sqlite3.connect(self.db_path)) as conn:
            columns = {row[1] for row in conn.execute("PRAGMA table_info(events)")}
        self.assertIn("estimated_duration", columns)

    def test_crud_and_counts(self):
        event_id = EventService.add_event(
            title="会议",
            event_type="timespan",
            start_time="2026-08-02 10:00",
            end_time="2026-08-02 11:00",
        )

        event = EventService.get_event_by_id(event_id)
        self.assertEqual(event.title, "会议")
        self.assertEqual(EventService.get_event_counts()[date(2026, 8, 2)], 1)
        self.assertEqual(EventService.update_event(event_id, title="新会议"), 1)
        self.assertTrue(EventService.delete_event(event_id))
        self.assertIsNone(EventService.get_event_by_id(event_id))

    def test_database_update_can_explicitly_clear_nullable_end_time(self):
        event_id = database.add_event(
            "timespan", "会议", "2026-08-02 10:00", "2026-08-02 11:00"
        )

        self.assertEqual(database.update_event(event_id, end_time=None), 1)
        self.assertIsNone(database.get_event_by_id(event_id).end_time)

    def test_service_rejects_invalid_event_contracts(self):
        invalid_cases = [
            {"title": "", "event_type": "reminder", "start_time": "2026-08-02 10:00"},
            {"title": "x", "event_type": "other", "start_time": "2026-08-02 10:00"},
            {"title": "x", "event_type": "reminder", "start_time": "bad"},
            {
                "title": "x",
                "event_type": "timespan",
                "start_time": "2026-08-02 11:00",
                "end_time": "2026-08-02 10:00",
            },
            {
                "title": "x",
                "event_type": "reminder",
                "start_time": "2026-08-02 10:00",
                "estimated_duration": -1,
            },
        ]
        for case in invalid_cases:
            with self.subTest(case=case), self.assertRaises(ValueError):
                EventService.add_event(**case)

    def test_failed_statement_closes_connection(self):
        connection = mock.Mock()
        cursor = connection.cursor.return_value
        cursor.execute.side_effect = sqlite3.OperationalError("boom")
        with mock.patch.object(database, "get_connection", return_value=connection):
            with self.assertRaises(sqlite3.OperationalError):
                database.get_all_events()
        connection.close.assert_called_once()


if __name__ == "__main__":
    unittest.main()
