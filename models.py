"""
数据模型定义 - Event, Message, Conversation 及 EventType 枚举
替代现有代码中裸 dict 传递数据的方式
"""
from dataclasses import dataclass, field
from enum import Enum
from typing import Optional
from datetime import date, datetime
from uuid import uuid4


class EventType(Enum):
    """事件类型枚举"""
    REMINDER = "reminder"
    TIMESPAN = "timespan"


@dataclass
class Event:
    """数据库事件模型 - 替代裸 dict，兼容 dict 访问"""
    id: int = 0
    event_type: EventType = EventType.REMINDER
    title: str = ""
    start_time: str = ""          # "YYYY-MM-DD HH:MM"
    end_time: Optional[str] = None
    description: str = ""
    estimated_duration: int = 0   # 分钟
    created_at: str = ""

    notification_enabled: bool = False
    alarm_enabled: bool = False
    timer_minutes: int = 0

    # ── dict 兼容层（向后兼容现有 dict 访问代码）──
    _DICT_MAP = {
        "id": "id", "event_type": "event_type", "title": "title",
        "start_time": "start_time", "end_time": "end_time",
        "description": "description", "estimated_duration": "estimated_duration",
        "created_at": "created_at",
        "notification_enabled": "notification_enabled", "alarm_enabled": "alarm_enabled",
        "timer_minutes": "timer_minutes",
    }

    def __getitem__(self, key: str):
        """支持 ev['title'] 等 dict 式访问"""
        attr = self._DICT_MAP.get(key)
        if attr is None:
            raise KeyError(key)
        val = getattr(self, attr)
        if key == "event_type":
            return val.value  # 返回字符串 "reminder"/"timespan"
        return val

    def __contains__(self, key: str) -> bool:
        return key in self._DICT_MAP

    def get(self, key: str, default=None):
        """支持 ev.get('end_time') 等 dict 方法"""
        try:
            return self[key]
        except KeyError:
            return default

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
            notification_enabled=bool(row.get("notification_enabled", False)),
            alarm_enabled=bool(row.get("alarm_enabled", False)),
            timer_minutes=row.get("timer_minutes", 0),
        )

    def to_dict(self) -> dict:
        """转为 dict（用于序列化到 AI 上下文等场景）"""
        return {
            "id": self.id,
            "event_type": self.event_type.value,
            "title": self.title,
            "start_time": self.start_time,
            "end_time": self.end_time,
            "description": self.description,
            "estimated_duration": self.estimated_duration,
            "notification_enabled": self.notification_enabled,
            "alarm_enabled": self.alarm_enabled,
            "timer_minutes": self.timer_minutes,
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
    """对话消息模型"""
    role: str
    content: str
    timestamp: str = ""


@dataclass
class Conversation:
    """AI 对话模型"""
    id: str
    title: str = "新对话"
    messages: list = field(default_factory=list)
    created_at: str = ""
    token_count: int = 0

    @classmethod
    def new(cls, title: str = "新对话") -> "Conversation":
        """Create a conversation with a collision-resistant short ID."""
        return cls(id=uuid4().hex[:12], title=title, created_at=datetime.now().isoformat())

    def add_message(self, role: str, content: str) -> None:
        """添加一条消息到对话中"""
        self.messages.append(Message(
            role=role,
            content=content,
            timestamp=datetime.now().isoformat()
        ))

    def to_dict(self) -> dict:
        """序列化为 dict（用于持久化到 JSON）"""
        return {
            "id": self.id,
            "title": self.title,
            "messages": [
                {"role": m.role, "content": m.content, "timestamp": m.timestamp}
                for m in self.messages
            ],
            "created_at": self.created_at,
            "token_count": self.token_count,
        }

    @classmethod
    def from_dict(cls, d: dict) -> "Conversation":
        """从 dict 构造 Conversation（从 JSON 反序列化）"""
        c = cls(
            id=d["id"],
            title=d["title"],
            created_at=d.get("created_at", ""),
            token_count=int(d.get("token_count", 0) or 0),
        )
        for msg in d.get("messages", []):
            c.messages.append(Message(
                role=msg.get("role", "user"),
                content=msg.get("content", ""),
                timestamp=msg.get("timestamp", ""),
            ))
        return c
