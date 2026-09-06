# T47-P2C：Widget 自动本地刷新

## 2026-09-06 最新状态：P2C 独立实现与验证 PASS

本节按最终源码、已有执行日志和主 agent 最新结果补记；下文历史 tests-only、待重验
文字保留当时顺序，不代表当前源码尚未实施。本次只更新本文件，未执行 Gradle。
**P2C 独立实现与验证 PASS：JVM/静态门禁通过，真实 date/boot 验收已完成。
T47/T48 实现、构建、验收已完成。Git 交付为最后步骤，回执以主 agent 最终答复/Git
日志为准；本文不声称已经 commit/push。**

### 最终设备交付验收状态（主 agent 回执）

最终 APK SHA-256：`5d90cbb3d271063ad7b9c3645eb94ede9180bde62c46bde109a09cc0c56a897e`。
全部 8 台设备最终安装/hash 通过。API26/36 手机与 API36 平板最终 core 8 steps、实际
Widget 通过，API36 phone/tablet 最终 Drawer 18 steps 通过。API26 Widget 原 ADB
清理临时 XML 失败完整保留；`completion-widget-api26-r4-resume-result.json` PASS 为
只读确认已保存，随后继续完成自动更新/冷启动验收，不将原失败追写为成功。

扩展矩阵按增量证据验收：API29/31/33 phone core 复用 4cf、Drawer 复用 04；API35
phone core 复用 4cf、最终 Drawer 18 steps 通过；API33 tablet core 复用 c287、最终
Drawer 18 steps 通过。date/boot 仍为下述 c287 候选证据复用，未宣称最终 5d90 重跑。
最终 155/1611、UI57/665，failure/error/skip/leak 全零，全部门禁完成。

### 最新设备证据与复用边界

API36 phone 的 date/boot 实际验收使用 c287 候选 APK，完整 SHA-256 为
`c287f7ccec6cad4f430d8f92d8743ca4f5026b8272eb32f2a168ed6c5bc3452d`：

- `android/.tmp/completion-release-widget36-date.json`：PASS；日期边界前进程不存在，
  自然等待 151.97 秒后 Widget 从 Sep 6, 2026 更新为 Sep 7, 2026，并建立后继 one-time
  日期 work；验收后恢复时钟，auto_time=1。
- `android/.tmp/completion-release-widget36-boot.json`：PASS；真实 reboot 后测试未启动
  MainActivity，Widget 日期与事项已恢复，日期 work 存在；auto_time 已恢复、adbd 已 unroot。

此后生产仅变更 Drawer 三文件及 Renderer Refresh textColor 一行；P2C、Manifest、调度
逻辑无变化，因此复用上述候选设备证据。**未在 final5d90 重跑 date/boot**，不得将 c287
报告标成最终包的重新执行结果；最终设备验收及增量复用范围见上方回执。

### 最新完整门禁结果

主 agent 最终全量：**155 suites / 1611 tests**，UI **57 suites / 665 tests**；
failure/error/skip 全部 0，资源泄漏标记全部 0。完整命令 **96 tasks（26 executed、
70 up-to-date），5m39s PASS**；release/device fixtures **111/111**、policy **49/49**、
boundary **49/49** 全部 PASS。下文原有数量和执行记录作为历史证据完整保留，不追写覆盖。
本次仅更新本文档顶端，未修改源码、其他文档或运行 Gradle；更新后冻结。

### 最终生产装配与契约

- `WidgetAutomaticRefreshRuntime(scope, dependencies = WidgetAutomaticRefreshDependencies(...))`
  复用 container 既有 widget scope；dependencies 只汇集 ownedIds/localUpdates/dateWork、
  mutationVersion、Clock 和动态 ZoneId。runtime 子 Job 独立于 Receipt 等待者，合并目标
  时保留每个 completion；关闭拒收并结束待处理 Receipt，不取消已持久化日期 successor。
- `PlatformWidgetOwnedIds` 查询当前 provider 的正平台 ID，包含 configuration_optional
  实例。无实例不初始化 coordinator、Room 或 Widget 配置；冷 delete 只清同一配置 store，
  最后实例删除取消唯一日期 work。本地 mutation 忽略首次 version，后续变化恢复本地显示；
  安装/恢复建立监听，最后实例删除停止监听。
- `WidgetLocalUpdatePort.prepareUpdate(id)` 由生产端口取得同一 lazy coordinator，调用
  `WidgetUpdateCoordinator.prepareUpdate(ids, size)`。runtime 在删除登记使用的 inbox
  同一短锁中检查 ticket currency 并准备 generation/time，锁外 invoke 查询与渲染。
  prepare 可构造惰性包装对象，但不能实际打开数据库、读写配置或等待 I/O；invalidate
  只访问已初始化 coordinator。没有第二套 assembly blocked-ID 屏障，也没有锁内
  UNDISPATCHED 查询。动态时区同时用于日期排期及 coordinator 每批时间快照。
- Application 无 owned Widget 时保持 container lazy；首次合法创建在主线程绑定唯一
  `WidgetProcessLifecycleBinding`（后台首次创建只 post 主线程绑定），冷进程已有实例则恢复。
  主线程 close 先 CAS closed、解绑 observer，再关闭 runtime/coordinator、取消并等待 widget
  IO，最后释放 DataStore/Room；迟到主线程绑定回调因 closed 检查返回。该契约不扩展为
  允许任意后台线程与主线程并发 close/bind。
- Provider update/options、配置完成、manual refresh 进入同一 runtime；`prepareDelete`
  在广播协程启动前同步登记删除，随后等待同一 Receipt。Provider、LocalRefreshReceiver、
  BootReceiver 均以 **9 秒**等待上限和 finish-once 结束 PendingResult，等待超时不取消
  已 accepted 的 app-scoped 工作。Provider 的 7 方法/API26/36 共 **14 项**聚焦回归通过，
  覆盖三个 callback、排队计时、成功/失败/owner 取消及超时后真实 runtime 工作继续完成。
- `WorkManagerWidgetDateScheduler` 使用固定 `clender-widget-date-boundary`、
  `OneTimeWorkRequest<WidgetDateBoundaryWorker>`、REPLACE、空 input，并等待平台 Operation
  提交。日期排期根据真实下一本地午夜计算；普通 mutation/configuration/manual 不无条件
  重新排期。没有 periodic/expedited/foreground、Alarm、通知或后台网络。
- Worker 只调用 `request(DATE_BOUNDARY).await()`；COMPLETED/NO_WIDGETS/SUPERSEDED 为 success，
  有限失败为 failure，取消重抛，无 retry 或 onStopped/finally 取消 unique work。
  真实 WorkManager 测试验证运行中 REPLACE 可取消旧 Worker，而独立 runtime 所提交的
  successor 留存；这不是实际设备跨进程/重启日期验收的替代品。
- non-exported BootReceiver 在 onReceive 首 guard 直接检查 BOOT_COMPLETED，再验证系统
  envelope；允许空 extras 或唯一非负 Int `android.intent.extra.user_handle`，不按其值
  路由，不接受其他 payload/data/type/selector/clip/categories。此直接 action guard 同时
  满足 protected-broadcast lint，不通过 Suppress 或规则调整绕过。

### 远端可见变化与取消 pending 修复

`WebDavSyncRuntime` 最终使用
`WebDavSyncSignals(availability, mutationVersion, onRemoteVisibleChanged)` 聚合信号；
primary constructor 为 scope/lifecycle/gate/signals/runSync，保留旧六参数 secondary
兼容调用。AppContainer 将 callback 接到 `REMOTE_VISIBLE_CHANGE`，remote apply 不经过
EventService，不递增本地 mutation，不产生上传回路。

原 runtime 捕获 SyncCancellationException 后吞掉取消，会丢失已提交远端变化标志。
现先记录有限 CANCELLED 状态再重抛，由既有 SyncCoordinator 对成功、PUT 失败或取消中
已提交的可见变化恰好通知一次。`completion-webdav-widget-behavior-red.txt` 留存
**7 tests / 2 failures**（取消上传与 close 保留已提交通知）；修复后这 7 项及全量通过。

重抛暴露了旧 SyncCoordinator 的 pending 跨取消遗留：下一独立请求会额外跑一轮。
`completion-p2c-review-regressions-red.txt` 组合 **542 tests / 23 failures** 中保留
`cancelledRunDiscardsOldPendingBeforeIndependentRequest` 的真实失败，不能把组合全部失败
归为 pending。最小修复在 runLoop.finally 中按 `worker === currentJob` 才清 pending/worker；
旧 worker 的 finally/completion 不清新 worker 及其 pending。两项握手回归现均 GREEN：
旧 pending 不跨取消存活、旧清理与新 worker 重叠时新合法 pending 保留，remote notify 仍一次。

### 主要 RED → GREEN 与最终门禁证据

|阶段|保留的实际证据|修复/最终状态|
|---|---|---|
|runtime tests-first|`.tmp/completion-p2c-runtime-red.txt`，33s、22 actionable tasks，缺接口编译 RED|随后实现编排/Receipt/删除/日期/监听；不把编译 RED 记为运行断言失败|
|共享接口 tests-first|`.tmp/completion-p2c-all-interfaces-red.txt`，36s、21 actionable tasks，缺装配接口编译 RED|coordinator 同步 ticket、platform、Application/container 装配接入|
|首次集成|`.tmp/completion-p2c-integrated-first.txt` 及同名 results 目录，504 tests / 19 failures|恢复非法 QuickAI 冷入口 container lazy；刷新测试改实际文字/字号断言，修正未知 ID shadow NPE 与旧接线位置断言，保留安全边界|
|取消/远端可见性|上述 7/2 与 542/23 组合日志|按已提交可见变化重抛取消，按 worker 身份清旧 pending；保留新 worker 所有权|
|完整门禁首次|`.tmp/completion-android-full-first.txt` 与保存 XML：151 suites/1539 tests 的单次 CalendarOverflow After 数据库删除 false；Boot protected-broadcast lint 另有红灯|Boot 首 guard 最小修复；Room 只新增 8 项诊断，不改冻结 test/helper，不认定历史 teardown 根因已解决|
|后续完整 JVM|152 suites/1547 tests 全绿，继而最新 153 suites/1573 tests 全绿|历史失败保留；新增回归与既有测试共同通过|

最新日志 **`android/.tmp/completion-android-launcher-final-gates.txt`**：

- **153 suites / 1573 tests；UI 56 suites / 641 tests；failure/error/skip 全部 0。**
- CloseGuard/SQLiteConnectionPool/SQLiteDatabase leaked/RoomDatabase leaked 扫描均为 0。
- lintDebug、lintRelease、detekt、ktlint、release signing policy、Google/native runtime、
  resolved versions/strict dependency locks、assembleDebug、APK 无 native 等本轮 Android
  门禁 PASS；未放宽规则或增加依赖绕过失败。
- **BUILD SUCCESSFUL in 6m 36s；96 actionable tasks：35 executed、61 up-to-date。**
  不将 96 项全部写成重新执行；上述 JVM 汇总来自主 agent 对本轮结果的统计。

剩余：实际设备 reboot/日期边界/Widget 生命周期与交互验收、完整发布收口及其他平台
交付结果由主 agent 单独完成和记录。Room 诊断握手只证明 close 请求已登记，不能证明
close 实际执行先于 query release；历史 Baseline5 NON-REPRODUCIBLE 裁定保持。

## 2026-09-06 完整门禁 Boot lint 修复证据（历史阶段）

主 agent 报告本轮完整门禁 96/96 tasks 已执行结束：JVM cleanup 1 项失败，lintDebug/lintRelease 均报告同一个 `WidgetBootReceiver.onReceive` 的 `UnsafeProtectedBroadcastReceiver`，其余 static/safety 与 49 项 policy 通过。cleanup 由主 agent 独立处理，不归本次平台修复，本轮不记为全绿。

已读取 debug 证据 `app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt`：action 已由 `isSystemBootShape` 验证，但 lint 不识别跨 helper 检查。经授权仅在 `onReceive` 首个 guard 直接增加 `intent.action != Intent.ACTION_BOOT_COMPLETED`，与原 shape 校验合并为同一 return。既有 user_handle 空/唯一非负 Int 允许规则、其余载荷拒绝、non-exported、finish-once、9 秒上限不变；不加 Suppress，不调整 lint 配置。

本次只改这一行生产 guard 并记录证据，未运行 Gradle。修复后 debug/release lint 与最终完整门禁均待主 agent 重验；平台文件已冻结。

## 2026-09-06 取消 pending 回归补充（历史 tests-only 计划）

用户授权新增同步取消边界修复：P2C WebDavSyncRuntime 重抛 SyncCancellationException 后，旧 SyncCoordinator 在取消退出时可能留下 pending，令下一次独立请求额外执行一轮。此补充写集由 coordinator agent 独占 `android/app/src/main/java/com/molotov/clender/sync/SyncCoordinator.kt` 与新 `android/app/src/test/java/com/molotov/clender/sync/SyncCoordinatorCancellationPendingTest.kt`；当前只写测试与本节，生产修改须主 agent RED 后再授权。AppContainer/Application/WebDav/runtime/platform 仍归既有 agent，不修改其文件。

风险与接口：不新增 API、依赖、持久化或网络触发。清理旧 pending 必须按 worker 身份归属进行；取消旧 worker 时新 worker 可能已经入场，旧 finally/completion 不得清掉新 worker 或其合法 pending。remoteVisibleChanged 取消通知必须仍恰好一次，不能通过吞取消或恢复旧上传回路解决。回滚仅撤销本补充精确 diff。

|测试|确定性步骤|验收|
|---|---|---|
|旧 pending 不跨取消存活|首轮握手挂起→合并请求→首轮抛 remoteVisibleChanged=true 的取消→等待 idle→重新请求|总 runSync=2、remote notify=1、普通 failure=0；owner 仍活跃|
|旧 finally 不清新所有权|首轮捕获自身 Job 并在可控 NonCancellable 清理握手挂起→仅取消旧 Job→新请求获得 worker→再次请求形成新 pending→释放旧清理→运行新两轮|总 runSync=3（旧1+新2）、remote notify=1；旧 finally/completion 不抹掉新 pending|

测试只用现有 coroutines-core 的 runBlocking、CompletableDeferred、Job 与结构化清理，不使用 sleep、轮询、反射、额外依赖或延长超时。第二项 NonCancellable 仅为 test-only 受控旧任务清理窗口，生产不增加不可取消区。完成定义：主 agent 先记录旧实现真实断言 RED，再授权修复、统一聚焦/完整门禁、同步最终文档与提交；当前未运行 Gradle、未声明 PASS。

状态：2026-09-06 实现及 Android JVM/静态验证完成；真实 boot/date/Widget 验收仍进行中。

## 目标与边界

完成原有产品约定的本地 mutation、同步远端可见变化、配置、前台、日期边界与 boot 刷新。复用 P1 policy、P2A coordinator 与同一 DataStore/Room/widget scope，不新增后台网络、通知、Alarm、periodic/expedited/foreground work，不改依赖或 schema。

## 设计与数据接口

新增 app/widget 自动刷新编排，注入平台 owned ID 查询、本地 update、日期 schedule/cancel、Clock/动态 ZoneId。合并并发请求，逐实例隔离，异常仅有限状态，取消必须传播；关闭后不再排队。使用平台当前 owned IDs，不能依赖配置 registry 才发现 configuration_optional 实例。无实例不打开 Room/秘密/网络、不创建日期 work；删除最后实例取消唯一 work。

本地 mutation 观察现有非秘密 version（忽略初始 emission），远端通过现有 SyncCoordinator 的 onRemoteVisibleChanged 成功/失败后已提交信号接入（包括 SyncCancellationException.remoteVisibleChanged，不把远端 apply 转成本地 mutation）。配置保存保持一次 DataStore edit 和目标刷新。前台生命周期只注册一次且关闭时解绑；进程重建或安装 Widget 后恢复观察。

日期调度使用 P1 DATE_BOUNDARY 的真实本地次日 delay、固定 unique name、OneTimeWorkRequest 和 REPLACE；只在恢复/日期变化/实例变化时重算，避免事件密集变化无限延期。Worker 只重建本地并安排下一日期，不返回自动网络重试；Boot receiver non-exported，只接受系统 BOOT_COMPLETED，不接受用户数据或启动Activity，finish-once且9秒上限。时区/时钟变化下至少前台与下次系统日期任务重新计算动态本地时区。

## 影响文件与 agent 分工

- runtime agent：新增 app/widget 编排、widget Worker/Boot/调度 adapter、这些新增类的 tests。
- 主 agent/独立集成 agent：AppContainer、ClenderApplication、ClenderWidgetProvider 必要接线，WebDavSyncRuntime callback、source Manifest、exact policy 和生产集成 tests。
- 共享文档：T47、progress、两级 AGENTS、Android designs。

接口影响只为本地刷新端口/生命周期；无持久化 wire 变更，Worker input不带事件/秘密。

## 先行测试矩阵

平台审查补充：AOSP UserController 会在 BOOT_COMPLETED 添加 `android.intent.extra.user_handle`，不得把真实系统广播按“含任意extra”拒绝。接收空 extras 或此唯一非负 Int 字段但不用于选择用户，拒绝其他字段/data/selector/clip/categories；保持receiver non-exported、系统protected action与9秒finish-once。依据：[AOSP UserController](https://android.googlesource.com/platform/frameworks/base/+/7d7ee57b4dbb/services/core/java/com/android/server/am/UserController.java)。需测试真实系统形状与实际模拟器reboot，不能仅手动调用onReceive宣称boot完成。

|范围|正常|边界|非法/异常|回归|
|---|---|---|---|---|
|编排|七类触发按policy选择目标|多实例/合并、无实例、配置optional、删除|update异常、超时、cancel、close|无网络、无EventService，coordinator generation防迟到|
|信号|本地成功、远端可见提交、前台|初始version、重复绑定、重启已有实例|同步PUT失败/取消但已apply、无可见变化|远端零本地mutation/上传回路，失败本地写零刷新|
|日期|next local midnight one-time|DST gap/overlap、时区、当天worker重排|非法时间、安全取消、最后实例删除|唯一name、无periodic/expedited/foreground/alarm，WorkManager真实测试|
|Boot|合法系统启动本地恢复|API26/36、无实例|错action/extras/取消/失败|non-exported、finish一次、9秒、无网络/Activity|
|集成|真实AppContainer复用/渲染|关闭顺序、late mutation/delete|资源释放/CloseGuard|QuickAI/Configure/Edit/Refresh、AI/WebDAV/Room全量|

## 步骤、完成定义、回滚

- [x] tests-first 红灯审查后实现 runtime 与生产接线。
- [x] 聚焦→完整JVM→lint/detekt/ktlint/strict locks/Manifest/native/APK/policy。
- [ ] 真实设备 boot/date/Widget 验收，由主 agent 继续执行并留存证据。
- [ ] Windows198/imports/check/PyInstaller/正式exe smoke/data前后只读摘要。
- [ ] 文档、AGENTS、diff/秘密/生成物审核、独立提交。

回滚仅撤销本阶段精确 diff/提交，不操作真实数据。风险：Worker REPLACE 对当前 work 的取消语义须用真实 WorkManager 测试证明 successor 留存；生命周期解绑不能和主线程 join 死锁；远端失败后的已提交变化必须刷新。未通过以上门禁不得标完成。

## 2026-09-06 runtime API 与 tests-only 设计（尚未实施）

本节按用户最新分派追加，替代上文 runtime/platform 合并写集：runtime agent 负责纯编排及其测试，platform agent 负责平台 adapters，主 agent 负责共享接线与 WebDAV callback。当前只修改本任务文档；不新增 src/test 或生产类型，不执行 Gradle，不修改 progress/AGENTS，不单独提交。测试和实现须等当前新建入口/QuickAI 阶段收口并收到实施分派；以下代码是拟冻结接口，不代表已存在或通过编译。

### 最小公共 API

类型归属 `com.molotov.clender.app.widget`，不依赖 Activity、Lifecycle、WorkManager 或具体 Room/DataStore。

```kotlin
interface WidgetRefreshReceipt {
    val accepted: Boolean
    suspend fun await(): WidgetRefreshCompletion
}

enum class WidgetRefreshCompletion {
    COMPLETED, NO_WIDGETS, INVALID_TARGET, SUPERSEDED, CLOSED,
    OWNERSHIP_FAILED, UPDATE_FAILED, SCHEDULE_FAILED, TIME_OVERFLOW
}

interface WidgetAutomaticRefreshPort {
    fun restore(): WidgetRefreshReceipt
    fun request(
        trigger: WidgetRefreshTrigger,
        targetId: Int? = null
    ): WidgetRefreshReceipt
    fun instancesChanged(deletedIds: Set<Int> = emptySet()): WidgetRefreshReceipt
    fun close()
    suspend fun awaitClosed()
}

fun interface WidgetOwnedIdsPort {
    suspend fun ownedIds(): Set<Int>
}

interface WidgetLocalUpdatePort {
    // 同步、非阻塞；只失效已有实例票据，不创建 coordinator/Room。
    fun invalidate(ids: Set<Int>)
    suspend fun update(id: Int)
    suspend fun delete(ids: Set<Int>)
}

interface WidgetDateWorkPort {
    // 返回表示平台 enqueue/cancel operation 已完成，而非仅提交异步操作。
    suspend fun replace(schedule: WidgetDateBoundarySchedule)
    suspend fun cancel()
}
```

`WidgetAutomaticRefreshRuntime` 构造注入现有 widget `CoroutineScope`、以上三个端口、`StateFlow<Long>` mutationVersion、`Clock`、`() -> ZoneId`。内部只创建现有 widget Job 下的子 Job/消费任务，不创建第二个应用 scope、store、Room 或 coordinator。平台 adapter 在 `update(id)` 内才取得现有 coordinator，并按该 ID 的 host options 解析 size class。

`request(DATE_BOUNDARY)` 是 Worker 唯一调用入口；`request(BOOT)` 是 Boot 入口；`restore()` 是进程恢复/首次绑定入口。七类 trigger 全部消费 P1 policy；restore/instancesChanged 额外执行实例恢复与日期协调，不新增 domain trigger。只有 CONFIGURATION_CHANGE/MANUAL_LOCAL_REFRESH 接受且要求正 targetId，其他 trigger 携带 targetId 判 INVALID_TARGET；目标还须与平台 owned IDs 求交。`instancesChanged` 对输入复制后过滤非正 ID；空集合代表安装或实例集合重新核对。

### Receipt 与独立 widget scope 所有权

- 所有入口同步返回 Receipt，不在调用线程查询 DB、等待平台 operation 或运行渲染。accepted=false 用于 CLOSED/INVALID_TARGET 的同步拒绝；平台查询后的未拥有目标仍可能是 accepted=true、最终 INVALID_TARGET。
- 每个 accepted 请求有独立 completion，内部可合并执行，但不对外暴露 Deferred/Job/cancel。Receipt 的完成对象不以调用者 Job 为 parent；工作从注入的 widget scope 启动。
- `await()` 只等待结果。调用者取消/超时必须向调用者抛 CancellationException，但不能取消已接收工作、其他等待者或共享 completion；同一 Receipt 可重复/并行 await。Worker 被 REPLACE 取消即属于此情形。
- COMPLETED 只表示本轮端口操作已返回，不保证每个 Widget 都是 Ready；现有 coordinator 可合法发布有限错误状态。多实例单项异常继续其余实例；有限失败按固定优先级汇总，不保存 Throwable/事件正文。CancellationException 不转换成普通失败。
- 合并前入队的请求等待同一轮；执行期间到达的请求必须进入下一轮，不能被正在执行的旧快照提前完成。全量请求可覆盖待执行的目标集合，但须保留各 Receipt；不得使用会丢失 Receipt 的裸 conflated channel。建议锁内 pending 状态加单个唤醒信号，执行端口期间不持有锁。
- owner scope 取消或 close 后，所有未完成 Receipt 完成 CLOSED，awaitClosed 可确定性结束；已返回的完成结果不改写。receipt.await 被取消与 runtime 被取消是两件事。

### closed/delete 防迟到与装配要求

- runtime 入口在同一短锁内检查 closed、登记 pending/epoch。close 同步置 closed、清理待处理、完成 Receipt 并取消自身子任务；不 join、不切 Main、不取消整个注入 scope，也不自动 cancelUniqueWork。awaitClosed 只 join 自身任务。
- 主 agent 在主线程同步解绑唯一 lifecycle observer，然后 close runtime/coordinator，再取消并等待 widget scope，最后释放 DataStore/Room。被等待任务及其 finally 禁止要求 Main dispatcher；不得从 runtime 自身消费 Job 调用 awaitClosed。
- instancesChanged(deletedIds) 在返回前登记删除屏障并调用非阻塞 invalidate；删除失效不能排在 8 秒查询后。后台清配置与刷新消费任务是同一 widget scope 的不同子任务，使用既有逐实例配置 Mutex；delete 完成后再重新核对 owned IDs 和日期调度。
- 每个排队目标捕获实例 epoch；执行前再次核对屏障/ownership，删除后的旧 pending 丢弃为 SUPERSEDED。删除屏障保留到明确后续实例恢复核对，不能被旧 ownership 查询结果清除；同 ID 再次合法出现必须使用新 epoch。
- 仅 runtime epoch 不足以阻止已进入 coordinator 的迟到 upsert/render。共享接线须把 invalidate 接入既有 coordinator generation，并在配置写入/最终发布边界保证删除票据优先；不得仅新增一个与 coordinator 无关的计数器。不存在的 coordinator 不因 invalidate 初始化；冷进程确需清理已删除配置时允许访问现有 store，但不得因此打开 Room。
- close 的同步承诺是拒收和发出取消；已提交平台 operation 可能仍在完成，不能宣称 close 返回瞬间所有外部副作用已结束。awaitClosed 是释放完成边界。已经持久化的日期 successor 在普通 close 后保留；删除最后实例才取消日期 work。

### 日期调度与无实例边界

- 空 owned IDs 不读取配置/appearance/repository、不创建 coordinator、不 enqueue；初次空恢复允许只做固定名称取消以清理上个进程遗留任务。无实例时不启动 mutation collector；首次有实例先建立观察再做恢复刷新，忽略初始 emission，避免订阅窗口漏掉本地变化。
- P1 DATE_BOUNDARY plan 只用于计算调度，其他刷新不因此额外渲染。缓存已成功调度的本地日期、ZoneId、目标午夜 Instant 和 owned IDs；失败不更新缓存。每轮捕获一次 clock/zone；前台发现变化重新计算。普通 mutation/configuration/manual 不无条件 REPLACE。
- DATE_BOUNDARY 必须安排 successor，即使当前日期与缓存相同（例如旧 worker 因时钟变化提前运行）。有限本地更新失败不阻止日期重排；真正 owner 取消则传播。replace/cancel 串行，不能让最后实例删除后的迟到 replace 留下任务；正在进行的 replace 完成后必须由删除协调执行 cancel，并让删除 Receipt 等待此屏障。
- Worker 调用 `request(DATE_BOUNDARY).await()`；replace 在 runtime 消费 Job 内完成，不受 Worker 等待者取消影响。旧 Worker 可以 CANCELLED，成功标准是 successor 留存；平台 adapter 不在 onStopped/finally 取消 unique work，不返回自动 retry。进程被杀的跨进程可靠性依赖 WorkManager 持久化及下次 restore，Receipt 仅进程内，不作持久化承诺。
- 动态时区还须传入既有 coordinator 的时间策略，每次 update 捕获一次；仅 scheduler 动态化会留下显示旧时区缺陷，此共享修改归主 agent。

### tests-only 用例清单与交付顺序

先准备下列测试，实施授权后才写入 `app/src/test/java/com/molotov/clender/app/widget/`。使用现有 coroutines-test、可控 Clock/ZoneId、CompletableDeferred 握手和记录调用的纯 fake ports；不依赖 sleep/GC/重试/扩大超时。期望来自契约，不按内部字段或循环实现断言。

|拟定 suite|必须证明的用例|
|---|---|
|WidgetAutomaticRefreshRuntimeTest|七类 trigger 的全量/目标选择；非法 target、负/重复 owned IDs；optional 无配置仍更新；空实例零 update/Room supplier 调用；重复 restore 幂等；ownership 查询失败不得当空集合删除 work；单实例失败不阻断其他实例|
|WidgetRefreshReceiptTest|调用者取消 await 后工作仍完成；两个等待者只取消一个；同 Receipt 重复 await；执行中到达请求由下一轮处理；合并请求全部完成；close 前后 accepted 与 CLOSED；owner scope 取消没有悬挂 receipt|
|WidgetAutomaticRefreshDeletionTest|暂停查询后删除，先观察 invalidate，再放行旧查询，零迟到 render/upsert；pending 目标删除为 SUPERSEDED；删除 A 不阻断 B；迟到 ownership 结果不恢复删除目标；后续合法同 ID 新 epoch；冷删除只清 store 不开 Room|
|WidgetAutomaticRefreshDateTest|恢复首次排期；普通高频 mutation 不延期；DATE_BOUNDARY 同日执行仍重排；DST 23/25 小时、动态时区和午夜跨越；时间溢出有限失败；更新失败仍重排；replace 失败下次恢复可重新安排；暂停 replace 后删除最后实例，最终 cancel 且零 successor；close 不删除已提交 work|
|WidgetAutomaticRefreshLifecycleTest|初始 mutation emission 忽略、后续变化触发；首次 restore 与订阅间 mutation 不丢；无实例不订阅/最后实例停止；重复恢复仅一个 collector；close 中挂起 update/ownership/schedule 退出且 awaitClosed 完成；运行于非 Main 测试 dispatcher 证明清理不依赖 Main|

最后两类真实边界不由纯 fake 宣称通过：platform agent 负责 WorkManager 2.11.2 的运行中 REPLACE/successor、Boot finish-once/9 秒/API26/36；主 agent 负责真实 coordinator 删除竞态、主线程 AppContainer.close、动态时区、无实例 holder 初始化计数、WebDAV apply 后成功/PUT失败/取消的刷新及零本地 mutation。delete 的迟到发布测试必须至少有一项使用真实 coordinator/受控 repository，不能只验证 fake.invalidate 被调用。

实施顺序：先交付纯 runtime 测试并记录预期缺类型编译红灯，再实现 API/编排、跑聚焦验证；platform agent 仅消费冻结接口；主 agent 串行执行 Gradle 与共享集成验证。未实际执行前不得记录 PASS 或测试数量。

### 最新互斥写集与本次验证

- runtime agent：新增 `app/widget/WidgetAutomaticRefreshRuntime.kt`、`WidgetAutomaticRefreshPort.kt`、`WidgetRefreshReceipt.kt` 与上述纯 runtime suites（必要内部纯辅助类亦限定同目录，实施前列明）。不修改现有 coordinator、AppContainer、Application、Manifest、WebDAV 或平台类。
- platform agent：新增 widget scheduler/Worker/Boot/owned-ID adapter 及其测试；不改 runtime 接口、AppContainer、Application、Manifest。
- 主 agent：现有 coordinator 的失效/时间策略最小扩展、AppContainer/Application/Provider/configuration/local-refresh 接线、生命周期绑定、WebDAV callback 与取消重抛、Manifest/exact policy、真实集成测试及共享文档。
- 本次仅追加设计文档，未执行 Gradle、未写 src/test/生产源码。共享工作区现有 QuickAI/新建入口/T48 改动保持原样；完整构建、文档同步与提交留给主 agent 阶段收口。本节回滚只删除此追加段落。

## 2026-09-06 统一 RED 后的依赖对象与原子 admission 接口

主 agent 已执行 runtime RED（33 秒、22 tasks，缺预期 runtime 类型）及统一接口 RED（36 秒、21 tasks，日志 `android/.tmp/completion-p2c-all-interfaces-red.txt`）。解除冻结后按用户授权实施；本节替代前述七参数构造方式，并增加 prepare admission。禁止新增 lint suppression 或修改 lint 规则，原 runtime `LongParameterList` suppression 已移除。

Pascal 的生产装配固定消费以下 API；scope 仍为既有 widget scope，不放入依赖对象：

```kotlin
data class WidgetAutomaticRefreshDependencies(
    val ownedIds: WidgetOwnedIdsPort,
    val localUpdates: WidgetLocalUpdatePort,
    val dateWork: WidgetDateWorkPort,
    val mutationVersion: StateFlow<Long>,
    val clock: Clock,
    val zoneId: () -> ZoneId
)

WidgetAutomaticRefreshRuntime(scope = widgetScope, dependencies = dependencies)
```

`WidgetLocalUpdatePort` 新增 `fun prepareUpdate(id: Int): suspend () -> Unit = { update(id) }`。默认实现只返回 closure，保持原 fake 可用；Pascal 的 production override 调用 Lagrange 的 coordinator.prepareUpdate，同步捕获 generation/time 票据，不访问配置、Room、render 或其他数据端口。provider/runtime 构造不得提前取会打开 Room 的 coordinator；纯票据准备也不能经 lazy supplier 间接打开 Room。

runtime 的 `WidgetRefreshInbox.prepare(ticket, id, prepare)` 与 deletion register 使用同一短锁：锁内检查 closed/删除版本/屏障，再调用纯 prepare 返回 closure；`WidgetRefreshBatch` 在锁外调用 closure。不能退回 `current()` 后直接调用 `update()`，不能持锁执行 suspend update，也不增加第二套 production barrier。删除先登记屏障，使尚未准备的旧请求拒绝；已准备的 generation 由 coordinator.invalidate 失效。coordinator 最终发布、配置写入防迟到门禁仍由真实集成测试验证。

新增依赖对象文件 `app/widget/WidgetAutomaticRefreshDependencies.kt`；调整既有本轮 runtime/port/inbox/batch 与测试 fixture。其余共享 coordinator、container、manifest、平台 adapter 写集保持互斥。

runtime 原 29 项测试保留，Deletion suite 新增 6 项：默认 prepare 零提前 update；deleted/closed 拒绝且 supplier 零调用；generation 在准备时捕获、删除后旧 closure 零写入；合并请求仅 prepare/执行一次；prepare 失败隔离到实例；挂起 closure 不延迟删除 admission/清配置。合计 35 项拟聚焦测试，未由 runtime agent 运行 Gradle，新增测试的 RED/GREEN 与 lint 均待主 agent 串行验证，不声明通过。
