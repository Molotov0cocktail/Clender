# T04 — 创建事件业务服务 (event_service.py)

## 目标

创建 `event_service.py`，封装所有事件相关的业务逻辑，作为 UI 层与数据库层之间的隔离层。

## 输入文档

- `doc/detailed-design.md` §4.1
- `T01-models.md` — Event 类
- `T03-database-refactor.md` — 重构后的 database.py

## 预计变更文件

| 操作 | 文件 | 说明 |
|------|------|------|
| ✨ 新建 | `event_service.py` | 事件业务服务 |

## 实现步骤

### Step 1：创建 EventService 类

```python
"""事件业务服务层 - 隔离 UI 与 database"""
from datetime import date
from typing import Optional

import database
from models import Event, EventType

class EventService:
    """事件业务服务 - 纯静态方法，无状态"""

    @staticmethod
    def get_events_by_date(d: date) -> list[Event]:
        """获取指定日期的事件列表"""
        return database.get_events_by_date(d)

    @staticmethod
    def get_all_events() -> list[Event]:
        """获取所有事件"""
        return database.get_all_events()

    @staticmethod
    def get_event_counts() -> dict[date, int]:
        """统计每天的事件数量"""
        from collections import defaultdict
        all_events = database.get_all_events()
        counts = defaultdict(int)
        for ev in all_events:
            ev_date = ev.date
            if ev_date:
                counts[ev_date] += 1
        return dict(counts)

    @staticmethod
    def add_event(title: str, event_type: str, start_time: str,
                  end_time: Optional[str] = None,
                  description: str = "",
                  estimated_duration: int = 0) -> int:
        """添加事件，返回新事件ID"""
        return database.add_event(
            event_type=event_type,
            title=title,
            start_time=start_time,
            end_time=end_time,
            description=description,
            estimated_duration=estimated_duration,
        )

    @staticmethod
    def update_event(event_id: int, **fields) -> int:
        """更新事件字段"""
        return database.update_event(event_id, **fields)

    @staticmethod
    def delete_event(event_id: int) -> bool:
        """删除事件"""
        return database.delete_event(event_id)

    @staticmethod
    def get_event_by_id(event_id: int) -> Optional[Event]:
        """按ID获取事件"""
        return database.get_event_by_id(event_id)

    @staticmethod
    def load_sample_data_if_empty() -> None:
        """首次运行时加载示例数据"""
        if database.is_sample_loaded():
            return
        all_ev = database.get_all_events()
        if all_ev:
            database.mark_sample_loaded()
            return

        from datetime import date as dt_date
        today = dt_date.today()
        today_str = today.isoformat()
        samples = [
            ('reminder', '晨会', f'{today_str} 09:00', None, '每日站会，汇报昨日进展'),
            ('timespan', '项目开发', f'{today_str} 10:00', f'{today_str} 12:00', '新功能开发与代码审查'),
            ('reminder', '午餐', f'{today_str} 12:00', None, '与团队成员一起'),
            ('reminder', '项目周会', f'{today_str} 14:30', None, '准备本周进度汇报'),
            ('timespan', '文档编写', f'{today_str} 15:00', f'{today_str} 17:00', '编写技术文档与API说明'),
            ('reminder', '下班提醒', f'{today_str} 18:00', None, '整理当天工作，规划明日任务'),
        ]
        for ev_type, title, start, end, desc in samples:
            database.add_event(
                event_type=ev_type, title=title,
                start_time=start, end_time=end, description=desc,
            )
        database.mark_sample_loaded()
```

### Step 2：提取 load_sample_data_if_empty

此方法将 `main.py:237-260` 中的示例数据加载逻辑迁移到 Service 层，主窗口调用方简化为 `EventService.load_sample_data_if_empty()`。

## 测试与检查

```bash
python -c "from event_service import EventService; from datetime import date; events = EventService.get_events_by_date(date.today()); print(len(events))"
```

## 完成定义

- [x] `event_service.py` 创建成功
- [x] 所有方法正确委托给 `database.*`
- [x] `get_event_counts()` 返回与原始代码一致的结果
- [x] `load_sample_data_if_empty()` 逻辑与原始 `main.py` 一致

## 依赖

- T01（Event 类）
- T03（重构后的 database.py）