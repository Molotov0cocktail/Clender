# T06 — 提取对话持久化模块

## 目标

从 `ai_chat.py`（100-168行）提取 `Conversation` 类、`load_conversations()` 和 `save_conversations()` 到独立的 `conversation_store.py`。

## 输入文档

- `doc/detailed-design.md` §4.2
- `ai_chat.py` — 当前 Conversation 类和持久化逻辑

## 预计变更文件

| 操作 | 文件 | 说明 |
|------|------|------|
| ✨ 新建 | `conversation_store.py` | 对话持久化 |
| ✏️ 修改 | `ai_chat.py` | 改为从 conversation_store 导入 |

## 实现步骤

### Step 1：创建 conversation_store.py

将 `ai_chat.py` 中的以下内容迁移：
- `Conversation` 类 → 使用 models.Conversation
- `_conv_file()` 函数
- `load_conversations()` 函数
- `save_conversations()` 函数

### Step 2：修改 ai_chat.py 的导入

```python
# 旧导入
from ai_chat import Conversation, load_conversations, save_conversations

# 新导入
from conversation_store import load_conversations, save_conversations
from models import Conversation
```

### Step 3：保持向后兼容

在 `ai_chat.py` 中保留 `from conversation_store import *` 风格的别名，避免调用方大规模的 import 修改（T12 再做最终清理）。

## 测试与检查

```bash
python -c "from conversation_store import load_conversations, save_conversations; convs = load_conversations(); print(len(convs))"
```

## 完成定义

- [x] `conversation_store.py` 独立可运行
- [x] `ai_chat.py` 中的 Conversation 相关代码已迁移
- [x] 对话加载/保存功能正常

## 依赖

- T01（models.Conversation）