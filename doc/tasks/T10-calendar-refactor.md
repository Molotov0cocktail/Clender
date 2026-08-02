# T10 — 重构日历视图组件 (ui/calendar_widget.py)

## 目标

重构 `calendar_widget.py`：将 `import database` 替换为 `EventService`，将 Canvas 从 `ui/canvas` 导入，迁移到 `ui/` 包。

## 输入文档

- `doc/detailed-design.md` §5.5
- `calendar_widget.py` — 完整文件
- `T04-event-service.md` — EventService
- `T09-canvas-extract.md` — ui/canvas

## 预计变更文件

| 操作 | 文件 | 说明 |
|------|------|------|
| ✨ 新建 | `ui/calendar_widget.py` | 重构后的日历视图 |
| ✏️ 修改 | `main.py` → `ui/main_window.py` | import 路径更新 |
| 🗑 废弃 | `calendar_widget.py` | 保留作为兼容层，或待 T15 删除 |

## 实现步骤

### Step 1：创建 ui/calendar_widget.py

- 将原 `calendar_widget.py` 的内容复制到 `ui/calendar_widget.py`
- 修改 import：
  ```python
  # 旧
  import database
  # 新
  from event_service import EventService
  ```
- 修改 `Canvas` 导入：
  ```python
  from ui.canvas import WeekCanvas, DayCanvas
  ```
- 修改 `COLORS` 引用：
  ```python
  from constants import EVENT_COLORS as COLORS
  ```

### Step 2：数据访问替换

```python
# 旧：_render_week_view() 中
events = database.get_events_date_range(ws, we)

# 新：
events = EventService.get_events_date_range(ws, we)
```
注意：需要确保 `EventService` 中有 `get_events_date_range()` 方法（T04 中需要补充）。

### Step 3：原文件保留为兼容层

在 `calendar_widget.py` 中添加：
```python
# 兼容层 - 请使用 ui.calendar_widget
from ui.calendar_widget import CalendarWidget
```

## 测试与检查

```bash
python -c "from ui.calendar_widget import CalendarWidget; print('OK')"
```

## 完成定义

- [x] `ui/calendar_widget.py` 创建完成，不直接 import database
- [x] Canvas 从 ui/canvas 导入
- [x] 常量从 constants.py 导入
- [x] 原 `calendar_widget.py` 保留作为兼容别名

## 依赖

- T04（EventService）
- T09（ui/canvas）