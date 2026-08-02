"""Always-on-top, read-only list of today's configured schedule window."""
from datetime import date

from PyQt5.QtCore import QRect, Qt, QTimer, pyqtSignal
from PyQt5.QtWidgets import (
    QApplication,
    QLabel,
    QListWidget,
    QListWidgetItem,
    QVBoxLayout,
    QWidget,
)

import theme_manager
from event_service import EventService
from floating_window_logic import (
    FloatingWindowSettings,
    day_window,
    format_event_time,
)
from logger import get_logger


_log = get_logger(__name__)


class DailyFloatingWindow(QWidget):
    """Top-level daily agenda window controlled by MainWindow."""

    event_activated = pyqtSignal(int)
    geometry_changed = pyqtSignal(object)
    visibility_change_requested = pyqtSignal(bool)

    def __init__(self):
        super().__init__(None, Qt.Tool | Qt.WindowStaysOnTopHint)
        self.setWindowTitle("今日事项")
        self.setMinimumSize(260, 220)
        self.resize(340, 420)
        self._settings = FloatingWindowSettings.from_config({})
        self._displayed_date = None
        self._restoring_geometry = False
        self._allow_close = False
        self._init_ui()

        self._geometry_timer = QTimer(self)
        self._geometry_timer.setSingleShot(True)
        self._geometry_timer.setInterval(250)
        self._geometry_timer.timeout.connect(self._emit_geometry)

        self._date_timer = QTimer(self)
        self._date_timer.setInterval(60_000)
        self._date_timer.timeout.connect(self._check_date)
        self._date_timer.start()
        self.apply_theme()

    def _init_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(10, 10, 10, 10)
        layout.setSpacing(6)
        self._date_label = QLabel()
        self._date_label.setObjectName("floatingDate")
        layout.addWidget(self._date_label)

        self._event_list = QListWidget()
        self._event_list.itemClicked.connect(self._on_item_clicked)
        layout.addWidget(self._event_list, 1)

        self._empty_label = QLabel("当前时间段暂无日程")
        self._empty_label.setAlignment(Qt.AlignCenter)
        layout.addWidget(self._empty_label)

    def apply_settings(self, settings: FloatingWindowSettings):
        if not isinstance(settings, FloatingWindowSettings):
            raise TypeError("settings 必须是 FloatingWindowSettings")
        self._settings = settings
        self.setWindowOpacity(settings.opacity_percent / 100)
        if settings.geometry is not None:
            self._restore_geometry(settings.geometry)
        if settings.enabled:
            self.refresh(date.today())
            self.show()
        else:
            self.hide()

    def refresh(self, target_date=None):
        target = target_date or date.today()
        if not isinstance(target, date):
            raise TypeError("target_date 必须是 date")
        self._displayed_date = target
        self._date_label.setText(f"{target:%Y年%m月%d日} 今日事项")
        start, end = day_window(target, self._settings)
        events = EventService.get_events_overlapping_range(start, end)

        self._event_list.clear()
        for event in events:
            try:
                time_label = format_event_time(event)
            except (KeyError, TypeError, ValueError, AttributeError):
                _log.warning("跳过时间字段无效的悬浮窗事件，id=%s", event.get("id"))
                continue
            item = QListWidgetItem(f"{time_label}  {event.title}")
            item.setData(Qt.UserRole, event.id)
            item.setToolTip(event.description or event.title)
            self._event_list.addItem(item)
        self._empty_label.setVisible(self._event_list.count() == 0)

    def _on_item_clicked(self, item):
        event_id = item.data(Qt.UserRole)
        if isinstance(event_id, int) and not isinstance(event_id, bool) and event_id > 0:
            self.event_activated.emit(event_id)

    def _check_date(self):
        today = date.today()
        if (
            (self._settings.enabled or self.isVisible())
            and self._displayed_date != today
        ):
            self.refresh(today)

    def _restore_geometry(self, geometry):
        requested = QRect(*geometry)
        screens = QApplication.screens()
        if not screens:
            self.setGeometry(requested)
            return

        available_rects = [screen.availableGeometry() for screen in screens]
        intersection_areas = [
            max(0, requested.intersected(available).width())
            * max(0, requested.intersected(available).height())
            for available in available_rects
        ]
        best_index = max(range(len(available_rects)), key=intersection_areas.__getitem__)
        target_screen = available_rects[best_index]
        was_visible = intersection_areas[best_index] > 0

        width = min(max(requested.width(), self.minimumWidth()), target_screen.width())
        height = min(max(requested.height(), self.minimumHeight()), target_screen.height())
        if was_visible:
            x = min(max(requested.x(), target_screen.left()), target_screen.right() - width + 1)
            y = min(max(requested.y(), target_screen.top()), target_screen.bottom() - height + 1)
        else:
            margin = 24
            x = max(target_screen.left(), target_screen.right() - width - margin + 1)
            y = min(target_screen.bottom() - height + 1, target_screen.top() + margin)

        self._restoring_geometry = True
        self.setGeometry(x, y, width, height)
        self._restoring_geometry = False
        self._geometry_timer.stop()

    def _schedule_geometry_save(self):
        if not self._restoring_geometry and not self._allow_close:
            self._geometry_timer.start()

    def _emit_geometry(self):
        geometry = self.geometry()
        self.geometry_changed.emit(
            (geometry.x(), geometry.y(), geometry.width(), geometry.height())
        )

    def moveEvent(self, event):
        super().moveEvent(event)
        self._schedule_geometry_save()

    def resizeEvent(self, event):
        super().resizeEvent(event)
        self._schedule_geometry_save()

    def closeEvent(self, event):
        if self._allow_close:
            event.accept()
            return
        self.hide()
        self.visibility_change_requested.emit(False)
        event.ignore()

    def shutdown(self):
        """Stop timers and allow the owning application to destroy the window."""
        self._allow_close = True
        self._date_timer.stop()
        self._geometry_timer.stop()
        self.close()

    def apply_theme(self):
        theme = theme_manager.get_current_theme()
        self.setStyleSheet(f'''
            QWidget {{ background: {theme["frame_bg"]}; color: {theme["text_color"]}; }}
            QLabel#floatingDate {{ color: {theme["title_color"]}; font-weight: bold; font-size: 15px; }}
            QListWidget {{
                background: {theme["list_bg"]}; color: {theme["text_color"]};
                border: 1px solid {theme["frame_border"]}; border-radius: 6px;
            }}
            QListWidget::item {{ padding: 7px; }}
            QListWidget::item:hover {{ background: {theme["list_item_hover"]}; }}
            QListWidget::item:selected {{
                background: {theme["primary"]}; color: {theme["primary_text"]};
            }}
        ''')
