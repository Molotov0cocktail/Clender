# Clender 桌面体验修复与今日悬浮窗高层设计

## 架构概览

依赖方向保持 `UI → service/pure logic → data/model`：

```text
main.py
  ├─ SingleInstanceCoordinator ── QLocalServer/QLocalSocket
  └─ MainWindow
       ├─ CalendarWidget ── calendar_logic ── EventService ── database
       ├─ AIChatWidget
       ├─ AppSettingsDialog ── config
       └─ DailyFloatingWindow ── floating_window_logic ── EventService
```

`MainWindow` 仍是数据变化与主题变化的唯一 UI 编排者。悬浮窗不直接订阅数据库；`EventManager.data_changed`、`AIChatWidget.data_changed` 都进入 `_on_data_changed()`，该方法同时刷新日历、列表和悬浮窗。

## 模块边界

- `config.py`：完整 JSON 配置原子读写，不再依赖秘密存储。
- `single_instance.py`：应用实例竞争、激活消息与生命周期；不导入 UI 或数据库。
- `calendar_logic.py`：事件 block、重叠簇、lane 和 overlap ranges 的纯计算。
- `ui/canvas.py`：根据 lane/可用宽度绘制、建立命中区域、发射单个或多个事件 ID。
- `floating_window_logic.py`：悬浮设置校验、日期区间计算、过滤和格式化纯函数。
- `database.py` / `event_service.py`：提供与时间范围相交的事件查询。
- `ui/event_detail_dialog.py`：共享只读详情；不直接查询数据库。
- `ui/daily_floating_window.py`：今日列表、透明度、置顶、几何持久化与点击信号。
- `ui/app_settings.py`：应用设置表单；保存成功后发射新配置。
- `ui/main_window.py`：构造一个悬浮窗，编排刷新、详情、托盘和设置。
- `ui/ai_chat_widget.py`：Thinking 展开时使用视觉锚点恢复滚动。

## 主要数据流

### API Key

```text
SettingsDialog → config.save_config(full dict) → temp JSON → os.replace(config.json)
AI client → config.load_config() → api_key
```

### 单实例

```text
QApplication → coordinator.acquire()
  ├─ primary: listen → init data/log/db/UI
  └─ secondary: connect + "activate" → exit 0
primary message → MainWindow.activate_existing_instance()
```

### 日历点击

```text
EventService → calendar_logic blocks/lane → Canvas hit regions
mouse click → event_ids
  ├─ one ID → EventDetailDialog
  └─ multiple IDs → choice dialog → EventDetailDialog
```

### 悬浮窗刷新

```text
manual/AI change → MainWindow._on_data_changed()
  → CalendarWidget markers
  → EventManager.refresh()
  → DailyFloatingWindow.refresh()

date watchdog → actual date changed → DailyFloatingWindow.refresh(new date)
```

## 关键设计决策

- 保留配置原子替换，只逆转 Key 的存储位置；不恢复旧版直接覆盖写法。
- 单实例判定发生在运行时数据目录、日志和数据库初始化之前。
- Thinking 不依赖“距底部”恢复，而锚定被点击 HTML anchor 对应文档位置。
- 重叠 lane 在纯逻辑层计算；Canvas 根据像素宽度决定文字、marker 或 overflow summary。
- 悬浮窗使用标准 `Qt.Tool | Qt.WindowStaysOnTopHint`，保留标题栏以获得稳定拖动、缩放和键盘可访问性。
- 几何保存为 JSON 数组 `[x, y, width, height]`，写入前防抖，恢复时限制到可用屏幕。
- 跨日查询用明确的半开区间 `[start, end)`；reminder 以 start 判断，timespan 以区间相交判断。

## 替代方案

- 未使用进程名扫描或 lock 文件：无法可靠激活托盘实例且易受陈旧锁影响。
- 未使用无边框悬浮窗：自定义拖动缩放与无障碍风险较高。
- 未通过增加整条时间轴高度解决日历：不能解决同时段事项互相遮挡。
- 未复用可编辑 `EventDialog` 显示详情：避免只读查看时产生误编辑。

## 风险与缓解

- 本地 IPC 的并发启动/陈旧端点：连接、监听、复查、受控清理并覆盖随机服务名测试。
- lane 太窄：隐藏文字、保证点击宽度、溢出聚合并提供选择列表。
- 跨日查询边界：纯逻辑与临时 DB 同时覆盖 00:00、终点相等和跨日事件。
- 透明度过低：配置校验钳制为 30%–100%。
- 多屏/DPI 变化：恢复几何时与可用屏幕求交，否则回到主屏默认位置。
- 明文 Key：用户明确接受；仍禁止进入 Git、日志、测试夹具和构建资源。

## 高层测试策略

- 纯函数：配置校验、lane、重叠区间、时间范围和排序。
- 数据层：临时 SQLite 的相交查询及旧 schema 回归。
- Qt offscreen：滚动锚点、Canvas 命中、详情、设置、悬浮窗生命周期和信号。
- IPC：随机服务名的主/次实例、激活消息和异常路径。
- 集成：主窗口数据变化刷新悬浮窗；主题、托盘、设置即时应用。
- 发布：全量 unittest、导入、`build.py --check`、完整 PyInstaller、隔离双实例 exe 冒烟、`dist/data/` 摘要一致。
