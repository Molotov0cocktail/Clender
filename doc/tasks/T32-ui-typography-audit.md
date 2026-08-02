# T32：全 UI 字号迁移与遗漏静态门禁

## 目标

将所有应用拥有的文字迁移到命名字号角色，并建立防止裸字号、point/pixel 混用和重复定义的静态测试。

## 非目标

- 不改变主题色、产品文案或 AI 行为。
- 不重做窗口布局；只修复 20px 下与文字直接相关的裁剪。

## 输入文档

- Phase 9 三层设计
- `doc/tasks/T31-typography-foundation.md`

## 预期文件

- `theme_manager.py`
- `ui/calendar_widget.py`
- `ui/event_manager.py`
- `ui/ai_chat_widget.py`
- `ui/sidebar.py`
- `ui/ai_settings.py`
- `ui/app_settings.py`
- `ui/event_dialog.py`
- `ui/event_detail_dialog.py`
- `ui/daily_floating_window.py`
- `ui/main_window.py`
- `tests/test_typography.py`
- `tests/test_ui_smoke.py`
- `tests/test_ai_chat_widget.py`

## 接口与数据影响

- 不新增持久化字段。
- 各组件 `apply_theme()` 同时刷新命名字号；Thinking HTML 使用 scale。

## 风险

- 53 个现有字体设置命中分散且部分初始化/apply_theme 重复。
- 20px 下固定按钮、token bar、月格和对话框可能裁剪。

## 实施步骤

- [x] 先写静态扫描和 8/20px 组件冒烟测试，记录旧失败。
- [x] 按模块替换裸字号，合并重复 QSS。
- [x] 给关键控件稳定属性/objectName，取消按文本识别样式。
- [x] 按 metrics/sizeHint 调整文字相关固定尺寸。
- [x] 验证主题/字号重绘不破坏 Thinking anchor 和事件列表。

## 测试与检查

```powershell
$env:QT_QPA_PLATFORM='offscreen'
& 'C:\Users\30910\Miniconda3\python.exe' -m unittest tests.test_typography tests.test_ui_smoke tests.test_ai_chat_widget -v
rg -n "font-size\s*:\s*[0-9]|QFont\([^\n]*,[ ]*[0-9]|set(Point|Pixel)Size\([0-9]" --glob '*.py'
```

## 回滚方式

逐模块恢复原 QSS；保留 T31 配置不会破坏旧组件。

## 完成定义

- [x] 生产代码无未批准裸字号命中。
- [x] 应用 scale 覆盖所有非悬浮 UI，悬浮 scale 独立。
- [x] Light/Dark、8/20px 聚焦测试通过。

## 实施结果

- 迁移主窗口、日历、事项、AI、侧栏和设置中的裸字号，文字相关固定尺寸改用 `QFontMetrics`。
- 静态门禁确认生产代码无数值型 `QFont`/setter 或裸 `font-size`；Light/Dark × 8/20px 回归通过。
