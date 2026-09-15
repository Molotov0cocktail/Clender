"""事项编辑对话框 - 添加/编辑提醒和时间段事件"""
from datetime import datetime, date

from PyQt5.QtWidgets import (QDialog, QFormLayout, QLineEdit, QComboBox,
                             QDateTimeEdit, QDialogButtonBox, QMessageBox,
                             QTextEdit, QSpinBox, QCheckBox, QLabel)
from PyQt5.QtCore import QDate, QTime

import config as cfg_mod
import theme_manager
from logger import get_logger
from event_service import EventService
from typography import app_scale_from_config
from ui.time_input import TimeInput

_log = get_logger(__name__)


class EventDialog(QDialog):
    """添加 / 编辑事件的对话框 - 动态主题适配"""

    def __init__(self, default_date: date, parent=None, edit_event: dict = None):
        super().__init__(parent)
        self._edit_event = edit_event
        self.setWindowTitle('编辑日程' if edit_event else '添加日程')
        self.setMinimumWidth(420)

        layout = QFormLayout(self)
        layout.setSpacing(12)
        layout.setContentsMargins(24, 24, 24, 24)

        layout.addRow(self._section_label('基本信息'))

        # 类型选择
        self._cmb_type = QComboBox()
        self._cmb_type.addItem('🔔 提醒（指定时间点）', 'reminder')
        self._cmb_type.addItem('📅 待办时间段（有起止时间）', 'timespan')
        self._cmb_type.currentIndexChanged.connect(self._on_type_changed)
        layout.addRow('类型：', self._cmb_type)

        # 标题
        self._edit_title = QLineEdit()
        self._edit_title.setPlaceholderText('输入事项名称，如"开会"、"写报告"')
        layout.addRow('标题：', self._edit_title)

        # 日期
        self._edit_date = QDateTimeEdit(QDate(default_date))
        self._edit_date.setCalendarPopup(True)
        self._edit_date.setDisplayFormat('yyyy-MM-dd')
        layout.addRow('开始日期：', self._edit_date)

        # 起始时间
        self._edit_start = TimeInput(QTime(9, 0))
        layout.addRow('起始时间：', self._edit_start)

        self._edit_end_date = QDateTimeEdit(QDate(default_date))
        self._edit_end_date.setCalendarPopup(True)
        self._edit_end_date.setDisplayFormat('yyyy-MM-dd')
        layout.addRow('结束日期：', self._edit_end_date)
        self._lbl_end_date = layout.labelForField(self._edit_end_date)

        # 结束时间（仅timespan可见）
        self._edit_end = TimeInput(QTime(10, 0))
        layout.addRow('结束时间：', self._edit_end)
        self._lbl_end = layout.labelForField(self._edit_end)

        # 预估时长（仅提醒可见）
        self._spin_duration = QSpinBox()
        self._spin_duration.setRange(0, 480)
        self._spin_duration.setSuffix(' 分钟')
        self._spin_duration.setValue(0)
        self._spin_duration.setToolTip('提醒事项的预估时长，0表示仅标记时刻不占时段')
        layout.addRow('预估时长：', self._spin_duration)
        self._lbl_duration = layout.labelForField(self._spin_duration)

        self._edit_desc = QTextEdit()
        self._edit_desc.setPlaceholderText('可选的备注说明...')
        self._edit_desc.setMaximumHeight(80)
        layout.addRow('备注：', self._edit_desc)

        layout.addRow(self._section_label('提醒策略'))

        self._notification = QCheckBox('提醒')
        self._notification.setChecked(not edit_event)
        self._timer_minutes = QSpinBox()
        self._timer_minutes.setRange(0, 1440)
        self._timer_minutes.setSuffix(' 分钟')
        self._timer_minutes.setSpecialValueText('关闭')
        layout.addRow('到时通知：', self._notification)
        layout.addRow('开始后计时：', self._timer_minutes)
        self._alert_note = QLabel('提醒使用系统通知；计时在开始后到期通知。需要保持应用运行。')
        self._alert_note.setObjectName('eventDialogAlertNote')
        self._alert_note.setWordWrap(True)
        layout.addRow(self._alert_note)

        btn_box = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        btn_box.button(QDialogButtonBox.Ok).setText('确定')
        btn_box.button(QDialogButtonBox.Ok).setProperty('btnClass', 'primary')
        btn_box.button(QDialogButtonBox.Cancel).setText('取消')
        btn_box.accepted.connect(self._validate_and_accept)
        btn_box.rejected.connect(self.reject)
        layout.addRow(btn_box)

        if edit_event:
            self._populate_from_event(edit_event)
        self._on_type_changed()
        self.apply_theme()

    @staticmethod
    def _section_label(text: str) -> QLabel:
        label = QLabel(text)
        label.setObjectName('eventDialogSection')
        return label

    def apply_theme(self):
        """Refresh form colors while inheriting the application's type scale."""
        t = theme_manager.get_current_theme()
        scale = app_scale_from_config(cfg_mod.load_config())
        self.setStyleSheet(f'''
            QDialog {{ background: {t["frame_bg"]}; }}
            QLabel {{ color: {t["text_color"]}; background: transparent; }}
            QLabel#eventDialogSection {{
                font-size: {scale.section_title_px}px; font-weight: bold;
                color: {t["primary"]};
                border-left: 3px solid {t["primary"]};
                padding: 2px 8px;
            }}
            QLabel#eventDialogAlertNote {{
                color: {t["subtitle_color"]};
                font-size: {scale.secondary_px}px;
            }}
            QLineEdit, QDateTimeEdit, QTextEdit, QComboBox, QSpinBox {{
                padding: 7px; border: 1px solid {t.get("border_soft", t["frame_border"])};
                border-radius: 6px; background: {t.get("input_bg", t["list_bg"])}; color: {t["text_color"]};
            }}
            QLineEdit:focus, QDateTimeEdit:focus, QTextEdit:focus,
            QComboBox:focus, QSpinBox:focus {{
                border: 1px solid {t["primary"]};
            }}
        ''')

    def _on_type_changed(self):
        """类型切换时显示/隐藏结束时间和预估时长"""
        is_timespan = self._cmb_type.currentData() == 'timespan'
        is_reminder = not is_timespan
        if not self._edit_event and hasattr(self, "_notification"):
            self._notification.setChecked(is_reminder)
        self._edit_end.setVisible(is_timespan)
        self._edit_end_date.setVisible(is_timespan)
        self._lbl_end_date.setVisible(is_timespan)
        if self._lbl_end:
            self._lbl_end.setVisible(is_timespan)
        self._spin_duration.setVisible(is_reminder)
        if hasattr(self, '_lbl_duration') and self._lbl_duration:
            self._lbl_duration.setVisible(is_reminder)

    def _populate_from_event(self, ev: dict):
        """用已有事件数据填充表单"""
        self._edit_title.setText(ev.get('title', ''))
        self._edit_desc.setPlainText(ev.get('description', ''))

        ev_type = ev.get('event_type', 'reminder')
        idx = self._cmb_type.findData(ev_type)
        if idx >= 0:
            self._cmb_type.setCurrentIndex(idx)

        # 解析日期和时间
        start_str = ev.get('start_time', '')
        if start_str:
            try:
                sd = datetime.strptime(start_str, '%Y-%m-%d %H:%M')
                self._edit_date.setDate(QDate(sd.year, sd.month, sd.day))
                self._edit_end_date.setDate(QDate(sd.year, sd.month, sd.day))
                self._edit_start.setTime(QTime(sd.hour, sd.minute))
            except ValueError:
                _log.debug('无法解析事项开始时间')

        end_str = ev.get('end_time', '')
        if end_str and ev_type == 'timespan':
            try:
                ed = datetime.strptime(end_str, '%Y-%m-%d %H:%M')
                self._edit_end_date.setDate(QDate(ed.year, ed.month, ed.day))
                self._edit_end.setTime(QTime(ed.hour, ed.minute))
            except ValueError:
                _log.debug('无法解析事项结束时间')

        est_dur = ev.get('estimated_duration', 0) or 0
        self._spin_duration.setValue(est_dur)
        self._notification.setChecked(
            ev.get('notification_enabled', False) or ev.get('alarm_enabled', False)
        )
        self._timer_minutes.setValue(ev.get('timer_minutes', 0))

    def _validate_and_accept(self):
        """验证输入后接受"""
        try:
            EventService.validate_event(**self.get_data())
        except (ValueError, TypeError) as exc:
            QMessageBox.warning(self, '请检查日程', str(exc))
            return
        self.accept()

    def get_data(self):
        """获取表单数据，返回字典"""
        selected_date = self._edit_date.date().toPyDate()
        start_time = self._edit_start.time().toPyTime()
        start_dt = datetime.combine(selected_date, start_time)
        start_str = start_dt.strftime('%Y-%m-%d %H:%M')

        ev_type = self._cmb_type.currentData()
        end_str = None
        if ev_type == 'timespan':
            end_time = self._edit_end.time().toPyTime()
            end_dt = datetime.combine(self._edit_end_date.date().toPyDate(), end_time)
            end_str = end_dt.strftime('%Y-%m-%d %H:%M')

        est_dur = self._spin_duration.value() if ev_type == 'reminder' else 0

        return {
            'event_type': ev_type,
            'title': self._edit_title.text().strip(),
            'start_time': start_str,
            'end_time': end_str,
            'description': self._edit_desc.toPlainText().strip(),
            'estimated_duration': est_dur,
            'notification_enabled': self._notification.isChecked(),
            'alarm_enabled': False,
            'timer_minutes': self._timer_minutes.value(),
        }
