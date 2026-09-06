# T47：平台 App Widget 今日组件与快捷入口

## 2026-09-06 当前实施记录（实现与验收完成）

本轮接续主任务为 `doc/tasks/T47-T48-android-completion.md`。下文 P0–P2B2a、Baseline 及 STOP/未开始描述保留各自历史阶段含义；本记录补充当前生产接线，不抹除历史证据。T47/T48 实现、构建与验收已完成，最终证据与增量复用边界见主任务末节；Git交付回执见最终答复。

- **QuickAI/P2B2b：** LARGE RemoteViews 增加显式 immutable QuickAI 入口，非导出 QuickAiActivity 验证完整 Intent/平台实例归属后才访问 container，复用活动会话与既有 AI gateway；打开零自动提交，正文不进入 Intent/SavedState。创建 flags 仍为 `0x24000000`；QuickAI/EditEvent 入站可接受 NEW_TASK，且仅伴随 NEW_TASK 时可接受 BROUGHT_TO_FRONT，QuickAI 独立可选 EXCLUDE_FROM_RECENTS。内部导航与 LocalRefresh 严格不扩展，其他 envelope/owner 校验保持。API26/36 实际 JDB 交付及同对象对照证据、RED→GREEN 见主任务；最终签名包已证明真实 Launcher QuickAI 正常打开。
- **真实新建 UI：** 日历/事项顶层 TopAppBar 的 `clender_create_event` 提供本地化、至少 48dp 点击入口；按当前 selectedDate 打开既有 NewEvent 表单，沿用 EventService 保存、dirty 取消确认与草稿恢复。其他目的地/子页面不显示；测试覆盖按钮进入、保存、取消和 recreate。
- **P2C 本地触发：** WidgetAutomaticRefreshRuntime 在既有 Widget scope 内复用同一 store/coordinator，汇合 local mutation、remote visible、configuration、foreground、manual refresh、date boundary、boot。配置/手动仅允许正 target ID；实例以 AppWidgetManager 归属为准；无实例不打开 Room/网络。删除先失效 generation，最后实例删除取消日期安排；close 在 DataStore/Room 前取消并等待 Widget scope。
- **远端可见变化：** WebDavSyncSignals 默认空 callback 传入既有 SyncCoordinator；成功 localChanged 或异常/取消 remoteVisibleChanged 为 true 才通知 Widget。runtime 对 SyncCancellationException 先设置 CANCELLED 再重抛，保留已 apply 的通知；远端单事务 apply 不经过 EventService、不递增本地 mutation。
- **日期与 Boot：** WorkManager 2.11.2 只运行唯一 `clender-widget-date-boundary` one-time REPLACE 并等待提交；Worker 只处理空 inputData 的本地刷新和后继安排，不 retry、不联网。非导出 Boot receiver 允许 BOOT_COMPLETED 的空 extras 或唯一非负 Int `android.intent.extra.user_handle`，拒绝其他 extras/data/type/selector/clip/categories；9 秒上限、finish-once。不引入 periodic/expedited/foreground work、精确 alarm 或通知。
- **验证范围：** 最终JVM155 suites/1611 tests、UI57/665，失败/错误/跳过和完整XML/日志四类泄漏标记全部0；完整verify-all的96tasks/5m39s、发布工具111项、两组49项策略与签名APK/AAB审计均通过。Drawer已补目标状态同步并移除重复关闭动画；LARGE Refresh已补既有主题前景色，保持点击/布局/字体/调度不变。各自RED与历史格式失败保留在专用任务。Windows198/198、完整构建及20场景通过。最低三类设备最终包核心与实际Widget通过，扩展五组按增量策略通过；结果以主任务和T48最终证据为准。

本次只同步本任务及 Android 高层设计、详细设计、控制提示四份文档；源码、锁、AGENTS、progress 与其他任务由主 Agent 维护。文档交付后冻结，后续状态变化由主 Agent 统一收口。

## 目标

用 Android 平台 `AppWidgetProvider + RemoteViews + AppWidgetProviderInfo XML` 替代桌面悬浮窗，实现多实例、响应式今日事项/current/next、配置、编辑跳转、快速 AI Activity 和安全刷新。主应用继续使用 Compose；Launcher Widget 不使用 Glance。

## 非目标

- 不在 Widget 直接输入、不精确分钟刷新、不周期 WebDAV、不置顶/置底/双击。

## 输入文档

- Android detailed design 第 8 节；T43、T46

## 影响文件

- `android/app/src/main/.../widget/`
- AppWidgetProvider/config/boot manifest、RemoteViews/provider-info resources
- QuickAiActivity/UI
- 对应平台 Widget/Robolectric/instrumentation 测试

## 接口/数据影响

- 每 appWidgetId 的时段、opacity、font/theme 配置；API 26–30 size bucket 与 API 31+ responsive/exact RemoteViews mapping；显式 edit/quick AI Intent；WidgetUpdateCoordinator。

## 风险

- Launcher 尺寸/OEM差异；PendingIntent 串实例/劫持；午夜/进程杀死陈旧；透明度或大字体不可读。

## 实施步骤

- [ ] 第一轮只写 state/config/action/receiver/刷新失败测试。
- [ ] 实现 API 26–30 size bucket、API 31+ responsive/exact RemoteViews 小/中/大布局与本地 overlap query。
- [ ] 实现 current/next、截断/剩余计数、主题/opacity/字号和多实例配置。
- [ ] 实现显式事项编辑与 Quick AI Activity，复用最近活动 conversation。
- [ ] 实现数据/同步/配置/foreground/日期边界更新与 boot 恢复，不启动网络。
- [ ] 审计 receiver/exported/PendingIntent/锁屏隐私并完成矩阵验证。

## 测试与检查

```powershell
.\android\scripts\gradle.ps1 testDebugUnitTest --tests "*.widget.*"
.\android\scripts\gradle.ps1 connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.molotov.clender.widget.AllWidgetTests
```

覆盖添加/删除/多实例、小中大、空态、current/next/并列/午夜、跨日、0/100 opacity 以及从 0% 在应用设置恢复可见、8/20sp、Light/Dark、事项/AI Intent、进程杀死、boot、Doze、配置坏值、receiver/exported/PendingIntent/权限安全。

## 回滚方式

删除 Widget/Quick Activity 及 manifest entries；主应用继续可用。

## 完成定义

- [ ] 三尺寸多实例与安全 action 通过。
- [ ] 所有刷新均为本地/尽力，不引入精确闹钟或周期网络。
- [ ] Widget 失败不影响主应用启动。
- [ ] 按根门禁完成 Windows 全量/构建/exe/`dist/data` 核验、更新文档并创建 T47 聚焦提交。

## T47-P2B1：配置 Activity 与 Configure action（进行中）

- 独立任务：`doc/tasks/T47-android-widget-configuration-ui.md`。
- 仅实现非秘密 Widget 配置草稿/状态机、Compose 配置 Activity、Launcher configure/reconfigure、三尺寸唯一 Configure PendingIntent 和保存后目标本地重建。
- 配置 Activity 必须 `exported=true` 供 Launcher 跨包调用；入口在任何 container 访问前校验精确 action/data/正 ID 与 `AppWidgetManager` provider ownership，不信任 caller/referrer/sender。
- provider-info 只增加 `configure` 与 API 31+ `reconfigurable|configuration_optional`；PendingIntent 只允许 Configure，显式、immutable、实例 identity 唯一。
- P2B2 的 EditEvent/QuickAi/LocalRefresh、P2C 自动触发/Worker/Boot、T47 整体与 T48 均保持未开始/未完成。

## T47-P0（历史拒绝路径）：Glance/WorkManager 依赖、Manifest 与纯契约 checkpoint

### 目标

- 只验证并锁定 Glance `1.1.1` 与 WorkManager `2.11.2` 五个获准直接依赖，生成真实 resolved graph、lock、verification metadata 和 debug/release merged-manifest allowlist。
- 实现不依赖 Glance UI、Receiver、Activity、Worker、Context、Room、网络或系统时钟的 Widget 配置、状态、展示、action 与刷新策略纯契约。
- 复用现有 Preferences DataStore 文件，实现按 `appWidgetId` 隔离的配置读写；本轮不接入 `AppContainer`。

### 非目标与禁止扩展

- 不实现 `GlanceAppWidget`/布局、receiver/provider XML、配置 Activity、QuickAiActivity、BootReceiver、Worker、WorkManager enqueue 或 `MainActivity` action 接线。
- 不修改 Room/DAO/schema/migration、AppContainer、AI/WebDAV 协议，不运行 connected/emulator 测试，不开始 T47-A/B/C 或 T48。
- 不引入 `glance-material3`、`work-multiprocess`、`work-gcm`、RxJava、Navigation Compose、通知、精确闹钟、前台工作或网络刷新。
- 源 `AndroidManifest.xml` 原则上不变；只审计依赖生成的 merged manifest，禁止由本轮新增自有组件/权限。

### 影响文件与 Agent 边界

- 主 Agent 独占 version catalog、Gradle build、lock、verification metadata、共享 dependency policy、Manifest 最终审计、文档、AGENTS、staging 和 commit。
- Agent A 仅负责依赖图/configuration universe/merged manifest/Google/native/动态版本测试；不修改共享构建文件。
- Agent B 仅负责 `WidgetConfiguration` 与 `DataStoreWidgetConfigurationStore` tests-first，解析通过后才可实现同包生产代码。
- Agent C 仅负责 `WidgetStateBuilder`、`WidgetPresentationPolicy`、`WidgetActionSpec`、`WidgetRefreshPolicy` tests-first，解析通过后才可实现同包生产代码。
- 第一阶段测试必须互不重叠且只能写失败测试；主 Agent 审查红灯只接受缺失获准依赖或 P0 符号/行为失败。

### 接口与数据契约

- `WidgetConfiguration`：正 `appWidgetId`；同日、分钟精度且 `start < end`；默认 `08:00–22:00`、opacity `100`、theme `SYSTEM`、字号取当前合法 widget 字号，否则 `13sp`；字号 `8..20sp`、opacity `0..100`。
- `DataStoreWidgetConfigurationStore`：复用现有 DataStore，使用稳定 namespaced keys，提供 `get/observeAll/upsert/delete`；单实例变更各为一次 `DataStore.edit`，坏实例值不得污染其他实例或既有 appearance/navigation/conversation/AI/WebDAV/secret keys。
- `WidgetStateBuilder`：仅消费事件、配置、日期及注入的 `LocalDateTime/ZoneId`；按配置日 `[start,end)` 筛选，过滤墓碑/非法事件/重复 ID，复用 `EventStateClassifier` 的全部 current 与最早 future 的全部并列 next，稳定按 start/end/id 排序，展示模型不含 description 或同步/创建/更新/删除 metadata。
- `WidgetPresentationPolicy`：SMALL/MEDIUM/LARGE 容量 `2/4/8`，按 current → next → future 截断，不重复，返回精确非负 `remainingCount`，保留被截断项状态，opacity `0` 仍生成安全模型。
- `WidgetActionSpec`：只定义 explicit action/identity/validation，不创建 PendingIntent；支持 EditEvent/QuickAi/LocalRefresh/Configure，正 widget ID，EditEvent 事件 ID 为 `1..Int.MAX_VALUE`，canonical identity 区分 widget/action/event，后续 flags 固定 `FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT`。
- `WidgetRefreshPolicy`：只定义 LOCAL_MUTATION、REMOTE_VISIBLE_CHANGE、CONFIGURATION_CHANGE、FOREGROUND、MANUAL_LOCAL_REFRESH、DATE_BOUNDARY、BOOT 的本地 Widget update 触发语义；下一本地日期使用注入 Clock/ZoneId 计算真实正延迟，后续采用单个 app-wide unique one-time work，非 periodic/exact/expedited；本轮不 enqueue。

### 修改前基线与测试矩阵

| 层级 | 正常 | 边界 | 非法/异常 | 回归/安全 |
|---|---|---|---|---|
| Android 基线 | `testDebugUnitTest` 99 suites/979 tests | API 26/36 JVM | 0 failure/error/skip | CloseGuard 四类 0；generated/boundary 44/44 |
| 依赖/配置宇宙 | 五个 producer 的真实 resolved graph | 85 configurations（84 active + 1 stale lock-only `androidApis`） | 动态/SNAPSHOT/未校验 artifact 拒绝 | 既有 76 项配置零漂移，新增坐标逐项 producer attribution |
| Artifact/Manifest | debug/release merged manifest 可解析 | 仅 WorkManager 2.11.2 必需注入项 | Google/native/未知二进制、额外权限/组件拒绝 | allowlist 精确匹配，无 BROWSABLE/data、provider/runner/debug CA |
| 配置/存储 | 默认、边界值、双实例 get/observe/upsert | ID 重用、删除后默认、单 edit | 坏 ID/时间/秒纳秒/越界、IOException 空回退、其他异常传播 | 保留 unrelated keys、secret 不读不写不生成 |
| 状态/展示 | reminder/timespan、排序、空态、current/next | 半开端点、跨日、零时长、并列 current/next、超大 duration | 墓碑/非法/重复 ID、全部非法、溢出 | 敏感字段缺席、被截断状态保留、remaining 精确 |
| Action | 四类 explicit target/canonical identity | `Int.MAX_VALUE`、0 opacity | 空白/HTTP(S)/坏数字/负数/0/Long 溢出/未知 action | widget/event/action 不串实例；无写事件、AI、同步、网络语义 |
| Refresh | 七类 trigger 映射本地 update | 午夜前后、DST gap/overlap、API 26/36 | 非法时区/负延迟拒绝 | 不 periodic/exact/expedited，不触发 AI/WebDAV |
| 根门禁 | Windows unittest 171/171、30 模块导入 | build check、PyInstaller、双向单实例 | 构建/启动失败必须停止 | dist/data 只读摘要前后一致，禁止敏感/生成物 |

### 依赖与 Manifest STOP 条件

- 只允许五个直接依赖：`glance-appwidget:1.1.1`、`glance-testing:1.1.1`、`glance-appwidget-testing:1.1.1`、`work-runtime-ktx:2.11.2`、`work-testing:2.11.2`；禁止 force/strictly/resolutionStrategy/substitution/new exclusion。
- Compose Runtime `1.11.3`、Lifecycle `2.9.4`、SavedState `1.3.2`、BOM/UI/graphics/foundation/material、Material3、Activity/Core 与既有 Annotation Experimental F 契约必须保持。
- 任何既有版本漂移、configuration universe 改变、Google/native/dynamic/SNAPSHOT/未校验 artifact、AAR/JAR/APK 中 `jni/`/`lib/`/`*.so`、或 merged manifest 产生非精确 WorkManager 必需项，均完整回退依赖、lock、metadata 和相关测试并 STOP；不得升级 AGP/Compose 或排除依赖规避。

### 实施步骤、回滚与 STOP

1. 记录现场、运行 strict/offline 单 worker `--rerun-tasks` 基线，并完成五个 Agent 的 tests-only 红灯。
2. 主 Agent 以官方 lock/verification writer 添加五个直接依赖，逐项审计 85 configuration before/after、dependencyInsight、artifact native/Google 和 debug/release manifest。
3. 解析通过后分别实现 B/C 对应纯契约，运行聚焦测试和全量 Android 门禁。
4. 执行根 Windows 全量/导入/build/PyInstaller/exe 双向隔离冒烟与 `dist/data` 只读摘要；更新任务、progress、AGENTS，审查 diff/secret，创建单一 checkpoint commit，不 push。

回滚仅允许删除本轮新增 Widget 源码/测试以及五个依赖声明，并用官方工具恢复 lock/verification metadata；不得 stash、clean、reset、checkout、覆盖用户数据或生成物。完成 checkpoint 后立即 STOP；任一 prompt 明定 STOP 条件触发后只保留证据和文档，不扩展范围。

### 完成定义

- [ ] 五个依赖真实解析、严格锁定、verification metadata/85 configuration/producer attribution 通过。
- [ ] debug/release merged manifest 精确 allowlist、无 Google/native/未知 artifact、无额外自有组件/权限。
- [ ] Widget 配置/单 DataStore、多实例隔离、状态/展示/action/refresh 纯契约及聚焦测试通过。
- [ ] Android 既有 99 suites/979 tests、CloseGuard 0、generated/boundary 44/44 与全部指定静态门禁通过。
- [ ] Windows 171/171、导入/build/PyInstaller/exe 双向冒烟通过，`dist/data` 摘要不变，文档更新且 checkpoint 已提交。
- [ ] T47 整体仍进行中；Glance UI、Receiver、配置 Activity、QuickAiActivity、Worker/Boot 接线和 T48 均未开始。

### T47-P0 STOP 记录（2026-09-01）

- 修改前 Android 基线通过：`testDebugUnitTest` 为 99 suites/979 tests、0 failure/error/skip；既有 Python policy 为 44/44；现场六个冻结 SHA-256 全部匹配。
- tests-only 红灯已取得：B 新增 15 个配置/DataStore 用例，C 新增 4 组纯契约用例；失败仅为缺少 P0 类型/接口。A 子 Agent 因等待无输出关闭，主 Agent 接管其限定的 10 项依赖/Manifest 策略红灯，失败仅对应缺少候选依赖/WorkManager merged entries。
- 依赖试验使用官方 `:app:dependencies --write-locks` writer；strict/offline 首次因候选未在隔离缓存而未写锁，联网解析后成功写入临时锁。真实 graph 显示既有 `androidx.tracing` 从 runtime `1.0.0`/test `1.1.0` 漂移为 `1.2.0`，并新增 `tracing-ktx`；同时出现既有配置集合扩展及 DataStore Android 变体。
- 该既有坐标/配置漂移命中 prompt 的 STOP 条件。已完整回退 catalog、app/root Gradle、app lock 和所有 T47-P0 测试；verification metadata、settings lock、Manifest、生产 Kotlin、Room/DAO/schema 未改。未实现 Widget 生产代码、未接 AppContainer、未运行 connected 测试。
- 本任务停留在依赖兼容性决策，不尝试追加 allowlist、force/strictly/resolutionStrategy/exclusion、升级 Compose/AGP 或用其他依赖替代。待后续明确依赖策略后，T47-P0 才能重新开始。
- 回退后重新生成当前源 Manifest 成功，既有 policy 仍为 44/44；strict/offline 完整 JVM 重跑编译并完成 979 tests，但一个既有 `OkHttpAiClientTest.thinking400Or422FallsBackExactlyOnceWithoutExtensions` 因 `Another AI request is already active` 竞态失败。未重试掩盖该失败，也不改变本轮依赖漂移 STOP 结论。

### T47-Baseline 独立 blocker 收口（2026-09-01）

- 回退依赖试验后发现的 `OkHttpAiClient` Thinking 400/422 fallback active-request 竞态已作为独立 Baseline checkpoint 修复；terminal callback 现在先关闭 response、按当前 Call 做 expected-value CAS release，再恢复 continuation。
- 旧实现的确定性红灯已记录在 `doc/tasks/T47-android-ai-client-request-lifecycle.md`；修复后 client 16/16、AI 聚焦 9 suites/70 tests、完整 JVM 连续三轮 99 suites/980 tests 与完整 Android 门禁均通过。
- 该修复不改变 T47-P0 依赖 STOP 决策：`tracing 1.2.0`、`tracing-ktx`、DataStore Android variant 漂移仍未获授权；未实现 Glance/WorkManager/Widget、T47-A/B/C 或 T48。T47 整体仍进行中，Baseline 修复提交后立即 STOP，未 push。

## T47-P0R：平台 Widget 架构与 WorkManager dependency checkpoint

### 状态与目标

**进行中（2026-09-01）**。本 checkpoint 只完成架构裁决、WorkManager `2.11.2` 依赖 producer graph、debug/release merged manifest 精确 allowlist、native/Google/APK/lock/verification 闭环和独立提交。通过并提交后立即 STOP。

目标：

- 冻结 `Compose 主应用 + 平台 AppWidgetProvider/RemoteViews Widget` 架构；API 26–30 使用 `OPTION_APPWIDGET_*` size bucket，API 31+ 使用 responsive/exact `RemoteViews` size mapping。
- 只引入 `implementation androidx.work:work-runtime-ktx:2.11.2` 与 `testImplementation androidx.work:work-testing:2.11.2`，不得直接声明任何传递依赖。
- 日期边界后续只使用 one-time work；不使用 periodic、expedited、long-running、foreground work，不使用精确 AlarmManager、通知或后台网络刷新。
- 真实解析并冻结 85 项 configuration universe（84 active + stale lock-only `androidApis`）、逐 producer 归因、lock、verification metadata、debug/release merged manifest 与最终 APK 无 native 证据。

### 非目标与冻结边界

- 不实现 `WidgetConfiguration`、`WidgetStateBuilder` 或其他纯 Widget state/action/refresh 契约；这些属于后续 T47-P1。
- 不实现 `AppWidgetProvider`/receiver、RemoteViews/XML layout/provider-info、Widget config Activity、QuickAiActivity、Worker、BootReceiver、enqueue、PendingIntent。
- 不修改任何生产 Kotlin、资源或 source `AndroidManifest.xml`；不修改 Room/DAO/schema/migration、AppContainer、事件 mutation、AI/WebDAV 协议。
- 不运行 connected/emulator；不开始 T47-P1/A/B/C 或 T48；不 push。
- `android/build.gradle.kts` 与 `android/settings-gradle.lockfile` 必须保持字节不变。

### Glance 拒绝证据与不可恢复结论

历史 T47-P0 的 Glance `1.1.1` producer graph 会选择 `androidx.datastore:datastore-core-android:1.2.1`。该 AAR 包含：

- `jni/arm64-v8a/libdatastore_shared_counter.so`
- `jni/armeabi-v7a/libdatastore_shared_counter.so`
- `jni/x86/libdatastore_shared_counter.so`
- `jni/x86_64/libdatastore_shared_counter.so`

这与无 NDK/native、universal APK 不含 `.so`、当前 DataStore JVM core 变体和尊重 producer metadata 的冻结契约冲突。因此 Glance 是明确拒绝方案，历史 STOP 证据保留；不得再声明或试验 `androidx.glance:*`、`datastore-*-android`、`libdatastore_shared_counter.so`，也不得以 exclusion、force、strictly、resolutionStrategy、substitution、手工 jar、复制源码或其他绕过恢复。

### WorkManager 授权矩阵

直接依赖仅允许：

| scope | 坐标 | 版本 |
|---|---|---:|
| implementation | `androidx.work:work-runtime-ktx` | `2.11.2` |
| testImplementation | `androidx.work:work-testing` | `2.11.2` |

producer metadata 明确获准的新选择：

| 坐标 | 版本 |
|---|---:|
| `androidx.work:work-runtime` | `2.11.2` |
| `androidx.tracing:tracing` | `1.2.0` |
| `androidx.tracing:tracing-ktx` | `1.2.0` |
| `androidx.lifecycle:lifecycle-livedata` | `2.9.4` |
| `androidx.lifecycle:lifecycle-livedata-core-ktx` | `2.9.4` |
| `androidx.lifecycle:lifecycle-service` | `2.9.4` |
| `androidx.concurrent:concurrent-futures-ktx` | `1.1.0` |

只有 `dependencyInsight` 证明由 WorkManager producer 引入时，允许 Room `2.8.4`、Lifecycle 其余 artifact `2.9.4`、Core/Core KTX `1.13.1`、Startup Runtime `1.2.0`、Annotation Experimental 既有 F 契约、Concurrent Futures `1.1.0`、Coroutines `1.9.0`、Kotlin stdlib `2.3.21` 及既有 jspecify/listenablefuture/arch-core 坐标增加 configuration coverage；版本不得漂移，必须逐坐标记录 before/after configuration 集合。

继续冻结 Compose Runtime `1.11.3`、Lifecycle 全组选中版本 `2.9.4`、SavedState `1.3.2`、Compose BOM `2024.08.00`、UI/graphics/foundation/material `1.6.8`、Material3 `1.2.1`、Activity `1.9.2`、Room `2.8.4` 与 DataStore JVM-only 生产图。禁止 WorkManager multiprocess/GCM/RxJava、Google Play services/Firebase、native、dynamic/SNAPSHOT、graphics-path、runtime-retain、任何未列明新坐标或既有版本漂移。

### 影响文件与 Agent 边界

允许修改：

- 主 Agent 独占：`android/gradle/libs.versions.toml`、`android/app/build.gradle.kts`、`android/app/gradle.lockfile`、`android/gradle/verification-metadata.xml`、必要的既有 dependency policy、三层 Android 设计、本任务、`progress.md`、根 `AGENTS.md`，以及确有规则变化时的 `android/AGENTS.md`。
- Agent A（tests-only）：只新增 WorkManager dependency graph policy 测试；不得改 catalog、Gradle、lock、metadata 或既有共享 policy。
- Agent B（tests-only）：只新增 merged manifest/WorkManager API/native/APK policy 测试；不得改生产、资源、source Manifest、build、lock、metadata 或既有共享 policy。

主 Agent负责审查两个 Agent diff、合并进既有 dependency policy、生产依赖 writer、设计/AGENTS、最终验证与 Git。子 Agent 不修改共享 progress/AGENTS。

### TEST-FIRST 测试矩阵

第一阶段只新增 tests-only policy；旧图红灯必须仅因两个 WorkManager 直接依赖、producer graph 坐标/configuration coverage 或 merged entries 缺失。既有 99 suites/980 tests、44/44 policy 或任何现存契约失败不属于有效红灯。

| 层级 | 正常路径 | 边界 | 非法/异常路径 | 回归/安全 |
|---|---|---|---|---|
| 直接依赖 | runtime-ktx/test testing 精确存在 | scope 分离、版本同为 2.11.2 | 多余直接声明、动态/SNAPSHOT | catalog-only 版本；无 inline coordinate |
| 真实图 | runtime/runtime-ktx/testing 与授权 tracing/Lifecycle/concurrent delta | 85 universe、空/测试/variant 配置 | 未列新坐标、版本漂移、Google/Glance/DataStore Android | producer attribution；逐坐标 before/after configuration |
| 锁/metadata | 官方 writer 生成并 strict/offline 重放 | stale `androidApis` 保留 | 手工 SHA、未验证 artifact、stale universe 漂移 | unaffected hash 与配置集合精确冻结 |
| merged manifest | debug/release 精确 WorkManager provider/service/receiver/metadata/权限 | variant package 与动态 receiver 权限 | Glance receiver、自有 service、BROWSABLE/data、额外权限、test provider/runner/debug CA | source Manifest 零 diff；每项 producer 归因 |
| Work API 静态门禁 | 后续仅 one-time 日期 work 允许 | 本轮无 Worker/enqueue 生产代码 | periodic/expedited/setForeground/setForegroundAsync/long-running | 无网络/通知/精确 Alarm/应用 foreground service |
| native/archive/APK | 所有新增 AAR/JAR/module/POM 与 debug APK 可扫描 | `jni/`、`lib/`、`.so/.a/.dll/.dylib` 全部 0 | `datastore-core-android`、`libdatastore_shared_counter.so`、Glance | universal APK 无 native；既有 DataStore JVM-only |
| Android 回归 | 99 suites/980 tests、OkHttp 16/16 | `ui.*` 50/567、API 26/36 | failure/error/skip 或 CloseGuard 命中即 STOP | lint/detekt/ktlint/signing/Google/native/lock/APK；generated/boundary 44/44 |
| Windows 发布 | 171/171、30 imports、build check、完整 PyInstaller | normal/silent 双向 primary-secondary | secondary 非 0、primary 退出、残留进程/目录 | `dist/data` 5 文件/195,620 bytes/摘要不变 |

### Manifest 精确 allowlist 方法

1. source `AndroidManifest.xml` 在修改前后按 SHA-256 与 Git diff 证明零变化。
2. 修改前后分别生成 debug/release merged manifest；解析 `uses-permission`、application 的 provider/service/receiver 及其 metadata、intent-filter、exported/permission/authorities/directBootAware/enabled 等安全相关属性。
3. 对每个新增条目追溯 merger blame/producer AAR manifest，只接受 WorkManager `2.11.2` 实际调度所需精确条目并冻结两个 variant 的集合；任何无法明确归因条目立即 STOP。
4. 若实际存在 `SystemForegroundService`，只将其作为锁定 producer 内部组件；同时源码 policy 保证应用没有 `setForeground`/`setForegroundAsync`、expedited、long-running、periodic 或自有 foreground service。
5. 禁止 Glance/AppWidget/Boot 自有组件、BROWSABLE/data、通知/精确闹钟/overlay/storage/calendar/location/camera/microphone 权限、test provider/runner 和 debug CA。

### 依赖解析、native 与验证流程

1. 修改前保存完整 85 configuration 图、冻结文件 SHA-256、debug/release merged manifest 与 `dist/data` 只读摘要；运行 99/980、UI 50/567、OkHttp 16/16、CloseGuard 0、policy/generated/boundary 44/44 和 Windows 171/import/check。
2. 文档先行后两个 tests-only Agent 写红灯；主 Agent 审查失败只来自 WorkManager/merged entries 缺失。
3. catalog/app build 只加入两个直接依赖；先 strict/offline，允许仅因新 artifact/verification 尚未存在而 fail closed。
4. 只使用 Gradle 官方 `--write-locks` 与 `--write-verification-metadata sha256`；网络仅用于依赖仓库解析，不访问真实 AI/WebDAV。
5. 解析全部 active configuration 与 stale lock universe；对所有新增/变化坐标运行 `dependencyInsight`，更新既有 dependency policy 的精确坐标、版本、configuration 与 unaffected hash。
6. 扫描新增 AAR/JAR/module/POM 与 APK 中 `jni/`、`lib/`、`*.so/*.a/*.dll/*.dylib`；结果必须为 0。
7. 切回 strict/offline 单 worker `--rerun-tasks` 执行完整 Android 与 Windows/PyInstaller/exe/`dist/data` 门禁，审查 diff/secret/generated/staged，创建独立提交后立即 STOP。

### STOP、回滚与完成定义

立即 STOP 条件：980 基线不绿；出现 Glance/DataStore Android/native/Google/dynamic/SNAPSHOT；未授权坐标或既有版本漂移；Lifecycle 非统一 `2.9.4`、Room 非 `2.8.4`、configuration universe 非 85；merged manifest 无法归因/禁止项；lint metadata 崩溃；需要 source Manifest、生产 Kotlin/资源、Room/schema 变化；Windows/PyInstaller/exe/`dist/data` 任一失败。

不得以 force/exclusion/升级 Compose/AGP/放宽规则/追加 allowlist绕过。触发 STOP 时仅用逐行补丁回退本轮 catalog/app build/lock/verification/policy 测试，lock/metadata 只用官方 writer 恢复；保留架构裁决、Glance 证据与 STOP 记录，不得 reset/checkout/stash/clean 或触碰用户数据。

完成定义：

- [x] Glance 拒绝与平台 App Widget/RemoteViews 架构已在三层设计、任务、progress、AGENTS 冻结。
- [x] 两个直接依赖及授权 producer delta 真实解析，85 universe/逐 configuration/producer attribution/lock/verification strict offline 全绿。
- [x] debug/release merged manifest 精确 allowlist、Work API 静态门禁、archive/APK 无 native 与无 Google/Glance/DataStore Android 全绿。
- [x] Android 99 suites/980 tests、UI 50/567、OkHttp 16/16、CloseGuard 0、policy/generated/boundary 与完整 lint/build 门禁通过。
- [x] Windows 171/171、30 imports、build check、完整 PyInstaller、EXE 双向隔离冒烟通过；`dist/data` 摘要不变。
- [x] 文档/AGENTS/diff/secret/generated/staged 审查完成，提交 `Align Android T47 platform widget dependencies`，不 push，working tree/staged clean 后立即 STOP。

### 后续 T47-P1 纯契约边界

P1 foundation contracts 已完成；后续仍只允许在下一阶段实现不依赖 `RemoteViews`/Receiver/Activity/Worker/Context/Room/网络/系统时钟的剩余 Widget 接线前置逻辑。AppWidgetProvider、RemoteViews、配置 Activity、QuickAiActivity、Worker/Boot 仍属更后阶段。

### 修改前执行证据（2026-09-01）

- RECOVERY 完全匹配：branch `codex/android-architecture`、HEAD `a4c3f30f14d3db5e99e8390858c0b78d229c77d8`（`Stabilize Android AI fallback request ownership`），working tree clean、staged empty、未 push。
- 忽略证据目录保存修改前完整 dependency report、冻结哈希和 merged manifests；84 active + stale lock-only `androidApis` = 85，active 未缺 lock 条目。
- strict/offline 单 worker强制 rerun：99 suites/980 tests、UI 50/567、OkHttp 16/16，0 failure/error/skip，四类 SQLite/Room CloseGuard 0；policy、generated、boundary 均 44/44。
- Windows 171/171、30 production imports、`build.py --check` 通过；修改前 `dist/data` 为 5 文件/195,620 bytes/摘要 `E43733909427A268005A9514FB136CDA0F74C43395046269E73EF20E539668D9`。
- tests-only 红灯：Agent A 仅新增 dependency graph policy 6 methods，旧图 3 绿/3 红（8 条 failure records）；Agent B 仅新增 manifest/native policy 6 methods，3 个安全负向方法绿、3 个精确 Manifest 方法在 debug/release 各红一次。主 Agent复跑合计 12 methods/14 failure records，失败只对应两个 WorkManager 直接依赖、授权 producer delta、4 项权限、5 个组件和 `WorkManagerInitializer`/filters 缺失；85 universe、无 Glance/DataStore Android/Google/native/dynamic/SNAPSHOT、禁 periodic/expedited/foreground、无 BROWSABLE/data/敏感权限全部保持绿色。

### T47-P0R 完成证据（2026-09-01）

#### 解析与锁图

- 唯一直接依赖：`implementation work-runtime-ktx:2.11.2`、`testImplementation work-testing:2.11.2`；没有直接声明任何传递依赖。
- Work `runtime/runtime-ktx` 精确覆盖 9 个 app classpath，`work-testing` 精确覆盖 4 个 unit-test classpath；tracing `1.2.0` 覆盖 main runtime + unit compile/runtime 6 个配置，`tracing-ktx:1.2.0` 覆盖 4 个 runtime 配置。
- Lifecycle 新选择 `lifecycle-livedata/lifecycle-livedata-core-ktx:2.9.4` 覆盖 9 个 app classpath，`lifecycle-service:2.9.4` 覆盖 4 个 runtime；`concurrent-futures-ktx:1.1.0` 覆盖 main runtime + unit compile/runtime 6 个配置。Lifecycle 全组继续统一 `2.9.4`。
- producer attribution 允许的既有扩展只有 `arch-core:core-runtime:2.2.0` 从 4 runtime 扩为 9 app classpath，以及 `listenablefuture:1.0` 从 debug/release runtime 扩为 debug/release compile/runtime + debugAndroidTest compile 5 个配置；版本均未漂移。逐坐标 `dependencyInsight` 均回指 WorkManager 或其 Lifecycle 原子组。
- 官方 writer 还为解析时读取但最终未选中的 `lifecycle-livedata-core:2.6.2.module` 写入校验 SHA；该 producer metadata 不在 resolved lock 中，最终选择仍仅为 `2.9.4`。unaffected lock SHA 与 85 configuration universe 已重新冻结；84 active 全量解析、failure marker 0，stale lock-only 仍只有 `androidApis`。

#### merged manifest 与负向边界

- debug/release 精确权限：既有 `INTERNET` 与动态 receiver permission，加 Work producer 的 `ACCESS_NETWORK_STATE`、`FOREGROUND_SERVICE`、`RECEIVE_BOOT_COMPLETED`、`WAKE_LOCK`。
- 精确组件：既有 `MainActivity`、`InitializationProvider`、`ProfileInstallReceiver`，加 `SystemJobService`、`SystemForegroundService`、`ForceStopRunnable$BroadcastReceiver`、`RescheduleReceiver`、`DiagnosticsReceiver`；startup provider metadata 精确加入 `WorkManagerInitializer`，intent-filter 只保留 launcher、boot reschedule、diagnostics 与 profileinstaller producer filter。
- `SystemForegroundService` 仅作为 WorkManager producer 的 `enabled=@bool/enable_system_foreground_service_default`、`exported=false` 内部组件锁定；生产源码无 periodic/expedited/foreground/long-running Work API。source `AndroidManifest.xml` 没有变化，也没有 AppWidget/Glance/应用自有 service、BROWSABLE/data、敏感权限、test provider/runner 或 debug CA。
- 11 个新增/变化相关坐标的 32 个 AAR/JAR/module/POM 与最终 debug APK 扫描 `jni/lib/*.so/*.a/*.dll/*.dylib` 命中 0；APK 无 `libdatastore_shared_counter.so`，解析图无 Glance/DataStore Android/Google/dynamic/SNAPSHOT。

#### 最终验证与范围审计

- strict/offline、单 worker、`--rerun-tasks`：96 tasks BUILD SUCCESSFUL；JVM 99 suites/980 tests、UI 50/567、`OkHttpAiClientTest` 16/16、0 failure/error/skip、四类 CloseGuard 0；lintDebug/lintRelease/detekt/ktlint/signing/Google/native/lock/assemble/APK 全绿。
- `verify-generated.ps1` 与 `verify-boundaries.ps1` 各 44/44；agent 的 12 个 tests-only 红测方法在主 Agent 审查真实 producer 图后合并进既有 44 项 suite，没有过滤、降级或增加最终测试数。
- Windows offscreen 171/171、30 production imports、`build.py --check`、完整 PyInstaller、normal primary + silent secondary 与 silent primary + normal secondary 均通过；secondary 均 exit 0、primary 存活，最终无 Clender 进程或隔离目录。
- `dist/data` 前后 5 files/195,620 bytes/摘要 `E43733909427A268005A9514FB136CDA0F74C43395046269E73EF20E539668D9`；只读取 metadata/哈希。无生产 Kotlin、资源、source Manifest、Room/schema、`android/build.gradle.kts`、settings lock 或 Windows 源码变化。
- P0R 完成并在 checkpoint commit 后 STOP。P1 纯 Widget configuration/state/action/refresh 未开始；AppWidgetProvider、RemoteViews、配置 Activity、QuickAiActivity、Worker/Boot、enqueue/PendingIntent 均未开始；T47 整体未完成，T48 未开始，未 push。

## T47-P1：Widget foundation contracts（2026-09-02）

P1 已启动，详细任务清单、纯接口、持久化 wire contract、测试矩阵、三 Agent 独占边界、STOP 条件和完成定义见 `doc/tasks/T47-android-widget-foundation-contracts.md`。

本轮只允许新增纯 `domain/widget` configuration/state/presentation/action/refresh 类型，以及只接受现有 `DataStore<Preferences>` 的 `DataStoreWidgetConfigurationStore`。不实现 Provider、Receiver、Activity、Worker、RemoteViews、资源、Manifest、PendingIntent、WorkManager enqueue、AppContainer 或 Quick AI。T47 整体保持进行中。

## T47-P2A：只读平台 Launcher Widget Provider 与 RemoteViews 渲染（2026-09-02）

P2A 任务细则、恢复证据、冻结尺寸/ProviderInfo、RemoteViews/资源、Coordinator、Provider/goAsync 生命周期、Manifest allowlist、三 Agent 独占写集、tests-first 矩阵、STOP 条件和完成定义见 `doc/tasks/T47-android-widget-provider-rendering.md`。

本轮只实现 `AppWidgetProvider → app-scoped WidgetUpdateCoordinator → P1 WidgetStateBuilder/WidgetPresentationPolicy → RemoteViews` 的只读生产闭环：API 26–30 单尺寸安全分类，API 31+ 精确四项 responsive map；默认配置、有限空态/错误态、多实例、generation 防迟到、删除清理、生产懒装配和确定性 `goAsync().finish()`。不实现 P2B/P2C 或 T48。

### P2A 启动证据与实施状态

- [x] 恢复现场精确匹配 branch/HEAD/subject/clean/staged-empty、六冻结构建 SHA、source Manifest SHA。
- [x] 修改前完整 Android JVM：105 suites/1033 instances、UI 50/567、P1 6 suites/53、OkHttp 16/16、0 failure/error/skip、CloseGuard 四类 0。
- [x] 新建 P2A 任务文件并更新高层/详细设计、T47 任务和 progress，冻结三 Agent 互斥测试/实现边界。
- [x] Agent A/B/C tests-only 红灯审查，失败仅来自计划内类型、资源、Provider 与 Manifest 缺失。
- [x] 原 Agent 实现 RemoteViews/Coordinator/Provider 三片；主 Agent完成 AppContainer/ClenderApplication 惰性装配和跨层测试。
- [x] Android/Windows/PyInstaller/EXE/dist_data 完整验证通过；文档/AGENTS 更新与 checkpoint commit 收口中。

### T47-P2A 当前证据

- P2A 聚焦 7 suites/78 JVM instances；完整 Android 112 suites/1111 tests、UI 50/567、P1 6 suites/53、OkHttp 16/16，0 failure/error/skip，CloseGuard 四类 0。strict/offline 单 worker 96 tasks、generated/boundary 各 44/44、85 configuration universe、六冻结 SHA、WorkManager producer allowlist全绿。
- API 26–30 单尺寸与 API 31+ 四项 responsive RemoteViews、2/4/8 容量、有限空/错态、中英资源、theme/font/opacity、纯文本安全渲染通过；Provider 的 update/options/delete、goAsync finish-once、超时/取消/关闭和 lazy assembly 在 API 26/36 通过。
- source/debug/release Manifest 与 APK 只有获准的应用 Widget receiver/provider-info delta；无点击/PendingIntent、自有 Service/Worker/Boot/QuickAI/config、Glance/DataStore Android/native/Google 或新增权限。
- Windows 171/171、30 imports、build check、完整 PyInstaller 与双向 EXE 冒烟通过；最终 EXE 45,582,391 bytes、SHA-256 `4EDD84C5C6808FC718F59867DDB6C483AFC524A25ED08DD8282CC33D12D835B4`。`dist/data` 前后 5 files/195,620 bytes/摘要 `E43733909427A268005A9514FB136CDA0F74C43395046269E73EF20E539668D9` 不变，最终 0 进程/0 冒烟目录。
- P2A 完成不等于 P2/T47 完成：P2B 配置 Activity、编辑/Quick AI/本地刷新 PendingIntent 未开始；P2C mutation/remote/foreground/date-boundary、Worker/Boot 未开始；T48 未开始。

## T47-P2B1：Widget 配置 Activity 与唯一 Configure action（2026-09-02）

P2B1 已完成，详细状态机、入口归属、UI、provider-info、PendingIntent、RemoteViews、生产装配、三 Agent tests-first 证据和完整发布验证见 `doc/tasks/T47-android-widget-configuration-ui.md`。

- 配置 Activity 因 Launcher 跨包调用固定 `exported=true`，但在初始化 container 前严格验证 action、可选 canonical data、正 ID 与 `AppWidgetManager` provider ownership；无效入口返回有限取消结果且零持久化/Room/AI/WebDAV/Keystore 副作用。
- 非秘密草稿支持 recreate、dirty/cancel/discard、有限错误与 operation guard；保存仅一次 DataStore upsert，成功后请求目标 P2A 本地重建，有限更新失败不回滚已持久化配置。
- 本轮唯一平台 action 为 explicit/immutable `Configure` getActivity PendingIntent；三尺寸和 API31+ 四 map 仅 header Configure 可点击。provider-info 只增加 configure 与 optional/reconfigurable。
- 完整 Android 117 suites/1185 tests、96-task gate、generated/boundary 44/44、85 universe/六 SHA/Manifest/APK/CloseGuard 全绿；Windows 171/171、30 imports、build/check/PyInstaller/双向 EXE 与 dist/data 全绿。
- P2B2 的 EditEvent、QuickAi、LocalRefresh PendingIntent/Activity/路由尚未开始；P2C 自动触发、Worker/Boot 尚未开始；T47 整体未完成，T48 未开始。

## T47-P2B1R：生产 Configure action 装配修复（已完成验证，待提交，2026-09-02）

- 独立回归任务见 `doc/tasks/T47-android-widget-configuration-production-binding.md`；不并入 P2B2。
- 已确认生产调用链的 `ProductionWidgetRenderSink` 错误调用不带 `appWidgetId` 的 `renderForHost` overload，导致实际发布给 `AppWidgetManager` 的 RemoteViews 缺少 Configure 点击；既有 renderer 测试仅直调带 ID overload，未覆盖装配缺口。
- 恢复现场与修改前 strict/offline JVM 基线通过：117 suites/1185 tests、UI 52/607、0 failure/error/skip、CloseGuard 四类 0；六冻结构建文件、source Manifest 与 provider-info 哈希匹配。
- API 26/36 真实生产装配回归先在旧实现以 Configure 无点击稳定红灯；随后只让 `ProductionWidgetRenderSink` 把目标 `appWidgetId` 传给既有 renderer overload，实际 host view 的 Intent 与两实例 identity 均通过。
- 最终 Android 117 suites/1187 tests、聚焦 7/80、96-task gate、generated/boundary 44/44、85 universe/六 SHA/Manifest/provider-info/APK/CloseGuard 全绿；Windows 171/171、30 imports、build/PyInstaller/双向 EXE 与 `dist/data` 全绿。P2B2/P2C/T47/T48 状态不变。

## T47-P2B2a：Widget 事项入口与本地刷新 action（已完成验证，待提交，2026-09-02）

详细产品裁决、冷/热启动路由、非导出 Receiver、PendingIntent/validator、LARGE-only refresh surface、Manifest allowlist、三 Agent tests-first 写集、STOP 与发布门禁见 `doc/tasks/T47-android-widget-edit-local-refresh-actions.md`。

- 本轮只实现 EditEvent 与 LocalRefresh；EditEvent 仅导航至既有 `EVENTS + EventDetail(eventId)`，不自动进入编辑、不写事件；LocalRefresh 只消费 `MANUAL_LOCAL_REFRESH` 并通过同一 app-scoped coordinator 重建目标实例。
- Configure/P2B1R、2/4/8、API 31+ 四 map、provider-info、六冻结构建文件与 85 configuration universe 保持；source Manifest 唯一允许新增非导出、无 filter 的 `WidgetLocalRefreshReceiver`。
- QuickAi 必须继续 fail-closed，留给独立 P2B2b；P2C、T47 整体与 T48 不完成。
- 恢复与修改前门禁已通过：`codex/android-architecture @ bbdd776f…15d52`，clean/staged empty/无 upstream，117 suites/1187 tests、UI 52/607、0 failure/error/skip、CloseGuard 四类 0。
- 三 Agent tests-only 红灯只来自计划内缺失；实现后 Edit 冷/热入口、非导出 LocalRefresh Receiver、共享 strict validator、唯一 PI factory 与 LARGE-only action surface 聚焦全绿，主 Agent 真实生产链路组合为 9 suites/119 tests。
- 最终 Android 为 124 suites/1263 tests、UI 52/607、96/96 tasks、CloseGuard 0、generated/boundary 各 44/44；六冻结 SHA、85 universe、provider-info、Work producer graph、2/4/8 与四 map 均未漂移。Windows 171/171、30 imports、check、PyInstaller、双向 EXE 与 `dist/data` 全绿。
- P2B2a 已完成；QuickAi/P2B2b、P2C、T47 整体与 T48 仍未完成。

## T47-P2B2b：Widget Quick AI Activity（STOP-ESCALATE，2026-09-02）

详细安全边界、活动会话/AI runtime 复用、Activity/UI/无障碍、MainActivity 冷热导航、三 Agent 互斥 tests-first 写集、完整验证与 STOP/回滚见 `doc/tasks/T47-android-widget-quick-ai-activity.md`。

- 修改前 RECOVERY 精确匹配 `codex/android-architecture @ c7403a1b...0e97`，working tree/staged clean；六冻结构建文件、source/provider/debug/release merged Manifest 摘要已记录。
- strict/offline single-worker `--rerun-tasks` 完整 96-task gate 通过：124 suites/1263 tests、UI 52/607、OkHttp 16/16、0 failure/error/skip、CloseGuard 四类 0、generated/boundary 各 44/44。
- 三 Agent 完成 tests-first 与分区实现，聚焦 150 tests、ktlint、detekt 曾全绿；最终完整 Android gate 在 1333 tests 中出现 2 个失败，依约触发 STOP，不重跑。
- 全部 P2B2b production/resource/Manifest/test/policy 已撤回，Android 源码与 HEAD 无 diff；仅保留任务/progress/STOP 证据。未运行 Windows/PyInstaller，未提交或 push；P2C/Worker/Boot/自动刷新/T48 均未开始。

## T47-Baseline2：Event Room 冷 Flow 测试稳定性（STOP-ESCALATE，2026-09-02）

- 独立任务、根因、显式订阅握手、重复稳定性矩阵、STOP/回滚与完成定义见 `doc/tasks/T47-android-event-flow-test-stability.md`。
- 本 checkpoint 只允许修改 `EventCrudIntegrationTest.kt` 的既有冷 Flow 测试：两个 collector 必须在 add 前实际收到初始空 emission，写入后等待精确 range/month-count 谓词，保留 5000ms 与 mutation version `== 1`。
- P2B2b 保持 STOP/未实现；RemoteViews 失败留待未来 P2B2b tests-first 精确迁移。本任务不修改生产、Quick AI、RemoteViews、Manifest、资源、依赖、P2C 或 T48。
- Flow 临时修复通过目标 suite 10 次独立 API 26/36 rerun、完整 JVM 连续三次 124 suites/1263 tests、96-task Android gate、generated/boundary 与冻结边界；Windows 171/171、30 imports、check、PyInstaller 亦通过。
- 最终 normal→silent EXE 冒烟通过，但 silent primary→normal secondary 场景的 primary 在 readiness 前以 0 退出，命中 EXE 失败即 STOP。未重试；唯一 Android 测试改动已撤回，Android 对 HEAD 零 diff，未提交。P2B2b/P2C/T48 状态不变。

### P1 测试与实施状态

- [x] 恢复现场、六冻结 SHA-256、working tree/staged 与 Android JVM 基线核对。
- [x] 新建 P1 任务文件并冻结测试矩阵、接口、Agent 边界和 STOP 条件。
- [x] Agent A/B/C tests-only 红灯审查。
- [x] configuration/store、state/presentation、action/refresh 实现与聚焦验证。
- [x] Android/Windows/PyInstaller/EXE/dist_data 完整门禁、文档和 checkpoint commit 前审查。

### T47-P1 当前证据

- 三个独立 Agent 先完成 tests-only 红灯，再由原 Agent 在独占文件中实现；主 Agent 仅接管 C Agent 未完成的 action/refresh detekt/ktlint 最小重构。新增 P1 源码测试方法 45 个，DataStore suite 在 API 26/36 展开为 53 个 JVM instances。
- P1 聚焦六 suite 为 53/53；完整 strict/offline 单 worker `--rerun-tasks` Android gate 为 96 tasks BUILD SUCCESSFUL，最终 105 suites/1033 tests、UI 50/567、OkHttp 16/16、0 failure/error/skip，CloseGuard 四类 0。`detekt` 19 项与 `ktlint` 格式项均先失败后修复，未关闭规则或放宽测试。
- `verify-generated.ps1`、`verify-boundaries.ps1` 各 44/44；六冻结文件 SHA-256、85 configuration universe、source/merged Manifest、依赖和资源均未漂移。焦点 `ui.* + OkHttpAiClientTest` 的既有 `EventCrudIntegrationTest` Room Flow race 已记录：API26/36 各曾超时，未修改该测试/超时，限定方法与最终完整 suite 通过。
- 首次全量 gate 在 `dexBuilderDebug` 因主机 native memory 不足导致 JVM daemon 崩溃；仅调整本次命令的 `GRADLE_OPTS` 为 `-Xmx2g`/`ActiveProcessorCount=4` 后，原严格门禁完整成功，未改 Gradle 配置。
- Windows 使用锁定 Miniconda Python 3.12.4 完成 171/171、30 imports、`build.py --check`；最终 PyInstaller `dist/Clender.exe` 为 45,582,491 bytes，SHA-256 `A64C190E47167B9E905545A421E1792D9A3802456CF11587719A9886F202802B`。普通/静默双向 primary-secondary 隔离冒烟均通过，secondary exit 0、primary 存活、最终无 Clender 进程及临时目录。
- `dist/data` 前后均为 5 files / 195,620 bytes，逐文件哈希一致，组合摘要 `E43733909427A268005A9514FB136CDA0F74C43395046269E73EF20E539668D9`；P1 文档、AGENTS、diff/secret/generated/staged 审查已完成，待创建指定 checkpoint commit。

## T47-Baseline3：Windows frozen 双向单实例冒烟（历史 STOP，已由 Baseline5R2 收口）

- 独立任务见 `doc/tasks/T47-windows-frozen-single-instance-smoke.md`；只建立 exact-path onefile cohort、fresh DB、QLocalServer probe 与场景间 quiescence 的验证协议，并分类 Baseline2 silent-primary exit 0。
- 根因确证前禁止修改 Windows 生产入口/单实例实现；Android 相对 HEAD 继续零 diff，Baseline2 Flow 修复仍已撤回，P2B2b 仍 STOP/未实现，P2C/T48 未开始。

### T47-Baseline3 STOP-ESCALATE（2026-09-02）

- Reviewer 与唯一 instrumented 旧 EXE 双向诊断确认 production 无 silent-primary 提前退出证据；根因分类为旧验证流程缺少 onefile cohort + QLocalServer quiescence。临时 harness 23/23、Windows 194/194、30 imports、check/build 和新 EXE 10 cycles / 20 scenes 全绿。
- 最终 Android gate 在既有 `EventCrudIntegrationTest.addRefreshesExistingB1RangeAndMonthCountFlows[26]` 再次 5000ms 超时后立即停止；未重跑。harness 脚本/测试已撤回，只保留文档，不更新 AGENTS、不提交。Flow/P2B2b 仍未完成，P2C/T48 未开始。

### T47-Baseline4 STOP-ESCALATE（2026-09-02）

- Flow 显式首次 emission 握手通过目标/相关/10轮/三轮完整 JVM稳定性矩阵；frozen harness 保留原23项能力并新增4项异常安全回归，27/27，Windows完整198/198、30 imports/check和Reviewer终审通过。Android 96-task正式 gate为96/96。
- generated policy 43/44：唯一失败是既有 Android current-change boundary 将本任务白名单内的两个 Windows harness 文件判为越界；修复需修改白名单外 policy 文件，依约立即 STOP。不运行 boundary/85 universe/PyInstaller/最终20场景，不提交、不push；两项已验证修复按规则保留为 staged-empty diff。P2B2b/P2C/T48 状态不变。

### T47-Baseline5R2 基础设施最终发布收口（2026-09-03）

- Baseline4/4R 已完成：Room 冷 Flow 使用真实首次 emission 握手；Windows frozen smoke 使用 exact-path onefile cohort、fresh sandbox DB 与 QLocalServer quiescence；change-boundary 仅加入两个 harness exact path。
- Baseline5 teardown 红灯保持 `NON-REPRODUCIBLE`，CalendarOverflow/helper/生产代码零修改。最终 Android 96/96、124 suites/1263 tests、UI 52/607、OkHttp 16/16、CloseGuard 0，policy/generated/boundary 44/44 与 85 universe 保持；Windows 198/198、30 imports、check/build、新 EXE 20/20 和 `dist/data` 一致性全绿。
- P2B2b/P2C/T48 仍未开始；APK 无 QuickAiActivity、BootReceiver、应用 Worker、Glance、Google/Firebase 或 native artifact。
