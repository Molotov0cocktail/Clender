# T47-P2A：只读平台 Launcher Widget Provider 与 RemoteViews 渲染

## 状态

P2A 已完成（2026-09-02；checkpoint `Implement Android T47 read-only widget provider`）。T47-P2/P2B/P2C/T47/T48 仍未完成。

## 目标

实现以下 app-scoped、只读、多实例闭环：

```text
AppWidgetProvider
  -> WidgetUpdateCoordinator
  -> WidgetConfigurationStore / EventRepository
  -> WidgetStateBuilder / WidgetPresentationPolicy
  -> API 26–30 单 RemoteViews 或 API 31+ responsive RemoteViews map
```

Provider 只支持 `onUpdate`、`onAppWidgetOptionsChanged`、`onDeleted`。缺省实例自动创建安全默认配置，事件查询失败/超时/渲染失败显示统一有限状态；一个实例失败不能阻止其他实例；删除会精确清理配置并使所有在途结果失效。

## 非目标与严格禁止

- 不实现配置 Activity、编辑/Quick AI/本地刷新 PendingIntent、MainActivity action route 或 Widget 设置 UI。
- 不实现 Worker、BootReceiver、WorkManager enqueue、mutation/remote/foreground/date-boundary 自动触发、周期/精确刷新、通知、AlarmManager、后台网络。
- 不新增 RemoteViewsService、collection service、自有 Service、Bitmap 生成、自定义 View、Compose、Glance、DataStore Android/native/Google 路径。
- 不修改 Room/DAO/schema/migration、Event/EventService、AI/WebDAV 协议、T46 UI、Windows 源码、Gradle/catalog/lock/verification metadata。
- 生产 Widget 源码不得出现 `PendingIntent`、`setOnClickPendingIntent`、fill-in Intent、`RemoteViewsService`、自定义 action/URI/query/external extras。

## 输入文档与恢复基线

- `AGENTS.md`、`android/AGENTS.md`。
- `doc/android-architecture-high-level-design.md`、`doc/android-architecture-detailed-design.md`（Widget/Manifest 段落）。
- `doc/tasks/T47-android-widget.md`、`doc/tasks/T47-android-widget-foundation-contracts.md`。
- P1 冻结接口：`WidgetConfiguration`、`DataStoreWidgetConfigurationStore`、`WidgetStateBuilder`、`WidgetPresentationPolicy`、`EventRepository.observeRange`。
- 恢复现场：`codex/android-architecture @ 6e37419d782d60b2a9c5d8fa97d45c9c0d9e3c38`、subject 与冻结 SHA 精确匹配、staged empty、未 push；working tree 已有本轮 P2A 文档/tests-only 中间态。按用户“检查任务阶段后继续”的授权逐文件审计，确认无生产/依赖/Manifest 越界后续做；修改前完整 Android JVM 105/1033、UI 50/567、P1 6 suites/53、OkHttp 16/16，CloseGuard 四类 0。
- 六个构建文件 SHA、source Manifest SHA 与 P0R/P1 记录保持不变，85 configuration universe 保持不变。

## 影响文件与职责

### Agent A：RemoteViews、尺寸、资源（独占）

- `android/app/src/main/java/com/molotov/clender/widget/WidgetSizeClassResolver.kt`
- `android/app/src/main/java/com/molotov/clender/widget/WidgetRemoteViewsRenderer.kt`
- 仅限 `widget` 包内 render model/formatter。
- `android/app/src/main/res/layout/widget_clender_small.xml`
- `android/app/src/main/res/layout/widget_clender_medium.xml`
- `android/app/src/main/res/layout/widget_clender_large.xml`
- `android/app/src/main/res/layout/widget_clender_event_row.xml`
- `android/app/src/main/res/xml/clender_widget_info.xml`
- Widget 中英文 strings、colors、dimen 资源及对应 Robolectric/resource tests。

不得修改 AppContainer、Provider、Coordinator、Manifest policy、Room、Gradle 或共享文档。

### Agent B：Application Coordinator（独占）

- `android/app/src/main/java/com/molotov/clender/app/widget/WidgetUpdateCoordinator.kt`
- 仅限其 application-level ports/result/error/render model types 与对应 tests。
- 负责正数 ID 归一化、默认配置、Repository Flow 首值、8 秒上限、实例隔离、generation guard、删除竞态和不泄露信息。

不得依赖 `AppWidgetManager`、`RemoteViews`、Android View、Provider、Manifest、资源、AppContainer、AI/WebDAV/network。

### Agent C：Provider、Manifest、安全（独占）

- `android/app/src/main/java/com/molotov/clender/widget/ClenderWidgetProvider.kt`
- Provider callback/async lifecycle tests。
- `android/tests/policy/test_merged_manifest_security.py`
- `android/app/src/main/AndroidManifest.xml`

不得修改 AppContainer、Coordinator、Renderer、布局、资源、Gradle 或 lock/metadata。

### 主 Agent 独占

- `android/app/src/main/java/com/molotov/clender/app/AppContainer.kt`
- `android/app/src/main/java/com/molotov/clender/app/ClenderApplication.kt`
- app-scoped Widget scope/close 顺序。
- 跨层 production assembly tests、共享文档、根/Android `AGENTS.md`、最终集成修复、审计和提交。

## 冻结生产契约

### 尺寸与 ProviderInfo

- API 26–30 从 options 与当前 orientation 推导保守尺寸：portrait 为 `minWidth/maxHeight`，landscape 为 `maxWidth/minHeight`；未知 orientation、缺失、零/负值为 SMALL；不得读取屏幕像素或 DisplayMetrics 猜 Launcher cell。
- `width >= 250 && height >= 250` 为 LARGE；否则任一维 `>=250` 为 MEDIUM；其余为 SMALL。
- API 31+ 必须构造精确四项 `RemoteViews(Map<SizeF, RemoteViews>)`：110×110 SMALL、250×110 MEDIUM、110×250 MEDIUM、250×250 LARGE；API 30 及以下不可执行该构造路径。
- `AppWidgetProviderInfo` 固定 110dp min、110dp resize min、360dp resize max、target cell 2×2、horizontal|vertical、home_screen、updatePeriodMillis=0、small initial layout、medium preview layout、本地化 description。
- 必须省略 configure/widgetFeatures/initialKeyguardLayout/previewImage/autoAdvanceViewId；初始和预览布局无用户内容、假事件或敏感样例。

### Renderer 与资源

- 只使用 RemoteViews 支持的标准 View；日期标题、最多 2/4/8 行、时间摘要、原始标题和 CURRENT/NEXT/FUTURE 有限状态；超出显示精确 `+N`。
- 空态和查询/配置/渲染错误均保留可读日期/状态；错误统一本地化“暂时不可用”，不得含异常正文。
- 不显示 description、sync UID、created/updated/deleted metadata、身份、路径或配置详情；Unicode/emoji/换行作为纯文本，布局用 maxLines/ellipsize。
- 字号 8–20sp；LIGHT/DARK 固定可审计配色；SYSTEM 只读 `Resources.configuration.uiMode`；opacity 0–100 只作用背景，不隐藏状态或向其他 View 泄露。
- 中英文关键文本全部资源化；任何 View 不设置点击动作。

### Coordinator

- 依赖抽象的配置 store、appearance/widget-font provider、`EventRepository`、注入 `Clock`/`ZoneId` 和 render sink；只经 P1 builder/presentation 产生展示模型。
- 正数 appWidgetId 去重并稳定排序；缺配置时读取合法 appearance widget 字号，调用 `WidgetConfiguration.defaults`（非法字号回退 13sp），原子 upsert 后重新 get。
- 使用配置日期窗口调用 `observeRange(...).first()`；本地查询总时限 ≤8 秒。超时、异常、取消、关闭映射为有限结果，取消不成为错误正文。
- 每实例独立失败；generation 单调递增，新请求使旧结果失效；delete 先失效再删除，删除后迟到结果不可 render 或重建配置。
- 不写事件、不调用 EventService/AI/WebDAV/network/mutation，不记录标题、异常、路径、配置或 Widget 内容。

### Provider 与生命周期

- `ClenderWidgetProvider : AppWidgetProvider` 只处理 `onUpdate`、`onAppWidgetOptionsChanged`、`onDeleted`；不自定义 `onReceive`，不增加 onEnabled/onDisabled/onRestored 副作用。
- 每个 callback 使用 `goAsync()`，在 `finally` 恰好 `finish()` 一次；异常、取消、超时、container close 均必须结束 PendingResult，不在主线程 runBlocking/Room/Flow。
- 使用 app-scoped `SupervisorJob + Dispatchers.IO`；AppContainer.close 在 DataStore/Room 前取消并等待 Widget scope 不再提交 render。
- Provider/renderer/coordinator 惰性初始化；普通 MainActivity 冷启不初始化 Widget runtime/query/render；Widget 首更不初始化 AI、Keystore、WebDAV 或 network client。
- onUpdate 只读更新所有传入实例，options changed 只目标实例，onDeleted 是唯一配置删除入口；不接受自定义 action/URI/query/external extras。

### Manifest allowlist

source Manifest 只新增一个 receiver：`.widget.ClenderWidgetProvider`，enabled=true、exported=true，唯一 filter action 为 `android.appwidget.action.APPWIDGET_UPDATE`，唯一 metadata 为 `android.appwidget.provider=@xml/clender_widget_info`。禁止 BROWSABLE/category/data、自定义 action、权限扩张、其他 Activity/Service/Provider/Boot receiver/Worker、自有 application metadata/directBootAware。merged debug/release 只在既有 WorkManager producer allowlist 外增加这一精确 receiver delta，既有 85 universe、权限和 producer 逐项不变；allowBackup=false 保持不变。

## TEST-FIRST 测试矩阵

第一阶段三个 Agent 只能新增互斥 tests-only 红灯。主 Agent 审查红灯只能由计划中的类型、资源、Provider 和 Manifest 条目缺失导致，确认后才让原 Agent实现自己的范围。

| 层 | 正常/边界 | 异常/回归/安全 |
|---|---|---|
| 尺寸/Renderer | API 26/30/31/36；portrait/landscape；阈值前后；坏 options；四项 SizeF 唯一映射；2/4/8；empty/content/+N；LIGHT/DARK/SYSTEM；8/20sp；opacity 0/100；中英文、Unicode、emoji、换行、长标题；RemoteViews apply/inspect | API≤30 不走 map 构造；不含 description/metadata/异常正文；initial/preview 无用户内容；无点击/PendingIntent/HTML/Bitmap/Glance/Service |
| Coordinator | 默认一次 upsert 后重读；多实例隔离/稳定排序；不同日期窗口与半开边界；repository 首值；无写入副作用 | repository error/never emit/≤8s timeout/cancel；一实例失败不影响其他；迟到旧结果拒绝；delete/in-flight 竞态；非法/重复 ID；不触发 AI/WebDAV/network/mutation |
| Provider/Assembly | onUpdate/options/delete；多实例；API 26/36 生产装配 | goAsync finish 恰好一次；异常/超时/cancel/close；MainActivity 冷启不初始化 Widget；首更不初始化 AI/Keystore/WebDAV；close 顺序/CloseGuard 0；精确 Manifest/merged policy；无自有禁止组件/权限 |

不得使用 ignored、假断言、sleep、重试、排序依赖、GC、延长超时或过滤。记录源码测试方法数及 API 参数化后的实际 JVM instances。

## 实施步骤

1. 完成恢复检查、P2A 文档/设计/任务/进度更新；运行 105/1033 未修改基线。
2. 三个独立 Agent 只写互斥 tests-only 红灯；主 Agent 审查 diff、失败归因和禁止范围。
3. 原 Agent 在各自独占范围实现并完成聚焦测试；主 Agent 不代写其生产文件。
4. 主 Agent 接入 AppContainer/ClenderApplication 的懒加载 Widget runtime、scope close 顺序和跨层 assembly tests。
5. 聚焦、完整 Android、policy/lint/detekt/ktlint/APK/native/锁图、Windows/PyInstaller/EXE/dist_data 验证；更新全部任务/设计/AGENTS；单一 checkpoint commit，不 push，提交后停止。

## 风险、回滚与 STOP

- 风险：RemoteViews 受支持属性/资源限制、API 31 构造路径、Robolectric apply 差异、DataStore/Room 关闭时序、Provider goAsync race、Manifest producer 归因和系统 orientation options 不确定。
- 回滚仅允许逐行补丁删除本轮新增 Widget 生产/测试/资源/Manifest delta 和恢复文档状态；禁止 reset/checkout/stash/clean，禁止触碰用户数据、dist/data、冻结构建文件和既有 T47 checkpoints。
- 立即 STOP-ESCALATE：修改前 105/1033 或 CloseGuard 不绿；需要依赖/Gradle/Room/schema/Glance/native/Google/RemoteViewsService/自有 Service/Worker/Boot/PendingIntent/网络；Manifest 超出精确 receiver；破坏 P1 契约；API 26/36 无法安全渲染或 goAsync 无法确定 finish；85 universe/producer allowlist/冻结 SHA 漂移；Windows/PyInstaller/EXE/dist_data 失败；出现新的产品/隐私/OEM 语义歧义。

## 完成定义

- [x] 三组 tests-only 红灯证据和原 Agent 实现记录完整；P2A 聚焦 7 suites/78 JVM instances，0 failure/error/skip。
- [x] 只读 Provider→Coordinator→P1→RemoteViews 闭环在 API 26–36 生产装配，多实例隔离、有限错误、删除清理和关闭顺序成立。
- [x] source/merged Manifest 只出现获准 receiver delta；无点击、PendingIntent、自有禁止组件、额外权限、Glance/native/Google/Worker/Boot。
- [x] Android 全量为 112 suites/1111 tests；UI 50/567、P1 6 suites/53、OkHttp 16/16、CloseGuard 四类 0；完整 strict/offline 96 tasks、generated/boundary 各 44/44、85 universe 和六 SHA 不变。
- [x] Windows 171/171、30 imports、build check、完整 PyInstaller、双向 normal/silent EXE 冒烟和 dist/data 只读摘要不变。
- [x] 更新 T47、progress、两层 Android design、根/Android AGENTS；创建单一 `Implement Android T47 read-only widget provider` checkpoint 且不 push；P2B/P2C/T48 明确未开始。

## 实施与验证证据

- Agent A/B/C 严格先写互斥 tests-only；组合红灯仅为计划内 size/renderer/resource、coordinator ports、Provider/Manifest 类型缺失。原 Agent 随后各自实现独占范围，主 Agent 只完成 `AppContainer`/`ClenderApplication` 惰性生产装配与跨层测试。新增测试共 49 个源码方法，按 API 26/30/31/36 等参数化展开为 7 suites/78 JVM instances。
- Renderer 冻结 API 26–30 保守单尺寸分类和 API 31+ 精确四项 `SizeF` map；SMALL/MEDIUM/LARGE 仅渲染 2/4/8 行，支持中英、Unicode/emoji/换行、8/20sp、0/100 opacity、LIGHT/DARK/SYSTEM，并把空态/失败收敛为有限本地化文本。布局、provider-info 与生产 Kotlin 无任何点击、PendingIntent、collection service 或用户样例。
- Coordinator 通过抽象配置/appearance/repository/sink ports 工作：正 ID 去重稳定排序、缺配置默认 upsert 后再查询、每实例 generation/Mutex、8 秒上限、独立失败与 delete-in-flight 失效；只复用 P1 state/presentation，不触发 EventService、AI、WebDAV、网络或 mutation。
- Provider 只实现 `onUpdate`、`onAppWidgetOptionsChanged`、`onDeleted`；每个异步回调 `goAsync()`，coroutine `finally` 加 completion fallback 以原子门保证底层 `finish()` 恰好一次。Widget runtime 全部 lazy；容器关闭在 DataStore/Room 前关闭 coordinator、取消并等待 Widget scope。
- 聚焦 P2A 7 suites/78 通过；最终完整 JVM 112 suites/1111 tests、UI 50/567、P1 6 suites/53、OkHttp 16/16，0 failure/error/skip，SQLite/Room CloseGuard 四类 0。严格离线单 worker `--rerun-tasks` 96/96 执行成功，包含 test、debug/release lint、detekt、ktlint、签名、Google/native/locked versions、assemble 与 APK native audit。
- `verify-generated.ps1` 与 `verify-boundaries.ps1` 最终各 44/44。首次 generated 因新增重复 policy 文件变为 48，已把断言合并回既有 policy 并删除重复文件；未减少既有断言。六冻结构建 SHA、84 active + stale `androidApis` = 85 configuration universe、WorkManager producer allowlist均不变。
- source/debug/release Manifest 与 APK 审计确认精确一个应用自有 `ClenderWidgetProvider`、唯一 `APPWIDGET_UPDATE` filter 和一个 provider-info；无新增权限/BROWSABLE/data/应用 metadata，无自有 QuickAI/config/Boot/Worker/Service，APK native entries 为 0，且无 Glance/DataStore Android/Google 命中。
- Windows Miniconda Python 3.12.4：unittest 171/171、30/30 production imports、`build.py --check`、完整 PyInstaller 与双向 EXE 隔离冒烟均通过。最终 `dist/Clender.exe` 为 45,582,391 bytes，SHA-256 `4EDD84C5C6808FC718F59867DDB6C483AFC524A25ED08DD8282CC33D12D835B4`；两个 secondary exit 0、primary 均存活，最终 0 Clender 进程/0 冒烟目录。
- `dist/data` 构建/冒烟前后均为 5 files / 195,620 bytes，逐文件元数据与 SHA-256 不变，冻结组合摘要 `E43733909427A268005A9514FB136CDA0F74C43395046269E73EF20E539668D9`；未解析、复制或修改真实正文。

## Remaining

P2A 完成后仍明确保留：P2B 配置 Activity、编辑/Quick AI/本地刷新 PendingIntent 未开始；P2C mutation/remote/foreground/date-boundary、Worker/Boot 未开始；T47 整体未完成；T48 未开始。
