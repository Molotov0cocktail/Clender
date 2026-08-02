"""
事件业务服务层 - 隔离 UI 层与数据库层
所有事件相关的业务逻辑（查询、创建、修改、删除）均通过本服务调用
"""
from collections import defaultdict
from datetime import date
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
    def add_event(title: str, event_type: str, start_time: str,
                  end_time: Optional[str] = None,
                  description: str = "",
                  estimated_duration: int = 0) -> int:
        """添加事件，返回新事件 ID"""
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
        return database.update_event(event_id, **fields)

    @staticmethod
    def delete_event(event_id: int) -> bool:
        """删除事件，返回是否成功"""
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