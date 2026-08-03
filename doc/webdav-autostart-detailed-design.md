# Clender WebDAV 日程同步与静默自启动详细设计

## 1. 配置契约

`DEFAULT_CONFIG` 新增：

```python
{
    "webdav_enabled": False,
    "webdav_url": "",
    "webdav_username": "",
    "webdav_password": "",
    "startup_enabled": False,
}
```

旧 JSON 由现有默认合并兼容。密码允许 Unicode 和空字符串，但启用同步、测试连接或手动同步时 URL、用户名、密码必须都是非空字符串。URL 必须为 HTTPS、不得带 query/fragment；作为目录规范化后固定拼接 `clender-events.json`。配置、日志、异常和测试输出不得打印密码。

## 2. SQLite 同步元数据与迁移

`events` 表新增：

| 字段 | 契约 |
|---|---|
| `sync_uid` | 32 位小写 UUID hex，非空且唯一 |
| `updated_at` | UTC RFC3339 微秒时间戳，以 `Z` 结尾 |
| `deleted_at` | 同格式或 NULL；非空表示墓碑 |

`init_db()` 逐列幂等 `ALTER TABLE`，为旧活动行生成 UUID 和当前 UTC 更新时间，再创建唯一索引 `idx_events_sync_uid`。迁移在单事务完成；失败回滚。旧数据库不得就地试验，测试复制最小旧 schema 到临时目录。

本地 `add_event` 写入新 UUID/更新时间；`update_event` 在同一 UPDATE 中刷新 `updated_at`；`delete_event` 改为设置 `deleted_at=updated_at=now`。所有既有读查询增加 `deleted_at IS NULL`。`Event` 模型无需向 UI 暴露同步元数据，AI `to_dict()` 维持现有契约。

数据库新增：

```python
def get_sync_records() -> list[dict]: ...       # 包含墓碑
def apply_sync_records(records: list[dict]) -> bool: ...
```

`apply_sync_records` 在单事务内按 `sync_uid` INSERT/UPDATE 完整字段并返回可见日程是否变化；整批任一数据库错误必须回滚。同步服务调用前已经完成全部不可信输入验证，数据库仍使用固定列和参数化值。

## 3. 远端文档

```json
{
  "schema_version": 1,
  "events": [
    {
      "sync_uid": "0123456789abcdef0123456789abcdef",
      "event_type": "reminder",
      "title": "事项",
      "start_time": "2026-08-03 09:00",
      "end_time": null,
      "description": "",
      "estimated_duration": 0,
      "created_at": "2026-08-03T01:00:00.000000Z",
      "updated_at": "2026-08-03T01:00:00.000000Z",
      "deleted_at": null
    }
  ]
}
```

- UTF-8，`sort_keys=True`、紧凑 separators，事件按 `sync_uid` 排序，便于稳定 ETag/测试。
- 最大响应 5 MiB，最大 100000 条；未知 schema、重复 UUID、未知/缺失字段、bool 冒充整数、非法时间或事件规则均拒绝。
- `deleted_at` 非空时仍保存完整最后事件内容，便于数据库 upsert；`deleted_at` 必须等于 `updated_at` 或不晚于它。
- `created_at` 对旧记录可由迁移生成；远端必须是合法 UTC 时间戳。

## 4. 合并算法

`merge_records(local, remote)` 分别建立 UUID map：

1. 仅一端存在则保留该端。
2. 两端存在且 `updated_at` 不同，保留较新者。
3. `updated_at` 相同时，对不含本机整数 ID 的规范 JSON 字节做词典序比较，选择较大者，保证所有设备结果一致。
4. 墓碑与活动记录遵循同一规则；墓碑不会因旧活动副本复活。
5. 返回按 UUID 排序的全量规范记录。

同步每轮流程：

1. GET 远端；文件 404 视为空文档，目录测试 404 则失败。
2. 校验远端，读取数据库当前快照并合并。
3. 在一个事务应用 merged，随后重新读取本地（吸收事务前已完成的本地写入）并形成上传文档。
4. 远端存在时 `PUT If-Match: <etag>`；不存在时 `PUT If-None-Match: *`。
5. 412 重新从第 1 步开始，最多两轮；第二次仍冲突则失败。

## 5. WebDAV HTTP 契约

`WebDAVClient(settings, session=requests)`：

- `probe()`：对目录 URL 发 `PROPFIND`，header `Depth: 0`；200/207 成功。
- `fetch()`：GET 固定文件；200 返回 body/ETag，404 返回空/不存在，其余失败。
- `put(body, etag, exists)`：PUT JSON；200/201/204 成功，412 抛并发异常，其余失败。
- 所有请求使用 `(5, 15)` connect/read timeout、Basic auth tuple、TLS 默认校验、有限重定向和明确 User-Agent。
- 错误按配置、认证/权限、目录/文件、超时/连接、HTTP、文档、并发分类；UI 消息限制长度且不附响应正文。

## 6. Qt 同步控制器

```python
class SyncController(QObject):
    status_changed = pyqtSignal(str)
    schedules_changed = pyqtSignal()
    test_finished = pyqtSignal(bool, str)

    def request_sync(self, reason: str) -> bool: ...
    def test_connection(self, settings: dict) -> bool: ...
    def shutdown(self) -> None: ...
```

- 只从保存配置构建正式同步设置；未启用时本地变化请求静默忽略，手动请求显示“未启用”。
- worker 运行时收到同步请求置 pending；测试请求在 busy 时立即报告忙。
- worker 完成后清理引用；若 pending 且配置仍有效，启动下一轮。
- 远端事务改变可见日程时发 `schedules_changed`；MainWindow 连接到仅刷新方法，不能回到 `request_sync`。
- 状态使用稳定前缀 `working/success/unchanged/error:`，UI 转成中文短文本。
- shutdown 禁止新请求、请求线程中断并有界等待；网络调用之间检查 interruption。

## 7. 设置 UI 与保存事务

新增稳定 objectName：`webdavEnabled`、`webdavUrl`、`webdavUsername`、`webdavPassword`、`startupEnabled`、`webdavTestButton`、`webdavSyncButton`、`webdavStatus`。密码框使用 `QLineEdit.Password`。

`AppSettingsDialog` 新增信号：

```python
webdav_test_requested = pyqtSignal(dict)
webdav_sync_requested = pyqtSignal()
```

- 测试按钮只校验并发出当前表单连接字典，不落盘。
- 保存关闭：校验；先将 HKCU Run 调整到目标状态，再原子保存完整配置；配置保存失败时尝试恢复原注册表状态，留窗报错且不发 `config_saved`。
- 立即同步：执行同一保存流程但不关闭，成功后发 `config_saved` 与 `webdav_sync_requested`。
- 测试/同步期间禁用相应按钮；结果通过 `set_webdav_status()` 显示，不泄露凭据。
- 开发态自启动 checkbox 禁用并显示“仅打包版可用”；旧配置值不导致开发态写注册表。

## 8. 开机自启动

`startup_manager.py`：

```python
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
VALUE_NAME = "Clender"

def is_supported_runtime() -> bool: ...
def build_startup_command(executable=None) -> str: ...
def is_startup_enabled() -> bool: ...
def set_startup_enabled(enabled: bool) -> None: ...
```

命令使用 Windows 参数引用规则生成 `"<absolute exe>" --silent`。启用只允许 Windows frozen runtime；禁用删除当前用户值，值不存在视为成功。注册表异常包装为不含本机敏感细节的 `StartupError`，底层异常保留为 cause 供日志定位。

## 9. 入口和单实例

- `main.main(argv)` 识别 `--silent` 并从传给 QApplication 的参数中移除。
- `SingleInstanceCoordinator.acquire(..., activate_existing=True)`：普通 secondary 发 `activate`；静默 secondary 发 `probe`，primary 只确认连接不置前。
- primary 初始化完成后：普通启动 `window.show()`；静默且托盘可用不 show；静默无托盘时 show。
- MainWindow 构造期间悬浮设置仍可显示今日悬浮窗；隐藏主窗口不能隐藏悬浮窗。

## 10. 测试矩阵

| 区域 | 正常路径 | 边界值 | 非法/异常 | 回归 |
|---|---|---|---|---|
| 配置 | 五字段完整往返 | Unicode/长密码、缺字段 | 非 HTTPS、空字段、query/fragment | API Key/主题/字号完整保存 |
| DB 迁移 | 新库字段/索引、旧库补齐 | 空库、多旧行、重复 init | 事务中途失败回滚 | CRUD/查询/相交/类型转换 |
| DB 写入 | add/update 时间戳、soft delete | 连续更新、墓碑查询 | 非法同步记录、SQL 错误 | AI/手工看不到墓碑 |
| 合并 | 本地/远端/并集/LWW | 相同 timestamp、Unicode、空集 | 重复 UUID、坏时间、未知 schema、过大 | 不使用整数 ID 合并 |
| HTTP | PROPFIND、GET 404、PUT create/update | 空文档、缺 ETag | timeout、connection、401/403/500、412×2 | 不发真实网络、不泄露 auth |
| Controller | local/manual、pending 合并 | disabled、busy、远端无变化 | worker error、shutdown | 远端刷新不触发循环 |
| 设置 UI | 保存、测试、保存并同步 | 密码显示模式、开发态 startup | 配置/注册表失败与补偿 | 主题、字号、完整 dict 信号 |
| 自启动 | HKCU enable/disable、quoted command | value missing、路径空格 | 非 frozen enable、registry error | 不需管理员权限 |
| 入口 | 普通、silent tray、silent floating | silent secondary、无托盘 | 未知参数 | 单实例、启动副作用顺序 |
| 发布 | offscreen、完整构建、exe 启动 | `--silent` 隔离运行 | 构建失败不发布 | `dist/data/` 完整性不变 |

所有可自动化项必须先在旧实现取得失败/缺失证据，再实施。

## 11. 回滚与兼容

- 回滚源码使用 Git revert；远端 `clender-events.json` 可保留，旧版本完全忽略。
- SQLite 新列和索引是向后兼容附加项；旧代码忽略列，但会把墓碑行当普通事件，因此回滚前必须先运行本版本提供的“恢复策略”：将仍活动行导出到新临时数据库或用经过审查的事务物理移除墓碑。不得直接对真实库执行手工 SQL。
- 配置新字段被旧版本忽略；自启动回滚需移除 HKCU Run 值。
- 首版实施不自动备份或迁移真实 `data/`；实际迁移只在用户启动新版时由 `init_db()` 单事务发生。完整构建/测试不得启动真实数据路径。

## 12. 开放问题

无阻塞性开放问题。时钟偏差、墓碑无限保留和整体文档规模作为已知后续风险。
