# T47-P2B1：Widget 配置 Activity 与唯一 Configure PendingIntent

## 状态

进行中（2026-09-02）。P2A 已完成；本任务只交付 P2B1。P2B2、P2C、T47 整体与 T48 不因本任务完成。

## 目标

在 P2A 只读 Launcher Widget 基础上实现安全、可重配置的配置闭环：非秘密草稿加载/校验/保存/dirty/cancel，Compose `WidgetConfigurationActivity`，Launcher `ACTION_APPWIDGET_CONFIGURE` 接线，API 31+ optional/reconfigurable provider-info，以及所有尺寸唯一获准的显式 immutable Configure PendingIntent。保存成功后只请求目标实例本地重建。

## 非目标

- 不实现 EditEvent、QuickAi、LocalRefresh 的 PendingIntent、Activity 或路由。
- 不实现 Worker、BootReceiver、WorkManager enqueue、mutation/remote/foreground/date-boundary 自动触发。
- 不改 Room/DAO/schema/migration、EventService、AI/WebDAV、主 Settings、MainActivity 导航或 Windows 功能。
- 不新增依赖，不改六个冻结 Gradle/catalog/lock/verification 文件，不引入 Glance、DataStore Android、native 或 Google 服务。

## 输入与恢复基线

- `AGENTS.md`、`android/AGENTS.md`、Android 高层/详细设计的 Manifest、Widget、安全段落。
- `T47-android-widget.md`、P1 foundation、P2A provider/rendering 任务。
- 冻结 P1 `WidgetConfiguration`、`DataStoreWidgetConfigurationStore`、`WidgetActionSpec`；P2A `WidgetUpdateCoordinator`、Provider、renderer/layout、AppContainer 生命周期。
- 恢复现场精确匹配 `codex/android-architecture @ 4537c7cd69ae91fdf31c82a643b4a2998c9f05f8`，subject `Implement Android T47 read-only widget provider`，working tree clean、staged empty、无 upstream/push。
- 修改前完整 Android JVM：112 suites/1111 tests，UI 50/567，P1 6/53，P2A 7/78，OkHttp 16/16，0 failure/error/skip，四类 CloseGuard 0。
- 六冻结构建文件、source Manifest 与 provider-info SHA-256 均与用户给定基线精确一致。

## 影响文件与 Agent 边界

### Agent A：配置 application/ViewModel（独占）

- `app/widget/WidgetConfigurationApplicationService.kt`
- `ui/widget/WidgetConfigurationViewModel.kt` 及 draft/state/effect/error 类型
- 对应纯 JVM/Robolectric tests

不得修改 Activity、Manifest、renderer/layout、资源或 AppContainer。

### Agent B：Compose Activity/UI（独占）

- `widget/WidgetConfigurationActivity.kt`
- `ui/widget/WidgetConfigurationScreen.kt` 与 Widget 专用 picker/确认组件
- 中英文配置字符串
- Activity/Compose/API 26/36 tests

不得修改 Manifest、renderer/layout、provider-info 或 AppContainer。

### Agent C：Configure 平台 action/安全（独占）

- `widget/WidgetPendingIntentFactory.kt`
- `WidgetRemoteViewsRenderer.kt` Configure action 接线
- 三个 Widget layout 的统一配置操作区
- `clender_widget_info.xml`、source Manifest
- PendingIntent/resource/source+merged Manifest/security policy tests

不得修改 ViewModel、配置 screen 或 AppContainer。

### 主 Agent（独占）

- `AppContainer`/`ClenderApplication` 最小生产装配、Activity ownership/runtime port
- 跨层 production integration tests
- 共享任务/设计/progress/AGENTS、diff 审查、完整验证与提交

第一阶段三个 Agent 只能写 tests-only。主 Agent 仅在确认组合红灯完全来自计划中的 P2B1 类型、资源、Activity、Manifest 和 PendingIntent 缺失后，才允许原 Agent 在原独占范围实现。

## 配置数据与状态机

继续使用冻结 `WidgetConfiguration`：正 appWidgetId；start/end 分钟精度且 `start < end`；opacity `0..100`；font `8..20sp`；theme `SYSTEM/LIGHT/DARK`；默认 `08:00–22:00`、100%、SYSTEM、合法现有 widget font，否则 13sp。不得改变 P1 wire keys、registry、单 edit 或 fail-closed 契约。

`WidgetConfigurationDraft` 只含上述非秘密字段，不含事件正文、AI/WebDAV、秘密/envelope、Room/同步状态、Intent/caller/package 或异常正文。

有限状态固定为：

- `Loading`
- `Content(baseline, draft, dirty, saving, validation)`
- `LoadFailed`
- `SaveFailed`

错误码只允许 `INVALID_TIME_RANGE`、`LOAD_FAILED`、`SAVE_FAILED`，支持 retry，不保存 Throwable/message/path。

状态转移：

1. load 已有配置精确加载；缺配置只在内存生成 defaults，不立即写 DataStore。
2. picker 归零秒/纳秒；UI 限制范围，ViewModel 对注入坏值仍 fail closed。
3. save 先构造并验证配置，operation guard 拒绝重复；成功仅一次 `upsert`，然后请求目标实例本地重建。
4. store 失败不返回 RESULT_OK、不更新 Widget、保留 draft/dirty；重建有限失败不回滚已持久化配置且不暴露异常。
5. 成功发一次性 `Complete(appWidgetId)` effect；旋转/重建保留非秘密 draft/dirty，不改变 AppShell SavedStateHandle 四字段。
6. saving 期间 Save/Cancel/Back 都不可重复触发；结束后恢复。
7. clean cancel/back 直接取消；dirty 必须显式确认放弃；取消绝不 upsert/delete，既有 wire bytes 不变。

## Activity 入口与结果安全契约

`WidgetConfigurationActivity : ComponentActivity` 在访问 AppContainer/DataStore 前必须完成全部验证：

- action 精确为 `AppWidgetManager.ACTION_APPWIDGET_CONFIGURE`。
- `EXTRA_APPWIDGET_ID` 为正 Int。
- data 为 null，或精确等于 `clender-internal://widget/{id}/configure`；拒绝 query、fragment、HTTP(S)、额外 route 或配置 payload。
- `AppWidgetManager.getAppWidgetInfo(id)?.provider == ComponentName(this, ClenderWidgetProvider::class.java)`；异常、缺失或错误归属均拒绝。

不使用 callingPackage、referrer、PendingIntent creator/sender 做授权。无效入口不得初始化 container/DataStore/Room/AI/WebDAV/Keystore；返回 `RESULT_CANCELED + INVALID_APPWIDGET_ID` 并立即 finish。有效入口 onCreate 先设默认 `RESULT_CANCELED + 原 id`；保存成功才返回 `RESULT_OK`，result intent 只含 `EXTRA_APPWIDGET_ID`，不含配置、identity 或其他 extra。

source Manifest 精确新增一个 Activity：`.widget.WidgetConfigurationActivity`、`exported=true`、`enabled=true`、`excludeFromRecents=true`、现有 `Theme.Clender`，唯一 filter action 为 `android.appwidget.action.APPWIDGET_CONFIGURE`；无 category/BROWSABLE/data/permission/launchMode/taskAffinity/directBootAware/process 或新增权限。`exported=true` 是 Launcher 跨包调用的系统要求；安全边界由精确 action/data、正 ID、平台实例归属、无自动保存、无秘密输入和有限结果共同构成。

## Compose UI 契约

- 本地化标题、start/end time、opacity 0–100、widget font 8–20sp、SYSTEM/LIGHT/DARK 单选、Save/Cancel、有限 validation/load/save 状态、dirty 放弃确认。
- 使用现有 `ClenderTheme` 和只读 appearance；Widget theme draft 不写应用 theme。
- 使用现有 Material3 TimePicker 模式，按 locale/系统 12/24h 展示，持久化为分钟 `LocalTime`。
- 表单可滚动；覆盖 360/600/840dp、横竖屏、应用字号 8/20sp、200% font scale；关键目标 ≥48dp。
- loading/error 使用 polite liveRegion；radio/slider/button 具正确 role/stateDescription/contentDescription 与稳定 testTag。
- 不显示事件/真实预览，不读 Room/AI/WebDAV/secret，不联网，不新增 navigation destination 或接 Drawer/MainActivity。

稳定 testTag：`widget_config_start_time`、`widget_config_end_time`、`widget_config_opacity`、`widget_config_font_size`、`widget_config_theme_system`、`widget_config_theme_light`、`widget_config_theme_dark`、`widget_config_save`、`widget_config_cancel`、`widget_config_discard_dialog`、`widget_config_status`。

## ProviderInfo 与唯一 PendingIntent

`clender_widget_info.xml` 只新增：

- `android:configure="com.molotov.clender.widget.WidgetConfigurationActivity"`
- `android:widgetFeatures="reconfigurable|configuration_optional"`

其余 P2A size/layout/description/resize/category/updatePeriod 必须逐字保持。API 31+ 可跳过初始配置并长按重配；API 26–30 可在添加时启动配置；自定义 Configure 控件覆盖所有支持版本。取消不删配置，删除仍只由 Provider.onDeleted 清理。

本轮唯一允许创建的平台 PendingIntent 是 `WidgetActionSpec.Configure(appWidgetId)`：

- `PendingIntent.getActivity`；显式 component 指向配置 Activity；package 明确为本应用。
- action=`ACTION_APPWIDGET_CONFIGURE`，data=canonical identity，extra 仅 `EXTRA_APPWIDGET_ID`。
- flags 精确 `FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT`；requestCode 固定 0；唯一性由 component + canonical data 保证，可重复打开。
- 非正 ID 在创建平台对象前拒绝；EditEvent/QuickAi/LocalRefresh 必须 unsupported/fail closed。
- 不携带事件、配置、conversation、Key、密码或异常。

## RemoteViews 与生产装配

三个 layout 的 header 增加统一 `widget_configure` 控件，本地化文本/语义、minWidth/minHeight ≥48dp，SMALL 110×110 仍可读且不遮挡日期/状态/列表。renderer 为每个 appWidgetId 绑定 Configure PendingIntent；API 31+ 四个 responsive mapping 全部绑定同一正确实例。event row、date、root、status、more 均无点击；preview/initial layout 不带 PendingIntent 或真实 ID。

新增最小配置 runtime port：`loadConfiguration(id)`、`saveConfiguration(configuration)`、`requestWidgetUpdate(id)` 与只读 appearance Flow。AppContainer 复用现有 DataStore store 和 P2A runtime/coordinator；load 只初始化 DataStore/appearance，不开 Room/AI/WebDAV/Keystore，save 后本地 update 才可沿 P2A 路径惰性查询 Room；不创建第二 DataStore/database/coordinator，不改变 AI/WebDAV close 顺序，配置 scope 确定性释放。

## TEST-FIRST 矩阵

| 所有者 | 正常/边界 | 非法/异常/回归 |
|---|---|---|
| Agent A | existing/missing/default；08:00–22:00/边界；dirty/恢复；单次 upsert；rotation/recreate | start=end/>、秒纳秒、opacity/font 越界；load/save/update failure；重复 guard；cancel/discard 零写；无异常正文 |
| Agent B | API 26/36；initial/reconfigure；zh/en、8/20sp、200%、360/600/840dp；picker/0/100/8/20/三 theme | invalid/missing/wrong owner；wrong action/bad data/query/fragment；精确 result；无效入口零 container；dirty Back/Cancel；loading/error/retry/saving；≥48dp/role/state/liveRegion/tag |
| Agent C | canonical round-trip；PI component/package/action/data/extra/flags；实例唯一与重复创建；三尺寸和四 map action | 非正 ID；非 Configure unsupported；root/date/status/event row 无点击；Manifest/activity/filter/provider-info 精确；无权限/BROWSABLE/data/QuickAI/Boot/Worker |
| 主 Agent | Activity→port→单 edit→Provider update→RemoteViews；目标更新、其他不变；cold MainActivity lazy | initial cancel 无配置；reconfigure cancel wire bytes 不变；invalid exported entry 零副作用；load 不开 Room/AI/WebDAV/Keystore；close/CloseGuard 0 |

不得 ignored、假断言、sleep、GC、重试、顺序依赖、延长超时或过滤测试。

## 实施步骤

1. 恢复/哈希/未推送检查并运行 112/1111 修改前门禁。
2. 文档冻结本任务及两层设计、T47/progress。
3. 三 Agent 仅写互斥 tests-only 红灯；主 Agent 审查失败归因。
4. 原 Agent 在各自独占范围实现并跑聚焦验证；主 Agent完成 AppContainer/ownership port/跨层测试与集成审计。
5. 执行 P2B1 聚焦、完整 Android 96-task gate、policy/generated/boundary/85 universe/冻结哈希/Manifest/APK 审计。
6. 执行 Windows 171/171、30 imports、build check、完整 PyInstaller、双向 EXE 冒烟与 dist/data 只读完整性核对。
7. 更新任务/设计/AGENTS，diff/secret/staged 白名单审查，创建单一提交 `Implement Android T47 widget configuration UI`，不 push，提交后立即停止。

## 风险、回滚与 STOP

风险：Launcher/OEM 配置调用差异、exported Activity 入口滥用、PendingIntent token 串实例、SavedState 非秘密草稿恢复、RemoteViews 小尺寸可读性、DataStore/Room 惰性与关闭竞态。

回滚只允许逐行补丁删除本轮新增配置 Activity/UI/application service/PendingIntent/测试/资源/Manifest/provider-info delta，并恢复文档状态；不得 reset/checkout/stash/clean，不得触碰用户数据、dist/data、冻结构建文件或 P0R/P1/P2A checkpoint。

立即 STOP-ESCALATE：修改前基线/CloseGuard 不绿；需要依赖/Gradle/lock/metadata变化；Activity 不能 exported=true 或不能验证实例归属；需要信任 caller/referrer/sender；Manifest 超出唯一获准 Activity；需要实现其他 action/Worker/Boot；PendingIntent 不能 explicit+immutable；需要修改 P1 wire/state/action；85 universe/producer allowlist/冻结哈希漂移；出现 Glance/DataStore Android/native/Google；Android、Windows、PyInstaller、EXE 或 dist/data 任一失败；出现新的产品、隐私、破坏性或 Launcher/OEM 语义歧义。

## 完成定义

- [x] 三 Agent tests-only 红灯证据、原 Agent实现和聚焦验证完整。
- [x] 配置状态机、入口归属、dirty/cancel、单 edit/save→目标 update 与有限结果契约成立。
- [x] Manifest/provider-info/唯一 Configure PendingIntent/三尺寸和四 responsive mapping 精确，无其他点击/action/组件/权限。
- [x] AppContainer 惰性和关闭顺序成立，load 零 Room/AI/WebDAV/Keystore，save 后才沿 P2A 更新。
- [x] Android/Windows/PyInstaller/EXE/dist_data/冻结依赖与安全审计全部通过。
- [x] 文档/AGENTS 已完成并进入指定提交；P2B2、P2C、T47、T48 明确保持未完成。

## 完成证据（2026-09-02）

- 三个独立 Agent 先只写测试：A 负责 application service/ViewModel，B 负责 Activity/Compose，C 负责 PendingIntent/RemoteViews/Manifest；组合红灯只缺计划内 P2B1 类型、资源、Activity、Manifest 和平台 action。主 Agent审查后才允许原 Agent在互斥写集实现，主 Agent仅完成 AppContainer/ClenderApplication 与跨层装配。
- P2B1 聚焦为 5 suites/67 instances；完整 Android 为 117 suites/1185 tests，UI 52/607、P1 6/53、P2A-family 7/85、OkHttp 16/16，0 failure/error/skip，SQLite/Room CloseGuard 四类均为 0。P2A-family 仅因 renderer/provider-info/assembly 增加 P2B1 断言而从 78 增至 85，冻结语义未改变。
- strict/offline、单 worker、`--rerun-tasks` 96-task gate BUILD SUCCESSFUL；lintDebug/lintRelease/detekt/ktlint、release signing、无 Google/native、版本锁、assembleDebug/APK no-native 全绿。首次 lint 仅发现 `Uri.parse` KTX 建议，改用 `toUri()` 后完整门禁通过；未禁用规则或放宽测试。
- `verify-generated.ps1` 与 `verify-boundaries.ps1` 各 44/44；两项新增平台安全断言合并进既有 policy 方法，覆盖不减且冻结计数不变。85 configuration universe、WorkManager producer allowlist 与六冻结构建文件 SHA-256 完全不变。
- source/debug/release Manifest 只增加获准的 exported 配置 Activity；provider-info 只增加 configure/features；APK 只在既有 MainActivity、P2A Provider 与 WorkManager producer 组件外增加配置 Activity，无 QuickAI/Boot/自有 Worker/Service、Glance/DataStore Android/native/Google。
- Windows Miniconda Python 3.12.4：171/171 unittest、30/30 production imports、`build.py --check`、完整 PyInstaller 和 normal→silent / silent→normal 双向隔离 EXE 冒烟通过。最终 `dist/Clender.exe` 为 45,582,023 bytes，SHA-256 `29F4A021AEBFD22C845B29A6CF634CE9AA1FD8C64AF36E554D4B15E379CA9E74`；secondary 均 exit 0，最终 0 Clender 进程/0 冒烟目录。
- `dist/data` 构建/冒烟前后均为 5 files/195,620 bytes，相对路径、大小、UTC 时间和逐文件 SHA-256 完全一致，冻结组合摘要 `E43733909427A268005A9514FB136CDA0F74C43395046269E73EF20E539668D9`；未打开、解析、复制或修改真实正文。
