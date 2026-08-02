# T07 — 提取 AI HTTP 客户端 (ai_client.py)

## 目标

从 `ai_chat.py` 提取 `AICallThread` 和 `fetch_models_list()` 到独立的 `ai_client.py`，将 HTTP 调用与 UI 层解耦。

## 输入文档

- `doc/detailed-design.md` §4.3
- `ai_chat.py` — AICallThread（191-255行）、fetch_models_list（170-188行）

## 预计变更文件

| 操作 | 文件 | 说明 |
|------|------|------|
| ✨ 新建 | `ai_client.py` | AI HTTP 客户端 |
| ✏️ 修改 | `ai_chat.py` | 改为从 ai_client 导入 |
| ✏️ 修改 | `ai_service.py` | 从 ai_client 导入 AICallThread |

## 实现步骤

### Step 1：创建 ai_client.py

从 `ai_chat.py` 提取：
- `fetch_models_list()` 函数（170-188行）
- `AICallThread(QThread)` 类（191-255行）
- 相关的 import 语句

**关键改进**：
- `AICallThread` 不再直接访问 `self._conv`，改为接收 `messages: list[dict]` 参数
- 移除对 `build_context_messages()` 的直接调用（由调用方在启动线程前构建消息）

### Step 2：修改 AICallThread 构造函数

```python
# 旧：接收 conversation 并在 run 内部构建上下文
class AICallThread(QThread):
    def __init__(self, user_msg, conv, parent=None):
        self._msg = user_msg
        self._conv = conv

# 新：接收完整的消息列表
class AICallThread(QThread):
    def __init__(self, messages: list[dict], parent=None):
        self._messages = messages
```

### Step 3：修改 ai_chat.py 导入

```python
from ai_client import AICallThread, fetch_models_list
```

## 测试与检查

```bash
python -c "from ai_client import AICallThread, fetch_models_list; print('OK')"
```

## 完成定义

- [x] `ai_client.py` 独立可运行，不依赖 ai_chat.py
- [x] `AICallThread` 接收 messages 而非 conversation
- [x] `fetch_models_list()` 功能不变

## 依赖

- T02（constants.py 中的 MODEL_CAPABILITIES）