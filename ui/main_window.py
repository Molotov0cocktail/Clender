"""
Clender 主窗口 - 日历 + 事项 + AI + 托盘
从 main.py 迁移到 ui 包，使用 EventService 替代直接 database 调用
"""
from datetime import date, datetime
import calendar
import sqlite3

from PyQt5.QtWidgets import (QApplication, QMainWindow, QWidget, QHBoxLayout, QVBoxLayout,
                             QSplitter, QStatusBar, QLabel, QPushButton,
                             QSystemTrayIcon, QMenu, QInputDialog, QDialog,
                             QMessageBox)
from PyQt5.QtCore import Qt, QTimer
from PyQt5.QtGui import QColor
from background import BackgroundWidget
from app_icon import create_app_icon

import config as cfg_mod
import theme_manager
from event_service import EventService
from event_alerts import EventAlertDispatcher
from floating_window_logic import FloatingWindowSettings
from logger import get_logger
from sync_controller import SyncController
from ui.app_settings import AppSettingsDialog
from ui.calendar_widget import CalendarWidget
from ui.event_manager import EventManager
from ui.ai_chat_widget import AIChatWidget
from ui.ai_settings import SettingsDialog
from ui.daily_floating_window import DailyFloatingWindow
from ui.event_detail_dialog import EventDetailDialog
from ui.event_dialog import EventDialog


_log = get_logger(__name__)


class MainWindow(QMainWindow):
    """主窗口：日历 + 事项 + AI + 托盘"""

    def __init__(self, app: QApplication):
        super().__init__()
        self._app = app
        self.setWindowTitle('Clender - 智能日程管理')
        self.setWindowIcon(create_app_icon())
        self.resize(1280, 800)
        self.setMinimumSize(960, 600)

        self._detail_dialogs = set()
        self._settings_dialog = None
        self._shutting_down = False

        self._init_ui()
        self._floating_window = DailyFloatingWindow()
        self._sync_controller = SyncController(self)
        self._init_tray()
        self._alert_dispatcher = EventAlertDispatcher(self._deliver_event_alert)
        self._alert_timer = QTimer(self)
        self._alert_timer.setInterval(1000)
        self._alert_timer.timeout.connect(self._poll_event_alerts)
        self._alert_timer.start()
        self._load_sample_data_if_empty()
        self._connect_signals()
        self._apply_app_settings(cfg_mod.load_config())
        self._refresh_all()
        self._app.aboutToQuit.connect(self._shutdown_auxiliary_windows)

    def _init_ui(self):
        central = BackgroundWidget()
        self.setCentralWidget(central)

        main_layout = QVBoxLayout(central)
        main_layout.setContentsMargins(16, 12, 16, 16)
        main_layout.setSpacing(12)
        header = QHBoxLayout()
        self._brand_label = QLabel('Clender  /  我的日程')
        header.addWidget(self._brand_label)
        header.addStretch()
        appearance = QPushButton('外观与设置')
        appearance.setToolTip('背景、字号、悬浮窗和同步设置')
        appearance.clicked.connect(self._open_settings_dialog)
        header.addWidget(appearance)
        main_layout.addLayout(header)

        self._calendar = CalendarWidget()
        self._calendar.setMinimumWidth(380)
        self._calendar.setMaximumWidth(500)

        self._event_mgr = EventManager()
        self._event_mgr.setMinimumWidth(300)

        self._ai_chat = AIChatWidget()
        self._ai_chat.setMinimumWidth(350)

        self._splitter = QSplitter(Qt.Horizontal)
        self._splitter.setHandleWidth(10)
        self._splitter.setStyleSheet('QSplitter::handle { background: transparent; }')
        self._splitter.addWidget(self._calendar)
        self._splitter.addWidget(self._event_mgr)
        self._splitter.addWidget(self._ai_chat)
        self._splitter.setStretchFactor(0, 3)
        self._splitter.setStretchFactor(1, 3)
        self._splitter.setStretchFactor(2, 4)
        self._splitter.setSizes([400, 320, 500])

        main_layout.addWidget(self._splitter)

        # 状态栏
        self._status_bar = QStatusBar()
        self._status_label = QLabel('就绪')
        self._status_bar.addWidget(self._status_label)

        # 设置按钮
        self._btn_settings = QPushButton('设置')
        self._btn_settings.setToolTip('设置')
        self._btn_settings.clicked.connect(self._open_settings_dialog)
        self._status_bar.addPermanentWidget(self._btn_settings)

        # 主题切换
        self._theme_btn = QPushButton()
        self._theme_btn.setToolTip('切换日间/夜间主题')
        self._theme_btn.clicked.connect(self._toggle_theme)
        self._status_bar.addPermanentWidget(self._theme_btn)

        self.setStatusBar(self._status_bar)

    def _init_tray(self):
        if not QSystemTrayIcon.isSystemTrayAvailable():
            return
        self._tray_icon = QSystemTrayIcon(create_app_icon(), self)
        self._tray_icon.setToolTip('Clender - 智能日程管理')
        tray_menu = QMenu()
        show_action = tray_menu.addAction('显示主窗口')
        show_action.triggered.connect(self._show_from_tray)
        self._floating_action = tray_menu.addAction('显示今日悬浮窗')
        self._floating_action.setCheckable(True)
        self._floating_action.triggered.connect(self._set_floating_visibility)
        self._sync_action = tray_menu.addAction('立即同步日程')
        self._sync_action.triggered.connect(self._manual_sync)
        tray_menu.addSeparator()
        quit_action = tray_menu.addAction('退出')
        quit_action.triggered.connect(self._quit_app)
        self._tray_icon.setContextMenu(tray_menu)
        self._tray_icon.activated.connect(self._on_tray_activated)
        self._tray_icon.show()

    def _poll_event_alerts(self) -> None:
        if self._shutting_down or not self.has_system_tray():
            return
        try:
            self._alert_dispatcher.poll()
        except sqlite3.Error:
            self._status_label.setText('系统提醒暂时不可用，请稍后重试')

    def _deliver_event_alert(self, title: str, body: str) -> bool:
        if not self.has_system_tray() or not QSystemTrayIcon.supportsMessages():
            self._status_label.setText('系统通知不可用')
            return False
        self._tray_icon.showMessage(title, body, QSystemTrayIcon.Information, 10000)
        return True

    def has_system_tray(self) -> bool:
        """Return whether this window owns a usable tray icon."""
        return hasattr(self, '_tray_icon') and self._tray_icon.isVisible()

    def _on_tray_activated(self, reason):
        if reason == QSystemTrayIcon.DoubleClick:
            self._show_from_tray()

    def _show_from_tray(self):
        self.activate_existing_instance()

    def activate_existing_instance(self):
        """Restore and foreground the active modal or the main window."""
        modal = QApplication.activeModalWidget()
        if modal is not None:
            modal.show()
            modal.raise_()
            modal.activateWindow()
            return

        state = self.windowState() & ~Qt.WindowMinimized
        self.setWindowState(state)
        self.show()
        self.raise_()
        self.activateWindow()

    def _quit_app(self):
        if hasattr(self, '_tray_icon'):
            self._tray_icon.hide()
        self._shutdown_auxiliary_windows()
        QApplication.quit()

    def _open_settings_dialog(self):
        """打开综合设置对话框。"""
        dlg = AppSettingsDialog(self)
        self._settings_dialog = dlg
        dlg.config_saved.connect(self._apply_app_settings)
        dlg.theme_toggle_requested.connect(self._toggle_theme)
        dlg.ai_settings_requested.connect(lambda: self._open_ai_settings(dlg))
        dlg.webdav_test_requested.connect(self._sync_controller.test_connection)
        dlg.webdav_sync_requested.connect(self._manual_sync)
        try:
            dlg.exec_()
        finally:
            self._settings_dialog = None

    def _open_ai_settings(self, parent):
        dlg2 = SettingsDialog(parent)
        dlg2.exec_()

    def _apply_app_settings(self, config):
        settings = FloatingWindowSettings.from_config(config)
        self._floating_window.apply_settings(settings)
        theme_manager.apply_theme(self._app, config)
        self._apply_component_styles()
        self._sync_floating_action()

    def _toggle_theme(self):
        current = cfg_mod.get_theme()
        new_theme = 'dark' if current == 'light' else 'light'
        theme_manager.switch_theme(self._app, new_theme)
        self._apply_component_styles()
        self._update_theme_button()

    def _update_theme_button(self):
        current = cfg_mod.get_theme()
        self._theme_btn.setText('日间' if current == 'dark' else '夜间')

    def _apply_component_styles(self):
        self.centralWidget().apply_config(cfg_mod.load_config())
        self._calendar.setProperty('backgroundSurface', None)
        self._calendar.apply_theme()
        self._event_mgr.apply_theme()
        self._ai_chat.apply_theme()
        self._apply_background_surfaces()
        self._floating_window.apply_theme()
        for dialog in tuple(self._detail_dialogs):
            dialog.apply_theme()
        if self._settings_dialog is not None:
            self._settings_dialog.apply_theme()
        self._update_theme_button()
        self._resize_status_buttons()

    def _apply_background_surfaces(self):
        """Keep background visible through panel shells and large reading areas."""
        if not self.centralWidget().has_background:
            return
        theme = theme_manager.get_current_theme()
        color = QColor(theme['frame_bg'])
        surface = f'rgba({color.red()}, {color.green()}, {color.blue()}, 150)'
        for panel in (self._calendar, self._event_mgr, self._ai_chat):
            panel.setProperty('backgroundSurface', surface)
            # Only replace the shell's palette color, preserving buttons and text.
            panel.setStyleSheet(panel.styleSheet().replace(theme['frame_bg'], surface))
        for panel, attribute in ((self._event_mgr, '_list_widget'), (self._ai_chat, '_chat_display')):
            area = getattr(panel, attribute, None)
            if area is not None:
                area.setStyleSheet(area.styleSheet().replace(theme['list_bg'], 'transparent'))
                area.viewport().setAutoFillBackground(False)

    def _resize_status_buttons(self):
        """Keep compact text actions readable across the app font range."""
        for button in (self._btn_settings, self._theme_btn):
            metrics = button.fontMetrics()
            button.setFixedSize(max(52, metrics.horizontalAdvance(button.text()) + 24),
                                max(28, metrics.height() + 14))

    def _connect_signals(self):
        self._calendar.date_selected.connect(self._on_date_selected)
        self._calendar.event_activated.connect(self._on_event_activated)
        self._calendar.event_edit_requested.connect(self._on_event_edit_requested)
        self._floating_window.event_activated.connect(self._on_event_activated)
        self._event_mgr.data_changed.connect(self._on_data_changed)
        self._ai_chat.data_changed.connect(self._on_data_changed)
        self._floating_window.event_edit_requested.connect(
            self._on_floating_event_edit_requested
        )
        self._floating_window.ai_message_submitted.connect(
            self._ai_chat.submit_external_message
        )
        self._ai_chat.external_request_status.connect(
            self._floating_window.set_ai_request_status
        )
        self._floating_window.geometry_changed.connect(self._save_floating_geometry)
        self._floating_window.visibility_change_requested.connect(
            self._set_floating_visibility
        )
        self._sync_controller.status_changed.connect(self._on_sync_status)
        self._sync_controller.schedules_changed.connect(
            self._on_remote_data_changed
        )
        self._sync_controller.test_finished.connect(
            self._on_webdav_test_finished
        )

    def _on_date_selected(self, d: date):
        self._event_mgr.set_date(d)
        self._calendar.set_selected_date(d)
        self._calendar.update_event_markers(self._get_event_counts())
        self._status_label.setText(f'已选中: {d.year}年{d.month}月{d.day}日')

    def _on_data_changed(self):
        self._refresh_all()
        self._status_label.setText('日程已更新 ✓')
        self._sync_controller.request_sync('local-change')

    def _on_remote_data_changed(self):
        self._refresh_all()
        self._status_label.setText('已应用 WebDAV 远端日程 ✓')

    def _manual_sync(self):
        self._sync_controller.request_sync('manual')

    def _on_sync_status(self, status):
        text = str(status)
        if ':' in text:
            _kind, text = text.split(':', 1)
        self._status_label.setText(text[:200])
        if self._settings_dialog is not None:
            self._settings_dialog.set_webdav_status(status)

    def _on_webdav_test_finished(self, ok, message):
        if self._settings_dialog is not None:
            self._settings_dialog.set_webdav_test_result(bool(ok), message)

    def _refresh_all(self):
        events_by_date = self._get_event_counts()
        self._calendar.update_event_markers(events_by_date)
        self._event_mgr.refresh()
        self._floating_window.refresh()

    def _on_event_activated(self, event_ids):
        self._open_calendar_event(event_ids, edit=False)

    def _on_event_edit_requested(self, event_ids):
        self._open_calendar_event(event_ids, edit=True)

    def _open_calendar_event(self, event_ids, *, edit):
        if isinstance(event_ids, int) and not isinstance(event_ids, bool):
            candidates = [event_ids]
        elif isinstance(event_ids, (list, tuple, set)):
            candidates = list(event_ids)
        else:
            return

        unique_ids = []
        for event_id in candidates:
            if (
                isinstance(event_id, int)
                and not isinstance(event_id, bool)
                and event_id > 0
                and event_id not in unique_ids
            ):
                unique_ids.append(event_id)
        events = [EventService.get_event_by_id(event_id) for event_id in unique_ids]
        events = [event for event in events if event is not None]
        if not events:
            return

        selected = events[0]
        if len(events) > 1:
            labels = [
                f"{event.title} — {event.start_time} [#{event.id}]"
                for event in events
            ]
            choice, accepted = QInputDialog.getItem(
                self,
                "选择事项",
                "请选择要编辑的事项：" if edit else "请选择要查看的事项：",
                labels,
                0,
                False,
            )
            if not accepted:
                return
            selected = events[labels.index(choice)]
        if edit:
            self._on_floating_event_edit_requested(selected.id)
        else:
            self._show_event_detail(selected)

    def _on_floating_event_edit_requested(self, event_id):
        if (
            isinstance(event_id, bool)
            or not isinstance(event_id, int)
            or event_id <= 0
        ):
            return

        event = EventService.get_event_by_id(event_id)
        if event is None:
            return
        try:
            event_date = datetime.strptime(
                event.start_time, "%Y-%m-%d %H:%M"
            ).date()
        except (AttributeError, TypeError, ValueError):
            QMessageBox.warning(self, "无法编辑", "事项开始时间无效。")
            return

        dialog = EventDialog(event_date, parent=self, edit_event=event)
        while dialog.exec_() == QDialog.Accepted:
            try:
                fields = dialog.get_data()
                updated = EventService.update_event(event_id, **fields)
            except (TypeError, ValueError) as exc:
                QMessageBox.warning(self, "保存失败", str(exc))
                continue
            except (OSError, sqlite3.Error):
                _log.warning("事项保存失败")
                QMessageBox.critical(self, "保存失败", "无法保存事项，请稍后重试。")
                continue

            if not updated:
                QMessageBox.warning(self, "保存失败", "事项不存在或未更新。")
                return
            self._on_data_changed()
            break

    def _show_event_detail(self, event):
        dialog = EventDetailDialog(event, self)
        self._detail_dialogs.add(dialog)
        dialog.finished.connect(
            lambda _result=0, current=dialog: self._detail_dialogs.discard(current)
        )
        dialog.show()
        dialog.raise_()

    def _save_floating_geometry(self, geometry):
        if (
            not isinstance(geometry, (list, tuple))
            or len(geometry) != 4
            or any(
                isinstance(value, bool) or not isinstance(value, int)
                for value in geometry
            )
            or geometry[2] <= 0
            or geometry[3] <= 0
        ):
            return
        current = cfg_mod.load_config()
        current["floating_window_geometry"] = list(geometry)
        try:
            cfg_mod.save_config(current)
        except (OSError, TypeError, ValueError) as exc:
            _log.warning("悬浮窗位置保存失败：%s", exc)

    def _set_floating_visibility(self, visible):
        if visible:
            self._floating_window.refresh()
            self._floating_window.show()
            self._floating_window.raise_()
            self._floating_window.activateWindow()
        else:
            self._floating_window.hide()
        self._sync_floating_action()

    def _sync_floating_action(self):
        if not hasattr(self, '_floating_action'):
            return
        visible = self._floating_window.isVisible()
        self._floating_action.setChecked(visible)
        self._floating_action.setText(
            '隐藏今日悬浮窗' if visible else '显示今日悬浮窗'
        )

    def _shutdown_auxiliary_windows(self):
        if self._shutting_down:
            return
        self._shutting_down = True
        self._alert_timer.stop()
        self._sync_controller.shutdown()
        self._floating_window.shutdown()
        for dialog in tuple(self._detail_dialogs):
            dialog.close()
        self._detail_dialogs.clear()

    def _get_event_counts(self) -> dict:
        focused = self._calendar.get_selected_date()
        first = date(focused.year, focused.month, 1)
        last = date(focused.year, focused.month, calendar.monthrange(focused.year, focused.month)[1])
        return EventService.get_event_counts(first, last)

    def _load_sample_data_if_empty(self):
        EventService.load_sample_data_if_empty()

    def closeEvent(self, event):
        tray_enabled = cfg_mod.load_config().get('close_to_tray', False)
        if tray_enabled and hasattr(self, '_tray_icon'):
            self.hide()
            self._tray_icon.showMessage('Clender', '应用已最小化到系统托盘', QSystemTrayIcon.Information, 2000)
            event.ignore()
        else:
            if hasattr(self, '_tray_icon'):
                self._tray_icon.hide()
            self._shutdown_auxiliary_windows()
            QApplication.quit()
            event.accept()
