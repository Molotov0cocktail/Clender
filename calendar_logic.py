"""Pure calendar layout helpers shared by week and day views."""
from __future__ import annotations

from datetime import date, datetime
from typing import Iterable

from constants import EVENT_COLORS


DATETIME_FORMAT = "%Y-%m-%d %H:%M"


def _block_bottom(block: dict) -> float:
    return block["top"] + block.get("_collision_height", block["height"])


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
            first_bottom = _block_bottom(first)
            second_bottom = _block_bottom(second)
            if first["top"] < second_bottom and second["top"] < first_bottom:
                overlap = (
                    max(first["top"], second["top"]),
                    min(first_bottom, second_bottom),
                )
                first["overlap_ranges"].append(overlap)
                second["overlap_ranges"].append(overlap)
    for block in blocks:
        block["overlap_ranges"] = merge_ranges(block["overlap_ranges"])


def _assign_lanes(blocks: list[dict]) -> None:
    """Assign stable lanes to connected overlap clusters in each day column."""
    cluster_id = 0
    by_column: dict[int, list[tuple[int, dict]]] = {}
    for order, block in enumerate(blocks):
        by_column.setdefault(block["col"], []).append((order, block))

    for column in sorted(by_column):
        ordered = sorted(
            by_column[column],
            key=lambda item: (item[1]["top"], item[0]),
        )
        active: list[tuple[float, int]] = []
        cluster_blocks: list[dict] = []
        cluster_lane_count = 0

        def finish_cluster() -> None:
            nonlocal cluster_id, cluster_blocks, cluster_lane_count
            if not cluster_blocks:
                return
            for cluster_block in cluster_blocks:
                cluster_block["lane_count"] = cluster_lane_count
            cluster_id += 1
            cluster_blocks = []
            cluster_lane_count = 0

        for _, block in ordered:
            start = block["top"]
            active = [(end, lane) for end, lane in active if end > start]
            if not active:
                finish_cluster()

            used_lanes = {lane for _, lane in active}
            lane = 0
            while lane in used_lanes:
                lane += 1
            block["lane"] = lane
            block["cluster_id"] = cluster_id
            cluster_blocks.append(block)
            active.append((_block_bottom(block), lane))
            cluster_lane_count = max(cluster_lane_count, len(active))

        finish_cluster()


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
            duration_minutes = (end - start).total_seconds() / 60
            height = duration_minutes / 60 * per_hour
            time_label = f"{start:%H:%M}-{end:%H:%M}"
        elif duration > 0:
            duration_minutes = duration
            height = duration / 60 * per_hour
            time_label = f"{start:%H:%M}"
        else:
            duration_minutes = 0
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
            "duration_minutes": duration_minutes,
            "_collision_height": height,
        })

    _add_overlap_ranges(blocks)
    _assign_lanes(blocks)
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
