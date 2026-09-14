"""
事件业务服务层 - 隔离 UI 层与数据库层
所有事件相关的业务逻辑（查询、创建、修改、删除）均通过本服务调用
"""
from collections import defaultdict
from datetime import date, datetime, time, timedelta
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
        return EventService.get_events_date_range(d, d)

    @staticmethod
    def get_events_date_range(start: date, end: date) -> list:
        """获取日期区间内的事件"""
        if type(start) is not date or type(end) is not date:
            raise TypeError("start 和 end 必须是 date")
        if end < start:
            raise ValueError("end 不能早于 start")
        upper = datetime.max if end == date.max else datetime.combine(end, time.min) + timedelta(days=1)
        events = EventService.get_events_overlapping_range(datetime.combine(start, time.min), upper)
        if end == date.max:
            # SQLite queries use minute precision; include the final representable minute.
            by_id = {event.id: event for event in events}
            by_id.update((event.id, event) for event in database.get_events_by_date(end))
            events = sorted(by_id.values(), key=lambda event: (event.start_time, event.id))
        return events

    @staticmethod
    def get_events_overlapping_range(start: datetime, end: datetime) -> list[Event]:
        """获取与半开时间区间 ``[start, end)`` 相交的事件。"""
        if not isinstance(start, datetime) or not isinstance(end, datetime):
            raise TypeError("start 和 end 必须是 datetime")
        if end <= start:
            raise ValueError("end 必须晚于 start")
        return database.get_events_overlapping_range(start, end)

    @staticmethod
    def get_alert_candidates(now: datetime) -> list[Event]:
        return database.get_alert_candidates(now)

    @staticmethod
    def get_all_events() -> list:
        """获取所有事件"""
        return database.get_all_events()

    @staticmethod
    def get_event_counts(start: date | None = None, end: date | None = None) -> dict:
        """Count occupied days, optionally clipped to an inclusive visible date range."""
        if (start is None) != (end is None):
            raise ValueError("计数范围必须同时提供起止日期")
        if start is not None:
            if type(start) is not date or type(end) is not date:
                raise TypeError("计数范围必须是 date")
            if end < start:
                raise ValueError("计数结束日期不能早于开始日期")
        range_start, range_end = start, end
        all_events = database.get_all_events()
        counts = defaultdict(int)
        for ev in all_events:
            try:
                start = datetime.strptime(ev.start_time, "%Y-%m-%d %H:%M")
                last = start.date()
                if ev.event_type == EventType.TIMESPAN:
                    end = datetime.strptime(ev.end_time, "%Y-%m-%d %H:%M")
                    if end <= start:
                        continue
                    last = (end - timedelta(microseconds=1)).date()
                current = start.date()
                if range_start is not None:
                    current = max(current, range_start)
                    last = min(last, range_end)
                while current <= last:
                    counts[current] += 1
                    if current == last:
                        break
                    current += timedelta(days=1)
            except (TypeError, ValueError, AttributeError):
                continue
        return dict(counts)

    @staticmethod
    def validate_event(
        title: str,
        event_type: str,
        start_time: str,
        end_time: Optional[str],
        description: str,
        estimated_duration: int,
        notification_enabled: bool | None = None,
        alarm_enabled: bool = False,
        timer_minutes: int = 0,
    ) -> tuple[str, Optional[str], str, int]:
        EventService.validate_alert_policy(notification_enabled, alarm_enabled, timer_minutes)
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
    def validate_alert_policy(
        notification_enabled: bool | None = None,
        alarm_enabled: bool = False,
        timer_minutes: int = 0,
    ) -> None:
        if notification_enabled is not None and type(notification_enabled) is not bool:
            raise ValueError("notification_enabled 必须为布尔值")
        if type(alarm_enabled) is not bool:
            raise ValueError("alarm_enabled 必须为布尔值")
        if type(timer_minutes) is not int or not 0 <= timer_minutes <= 1440:
            raise ValueError("timer_minutes 必须为0–1440整数")

    @staticmethod
    def add_event(title: str, event_type: str, start_time: str,
                  end_time: Optional[str] = None,
                  description: str = "",
                  estimated_duration: int = 0,
                  notification_enabled: bool | None = None,
                  alarm_enabled: bool = False, timer_minutes: int = 0) -> int:
        """添加事件，返回新事件 ID"""
        title, end_time, description, estimated_duration = EventService.validate_event(
            title, event_type, start_time, end_time, description, estimated_duration
        )
        EventService.validate_alert_policy(notification_enabled, alarm_enabled, timer_minutes)
        if notification_enabled is None:
            notification_enabled = event_type == EventType.REMINDER.value
        return database.add_event(
            event_type=event_type,
            title=title,
            start_time=start_time,
            end_time=end_time,
            description=description,
            estimated_duration=estimated_duration,
            notification_enabled=notification_enabled,
            alarm_enabled=alarm_enabled,
            timer_minutes=timer_minutes,
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
            'estimated_duration', 'notification_enabled', 'alarm_enabled', 'timer_minutes',
        }
        unknown = set(fields) - allowed
        if unknown:
            raise ValueError(f"不允许更新字段: {', '.join(sorted(unknown))}")
        merged = existing.to_dict()
        merged.update(fields)
        EventService.validate_alert_policy(
            merged.get("notification_enabled"),
            merged.get("alarm_enabled", False),
            merged.get("timer_minutes", 0),
        )
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
        if all(existing.to_dict().get(key) == value for key, value in normalized.items()):
            return 0
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
