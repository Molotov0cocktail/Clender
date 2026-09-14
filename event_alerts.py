"""Local system-notification delivery while the application is running."""
from datetime import datetime, timedelta
from collections.abc import Callable

import database
from event_service import EventService


class EventAlertDispatcher:
    def __init__(self, deliver: Callable[[str, str], bool]) -> None:
        self.deliver = deliver

    def poll(self, now: datetime | None = None) -> None:
        now = now or datetime.now()
        for event in EventService.get_alert_candidates(now):
            try:
                start = datetime.strptime(event.start_time, '%Y-%m-%d %H:%M')
                triggers = []
                if event.notification_enabled or event.alarm_enabled:
                    triggers.append(('start', start))
                if event.timer_minutes:
                    triggers.append((
                        'timer', start + timedelta(minutes=event.timer_minutes)
                    ))
                for kind, due in triggers:
                    signature = f'{kind}:{due.isoformat()}'
                    if not timedelta(0) <= now - due < timedelta(minutes=1):
                        continue
                    if database.has_alert_receipt(event.id, signature):
                        continue
                    title = '计时结束' if kind == 'timer' else '日程提醒'
                    if self.deliver(title, f'{event.title}\n{due:%Y-%m-%d %H:%M}'):
                        database.record_alert_receipt(event.id, signature)
            except (ValueError, TypeError, OverflowError):
                continue
