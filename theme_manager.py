"""
主题管理模块
提供日间(Light)和夜间(Dark)两套配色方案
夜间主题彻底回避白色/亮灰色，确保长时间使用的眼部舒适
支持在运行时动态切换所有组件的样式
"""
from PyQt5.QtWidgets import QApplication
from PyQt5.QtGui import QPalette, QColor
import config as cfg_mod
from typography import app_scale_from_config, apply_application_font


# ========== 日间主题（Light）==========
LIGHT_THEME = {
    "app_bg": "#f3f5fb",
    "frame_bg": "#ffffff",
    "frame_border": "#e1e5f0",
    "title_color": "#2d3436",
    "subtitle_color": "#636e72",
    "text_color": "#2d3436",
    "muted_color": "#b2bec3",
    "primary": "#6154cc",
    "primary_hover": "#786add",
    "primary_text": "#ffffff",
    "danger": "#d63031",
    "danger_hover": "#ff7675",
    "info": "#0984e3",
    "info_hover": "#74b9ff",
    "success": "#00b894",
    "warning_text": "#e17055",
    "header_bg": "#edf0f9",
    "input_bg": "#ffffff",
    "input_border": "#cbd2e3",
    "list_bg": "#fafbfe",
    "list_item_hover": "#edf0fa",
    "scrollbar_bg": "#b2bec3",
    "chat_user_color": "#2d3436",
    "chat_ai_color": "#0984e3",
    "chat_system_color": "#636e72",
    "calendar_today_border": "#0984e3",
    "calendar_today_bg": "#dfe6e9",
    "calendar_today_text": "#0984e3",
    "calendar_selected_border": "#6c5ce7",
    "calendar_selected_bg": "#6154cc",
    "calendar_selected_text": "#ffffff",
    "calendar_cell_bg": "#ffffff",
    "calendar_cell_border": "#dfe6e9",
    "calendar_cell_text": "#2d3436",
    "event_reminder_color": "#d63031",
    "event_timespan_color": "#6c5ce7",
    "statusbar_bg": "#dfe6e9",
    "nav_btn_color": "#0984e3",
    "switch_btn_text": "#636e72",
    "switch_btn_checked_bg": "#6c5ce7",
    "slot_bg": "#f8f9fa",
    "slot_border": "#dfe6e9",
}

# ========== 夜间主题（Dark）— 零亮色，纯暗色系 ==========
DARK_THEME = {
    "app_bg": "#0d1117",            # GitHub暗色背景
    "frame_bg": "#161b22",           # 面板深色
    "frame_border": "#30363d",       # 边框暗灰
    "title_color": "#e6edf3",        # 标题亮灰（可读）
    "subtitle_color": "#8b949e",     # 副标题中灰
    "text_color": "#c9d1d9",         # 正文灰白（柔和）
    "muted_color": "#484f58",        # 禁用/占位暗灰
    "primary": "#7c6ff7",            # 主色紫
    "primary_hover": "#9d8fff",      # 主色hover
    "primary_text": "#ffffff",       # 主色按钮文字白
    "danger": "#e74c3c",             # 红色
    "danger_hover": "#ff6b6b",
    "info": "#58a6ff",               # 信息蓝
    "info_hover": "#79c0ff",
    "success": "#3fb950",            # 成功绿
    "warning_text": "#d29922",       # 警告橙
    "header_bg": "#21262d",          # 表头暗色
    "input_bg": "#0d1117",           # 输入框暗
    "input_border": "#30363d",       # 输入框边框
    "list_bg": "#161b22",            # 列表暗
    "list_item_hover": "#1c2430",    # 列表hover
    "scrollbar_bg": "#30363d",
    "chat_user_color": "#c9d1d9",
    "chat_ai_color": "#58a6ff",
    "chat_system_color": "#8b949e",
    "calendar_today_border": "#58a6ff",
    "calendar_today_bg": "#1f2937",           # 深蓝灰（不是亮蓝）
    "calendar_today_text": "#58a6ff",
    "calendar_selected_border": "#7c6ff7",
    "calendar_selected_bg": "#3b2f5c",       # 暗紫（不是亮紫）
    "calendar_selected_text": "#e6edf3",
    "calendar_cell_bg": "#161b22",            # 深色格（不是白色）
    "calendar_cell_border": "#30363d",
    "calendar_cell_text": "#c9d1d9",
    "event_reminder_color": "#ff6b6b",
    "event_timespan_color": "#7c6ff7",
    "statusbar_bg": "#161b22",
    "nav_btn_color": "#58a6ff",
    "switch_btn_text": "#8b949e",
    "switch_btn_checked_bg": "#7c6ff7",
    "slot_bg": "#0d1117",
    "slot_border": "#30363d",
}


class ThemeColors:
    """主题颜色包装器 - 提供类型安全访问，兼容 dict 访问方式"""

    def __init__(self, theme_dict: dict):
        self._d = theme_dict

    def __getattr__(self, name: str) -> str:
        """支持 t.primary 属性访问"""
        if name.startswith('_'):
            raise AttributeError(name)
        val = self._d.get(name)
        if val is None:
            raise AttributeError(f"Theme key '{name}' not found")
        return val

    def __getitem__(self, key: str) -> str:
        """支持 t['primary'] dict 访问"""
        return self._d[key]

    def __contains__(self, key: str) -> bool:
        return key in self._d

    def get(self, key: str, default: str = "") -> str:
        """支持 t.get('primary', '#fff') dict 方法"""
        return self._d.get(key, default)


def get_current_theme() -> ThemeColors:
    """获取当前主题配色（ThemeColors 包装器，支持 .attr 和 ['key'] 访问）"""
    theme_name = cfg_mod.get_theme()
    return ThemeColors(DARK_THEME if theme_name == 'dark' else LIGHT_THEME)


def apply_theme(app: QApplication, config: dict | None = None):
    """应用当前主题到整个应用程序"""
    config_values = cfg_mod.load_config() if config is None else dict(config)
    theme_name = config_values.get("theme", "light")
    theme = ThemeColors(DARK_THEME if theme_name == "dark" else LIGHT_THEME)
    scale = app_scale_from_config(config_values)
    apply_application_font(app, scale)

    # 全局调色板
    palette = QPalette()
    palette.setColor(QPalette.Window, QColor(theme["app_bg"]))
    palette.setColor(QPalette.WindowText, QColor(theme["text_color"]))
    palette.setColor(QPalette.Base, QColor(theme["frame_bg"]))
    palette.setColor(QPalette.AlternateBase, QColor(theme["list_bg"]))
    palette.setColor(QPalette.ToolTipBase, QColor(theme["frame_bg"]))
    palette.setColor(QPalette.ToolTipText, QColor(theme["text_color"]))
    palette.setColor(QPalette.Text, QColor(theme["text_color"]))
    palette.setColor(QPalette.Button, QColor(theme["frame_bg"]))
    palette.setColor(QPalette.ButtonText, QColor(theme["text_color"]))
    palette.setColor(QPalette.BrightText, QColor(theme["danger"]))
    palette.setColor(QPalette.Link, QColor(theme["info"]))
    palette.setColor(QPalette.Highlight, QColor(theme["primary"]))
    palette.setColor(QPalette.HighlightedText, QColor(theme["primary_text"]))
    palette.setColor(QPalette.PlaceholderText, QColor(theme["subtitle_color"]))
    app.setPalette(palette)

    # 全局样式表
    app.setStyleSheet(f"""
        QMainWindow {{
            background-color: {theme["app_bg"]};
        }}
        QStatusBar {{
            background: {theme["statusbar_bg"]};
            color: {theme["text_color"]};
            font-size: {scale.secondary_px}px;
        }}
        QToolTip {{
            background-color: {theme["frame_bg"]};
            color: {theme["text_color"]};
            border: 1px solid {theme["frame_border"]};
            padding: 4px;
        }}
        QScrollBar:vertical {{
            background: {theme["app_bg"]};
            width: 8px;
        }}
        QScrollBar::handle:vertical {{
            background: {theme["scrollbar_bg"]};
            border-radius: 4px;
            min-height: 20px;
        }}
        QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {{
            height: 0px;
        }}
        QScrollBar:horizontal {{
            background: {theme["app_bg"]};
            height: 8px;
        }}
        QScrollBar::handle:horizontal {{
            background: {theme["scrollbar_bg"]};
            border-radius: 4px;
            min-width: 20px;
        }}
        QScrollBar::add-line:horizontal, QScrollBar::sub-line:horizontal {{
            width: 0px;
        }}
        QDialog {{
            background-color: {theme["frame_bg"]};
            color: {theme["text_color"]};
        }}
        QPushButton {{
            background: {theme["header_bg"]};
            color: {theme["text_color"]};
            border: 1px solid {theme["frame_border"]};
            border-radius: 6px;
            padding: 6px 10px;
        }}
        QPushButton:hover {{ background: {theme["list_item_hover"]}; }}
        QPushButton:disabled {{ color: {theme["muted_color"]}; }}
        QLineEdit:focus, QTextEdit:focus, QComboBox:focus, QDateTimeEdit:focus {{
            border: 1px solid {theme["primary"]};
        }}
        QMenuBar {{
            background-color: {theme["frame_bg"]};
            color: {theme["text_color"]};
            font-size: {scale.control_px}px;
            padding: 2px;
            border-bottom: 1px solid {theme["frame_border"]};
        }}
        QMenuBar::item:selected {{
            background-color: {theme["primary"]};
            color: {theme["primary_text"]};
            border-radius: 4px;
        }}
        QMenu {{
            background-color: {theme["frame_bg"]};
            color: {theme["text_color"]};
            border: 1px solid {theme["frame_border"]};
        }}
        QMenu::item:selected {{
            background-color: {theme["primary"]};
            color: {theme["primary_text"]};
        }}
        QMessageBox {{
            background-color: {theme["frame_bg"]};
            color: {theme["text_color"]};
        }}
        QLineEdit {{
            background-color: {theme["input_bg"]};
            color: {theme["text_color"]};
            border: 1px solid {theme["input_border"]};
            border-radius: 4px;
            padding: 6px;
            font-size: {scale.body_px}px;
        }}
        QTextEdit {{
            background-color: {theme["input_bg"]};
            color: {theme["text_color"]};
            border: 1px solid {theme["input_border"]};
            border-radius: 4px;
            padding: 6px;
            font-size: {scale.body_px}px;
        }}
        QComboBox {{
            background-color: {theme["input_bg"]};
            color: {theme["text_color"]};
            border: 1px solid {theme["input_border"]};
            border-radius: 4px;
            padding: 6px;
            font-size: {scale.control_px}px;
        }}
        QComboBox::drop-down {{
            border: none;
            background: {theme["header_bg"]};
            border-radius: 0 4px 4px 0;
            width: 20px;
        }}
        QComboBox QAbstractItemView {{
            background-color: {theme["list_bg"]};
            color: {theme["text_color"]};
            border: 1px solid {theme["frame_border"]};
            selection-background-color: {theme["primary"]};
            selection-color: {theme["primary_text"]};
        }}
        QDateTimeEdit {{
            background-color: {theme["input_bg"]};
            color: {theme["text_color"]};
            border: 1px solid {theme["input_border"]};
            border-radius: 4px;
            padding: 6px;
            font-size: {scale.control_px}px;
        }}
        QLabel {{
            color: {theme["text_color"]};
        }}
        QSplitter::handle {{
            background-color: {theme["frame_border"]};
            width: 2px;
        }}
        QSpinBox, QDoubleSpinBox {{
            background-color: {theme["input_bg"]};
            color: {theme["text_color"]};
            border: 1px solid {theme["input_border"]};
            border-radius: 4px;
            padding: 4px 6px;
            font-size: {scale.control_px}px;
        }}
        QSpinBox::up-button, QDoubleSpinBox::up-button {{
            background-color: {theme["header_bg"]};
            border: none;
            border-left: 1px solid {theme["input_border"]};
            border-bottom: 1px solid {theme["input_border"]};
            width: 18px;
        }}
        QSpinBox::down-button, QDoubleSpinBox::down-button {{
            background-color: {theme["header_bg"]};
            border: none;
            border-left: 1px solid {theme["input_border"]};
            width: 18px;
        }}
    """)


def switch_theme(app: QApplication, theme_name: str):
    """切换主题并保存配置"""
    cfg_mod.set_theme(theme_name)
    apply_theme(app)
