# Clender Android 原生架构版高层设计

## 2026-09-06 当前完成状态与 Git 交付边界

T47/T48 的实现、构建与验收已完成。最终同源码 JVM 155 suites/1611 tests、UI 57 suites/665 tests，failure/error/skip 及四类泄漏标记均为 0；完整 verify-all、签名审计与设备验收全部完成。Git 交付为最后步骤，提交/推送回执以主 Agent 最终答复与 Git 日志为准；本记录不宣称已经 commit 或 push。下文阶段性的“仍进行中”“待设备”“未完成”及旧包摘要均保留为历史过程，当前状态以本节为准。

最终验收 APK SHA-256：`5d90cbb3d271063ad7b9c3645eb94ede9180bde62c46bde109a09cc0c56a897e`。API26/36 phone 与 API36 tablet 已完成最终包 core 8 步及实际 Widget；API36 phone/tablet 的最终 Drawer 18 步通过。所有 8 台设备的最终包安装与 hash 核验均通过。

增量证据复用保持原事实：API29/31/33 phone 的 core 复用 4cf 候选、Drawer 复用 04 候选；API35 phone 的 core 复用 4cf、Drawer 18 步使用最终包；API33 tablet 的 core 复用 c287、Drawer 18 步使用最终包。date/boot 继续复用 c287 证据。这是已完成的增量验收矩阵，不表述为所有场景都在最终包重新执行。

API26 Widget 原 ADB 清理临时 XML 失败记录保留；`completion-widget-api26-r4-resume-result.json` 为 PASS，恢复时只读确认配置已保存，再续跑自动更新/冷启动。此续跑不抹除原失败。历史 Room teardown 的 NON-REPRODUCIBLE 裁决不变，后续全绿不等于根因修复。

独立签名 key 与配置保持忽略，须由用户安全备份，不输出秘密。最终 Git 交付仍按已授权的统一集成提交与 Gitee `codex/android-architecture` 分支执行；不合并远程 main、不推 GitHub。本次仅同步原六份文档，完成即冻结，不测试、不修改生产、不提交。

## 1. 架构总览

Android 主应用采用单 MainActivity 的 Compose 应用壳，另有 Widget 配置与 QuickAiActivity；使用分层包结构和单向数据流：

```text
Compose UI / platform RemoteViews Widget
        ↓ intent
ViewModel / Widget action coordinator
        ↓ use case
Domain services and pure logic
        ↓ repository interfaces
Room / DataStore / Keystore / OkHttp
```

依赖只允许向下：UI 不直接访问 DAO/HTTP；网络层不依赖 UI；Room entity 不携带 Android UI 类型。所有事件写入统一经过 `EventService`，AI 操作统一经过 `AiOperationExecutor -> EventService`，同步写入统一经过事务 repository。

## 2. 工程边界

```text
android/
├─ build.gradle.kts, settings.gradle.kts, gradle.properties
├─ gradle/libs.versions.toml, wrapper/, verification-metadata.xml
├─ app/
│  ├─ build.gradle.kts, proguard-rules.pro
│  └─ src/
│     ├─ main/AndroidManifest.xml
│     ├─ main/java/com/molotov/clender/
│     │  ├─ app/             # Application、Activity、依赖装配、生命周期
│     │  ├─ core/            # model、时间、结果、日志清洗
│     │  ├─ domain/          # 事件/AI/日历/同步纯逻辑与接口
│     │  ├─ data/local/      # Room entity/DAO/database/repository
│     │  ├─ data/settings/   # DataStore + Keystore envelope
│     │  ├─ data/network/    # AI 与 WebDAV OkHttp transport
│     │  ├─ sync/            # foreground-triggered coordinator
│     │  ├─ ui/              # navigation、screens、components、theme
│     │  └─ widget/          # 平台 AppWidgetProvider、RemoteViews、配置与 action
│     ├─ test/               # JVM、Robolectric、MockWebServer
│     └─ androidTest/        # Room/Compose/App Widget/设备测试
├─ scripts/                  # 隔离工具链、验证、APK 审计
├─ config/                   # lint/detekt/ktlint 与固定测试配置
├─ .gitignore
├─ AGENTS.md                 # Android 子项目补充门禁
└─ README.md
```

Windows 源码与 Android 构建没有编译依赖。两端只共享文档化的数据契约，不共享运行时文件。

## 3. 模块职责

### 3.1 Core/Domain

- `Event`、`EventType`、`Conversation`、`Message`、同步记录为纯 Kotlin immutable model。
- `EventValidator/EventService` 负责标题、类型、时间、时长、ID、转换和写入边界。
- `CalendarLayoutEngine` 负责日期相交、24h 几何、lane/cluster、marker、overflow 输入模型；Compose 只绘制结果。
- `EventStateClassifier` 负责 Widget current/next 半开区间分类。
- `AiMessageBudgeter`、`AiResponseParser`、`AiOperationValidator/Executor` 负责不可信模型输出。
- `WebDavDocumentCodec`、`MergeEngine` 负责 schema v1、规范化、上限、LWW 与确定性决胜。

### 3.2 Data

- Room 保存事件、对话和消息；事件删除为墓碑，普通查询过滤 `deletedAt`。
- `EventRepository` 暴露 Flow 与事务方法；DAO 只在 data 包可见。
- DataStore 保存非秘密配置、活动页面/日期/对话和 Widget instance 设置。
- Keystore 生成不可导出的 AES/GCM key；秘密以带版本、IV、ciphertext 的 envelope 保存。
- Android 自动备份和设备迁移显式禁用并排除秘密、数据库与本地配置；首版卸载即删除 Android 本地数据，未来迁移另设显式导入/导出。

### 3.3 Network/Sync

- AI/WebDAV 各自使用限定超时的 OkHttpClient；不共享 Authorization interceptor，避免凭据串域。
- endpoint/URL 在构造请求前严格验证 HTTPS、host、path 和禁止内嵌凭据。
- `SyncCoordinator` 使用 `Mutex` 与 pending flag 串行化；只响应 local-change、manual、foreground。
- 同步远端变化在单 Room transaction 应用；刷新 UI/Widget，不再次排队上传。
- WorkManager 只用于系统允许时的 Widget 尽力刷新/日期边界恢复，不用于周期网络同步或 AI。

### 3.4 UI/Widget

- `AppShellViewModel` 管理顶层目的地、选中日期与日历模式；主题由 appearance Flow 驱动。
- 每个 screen 有独立 ViewModel 与 `StateFlow<UiState>`；事件使用显式 action sealed interface。
- Navigation Drawer 提供五个目的地；事项编辑使用 navigation route/dialog destination。
- 周/日时间轴由 Compose Canvas/布局绘制，语义层提供可访问节点和聚合选择。
- Widget 读取 repository 的只读快照与实例设置；P2A 通过 app-scoped `WidgetUpdateCoordinator` 生成标准 `RemoteViews`。P2B1 当时只开放 Configure；后续 Edit/Refresh 与本轮 QuickAI/P2C 接线见第 5.1 节。
- 快速 AI Activity 复用同一 AI coordinator；只显示输入、busy 与结果摘要，正文仍在 AI 视图查看。

## 4. 关键数据流

### 手工事件

```text
Compose form -> EventViewModel -> EventService.validate
-> EventRepository transaction -> Room
-> UI Flow refresh + Widget update request + SyncCoordinator(local-change)
```

### AI

```text
AI screen/quick Activity -> AiCoordinator(single in-flight)
-> context + budget -> HTTPS client
-> parse/validate -> AiOperationExecutor -> EventService -> Room
-> conversation/message transaction -> UI
-> successful schedule mutation only -> Widget + sync
```

生命周期变为后台时 coordinator 取消 Call/coroutine；不自动重试。

### WebDAV

```text
trigger -> SyncCoordinator mutex/pending
-> local records -> GET remote
-> codec + merge -> Room transaction
-> conditional PUT -> status
-> visible remote changes -> UI + Widget only
```

### Widget

```text
AppWidgetProvider/update request
-> app-scoped WidgetUpdateCoordinator
-> configuration store + EventRepository.observeRange(...).first()
-> WidgetStateBuilder -> WidgetPresentationPolicy
-> API 26–30 single RemoteViews / API 31+ SizeF RemoteViews map
```

```text
Launcher/custom Configure PendingIntent
-> exported WidgetConfigurationActivity
-> exact action/data/id + AppWidgetManager provider ownership
-> non-secret draft -> one DataStore upsert
-> target Widget local rebuild through existing P2A runtime
```

## 5. 主要设计决策

1. **单 app module + package 分层。** 首版避免过度 Gradle 多模块化，同时通过 Kotlin visibility、package 和测试守住边界；后续可按稳定包拆 module。
2. **Room 同时保存事件与对话。** Android 不需要兼容桌面 JSON 本地格式，Room 提供事务、迁移和 Flow；WebDAV schema 保持独立 DTO。
3. **墙钟时间使用严格字符串/`LocalDateTime` 转换。** 不存 timezone/Instant，确保与桌面互通；同步 metadata 独立使用 `Instant` UTC。
4. **直接 Android Keystore envelope。** 避免明文 DataStore，也避免依赖 Google 服务或已弃用抽象；解密失败清空配置状态而不泄露异常内容。
5. **纯 JVM/无 NDK。** universal APK 跨 arm64/armv7/x86_64，不承担 native ABI 拆包和加载风险。
6. **前台触发同步。** 符合用户决策，避免 OEM 后台限制导致伪精确承诺；Widget 时间刷新为尽力而为。
7. **标准 Widget 能力映射。** 主应用保留 Compose；Launcher Widget 使用平台 `AppWidgetProvider + RemoteViews + AppWidgetProviderInfo XML`。P2A API 26–30 依据 `OPTION_APPWIDGET_*` size bucket，API 31+ 使用精确四项 responsive `RemoteViews` size mapping；P2A 不产生任何点击动作或 Intent，后续快速输入/编辑必须走独立 Activity。
8. **Glance 明确拒绝。** T47 历史候选 Glance `1.1.1` 的 producer graph 选择 `datastore-core-android:1.2.1`，其 AAR 含四 ABI `libdatastore_shared_counter.so`，违背无 native 与 DataStore JVM-only 契约。禁止用 exclusion/force/strictly、手工 jar、复制源码或其他绕过恢复。
9. **WorkManager 最小边界。** 日期边界继续使用 `2.11.2` one-time work；P2A 不 enqueue、不使用 periodic/expedited/long-running/foreground work，不申请精确闹钟/通知，也不执行后台网络刷新。

10. **P2A 生命周期与安全边界。** Provider 只接受系统 AppWidget 更新回调；每个 callback 使用 `goAsync()` 并在 `finally` 恰好完成。Coordinator 以正数 ID、每实例 generation 和 8 秒本地查询上限隔离失败/迟到结果；AppContainer 在 DataStore/Room 前取消并等待 Widget scope。source Manifest 只允许一个 exported 的 `ClenderWidgetProvider` receiver 与 `APPWIDGET_UPDATE`/provider-info metadata。

11. **P2B1 配置入口安全。** Widget 配置 Activity 必须 `exported=true`，因为 Launcher 需跨包调用；授权不依赖 caller/referrer/PendingIntent sender，而由精确 `ACTION_APPWIDGET_CONFIGURE`、可选 canonical data、正 ID 和 `AppWidgetManager` provider ownership 在 container 初始化前共同验证。配置只含非秘密字段，取消零写入，保存为一次原子 upsert 后目标本地重建。唯一平台 action 是显式、immutable、canonical data 区分实例的 Configure PendingIntent。

以上第 7、9、10、11 项中的 P2A/P2B1 限制描述对应历史阶段：P2B2a 收口时已接入 EditEvent 冷/热导航与目标 LocalRefresh，当时 QuickAI/P2C/T48 尚未开始。保留此边界作为历史证据，本轮状态如下。

### 5.1 2026-09-06 接续实施契约（全 JVM 已通过，发布验收中）

本轮以 `doc/tasks/T47-T48-android-completion.md` 为主任务。QuickAI/P2C 与 Drawer 修复已通过同源码全 JVM；最终签名产物审计与 verify-all 全门禁通过，剩余设备与交付收口仍进行中，不标 T48 完成。独立签名材料已生成并保持忽略，脚本不回显密码/alias，用户须安全备份 key 与配置；当前 APK/AAB 哈希见本轮最新证据，最终证书指纹由主 Agent 补录。

- RemoteViews 保留 API 26–30 单尺寸与 API 31+ 四尺寸映射，Configure/Edit/Refresh 沿用原契约；LARGE 增加显式、immutable 的 QuickAI PendingIntent。非导出 QuickAiActivity 在访问 container 前验证实例归属和完整 Intent，复用现有活动对话/AI coordinator，用户提交前不发送请求。
- 当前 Intent 交付契约：创建端仍为 CLEAR_TOP|SINGLE_TOP（0x24000000）。仅 EditEvent/QuickAI 消费 externalTaskFlags：NEW_TASK（0x10000000）可选，只有存在 NEW_TASK 时才允许附加 BROUGHT_TO_FRONT（0x00400000）；单独 BROUGHT_TO_FRONT 拒绝。QuickAI 的 EXCLUDE_FROM_RECENTS（0x00800000）独立可选。EditEvent 合法 flags 为 0x24000000/0x34000000/0x34400000；QuickAI 为这三种各自可选 EXCLUDE_FROM_RECENTS，另有 0x24800000/0x34800000/0x34c00000。内部导航真实交付仍为 0x24000000，不扩展；Configure/LocalRefresh 及 action/component/package、canonical data、唯一 appWidgetId、平台 owner 等边界不变。

真实 JDB 证据：QuickAI 交付 0x34c00000、EditEvent 交付 0x34400000，均比 ActivityTaskManager 日志多 BROUGHT_TO_FRONT；唯一 appWidgetId、canonical identity 与 owner 正常，仅去除此位同一 Intent 即校验成功。tests-first RED 为 QuickAI 58 tests/8 failures、EditEvent 30 tests/2 failures；完整证据见主任务。
- P2C 的 `WidgetAutomaticRefreshRuntime` 在现有 Widget scope 内汇合本地 mutation、远端可见变化、配置保存、前台恢复、手动刷新、日期边界和 Boot，复用同一 store/coordinator，只读本地事件。平台拥有的实例 ID 决定更新范围；无实例时不打开 Room 或网络。
- 日期恢复只使用唯一 `clender-widget-date-boundary` 的 WorkManager 2.11.2 one-time REPLACE；日期 Worker 完成后安排后继，不使用 periodic/expedited/foreground work、精确闹钟、通知或后台网络。
- 非导出 Boot receiver 仅接受 `BOOT_COMPLETED` 的受限形状，extras 可为空或仅含非负 Int `android.intent.extra.user_handle`；验证后以 9 秒上限及 finish-once 请求本地恢复。未知 extras 或 data/type/selector/clip/categories 均拒绝。
- 远端事务 apply 不经过 EventService、不产生本地 mutation；WebDAV 通过可见变化 callback 通知 Widget，即使后续 PUT 失败或取消也保留已经发生的可见变化通知。
- 日历/事项顶层页面现在有真实的“新建事项”按钮（至少 48dp）；点击以当前选中日期打开既有新建表单，保存仍走 EventService，取消/草稿恢复沿用现有 CRUD 契约。

现有签名 APK/AAB 的工具审计已通过，但该证据不代表后续 P2C 集成源码的最终产物验证；最终构建、设备矩阵及收口记录由主任务统一完成。

### 5.2 当前验证证据

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

真实 UI 修复：日历/事项顶层新建按钮以 selectedDate 进入现有表单；旋转/recreate 对同一 Detail/Edit ID 保留编辑态，对同日期且已有 form 的 New route 不再 startNew 覆盖草稿。MainActivity 重建不重放旧启动 Widget Intent，onNewIntent 继续独立校验。About 使用真实应用/版本 metadata，资源格式占位符已修复，不显示原样占位符；缺失 metadata 仍显示有限不可用。

Windows 198/198、imports 30/30、完整 PyInstaller 与正式 10 cycles/20 scenes 均通过；EXE 45,582,242 bytes，SHA-256 `C52F386542E78C5FA2E85C4A0B02F942FC2A2481C60694D5D29A33832CCA207D`；dist/data 5 files/196,074 bytes，路径、大小、时间及逐文件哈希前后完全一致。

历史 checkpoint 的“未开始”“QuickAI fail-closed”“无 Worker/Boot”与旧测试数量只描述各自阶段，不是当前基线。保留历史 Room/CalendarOverflow 一次 teardown 失败及 NON-REPRODUCIBLE 裁决；后续全量绿色不构成根因修复证明，不得宣称已修复该历史失败。

## 6. 备选方案及取舍

- Flutter/React Native：Widget、Room/Keystore、后台与原生测试需额外桥接，且引入更多运行时/native ABI，拒绝。
- 多 Gradle module：隔离更强但初期构建与依赖复杂度高，暂缓。
- SQLCipher：增强数据库加密但引入 native ABI 与密钥管理范围，当前只要求保护秘密，拒绝。
- 精确 AlarmManager/前台服务：可提高 Widget 边界刷新，但增加权限、耗电和上架限制，拒绝。
- 周期 WorkManager WebDAV：移动端常见但不符合确认的同步语义，拒绝。
- 系统 Calendar Provider：需要敏感权限且改变数据所有权，拒绝。

## 7. 高层测试策略

- 纯 JVM：模型、验证、日历布局、AI 预算/解析/操作、WebDAV codec/merge、Widget state。
- Room/Robolectric：DAO、事务、迁移、Flow、进程重建近似与 DataStore。
- MockWebServer：AI/WebDAV 请求、超时、HTTP、redirect、TLS/URL、412、上限与取消。
- Compose UI：Drawer、三视图、表单、主题、字号、状态恢复、无障碍语义。
- Robolectric + instrumentation 平台 Widget：多实例、API 26–30 size bucket、API 31+ responsive/exact size mapping、RemoteViews action、进程终止、日期边界。
- Instrumentation：API 26/29/31/33/35/36 手机，API 33/36 平板；旋转、分屏、字体、主题、离线、Doze。
- 构建安全：lint、detekt、ktlint、dependency verification、release R8、manifest、APK 签名/内容、无 cleartext/native/秘密。
- 仓库回归：Windows 198 unittest（171 既有 + 27 frozen smoke）、导入、build check、PyInstaller、隔离 exe、`dist/data` 前后摘要。

## 8. 风险与未知

- API 36、AGP、Compose、RemoteViews 与 WorkManager 当前稳定版本组合必须通过真实构建、producer graph、merged manifest 与 APK 审计锁定。
- Launcher 对集合、透明度、最小尺寸和更新节流的实现差异只能覆盖 AOSP 模拟器，保留 OEM 人工风险。
- Compose Canvas 的可访问性需额外语义 overlay，不能只依赖绘图命中。
- Keystore 在锁屏重置、设备恢复、厂商实现异常下可能失效；必须表现为重新配置，不得崩溃。
- WebDAV 真实服务仍有 Provider 差异；本轮不连接真实服务。
- 本机磁盘/网络可能不足以完成全部 emulator 下载；若发生，以命令、错误和未覆盖矩阵明确报告，不降低代码测试门禁。
