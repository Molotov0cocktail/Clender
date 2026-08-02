# T01 — 创建数据模型 (models.py)

## 目标

创建 `models.py`，定义 `Event`、`Message`、`Conversation` 三个 dataclass 以及 `EventType` 枚举，替代现有代码中裸 dict 传递数据的方式。

## 输入文档

- `doc/proposal.md` — 了解现有 dict 数据契约问题
- `doc/high-level-design.md` §4.1 — events 表结构
- `doc/detailed-design.md` §2.1

## 预计变更文件

| 操作 | 文件 | 说明 |
|------|------|------|
| ✨ 新建 | `models.py` | 数据类定义 |

## 实现步骤

### Step 1：创建 `models.py`

```python
from dataclasses import dataclass, field
from enum import Enum
from typing import Optional
from datetime import date

class EventType(Enum):
    REMINDER = "reminder"
    TIMESPAN = "timespan"

@dataclass
class Event:
    id: int = 0
    event_type: EventType = EventType.REMINDER
    title: str = ""
    start_time: str = ""          # "YYYY-MM-DD HH:MM"
    end_time: Optional[str] = None
    description: str = ""
    estimated_duration: int = 0   # 分钟
    created_at: str = ""

    @classmethod
    def from_row(cls, row: dict) -> "Event":
        """从 sqlite3.Row 转换的 dict 构造 Event 对象"""
        return cls(
            id=row.get("id", 0),
            event_type=EventType(row.get("event_type", "reminder")),
            title=row.get("title", ""),
            start_time=row.get("start_time", ""),
            end_time=row.get("end_time"),
            description=row.get("description", ""),
            estimated_duration=row.get("estimated_duration", 0) or 0,
            created_at=row.get("created_at", ""),
        )

    def to_dict(self) -> dict:
        """转为 dict（用于序列化到 AI 上下文）"""
        return {
            "id": self.id,
            "event_type": self.event_type.value,
            "title": self.title,
            "start_time": self.start_time,
            "end_time": self.end_time,
            "description": self.description,
            "estimated_duration": self.estimated_duration,
        }

    @property
    def date(self) -> Optional[date]:
        """从 start_time 提取日期部分"""
        try:
            from datetime import datetime
            return datetime.strptime(self.start_time[:10], "%Y-%m-%d").date()
        except (ValueError, IndexError):
            return None

@dataclass
class Message:
    role: str
    content: str
    timestamp: str = ""

@dataclass
class Conversation:
    id: str
    title: str = "新对话"
    messages: list = field(default_factory=list)
    created_at: str = ""
    token_count: int = 0

    def add_message(self, role: str, content: str):
        from datetime import datetime
        self.messages.append(Message(
            role=role,
            content=content,
            timestamp=datetime.now().isoformat()
        ))

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "title": self.title,
            "messages": [{"role": m.role, "content": m.content, "timestamp": m.timestamp} for m in self.messages],
            "created_at": self.created_at,
        }

    @classmethod
    def from_dict(cls, d: dict) -> "Conversation":
        c = cls(id=d["id"], title=d["title"], created_at=d.get("created_at", ""))
        for msg in d.get("messages", []):
            c.messages.append(Message(
                role=msg.get("role", "user"),
                content=msg.get("content", ""),
                timestamp=msg.get("timestamp", ""),
            ))
        return c
```

### Step 2：验证

- [ ] 文件创建成功，无语法错误
- [ ] `Event.from_row()` 能正确解析现有 database 返回的 dict
- [ ] `Event.date` 属性正确提取日期
- [ ] `Conversation.from_dict()` / `to_dict()` 往返转换一致

## 测试与检查

```bash
python -c "from models import Event, EventType; e = Event(title='测试', event_type=EventType.REMINDER); print(e.to_dict())"
```

## 完成定义

- [x] `models.py` 创建成功
- [x] `Event`, `EventType`, `Message`, `Conversation` 四个类可用
- [x] `Event.from_row()` 兼容现有 `sqlite3.Row` → `dict(row)` 格式
- [x] 无语法错误

## 依赖

- 无（Phase 1 首个任务）