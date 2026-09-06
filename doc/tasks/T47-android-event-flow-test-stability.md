# T47-Baseline2：Android Event Room 冷 Flow 测试稳定性

## 状态、目标与根因裁决

**状态：已完成（2026-09-03，Baseline5R2 联合 checkpoint）。** 历史 STOP 现场保留在下文；Flow 修复已由 Baseline4 恢复并通过最终跨平台门禁，合并到唯一 checkpoint `Stabilize cross-platform test infrastructure`。P2B2b 保持 STOP/未实现，P2C/T48 不开始。

已发生的有效红灯来自 P2B2b 最终 API 26 完整套件：该测试在 5000ms 内未收到第二次 Room Flow emission。获准根因为测试用 `async` 启动两个冷 Flow collector、一次 `yield()` 猜测订阅就绪，再用 `drop(1).first()` 等待更新；默认调度与一次 `yield()` 不保证 Room 已完成首次查询。若 `service.add` 先于首次 emission，写入后的非空快照会成为首值并被 `drop(1)` 丢弃，之后没有第二次 invalidation，最终超时。这不是 `EventService`、`RoomEventRepository`、DAO、Room invalidation、dispatcher 或查询实现缺陷。

## 非目标与文件白名单

- 不恢复或实现 P2B2b，不修改 Quick AI、RemoteViews、Widget/PendingIntent/MainActivity、source Manifest、provider-info、资源、依赖或构建图。
- 不修改 `EventService`、`RoomEventRepository`、DAO、Room schema/migration、dispatcher、查询或 5000ms 上限；若需要任一生产改动，立即 STOP-ESCALATE。
- 不修复 `WidgetResponsiveRemoteViewsTest.everyResponsiveMappingBindsConfigureAndOnlyLargeAlsoBindsRefresh`。其当前回滚基线断言正确；未来 P2B2b tests-first 时才精确迁移 Configure/Refresh/Quick AI/Event row 点击契约。
- 唯一允许修改的 Android 文件为 `android/app/src/test/java/com/molotov/clender/ui/event/EventCrudIntegrationTest.kt`。
- 文档只允许本文件、根 `AGENTS.md`、`doc/tasks/T47-android-widget-quick-ai-activity.md`、`doc/tasks/T47-android-widget.md` 与 `doc/tasks/progress.md`；既有 P2B2b STOP 内容受保护，只追加、不删除、不覆盖、不机械重写。`android/AGENTS.md` 无规则变化则不修改。

## 接口、数据与风险

- 生产接口、数据契约、Room schema、依赖、Manifest、Widget 资源和 Windows 生产代码均无变化。
- 测试订阅握手改为可观察状态：写入前启动 range 与 month-count collector；两个 collector 各自实际收到初始空快照后才完成 readiness；主测试等待两个 readiness 后才调用 `service.add`。
- 写入后只接受精确谓词：range emission 必须精确等于新增事件列表，month-count emission 必须在目标日期精确为 1；保留 mutation version `== 1`。
- collector 异常必须经结构化协程/`await` 传播，不能静默吞掉；所有 collector 与 channel 在 `finally` 中取消、等待并关闭。
- 主要风险是伪 readiness、迟到 collector、清理泄漏或宽松谓词掩盖回归；禁止 sleep、GC、重试、测试排序、Unconfined、全局 dispatcher 替换、宽松轮询和超时延长。

## TEST-FIRST 与测试矩阵

历史 P2B2b 完整套件失败是有效红灯证据，不人为制造随机失败。修改前只运行一次 API 26/36 `EventCrudIntegrationTest` 和一次完整 `testDebugUnitTest`；当前运行即使通过也不循环碰撞竞态。除目标测试外出现任何失败立即 STOP。

| 层级 | 正常路径 | 边界 | 非法/异常 | 回归与完成信号 |
|---|---|---|---|---|
| 握手 | 两个冷 Flow 均先发初始空值，再执行 add | API 26/36、collector 调度顺序任意 | collector 异常传播；finally 确定性清理 | 删除 `yield`/`drop(1)`；5000ms 不变 |
| 写入后 emission | range 精确为 `[added]` | month count 仅目标日精确为 1 | 不接受空值、错误事件或错误 count | mutation version 精确为 1 |
| 聚焦回归 | Event CRUD、EventService、Room repository | API 26/36 | transaction rollback、墓碑与 overlap 保持 | 目标 suite 10 次独立 rerun 全绿 |
| 完整 JVM | 124 suites/1263 tests | UI 52/607、OkHttp 16/16 | 0 failure/error/skip | 连续三次真实 rerun；四类 CloseGuard 均 0 |
| 发布边界 | strict/offline 96-task gate、generated/boundary | 85 configuration universe | native/Glance/Google/Firebase/QuickAi/Boot/Worker 均无 | 六冻结文件、source/provider/merged Manifest 逐字节不变 |
| Windows 发布 | 171 unittest、30 imports、check、PyInstaller、双向 EXE | normal/silent 两方向 | secondary exit 0、primary 存活、残留进程/隔离目录 0 | `dist/data` 5 文件/195,620 bytes 元数据与 SHA-256 前后一致 |

## 实施步骤

1. 核对 branch/HEAD/staged/Android diff、三份受保护 STOP 文档、六冻结文件、Manifest/provider-info 与 `dist/data` 修改前只读快照。
2. 运行修改前 API 26/36 聚焦测试与一次完整 JVM 基线并扫描结果；保留历史红灯作为回归证据。
3. 只修改目标测试：显式 readiness channel/deferred、精确 post-write 谓词、结构化异常传播和 finally 清理。
4. 依次执行聚焦、相关回归、10 次独立目标 suite、三次完整 JVM、strict/offline 96-task、策略/配置/Manifest/APK 边界验证。
5. Android 全绿后执行 Windows/PyInstaller/EXE 与 `dist/data` 前后只读核对。
6. 更新本文件、T47、P2B2b STOP 文档、progress 与根 `AGENTS.md`，审查 diff/秘密/二进制/生成物，创建唯一提交 `Stabilize Android event Flow integration test`，不 push；提交后确认 clean/staged empty 并 STOP。

## STOP、回滚与完成定义

任一条件立即 STOP-ESCALATE：显式 readiness 不能解决；需要生产代码/DAO/Room/dispatcher/查询/超时改动；10 次聚焦或三次完整 JVM任一失败；出现目标测试外的新基线失败；四类 CloseGuard 任一非零；依赖图、六冻结文件、Manifest/provider-info/APK、Android、Windows、PyInstaller、EXE 或 `dist/data` 任一漂移/失败。

STOP 时只用逐行补丁撤回本任务新增的测试改动，保留原 P2B2b STOP 文档与本任务证据；禁止 stash、clean、reset、checkout、rebase、push，不开始 P2B2b/P2C/T48。

完成定义：显式初始 emission 握手和精确更新谓词生效；全部稳定性与发布门禁通过；生产/依赖/Manifest/资源边界零变化；文档与根规则同步；唯一提交完成且工作树 clean、staged empty。

## 验证与 STOP 记录（2026-09-02）

- RECOVERY 精确匹配指定 branch/HEAD/subject、staged empty、Android 零 diff和三份受保护 P2B2b STOP 文档；六冻结构建文件、source Manifest/provider-info 哈希匹配。`dist/data` 修改前为 5 files/195,620 bytes，逐文件相对路径、大小、UTC 时间和 SHA-256 已只读记录。
- TEST-FIRST：修改前 `EventCrudIntegrationTest` API 26/36 为 10 tests / 1 failure，仅目标测试 5000ms 超时；修改前完整 JVM 为 1263 tests / 1 failure，仍仅目标测试失败，无其他基线红灯。
- 临时修复只改 `EventCrudIntegrationTest.kt`：两个长期 collector 用 `CompletableDeferred` 等待实际初始空 emission，channel 传递快照；两个 readiness 后才 add，写入后分别等待 range 精确 `[added]` 与目标日期 count 精确 1；保留单一 5000ms 和 mutation version 1；finally `cancelAndJoin` 两个 collector 并关闭 channel。删除该测试的 `async + yield + drop(1)` readiness，collector 异常继续由结构化协程传播。
- 聚焦结果：修复后目标 suite 10/10；EventService/EventLocalData/Event CRUD 六 suite 聚焦全绿。稳定性矩阵 10 次独立 `--rerun-tasks EventCrudIntegrationTest` 全绿，每次 API 26/36 共 10/10，未重试失败。
- 完整 JVM 连续三次真实 `--rerun-tasks` 均为 124 suites/1263 tests、UI 52/607、OkHttp 16/16、0 failure/error/skip；每轮 CloseGuard、SQLiteConnectionPool、SQLiteDatabase leaked、RoomDatabase leaked 均 0。
- Android 完整 strict/offline 单 worker `--rerun-tasks` 为 96/96 executed、BUILD SUCCESSFUL；lintDebug/lintRelease/detekt/ktlint、签名、Google/native、锁、assemble/APK no-native 全绿；generated/boundary 各 44/44，85 universe 保持。
- 六冻结文件、source/provider/debug/release merged Manifest 完整 SHA-256 保持；debug/release merged 分别为 `53321DD26E4D4A538FF8E93D828ADF45EF4EA0037BF281412DF0DFDAD823ED9B` / `931805697D28BF3448FE28176A5AED0E22BCDB481B9DEE37F2620FF3E65F51D0`。debug APK 保持 11,158,508 bytes / `F95738A0C5DA8BF02B76F021F0DAD8EA8D6BA4C2DD9992D400F00954F0FE5E30`，native/Glance/Google/Firebase/QuickAiActivity/BootReceiver/应用 Worker 命中 0。
- Windows Miniconda Python 3.12.4：offscreen unittest 171/171、生产模块 imports 30/30、`build.py --check`、完整 PyInstaller 均通过；生成 `dist/Clender.exe` 45,582,868 bytes / SHA-256 `55B86682F1BEDAE361A62CFF808C2805E65D0385E18007D354E8ABF61B113D9F`。
- EXE STOP：隔离 normal primary → silent secondary 通过（secondary exit 0、primary alive、隔离 DB 创建）；紧接的 silent primary → normal secondary 场景中 silent primary 在 readiness 前以 0 提前退出，未满足 primary alive。按任务条件未重试、未调整等待或修补冒烟脚本，立即 STOP。
- STOP 清理：两个隔离冒烟目录与 Clender 测试进程最终均为 0；`dist/data` 前后仍为同一 5 files/195,620 bytes，逐文件大小、UTC 时间和 SHA-256 完全一致。已用逐行补丁撤回唯一 Android 测试改动，`git diff --exit-code HEAD -- android` 通过；生产、依赖、Manifest、资源、P2B2b/P2C/T48 均未改变。
- 本任务只保留本文件、T47、P2B2b STOP 与 progress 证据；根/Android AGENTS 无完成规则变更，不更新。未配置 Git proxy，未暂存、未提交、未 push。

## T47-Baseline3 后续裁决（2026-09-02）

- 已启动独立任务 `T47-windows-frozen-single-instance-smoke.md`，裁决本任务 silent-primary readiness 前 exit 0 的原因。
- Flow 临时修复仍已撤回、未完成；`EventCrudIntegrationTest.kt` 与 Android 工程继续相对 HEAD 零 diff。
- Baseline3 未完成并全绿前，不恢复本任务，不重启 P2B2b，不开始 P2C/T48。

### Baseline3 STOP（2026-09-02）

- Baseline3 的 Windows harness 23/23、Windows 194/194、build 与新 EXE 20/20 场景曾全绿，但最终 Android gate 在本文件同一目标测试 API 26 再次发生 5000ms Flow 超时。
- 已立即停止且未重跑；harness 脚本/测试按 STOP 规则撤回。本 Flow 修复继续保持已撤回、未完成，Android 相对 HEAD 零 diff，P2B2b/P2C/T48 状态不变。

### Baseline4 联合收口 STOP（2026-09-02）

- Flow 修复已按本文件契约永久恢复：目标 10/10、相关 70/70、10 次独立稳定性矩阵累计 100/100；完整 JVM 连续三轮均 124 suites/1263 tests、UI 52/607、OkHttp 16/16、CloseGuard 0。最终 Reviewer 确认只改变测试订阅时序。
- Android 96-task 正式 gate 96/96 成功；后续 generated policy 43/44，仅因既有 Android change-boundary 不允许本任务获准的两个 Windows harness 文件而 STOP。修复 policy 需要扩大白名单，未执行；本 Flow 修复因已通过自身矩阵而保留为 staged-empty 未提交 diff。不继续 PyInstaller/最终 EXE，不提交或 push。

## Baseline5R2 最终完成（2026-09-03）

- 上述“已撤回/未完成”和 STOP 均为历史现场，已由 Baseline4 恢复及本节最终结果取代。Flow 修复 SHA-256 保持 `44FFF6CC4A36EB4E45ED201E4A302C8EC40B0A0BAC0F7C24E3A68902A0CAC879`，契约永久明确为 Room 冷 Flow 在 mutation 前等待真实首次 emission；未改生产、Room、DAO、dispatcher 或 5000ms 上限。
- 最终 Android 聚合门禁 96/96，124 suites/1263 tests、UI 52/607、OkHttp 16/16、CloseGuard 0；Windows 198/198、imports 30/30、check/build、唯一 EXE 20/20 与 `dist/data` 一致性均通过。本任务随 Baseline4/4R/5R2 联合收口完成。
