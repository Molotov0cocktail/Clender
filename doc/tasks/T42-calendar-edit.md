# T42 日历块、跨日与双击

目标、非目标、接口/数据影响、风险、测试矩阵、回滚、完成定义见 `doc/pc-experience-design.md`。
影响文件：calendar_logic.py、ui/canvas.py、ui/calendar_widget.py、event_service.py、ui/main_window.py、tests/test_calendar_experience.py 及必要旧测试。
步骤：先写短块/跨日/查询/月标记/单双击失败测试；实现切片、相交查询、色块、编辑信号与主窗口接线（含悬浮预览）；聚焦回归和 diff 审查。
状态：待开始；完成需记录命令、结果、旧失败与风险。

## 实施与验证（2026-09-06）
- 旧实现证据：`python -m unittest discover -s tests -p test_calendar_experience.py -v` 首6项为4失败、1错误（短timespan画marker、跨周切片遗漏、跨日查询遗漏、导航未更新聚焦日、缺编辑信号）。
- 实现：跨日同ID按每日半开区间切片；周最多7片，午夜终止无下一日零长片，全天标为00:00–24:00。日/月/周与列表服务改相交查询；仅提醒画红线，timespan保留可点击色块，文字沿用空间判断。
- Canvas与CalendarWidget增加`event_edit_requested(tuple IDs)`，单击等待系统双击间隔再预览；双击取消待发预览，聚合选择后复用主窗口编辑。悬浮event_activated(int)接共享预览。
- 导航同步当前聚焦日；月份导航选目标月1日，越过date.min/max时夹取支持范围。服务date参数拒绝datetime，9999-12-31查询包含末分钟，末年周标题/切片无溢出。
- `EventService.get_event_counts(start=None,end=None)`可指定闭区间；MainWindow仅计数聚焦月、导航刷新，避免数千年事件逐日展开。无参保留历史全日期接口。
- 追加边界用例旧实现再得到2错误（date.max溢出、缺范围计数参数），实现后通过。
- 聚焦实际命令均使用`C:\Users\30910\Miniconda3\python.exe -m unittest discover -s tests -p <文件名> -v`：test_calendar_experience.py 12/12；test_calendar_logic.py 10/10；test_canvas.py 10/10；test_main_window_floating.py 10/10；test_floating_ai_edit.py 16/16。Canvas含Light/Dark×8/20px离屏绘制及字号gutter、聚合命中回归；新测试用临时DB与mock，不读用户data。
- 改动文件另包括必要旧测试`tests/test_canvas.py`、`tests/test_main_window_floating.py`和`tests/test_floating_ai_edit.py`（后者仅StubCalendar接口）；迁移旧“短事项marker”和“悬浮不预览”断言以匹配用户新契约。
- 主agent负责统一编辑失败重试集成、全量/导入/完整构建/exe隔离冒烟、AGENTS/progress同步与Git提交；本子任务未构建提交，不触碰android/或真实数据。
- 限制：无参全日期计数仍为历史接口；产品入口均指定可见月。极短事项有最小视觉高度并保留真实碰撞范围，密集相邻块的视觉高度可能相交，命中选最近可见块；实际DWM/多屏交互留人工检查。
状态：实现与聚焦验证完成，交主agent集成复核。
