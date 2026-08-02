# T30：悬浮快捷对话、双字号与日历间距规划

## 目标

- 将用户确认的 Phase 9 产品语义固化为可测试的提案、设计、任务边界与控制提示。
- 在任何生产代码修改前建立正常、边界、异常和回归测试矩阵。
- 为后续独立 subagent 提供不重叠、可审查、可回滚的任务输入。

## 非目标

- 本任务不修改生产代码、运行时配置、数据库或真实用户数据。
- 本任务不调用真实 AI Provider，不读取 `data/` 或 `dist/data/` 内容。
- 本任务不修改或提交用户已暂存的 `.github/workflows/test.yml`。

## 输入文档

- `AGENTS.md`
- `doc/desktop-experience-*.md`
- `doc/tasks/T26-calendar-layout.md`
- `doc/tasks/T27-floating-window.md`
- `references/prompt-templates.md`
- 用户于 2026-08-02 确认的十项产品决策

## 影响文件

- `doc/floating-ai-typography-proposal.md`
- `doc/floating-ai-typography-high-level-design.md`
- `doc/floating-ai-typography-detailed-design.md`
- `doc/prompt.md`
- `doc/tasks/T30-phase9-planning.md`
- `doc/tasks/T31-typography-foundation.md` 至 `T37-integration-release.md`
- `doc/tasks/progress.md`

## 接口与数据影响

- 规划新增两个独立持久化字号字段：应用字号与悬浮窗字号，均为 8–20px。
- 规划中的置顶状态仅为本次进程内 UI 状态，不写配置；启动默认使用窗口置底提示。
- 规划中的悬浮 AI 输入复用主页面当前 Conversation、预算、网络线程、响应执行和历史持久化路径。
- 规划中的悬浮事项双击走可编辑 EventDialog；事件类型切换必须真实保存。
- 不修改 SQLite schema。

## 已确认决策

1. 去除原生标题栏和内部日期标题；保留自定义边缘/四角缩放。
2. 默认 `WindowStaysOnBottomHint`；运行时可切换置顶，不跨重启保存。
3. 悬浮输入写入主页面当前对话历史；Enter 发送；无模型正文显示，只显示短状态；请求中禁用输入。
4. AI 删除和批量修改不增加二次确认，沿用主 AI 执行权限。
5. 单击用于选择/拖动；双击直接编辑；取消不写入；允许切换事件类型；不在编辑框增加删除。
6. 当前/下一事项采用用户确认的半开区间、预计时长、同刻并列和每分钟刷新语义。
7. 应用字号与悬浮窗字号分开设置；集中命名字号角色并静态扫描所有字号变量。
8. 时间轴 gutter 按字体度量，和网格/事项至少间隔 8px；保持 30px 每小时和现有三栏比例。
9. 透明度允许真实 0%，恢复入口为主应用设置。
10. 既有 CI 暂存改动保持原样并排除在本轮提交外。

## 风险

- `Qt.WindowStaysOnBottomHint`、无边框缩放和运行时 flags 切换在 Windows/DWM、多屏和 DPI 下存在平台差异。
- 悬浮窗与主聊天共享当前 Conversation，切换会话和并发请求时必须绑定发起时的 conversation ID。
- 20px 字号会放大固定尺寸控件、富文本、Canvas gutter 和 lane 聚合压力。
- 0% 窗口不可见且仍可能接收鼠标，只能从主应用恢复。
- 允许事件类型变化会扩展 `EventService.update_event()` 契约，必须覆盖 reminder/timespan 双向转换。

## 测试矩阵（生产修改前建立）

| 范围 | 正常路径 | 边界值 | 非法/异常路径 | 回归路径 |
|---|---|---|---|---|
| 字号配置 | 应用/悬浮 13px 往返、即时应用 | 8px、20px、逐像素步进 | bool、7、21、字符串、保存失败 | 旧配置缺字段、主题切换、完整 JSON 不丢 Key |
| 字号覆盖 | 语义角色驱动 QSS/QFont/QPainter/HTML | Light/Dark、8/20px、窄窗口 | 静态扫描发现裸字号即失败 | 设置、日历、事项、AI、对话框、菜单、悬浮全部更新 |
| 时间轴 | 动态 gutter 与 8px 间隔 | 周/日、00:00/23:59、8/20px | 极窄宽度、字体回退 | lane、marker、overflow、命中不退化 |
| 悬浮壳 | 无标题/日期栏、底层/置顶切换、拖动/缩放 | 边角、拖动阈值、多屏、0/100% | 非法 opacity/geometry、flags 切换 | 输入和按钮不拖动、geometry 防抖、托盘恢复 |
| 当前/下一 | timespan、reminder duration、零时长 | 多个 current、同刻 next、跨日、端点 | 坏旧时间/类型 | 每分钟和日期变化刷新、两主题对比度 |
| 双击编辑 | 双击打开、保存并全局刷新 | reminder↔timespan | 取消、无效 ID、服务验证/写入失败 | 单击不打开、EventManager 编辑仍工作 |
| 悬浮 AI | 当前会话收到 user/think/assistant、成功 CRUD 刷新 | 会话切换、无操作回复、并发禁用 | 无配置、超时、非 200、畸形 JSON、非法操作 | 网络仍在 QThread、主聊天渲染/预算/历史不退化 |
| 发布 | 全量测试、导入、offscreen、完整构建、exe 冒烟 | 8/20px 两主题截图 | 构建失败不替换 exe | `dist/data/` 前后元数据与 SHA256 完全一致 |

## 实施步骤

- [x] 完成仓库、截图、现有文档、源码和测试只读盘点。
- [x] 由三个 subagent 分别审查悬浮/AI、日历/字号和测试/发布边界。
- [x] 集中询问并取得全部产品决策。
- [x] 编写 Phase 9 proposal、high-level design 和 detailed design。
- [x] 拆分 T31–T37 并更新共享进度与控制提示。
- [x] 主 agent 审核任务边界和测试先行顺序。

## 回滚方式

- 删除本轮新增 Phase 9 规划文档与 T30–T37 任务文件。
- 从 `doc/tasks/progress.md` 和 `doc/prompt.md` 移除 Phase 9 追加段落。
- 不触碰源码、配置、数据库、`data/`、`dist/data/` 或用户暂存 CI 改动。

## 完成定义

- [x] 三层设计文档无开放阻塞问题。
- [x] 每个后续任务都有文件边界、接口、风险、回滚、测试和完成定义。
- [x] 测试矩阵覆盖正常、边界、异常、回归、主题、无显示器和发布。
- [x] `doc/tasks/progress.md` 成为 Phase 9 单一进度来源。
- [x] 未修改任何生产代码。
