# Clender Android 原生架构版详细设计

## 2026-09-06 当前完成状态与 Git 交付边界

T47/T48 的实现、构建与验收已完成。最终同源码 JVM 155 suites/1611 tests、UI 57 suites/665 tests，failure/error/skip 及四类泄漏标记均为 0；完整 verify-all、签名审计与设备验收全部完成。Git 交付为最后步骤，提交/推送回执以主 Agent 最终答复与 Git 日志为准；本记录不宣称已经 commit 或 push。下文阶段性的“仍进行中”“待设备”“未完成”及旧包摘要均保留为历史过程，当前状态以本节为准。

最终验收 APK SHA-256：`5d90cbb3d271063ad7b9c3645eb94ede9180bde62c46bde109a09cc0c56a897e`。API26/36 phone 与 API36 tablet 已完成最终包 core 8 步及实际 Widget；API36 phone/tablet 的最终 Drawer 18 步通过。所有 8 台设备的最终包安装与 hash 核验均通过。

增量证据复用保持原事实：API29/31/33 phone 的 core 复用 4cf 候选、Drawer 复用 04 候选；API35 phone 的 core 复用 4cf、Drawer 18 步使用最终包；API33 tablet 的 core 复用 c287、Drawer 18 步使用最终包。date/boot 继续复用 c287 证据。这是已完成的增量验收矩阵，不表述为所有场景都在最终包重新执行。

API26 Widget 原 ADB 清理临时 XML 失败记录保留；`completion-widget-api26-r4-resume-result.json` 为 PASS，恢复时只读确认配置已保存，再续跑自动更新/冷启动。此续跑不抹除原失败。历史 Room teardown 的 NON-REPRODUCIBLE 裁决不变，后续全绿不等于根因修复。

独立签名 key 与配置保持忽略，须由用户安全备份，不输出秘密。最终 Git 交付仍按已授权的统一集成提交与 Gitee `codex/android-architecture` 分支执行；不合并远程 main、不推 GitHub。本次仅同步原六份文档，完成即冻结，不测试、不修改生产、不提交。

## 1. 构建与版本

- Kotlin DSL、Gradle wrapper 和 version catalog；所有直接/关键插件版本精确锁定。
- `namespace/applicationId=com.molotov.clender`，`minSdk=26`、`targetSdk/compileSdk=36`。
- Java/Kotlin toolchain 17；Compose compiler 与 Kotlin 版本按官方兼容组合锁定。
- `debug` 使用系统 debug key；`release` 只从环境变量/忽略的 `keystore.properties` 读取签名信息。签名检查只挂在 `assembleRelease`、`bundleRelease`、package/sign 等产物任务的执行阶段；`lintRelease` 可在无密钥 CI 中运行。缺失时产物任务明确失败，不回退 debug 签名。
- release 启用 R8、资源压缩、`debuggable=false`、`testOnly=false`。首版 baseline profile 为非目标，避免引入额外设备/插件不确定性。
- `android/.gitignore` 排除 `.gradle/`、`.toolchain/`、`.android/`、`.sdk/`、`local.properties`、`*.jks`、`*.keystore`、`keystore.properties`、`app/build/`、`build/`、`release/`、测试报告和 IDE 文件。
- `scripts/env.ps1` 必须把 `JAVA_HOME`、`ANDROID_HOME`、`ANDROID_SDK_ROOT`、`ANDROID_USER_HOME`、`ANDROID_EMULATOR_HOME`、`ANDROID_AVD_HOME`、`GRADLE_USER_HOME`、`TMP`、`TEMP` 全部规范化到 `android/.toolchain/` 或 `android/.tmp/` 忽略路径；解析后验证不得用 `..` 逃逸。下载前后对仓库外常见用户目录做只读污染哨兵比较，并校验下载包 SHA-256。
- T42 已验证基础锁定为 Microsoft OpenJDK 17.0.20+8（生产 toolchain）、Microsoft OpenJDK 21.0.12+8（仅运行 Robolectric API 36）、AGP 8.13.2、Gradle 8.13、Kotlin/Compose compiler 2.3.21、compile/target 36、Build Tools 36.0.0、Compose BOM 2024.08.00（UI 1.6.8）、Activity Compose 1.9.2、Lifecycle 2.8.4、Core KTX 1.13.1、Robolectric 4.16。T43 已实证并锁定 Room 2.8.4、KSP 2.3.9 与 AndroidX Test Core 1.6.1，Room/Robolectric API 26/36 通过且最终 APK 无 native artifact。较新 Compose UI 会传递引入含 native artifact 的 `graphics-path`，违反无 `.so` 契约；因此使用实际解析、静态分析、Robolectric 与 APK 审计均通过的稳定组合。DataStore 1.2.1、OkHttp 5.3.0、serialization 1.11.0 已按后续任务实证锁定；WorkManager 2.11.2 只在 T47-P0R 以 runtime/testing 两个直接依赖引入并重新解析、锁定和审计，不得静默抬升基础 Compose/Lifecycle 原子组。
- Glance `1.1.1` 已在历史 T47-P0 依赖试验中拒绝：producer graph 选择 `androidx.datastore:datastore-core-android:1.2.1`，其 AAR 的 `jni/arm64-v8a`、`jni/armeabi-v7a`、`jni/x86`、`jni/x86_64` 均含 `libdatastore_shared_counter.so`。这与无 NDK/native、DataStore JVM core 变体和 universal APK 无 `.so` 契约冲突。不得再声明 `androidx.glance:*`、`datastore-*-android` 或通过 exclusion、force、strictly、手工 jar、复制 Glance 源码恢复该方案。

## 2. 数据模型与契约

### 2.1 Event

```kotlin
enum class EventType { REMINDER, TIMESPAN }

data class Event(
    val id: Long,
    val eventType: EventType,
    val title: String,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime?,
    val description: String,
    val estimatedDurationMinutes: Int,
    val createdAt: Instant,
    val syncUid: String,
    val updatedAt: Instant,
    val deletedAt: Instant?
)
```

约束：标题 trim 后非空；`reminder.endTime=null`、预计时长为任意非负 `Int`；UI 可提供 0–480 的快捷选择但必须允许合法更大值和远端旧数据；`timespan.endTime>startTime`；`syncUid` 为 32 位小写 hex；普通 UI 不接收墓碑。`LocalDateTime` 以严格 `yyyy-MM-dd HH:mm` 与 WebDAV 互转，不做时区换算；metadata 以 `yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'` UTC 互转。

### 2.2 Conversation/Message

```kotlin
data class Conversation(id: String, title: String, createdAt: Instant, tokenCount: Int)
enum class MessageRole { USER, ASSISTANT, THINK }
data class Message(id: Long, conversationId: String, role: MessageRole, content: String, timestamp: Instant)
```

Conversation UUID 与 Event sync UID 无关；消息只在本地 Room。删除活动对话时选择下一个；删除最后一个后立即创建空白对话。

### 2.3 Room schema v1

- `events`: 上述字段，`id INTEGER PRIMARY KEY AUTOINCREMENT`，`sync_uid UNIQUE NOT NULL`，索引 `start_time`、`sync_uid`、`deleted_at`。
- `conversations`: `id TEXT PRIMARY KEY`、title、created_at、token_count。
- `messages`: 自增 id、conversation_id FK cascade、role/content/timestamp，索引 conversation/time。
- `widget_configs`: 可放 DataStore；Room 不保存系统 widget ID。
- DB version 1 从空库创建；migration 测试基线保留导出的 schema JSON。未来升级必须显式 migration，禁止 destructive fallback。

DAO 查询：

- reminder：`start >= rangeStart && start < rangeEnd`。
- timespan：`start < rangeEnd && end > rangeStart`。
- 普通查询一律 `deleted_at IS NULL`。
- 同步快照包含墓碑并按 `sync_uid` 排序。

事务：本地 add/update/delete 同一事务更新 sync metadata；远端 apply 以单事务 upsert/delete tombstone，返回 visibleChanged；conversation 消息与 token count 同事务。

## 3. 领域服务

### 3.1 EventService

```kotlin
suspend fun add(command: AddEvent): Event
suspend fun update(id: Long, patch: EventPatch): Event
suspend fun delete(id: Long): Boolean
fun observeDate(date: LocalDate): Flow<List<Event>>
fun observeRange(start: LocalDateTime, end: LocalDateTime): Flow<List<Event>>
```

Update 先读现值、应用白名单 patch、整体重验；类型转 reminder 强制 end null；类型转 timespan 若没有合法 end 则拒绝。成功后发 `ScheduleMutation`，由 app-scoped coordinator 请求 Widget 与同步；远端 apply 不发 local mutation。

### 3.2 CalendarLayoutEngine

输入为已过滤 Event、日期范围、每小时逻辑高度、列宽。计算真实碰撞区间与视觉最小高度分离；按开始、结束、ID 确定性排序；greedy 分 lane，cluster 内 laneCount 固定；窄列按最小可访问宽度聚合 overflow。输出不包含 Compose 类型：

```kotlin
data class CalendarBlock(
  id: Long, column: Int, topFraction: Float, heightFraction: Float,
  lane: Int, laneCount: Int, clusterId: Int, marker: Boolean,
  overlapRanges: List<MinuteRange>
)
```

跨日 timespan 先裁剪到每个日期的 `[00:00, next 00:00)` 再布局；倒序/损坏记录跳过并返回 sanitized count，日志不得含标题。

### 3.3 EventStateClassifier

- timespan 与有预计时长 reminder 使用 `[start,end)`。
- 零时长 reminder 仅在开始分钟为 current。
- 输出全部 current；若无/有 current 均输出最早 future 同起点的全部 next；past 为其余已结束事项。

## 4. AI 设计

### 4.1 设置与请求

- `AiSettings(endpoint, encryptedKey, model, temperature, maxOutputTokens, contextWindow, thinkingEnabled, thinkingEffort, systemPrompt, personality)`。
- endpoint 必须 HTTPS、无 userInfo/query/fragment；请求路径统一相对 `/v1/models`、`/v1/chat/completions`，保留 endpoint base path 的明确规则并测试。
- models 15 秒；chat 180 秒；连接/读取超时分开设置；错误正文最多 512 个清洗字符。
- Authorization 仅在单个 request builder 添加，不装全局日志 interceptor。

### 4.2 Token 与上下文

- UTF-8 字节保守估算；每条消息加固定开销。
- 预留 max output 与至少 context window 10% 安全余量；保留 system、人格、当前日期/时间/星期、非墓碑事件摘要和最新非 think 历史；必要时逐消息/内容截断。
- UI 展示估算值并明确非官方 tokenizer。

### 4.3 解析与执行

- kotlinx.serialization `Json { ignoreUnknownKeys=false; isLenient=false }`；先剥离单个 Markdown JSON fence。
- 接受 `{operations:[...]}`、operation array、单 operation object；其他/失败转 reply。
- operation 字段严格白名单：
  - add：event_type/title/start_time/end_time/description/estimated_duration；
  - update：event_id 加上述可更新字段；
  - delete：正整数 event_id；
  - reply：非空 message。
- 限制单响应操作数、单字符串长度和总 payload；危险 action/未知字段拒绝并生成用户可读错误。
- 执行按响应顺序；每个事件操作独立经 EventService。AI 删除直接执行。只有成功 schedule mutation 触发一次合并后的 Widget/sync refresh。

### 4.4 并发与生命周期

- `AiCoordinator` 持有 `Mutex/Job/OkHttp Call`；busy 时拒绝第二次提交。
- 发起时捕获 conversationId；结果写回捕获 ID。
- ProcessLifecycle 进入 background 时 coordinator 取消；旋转/配置变更只重建 Activity，不取消 app-scoped 请求。取消结果写有限状态但不自动重试、不执行半途未解析操作。
- HTTP 完整成功并解析后才执行操作，避免取消时部分网络正文导致写入。

## 5. WebDAV 设计

### 5.1 Settings/URL

- HTTPS directory URL；禁止 query、fragment、userInfo；用户名/密码非空。
- 固定 remote `clender-events.json`，使用安全 URL resolution，不能跳出 base path。

### 5.2 schema v1

根对象严格包含 `schema_version:1`、`events:[...]`。Event record 字段严格为 desktop 当前字段：event_type、title、start_time、end_time、description、estimated_duration、created_at、sync_uid、updated_at、deleted_at。上限在读入完整正文前由 Content-Length 和 streaming byte count 双重执行。

### 5.3 Merge

- 两侧按 syncUid 规范化；同 UID 选择 updatedAt 新者。
- 相同 updatedAt 必须逐字符复刻桌面 `json.dumps(record, sort_keys=True, ensure_ascii=False, separators=(",", ":"))`：字段按 ASCII key 排序，字符串使用 Python JSON 的控制字符/引号/反斜杠转义、非 ASCII 原样保留并拒绝未配对 surrogate；比较按 Unicode code point 数值逐个进行，而不是 Kotlin UTF-16 `String.compareTo` 或 UTF-8 bytes。T44 共享黄金向量覆盖中文、emoji/非 BMP、反斜杠、引号、换行、null 和字段顺序。
- tombstone 参与 merge，不清理。
- 本地缺失与远端缺失取 union；无 syncUid/坏时间/未知字段使整个远端文档拒绝，不部分应用。

### 5.4 HTTP

- PROPFIND Depth:0；成功范围按 desktop 契约。
- GET 404 表示不存在；其他非 2xx 为受限错误。
- PUT：存在时 `If-Match: etag`；不存在时 `If-None-Match:*`。
- 412 最多重新 GET/merge/PUT 一次；第二次冲突失败。
- redirect 默认关闭，防止 Basic 凭据跨主机；若响应 redirect，显式错误。

### 5.5 2026-09-06 远端可见变化接线

`WebDavSyncSignals` 将 availability、mutationVersion 与默认空实现的 `onRemoteVisibleChanged: () -> Unit` 传入 runtime；runtime 把 callback 交给既有 `SyncCoordinator`，AppContainer 将其接到 Widget 的 `REMOTE_VISIBLE_CHANGE`。成功结果只有 localChanged 为 true 才通知；PUT 异常或 `SyncCancellationException` 只有 remoteVisibleChanged 为 true 才通知。runtime 捕获该取消例外后先设置有限 CANCELLED 状态，再重抛，让 coordinator 传播已 apply 的可见变化；普通异常不伪造通知。远端 apply 仍为单 Room 事务，零 EventService/本地 mutation，避免再次排队上传。close 后不再发起 Widget 请求。

## 6. 配置与 Keystore

`AppPreferences` 保存主题、字号、日历状态、活动 conversation、endpoint/model、WebDAV 非秘密字段、Widget config。秘密接口：

```kotlin
interface SecretStore {
  suspend fun put(alias: SecretAlias, value: CharArray)
  suspend fun get(alias: SecretAlias): CharArray?
  suspend fun delete(alias: SecretAlias)
}
```

- AndroidKeyStore AES/GCM/NoPadding、256 bit、随机 12-byte IV、128-bit tag。
- envelope version + Base64 IV/ciphertext；associated data 绑定 applicationId + alias + version。
- CharArray 使用后覆盖；不可避免的 String 生命周期不写日志。
- `KeyPermanentlyInvalidatedException`、坏 tag、坏 envelope 统一删除对应密文并返回未配置。
- `android:allowBackup=false`，cloud backup 与 device transfer 规则同时排除 DataStore/Room/keystore envelope；卸载删除全部本地数据。这是首版明确产品契约，不依赖 OEM 自动迁移。

### 6.1 Manifest 组件与权限表

| 组件/权限 | exported/策略 | 理由 |
|---|---|---|
| MainActivity | `exported=true`，仅 MAIN/LAUNCHER，无 BROWSABLE/data | 系统启动入口 |
| QuickAiActivity | `exported=false` | 仅显式内部/Widget PendingIntent |
| Widget config Activity | `exported=true`，唯一 `APPWIDGET_CONFIGURE` filter，无 category/data/BROWSABLE | Launcher 必须跨包调用；Activity 在 container 初始化前校验精确 action/data/正 ID 与平台 provider ownership，不信任 caller/referrer/sender |
| 平台 AppWidgetProvider receiver | `exported=true`，仅 `APPWIDGET_UPDATE` | Launcher 管理组件；P2A 已加入 source Manifest |
| Boot receiver | `exported=false`，只恢复本地 Widget 更新 | 重启恢复，不启动网络 |
| `INTERNET` | 应用允许 | AI/WebDAV HTTPS |
| `RECEIVE_BOOT_COMPLETED` | 应用允许 | 恢复 Widget 日期更新 |
| WorkManager 合并组件/普通权限 | 只允许锁定版本 2.11.2 的精确 merged-manifest allowlist | JobScheduler/约束追踪实现；不得据此启用周期网络或应用前台服务 |

应用源 Manifest 必须显式 `usesCleartextTraffic=false`、禁用 backup/device transfer、所有自有组件写 exported；禁止 BROWSABLE、debug CA、测试 runner/provider 和通知/精确闹钟/overlay/storage/calendar/location/camera/microphone 权限，也禁止应用自有 foreground service。最终 merged manifest 必须生成并冻结精确 allowlist：除上表自有组件/权限外，只接受锁定 WorkManager 2.11.2 注入且确为其内部调度所需的 provider/service/receiver 与普通权限；任何依赖升级造成的新增项都使门禁失败并要求人工复核。应用代码禁止周期网络、long-running/expedited foreground work 和 SystemForegroundService 主动路径。PendingIntent 必须 explicit、immutable、实例唯一 request key。

## 7. Compose UI

### 7.1 Navigation

routes：`calendar`、`events?date=`、`ai?conversation=`、`settings`、`about`、`event/{id}`、`event/new?date=`、`quick-ai`。深链仅使用内部 explicit Intent，不暴露 BROWSABLE URL。

`SavedStateHandle` 保存 destination、date、calendarMode、草稿 ID；敏感输入不保存到系统 saved state。Drawer 语义、44/48dp 触控目标、TalkBack label 完整。

### 7.2 Calendar

- Month 使用自有 grid，避免依赖第三方日历库；周起始按 locale 但测试中文/英文。
- Week/Day 由 LazyColumn 时间轴 + overlay Canvas/block composables；逻辑高度以 dp、字体以 sp，支持缩放。
- Canvas 视觉与可点击语义层分离；聚合块打开 bottom sheet 列表。
- 颜色达到对比度；重叠除颜色外使用纹理/图标语义。

### 7.3 Events

- 2026-09-06：日历/事项顶层且 childRoutes 为空时，TopAppBar 提供 `clender_create_event` 按钮，使用本地化 label 与至少 48dp 目标。点击重新检查当前 shell 状态并 push `AppRoute.NewEvent(selectedDate)`；复用真实表单、EventService 保存、dirty 放弃确认与内存草稿恢复，不新增写入旁路。其他目的地与子页面不显示该入口。
- 事项页按选中日期的相交事件排序；跨日标记实际开始/结束日期。
- 表单使用日期/时间选择器和文本字段；手工删除 AlertDialog 确认。
- 所有异常显示本地化、受限、可恢复错误，不暴露堆栈/正文。

### 7.4 AI/Settings

- AI drawer/list 与消息区在手机单列切换、宽屏自适应双栏；Thinking 默认折叠。
- 设置分应用、AI、WebDAV、Widget、安全/关于；模型获取使用当前表单保存成功后的值。
- 字号预览不影响 Widget 独立字号。

## 8. Widget

以下 P2A/P2B1/P2B2a 条目保留各阶段原始边界；其中“无点击”“普通冷启不初始化”“QuickAI/P2C 未开始”等限定不是本轮现状。2026-09-06 接续实现见第 8.1–8.3 节，最终状态由主任务 `doc/tasks/T47-T48-android-completion.md` 记录，QuickAI/P2C 已通过最终全 JVM，T48 的最终 release/设备验收仍进行中。

- `ClenderWidgetProvider : AppWidgetProvider` + `RemoteViews` + `AppWidgetProviderInfo XML`；主应用继续 Compose，Widget 不使用 Glance。
- **T47-P2A 闭环：** Provider → app-scoped `WidgetUpdateCoordinator` → `DataStoreWidgetConfigurationStore`/`EventRepository` → 已冻结 `WidgetStateBuilder`/`WidgetPresentationPolicy` → RemoteViews。Coordinator 只依赖抽象 ports，不依赖 AppWidgetManager/View；按正数 ID 去重排序，缺配置用 appearance widget 字号安全创建默认配置，使用配置窗口首个 Flow emission，8 秒上限和每实例 generation 防止迟到覆盖。
- API 26–30 根据当前 orientation 下的 options 受限 size bucket 选择单个小/中/大布局；API 31+ 精确使用 110×110、250×110、110×250、250×250 四个 `SizeF` key，分别映射 SMALL/MEDIUM/MEDIUM/LARGE。
- 每 widget ID 在 DataStore 保存 time range、opacity、font size、theme；删除先失效在途 generation，再精确清理实例配置。一个实例异常只生成有限 unavailable，不影响其他实例。
- 小/中/大分别最多 2/4/8 行，行只展示日期标题、时间摘要、标题和 CURRENT/NEXT/FUTURE；空态/错误态仍显示可读日期/有限状态，剩余内容显示精确 `+N`。
- P2A 所有 View 无点击。P2B1 只允许 header 的 Configure 点击：显式 `getActivity` 指向配置 Activity，package/action/canonical data/正 ID extra 精确，flags 仅 `IMMUTABLE|UPDATE_CURRENT`，requestCode 0；Edit/Quick AI/LocalRefresh 继续 fail closed。Widget 不泄露 description、同步 metadata、身份、路径或异常正文。
- Renderer 只使用标准 RemoteViews View 和资源化中英文文本；字号 8–20sp，SYSTEM 从 `uiMode` 读取，opacity 只作用背景。初始/preview layout 不包含用户内容。
- Provider 只处理 `onUpdate`、`onAppWidgetOptionsChanged`、`onDeleted`；所有回调 `goAsync()` 并在 `finally` 恰好 finish，未覆盖自定义 `onReceive`。普通 MainActivity 冷启不初始化 Widget runtime；首更不初始化 AI/Keystore/WebDAV/network。
- source Manifest 在 P2A receiver 外，P2B1 只新增 exported `.widget.WidgetConfigurationActivity`，唯一 `APPWIDGET_CONFIGURE` filter、现有主题与 `excludeFromRecents=true`；无 category/data/BROWSABLE/permission/launchMode/taskAffinity/process。provider-info 只新增 configure 与 `reconfigurable|configuration_optional`，其余 P2A 字段不变。
- 日期边界使用 WorkManager `2.11.2` 非精确 one-time 调度并在 BOOT_COMPLETED 后恢复 Widget 更新；不使用 periodic、expedited、long-running 或 foreground work，不启动 UI/网络。Boot receiver 非 exported。
- P2B1 配置状态固定为 Loading/Content/LoadFailed/SaveFailed，错误只允许 INVALID_TIME_RANGE/LOAD_FAILED/SAVE_FAILED；缺配置只建内存 defaults，dirty cancel/back 要显式放弃，save 单次 upsert 后请求目标本地更新，更新失败不回滚持久化。草稿可跨 recreate 保存但只含非秘密配置，不进入 AppShell SavedStateHandle。
- AppContainer 的配置 port 复用现有 DataStore store、appearance 与 P2A runtime；load 不开 Room/AI/WebDAV/Keystore，save 后 update 才惰性查询 Room；不创建第二 DataStore/database/coordinator，close 顺序保持确定性。
- T47-P0R 锁定架构/依赖 producer graph；T47-P1、P2A、P2B1/P2B1R 与 P2B2a 已完成。P2B2a 的 `WidgetActionIntentContract` 对 EditEvent/LocalRefresh 精确验证 action/component/package/canonical data/extras/provider ownership；`MainActivity` 只建立既有 `EVENTS + EventDetail`，非导出 Receiver 在验证后以 finish-once/9 秒上限消费 `MANUAL_LOCAL_REFRESH` 并复用 app-scoped coordinator 只重建目标。Quick AI/P2B2b、P2C mutation/remote/foreground/date-boundary/Worker/Boot 与 T48 均保持未开始。

### 8.1 2026-09-06 QuickAI 入口

LARGE/250×250 RemoteViews 的 QuickAI 使用现有显式 getActivity 创建点，PendingIntent 仍为 requestCode 0、`IMMUTABLE|UPDATE_CURRENT`，canonical data 区分实例。非导出 QuickAiActivity 在访问 container 前验证 action/component/package/data、唯一正 Int widget ID extra、平台 provider ownership 及空 selector/clip/categories；非法冷启动结束，非法热 Intent 不替换当前 Intent/草稿。

Activity 原始 `CLEAR_TOP|SINGLE_TOP` 为 `0x24000000`；真实 API 26 Launcher 交付时系统依据 Manifest `excludeFromRecents=true` 追加 `FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS`，得到 `0x24800000`。上述为首次 API 26 定位证据；后续 Launcher NEW_TASK 交付回归已补齐。当前 Intent 交付契约：创建端仍为 CLEAR_TOP|SINGLE_TOP（0x24000000）。仅 EditEvent/QuickAI 消费 externalTaskFlags：NEW_TASK（0x10000000）可选，只有存在 NEW_TASK 时才允许附加 BROUGHT_TO_FRONT（0x00400000）；单独 BROUGHT_TO_FRONT 拒绝。QuickAI 的 EXCLUDE_FROM_RECENTS（0x00800000）独立可选。EditEvent 合法 flags 为 0x24000000/0x34000000/0x34400000；QuickAI 为这三种各自可选 EXCLUDE_FROM_RECENTS，另有 0x24800000/0x34800000/0x34c00000。内部导航真实交付仍为 0x24000000，不扩展；Configure/LocalRefresh 及 action/component/package、canonical data、唯一 appWidgetId、平台 owner 等边界不变。

真实 JDB 证据：QuickAI 交付 0x34c00000、EditEvent 交付 0x34400000，均比 ActivityTaskManager 日志多 BROUGHT_TO_FRONT；唯一 appWidgetId、canonical identity 与 owner 正常，仅去除此位同一 Intent 即校验成功。tests-first RED 为 QuickAI 58 tests/8 failures、EditEvent 30 tests/2 failures；完整证据见主任务。输入复用既有活动会话与 app-scoped AI gateway，草稿只在 ViewModel 内存，Intent/SavedState 不携带正文或秘密；打开页面不自动提交，旋转与后台取消继续遵循既有 AI 生命周期契约。

### 8.2 2026-09-06 自动本地刷新

`WidgetAutomaticRefreshRuntime` 使用注入的现有 Widget scope 及其子 Job，复用 store、`WidgetUpdateCoordinator` 与 Room repository。七个 trigger 为 LOCAL_MUTATION、REMOTE_VISIBLE_CHANGE、CONFIGURATION_CHANGE、FOREGROUND、MANUAL_LOCAL_REFRESH、DATE_BOUNDARY、BOOT；只有配置/手动刷新要求正 target ID，其余禁止 target。目标以 AppWidgetManager 归属 ID 为准，不把 DataStore registry 当作存活实例来源。

本地 mutation 观察忽略初始 emission；远端变化走第 5.5 节 callback；配置保存后目标重建；ProcessLifecycle 前台/冷启动仅在已有 runtime 或存在自有 Widget 时恢复。请求合并并返回可等待 receipt，等待者取消不取消 runtime 的共享工作。无实例时不查询 Room/配置/appearance，并可取消遗留日期 work。删除先使 generation/在途请求失效再清配置；最后实例删除后取消日期 work。AppContainer 先关闭 lifecycle/runtime/coordinator 并取消、等待 Widget scope，再关闭 DataStore/Room。

`WorkManagerWidgetDateScheduler` 只接受唯一 `clender-widget-date-boundary`、app-wide、one-time、replaceExisting、正延时计划；使用 `OneTimeWorkRequestBuilder<WidgetDateBoundaryWorker>` 与 `ExistingWorkPolicy.REPLACE`，等待 enqueue/cancel Operation 提交。日期边界按本地日期/时区尽力恢复并安排后继，普通 mutation 不持续推迟同一午夜计划。Worker 只接受空 inputData，请求本地 DATE_BOUNDARY；有限失败返回 failure，不 retry；取消继续传播，等待者退出不取消共享 runtime 的后继安排。禁止 periodic、expedited、foreground/long-running、精确 alarm、通知、AI/WebDAV 或其他后台网络。

### 8.3 2026-09-06 Boot 边界与验收状态

`WidgetBootReceiver` 非 exported，只接受 `ACTION_BOOT_COMPLETED`；extras 为空或精确单项 `android.intent.extra.user_handle` 且为非负 Int。data/type/selector/clipData 必须为空，categories 为空；错误类型、负值和其他 extras 拒绝。校验在 goAsync/owner 访问前；合法广播在现有 scope 内以 9 秒上限等待 BOOT receipt，所有退出路径由 AtomicBoolean 保证 pending result 恰好 finish。此入口只恢复本地 Widget，不启动 UI/网络。

回归覆盖须保留系统追加 flags 与其他位拒绝、真实新建按钮保存/取消/recreate、远端 visible true/false 的成功/PUT异常/取消、零本地 mutation、无实例、删除/close/receipt 取消、午夜/时区与合法系统 user_handle。上述为当前生产接线契约；最终源码全 JVM 门禁已通过，主任务继续最终 release/设备验收。既有签名 APK/AAB audit 通过不替代最终 P2C 产物、完整测试和 release 设备最低矩阵，不标 T47/T48 PASS。

### 8.4 最终源码基线与 UI 集成

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

历史 checkpoint 的“未开始”“QuickAI fail-closed”“无 Worker/Boot”与旧测试数量只描述各自阶段，不是当前基线。保留历史 Room/CalendarOverflow 一次 teardown 失败及 NON-REPRODUCIBLE 裁决；后续全量绿色不构成根因修复证明，不得宣称已修复该历史失败。

QuickAI/P2C 与 Drawer 修复已通过同源码全 JVM；最终签名产物审计与 verify-all 全门禁通过，剩余设备与交付收口仍进行中，不标 T48 完成。独立签名材料已生成并保持忽略，脚本不回显密码/alias，用户须安全备份 key 与配置；当前 APK/AAB 哈希见本轮最新证据，最终证书指纹由主 Agent 补录。

## 9. 错误处理与日志

- 领域错误使用 sealed type；UI 映射为本地化消息。
- 日志仅记录类别、状态码、request ID/计数，不记录 URL query、Authorization、秘密、AI 上下文、事件标题/描述、远端正文。
- release 移除 verbose/debug 日志；未捕获异常不上传第三方服务。
- 所有 coroutine 有 supervisor/handler，取消不当成错误。

## 10. 测试设计

### 10.1 JVM/Property

- Event validation：正常、空白/Unicode/长标题、两类型、跨日、边界、转换、坏 patch。
- Calendar：空、全天边界、跨日裁剪、2/3/N lane、相邻、marker、overflow、确定性、坏数据。
- AI：budget、截断、JSON 变体、未知/危险 action、ID/字段/长度、类型转换、plain reply。
- Sync：normalize、round-trip、union、LWW、tombstone、同时间戳、上限、坏 schema。
- Widget state：current/next、零时长、并列、午夜、配置范围。

### 10.2 Room/Robolectric

- 空 DB、CRUD、Flow、索引、UUID、事务 rollback、墓碑过滤、跨日 overlap、conversation cascade。
- schema export 与 migration harness；禁止 destructive migration。
- DataStore defaults/坏值、Keystore fake 的加密/解密/失效/无日志。

### 10.3 Network

- AI models/chat 200、400/401/429/5xx、timeout、disconnect、oversize、Thinking 400/422 fallback、旋转保持、后台取消。
- WebDAV PROPFIND/GET/PUT/404/412、ETag、redirect、Content-Length/streaming 上限、畸形 JSON、Basic header 存在但不打印；覆盖目录 URL 有/无 `/`、空格/中文/percent path、端口、IPv6、重复 `/`、`..` 和同/跨主机 redirect 均拒绝且不转发 Authorization。Basic 对 Unicode 用户名/密码明确使用 RFC 7617 UTF-8 charset 参数/编码策略并有 Latin-1 边界测试。
- TLS URL validation、userInfo/query/fragment、host/path、error length。

### 10.4 UI/Widget

- Drawer destinations、返回、日期联动、CRUD、跨日、对话管理、Thinking、settings validation。
- 状态恢复、Light/Dark/System、8/20sp、200% font、中文/英文、phone/tablet semantics。
- 平台 Widget 添加/删除/多实例/三尺寸/主题/opacity/action/午夜/进程杀死/boot/Doze；API 26–30 size bucket 与 API 31+ responsive/exact mapping 分开覆盖。

### 10.5 构建/设备

- `testDebugUnitTest`、`lintRelease`、detekt、ktlint、dependency verification、assemble/bundle。
- managed/emulator API 26/29/31/33/35/36 phone；API 33/36 tablet。
- APK：apksigner、zipalign、aapt2 badging、manifest、R8、无 native/secret/cleartext/debuggable/testOnly。
- debug/test variant 安装后运行 mock AI/WebDAV 功能矩阵；交付 release APK只运行无配置/离线安全失败、生产装配冷启、空库、CRUD、导航和 Widget 冒烟，并审计无 fake transport/test CA/test hook。
- 至少 API 26 phone、API 36 phone、API 36 tablet 的 release 安装/冷启/核心冒烟不可自动豁免；其余矩阵失败也必须记录并由用户明确决定是否豁免。
- AAB 运行 `bundletool validate`；APK 运行 `apksigner verify --min-sdk-version 26`。可复现指命令、版本、锁文件和输入可追溯，不承诺 APK 字节级完全相同。

## 11. 隔离与回滚

- Android 所有运行数据在 app sandbox；本地工具链在忽略目录。
- 每个任务可通过删除对应 package/Gradle声明和测试回滚；WebDAV schema 不做远端迁移。
- release keystore 不纳入自动清理；回滚工程前先提示用户备份。
- Windows 最终回归失败时不得推送，先确认是否为 Android 改动引起的根构建/CI影响。

## 12. 实施顺序

1. T42 工具链、Gradle、安全/CI基础与红灯 harness。
2. T43 领域、Room、日历纯逻辑。
3. T44 WebDAV；T45 AI 可在 T43 基础稳定后并行。
4. T46 Compose 主应用。
5. T47 Widget/快速入口。
6. T48 全矩阵、签名、APK/AAB、Windows 回归、文档、提交与 Gitee 推送。

每个生产任务必须先提交聚焦测试并取得缺失/失败证据，再收到实现 follow-up；子 Agent 不更新共享 progress 或根 AGENTS。
