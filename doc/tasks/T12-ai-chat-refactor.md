# T12 — 重构 AI 对话组件 (ui/ai_chat_widget.py + ui/sidebar.py + ui/ai_settings.py)

## 目标

将 `ai_chat.py`（900行）拆分为三个独立 UI 文件：
1. `ui/ai_chat_widget.py` — AIChatWidget 主对话组件
2. `ui/sidebar.py` — ConversationSidebar 对话侧栏
3. `ui/ai_settings.py` — SettingsDialog AI 设置对话框

并替换所有 `database` / `config` 直接调用为 Service 层调用。

## 输入文档

- `doc/detailed-design.md` §5.2, §5.3, §5.7
- `ai_chat.py` — 完整文件
- `T06-conversation-store.md` — 对话持久化
- `T07-ai-client.md` — AI HTTP 客户端
- `T08-ai-service.md` — AI 业务服务

## 预计变更文件

| 操作 | 文件 | 说明 |
|------|------|------|
| ✨ 新建 | `ui/ai_chat_widget.py` | AI 对话主组件 |
| ✨ 新建 | `ui/sidebar.py` | 对话侧栏 |
| ✨ 新建 | `ui/ai_settings.py` | AI 设置对话框 |
| 🗑 废弃 | `ai_chat.py` | 保留作为兼容别名 |

## 实现步骤

### Step 1：提取 ui/sidebar.py

将 `ConversationSidebar` 类（476-553行）提取到 `ui/sidebar.py`。

### Step 2：提取 ui/ai_settings.py

将 `SettingsDialog` 类（310-474行）提取到 `ui/ai_settings.py`。

**关键变更**：
- 原来对 `config.load_config()` 的直接调用保持不变（SettingsDialog 是 UI 层，可以访问 config）
- 原来对 `fetch_models_list()` 的调用改为从 `ai_client` 导入

### Step 3：创建 ui/ai_chat_widget.py

将 `AIChatWidget` 类（556-900行）迁移到 `ui/ai_chat_widget.py`。

**关键变更**：

```python
# 旧导入
import config as cfg_mod
import database

# 新导入
from ai_client import AICallThread
from ai_service import AIService
from conversation_store import load_conversations, save_conversations
from ui.sidebar import ConversationSidebar
from ui.ai_settings import SettingsDialog
from models import Conversation
```

**数据访问替换**：
- `database.get_all_events()` → 不再需要（上下文构建交给 AIService）
- `cfg_mod.load_config()` → 保留（UI 层需要读取配置来显示 Token 条等）
- `cfg_mod.is_api_configured()` → 保留

### Step 4：_send_message 重构

`AIChatWidget._send_message()` 方法的关键变更：

```python
# 旧
self._ai_thread = AICallThread(txt, self._active_conv)

# 新
messages, ctx = AIService.build_context_messages(self._active_conv)
self._ai_thread = AICallThread(messages)
```

### Step 5：保持 ai_chat.py 兼容

```python
from ui.ai_chat_widget import AIChatWidget
from ui.sidebar import ConversationSidebar
from ui.ai_settings import SettingsDialog
```

## 完成定义

- [x] `ui/sidebar.py` 独立可导入
- [x] `ui/ai_settings.py` 独立可导入
- [x] `ui/ai_chat_widget.py` 不直接 import database
- [x] AI 对话功能不变（发送/接收/操作执行）
- [x] 设置对话框功能不变
- [x] 对话管理（新建/切换/删除/重命名）功能不变

## 依赖

- T06（conversation_store）
- T07（ai_client）
- T08（ai_service）