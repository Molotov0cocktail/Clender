# T19 — AI 与对话链路加固

## Objective

统一 Conversation 模型、删除旧线程构造路径、实现安全消息预算和严格 AI 操作验证。

## Expected Files

- `models.py`、`conversation_store.py`
- `ai_service.py`、`ai_client.py`
- `ui/ai_chat_widget.py`、相关测试

## Dependencies

- T17、T18。

## Implementation Steps

- [x] UI 全面切换到 `models.Conversation/Message`。
- [x] `AICallThread` 只接收预构建 messages。
- [x] 增加带安全余量的消息预算与 usage 回写。
- [x] 校验 AI 操作并测试 Thinking 字段不兼容重试。

## Tests And Checks

- 正常：旧 JSON 往返、上下文裁剪、四种 action、成功 HTTP。
- 边界：空消息、极小窗口、无操作、reply-only、usage 缺失。
- 异常：未知 action、缺 ID、非法时间、非 200、超时、连接错误、坏响应结构。
- 回归：对话标题/Token 展示、data_changed 仅在真实数据变化时发出。

## Definition Of Done

- [x] 仅保留一套 Conversation 模型。
- [x] AI 非可信输入不会绕过业务校验。
- [x] 网络错误均由 Qt 信号返回而非崩溃。
