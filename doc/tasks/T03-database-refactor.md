# T03 — 重构数据库层

## 目标

重构 `database.py`：
1. 所有函数返回 `Event` 对象而非 `dict`
2. 移除模块末尾 `init_db()` 的自动调用，改为显式调用
3. `update_event()` 使用白名单字段校验
4. 添加类型注解和文档字符串

## 输入文档

- `doc/detailed-design.md` §3.1
- `T01-models.md` — Event 类定义

## 预计变更文件

| 操作 | 文件 | 说明 |
|------|------|------|
| ✏️ 修改 | `database.py` | 重构 CRUD 函数 |

## 实现步骤

### Step 1：移除副作用

删除 `database.py` 末尾的 `init_db()` 调用行（当前第141行）。

### Step 2：引入 Event 模型

在所有返回事件的函数中，将 `dict(row)` 替换为 `Event.from_row(dict(row))`：

```python
# 原代码
return [dict(row) for row in rows]

# 重构后
return [Event.from_row(dict(row)) for row in rows]
```

涉及函数：`get_events_by_date()`, `get_all_events()`, `get_events_date_range()`, `get_event_by_id()`

### Step 3：update_event 白名单校验

```python
_ALLOWED_FIELDS = {'title', 'start_time', 'end_time', 'description', 'estimated_duration'}

def update_event(event_id: int, **fields) -> int:
    # 过滤非法字段名
    safe_fields = {k: v for k, v in fields.items() if k in _ALLOWED_FIELDS}
    if not safe_fields:
        return 0
    ...
```

### Step 4：添加 add_event Event 重载

新增接受 `Event` 对象的重载，保留兼容旧版参数调用的签名。

### Step 5：验证

- [ ] `init_db()` 不在导入时自动执行
- [ ] 所有查询返回 `list[Event]` / `Optional[Event]`
- [ ] `update_event()` 拒绝未知字段
- [ ] 调用方无需修改（`Event` 支持 `ev.title` 属性访问）

## 测试与检查

```bash
python -c "import database; database.init_db(); events = database.get_all_events(); print(type(events[0]))"
```

## 完成定义

- [x] `init_db()` 需显式调用，不在模块导入时执行
- [x] 所有查询返回 `Event` 对象
- [x] `update_event()` 有白名单字段保护
- [x] 类型注解完整

## 依赖

- T01（Event 类）