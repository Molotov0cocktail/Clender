# Clender Android 原生架构版提案

## 1. 目标

在仓库根目录新增完全隔离的 `android/` 原生 Android 子项目，实现 Windows Clender 的核心日程、AI、WebDAV、主题与字号能力，并将桌面悬浮窗重构为符合 Android Launcher 约束的今日 App Widget。交付经过严格自动化验证、可直接安装的 universal release APK、用于商店发布的 AAB、可复现构建说明和源码 Git 提交。

## 2. 非目标

- 不修改 Windows Python/PyQt 生产源码、数据库或业务行为。
- 不读取、迁移、复制或测试真实 `data/`、`dist/data/`。
- 不使用 Google Play services、Firebase、Google 账号、云推送或 Google 专属 API。
- 不把 AI 对话、设置、API Key 或 WebDAV 密码同步到 WebDAV。
- 不新增 Android 系统通知、精确闹钟、系统日历集成、后台 AI 请求或周期后台 WebDAV。
- 不复刻 Windows 托盘、单实例、静默开机启动、悬浮窗置顶/置底、任意拖缩、双击与 Widget 内自由文本输入。
- 不提交 SDK、Gradle cache、模拟器、数据库、配置、keystore、APK 或 AAB。

## 3. 用户与运行环境

- Android 8.0/API 26 至 Android 16/API 36 的手机和平板用户。
- 支持竖屏、横屏、分屏、可变窗口和大屏；折叠屏按可变窗口适配，不依赖厂商 SDK。
- 支持没有 Google 服务的 AOSP/厂商系统；所有运行依赖来自 AndroidX、Kotlin/JVM 和标准 HTTPS。
- 默认简体中文，并提供完整英文字符串资源。

## 4. 功能需求

### 4.1 导航与三份主视图

- 单 Activity + Compose Navigation。
- 顶部三横线打开 Modal Navigation Drawer。
- 抽屉包含：日历、事项、AI 对话、设置、关于。
- 默认进入日历；返回键优先关闭抽屉，再执行页面/系统返回。
- 日历、事项和 AI 视图保留选中日期、显示模式、滚动位置、活动对话和输入草稿；旋转、分屏与系统重建后恢复。

### 4.2 日历与事项

- 月、周、日三种视图；支持上一段、下一段、今天与选中日期。
- 月视图展示日期与事项计数；点击日期保持选择，可显式打开事项视图。
- 周/日使用可滚动 24 小时时间轴，支持 reminder marker、预计时长块、timespan 块、lane/cluster、真实碰撞、窄列聚合与可访问点击目标。
- 事件类型为 `reminder`、`timespan`；字段与桌面 schema 一致。
- 新增、详情、编辑、删除；AI 删除无需二次确认，手工删除需要确认。
- 支持 reminder/timespan 双向转换；转 reminder 清空 `end_time`。
- 支持跨日 timespan 创建，并在所有相交日期的日/周视图中显示。
- 时间语义为无时区本地墙钟 `YYYY-MM-DD HH:mm`；设备跨时区不换算显示时刻。
- 空库不自动写示例数据。

### 4.3 AI

- OpenAI-compatible `GET /v1/models` 与 `POST /v1/chat/completions`，仅允许 HTTPS endpoint。
- 配置 endpoint、API Key、模型、temperature、最大输出、上下文窗口、Thinking 开关/强度、自定义 system prompt 与人格。
- 多对话新建、选择、重命名、删除、清空；至少保留一个活动对话。
- 消息角色 `user`、`assistant`、`think`；Thinking 可折叠；显示保守 Token 估算与预算。
- 每次只允许一个在途 AI 请求；响应绑定发起时 conversation ID，切换页面/对话不串写。
- ProcessLifecycle 进入后台或进程终止时取消；旋转、分屏等配置变更导致的 Activity 重建不取消。不通过 WorkManager 续跑，不自动重试。
- 接受纯 JSON、Markdown JSON 代码块、数组或单对象；非操作 JSON/解析失败作为普通回复。
- 仅允许 `add/update/delete/reply`；允许 update 转换事件类型；所有事件写入必须经过领域服务验证。
- AI 输出视为不可信输入，不允许文件、命令、SQL、Intent、任意 URL 或其他外部操作。

### 4.4 WebDAV

- 与 Windows `clender-events.json` schema version 1 双向兼容，只同步事件。
- 仅 HTTPS + Basic Authentication；目录 URL 不允许 query、fragment 或内嵌凭据。
- 连接测试使用 PROPFIND；同步使用 GET 与条件 PUT。
- 使用 `sync_uid`、UTC RFC3339 微秒 `Z`、软删除墓碑、逐事件 LWW、确定性同时间戳决胜、ETag `If-Match`/`If-None-Match`。
- 412 最多重拉并重试一次；远端文档限制 5 MiB、100000 条。
- 本地成功变更、手动请求和应用回到前台时触发；没有固定周期任务。
- 同步运行中新增请求只合并为一次 pending；远端写回本地不触发循环。

### 4.5 设置与秘密

- 主题：跟随系统、浅色、深色。
- 应用字号与 Widget 字号分别为 8–20sp，默认 13sp。
- Widget 时间段、透明度、主题和实例配置。
- API Key 与 WebDAV 密码由 Android Keystore AES-GCM 保护；DataStore 只保存密文、IV 和非秘密设置。
- 解密失败安全回退为“未配置”，不得记录密文、明文、Authorization 或私人正文。
- 按用户允许 Android 端自主采用安全默认的授权，首版显式禁用 Android 自动备份/设备迁移，Room、DataStore 与秘密 envelope 均不上传或跨设备恢复；卸载即删除本地数据。未来如需迁移，另行设计显式导出/导入。

### 4.6 今日 Widget

- 支持小、中、大响应式布局和多个 Widget 实例。
- 展示配置时间段内与今日相交的事项，区分全部 current 与最早 future 的全部并列 next。
- 数据变更、同步完成、配置变化、应用回前台和日期切换时请求刷新；系统后台刷新为尽力而为，不申请精确闹钟。
- 点击事项打开应用内编辑页；点击 AI 打开快速输入 Activity，复用最近活动对话。
- 不在 Widget 内直接输入；不提供置顶、置底、双击或像素级几何。
- 所有 PendingIntent 使用显式组件、唯一 request key 与正确 immutable/update flags，避免劫持与串实例。

## 5. 输入、输出与外部依赖

### 输入

- 用户创建/编辑的日程、配置、AI 对话和 Widget 设置。
- HTTPS AI 响应与 WebDAV 文档，均视为不可信输入。
- 系统日期、时间、主题、窗口尺寸与生命周期事件。

### 输出

- 应用私有 Room 数据库与 DataStore 配置。
- HTTPS AI/WebDAV 请求。
- universal release APK、release AAB、哈希、签名验证和测试报告。

### 依赖

- JDK 17、Gradle wrapper、Android SDK 36/build-tools/platform-tools/emulator。
- Kotlin、Android Gradle Plugin、Compose BOM、AndroidX、Room、DataStore、WorkManager、Android 平台 App Widget/RemoteViews、OkHttp、kotlinx.serialization。
- 所有版本锁定在 version catalog；Gradle verification metadata 和 wrapper checksum 防止供应链漂移。

## 6. 约束与假设

- 所有 Android 工具链、home、缓存、临时文件、AVD 和生成物只能使用 `android/` 下的忽略路径；不得回落到标准用户目录或进入提交。
- 纯 Kotlin/JVM、无 `.so`，因此 universal APK 不依赖 CPU ABI；以 x86_64 模拟器矩阵验证系统版本，以 APK 内容审计验证无 native 库。
- T47 历史候选 Glance `1.1.1` 已拒绝：producer graph 会选择 `androidx.datastore:datastore-core-android:1.2.1`，其 AAR 携带四 ABI `libdatastore_shared_counter.so`，与无 NDK/native、DataStore JVM-only 和 universal APK 无 `.so` 契约冲突。不得通过 exclusion、force、strictly、手工 jar、复制源码或其他绕过恢复 Glance。
- Widget 采用平台 `AppWidgetProvider + RemoteViews + AppWidgetProviderInfo XML`；API 26–30 按 `OPTION_APPWIDGET_*` size bucket 选布局，API 31+ 使用 responsive/exact `RemoteViews` size mapping。主应用继续使用 Compose，Launcher Widget 不使用 Compose/Glance。
- 日期边界只允许 WorkManager `2.11.2` one-time work；不使用 periodic、expedited、long-running、foreground work、精确 AlarmManager、通知或网络后台刷新。
- Widget 刷新受 Launcher/Doze 限制，不承诺分钟级精确边界。
- 不连接真实 Provider/WebDAV；网络测试使用 MockWebServer 和固定非秘密夹具。
- release keystore 只在本机生成、忽略和用于签名；用户负责最终备份。
- Android CI 可新增根工作流，但不更改既有 Windows CI；最终仍按根门禁执行 Windows 全量回归、PyInstaller 与 `dist/data` 只读核验。

## 7. 成功标准

- 三份主视图、事件 CRUD、月/周/日、AI、多对话、设置、WebDAV 和 Widget 均可运行。
- Android 数据和构建对 Windows 生产源码及用户数据零影响。
- desktop schema v1 兼容测试通过；秘密不以明文进入 DataStore、日志、APK、测试或 Git。
- JVM、Robolectric、Room、Compose UI、平台 App Widget/RemoteViews、MockWebServer、lint、静态分析和模拟器矩阵通过。API 26 手机、API 36 手机与 API 36 平板的 release 安装/冷启动/核心冒烟是不可自动豁免最低线；若无法完成必须暂停并取得用户明确豁免，不能以错误记录替代完成。
- release APK/AAB 成功签名；APK `debuggable=false`、`testOnly=false`、无 cleartext、无 native 库、可安装冷启动。
- Windows 171 项基线、导入、build check、完整 PyInstaller 和隔离 exe 冒烟继续通过，`dist/data` 前后完整性一致。
- 提交只包含获准工程文件并推送 Gitee `codex/android-architecture`；APK/AAB/keystore 不提交。

## 8. 验收检查

1. 新装应用，无配置/空库进入日历，不崩溃且不写示例事项。
2. 手工创建、编辑、跨日显示、类型转换和删除均持久化并触发 Widget/同步编排。
3. Drawer 可达所有视图，状态在旋转和进程重建后恢复。
4. debug/test variant 的 AI mock 覆盖成功、Thinking、长上下文、切会话、旋转不取消、危险输入、超时、429、5xx、畸形 JSON和后台取消；交付 release 不包含 fake transport/test CA/test hook，只验证无配置、离线安全失败与生产装配冷启动。
5. 两个隔离数据库通过 WebDAV mock 完成新增、更新、墓碑、同时间戳、412 合并。
6. Widget 多实例在三尺寸、Light/Dark、午夜、进程杀死、Doze 与无数据下安全显示并正确跳转。
7. API 26/29/31/33/35/36 手机和 API 33/36 平板的规定矩阵完成。
8. release APK/AAB 验证、安装、冷启动和核心冒烟通过。
9. Windows 回归和数据完整性门禁通过。
10. Git diff、staged diff、敏感扫描与 Gitee push 成功。

## 9. 已确认假设

- 用户接受全部推荐默认值以及本文列出的 Android 平台等价映射。
- AI 删除直接执行；进后台不续跑；本轮不新增系统通知。
- 用户接受无 ARM64 实机，以多 API x86_64 模拟器与无 native 库审计作为泛用性证据。
- 用户手动创建 Release；本任务只推 Gitee 源码分支。

## 10. 开放问题

无阻塞性开放问题。若工具链版本、依赖解析、Android 36 行为或 Launcher 能力在实施中出现新事实冲突，立即暂停对应任务并回报，不自行改变产品契约。
