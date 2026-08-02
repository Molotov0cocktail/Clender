"""Pure validation and formatting helpers for the daily floating window."""
from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date, datetime, time

from constants import DEFAULT_CONFIG
from models import EventType


DATETIME_FORMAT = "%Y-%m-%d %H:%M"
_TIME_PATTERN = re.compile(r"([01][0-9]|2[0-3]):[0-5][0-9]")


def _parse_time(value, fallback: str) -> time:
    text = value if isinstance(value, str) else fallback
    if _TIME_PATTERN.fullmatch(text) is None:
        text = fallback
    return datetime.strptime(text, "%H:%M").time()


def _parse_geometry(value) -> tuple[int, int, int, int] | None:
    if not isinstance(value, (list, tuple)) or len(value) != 4:
        return None
    if any(isinstance(item, bool) or not isinstance(item, int) for item in value):
        return None
    if any(not -(2 ** 31) <= item <= 2 ** 31 - 1 for item in value):
        return None
    x, y, width, height = value
    if width <= 0 or height <= 0:
        return None
    return x, y, width, height


@dataclass(frozen=True)
class FloatingWindowSettings:
    """Validated settings used by the daily floating window UI."""

    enabled: bool
    opacity_percent: int
    start_time: time
    end_time: time
    geometry: tuple[int, int, int, int] | None

    @classmethod
    def from_config(cls, value: dict) -> "FloatingWindowSettings":
        config = value if isinstance(value, dict) else {}
        enabled_value = config.get(
            "floating_window_enabled",
            DEFAULT_CONFIG["floating_window_enabled"],
        )
        enabled = (
            enabled_value
            if isinstance(enabled_value, bool)
            else DEFAULT_CONFIG["floating_window_enabled"]
        )

        opacity_value = config.get(
            "floating_window_opacity",
            DEFAULT_CONFIG["floating_window_opacity"],
        )
        if (
            isinstance(opacity_value, bool)
            or not isinstance(opacity_value, int)
            or not 30 <= opacity_value <= 100
        ):
            opacity_value = DEFAULT_CONFIG["floating_window_opacity"]

        default_start = DEFAULT_CONFIG["floating_window_start_time"]
        default_end = DEFAULT_CONFIG["floating_window_end_time"]
        start_value = _parse_time(
            config.get("floating_window_start_time"), default_start
        )
        end_value = _parse_time(
            config.get("floating_window_end_time"), default_end
        )
        if end_value <= start_value:
            start_value = _parse_time(default_start, default_start)
            end_value = _parse_time(default_end, default_end)

        geometry = _parse_geometry(config.get("floating_window_geometry"))
        return cls(enabled, opacity_value, start_value, end_value, geometry)


def day_window(
    target: date,
    settings: FloatingWindowSettings,
) -> tuple[datetime, datetime]:
    """Build the configured same-day half-open datetime range."""
    if not isinstance(target, date) or isinstance(target, datetime):
        raise TypeError("target 必须是 date")
    if not isinstance(settings, FloatingWindowSettings):
        raise TypeError("settings 必须是 FloatingWindowSettings")
    return (
        datetime.combine(target, settings.start_time),
        datetime.combine(target, settings.end_time),
    )


def _event_datetime(value) -> datetime:
    if not isinstance(value, str):
        raise ValueError("事件时间必须是字符串")
    try:
        parsed = datetime.strptime(value, DATETIME_FORMAT)
    except ValueError as exc:
        raise ValueError("事件时间格式无效") from exc
    if parsed.strftime(DATETIME_FORMAT) != value:
        raise ValueError("事件时间格式无效")
    return parsed


def format_event_time(event) -> str:
    """Format an Event time label, raising ``ValueError`` for corrupt old data."""
    start = _event_datetime(event["start_time"])
    event_type = event["event_type"]
    if event_type == EventType.REMINDER.value:
        return start.strftime("%H:%M")
    if event_type != EventType.TIMESPAN.value:
        raise ValueError("未知事件类型")

    end = _event_datetime(event.get("end_time"))
    if end <= start:
        raise ValueError("结束时间必须晚于开始时间")
    if start.date() == end.date():
        return f'{start:%H:%M}–{end:%H:%M}'
    return f'{start:%m-%d %H:%M}–{end:%m-%d %H:%M}'
