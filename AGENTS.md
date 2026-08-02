# AGENTS.md — Clender 工程协作指南

> **适用范围：** 本文件位于工程根目录，规则适用于整个仓库。若子目录以后出现更具体的 `AGENTS.md`，子目录规则只能补充本文件，不得降低这里的质量、测试和维护要求。
>
> **当前基线：** 2026-08-02，按仓库实际代码整理。`doc/` 中的文档记录了 2026-07-15 的重构背景与历史任务，代码现状优先于旧设计描述。

## 🚨 强制维护门禁（所有代理和开发者必须遵守）

### 修改前：先有任务清单和严格测试用例，再改代码

任何代码、配置、数据库结构、构建脚本或工程文档修改开始前，必须先完成以下事项：

1. 阅读本文件、`git status --short`、相关源码、相关 `doc/` 文档和已有任务记录；不得覆盖用户未提交的改动。
2. 在 `doc/tasks/` 新建或更新本次任务文件，并同步 `doc/tasks/progress.md`。任务文件至少写明：目标、非目标、影响文件、接口/数据影响、风险、实施步骤、回滚方式、完成定义。
3. **先写测试矩阵。** 每项修改至少覆盖：正常路径、边界值、非法输入/异常路径、回归路径；数据库修改还要覆盖事务与旧数据兼容；UI 修改还要覆盖信号、主题切换和无显示器冒烟；AI 修改还要覆盖无配置、超时、非 200、畸形 JSON 与危险操作输入。
4. 能自动化的用例应先落到测试代码中，并在修复 Bug 时先证明用例能够复现问题。仓库当前没有自动化测试目录，这是技术债，不是跳过测试的理由。
5. 未明确验收标准、测试用例或数据契约前，不得直接实施。发现需求歧义、破坏性操作、真实数据迁移或外部依赖变化时，先向用户确认。

### 修改中：小步、分层、可回滚

- 只修改任务清单列出的范围，避免顺手重构和无关格式化。
- 保持依赖方向：`ui → service → data/model`；数据层不得依赖 UI。
- 数据库访问走 `EventService`（服务层内部再访问 `database`）；AI 日程操作走 `AIService → EventService`。
- 不在主线程执行网络请求，不从工作线程直接更新 Qt 控件；使用 `QThread` 信号回到主线程。
- 不把 API Key、真实对话、数据库、日志或其他用户数据写入源码、测试夹具、构建产物或提交记录。
- 不手工编辑 `build/`、`build_win7/`、`dist/`、`__pycache__/` 中的生成文件。

### 修改后：测试、复核、同步文档缺一不可

1. 运行与风险匹配的测试、语法检查、导入检查和 UI 冒烟；不能运行的检查必须说明原因和剩余风险。
2. 检查 `git diff`，确认没有敏感数据、生成垃圾、无关改动或意外重写。
3. 更新对应 `doc/tasks/*.md` 和 `doc/tasks/progress.md`，记录实际变更、测试命令、结果、失败与遗留项。
4. **每次工程修改后必须立即更新本 `AGENTS.md`：**
   - 修改受影响的文件结构、接口、数据契约、命令、风险或约定；
   - 在“维护记录”中追加日期、任务、改动文件和验证摘要；
   - 即使接口未变化，也要记录“接口无变化”及本次验证结果。
5. 最终向用户明确反馈：改了什么、为何修改、执行了哪些测试、结果如何、`AGENTS.md` 更新了哪些内容、还有哪些风险。

## 1. 项目速览

Clender 是 Windows 桌面智能日程管理应用，使用 Python、PyQt5、SQLite 和 OpenAI 兼容的 Chat Completions API。主要能力包括：

- 月/周/日三种日历视图，周/日视图用 `QPainter` 绘制时间轴、事件块和重叠标记；
- 提醒（`reminder`）与时间段（`timespan`）事件的添加、编辑、删除和本地持久化；
- 多对话 AI 助手，将模型返回的 JSON 操作转换成日程 CRUD；
- 日间/夜间主题、系统托盘、运行时配置、日志和 PyInstaller 打包；
- 标准 Windows 构建，以及基于工程内 Python 3.8 环境的 Windows 7 兼容构建。

当前没有 README、CI、`pyproject.toml`、锁文件或自动化测试目录。依赖仅记录在 `requirements.txt`；工程内 `.conda/` 与 `.wheels/` 用于本地/离线及 Win7 构建。

## 2. 技术栈与当前环境

| 层面 | 实现 |
|---|---|
| GUI | PyQt5（Widgets、Signals/Slots、QThread、QPainter） |
| 语言 | Python；工程内 `.conda/python.exe` 为 3.8.20 |
| 数据库 | `sqlite3` + `data/clender.db` |
| 配置/对话 | UTF-8 JSON 文件 |
| HTTP | `requests`，Bearer Token，OpenAI 兼容接口 |
| 日期处理 | 标准库 `datetime`、`calendar`；依赖中另含 `python-dateutil` |
| 打包 | PyInstaller；标准单文件包与 Win7 单文件包 |

已知本机状态：默认 `python` 为 3.14.6，但未安装本项目依赖；`.conda/python.exe` 能导入 PyQt5、requests 2.32.4、python-dateutil 2.9.0.post0，但**当前项目代码不能在 Python 3.8 完整导入**：`conversation_store.py`、`ai_service.py` 等文件使用了 `dict[str, ...]`、`list[...]`、`tuple[...]` 这类 Python 3.9+ 运行时注解，导入时会报 `TypeError: 'type' object is not subscriptable`。因此当前没有开箱即用的完整源码运行环境；开发者需使用装有依赖的 Python 3.9+ 环境，或先通过独立任务恢复 Python 3.8 注解兼容性。

## 3. 文件结构与职责

```text
Clender/
├─ main.py                    # 应用入口：QApplication、显式 init_db、MainWindow
├─ models.py                  # EventType、Event、Message、Conversation
├─ constants.py               # 提示词、模型能力、默认配置、事件/Canvas 常量
├─ config.py                  # data 路径及 config.json 读写
├─ logger.py                  # 控制台 + data/clender.log 日志
├─ database.py                # SQLite events 表与 CRUD
├─ event_service.py           # UI 与数据库之间的事件业务层
├─ conversation_store.py      # Conversation JSON 持久化
├─ ai_service.py              # AI 上下文、Token 估算、响应解析、操作执行
├─ ai_client.py               # 模型列表请求、Chat Completions QThread
├─ theme_manager.py           # Light/Dark 颜色与全局主题应用
├─ ui/
│  ├─ main_window.py          # 三栏主窗口、设置、主题、托盘、刷新编排
│  ├─ calendar_widget.py      # 月/周/日视图及事件块数据计算
│  ├─ canvas.py               # WeekCanvas、DayCanvas 绘制
│  ├─ event_manager.py        # 某日事件列表与增删改入口
│  ├─ event_dialog.py         # 事件表单、校验与数据组装
│  ├─ ai_chat_widget.py       # AI 对话 UI、线程编排、对话兼容层
│  ├─ ai_settings.py          # API、模型、Token、Thinking、提示词设置
│  ├─ sidebar.py              # 多对话列表及选择/新建/删除/重命名信号
│  └─ __init__.py
├─ data/                      # 运行时数据；被 .gitignore 忽略，视为敏感数据
│  ├─ clender.db              # events 表
│  ├─ config.json             # 可能含明文 API Key
│  ├─ conversations.json      # 真实聊天历史
│  ├─ clender.log             # 日志
│  └─ sample_loaded.flag      # 示例数据仅加载一次的标记
├─ doc/
│  ├─ proposal.md             # 重构目标、问题与验收背景
│  ├─ high-level-design.md    # 历史架构逆向说明
│  ├─ detailed-design.md      # 分层重构设计
│  └─ tasks/                  # T01–T15 历史任务及 progress.md
├─ references/prompt-templates.md  # 文档驱动任务模板
├─ agents/openai.yaml         # Vibe Coding 工作流界面元数据
├─ requirements.txt           # PyQt5、requests、python-dateutil
├─ build.py                   # 标准 PyInstaller 构建并创建桌面快捷方式
├─ build_win7.py              # Win7 环境检查、清理、构建、快捷方式
├─ Clender.spec               # 标准构建生成/使用的 spec（含本机绝对路径）
├─ Clender_win7.spec          # Win7 构建生成/使用的 spec
├─ create_shortcut.ps1        # 已有标准版快捷方式脚本
├─ .conda/                    # 本地 Python 3.8.20 构建环境，不作为源码修改
├─ .wheels/                   # 离线 wheel 集合，不手工修改
├─ build/、build_win7/        # PyInstaller 中间产物
└─ dist/                      # 可执行文件输出
```

`build*`、`dist/`、`.conda/`、`.wheels/` 和运行时 `data/` 可能很大或包含二进制文件；理解工程时检查其清单和构建配置即可，不要把生成文件当成源码逐项修改。

## 4. 架构与主要数据流

### 启动

`main.main()` 创建 `QApplication` → 设置应用名、组织名、字体和托盘关闭策略 → `database.init_db()` → 创建并显示 `ui.main_window.MainWindow` → 进入 Qt 事件循环。

`MainWindow` 创建 `CalendarWidget`、`EventManager`、`AIChatWidget` 三栏，加载首次示例数据，连接信号，刷新事件标记并应用主题。

### 手工事件

`CalendarWidget.date_selected(date)` → `MainWindow._on_date_selected()` → `EventManager.set_date()`/日历选中态更新。

增删改由 `EventManager` 打开 `EventDialog`，再调用 `EventService`；成功后发出 `data_changed`，`MainWindow` 统一刷新日历标记和事件列表。

### AI 日程操作

`AIChatWidget._send_message()` → 保存用户消息 → `AICallThread` 在子线程读取配置、构建上下文并请求 Chat Completions → `AIService.parse_ai_response()` → 主线程 `_on_result()` → `AIService.execute_operations()` → `EventService` → `database` → `AIChatWidget.data_changed` → 主窗口刷新。

### 主题

`MainWindow._toggle_theme()` → `theme_manager.switch_theme()` 保存主题并应用全局 Palette/QSS → 三个主组件各自 `apply_theme()` 重绘。

## 5. 核心接口与契约

### 5.1 模型：`models.py`

- `EventType.REMINDER == "reminder"`；`EventType.TIMESPAN == "timespan"`。
- `Event(id, event_type, title, start_time, end_time, description, estimated_duration, created_at)`：
  - `start_time`/`end_time` 使用字符串 `YYYY-MM-DD HH:MM`；
  - `reminder` 通常 `end_time=None`，可用 `estimated_duration`（分钟）影响显示高度；
  - `timespan` 必须有晚于开始时间的 `end_time`；
  - 提供 `from_row()`、`to_dict()`、`date`，并暂时兼容 `event['title']`/`event.get(...)`。
- `Message(role, content, timestamp)`：角色当前使用 `user`、`assistant`、`think`。
- `Conversation(id, title, messages, created_at, token_count)`：提供 `add_message()`、`to_dict()`、`from_dict()`。

注意：`ui/ai_chat_widget.py` 仍保留一个 dict-based 的本地 `Conversation` 兼容类，并在持久化边界与 `models.Conversation` 相互转换。修改对话模型时必须同时覆盖这两条表示路径，或通过独立任务安全消除兼容层。

### 5.2 配置与日志

`config.py` 的公开接口：

- `get_app_data_dir()`：开发环境返回工程根目录下 `data/`；冻结 exe 返回 exe 同目录下 `data/`。
- `APP_DATA_DIR`、`CONFIG_FILE`、`DB_PATH`：模块级路径；导入时会创建数据目录。
- `load_config()`：默认配置与 JSON 合并，坏 JSON/IO 错误时记录警告并返回默认值。
- `save_config(config)`、`is_api_configured()`、`get_theme()`、`set_theme(theme)`。

配置键由 `constants.DEFAULT_CONFIG` 定义：`theme`、`api_endpoint`、`api_key`、`model`、`temperature`、`max_tokens`、`context_window`、`thinking_enabled`、`think_effort`、`available_models`、`system_prompt`、`ai_personality`、`close_to_tray`。

`logger.get_logger(name)` 返回 DEBUG logger：文件记录 DEBUG+，控制台仅 WARNING+。日志路径与应用数据目录保持一致。API Key 不得写日志。

### 5.3 数据库与服务

`database.py`：

- `get_connection()`；`init_db()` 必须在启动时显式调用。
- `add_event(event_type, title, start_time, end_time=None, description='', estimated_duration=0) -> int`。
- `update_event(event_id, **fields) -> int`：仅允许 `title/start_time/end_time/description/estimated_duration`，未知字段和 `None` 当前会被过滤。
- `delete_event(event_id) -> bool`。
- `get_events_by_date(date) -> list[Event]`、`get_events_date_range(start, end) -> list[Event]`、`get_all_events() -> list[Event]`、`get_event_by_id(id) -> Event | None`。
- `is_sample_loaded()`、`mark_sample_loaded()`。

`EventService` 对外提供同类查询/CRUD，并额外提供 `get_event_counts()` 与 `load_sample_data_if_empty()`。UI 新代码应调用服务层，不应直接调用数据库层。

`events` 表当前字段：

| 字段 | 约束/含义 |
|---|---|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT |
| `event_type` | `reminder` 或 `timespan` |
| `title` | 非空文本 |
| `start_time` | 非空文本，`YYYY-MM-DD HH:MM` |
| `end_time` | 可空；时间段结束时间 |
| `description` | 默认空字符串 |
| `estimated_duration` | 整数分钟，默认 0 |
| `created_at` | 默认 CURRENT_TIMESTAMP |

数据库没有显式业务索引；日期查询依赖 `start_time` 文本比较/前缀匹配。Schema 变更必须包含旧库迁移、回滚和临时数据库测试，禁止直接在真实 `data/clender.db` 上试验。

### 5.4 AI 接口

`AIService` 的主要接口：

- `get_current_date_context()`、`get_effective_system_prompt()`、`build_context_messages()`；上下文包含当前日期和全部事件。
- `estimate_tokens()`/`count_messages_tokens()` 是经验估算，不是模型官方 tokenizer。
- `guess_model_capabilities(model_id)` 根据 `MODEL_CAPABILITIES` 和名称回退规则推断窗口。
- `parse_ai_response(content)` 接受纯 JSON、Markdown JSON 代码块、操作数组或单个对象；无法解析时写入 `reply_text`。
- `execute_operations(ops)` 支持 `add`、`update`、`delete`、`reply`。数据库变更均经 `EventService`。

模型响应的目标契约：

```json
{"operations":[
  {"action":"add","event_type":"reminder|timespan","title":"...","start_time":"YYYY-MM-DD HH:MM","end_time":null},
  {"action":"update","event_id":1,"title":"..."},
  {"action":"delete","event_id":2},
  {"action":"reply","message":"..."}
]}
```

`ai_client.fetch_models_list()` 调用 OpenAI 兼容 `GET /v1/models`（endpoint 已以 `/v1` 结尾时用 `/models`），超时 15 秒。

`AICallThread` 调用 `POST /v1/chat/completions`，Bearer 鉴权，超时 180 秒，并发出：

- `result_ready(dict)`：解析结果，附加 `think` 和 `usage`；
- `error_occurred(str)`：未配置、超时、连接失败、HTTP 错误或解析异常。

Thinking 模式通过 `reasoning_effort` 和 `extra_body.thinking.type` 传递。不同兼容服务未必支持这些字段；变更时要测试支持与不支持两类 Provider。

### 5.5 UI 类与信号

| 类 | 对外接口/信号 | 职责 |
|---|---|---|
| `MainWindow` | Qt 窗口生命周期 | 三栏编排、设置、托盘、主题和刷新 |
| `CalendarWidget` | `date_selected(date)`；`get_selected_date()`、`set_selected_date()`、`update_event_markers()`、`apply_theme()` | 月/周/日选择与渲染 |
| `WeekCanvas`/`DayCanvas` | 构造参数 `blocks, timeline_height, per_hour, theme` | 只负责绘制，不做数据库访问 |
| `EventManager` | `data_changed()`；`set_date()`、`refresh()`、`apply_theme()` | 事件列表与 CRUD 对话框入口 |
| `EventDialog` | `get_data()` | 标题、类型、开始/结束、描述、预计时长校验与组装 |
| `AIChatWidget` | `data_changed()`；`refresh_api_state()`、`apply_theme()` | 对话、线程、响应处理和 Token 展示 |
| `SettingsDialog` | `config_saved()` | AI 配置读写与模型列表获取 |
| `ConversationSidebar` | `conversation_selected(str)`、`new_conversation()`、`delete_conversation(str)`、`rename_conversation(str,str)` | 对话导航 |

绘制块是内部 dict 契约。周视图包含 `col/top/height/title/tlabel/color_idx/event_type/is_reminder/id/overlap_ranges`；日视图除 `col` 固定为 0 外结构相同。修改生成逻辑或 Canvas 时必须成对验证。

## 6. 运行、检查和构建

### 开发运行

```powershell
# 需要 Python 3.9+ 且已安装 requirements.txt 中的依赖
python .\main.py
```

新环境安装：

```powershell
python -m pip install -r requirements.txt
python main.py
```

不要假设系统默认 Python 已安装依赖。当前本机默认 Python 3.14.6 缺少依赖，而工程内 Python 3.8.20 又会被新式类型注解阻断；运行前必须明确解释器和依赖状态。首次运行会创建/修改 `data/` 并可能注入 6 条当天示例日程。

### 当前可用的最低验证

仓库尚无 pytest/unittest 套件。每次改动至少执行：

```powershell
# 语法检查（不导入项目模块；会生成可忽略的 __pycache__）
python -m compileall -q .

# 关键模块导入冒烟（要求当前解释器已安装依赖）
& '.\.conda\python.exe' -c "import models, constants, config, logger, database, event_service, conversation_store, ai_service, ai_client, theme_manager; import ui.main_window, ui.calendar_widget, ui.canvas, ui.event_manager, ui.event_dialog, ui.ai_chat_widget, ui.ai_settings, ui.sidebar; print('imports ok')"

# 构建环境检查（只读）
& '.\.conda\python.exe' .\build_win7.py --check
```

注意：截至当前基线，上述 `.conda` 全模块导入命令会在 `conversation_store.py` 失败。它既是回归检查，也是 Python 3.8 兼容修复任务的明确验收命令；不得把预期失败报告成通过。

UI 逻辑变更还必须设置 `QT_QPA_PLATFORM=offscreen` 做无显示器构造/退出冒烟，并在可用 Windows 桌面环境手工检查月/周/日视图、CRUD、主题和托盘。涉及数据的自动化测试必须将 `config.DB_PATH`、`CONFIG_FILE`、`APP_DATA_DIR` 指向临时目录，绝不能污染真实 `data/`。

引入正式测试时，优先创建 `tests/`，覆盖纯逻辑/服务/临时 SQLite；Qt 测试再选择与既有环境兼容的工具。新增依赖必须先获得用户同意并更新 `requirements.txt`、离线 wheel/Win7 兼容说明和本文件。

### 构建

```powershell
# 标准构建；可能安装依赖、覆盖构建产物并创建桌面快捷方式
python .\build.py

# Win7 环境检查 / 完整构建
& '.\.conda\python.exe' .\build_win7.py --check
& '.\.conda\python.exe' .\build_win7.py
```

`build_win7.py --clean` 会删除 `dist/win7/`、`build_win7/` 和 `Clender_win7.spec`，属于破坏性但范围明确的命令；非用户明确要求或构建流程需要时不要执行。

两套构建脚本当前都会把 `data/` 加入包。构建前必须确认其中没有 API Key、私人对话、日志或真实数据库；更稳妥的后续改进是只打包空目录/默认模板而不是整个运行数据目录。

## 7. 测试基线与变更测试矩阵

| 变更类型 | 最低自动化/脚本检查 | 必须的专项验证 |
|---|---|---|
| 模型/纯函数 | 正常、空值、非法值、序列化往返 | dict 兼容层及旧 JSON 兼容 |
| 数据库/服务 | 临时 DB 的建表、CRUD、不存在 ID、非法字段、日期范围 | 旧 schema 迁移、连接关闭、真实数据不被触碰 |
| AI 解析/执行 | 纯 JSON、代码块、数组、畸形 JSON、未知 action、缺 ID | 无配置、超时、连接错误、非 200、Provider 字段兼容 |
| 对话持久化 | 空文件、坏 JSON、UTF-8、往返、多个对话 | 本地兼容 Conversation 与模型 Conversation 一致性 |
| UI 表单/信号 | offscreen 构造、信号次数、输入校验 | 月/周/日、主题、增删改、关闭/托盘人工回归 |
| Canvas | 空 blocks、边界时间、跨界高度、重叠合并、reminder/timespan | 不同尺寸、Light/Dark 的截图或人工视觉检查 |
| 配置/安全 | 缺文件、坏 JSON、默认合并、空 Key | Key 不入日志/diff/包，打包数据清洁 |
| 构建 | 语法、导入、`--check` | 新依赖 hidden import、标准/Win7 exe 启动冒烟 |

Bug 修复必须包含一个能在修复前失败、修复后通过的回归用例。若环境限制导致无法先运行失败态，应在任务文件中记录复现输入、预期/实际结果和限制。

## 8. 安全、数据与兼容性约束

- `data/` 被忽略但不代表可随意读取、复制或清空；其中内容属于用户运行数据。
- API Key 当前以明文存入 `config.json`。任何输出、日志、测试快照和提交中都必须脱敏；不要在最终回复展示真实配置值。
- 所有 SQL 值必须参数化；动态字段名必须使用白名单。数据库写入应保证异常时连接可关闭，重大改造优先使用上下文管理器/事务。
- AI 返回内容是不可信输入。执行前验证 action、事件类型、ID、时间格式、时间顺序和必填字段；不要扩大为文件、命令或任意 SQL 执行。
- Win7 构建目标要求 Python 3.8 兼容，除非用户明确取消该目标。当前代码已经存在 Python 3.9+ 泛型注解导致的兼容缺陷；新增代码不得继续扩大问题，修复时可统一引入 `from __future__ import annotations` 或改用 `typing.List/Dict/Tuple`，并以 `.conda` 全模块导入和 Win7 构建冒烟为验收。
- `.spec` 中存在本机绝对路径，构建脚本可能重新生成/重命名 spec；不要把生成差异误判为业务改动。
- `config.py` 与 `logger.py` 在导入时可能创建 `data/`；测试应提前隔离路径。

## 9. 已知技术债与高风险区

1. 无自动化测试和 CI，历史“验证通过”主要是语法、导入和手工检查。
2. **Win7/Python 3.8 兼容当前已破坏：** 新式内置泛型注解会在模块导入时触发 `TypeError`，导致工程内构建环境无法加载完整项目。
3. AI 对话有两套 `Conversation` 表示及转换层，容易出现消息类型/Token 计数不一致。
4. `AICallThread` 的旧兼容构造路径依赖 dict 消息；变更模型对象时容易破坏。
5. Token 数量只是字符估算，不能作为严格上下文上限保证。
6. `AIService.execute_operations()` 的输入校验有限，时间格式和业务约束仍需强化。
7. 数据库连接采用手工 `close()`，异常路径存在连接未关闭风险；没有业务索引。
8. `update_event()` 会过滤值为 `None` 的字段，当前无法用它显式清空可空字段。
9. 构建把整个 `data/` 作为资源，存在把敏感运行数据带入 exe 的风险。
10. `requirements.txt` 只有下限、无锁定；默认系统 Python 与工程依赖环境不一致。
11. 部分 UI 文件仍包含较密集的布局、业务组装和内联 QSS，修改时回归面较大。

## 10. 文档与提交约定

- 以 UTF-8 保存 Python、Markdown、JSON 和 PowerShell 文本。
- 公共函数和关键业务路径使用类型注解与简洁 docstring；避免新增裸 `except:` 和静默吞错。
- 新模块应明确所在层、公开接口、依赖方向和测试入口，并同步本文件的结构与接口章节。
- `doc/proposal.md`、设计文档和 T01–T15 是重构历史，不应为了“看起来一致”覆盖事实；新工作使用新的任务编号/可读任务名，并在 `progress.md` 追加。
- 每次最终交付报告测试命令及结果，不使用笼统的“已测试”。

## 11. 维护记录

| 日期 | 任务 | 变更与接口影响 | 验证 |
|---|---|---|---|
| 2026-08-02 | 建立工程级代理指南 | 新增根目录 `AGENTS.md`；未修改运行代码、接口或数据；记录 Python 3.8 导入兼容缺陷 | 核对全部源码模块、设计/任务文档、构建配置和运行数据结构；Python 3.8 语法编译通过，全模块导入在 `conversation_store.py` 按现状失败并已记录 |

后续每次工程修改都必须在此追加一行，并同时更新上文受影响章节。维护记录用于快速定位，完整实施细节和测试证据仍保存在对应 `doc/tasks/` 文件中。
