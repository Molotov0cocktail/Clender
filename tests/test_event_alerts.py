import os
from contextlib import closing
import tempfile
import unittest
import json
from datetime import datetime
from unittest.mock import patch, Mock
from types import SimpleNamespace

import database
from event_service import EventService
from ai_service import AIService


class EventAlertTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.db = patch.object(database, 'DB_PATH', os.path.join(self.temp.name, 'test.db'))
        self.db.start()
        self.addCleanup(self.db.stop)
        database.init_db()

    def test_policy_defaults_and_roundtrip(self):
        eid = EventService.add_event('synthetic', 'reminder', '2030-01-01 09:00')
        self.assertTrue(EventService.get_event_by_id(eid).notification_enabled)
        EventService.update_event(eid, notification_enabled=False, alarm_enabled=True, timer_minutes=1440)
        event = EventService.get_event_by_id(eid)
        self.assertEqual((False, True, 1440), (event.notification_enabled, event.alarm_enabled, event.timer_minutes))
        self.assertNotIn('alarm_enabled', database.get_sync_records()[0])

    def test_unchanged_update_does_not_claim_changed_or_touch_sync_timestamp(self):
        eid = EventService.add_event('synthetic', 'reminder', '2030-01-01 09:00')
        before = database.get_sync_records()
        result = AIService.execute_operations([{'action': 'update', 'event_id': eid, 'notification_enabled': True}])
        self.assertFalse(result[0].startswith('✅'))
        self.assertEqual(before, database.get_sync_records())

    def test_invalid_policies_never_write(self):
        for field, value in [('alarm_enabled', 1), ('notification_enabled', 'true'), ('timer_minutes', True), ('timer_minutes', -1), ('timer_minutes', 1441)]:
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                EventService.add_event('synthetic', 'reminder', '2030-01-01 09:00', **{field: value})
        self.assertEqual([], EventService.get_all_events())

    def test_ai_policies_are_not_silently_ignored(self):
        result = AIService.execute_operations([dict(action='add', title='synthetic', start_time='2030-01-01 09:00', alarm_enabled=True, timer_minutes=1)])
        self.assertTrue(result[0].startswith('✅'))
        self.assertTrue(EventService.get_all_events()[0].alarm_enabled)

    def test_delivery_deduplicates_restart_and_timer(self):
        from event_alerts import EventAlertDispatcher
        EventService.add_event('synthetic', 'reminder', '2030-01-01 09:00', alarm_enabled=True, timer_minutes=1)
        sent = []
        send = lambda title, body: sent.append((title, body)) or True
        EventAlertDispatcher(send).poll(datetime(2030, 1, 1, 9, 0, 5))
        EventAlertDispatcher(send).poll(datetime(2030, 1, 1, 9, 0, 15))
        self.assertEqual(1, len(sent))
        EventAlertDispatcher(send).poll(datetime(2030, 1, 1, 9, 1, 0))
        self.assertEqual(2, len(sent))

    def test_failed_delivery_retry_delete_and_old_events(self):
        from event_alerts import EventAlertDispatcher
        eid = EventService.add_event('synthetic', 'reminder', '2030-01-01 09:00')
        now = datetime(2030, 1, 1, 9, 0, 5)
        EventAlertDispatcher(lambda *_: False).poll(now)
        sent = []
        dispatcher = EventAlertDispatcher(lambda *args: sent.append(args) or True)
        dispatcher.poll(now)
        self.assertEqual(1, len(sent))

        EventService.update_event(eid, start_time='2030-01-01 09:01')
        EventService.delete_event(eid)
        dispatcher.poll(datetime(2030, 1, 1, 9, 1))
        EventService.add_event('past', 'reminder', '2030-01-01 08:00')
        dispatcher.poll(now)
        self.assertEqual(1, len(sent))

    def test_legacy_migration_is_idempotent_and_sync_keeps_local_policy(self):
        with closing(database.get_connection()) as conn, conn:
            conn.execute('DROP TABLE events')
            conn.execute("CREATE TABLE events(id INTEGER PRIMARY KEY, event_type TEXT, title TEXT, start_time TEXT, end_time TEXT, description TEXT, created_at TEXT)")
            conn.execute("INSERT INTO events VALUES(1,'reminder','legacy','2030-01-01 09:00',NULL,'','2026-01-01 00:00:00')")
        database.init_db()
        database.init_db()
        event = EventService.get_event_by_id(1)
        self.assertEqual((False, False, 0), (event.notification_enabled, event.alarm_enabled, event.timer_minutes))
        EventService.update_event(1, alarm_enabled=True, timer_minutes=1)
        remote = database.get_sync_records()[0]
        remote.update(title='remote', updated_at='2099-01-01T00:00:00Z')
        database.apply_sync_records([remote])
        event = EventService.get_event_by_id(1)
        self.assertEqual(('remote', True, 1), (event.title, event.alarm_enabled, event.timer_minutes))

    def test_webdav_v1_roundtrip_does_not_transfer_local_reminder_modes(self):
        from webdav_sync import parse_document, serialize_document
        local_id = EventService.add_event(
            'synthetic', 'reminder', '2030-01-01 09:00',
            notification_enabled=False, alarm_enabled=True, timer_minutes=15,
        )
        outgoing = serialize_document(database.get_sync_records())
        self.assertEqual(1, json.loads(outgoing)['schema_version'])
        for field in ('notification_enabled', 'alarm_enabled', 'timer_minutes'):
            self.assertNotIn(field.encode(), outgoing)
        second_db = os.path.join(self.temp.name, 'other-synthetic.db')
        with patch.object(database, 'DB_PATH', second_db):
            database.init_db()
            database.apply_sync_records(parse_document(outgoing))
            imported = EventService.get_all_events()[0]
            self.assertEqual(
                (False, False, 0),
                (imported.notification_enabled, imported.alarm_enabled, imported.timer_minutes),
            )
            EventService.update_event(imported.id, title='shared edit', start_time='2030-01-01 10:00')
            self.assertFalse(EventService.get_event_by_id(imported.id).notification_enabled)
            incoming = serialize_document(database.get_sync_records())
        database.apply_sync_records(parse_document(incoming))
        retained = EventService.get_event_by_id(local_id)
        self.assertEqual(('shared edit', '2030-01-01 10:00'), (retained.title, retained.start_time))
        self.assertEqual(
            (False, True, 15),
            (retained.notification_enabled, retained.alarm_enabled, retained.timer_minutes),
        )

    def test_migration_failure_rolls_back_schema(self):
        original = database._utc_now_text
        with closing(database.get_connection()) as conn, conn:
            conn.execute('DROP TABLE events')
            conn.execute("CREATE TABLE events(id INTEGER PRIMARY KEY, event_type TEXT, title TEXT, start_time TEXT)")
        with patch.object(database, '_utc_now_text', side_effect=RuntimeError('synthetic')):
            with self.assertRaises(RuntimeError):
                database.init_db()
        with closing(database.get_connection()) as conn, conn:
            columns = {row[1] for row in conn.execute('PRAGMA table_info(events)')}
        self.assertNotIn('notification_enabled', columns)
        self.assertIs(database._utc_now_text, original)

    def test_ui_policy_roundtrip_and_theme(self):
        os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
        from PyQt5.QtWidgets import QApplication, QCheckBox
        from ui.event_dialog import EventDialog
        from ui.event_detail_dialog import EventDetailDialog
        from datetime import date
        app = QApplication.instance() or QApplication([])
        eid = EventService.add_event('synthetic', 'reminder', '2030-01-01 09:00', notification_enabled=False, alarm_enabled=True, timer_minutes=1440)
        with patch('config.load_config', return_value={}):
            event = EventService.get_event_by_id(eid)
            dialog = EventDialog(date(2030, 1, 1), edit_event=event.to_dict())
            detail = EventDetailDialog(event)
            try:
                self.assertEqual(1, len(dialog.findChildren(QCheckBox)))
                self.assertTrue(dialog.get_data()['notification_enabled'])
                self.assertFalse(dialog.get_data()['alarm_enabled'])
                dialog._notification.setChecked(False)
                dialog._timer_minutes.setValue(0)
                dialog._validate_and_accept()
                self.assertEqual(dialog.Accepted, dialog.result())
                self.assertFalse(dialog.get_data()['alarm_enabled'])
                self.assertFalse(dialog.get_data()['notification_enabled'])
                for theme in ('light', 'dark'):
                    with patch('config.load_config', return_value={'theme': theme}):
                        dialog.apply_theme()
                        detail.apply_theme()
                self.assertEqual('系统提醒', detail._lbl_alert.text())
            finally:
                dialog.close()
                detail.close()
                dialog.deleteLater()
                detail.deleteLater()
                app.processEvents()

    def test_tray_unavailable_does_not_report_delivery(self):
        from ui.main_window import MainWindow
        window = SimpleNamespace(has_system_tray=lambda: False, _tray_icon=Mock())
        self.assertFalse(MainWindow._deliver_event_alert(window, 'synthetic', 'body'))
        window._tray_icon.showMessage.assert_not_called()
        window.has_system_tray = lambda: True
        with patch('ui.main_window.QSystemTrayIcon.supportsMessages', return_value=True):
            self.assertTrue(MainWindow._deliver_event_alert(window, 'synthetic', 'body'))
        window._tray_icon.showMessage.assert_called_once()
