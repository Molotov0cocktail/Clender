"""Qt canvas widgets for lane-based week and day calendar rendering."""
from __future__ import annotations

from collections import defaultdict
from math import ceil

from PyQt5.QtCore import QRectF, Qt, pyqtSignal
from PyQt5.QtGui import QBrush, QColor, QPainter, QPen
from PyQt5.QtWidgets import QFrame

from constants import EVENT_COLORS, OVERLAP_MARKER_COLOR, REMINDER_LINE_COLOR


class _BaseCalendarCanvas(QFrame):
    """Shared responsive layout, rendering, and hit testing for calendar canvases."""

    event_activated = pyqtSignal(object)

    LANE_GAP = 2.0
    MIN_CLICK_WIDTH = 18.0
    MIN_HIT_HEIGHT = 12.0
    MIN_TEXT_WIDTH = 28.0
    MARKER_VISUAL_HALF_HEIGHT = 3.0
    OVERLAP_BAND_WIDTH = 6.0
    BLOCK_PADDING = 4.0
    TIME_LABEL = "00:00"
    TIME_GAP = 8.0
    TIME_LEFT_PADDING = 4.0

    def __init__(self, blocks, timeline_height, per_hour, theme, parent=None):
        super().__init__(parent)
        self.blocks = blocks
        self.timeline_height = timeline_height
        self.per_hour = per_hour
        self.theme = theme
        self._layout_entries: list[dict] = []
        self._hit_regions: list[tuple[QRectF, tuple[int, ...]]] = []
        self._paint_regions: list[dict] = []
        self.setMinimumHeight(timeline_height)

    def _column_geometry(self, block: dict, width: float) -> tuple[float, float]:
        raise NotImplementedError

    @classmethod
    def timeline_gutter_for_metrics(cls, metrics) -> float:
        """Return the shared, whole-pixel gutter for the current font metrics."""
        label_width = float(metrics.horizontalAdvance(cls.TIME_LABEL))
        return float(ceil(cls.TIME_LEFT_PADDING + label_width + cls.TIME_GAP))

    def _timeline_gutter(self, painter_or_metrics) -> float:
        metrics = (
            painter_or_metrics.fontMetrics()
            if hasattr(painter_or_metrics, "fontMetrics")
            else painter_or_metrics
        )
        return self.timeline_gutter_for_metrics(metrics)

    def _timeline_geometry(self, metrics, width: float) -> dict:
        label_width = float(metrics.horizontalAdvance(self.TIME_LABEL))
        label_height = float(metrics.lineSpacing())
        grid_start = self.timeline_gutter_for_metrics(metrics)
        return {
            "gutter": grid_start,
            "grid_start": grid_start,
            "label_rect": QRectF(
                self.TIME_LEFT_PADDING,
                0.0,
                label_width,
                label_height,
            ),
            "available_width": max(0.0, float(width) - grid_start),
        }

    def _draw_timeline(self, painter: QPainter, width: int) -> None:
        metrics = painter.fontMetrics()
        geometry = self._timeline_geometry(metrics, width)
        grid_start = geometry["grid_start"]
        base_label_rect = geometry["label_rect"]
        max_label_top = max(
            0.0,
            float(self.timeline_height) - base_label_rect.height(),
        )

        painter.setPen(QPen(QColor(self.theme["muted_color"]), 1, Qt.DashLine))
        for hour in range(25):
            y = float(hour * self.per_hour)
            if hour < 24:
                painter.drawLine(int(grid_start), int(y), width, int(y))
            label_top = max(
                0.0,
                min(y - base_label_rect.height() / 2.0, max_label_top),
            )
            label_rect = QRectF(
                base_label_rect.x(),
                label_top,
                base_label_rect.width(),
                base_label_rect.height(),
            )
            painter.drawText(
                label_rect,
                int(Qt.AlignRight | Qt.AlignVCenter),
                f"{hour:02d}:00",
            )

    @staticmethod
    def _unique_ids(blocks: list[dict]) -> tuple[int, ...]:
        return tuple(dict.fromkeys(block["id"] for block in blocks))

    def _slot_count(self, width: float, lane_count: int) -> int:
        possible = max(
            1,
            int((width + self.LANE_GAP) // (self.MIN_CLICK_WIDTH + self.LANE_GAP)),
        )
        return min(max(1, lane_count), possible)

    def _slot_geometry(
        self,
        base_x: float,
        available_width: float,
        slot: int,
        slot_count: int,
    ) -> tuple[float, float]:
        slot_width = max(
            1.0,
            (available_width - self.LANE_GAP * (slot_count - 1)) / slot_count,
        )
        return base_x + slot * (slot_width + self.LANE_GAP), slot_width

    def _vertical_rect(
        self,
        block: dict,
        x: float,
        width: float,
    ) -> QRectF | None:
        top = max(0.0, float(block["top"]))
        bottom = min(
            float(self.timeline_height),
            float(block["top"]) + max(0.0, float(block["height"])),
        )
        if top >= self.timeline_height or bottom <= 0 or bottom <= top:
            return None
        return QRectF(x, top, max(1.0, width), max(0.5, bottom - top))

    def _minimum_hit_rect(self, rect: QRectF, anchor_y: float | None = None) -> QRectF:
        if anchor_y is None and rect.height() >= self.MIN_HIT_HEIGHT:
            return QRectF(rect)
        hit_height = min(self.MIN_HIT_HEIGHT, float(self.timeline_height))
        center_y = rect.center().y() if anchor_y is None else anchor_y
        top = center_y - hit_height / 2
        top = max(0.0, min(top, self.timeline_height - hit_height))
        return QRectF(rect.x(), top, rect.width(), hit_height)

    def _visual_hit_rect(self, entry: dict) -> QRectF:
        rect = entry["rect"]
        if not entry.get("marker", False):
            return QRectF(rect)
        top = max(0.0, rect.top() - self.MARKER_VISUAL_HALF_HEIGHT)
        bottom = min(
            float(self.timeline_height),
            rect.top() + self.MARKER_VISUAL_HALF_HEIGHT,
        )
        return QRectF(rect.x(), top, rect.width(), max(1.0, bottom - top))

    @staticmethod
    def _vertical_distance(point_y: float, rect: QRectF) -> float:
        if point_y < rect.top():
            return rect.top() - point_y
        if point_y > rect.bottom():
            return point_y - rect.bottom()
        return 0.0

    def _distance_to_entry(self, point_y: float, entry: dict) -> float:
        if entry.get("marker", False):
            return abs(point_y - entry["rect"].top())
        return self._vertical_distance(point_y, entry["rect"])

    def _entry_at(self, point) -> dict | None:
        candidates = [
            (index, entry)
            for index, entry in enumerate(self._layout_entries)
            if entry["hit_rect"].contains(point)
        ]
        if not candidates:
            return None

        visual_candidates = [
            candidate
            for candidate in candidates
            if self._visual_hit_rect(candidate[1]).contains(point)
        ]
        eligible = visual_candidates or candidates
        _, entry = min(
            eligible,
            key=lambda candidate: (
                self._distance_to_entry(point.y(), candidate[1]),
                -candidate[0],
            ),
        )
        return entry

    def _uses_marker(self, block: dict) -> bool:
        minimum_text_height = float(self.fontMetrics().lineSpacing()) + 4.0
        duration_minutes = block.get("duration_minutes")
        if duration_minutes is None:
            return (
                bool(block.get("is_reminder"))
                and block["height"] < minimum_text_height
            )
        return duration_minutes / 60 * self.per_hour < minimum_text_height

    def _individual_entry(
        self,
        block: dict,
        base_x: float,
        available_width: float,
        slot: int,
        slot_count: int,
    ) -> dict | None:
        x, width = self._slot_geometry(base_x, available_width, slot, slot_count)
        rect = self._vertical_rect(block, x, width)
        if rect is None:
            return None
        is_marker = self._uses_marker(block)
        return {
            "rect": rect,
            "hit_rect": self._minimum_hit_rect(
                rect,
                anchor_y=rect.top() if is_marker else None,
            ),
            "event_ids": (block["id"],),
            "block": block,
            "overflow": False,
            "marker": is_marker,
        }

    def _overflow_entry(
        self,
        blocks: list[dict],
        base_x: float,
        available_width: float,
        slot: int,
        slot_count: int,
    ) -> dict | None:
        x, width = self._slot_geometry(base_x, available_width, slot, slot_count)
        top = min(float(block["top"]) for block in blocks)
        bottom = max(float(block["top"]) + float(block["height"]) for block in blocks)
        aggregate = {"top": top, "height": bottom - top}
        rect = self._vertical_rect(aggregate, x, width)
        if rect is None:
            return None
        return {
            "rect": rect,
            "hit_rect": self._minimum_hit_rect(rect),
            "event_ids": self._unique_ids(blocks),
            "blocks": blocks,
            "overflow": True,
        }

    def _build_layout(self, width: int | None = None) -> list[dict]:
        canvas_width = float(self.width() if width is None else width)
        clusters: dict[tuple[int, int], list[dict]] = defaultdict(list)
        for block in self.blocks:
            clusters[(block.get("col", 0), block.get("cluster_id", -1))].append(block)

        entries: list[dict] = []
        for cluster_blocks in clusters.values():
            cluster_blocks.sort(key=lambda block: (block["top"], block.get("lane", 0)))
            sample = cluster_blocks[0]
            base_x, available_width = self._column_geometry(sample, canvas_width)
            lane_count = max(1, int(sample.get("lane_count", 1)))
            slot_count = self._slot_count(available_width, lane_count)

            if lane_count <= slot_count:
                for block in cluster_blocks:
                    entry = self._individual_entry(
                        block,
                        base_x,
                        available_width,
                        int(block.get("lane", 0)),
                        lane_count,
                    )
                    if entry is not None:
                        entries.append(entry)
                continue

            visible_lane_count = max(0, slot_count - 1)
            overflow_blocks: list[dict] = []
            for block in cluster_blocks:
                lane = int(block.get("lane", 0))
                if lane < visible_lane_count:
                    entry = self._individual_entry(
                        block,
                        base_x,
                        available_width,
                        lane,
                        slot_count,
                    )
                    if entry is not None:
                        entries.append(entry)
                else:
                    overflow_blocks.append(block)
            if overflow_blocks:
                entry = self._overflow_entry(
                    overflow_blocks,
                    base_x,
                    available_width,
                    visible_lane_count,
                    slot_count,
                )
                if entry is not None:
                    entries.append(entry)

        self._layout_entries = entries
        self._hit_regions = [
            (QRectF(entry["hit_rect"]), entry["event_ids"])
            for entry in entries
        ]
        return entries

    def _text_rect_for(self, rect: QRectF, has_overlap: bool) -> QRectF:
        right_padding = self.BLOCK_PADDING
        if has_overlap:
            right_padding += self.OVERLAP_BAND_WIDTH
        return rect.adjusted(
            self.BLOCK_PADDING,
            2.0,
            -right_padding,
            -2.0,
        )

    def _texture_rects(self, entry: dict) -> list[QRectF]:
        if entry["overflow"]:
            return []
        rect = entry["rect"]
        texture_rects = []
        for overlap_top, overlap_bottom in entry["block"].get("overlap_ranges", []):
            top = max(rect.top(), float(overlap_top))
            bottom = min(rect.bottom(), float(overlap_bottom))
            if bottom - top < 1.0:
                continue
            texture_rects.append(QRectF(
                rect.right() - self.OVERLAP_BAND_WIDTH,
                top,
                self.OVERLAP_BAND_WIDTH,
                bottom - top,
            ))
        return texture_rects

    def _draw_marker(self, painter: QPainter, entry: dict) -> None:
        rect = entry["rect"]
        y = rect.top()
        color = QColor(REMINDER_LINE_COLOR)
        painter.setPen(QPen(color, 3))
        painter.drawLine(int(rect.left()), int(y), int(rect.right()), int(y))
        painter.setBrush(QBrush(color))
        painter.setPen(Qt.NoPen)
        painter.drawEllipse(QRectF(rect.left(), y - 3.0, 6.0, 6.0))

    def _draw_text(self, painter: QPainter, entry: dict, text_rect: QRectF) -> None:
        if text_rect.width() < self.MIN_TEXT_WIDTH:
            return
        metrics = painter.fontMetrics()
        line_height = metrics.lineSpacing()
        if text_rect.height() < line_height:
            return
        block = entry["block"]
        title = metrics.elidedText(str(block.get("title", "")), Qt.ElideRight, int(text_rect.width()))
        first_line = QRectF(text_rect.x(), text_rect.y(), text_rect.width(), line_height)
        painter.drawText(first_line, Qt.AlignLeft | Qt.AlignVCenter, title)
        if text_rect.height() >= line_height * 2:
            time_label = metrics.elidedText(
                str(block.get("tlabel", "")),
                Qt.ElideRight,
                int(text_rect.width()),
            )
            second_line = QRectF(
                text_rect.x(),
                text_rect.y() + line_height,
                text_rect.width(),
                line_height,
            )
            painter.drawText(second_line, Qt.AlignLeft | Qt.AlignVCenter, time_label)

    def _draw_texture(self, painter: QPainter, rect: QRectF) -> None:
        painter.save()
        painter.setClipRect(rect)
        painter.setPen(QPen(QColor(*OVERLAP_MARKER_COLOR), 1))
        start_y = int(rect.top()) - int(rect.width())
        end_y = int(rect.bottom()) + int(rect.width())
        step = 4
        for y in range(start_y, end_y + step, step):
            painter.drawLine(
                int(rect.left()),
                y,
                int(rect.right()),
                y + int(rect.width()),
            )
        painter.restore()

    def _draw_entry(self, painter: QPainter, entry: dict) -> None:
        rect = entry["rect"]
        if entry["overflow"]:
            first = entry["blocks"][0]
            color_index = first.get("color_idx", 0)
        else:
            color_index = entry["block"].get("color_idx", 0)
        color = QColor(EVENT_COLORS[color_index % len(EVENT_COLORS)])

        if entry.get("marker", False):
            self._draw_marker(painter, entry)
            self._paint_regions.append({"event_ids": entry["event_ids"], "text_rect": None, "texture_rects": []})
            return

        painter.setPen(Qt.NoPen)
        painter.setBrush(QBrush(color))
        painter.drawRoundedRect(rect, 3, 3)
        painter.setPen(QColor(255, 255, 255))

        if entry["overflow"]:
            label = f'+{len(entry["event_ids"])}'
            painter.drawText(rect, Qt.AlignCenter, label)
            self._paint_regions.append({"event_ids": entry["event_ids"], "text_rect": QRectF(rect), "texture_rects": []})
            return

        texture_rects = self._texture_rects(entry)
        text_rect = self._text_rect_for(rect, bool(texture_rects))
        self._draw_text(painter, entry, text_rect)
        for texture_rect in texture_rects:
            self._draw_texture(painter, texture_rect)
        self._paint_regions.append({
            "event_ids": entry["event_ids"],
            "text_rect": QRectF(text_rect),
            "texture_rects": [QRectF(rect) for rect in texture_rects],
        })

    def paintEvent(self, event):
        painter = QPainter(self)
        painter.setRenderHint(QPainter.Antialiasing)
        self._draw_timeline(painter, self.width())
        self._paint_regions = []
        for entry in self._build_layout():
            self._draw_entry(painter, entry)
        painter.end()

    def mousePressEvent(self, event):
        if event.button() == Qt.LeftButton:
            self._build_layout()
            point = event.localPos()
            entry = self._entry_at(point)
            if entry is not None:
                self.event_activated.emit(entry["event_ids"])
                event.accept()
                return
        super().mousePressEvent(event)


class WeekCanvas(_BaseCalendarCanvas):
    """Seven-column calendar canvas with responsive overlap lanes."""

    def _column_geometry(self, block: dict, width: float) -> tuple[float, float]:
        grid_start = self._timeline_geometry(
            self.fontMetrics(), width
        )["grid_start"]
        column_width = max(1.0, (float(width) - grid_start) / 7.0)
        return (
            grid_start + block["col"] * column_width + 2.0,
            max(1.0, column_width - 4.0),
        )


class DayCanvas(_BaseCalendarCanvas):
    """Single-column calendar canvas with responsive overlap lanes."""

    def _column_geometry(self, block: dict, width: float) -> tuple[float, float]:
        grid_start = self._timeline_geometry(
            self.fontMetrics(), width
        )["grid_start"]
        event_left = grid_start + self.BLOCK_PADDING
        return event_left, max(
            1.0,
            float(width) - event_left - self.BLOCK_PADDING * 2.0,
        )
