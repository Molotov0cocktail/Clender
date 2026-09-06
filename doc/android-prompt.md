# Phase 11 Android 多 Agent 控制提示

## 2026-09-06 当前完成状态与 Git 交付边界

T47/T48 的实现、构建与验收已完成。最终同源码 JVM 155 suites/1611 tests、UI 57 suites/665 tests，failure/error/skip 及四类泄漏标记均为 0；完整 verify-all、签名审计与设备验收全部完成。Git 交付为最后步骤，提交/推送回执以主 Agent 最终答复与 Git 日志为准；本记录不宣称已经 commit 或 push。下文阶段性的“仍进行中”“待设备”“未完成”及旧包摘要均保留为历史过程，当前状态以本节为准。

最终验收 APK SHA-256：`5d90cbb3d271063ad7b9c3645eb94ede9180bde62c46bde109a09cc0c56a897e`。API26/36 phone 与 API36 tablet 已完成最终包 core 8 步及实际 Widget；API36 phone/tablet 的最终 Drawer 18 步通过。所有 8 台设备的最终包安装与 hash 核验均通过。

增量证据复用保持原事实：API29/31/33 phone 的 core 复用 4cf 候选、Drawer 复用 04 候选；API35 phone 的 core 复用 4cf、Drawer 18 步使用最终包；API33 tablet 的 core 复用 c287、Drawer 18 步使用最终包。date/boot 继续复用 c287 证据。这是已完成的增量验收矩阵，不表述为所有场景都在最终包重新执行。

API26 Widget 原 ADB 清理临时 XML 失败记录保留；`completion-widget-api26-r4-resume-result.json` 为 PASS，恢复时只读确认配置已保存，再续跑自动更新/冷启动。此续跑不抹除原失败。历史 Room teardown 的 NON-REPRODUCIBLE 裁决不变，后续全绿不等于根因修复。

独立签名 key 与配置保持忽略，须由用户安全备份，不输出秘密。最终 Git 交付仍按已授权的统一集成提交与 Gitee `codex/android-architecture` 分支执行；不合并远程 main、不推 GitHub。本次仅同步原六份文档，完成即冻结，不测试、不修改生产、不提交。

## 2026-09-06 本轮执行状态

当前以 `doc/tasks/T47-T48-android-completion.md` 为接续主任务：QuickAI/P2C、新建事项真实 UI 与发布工具已进入集成，T47/T48 均未最终验收，不得标记 PASS。历史阶段 STOP/未开始记录保留其证据意义，不覆盖本轮已授权的接续范围。用户已授权代理自行决定不影响最终产品能力的实现细节；破坏性操作、真实数据或超出授权范围的事项仍须单独处理。

本轮每子阶段保持 tests-first、有效 RED、聚焦验证和 diff 审查；由主 Agent 对同一完整源码统一执行最终 Android/设备/Windows/PyInstaller/正式 smoke 门禁及集成提交。以下历史“每任务独立完整打包/提交”要求由主任务本轮效率修订替代，最终门禁不减少。主 Agent 独占共享构建、设备与真实签名；子 Agent 按最新明确文件授权工作，交付即冻结。

本轮审查重点：

- Widget 固定平台 RemoteViews，保留 API 26–30 单尺寸/API 31+ 四尺寸；Glance/native 禁令不变。
- 当前 Intent 交付契约：创建端仍为 CLEAR_TOP|SINGLE_TOP（0x24000000）。仅 EditEvent/QuickAI 消费 externalTaskFlags：NEW_TASK（0x10000000）可选，只有存在 NEW_TASK 时才允许附加 BROUGHT_TO_FRONT（0x00400000）；单独 BROUGHT_TO_FRONT 拒绝。QuickAI 的 EXCLUDE_FROM_RECENTS（0x00800000）独立可选。EditEvent 合法 flags 为 0x24000000/0x34000000/0x34400000；QuickAI 为这三种各自可选 EXCLUDE_FROM_RECENTS，另有 0x24800000/0x34800000/0x34c00000。内部导航真实交付仍为 0x24000000，不扩展；Configure/LocalRefresh 及 action/component/package、canonical data、唯一 appWidgetId、平台 owner 等边界不变。

真实 JDB 证据：QuickAI 交付 0x34c00000、EditEvent 交付 0x34400000，均比 ActivityTaskManager 日志多 BROUGHT_TO_FRONT；唯一 appWidgetId、canonical identity 与 owner 正常，仅去除此位同一 Intent 即校验成功。tests-first RED 为 QuickAI 58 tests/8 failures、EditEvent 30 tests/2 failures；完整证据见主任务。
- P2C 七种触发只读本地刷新，复用现有 scope/store/coordinator；远端 visible callback 不产生本地 mutation。WorkManager 只允许唯一日期边界 one-time REPLACE，不运行后台网络。
- Boot 非导出；合法系统广播允许空 extras 或唯一非负 Int `android.intent.extra.user_handle`，其余严格拒绝，9 秒上限/finish-once。
- 新建事项必须通过日历/事项顶层真实按钮进入当前选中日期表单，验证保存、取消和 recreate；不能只证明内部 route 可调用。
- 既有签名 APK/AAB audit 已通过，但不能据此宣布后续 P2C 最终产物或设备矩阵通过；最终状态/证据统一回填主任务。

## 当前证据与最终交接

2026-09-06 Drawer 最新接续契约：独立 AppDrawerState.kt 的 rememberSynchronizedDrawerState 由 ClenderApp 接入。snapshotFlow 观察 DrawerState.targetValue，以 Closed 为初始已观察值，忽略初始 Closed emission，避免覆盖 shell 已请求的打开意图；后续 target 变化同步 shell 与 settings 的 drawerOpen。rememberUpdatedState 保持观察回调使用最新 dependencies；请求端仅在 requestedOpen 对应 target 与当前 targetValue 不同时调用 open/close 动画。ClenderAppSettingsNavigation 的 Back 只提交 shell/settings 的 close 意图，动画由同步 helper 统一执行，消除重复关闭动画。既有 dirty/确认与导航安全边界不放宽。

2026-09-06 当前 Refresh 颜色修复后完整门禁：verify-all PASS，exit 0；Gradle 96 tasks（26 executed、70 cached），BUILD SUCCESSFUL，5m39s；release/device fixtures 111/111（2.397s），foundation 49/49（3.852s），boundary 49/49（3.706s）。JVM 155 suites/1611 tests，UI 57 suites/665 tests，failure/error/skip 均 0；CloseGuard、SQLiteConnectionPool、SQLiteDatabase leaked、RoomDatabase leaked 全部 0；六个冻结文件零漂移。日志 android/.tmp/completion-refresh-color-full-final-gates.txt，XML 结果 android/.tmp/completion-refresh-color-full-final-results。当前不再进行生产改动；下方154/1601及后半门禁曾进行中的记录保留为历史。完整门禁与设备验收均已完成，T47/T48实现、构建、验收完成；仅Git交付回执待主Agent最终答复/Git日志确认。

当前最终签名产物（含 Refresh 颜色修复）完整签名审计 PASS，证书不变：APK 1,724,626 bytes，SHA-256 `5d90cbb3d271063ad7b9c3645eb94ede9180bde62c46bde109a09cc0c56a897e`；AAB 4,615,672 bytes，SHA-256 `e5c5541df3e6d8e408a530eeb44615d34a3f93eb9511d367212eeef6c1cbacf5`。旧包摘要仅作历史证据，当前新包设备验收已完成；Git交付为最后步骤，回执见主Agent最终答复/Git日志。

2026-09-06 Refresh 主题颜色实现与聚焦历史证据：04候选包在 API26 Dark 下 LARGE Refresh 显示黑字；WidgetRemoteViewsRenderer 为 LARGE 的 widget_refresh 显式调用 setTextColor(..., colors.foreground)，使文字使用当前 Widget 主题前景色。生产仅此一行，不改变点击 Intent、刷新流程或其他尺寸布局。独立 WidgetRefreshColorTest 为10 tests/10 failures RED，修复后10/10 GREEN（10.036s）；detekt通过，测试两处换行的ktlint纯格式修复后 detekt+ktlint 8 tasks/35s成功。该聚焦阶段曾待重新签名和全量；现已取得上方155/1611、签名审计及完整verify-all PASS，设备矩阵仍待收口。下方154/1601、UI57/665和verify-all PASS均为该修复前的上一完整基线；T48仍待新包最终设备验收与交付收口。

2026-09-06 Refresh 颜色修复前上一同源码最终门禁（历史已通过）：verify-all wrapper PASS，exit 0；Gradle 96 tasks（30 executed、66 cached），BUILD SUCCESSFUL，6m25s；release/device fixtures 111/111（2.433s），foundation 49/49（3.936s），boundary 49/49（3.337s）。JVM 154 suites/1601 tests，UI 57 suites/665 tests，failure/error/skipped 均 0；CloseGuard、SQLiteConnectionPool、SQLiteDatabase leaked、RoomDatabase leaked 全部 0。六个冻结文件 hash 零漂移。日志 android/.tmp/completion-drawer-full-final-gates.txt，XML 结果 android/.tmp/completion-drawer-full-final-results。此前 lint/boundary 进行中及153/1577等旧结果保留为历史；该上一源码 gate 已通过；Refresh 修复后的JVM155/1611与新包签名审计已通过，完整wrapper已PASS，设备证据仍待补，T48整体仍待收口。

Refresh 颜色修复前签名产物历史证据（完整签名审计 PASS）：APK 1,724,618 bytes，SHA-256 `04b4681c90d4a51dcf30265fb54938a71d2f8c2381b8fcf33109ee5cd454873d`；AAB 4,615,682 bytes，SHA-256 `cb83dc87b86f08658d9272d5aa5a484862e26c63a36260cec0a3e1224f9a7172`。这些摘要对应修复前签名包，不能作为当前重签新包的最终摘要；证书指纹由主 Agent 补录，签名材料继续忽略且须安全备份。

Refresh 颜色修复前包设备历史证据：API 29 的 Drawer 18 steps 全通过；API 26 的 core 8 steps 全通过且旋转正常。其他设备继续验收，不把上述局部通过等同于完整最低矩阵或 T48 完成；上一源码 verify-all/lint/boundary 已通过，Refresh 修复后的新包最终设备与交付提交仍由主 Agent 收口。

Drawer 聚焦历史证据：API 26/36 合计 24 cases 全通过（22.191s），ktlint 通过；helper 拆为独立文件后 detekt + ktlint 共 8 tasks/36s 成功。后续同源码全 JVM 已获上方154/1601结果；153 suites/1577 tests、UI 56/641 仍仅为 Drawer 修改前历史基线。

2026-09-06 Drawer 修改前历史源码基线：153 suites/1577 tests，UI 56 suites/641 tests，failure/error/skip 均 0，四类资源泄漏标记继续为 0；静态、依赖、Manifest、APK 门禁已通过。本轮完整 96 tasks 用时 4m56s，仅测试列表换行触发 ktlint 失败；精确纯空白修复后 ktlintCheck + detekt 为 8 tasks/30s PASS，未改行为，不能将本轮完整命令记为一次 BUILD SUCCESSFUL。该历史阶段 policy 49/49 已通过、boundary 尚待执行；当前最终 boundary 已通过，见上方门禁记录。六个冻结构建依赖文件零漂移，配置宇宙 85；Room/schema/wire/Windows 生产零变化。

上一源码阶段历史证据：96 tasks（35 executed/61 up-to-date）、6m36s BUILD SUCCESSFUL，153 suites/1573 tests，UI 56/641；该数字不再代表当前源码。本轮绿色 JVM 与纯格式修复不改变历史 Room teardown 的 NON-REPRODUCIBLE 裁决，也不证明历史根因已修复。

发布工具 CI：主 Agent 在 .github/workflows/android.yml 增加独立 release/device fixture 步骤，仓库根执行 `python -B -m unittest discover -s android/tests/release -v`；本机固定解释器运行同一根目录命令为 111/111（2.821s）。这替代当前 fixture 数字引用；先前 111/111（3.292s）保留为历史证据。未新增工具或依赖，Windows job 未改；本机通过不等于声称远端 CI 已运行。

历史设备计划（当前最终包及全量结果以上方更新为准）：Drawer 修改前阶段曾冻结源码并进入重新签名。T48 仍处于设备验收与最终提交阶段。API 29/31/33/35 phone 在 4cf 候选包的核心 8 步均通过，当时仅 Widget flags 改动，曾按未受影响范围复用核心证据并计划新 APK 轻量安装/冷启；此次 Drawer 改动涉及核心导航，旧复用结论不能自动覆盖该改动，最终设备验收由主 Agent 重新核对。最低 API 26 phone、API 36 phone、API 36 tablet 继续要求完整最终包验收；API 33 tablet 首次核心验收待补。不得将候选包或历史审计哈希标为最终产物，该历史阶段待补的 APK/AAB 哈希现见上方；签名指纹仍由主 Agent 补录，不标 T48 完成。

Windows 198/198、imports 30/30、完整 PyInstaller 与正式 10 cycles/20 scenes 均通过；EXE 45,582,242 bytes，SHA-256 `C52F386542E78C5FA2E85C4A0B02F942FC2A2481C60694D5D29A33832CCA207D`；dist/data 5 files/196,074 bytes，路径、大小、时间及逐文件哈希前后完全一致。

QuickAI/P2C 与 Drawer 修复已通过同源码全 JVM；最终签名产物审计与 verify-all 全门禁通过，剩余设备与交付收口仍进行中，不标 T48 完成。独立签名材料已生成并保持忽略，脚本不回显密码/alias，用户须安全备份 key 与配置；当前 APK/AAB 哈希见本轮最新证据，最终证书指纹由主 Agent 补录。

真实 UI 修复：日历/事项顶层新建按钮以 selectedDate 进入现有表单；旋转/recreate 对同一 Detail/Edit ID 保留编辑态，对同日期且已有 form 的 New route 不再 startNew 覆盖草稿。MainActivity 重建不重放旧启动 Widget Intent，onNewIntent 继续独立校验。About 使用真实应用/版本 metadata，资源格式占位符已修复，不显示原样占位符；缺失 metadata 仍显示有限不可用。

历史 checkpoint 的“未开始”“QuickAI fail-closed”“无 Worker/Boot”与旧测试数量只描述各自阶段，不是当前基线。保留历史 Room/CalendarOverflow 一次 teardown 失败及 NON-REPRODUCIBLE 裁决；后续全量绿色不构成根因修复证明，不得宣称已修复该历史失败。

本轮已授权最终统一一次集成提交。Gitee 仅创建/推送 codex/android-architecture 远程分支；远程 main 只有 LICENSE 且与本地分叉，不合并 main，不推 GitHub。最终提交前复核 staged 白名单及 boundary，不能用暂存前通过代替索引门禁。

## 主 Agent

- 必读根 `AGENTS.md`、Android proposal/high-level/detailed design、T41–T48、`doc/tasks/progress.md`。
- 只在任务文件边界清晰后分派；同一时间不得让两个 Agent 修改同一生产文件、Gradle 文件、manifest、共享资源、progress 或根 AGENTS。
- 严格执行“只写测试 → 运行并记录缺失/失败 → follow-up 实现 → 聚焦通过 → diff 审查 → 集成”的两阶段节奏。
- 子 Agent 不安装工具链、不创建 keystore、不访问网络外部服务、不提交/push；工具链、共享构建、manifest、依赖冲突、共享文档和 Git 由主 Agent 负责。
- 每批前检查 `git status --short`，保留用户改动；每批后审查 diff、敏感模式、生成物和 Windows 边界。
- Android 只允许写 `android/`、Android CI、Phase 11 文档和根 AGENTS；不得修改 Windows Python/PyQt 生产源码，除非发现 Android 根配置确实破坏 Windows 且先向用户说明。
- 不读取真实 `data/`、`dist/data/` 内容；最终仅允许按根门禁做路径/大小/时间/哈希只读完整性比较。
- 发现新产品语义、数据契约、工具链大版本冲突、破坏性操作、真实服务/设备需求或签名风险，立即暂停对应任务并询问用户。

## 子 Agent

- 每次只接受一个任务或其中文件边界明确的子模块；先读取 Android 三层设计、分配任务、Android/根 AGENTS 和相关文件。
- 第一轮只能添加/更新分配范围测试，不得写生产实现；运行聚焦测试并返回失败/错误名称、命令和为何对应待实现契约。
- 收到主 Agent 的实现 follow-up 后，只修改任务允许的 production/test 文件；不得修改 Gradle 共享版本、manifest、progress、根 AGENTS、CI 或其他任务文件，除非 follow-up 明确授权。
- 不读取 Windows/Android 真实数据库、配置、keystore、Provider、WebDAV、API Key 或用户会话；网络只用 MockWebServer，数据只用 in-memory/临时 fixture。
- 不记录 Authorization、秘密、AI 上下文、事件正文或远端正文；不添加 Google Play services、Firebase、NDK/native 依赖、cleartext、周期网络或后台 AI。
- 完成前运行聚焦检查、检查自己的 diff，报告修改文件、红灯证据、绿灯命令/结果、未覆盖项和风险；由主 Agent 更新共享状态。
- 如果测试无法在旧/空实现产生预期失败，或需要越过文件边界，停止并回报，不用假断言掩盖。

## 必读上下文

- `doc/android-architecture-proposal.md`
- `doc/android-architecture-high-level-design.md`
- `doc/android-architecture-detailed-design.md`
- `doc/tasks/progress.md`
- 分配到的 `doc/tasks/T42-*.md` 至 `T48-*.md`
- `AGENTS.md` 与 `android/AGENTS.md`（创建后）

## 文件所有权与并行顺序

1. T42 由主 Agent 建立共享 Gradle/toolchain/test harness；完成前不并行生产实现。
2. T43 稳定后：
   - T44 只拥有 `domain/sync`、`data/network/webdav`、`sync` 与对应测试；
   - T45 只拥有 `domain/ai`、`data/network/ai`、`data/settings`、AI coordinator 与对应测试；
   - UI Agent 可先做 T46 纯 UI state/组件测试，但不得假造最终 repository API。
3. T46 集成 T43/T45；共享 navigation/resources/manifest 由主 Agent 最终合并。
4. T47 在 T46 路由稳定后实施；Widget manifest 和 Quick Activity 由主 Agent审查。
5. T48 只由主 Agent执行全矩阵、签名、artifact、Windows 回归、文档、commit 与 Gitee push。

## 每任务完成门禁（历史阶段规则；本轮适用上方修订）

- T42–T47 每项聚焦实现通过后，主 Agent 都必须运行 Windows 全量 unittest、导入、build check、完整 PyInstaller、隔离 exe 冒烟与 `dist/data` 前后只读摘要，更新根 AGENTS/任务/progress并创建独立可回滚提交；不能把全部提交和 Windows 门禁推迟到 T48。
- T48 负责最终合并矩阵、release artifact、再次 Windows 回归和 Gitee push，不取代前述每任务门禁。

## 测试先行门禁

- 新模块没有旧实现时，红灯证据可以是测试编译失败的缺失类型/方法，或契约断言对占位实现失败；不得把测试标记 ignored 来伪造。
- 修复集成 Bug 时必须新增能在修复前失败的最小回归。
- Room 修改必须覆盖事务和 migration；UI 修改必须覆盖 semantics、主题/字号和无显示器 Robolectric；AI 必须覆盖无配置、超时、非 200、畸形 JSON、危险操作、后台取消；Widget 必须覆盖多实例、午夜、进程/boot/Doze与 PendingIntent。

## 最终验证

- Android unit/Robolectric/Room/MockWebServer/Compose/平台 RemoteViews 全部通过；Glance 为永久拒绝的历史候选。
- lintRelease、detekt、ktlint、dependency verification、release R8 通过。
- API 26/29/31/33/35/36 phone 与 API 33/36 tablet instrumentation/冒烟完成；API 26 phone、API 36 phone 和 API 36 tablet 的 release 最低线不可自动豁免。
- release APK/AAB 签名、zipalign、badging、manifest、无 native/cleartext/secret/debuggable/testOnly 审计通过。
- debug/test variant 完成 AI/WebDAV mock；安装交付 release APK，完成冷启、空库、Drawer、CRUD、无配置/离线安全失败、生产装配和 Widget 冒烟，并证明无 fake/test hook/测试 CA。
- Windows 198 unittest（171 既有 + 27 frozen smoke）、导入、build check、完整 PyInstaller、隔离 exe 冒烟通过；`dist/data` 前后摘要一致。
- Git diff/staged diff 只有获准源码/文档/CI；无 SDK/cache/keystore/APK/AAB/data/日志/数据库/对话/秘密。
- 只推 Gitee `Molotov/codex/android-architecture`；不推 GitHub、不创建 Release。
