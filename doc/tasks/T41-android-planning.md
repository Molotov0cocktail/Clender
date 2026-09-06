# T41：Android 架构版规划、契约与测试门禁

## 目标

在不改动 Windows 生产源码和用户数据的前提下，固化原生 Android 版的产品范围、架构、数据契约、测试矩阵、任务拆分、隔离工具链与发布策略，为后续测试先行和多 Agent 实施建立唯一输入。

## 非目标

- 本任务不创建 Android 生产代码、Gradle 工程、APK、AAB 或签名密钥。
- 不改动 Windows Python/PyQt 源码、现有测试、`data/` 或 `dist/data/`。
- 不连接真实 AI Provider、WebDAV、Google 服务或真实用户数据。

## 已确认决策

- 原生 Kotlin、Jetpack Compose/Material 3、Room、DataStore、Coroutines/Flow、OkHttp、WorkManager、Glance/AppWidget；不使用 Google Play services，不引入 NDK/native 库。
- 子项目 `android/`，应用名 `Clender`，`applicationId=com.molotov.clender`，初始 `versionName=1.0.0`、`versionCode=1`，中文默认并提供英文资源。
- `minSdk=26`、`compileSdk=36`、`targetSdk=36`；覆盖手机、平板、横竖屏、分屏、折叠/大屏和无 Google 服务设备。
- Navigation Drawer 拆分日历、事项、AI、设置、关于；默认日历；页面状态跨旋转/进程重建持久化。
- 保留月/周/日、两类事件、跨日创建与跨日逐日显示、lane/cluster/短 marker/聚合能力；时间继续使用无时区本地墙钟，WebDAV 保持 desktop schema v1 兼容。
- Android 本地数据完全独立；不读写/复制 Windows `data/`；WebDAV 只同步日程，不同步对话、设置或秘密；首次不写示例事项。
- 首版显式禁用 Android 自动备份和设备迁移，Room/DataStore/秘密均不自动恢复；卸载即删除 Android 本地数据，未来迁移另设显式导入/导出。
- AI 保留多对话、Thinking、模型获取、预算和受限 JSON CRUD；删除直接执行；允许类型转换；进后台或进程终止时取消，不后台续跑、不自动重试。
- Widget 显示今日事项/current/next，点击事项打开编辑，AI 按钮打开快速输入 Activity；支持多实例、时段/透明度/字号/主题/尺寸；不直接输入、双击、置顶/置底或精确分钟闹钟。
- 同步在本地变更、手动请求和应用回前台时触发；无周期后台同步；API Key/WebDAV 密码使用 Android Keystore 保护。
- 本轮不新增系统通知；Android 不实现托盘、单实例、开机静默启动；设备重启只恢复 Widget 状态。
- 允许隔离下载 JDK/SDK/Gradle/模拟器依赖；接受 x86_64 多 API 模拟器和 universal APK 无 native 库审计，不要求 ARM64 实机或设备云。
- 生成本地 release keystore，严格忽略并要求用户备份；生成 universal release APK 与 AAB，但二进制不提交。
- Git 使用 `codex/android-architecture` 分支，最终只推送 Gitee `Molotov`；GitHub 由用户手动同步。允许更新根 `AGENTS.md`、新增 Android CI，并按根门禁最终重建 Windows exe、只读核验 `dist/data/`。

## 影响文件

- `doc/android-architecture-proposal.md`
- `doc/android-architecture-high-level-design.md`
- `doc/android-architecture-detailed-design.md`
- `doc/android-prompt.md`
- `doc/tasks/T41-*.md` 至 `T48-*.md`
- `doc/tasks/progress.md`
- `AGENTS.md`

## 接口/数据影响

- 本任务只定义 Android 契约，不更改现有 Windows 接口和数据。
- Android 与桌面唯一共享边界为 WebDAV `clender-events.json` schema v1；本地 Room 主键不得作为跨设备标识。
- Android 生成物、SDK、Gradle cache、keystore、配置与数据库必须留在忽略路径，不能进入 Git。

## 风险

- Android 工具链和依赖需要大规模联网下载；版本不兼容会阻塞构建。
- Widget/Doze/Launcher 行为存在厂商差异，模拟器不能证明所有 OEM 行为。
- WebDAV 无时区墙钟与 LWW 依赖设备时钟；墓碑持续增长风险不变。
- Compose 日历几何、重叠命中与大字体/窄屏组合容易产生可访问性回归。
- release keystore 丢失会导致后续版本无法覆盖安装，必须由用户安全备份。

## 修改前测试矩阵

| 范围 | 正常路径 | 边界值 | 非法/异常 | 回归/兼容 |
|---|---|---|---|---|
| 领域事件 | reminder/timespan CRUD、类型转换、跨日 | 00:00、23:59、闰日、零/480/481/Int 大值、并列重叠 | 空标题、坏时间、倒序、负数、坏 ID | desktop schema 字段、半开区间、旧记录 |
| Room | 插入/更新/墓碑/查询/事务 | 空库、100000 条上限、并发读取 | 唯一 UUID、事务中断、损坏迁移 | schema v1、进程重建、索引 |
| 日历 | 月/周/日、lane/cluster、marker/overflow | 极窄/极宽、N 重叠、跨日 | 坏记录跳过 | 选中日期、24h 语义、主题/字号 |
| AI | 多对话、解析/执行、Thinking、模型获取 | 长上下文、Unicode、切会话 | 无配置、超时、断网、非 200、429/5xx、畸形 JSON、危险操作 | 响应绑定、单 in-flight、后台取消、不重试 |
| WebDAV | PROPFIND/GET/PUT、ETag、LWW、墓碑 | 404、412、相同时间戳、5 MiB | 非 HTTPS、401/403/5xx、重定向、畸形文档 | 与 desktop schema v1 双向契约 |
| 导航/UI | Drawer 三视图、CRUD、设置、关于 | 旋转、分屏、平板、200% 字体 | 空态、错误态、权限/网络拒绝 | 状态恢复、返回键、Light/Dark、中英 |
| Widget | 添加/配置/刷新/多实例、事项与 AI 跳转 | 小/中/大、午夜、current/next 并列 | 进程杀死、重启、Doze、无数据 | 数据/同步后刷新、PendingIntent 安全 |
| 安全/构建 | Keystore 加密、release APK/AAB | 空/Unicode/长秘密 | 解密失败、备份恢复、签名失败 | 无明文 HTTP、无秘密/数据/native 库 |
| 设备矩阵 | API 26/29/31/33/35/36 手机 | API 33/36 平板、横竖屏 | 离线、低内存、Doze | universal APK 安装/冷启、无 Google 服务 |

## 实施步骤

- [x] 完成仓库、功能、工具链和发布只读审计。
- [x] 集中确认全部产品、兼容、签名、工具链和 Git 决策。
- [x] 编写 proposal、high-level design、detailed design 和 Android 控制提示。
- [x] 拆分 T42–T48，明确文件所有权、依赖、失败用例和验证命令。
- [x] 更新 `AGENTS.md` 的 Android 结构、接口、命令、风险与维护记录。
- [x] 审查规划 diff，确认没有越界修改、秘密或真实数据。

## 回滚方式

删除本任务新增的 Android 规划文档并还原 `doc/tasks/progress.md`、`AGENTS.md` 对应 Phase 11 记录；不会影响 Windows 代码或数据。

## 完成定义

- [x] 三层设计、测试矩阵、任务拆分和控制提示完整且互相一致。
- [x] 所有阻塞问题均有用户确认记录，没有实施阶段待猜决策。
- [x] Android 文件边界、WebDAV 兼容、安全与发布门禁明确。
- [x] 精确暂存规划文件后 `git diff --cached --check`、范围与敏感扫描通过，且不包含 T42 `android/` 红灯测试。

## 实施结果

- 三个只读子 Agent 完成功能、工具链、质量/发布审计；用户一次性确认产品、平台、Widget、同步、签名、设备与 Git 决策。
- 独立二次审计修正了 desktop 合法 481+ 分钟、Python canonical JSON code-point LWW、旋转/后台 AI 生命周期、WorkManager merged manifest、release test hook、自动备份、最低设备线与每任务 Windows/提交门禁。
- Windows 全量 `unittest` 171/171、全模块导入和 `build.py --check` 通过。
- 完整 PyInstaller 生成 45,581,556-byte `dist/Clender.exe`，SHA-256 `4C1AEEBDB89EEDA6B274D63459428A2924386B3006165117E3F2C1F646421F9E`；隔离 silent primary/secondary 冒烟通过，secondary 退出 0，隔离 DB 创建成功且残留进程/目录已清理。
- `dist/data` 构建前后均为 5 文件、96,806 字节、逐项摘要 `CFFC3448EB14A859FC7BF484C8ADC136B0AA3A1C57B7F66DCD1A7C1ED4FCF436`，完整性一致。
- T42 第一阶段策略测试已在未跟踪 `android/` 中取得空工程红灯：27 tests、44 expected failures、0 errors、0 skipped；该代码不进入 T41 规划提交。
