"""Pure calendar layout helpers shared by week and day views."""
from __future__ import annotations

from datetime import date, datetime
from typing import Iterable

from constants import EVENT_COLORS


DATETIME_FORMAT = "%Y-%m-%d %H:%M"


def merge_ranges(ranges: Iterable[tuple[float, float]]) -> list[tuple[float, float]]:
    """Merge overlapping or touching numeric ranges."""
    merged: list[tuple[float, float]] = []
    for start, end in sorted(ranges, key=lambda item: item[0]):
        if end <= start:
            continue
        if merged and start <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(merged[-1][1], end))
        else:
            merged.append((start, end))
    return merged


def _add_overlap_ranges(blocks: list[dict]) -> None:
    for block in blocks:
        block["overlap_ranges"] = []
    for index, first in enumerate(blocks):
        for second in blocks[index + 1:]:
            if first["col"] != second["col"]:
                continue
            first_bottom = first["top"] + first["height"]
            second_bottom = second["top"] + second["height"]
            if first["top"] < second_bottom and second["top"] < first_bottom:
                overlap = (
                    max(first["top"], second["top"]),
                    min(first_bottom, second_bottom),
                )
                first["overlap_ranges"].append(overlap)
                second["overlap_ranges"].append(overlap)
    for block in blocks:
        block["overlap_ranges"] = merge_ranges(block["overlap_ranges"])


def _build_blocks(
    events: Iterable,
    *,
    per_hour: int,
    minimum_height: int,
    marker_ratio: float,
    week_start: date | None,
) -> list[dict]:
    blocks: list[dict] = []
    color_by_id: dict[int, int] = {}
    next_color = 0

    for event in events:
        try:
            event_id = event["id"]
            event_type = event["event_type"]
            title = event["title"]
            start_text = event["start_time"]
            end_text = event.get("end_time")
            duration = event.get("estimated_duration", 0) or 0
            if event_type not in ("reminder", "timespan"):
                continue
            if isinstance(duration, bool) or not isinstance(duration, int) or duration < 0:
                continue
            start = datetime.strptime(start_text, DATETIME_FORMAT)
        except (KeyError, TypeError, ValueError):
            continue

        column = 0
        if week_start is not None:
            column = (start.date() - week_start).days
            if not 0 <= column <= 6:
                continue

        top = (start.hour * 60 + start.minute) / 60 * per_hour
        if event_type == "timespan":
            if not end_text:
                continue
            try:
                end = datetime.strptime(end_text, DATETIME_FORMAT)
            except (TypeError, ValueError):
                continue
            if end <= start:
                continue
            height = (end - start).total_seconds() / 3600 * per_hour
            time_label = f"{start:%H:%M}-{end:%H:%M}"
        elif duration > 0:
            height = duration / 60 * per_hour
            time_label = f"{start:%H:%M}"
        else:
            height = per_hour * marker_ratio
            time_label = f"{start:%H:%M}"

        if event_id not in color_by_id:
            color_by_id[event_id] = next_color
            next_color = (next_color + 1) % len(EVENT_COLORS)
        blocks.append({
            "col": column,
            "top": top,
            "height": max(height, minimum_height),
            "title": title,
            "tlabel": time_label,
            "color_idx": color_by_id[event_id],
            "event_type": event_type,
            "is_reminder": event_type == "reminder",
            "id": event_id,
        })

    _add_overlap_ranges(blocks)
    return blocks


def build_week_blocks(events: Iterable, week_start: date, per_hour: int) -> list[dict]:
    """Convert events into the WeekCanvas block contract."""
    return _build_blocks(
        events,
        per_hour=per_hour,
        minimum_height=12,
        marker_ratio=0.22,
        week_start=week_start,
    )


def build_day_blocks(events: Iterable, per_hour: int) -> list[dict]:
    """Convert events into the DayCanvas block contract."""
    return _build_blocks(
        events,
        per_hour=per_hour,
        minimum_height=14,
        marker_ratio=0.3,
        week_start=None,
    )
