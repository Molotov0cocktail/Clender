"""事项管理面板 - 显示事件列表 + 添加/编辑/删除操作"""
from datetime import datetime, date

from PyQt5.QtWidgets import (QWidget, QVBoxLayout, QHBoxLayout, QPushButton,
                             QLabel, QListWidget, QListWidgetItem, QFrame,
                             QMessageBox)
from PyQt5.QtCore import Qt, pyqtSignal
from PyQt5.QtGui import QColor

from event_service import EventService
import theme_manager
from ui.event_dialog import EventDialog
from logger import get_logger

_log = get_logger(__name__)


class EventManager(QFrame):
    """事项管理面板：显示事件列表 + 添加/删除操作"""

    data_changed = pyqtSignal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setFrameStyle(QFrame.StyledPanel | QFrame.Raised)
        self.setStyleSheet('EventManager { background-color: #ffffff; border-radius: 8px; }')
        self._current_date = date.today()
        self._init_ui()

    def _init_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(10, 10, 10, 10)

        # ---- 标题 ----
        title = QLabel('📋 日程安排')
        title.setStyleSheet('font-size: 16px; font-weight: bold; color: #2d3436; padding: 4px 0;')
        layout.addWidget(title)

        # ---- 选中日期显示 ----
        self._lbl_date = QLabel()
        self._lbl_date.setStyleSheet('font-size: 13px; color: #636e72; padding: 2px 0;')
        layout.addWidget(self._lbl_date)

        # ---- 事件列表 ----
        self._list_widget = QListWidget()
        self._list_widget.setStyleSheet("""
            QListWidget {
                border: 1px solid #dfe6e9;
                border-radius: 6px;
                background-color: #f8f9fa;
                font-size: 13px;
            }
            QListWidget::item {
                padding: 8px;
                border-bottom: 1px solid #dfe6e9;
            }
            QListWidget::item:hover {
                background-color: #dfe6e9;
            }
        """)
        layout.addWidget(self._list_widget, 1)

        # ---- 操作按钮 ----
        btn_layout = QHBoxLayout()
        btn_layout.setSpacing(8)

        self._btn_add = QPushButton('＋ 添加事项')
        self._btn_add.setStyleSheet("""
            QPushButton {
                background-color: #6c5ce7;
                color: white;
                border: none;
                border-radius: 6px;
                padding: 8px 16px;
                font-size: 13px;
                font-weight: bold;
            }
            QPushButton:hover {
                background-color: #a29bfe;
            }
        """)
        self._btn_add.clicked.connect(self._on_add_clicked)

        self._btn_edit = QPushButton('✏️ 编辑选中')
        self._btn_edit.setStyleSheet("""
            QPushButton {
                background-color: #0984e3;
                color: white;
                border: none;
                border-radius: 6px;
                padding: 8px 16px;
                font-size: 13px;
                font-weight: bold;
            }
            QPushButton:hover {
                background-color: #74b9ff;
            }
        """)
        self._btn_edit.clicked.connect(self._on_edit_clicked)

        self._btn_delete = QPushButton('🗑 删除选中')
        self._btn_delete.setStyleSheet("""
            QPushButton {
                background-color: #d63031;
                color: white;
                border: none;
                border-radius: 6px;
                padding: 8px 16px;
                font-size: 13px;
                font-weight: bold;
            }
            QPushButton:hover {
                background-color: #ff7675;
            }
        """)
        self._btn_delete.clicked.connect(self._on_delete_clicked)

        btn_layout.addWidget(self._btn_add)
        btn_layout.addWidget(self._btn_edit)
        btn_layout.addWidget(self._btn_delete)
        btn_layout.addStretch()

        layout.addLayout(btn_layout)

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
                    time_str = f'{dt_start.strftime("%H:%M")} - {dt_end.strftime("%H:%M")}'
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
        if dialog.exec_() == EventDialog.Accepted:
            data = dialog.get_data()
            EventService.add_event(
                title=data['title'],
                event_type=data['event_type'],
                start_time=data['start_time'],
                end_time=data.get('end_time'),
                description=data.get('description', ''),
                estimated_duration=data.get('estimated_duration', 0),
            )
            self.refresh()
            self.data_changed.emit()

    def apply_theme(self):
        """动态应用当前主题样式"""
        t = theme_manager.get_current_theme()
        self.setStyleSheet(f'''
            EventManager {{
                background-color: {t["frame_bg"]};
                border-radius: 8px;
            }}
        ''')
        # 找到并更新子控件样式
        for i in range(self.layout().count()):
            w = self.layout().itemAt(i).widget()
            if isinstance(w, QLabel):
                text = w.text()
                if '日程安排' in text:
                    w.setStyleSheet(f'font-size: 16px; font-weight: bold; color: {t["title_color"]}; padding: 4px 0;')
                elif '年' in text:
                    w.setStyleSheet(f'font-size: 13px; color: {t["subtitle_color"]}; padding: 2px 0;')
        self._list_widget.setStyleSheet(f"""
            QListWidget {{
                border: 1px solid {t["frame_border"]};
                border-radius: 6px;
                background-color: {t["list_bg"]};
                font-size: 13px;
                color: {t["text_color"]};
            }}
            QListWidget::item {{
                padding: 8px;
                border-bottom: 1px solid {t["frame_border"]};
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
                font-size: 13px;
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
                font-size: 13px;
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
                font-size: 13px;
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
        ev = EventService.get_event_by_id(ev_id)
        if not ev:
            return
        dialog = EventDialog(self._current_date, edit_event=ev, parent=self)
        if dialog.exec_() == EventDialog.Accepted:
            data = dialog.get_data()
            EventService.update_event(ev_id,
                title=data['title'], start_time=data['start_time'],
                end_time=data.get('end_time'), description=data.get('description', ''),
                estimated_duration=data.get('estimated_duration', 0))
            self.refresh()
            self.data_changed.emit()

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