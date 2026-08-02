# 高层架构设计（当前现状）

> **说明**：本文档基于项目当前代码逆向推导，描述 **现状架构** 而非目标架构。  
> 后续重构的 detailed-design.md 将基于本文档中的痛点提出改进方案。

---

## 1. 系统概览

Clender 采用**单体桌面应用**架构，基于 PyQt5 的事件驱动模型。整体上可分为四个非正式的层次：

```
┌─────────────────────────────────────────────────────────┐
│                   UI 层 (PyQt5 Widgets)                  │
│  main.py  │  calendar_widget.py  │  event_manager.py    │
│           │  ai_chat.py (AIChatWidget/Sidebar/Dialog)   │
├─────────────────────────────────────────────────────────┤
│                 业务逻辑层（隐式、分散）                    │
│  ai_chat.py (AI解析/操作执行)  │  calendar_widget.py     │
│  (Canvas渲染算法)              │  event_manager.py       │
│                               │  (EventDialog表单逻辑)    │
├─────────────────────────────────────────────────────────┤
│                    基础设施层                              │
│  config.py (JSON配置)  │  theme_manager.py (主题管理)     │
├─────────────────────────────────────────────────────────┤
│                    数据层                                 │
│  database.py (SQLite CRUD)  │  conversations.json        │
│  clender.db                 │  config.json               │
└─────────────────────────────────────────────────────────┘
```

**关键特征**：层次边界模糊——UI 组件直接调用 `database.*` 和 `config.*`，业务逻辑与 UI 渲染代码混合在同一文件中。

---

## 2. 模块职责与依赖关系

### 2.1 模块清单

| 文件 | 行数 | 主要职责 | 依赖关系 |
|------|------|----------|----------|
| `main.py` | 290 | 应用入口、主窗口、信号编排、布局管理 | calendar_widget, event_manager, ai_chat, database, config, theme_manager |
| `calendar_widget.py` | 446 | 月/周/日日历视图、Canvas渲染、事件标记 | theme_manager, database, calendar(stdlib) |
| `event_manager.py` | 442 | 事件列表、添加/编辑/删除对话框 | database, theme_manager |
| `ai_chat.py` | 900 | AI对话UI、多对话管理、API调用、操作解析执行 | config, database, theme_manager, requests |
| `database.py` | 141 | SQLite CRUD、示例数据标记 | config |
| `config.py` | 83 | JSON配置读写、路径管理 | 无内部依赖（仅 stdlib） |
| `theme_manager.py` | 280 | 日间/夜间主题定义、全局样式应用 | config |
| `build.py` | 154 | PyInstaller 打包、快捷方式创建 | 无内部依赖 |

### 2.2 当前依赖图（ASCII）

```
                    ┌─────────┐
                    │ main.py │  ←─ 编排者，导入所有其他模块
                    └────┬────┘
         ┌───────────────┼───────────────┐
         ▼               ▼               ▼
┌────────────────┐ ┌───────────┐ ┌──────────────┐
│calendar_widget │ │event_mgr  │ │  ai_chat.py  │
│     .py        │ │   .py     │ │              │
└───────┬────────┘ └─────┬─────┘ └──────┬───────┘
        │                │              │
        └────────┬───────┘              │
                 ▼                      │
          ┌──────────┐                  │
          │ database │◄─────────────────┘
          └────┬─────┘
               │
          ┌────▼─────┐         ┌──────────────┐
          │ config.py │◄────────│theme_manager │
          └───────────┘         └──────────────┘
```

**核心问题**：
- `ai_chat.py` 被 `main.py` 导入，同时 `ai_chat.py` 又导入 `config`, `database`, `theme_manager`——UI 模块反向依赖数据/配置层，形成事实上的网状耦合。
- `calendar_widget.py` 和 `event_manager.py` 直接导入 `database`，View 层直达 Data 层。
- 没有独立的 Service/Business 层来隔离 UI 和数据。

---

## 3. 数据流

### 3.1 用户手动操作流程

```
用户点击日历日期
    │
    ▼
CalendarWidget.date_selected(date) 信号
    │
    ▼
MainWindow._on_date_selected(d)
    ├── EventManager.set_date(d)
    │       └── database.get_events_by_date(d)  →  更新事件列表
    └── CalendarWidget.set_selected_date(d)  →  高亮选中日期
```

```
用户点击"添加事项"
    │
    ▼
EventDialog.exec_()  →  用户填写表单  →  get_data()
    │
    ▼
database.add_event(...)  →  SQLite INSERT
    │
    ▼
EventManager.data_changed 信号
    │
    ▼
MainWindow._on_data_changed()
    ├── database.get_all_events()  →  统计每日期事件数
    ├── CalendarWidget.update_event_markers(counts)
    └── EventManager.refresh()
```

### 3.2 AI 操作流程

```
用户发送消息
    │
    ▼
AIChatWidget._send_message()
    ├── Conversation.add_message('user', text)
    └── AICallThread (QThread) 启动
            │
            ▼ (子线程)
        build_context_messages()
            ├── cfg_mod.load_config()      ← 获取系统提示词
            ├── database.get_all_events()   ← 获取当前所有事件
            └── get_current_date_context()  ← 获取当前日期上下文
            │
        requests.post(API)  →  LLM 返回 JSON
            │
        parse_ai_response(content)
            └── 正则提取 JSON  →  {'operations': [...]}
            │
            ▼ (回主线程)
        AIChatWidget._on_result(parsed)
            ├── Conversation.add_message('think', ...)
            ├── execute_operations(ops)
            │       ├── database.add_event()   ← add
            │       ├── database.update_event()← update
            │       └── database.delete_event()← delete
            ├── Conversation.add_message('assistant', result)
            └── AIChatWidget.data_changed 信号
                    │
                    ▼
                MainWindow._on_data_changed()  →  刷新日历和列表
```

### 3.3 主题切换流程

```
用户点击主题按钮 / 设置中切换
    │
    ▼
MainWindow._toggle_theme()
    └── theme_manager.switch_theme(app, new_theme)
            ├── config.set_theme(new_theme)  →  保存到 config.json
            └── theme_manager.apply_theme(app)
                    ├── QApplication.setPalette(...)
                    ├── QApplication.setStyleSheet(...)
                    └── (返回 MainWindow)
                        ├── CalendarWidget.apply_theme()  →  重建视图
                        ├── EventManager.apply_theme()    →  更新样式表
                        └── AIChatWidget.apply_theme()    →  更新样式表
```

---

## 4. 数据模型

### 4.1 SQLite 数据库

**表：`events`**

| 字段 | 类型 | 说明 |
|------|------|------|
| id | INTEGER PK | 自增主键 |
| event_type | TEXT NOT NULL | `'reminder'` 或 `'timespan'` |
| title | TEXT NOT NULL | 事件标题 |
| start_time | TEXT NOT NULL | 起始时间 `YYYY-MM-DD HH:MM` |
| end_time | TEXT | 结束时间（仅 timespan） |
| description | TEXT DEFAULT '' | 备注说明 |
| estimated_duration | INTEGER DEFAULT 0 | 提醒预估时长（分钟） |
| created_at | TEXT DEFAULT CURRENT_TIMESTAMP | 创建时间 |

**索引**：无显式索引（查询依靠 `start_time LIKE 'YYYY-MM-DD%'` 前缀匹配）。

### 4.2 JSON 配置文件

**`data/config.json`**：扁平键值结构，共 14 个配置项（参见 `config.py:30-44` 的 `DEFAULT_CONFIG`）。

**`data/conversations.json`**：
```json
{
  "<conv_id>": {
    "id": "abc123",
    "title": "对话标题",
    "messages": [
      {"role": "user/assistant/think", "content": "...", "timestamp": "ISO8601"}
    ],
    "created_at": "ISO8601"
  }
}
```

### 4.3 内存中的数据传递

整个代码库使用 **dict** 作为通用数据传递格式：
- `database.*` 返回 `dict`（通过 `sqlite3.Row` + `dict(row)`）
- `parse_ai_response()` 返回 `dict`
- `EventDialog.get_data()` 返回 `dict`
- `execute_operations()` 接收/返回 `list[dict]`

**无任何 dataclass/NamedTuple/类型定义**，字段契约完全由代码约定。

---

## 5. 主题系统设计

### 5.1 结构

`theme_manager.py` 定义两个扁平字典 `LIGHT_THEME` / `DARK_THEME`，各包含约 55 个颜色键。这些键按语义分组：

- **全局**：`app_bg`, `frame_bg`, `frame_border`
- **文字**：`title_color`, `subtitle_color`, `text_color`, `muted_color`
- **交互**：`primary`, `primary_hover`, `primary_text`, `danger`, `danger_hover`, `info`, `info_hover`, `success`, `warning_text`
- **控件**：`header_bg`, `input_bg`, `input_border`, `list_bg`, `list_item_hover`, `scrollbar_bg`
- **聊天**：`chat_user_color`, `chat_ai_color`, `chat_system_color`
- **日历**：`calendar_today_*`, `calendar_selected_*`, `calendar_cell_*`
- **事件**：`event_reminder_color`, `event_timespan_color`
- **其他**：`statusbar_bg`, `nav_btn_color`, `switch_btn_text`, `switch_btn_checked_bg`, `slot_bg`, `slot_border`

### 5.2 应用方式

- **全局**：`theme_manager.apply_theme(app)` 通过 `QPalette` + `QSS` 设置全局样式表
- **组件级**：各 Widget 的 `apply_theme()` 方法负责自己的局部样式，通过 `theme_manager.get_current_theme()` 获取当前主题字典，用 f-string 拼接 CSS
- **切换时**：`switch_theme()` → 修改 `config.json` → 重新调用所有组件的 `apply_theme()` → 日历组件还需 `_render_view()` 重建

---

## 6. Canvas 渲染系统

周视图和日视图使用 **QPainter 手绘** 而非 Widget 堆叠：

### 6.1 渲染流程

1. 从 `database.get_events_date_range()` / `get_events_by_date()` 获取事件列表
2. 将每个事件转换为 `{col, top, height, title, tlabel, color_idx, event_type}` 格式的 block
3. **重叠检测**：O(n²) 比较同列内 pair 的 top/bottom 区间，计算 overlap_ranges
4. 内嵌 `WeekCanvas` / `DayCanvas` 类通过 `paintEvent()` 绘制：
   - 时间轴虚线（0:00-24:00，每小时一条）
   - 事件色块（圆角矩形 + 标题/时间文字）
   - 重叠标记（半透明白色虚线竖条）
   - 提醒事项（红色横线标记）

### 6.2 性能特征

- 每次日期切换、主题切换、数据变更都会**完全重建** Canvas 内的所有 QWidget（`_clear_content()` + `_render_view()`）
- 色块数量 = 当日/当周事件数，通常 ≤ 50
- 无虚拟滚动，时间轴固定 720px（24h × 30px）

---

## 7. AI 集成架构

### 7.1 API 调用

- **协议**：OpenAI Chat Completions 兼容 API
- **端点**：用户可配置，自动补全 `/v1/chat/completions`
- **认证**：Bearer Token
- **超时**：180 秒
- **线程模型**：`AICallThread(QThread)` 在子线程发起 HTTP 请求，通过信号回传结果

### 7.2 上下文构建

每次请求动态构建消息数组：
```
[system: SYSTEM_PROMPT]
[system: AI人格设定] (可选)
[system: 当前日期+所有事件JSON]
[历史消息...] (受Token预算裁剪)
[user: 当前输入]
```

Token 预算计算：`avail = max(ctx_window - base_tokens - max_tokens, 4096)`

### 7.3 响应解析

`parse_ai_response()` 按优先级尝试：
1. 提取 Markdown 代码块中的 JSON
2. 搜索第一个 `{` 或 `[` 后的 JSON 子串
3. 失败则当作纯文本回复

### 7.4 操作执行

`execute_operations()` 根据 JSON 中的 `action` 字段分发：
- `add` → `database.add_event()`
- `update` → `database.update_event(event_id, **fields)`
- `delete` → `database.delete_event(event_id)`
- `reply` → 不执行操作，仅显示文本

执行结果回显到聊天界面，并通过 `data_changed` 信号通知主窗口刷新。

---

## 8. 已知风险与重构切入点

| 编号 | 风险 | 影响范围 | 优先级 |
|------|------|----------|--------|
| R1 | `ai_chat.py` 900行单体文件 | 维护性差，修改任一功能需遍历全文件 | 🔴 高 |
| R2 | UI 直接依赖 database/config | 无法独立测试 UI，数据层变更波及 UI | 🔴 高 |
| R3 | Canvas 内嵌类不可测试 | 渲染逻辑无法验证 | 🟡 中 |
| R4 | dict 作为唯一数据契约 | 字段拼写错误只能在运行时发现 | 🟡 中 |
| R5 | `init_db()` 在模块导入时执行 | 导入 database 即产生副作用 | 🟡 中 |
| R6 | 线程间无锁访问共享状态 | 潜在竞态条件 | 🟡 中 |
| R7 | `config.json` 含真实 API Key | 密钥已泄露到代码仓库 | 🔴 高 |
| R8 | 无日志系统 | 运行时错误难以追踪 | 🟢 低 |

---

## 9. 文件组织

```
Clender/                          # 项目根目录
├── main.py                       # 应用入口 + 主窗口
├── calendar_widget.py            # 日历视图组件
├── event_manager.py              # 事项管理面板 + 编辑对话框
├── ai_chat.py                    # AI对话UI + 对话管理 + API调用 + 操作解析
├── database.py                   # SQLite 数据库操作
├── config.py                     # JSON 配置管理
├── theme_manager.py              # 日间/夜间主题定义与应用
├── build.py                      # PyInstaller 打包脚本
├── requirements.txt              # Python 依赖
├── Clender.spec                  # PyInstaller spec 文件
├── create_shortcut.ps1           # 桌面快捷方式 PowerShell 脚本
├── data/                         # 运行时数据（gitignore 候选）
│   ├── clender.db                # SQLite 数据库
│   ├── config.json               # 应用配置（⚠️ 含 API Key）
│   ├── conversations.json        # 对话历史
│   └── sample_loaded.flag        # 示例数据加载标记
├── build/                        # PyInstaller 输出
│   └── Clender/
├── agents/                       # AI Agent 配置（与本项目无关）
│   └── openai.yaml
└── references/                   # 参考文档
    └── prompt-templates.md