"""The shared calendar/floating edit path retains input on storage failure."""
import os
os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
import sqlite3
import unittest
from unittest.mock import Mock, patch
from PyQt5.QtWidgets import QDialog
from models import Event, EventType
from ui.main_window import MainWindow


class PCEditRetryTests(unittest.TestCase):
    def test_failed_save_retries_same_dialog_and_refreshes_once(self):
        self.check_retry(True)

    def test_failed_save_then_cancel_does_not_refresh(self):
        self.check_retry(False)

    def check_retry(self, succeeds):
        owner = Mock()
        event = Event(id=7, event_type=EventType.TIMESPAN, title='Test',
                      start_time='2026-09-06 09:00', end_time='2026-09-06 10:00')
        fields = dict(event_type='timespan', title='Edited', start_time=event.start_time,
                      end_time=event.end_time, description='', estimated_duration=0)
        dialog = Mock()
        dialog.exec_.side_effect = [QDialog.Accepted, QDialog.Accepted if succeeds else QDialog.Rejected]
        dialog.get_data.return_value = fields
        with patch('ui.main_window.EventService.get_event_by_id', return_value=event), patch(
            'ui.main_window.EventDialog', return_value=dialog
        ) as constructor, patch('ui.main_window.EventService.update_event',
                              side_effect=[sqlite3.OperationalError('locked'), 1]) as update, patch(
            'ui.main_window.QMessageBox.critical'
        ) as warning:
            MainWindow._on_floating_event_edit_requested(owner, event.id)
        constructor.assert_called_once()
        self.assertEqual(dialog.exec_.call_count, 2)
        self.assertEqual(update.call_count, 2 if succeeds else 1)
        warning.assert_called_once()
        if succeeds:
            self.assertEqual(update.call_args_list[0], update.call_args_list[1])
            owner._on_data_changed.assert_called_once_with()
        else:
            owner._on_data_changed.assert_not_called()
