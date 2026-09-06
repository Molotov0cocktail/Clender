"""事项管理面板 - 显示事件列表 + 添加/编辑/删除操作"""
from datetime import datetime, date
import sqlite3

from PyQt5.QtWidgets import (QWidget, QVBoxLayout, QHBoxLayout, QPushButton,
                             QLabel, QListWidget, QListWidgetItem, QFrame,
                             QMessageBox)
from PyQt5.QtCore import Qt, pyqtSignal
from PyQt5.QtGui import QColor

from event_service import EventService
import theme_manager
from typography import app_scale_from_config
import config as cfg_mod
from ui.event_dialog import EventDialog
from logger import get_logger

_log = get_logger(__name__)


class EventManager(QFrame):
    """事项管理面板：显示事件列表 + 添加/删除操作"""

    data_changed = pyqtSignal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setFrameStyle(QFrame.StyledPanel | QFrame.Raised)
        self._current_date = date.today()
        self._init_ui()

    def _init_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(10, 10, 10, 10)

        # ---- 标题 ----
        self._title_label = QLabel('📋 日程安排')
        self._title_label.setObjectName('eventManagerTitle')
        layout.addWidget(self._title_label)

        # ---- 选中日期显示 ----
        self._lbl_date = QLabel()
        self._lbl_date.setObjectName('eventManagerDate')
        layout.addWidget(self._lbl_date)

        # ---- 事件列表 ----
        self._list_widget = QListWidget()
        self._list_widget.setSpacing(3)
        self._list_widget.setWordWrap(True)
        self._list_widget.setToolTip('双击事项直接编辑')
        self._list_widget.itemDoubleClicked.connect(lambda item: self._on_edit_clicked())
        layout.addWidget(self._list_widget, 1)

        # ---- 操作按钮 ----
        btn_layout = QHBoxLayout()
        btn_layout.setSpacing(8)

        self._btn_add = QPushButton('＋ 添加事项')
        self._btn_add.clicked.connect(self._on_add_clicked)

        self._btn_edit = QPushButton('✏️ 编辑选中')
        self._btn_edit.clicked.connect(self._on_edit_clicked)

        self._btn_delete = QPushButton('🗑 删除选中')
        self._btn_delete.clicked.connect(self._on_delete_clicked)

        btn_layout.addWidget(self._btn_add)
        btn_layout.addWidget(self._btn_edit)
        btn_layout.addWidget(self._btn_delete)
        btn_layout.addStretch()

        layout.addLayout(btn_layout)
        self.apply_theme()

    # ---------- 公共方法 ----------
    def set_date(self, d: date):
        """设置当前查看的日期并刷新列表"""
        self._current_date = d
        weekdays = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
        self._lbl_date.setText(f'{d.year}年{d.month}月{d.day}日  {weekdays[d.weekday()]}')
        self.refresh()

    def refresh(self):
        """从数据库重新加载事件列表"""
        t = theme_manager.get_current_theme()
        self._list_widget.clear()
        events = EventService.get_events_by_date(self._current_date)
        if not events:
            item = QListWidgetItem('  暂无日程安排')
            item.setFlags(item.flags() & ~Qt.ItemIsSelectable)
            item.setForeground(QColor(t["muted_color"]))
            self._list_widget.addItem(item)
            return

        for ev in events:
            ev_id = ev['id']
            ev_type = ev['event_type']
            title = ev['title']
            start_time = ev['start_time']
            end_time = ev['end_time']

            if ev_type == 'reminder':
                try:
                    dt = datetime.strptime(start_time, '%Y-%m-%d %H:%M')
                    time_str = dt.strftime('%H:%M')
                except ValueError:
                    time_str = start_time
                display_text = f'🔔 {time_str}  {title}'
            else:
                try:
                    dt_start = datetime.strptime(start_time, '%Y-%m-%d %H:%M')
                    dt_end = datetime.strptime(end_time, '%Y-%m-%d %H:%M')
                    fmt = '%m-%d %H:%M' if dt_start.date() != dt_end.date() else '%H:%M'
                    time_str = f'{dt_start.strftime(fmt)} - {dt_end.strftime(fmt)}'
                except (ValueError, TypeError):
                    time_str = f'{start_time} - {end_time}'
                display_text = f'📅 {time_str}  {title}'

            item = QListWidgetItem(display_text)
            item.setData(Qt.UserRole, ev_id)
            if ev_type == 'reminder':
                item.setForeground(QColor(t["event_reminder_color"]))
            else:
                item.setForeground(QColor(t["event_timespan_color"]))
            self._list_widget.addItem(item)

    # ---------- 事件处理 ----------
    def _on_add_clicked(self):
        dialog = EventDialog(self._current_date, parent=self)
        while dialog.exec_() == EventDialog.Accepted:
            try:
                EventService.add_event(**dialog.get_data())
            except (ValueError, TypeError, sqlite3.Error, OSError) as exc:
                self._show_save_error(exc)
                continue
            self.refresh()
            self.data_changed.emit()
            break

    def _show_save_error(self, exc: Exception):
        """Keep database and validation errors inside the Qt slot boundary."""
        message = str(exc) if isinstance(exc, (ValueError, TypeError)) else '保存失败，请稍后重试。'
        QMessageBox.warning(self, '无法保存日程', message)

    def apply_theme(self):
        """动态应用当前主题样式"""
        t = theme_manager.get_current_theme()
        scale = app_scale_from_config(cfg_mod.load_config())
        self.setStyleSheet(f'''
            EventManager {{
                background-color: {t["frame_bg"]};
                border-radius: 8px;
            }}
        ''')
        self._title_label.setStyleSheet(
            f'font-size:{scale.section_title_px}px;font-weight:bold;'
            f'color:{t["title_color"]};padding:4px 0;'
        )
        self._lbl_date.setStyleSheet(
            f'font-size:{scale.secondary_px}px;color:{t["subtitle_color"]};'
            'padding:2px 0;'
        )
        self._list_widget.setStyleSheet(f"""
            QListWidget {{
                border: 1px solid {t["frame_border"]};
                border-radius: 6px;
                background-color: {t["list_bg"]};
                font-size: {scale.body_px}px;
                color: {t["text_color"]};
            }}
            QListWidget::item {{
                padding: 10px;
                border-radius: 6px;
            }}
            QListWidget::item:hover {{
                background-color: {t["list_item_hover"]};
            }}
        """)
        self._btn_add.setStyleSheet(f"""
            QPushButton {{
                background-color: {t["primary"]};
                color: {t["primary_text"]};
                border: none;
                border-radius: 6px;
                padding: 8px 16px;
                font-size: {scale.control_px}px;
                font-weight: bold;
            }}
            QPushButton:hover {{
                background-color: {t["primary_hover"]};
            }}
        """)
        self._btn_delete.setStyleSheet(f"""
            QPushButton {{
                background-color: {t["danger"]};
                color: {t["primary_text"]};
                border: none;
                border-radius: 6px;
                padding: 8px 16px;
                font-size: {scale.control_px}px;
                font-weight: bold;
            }}
            QPushButton:hover {{
                background-color: {t["danger_hover"]};
            }}
        """)

        self._btn_edit.setStyleSheet(f"""
            QPushButton {{
                background-color: {t["info"]};
                color: {t["primary_text"]};
                border: none;
                border-radius: 6px;
                padding: 8px 16px;
                font-size: {scale.control_px}px;
                font-weight: bold;
            }}
            QPushButton:hover {{
                background-color: {t["info_hover"]};
            }}
        """)

    def _on_edit_clicked(self):
        current_item = self._list_widget.currentItem()
        if current_item is None:
            QMessageBox.information(self, '提示', '请先选中要编辑的事项')
            return
        ev_id = current_item.data(Qt.UserRole)
        if ev_id is None:
            return
        try:
            ev = EventService.get_event_by_id(ev_id)
        except (ValueError, TypeError, sqlite3.Error, OSError) as exc:
            self._show_save_error(exc)
            return
        if not ev:
            return
        dialog = EventDialog(self._current_date, edit_event=ev, parent=self)
        while dialog.exec_() == EventDialog.Accepted:
            try:
                updated = EventService.update_event(ev_id, **dialog.get_data())
                if not updated:
                    QMessageBox.warning(self, '无法保存日程', '此事项已不存在，请刷新后重试。')
                    return
            except (ValueError, TypeError, sqlite3.Error, OSError) as exc:
                self._show_save_error(exc)
                continue
            self.refresh()
            self.data_changed.emit()
            break

    def _on_delete_clicked(self):
        current_item = self._list_widget.currentItem()
        if current_item is None:
            QMessageBox.information(self, '提示', '请先选中要删除的事项')
            return
        ev_id = current_item.data(Qt.UserRole)
        if ev_id is None:
            return
        ev = EventService.get_event_by_id(ev_id)
        if not ev:
            return

        reply = QMessageBox.question(
            self, '确认删除',
            f'确定要删除 "{ev["title"]}" 吗？',
            QMessageBox.Yes | QMessageBox.No,
            QMessageBox.No
        )
        if reply == QMessageBox.Yes:
            EventService.delete_event(ev_id)
            self.refresh()
            self.data_changed.emit()
