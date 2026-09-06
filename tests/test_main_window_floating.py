import os
import unittest
from datetime import date
from unittest import mock

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PyQt5.QtCore import QObject, pyqtSignal
from PyQt5.QtWidgets import QApplication, QWidget

from constants import DEFAULT_CONFIG
from models import Event, EventType
from ui.main_window import MainWindow


class StubCalendar(QWidget):
    date_selected = pyqtSignal(date)
    event_activated = pyqtSignal(object)
    event_edit_requested = pyqtSignal(object)

    def __init__(self):
        super().__init__()
        self.set_selected_date = mock.Mock()
        self.get_selected_date = mock.Mock(return_value=date(2026, 9, 6))
        self.update_event_markers = mock.Mock()
        self.apply_theme = mock.Mock()


class StubEventManager(QWidget):
    data_changed = pyqtSignal()

    def __init__(self):
        super().__init__()
        self.set_date = mock.Mock()
        self.refresh = mock.Mock()
        self.apply_theme = mock.Mock()


class StubAIChat(QWidget):
    data_changed = pyqtSignal()
    external_request_status = pyqtSignal(str)

    def __init__(self):
        super().__init__()
        self.apply_theme = mock.Mock()
        self.submit_external_message = mock.Mock(return_value=True)


class StubSyncController(QObject):
    status_changed = pyqtSignal(str)
    schedules_changed = pyqtSignal()
    test_finished = pyqtSignal(bool, str)

    def __init__(self):
        super().__init__()
        self.request_sync = mock.Mock(return_value=True)
        self.test_connection = mock.Mock(return_value=True)
        self.shutdown = mock.Mock()


class SignalStub:
    def __init__(self):
        self._callbacks = []

    def connect(self, callback):
        self._callbacks.append(callback)

    def emit(self, *args):
        for callback in tuple(self._callbacks):
            callback(*args)


class StubFloatingWindow:
    def __init__(self):
        # Floating and main calendar share the read-only preview path.
        self.event_activated = SignalStub()
        self.event_edit_requested = SignalStub()
        self.ai_message_submitted = SignalStub()
        self.geometry_changed = SignalStub()
        self.visibility_change_requested = SignalStub()
        self._visible = False
        self.refresh = mock.Mock()
        self.apply_theme = mock.Mock()
        self.apply_font_scale = mock.Mock()
        self.set_ai_request_status = mock.Mock()
        self.shutdown = mock.Mock()
        self.apply_settings = mock.Mock(side_effect=self._apply_settings)
        self.show = mock.Mock(side_effect=lambda: self._set_visible(True))
        self.hide = mock.Mock(side_effect=lambda: self._set_visible(False))
        self.raise_ = mock.Mock()
        self.activateWindow = mock.Mock()

    def _set_visible(self, visible):
        self._visible = visible

    def _apply_settings(self, settings):
        self._visible = settings.enabled

    def isVisible(self):
        return self._visible


class StubDialog:
    def __init__(self):
        self.config_saved = SignalStub()
        self.theme_toggle_requested = SignalStub()
        self.ai_settings_requested = SignalStub()
        self.webdav_test_requested = SignalStub()
        self.webdav_sync_requested = SignalStub()
        self.finished = SignalStub()
        self.exec_ = mock.Mock(return_value=0)
        self.show = mock.Mock()
        self.raise_ = mock.Mock()
        self.apply_theme = mock.Mock()
        self.set_webdav_status = mock.Mock()
        self.set_webdav_test_result = mock.Mock()
        self.close = mock.Mock()


class MainWindowFloatingIntegrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def setUp(self):
        self.config = dict(DEFAULT_CONFIG)
        self.floating = StubFloatingWindow()
        self.sync = StubSyncController()
        self.saved_configs = []
        self.events = {
            1: Event(
                id=1,
                event_type=EventType.REMINDER,
                title="提醒一",
                start_time="2026-08-02 09:00",
            ),
            2: Event(
                id=2,
                event_type=EventType.TIMESPAN,
                title="事项二",
                start_time="2026-08-02 10:00",
                end_time="2026-08-02 11:00",
            ),
        }

    def _make_window(self, tray_available=False):
        floating_class = mock.Mock(return_value=self.floating)
        patchers = [
            mock.patch("ui.main_window.CalendarWidget", StubCalendar),
            mock.patch("ui.main_window.EventManager", StubEventManager),
            mock.patch("ui.main_window.AIChatWidget", StubAIChat),
            mock.patch("ui.main_window.SyncController", return_value=self.sync),
            mock.patch(
                "ui.main_window.DailyFloatingWindow",
                floating_class,
                create=True,
            ),
            mock.patch(
                "ui.main_window.cfg_mod.load_config",
                side_effect=lambda: dict(self.config),
            ),
            mock.patch(
                "ui.main_window.cfg_mod.save_config",
                side_effect=lambda value: self.saved_configs.append(dict(value)),
            ),
            mock.patch("ui.main_window.cfg_mod.get_theme", return_value="light"),
            mock.patch("ui.main_window.theme_manager.apply_theme"),
            mock.patch("ui.main_window.theme_manager.switch_theme"),
            mock.patch(
                "ui.main_window.EventService.load_sample_data_if_empty"
            ),
            mock.patch(
                "ui.main_window.EventService.get_event_counts", return_value={}
            ),
            mock.patch(
                "ui.main_window.EventService.get_event_by_id",
                side_effect=lambda event_id: self.events.get(event_id),
            ),
            mock.patch(
                "ui.main_window.QSystemTrayIcon.isSystemTrayAvailable",
                return_value=tray_available,
            ),
        ]
        for patcher in patchers:
            patcher.start()
            self.addCleanup(patcher.stop)
        window = MainWindow(self.app)
        self.addCleanup(window.close)
        return window, floating_class

    def test_constructs_one_floating_window_and_both_data_signals_refresh_it(self):
        window, floating_class = self._make_window()

        floating_class.assert_called_once_with()
        self.assertIs(window._floating_window, self.floating)
        self.floating.refresh.reset_mock()

        window._event_mgr.data_changed.emit()
        self.floating.refresh.assert_called_once_with()
        self.floating.refresh.reset_mock()

        window._ai_chat.data_changed.emit()
        self.floating.refresh.assert_called_once_with()
        floating_class.assert_called_once_with()
        self.assertEqual(
            self.sync.request_sync.call_args_list,
            [mock.call("local-change"), mock.call("local-change")],
        )

    def test_remote_refresh_does_not_start_a_second_sync_loop(self):
        window, _ = self._make_window()
        self.floating.refresh.reset_mock()
        self.sync.request_sync.reset_mock()

        self.sync.schedules_changed.emit()

        self.floating.refresh.assert_called_once_with()
        self.sync.request_sync.assert_not_called()

    def test_calendar_and_floating_open_read_only_detail(self):
        window, _ = self._make_window()
        detail = StubDialog()
        detail_class = mock.Mock(return_value=detail)

        with mock.patch(
            "ui.main_window.EventDetailDialog", detail_class, create=True
        ):
            window._calendar.event_activated.emit((1,))
            self.floating.event_activated.emit(2)

        self.assertEqual(detail_class.call_count, 2)
        self.assertEqual([c.args[0].id for c in detail_class.call_args_list], [1, 2])
        self.assertEqual(detail.show.call_count, 2)

    def test_multiple_ids_use_choice_dialog_before_opening_details(self):
        window, _ = self._make_window()
        detail = StubDialog()

        def choose_second(parent, title, prompt, items, *args):
            return items[1], True

        with mock.patch(
            "ui.main_window.QInputDialog.getItem", side_effect=choose_second, create=True
        ) as get_item, mock.patch(
            "ui.main_window.EventDetailDialog", return_value=detail, create=True
        ) as detail_class:
            window._calendar.event_activated.emit((1, 2))

        get_item.assert_called_once()
        self.assertEqual(detail_class.call_args.args[0].id, 2)
        detail.show.assert_called_once_with()

    def test_calendar_double_click_chooses_one_event_and_uses_shared_editor(self):
        window, _ = self._make_window()
        with mock.patch.object(window, "_on_floating_event_edit_requested") as edit, mock.patch(
            "ui.main_window.QInputDialog.getItem",
            side_effect=lambda parent, title, prompt, items, *args: (items[1], True),
        ), mock.patch.object(window, "_show_event_detail") as preview:
            window._calendar.event_edit_requested.emit((1, 2))
        edit.assert_called_once_with(2)
        preview.assert_not_called()

    def test_invalid_or_cancelled_calendar_edit_does_not_open_editor(self):
        window, _ = self._make_window()
        with mock.patch.object(window, "_on_floating_event_edit_requested") as edit, mock.patch(
            "ui.main_window.QInputDialog.getItem", return_value=("", False)
        ):
            window._calendar.event_edit_requested.emit((True, -1, "bad", 99))
            window._calendar.event_edit_requested.emit((1, 2))
        edit.assert_not_called()

    def test_geometry_signal_atomically_updates_complete_config(self):
        self.config["api_key"] = "preserved-key"
        window, _ = self._make_window()

        self.floating.geometry_changed.emit((10, 20, 360, 480))

        self.assertEqual(len(self.saved_configs), 1)
        self.assertEqual(
            self.saved_configs[0]["floating_window_geometry"], [10, 20, 360, 480]
        )
        self.assertEqual(self.saved_configs[0]["api_key"], "preserved-key")

    def test_app_settings_applies_immediately_without_recreating_floating_window(self):
        window, floating_class = self._make_window()
        dialog = StubDialog()
        updated = dict(self.config)
        updated.update({
            "floating_window_enabled": True,
            "floating_window_opacity": 55,
            "floating_window_start_time": "09:00",
            "floating_window_end_time": "18:00",
        })

        with mock.patch(
            "ui.main_window.AppSettingsDialog", return_value=dialog, create=True
        ), mock.patch(
            "ui.main_window.QDialog",
            side_effect=AssertionError("legacy settings dialog used"),
            create=True,
        ):
            window._open_settings_dialog()
            self.floating.apply_settings.reset_mock()
            dialog.config_saved.emit(updated)

        dialog.exec_.assert_called_once_with()
        settings = self.floating.apply_settings.call_args.args[0]
        self.assertTrue(settings.enabled)
        self.assertEqual(settings.opacity_percent, 55)
        floating_class.assert_called_once_with()

    def test_tray_action_and_close_signal_show_and_hide_floating_window(self):
        window, _ = self._make_window(tray_available=True)
        self.floating.refresh.reset_mock()
        self.floating.show.reset_mock()
        self.floating.hide.reset_mock()

        window._floating_action.trigger()
        self.floating.refresh.assert_called_once_with()
        self.floating.show.assert_called_once_with()
        self.assertTrue(window._floating_action.isChecked())

        self.floating.visibility_change_requested.emit(False)
        self.floating.hide.assert_called_once_with()
        self.assertFalse(window._floating_action.isChecked())

    def test_theme_reaches_floating_and_open_details_and_exit_shuts_down(self):
        window, _ = self._make_window()
        detail = StubDialog()
        with mock.patch(
            "ui.main_window.EventDetailDialog", return_value=detail, create=True
        ):
            window._calendar.event_activated.emit((1,))

        self.floating.apply_theme.reset_mock()
        detail.apply_theme.reset_mock()
        window._apply_component_styles()
        self.floating.apply_theme.assert_called_once_with()
        detail.apply_theme.assert_called_once_with()

        with mock.patch("ui.main_window.QApplication.quit") as quit_app:
            window._quit_app()
        self.floating.shutdown.assert_called_once_with()
        quit_app.assert_called_once_with()


if __name__ == "__main__":
    unittest.main()
