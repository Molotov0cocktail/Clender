# AGENTS.md — Clender 工程协作指南

> **适用范围：** 本文件位于工程根目录，规则适用于整个仓库。若子目录以后出现更具体的 `AGENTS.md`，子目录规则只能补充本文件，不得降低这里的质量、测试和维护要求。
>
> **当前基线：** 2026-08-02，按 Python 3.12 现代化与工程加固后的实际代码整理。`doc/` 中的旧文档保留历史背景，代码现状与本文件优先。

## 🚨 强制维护门禁（所有代理和开发者必须遵守）

### 修改前：先有任务清单和严格测试用例，再改代码

任何代码、配置、数据库结构、构建脚本或工程文档修改开始前，必须先完成：

1. 阅读本文件、`git status --short`、相关源码、相关 `doc/` 文档和已有任务记录；不得覆盖用户未提交的改动。
2. 在 `doc/tasks/` 新建或更新本次任务文件，并同步 `doc/tasks/progress.md`。任务文件至少写明：目标、非目标、影响文件、接口/数据影响、风险、实施步骤、回滚方式、完成定义。
3. **先写测试矩阵。** 每项修改至少覆盖正常路径、边界值、非法输入/异常路径和回归路径；数据库修改还要覆盖事务与旧数据兼容；UI 修改还要覆盖信号、主题切换和无显示器冒烟；AI 修改还要覆盖无配置、超时、非 200、畸形 JSON 与危险操作输入。
4. 能自动化的用例应先写入 `tests/`。修复 Bug 时先证明回归用例能在旧实现上失败，再实施修复。
5. 未明确验收标准、测试用例或数据契约前不得实施。发现需求歧义、破坏性操作、真实数据迁移或外部依赖变化时，先向用户确认。

### 修改中：小步、分层、可回滚

- 只修改任务清单列出的范围，避免无关重构和全局格式化。
- 保持依赖方向：`ui → service/pure logic → data/model`；数据层不得依赖 UI。
- UI 的事件写入走 `EventService`；AI 日程操作走 `AIService → EventService`。
- 网络请求只能在 `QThread` 等工作线程中执行；Qt 控件只能由主线程更新。
- API Key、真实对话、数据库和日志不得进入源码、测试夹具、构建资源、日志输出或提交记录。
- 不手工编辑 `build/`、`dist/`、`__pycache__/` 和 `*.spec` 生成产物。

### 修改后：测试、复核、同步文档缺一不可

1. 运行与风险匹配的单元测试、语法检查、导入检查、Qt offscreen 冒烟和构建检查；未运行项必须说明原因及风险。
2. **每次更改完成后都必须重新执行完整 PyInstaller 构建并做 exe 启动冒烟**，不能只运行 `build.py --check`。构建只能替换 `dist/Clender.exe`，必须保留既有 `dist/data/` 用户数据；构建前后对该目录做只读完整性核对。
3. 检查 `git diff`，排除敏感数据、生成垃圾、无关改动和意外重写。
4. 更新对应 `doc/tasks/*.md` 与 `doc/tasks/progress.md`，记录命令、结果、失败、修复和遗留项。
5. **每次工程修改后必须立即更新本 `AGENTS.md`：** 更新受影响的结构、接口、契约、命令和风险，并在“维护记录”追加日期、任务、改动文件和验证摘要。即使接口无变化也要记录。
6. **每次更改完成并验证通过后都必须创建 Git 提交。** 提交前检查 staged diff 和敏感模式，只提交工程文件；严禁提交 `data/`、`dist/data/`、数据库、配置秘密、真实对话、日志、构建缓存或 exe。若用户明确要求暂不提交，才可例外并在任务记录中说明。
7. 最终向用户明确反馈：改了什么、为何修改、执行了哪些测试、结果如何、构建与提交哈希、`AGENTS.md` 更新了什么、还有哪些风险。

## 1. 项目速览

Clender 是 Windows 桌面智能日程管理应用，使用 Python 3.12.4、PyQt5、SQLite 和 OpenAI 兼容 Chat Completions API。主要能力：

- 月/周/日三种日历视图；周/日视图使用 `QPainter` 绘制时间轴、事件块和重叠标记；
- 提醒（`reminder`）与时间段（`timespan`）事件的添加、编辑、删除和 SQLite 持久化；
- 多对话 AI 助手，将经过验证的模型 JSON 操作转换成日程 CRUD；
- 日间/夜间主题、系统托盘、运行时配置、安全凭据存储和日志；
- PyInstaller Windows 单文件构建。

唯一支持的开发、测试和构建环境是本机 Miniconda base Python 3.12.4。不再维护项目内 Python 环境、离线 wheel 集合或旧版 Windows 兼容构建。

## 2. 技术栈与环境

| 层面 | 当前实现 |
|---|---|
| 语言/环境 | `C:\Users\30910\Miniconda3\python.exe`，Python 3.12.4 |
| GUI | PyQt5 5.15.11（Widgets、Signals/Slots、QThread、QPainter） |
| 数据库 | 标准库 `sqlite3` + `data/clender.db` |
| 配置/对话 | UTF-8 JSON；写入使用原子替换 |
| 秘密存储 | Windows Credential Manager（pywin32 311）；非 Windows 仅接受 `CLENDER_API_KEY` |
| HTTP | requests 2.32.5，Bearer Token，OpenAI 兼容 API |
| 打包 | PyInstaller 6.21.0，单文件 windowed exe |
| 测试 | 标准库 `unittest` + Qt offscreen；GitHub Actions Python 3.12.4 |

`requirements.txt` 精确锁定该环境已验证的直接与关键传递依赖。不要使用系统默认 `C:\Python314\python.exe`，不要在工程根目录重新创建 `.conda/` 或 `.venv/`。升级 Python/依赖必须作为独立任务，更新锁定版本、CI、构建检查和本文件。

## 3. 文件结构与职责

```text
Clender/
├─ main.py                    # 入口：显式创建数据目录/日志、init_db、MainWindow
├─ models.py                  # EventType、Event、Message、唯一 Conversation 模型
├─ constants.py               # 系统提示词、模型能力、默认配置、Canvas 常量
├─ config.py                  # 非秘密配置读写、旧明文 Key 迁移、运行路径
├─ secret_store.py            # Credential Manager / CLENDER_API_KEY
├─ logger.py                  # 显式 configure/shutdown，无导入时文件副作用
├─ database.py                # SQLite schema、迁移、索引与 CRUD
├─ event_service.py           # 事件业务验证与数据层边界
├─ conversation_store.py      # Conversation JSON 持久化
├─ ai_service.py              # 上下文、预算、解析、操作验证与执行
├─ ai_client.py               # 模型列表请求、Chat Completions QThread
├─ calendar_logic.py          # 周/日 block 构建、重叠检测与区间合并纯函数
├─ theme_manager.py           # Light/Dark 配色与全局主题应用
├─ build.py                   # Python 3.12.4 环境检查、PyInstaller 构建、可选快捷方式
├─ requirements.txt           # Miniconda base 已验证版本锁定
├─ ui/
│  ├─ main_window.py          # 三栏主窗口、设置、主题、托盘、刷新编排
│  ├─ calendar_widget.py      # 月/周/日视图；复用 calendar_logic
│  ├─ canvas.py               # WeekCanvas、DayCanvas 绘制
│  ├─ event_manager.py        # 某日事件列表与 CRUD 入口
│  ├─ event_dialog.py         # 事件表单、前端校验与数据组装
│  ├─ ai_chat_widget.py       # 对话 UI、请求预算、线程结果编排
│  ├─ ai_settings.py          # API、模型、Token、Thinking、提示词设置
│  ├─ sidebar.py              # 对话选择/新建/删除/重命名信号
│  └─ __init__.py
├─ tests/                     # 38 项 unittest：模型、配置、DB、AI、日历、构建、Qt
├─ .github/workflows/test.yml # Windows + Python 3.12.4 CI
├─ data/                      # 真实运行数据；被忽略，视为敏感数据
│  ├─ clender.db
│  ├─ config.json             # 仅非秘密配置；旧 api_key 会在加载时迁移并清除
│  ├─ conversations.json
│  ├─ clender.log
│  └─ sample_loaded.flag
├─ doc/
│  ├─ proposal.md、high-level-design.md、detailed-design.md  # 历史重构背景
│  ├─ modernization-proposal.md、modernization-design.md     # 当前现代化方案
│  └─ tasks/                  # T01–T21 与 progress.md
├─ references/prompt-templates.md
├─ build/                     # PyInstaller 中间产物，忽略
└─ dist/
   ├─ Clender.exe            # 构建输出，忽略；每次更改后重新生成
   └─ data/                   # 冻结版用户运行数据，忽略；构建和提交均不得触碰
```

`data/`、`build/`、`dist/` 和 `*.spec` 不属于源码。不得把构建生成的 spec 或缓存加入接口文档，也不得用真实 `data/` 做测试。

## 4. 架构与数据流

### 启动

`main.main()` → `config.ensure_app_data_dir()` → `logger.configure_logging()` → `QApplication` → `database.init_db()` → `MainWindow` → Qt 事件循环。

导入 `config`、`logger` 或业务模块不应创建目录或日志；所有运行时文件副作用由入口显式触发。

### 手工事件

`CalendarWidget.date_selected(date)` → `MainWindow._on_date_selected()` → `EventManager.set_date()`。

`EventManager` 从 `EventDialog` 取得数据 → `EventService` 验证标题、类型、时间和预计时长 → `database` 参数化写入 → `data_changed` → 主窗口统一刷新日历标记和列表。

### AI 日程操作

`AIChatWidget._send_message()` → `Conversation.add_message()` → `AIService.build_request_messages()` 按窗口/输出/安全余量裁剪 → `AICallThread(messages)` 发送 HTTP → `AIService.parse_ai_response()` → 主线程 `_on_result()` → `AIService.execute_operations()` 校验不可信操作 → `EventService` → `database`。

线程结果绑定发起请求时的 conversation ID；切换侧栏不会把响应写入错误对话。只有成功的 add/update/delete 才发出 `data_changed`。

### 日历绘制

`CalendarWidget` 查询 `EventService` → `calendar_logic.build_week_blocks()` / `build_day_blocks()` → `WeekCanvas` / `DayCanvas`。纯逻辑层负责严格解析、列定位、尺寸、标签和重叠区间；Canvas 只绘制。

### 主题

`MainWindow._toggle_theme()` → `theme_manager.switch_theme()` 保存非秘密配置并应用全局 Palette/QSS → 三个主组件 `apply_theme()` 重绘。

## 5. 核心接口与契约

### 5.1 模型

- `EventType.REMINDER == "reminder"`；`EventType.TIMESPAN == "timespan"`。
- `Event(id, event_type, title, start_time, end_time, description, estimated_duration, created_at)`：
  - 时间字符串严格为 `YYYY-MM-DD HH:MM`；
  - reminder 的 `end_time=None`；`estimated_duration` 为非负整数分钟；
  - timespan 必须有晚于开始时间的 `end_time`；
  - 提供 `from_row()`、`to_dict()`、`date`，暂时兼容 `event['title']`/`event.get(...)`。
- `Message(role, content, timestamp)`：角色为 `user`、`assistant` 或 `think`。
- `Conversation.new(title)` 创建 ID 和 `created_at`；`add_message()`、`to_dict()`、`from_dict()` 保持旧 JSON 兼容并持久化 `token_count`。

项目只允许 `models.Conversation/Message` 一套表示。不得在 UI 新建 dict-based Conversation 兼容类。

### 5.2 配置、秘密与日志

`config.py`：

- `get_app_data_dir()`：开发态为工程 `data/`；冻结态为 exe 同目录 `data/`。
- `ensure_app_data_dir()`：显式创建数据目录。
- `load_config()`：默认配置与 JSON 合并，从安全存储注入运行时 `api_key`；发现旧 JSON 的 `api_key` 字段时迁移并原子清除，即使值为空也会清除字段。
- `save_config(config)`：先把 `api_key` 写入安全存储，再原子写入不含 key 的 JSON；安全存储失败时不写配置。
- `is_api_configured()`、`get_theme()`、`set_theme(theme)`。

`secret_store.py`：

- `get_api_key()`：`CLENDER_API_KEY` 环境变量优先，其次 Windows Credential Manager。
- `set_api_key(value)`：空值删除凭据；非 Windows 拒绝持久化并提示使用环境变量。
- Credential Manager 的 `CredentialBlob` 按 pywin32 311 契约以 Unicode 字符串写入、以 UTF-16LE bytes 读取；错误 1312 时降级为当前 Windows 登录会话凭据。
- `delete_api_key()`；凭据目标名为 `Clender/API`。

`logger.configure_logging(data_dir)` 显式安装 DEBUG 文件 handler 和 WARNING 控制台 handler；`shutdown_logging()` 用于测试/关闭；`get_logger(name)` 无文件副作用。绝不记录 Key 或 Authorization header。

### 5.3 数据库与事件服务

`database.py`：

- `init_db()` 显式建表、迁移 `estimated_duration`、创建 `idx_events_start_time`。
- `add_event(...) -> int`、`update_event(id, **fields) -> int`、`delete_event(id) -> bool`。
- `get_events_by_date()`、`get_events_date_range()`、`get_all_events()`、`get_event_by_id()`。
- 所有连接使用上下文管理，在成功与异常路径关闭；值参数化，动态更新字段使用白名单。
- `update_event(end_time=None)` 可显式清空可空字段；其他 `None` 更新被过滤。

`EventService.validate_event(...)` 是通用业务校验边界。`add_event`、`update_event`、`delete_event` 验证类型、ID、标题、时间格式/顺序、描述和预计时长；UI/AI 不得绕过该服务直接写库。

`events` 表：

| 字段 | 约束/含义 |
|---|---|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT |
| `event_type` | `reminder` 或 `timespan` |
| `title` | 非空文本 |
| `start_time` | `YYYY-MM-DD HH:MM`，有 `idx_events_start_time` |
| `end_time` | 可空；时间段结束时间 |
| `description` | 默认空字符串 |
| `estimated_duration` | 非负整数分钟，默认 0（业务层保证） |
| `created_at` | 默认 CURRENT_TIMESTAMP |

Schema 变化必须有幂等迁移、旧库测试和回滚说明；禁止在真实 `data/clender.db` 上试验。

### 5.4 AI 服务与客户端

`AIService`：

- `build_context_messages()` 生成系统提示、人格和事件上下文。
- `estimate_tokens()` 使用 UTF-8 字节的保守估算；`count_messages_tokens()` 加消息开销。
- `build_request_messages(conversation, context_window, max_output_tokens)` 预留输出和至少 10% 安全余量，保留系统消息与最新非 think 历史，必要时截断内容。
- `parse_ai_response()` 接受纯 JSON、Markdown JSON 块、数组或单对象；解析失败作为普通回复。
- `execute_operations()` 只接受 JSON 对象和 `add/update/delete/reply`，写库前验证事件、正整数 ID、字段与 reply message。

模型目标响应：

```json
{"operations":[
  {"action":"add","event_type":"reminder|timespan","title":"...","start_time":"YYYY-MM-DD HH:MM","end_time":null},
  {"action":"update","event_id":1,"title":"..."},
  {"action":"delete","event_id":2},
  {"action":"reply","message":"..."}
]}
```

`ai_client.fetch_models_list()` 调用 `GET /v1/models`，超时 15 秒。

`AICallThread(messages: list[dict])` 只接收已构建消息，调用 `POST /v1/chat/completions`，超时 180 秒：

- `result_ready(dict)` 返回解析结果、`think` 和 `usage`；
- `error_occurred(str)` 返回配置、连接、超时、HTTP 或响应结构错误；
- Thinking 扩展在 400/422 时只重试一次不含非标准字段的请求。

不得恢复 `user_msg + conv` 旧构造路径，也不得从线程直接读取活动 UI 对话。

### 5.5 日历纯逻辑与 UI

`calendar_logic.py`：

- `merge_ranges(ranges)` 合并重叠/相邻区间；
- `build_week_blocks(events, week_start, per_hour)`；
- `build_day_blocks(events, per_hour)`。

两者输出统一 block：`col/top/height/title/tlabel/color_idx/event_type/is_reminder/id/overlap_ranges`。非法日期、负时长、缺失/倒序结束时间会被跳过。生成逻辑与 Canvas 必须成对测试。

Qt 信号：

| 类 | 对外信号/方法 |
|---|---|
| `CalendarWidget` | `date_selected(date)`；`set_selected_date()`、`update_event_markers()`、`apply_theme()` |
| `EventManager` | `data_changed()`；`set_date()`、`refresh()`、`apply_theme()` |
| `AIChatWidget` | `data_changed()`；`refresh_api_state()`、`apply_theme()` |
| `SettingsDialog` | `config_saved()` |
| `ConversationSidebar` | selected/new/delete/rename 四类信号 |

`SettingsDialog._save()` 只有在凭据与非秘密配置都成功写入后才提示成功、发射 `config_saved` 并关闭；`SecretStoreError`/IO 失败必须留在窗口内显示错误。获取模型前保存连接配置同样不得吞掉凭据错误。

## 6. 运行、测试和构建

### 解释器与依赖

```powershell
$ClenderPython = 'C:\Users\30910\Miniconda3\python.exe'
& $ClenderPython --version
& $ClenderPython -m pip install -r .\requirements.txt
```

不要修改 Miniconda base 中与本任务无关的包；依赖安装/升级需要用户授权。当前环境已满足锁文件，不需要重复安装。

### 开发运行

```powershell
& 'C:\Users\30910\Miniconda3\python.exe' .\main.py
```

运行会使用真实 `data/`，可能迁移旧明文 Key、建立索引或加载首次示例数据。自动化检查禁止直接运行此命令，必须隔离路径。

### 必须验证

```powershell
$env:QT_QPA_PLATFORM = 'offscreen'
& 'C:\Users\30910\Miniconda3\python.exe' -m unittest discover -s tests -v
Remove-Item Env:QT_QPA_PLATFORM

& 'C:\Users\30910\Miniconda3\python.exe' -c "import main, models, config, secret_store, database, event_service, conversation_store, ai_service, ai_client, calendar_logic, theme_manager; import ui.main_window, ui.calendar_widget, ui.canvas, ui.event_manager, ui.event_dialog, ui.ai_chat_widget, ui.ai_settings, ui.sidebar; print('imports ok')"

& 'C:\Users\30910\Miniconda3\python.exe' .\build.py --check
```

测试必须使用 `TemporaryDirectory`、mock 网络和 mock Credential Manager。Qt 测试设置 `QT_QPA_PLATFORM=offscreen`，并在涉及视觉变更时补做 Light/Dark 人工或截图回归。

### 构建

```powershell
# 标准单文件构建
& 'C:\Users\30910\Miniconda3\python.exe' .\build.py

# 仅在用户明确要求时创建桌面快捷方式
& 'C:\Users\30910\Miniconda3\python.exe' .\build.py --shortcut
```

`build.py` 会验证 Python 3.12.4、Conda base 标识（base prefix 下存在 `conda-meta`）和关键依赖版本；子进程禁用用户 site-packages，并补齐 conda DLL 搜索路径。PyInstaller 只输出到 `build/release/`，成功后仅原子替换 `dist/Clender.exe`。构建不使用 `--add-data`，不会把真实 `data/` 打入 exe，也不得删除、覆盖或迁移既有 `dist/data/`。生成的 `build/`、`dist/` 和 `*.spec` 均被忽略。

## 7. 测试矩阵

| 变更类型 | 最低自动化覆盖 | 专项验证 |
|---|---|---|
| 模型/对话 | 新建 ID、Message 类型、旧 JSON、UTF-8、token_count 往返 | UI 只持有模型 Conversation |
| 配置/秘密 | 缺/坏 JSON、原子保存、旧 Key 迁移、空 Key、后端失败 | JSON/log/diff/exe 不含秘密 |
| 数据库/服务 | 新旧 schema、CRUD、索引、非法字段/时间、清空、异常关闭 | 临时 DB；真实数据不触碰 |
| AI 解析/执行 | JSON 变体、action/ID/时间验证、预算/截断 | 无配置、非 200、超时、连接、Thinking 重试 |
| 日历/Canvas | 空值、00:00/23:59、两类事件、相邻/三重重叠、坏数据 | 月/周/日、Light/Dark、不同尺寸 |
| UI/信号 | offscreen 构造、视图切换、主题、Conversation 类型 | Windows 桌面 CRUD/托盘人工回归 |
| 构建 | 精确环境、无 `--add-data`、用户 site 隔离、DLL PATH、暂存发布路径 | PyInstaller + exe 隐藏启动冒烟；`dist/data/` 构建前后完整性一致 |

Bug 修复必须包含一个修复前失败、修复后通过的用例。若无法复现失败态，任务文件必须记录输入、预期/实际结果和限制。

## 8. 安全与数据约束

- `data/` 与 `dist/data/` 都属于用户，不得清空、覆盖、复制、纳入测试或提交。只有用户运行应用时才允许就地迁移；数据库结构修改前必须备份方案。
- `config.json` 不得出现 `api_key` 字段；Key 只存在于运行时对象、环境变量或 Credential Manager。
- 所有 SQL 值参数化，字段名白名单；连接与事务必须在异常路径关闭。
- AI 输出是不可信输入，不得扩展为文件、命令、任意 SQL 或未确认的外部操作。
- HTTP 错误文本最多展示受限长度，不记录 Authorization header、请求完整上下文或私人日程。
- 构建前扫描命令和 spec，确认没有 `data/`、本机秘密、绝对用户数据路径。

## 9. 当前剩余风险

1. Token 预算是跨 Provider 的保守估算，不是官方 tokenizer；10% 余量降低溢出概率，最终限制仍由服务端决定。
2. Credential Manager 绑定 Windows；非 Windows 只能通过 `CLENDER_API_KEY` 注入且不持久化。若 Windows 持久凭据因错误 1312 不可用，Key 仅保存到当前登录会话，注销或重启后需要重新输入。
3. SQLite schema 仍是轻量幂等迁移，没有独立版本表；后续复杂迁移需先引入 schema version。
4. AI 网络测试全部使用 mock，本轮未向真实 Provider 发送请求；Provider 特有字段仍可能存在差异。
5. UI 仍有较多内联 QSS；业务/布局逻辑已提取并覆盖，但像素级视觉变化仍需要截图或人工检查。
6. CI 配置已加入仓库，但远程工作流结果需在推送到 GitHub 后确认。
7. 依赖锁定针对当前 Windows Miniconda base；升级需重新运行完整测试和 exe 构建冒烟。

## 10. 文档与提交约定

- 所有文本使用 UTF-8；公共接口使用类型注解和简洁 docstring。
- 禁止新增裸 `except:`、静默吞错、导入时文件副作用和 UI 直连数据库。
- 新模块必须说明层级、公开接口、依赖方向和测试入口，并同步本文件。
- `doc/proposal.md`、旧设计和 T01–T15 是历史；当前现代化背景见 `doc/modernization-*.md` 与 T16–T21。
- 最终报告必须列出实际测试命令和结果，不能只写“已测试”。
- 每次验证通过后必须重新构建、确认用户数据未变化并创建 Git 提交；提交信息应概括任务目的，不得包含秘密。

## 11. 维护记录

| 日期 | 任务 | 变更与接口影响 | 验证 |
|---|---|---|---|
| 2026-08-02 | 建立工程级代理指南 | 新增根目录指南，记录初始结构和风险 | 全源码与文档盘点、语法检查 |
| 2026-08-02 | T16–T21 Python 3.12 现代化与工程加固 | 统一 Miniconda base 3.12.4；删除旧环境/旧构建资产；新增测试/CI、Credential Manager、日历纯逻辑；统一 Conversation/AICallThread；加固 DB/AI/构建；公共接口按本文件更新 | 首轮回归失败得到复现；修复后 35 项 unittest、全模块导入、build `--check`、PyInstaller 构建和 43,880,676-byte exe offscreen 启动冒烟通过；真实 `data/` 未用于测试 |
| 2026-08-02 | T22 API Key 保存、构建数据保护与 Git 提交 | CredentialBlob 遵循 pywin32 字符串写入契约并对错误 1312 降级会话凭据；设置保存失败保留窗口并提示；构建改为 staging 后仅发布 exe；新增“每次修改后重新构建、提交且保护 `dist/data/`”强制规则 | 修改前 9 项聚焦测试出现 2 失败、1 错误；修复后 39 项全量测试、真实临时凭据、导入、环境检查、完整构建及隔离 exe 冒烟通过；`dist/data/` 完整性不变 |

后续每次工程修改都必须在此追加一行，并同时更新受影响章节。维护记录用于定位，完整实施细节和测试证据保存在对应 `doc/tasks/` 文件中。
