"""
事件业务服务层 - 隔离 UI 层与数据库层
所有事件相关的业务逻辑（查询、创建、修改、删除）均通过本服务调用
"""
from collections import defaultdict
from datetime import date, datetime
from typing import Optional

import database
from models import Event, EventType
from logger import get_logger

_log = get_logger(__name__)


class EventService:
    """事件业务服务 - 纯静态方法，无状态，不依赖 PyQt5"""

    @staticmethod
    def get_events_by_date(d: date) -> list:
        """获取指定日期的所有事件"""
        return database.get_events_by_date(d)

    @staticmethod
    def get_events_date_range(start: date, end: date) -> list:
        """获取日期区间内的事件"""
        return database.get_events_date_range(start, end)

    @staticmethod
    def get_events_overlapping_range(start: datetime, end: datetime) -> list[Event]:
        """获取与半开时间区间 ``[start, end)`` 相交的事件。"""
        if not isinstance(start, datetime) or not isinstance(end, datetime):
            raise TypeError("start 和 end 必须是 datetime")
        if end <= start:
            raise ValueError("end 必须晚于 start")
        return database.get_events_overlapping_range(start, end)

    @staticmethod
    def get_all_events() -> list:
        """获取所有事件"""
        return database.get_all_events()

    @staticmethod
    def get_event_counts() -> dict:
        """统计每天的事件数量（用于日历标记点）"""
        all_events = database.get_all_events()
        counts = defaultdict(int)
        for ev in all_events:
            ev_date = ev.date
            if ev_date:
                counts[ev_date] += 1
        return dict(counts)

    @staticmethod
    def validate_event(
        title: str,
        event_type: str,
        start_time: str,
        end_time: Optional[str],
        description: str,
        estimated_duration: int,
    ) -> tuple[str, Optional[str], str, int]:
        if not isinstance(title, str) or not title.strip():
            raise ValueError("事件标题不能为空")
        if event_type not in (EventType.REMINDER.value, EventType.TIMESPAN.value):
            raise ValueError(f"未知事件类型: {event_type}")
        if not isinstance(description, str):
            raise ValueError("事件描述必须是字符串")
        if isinstance(estimated_duration, bool) or not isinstance(estimated_duration, int):
            raise ValueError("预计时长必须是整数")
        if estimated_duration < 0:
            raise ValueError("预计时长不能为负数")
        try:
            start = datetime.strptime(start_time, "%Y-%m-%d %H:%M")
        except (TypeError, ValueError) as exc:
            raise ValueError("开始时间格式必须为 YYYY-MM-DD HH:MM") from exc

        normalized_end = None
        if event_type == EventType.TIMESPAN.value:
            if not end_time:
                raise ValueError("时间段事件必须包含结束时间")
            try:
                end = datetime.strptime(end_time, "%Y-%m-%d %H:%M")
            except (TypeError, ValueError) as exc:
                raise ValueError("结束时间格式必须为 YYYY-MM-DD HH:MM") from exc
            if end <= start:
                raise ValueError("结束时间必须晚于开始时间")
            normalized_end = end_time
        return title.strip(), normalized_end, description, estimated_duration

    @staticmethod
    def add_event(title: str, event_type: str, start_time: str,
                  end_time: Optional[str] = None,
                  description: str = "",
                  estimated_duration: int = 0) -> int:
        """添加事件，返回新事件 ID"""
        title, end_time, description, estimated_duration = EventService.validate_event(
            title, event_type, start_time, end_time, description, estimated_duration
        )
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
        """更新事件字段，返回受影响行数"""
        if isinstance(event_id, bool) or not isinstance(event_id, int) or event_id <= 0:
            raise ValueError("event_id 必须是正整数")
        existing = database.get_event_by_id(event_id)
        if existing is None:
            return 0
        allowed = {
            'event_type',
            'title',
            'start_time',
            'end_time',
            'description',
            'estimated_duration',
        }
        unknown = set(fields) - allowed
        if unknown:
            raise ValueError(f"不允许更新字段: {', '.join(sorted(unknown))}")
        merged = existing.to_dict()
        merged.update(fields)
        title, end_time, description, estimated_duration = EventService.validate_event(
            merged['title'], merged['event_type'], merged['start_time'],
            merged.get('end_time'), merged.get('description', ''),
            merged.get('estimated_duration', 0),
        )
        normalized = dict(fields)
        if 'event_type' in fields:
            normalized['event_type'] = merged['event_type']
        if 'title' in fields:
            normalized['title'] = title
        if merged['event_type'] == EventType.REMINDER.value and (
            'event_type' in fields or 'end_time' in fields
        ):
            # Type conversion must not retain a stale timespan end.  Passing
            # None explicitly is part of the database update contract.
            normalized['end_time'] = None
        elif 'end_time' in fields:
            normalized['end_time'] = end_time
        if 'description' in fields:
            normalized['description'] = description
        if 'estimated_duration' in fields:
            normalized['estimated_duration'] = estimated_duration
        return database.update_event(event_id, **normalized)

    @staticmethod
    def delete_event(event_id: int) -> bool:
        """删除事件，返回是否成功"""
        if isinstance(event_id, bool) or not isinstance(event_id, int) or event_id <= 0:
            raise ValueError("event_id 必须是正整数")
        return database.delete_event(event_id)

    @staticmethod
    def get_event_by_id(event_id: int) -> Optional[Event]:
        """按 ID 获取事件"""
        return database.get_event_by_id(event_id)

    @staticmethod
    def load_sample_data_if_empty() -> None:
        """首次运行时加载示例日程数据"""
        if database.is_sample_loaded():
            return
        all_ev = database.get_all_events()
        if all_ev:
            database.mark_sample_loaded()
            return

        today = date.today()
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
        _log.info("示例日程数据加载完成")
