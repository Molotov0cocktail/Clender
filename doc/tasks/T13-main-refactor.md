# T13 — 重构主窗口入口 (ui/main_window.py)

## 目标

将 `main.py` 中的 `MainWindow` 类迁移到 `ui/main_window.py`，精简 `main.py` 为纯入口点。
显式调用 `database.init_db()`，通过 `EventService` 访问数据而非直接查 database。

## 输入文档

- `doc/detailed-design.md` §5.8
- `main.py` — 完整文件
- `T04-event-service.md` — EventService
- `T10-calendar-refactor.md` — ui/calendar_widget
- `T11-event-manager-refactor.md` — ui/event_manager
- `T12-ai-chat-refactor.md` — ui/ai_chat_widget

## 预计变更文件

| 操作 | 文件 | 说明 |
|------|------|------|
| ✨ 新建 | `ui/main_window.py` | 主窗口 |
| ✏️ 修改 | `main.py` | 精简为入口点 |

## 实现步骤

### Step 1：创建 ui/main_window.py

将 `MainWindow` 类（24-271行）迁移到 `ui/main_window.py`。

**关键变更**：

```python
# 旧导入
import database
import config as cfg_mod
from calendar_widget import CalendarWidget
from event_manager import EventManager
from ai_chat import AIChatWidget

# 新导入
import config as cfg_mod
import theme_manager
from event_service import EventService
from ui.calendar_widget import CalendarWidget
from ui.event_manager import EventManager
from ui.ai_chat_widget import AIChatWidget
from ui.ai_settings import SettingsDialog
import database  # 仅用于 init_db() 显式调用
```

### Step 2：_get_event_counts 替换

```python
# 旧
def _get_event_counts(self) -> dict:
    all_events = database.get_all_events()
    counts = defaultdict(int)
    for ev in all_events:
        try:
            date_str = ev['start_time'][:10]
            ...
        except:
            ...

# 新
def _get_event_counts(self) -> dict:
    return EventService.get_event_counts()
```

### Step 3：_load_sample_data_if_empty 替换

```python
# 旧（main.py 237-260行 — 整个方法体）
def _load_sample_data_if_empty(self):
    if database.is_sample_loaded(): return
    ...

# 新
def _load_sample_data_if_empty(self):
    EventService.load_sample_data_if_empty()
```

### Step 4：精简 main.py 为入口点

```python
"""Clender - 智能日程管理桌面应用 入口点"""
import sys
from PyQt5.QtWidgets import QApplication
from PyQt5.QtGui import QFont
import database
from ui.main_window import MainWindow

def main():
    app = QApplication(sys.argv)
    app.setApplicationName('Clender')
    app.setOrganizationName('ClenderApp')
    app.setQuitOnLastWindowClosed(False)
    
    database.init_db()  # 显式调用！
    
    font = QFont('Microsoft YaHei', 10)
    app.setFont(font)
    
    window = MainWindow(app)
    window.show()
    
    sys.exit(app.exec_())

if __name__ == '__main__':
    main()
```

### Step 5：验证

- [ ] `python main.py` 正常启动
- [ ] `Events.date` 属性访问在 `_get_event_counts` 中正常工作（不同 dict 方式）
- [ ] 示例数据加载正常

## 完成定义

- [x] `ui/main_window.py` 不直接 import database（除 init_db 外）
- [x] `_get_event_counts` 委托给 EventService
- [x] `_load_sample_data_if_empty` 委托给 EventService
- [x] `main.py` 精简为 ≤ 30 行入口点
- [x] 应用启动功能完全不变

## 依赖

- T10（ui/calendar_widget）
- T11（ui/event_manager）
- T12（ui/ai_chat_widget）