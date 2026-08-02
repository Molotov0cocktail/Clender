"""
Canvas 渲染组件 - 周视图和日视图的 QPainter 事件块绘制
从 calendar_widget.py 提取为独立可测试的组件
"""
from PyQt5.QtWidgets import QFrame
from PyQt5.QtCore import Qt, QRectF
from PyQt5.QtGui import QPainter, QPen, QBrush, QColor

from constants import EVENT_COLORS, PER_HOUR_PX, REMINDER_LINE_COLOR, OVERLAP_MARKER_COLOR


class WeekCanvas(QFrame):
    """周视图 Canvas - QPainter 绘制 7 列时间轴事件块"""

    def __init__(self, blocks, timeline_height, per_hour, theme, parent=None):
        super().__init__(parent)
        self.blocks = blocks
        self.timeline_height = timeline_height
        self.per_hour = per_hour
        self.theme = theme
        self.setMinimumHeight(timeline_height)

    def paintEvent(self, event):
        qp = QPainter(self)
        qp.setRenderHint(QPainter.Antialiasing)
        w = self.width()
        qw = (w - 30) / 7

        # 虚线时间线（0:00 - 24:00）
        pen = QPen(QColor(self.theme["muted_color"]), 1, Qt.DashLine)
        qp.setPen(pen)
        for h in range(0, 25):
            y = int(h * self.per_hour)
            if h < 24:
                qp.drawLine(30, y, w, y)
            label = f'{h:02d}:00'
            qp.drawText(2, y + 10, label)

        # 事件块
        for b in self.blocks:
            col = b['col']
            top = b['top']
            ht = b['height']
            title = b['title']
            tl = b['tlabel']
            ci = b['color_idx']
            rtype = b.get('event_type', '')
            overlap_ranges = b.get('overlap_ranges', [])

            x = int(30 + col * qw + 2)
            y = int(top)
            wd = int(qw - 4)
            hh = int(ht)
            if y + hh > self.timeline_height:
                hh = self.timeline_height - y
            if hh < 6:
                continue

            base_color = QColor(EVENT_COLORS[ci % len(EVENT_COLORS)])

            if b.get('is_reminder') and rtype == 'reminder' and not overlap_ranges:
                # 粗红色实线（无重叠提醒线）
                qp.setPen(QPen(QColor(REMINDER_LINE_COLOR), 3))
                qp.drawLine(int(x), y, int(x + wd), y)
            else:
                # 正常绘制事件块背景和文字
                brush = QBrush(base_color)
                qp.setBrush(brush)
                qp.setPen(Qt.NoPen)
                qp.drawRoundedRect(QRectF(x, y, wd, hh), 3, 3)
                qp.setPen(QColor(255, 255, 255))
                qp.drawText(QRectF(x + 4, y + 2, wd - 8, hh - 4),
                            Qt.TextWordWrap, f'{title}\n{tl}')

                # 仅在重叠部分绘制半透明竖线标记
                if overlap_ranges:
                    sorted_ranges = sorted(overlap_ranges, key=lambda r: r[0])
                    merged = []
                    for rg in sorted_ranges:
                        if merged and rg[0] <= merged[-1][1]:
                            merged[-1] = (merged[-1][0], max(merged[-1][1], rg[1]))
                        else:
                            merged.append(rg)
                    pen2 = QPen(QColor(*OVERLAP_MARKER_COLOR), 1, Qt.DashLine)
                    qp.setPen(pen2)
                    step = wd / 6
                    for ov_top, ov_bot in merged:
                        ov_y = int(ov_top)
                        ov_h = int(ov_bot - ov_top)
                        if ov_y < y:
                            ov_h -= (y - ov_y)
                            ov_y = y
                        if ov_y + ov_h > y + hh:
                            ov_h = (y + hh) - ov_y
                        if ov_h < 4:
                            continue
                        for k in range(1, 6):
                            lx = int(x + k * step)
                            qp.drawLine(lx, ov_y, lx, ov_y + ov_h)
        qp.end()


class DayCanvas(QFrame):
    """日视图 Canvas - QPainter 绘制单列时间轴事件块"""

    def __init__(self, blocks, timeline_height, per_hour, theme, parent=None):
        super().__init__(parent)
        self.blocks = blocks
        self.timeline_height = timeline_height
        self.per_hour = per_hour
        self.theme = theme
        self.setMinimumHeight(timeline_height)

    def paintEvent(self, event):
        qp = QPainter(self)
        qp.setRenderHint(QPainter.Antialiasing)
        w = self.width()

        pen = QPen(QColor(self.theme["muted_color"]), 1, Qt.DashLine)
        qp.setPen(pen)
        for h in range(0, 25):
            y = int(h * self.per_hour)
            if h < 24:
                qp.drawLine(40, y, w, y)
            qp.drawText(4, y + 10, f'{h:02d}:00')

        for b in self.blocks:
            top = b['top']
            ht = b['height']
            title = b['title']
            tl = b['tlabel']
            ci = b['color_idx']
            overlap_ranges = b.get('overlap_ranges', [])

            x = 44
            wd = w - 52
            y = int(top)
            hh = int(ht)
            if y + hh > self.timeline_height:
                hh = self.timeline_height - y
            if hh < 6:
                continue

            base_color = QColor(EVENT_COLORS[ci % len(EVENT_COLORS)])

            if b.get('is_reminder') and not overlap_ranges:
                # 提醒红线
                qp.setPen(QPen(QColor(REMINDER_LINE_COLOR), 3))
                qp.drawLine(x, y, x + wd, y)
                brush = QBrush(base_color)
                qp.setBrush(brush)
                qp.drawRoundedRect(QRectF(x, y, wd, hh), 3, 3)
                qp.setPen(QColor(255, 255, 255))
                qp.drawText(QRectF(x + 4, y + 2, wd - 8, hh - 4),
                            Qt.TextWordWrap, f'🔔 {title}')
            else:
                # 正常绘制事件块背景和文字
                brush = QBrush(base_color)
                qp.setBrush(brush)
                qp.setPen(Qt.NoPen)
                qp.drawRoundedRect(QRectF(x, y, wd, hh), 3, 3)
                qp.setPen(QColor(255, 255, 255))
                qp.drawText(QRectF(x + 4, y + 2, wd - 8, hh - 4),
                            Qt.TextWordWrap, f'{title}\n{tl}')

                # 重叠标记
                if overlap_ranges:
                    sorted_ranges = sorted(overlap_ranges, key=lambda r: r[0])
                    merged = []
                    for rg in sorted_ranges:
                        if merged and rg[0] <= merged[-1][1]:
                            merged[-1] = (merged[-1][0], max(merged[-1][1], rg[1]))
                        else:
                            merged.append(rg)
                    pen2 = QPen(QColor(*OVERLAP_MARKER_COLOR), 1, Qt.DashLine)
                    qp.setPen(pen2)
                    step = wd / 6
                    for ov_top, ov_bot in merged:
                        ov_y = int(ov_top)
                        ov_h = int(ov_bot - ov_top)
                        if ov_y < y:
                            ov_h -= (y - ov_y)
                            ov_y = y
                        if ov_y + ov_h > y + hh:
                            ov_h = (y + hh) - ov_y
                        if ov_h < 4:
                            continue
                        for k in range(1, 6):
                            lx = int(x + k * step)
                            qp.drawLine(lx, ov_y, lx, ov_y + ov_h)
        qp.end()