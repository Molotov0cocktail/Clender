"""
应用级常量定义
从 ai_chat.py、config.py、calendar_widget.py 提取集中管理
"""
# ── 系统提示词（从 ai_chat.py 提取）──
SYSTEM_PROMPT = """你是智能日程管理助手。当前所有事件以 JSON 提供。

输出必须**仅**是以下 JSON,不要额外文字:
{"operations":[{"action":"add","event_type":"reminder","title":"…","start_time":"2026-07-15 14:30","end_time":null},
{"action":"add","event_type":"timespan","title":"…","start_time":"2026-07-15 09:00","end_time":"2026-07-15 11:00"},
{"action":"update","event_id":1,"title":"…"},{"action":"delete","event_id":2},
{"action":"reply","message":"自然语言回复"}]}

规则:时间 YYYY-MM-DD HH:MM; reminder 无 end_time; timespan 必须有 end_time; 默认今天; event_id 必须真实; 只输出合法 JSON。"""

# ── 模型能力映射（从 ai_chat.py MODEL_CAPS 提取）──
MODEL_CAPABILITIES = {
    "deepseek-v4-pro":     (1048576, 65536),
    "deepseek-v3":         (128000,  8192),
    "deepseek-r1":         (128000,  8192),
    "deepseek":            (131072,  8192),
    "gpt-4":               (128000,  16384),
    "gpt-4-turbo":         (128000,  4096),
    "gpt-4o":              (128000,  16384),
    "gpt-4o-mini":         (128000,  16384),
    "gpt-3.5-turbo":       (16385,   4096),
    "claude-3":            (200000,  4096),
    "claude-3.5":          (200000,  8192),
    "llama3":              (128000,  4096),
    "llama3.1":            (131072,  4096),
    "qwen":                (32768,   4096),
    "mixtral":             (32768,   4096),
    "command-r":           (131072,  4096),
}

# ── 事件颜色（从 calendar_widget.py COLORS 提取）──
EVENT_COLORS = [
    '#58a6ff', '#7c6ff7', '#3fb950', '#d29922', '#e74c3c', '#00b894',
    '#6c5ce7', '#0984e3', '#f39c12', '#2ecc71', '#9b59b6', '#1abc9c',
]

# ── 默认配置（从 config.py DEFAULT_CONFIG 提取）──
DEFAULT_CONFIG = {
    "theme": "light",
    "api_endpoint": "",
    "api_key": "",
    "model": "deepseek-v4-pro",
    "temperature": 0.7,
    "max_tokens": 4096,
    "context_window": 128000,
    "thinking_enabled": True,
    "think_effort": "high",
    "available_models": [],
    "system_prompt": "",
    "ai_personality": "",
    "close_to_tray": False,
    "floating_window_enabled": False,
    "floating_window_opacity": 90,
    "floating_window_start_time": "08:00",
    "floating_window_end_time": "22:00",
    "floating_window_geometry": None,
}

# ── 应用信息 ──
APP_NAME = "Clender"
APP_VERSION = "1.0"
APP_ORG = "ClenderApp"

# ── 视角/Canvas 渲染配置 ──
PER_HOUR_PX = 30               # 每小时像素高度
TIMELINE_HOURS = 24            # 时间轴总小时数
TIMELINE_HEIGHT = PER_HOUR_PX * TIMELINE_HOURS  # 720px
WEEK_COLS = 7                  # 周视图列数

# ── Canvas 颜色常量 ──
REMINDER_LINE_COLOR = '#e74c3c'   # 提醒红线颜色
OVERLAP_MARKER_COLOR = (255, 255, 255, 60)  # 重叠标记半透明白色 RGBA
