# T46：Compose 导航、日历、事项、AI 与设置 UI

## 目标

实现 Navigation Drawer 和日历/事项/AI 三份主视图，以及设置、关于、事件详情/编辑，完整接入 T43–T45。

## 非目标

- 不实现 Widget；不新增系统通知；不连接真实网络。

## 输入文档

- Android detailed design 第 7 节；T43–T45

## 影响文件

- `android/app/src/main/.../ui/`
- `android/app/src/main/.../app/MainActivity*`
- 对应 Compose/Robolectric/instrumentation/screenshot 测试与资源

## 接口/数据影响

- 顶层 routes、ViewModel UiState/Action、主题/字号、表单和内部 navigation contract。

## 风险

- 窄屏日历命中/文字重叠；状态恢复丢草稿；大字体不可达；平板返回/Drawer 行为不一致。

## 当前状态

- 2026-08-09 在 T44/T45 实施期间，仅并行准备不依赖最终 repository/AI 接口的 routes、Drawer、SavedState、表单、主题字号、语义与 adaptive policy 红灯；生产 UI 和共享资源仍等待 T45 接口冻结。
- 2026-08-31 T46-P0 完成：四组纯状态契约与 15 项测试（5+3+3+4）纳入 checkpoint 提交；实际 Compose 页面、Drawer、导航 host 尚未开始。
- 2026-08-31 T46-A 与 T46-B1 完成：应用壳之后已接入只读月/周/日日历、所选日期事项列表、overflow/detail 空壳导航及 `EventRepository → RoomEventRepository → Room v1` 生产查询。
- 2026-08-31 T46-B2 完成：事件详情、新建/编辑/删除、dirty 返回确认、有限错误、共享 EventService 生产写入与无正文 mutation version signal 已接线；T46 当前 P0/A/B1/B2/B ✅。
- 2026-08-31 T46-C 拆为 C1a（离线多对话管理、消息 UI、活动会话持久化）、C1b（真实 AI 提交/Thinking 请求/quick-ai）、C2（应用/AI/WebDAV 设置与同步状态）、C3（完整自适应/无障碍/集成收口）。C1a 已完成；C1b/C2/C3、T47/T48 未开始，T46 整体仍进行中。
- 2026-09-01 T46-C2b 已启动：欢迎检查通过（83 suites/752 tests、CloseGuard 0），任务契约见 `T46-android-webdav-settings-sync.md`；完成前 C2b 不再接收后续任务扩展。
- 2026-09-01 T46-C2b 完成：WebDAV 设置/连接测试/生产同步/有限状态 UI 全部实现并验证通过（93 suites/909 tests、CloseGuard 0、generated/boundary 44/44、Windows 171/171、PyInstaller/exe 双向冒烟、dist/data 摘要不变），实施结果见独立任务文件；C2b/C2 均完成，C3/T47/T48 未开始。

## P0：纯状态契约 checkpoint

### 目标

审计、验证并提交现有四组纯状态契约，形成 T46 后续 Compose UI 实施的干净 checkpoint：

1. 页面目标、路由参数编码及返回行为。
2. 日历模式、选中日期、抽屉状态和草稿 ID 的安全恢复。
3. 紧凑/中等/扩展窗口分类和 AI 单栏/双栏策略。
4. app/widget 字号相互独立，范围 8–20sp，非法值回退 13sp。
5. 关键语义控件最小点击目标不小于 48dp。
6. reminder/timespan 表单校验与类型转换。
7. 转换为 reminder 时清除旧 end time。
8. 生成的 AddEventCommand 满足 EventService 的输入边界。

### 非目标（P0）

- 不实现任何实际 Compose 页面、Navigation Host、Drawer、Calendar Canvas、Dialog 或表单 UI。
- 不修改 MainActivity、AndroidManifest、res、version catalog、Gradle build、dependency lock、verification metadata。
- 不引入 Navigation Compose、ViewModel Compose 或任何新依赖。
- 不修改 Room、repository、EventService、AI、WebDAV、DataStore 或网络代码。
- 不修改 T47 Widget、T48 发布任务。
- 不运行 connectedAndroidTest、模拟器或真实网络请求。
- 不读取或修改根 `data/`、`dist/data/` 内容；只允许生成只读完整性摘要。
- 不 push。

### 当前恢复现场

- Branch `codex/android-architecture`，HEAD `22b6e3c713ad3e500da21dd1883ce37fb7139fef`，staged 为空。
- 修改：`doc/tasks/T46-android-compose-ui.md`（仅原“当前状态”4 行）。
- 未跟踪：`android/app/src/main/java/com/molotov/clender/ui/{state,navigation,foundation,event}/` 四文件与 `android/app/src/test/java/com/molotov/clender/ui/{state,navigation,foundation,event}/` 四测试文件。
- F 依赖基线仍在：Compose Runtime 1.11.3、Lifecycle 2.9.4、SavedState 1.3.2、Annotation Experimental 1.4.1（仅 producer 传递）、Compose BOM 2024.08.00（UI 1.6.8）、无 graphics-path/Google/native。

### 允许修改的文件

- `doc/tasks/T46-android-compose-ui.md`、`doc/tasks/progress.md`、根 `AGENTS.md`。
- `android/app/src/main/java/com/molotov/clender/ui/**` 与 `android/app/src/test/java/com/molotov/clender/ui/**` 仅限最小修正。

### 状态和路由契约

- 五抽屉目的地 `calendar/events/ai/settings/about`；`events?date=YYYY-MM-DD`、`ai?conversation=<percent-encoded>`。
- `SavedAppState` 仅 destination/selectedDate/calendarMode/draftId；不保存 API Key、WebDAV 密码、AI 正文或事件描述。
- Codec 未知/非法值 fail-closed：未知 destination→CALENDAR、坏日期→默认日期、未知 mode→MONTH、空白 draft→null。
- 返回键顺序：drawer 开→CLOSE_DRAWER；可弹栈→POP_ROUTE；否则 DEFER_TO_SYSTEM。
- 自适应断点：`<600` COMPACT、`600–839` MEDIUM、`>=840` EXPANDED；AI COMPACT 单栏、其余双栏；导航一律 MODAL_DRAWER；宽度 `<=0` 抛 IllegalArgumentException。
- 字号：app/widget 独立，8–20sp，越界/缺失回退 13sp；主题 system/light/dark。
- 表单：BLANK_TITLE、NEGATIVE_ESTIMATED_DURATION（任意非负 Int，无 480 上限）、MISSING_END_TIME、END_NOT_AFTER_START（timespan end 严格晚于 start）、REMINDER_END_TIME_PRESENT；转 reminder 清除 endTime。

### 测试矩阵（P0）

正常路径：所有合法 destination 和 calendar mode 经 codec 往返；events 日期参数与 AI conversation ID 编码；合法 reminder/timespan 表单生成命令；compact/medium/expanded 分类边界。
边界路径：599/600/839/840dp；字号 8、20 及默认值；空 route stack；timespan 结束时间恰等于开始时间；reminder/timespan 相互转换。
非法/异常路径：未知 destination；非法日期、calendar mode、草稿 ID；空白标题；负 estimated duration；timespan 缺结束或结束不晚于开始；reminder 意外携带结束时间。
回归路径：返回键先关 drawer 再 pop 最后交给系统；转 reminder 清除旧 end；app 字号变化不影响 widget 字号及反向；恢复状态不含 API Key/WebDAV 密码/AI 正文等敏感字段。

### 风险

- 纯状态契约与后续 Compose UI 实现存在接线偏差的风险；本轮不接线，由 T46-A/B/C 处理。
- 测试若弱化（如触控目标下界）会导致契约漂移；已锁定 `>= 48dp` 断言。
- 未来引入 Navigation Compose 时必须重新解析、锁定并审计依赖，不得静默抬升基础原子组。

### 实施步骤

- [x] 恢复审计：branch/HEAD/staged/现场与 F 基线核对。
- [x] 两个只读子 Agent 审查生产与测试文件。
- [x] 测试先行补强：REMINDER_END_TIME_PRESENT、全 destination/mode 往返、触控目标 `>=48`。
- [x] 聚焦测试通过并报告实际数量。
- [x] Android 完整门禁（lint/detekt/ktlint/策略/无 Google/无 native/lock/APK）。
- [x] Windows 171 项回归、导入、build check。
- [x] PyInstaller 完整构建、隔离 exe 双向冒烟、`dist/data` 前后摘要一致。
- [x] 更新文档/AGENTS.md 并创建聚焦 checkpoint 提交。

### 回滚方式

- 删除 `ui/` 生产与测试包即可还原；不触碰领域、数据、MainActivity 或构建配置。

### 完成定义（P0）

- [x] 8 项契约全部通过对应测试锁定。
- [x] 聚焦测试实际数量已报告（15 项：5+3+3+4）。
- [x] Android 完整门禁、Windows 回归、PyInstaller/exe/`dist/data` 核验通过。
- [x] T46 标记为进行中、P0 已完成；T46 整体未完成。
- [x] 聚焦 checkpoint commit 已创建且未 push。

### 后续阶段边界

- T46-A：应用壳、主题、Drawer 与内部导航（含 `event/{id}`、`event/new?date=`、`quick-ai` 路由接线）。
- T46-B：月/周/日历视图与事件 CRUD UI。
- T46-C1a：离线多对话管理、消息 UI 与活动会话持久化。
- T46-C1b：真实 AI 提交、Thinking 请求状态与 quick-ai。
- T46-C2：应用/AI/WebDAV 设置、Keystore、模型获取与同步状态。
- T46-C3：完整 T46 自适应、无障碍与集成收口。
- T47 Widget、T48 发布保持未开始。

### 实施结果（2026-08-31）

- 恢复现场核对：branch/HEAD/staged/文件集与 F 依赖基线完全匹配 prompt；未删除、覆盖或 Git 还原任何受保护现场。
- 两个只读子 Agent：Agent A 审查四生产文件（设计/EventService 契约/半开语义）返回“生产无缺陷”；Agent B 审查四测试文件（正常/边界/非法/回归覆盖与敏感/真实数据/时区/网络依赖）返回 13 项计数准确、3 个最小测试缺口。
- 测试先行补强（生产代码零改动）：EventFormStateTest 新增 `reminderUnexpectedlyCarryingEndTimeIsRejected`；AppUiStateContractTest 新增 `everyDestinationAndCalendarModeRoundTripsThroughCodec`；UiPresentationPolicyTest 触控目标断言 `>=44` 收紧为 `>=48`。补齐后聚焦测试为 15 项（5+3+3+4）。
- 命令与结果：
  - `bootstrap-toolchain.py --verify-only`：`Locked Android toolchain verified`。
  - `gradle.ps1 --offline --no-daemon --dependency-verification strict testDebugUnitTest --tests "com.molotov.clender.ui.*"`：BUILD SUCCESSFUL；4 suites、15 tests、0 failure/error/skip。
  - `gradle.ps1 --offline --no-daemon --dependency-verification strict testDebugUnitTest lintDebug lintRelease detekt ktlintCheck verifyReleaseSigningPolicy verifyNoGoogleServices verifyNoNativeRuntimeArtifacts verifyResolvedVersionsLocked assembleDebug verifyDebugApkNoNativeArtifacts`：BUILD SUCCESSFUL（96 tasks）；Android JVM 全量 231 项（229 基线 + 2 新增）、0 失败；lint 55 任务真实执行、无 baseline/禁用/抑制。
  - `verify-generated.ps1`：policy 44/44 通过；`verify-boundaries.ps1`：policy 44/44 通过且 Android boundary passed。
  - Windows：`unittest discover -s tests` 171/171；全模块导入 `imports ok`；`build.py --check` 通过。
  - `build.py` 完整 PyInstaller：exe 45,582,260 bytes、SHA-256 `D167F0F592C419252E36A709930DA257C2FB6CA221B5403C0540CCAD4C88C403`。
  - 隔离 exe 冒烟：普通 primary + silent secondary（secondary exit 0）、silent primary + 普通 secondary（secondary exit 0）；最终 0 Clender 进程、临时目录已删除。
  - `dist/data` 前后均为 5 文件、195,620 bytes、组合摘要 `37FAECE06B2749469E2B1A34E07CE6529593858745C7F3A42981D7ABF3E3F8C2` 完全一致。
- 依赖/门禁审计：lockfile 与 verification metadata 零变化；F 版本零漂移（Runtime 1.11.3 / Lifecycle 2.9.4 / SavedState 1.3.2 / Annotation Experimental 1.4.1 仅 producer 传递 / BOM 2024.08.00 / UI 1.6.8）；无 Google services、无 native、APK 无 native。
- 提交：聚焦 checkpoint commit 已创建，未 push；commit 哈希见最终报告。

## T46-A：应用壳、主题、Modal Drawer 与内部导航

### 目标

实现 Android Compose 应用壳（`ClenderApp`）、Light/Dark/System 主题与 8–20sp 应用字号策略、Material3 ModalNavigationDrawer 五目的地、四类内部路由（顶层五 route、`event/{positive-id}`、`event/new?date=YYYY-MM-DD`、`quick-ai`）、基于 SavedStateHandle 的安全状态恢复，以及 MainActivity 真实应用壳接线与中文/英文资源与 TalkBack semantics。T46-B/C 内容页只显示本地化、可访问空壳标题，不接 repository/DAO/AI/WebDAV/DataStore。

### 非目标（T46-A）

- 不实现月/周/日日历、Canvas、lane、聚合块；不实现事件列表/详情/表单/CRUD。
- 不实现 AI 对话、Thinking、模型设置或同步 UI；不连接 Room/EventService/repository/DataStore/AI/WebDAV。
- 不引入 Navigation Compose、Hilt、Accompanist 或第三方 UI 库。
- 不修改 AndroidManifest 的 exported/intent-filter 或加入 BROWSABLE；不实现 T47/T48。
- 不运行真实网络、真实数据、设备或模拟器测试；不 push。
- 主题接受注入的 `AppearanceUiState`；生产入口使用安全默认值，AppPreferences 实时接线留给 T46-C。

### 影响文件

生产：`app/MainActivity.kt`、`ui/navigation/**`、`ui/state/**`、`ui/foundation/**`、新增 `ui/app/**` 与 `ui/theme/**`。
资源：`res/values/strings.xml`、`res/values-en/strings.xml`，必要时最小修改 `res/values/styles.xml`。
测试：`android/app/src/test/java/com/molotov/clender/ui/**`。
构建/依赖：`android/gradle/libs.versions.toml`、`android/app/build.gradle.kts`、`android/app/gradle.lockfile`、`android/gradle/verification-metadata.xml`；Android dependency policy tests 仅在确有必要时更新。
文档：本文件、`doc/tasks/progress.md`、根 `AGENTS.md`。

### 依赖授权

1. 把当前已解析并锁定的 `androidx.lifecycle:lifecycle-viewmodel-savedstate:2.9.4` 声明为直接 `implementation` 依赖；不得引起任何生产解析版本变化。
2. 增加 BOM 对齐的 `testImplementation(androidx.compose.ui:ui-test-junit4)`，预期版本 1.6.8。
3. 只允许该测试库官方 metadata 必需的 test configuration 传递依赖；不增加 `ui-test-manifest`。
4. 解析后比较全部 active configuration：debug/release compile/runtime 零变化；只允许 debug/release unit test 测试图增加 UI test harness。Runtime/Lifecycle/SavedState/Annotation Experimental/BOM/UI 等 F 基线版本不得漂移；无 native、Google SDK、graphics-path、动态或 SNAPSHOT；不增加 force/strictly/resolutionStrategy/exclusion。

### 风险

- `ui-test-junit4` 及其传递测试依赖改变 unit-test 解析图，可能触碰 dependency policy 冻结 lock 哈希；只允许测试图新增，主图与 84 active + stale `androidApis` 审计 universe 必须零漂移。
- Robolectric + Compose UI test rule（`createAndroidComposeRule<MainActivity>()`，无 `ui-test-manifest`）在 API 26/36 的离屏渲染稳定性。
- SavedStateHandle 只保存 destination/selectedDate/calendarMode/draftId；运行中子路由栈不写入 saved state，恢复 fail-closed。
- 200% 系统字体与 48dp 触控目标、RTL、360/600/840dp 下统一 modal drawer 的语义正确性。

### 测试矩阵（T46-A）

导航正常路径：五个 Drawer 目的地顺序/标签/选中态；选择后关闭 Drawer 并切换内容；`event/{id}`、`event/new?date=`、`quick-ai` route 往返；子页面 pop 回原顶层页面。
导航边界/非法：event ID 0/负数/非数字/溢出；new event 缺日期/非法日期/未知附加参数；空白或未知 route；HTTP/HTTPS/BROWSABLE 风格 route 拒绝；Drawer 打开且有子页面时返回键先关 Drawer；顶层根页面不得吞系统返回。
状态恢复：destination/date/calendarMode/draftId 经 SavedStateHandle 恢复；未知 destination/坏日期/坏 mode/空白 draft fail-closed；恢复容器只出现四类获准字段；不保存 API Key/密码/AI 正文/事件标题/描述；子路由栈不得写正文或外 URI。
主题/字号：Light/Dark/System 可选；8sp/20sp/非法回退；appFontSizeSp 变化影响应用 typography；widgetFontSizeSp 不影响应用 typography；Light/Dark 渲染颜色不同；不硬编码不可本地化字符串。
可访问性/布局：Drawer 开关有本地化 content description 与稳定 testTag；Drawer 项有 role/selected/标签；关键点击目标 ≥48dp；360/600/840dp 均 modal drawer；200% 字体下 Drawer 项与顶栏操作可找到可点击；zh/en 资源非空；RTL 不崩溃；API 26/36 离屏渲染不崩溃。
MainActivity：不再显示 `calendar_placeholder` 占位；启动显示真实应用壳与日历顶层目的地；任意 Intent data 不得作为外部 route 自动执行；recreate 后安全恢复顶层状态。

### API 契约（T46-A）

`ui/navigation/AppRoute.kt`（新增）
- `sealed interface AppRoute`，携带 `val route: String`，覆盖四类内部路由：
  - `data object Calendar/Events/Ai/Settings/About : AppRoute`（route 分别为 `calendar`/`events`/`ai`/`settings`/`about`，与 `AppDestination` 对应）
  - `data class EventDetail(val id: Int) : AppRoute`（route `event/{id}`，id 必须为正 `Int`）
  - `data class NewEvent(val date: LocalDate) : AppRoute`（route `event/new?date=YYYY-MM-DD`）
  - `data object QuickAi : AppRoute`（route `quick-ai`）
- `object AppRouteParser`：
  - `fun parse(raw: String?): AppRoute?`：严格 fail-closed；空白/未知/非法 ID/非法日期/未知附加参数/HTTP/HTTPS/BROWSABLE 均返回 `null`，不得抛异常
  - `fun serialize(route: AppRoute): String`（与 `route` 属性一致，供往返断言）

`ui/state/AppShellViewModel.kt`（新增）
- `class AppShellViewModel(savedStateHandle: SavedStateHandle) : ViewModel()`
- `val state: StateFlow<AppShellUiState>`，`data class AppShellUiState(destination: AppDestination, selectedDate: LocalDate, calendarMode: CalendarMode, drawerOpen: Boolean, childRoutes: List<AppRoute>, draftId: String?)`
- 方法：`openDrawer()`、`closeDrawer()`、`navigateTo(AppDestination)`、`pushRoute(AppRoute)`（仅子路由）、`popRoute()`、`selectDate(LocalDate)`、`changeMode(CalendarMode)`、`onBack(drawerOpen: Boolean): BackNavigationAction`（内部按 `BackNavigationPolicy` 决策）
- SavedStateHandle 只持久化 `destination/selectedDate/calendarMode/draftId`（复用 `AppSavedStateCodec`）；`childRoutes` 与 `drawerOpen` 为运行时状态，不持久化；恢复时未知 destination/坏日期/坏 mode/空白 draft fail-closed

`ui/theme/ClenderTheme.kt`（新增）
- `@Composable fun ClenderTheme(appearance: AppearanceUiState, content: @Composable () -> Unit)`
- `fun resolveIsDark(themeMode: ThemeMode, systemDark: Boolean): Boolean`（SYSTEM 取 systemDark）
- `fun clenderColorScheme(dark: Boolean): ColorScheme`（Light 与 Dark 背景颜色必须不同）
- `fun buildAppTypography(appFontSizeSp: Int): Typography`（`FontSizePolicy.normalize` 8–20，回退 13；不使用 `widgetFontSizeSp`）

`ui/app/ClenderApp.kt`（新增）
- `@Composable fun ClenderApp(viewModel: AppShellViewModel, appearance: AppearanceUiState = AppearanceUiState.fromPersisted(null, null, null))`
- Material3 `ClenderTheme` → `ModalNavigationDrawer`（所有宽度等级均使用 modal drawer）→ `Scaffold` + TopAppBar（三横线按钮）→ 当前 route 内容 host
- Drawer 开关：testTag `clender_open_navigation_drawer`，本地化 content description（`semantics_open_navigation_drawer`），点击目标 ≥48dp
- Drawer 项：testTag `clender_drawer_<destination.route>`，role/selected 状态/本地化标签（`drawer_calendar` 等），选择后关闭 Drawer 并切换顶层 destination
- 子页面（event/event new/quick-ai）与五个顶层页面均为本地化、可访问空壳（`screen_<route>` 标题），注释说明内容属于 T46-B/C；不制造 fake repository 或测试后门
- `MainActivity` 只负责创建 `AppShellViewModel`（`ViewModelProvider(this)`，依赖 SavedStateHandle 构造注入）并 `setContent { ClenderApp(viewModel) }`，不得堆叠导航逻辑；不再显示 `calendar_placeholder` 占位

### 资源契约（T46-A）

`res/values/strings.xml`（默认 zh）与 `res/values-en/strings.xml` 新增且两边非空：
- `drawer_calendar/events/ai/settings/about`（Drawer 项标签）
- `screen_calendar/events/ai/settings/about`（顶层空壳标题）
- `screen_event_detail`、`screen_event_new`、`screen_quick_ai`（子页空壳标题）
- `semantics_open_navigation_drawer`（三横线按钮 content description）
- `calendar_placeholder` 不再被 MainActivity 引用（可删除或保留），不产生不可本地化硬编码

### L2 审计裁决（Agent B 只读报告对应）

- **顶层 `events` vs `events?date=`（§1.1）**：仲裁为顶层导航一律走 `navigateTo(AppDestination)`，date 存 SavedStateHandle（`selectDate`），不复用 query 串。`AppRouteParser.parse` 对顶层 route 拒绝任何 query（含 `events?date=`），已由 Agent A 测试固化（`AppRouteParserTest.blankUnknownAndTopLevelQueryRoutesAreRejected` 断言 `parse("events?date=2026-08-09")==null`）。`AppRouteFactory.events(date)` 保留为 T46-B 日期联动跳转的构造器，其输出不经过 `AppRouteParser`。
- **双 `ThemeMode`（§2.1）**：仲裁为 `ClenderTheme` 只依赖 `ui.foundation.ThemeMode`；生产入口 T46-A 用 `AppearanceUiState.fromPersisted(null,null,null)`（安全默认 SYSTEM/13/13），不接 AppPreferences。`data.settings.ThemeMode`（大写 name 序列化）→ `ui.foundation.ThemeMode`（小写 wire）的映射属 T46-C，在 T46-A 不新增代码，仅记录风险。
- **`AppRoute` 顶层对象 vs `AppDestination`（§1.2）**：`AppShellUiState.destination` 保持 `AppDestination`；`AppRoute` 顶层 data object（Calendar/Events/Ai/Settings/About）只作为 `AppRouteParser` 的类型化输出与 `route` 往返表示；`navigateTo(AppDestination)` 为唯一顶层入口，`pushRoute(AppRoute)` 只接受子路由（EventDetail/NewEvent/QuickAi）。在 `AppNavigation.kt` 增加显式 `AppDestination.Companion.fromAppRoute(AppRoute): AppDestination?` 并加测试（Agent B §1.2 建议），避免双表示漂移。
- **`EventDetail.route` 具体化（§1.3）**：`route` 属性返回具体化字符串（如 `event/5`），文档中 `event/{id}` 仅为 pattern；parser 先匹配 `event/new` 再匹配 `event/<positive-int>`，防 `new` 误判为数字。
- **返回键（§1.5）**：`BackHandler` 唯一挂载点在 `ClenderApp`；开态由 Material3 `ModalNavigationDrawer` 内部处理（关闭 Drawer），关态且有子路由时 `popRoute()`，根页面时交系统；VM `onBack(drawerOpen)` 读自身 state 做决策。
- **Drawer 项语义（§3.2）**：五个 Drawer 项 testTag 为 `clender_drawer_<route>`（沿用契约，不修改 `UiSemantics.requiredTargets`，P0 测试保持不变）；`NavigationDrawerItem` 自带 role/selected 语义。
- **触控目标（§3.3）**：实现与测试以 ≥48dp 为准；设计文档 7.1 的 "44/48dp" 措辞过期为文档层，T46-A 不强制改设计文档（仅记录）。
- **L1 依赖（§4.1）**：`lifecycle-viewmodel-savedstate:2.9.4` 直接 implementation、`ui-test-junit4:1.6.8` testImplementation 已在 L1 落地并冻结（生产图零版本漂移，仅测试图新增 harness，policy 44/44）；随 T46-A 最终一并提交（单聚焦提交，不拆分 L1 独立提交）。

### 实施步骤

- [x] L0 恢复审计与任务文档（本文件、progress）。
- [x] L1 增加最小测试依赖并解析、冻结、比较全 configuration、native 扫描。
- [x] L2 只读审查 Agent + 只写测试 Agent：红灯证据。
- [x] L3 最小生产实现（routes/parser、SavedState ViewModel、theme/typography、Drawer/Scaffold/host、MainActivity、zh/en 资源）。
- [x] L4 聚焦绿灯：全部 ui 测试、API 26/36 Robolectric、Light/Dark、8/20sp、zh/en、360/600/840dp、MainActivity recreate、P0 15 项不退化。
- [x] 完整 Android 门禁、Windows 回归、PyInstaller/exe/`dist/data`、文档与提交。

### 实施结果（T46-A，2026-08-31）

- 测试先行：Agent A 新增 6 个测试类共 36 个测试方法，首轮 compileDebugUnitTestKotlin 因缺少 `AppRoute`/`AppRouteParser`/`AppShellViewModel`/`ClenderTheme`/`ClenderApp` 与 R.string 资源共 146 个编译错误（红灯证据），未使用 ignored/假断言。
- 只读审查：Agent B 返回路由/状态恢复/主题/可访问性风险报告，全部裁决写入「L2 审计裁决」。
- 生产实现：新增 `ui/navigation/AppRoute.kt`（sealed AppRoute + 严格 AppRouteParser）、`ui/state/AppShellViewModel.kt`（SavedStateHandle 四字段持久化 + 运行时子路由栈 + 返回策略）、`ui/theme/ClenderTheme.kt`（Light/Dark/System、8–20sp typography、颜色/字号 const 声明满足 detekt）、`ui/app/ClenderApp.kt`（ModalNavigationDrawer + Scaffold + 内容 host + 48dp 开关 + BackHandler）；`AppNavigation.kt` 增补 `AppDestination.fromAppRoute`；`MainActivity` 改为 `ViewModelProvider` 注入 `AppShellViewModel` 并 `setContent { ClenderApp(viewModel) }`，删除 `calendar_placeholder` 占位。
- 资源：`values/strings.xml` 与 `values-en/strings.xml` 各新增 `drawer_*`（5）、`screen_*`（8）、`semantics_open_navigation_drawer` 共 14 键并保持双 locale 非空；`calendar_placeholder` 随 MainActivity 弃用一并移除（lint UnusedResources 门禁）。
- 集成修复（主 Agent）：补 `ClenderAppDrawerTest` 的 `semantics.getOrNull` 导入；Drawer 测试改为经真实 MainActivity 壳驱动（`ViewModelProvider(activity)`），避免 `setContent` 二次调用冲突；资源 locale 断言改用 `createConfigurationContext`；Drawer 开关加 `sizeIn(min 48dp)` 满足 ≥48dp 断言；ClenderTheme/AppRoute 按 detekt ReturnCount/LongMethod/MagicNumber 与 ktlint 规则重构。
- 聚焦结果：`testDebugUnitTest --tests "com.molotov.clender.ui.*"` 为 65 项（P0 15 + 新增 36 + SDK 26/36 参数化展开），0 失败；P0 四测试文件未改动保持绿色。
- Android 完整门禁：`testDebugUnitTest lintDebug lintRelease detekt ktlintCheck verifyReleaseSigningPolicy verifyNoGoogleServices verifyNoNativeRuntimeArtifacts verifyResolvedVersionsLocked assembleDebug verifyDebugApkNoNativeArtifacts` 共 96 tasks 成功；Android JVM 全量 281 项（229 基线 + P0 2 + 新增 50）0 失败；lint 真实执行（debug/release 含 AndroidTest/UnitTest analyze），无 baseline/禁用/抑制。
- dependency：`lifecycle-viewmodel-savedstate:2.9.4` 直接 implementation（锁文件该坐标零变化）、`ui-test-junit4:1.6.8` testImplementation（BOM 对齐）；仅 unit-test 图新增 UI test harness，debug/release compile/runtime 零版本漂移；`test_dependency_policy.py` 仅 `FROZEN_UNAFFECTED_LOCK_SHA256` 更新（universe 不变 85=84+androidApis）；新增 AAR 扫描无 `jni/`/`lib/`/`.so`。
- generated/boundary：`verify-generated.ps1` 与 `verify-boundaries.ps1` 均 44/44 通过。
- Windows 回归：`unittest discover -s tests -v` 171/171；全模块导入 `imports ok`；`build.py --check` 通过。
- PyInstaller：`build.py` 完整构建成功，`dist/Clender.exe` 45,582,585 bytes、SHA-256 `2C1D7075D8FFEBBB94F9E8D6D2347F5B3C6FF99CC9E042EC9AA733E1C422AD32`；隔离目录双向单实例冒烟（普通 primary + silent secondary、silent primary + 普通 secondary）secondary 均 exit 0，最终 0 Clender 进程且临时目录已删除。
- `dist/data`：构建与冒烟前后均为 5 文件、195,620 bytes、组合摘要 `A1D1D137F68A4F63C03DB0425A53C69D045A15F4725418F072B232F4F88A3472` 完全一致；未读取真实数据内容。

### 回滚方式

删除 `ui/app/`、`ui/theme/` 生产包及对应测试，还原 MainActivity 到 T42 占位并撤销字符串/依赖修改；领域/数据保持独立。

### 完成定义（T46-A）

- [x] 应用壳/主题/Drawer/内部路由/状态恢复/资源全部实现并通过聚焦测试。
- [x] P0 15 项测试保持绿色；新增 ui 测试真实红灯后转绿。
- [x] Android 完整门禁、Windows 回归、PyInstaller/exe/`dist/data` 核验通过。
- [x] 文档与 AGENTS 同步，创建聚焦提交且不 push。

## T46-B1：只读月/周/日日历、事项列表与生产查询接线

### 目标与阶段边界

在不增加依赖、不修改领域/DAO/schema 的前提下，完成 Android sandbox Room v1 → `RoomEventRepository` → `EventRepository.observeRange` → `CalendarViewModel` → Compose 的只读生产链路；提供固定 6×7 月网格、周/日 24 小时时间轴、选中日期相交事项列表、日期/模式联动、受限错误与重试，以及单事项/overflow 到既有 `AppRoute.EventDetail` 空壳的导航。原 T46-B 拆为 B1（本任务，只读查询）与 B2（详情及 CRUD）；T46 整体继续进行中。

### 非目标

- 不创建、编辑、删除、保存事件，不调用 `EventService.add/update/delete`；详情/新建仍为 T46-A 空壳，真实表单、类型转换和删除确认留 B2。
- 不修改 `EventService`、`EventRepository`、`RoomEventRepository`、DAO、Room schema/migration、Manifest、AI、同步、WebDAV、DataStore、Widget 或 Windows 生产源码。
- 不新增/升级依赖、第三方日历库、Navigation Compose、截图依赖、fake/test hook、网络、设备/模拟器或 `connectedAndroidTest`；不修改 catalog/build/lock/verification metadata；不 push。
- 不显示 description、syncUid、updatedAt、异常正文、SQL、路径或事件正文；错误只映射为有限本地化状态。

### 允许影响文件与接口/数据影响

- 生产：`app/ClenderApplication.kt`、`app/MainActivity.kt`、可新增 `app/AppContainer.kt`、`ui/app/ClenderApp.kt`、`ui/calendar/**`、`ui/event/**`（仅只读列表）、`ui/state/**`（仅必要接线）、中英 `strings.xml`。
- 测试：`src/test/.../ui/calendar/**`、`src/test/.../ui/event/**`、`src/test/.../ui/app/**` 与必要的隔离 container 测试。
- 文档：本文件、`doc/tasks/progress.md`、根 `AGENTS.md`。
- 数据契约零变化。查询区间统一为 `[start,endExclusive)`；MONTH 查询完整 42 日网格、WEEK 7 日、DAY 1 日。UI 依赖领域 `EventRepository`，不得接 DAO/Room 具体类型；周/日布局唯一真源为 `CalendarLayoutEngine`。

### 已冻结实现裁决

- 月网格固定 42 日；`Locale.US` 周日开始，`Locale.SIMPLIFIED_CHINESE` 周一开始，其余取 `WeekFields.of(locale).firstDayOfWeek`。
- previous/next：MONTH 为 selectedDate ±1 month（标准 `LocalDate` 夹日规则），WEEK ±1 week，DAY ±1 day；`LocalDate.MIN/MAX` 溢出时保持原日期。
- 列表稳定排序为 startTime、有效 endTime、id；跨日 timespan 摘要显示实际起止日期。
- 日期/模式变化必须取消旧 Flow；empty/error/retry 不写事件。错误 state 只含有限 code。
- 月外日期可见但弱化；selected/today/outside-month/数量、overlap/sanitized 均须有非纯颜色语义。Canvas 视觉层与可点击 semantics 层分离；触控目标至少 48dp。
- 单块点击 push 正 ID `EventDetail`；overflow 只展示其 `eventIds` 对应事项，空/缺失/重复 ID fail-closed，选择后关闭 sheet 并 push detail。
- `AppContainer` 只在 `ClenderApplication` 中延迟 `Room.databaseBuilder`，数据库使用 Android 应用 sandbox 固定名称与现有 v1，不用 destructive migration；Application 启动不主动查询或写库。MainActivity 从容器取得 `EventRepository` 并构造 `CalendarViewModel`。

### 风险与 STOP 条件

- 风险：旧 Flow 迟到覆盖新 viewport；月外补齐日期或端点相交错误；最小视觉高度污染真实碰撞；overflow 跨越时间空隙；语义层与视觉层错位；Activity 启动意外写库；大字体/窄宽不可达。
- 若现有领域查询不足、必须修改 repository/DAO/schema/migration/依赖/Manifest、绕过 `EventRepository`、复制 layout/墓碑/overlap 算法、产生写入或猜测 B2/C 语义，立即 STOP-ESCALATE。
- 若 lock/metadata/85 项配置 universe/Runtime 1.11.3/Lifecycle 2.9.4/SavedState 1.3.2/UI 1.6.8 漂移，或引入 graphics-path/Google/native/动态/SNAPSHOT，立即停止并回滚本任务改动。

### 测试矩阵（先红后绿）

1. 范围/日期纯逻辑：28/29/30/31 日月份均 42 格；中文周一/US 周日/其他 locale；MONTH/WEEK/DAY 半开范围；上一/下一；跨年、1/31→2 月、闰日、MIN/MAX、月外 selected；空/不支持 locale 不崩溃；所有范围严格递增。
2. ViewModel/Flow：首次 loading 与正确订阅；模式/日期变化取消旧查询且迟到 emission 不覆盖；空→empty；异常→通用 code；retry 新订阅；三模式零写入；跨日 timespan 纳入、端点不相交排除、稳定排序；构造只依赖 `EventRepository`。
3. 月视图：42 个日期语义节点；locale 周标题；selected/today/outside-month 非纯颜色语义；数量 0/1/多；点击月外日期同步；360/600/840dp、8/20sp、200% 字体可找可点。
4. 周/日时间轴：24 小时；DAY 1 列/WEEK 7 列；0-duration marker、estimated reminder 实际区间、跨日切片；相邻不 overlap、2/3/N lane、窄列 overflow、空隙独立 overflow；overlap 纹理/语义；sanitized 只显示通用隐藏提示；视觉/语义层分别可定位。
5. overflow/导航：单块 push positive detail；overflow 打开 sheet 且仅列聚合 IDs；选择后关闭并 push；空/缺失/重复 ID fail-closed；detail 保持空壳。
6. 事项列表：reminder/timespan 本地化时间摘要；跨日显示实际日期；loading/empty/error/retry；点击 detail；不泄露 description/sync metadata/异常/DB；中英资源非空。
7. 生产隔离：Application 延迟创建 DB；路径属于 sandbox；无 destructive fallback；MainActivity 创建真实 CalendarViewModel；启动/空库不写且显示空态；测试不读取根 `data/`/`dist/data/`。
8. 回归：T46-P0/A 65 项、Drawer/BackHandler/主题/SavedStateHandle 全绿；依赖图、lock、verification metadata 零变化；API 26/36、Light/Dark、zh/en、8/20sp、360/600/840dp、200% 字体覆盖。

### 两阶段多 Agent 实施

- [x] L0：恢复检查、必读与本章节/progress 冻结。
- [x] L1 只写失败测试：Agent A（range/ViewModel/container）、Agent B（月/时间轴/block）、Agent C（列表/overflow/导航）；生产代码零修改。
- [x] L2 红灯：运行 B1 新测试与既有 `com.molotov.clender.ui.*`；只接受缺类型/占位/未接查询导致失败，既有测试必须绿色。
- [x] L3 follow-up 生产实现：Agent A 仅 policy/ViewModel/state；Agent B 仅 Month/Timeline/EventBlock；Agent C 仅 EventList/overflow sheet；共享壳、Activity/container/resources 由主 Agent。
- [x] L4 聚焦四组 UI 测试与 API/主题/字号/locale/宽度矩阵。
- [x] L5 完整 Android 离线门禁、Windows 171、导入/check、PyInstaller、双向 exe 冒烟、`dist/data` 前后摘要。
- [x] L6 文档/AGENTS、diff/秘密/生成物/staged 审查、指定代理、聚焦 commit；不 push。

### 回滚方式

删除新增 `ui/calendar/**`、B1 只读 `ui/event/**` 与 `AppContainer.kt`，还原 `ClenderApplication`、`MainActivity`、`ClenderApp`、必要 state、strings 和 B1 测试/文档；不触碰 Room v1、领域、用户数据、依赖或生成物。

### 完成定义（T46-B1）

- [x] 只读月/周/日、事项列表、日期联动、overflow/detail 空壳导航与生产 Room 查询链路全部满足冻结裁决。
- [x] 新测试有旧实现红灯证据并转绿；P0/A 回归不退化；无事件写调用。
- [x] dependency/configuration/native/Google/lint/generated/boundary/APK 门禁与 Windows/PyInstaller/exe/`dist/data` 门禁全部通过。
- [x] T46 状态为 P0 ✅、A ✅、B1 ✅，B2/C/T47/T48 保持未开始；创建聚焦提交且不 push。

### 实施结果（T46-B1，2026-08-31）

- 三个子 Agent 严格执行“先测试、后 follow-up 生产实现”：Agent A 提交 range/ViewModel/container 21 个源码测试方法，Agent B 提交月历/时间轴/block 19 个，Agent C 提交列表/overflow/导航 16 个；主 Agent 增加 1 个生产 Activity 端到端方法。API 26/36 参数化后新增 90 个 JVM test instances。
- 红灯证据：生产源码尚未修改时，B1 聚焦编译仅因计划中的 `CalendarRangePolicy`、`CalendarViewModel`、月/时间轴组件、事项列表、overflow/controller 与 `AppContainer` 缺失而失败；既有 65 项 `ui.*` 基线保持绿色。没有以依赖、工具链或环境错误充当红灯。
- 生产实现：新增 42 日 locale 月网格与安全 previous/next、可取消且防迟到覆盖的半开范围 Flow ViewModel、月历和由 `CalendarLayoutEngine` 唯一驱动的 24h 周/日时间轴、marker/lane/overlap/sanitized/overflow 可访问语义、所选日期只读事项列表与本地化摘要、精确 overflow bottom sheet 和正 ID detail 空壳导航。所有 UI 只依赖 `EventRepository`；没有事件写入、DAO 直连或算法复制。
- 生产装配：`ClenderApplication → AppContainer → RoomEventRepository → Room v1` 全部延迟初始化且固定在 Android sandbox；`MainActivity` 以现有 shell 状态创建真实 `CalendarViewModel`。未知更高 DB 版本 fail-closed，不启用 destructive fallback；测试显式关闭隔离 Room。
- 聚焦结果：`calendar.*`、`event.*`、`ui.app.*`、完整 `ui.*` 均 BUILD SUCCESSFUL；完整结果中 `ui.*` 147 项（calendar 70、event 14、app 30，含既有其他 UI 33 项），0 failure/error/skip。初次完整 lint 精确发现 5 项产品问题（Modifier 顺序、2 个 plural、2 个未使用状态资源），全部在代码/资源中修复后重跑通过，未使用 baseline、disable 或 suppress。
- Android 完整门禁：toolchain verify 通过；strict offline `testDebugUnitTest lintDebug lintRelease detekt ktlintCheck verifyReleaseSigningPolicy verifyNoGoogleServices verifyNoNativeRuntimeArtifacts verifyResolvedVersionsLocked assembleDebug verifyDebugApkNoNativeArtifacts` 共 96 tasks 成功；JVM 53 suites / 371 tests，0 failure/error/skip；generated/boundary policy 各 44/44。85 项 configuration universe、Runtime 1.11.3、Lifecycle 2.9.4、SavedState 1.3.2、UI 1.6.8 与五个冻结文件 SHA-256 均零漂移；无 Google/native/graphics-path/动态/SNAPSHOT。
- Windows：171/171 unittest、全模块导入与 `build.py --check` 通过；完整 PyInstaller 生成 45,580,825-byte exe（SHA-256 `B56011CB1349C0A478A6F5091BC264175F19054C1631C50C22C657B57A862223`）。普通 primary + silent secondary、silent primary + 普通 secondary 双向隔离冒烟均为 secondary exit 0、primary 存活并创建隔离 DB，最终 0 进程/0 临时目录。
- `dist/data` 构建与冒烟前后均为 5 文件、195,620 bytes、只读聚合 SHA-256 `3E1580D563151651E5A9FB962A679E2388D7DAB75FDC2A877A11B5123EC8CA56`；未读取数据内容、未接真实网络、未修改 Manifest/领域/DAO/schema/依赖/lock/verification metadata。
- 提交：完成最终 staged diff 与敏感模式检查后创建 T46-B1 聚焦提交，不 push；哈希见最终报告。

## T46-B2：事件详情、表单、CRUD 与 EventService 生产写入

### 目标与阶段边界

在不修改领域、repository、DAO、Room schema、Manifest 或依赖的前提下，把既有 `AppRoute.EventDetail/NewEvent` 空壳替换为可访问的事件详情和编辑器；所有 add/update/delete 仅通过 `EventService`，并在 `AppContainer` 中复用 B1 的同一个 `RoomEventRepository`。新增应用级、线程安全、无事件正文的单调 `StateFlow<Long>` mutation version signal，供后续 T46-C/T47 消费，但本轮不创建同步、网络或 Widget。

### 非目标

- 不修改 `EventRepository`、`EventService`、`EventValidator`、`RoomEventRepository`、DAO、Room schema/version/migration/SQL。
- 不实现 AI、WebDAV 设置、同步状态、网络、Widget、T46-C、T47 或 T48；不新增依赖、不修改 catalog/build/lock/verification metadata、Manifest。
- UI/ViewModel 不直接调用 `repository.insert/update` 或 DAO；表单正文不进入 SavedStateHandle、DataStore、日志、Bundle、错误状态或 mutation signal。
- 不连接真实网络、真实数据库、Windows `data/`/`dist/data/`、模拟器或 connectedAndroidTest；不 push。

### 允许影响文件与接口/数据影响

- 生产：`app/AppContainer.kt`、`app/MainActivity.kt`、`ui/app/ClenderApp.kt`、`ui/event/**`、`ui/state/AppShellViewModel.kt`（仅 route replacement 与 draft ID）、中英 `strings.xml`。
- 测试：`src/test/.../ui/event/**`、`src/test/.../ui/app/**` 与必要的 container/mutation 隔离装配测试。
- 文档：本文件、`doc/tasks/progress.md`、根 `AGENTS.md`。
- 数据/领域契约零变化；Room v1 与 B1 查询范围保持冻结。`AppContainer` 只创建并复用一个 Room repository、一个 `EventService` 和一个 mutation signal。

### 表单、详情与导航契约

- 新建默认 `REMINDER`、路由日期 09:00（秒/纳秒为 0）、end null、标题/描述空、duration raw string `"0"`。
- duration 在提交前严格解析 `Int`：空、非数字、负数、溢出为有限验证错误；0 与 `Int.MAX_VALUE` 合法，不截断、不夹值。
- 标题只以 trim 后非空为合法且提交时 trim；description 原样保留 Unicode/空白。timespan end 必须由用户明确选择且严格晚于 start；转 reminder 立即清 end，转 timespan 保持 end null；修改 start 不自动改 end。
- DatePicker millis 以 UTC epoch day 与 `LocalDate` 转换，不受设备时区影响；非整日输入按明确 fail-closed/归一契约测试，TimePicker 合成值强制秒/纳秒为 0，年份支持 0001–9999。
- update patch 仅对真实变化字段生成 `FieldUpdate.Set`；完全未变化为空 patch，UI 视为成功回详情且不调用 `EventService.update`。timespan→reminder 只需显式类型变化，end 清理由领域服务保证。
- 详情只显示标题、类型、起止时间、description、预计时长；不显示 syncUid/createdAt/updatedAt/deletedAt。正 ID load 状态为 loading/content/not-found/`LOAD_FAILED`/retry；墓碑等同 not-found。
- 保存错误只为 `VALIDATION_FAILED/NOT_FOUND/SAVE_FAILED`，删除错误只为 `NOT_FOUND/DELETE_FAILED`；不展示 Throwable message、SQL、路径、正文或堆栈，失败保留当前表单。
- 删除必须经详情页显式按钮与 AlertDialog 二次确认；取消零写入，成功 pop detail，失败可恢复。Saving/Deleting 禁止重复操作；成功才通过一次性 Channel/Flow 导航 effect。
- new 成功以 `EventDetail(newId)` replace top；edit 成功回同一 detail；delete 成功 pop。clean new back 直接 pop，clean edit back 回 detail；dirty new/edit back 先显示放弃确认，取消留在表单，确认分别 pop/回 detail；子页显示 Back 而非 Drawer，dirty 不得从 Drawer 绕过确认。
- `AppShellViewModel` 只新增 `replaceTopRoute` 与无正文 draft ID 设置/清除；draft 必须为 32 位小写 UUID hex。SavedStateHandle 仍只含 destination/date/calendar_mode/draft_id，不保存表单内容。

### Mutation 与生产装配契约

- mutation signal 初始 version 0；每次 Added/Updated/Deleted/Batch sink 回调原子递增一次，只保存/暴露 `StateFlow<Long>`，不持有 Event、标题、description、同步 metadata 或异常；进程重启可从 0 开始。
- `AppContainer` 的 `EventService` 使用 `Clock.systemUTC()` 与 `UUID.randomUUID().toString().replace("-", "")` 等价的唯一 32 位小写 hex UID 生成器，并与 B1 查询共享同一个 `RoomEventRepository`/数据库实例。
- EventService 只在持久化成功后通知 signal；失败不递增。Application/container 保持 lazy，启动不主动写库，不发网络请求。

### 风险与 STOP 条件

- 风险：route 切换后旧 load 覆盖新 route、配置变化重复 effect、双击产生重复写、UTC date picker 时区漂移、dirty 表单绕过确认、空 patch 误写、错误状态泄露正文、AppContainer 创建双 repository。
- 若必须修改领域/repository/DAO/Room/Manifest/依赖/冻结 metadata，或只能使用空 mutation sink、UI 直写 repository/DAO、保存正文到系统 state/日志/DataStore、无法确认删除或阻止重复写，立即 STOP-ESCALATE。
- 若 lock/metadata/85 configuration universe/冻结版本漂移，或 Android/Windows/PyInstaller/exe/`dist/data` 门禁失败，停止且不提交。

### 测试矩阵（先红后绿）

1. 表单纯逻辑：新建默认；合法 reminder/timespan；blank title；duration 空/非数字/负数/溢出/0/`Int.MAX_VALUE`；end 缺失/等于/早于/跨日；双向类型转换；start 使 end 失效但不改写；Unicode；add command；update 每字段 patch、类型变化/清 end 与完全 no-op。
2. Picker codec：epoch day 0、闰年、年份 1/9999、UTC+14/UTC-12 不漂移、非整日 millis 明确处理、秒/纳秒归零。
3. ViewModel：detail loading/content/not-found/error/retry/tombstone；new 初始化/edit 回填；add/update/delete 成功；持久化失败无 mutation/effect；double submit/confirm 一次写；route 切换取消旧 load且 stale 不覆盖；空 patch 不 update；失败保留表单；delete false→not-found；effect 一次消费；clear/cancel 无写入。
4. Mutation/container：同一 Room repository；UTC clock metadata；唯一 32 位小写 UID；成功 add/update/delete 各 +1、失败 +0、Batch callback +1；signal 类型无正文；container lazy、不启动写库/网络。
5. 详情 UI：全部允许字段、四个禁止 metadata 字段、loading/not-found/error/retry、edit/delete、正 ID、中英/主题/字号/尺寸矩阵。
6. 编辑 UI：完整回填、类型切换、start/end picker、inline validation、invalid save disabled、saving 禁止重复、错误保留、长内容可滚动、错误 semantics 不复制 description。
7. 删除：先确认、cancel 零写、confirm 一写、double confirm 不重复、success pop、failure 可恢复且错误无标题/异常正文。
8. 返回/draft：clean/dirty new/edit、discard confirm/cancel、dirty 无 Drawer 绕过、add replace、update detail、delete pop；SavedStateHandle 只新增合法 draft ID 而无表单正文。
9. Room 集成：隔离 DB 中 add 后 B1 月计数/列表更新；update 跨日期；双向类型转换；delete 墓碑从普通查询消失而 sync snapshot 保留；mutation 仅事务成功后发生；显式关闭 DB，无真实数据。
10. 回归/矩阵：既有 UI 147 项和 Android 371 项不退化；API 26/36、zh/en、Light/Dark/System、8/20sp、200% 字体、360/600/840dp、空库/验证失败/持久化失败/取消/重复点击；依赖冻结文件零变化。

### 两阶段多 Agent 实施

- [x] L0：恢复/必读/领域可满足性检查，T46-B2 与 progress 冻结，既有 `ui.*` 147 项绿色。
- [x] L1 第一阶段仅测试：Agent A（Form/Patch/ViewModel）、Agent B（Screen/Dialog/Picker）、Agent C（Mutation/Container/Room integration）；生产/资源/Gradle/文档零改动。
- [x] L2 红灯：新增 B2 与完整 `ui.*`；缺 B2 类型、占位详情/新建页、无 EventService 装配产生预期编译/行为失败，既有 147 项先独立保持绿色。
- [x] L3 follow-up：Agent A 实现 `EventFormState` 扩展与 Crud ViewModel/state；Agent B 实现详情/编辑器/picker/dialog；Agent C 实现 mutation signal 与聚焦装配；共享 AppContainer/MainActivity/ClenderApp/resources/Git 由主 Agent。
- [x] L4 聚焦 event/app/ui、EventService/Room 与完整矩阵验证。
- [x] L5 完整 Android strict/offline、Windows 171、导入/check、PyInstaller/exe 双向冒烟及 `dist/data` 前后摘要。
- [x] L6 文档/AGENTS、diff/秘密/生成物/staged 审查、代理配置、聚焦提交；不 push。

### 回滚方式

删除新增 `ui/event/**` B2 文件并还原 `EventFormState`、`AppContainer`、`MainActivity`、`ClenderApp`、`AppShellViewModel`、中英字符串及 B2 测试/文档；Room v1、领域、DAO、Manifest、依赖和用户数据保持不变。

### 完成定义（T46-B2）

- [x] 详情/新建/编辑/删除/确认/dirty 返回/有限错误/可访问性全部符合冻结契约。
- [x] 所有本地写入仅走共享 `EventService → RoomEventRepository`；mutation version signal 无正文且只在成功后递增。
- [x] 新测试先取得有效红灯再转绿；既有 P0/A/B1 和完整 Android/Windows/PyInstaller/exe/`dist/data` 门禁不退化。
- [x] T46 标记 P0/A/B1/B2/B ✅、C 未开始；T47/T48 未开始；创建聚焦提交且不 push。

### 实施结果（T46-B2，2026-08-31）

- 测试先行：初始 `ui.*` 21 suites/147 tests 全绿；三个 Agent 第一阶段只新增失败测试，分别因缺失 Form/Crud ViewModel、详情/编辑器/picker/dialog 与 mutation/AppContainer 装配而有效失败，主 Agent 的真实路由集成在占位页产生 6 个预期失败。第二阶段共新增 111 个 JVM test instances，未以依赖、工具链、真实数据或空断言充当红灯。
- 实现：`EventCrudViewModel` 只以 `EventRepository.findById` 查询、以 `EventService.add/update/delete` 写入；表单使用严格 raw duration、UTC epoch-day picker codec、分钟精度、显式类型转换与差异 patch/no-op；详情/编辑器/删除及 dirty 放弃确认完整接入，错误均为有限 code。SavedStateHandle 只新增/清除 32 位小写 hex draft ID，不保存正文。
- 生产装配：`AppContainer` 复用同一个 lazy `RoomEventRepository` 给 B1 查询和生产 `EventService`；`Clock.systemUTC()`、随机 32 位小写 UUID hex 与线程安全 `StateFlow<Long>` mutation signal 已接线，Added/Updated/Deleted/Batch 每次 sink callback 只递增一次，失败写入不递增且 signal 不持有正文。
- 聚焦：`ui.event.*` 103/103、`ui.app.*` 40/40、`ui.*` 246/246、EventService/Room 36/36；API 26/36、zh/en、Light/Dark/System、8/20sp、200% font scale、360/600/840dp、validation/失败/取消/重复点击由对应参数化 suites 覆盖。
- Android：锁定工具链 verify-only 通过；strict/offline 完整 96 tasks 成功，JVM 64 suites/482 tests、0 failure/error/skip；lintDebug/lintRelease/detekt/ktlint、签名/Google/native/lock、assembleDebug/APK native 检查全绿；generated/boundary 各 44/44。85 configuration universe、Runtime 1.11.3、Lifecycle 2.9.4、SavedState 1.3.2、UI 1.6.8 与五个冻结文件 SHA-256 均零漂移。首次 `verify-generated.ps1` 因未显式传 Python 在内部 assemble 成功后 fail-closed，补 `-PythonExecutable C:\Users\30910\Miniconda3\python.exe` 后与 boundary 均完整通过。
- Windows/构建：offscreen 171/171、全模块导入、`build.py --check` 与完整 PyInstaller 通过；exe 45,582,579 bytes，SHA-256 `D834B612169D95BC9B4C41FB70126A9683BE01C2419A39039F5EC5B38EADBBEC`。普通 primary + silent secondary、silent primary + 普通 secondary 均 primary 存活、隔离 DB 创建、secondary exit 0；最终 0 Clender 进程/0 测试临时目录。
- `dist/data`：前后均 5 文件、195,620 bytes、组合摘要 `3E1580D563151651E5A9FB962A679E2388D7DAB75FDC2A877A11B5123EC8CA56`；未修改或复制真实内容。未修改 domain/data/DAO/Room schema/Manifest/Gradle/依赖冻结文件，未实现 AI/WebDAV/Widget/T46-C/T47/T48。

## T46-C1a：离线多对话管理、自适应消息 UI 与活动会话持久化

### 目标与阶段边界

首次显示 AI destination 时才激活真实 `ConversationViewModel`，以既有 `ConversationManager`、`ConversationRepository` 与 Room v1 实现本地多对话新建、选择、重命名、清空、删除和当前消息 Flow；以新的最小 `ActiveConversationStore` contract 只在共享 DataStore 中持久化活动 conversation ID；实现 USER/ASSISTANT/THINK 纯文本消息、逐条默认折叠 Thinking，以及 `<600dp` 单栏、`>=600dp` 双栏布局。C1a 全程离线，不提交消息、不调用 AI/秘密/HTTP。

### 非目标

- 不调用或装配 `AiCoordinator`、`AiClient`、OkHttp、`SecretStore`，不读写 API Key，不追加/伪造 USER/ASSISTANT 消息。
- 不实现发送输入、AI 请求状态、quick-ai、AI/应用/WebDAV 设置、模型列表、同步状态、Widget、C1b/C2/C3、T47/T48。
- 不修改 `ConversationRepository`/`ConversationManager` 语义、DAO、Room entity/schema/migration、EventService/mutation signal、Manifest、Gradle/catalog/lock/verification metadata 或依赖。
- 不把消息正文、标题、rename 草稿或 Thinking 展开状态写入 DataStore、SavedStateHandle、Bundle 或日志；不连接真实数据、网络、模拟器或 connectedAndroidTest；不 push。

### 允许影响文件与接口/数据影响

- 生产：仅新增 `domain/conversation/ActiveConversationStore.kt`、`data/settings/DataStoreActiveConversationStore.kt`；修改 `app/AppContainer.kt`、`app/MainActivity.kt`、`ui/app/ClenderApp.kt`；新增 `ui/ai/**`；更新中英 `strings.xml`。
- 测试：`src/test/.../data/settings/*ActiveConversation*`、`src/test/.../ui/ai/**`、`src/test/.../app/*Conversation*` 与必要的隔离 Room conversation 集成测试。
- 文档：本文件、`doc/tasks/progress.md`、根 `AGENTS.md`。`android/AGENTS.md` 规则不变则不修改。
- 新 contract：`ActiveConversationStore.activeConversationId: Flow<String?>` 与 `setActiveConversationId(String?)`。DataStore 文件固定为 `context.filesDir/datastore/clender.preferences_pb`，仅操作 `AppPreferenceKeys.ACTIVE_CONVERSATION_ID`，与其他设置共享同一 lazy DataStore 实例但执行局部原子 edit。

### 冻结契约

- `ConversationViewModel` 构造零数据库/DataStore访问；`activate()` 幂等，仅 AI destination 首次可见时调用。冷启停留 calendar 不创建默认对话、不打开消息 Flow、不创建 DataStore 文件。
- activate 读取 preferred ID 并调用 `ConversationManager.resolveActive`；空库恰好创建一个 locale 默认标题对话；解析后写回 active ID。ID 为 `UUID.randomUUID()` 去连字符后的 32 位小写 hex，Clock 为 `Clock.systemUTC()`。
- 列表保持 repository 的 createdAt、id 顺序。选择会立即更新 active、持久化 ID、取消旧消息 observation，并用 job/version guard 阻止迟到 emission。消息稳定按 timestamp、id 排序。
- create 成为 active；rename trim 后非空；clear/delete 必须确认。删除 active 后确定选择下一个，删除最后一个立即创建替代对话；删除 inactive 不改变 active。operation guard 阻止重复写。
- 状态仅 inactive/loading/ready/有限 error code；Throwable/message/SQL/路径/ID 不进入 UI error。Flow error 可 retry；ViewModel 清理取消全部 job；本轮零 `appendMessageAndIncrementTokens`。
- DataStore null 删除 key，非 null trim 后须非空，blank 拒绝；读取 blank/corrupt 与 IOException 返回 null，非 IOException 传播；串行更新且不覆盖 theme/font/AI/WebDAV/navigation 等 key，不记录 ID/内容。
- compact 初始消息 pane，可显式进入 conversation list，选择后回消息；medium/expanded 始终双栏。返回优先 dialog、Drawer、compact list→messages、既有 event dirty/child route、system。
- THINK 默认折叠且逐条内存展开，切换 conversation 重置；USER/ASSISTANT/THINK 有本地化角色语义，文本保留 Unicode/emoji/换行并按纯文本显示，不解析 Markdown/HTML、不创建链接/WebView/远端资源。

### 测试矩阵（先红后绿）

1. Active store：初始 null、Unicode/trim、blank 拒绝、null 删除、IOException 回退、非 IO 传播、局部更新不改其他 key、并发最后写确定、sandbox/datastore 路径与 backup/device-transfer exclusion。
2. 激活：未 activate 零访问；空库仅建一个；preferred 存在/缺失；重复 activate；有限初始化错误与 retry。
3. 管理：create + 唯一 hex ID；rename trim/blank 零写；clear/delete confirm/cancel；删除 active/inactive/last；重复操作单写；失败可恢复；token count 刷新。
4. Flow：排序、切换取消、迟到保护、missing conversation、有限 Flow error/retry、删除后停止旧 ID observation、零 append。
5. 消息 UI：三角色、Thinking 默认折叠/逐条展开、切会话重置、Unicode/emoji/换行、空/长消息滚动、纯文本、无内部 ID。
6. 自适应/无障碍：360/599 单栏、600/840 双栏、compact pane/Back、selected semantics、关键目标 >=48dp、zh/en、Light/Dark/System、8/20sp、200% font、API 26/36。
7. 装配：event/conversation repository 复用同一 Room DB；DataStore lazy；calendar 冷启零 conversation/DataStore side effect；首次 AI 激活；真实 ConversationViewModel；无 AI client/secret/network 初始化；close 取消 DataStore scope 并关闭 Room；不读根真实数据。
8. 回归：既有 `ui.*` 246 项与 Android JVM 482 项不退化；B1/B2/Drawer/Back/主题保持；五个冻结依赖文件、85 configuration universe、native/Google/APK/边界零变化。

### 两阶段多 Agent 实施

- [x] L0：恢复/必读、C1a/progress 冻结、既有 `ui.*` 246 项绿色。
- [x] L1 第一阶段只写失败测试：Agent A（active store/ViewModel）、Agent B（conversation list/dialog/adaptive）、Agent C（message UI/container/Room integration）；生产/资源/Gradle/文档零改动。
- [x] L2 红灯：C1a 新测试与完整 `ui.*`；失败仅为缺失 contract/ViewModel/UI/装配；既有基线保持绿色。
- [x] L3 follow-up：Agent A 实现 store/ViewModel/state，Agent B 实现 list/dialog/adaptive，Agent C 实现 message/Thinking/聚焦集成；共享 AppContainer/MainActivity/ClenderApp/resources 由主 Agent。
- [x] L4 聚焦 ai/settings/app/Room 与完整 UI 矩阵。
- [x] L5 完整 Android strict/offline、Windows 171、导入/check、PyInstaller/exe 双向冒烟、`dist/data` 前后摘要。
- [x] L6 文档/AGENTS、diff/秘密/生成物/staged 审查、代理配置、聚焦提交；不 push。

### 风险与 STOP 条件

- 风险：eager DataStore/Room side effect、旧 Flow 迟到、delete-last 竞态、局部 edit 覆盖设置、Back 优先级破坏 B2 dirty、长文本/200% 字体不可达、错误泄露内部信息。
- 若需改 DAO/schema/migration/依赖/Manifest、调用 AI/秘密/网络、伪造/追加消息、保存正文才能恢复 active、覆盖其他 DataStore key、calendar 冷启无法保持零副作用，或扩展 C1b/C2/C3/T47/T48，立即 STOP-ESCALATE。
- 若既有 UI 基线、冻结依赖/85 universe、Android/lint/Windows/PyInstaller/exe/`dist/data` 任一门禁失败，停止且不提交。

### 回滚方式

删除 C1a 新增 contract/store/ui/ai 与测试，精确还原 AppContainer/MainActivity/ClenderApp/strings/文档；Room v1、DAO、领域已有语义、依赖、Manifest 和用户数据均不变。

### 完成定义

- [x] 离线多对话、活动选择、消息 Flow、Thinking、自适应和有限错误全部满足冻结契约。
- [x] 新测试有有效红灯并转绿；既有 UI/Android/Windows/PyInstaller/exe/`dist/data` 不退化。
- [x] 本轮无 AI/秘密/HTTP/message append，依赖与 schema 零变化。
- [x] T46 标记 P0/A/B1/B2/B/C1a ✅，C1b/C2/C3 未开始；T46 整体仍进行中；创建指定提交且不 push。

### 实施结果（T46-C1a，2026-08-31）

- 三个 Agent 按文件边界完成测试先行：第一轮只增加 active store/ViewModel、列表/对话框/自适应、消息/容器/Activity 集成失败用例；首轮失败均指向尚不存在的 C1a contract、ViewModel、Compose 组件和生产装配。实现后 C1a 8 suites / 112 test instances（API 26/36）全绿，完整 `ui.*` 为 35 suites / 334 tests。
- 新增 `ActiveConversationStore` 与 `DataStoreActiveConversationStore`；固定 sandbox 文件 `filesDir/datastore/clender.preferences_pb`，只局部 edit active ID，null 删除、blank 拒绝、IOException 安全回退，不保存标题或正文。`AppContainer` 复用单一 Room v1，装配 `RoomConversationRepository` 和独立 SupervisorJob/IO DataStore scope，关闭时取消 scope 并关闭已初始化 Room。
- `ConversationViewModel` 构造保持 inactive，AI destination 首次显示才幂等 activate；以 `ConversationManager.resolveActive` 处理空库/preferred ID，管理 create/select/rename/clear/delete，确认与 operation guard、Flow job/version guard、有限 error/retry 均已接线。MainActivity 使用 UTC Clock、32 位小写 UUID 与 Activity locale 默认标题；calendar 冷启不创建默认对话、不开消息 Flow、不创建 DataStore 文件。
- Compose AI 页实现 compact 单栏列表/消息切换与 medium/expanded 双栏；USER/ASSISTANT/THINK 纯文本和本地化语义明确，THINK 逐条默认折叠、展开状态仅按当前 conversation 留在 Compose 内存；无发送输入、AI coordinator、SecretStore、HTTP、message append 或 quick-ai 接线。
- Android：锁定工具链 `--verify-only` 通过；完整 JVM 72 suites / 594 tests、0 failure/error/skip；`lintDebug`、`lintRelease`、detekt、ktlint、签名策略、无 Google services、无 native runtime、85 configuration lock universe、assembleDebug/APK no-native 全绿；generated/boundary 各 44/44。五个冻结依赖文件、Gradle、Manifest、DAO、Room schema/migration 均零变化。
- Windows：offscreen 171/171、全模块导入、`build.py --check` 与完整 PyInstaller 通过；`dist/Clender.exe` 45,580,992 bytes，SHA-256 `647293A49A5E31A6E1B8BB0D5AB91652554D3D447DBA81F1C468D983FA0BA7F7`。普通 primary + silent secondary、silent primary + 普通 secondary 均 secondary exit 0、primary 存活并创建隔离 DB，最终 0 测试进程/目录。
- `dist/data` 构建前后均 5 文件、195,620 bytes、路径/大小/UTC 时间/逐文件 SHA-256 组合摘要 `EEDB7057629C759BB9B17FC7813CD944C8C253B8B7C4F1573BD5687EAAD5705B`，完全一致；未读取真实正文、未复制或修改真实数据。T46 仍进行中，C1b/C2/C3、T47/T48 未开始。

## T46-C1b：真实 AI 提交、请求 Thinking 状态与内部 quick-ai

### 目标与非目标

在 C1a 多对话页面加入真实纯文本输入与发送，生产复用既有 DataStore/Keystore/OkHttp/预算/解析/执行/Coordinator 链路；以 app-scoped 有限状态展示 idle、working/thinking、completed、cancelled 与有限错误；实现既有内部 `AppRoute.QuickAi` 的活动对话快捷输入、摘要和跳转。请求必须捕获发起时 conversation ID，AI 日程写入只走 `AiCoordinator → AiResponseParser → AiOperationExecutor → EventService → Room`；进后台取消，Activity 旋转不取消，不自动重试。

本阶段不实现 C2 设置/模型列表/WebDAV UI、C3 收口、QuickAiActivity、Widget、T47/T48；不修改 DAO/Room schema/version/migration、Manifest、Gradle/catalog/lock/verification metadata 或依赖；不引入流式 token/Thinking、后台 AI、通知、分析/网络日志、真实 Provider/Key/用户数据、release fake 或运行时测试开关，也不 push。全新安装未配置时安全显示“未配置”是预期行为。

### 恢复现场与允许影响文件

- 恢复检查通过：branch `codex/android-architecture`、HEAD `bfd78b5cc04d5d66109a0531fac7e9aa94456481`（`Implement Android T46 offline conversation UI`），工作树 clean、staged 为空。
- Agent A 第一阶段仅改 AI coordinator/gateway/lifecycle 对应测试；红灯后实现仅限 `app/ai/**` 与必要既有 coordinator 文件。
- Agent B 第一阶段仅改 submission ViewModel/composer/quick-ai 对应测试；红灯后实现仅限 `ui/ai/**` 与必要测试。
- Agent C 第一阶段只新增生产装配/隔离端到端测试，不修改生产、资源、Gradle、文档或 Git；仅向主 Agent 返回共享装配建议。
- 主 Agent 独占 `AppContainer.kt`、`ClenderApplication.kt`、`MainActivity.kt`、`ClenderApp.kt`、中英 `strings.xml`、文档/AGENTS 与全部 Git 操作，并负责跨 Agent 集成修复。

### 接口、数据影响与冻结契约

- `AiCoordinatorState.Failed` 改为有限错误码（配置、超时、网络、Provider、协议/响应、内部）；UI 只由 enum 映射本地化字符串，绝不展示 Throwable message、HTTP body、URL、prompt、回复、标题、conversation ID 或路径。Cancelled 独立；只允许 Failed/Cancelled acknowledgement 回 Idle，不能借此取消 Working。
- 新增应用级 submission gateway。每次发送先读共享 `DataStoreAppPreferences.state.first()` 并验证 endpoint/model，再按需读取 `SecretAlias.AI_API_KEY`；返回 accepted/busy/unconfigured/rejected/settings failure/secret failure 等有限结果。Key 在 accepted/rejected/exception/cancelled 全路径覆写归零；缺配置/Key/读取失败时不追加 USER、不联网、不执行 EventService、不改变 mutation version并保留草稿。
- AppContainer lazy 复用 C1a/B2 的同一 RoomEventRepository、RoomConversationRepository、EventService、ScheduleMutationVersionSignal 与 DataStore；AI runtime 仅在 AI destination 或 QuickAi 首次显示时幂等激活。calendar 冷启动不得读 Key、创建 Keystore alias/DataStore/default conversation 或发网络。ProcessLifecycle binding 全进程一次；container close 取消 app scope、解绑 observer 并关闭已初始化资源。
- `AiSubmissionViewModel` 构造 inactive，`activate()` 幂等；草稿仅存 ViewModel 内存，不进 SavedStateHandle/DataStore/Room/Bundle/log/error。外围 trim、内部 Unicode/emoji/换行保留；空白零提交；accepted 清草稿，busy/未配置/preflight/secret/settings 失败保留；operation guard 防双击；所有 composer 共享全局 busy；dismiss 仅清有限 UI/ack terminal，不取消 Working，ViewModel clear 不取消 app request。
- 主 AI composer 为多行纯文本、send ≥48dp 且有本地化语义；blank/inactive/Working 不可发送；显示 working/thinking、有限失败/取消和恢复操作；未配置只导航既有 Settings placeholder。USER/ASSISTANT/THINK 均保持纯文本，最终 THINK 继续逐条默认折叠；显示本地化“近似/Provider 统计”的 tokenCount。
- QuickAi 只显示输入、busy/completed/cancelled/有限错误摘要，不展示历史、assistant/Thinking 正文；使用最近活动 conversation，空库只创建一个默认会话；支持“查看 AI 对话”、打开 Settings 与现有 child-route Back，不新增 Activity/Intent filter/BROWSABLE。
- 请求捕获提交时 conversation ID；切换活动会话不取消，完成结果只写原 conversation。消息事务 emission 后 ConversationViewModel 以 selection/version guard 刷新对应 metadata/tokenCount，迟到结果不得污染新会话。
- 保持 T45 single in-flight、background cancellation、无普通网络自动重试、Thinking 仅 400/422 单次协议降级、取消后无 assistant/think/operation/自动重发；多 schedule operation 继续只产生一次 batch mutation version。

### 测试矩阵与测试先行

第一阶段三个 Agent 只能新增测试，生产/资源/Gradle/文档零改动；先确认既有 Android JVM 72 suites/594 tests、`ui.*` 35 suites/334 tests 与 C1a 8 suites/112 tests 全绿。新增测试必须因缺失 C1b contract/行为产生有效红灯，不得以工具链、缺依赖、真实网络或无意义断言充当红灯，取得证据后才 follow-up 实现。

1. Coordinator/gateway/lifecycle/security：成功 Thinking/plain/operation；timeout/network/non-200/protocol/oversize/internal 有限映射；所有错误无 prompt/Key/body/URL/title/conversation ID；busy/blank/missing/background 零第二请求；缺 endpoint/model/key、坏 envelope、settings IOException；Key 全路径归零；foreground/background/binding once/close；旋转/observer replacement 不取消；后台取消无 assistant/think/operation/retry；危险/未知 action、未知字段、非法 ID/时间零 Event 写；malformed JSON 只走安全 plain reply；多日程操作 mutation +1。
2. Submission/ViewModel/UI：inactive 零 runtime、activate 幂等；blank 零提交；accepted 清草稿，其余 preflight 保留；double-click 一次；Working/Idle/completed/Failed/Cancelled；切会话不串写；clear/recreate 不取消；composer blank/busy/错误/未配置/recover；请求 Thinking 与最终 THINK 并存；token metadata 更新；QuickAi 仅输入/摘要；QuickAi→AI/Settings/Back；360/599/600/840dp、8/20sp、200% font、zh/en、Light/Dark/System、API 26/36、TalkBack/stateDescription/≥48dp；Unicode/emoji/换行/长输入纯文本且无 HTML/Markdown/WebView。
3. 生产装配/隔离 E2E：calendar 冷启零 AI/DataStore/Keystore/network side effect；AI/QuickAi 首显 runtime once；同一 Room/EventService/DataStore；真实 OkHttpAiClient/EnvelopeSecretStore 且无 fake；release 未配置零网络；隔离 DB + fake/MockWebServer 的 reply/Thinking/token；timeout/non-200/malformed/dangerous；AI CRUD 后 calendar Flow 可见且 batch mutation +1；background cancel/no retry/no正文泄露；close scope/unbind/Room；无真实数据、真实服务、test CA 或 release hook。
4. 回归：C1a 112、`ui.*` 334、Android JVM 594 基线不退化；app.ai/data.settings/network.ai/domain.ai/ui.ai/app 聚焦全绿；API 26/36 Robolectric 展开；五个冻结依赖文件、85 configuration universe、Runtime 1.11.3/Lifecycle 2.9.4/SavedState 1.3.2/Annotation Experimental 1.4.1/UI 1.6.8、Manifest、schema、Google/native/APK 边界零变化。

### 风险与 STOP 条件

主要风险为 Key 擦除不完整、lazy 边界被破坏、ProcessLifecycle 重复注册、旋转误取消、后台未取消、captured conversation 串写、危险操作绕过 parser/EventService、错误正文泄露、mutation 重复，以及大字体/长输入不可达。

若需要新依赖或修改 Gradle/catalog/lock/verification metadata、Manifest、DAO/Room schema/version/migration，扩展 C2/C3/Widget/QuickAiActivity/T47/T48，UI 直连 DataStore/SecretStore/OkHttp/DAO，只能用原始异常/正文作错误状态，Key 无法全路径擦除，calendar 冷启无法 lazy，请求不能同时满足旋转保留与后台取消，captured conversation/危险操作无法保证，必须访问真实 Provider/Key/数据，或 85 universe/冻结版本/native/Google/Manifest/Android/Windows/PyInstaller/exe/`dist/data` 任一门禁漂移，立即 STOP-ESCALATE；失败时仅保留任务记录和证据，不提交半成品。

### 实施步骤、回滚与完成定义

- [x] 恢复 branch/HEAD/clean/staged 与必读文档核对；冻结本节与 progress 启动状态。
- [x] 运行 594 Android JVM、334 UI 与 112 C1a 修改前基线。
- [x] 三 Agent 第一阶段只写测试并记录有效红灯；主 Agent 审查 diff 后发 follow-up。
- [x] Agent A/B 完成边界实现；Agent C 提交装配建议；主 Agent 完成共享 container/application/activity/app/resources 集成。
- [x] 聚焦、完整 Android strict/offline、generated/boundary、Windows 171、导入/check、完整 PyInstaller、双向 exe 冒烟与 `dist/data` 前后摘要全部通过。
- [x] 更新本文件/progress/根 AGENTS，完成 diff/生成物/秘密/staged 审查，设置代理，提交 `Implement Android T46 AI submission UI`，不 push，提交后 clean。

回滚时删除 C1b 新增 gateway/submission/ui/tests，精确还原 coordinator、AppContainer/Application/Activity/ClenderApp/strings/docs；Room v1、DAO、Manifest、依赖、真实数据与既有 C1a/B2 保持不变。完成要求为全部冻结契约和测试矩阵转绿、真实 Provider 未访问、依赖/Manifest/schema 零变化、完整发布门禁与提交成功；T46 仍为进行中，C2/C3/T47/T48 仍未开始。

### 实施结果（T46-C1b，2026-08-31）

- 三个独立 Agent 按测试先行边界工作：A 新增 coordinator/gateway/lifecycle/security 16 个 API 26/36 test instances，B 新增 submission/ViewModel/UI/QuickAi 32 个 instances，C 新增生产装配/惰性/隔离 12 个 instances，共 4 个新 suites/60 instances；首轮统一 compile-red 仅因缺失 C1b API/行为，未修改生产、资源、Gradle 或文档。A/B 获得红灯后分别只实现 `app/ai/**`、`ui/ai/**`，C 保持 tests-only，主 Agent 完成共享装配和集成修复。`*C1b*` 三个新 suites 48 项 + 既有 `AiCoordinatorTest` 10 项的聚焦命令为 4 suites/58 tests；生产装配新 suite 另为 12 tests。AI/runtime/app 聚焦 28/269、C1a 8/112、`ui.*` 37/366 均绿色。
- `ConfiguredAiSubmissionGateway` 仅在 AI/QuickAi 首显后读取同一 DataStore AI 设置和 Keystore envelope；未配置/坏设置/坏 secret 安全返回有限结果且零网络。`AiCoordinator` 捕获 conversation ID，按 USER→预算/context→OkHttp→parser→executor→EventService 链路执行，Thinking/assistant/token 写回原会话；timeout/network/non-200/protocol/oversize/internal 映射有限 enum，危险/未知/非法操作不绕过 parser/EventService，多操作仍只发一次 batch mutation。
- API Key 由 gateway 取得后交给 coordinator 内部副本；调用方数组、内部请求副本在 accepted/rejected/异常/取消/completion 全路径覆写归零。全局 app scope + `ProcessLifecycleOwner` 保证 Activity recreate/ViewModel clear 不取消请求，进后台取消 in-flight 且不重试、不追加 assistant/think/operation；container close 幂等解绑 observer、取消 scope 并关闭已初始化 Room。calendar 冷启动保持 AI/DataStore/Keystore/network lazy。
- 主 AI 页加入共享内存草稿、多行纯文本 composer、全局 busy、completed/cancelled/有限错误与本地化近似 token；最终 THINK 沿用 C1a 逐条默认折叠。内部 QuickAi 只显示输入/有限摘要，复用最近活动会话并提供 AI/Settings/Back；不显示历史/assistant/Thinking 正文，不新增 Activity/Intent/Manifest。消息 emission 后 `ConversationViewModel` 以 selection/version guard 刷新活动会话和列表 token metadata。
- 完整 Android 为 76 suites/654 tests，0 failure/error/skip。完整套件曾在 API 26 `CalendarAppIntegrationTest` 冷启动超时；单类始终绿色，UI 聚焦的 SQLite CloseGuard 进一步定位为 C1b `createAndroidComposeRule<MainActivity>` 测试覆盖 content 后未关闭生产 AppContainer。统一 `@After` 销毁 scenario、关闭 container 并清除隔离 DB/DataStore 后，`--max-workers 1 --rerun-tasks testDebugUnitTest detekt ktlintCheck` 40/40 tasks、654 tests 稳定通过，原 30 秒断言未放宽。其余 strict/offline 86/86 tasks 强制串行真实执行；最终 `lintDebug`/`lintRelease` 55/55 tasks 再次真实执行，签名、无 Google/native、85 configuration universe、锁版本、assembleDebug/APK no-native 全绿；未加 baseline/disable/suppress。工具链 verify、generated/boundary 各 44/44 通过；五个冻结依赖文件、Runtime 1.11.3/Lifecycle 2.9.4/SavedState 1.3.2/Annotation Experimental 1.4.1/UI 1.6.8、Gradle、Manifest、DAO、Room schema/version/migration 零变化。
- Windows offscreen 171/171、全模块导入、`build.py --check` 与最终完整 PyInstaller 通过；`dist/Clender.exe` 45,581,503 bytes，SHA-256 `44C830B513AF5DE24C9FDDB2F321B71D9E3E2D204C16742F85C0B22775230AAA`。normal primary + silent secondary、silent primary + normal secondary 均以最终 exe 验证 secondary exit 0、primary 存活并创建隔离 DB；onefile 父/子 PID 按隔离 executable path 清理，最终 0 Clender 进程/0 冒烟目录。
- `dist/data` 构建前后均为 5 文件、195,620 bytes，相对路径/大小/UTC/逐文件 SHA-256 聚合 `E43733909427A268005A9514FB136CDA0F74C43395046269E73EF20E539668D9` 完全一致；未打开、解析、复制或修改真实正文。未访问真实 Provider/Key/用户数据，未 push；T46 仍进行中，P0/A/B1/B2/B/C1a/C1b ✅，C2/C3、T47/T48 未开始。

## T46-C2a：应用与 AI 设置、秘密原子保存、模型获取和生产主题接线

### 目标与非目标

本阶段只把 Settings 顶层目的地替换为真实的 Application/AI 设置页：持久化 System/Light/Dark、独立 8–20sp 应用/Widget 字号，接入生产 `ClenderTheme`；提供完整 AI 非秘密表单与 API Key KEEP/REPLACE/REMOVE；实现“先原子保存当前表单、再从刚保存状态取 endpoint/Key”的模型获取；让 C1b chat 与 model fetch 在 USER append 前共享全局网络 gate；主 AI/QuickAi 的“打开设置”先选择 AI section 再导航；补齐 dirty Drawer/Back/放弃确认。

明确不实现 WebDAV 设置、密码、连接测试或同步状态/生产 `SyncCoordinator`；不实现 C2b/C3、Widget、QuickAiActivity、About 最终页、T47/T48；不改 DAO、Room entity/schema/version/migration、Manifest、Gradle/catalog/lock/verification metadata、依赖或 SavedStateHandle 四字段契约；不改变 C1a 对话语义、C1b parser/executor/EventService 安全边界，不增加流式/后台 AI、通知、真实 Provider 或 push。

### 起始现场与恢复证据

- Branch `codex/android-architecture`，HEAD `2a81b1d2774871751384a3485ecef41d1f3366f5`，提交 `Implement Android T46 AI submission UI`。
- 修改前 working tree clean、staged 为空，`git diff` 与 `git diff --cached` 均为空；未 stash/clean/reset/checkout。
- 基线冻结为 Android JVM 76 suites/654 tests、`ui.*` 37 suites/366 tests、Windows 171/171；Runtime 1.11.3、Lifecycle 2.9.4、SavedState 1.3.2、Annotation Experimental 1.4.1、Compose UI 1.6.8、85 configuration universe。
- P0/A/B1/B2/B/C1a/C1b 已完成；C2b/C3、T47/T48 未开始。

### 影响文件与 Agent 边界

- Agent A 第一阶段只改 `data/settings/**` 测试；有效红灯后只改 `data/settings/**` 生产与对应测试。不得改 app/ui/AppContainer/resources/docs/Git。
- Agent B 第一阶段只改 `app/settings/**`、必要 `app/ai` gate 的测试；有效红灯后只改这些生产文件与对应测试。不得改 UI、AppContainer/MainActivity/ClenderApp/resources/docs/Git。
- Agent C 第一阶段只改 `ui/settings/**` 测试；有效红灯后只改 `ui/settings/**` 生产与对应测试。不得改 AppContainer/MainActivity/ClenderApp/resources/docs/Git。
- 主 Agent 独占 `AppContainer.kt`、`ClenderApplication.kt`、`MainActivity.kt`、`ClenderApp.kt`/`ClenderAppEffects.kt`、中英 strings、跨 Agent 集成测试/修复、任务/progress/AGENTS 与 Git。
- 允许新增 `app/settings/**`、`ui/settings/**` 以及必要的 `data/settings/**` 内部 codec/store；现有 WebDAV preferences 与 `WEB_DAV_PASSWORD` envelope 只能保持原样。

### 数据、秘密与 section 原子保存设计

- 保留 `DataStoreAppPreferences.save()` 作为兼容 API，但生产 Settings 禁止 state-first/copy/full-save；新增 `updateAppearance` 与 AI section 原子 update，一次 `DataStore.edit` 只写所属 section keys，不覆盖 active conversation、WebDAV、navigation 或其他 section。
- 抽取单一 preferences/envelope key codec，让 `DataStoreAppPreferences`、`DataStoreSecretEnvelopeStorage` 和 AI 原子 settings store 共享相同 wire keys；禁止复制两套 envelope 编码。
- Appearance update 只写 theme/appFontSizeSp/widgetFontSizeSp；AI update 只写 AI 非秘密 keys，并按 mutation 处理 API Key envelope：KEEP 逐字段不动，REPLACE 先完成 AES-GCM envelope 生成、再在同一次 edit 写 AI keys+envelope 三字段，REMOVE 在同一次 edit 写 AI keys并删 envelope 三字段。
- validation/encryption 失败不开始 edit；edit 失败保持旧 AI 设置和旧 envelope。Keystore 中无引用 alias 不是真源，DataStore envelope 才是真源。corrupt preference/envelope 继续 fail closed。
- API Key 不解密回填 UI，只暴露 configured/not configured；空白输入是 KEEP，显式确认才 REMOVE，非全空白 REPLACE 时保留原始字符。输入不进入 SavedStateHandle/Bundle/DataStore 明文/日志/错误状态；保存成功、离页、clear、取消敏感操作时清引用。所有交给 cipher/client 的 `CharArray` 在成功、失败、拒绝、取消路径归零。

### AI 网络互斥与模型获取设计

- 在 `app/ai` 提取由 chat 与 model fetch 共享的 app-scoped gate；chat 必须在读取 Key、追加 USER 和发网络前成功占有 gate，model fetch 同样只允许一次。不得依赖 `OkHttpAiClient` 内部 in-flight 异常兜底。
- model fetch 顺序固定：校验当前 draft → chat Working/gate busy 时 BUSY 且零保存/取 Key/网络 → 原子保存当前 AI section+secret mutation → 从刚提交的保存结果/同一 DataStore envelope 读取 endpoint 与 Key → 缺失返回 UNCONFIGURED → 复用单一真实 `OkHttpAiClient.fetchModels` → 全路径擦 Key。
- 当前表单 validation/save/encryption 失败不得回退磁盘旧 endpoint/Key；列表只在内存去重并保持 provider 首次顺序，不自动选第一个、不覆盖当前 model。无自动重试，不记录 URL/Authorization/body/models/异常正文。
- 结果限定为 SUCCESS/BUSY/UNCONFIGURED/VALIDATION_FAILED/SAVE_FAILED/TIMEOUT/NETWORK/PROVIDER/INVALID_RESPONSE/CANCELLED/INTERNAL。Settings 离页/ViewModel clear 取消 model fetch并释放 gate，不取消 chat；chat 捕获旧 settings/Key继续执行，后续 chat 读取新保存设置。

### Settings UI、生命周期与 dirty navigation 设计

- appearance observation 在 App 启动后只读非秘密 DataStore Flow；不得打开 Room、创建对话、读 Key、创建 Keystore alias/AiClient或联网。Flow 成功保存后立即驱动整个 `ClenderTheme`；appFontSizeSp 影响 app typography，widgetFontSizeSp 只持久化与局部预览。
- sensitive activation 只有 Settings 首显时幂等执行，只读取 AI 非秘密设置和 Key presence，不解密 Key、不联网、不触碰 WebDAV secret/network/Room。
- Settings 仅 Application/AI 两 section；Drawer 直入默认 Application，`showAiSection()` 只存 ViewModel 内存。C1b 主 AI/QuickAi 入口先 `showAiSection()` 再导航 Settings。
- 非秘密 draft 或 secret input 相对最后持久化值变化即 dirty；返回优先级固定为 secret remove dialog → 其他 Settings dialog → Drawer close → dirty discard confirmation → 既有系统 Back/navigation。Drawer 其他目的地与系统 Back 均先确认；Discard 擦 secret、恢复持久化 draft后执行原动作，Cancel 留页；成功保存清 dirty、失败保留 dirty；无自动保存。
- 页面在 360/599/600/840dp、8/20sp、200% 字体、zh/en、Light/Dark/System、API 26/36 下可滚动可达；关键动作 ≥48dp；API Key password semantics/visual transformation 且语义树无明文；loading/saving/fetching 防重复；模型列表可滚动，空/失败可恢复。

### 测试矩阵与有效红灯标准

1. Data/settings：appearance/AI partial update；active/WebDAV/navigation 与另一 secret 不变；和 `DataStoreActiveConversationStore` 并发不丢 active ID；KEEP/REPLACE/REMOVE 单 edit；validation/encryption/edit failure 零部分状态；corrupt envelope；调用方与 cipher `CharArray` 擦除；`AppPreferencesState` 不出现 secret raw fields。
2. App/settings/gate：Application/AI 保存有限 decision；model fetch save-before-request 且使用刚保存 endpoint/Key；当前 draft失败绝不回退旧配置；missing endpoint/key；timeout/network/non-200/malformed；model ID 去重稳定顺序；取消/key wipe；chat↔model fetch 双向 gate；设置变更不改变已捕获 chat；busy 时零 USER append。
3. ViewModel/UI：defaults/load/activate；Application/AI 正常、边界和非法校验（8/20、URL、NaN/Infinity、0/负数/溢出 token、context <= output、Unicode/emoji/换行）；operation guard；save 成败；secret never hydrated/toString 安全/masked semantics/remove confirm；model loading/success/empty/error；AI focus；dirty Drawer/Back；主题/字号预览；360/599/600/840、zh/en、8/20、200%、API26/36、≥48dp。
4. 回归：C1a 全部 Flow/Thinking、C1b coordinator/gateway/composer/QuickAi、无配置/timeout/non-200/malformed/dangerous operation、Activity recreate/background cancel、Event CRUD/calendar/Drawer/dirty Back、SavedStateHandle 四 key、active局部 edit、SecretStore/AES-GCM invalidation、OkHttp models/chat、dependency/generated/boundary。
5. 第一阶段三 Agent 只能新增测试；红灯必须由缺失 C2a contract/行为产生，不能破坏依赖、工具链，不能访问真实网络，不能用假断言。主 Agent 审查红灯后才发送 follow-up 生产实现。

### 风险与 STOP 条件

主要风险为 DataStore section 更新丢并发字段、AI envelope/非秘密字段部分提交、Key 残留、chat 已追加 USER 才发现 busy、主题观察触发冷启秘密/Room side effect、dirty Back 破坏 B2/C1a/QuickAi，以及大字体下操作不可达。

若 section-scoped 或 AI+secret 单 edit 原子语义不可实现；必须写明文 Key、回退旧磁盘模型配置、无法在 USER append 前互斥；需要 WebDAV/sync/C2b/C3/T47/T48、依赖/Gradle/lock/Manifest/DAO/schema/migration；冷启必须创建 Key/AiClient/Room/对话/网络；UI 必须直连 DataStore/SecretStore/OkHttp；需要真实 Provider/Key/用户数据；既有 C1a/C1b/B2 退化；85 universe/冻结依赖/native/Google/APK、Android/lint/Windows/PyInstaller/exe/`dist/data` 任一门禁失败，立即 STOP-ESCALATE。失败时完整撤回 C2a 试验代码，只保留任务记录与证据，不提交半成品。

### 实施步骤、回滚与完成定义

- [x] 恢复 branch/HEAD/clean/staged，读取规则、架构、T45/T46/progress 与相关实现/测试，冻结本节和 progress 启动状态。
- [x] 修改前重跑 654 Android JVM、366 UI 与 C1a/C1b 聚焦基线；旧轮次 STOP 证据保留在下方，本轮 restart 基线已全绿。
- [x] 三 Agent 第一阶段只写互不重叠失败测试并取得有效红灯；主 Agent 审查后发 follow-up。
- [x] Agent A/B/C 分别完成 data/app/ui 边界实现；主 Agent完成 container/theme/app/resources/dirty navigation 跨层集成。
- [x] 聚焦测试与完整 Android strict/offline、generated/boundary、依赖/Manifest/schema 零漂移审计。
- [x] Windows 171、导入/check、完整 PyInstaller、双向 exe 冒烟与 `dist/data` 前后只读摘要。
- [x] 更新本文件/progress/根 AGENTS，完成 diff/秘密/生成物/staged 审查，设置代理，提交指定信息且不 push。

回滚为删除 C2a 新增 app/ui settings 与测试，精确还原 data/settings、app/ai gate、AppContainer/Application/Activity/ClenderApp/effects/strings/docs；不得触碰 Room v1、DAO、Manifest、依赖、WebDAV envelope 或用户数据。完成定义为上述冻结行为和测试矩阵全绿、production appearance 实时接线、单 edit section/secret 原子性与 USER append 前双向 gate 有证据、真实 Provider 未访问、完整发布门禁通过并创建聚焦提交；T46 仍进行中，C2b/C3/T47/T48 未开始。

### STOP-ESCALATE 证据（修改前基线）

- 第一次 `--rerun-tasks testDebugUnitTest` 真实执行 654 tests，`CalendarAppIntegrationTest.productionActivityConnectsQueryNavigationAndReadOnlyEvents[26]` 在 `awaitCalendarLoad()` 的 30 秒等待处失败；结果 653 pass / 1 failure。
- 该类以正确 FQCN 单独运行后 API 26/36 均通过，证明不是缺依赖、工具链或伪红灯；随后第二次完整 `--rerun-tasks testDebugUnitTest` 再次在同一 API 26 用例失败，仍为 653/654，并输出多个 Room/SQLite `CloseGuard` 未关闭警告。
- 失败发生在任何 C2a 测试或生产代码之前；现场只有本任务文件和 progress 文档变更。按“先验证既有 654 JVM 绿色”和 Android 门禁失败 STOP 条件，未启动 Agent A/B/C，未写 C2a 测试、生产、资源、Gradle 或 Git 提交，也未访问真实 Provider/Key/用户数据。
- 当前阻塞需要用户裁决：先以独立任务修复/稳定既有 `CalendarAppIntegrationTest` 资源生命周期，或明确提供其他基线处理方式；不得把该既有失败冒充 C2a 测试红灯后继续。

### T46-Baseline 生命周期 blocker 处理结果（2026-08-31）

- 独立任务已修复测试资源所有权：纯组件 Compose suite 不再启动生产 `MainActivity`，生产集成 suite 统一确定性销毁 scenario、drain main looper、关闭 `AppContainer` 并清除 sandbox；额外 `ActivityController` 和直接 container 也保证异常路径关闭。生产代码、Manifest、Gradle、依赖、Room/schema、超时和断言均未改变。
- 高风险组合连续两轮、完整 Android JVM 连续三轮均通过；三轮各为 76 suites / 654 tests、0 failure/error/skip，最终 SQLite/Room CloseGuard 报告扫描为 0。`ui.*` 366/366、C1a/C1b 聚焦、strict/offline 96 tasks、generated/boundary 44/44、Windows 171、PyInstaller/exe/`dist/data` 全部通过。
- 本 blocker 已解除，状态恢复为“C2a 可在新的独立任务中重新启动”。上方 C2a 设计、未完成清单和原 STOP 证据全部保留；本次没有实施任何 C2a 测试或生产功能。

### T46-C2a restart 现场（2026-08-31）

- 新一轮恢复现场精确确认为 branch `codex/android-architecture`、HEAD `9d439d4aaa6336beece8a8c2195f737591fe5ff1`、提交 `Stabilize Android UI test resource lifecycle`；working tree clean、staged 为空，该分支无远端跟踪项，未 stash/clean/reset/checkout、未 push。
- lifecycle blocker 已由 `9d439d4` 解除；原 STOP-ESCALATE 历史、失败证据和 blocker 处理记录完整保留，不改写为 C2a 红灯。当前状态为 **C2a 已重新启动**，C2b/C3/T47/T48 仍未开始。
- 在任何新 C2a 测试或生产修改前执行 `gradle.ps1 --offline --no-daemon --dependency-verification strict --max-workers 1 --rerun-tasks testDebugUnitTest`，结果 `BUILD SUCCESSFUL in 4m 10s`：76 suites / 654 tests、`ui.*` 37 suites / 366 tests、C1a/C1b 聚焦 12 suites / 168 tests，全部 0 failure/error/skip；四类 SQLite/Room CloseGuard XML/HTML 扫描均为 0。
- 本轮 Agent 边界继续冻结：Agent A 只负责 `data/settings/**` 测试，红灯获主 Agent 审查后才可实现同包；Agent B 只负责 `app/settings/**` 与必要 `app/ai` gate 测试，获批后只实现这些包；Agent C 只负责 `ui/settings/**` 测试并统一复用 test-only `RobolectricComposeHost`，获批后只实现该包。主 Agent 独占 AppContainer/Application/MainActivity/ClenderApp/effects、跨层集成、strings、文档、Git 与发布验证。
- 第一阶段测试矩阵保持本节既有五组并细化为：A 覆盖 section partial update、active 并发、KEEP/REPLACE/REMOVE 单 edit、envelope/CharArray/失败原子性；B 覆盖有限 decision、save-before-request、刚保存配置、chat↔fetch gate、异步 lease/cancel/close 与单 client；C 覆盖 activation/校验/secret 不回填、模型状态、AI section、dirty Drawer/Back、主题字号及尺寸/语言/API/语义矩阵。有效红灯只能来自缺失 C2a contract。
- STOP 条件不变：任何基线/CloseGuard 回归、section/secret 无法单 edit、USER append 前无法取得共享 gate、模型必须回退旧配置、appearance 必须初始化秘密/Keystore/AiClient/network、需要越界到 WebDAV/C2b/C3/T47/T48 或依赖/Gradle/Manifest/DAO/schema，以及后续 Android/lint/Windows/PyInstaller/exe/`dist/data` 任一门禁失败，立即撤回 C2a 试验代码，仅保留任务与证据。

### T46-C2a 实施结果（2026-09-01）

- 三 Agent 严格分阶段执行：A/B/C 首轮只新增 data/app/ui settings 测试，主 Agent确认编译红灯只来自缺失 C2a contract 后才授权同包实现；合并后的 C2a 聚焦 6 suites/90 tests 全绿。新增共享 preference/envelope codec、section-scoped appearance/AI update、AI envelope KEEP/REPLACE/REMOVE 单 edit、`AiOperationGate`、model save-before-request service、Settings ViewModel/Compose UI 与生产装配。
- appearance Flow 只初始化非秘密 DataStore；Settings 首显仅加载非秘密 state 与完整 envelope presence，不解密、不创建 Keystore alias/AiClient、不联网。chat/model fetch 复用同一 app-scoped gate 与同一真实 `OkHttpAiClient`；busy 在 Key read/USER append/network 前返回，模型只消费刚保存 snapshot/envelope，全路径释放 lease 并擦除 `CharArray`。
- Settings placeholder 已替换为 Application/AI；System/Light/Dark、应用/Widget 8–20sp 与 AI 表单真实持久化，保存 appearance 后生产 `ClenderTheme` 立即更新，未保存 draft 不影响全局主题。Drawer 默认 Application，主 AI/QuickAi 直达 AI；secret remove/其他 dialog/Drawer/dirty discard/既有 Back 优先级与离页 model cancel 已接线，中英文资源与 API 26/36 生产 Activity 集成回归通过。
- 完整 Android 强制 rerun 为 83 suites/752 tests（相对基线新增 98），0 failure/error/skip；`ui.*` 41 suites/428 tests，相关 AI/对话聚焦 17 suites/198 tests，四类 SQLite/Room CloseGuard 扫描均为 0。`lintDebug`/`lintRelease` 真实 rerun，detekt、ktlint、签名、无 Google/native、lock、assembleDebug/APK no-native 全绿；generated/boundary 各 44/44。
- 85 configuration universe、五个冻结 SHA-256、Runtime 1.11.3、Lifecycle 2.9.4、SavedState 1.3.2、Annotation Experimental 1.4.1、UI 1.6.8 零漂移；Manifest、Room schema/entity/DAO/migration 无 diff。未访问真实 Provider/Key/用户数据，未实现 WebDAV/C2b/C3/T47/T48。
- Windows offscreen 171/171、全模块导入与 `build.py --check` 通过；最终完整 PyInstaller 生成 45,579,775-byte exe（SHA-256 `B4C902E5C7D86A146450D1DF3D5A85F7D9F1281FAC8E712E239A48E33F73E316`）。normal primary + silent secondary、silent primary + normal secondary 均 secondary exit 0、primary 存活并创建隔离 DB，最终 0 测试进程/0 隔离目录。
- `dist/data` 前后均为 5 文件、195,620 bytes，相对路径/大小/UTC/逐文件 SHA-256 聚合 `E43733909427A268005A9514FB136CDA0F74C43395046269E73EF20E539668D9` 完全一致；只读取 metadata 与哈希。T46 当前 P0/A/B1/B2/B/C1a/C1b/C2a ✅，C2b/C3/T47/T48 未开始。

## T46-C2b：WebDAV 设置、连接测试、生产同步装配与有限状态 UI

- 2026-09-01 本阶段已完成，完整契约与实施结果见独立任务文件 `doc/tasks/T46-android-webdav-settings-sync.md`。
- 范围：Settings 新增第三个 WebDAV section（Application/AI/WebDAV）；持久化 enabled/HTTPS URL/username；`WEB_DAV_PASSWORD` envelope 复用 Keystore/AES-GCM 并支持 KEEP/REPLACE/REMOVE 单 edit 原子提交；Test Connection 只发送 `PROPFIND Depth: 0`（200/207）且零保存；Sync Now 先原子保存再用绑定 snapshot 请求 `SyncCoordinator` MANUAL；local-change（mutation signal，忽略初始 0）/manual/foreground 三路 trigger；内存 `StateFlow<SyncState>` 有限状态与有限错误码；remote apply 单事务不递增 mutation、不产生上传回路；`AppContainer.close()` 先取消 probe、shutdown 并等待 sync worker、解绑 lifecycle、擦除 session/password 再关 AI/DataStore/Room。
- 非目标：C3、About、Widget、QuickAiActivity、T47/T48、周期任务/通知/权限、OAuth/Digest/cleartext、Room schema/DAO/migration、Manifest/Gradle/lock/依赖变更；T44 codec/LWW/墓碑/ETag/412 语义与 `SyncCoordinator.request(trigger)` 行为不变（只新增 `requestDiagnosed` 有限结果接口）。
- Agent 边界：A=仅 `data/settings/**`；B=仅 `app/sync/**`、必要 `sync/**`、`app/settings/**`；C=仅 `ui/settings/**`（纯 Compose 复用 `RobolectricComposeHost`）；主 Agent 独占 AppContainer/MainActivity/ClenderApp/ProductionSettingsPort/strings/文档/Git 与发布验证。
- 完成证据：Android strict/offline 全门禁 BUILD SUCCESSFUL（93 suites/909 tests、CloseGuard 0、lint 真实 rerun）；generated/boundary 各 44/44；85 universe/五冻结文件零漂移；Manifest/Room/Gradle 零变化；Windows 171/171、导入/check、PyInstaller 45,580,456-byte exe（SHA-256 `459C61BF…F308B`）、双向隔离冒烟通过；`dist/data` 5 文件/195,620 bytes/摘要 `E4373390…668D9` 不变。T46 当前 P0/A/B1/B2/B/C1a/C1b/C2a/C2b/C2 全部完成，C3/T47/T48 未开始。

## 实施步骤

- [ ] 第一轮只写 navigation/state/form/calendar/AI/settings UI 失败测试与 semantics 契约。
- [ ] 实现主题、typography、string resources 和顶层 Drawer/navigation。
- [ ] 实现 Month/Week/Day、Canvas/semantic overlay、聚合选择和日期联动。
- [ ] 实现事项列表、详情、跨日表单、类型转换、手工删除确认。
- [ ] 实现多对话/Thinking/输入/busy/token UI 和设置/模型/同步状态。
- [ ] 实现 phone/tablet adaptive layout、SavedState、TalkBack、200% 字体。
- [ ] 聚焦 UI、截图/语义与无网络 mock 验证。

## 测试与检查

```powershell
.\android\scripts\gradle.ps1 testDebugUnitTest --tests "*.ui.*"
.\android\scripts\gradle.ps1 connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.molotov.clender.ui.AllUiTests
```

覆盖 Drawer 五目的地/返回、状态恢复、月周日、lane/marker/overflow 点击、CRUD/跨日/空态/错误态、对话管理/Thinking、设置校验、Light/Dark/System、8/20sp、字体 200%、中英、phone/tablet、横竖/分屏和可访问语义。

## 回滚方式

还原 MainActivity 到 T42 占位并删除 ui packages/resources；领域/数据保持独立。

## 完成定义

- [x] 三主视图与设置/关于可达且状态恢复。
- [x] UI 写入均走 use case/service，无 DAO/HTTP 直连。
- [x] UI/语义/主题/字号/尺寸矩阵通过。
- [x] 按根门禁完成 Windows 全量/构建/exe/`dist/data` 核验、更新文档并创建 T46 聚焦提交。

## T46-C3：真实 About 页、全应用自适应/无障碍与集成收口（2026-09-01）

本节是 T46 的最终独立收口，不扩展 T47 Widget、QuickAiActivity 或 T48 设备/发布矩阵。完整目标、边界、测试矩阵、红灯与回滚证据见 `doc/tasks/T46-android-adaptive-accessibility-closeout.md`。

### 实施与契约结果

- `AppDestination.ABOUT` 已从 placeholder 接入真实只读 About；通过 PackageManager 读取 application label、versionName、longVersionCode，API 26/P 兼容，空值/异常只显示有限本地化“不可用”。页面说明 Android sandbox、WebDAV 仅同步日程且不同步对话/设置、AI 仅在用户明确提交且完成配置后联网；不显示 applicationId、绝对路径、endpoint、身份、秘密、正文，不创建外部 Intent/WebView/网络。
- 全应用冻结 `<600/600..839/>=840dp` 三断点，compact 单栏、medium/expanded AI 双栏，所有宽度仍使用 ModalNavigationDrawer。日历、事项、AI conversation/message、Settings/About 使用稳定 page key 隔离的 `rememberSaveable` 滚动位置；Activity recreate/旋转/窗口尺寸变化只恢复整数位置，四个既定 AppShell SavedState key 和既有 ViewModel/store 契约不变。
- Drawer、TopAppBar、日历 mode/range/timeline、事项 CRUD/picker/dialog、AI composer/conversation/Thinking、Settings/WebDAV 和 About 均补齐稳定 tag、本地化 label、heading/paneTitle、role/state、polite liveRegion/有限 stateDescription 与 48dp 目标。THINK/WebDAV enabled 各暴露单一 toggle 语义，折叠 THINK 正文不进入语义树；颜色之外保留边框/纹理/语义区分。Timeline Canvas 与 semantic overlay 共用坐标，RTL、横向滚动、窄 lane 的点击和朗读几何一致。
- UI 写入仍只通过既有 EventService、SettingsPort、AI/WebDAV gateway/use case；未改 domain/data/Room/DAO/schema/Manifest/Gradle/锁定依赖或协议。

### 测试先行与最终验证

- Newton/Agent A、Zeno/Agent B、Hooke/Agent C 分别负责 About、calendar/event、AI/settings；首轮仅新增互不重叠测试，真实编译红灯仅来自缺失 About/API，主 Agent 审查后才实现同包。三 Agent 合计 34 个源码测试方法，API 26/36 展开 70 个 instances；C3 新增 6 suites/70 tests。
- Android 最终 strict/offline 全命令通过：99 suites/979 tests、0 failure/error/skip；`ui.*` 50 suites/567 tests；lintDebug/lintRelease、detekt、ktlint、签名、无 Google/native、锁、assembleDebug/APK 审计通过，CloseGuard 四类为 0；generated/boundary 各 44/44，85 configuration universe 与五冻结依赖 SHA-256 零漂移。
- Windows offscreen 171/171、30 模块导入、build check、完整 PyInstaller 与 normal/silent 双向 primary-secondary 隔离冒烟通过；最终 exe 45,583,001 bytes，SHA-256 `694269E4A38D4D788D3F961CA6C1056AE0BD3E0E72F6B680255E63C8808BCE3F`。`dist/data` 前后 5 files/195,620 bytes/聚合摘要 `E43733909427A268005A9514FB136CDA0F74C43395046269E73EF20E539668D9` 一致。
- T46-P0/A/B1/B2/C1a/C1b/C2a/C2b/C3 全部完成；T47 是下一未阻塞任务，T48 仍未开始。本阶段不运行真实 Provider/WebDAV，不读取真实 Key、密码、事件、对话或运行数据正文。
