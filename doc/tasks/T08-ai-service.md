# T08 — 创建 AI 业务服务 (ai_service.py)

## 目标

从 `ai_chat.py` 提取 AI 业务逻辑函数到独立的 `ai_service.py`，包括上下文构建、Token 估算、响应解析、操作执行。

## 输入文档

- `doc/detailed-design.md` §4.4
- `ai_chat.py` — `build_context_messages()`, `estimate_tokens()`, `count_messages_tokens()`, `guess_model_capabilities()`, `parse_ai_response()`, `execute_operations()`, `get_current_date_context()`, `get_effective_system_prompt()`

## 预计变更文件

| 操作 | 文件 | 说明 |
|------|------|------|
| ✨ 新建 | `ai_service.py` | AI 业务服务 |
| ✏️ 修改 | `ai_chat.py` | 改为从 ai_service 导入 |

## 实现步骤

### Step 1：创建 ai_service.py

提取以下函数并封装为 `AIService` 静态方法类：

- `build_context_messages()` → 依赖 `config.load_config()` 和 `database.get_all_events()`
- `get_current_date_context()` 
- `get_effective_system_prompt()` → 使用 constants.SYSTEM_PROMPT
- `estimate_tokens()`, `count_messages_tokens()`
- `guess_model_capabilities()` → 使用 constants.MODEL_CAPABILITIES
- `parse_ai_response()`
- `execute_operations()` → **关键变更**：将直接调用 `database.*` 改为通过 `EventService` 调用

### Step 2：execute_operations 解耦

```python
# 旧代码（ai_chat.py ~277行）
import database
database.add_event(...)

# 新代码（ai_service.py）
from event_service import EventService
EventService.add_event(...)
```

### Step 3：验证

- [ ] ai_service.py 不导入任何 PyQt5 模块
- [ ] execute_operations 通过 EventService 而非直接调 database
- [ ] 所有从 ai_chat.py 提取的函数逻辑完全一致

## 完成定义

- [x] AIService 类完成，所有静态方法可用
- [x] execute_operations 通过 EventService 调用
- [x] ai_service.py 无 PyQt5 依赖

## 依赖

- T04（EventService）
- T06（ConversationStore）
- T07（ai_client）