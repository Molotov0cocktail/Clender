# T09 — 提取 Canvas 渲染组件 (ui/canvas.py)

## 目标

从 `calendar_widget.py` 的 `_render_week_view()` 和 `_render_day_view()` 方法中提取内嵌的 `WeekCanvas` 和 `DayCanvas` 类，放入独立的 `ui/canvas.py`。

## 输入文档

- `doc/detailed-design.md` §5.1
- `calendar_widget.py` — WeekCanvas（262-319行）、DayCanvas（379-435行）

## 预计变更文件

| 操作 | 文件 | 说明 |
|------|------|------|
| ✨ 新建 | `ui/__init__.py` | UI 包初始化 |
| ✨ 新建 | `ui/canvas.py` | Canvas 渲染组件 |
| ✏️ 修改 | `calendar_widget.py` | 改为从 ui.canvas 导入 |

## 实现步骤

### Step 1：创建 ui 包

```
mkdir ui
```

创建 `ui/__init__.py`（空文件或包含版本信息）。

### Step 2：创建 ui/canvas.py

将 `calendar_widget.py` 中的两个内嵌类提取：

- `WeekCanvas(QFrame)` → `ui/canvas.py`
- `DayCanvas(QFrame)` → `ui/canvas.py`

**关键变更**：
- 将 `CalendarWidget.COLORS` 改为从 `constants.py` 导入 `EVENT_COLORS`
- 将硬编码的颜色值 `'#e74c3c'` 提取为常量 `REMINDER_LINE_COLOR`
- 将硬编码的 `per_hour=30` 改为从 constants 导入 `PER_HOUR_PX`

### Step 3：修改 calendar_widget.py

```python
# 旧代码
class WeekCanvas(QF):
    ...

# 新代码
from ui.canvas import WeekCanvas, DayCanvas
```

## 测试与检查

```bash
python -c "from ui.canvas import WeekCanvas, DayCanvas; print('OK')"
```

## 完成定义

- [x] `ui/canvas.py` 包含 WeekCanvas 和 DayCanvas
- [x] Canvas 类中的硬编码颜色和常量已提取到 constants.py
- [x] calendar_widget.py 成功从 ui.canvas 导入

## 依赖

- T05（主题系统 — Canvas 需要 theme 参数）