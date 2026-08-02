# 详细设计：模块职责与接口设计

> **版本**：1.0  
> **基于**：proposal.md v1.0 / high-level-design.md v1.0  
> **原则**：不改变用户可见的功能与 UI 细节，只重构代码内部结构。

---

## 1. 目标架构

### 1.1 分层架构

```
┌──────────────────────────────────────────────────────────────┐
│                     UI 层 (ui/ 包)                            │
│  main_window.py  │  calendar_widget.py  │  event_manager.py  │
│                  │  ai_chat_widget.py    │  ai_settings.py   │
│                  │  sidebar.py           │  canvas.py        │
├──────────────────────────────────────────────────────────────┤
│                     业务服务层 (service/)                      │
│  event_service.py  │  ai_service.py  │  conversation_store.py│
├──────────────────────────────────────────────────────────────┤
│                     数据访问层                                 │
│  database.py (纯SQLite CRUD)  │  config.py (纯JSON读写)       │
├──────────────────────────────────────────────────────────────┤
│                     基础设施层                                 │
│  models.py (数据类)  │  constants.py  │  theme_manager.py     │
│  ai_client.py (HTTP) │  logger.py     │                       │
└──────────────────────────────────────────────────────────────┘
```

**核心规则**：
- 上层只能导入相邻下层（UI → Service → Data Access）
- 基础设施层被所有层共享
- **禁止** Data Access 层向上导入 Service/UI 层
- **禁止** Service 层导入 UI 层

### 1.2 文件结构变更

```
Clender/
├── main.py                          # 入口点（精简，仅启动逻辑）
├── build.py                         # 打包脚本（不变）
├── requirements.txt                 # 依赖（不变）
├── config.py                        # 配置管理（增强）
├── database.py                      # 数据库CRUD（重构）
├── theme_manager.py                 # 主题管理（结构化）
├── models.py                        # [新] 数据类定义
├── constants.py                     # [新] 常量提取
├── logger.py                        # [新] 日志系统
├── event_service.py                 # [新] 事件业务服务
├── ai_service.py                    # [新] AI业务服务
├── ai_client.py                     # [新] AI HTTP客户端
├── conversation_store.py            # [新] 对话持久化
├── ui/                              # [新] UI包
│   ├── __init__.py
│   ├── main_window.py               # 主窗口
│   ├── calendar_widget.py           # 日历视图
│   ├── canvas.py                    # Canvas渲染组件
│   ├── event_manager.py             # 事项管理面板
│   ├── event_dialog.py              # 编辑对话框
│   ├── ai_chat_widget.py            # AI对话组件
│   ├── ai_settings.py               # AI设置对话框
│   └── sidebar.py                   # 对话侧栏
└── data/                            # 运行时数据
    ├── clender.db
    ├── config.json
    ├── conversations.json
    └── sample_loaded.flag
```

---

## 2. 基础设施层设计

### 2.1 `models.py` — 数据类

**职责**：定义项目中所有核心数据结构，替代裸 dict 传递。

```python
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Optional

class EventType(Enum):
    REMINDER = "reminder"
    TIMESPAN = "timespan"

@dataclass
class Event:
    """数据库事件模型"""
    id: int = 0                                # 0 表示未持久化
    event_type: EventType = EventType.REMINDER
    title: str = ""
    start_time: str = ""                       # "YYYY-MM-DD HH:MM"
    end_time: Optional[str] = None
    description: str = ""
    estimated_duration: int = 0                # 分钟
    created_at: str = ""

    @staticmethod
    def from_row(row: dict) -> "Event":
        """从 sqlite3.Row dict 构造"""
        ...

    def to_dict(self) -> dict:
        """转为 dict（用于序列化）"""
        ...

@dataclass
class Message:
    role: str                                  # "user" | "assistant" | "think"
    content: str
    timestamp: str = ""

@dataclass
class Conversation:
    id: str
    title: str = "新对话"
    messages: list[Message] = field(default_factory=list)
    created_at: str = ""
    token_count: int = 0
```

**接口契约**：
- 所有 Service 层方法接受/返回 `Event` / `Conversation` 对象
- `database.py` 内部在 row → `Event` 转换时使用 `Event.from_row()`
- AI 操作执行时传递 `Event` 而非裸 dict

---

### 2.2 `constants.py` — 常量定义

**职责**：集中管理所有硬编码常量。

```python
# 系统提示词
SYSTEM_PROMPT = """你是智能日程管理助手..."""

# 模型能力映射
MODEL_CAPABILITIES: dict[str, tuple[int, int]] = {
    "deepseek-v4-pro": (1048576, 65536),
    ...
}

# 事件颜色
EVENT_COLORS: list[str] = [
    '#58a6ff', '#7c6ff7', '#3fb950', ...
]

# 默认配置
DEFAULT_CONFIG: dict = { ... }
```

---

### 2.3 `logger.py` — 日志系统

**职责**：提供统一的日志记录。

```python
import logging

def get_logger(name: str) -> logging.Logger:
    """获取命名 logger，输出到文件和控制台"""
    ...
```

---

### 2.4 `theme_manager.py` — 主题系统（重构）

**职责**：主题定义 + 全局样式应用，从扁平字典 → 结构化 Theme 类。

```python
@dataclass
class ThemeColors:
    """主题颜色数据类，类型安全访问"""
    app_bg: str
    frame_bg: str
    ...

class Theme:
    LIGHT: ThemeColors
    DARK: ThemeColors

    @staticmethod
    def current() -> ThemeColors:
        ...

    @staticmethod
    def apply(app: QApplication) -> None:
        ...

    @staticmethod
    def switch(app: QApplication, theme_name: str) -> None:
        ...
```

**渐进策略**：先保持现有 dict 结构不变，仅添加 `ThemeColors` 包装类提供类型安全访问。后续版本可完全迁移到 dataclass。

---

## 3. 数据访问层设计

### 3.1 `database.py` — 数据库层（重构）

**职责**：纯 SQLite CRUD，无副作用，不在导入时执行 `init_db()`。

**变更**：
1. 移除模块末尾的 `init_db()` 自动调用
2. `init_db()` 改为**显式调用**（由 `main.py` 在启动时调用）
3. 所有函数添加类型注解，返回 `Event` 对象或 `list[Event]`
4. `update_event()` 使用白名单字段校验，杜绝拼接非预期字段名

```python
def init_db() -> None: ...
def add_event(event: Event) -> int: ...                   # 返回新 event_id
def update_event(event_id: int, **fields) -> int: ...     # 返回影响行数
def delete_event(event_id: int) -> bool: ...
def get_events_by_date(d: date) -> list[Event]: ...
def get_events_date_range(start: date, end: date) -> list[Event]: ...
def get_all_events() -> list[Event]: ...
def get_event_by_id(event_id: int) -> Optional[Event]: ...
```

---

### 3.2 `config.py` — 配置管理（增强）

**职责**：JSON 配置的读写，添加验证。

**变更**：
1. 移除模块顶层的 `DEFAULT_CONFIG`（迁移到 `constants.py` 或保留）
2. 添加 `validate_config()` 函数
3. 添加 `get_api_config()` 返回命名元组

---

## 4. 服务层设计

### 4.1 `event_service.py` — 事件业务服务

**职责**：封装所有事件相关的业务逻辑，隔离 UI 与 database。

```python
class EventService:
    """事件业务服务 - 无状态，纯函数集合"""

    @staticmethod
    def get_events_by_date(d: date) -> list[Event]: ...
    
    @staticmethod
    def get_all_events() -> list[Event]: ...
    
    @staticmethod
    def get_event_counts() -> dict[date, int]: ...
    
    @staticmethod
    def add_event(event: Event) -> int: ...
    
    @staticmethod
    def update_event(event_id: int, **fields) -> int: ...
    
    @staticmethod
    def delete_event(event_id: int) -> bool: ...
    
    @staticmethod
    def load_sample_data_if_empty() -> None: ...
```

**触发通知**：EventService 不发射 PyQt5 信号。数据变更通知仍由 UI 层的信号机制处理（EventManager.data_changed → MainWindow._on_data_changed）。

---

### 4.2 `conversation_store.py` — 对话持久化

**职责**：Conversation 的加载/保存，从 `ai_chat.py` 提取。

```python
def load_conversations() -> dict[str, Conversation]: ...
def save_conversations(convs: dict[str, Conversation]) -> None: ...
```

---

### 4.3 `ai_client.py` — AI HTTP 客户端

**职责**：纯 HTTP 调用，无 UI 依赖，无 database 依赖。

```python
def fetch_models_list() -> tuple[list[str], Optional[str]]: ...

class AICallThread(QThread):
    """在子线程发起 API 调用，通过信号回传结果"""
    result_ready = pyqtSignal(dict)
    error_occurred = pyqtSignal(str)

    def __init__(self, messages: list[dict], parent=None): ...
    def run(self) -> None: ...
```

---

### 4.4 `ai_service.py` — AI 业务服务

**职责**：上下文构建、Token 估算、响应解析、操作执行编排。

```python
class AIService:
    """AI 业务服务"""

    @staticmethod
    def build_context_messages(conversation: Conversation) -> tuple[list[dict], dict]: ...
    
    @staticmethod
    def estimate_tokens(text: str) -> int: ...
    
    @staticmethod
    def count_messages_tokens(msgs: list[dict]) -> int: ...
    
    @staticmethod
    def guess_model_capabilities(model_id: str) -> tuple[int, int]: ...
    
    @staticmethod
    def parse_ai_response(content: str) -> dict: ...
    
    @staticmethod
    def execute_operations(ops: list[dict]) -> list[str]: ...
```

**execute_operations 变更**：原实现直接调用 `database.*`，重构后通过 `EventService` 调用。

---

## 5. UI 层设计

### 5.1 `ui/canvas.py` — Canvas 渲染组件

**职责**：`WeekCanvas` / `DayCanvas` 从 `calendar_widget.py` 的内嵌类提取为独立的可测试 QFrame 子类。

```python
class WeekCanvas(QFrame):
    """周视图 Canvas - QPainter 绘制"""
    def __init__(self, blocks: list[dict], timeline_height: int, 
                 per_hour: int, theme: dict, parent=None): ...
    def paintEvent(self, event) -> None: ...

class DayCanvas(QFrame):
    """日视图 Canvas - QPainter 绘制"""
    def __init__(self, blocks: list[dict], timeline_height: int, 
                 per_hour: int, theme: dict, parent=None): ...
    def paintEvent(self, event) -> None: ...
```

---

### 5.2 `ui/ai_settings.py` — AI 设置对话框

**职责**：从 `ai_chat.py` 提取 `SettingsDialog` 类。

---

### 5.3 `ui/sidebar.py` — 对话侧栏

**职责**：从 `ai_chat.py` 提取 `ConversationSidebar` 类。

---

### 5.4 `ui/event_dialog.py` — 编辑对话框

**职责**：从 `event_manager.py` 提取 `EventDialog` 类。

---

### 5.5 `ui/calendar_widget.py` — 日历视图

**变更**：
- 不再直接 `import database`，改用 `event_service.EventService`
- Canvas 类改为从 `ui.canvas` 导入
- `_render_week_view()` / `_render_day_view()` 简化

---

### 5.6 `ui/event_manager.py` — 事项管理面板

**变更**：
- 不再直接 `import database`，改用 `event_service.EventService`
- `EventDialog` 从 `ui.event_dialog` 导入

---

### 5.7 `ui/ai_chat_widget.py` — AI 对话组件

**变更**：
- 不再导入 `config` / `database`，改用 `AIService` + `ConversationStore`
- `ConversationSidebar` / `SettingsDialog` 从独立文件导入
- `AICallThread` 从 `ai_client` 导入

---

### 5.8 `ui/main_window.py` — 主窗口

**变更**：
- 显式调用 `init_db()` 在启动时
- 通过 `EventService` 获取数据而非直接查 database
- 子组件从 `ui.*` 导入

---

## 6. 数据流变更

### 6.1 重构前（现状）

```
UI Widget → database.add_event(dict)
UI Widget → database.get_events_by_date(date) → list[dict]
```

### 6.2 重构后

```
UI Widget → EventService.add_event(Event) → database.add_event(Event)
UI Widget → EventService.get_events_by_date(date) → list[Event]
```

**差异**：
- 中间经过 Service 层，可在此处添加验证、日志、缓存等逻辑
- 数据类型从 `dict` 变为 `Event`，IDE 可提供自动补全和类型检查

---

## 7. 风险缓解映射

| 原风险编号 | 风险描述 | 缓解措施 | 对应任务 |
|-----------|---------|---------|---------|
| R1 | ai_chat.py 900行 | 拆分为 6 个文件 | T06-T08, T12 |
| R2 | UI 直达 database | 插入 EventService 层 | T04, T10-T13 |
| R3 | Canvas 内嵌类 | 提取到 ui/canvas.py | T09 |
| R4 | dict 数据契约 | 引入 Event dataclass | T01 |
| R5 | init_db() 副作用 | 改为显式调用 | T03 |
| R6 | 线程安全 | ai_client 封装线程 + 信号 | T07 |
| R7 | API Key 泄露 | .gitignore + 安全指南 | T14 |
| R8 | 无日志 | 引入 logger.py | T02 |

---

## 8. 迁移策略

采用 **绞杀者模式（Strangler Fig）**：
1. 先新建模块（models, constants, logger, services, ui/）
2. 逐步修改现有代码的 import 指向新模块
3. 旧代码在确认新代码稳定后再删除
4. 每完成一个任务后运行一次完整的应用启动测试

**回滚方案**：Git 分支保护，每个任务在独立分支开发，合并前验证。