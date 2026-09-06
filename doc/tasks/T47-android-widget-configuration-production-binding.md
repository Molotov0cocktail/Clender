# T47-P2B1R：生产 RemoteViews Configure PendingIntent 装配修复

## 状态

已完成验证，待提交（2026-09-02）。这是独立的小型基线 Bug 修复；提交后立即停止，不开始 P2B2、P2C 或 T48。

## 根因与生产调用链证据

P2B1 的 renderer 与 PendingIntent 工厂本身已有正确实现，但生产装配选择了错误 overload：

`ClenderApplication/AppContainer → WidgetProviderRuntime → WidgetUpdateCoordinator → ProductionWidgetRenderSink → AppWidgetManager.updateAppWidget` 中，`ProductionWidgetRenderSink` 调用 `WidgetRemoteViewsRenderer.renderForHost(context, model, legacySizeClass)`。该 overload 只渲染视图，不绑定平台 action；只有带 `appWidgetId` 的 overload 才会给 `widget_configure` 绑定 `WidgetPendingIntentFactory.configure(...)`。现有 `WidgetRemoteViewsRendererTest` 直接调用带 ID overload，因此没有覆盖真实生产装配缺口。

## 目标

- 新增跨层生产回归测试，从真实 `ClenderApplication/AppContainer` 生产链路更新 Widget，并检查 `AppWidgetManager` 实际收到的 `RemoteViews`。
- 在 API 26 与 API 36 验证 Configure 点击精确启动目标实例的 `WidgetConfigurationActivity`。
- 以最小生产改动让 `ProductionWidgetRenderSink` 调用带 `appWidgetId` 的 renderer overload。
- 保持 P2B1 唯一平台 action、P2A 展示与全部冻结边界不变。

## 非目标

- 不实现 EditEvent、QuickAi、LocalRefresh、MainActivity 路由、QuickAiActivity、Receiver、Worker、BootReceiver 或 WorkManager enqueue。
- 不接 mutation、remote、foreground、date-boundary 自动刷新，不改 AI、WebDAV、EventService、Room/DAO/schema/migration 或 Windows 生产源码。
- 不新增依赖、权限、组件、intent-filter、资源或字符串，不改 source Manifest、provider-info 或六个冻结构建文件。
- 不使用 Glance、DataStore Android、native、Google SDK、force/strictly/exclusion、baseline、disable 或 suppression；不 push。

## 影响文件

计划内文件：

- `android/app/src/test/java/com/molotov/clender/app/AppContainerWidgetAssemblyContractTest.kt`：新增真实生产 RemoteViews 装配回归。
- `android/app/src/main/java/com/molotov/clender/app/AppContainer.kt`：仅把生产 renderer 调用切换到带 `appWidgetId` overload。
- `doc/tasks/T47-android-widget-configuration-production-binding.md`
- `doc/tasks/T47-android-widget.md`
- `doc/tasks/progress.md`
- 根 `AGENTS.md`；`android/AGENTS.md` 仅在局部命令或契约确有变化时更新。

## 接口与数据影响

- 不新增或修改接口、持久化字段、数据 schema、资源或组件。
- Configure Intent 必须保持 explicit `getActivity`：component 为 `WidgetConfigurationActivity`，package 为本应用，action 为 `ACTION_APPWIDGET_CONFIGURE`，data 为 `clender-internal://widget/{id}/configure`，extras 仅 `EXTRA_APPWIDGET_ID`。
- flags 精确为 `FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT`，requestCode 为 0；以 component + canonical data 区分实例，可重复打开且不使用 `FLAG_ONE_SHOT`。

## 测试矩阵

| 层级 | 正常路径 | 边界 | 非法/异常路径 | 回归/安全 |
|---|---|---|---|---|
| 生产装配 | 真实 runtime 更新后，实际 RemoteViews 的 Configure 可点击并启动精确 Intent | API 26 单尺寸、API 36 responsive；两个正 ID 身份隔离 | 非 Configure 区域无 action；非正 ID 继续由既有边界拒绝 | 不直调带 ID renderer 作为主要证据；容器关闭、主 looper drain、sandbox 清理、CloseGuard 0 |
| RemoteViews 状态 | CONTENT 保持 Configure | EMPTY、UNAVAILABLE 仍可 Configure | 有限错误不泄露 metadata/异常 | API 31+ 四项 mapping 均绑定同一目标实例；2/4/8 容量与主题/字号/透明度不变 |
| PendingIntent | 正确 component/package/action/data/extra | 两实例 canonical identity 与 ID 不串用 | EditEvent/QuickAi/LocalRefresh 继续 `UnsupportedOperationException` | explicit activity、immutable/update-current、requestCode 0、无 one-shot/秘密/正文 |
| 完整 Android | 聚焦 P2B1/P2A 与完整 JVM 全绿 | 117 suites/1185 tests 修改前基线；最终记录精确新增数量 | 0 failure/error/skip，CloseGuard 四类 0 | 96-task gate、generated/boundary 44/44、85 universe/producer allowlist/冻结哈希不变 |
| Windows 发布 | 171/171、30 imports、build check、PyInstaller 与双向单实例 | `dist/data` 前后 5 files/195,620 bytes | 最终无 Clender 进程和冒烟目录 | 不读取真实正文；EXE 不提交；摘要保持 `E4373390…668D9` |

## 依赖、Manifest 与资源冻结边界

六个冻结构建文件必须逐字节保持用户给定 SHA-256；source/debug/release Manifest 与 `clender_widget_info.xml` 不得变化。85 configuration universe、WorkManager producer allowlist、无 native/Google/Glance/DataStore Android 与 APK action/component 边界必须不变。Configure 仍是唯一允许的平台 PendingIntent。

## 实施步骤

1. 核对分支、HEAD、工作树、upstream、冻结哈希，并运行修改前 strict/offline JVM 基线。
2. 文档先行：创建本文件并同步 T47 总任务与 progress。
3. 测试先行：新增真实生产装配测试；在旧实现稳定红灯，失败必须明确为 `widget_configure` 没有点击行为。
4. 最小修复：仅向 `ProductionWidgetRenderSink` 的 `renderForHost` 调用传入 `appWidgetId`。
5. 运行回归绿灯、P2B1/P2A 聚焦、完整 JVM、96-task Android gate 与策略/冻结审计。
6. 仅 Android 全绿后执行 Windows/PyInstaller/EXE/`dist/data` 门禁。
7. 更新本文、T47、progress、根 AGENTS，审查 diff/secret/staged，配置代理并创建指定提交；不 push，提交后确认 clean/empty 并立即停止。

## 回滚方式

若测试不能证明旧生产路径缺失点击，或最小修复不能满足全部边界，则删除本任务新增回归测试，并撤销 `AppContainer.kt` 单处调用参数修改；只保留必要失败证据与任务记录。不得 reset、checkout、clean、stash 或覆盖现场/真实数据。

## 风险

- Robolectric 测试若没有绑定正确 `ClenderWidgetProvider` 或没有读取 `AppWidgetManager` 实际发布值，可能产生伪绿。
- API 31+ responsive 容器必须验证四个实际 mapping，而不只是容器可 apply。
- 多实例若复用错误 canonical data，`FLAG_UPDATE_CURRENT` 可能更新错误 token；测试必须检查两个实例。
- 测试必须按生产资源生命周期关闭 container、drain main looper 并清理 Android sandbox，防止 CloseGuard 或跨 suite 污染。

## 验证记录

- 修改前 strict/offline 完整 JVM：117 suites/1185 tests、UI 52/607、0 failure/error/skip、CloseGuard 四类 0。
- tests-only 红灯：聚焦 class 共 12 instances，新增 API 26/36 两项均以 `production Configure must be clickable for 401` 失败。
- 单处生产修复后同一 class 12/12 通过；P2B1/P2A 聚焦 7 suites/80 tests 全绿；完整 JVM 117 suites/1187 tests、UI 52/607、0 failure/error/skip、CloseGuard 四类 0。
- 完整 Android gate 首轮在新增测试第 172 行被 `ktlintTestSourceSetCheck` 拒绝（参数换行与 100 字符行长），此前 JVM、lintDebug、lintRelease、detekt 已通过。已按 lint 报告只调整测试格式，保留全部断言；必须重跑完整 gate，不以重试掩盖该确定性失败。
- 完整 Android gate 第二轮 strict/offline、单 worker、`--rerun-tasks` 为 96/96 tasks executed、BUILD SUCCESSFUL；testDebugUnitTest、lintDebug、lintRelease、detekt、ktlintCheck、签名/Google/native/锁、assembleDebug 与 APK no-native 全绿。
- `verify-generated.ps1` 与 `verify-boundaries.ps1` 各 44/44；85 configuration universe、WorkManager producer allowlist保持。六冻结构建文件、source Manifest、provider-info SHA-256 逐字节匹配恢复基线；debug/release merged Manifest SHA-256 分别为 `E9B657E9980C46EBE239CAABA22F36AF9AA556AC0E1EFE83DC64EAE61A6A9268`、`57259A9D1218549E2880979EF2C053D0E775BCD1D0C7B898D7A5C3F615AD754D`，精确 allowlist 通过且源/资源无 diff。
- debug APK 为 11,150,804 bytes、SHA-256 `C0A95ACA97ABD0F073BF9E6DBE022F5A5DAF4F3F333AC808CD38F198F14DA87C`；182 entries，native/`.so`、Glance、`datastore_shared_counter`、Google/Firebase、QuickAi/BootReceiver 命中均为 0。
- Windows Miniconda Python 3.12.4：171/171 unittest、30/30 production imports、`build.py --check` 与完整 PyInstaller 通过。最终 `dist/Clender.exe` 为 45,581,693 bytes、SHA-256 `EFFE09E911AE56A7F81F7B14CDB60A649855629F3D1D790576DF431405CB544A`；normal→silent、silent→normal 双向隔离单实例均 primary 存活、secondary exit 0，最终精确 smoke 进程/目录为 0。
- `dist/data` 构建/冒烟前后均为 5 files/195,620 bytes，相对路径、大小、UTC 时间与逐文件 SHA-256 完全一致，排序聚合 SHA-256 为 `E43733909427A268005A9514FB136CDA0F74C43395046269E73EF20E539668D9`；未打开、解析、复制、修改、暂存或提交真实正文。

## STOP 条件

- 恢复现场或修改前 117/1185 基线不匹配。
- 新测试在旧实现通过，或失败原因不是生产 RemoteViews Configure 未绑定点击。
- 修复需要超过生产 renderer 调用与必要测试装配的实质性改动。
- 需要修改依赖、Manifest、provider-info、资源、Room、AI、WebDAV，或发现 Configure 之外的生产 PendingIntent。
- 六冻结文件、85 universe、WorkManager graph、native/Google/Glance/DataStore Android 边界漂移。
- 无法确定性关闭测试资源，或完整 Android 门禁失败。

STOP 后不得进入 Windows 发布验证、不得提交失败实现、不得开始 P2B2。

## 完成定义

- [x] 恢复现场、冻结哈希与修改前 117 suites/1185 tests、UI 52/607、CloseGuard 0 已确认。
- [x] 根因与真实生产调用链已确认，任务文档、T47 和 progress 已先行更新。
- [x] 新增生产装配测试在旧实现稳定红灯：API 26/36 共 12 instances 中仅新增 2 instances 失败，均为 `production Configure must be clickable for 401`；实际 `AppWidgetManager` host view 的 Configure 无点击，其余编译/装配正常。
- [x] 单处最小生产修复后回归、聚焦与完整 Android 门禁全绿。
- [x] 六冻结哈希、Manifest/provider-info、85 universe、producer allowlist 与 APK 安全边界不变。
- [x] Windows 171/171、30 imports、build check、完整 PyInstaller、双向 EXE 冒烟与 `dist/data` 前后一致。
- [x] 文档/AGENTS/diff/secret/generated 审查完成，变更严格限于 7 个白名单工程文件。
- [ ] 创建 `Repair Android T47 configure action production binding` 提交；提交后确认 working tree clean、staged empty，未 push。
- [x] P2B2、P2C、T47 整体与 T48 仍未完成；下一任务才是 P2B2a（EditEvent + LocalRefresh），QuickAi 建议留作 P2B2b。
