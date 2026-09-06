"""Application settings dialog, including daily floating-window options."""
from PyQt5.QtCore import Qt, QTime, pyqtSignal
from PyQt5.QtWidgets import (
    QCheckBox,
    QDialog,
    QFormLayout,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMessageBox,
    QPushButton,
    QSlider,
    QSpinBox,
    QTimeEdit,
    QVBoxLayout,
    QScrollArea,
    QWidget,
    QFileDialog,
)

import config as cfg_mod
import startup_manager
import theme_manager
from background import BackgroundSettings, load_background
from pathlib import Path
from floating_window_logic import FloatingWindowSettings
from logger import get_logger
from webdav_sync import WebDAVSettings


_log = get_logger(__name__)
from typography import (
    MAX_FONT_PX,
    MIN_FONT_PX,
    app_scale_from_config,
    floating_scale_from_config,
)


class AppSettingsDialog(QDialog):
    """Edit non-AI application settings and emit the complete saved config."""

    config_saved = pyqtSignal(dict)
    theme_toggle_requested = pyqtSignal()
    ai_settings_requested = pyqtSignal()
    webdav_test_requested = pyqtSignal(dict)
    webdav_sync_requested = pyqtSignal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("外观与应用设置")
        self.setMinimumWidth(460)
        self.resize(560, 680)
        self._config = cfg_mod.load_config()
        self._init_ui()
        self.apply_theme()

    def _init_ui(self):
        outer = QVBoxLayout(self)
        scroll = QScrollArea()
        scroll.setObjectName('settingsScroll')
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QScrollArea.NoFrame)
        content = QWidget()
        content.setObjectName('settingsContent')
        layout = QVBoxLayout(content)
        scroll.setWidget(content)
        outer.addWidget(scroll, 1)
        layout.setSpacing(12)

        title = QLabel("应用设置")
        title.setObjectName("settingsTitle")
        layout.addWidget(title)

        self._section(layout, '外观与背景')
        background = BackgroundSettings.from_config(self._config)
        self._background_path = background.path
        self._background_label = QLabel()
        self._background_label.setWordWrap(True)
        self._update_background_label()
        layout.addWidget(self._background_label)
        background_actions = QHBoxLayout()
        choose = QPushButton('选择背景图片…')
        choose.clicked.connect(self._choose_background)
        remove = QPushButton('恢复纯色')
        remove.clicked.connect(self._remove_background)
        background_actions.addWidget(choose)
        background_actions.addWidget(remove)
        layout.addLayout(background_actions)
        self._background_strength = QSlider(Qt.Horizontal)
        self._background_strength.setRange(0, 100)
        self._background_strength.setValue(background.strength)
        self._background_strength.setAccessibleName('背景可见度')
        strength_label = QLabel(f'背景可见度 {background.strength}%')
        self._background_strength.valueChanged.connect(lambda value: strength_label.setText(f'背景可见度 {value}%'))
        layout.addWidget(strength_label)
        layout.addWidget(self._background_strength)
        hint = QLabel('日夜主题自动柔化背景。图片移动或删除后将恢复纯色背景。')
        hint.setWordWrap(True)
        layout.addWidget(hint)
        self._section(layout, '窗口与桌面悬浮窗')

        self._chk_close_to_tray = QCheckBox("关闭窗口时最小化到系统托盘（不退出）")
        self._chk_close_to_tray.setChecked(
            bool(self._config.get("close_to_tray", False))
        )
        layout.addWidget(self._chk_close_to_tray)

        self._startup_supported = startup_manager.is_supported_runtime()
        self._startup_enabled = QCheckBox("开机自启动（静默启动）")
        self._startup_enabled.setObjectName("startupEnabled")
        self._startup_enabled.setChecked(
            bool(self._config.get("startup_enabled", False))
        )
        if not self._startup_supported:
            self._startup_enabled.setEnabled(False)
            self._startup_enabled.setToolTip("仅打包版 Clender.exe 可设置开机自启动")
        layout.addWidget(self._startup_enabled)

        floating = FloatingWindowSettings.from_config(self._config)
        self._chk_floating_enabled = QCheckBox("显示今日桌面悬浮窗")
        self._chk_floating_enabled.setChecked(floating.enabled)
        layout.addWidget(self._chk_floating_enabled)

        form = QFormLayout()
        app_scale = app_scale_from_config(self._config)
        floating_scale = floating_scale_from_config(self._config)
        self._app_font_slider, self._app_font_spin, app_font_row = (
            self._make_font_size_row(
                app_scale.body_px,
                "appFontSlider",
                "appFontSpin",
            )
        )
        form.addRow("应用字号：", app_font_row)
        (
            self._floating_font_slider,
            self._floating_font_spin,
            floating_font_row,
        ) = self._make_font_size_row(
            floating_scale.body_px,
            "floatingFontSlider",
            "floatingFontSpin",
        )
        form.addRow("悬浮窗字号：", floating_font_row)

        opacity_row = QHBoxLayout()
        self._opacity_slider = QSlider(Qt.Horizontal)
        self._opacity_slider.setRange(0, 100)
        self._opacity_slider.setValue(floating.opacity_percent)
        self._lbl_opacity = QLabel(f"{floating.opacity_percent}%")
        self._opacity_slider.valueChanged.connect(
            lambda value: self._lbl_opacity.setText(f"{value}%")
        )
        opacity_row.addWidget(self._opacity_slider, 1)
        opacity_row.addWidget(self._lbl_opacity)
        form.addRow("悬浮窗不透明度：", opacity_row)

        self._start_time = QTimeEdit()
        self._start_time.setDisplayFormat("HH:mm")
        self._start_time.setTime(QTime(floating.start_time.hour, floating.start_time.minute))
        form.addRow("显示起始：", self._start_time)

        self._end_time = QTimeEdit()
        self._end_time.setDisplayFormat("HH:mm")
        self._end_time.setTime(QTime(floating.end_time.hour, floating.end_time.minute))
        form.addRow("显示结束：", self._end_time)
        layout.addLayout(form)

        webdav_title = QLabel("WebDAV 日程同步")
        webdav_title.setObjectName("webdavTitle")
        layout.addWidget(webdav_title)

        self._webdav_enabled = QCheckBox("启用 WebDAV 日程同步")
        self._webdav_enabled.setObjectName("webdavEnabled")
        self._webdav_enabled.setChecked(
            bool(self._config.get("webdav_enabled", False))
        )
        layout.addWidget(self._webdav_enabled)

        webdav_form = QFormLayout()
        self._webdav_url = QLineEdit(str(self._config.get("webdav_url", "")))
        self._webdav_url.setObjectName("webdavUrl")
        self._webdav_url.setPlaceholderText("https://dav.example.com/path/")
        webdav_form.addRow("目录 URL：", self._webdav_url)

        self._webdav_username = QLineEdit(
            str(self._config.get("webdav_username", ""))
        )
        self._webdav_username.setObjectName("webdavUsername")
        webdav_form.addRow("用户名：", self._webdav_username)

        self._webdav_password = QLineEdit(
            str(self._config.get("webdav_password", ""))
        )
        self._webdav_password.setObjectName("webdavPassword")
        self._webdav_password.setEchoMode(QLineEdit.Password)
        webdav_form.addRow("密码：", self._webdav_password)
        layout.addLayout(webdav_form)

        webdav_actions = QHBoxLayout()
        self._webdav_test_button = QPushButton("测试连接")
        self._webdav_test_button.setObjectName("webdavTestButton")
        self._webdav_test_button.clicked.connect(self._request_webdav_test)
        webdav_actions.addWidget(self._webdav_test_button)
        self._webdav_sync_button = QPushButton("保存并立即同步")
        self._webdav_sync_button.setObjectName("webdavSyncButton")
        self._webdav_sync_button.clicked.connect(self._save_and_sync)
        webdav_actions.addWidget(self._webdav_sync_button)
        layout.addLayout(webdav_actions)

        self._webdav_status = QLabel("仅同步日程；不会同步 AI 对话。")
        self._webdav_status.setObjectName("webdavStatus")
        self._webdav_status.setWordWrap(True)
        layout.addWidget(self._webdav_status)

        action_row = QHBoxLayout()
        self._btn_theme = QPushButton("切换日间/夜间主题")
        self._btn_theme.clicked.connect(self.theme_toggle_requested)
        action_row.addWidget(self._btn_theme)
        self._btn_ai = QPushButton("AI 设置…")
        self._btn_ai.clicked.connect(self.ai_settings_requested)
        action_row.addWidget(self._btn_ai)
        layout.addLayout(action_row)

        about = QPushButton("关于 Clender")
        about.clicked.connect(lambda: QMessageBox.about(
            self,
            "关于 Clender",
            "Clender - 智能日程管理桌面应用\n\n版本：1.0\n技术栈：Python + PyQt5 + SQLite + AI",
        ))
        layout.addWidget(about)

        self._btn_save = QPushButton("保存并关闭")
        self._btn_save.clicked.connect(self._save)
        self._btn_save.setObjectName('settingsSave')
        footer = QHBoxLayout()
        cancel = QPushButton('取消')
        cancel.clicked.connect(self.reject)
        footer.addWidget(cancel)
        footer.addWidget(self._btn_save, 1)
        outer.addLayout(footer)

    @staticmethod
    def _section(layout, text):
        label = QLabel(text)
        label.setObjectName('settingsSection')
        layout.addWidget(label)

    def _update_background_label(self):
        self._background_label.setText(Path(self._background_path).name if self._background_path else '纯色背景 · 跟随日夜主题')

    def _choose_background(self):
        path, _ = QFileDialog.getOpenFileName(self, '选择背景图片', '', '图片 (*.png *.jpg *.jpeg *.bmp *.webp)')
        if not path:
            return
        if load_background(path).isNull():
            QMessageBox.warning(self, '无法使用图片', '请选择有效图片，文件不超过 32 MB，像素不超过 2400 万。')
            return
        self._background_path = path
        self._update_background_label()

    def _remove_background(self):
        self._background_path = ''
        self._update_background_label()

    @staticmethod
    def _make_font_size_row(value, slider_name, spin_name):
        slider = QSlider(Qt.Horizontal)
        slider.setObjectName(slider_name)
        slider.setRange(MIN_FONT_PX, MAX_FONT_PX)
        slider.setSingleStep(1)
        slider.setValue(value)

        spin = QSpinBox()
        spin.setObjectName(spin_name)
        spin.setRange(MIN_FONT_PX, MAX_FONT_PX)
        spin.setSingleStep(1)
        spin.setSuffix(" px")
        spin.setValue(value)

        slider.valueChanged.connect(spin.setValue)
        spin.valueChanged.connect(slider.setValue)

        row = QHBoxLayout()
        row.addWidget(slider, 1)
        row.addWidget(spin)
        return slider, spin, row

    def _connection_mapping(self) -> dict:
        return {
            "webdav_enabled": self._webdav_enabled.isChecked(),
            "webdav_url": self._webdav_url.text().strip(),
            "webdav_username": self._webdav_username.text().strip(),
            "webdav_password": self._webdav_password.text(),
        }

    def _request_webdav_test(self):
        mapping = self._connection_mapping()
        try:
            WebDAVSettings.from_mapping(mapping, require_enabled=False)
        except ValueError as exc:
            QMessageBox.warning(self, "WebDAV 设置无效", str(exc))
            return
        self._webdav_status.setText("正在测试连接…")
        self._webdav_test_button.setEnabled(False)
        self.webdav_test_requested.emit(dict(mapping))

    def set_webdav_test_result(self, ok: bool, message: str):
        self._webdav_test_button.setEnabled(True)
        prefix = "✓" if ok else "⚠"
        self._webdav_status.setText(f"{prefix} {str(message)[:200]}")

    def set_webdav_status(self, status: str):
        text = str(status)
        if ":" in text:
            _kind, text = text.split(":", 1)
        self._webdav_status.setText(text[:200])

    def _save_and_sync(self):
        if not self._webdav_enabled.isChecked():
            QMessageBox.warning(self, "WebDAV 未启用", "请先启用 WebDAV 日程同步。")
            return
        self._persist(close_after=False, sync_after=True)

    def _save(self):
        self._persist(close_after=True, sync_after=False)

    def _persist(self, *, close_after: bool, sync_after: bool):
        start = self._start_time.time()
        end = self._end_time.time()
        if start >= end:
            QMessageBox.warning(self, "时间范围无效", "显示结束时间必须晚于起始时间。")
            return False

        connection = self._connection_mapping()
        if connection["webdav_enabled"]:
            try:
                WebDAVSettings.from_mapping(connection)
            except ValueError as exc:
                QMessageBox.warning(self, "WebDAV 设置无效", str(exc))
                return False

        updated = cfg_mod.load_config()
        previous_startup = bool(updated.get("startup_enabled", False))
        requested_startup = (
            self._startup_enabled.isChecked()
            if self._startup_supported
            else previous_startup
        )
        updated.update({
            "background_image": self._background_path,
            "background_strength": self._background_strength.value(),
            "close_to_tray": self._chk_close_to_tray.isChecked(),
            "app_font_size_px": self._app_font_spin.value(),
            "floating_font_size_px": self._floating_font_spin.value(),
            "floating_window_enabled": self._chk_floating_enabled.isChecked(),
            "floating_window_opacity": self._opacity_slider.value(),
            "floating_window_start_time": start.toString("HH:mm"),
            "floating_window_end_time": end.toString("HH:mm"),
            "webdav_enabled": connection["webdav_enabled"],
            "webdav_url": connection["webdav_url"],
            "webdav_username": connection["webdav_username"],
            "webdav_password": connection["webdav_password"],
            "startup_enabled": requested_startup,
        })
        startup_applied = False
        try:
            if self._startup_supported:
                startup_manager.set_startup_enabled(requested_startup)
                startup_applied = True
            cfg_mod.save_config(updated)
        except (OSError, TypeError, ValueError, startup_manager.StartupError) as exc:
            if startup_applied:
                try:
                    startup_manager.set_startup_enabled(previous_startup)
                except startup_manager.StartupError as rollback_exc:
                    _log.warning(
                        "开机自启动补偿回滚失败：%s",
                        type(rollback_exc).__name__,
                    )
            QMessageBox.critical(self, "保存失败", f"设置保存失败：{exc}")
            return False
        self._config = dict(updated)
        self.config_saved.emit(dict(updated))
        if sync_after:
            self.webdav_sync_requested.emit()
        if close_after:
            self.accept()
        return True

    def apply_theme(self):
        theme = theme_manager.get_current_theme()
        scale = app_scale_from_config(self._config)
        self.setStyleSheet(f'''
            QDialog {{ background: {theme["frame_bg"]}; color: {theme["text_color"]}; }}
            QLabel, QCheckBox {{ color: {theme["text_color"]}; }}
            QWidget#settingsContent, QScrollArea {{ background: {theme["frame_bg"]}; }}
            QLabel#settingsSection {{ font-size: {scale.section_title_px}px; font-weight: bold; padding-top: 12px; color: {theme["primary"]}; }}
            QLabel#settingsTitle {{
                color: {theme["title_color"]};
                font-size: {scale.section_title_px}px; font-weight: bold;
            }}
            QLabel#webdavTitle {{
                color: {theme["title_color"]};
                font-size: {scale.section_title_px}px; font-weight: bold;
            }}
            QPushButton {{
                background: {theme.get("header_bg", theme["frame_bg"])}; color: {theme["text_color"]};
                border: 1px solid {theme.get("frame_border", theme["frame_bg"])}; border-radius: 8px; padding: 8px;
            }}
            QPushButton:hover {{ background: {theme.get("list_item_hover", theme["primary_hover"])}; }}
            QPushButton#settingsSave {{ background: {theme["primary"]}; color: {theme["primary_text"]}; }}
        ''')
