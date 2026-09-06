# T48 Android Room shutdown verification

## 2026-09-06 最新结果与历史失败保留

本次仅依据主 agent 已执行结果更新文档，未运行 Gradle，未修改任何生产、诊断测试、
冻结 CalendarOverflow 测试或 ProductionActivityTestResources。

|执行阶段|JVM 结果|证据解释|
|---|---|---|
|首次完整门禁|151 suites / 1539 tests；1 failure|CalendarOverflowDetailPlaceholderTest 的 After 发生一次数据库删除 false；业务断言通过，完整历史失败保留|
|增加独立 Room 诊断后|152 suites / 1547 tests；failure/error/skip = 0|新增四方法、API26/36 共 8 项诊断通过；没有为此修改生产或冻结 helper/test|
|后续功能回归整合|153 suites / 1573 tests；failure/error/skip = 0|主 agent 最新完整 JVM 全绿；当前 lint 仍在进行，不将其写成所有发布门禁已通过|

首次失败 XML 完整保存在
`android/.tmp/completion-android-full-first-results/TEST-com.molotov.clender.ui.calendar.CalendarOverflowDetailPlaceholderTest.xml`，
执行日志为 `android/.tmp/completion-android-full-first.txt`。失败是
`Production Activity test resource cleanup failed`，suppressed 仅有
`Failed to delete the isolated production test database`；不能因后续全绿删除、覆盖
或弱化该记录。历史 Baseline5 的 **NON-REPRODUCIBLE** 裁定保持，不认定历史根因已解决。

诊断使用真实文件 Room、兼容 OpenHelper/query/cursor 路径和合成空库，证明八条受控
路径完成取消、释放和单次删除；它既没有识别原失败时的 Windows 文件句柄拥有者，
也没有重现相同 teardown 失败。`closeRequested` 信号在调用 Room.close **之前**发出，
控制线程接到信号便可释放查询，因此握手**不能证明 close 实际执行先于 query release**，
更不能证明其内部 barrier 已入场。不得将诊断 GREEN 提升为对旧失败机制的确认或排除。

## 目标与证据边界

以独立、文件型 Room 诊断 suite 验证取消实际查询后，close 返回与 cursor/helper
资源释放的顺序，以及完成后单次数据库删除。首次全量 1539 tests 的唯一 JVM
失败为 CalendarOverflowDetailPlaceholderTest 的 After：隔离数据库删除返回 false；
业务断言通过。这不足以证明查询关闭竞态，更不足以归因于导航 guard。

## 非目标与精确写集

- 新增本文件。
- 新增 `android/app/src/test/java/com/molotov/clender/data/local/RoomShutdownOwnershipTest.kt`。
- 不修改生产、依赖、规则、冻结 CalendarOverflow 测试或 ProductionActivityTestResources。
- 不运行 Gradle；主 agent 统一执行。AGENTS/progress 与最终门禁由主 agent 维护。

## 测试矩阵

四方法分别为 findById 冷打开、findById 已打开、observeRange 冷打开、observeRange
已打开；每项运行 API 26/36。热路径先完成一次真实查询；冷路径不预开数据库。
空数据库与正 id 7 为合成边界输入，不读取任何用户数据。

每项用 test-only SupportSQLiteOpenHelper/Database/Cursor 包装，在目标 events SELECT
实际取得 cursor 并完成首次 moveToNext 后暂停。主测试等待该信号，取消调用方，
独立线程记录 close 请求并调用真实 Room.close，再释放查询。等待 job、关闭线程及
Room executor 全部结束；断言 cursor 关闭先于 close 返回、helper 关闭先于 close
返回、取消无正常结果交付；最后只调用一次 Context.deleteDatabase 并断言成功。
轨迹仅记录有限阶段名，不含 SQL 参数、路径或正文。失败断言附轨迹用于判断顺序。

## 风险、清理与实施步骤

1. 先记录本设计，再新增 suite；不把预期断言当成已经验证的事实。
2. Latch 只控制实际查询暂停及 close 请求顺序；不宣称 close 请求信号证明其内部
   barrier 已入场。并发阶段不强制 job 完成与 close 返回之间的无关顺序。
3. 使用有界等待只用于死锁失败保护，不用 sleep、GC、重试或超时成功推断。
4. finally 无条件放行查询、取消 scope、等待 job/线程、关闭 DB 并终止自有 executor。
   清理失败保留为 suppressed，不覆盖主失败；不得因诊断失败残留工作线程或锁。
5. 一个 suite 不能覆盖 AppContainer 所有消费者，也不能识别历史失败的 Windows
   句柄拥有者。通过只证明受控路径；失败应保留 XML/轨迹后分析，禁止盲重跑。

## 回滚与完成定义

回滚仅删除上述两个新增文件。完成定义：四方法可供主 agent 编译运行、断言及资源
所有权可审查、无生产 diff。主 agent 已执行四方法、API 26/36 共八 case，全部通过；
同轮 Boot 14 cases、两个 lint 与 detekt 通过。日志为
`android/.tmp/completion-room-shutdown-diagnostic.txt`。唯一失败为本 suite import 排序，
本轮仅调整 async import 至字典序位置，未自行运行 Gradle；后续完整门禁由主 agent 执行。
八项诊断通过仅证明受控场景，不证明或排除历史数据库删除失败的根因。
单次 delete 在 fixture 的 finally 清理中执行，所有权断言失败也会尝试完成资源清理；
清理轨迹以有限阶段名输出到测试报告。没有修改冻结文件或生产代码。

聚焦命令（从 android，使用既有隔离环境，由主 agent 执行）：

```powershell
./gradlew.bat :app:testDebugUnitTest --tests com.molotov.clender.data.local.RoomShutdownOwnershipTest
```
