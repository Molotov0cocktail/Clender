# T43 悬浮视图、AI 输入与视觉

目标、非目标、接口/数据影响、风险、测试矩阵、回滚、完成定义见 `doc/pc-experience-design.md`。
影响文件：ui/daily_floating_window.py、ui/ai_chat_widget.py、theme_manager.py、新 ui/chat_input.py（如需）、tests/test_floating_views.py、tests/test_chat_input.py 及必要旧测试。
步骤：先写视图/预览/拖缩/换行/主题边界失败测试；实现三视图和多行输入、集中主题优化；验证明暗及独立字体。Canvas 归 T42，不修改 main_window。
状态：实现与聚焦验证完成，等待主 agent 集成/截图/全量与发布验证。

## 实现与接口
- `DailyFloatingWindow.set_view_mode('events'|'day'|'week')` 切换会话内视图；事件列表遵循配置时间窗口，日视图查询整日，周视图查询当前周一到下周一半开区间。复用 T42 跨日纯逻辑和 Canvas，周含日期表头及窄窗横向滚动。
- 事件列表单击延迟一个系统双击间隔发射 `event_activated(int)`，双击/拖动取消待预览；双击维持 `event_edit_requested(int)`。Canvas 聚合项弹菜单选择原始事件，不静默选第一项。
- 顶部拖动把手适用于日/周；保留事件列表拖动、边缘缩放、临时 pin、快捷 AI、午夜刷新和独立字号。新增按钮不持久化配置。
- 新增 UI 层 `ChatInput(QTextEdit)`，纯文本自动折行，按内容有界增长至六行，Enter 发送、Shift+Enter 换行，IME preedit 状态不发送；兼容 `text/setText/returnPressed`，复用既有 AI 校验、busy、网络与数据链路。
- 调整 Light 背景/主色/输入边框、提醒红色、全局按钮圆角/焦点、聊天间距与主题分隔线；Dark 保留低亮度，使用统一焦点样式。无数据/网络接口变化。

## 旧失败与验证
修改前运行：`& 'C:\Users\30910\Miniconda3\python.exe' -m unittest tests.test_chat_input tests.test_floating_views -v`，5 项为 1 failure/4 error，证明旧代码没有单击预览、切视图和多行输入。

实现后运行：`& 'C:\Users\30910\Miniconda3\python.exe' -m unittest tests.test_chat_input tests.test_floating_views tests.test_floating_window_interactions tests.test_floating_window_ui tests.test_typography tests.test_floating_ai_edit -v`，52/52 通过。覆盖输入折行/高度/发送/换行/preedit/disabled，三视图/周界/跨日/空/非法 ID/聚合选择，预览/双击互斥，既有拖缩/pin/busy/AI归属，Light/Dark × 8/20px × 三视图离屏 grab 与独立 Canvas 字号。

`git diff --check` 通过，仅现有 Git CRLF 提示。主 agent 负责完整 PyInstaller、exe 隔离冒烟、用户数据摘要、AGENTS/progress 与提交。

集成复核追加：主 agent 截图发现周表头随纵向滚动消失、20px 窄窗未定位当前日；独立 agent 复现隐藏后仍触发待预览和原地字号变化漏更新表头。已将周表头固定于独立滚动区并同步横向位置，初始横向定位目标日期，字号变化重算表头/gutter，hideEvent 停止待预览。新增两个回归，`-m unittest tests.test_floating_views tests.test_chat_input tests.test_floating_window_interactions tests.test_typography -v` 29/29 通过；这两项验证覆盖隐藏取消、字号更新、固定表头与周日初始定位。

## 风险与回滚
无 schema 或配置迁移，Git revert 即可回滚。Windows 原生 IME 候选窗、DWM/多屏/DPI 真机交互仍需人工观察；已通过合成输入法 preedit 与 offscreen 事件验证。周视图在窄窗支持横向滚动，拖动窗口使用顶部把手。未读取真实数据/配置或调用真实网络，未触碰 android/。
