# T36：悬浮 AI 与主页面当前对话桥接

## 目标

让悬浮输入作为主 AIChatWidget 的第二个输入入口，共享当前 Conversation、历史、预算、QThread、响应执行和刷新。

## 非目标

- 不创建独立会话或第二套网络客户端。
- 不在悬浮窗显示模型正文/Thinking/Token。
- 不新增删除/批量确认。

## 输入文档

- Phase 9 三层设计
- `doc/tasks/T34-floating-shell-state.md`

## 预期文件

- `ui/ai_chat_widget.py`
- `ui/daily_floating_window.py`
- `ui/main_window.py`
- `tests/test_ai_chat_widget.py`
- `tests/test_floating_window_ui.py`
- `tests/test_main_window_floating.py`
- `tests/test_ai_client.py`

## 接口与数据影响

- 新增 `AIChatWidget.submit_external_message(text) -> bool` 与短状态信号。
- Conversation JSON 沿用现有结构；消息写入发起时 Conversation ID。

## 风险

- 主/悬浮并发可能覆盖 `_pending_conv_id` 或 worker。
- 窗口隐藏/切会话/退出时线程结果仍须安全落位。
- 错误摘要不得泄露请求、Key 或私人日程。

## 实施步骤

- [x] 先写当前会话历史、切换绑定、busy、成功/无改动/错误和隐藏正文测试，取得旧失败。
- [x] 提取 `_submit_message(text, source)`，保留主输入行为。
- [x] 增加 external API、状态信号和单 worker guard。
- [x] 接线悬浮输入/MainWindow；按接受结果清空输入并更新短状态。
- [x] 回归无配置、timeout、非 200、畸形 JSON、Thinking fallback 和 data_changed。

## 测试与检查

```powershell
$env:QT_QPA_PLATFORM='offscreen'
& 'C:\Users\30910\Miniconda3\python.exe' -m unittest tests.test_ai_chat_widget tests.test_ai_client tests.test_ai_service tests.test_floating_window_ui tests.test_main_window_floating -v
```

## 回滚方式

移除 external API/signals/input row 接线；主 AI 私有发送路径可恢复，Conversation 文件不需迁移。

## 完成定义

- [x] 悬浮消息和结果写入发起时主 Conversation。
- [x] 悬浮窗没有 AI 正文控件，只有短状态。
- [x] busy/错误/切会话/隐藏/退出测试通过。

## 实施结果

- 外部入口复用主聊天的 Conversation、预算、单 worker 与 QThread；结果始终绑定发起时 Conversation，悬浮窗只显示受限短状态。
- 独立复核补齐主页面发起时也广播 busy/完成/错误状态；旧实现新增用例 2 failure/1 error，修复后 AI 相关 21/21 通过。
