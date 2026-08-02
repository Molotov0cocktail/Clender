"""Shared read-only event detail dialog."""
from PyQt5.QtCore import Qt
from PyQt5.QtWidgets import (
    QDialog,
    QDialogButtonBox,
    QFormLayout,
    QLabel,
    QTextBrowser,
    QVBoxLayout,
)

import theme_manager
from models import Event, EventType


class EventDetailDialog(QDialog):
    """Display an Event without exposing edit or save controls."""

    def __init__(self, event: Event, parent=None):
        super().__init__(parent)
        if not isinstance(event, Event):
            raise TypeError("event 必须是 Event")
        self._event = event
        self.setWindowTitle("事项详情")
        self.setMinimumSize(420, 300)
        self._init_ui()
        self.apply_theme()

    def _init_ui(self):
        layout = QVBoxLayout(self)
        form = QFormLayout()
        form.setLabelAlignment(Qt.AlignRight | Qt.AlignTop)

        type_text = "提醒" if self._event.event_type == EventType.REMINDER else "时间段"
        self._lbl_type = QLabel(type_text)
        self._lbl_title = QLabel(self._event.title)
        self._lbl_title.setWordWrap(True)

        if self._event.event_type == EventType.TIMESPAN:
            time_text = f"{self._event.start_time}\n至 {self._event.end_time or '未设置'}"
            duration_text = "不适用"
        else:
            time_text = self._event.start_time
            duration_text = f"{self._event.estimated_duration} 分钟"
        self._lbl_time = QLabel(time_text)
        self._lbl_duration = QLabel(duration_text)

        self._description = QTextBrowser()
        self._description.setReadOnly(True)
        self._description.setPlainText(self._event.description or "无")
        self._description.setMinimumHeight(100)

        form.addRow("类型：", self._lbl_type)
        form.addRow("标题：", self._lbl_title)
        form.addRow("时间：", self._lbl_time)
        form.addRow("预计时长：", self._lbl_duration)
        form.addRow("描述：", self._description)
        layout.addLayout(form)

        buttons = QDialogButtonBox(QDialogButtonBox.Close)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

    def apply_theme(self):
        theme = theme_manager.get_current_theme()
        self.setStyleSheet(f'''
            QDialog {{ background: {theme["frame_bg"]}; color: {theme["text_color"]}; }}
            QLabel {{ color: {theme["text_color"]}; }}
            QTextBrowser {{
                background: {theme["input_bg"]}; color: {theme["text_color"]};
                border: 1px solid {theme["input_border"]}; border-radius: 4px;
            }}
        ''')
