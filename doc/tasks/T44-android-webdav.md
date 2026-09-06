# T44：Android WebDAV schema v1 与同步编排

## 目标

实现与 Windows 完全兼容的 WebDAV codec、确定性 LWW、HTTPS Basic transport、ETag/412 和前台触发串行同步。

## 非目标

- 不连接真实 WebDAV；不周期后台同步；不上传对话/设置/秘密。

## 输入文档

- Android detailed design 第 5 节；desktop `webdav_sync.py`/测试契约；T43

## 影响文件

- `android/app/src/main/.../domain/sync/`
- `android/app/src/main/.../data/network/webdav/`
- `android/app/src/main/.../sync/`
- 对应 JVM/Room/MockWebServer 测试

## 接口/数据影响

- `WebDavSettings`、`WebDavDocumentCodec`、`MergeEngine`、`WebDavClient`、`SyncService`、`SyncCoordinator`。
- 固定 `clender-events.json` schema v1；Room 事务 apply。

## 风险

- Basic 凭据跨 redirect；远端畸形文档部分应用；412 无限循环；remote refresh 触发上传回路。

## 当前状态

- 2026-08-30 恢复审计确认 codec/merge/transport/service/coordinator/Room store 生产实现均已存在，51 项 T44 聚焦测试通过；旧文字“仅红灯”已过期。
- dependency policy 曾命中 `RoomEventRepository → domain.sync.MergeEngine` 反向依赖；现由 `RoomSyncRecordStore` 在单 Room 事务内注入 winner 判定，策略与 race 回归通过。
- 任务尚未完成：完整 Android lint 因锁定 Compose/AGP 无法读取 Kotlin 2.3 metadata 而崩溃，且生产 UI/生命周期入口尚未接线；不得把核心实现通过等同于 T44 交付完成。

## 实施步骤

- [x] 第一轮只写 codec/merge/HTTP/controller/双库失败测试并记录红灯。
- [x] 实现严格 schema、streaming 大小/数量上限与规范序列化。
- [x] 实现 union/LWW/tombstone/同时间戳确定性决胜。
- [x] 实现 PROPFIND、GET、conditional PUT、redirect 拒绝、412 单次重试。
- [x] 实现 Mutex busy/pending、local/manual/foreground 触发与 remote no-loop 核心编排。
- [x] 聚焦验证、敏感日志与 desktop fixture 兼容复核。
- [ ] 解决完整 lint 工具链阻塞并完成生产入口接线、全门禁与聚焦提交。

## 测试与检查

```powershell
.\android\scripts\gradle.ps1 testDebugUnitTest --tests "*.webdav.*" --tests "*.sync.*"
```

覆盖 200/404/401/403/5xx/timeout/disconnect/同/跨主机 redirect、PROPFIND、ETag、412×2、5 MiB/100000、未知字段、坏 UUID/时间、481+ 合法时长、local/remote/union、墓碑、事务 rollback、busy/pending/shutdown、两个隔离 DB 收敛；URL 覆盖尾 `/`、空格/中文/percent、端口、IPv6、重复 `/`、`..`，Basic 覆盖 Unicode/Latin-1 且不转发凭据。
- desktop canonical JSON 黄金向量覆盖中文、emoji/非 BMP、引号、反斜杠、换行、null、字段乱序；Kotlin 必须按 Unicode code point 复刻 Python `json.dumps(...ensure_ascii=False...)` 决胜，禁止直接 `String.compareTo`。

## 红灯证据

- 第一阶段仅新增 7 个 T44 测试/fixture 文件、39 项契约，未写生产实现；聚焦 Gradle 在 `compileDebugUnitTestKotlin` 真实失败于缺失 `WebDavDocumentCodec`、`MergeEngine`、`WebDavClient`、`SyncService`、`SyncCoordinator` 等类型以及尚未引入的共享 OkHttp 依赖。
- 独立审计纠正了 desktop 接受无小数/1–6 位 UTC 并规范为微秒、历史墓碑允许 `deleted_at < updated_at`、本机 Room ID 不参与 canonical 比较等兼容边界；对应测试已先修订，生产实现不得收窄 Windows schema v1。

## 回滚方式

删除 T44 `domain/sync` 包前，必须同时还原 `RoomEventRepository` 对 `MergeEngine` 的导入、构造参数与事务内 winner 门禁；随后再删除 WebDAV/network/sync 包及其测试、fixture 和设置引用。回滚不得触碰 Android/Windows 运行数据或真实远端。

删除 WebDAV/sync packages 和设置引用；本任务不访问真实远端。

## 完成定义

- [x] 与 desktop schema fixture 双向 round-trip。
- [x] HTTP/merge/controller 全矩阵通过，无凭据/正文日志。
- [x] 仅三种确认触发，无周期任务和远端回路。
- [x] 按根门禁完成 Android、Windows、构建、exe 与 `dist/data` 核验；与 T45 共享 checkpoint 提交。

## 最终结果（2026-08-30）

T44 核心随 F 路径通过完整 lint 和发布门禁：Android JVM 229 项、97-task 完整门禁、generated/boundary 各 44/44、无 Google/native/lock/APK 审计均通过；未连接真实 WebDAV。T46 UI 接线仍属于后续任务，不影响本任务同步核心完成。
