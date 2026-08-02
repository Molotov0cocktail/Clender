# Clender 悬浮快捷对话、双字号与日历间距高层设计

## 架构概览

依赖方向继续保持 `UI → service/pure logic → data/model`：

```text
MainWindow
  ├─ AppSettingsDialog ── complete config ── config.py
  ├─ CalendarWidget ── Canvas ── typography + calendar_logic
  ├─ EventManager ── EventDialog ── EventService ── database
  ├─ AIChatWidget ── AIService ── EventService
  │                └─ AICallThread ── Provider
  └─ DailyFloatingWindow
       ├─ floating_window_logic (time state)
       ├─ typography (floating scale)
       ├─ event_edit_requested ── MainWindow ── EventDialog/EventService
       └─ ai_message_submitted ── MainWindow ── AIChatWidget current conversation
```

`MainWindow` 仍是跨组件编排者。悬浮窗不直接写配置、Conversation 或事件数据库；AIChatWidget 不直接操控悬浮控件，只发出请求状态和数据变更信号。

## 模块边界

- `typography.py`：字号范围、严格解析、语义角色、应用/悬浮两套 scale、QFont 像素应用和样式值生成的单一来源。
- `constants.py`：新增 `app_font_size_px=13`、`floating_font_size_px=13` 默认字段。
- `theme_manager.py`：应用 palette/QSS 时使用应用 scale，不保留裸字号。
- `ui/app_settings.py`：保存两个独立字号和 0–100 opacity；保存成功后发完整配置。
- `ui/*`：每个组件只引用命名角色，不定义字号数字；主题重绘同时刷新字号。
- `ui/canvas.py`：依据 font metrics 建立 timeline geometry；geometry 既服务绘制也服务命中和测试。
- `floating_window_logic.py`：纯配置验证、时态分类、current/next 集合选择；不读取系统时间，由调用方注入。
- `ui/daily_floating_window.py`：frameless shell、拖缩、列表状态、AI 输入和 pin 控件；只发意图信号。
- `ui/ai_chat_widget.py`：将主输入与外部输入统一到一个发送方法，维护单个 worker 和发起 Conversation ID；对外发悬浮请求短状态。
- `ui/main_window.py`：连接双击编辑、快捷 AI、主题/字号应用和统一刷新。
- `event_service.py`：允许白名单字段 `event_type`，在合并后的完整事件上验证转换并向数据库一次写入。

## 数据流

### 双字号保存与应用

```text
AppSettingsDialog
  → validate sliders/spinboxes
  → config.save_config(full dict)
  → config_saved(config)
  → MainWindow._apply_app_settings(config)
      ├─ typography.apply_application_font(app scale)
      ├─ theme_manager.apply_theme(app)
      ├─ existing components apply_theme/re-render
      └─ DailyFloatingWindow.apply_settings(floating scale)
```

### 悬浮 AI

```text
Enter in floating input
  → DailyFloatingWindow.ai_message_submitted(text)
  → MainWindow
  → AIChatWidget.submit_external_message(text)
      → active Conversation.add_message(user)
      → build_request_messages
      → AICallThread
      → parse + execute_operations
      → originating Conversation persistence/render
      → external_request_status(short)
      → data_changed only when CRUD succeeded
  → MainWindow._on_data_changed() → all schedule views + floating refresh
```

### 双击编辑

```text
QListWidget.itemDoubleClicked
  → event_edit_requested(id)
  → MainWindow fetches Event
  → EventDialog(edit_event)
  → EventService.update_event(id, event_type, ...)
  → database.update_event
  → MainWindow._on_data_changed()
```

### 时态刷新

```text
refresh/query events + injected now
  → classify current candidates
  → find earliest future start and all ties
  → QListWidget roles current/next/normal
minute tick → recompute roles; date change additionally requery events
```

## 主要设计决策

- 使用 `WindowStaysOnBottomHint` 而非普通窗口表示“留在桌面”；pin 时与 `WindowStaysOnTopHint` 互斥。
- pin 是运行时状态，flags 切换必须保存 geometry/visibility，重新 `show()` 后恢复，不写配置。
- 悬浮 AI 调用 AIChatWidget 公共入口，不创建第二个 Controller 或 Conversation，确保历史、预算和线程完全一致。
- 悬浮窗只消费短状态信号；AI 正文只在主聊天渲染。
- 共享在途锁：任何入口发起请求后，另一个入口不能排队或覆盖 `_pending_conv_id`。
- 字号用 px 而非 point；两个 base scale 分离，最终角色钳制 8–20。
- 静态源码测试作为维护门禁，防止后续再次引入裸数字字号。
- Canvas gutter 是共享几何结果而非散落坐标，最小间距写入常量/测试契约。
- 无边框移动采用 Qt 拖动距离；缩放采用边缘 hit-test，避免输入控件被事件过滤器劫持。

## 替代方案

- 未共享主聊天 Conversation：会破坏用户确认的“便捷入口本质仍是 AI 页面”。
- 未将悬浮 AI 直接调用 `AIService.execute_operations`：会复制对话、线程、错误和持久化编排。
- 未持久化 pin：用户明确要求不跨重启。
- 未将默认设为普通非置顶：用户明确要求沉在所有窗口底层。
- 未统一所有文字为同一字号：保留语义层级，同时把每个最终字号限制在用户范围。
- 未增加 per-hour：用户接受保持 30px，短块使用现有自适应/marker。

## 风险与缓解

- Windows flags 切换可能隐藏/重建窗口：在切换前缓存 geometry、visibility、opacity，设置互斥 flags 后恢复。
- child viewport 吞噬拖动：统一事件过滤并明确排除输入、按钮、滚动条和 resize zone。
- 双击与拖动冲突：拖动超过 `startDragDistance()` 才移动；双击事件不由单击激活。
- Conversation 切换/并发：使用发起 Conversation ID 和单 worker guard，外部状态按 source 隔离。
- 字号迁移遗漏：静态扫描、命名 dataclass 角色、8/20px 组件测试和截图四层防线。
- 20px 固定控件裁剪：去除或按 `sizeHint/fontMetrics` 调整与文字相关的固定高度；窄区域允许 elide/overflow。
- 0% 不可见：设置 UI 始终可恢复，测试确认不自动钳高。

## 高层测试策略

- 纯逻辑：字号解析/角色、current/next、pin flag 选择、Canvas gutter 计算。
- 服务：事件类型转换、显式清空 end、无效转换零写入。
- Qt offscreen：两个字号设置、即时主题/字号、frameless flags、拖缩排除、双击信号、AI 外部提交和状态。
- 回归：现有 89 项、Thinking 锚点、Conversation ID 绑定、lane/marker/overflow/geometry/tray。
- 视觉：Light/Dark × 8/20px 的周/日/悬浮截图。
- 发布：全导入、环境检查、完整 PyInstaller、隔离 exe 启动/双实例、`dist/data/` 前后完整性。
