# Clender WebDAV 日程同步与静默自启动高层设计

## 架构概览

```text
EventManager / AIChatWidget / Floating edit
                │ successful local data_changed
                ▼
            MainWindow ───────────────┐
                │ refresh UI          │ request_sync
                ▼                     ▼
      Calendar/Event/Floating   SyncController (QObject)
                                      │ QThread
                                      ▼
                                SyncService
                             ┌────────┴────────┐
                             ▼                 ▼
                       database sync view   WebDAVClient
                       + transactional merge  GET/PUT/ETag

AppSettingsDialog ── config.py
        ├─ test/sync intent ── MainWindow ── SyncController
        └─ startup setting ── startup_manager ── HKCU Run

main.py --silent ── MainWindow constructed ── tray/floating kept, main hidden
```

依赖方向保持 `ui → controller/service → database/model`。WebDAV 客户端不依赖 Qt；同步控制器仅负责线程生命周期、请求合并和信号。数据库不知道 HTTP，UI 不直接写数据库或发网络请求。

## 模块边界

- `webdav_sync.py`：配置校验、远端文档解析/规范化、事件合并、Basic WebDAV GET/PUT/PROPFIND、同步服务和有界错误类型。
- `sync_controller.py`：`QThread` worker、busy/pending 状态、测试连接、手动/本地变更触发、远端变更通知与关闭等待。
- `database.py`：同步元数据幂等迁移、默认查询过滤墓碑、同步快照、整批事务应用。
- `event_service.py`：现有 CRUD 契约不变；数据库层为本地写入生成同步元数据。
- `startup_manager.py`：冻结 Windows exe 的 HKCU Run 查询、启用和禁用，生成带 `--silent` 的安全命令。
- `ui/app_settings.py`：WebDAV/自启动控件、字段校验、完整配置保存、测试和手动同步意图。
- `ui/main_window.py`：区分本地数据变化与远端刷新，编排 SyncController、设置状态、托盘手动入口和退出。
- `main.py`：解析并移除内部 `--silent` 参数，控制主窗口初始可见性；无托盘时回退显示。
- `single_instance.py`：静默 secondary 只探测已有实例而不发激活消息。

## 数据流

### 本地变化同步

```text
EventService CRUD succeeds
 → component data_changed
 → MainWindow refreshes all views
 → SyncController.request_sync("local-change")
 → worker reads current local sync records
 → GET clender-events.json (+ ETag; 404 file means empty)
 → validate + merge local/remote by sync_uid
 → one SQLite transaction applies remote winners
 → PUT canonical merged document with If-Match/If-None-Match
 → schedules_changed only if visible local rows changed
 → MainWindow refresh only (no new sync request)
```

运行中再次收到本地变化时只置 `pending_sync=True`；当前 worker 完成后再运行一次，确保网络过程中产生的本地更新不会遗漏。

### 手动同步与测试

- 设置页“立即同步”先完成表单校验、注册表更新与完整配置原子保存，然后发出手动同步信号。
- 托盘“立即同步日程”直接使用已保存配置。
- “测试连接”使用当前表单的 URL/用户名/密码发 `PROPFIND Depth: 0`，不保存配置、不写远端文件。
- 同一时间只运行一个测试或同步 worker；忙时给出明确短状态。

### 静默自启动

```text
save startup_enabled
 → startup_manager writes/removes HKCU Run value
 → config.save_config(full config)

Windows logon → "Clender.exe" --silent
 → QApplication(filtered argv)
 → single-instance acquire without activation on secondary
 → construct MainWindow/tray/floating
 → main window stays hidden when tray exists
 → enabled floating window remains visible
```

## 主要设计决策

- 使用每事件 UUID 而不是跨设备复用 SQLite 整数 ID；本机 ID 继续供现有 UI/AI 使用。
- 使用软删除墓碑保证离线设备重新上线时不会恢复已删除事件。
- 使用 UTC 微秒时间戳做 LWW；承认设备时钟偏差风险，并以规范 JSON 解决完全相同时间戳的确定性。
- 使用 ETag 条件 PUT；412 后重新 GET/合并，最多两轮，避免无限重试和静默丢失并发更新。
- 远端文档整体读写，适合当前轻量日程规模；设大小与记录数上限防止不可信响应耗尽内存。
- 不在设置保存时自动同步；只有明确的“立即同步”或后续日程变更触发。
- 注册表和 JSON 配置采取可回滚的顺序；任何一步失败都不宣告保存成功。

## 替代方案

- 整库上传/最后文件胜出：会覆盖另一设备独立新增，不采用。
- 按整数 ID 合并：不同设备会产生相同 ID，不采用。
- 硬删除：无法区分“从未见过”与“已删除”，会复活事件，不采用。
- 定时/启动轮询：用户明确不需要，不采用。
- 新增 WebDAV 第三方库：现有 requests 足以覆盖所需方法，避免增加构建依赖。
- Windows Credential Manager：用户明确选择配置 JSON 明文保存，不采用。

## 风险与缓解

- 设备时钟错误影响 LWW：文档明确风险，相同时间戳确定性决胜；未来可升级逻辑时钟。
- 整体 JSON 随历史墓碑增长：首版限制大小/数量并保留墓碑；未来版本设计确认协议后再压缩。
- HTTP 请求退出时仍在阻塞：使用连接/读取分离超时，控制器关闭时请求中断提示并有界等待。
- 注册表与配置是两个持久化目标：保存失败执行补偿回滚，并测试每个失败点。
- 远端是不可信输入：严格 schema、字段、类型、时间、UUID、事件业务规则与大小验证后才进入事务。
- 远端应用与本地即时修改并发：事务重新读取当前本地快照；controller pending 再跑确保最终上传。

## 高层测试策略

- 纯逻辑：设置、URL、文档、记录、LWW、墓碑和相同时间戳。
- 数据库：新库、旧库幂等迁移、UUID 唯一、更新时间、软删除、事务回滚、旧查询兼容。
- HTTP：PROPFIND、404 文件、GET/PUT、ETag、412、超时、非 2xx、畸形/过大 JSON，全部 mock。
- Qt：设置控件/信号/主题、controller busy/pending、仅本地变更或手动触发、远端刷新不循环。
- Windows：注册表命令、冻结限制、启停、失败回滚、普通/静默/secondary/无托盘入口。
- 发布：全量导入、offscreen、完整构建、隔离 exe 普通与 `--silent` 冒烟、`dist/data/` 完整性。
