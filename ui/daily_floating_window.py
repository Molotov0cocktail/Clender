"""Frameless desktop agenda with runtime pinning and compact AI input."""
from datetime import date, datetime, time, timedelta

from PyQt5.QtCore import QEvent, QPoint, QRect, Qt, QTimer, pyqtSignal
from PyQt5.QtGui import QColor
from PyQt5.QtWidgets import (
    QAbstractItemView,
    QApplication,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QListWidget,
    QListWidgetItem,
    QPushButton,
    QScrollArea,
    QMenu,
    QVBoxLayout,
    QWidget,
)

import config as cfg_mod
import theme_manager
from event_service import EventService
from calendar_logic import build_day_blocks, build_week_blocks
from ui.canvas import DayCanvas, WeekCanvas
from floating_window_logic import (
    FloatingEventState,
    FloatingWindowSettings,
    classify_event_states,
    day_window,
    format_event_time,
)
from logger import get_logger
from typography import (
    TypographyScale,
    floating_scale_from_config,
    qfont_for,
)


_log = get_logger(__name__)

EVENT_STATE_ROLE = Qt.UserRole + 1
EVENT_BASE_TOOLTIP_ROLE = Qt.UserRole + 2


class DailyFloatingWindow(QWidget):
    """Top-level daily agenda window controlled by MainWindow."""

    event_activated = pyqtSignal(int)
    event_edit_requested = pyqtSignal(int)
    ai_message_submitted = pyqtSignal(str)
    geometry_changed = pyqtSignal(object)
    visibility_change_requested = pyqtSignal(bool)

    _EDGE_NONE = 0
    _EDGE_LEFT = 1
    _EDGE_TOP = 2
    _EDGE_RIGHT = 4
    _EDGE_BOTTOM = 8
    _RESIZE_MARGIN = 6
    _AI_STATUS_LIMIT = 120

    def __init__(self):
        flags = Qt.Tool | Qt.FramelessWindowHint | Qt.WindowStaysOnBottomHint
        super().__init__(None, flags)
        self.setWindowTitle("")
        self.setMinimumSize(260, 220)
        self.resize(340, 420)
        self._settings = FloatingWindowSettings.from_config({})
        self._font_scale = floating_scale_from_config(cfg_mod.load_config())
        self._displayed_date = None
        self._displayed_events = []
        self._restoring_geometry = False
        self._allow_close = False
        self._pinned = False
        self._view_mode = "events"
        self._canvas = None
        self._pending_preview_id = None
        self._preview_timer = QTimer(self)
        self._preview_timer.setSingleShot(True)
        self._preview_timer.timeout.connect(self._emit_preview)

        self._drag_press_global = None
        self._drag_window_pos = None
        self._drag_started = False
        self._resize_edges = self._EDGE_NONE
        self._resize_press_global = None
        self._resize_start_geometry = None
        self._resize_screen_rect = None

        self._init_ui()

        self._geometry_timer = QTimer(self)
        self._geometry_timer.setSingleShot(True)
        self._geometry_timer.setInterval(250)
        self._geometry_timer.timeout.connect(self._emit_geometry)

        self._date_timer = QTimer(self)
        self._date_timer.setInterval(60_000)
        self._date_timer.timeout.connect(self._check_date)
        self._date_timer.start()
        self.apply_font_scale(self._font_scale)
        self.apply_theme()

    def _init_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(10, 10, 10, 10)
        layout.setSpacing(6)

        view_row = QHBoxLayout()
        self._drag_handle = QLabel("⠿")
        self._drag_handle.setToolTip("拖动悬浮窗")
        view_row.addWidget(self._drag_handle)
        self._view_buttons = {}
        for mode, label in (("events", "事件"), ("day", "日"), ("week", "周")):
            button = QPushButton(label)
            button.setObjectName("floatingViewButton")
            button.clicked.connect(lambda _checked=False, value=mode: self.set_view_mode(value))
            self._view_buttons[mode] = button
            view_row.addWidget(button)
        layout.addLayout(view_row)

        self._event_list = QListWidget()
        self._event_list.setSelectionMode(QAbstractItemView.SingleSelection)
        self._event_list.itemClicked.connect(self._on_item_clicked)
        self._event_list.itemDoubleClicked.connect(self._on_item_double_clicked)
        layout.addWidget(self._event_list, 1)

        self._timeline_scroll = QScrollArea()
        self._timeline_scroll.setWidgetResizable(True)
        self._timeline_scroll.hide()
        self._week_header = QScrollArea()
        self._week_header.setWidgetResizable(True)
        self._week_header.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        self._week_header.setVerticalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        self._week_header.hide()
        layout.addWidget(self._week_header)
        layout.addWidget(self._timeline_scroll, 1)
        self._timeline_scroll.horizontalScrollBar().valueChanged.connect(
            self._week_header.horizontalScrollBar().setValue
        )

        self._empty_label = QLabel("当前时间段暂无日程")
        self._empty_label.setAlignment(Qt.AlignCenter)
        layout.addWidget(self._empty_label)

        bottom_row = QHBoxLayout()
        bottom_row.setSpacing(6)
        self._ai_input = QLineEdit()
        self._ai_input.setObjectName("floatingAiInput")
        self._ai_input.setPlaceholderText("输入 AI 日程指令，按 Enter 发送")
        self._ai_input.returnPressed.connect(self._submit_ai_message)
        bottom_row.addWidget(self._ai_input, 1)

        self._ai_status = QLabel("")
        self._ai_status.setAlignment(Qt.AlignRight | Qt.AlignVCenter)
        bottom_row.addWidget(self._ai_status)

        self._btn_pin = QPushButton("📌")
        self._btn_pin.setCheckable(True)
        self._btn_pin.setToolTip("置顶悬浮窗")
        self._btn_pin.toggled.connect(self._set_pinned)
        bottom_row.addWidget(self._btn_pin)
        layout.addLayout(bottom_row)

        self.setMouseTracking(True)
        for watched in (
            self,
            self._event_list.viewport(),
            self._empty_label,
            self._ai_status,
            self._drag_handle,
        ):
            watched.setMouseTracking(True)
            watched.installEventFilter(self)

    def apply_settings(self, settings: FloatingWindowSettings):
        if not isinstance(settings, FloatingWindowSettings):
            raise TypeError("settings 必须是 FloatingWindowSettings")
        self._settings = settings
        self.apply_font_scale(floating_scale_from_config(cfg_mod.load_config()))
        self.setWindowOpacity(settings.opacity_percent / 100)
        if settings.geometry is not None:
            self._restore_geometry(settings.geometry)
        if settings.enabled:
            self.refresh(date.today())
            self.show()
        else:
            self.hide()

    def apply_font_scale(self, scale: TypographyScale):
        """Apply the independent floating-window typography scale."""
        if not isinstance(scale, TypographyScale):
            raise TypeError("scale 必须是 TypographyScale")
        self._font_scale = scale
        self.setFont(qfont_for(scale, "body"))
        self._event_list.setFont(qfont_for(scale, "body"))
        self._empty_label.setFont(qfont_for(scale, "secondary"))
        self._ai_input.setFont(qfont_for(scale, "control"))
        self._ai_input.setStyleSheet(
            "QLineEdit#floatingAiInput { "
            f"font-size: {scale.control_px}px;"
            " }"
        )
        self._ai_status.setFont(qfont_for(scale, "caption"))
        self._btn_pin.setFont(qfont_for(scale, "control"))
        self._drag_handle.setFont(qfont_for(scale, "control"))
        for button in self._view_buttons.values():
            button.setFont(qfont_for(scale, "control"))
        if self._canvas is not None:
            self._canvas.setFont(qfont_for(scale, "body"))
            self._canvas.update()
        if self._view_mode == "week" and self._displayed_date is not None:
            self._render_timeline(self._displayed_date)
        self._apply_event_states(datetime.now())
        self.updateGeometry()

    def refresh(self, target_date=None):
        target = target_date or date.today()
        if not isinstance(target, date) or isinstance(target, datetime):
            raise TypeError("target_date 必须是 date")
        self._displayed_date = target
        self._preview_timer.stop()
        if self._view_mode == "week":
            start = datetime.combine(target - timedelta(days=target.weekday()), time.min)
            end = start + timedelta(days=7)
        elif self._view_mode == "day":
            start = datetime.combine(target, time.min)
            end = start + timedelta(days=1)
        else:
            start, end = day_window(target, self._settings)
        events = EventService.get_events_overlapping_range(start, end)

        self._event_list.clear()
        self._displayed_events = []
        for event in events:
            try:
                time_label = format_event_time(event)
                event_id = event.id
                title = event.title
                description = event.description
                if (
                    isinstance(event_id, bool)
                    or not isinstance(event_id, int)
                    or event_id <= 0
                ):
                    raise ValueError("事件 ID 无效")
            except (KeyError, TypeError, ValueError, AttributeError):
                safe_id = event.get("id") if hasattr(event, "get") else None
                _log.warning("跳过字段无效的悬浮窗事件，id=%s", safe_id)
                continue
            item = QListWidgetItem(f"{time_label}  {title}")
            item.setData(Qt.UserRole, event_id)
            base_tooltip = description or title
            item.setData(EVENT_BASE_TOOLTIP_ROLE, base_tooltip)
            item.setToolTip(base_tooltip)
            self._event_list.addItem(item)
            self._displayed_events.append(event)
        self._empty_label.setVisible(self._event_list.count() == 0)
        self._apply_event_states(datetime.now())
        self._render_timeline(target)

    def set_view_mode(self, mode: str) -> None:
        """Switch the session-only agenda, day or current-week presentation."""
        if mode not in self._view_buttons:
            raise ValueError("未知悬浮视图")
        self._view_mode = mode
        self.refresh(self._displayed_date or date.today())

    def _render_timeline(self, target: date) -> None:
        is_timeline = self._view_mode != "events"
        self._event_list.setVisible(not is_timeline)
        self._timeline_scroll.setVisible(is_timeline)
        self._week_header.setVisible(self._view_mode == "week")
        for mode, button in self._view_buttons.items():
            button.setProperty("activeView", mode == self._view_mode)
            button.style().unpolish(button)
            button.style().polish(button)
        if not is_timeline:
            return
        per_hour = max(36, self._font_scale.body_px * 3)
        if self._view_mode == "week":
            week_start = target - timedelta(days=target.weekday())
            blocks = build_week_blocks(self._displayed_events, week_start, per_hour)
            canvas_type = WeekCanvas
        else:
            blocks = build_day_blocks(self._displayed_events, per_hour, target_date=target)
            canvas_type = DayCanvas
        content = QWidget()
        if self._view_mode == "week":
            content.setMinimumWidth(max(360, self._font_scale.body_px * 28))
        column = QVBoxLayout(content)
        column.setContentsMargins(0, 0, 0, 0)
        if self._view_mode == "week":
            header_widget = QWidget()
            header_widget.setMinimumWidth(content.minimumWidth())
            header = QHBoxLayout(header_widget)
            header.setContentsMargins(0, 0, 0, 0)
            header.setSpacing(0)
            header.addSpacing(int(WeekCanvas.timeline_gutter_for_metrics(self._event_list.fontMetrics())))
            for offset in range(7):
                day = week_start + timedelta(days=offset)
                label = QLabel(f'{"一二三四五六日"[offset]}\n{day.day}')
                label.setFont(qfont_for(self._font_scale, "caption"))
                label.setAlignment(Qt.AlignCenter)
                header.addWidget(label, 1)
            self._week_header.setFixedHeight(self._event_list.fontMetrics().lineSpacing() * 2 + 10)
            self._week_header.setViewportMargins(0, 0, self._timeline_scroll.verticalScrollBar().sizeHint().width(), 0)
            self._week_header.setWidget(header_widget)
        self._canvas = canvas_type(blocks, per_hour * 24, per_hour, theme_manager.get_current_theme())
        self._canvas.setFont(qfont_for(self._font_scale, "body"))
        self._canvas.event_activated.connect(lambda ids: self._activate_canvas_ids(ids, False))
        self._canvas.event_edit_requested.connect(lambda ids: self._activate_canvas_ids(ids, True))
        column.addWidget(self._canvas)
        self._timeline_scroll.setWidget(content)
        QTimer.singleShot(0, self._position_timeline)

    def _position_timeline(self):
        if self._canvas is None or self._view_mode == "events":
            return
        self._timeline_scroll.verticalScrollBar().setValue(max(0, (datetime.now().hour - 1) * self._canvas.per_hour))
        if self._view_mode == "week":
            bar = self._timeline_scroll.horizontalScrollBar()
            day_index = (self._displayed_date or date.today()).weekday()
            bar.setValue(round(bar.maximum() * day_index / 6))

    def _activate_canvas_ids(self, event_ids, editing: bool) -> None:
        ids = tuple(i for i in event_ids if isinstance(i, int) and not isinstance(i, bool) and i > 0)
        signal = self.event_edit_requested if editing else self.event_activated
        if len(ids) == 1:
            signal.emit(ids[0])
        elif ids:
            menu = QMenu(self)
            menu.setAttribute(Qt.WA_DeleteOnClose)
            titles = {event.id: event.title for event in self._displayed_events}
            for event_id in ids:
                action = menu.addAction(titles.get(event_id, f"事项 {event_id}"))
                action.triggered.connect(lambda _checked=False, value=event_id: signal.emit(value))
            menu.popup(self.mapToGlobal(self.rect().center()))

    def _on_item_clicked(self, item):
        self._pending_preview_id = item.data(Qt.UserRole)
        self._preview_timer.start(QApplication.doubleClickInterval())
        self._clear_item_selection(item)

    def _emit_preview(self):
        event_id = self._pending_preview_id
        self._pending_preview_id = None
        if isinstance(event_id, int) and not isinstance(event_id, bool) and event_id > 0:
            self.event_activated.emit(event_id)

    def _on_item_double_clicked(self, item):
        self._preview_timer.stop()
        self._pending_preview_id = None
        event_id = item.data(Qt.UserRole)
        if isinstance(event_id, int) and not isinstance(event_id, bool) and event_id > 0:
            self.event_edit_requested.emit(event_id)

    def _clear_item_selection(self, _item):
        self._event_list.clearSelection()
        self._event_list.setCurrentItem(None)

    def _submit_ai_message(self):
        text = self._ai_input.text().strip()
        if text:
            self.ai_message_submitted.emit(text)

    def set_ai_request_status(self, status: str):
        """Render a bounded request state without displaying model output."""
        value = status if isinstance(status, str) else ""
        if value == "working":
            self._ai_input.clear()
            self._ai_input.setEnabled(False)
            self._ai_status.setText("处理中…")
        elif value == "changed":
            self._ai_input.setEnabled(True)
            self._ai_status.setText("已修改日程")
        elif value == "unchanged":
            self._ai_input.setEnabled(True)
            self._ai_status.setText("未修改日程")
        elif value.startswith("error:"):
            self._ai_input.setEnabled(True)
            detail = value[len("error:"):].strip()[:self._AI_STATUS_LIMIT]
            self._ai_status.setText(detail or "AI 请求失败")
        else:
            self._ai_input.setEnabled(True)
            self._ai_status.clear()

    def _apply_event_states(self, now: datetime):
        states = classify_event_states(self._displayed_events, now)
        theme = theme_manager.get_current_theme()
        for index in range(self._event_list.count()):
            item = self._event_list.item(index)
            event_id = item.data(Qt.UserRole)
            state = states.get(event_id, FloatingEventState.NORMAL)
            base_tooltip = item.data(EVENT_BASE_TOOLTIP_ROLE) or ""
            item.setData(EVENT_STATE_ROLE, state.value)
            font = qfont_for(self._font_scale, "body")
            if state == FloatingEventState.CURRENT:
                font.setBold(True)
                item.setBackground(QColor(theme["primary"]))
                item.setForeground(QColor(theme["primary_text"]))
                item.setToolTip(f"正在进行 · {base_tooltip}")
            elif state == FloatingEventState.NEXT:
                font.setBold(True)
                item.setBackground(QColor(theme["header_bg"]))
                item.setForeground(QColor(theme["warning_text"]))
                item.setToolTip(f"接下来 · {base_tooltip}")
            else:
                item.setBackground(QColor(theme["list_bg"]))
                item.setForeground(QColor(theme["text_color"]))
                item.setToolTip(base_tooltip)
            item.setFont(font)

    def _check_date(self):
        if not (self._settings.enabled or self.isVisible()):
            return
        today = date.today()
        if self._displayed_date != today:
            self.refresh(today)
        else:
            self._apply_event_states(datetime.now())

    def _set_pinned(self, pinned):
        pinned = bool(pinned)
        geometry = QRect(self.geometry())
        opacity = self.windowOpacity()
        was_visible = self.isVisible()

        flags = self.windowFlags()
        flags &= ~(Qt.WindowStaysOnTopHint | Qt.WindowStaysOnBottomHint)
        flags |= Qt.WindowStaysOnTopHint if pinned else Qt.WindowStaysOnBottomHint

        self._restoring_geometry = True
        self.setWindowFlags(flags)
        if was_visible:
            self.show()
        self.setGeometry(geometry)
        self.setWindowOpacity(opacity)
        self._restoring_geometry = False
        self._geometry_timer.stop()

        self._pinned = pinned
        previous = self._btn_pin.blockSignals(True)
        self._btn_pin.setChecked(pinned)
        self._btn_pin.blockSignals(previous)
        self._btn_pin.setToolTip("取消置顶" if pinned else "置顶悬浮窗")
        if pinned and was_visible:
            self.raise_()

    def _hit_test_edges(self, global_pos: QPoint) -> int:
        frame = self.frameGeometry()
        margin = self._RESIZE_MARGIN
        edges = self._EDGE_NONE
        if abs(global_pos.x() - frame.left()) <= margin:
            edges |= self._EDGE_LEFT
        elif abs(global_pos.x() - frame.right()) <= margin:
            edges |= self._EDGE_RIGHT
        if abs(global_pos.y() - frame.top()) <= margin:
            edges |= self._EDGE_TOP
        elif abs(global_pos.y() - frame.bottom()) <= margin:
            edges |= self._EDGE_BOTTOM
        return edges

    def _cursor_for_edges(self, edges):
        if edges in (
            self._EDGE_LEFT | self._EDGE_TOP,
            self._EDGE_RIGHT | self._EDGE_BOTTOM,
        ):
            return Qt.SizeFDiagCursor
        if edges in (
            self._EDGE_RIGHT | self._EDGE_TOP,
            self._EDGE_LEFT | self._EDGE_BOTTOM,
        ):
            return Qt.SizeBDiagCursor
        if edges & (self._EDGE_LEFT | self._EDGE_RIGHT):
            return Qt.SizeHorCursor
        if edges & (self._EDGE_TOP | self._EDGE_BOTTOM):
            return Qt.SizeVerCursor
        return Qt.ArrowCursor

    def _begin_resize(self, global_pos, edges):
        self._resize_edges = edges
        self._resize_press_global = QPoint(global_pos)
        self._resize_start_geometry = QRect(self.geometry())
        screen = QApplication.screenAt(global_pos)
        self._resize_screen_rect = screen.availableGeometry() if screen else None

    def _perform_resize(self, global_pos):
        delta = global_pos - self._resize_press_global
        rect = QRect(self._resize_start_geometry)
        if self._resize_edges & self._EDGE_LEFT:
            rect.setLeft(rect.left() + delta.x())
        if self._resize_edges & self._EDGE_RIGHT:
            rect.setRight(rect.right() + delta.x())
        if self._resize_edges & self._EDGE_TOP:
            rect.setTop(rect.top() + delta.y())
        if self._resize_edges & self._EDGE_BOTTOM:
            rect.setBottom(rect.bottom() + delta.y())

        minimum_width = self.minimumWidth()
        minimum_height = self.minimumHeight()
        if rect.width() < minimum_width:
            if self._resize_edges & self._EDGE_LEFT:
                rect.setLeft(rect.right() - minimum_width + 1)
            else:
                rect.setRight(rect.left() + minimum_width - 1)
        if rect.height() < minimum_height:
            if self._resize_edges & self._EDGE_TOP:
                rect.setTop(rect.bottom() - minimum_height + 1)
            else:
                rect.setBottom(rect.top() + minimum_height - 1)

        available = self._resize_screen_rect
        if available is not None:
            if self._resize_edges & self._EDGE_LEFT:
                rect.setLeft(max(rect.left(), available.left()))
            if self._resize_edges & self._EDGE_RIGHT:
                rect.setRight(min(rect.right(), available.right()))
            if self._resize_edges & self._EDGE_TOP:
                rect.setTop(max(rect.top(), available.top()))
            if self._resize_edges & self._EDGE_BOTTOM:
                rect.setBottom(min(rect.bottom(), available.bottom()))
        self.setGeometry(rect)

    def _clear_pointer_gesture(self):
        self._drag_press_global = None
        self._drag_window_pos = None
        self._drag_started = False
        self._resize_edges = self._EDGE_NONE
        self._resize_press_global = None
        self._resize_start_geometry = None
        self._resize_screen_rect = None

    def eventFilter(self, watched, event):
        event_type = event.type()
        if (
            event_type == QEvent.MouseButtonDblClick
            and watched is self._event_list.viewport()
            and event.button() == Qt.LeftButton
        ):
            item = self._event_list.itemAt(event.pos())
            if item is not None:
                self._on_item_double_clicked(item)
                self._clear_pointer_gesture()
                return True
        if event_type == QEvent.MouseButtonPress and event.button() == Qt.LeftButton:
            edges = self._hit_test_edges(event.globalPos())
            if edges != self._EDGE_NONE:
                self._begin_resize(event.globalPos(), edges)
                return True
            self._drag_press_global = QPoint(event.globalPos())
            self._drag_window_pos = QPoint(self.pos())
            self._drag_started = False
            return False

        if event_type == QEvent.MouseMove:
            if self._resize_edges != self._EDGE_NONE and event.buttons() & Qt.LeftButton:
                self._perform_resize(event.globalPos())
                return True
            if self._drag_press_global is not None and event.buttons() & Qt.LeftButton:
                delta = event.globalPos() - self._drag_press_global
                if not self._drag_started:
                    if delta.manhattanLength() < QApplication.startDragDistance():
                        return False
                    self._drag_started = True
                    self._preview_timer.stop()
                self.move(self._drag_window_pos + delta)
                return True
            if watched is self:
                self.setCursor(self._cursor_for_edges(self._hit_test_edges(event.globalPos())))

        if event_type == QEvent.MouseButtonRelease and event.button() == Qt.LeftButton:
            handled = self._drag_started or self._resize_edges != self._EDGE_NONE
            self._clear_pointer_gesture()
            return handled

        if event_type == QEvent.Leave and watched is self:
            self.unsetCursor()
        return super().eventFilter(watched, event)

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

    def hideEvent(self, event):
        self._preview_timer.stop()
        self._pending_preview_id = None
        super().hideEvent(event)

    def shutdown(self):
        """Stop timers and allow the owning application to destroy the window."""
        self._allow_close = True
        self._date_timer.stop()
        self._geometry_timer.stop()
        self._preview_timer.stop()
        self.close()

    def apply_theme(self):
        theme = theme_manager.get_current_theme()
        self.setStyleSheet(f'''
            QWidget {{ background: {theme["frame_bg"]}; color: {theme["text_color"]}; }}
            QListWidget {{
                background: {theme["list_bg"]}; color: {theme["text_color"]};
                border: 1px solid {theme["border_soft"]}; border-radius: 8px;
                padding: 2px;
            }}
            QListWidget::item {{ padding: 7px; border-radius: 6px; }}
            QListWidget::item:hover {{ background: {theme["list_item_hover"]}; }}
            QLineEdit {{
                background: {theme["input_bg"]}; color: {theme["text_color"]};
                border: 1px solid {theme["border_soft"]}; border-radius: 6px;
                padding: 6px;
            }}
            QLineEdit:focus {{ border: 1px solid {theme["primary"]}; }}
            QPushButton {{
                background: {theme["header_bg"]}; color: {theme["text_color"]};
                border: 1px solid {theme["border_soft"]}; border-radius: 6px;
                padding: 5px;
            }}
            QPushButton:hover {{
                background: {theme["surface_raised"]};
                border-color: {theme["input_border"]};
            }}
            QPushButton:checked {{
                background: {theme["primary"]}; color: {theme["primary_text"]};
            }}
            QPushButton[activeView="true"] {{
                background: {theme["primary"]}; color: {theme["primary_text"]};
                border: 1px solid {theme["primary"]};
            }}
            QScrollArea {{ border: 1px solid {theme["border_soft"]}; border-radius: 8px; }}
        ''')
        self.apply_font_scale(self._font_scale)
        self._apply_event_states(datetime.now())
        if self._canvas is not None:
            self._canvas.theme = theme
            self._canvas.update()
