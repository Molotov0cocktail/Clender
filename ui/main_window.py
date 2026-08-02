"""
Clender 主窗口 - 日历 + 事项 + AI + 托盘
从 main.py 迁移到 ui 包，使用 EventService 替代直接 database 调用
"""
from datetime import date

from PyQt5.QtWidgets import (QApplication, QMainWindow, QWidget, QHBoxLayout,
                             QVBoxLayout, QSplitter, QStatusBar, QMessageBox,
                             QLabel, QPushButton, QSystemTrayIcon, QMenu,
                             QAction, QDialog, QCheckBox)
from PyQt5.QtCore import Qt, QTimer
from PyQt5.QtGui import QFont, QIcon, QColor

import database
import config as cfg_mod
import theme_manager
from event_service import EventService
from ui.calendar_widget import CalendarWidget
from ui.event_manager import EventManager
from ui.ai_chat_widget import AIChatWidget
from ui.ai_settings import SettingsDialog


class MainWindow(QMainWindow):
    """主窗口：日历 + 事项 + AI + 托盘"""

    def __init__(self, app: QApplication):
        super().__init__()
        self._app = app
        self.setWindowTitle('Clender - 智能日程管理')
        self.resize(1280, 800)
        self.setMinimumSize(960, 600)

        self._init_ui()
        self._init_tray()
        self._load_sample_data_if_empty()
        self._connect_signals()
        self._refresh_all()

        theme_manager.apply_theme(self._app)
        self._apply_component_styles()

    def _init_ui(self):
        central = QWidget()
        self.setCentralWidget(central)

        main_layout = QHBoxLayout(central)
        main_layout.setContentsMargins(8, 8, 8, 8)
        main_layout.setSpacing(8)

        self._calendar = CalendarWidget()
        self._calendar.setMinimumWidth(380)
        self._calendar.setMaximumWidth(500)

        self._event_mgr = EventManager()
        self._event_mgr.setMinimumWidth(300)

        self._ai_chat = AIChatWidget()
        self._ai_chat.setMinimumWidth(350)

        self._splitter = QSplitter(Qt.Horizontal)
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
        self._btn_settings = QPushButton('⚙')
        self._btn_settings.setFixedSize(24, 24)
        self._btn_settings.setToolTip('设置')
        self._btn_settings.clicked.connect(self._open_settings_dialog)
        self._status_bar.addPermanentWidget(self._btn_settings)

        # 主题切换
        self._theme_btn = QPushButton()
        self._theme_btn.setFixedSize(24, 24)
        self._theme_btn.setToolTip('切换日间/夜间主题')
        self._theme_btn.clicked.connect(self._toggle_theme)
        self._status_bar.addPermanentWidget(self._theme_btn)

        self.setStatusBar(self._status_bar)

    def _init_tray(self):
        if not QSystemTrayIcon.isSystemTrayAvailable():
            return
        from PyQt5.QtGui import QPixmap
        pixmap = QPixmap(16, 16)
        pixmap.fill(QColor('#6c5ce7'))
        from PyQt5.QtGui import QIcon
        self._tray_icon = QSystemTrayIcon(QIcon(pixmap), self)
        self._tray_icon.setToolTip('Clender - 智能日程管理')
        tray_menu = QMenu()
        show_action = tray_menu.addAction('显示主窗口')
        show_action.triggered.connect(self._show_from_tray)
        tray_menu.addSeparator()
        quit_action = tray_menu.addAction('退出')
        quit_action.triggered.connect(self._quit_app)
        self._tray_icon.setContextMenu(tray_menu)
        self._tray_icon.activated.connect(self._on_tray_activated)
        self._tray_icon.show()

    def _on_tray_activated(self, reason):
        if reason == QSystemTrayIcon.DoubleClick:
            self._show_from_tray()

    def _show_from_tray(self):
        self.showNormal()
        self.activateWindow()

    def _quit_app(self):
        self._tray_icon.hide()
        QApplication.quit()

    def _open_settings_dialog(self):
        """打开综合设置对话框"""
        dlg = QDialog(self)
        dlg.setWindowTitle('⚙️ 设置')
        dlg.setMinimumWidth(440)

        layout = QVBoxLayout(dlg)
        layout.setSpacing(12)
        t = theme_manager.get_current_theme()

        title = QLabel('应用设置')
        title.setStyleSheet(f'font-size:16px; font-weight:bold; color:{t["title_color"]};')
        layout.addWidget(title)

        # 关闭行为
        self._chk_tray = QCheckBox('关闭窗口时最小化到系统托盘（不退出）')
        self._chk_tray.setChecked(cfg_mod.load_config().get('close_to_tray', False))
        layout.addWidget(self._chk_tray)

        # 主题
        theme_row = QHBoxLayout()
        lbl = QLabel('主题切换:')
        theme_row.addWidget(lbl)
        self._btn_toggle_theme = QPushButton('🌙 夜间模式' if cfg_mod.get_theme() == 'light' else '☀️ 日间模式')
        self._btn_toggle_theme.clicked.connect(self._toggle_theme)
        theme_row.addWidget(self._btn_toggle_theme)
        theme_row.addStretch()
        layout.addLayout(theme_row)

        # AI 设置按钮
        self._btn_ai_settings = QPushButton('🤖 AI 设置...')
        self._btn_ai_settings.clicked.connect(lambda: self._open_ai_settings(dlg))
        layout.addWidget(self._btn_ai_settings)

        layout.addStretch()

        # 关于
        about = QPushButton('ℹ️ 关于 Clender')
        about.clicked.connect(lambda: QMessageBox.about(
            dlg, '关于 Clender',
            'Clender - 智能日程管理桌面应用\n\n版本：1.0\n技术栈：Python + PyQt5 + SQLite + AI\n\n'
            '支持月/周/日视图切换、手动事项管理、\nAI智能日程规划（DeepSeek V4 Pro等模型）'
        ))
        layout.addWidget(about)

        # 确定按钮
        btn_save = QPushButton('保存并关闭')
        btn_save.setStyleSheet(f'QPushButton{{background:{t["primary"]};color:{t["primary_text"]};border:none;border-radius:4px;padding:8px;font-size:13px;}}')
        btn_save.clicked.connect(self._save_settings_and_close)
        layout.addWidget(btn_save)

        self._settings_dialog = dlg
        dlg.exec_()

    def _open_ai_settings(self, parent):
        dlg2 = SettingsDialog(parent)
        dlg2.exec_()

    def _save_settings_and_close(self):
        current = cfg_mod.load_config()
        current['close_to_tray'] = self._chk_tray.isChecked()
        cfg_mod.save_config(current)
        self._settings_dialog.accept()

    def _toggle_theme(self):
        current = cfg_mod.get_theme()
        new_theme = 'dark' if current == 'light' else 'light'
        theme_manager.switch_theme(self._app, new_theme)
        self._apply_component_styles()
        self._update_theme_button()

    def _update_theme_button(self):
        current = cfg_mod.get_theme()
        self._theme_btn.setText('☀️' if current == 'dark' else '🌙')

    def _apply_component_styles(self):
        self._calendar.apply_theme()
        self._event_mgr.apply_theme()
        self._ai_chat.apply_theme()
        self._update_theme_button()

    def _connect_signals(self):
        self._calendar.date_selected.connect(self._on_date_selected)
        self._event_mgr.data_changed.connect(self._on_data_changed)
        self._ai_chat.data_changed.connect(self._on_data_changed)

    def _on_date_selected(self, d: date):
        self._event_mgr.set_date(d)
        self._calendar.set_selected_date(d)
        self._status_label.setText(f'已选中: {d.year}年{d.month}月{d.day}日')

    def _on_data_changed(self):
        self._refresh_all()
        self._status_label.setText('日程已更新 ✓')

    def _refresh_all(self):
        events_by_date = self._get_event_counts()
        self._calendar.update_event_markers(events_by_date)
        self._event_mgr.refresh()

    def _get_event_counts(self) -> dict:
        return EventService.get_event_counts()

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
            QApplication.quit()