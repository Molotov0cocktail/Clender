# T11 — 重构事项管理面板 (ui/event_manager.py + ui/event_dialog.py)

## 目标

1. 将 `event_manager.py` 迁移到 `ui/event_manager.py`，通过 `EventService` 访问数据
2. 将 `EventDialog` 提取到独立的 `ui/event_dialog.py`

## 输入文档

- `doc/detailed-design.md` §5.4, §5.6
- `event_manager.py` — 完整文件
- `T04-event-service.md` — EventService

## 预计变更文件

| 操作 | 文件 | 说明 |
|------|------|------|
| ✨ 新建 | `ui/event_manager.py` | 事项管理面板 |
| ✨ 新建 | `ui/event_dialog.py` | 编辑对话框 |
| ✏️ 修改 | `main.py` → `ui/main_window.py` | import 路径更新 |
| 🗑 废弃 | `event_manager.py` | 保留作为兼容别名 |

## 实现步骤

### Step 1：提取 ui/event_dialog.py

将 `event_manager.py` 中的 `EventDialog` 类（300-442行）提取到 `ui/event_dialog.py`：

- 将 `theme_manager` 导入保留（用于获取当前主题颜色）
- `EventDialog.get_data()` 方法保持不变
- 添加类型注解

### Step 2：创建 ui/event_manager.py

将 `EventManager` 类迁移到 `ui/event_manager.py`，关键变更：

```python
# 旧
import database
# ...
database.add_event(...)

# 新
from event_service import EventService
from ui.event_dialog import EventDialog
# ...
EventService.add_event(title=..., ...)
```

### Step 3：数据访问替换点

| 原调用 | 新调用 |
|--------|--------|
| `database.get_events_by_date(d)` | `EventService.get_events_by_date(d)` |
| `database.add_event(...)` | `EventService.add_event(...)` |
| `database.update_event(id, ...)` | `EventService.update_event(id, ...)` |
| `database.delete_event(id)` | `EventService.delete_event(id)` |
| `database.get_event_by_id(id)` | `EventService.get_event_by_id(id)` |

### Step 4：保持原 event_manager.py 兼容

```python
from ui.event_manager import EventManager
from ui.event_dialog import EventDialog
```

## 完成定义

- [x] ui/event_dialog.py 独立可导入
- [x] ui/event_manager.py 不直接 import database
- [x] 添加/编辑/删除事件功能不变

## 依赖

- T04（EventService）