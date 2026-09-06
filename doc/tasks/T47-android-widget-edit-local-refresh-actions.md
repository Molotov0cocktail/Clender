# T47-P2B2a：Launcher Widget 事项入口与本地刷新 action

## 状态与恢复证据

已完成实现与发布验证，待创建唯一提交（2026-09-02）。本轮只实现 `EditEvent` 与 `LocalRefresh`；`QuickAi` 继续 fail-closed，留给独立 T47-P2B2b。P2C、T47 整体与 T48 均不在本轮完成。

- branch `codex/android-architecture`，HEAD `bbdd776fce503e86ebc64e44302add9d44a15d52`，subject `Repair Android T47 configure action production binding`。
- 初始 working tree clean、staged empty、无 upstream；不得 stash/reset/checkout/clean/push。
- 六个冻结构建文件、source Manifest `DE114772…CFEAD` 与 provider-info `7EC78BF3…4FD2` 的 SHA-256 精确匹配任务输入。
- 修改前 strict/offline 单 worker强制 rerun：117 suites/1187 tests、UI 52/607、0 failure/error/skip；SQLite/Room CloseGuard 四类扫描均为 0。

## 目标与非目标

### 目标

- 所有实际可见且 event ID 为 `1..Int.MAX_VALUE` 的 Widget 事项行点击后，冷/热启动主应用并唯一导航至 `EVENTS + AppRoute.EventDetail(eventId)`。
- 新增非导出 `WidgetLocalRefreshReceiver`；严格验证入口后消费 `WidgetRefreshPolicy.MANUAL_LOCAL_REFRESH`，只用既有 app-scoped Widget scope/store/coordinator/repository/renderer 重建目标实例。
- 扩展唯一 `WidgetPendingIntentFactory`、RemoteViews 与资源，使 Configure 保持不变、EditEvent/LocalRefresh 可用、QuickAi 继续 `UnsupportedOperationException`。

### 非目标

- 不自动调用 `EventCrudViewModel.startEdit()`，不新增 EventEdit route，不改变 T46 route 字符串、CRUD、dirty/back 或 SavedStateHandle 四字段契约。
- 不实现 QuickAiActivity/P2B2b、P2C 自动触发/Worker/Boot、T48；不增加网络、WebDAV、AI、EventService/DAO 写入、mutation、WorkManager enqueue、通知或 Alarm。
- 不新增依赖、权限、schema/migration、Glance/DataStore Android/Google/native，不改变固定 2/4/8 容量、API 31+ 四项 responsive mapping 或 provider-info。

## 冻结产品、路由与平台契约

### EditEvent 冷/热启动

- Widget action 只导航，不查询/写事件。不存在或已删除事件由既有 `EventCrudViewModel` 详情有限 `NOT_FOUND` 处理。
- `MainActivity.onCreate` 与 `onNewIntent` 使用同一最小 validator；合法输入均产生唯一 `destination=EVENTS`、`childRoutes=[EventDetail(eventId)]`。重复 action 不堆叠。
- `onNewIntent` 必须先 `super.onNewIntent(intent)`，接受后才 `setIntent(intent)`；非法新 Intent 完全保留当前 destination、child route、dirty/draft 状态。HTTP(S)、ACTION_VIEW/BROWSABLE 与普通 AppRoute URI 不得成为内部入口。
- Activity recreate 仍只恢复既有四个 SavedState 字段；Widget route 可从当前合法 Intent 重新安全建立，不把 childRoutes 加入 SavedStateHandle。

### LocalRefresh

- 新组件 `.widget.WidgetLocalRefreshReceiver`：`enabled=true`、`exported=false`、无 intent-filter/permission/metadata/process。不得把 custom action 加入已导出的 `ClenderWidgetProvider`。
- 同步验证必须发生在访问 `ClenderApplication.container`/runtime 前；合法后才 `goAsync()` 并投递既有 app-scoped Widget scope。
- `WidgetRefreshPolicy.plan(MANUAL_LOCAL_REFRESH, ..., targetWidgetId=...)` 必须被实际消费，计划只含目标实例；合法但未配置的 optional Widget 继续走 P2A 默认配置路径。
- 继续使用 coordinator 的 8 秒查询上限与 generation/latest-wins/delete invalidation；Receiver 在平台约 10 秒内完成或取消。owner 缺失、scope 关闭、launch 异常、取消、timeout/runtime 异常均静默且 `finish()` 恰好一次。
- 不创建第二 DataStore、Room、scope、coordinator 或 Executor；不使用 runBlocking/Thread.sleep/GlobalScope；不触发网络、AI、WebDAV、EventService、mutation、WorkManager、Toast 或通知。

## PendingIntent 与共享 validator 精确契约

- 所有 action：requestCode `0`；flags 精确 `FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT`；显式 component/action/package/canonical data；可重复、不 one-shot/mutable/fill-in/template。
- extras keySet 精确仅 `AppWidgetManager.EXTRA_APPWIDGET_ID`；eventId 只来自 canonical data。selector/clipData/categories 为空；无标题、描述、配置、conversation、Key、密码或异常。
- EditEvent：`getActivity` → `MainActivity`；action `com.molotov.clender.action.WIDGET_EDIT_EVENT`；data `clender-internal://widget/{widgetId}/edit/{eventId}`；Activity flags 精确 `CLEAR_TOP|SINGLE_TOP`；Manifest launchMode 不变。
- LocalRefresh：`getBroadcast` → `WidgetLocalRefreshReceiver`；action `com.molotov.clender.action.WIDGET_LOCAL_REFRESH`；data `clender-internal://widget/{widgetId}/refresh`；无 foreground broadcast flag。
- Configure 的当前 component/action/data/extras/flags 行为逐字段不变；QuickAi 继续 fail-closed。相同 action/实例复用 token，不同 widget/event/action identity 不串用。
- 最小共享 validator 必须逐项验证 exact action/component/package/canonical data、parsed action type、data widgetId 与唯一 extra、正 Int widgetId/eventId、精确 extras keySet、空 selector/clipData/categories，以及 `AppWidgetManager.getAppWidgetInfo(id)?.provider == ClenderWidgetProvider`。不信任 caller/referrer/creator package；拒绝未知、截断、溢出、query/fragment、编码别名、BROWSABLE 和 HTTP(S)。

## Action surface、资源与 Manifest allowlist

- Configure 继续存在于 SMALL/MEDIUM/LARGE 与 API 31+ 全四 mapping，目标 ≥48dp。
- 所有实际可见合法事项行绑定 EditEvent；整个 `widget_event_row` 是唯一点击目标，新增稳定资源 ID 和本地化 content description，时间/标题/状态子 View 不单独绑定。
- 保持紧凑行与 2/4/8 容量，不虚报事项行达到 48dp。
- LocalRefresh 只在 LARGE layout 和 API 31+ `250×250` mapping 显示独立 ≥48dp 控件；SMALL、MEDIUM、`110×250` 不显示。LARGE 的 CONTENT/EMPTY/UNAVAILABLE 均可刷新。
- preview/initial/no-ID renderer 不绑定 Edit/Refresh 真实实例 action。
- source Manifest 唯一允许 delta 是上述非导出 Receiver。provider-info 和六冻结构建文件必须逐字节不变；最终平台创建点精确为一个共享 `getActivity`（Configure+EditEvent）和一个 `getBroadcast`（LocalRefresh），无 getService/getForegroundService。

## 影响文件与 Agent 独占写集

第一阶段三个 Agent 只能写 tests-only；主 Agent审查红灯后，原 Agent才可实现。共享 contract 由 C 唯一创建，A/B 在其落地后实现依赖部分。

- Agent A（Edit 入口）：独占 MainActivity Widget Edit 测试、新 validator/route 测试；实现阶段仅 `MainActivity.kt` 与专用入口 adapter。不得改 factory/renderer/layout/Manifest/AppContainer/文档。
- Agent B（LocalRefresh）：独占 Receiver/runtime/application service 测试；实现阶段仅 Receiver、ClenderApplication/AppContainer 装配、必要通用 finish-once helper 与 source Manifest 唯一 Receiver delta。不得改 MainActivity/factory/renderer/layout/strings/文档。
- Agent C（平台 action/RemoteViews）：独占 `WidgetPendingIntentFactory.kt`、`WidgetRemoteViewsRenderer.kt`、event-row/large layout、values/values-en Widget strings、相应 tests/policy；共享 action constants/validator contract 也由 C 唯一建立。不得改 MainActivity/Receiver/AppContainer/Manifest/文档。
- 主 Agent：独占本任务、T47/progress、两层 Android design、AGENTS、跨层集成测试、组合 diff/边界审计、Windows 发布验证与 Git。

## TEST-FIRST 矩阵

| 分片 | 正常路径 | 边界 | 非法/异常 | 回归/安全 |
|---|---|---|---|---|
| A Edit | API 26/36 cold、warm onNewIntent、recreate、Events+Detail | id=1/MAX、已有 destination/route、重复 action | action/data/extra/component/package/provider 任一错；0/负/溢出/query/fragment/HTTP/BROWSABLE | 非法新 Intent保留 dirty；Configure/QuickAi/Refresh 拒绝；EventService 写入与 mutation 均 0；不存在事件有限 not-found |
| B Refresh | API 26/36 合法目标、MANUAL plan、optional 默认配置 | 快速重复、跨实例、refresh/delete race、close 后请求 | invalid/unowned/cross-provider 在 container 前拒绝；owner/scope/launch/cancel/timeout/runtime failure finish-once | 只更新目标；不初始化 AI/WebDAV/Keystore；无网络/EventService/mutation/WorkManager；关闭后零查询/渲染/配置写 |
| C PI/UI | Configure 零变化；Edit/Refresh explicit identity；三尺寸/四 map | MAX event、两实例、Unicode/emoji/换行、theme/font/opacity、EMPTY/UNAVAILABLE LARGE | QuickAi fail-closed；越界 Long展示但不可点；preview/no-ID 无新增 action | row 单点击；Refresh 仅 LARGE/250×250且 ≥48dp；中英文语义非空且不同；policy 精确创建点/Manifest allowlist |
| 主 Agent 跨层 | production update→row→PI→cold/warm MainActivity→shell；production LARGE→broadcast→receiver→policy→coordinator→host | API 26/36、目标/其他实例、资源关闭 | 无写入/网络/AI/WebDAV/Keystore/mutation；finish-once、timeout/delete | scenario destroy→looper drain→container close→sandbox cleanup；CloseGuard 0 |

所有测试禁止 ignored、sleep、GC、重试、排序依赖、过滤、延长超时或降低断言。三 Agent tests-only 必须先返回文件、红灯命令、失败数及仅因 P2B2a 缺失的归因。

## 实施步骤

1. 完成恢复、冻结哈希、修改前 117/1187 与 CloseGuard 0；文档先行冻结本任务/T47/progress。
2. 并行启动 A/B/C tests-only，主 Agent审查组合 diff 与红灯。
3. C 先落地共享 action contract；再让原 A/B/C 在互斥范围实施并跑聚焦绿灯。
4. 主 Agent补两个真实生产链路测试并完成跨层装配审查。
5. 依次运行 P1/P2A/P2B1/P2B1R、P2B2a、UI、OkHttp、完整 strict/offline 96-task gate、CloseGuard、generated/boundary、85 universe、Manifest/APK/负向审计。
6. Android 全绿后运行 Windows 171、30 imports、build check、完整 PyInstaller、双向 EXE 冒烟；`dist/data` 只读前后核对 5 files/195,620 bytes/`E4373390…668D9`。
7. 更新任务/设计/AGENTS，diff/secret/staged 白名单审查，设置指定 Git 代理，提交 `Implement Android T47 widget edit and local refresh actions`，不 push，提交后立即 STOP。

## 风险、回滚与 STOP

风险集中于 PendingIntent token identity、热启动覆盖 dirty route、非导出 Receiver 显式投递、约 10 秒 finish-once、refresh/delete 竞态和 RemoteViews 尺寸挤压。通过严格 validator、唯一 identity、现有 generation/delete invalidation、固定尺寸矩阵和真实生产链路测试控制。

回滚只允许用逐行补丁删除本轮新增/修改的 P2B2a 生产与测试，并恢复本轮文档状态；不得 reset/checkout/stash/clean，不能触碰用户数据或冻结文件。若命中任务输入任一 STOP 条件，只保留任务与 STOP 证据，不运行 Windows、不提交失败实现、不开始 QuickAi/P2C/T48。

特别 STOP：恢复或 1187 基线不匹配；需要新 route/CRUD/SavedState 变化；非导出 Receiver/显式 immutable PI 不可行；必须改 exported Provider；需要依赖/权限/WorkManager/network/schema；Configure/P2B1R、2/4/8、四 map/provider-info 退化；六冻结/85 universe/producer graph 漂移；出现 Google/native/Glance/DataStore Android；finish-once、目标隔离或 delete race 无法证明；完整 Android gate 不绿。

## 完成定义

- [x] 恢复、哈希与修改前 117/1187、UI 52/607、CloseGuard 0 精确通过。
- [x] 本任务、T47、progress 文档先行并冻结 action、validator、Manifest、测试矩阵、Agent 写集与 STOP。
- [x] 三 Agent tests-only 红灯仅因 P2B2a 缺失，随后原 Agent 聚焦实现全绿。
- [x] 两条真实生产链路、完整 Android 96-task gate、CloseGuard/策略/Manifest/APK/依赖边界全绿。
- [x] Windows/PyInstaller/EXE/dist_data 全绿；设计/AGENTS/任务记录完成，diff/secret/staged 审查在提交前执行。
- [ ] 创建指定唯一提交并确认 clean/staged empty、未 push；P2B2b/P2C/T47/T48 保持未完成。

## 实施与验证结果

- 三 Agent tests-only 组合红灯只包含计划内缺失符号/Manifest 创建点：A 缺 `WidgetActionIntentContract`/`WidgetEditEntryRouter`，B 缺 `localRefresh`/共享 contract，C 的 4 项静态安全测试仅 2 项因 Receiver 与 `getBroadcast` 缺失失败；实现后 A 22/22、B 26/26（含 P2A 回归 50/50）、C 69/69，均保持严格断言且未 suppress。
- `MainActivity` 冷启动与 `onNewIntent` 共用严格 validator；合法 EditEvent 确定性建立 `EVENTS + EventDetail`，非法热 Intent 不调用 `setIntent` 且不改变当前导航/草稿。`WidgetLocalRefreshReceiver` 在 container 前验证，合法后以 9 秒上限和 finish-once 调用 app-scoped runtime；runtime 实际消费 `MANUAL_LOCAL_REFRESH` 计划并只重建目标实例。
- 唯一 factory 保持 Configure 契约，新增 explicit immutable EditEvent `getActivity` 与 LocalRefresh `getBroadcast`；合法可见行整体点击，Refresh 仅 LARGE/`250×250`，QuickAi 继续抛 `UnsupportedOperationException`。source Manifest 唯一 delta 为 enabled、non-exported、无 filter 的 Receiver；provider-info 未变。
- 主 Agent 两条真实生产链路与组合聚焦 9 suites/119 tests 全绿。最终 strict/offline/single-worker/`--rerun-tasks` 96-task gate 96/96 executed；124 suites/1263 tests、UI 52/607、OkHttp 16/16、0 failure/error/skip，CloseGuard/SQLite/Room 四类扫描均 0。首轮 detekt 精确报告 5 项复杂度/参数问题与 generated policy 缺 Receiver 映射，均作结构/allowlist 修复后完整重跑全绿；无规则关闭或断言放宽。
- generated/boundary 各 44/44，85 configuration universe、六冻结 SHA 与 provider-info `7EC78BF3…4FD2` 不变。source Manifest 为 `B0B98755…E6CC`；debug/release merged Manifest 为 `53321DD2…ED9B` / `93180569…51D0`。debug APK 11,158,508 bytes、SHA-256 `F95738A0…5E30`，237 entries、native/Glance/Google/Firebase/QuickAiActivity/BootReceiver/WidgetWorker 均 0。
- Windows 171/171、30 imports、`build.py --check`、完整 PyInstaller 全绿。最终 `dist/Clender.exe` 45,582,171 bytes、SHA-256 `A56213DBBAFFE316B5A1183C9E599E66E9B3CA33E288EB2EAAD043E524A35AF8`；普通主实例→静默次实例及反向均 secondary exit 0、primary 存活、隔离 DB 创建、最终进程 0。`dist/data` 前后 5 files/195,620 bytes，逐文件大小、UTC 时间与 SHA-256 相同，摘要基线 `E4373390…668D9` 未变。
