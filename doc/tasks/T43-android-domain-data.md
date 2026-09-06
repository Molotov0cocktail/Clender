# T43：Android 事件领域、Room 数据层与日历纯逻辑

## 目标

实现事件/对话模型、严格 EventService、Room schema/DAO/repository、跨日查询、同步 metadata 事务和月/周/日日历布局纯逻辑。

## 非目标

- 不实现 HTTP、AI 协议、Compose screen 或 Widget UI。

## 输入文档

- Android detailed design 第 2、3、10 节；T42

## 影响文件

- `android/app/src/main/.../core/model/`
- `android/app/src/main/.../domain/event/`、`domain/calendar/`
- `android/app/src/main/.../data/local/`
- 对应 `test/`、`androidTest/` 与 Room schema export
- 根 `.gitignore`（仅将 Windows 运行数据规则锚定到仓库根，避免误忽略 Android `data/local` 源码）
- `android/app/src/main/AndroidManifest.xml`（移除 Room 未启用的多进程失效服务）
- `android/README.md`、`android/AGENTS.md`、Android 详细设计、根 `AGENTS.md` 与共享进度（同步已验证架构、命令和风险）

## 接口/数据影响

- Room v1：events/conversations/messages；EventRepository、ConversationRepository、EventService、CalendarLayoutEngine。
- event sync 字段严格兼容 desktop schema；本地 Room 不读取 Windows DB。

## 风险

- 墓碑被普通查询泄露；跨日/半开区间错误；事务失败留下部分 metadata；layout 视觉高度污染真实碰撞。
- 根 `data/` 忽略规则若未锚定会吞掉 Android 数据层源码；修复必须保持 Windows `data/` 继续被忽略。

## 实施步骤

- [x] 第一轮只写模型/验证/DAO/事务/查询/layout 失败测试并运行记录红灯。
- [x] 先增加 Git ignore 策略回归，证明 Android `data/local` 源码当前被误忽略，再将根运行数据规则最小化锚定。
- [x] 生成 debug/release merged manifest 后确认并移除未使用的 Room 多进程服务，不扩大组件暴露面。
- [x] 实现 immutable model、严格 codec 和 Clock/UUID 注入。
- [x] 实现 Room entities/DAO/database/repositories 与 schema export。
- [x] 实现 EventService、类型转换、local mutation 事件。
- [x] 实现跨日 overlap、month counts、CalendarLayoutEngine 与 EventStateClassifier。
- [x] 聚焦通过后做 diff/SQL/线程/数据隔离复核。

## 测试与检查

```powershell
.\android\scripts\gradle.ps1 testDebugUnitTest --tests "*.domain.*" --tests "*.data.local.*"
.\android\scripts\gradle.ps1 connectedDebugAndroidTest
```

覆盖正常、00:00/23:59、闰日、跨日、0/480/481/Int 大值、Unicode/长值、非法时间/ID/字段、UUID 唯一、事务 rollback、墓碑、Flow、cascade、2/3/N lane、marker/overflow、坏记录、确定性和 migration harness；领域/远端数据只拒绝负时长，不施加 480 上限。

## 红灯证据

- 中断恢复后的首轮聚焦编译失败于全部待实现模型、codec、EventService、Room/DAO/repository 与 layout 类型，证明 7 个已落盘测试文件未被误当成既有能力。
- 根忽略规则回归先稳定得到 33 项 policy 中 2 failure：Android `data/local` 生产源码与测试被根 `data/` 误忽略；锚定为 `/data/` 后 33/33 转绿且 Windows 运行数据继续忽略。
- Room/KSP 首轮解析在沙箱网络失败；获准联网后版本成功解析。后续分别捕获缺 test-core、测试装配、strict lock state、格式与静态分析问题，均保留依赖锁和校验元数据后转绿。
- 引入 Room 后 generated manifest 在 debug/release 各产生 1 个 allowlist failure，来源为未使用的 `MultiInstanceInvalidationService`；源 Manifest 明确移除后两变体均转绿。

## 实施与验证结果

- 锁定 Room `2.8.4`、KSP `2.3.9`、AndroidX Test Core `1.6.1`，更新 strict lock state 与 SHA verification metadata；未引入 Google services 或 native runtime。
- 新增不可变事件/对话/消息模型、严格四位年份墙钟与 UTC 微秒 codec、整体重验的 `EventService`、领域 repository 接口、Room v1 三表/索引/FK cascade/schema export、半开 overlap、墓碑与同步快照、远端/对话真实事务 rollback。
- 日历纯逻辑覆盖跨日裁剪、月度相交计数、真实碰撞与视觉高度隔离、确定性 lane/cluster/overlap/overflow；时态分类覆盖零时长 reminder、半开 current、最早 future 并列 next 与极值保护。
- 聚焦结果：core/domain 38/38；Room/Robolectric API 26/36 30/30。全量 Android 为 12 suites、72 tests、0 failure/error/skip；完整离线 strict Gradle 门禁 96 tasks 成功，policy 33/33、bootstrap、generated manifest、boundary、lint、detekt、ktlint、依赖/无 Google/无 native 与 debug APK 审计全部通过。
- 无签名 `assembleRelease` 按预期失败且只显示 `Release signing credentials are required`。本地忽略的 debug APK 为 8,041,814 bytes，SHA-256 `D0718AAB9DE19FB612506FA053AF6285BB4F6448862625092BFA205AF007A79B`。
- Windows 回归 171/171、全模块导入与 `build.py --check` 通过；完整 PyInstaller 与隔离 silent primary/secondary 冒烟通过。最终 exe 45,581,094 bytes，SHA-256 `3D4C56A49375E648076277E868CE06ECAD815A553B2B39B4C23723950A94E1C8`；`dist/data` 构建/冒烟前后保持 5 文件、97,513 bytes、摘要 `E21C49D4C01FEC90F813EC38F7BC6BC29B955ADB25A430FB22F8D7A4987A0397`。
- 独立审计最终无阻塞。当前 DB 为 v1，无历史版本可迁移；以空库 v1 实际打开和导出 schema/FK/index 校验作为基线，未来升 v2 必须增加显式 migration 与真正跨版本 harness。

## 回滚方式

删除本任务 packages/tests/schema；DB 尚未对真实用户发布，无真实迁移。

## 完成定义

- [x] 新增测试有旧/空实现失败证据并全部转绿。
- [x] 普通查询不含墓碑，所有本地事件写入经 EventService；远端 apply 使用独立事务边界且不发本地 mutation。
- [x] schema export、事务、跨日和 layout 契约通过。
- [x] 按根门禁完成 Windows 全量/构建/exe/`dist/data` 核验并更新文档；聚焦提交在最终 staged 审计后创建。
