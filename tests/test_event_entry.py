"""Isolated regressions for desktop event forms and edit actions."""
import os
os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
import sqlite3
import unittest
from datetime import date
from unittest.mock import patch
from PyQt5.QtWidgets import QApplication, QListWidgetItem
from PyQt5.QtCore import QDate, QTime, Qt
from PyQt5.QtTest import QTest, QSignalSpy
from ui.event_dialog import EventDialog
from ui.event_manager import EventManager
import theme_manager


class EventEntryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def setUp(self):
        self.config = patch('config.load_config', return_value={})
        self.config.start()
        self.addCleanup(self.config.stop)
        self.widgets = []

    def tearDown(self):
        for widget in self.widgets:
            widget.close()
            widget.deleteLater()

    def dialog(self):
        dialog = EventDialog(date(2026, 12, 31))
        self.widgets.append(dialog)
        dialog._edit_title.setText('Test')
        dialog._cmb_type.setCurrentIndex(1)
        return dialog

    def test_invalid_order_warns_and_keeps_dialog_open(self):
        for time in (QTime(9, 0), QTime(8, 59)):
            dialog = self.dialog()
            dialog._edit_end.setTime(time)
            with patch('ui.event_dialog.QMessageBox.warning') as warning:
                dialog._validate_and_accept()
            warning.assert_called_once()
            self.assertEqual(dialog.result(), EventDialog.Rejected)

    def test_valid_order_is_accepted(self):
        dialog = self.dialog()
        dialog._validate_and_accept()
        self.assertEqual(dialog.result(), EventDialog.Accepted)

    def test_dates_default_to_focus_and_cross_year_roundtrip(self):
        dialog = self.dialog()
        self.assertEqual(dialog._edit_end_date.date(), QDate(2026, 12, 31))
        dialog._edit_end_date.setDate(QDate(2027, 1, 1))
        data = dialog.get_data()
        self.assertEqual(data['end_time'], '2027-01-01 10:00')
        edited = EventDialog(date(2026, 1, 1), edit_event=data)
        self.widgets.append(edited)
        self.assertEqual(edited.get_data(), data)

    def test_four_digits_boundaries_and_placeholder(self):
        dialog = self.dialog()
        field = dialog._edit_start
        self.assertEqual(field.text(), '')
        self.assertEqual(field.placeholderText(), '09:00')
        for digits, expected in [('0000', '00:00'), ('2359', '23:59'), ('0930', '09:30')]:
            field.clear()
            QTest.keyClicks(field, digits)
            self.assertEqual(field.text(), expected)
            self.assertEqual(field.time().toString('HH:mm'), expected)
        field.clear()
        self.assertEqual(field.time(), QTime(9, 0))

    def test_partial_and_invalid_time_warn(self):
        for text in ('123', '2460', '99:99', 'abc'):
            dialog = self.dialog()
            dialog._edit_start.setText(text)
            with patch('ui.event_dialog.QMessageBox.warning') as warning:
                dialog._validate_and_accept()
            warning.assert_called_once()
            self.assertEqual(dialog.result(), EventDialog.Rejected)

    def test_reminder_hides_end_fields_and_drops_end(self):
        dialog = self.dialog()
        dialog._cmb_type.setCurrentIndex(0)
        self.assertTrue(dialog._edit_end_date.isHidden())
        self.assertIsNone(dialog.get_data()['end_time'])

    def manager(self):
        manager = EventManager()
        self.widgets.append(manager)
        return manager

    def test_list_double_click_invokes_edit(self):
        manager = self.manager()
        item = QListWidgetItem('Test')
        item.setData(Qt.UserRole, 3)
        manager._list_widget.addItem(item)
        manager._list_widget.setCurrentItem(item)
        with patch.object(manager, '_on_edit_clicked') as edit:
            manager._list_widget.itemDoubleClicked.emit(item)
        edit.assert_called_once()

    def test_add_storage_failure_is_reported_without_changed_signal(self):
        manager = self.manager()
        changed = QSignalSpy(manager.data_changed)
        with patch('ui.event_manager.EventDialog') as dialog, patch(
            'ui.event_manager.EventService.add_event', side_effect=sqlite3.OperationalError('locked')
        ), patch('ui.event_manager.QMessageBox.warning') as warning:
            dialog.return_value.exec_.side_effect = [dialog.Accepted, -1]
            dialog.return_value.get_data.return_value = self.dialog().get_data()
            manager._on_add_clicked()
        warning.assert_called_once()
        self.assertEqual(len(changed), 0)

    def test_edit_storage_failure_and_missing_event_do_not_emit_change(self):
        for result, error in [(0, None), (None, sqlite3.OperationalError('locked'))]:
            manager = self.manager()
            item = QListWidgetItem('Test')
            item.setData(Qt.UserRole, 3)
            manager._list_widget.addItem(item)
            manager._list_widget.setCurrentItem(item)
            changed = QSignalSpy(manager.data_changed)
            data = self.dialog().get_data()
            with patch('ui.event_manager.EventDialog') as dialog, patch(
                'ui.event_manager.EventService.get_event_by_id', return_value=data
            ), patch('ui.event_manager.EventService.update_event', return_value=result,
                     side_effect=error), patch('ui.event_manager.QMessageBox.warning') as warning:
                dialog.return_value.exec_.side_effect = [dialog.Accepted, -1]
                dialog.return_value.get_data.return_value = data
                manager._on_edit_clicked()
            warning.assert_called_once()
            self.assertEqual(len(changed), 0)

    def test_add_success_emits_once_and_cancel_does_not_save(self):
        manager = self.manager()
        changed = QSignalSpy(manager.data_changed)
        with patch('ui.event_manager.EventDialog') as dialog, patch(
            'ui.event_manager.EventService.add_event', return_value=4
        ) as add, patch.object(manager, 'refresh'):
            dialog.return_value.exec_.return_value = dialog.Accepted
            dialog.return_value.get_data.return_value = self.dialog().get_data()
            manager._on_add_clicked()
            self.assertEqual(len(changed), 1)
            dialog.return_value.exec_.return_value = -1
            manager._on_add_clicked()
            add.assert_called_once()
            self.assertEqual(len(changed), 1)

    def test_light_dark_form_and_list_offscreen_smoke(self):
        dialog, manager = self.dialog(), self.manager()
        for theme in (theme_manager.LIGHT_THEME, theme_manager.DARK_THEME):
            with patch('theme_manager.get_current_theme', return_value=theme):
                dialog.apply_theme()
                manager.apply_theme()
                dialog.show()
                manager.show()
                self.app.processEvents()
                self.assertFalse(dialog.grab().isNull())
                self.assertFalse(manager.grab().isNull())

    def test_reminder_conversion_defaults_end_date_to_existing_start(self):
        data = self.dialog().get_data()
        data.update(event_type='reminder', end_time=None)
        dialog = EventDialog(date(2027, 1, 15), edit_event=data)
        self.widgets.append(dialog)
        dialog._cmb_type.setCurrentIndex(1)
        self.assertEqual(dialog._edit_end_date.date(), dialog._edit_date.date())

    def test_storage_failure_reopens_same_form_and_retry_succeeds(self):
        manager = self.manager()
        changed = QSignalSpy(manager.data_changed)
        data = self.dialog().get_data()
        with patch('ui.event_manager.EventDialog') as dialog, patch(
            'ui.event_manager.EventService.add_event',
            side_effect=[sqlite3.OperationalError('locked'), 4]
        ) as add, patch('ui.event_manager.QMessageBox.warning'), patch.object(manager, 'refresh'):
            dialog.return_value.exec_.side_effect = [dialog.Accepted, dialog.Accepted]
            dialog.return_value.get_data.return_value = data
            manager._on_add_clicked()
            dialog.assert_called_once()
            self.assertEqual(add.call_count, 2)
            self.assertEqual(add.call_args_list[0], add.call_args_list[1])
            self.assertEqual(len(changed), 1)


if __name__ == '__main__':
    unittest.main()
