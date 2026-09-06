# T47-P1 Android Widget foundation contracts

## 状态

实施完成，发布验证完成，待创建 checkpoint（2026-09-02）。本任务只完成平台 Launcher Widget 的纯 configuration、state、presentation、action identity 与 refresh/date-boundary 契约；T47 整体保持进行中。

## 目标

- 定义不依赖 Android UI/系统装配的 `WidgetConfiguration`、`WidgetState`、`WidgetPresentationPolicy`、`WidgetActionSpec` 和 `WidgetRefreshPolicy`。
- 复用现有 `DataStore<Preferences>`，实现按正 `appWidgetId` 隔离的配置 registry 与完整实例字段原子读写。
- 为后续 `AppWidgetProvider`/`RemoteViews`/显式 `PendingIntent`/WorkManager one-time date boundary 接线提供稳定、可测试、fail-closed 的输入输出。
- 通过 tests-first 证明 P1 不引入依赖漂移、秘密泄露、网络/同步/AI 副作用或 Android/domain 分层倒置。

## 非目标与严格禁止

- 不新增或修改 `AppWidgetProvider`、`BroadcastReceiver`、`Activity`、`Service`、`Worker`、`RemoteViews`、PendingIntent、Intent、XML/图片/字符串资源或 source/merged Manifest。
- 不调用 WorkManager、AlarmManager、网络、AI、WebDAV、Room、EventService、AppContainer、MainActivity 或 `Context`；不接入 Widget、Boot、Quick AI 或配置 Activity。
- 不修改 Gradle/catalog/lock/verification metadata、既有 Event/EventStateClassifier、DAO/schema/migration、T46 UI 契约或 Windows 生产代码。
- 不恢复 Glance、`datastore-*-android`、`libdatastore_shared_counter.so` 或任何 native/Google/动态依赖路径。
- 不读取、复制、解析或写入仓库根 `data/`、`dist/data/` 或真实用户秘密/对话。

## 修改前恢复证据

- 分支：`codex/android-architecture`。
- HEAD：`d9c254350c2ab5e879cb8d1cef7936a8956a0a89`，subject：`Align Android T47 platform widget dependencies`。
- working tree clean，staged empty。
- 六个冻结构建文件 SHA-256 与 T47-P0R 记录完全一致：`libs.versions.toml` `1D60EBF0…54255`、`app/build.gradle.kts` `149231F3…4CEC`、`app/gradle.lockfile` `8D6F8088…DD2A`、`verification-metadata.xml` `1F02C0DF…857E`、`settings.gradle.kts` `42BF469C…BE18`、`build.gradle.kts` `53E62522…29803C`。
- 修改前执行 `./android/scripts/gradle.ps1 testDebugUnitTest --offline --no-parallel --max-workers=1 --rerun-tasks`：99 suites / 980 test instances，0 failure/error/skip；测试结果 XML 逐文件汇总确认。SQLite/Room CloseGuard 四类扫描为 0，既有 policy/generated/boundary 均为 44/44（由基线文档与门禁保持）。

## 影响文件与职责

生产文件只允许新增下列文件：

- `android/app/src/main/java/com/molotov/clender/domain/widget/WidgetConfiguration.kt`：纯 domain 配置类型、校验与显式 defaults factory；只依赖 Kotlin/JDK `java.time`。
- `android/app/src/main/java/com/molotov/clender/data/settings/DataStoreWidgetConfigurationStore.kt`：现有 `DataStore<Preferences>` 的 namespaced registry/实例编解码与 `get`/`observeAll`/`upsert`/`delete`。
- `android/app/src/main/java/com/molotov/clender/domain/widget/WidgetState.kt`：纯展示安全模型、时间状态和状态输出。
- `android/app/src/main/java/com/molotov/clender/domain/widget/WidgetStateBuilder.kt`：窗口筛选、合法性/墓碑/重复清理、权威状态分类与稳定排序。
- `android/app/src/main/java/com/molotov/clender/domain/widget/WidgetPresentationPolicy.kt`：SMALL/MEDIUM/LARGE 容量、分组优先级、截断和剩余计数。
- `android/app/src/main/java/com/molotov/clender/domain/widget/WidgetActionSpec.kt`、`WidgetRefreshPolicy.kt`：纯 action identity、校验、刷新触发和日期边界计划模型。

测试文件由三个 Agent 各自独占，不互相修改：

- Agent A：`domain/widget/WidgetConfigurationTest.kt`、`data/settings/DataStoreWidgetConfigurationStoreTest.kt`。
- Agent B：`domain/widget/WidgetStateBuilderTest.kt`、`domain/widget/WidgetPresentationPolicyTest.kt`。
- Agent C：`domain/widget/WidgetActionSpecTest.kt`、`domain/widget/WidgetRefreshPolicyTest.kt`。

主 Agent 独占本任务、T47、`progress.md`、AGENTS、最终边界审计和任何共享文件；若需要补充测试，只能新增主 Agent 自有文件，不重写 Agent 文件。

## 冻结纯接口与数据契约

### Configuration/store

- `WidgetConfiguration` 包含正 `appWidgetId`、同日 `LocalTime` 的 `startTime`/`endTime`、`opacityPercent`、`fontSizeSp` 和独立 `WidgetThemeMode(SYSTEM/LIGHT/DARK)`。秒/纳秒必须为 0，`start < end`；默认 `08:00–22:00`、opacity `100`、theme `SYSTEM`、字号使用调用方传入的合法 widget 字号，否则 `13`。
- 配置不得包含标题、描述、事件正文、身份、URL、网络状态、密码、Key、异常正文或 `data.settings.ThemeMode` 类型。
- `get(id)` 对缺失实例返回 `null`；默认值必须由显式 defaults factory 创建，不能把缺失伪装成已配置实例。
- store 构造只接受现有 `DataStore<Preferences>`；固定 registry `widget_configuration_ids`，实例前缀 `widget_configuration_<id>_`，字段为 `start_minute`、`end_minute`、`opacity_percent`、`font_size_sp`、`theme`。
- `upsert`/`delete` 进入 `DataStore.edit` 前完成全部校验；每次调用只进行一次 edit。upsert 在一次 edit 中完整更新 registry 与实例字段；delete 只删目标字段和 registry 成员。
- `observeAll` 只发出完整、有效、已注册实例并按 id 升序；非法 registry 项、不完整/非法实例 fail-closed 跳过，不污染其他实例。读取 `IOException` 回退为 `null`/empty；非 `IOException` 原样传播。
- 所有既有 appearance/navigation/active conversation/AI/WebDAV/secret envelope key 的 wire bytes 必须保持不变；不创建第二 DataStore、Keystore 或 AppContainer 接线。

### State/presentation

- `WidgetStateBuilder` 输入显式包含 events、configuration、日期和 `now: LocalDateTime`，窗口固定为该日期 `[start,end)`。reminder 使用 `start ∈ [rangeStart,rangeEnd)`；timespan 使用 `start < rangeEnd && end > rangeStart`。
- builder 过滤墓碑、`EventValidator.validatePersisted` 不通过的 persisted event 和重复 id；复用 `EventStateClassifier` 的 CURRENT/NEXT/FUTURE/PAST 语义，隐藏 PAST，保留全部 CURRENT，NEXT 为最早 future start 的全部并列项，其余为 FUTURE。
- 输出按 `startTime → effective end → id` 稳定排序。展示项只允许 id、eventType、title、startTime、endTime/有效结束和 temporalState；不得带 description、syncUid、createdAt、updatedAt、deletedAt、异常正文或隐藏派生敏感字段。Unicode、emoji、换行标题原样保留。
- presentation 的 `SizeClass` 容量固定 SMALL/MEDIUM/LARGE = `2/4/8`，优先 CURRENT → NEXT → FUTURE，各组内稳定排序，不重复事件；截断不改变 temporalState，`remainingCount` 精确且非负。opacity=0 仍生成完整安全模型。空、全 past、清理后为空、current/并列 next 超容量均是明确契约。

### Action/refresh

- action 只定义纯 action、target、canonical identity、parser/validation，不创建 Intent/PendingIntent，不执行事件写入、AI、网络或同步。支持 `EditEvent(widgetId,eventId)`、`QuickAi(widgetId)`、`LocalRefresh(widgetId)`、`Configure(widgetId)`。
- widget id 必须为正 Int；EditEvent event id 必须为 `1..Int.MAX_VALUE`。canonical identity 固定为 `clender-internal://widget/{widgetId}/edit/{eventId}`、`.../quick-ai`、`.../refresh`、`.../configure`。未知 path/query/fragment、空白、HTTP(S)、0/负数/溢出/非数字全部拒绝；round-trip 必须精确且区分 widget/action/event。后续 Android flags 只在更后阶段使用 `FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT`，P1 不导入 flags。
- refresh trigger 固定为 LOCAL_MUTATION、REMOTE_VISIBLE_CHANGE、CONFIGURATION_CHANGE、FOREGROUND、MANUAL_LOCAL_REFRESH、DATE_BOUNDARY、BOOT。mutation/remote/foreground/date-boundary/boot 面向全部已配置 widget；configuration/manual 只面向目标正 id；空配置 BOOT 为 no-op。
- refresh 是本地 widget 重建计划，不 enqueue、不读取系统时间、不触发 WebDAV/AI/网络。使用注入 `Clock`/`Instant` 和 `ZoneId` 计算下一个本地日期 `atStartOfDay` 的严格正 delay，覆盖 UTC、普通时区、DST gap/overlap、午夜和 Instant/日期溢出；溢出返回有限 fail-closed 结果，不泄露异常正文。计划固定 `uniqueWorkName=clender-widget-date-boundary`、app-wide、oneTime、replaceExisting=true，禁止 periodic/expedited/long-running/foreground/alarm/notification/network。

## TEST-FIRST 矩阵与 Agent 边界

第一阶段三个 Agent 只能新增失败测试，不得新增生产代码；主 Agent 先运行并审查红灯，只有确认失败仅因计划中的 P1 类型/行为缺失后，才向原 Agent 发实施指令。每个 Agent 的实现只能修改其列出的生产文件和测试文件，禁止共享文件冲突。

| Agent | 正常 | 边界 | 非法/异常 | 回归/边界审计 |
|---|---|---|---|---|
| A configuration/store | defaults、get/upsert/observe/delete、双实例 | id=1/MAX、08:00/22:00、opacity 0/100、字号 8/20、ID 重用 | 0/负 id、start=end、秒纳秒、越界、坏 registry/实例、IOException/其他异常 | 一次 edit、实例隔离、删除不影响其他 settings/secret bytes、domain theme 解耦 |
| B state/presentation | reminder/timespan、current/next/future、small/medium/large | 半开端点、跨范围、零时长、跨日、超大 duration、多 current/并列 next、2/4/8 | tombstone、非法 persisted event、重复/全清理、全部 past、标题原样 | classifier 复用、past 隐藏、截断保留状态、remaining 精确、安全字段缺席、domain 无 Android import |
| C action/refresh | 四 action round-trip、七 trigger target、标准时区边界 | MAX id、午夜、UTC/普通时区、DST gap/overlap、空配置 BOOT | blank/HTTP/bad path/query/fragment/0/负/溢出/非数字、日期/Instant 溢出 | identity 不串实例、不创建 Android action；one-time replace 计划，无 periodic/network/API 调用 |

所有自动化用例不得 ignored、sleep、重试、排序测试、放宽超时或假断言。另由主 Agent 增加/审查源码边界测试，证明 `domain/widget` 无 `android.*`、Compose、DataStore、Room、WorkManager、network import，`data/settings` 只依赖现有 DataStore，不接触 secret codec。

## 风险与控制

- DataStore registry 与实例字段不一致可能导致一个坏实例污染全部配置；通过单 edit、完整校验、逐实例 fail-closed 和隔离测试控制。
- `EventStateClassifier` 的有效结束与零时长 reminder 语义容易被 Widget 重写；builder 必须直接复用权威 classifier，并测试相同边界。
- DST gap/overlap 和 `LocalDate.atStartOfDay` 可能产生非正或溢出 delay；只输出有限计划/失败码，不把异常正文暴露给 UI。
- 纯模型误带进内部字段或 Android 类型会阻塞后续接线；源码 import 扫描和敏感字段反射/序列化检查作为门禁。
- 任何依赖、Manifest、资源、组件、Room/AppContainer 或 T46 契约漂移均立即 STOP，不自行扩展方案。

## 实施步骤

1. 保存恢复现场，完成基线与冻结摘要；已完成并记录于本文件。
2. 更新 T47 任务、progress 与本文件，冻结测试矩阵和三 Agent 独占边界。
3. 并行启动 A/B/C tests-only；主 Agent审查各自 diff 和红灯，不实施生产代码。
4. 让同一 Agent 在独占文件中实现其契约；先聚焦测试，再由主 Agent集成审查。
5. 运行聚焦测试、Android 99+ 全量、UI/OkHttp/CloseGuard、strict/offline 单 worker 完整门禁、generated/boundary 44/44、85 configuration universe/六 SHA/Manifest 零漂移和纯 import 边界。
6. 运行 Windows 171/171、30 imports、`build.py --check`、完整 PyInstaller/exe 双向隔离冒烟与 `dist/data` 只读前后摘要核对。
7. 更新 T47、本文件、progress、根/Android AGENTS；执行 diff/secret/generated/staged 审查，创建唯一 checkpoint commit，不 push，提交后立即 STOP。

## 实施与门禁记录（2026-09-02）

- 三组 Agent 均完成 tests-only 红灯阶段；主 Agent 审查后由原 Agent 在独占文件中完成实现。P1 源码测试方法共 45 个，因 DataStore API 26/36 参数化展开为 53 个 JVM test instances。
- 首次完整 gate 的 detekt 报告共 19 项，已通过命名常量、私有 helper、低复杂度 parser/plan 和 fixture 参数对象修复；未关闭 detekt 规则、未加 suppress。随后 ktlint 的格式报告也已逐项修复。
- strict/offline 单 worker gate 96 tasks 已成功：`testDebugUnitTest`、`lintDebug`、`lintRelease`、`detekt`、`ktlintCheck`、release signing、Google/native/locked-version、`assembleDebug`、APK native audit；最终 105 suites/1033 tests，0 failure/error/skip，UI 50/567，OkHttp 16/16，CloseGuard 四类 0。
- `verify-generated.ps1` 与 `verify-boundaries.ps1` 各 44/44；六个冻结构建文件 SHA-256 完全匹配。焦点 `ui.* + OkHttpAiClientTest` 曾在既有 `EventCrudIntegrationTest` 的 `drop(1).first()` Room Flow API 26/36 各超时一次；未改范围外测试/超时，限定方法通过，后续完整 suite 通过。
- 首次完整 gate 曾因主机 native memory 不足在 `dexBuilderDebug` 使 Gradle daemon 崩溃；未产生代码失败，移除明确的 JVM crash log 后用 `GRADLE_OPTS` 限制 `-Xmx2g`/`ActiveProcessorCount=4` 重跑成功，未改变工程文件。
- Windows 使用 `C:\Users\30910\Miniconda3\python.exe` 完成 171/171 unittest、30 模块导入与 `build.py --check`；最终完整 PyInstaller 生成 `dist/Clender.exe`，大小 `45,582,491` bytes、SHA-256 `A64C190E47167B9E905545A421E1792D9A3802456CF11587719A9886F202802B`。普通主进程→静默次进程、静默主进程→普通次进程均通过（secondary exit 0，primary 存活），最终无 Clender 进程或冒烟目录。
- `dist/data` 构建前后均为 5 files / 195,620 bytes，逐文件大小与 SHA-256 完全一致；组合摘要为 `E43733909427A268005A9514FB136CDA0F74C43395046269E73EF20E539668D9`。最终 diff/secret/generated/staged 审查完成，下一步仅创建指定 checkpoint commit，不 push。

## 回滚方式

只允许用逐行补丁删除本轮新增 P1 生产/测试文件，并恢复本轮文档中的状态记录；不得 `reset`、`checkout`、`stash`、`clean`，不得回退 P0R、Baseline 或用户未提交改动。由于本轮不改依赖/Manifest/资源/AppContainer，回滚不应触碰这些文件。

## STOP 条件

- 修改前 99/980 基线或 CloseGuard 非零；任何既有 failure/error/skip。
- 需要新增/改变依赖、Gradle、lock、verification、Manifest、资源、组件、Context、Room、WorkManager API 或网络。
- 需要改变 EventStateClassifier、Event/EventService、T46 契约、AppContainer、真实 DataStore wire 既有设置或 secret envelope。
- 出现 Glance/DataStore Android/native/Google/dynamic/SNAPSHOT、敏感字段泄露、domain/widget Android import、非确定性/未覆盖产品语义。
- 六冻结文件、85 configuration universe、merged Manifest 或 `dist/data` 漂移；Windows/PyInstaller/EXE 任一门禁失败。

## 完成定义

- [x] 三组纯 P1 生产契约与测试完成；所有指定正常、边界、非法、异常和回归路径有精确测试方法，未使用绕过手段。
- [x] DataStore 多实例/原子 edit/字节保持/异常处理、state classifier/sanitization、presentation 截断、action identity、refresh/DST/溢出契约全部通过。
- [x] `domain/widget` 与 store 边界无禁止 import/副作用；不修改组件、资源、Manifest、依赖、Room、AppContainer、T46 或 Windows。
- [x] Android 既有 980 + P1 新增 instances、UI 50/567、OkHttp 16/16、CloseGuard 0、全部 strict/offline 门禁与 generated/boundary 44/44 通过；冻结摘要零漂移。
- [x] Windows 171/171、imports/build/PyInstaller/exe 双向冒烟通过，`dist/data` 仍为 5 files / 195,620 bytes / `E43733909427A268005A9514FB136CDA0F74C43395046269E73EF20E539668D9`。
- [x] 文档、AGENTS、diff/secret/generated/staged 审查完成；下一步创建 `Checkpoint Android T47 widget foundation contracts`，不 push；T47 整体仍进行中，P2 组件接线和 T48 未开始。
