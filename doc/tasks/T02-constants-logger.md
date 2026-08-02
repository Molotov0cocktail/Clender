# T02 — 创建常量与日志模块

## 目标

1. 创建 `constants.py`，从 `ai_chat.py`、`config.py`、`calendar_widget.py` 中提取所有硬编码常量
2. 创建 `logger.py`，提供统一日志记录功能

## 输入文档

- `doc/detailed-design.md` §2.2, §2.3
- `ai_chat.py` — `SYSTEM_PROMPT`, `MODEL_CAPS` 常量来源
- `config.py` — `DEFAULT_CONFIG` 常量来源
- `calendar_widget.py` — `COLORS` 常量来源

## 预计变更文件

| 操作 | 文件 | 说明 |
|------|------|------|
| ✨ 新建 | `constants.py` | 集中管理所有硬编码常量 |
| ✨ 新建 | `logger.py` | 日志系统 |

## 实现步骤

### Step 1：创建 `constants.py`

从现有代码中提取以下常量（保持原值不变）：

```python
"""应用级常量定义"""

# ── 系统提示词 ──
SYSTEM_PROMPT = """你是智能日程管理助手。当前所有事件以 JSON 提供。

输出必须**仅**是以下 JSON,不要额外文字:
{"operations":[{"action":"add","event_type":"reminder","title":"…","start_time":"2026-07-15 14:30","end_time":null},
{"action":"add","event_type":"timespan","title":"…","start_time":"2026-07-15 09:00","end_time":"2026-07-15 11:00"},
{"action":"update","event_id":1,"title":"…"},{"action":"delete","event_id":2},
{"action":"reply","message":"自然语言回复"}]}

规则:时间 YYYY-MM-DD HH:MM; reminder 无 end_time; timespan 必须有 end_time; 默认今天; event_id 必须真实; 只输出合法 JSON。"""

# ── 模型能力映射 ──
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

# ── 事件颜色 ──
EVENT_COLORS = [
    '#58a6ff','#7c6ff7','#3fb950','#d29922','#e74c3c','#00b894',
    '#6c5ce7','#0984e3','#f39c12','#2ecc71','#9b59b6','#1abc9c',
]

# ── 默认配置 ──
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
}

# ── 应用信息 ──
APP_NAME = "Clender"
APP_VERSION = "1.0"
APP_ORG = "ClenderApp"

# ── 视角配置 ──
PER_HOUR_PX = 30          # 每小时像素高度
TIMELINE_HOURS = 24       # 时间轴总小时数
TIMELINE_HEIGHT = PER_HOUR_PX * TIMELINE_HOURS  # 720px
WEEK_COLS = 7             # 周视图列数
```

### Step 2：创建 `logger.py`

```python
"""统一日志系统"""
import logging
import os
import sys

def _get_log_path() -> str:
    """获取日志文件路径"""
    if getattr(sys, 'frozen', False):
        base = os.path.dirname(sys.executable)
    else:
        base = os.path.dirname(os.path.abspath(__file__))
    log_dir = os.path.join(base, 'data')
    os.makedirs(log_dir, exist_ok=True)
    return os.path.join(log_dir, 'clender.log')

def get_logger(name: str) -> logging.Logger:
    """获取命名 logger
    
    Args:
        name: logger 名称，通常使用 __name__
    
    Returns:
        配置好的 Logger 实例（输出到文件 + 控制台）
    """
    logger = logging.getLogger(name)
    
    if not logger.handlers:
        logger.setLevel(logging.DEBUG)
        
        # 文件 handler
        fh = logging.FileHandler(_get_log_path(), encoding='utf-8')
        fh.setLevel(logging.DEBUG)
        fh.setFormatter(logging.Formatter(
            '[%(asctime)s] %(name)s %(levelname)s: %(message)s',
            datefmt='%Y-%m-%d %H:%M:%S'
        ))
        
        # 控制台 handler
        ch = logging.StreamHandler()
        ch.setLevel(logging.WARNING)
        ch.setFormatter(logging.Formatter('%(levelname)s: %(message)s'))
        
        logger.addHandler(fh)
        logger.addHandler(ch)
    
    return logger
```

### Step 3：验证

- [ ] `constants.py` 创建成功，常量值与原文完全一致
- [ ] `logger.py` 创建成功，`get_logger(__name__)` 可正常调用
- [ ] 日志文件写入到 `data/clender.log`

## 测试与检查

```bash
python -c "from constants import SYSTEM_PROMPT, EVENT_COLORS, DEFAULT_CONFIG; print(len(EVENT_COLORS), len(DEFAULT_CONFIG))"
python -c "from logger import get_logger; log = get_logger('test'); log.info('测试日志')"
```

## 完成定义

- [x] `constants.py` 包含所有提取的常量，值与原文完全一致
- [x] `logger.py` 可正常工作，日志写入到 data/clender.log
- [x] 两个模块均可独立导入，无循环依赖

## 依赖

- T01（models.py — 无直接依赖，但同属基础设施层）