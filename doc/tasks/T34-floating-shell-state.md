# T34：无边框悬浮壳、拖缩、置底/置顶与时态高亮

## 目标

重做悬浮外壳，提供无标题简洁列表、默认置底、运行时 pin、全窗拖动、自定义缩放、0–100% 透明度和 current/next 高亮。

## 非目标

- 不实现事件编辑或 AI 提交编排。
- 不持久化 pin。

## 输入文档

- Phase 9 三层设计
- `doc/tasks/T31-typography-foundation.md`

## 预期文件

- `floating_window_logic.py`
- `ui/daily_floating_window.py`
- `ui/app_settings.py`
- `tests/test_floating_window_logic.py`
- `tests/test_floating_window_ui.py`

## 接口与数据影响

- `FloatingWindowSettings` opacity 合法范围为 0–100，并使用 floating scale。
- 新增 `FloatingEventState`/`classify_event_states()`。
- pin 是 UI 成员状态，不写 JSON。

## 风险

- Frameless 会失去原生缩放，需要 edge/corner hit-test。
- QListWidget viewport、双击和拖动存在事件竞争。
- flags 切换可能丢 geometry/visibility/opacity。

## 实施步骤

- [x] 先写配置、时态、flags、拖动排除、缩放、0/100 和分钟刷新测试，记录旧失败。
- [x] 删除原生/内部标题，建立 bottom/top 互斥 flags。
- [x] 实现拖动阈值与边缘/角缩放。
- [x] 实现纯时态分类、Qt role 与 Light/Dark 样式。
- [x] 保留 geometry 防抖、托盘、日期刷新和 shutdown。

## 测试与检查

```powershell
$env:QT_QPA_PLATFORM='offscreen'
& 'C:\Users\30910\Miniconda3\python.exe' -m unittest tests.test_floating_window_logic tests.test_floating_window_ui -v
```

## 回滚方式

恢复旧 `Qt.Tool | WindowStaysOnTopHint`、标题/日期行和 30% 下限；geometry 格式不变。

## 完成定义

- [x] 默认 bottom、pin top 且不持久化。
- [x] 拖动/缩放/排除区、current/next 和 0% 测试通过。
- [x] 没有关闭按钮或内部日期标题。

## 实施结果

- 悬浮 logic/interaction 旧实现为 8 failure/6 error；实现 frameless bottom/top、拖缩、0%–100%、时态分类和分钟重算后聚焦测试通过。
- 独立审查确认 flags、geometry/opacity、双击/拖动互斥与 pin 不持久化；整分钟边界最多约 60 秒延迟作为低风险保留。
