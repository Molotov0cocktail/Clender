"""Application settings dialog, including daily floating-window options."""
from PyQt5.QtCore import Qt, QTime, pyqtSignal
from PyQt5.QtWidgets import (
    QCheckBox,
    QDialog,
    QFormLayout,
    QHBoxLayout,
    QLabel,
    QMessageBox,
    QPushButton,
    QSlider,
    QTimeEdit,
    QVBoxLayout,
)

import config as cfg_mod
import theme_manager
from floating_window_logic import FloatingWindowSettings


class AppSettingsDialog(QDialog):
    """Edit non-AI application settings and emit the complete saved config."""

    config_saved = pyqtSignal(dict)
    theme_toggle_requested = pyqtSignal()
    ai_settings_requested = pyqtSignal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("⚙️ 设置")
        self.setMinimumWidth(460)
        self._config = cfg_mod.load_config()
        self._init_ui()
        self.apply_theme()

    def _init_ui(self):
        layout = QVBoxLayout(self)
        layout.setSpacing(12)

        title = QLabel("应用设置")
        title.setObjectName("settingsTitle")
        layout.addWidget(title)

        self._chk_close_to_tray = QCheckBox("关闭窗口时最小化到系统托盘（不退出）")
        self._chk_close_to_tray.setChecked(
            bool(self._config.get("close_to_tray", False))
        )
        layout.addWidget(self._chk_close_to_tray)

        floating = FloatingWindowSettings.from_config(self._config)
        self._chk_floating_enabled = QCheckBox("显示今日桌面悬浮窗")
        self._chk_floating_enabled.setChecked(floating.enabled)
        layout.addWidget(self._chk_floating_enabled)

        form = QFormLayout()
        opacity_row = QHBoxLayout()
        self._opacity_slider = QSlider(Qt.Horizontal)
        self._opacity_slider.setRange(30, 100)
        self._opacity_slider.setValue(floating.opacity_percent)
        self._lbl_opacity = QLabel(f"{floating.opacity_percent}%")
        self._opacity_slider.valueChanged.connect(
            lambda value: self._lbl_opacity.setText(f"{value}%")
        )
        opacity_row.addWidget(self._opacity_slider, 1)
        opacity_row.addWidget(self._lbl_opacity)
        form.addRow("透明度：", opacity_row)

        self._start_time = QTimeEdit()
        self._start_time.setDisplayFormat("HH:mm")
        self._start_time.setTime(QTime(floating.start_time.hour, floating.start_time.minute))
        form.addRow("显示起始：", self._start_time)

        self._end_time = QTimeEdit()
        self._end_time.setDisplayFormat("HH:mm")
        self._end_time.setTime(QTime(floating.end_time.hour, floating.end_time.minute))
        form.addRow("显示结束：", self._end_time)
        layout.addLayout(form)

        action_row = QHBoxLayout()
        self._btn_theme = QPushButton("切换日间/夜间主题")
        self._btn_theme.clicked.connect(self.theme_toggle_requested)
        action_row.addWidget(self._btn_theme)
        self._btn_ai = QPushButton("🤖 AI 设置…")
        self._btn_ai.clicked.connect(self.ai_settings_requested)
        action_row.addWidget(self._btn_ai)
        layout.addLayout(action_row)

        about = QPushButton("ℹ️ 关于 Clender")
        about.clicked.connect(lambda: QMessageBox.about(
            self,
            "关于 Clender",
            "Clender - 智能日程管理桌面应用\n\n版本：1.0\n技术栈：Python + PyQt5 + SQLite + AI",
        ))
        layout.addWidget(about)

        self._btn_save = QPushButton("保存并关闭")
        self._btn_save.clicked.connect(self._save)
        layout.addWidget(self._btn_save)

    def _save(self):
        start = self._start_time.time()
        end = self._end_time.time()
        if start >= end:
            QMessageBox.warning(self, "时间范围无效", "显示结束时间必须晚于起始时间。")
            return

        updated = cfg_mod.load_config()
        updated.update({
            "close_to_tray": self._chk_close_to_tray.isChecked(),
            "floating_window_enabled": self._chk_floating_enabled.isChecked(),
            "floating_window_opacity": self._opacity_slider.value(),
            "floating_window_start_time": start.toString("HH:mm"),
            "floating_window_end_time": end.toString("HH:mm"),
        })
        try:
            cfg_mod.save_config(updated)
        except (OSError, TypeError, ValueError) as exc:
            QMessageBox.critical(self, "保存失败", f"设置保存失败：{exc}")
            return
        self._config = dict(updated)
        self.config_saved.emit(dict(updated))
        self.accept()

    def apply_theme(self):
        theme = theme_manager.get_current_theme()
        self.setStyleSheet(f'''
            QDialog {{ background: {theme["frame_bg"]}; color: {theme["text_color"]}; }}
            QLabel, QCheckBox {{ color: {theme["text_color"]}; }}
            QLabel#settingsTitle {{
                color: {theme["title_color"]}; font-size: 16px; font-weight: bold;
            }}
            QPushButton {{
                background: {theme["primary"]}; color: {theme["primary_text"]};
                border: none; border-radius: 4px; padding: 8px;
            }}
            QPushButton:hover {{ background: {theme["primary_hover"]}; }}
        ''')
