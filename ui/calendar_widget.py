"""
日历视图组件 - 智能时间块渲染
周/日视图使用连续时间轴 + 事件色块 + 重叠竖线标记
"""
from PyQt5.QtWidgets import (QWidget, QVBoxLayout, QHBoxLayout, QPushButton,
                             QLabel, QGridLayout, QFrame, QScrollArea)
from PyQt5.QtCore import Qt, pyqtSignal
from datetime import date, timedelta
import calendar

import theme_manager
from event_service import EventService
from ui.canvas import WeekCanvas, DayCanvas
from calendar_logic import build_week_blocks, build_day_blocks
from typography import build_scale


class CalendarWidget(QFrame):

    date_selected = pyqtSignal(date)
    event_activated = pyqtSignal(object)

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setFrameStyle(QFrame.StyledPanel | QFrame.Raised)
        calendar.setfirstweekday(0)
        self._current_date = date.today()
        self._selected_date = date.today()
        self._view_mode = 'month'
        self._event_dates = {}
        self._active_canvas = None
        self._init_ui()

    def _t(self): return theme_manager.get_current_theme()

    def _scale(self):
        """Derive named application roles from the effective widget pixel font."""
        return build_scale(self.font().pixelSize())

    def _init_ui(self):
        self._main_layout = QVBoxLayout(self)
        self._main_layout.setContentsMargins(8,8,8,8)
        nav = QHBoxLayout()
        self._btn_prev = QPushButton('◀'); self._btn_prev.setFixedSize(36,36)
        self._btn_prev.clicked.connect(self._go_prev)
        self._lbl_title = QLabel(); self._lbl_title.setAlignment(Qt.AlignCenter)
        self._btn_next = QPushButton('▶'); self._btn_next.setFixedSize(36,36)
        self._btn_next.clicked.connect(self._go_next)
        nav.addWidget(self._btn_prev); nav.addWidget(self._lbl_title,1); nav.addWidget(self._btn_next)
        self._main_layout.addLayout(nav)

        sw = QHBoxLayout(); sw.setSpacing(6)
        self._btn_month=QPushButton('月'); self._btn_week=QPushButton('周'); self._btn_day=QPushButton('日')
        for b in (self._btn_month,self._btn_week,self._btn_day): b.setCheckable(True); b.setFixedHeight(30)
        self._btn_month.setChecked(True)
        self._btn_month.clicked.connect(lambda:self._switch_view('month'))
        self._btn_week.clicked.connect(lambda:self._switch_view('week'))
        self._btn_day.clicked.connect(lambda:self._switch_view('day'))
        self._btn_today=QPushButton('今天'); self._btn_today.setFixedHeight(30)
        self._btn_today.clicked.connect(self._go_today)
        sw.addWidget(self._btn_month);sw.addWidget(self._btn_week);sw.addWidget(self._btn_day);sw.addStretch();sw.addWidget(self._btn_today)
        self._main_layout.addLayout(sw)

        self._scroll = QScrollArea(); self._scroll.setWidgetResizable(True)
        self._content = QWidget(); self._content_layout = QVBoxLayout(self._content)
        self._content_layout.setContentsMargins(0,4,0,0)
        self._scroll.setWidget(self._content)
        self._main_layout.addWidget(self._scroll,1)
        self._render_view()

    def _switch_view(self,mode):
        self._view_mode=mode
        self._btn_month.setChecked(mode=='month');self._btn_week.setChecked(mode=='week');self._btn_day.setChecked(mode=='day')
        self._clear_content(); self._render_view()

    def _go_today(self):
        self._current_date=date.today();self._selected_date=date.today()
        self._clear_content();self._render_view();self.date_selected.emit(self._selected_date)

    def _go_prev(self):
        if self._view_mode=='month': y,m=self._current_date.year,self._current_date.month; self._current_date=date(y-1,12,1) if m==1 else date(y,m-1,1)
        elif self._view_mode=='week': self._current_date-=timedelta(days=7)
        else: self._current_date-=timedelta(days=1)
        self._clear_content();self._render_view()

    def _go_next(self):
        if self._view_mode=='month': y,m=self._current_date.year,self._current_date.month; self._current_date=date(y+1,1,1) if m==12 else date(y,m+1,1)
        elif self._view_mode=='week': self._current_date+=timedelta(days=7)
        else: self._current_date+=timedelta(days=1)
        self._clear_content();self._render_view()

    def _clear_content(self):
        self._active_canvas = None
        while self._content_layout.count():
            item=self._content_layout.takeAt(0)
            if item is not None:
                w=item.widget()
                if w is not None: w.deleteLater()
                sub=item.layout()
                if sub is not None:
                    self._recursive_clear(sub)

    def _recursive_clear(self, layout):
        while layout.count():
            si=layout.takeAt(0)
            if si is not None:
                sw=si.widget()
                if sw is not None: sw.deleteLater()
                sl=si.layout()
                if sl is not None: self._recursive_clear(sl)
                else: continue

    # ─────────── 渲染入口 ───────────
    def _render_view(self):
        t=self._t()
        scale=self._scale()
        self.setStyleSheet(f"CalendarWidget{{background:{t['frame_bg']};border-radius:8px;}}")
        self._btn_prev.setStyleSheet(f'QPushButton{{font-size:{scale.section_title_px}px;border:none;background:transparent;color:{t["nav_btn_color"]};}}QPushButton:hover{{color:{t["primary"]};}}')
        self._btn_next.setStyleSheet(self._btn_prev.styleSheet())
        self._lbl_title.setStyleSheet(f'font-size:{scale.page_title_px}px;font-weight:bold;color:{t["title_color"]};')
        ss=f'QPushButton{{border:1px solid {t["input_border"]};border-radius:4px;padding:2px 12px;font-size:{scale.control_px}px;background:{t["frame_bg"]};color:{t["switch_btn_text"]};}}QPushButton:checked{{background:{t["switch_btn_checked_bg"]};color:{t["primary_text"]};border-color:{t["switch_btn_checked_bg"]};}}QPushButton:hover{{background:{t["primary_hover"]};color:{t["primary_text"]};}}'
        for b in (self._btn_month,self._btn_week,self._btn_day): b.setStyleSheet(ss)
        self._btn_today.setStyleSheet(f'QPushButton{{border:1px solid {t["info"]};border-radius:4px;padding:2px 10px;font-size:{scale.control_px}px;background:{t["info"]};color:{t["primary_text"]};}}QPushButton:hover{{background:{t["info_hover"]};}}')
        if self._view_mode=='month': self._render_month_view()
        elif self._view_mode=='week': self._render_week_view()
        else: self._render_day_view()
        self._update_title()

    def _update_title(self):
        d=self._current_date
        if self._view_mode=='month': self._lbl_title.setText(f'{d.year}年 {d.month}月')
        elif self._view_mode=='week':
            s=d-timedelta(days=d.weekday()); e=s+timedelta(days=6)
            self._lbl_title.setText(f'{s.month}月{s.day}日 - {e.month}月{e.day}日  {d.year}年')
        else: self._lbl_title.setText(f'{d.year}年{d.month}月{d.day}日  {["周一","周二","周三","周四","周五","周六","周日"][d.weekday()]}')

    def _render_month_view(self):
        t=self._t(); scale=self._scale(); grid=QGridLayout(); grid.setSpacing(3)
        hds=['一','二','三','四','五','六','日']
        hs=f'QLabel{{font-size:{scale.section_title_px}px;font-weight:bold;color:{t["subtitle_color"]};padding:6px;background:{t["header_bg"]};border-radius:4px;}}'
        for c,n in enumerate(hds):
            l=QLabel(n);l.setAlignment(Qt.AlignCenter);l.setStyleSheet(hs);grid.addWidget(l,0,c)
        y,m=self._current_date.year,self._current_date.month
        cal_weeks=calendar.monthcalendar(y,m); today=date.today()
        for ri,wk in enumerate(cal_weeks):
            for ci,dn in enumerate(wk):
                if dn==0: grid.addWidget(QLabel(''),ri+1,ci)
                else:
                    cd=date(y,m,dn)
                    btn=QPushButton(str(dn)); btn.setCursor(Qt.PointingHandCursor)
                    is_t=cd==today; is_s=cd==self._selected_date
                    if is_s: bg,bd,tc,bw=t["calendar_selected_bg"],t["calendar_selected_border"],t["calendar_selected_text"],2
                    elif is_t: bg,bd,tc,bw=t["calendar_today_bg"],t["calendar_today_border"],t["calendar_today_text"],2
                    else: bg,bd,tc,bw=t["calendar_cell_bg"],t["calendar_cell_border"],t["calendar_cell_text"],1
                    btn.setStyleSheet(f'QPushButton{{border:{bw}px solid {bd};border-radius:6px;background:{bg};font-size:{scale.body_px}px;color:{tc};min-height:56px;max-height:72px;text-align:left;padding:4px;}}QPushButton:hover{{background:{t["info_hover"]};color:{t["primary_text"]};}}')
                    if cd in self._event_dates and self._event_dates[cd]>0:
                        btn.setText(f'{dn}\n● {self._event_dates[cd]}项')
                    btn.clicked.connect(lambda _,d=cd: self._on_date_clicked(d))
                    grid.addWidget(btn,ri+1,ci)
        self._content_layout.addLayout(grid); self._content_layout.addStretch()

    # ─────────── 周视图 事件块画布 ───────────
    def _render_week_view(self):
        t=self._t()
        scale=self._scale()
        wd=self._current_date.weekday()
        ws=self._current_date-timedelta(days=wd)
        we=ws+timedelta(days=6)
        events=EventService.get_events_date_range(ws,we)
        today=date.today()

        container=QVBoxLayout()
        # 列标题
        hdr_row=QHBoxLayout();hdr_row.setSpacing(2)
        time_spacer=QLabel('')
        time_spacer.setFixedWidth(
            int(WeekCanvas.timeline_gutter_for_metrics(self.fontMetrics()))
        )
        hdr_row.addWidget(time_spacer)
        for ci in range(7):
            cd=ws+timedelta(days=ci)
            wdn=['一','二','三','四','五','六','日'][ci]
            lbl=QLabel(f'{wdn}\n{cd.month}/{cd.day}');lbl.setAlignment(Qt.AlignCenter)
            is_t=cd==today; is_s=cd==self._selected_date
            if is_s: bg,cl=t["calendar_selected_bg"],t["calendar_selected_text"]
            elif is_t: bg,cl=t["calendar_today_bg"],t["calendar_today_text"]
            else: bg,cl=t["header_bg"],t["title_color"]
            lbl.setStyleSheet(f'font-size:{scale.caption_px}px;font-weight:bold;color:{cl};background:{bg};padding:4px;border-radius:4px;')
            hdr_row.addWidget(lbl)
        container.addLayout(hdr_row)

        # 时间轴 0:00-24:00 每整点一条虚线
        timeline_height=720  # 24h * 30px per hour
        per_hour=30

        blocks=build_week_blocks(events, ws, per_hour)

        # ── Canvas 绘制（使用 ui/canvas.py 中的 WeekCanvas）──
        canvas = WeekCanvas(blocks, timeline_height, per_hour, t)
        canvas.setFont(self.font())
        canvas.event_activated.connect(self.event_activated.emit)
        self._active_canvas = canvas
        container.addWidget(canvas)
        self._content_layout.addLayout(container)
        self._content_layout.addStretch()

    # ─────────── 日视图 事件块画布 ───────────
    def _render_day_view(self):
        t=self._t()
        scale=self._scale()
        d=self._current_date
        events=EventService.get_events_by_date(d)
        per_hour=30; timeline_height=24*per_hour

        container=QVBoxLayout()
        wd=['周一','周二','周三','周四','周五','周六','周日']
        title=QLabel(f'{d.year}年{d.month}月{d.day}日  {wd[d.weekday()]}')
        title.setAlignment(Qt.AlignCenter)
        title.setStyleSheet(f'font-size:{scale.page_title_px}px;font-weight:bold;color:{t["title_color"]};padding:4px;')
        container.addWidget(title)

        blocks=build_day_blocks(events, per_hour)

        # ── Canvas 绘制（使用 ui/canvas.py 中的 DayCanvas）──
        canvas = DayCanvas(blocks, timeline_height, per_hour, t)
        canvas.setFont(self.font())
        canvas.event_activated.connect(self.event_activated.emit)
        self._active_canvas = canvas
        container.addWidget(canvas)
        self._content_layout.addLayout(container)
        self._content_layout.addStretch()

    def _on_date_clicked(self,d): self._selected_date=d;self._clear_content();self._render_view();self.date_selected.emit(d)

    def get_selected_date(self): return self._selected_date
    def set_selected_date(self,d): self._selected_date=d;self._current_date=d;self._clear_content();self._render_view()
    def update_event_markers(self,ed): self._event_dates=ed;self._clear_content();self._render_view()
    def apply_theme(self): self._clear_content();self._render_view()
