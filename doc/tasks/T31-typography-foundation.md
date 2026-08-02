# T31：双字号配置与集中 typography 基础

## 目标

建立应用/悬浮两套 8–20px 配置、命名语义角色与设置控件，为所有 UI 迁移提供唯一字号来源。

## 非目标

- 不在本任务迁移每个组件的全部 QSS/HTML。
- 不修改 Canvas gutter、悬浮交互或 AI 流程。

## 输入文档

- `doc/floating-ai-typography-{proposal,high-level-design,detailed-design}.md`
- `doc/tasks/T30-phase9-planning.md`

## 预期文件

- `constants.py`
- `typography.py`（新增）
- `theme_manager.py`
- `ui/app_settings.py`
- `ui/main_window.py`
- `main.py`
- `tests/test_typography.py`（新增）
- `tests/test_floating_window_ui.py`

## 接口与数据影响

- 新增 `app_font_size_px`、`floating_font_size_px` 完整 JSON 字段。
- 新增 `TypographyScale` 与严格解析/构建接口。
- `AppSettingsDialog.config_saved(dict)` 仍发完整配置，加入两个字号字段。

## 风险

- QApplication 字体与组件 QSS 叠加可能让悬浮字号串用应用字号。
- 运行时更新顺序不当会造成现有窗口只部分生效。

## 实施步骤

- [x] 先写配置正常/边界/非法/旧配置/保存失败测试并在旧实现运行失败。
- [x] 新增默认字段与 typography 角色。
- [x] 设置两个 slider + spinbox 同步控件。
- [x] 保存后由 MainWindow 即时应用；悬浮 scale 独立。
- [x] 将 theme_manager/main 的基础 point 字体迁移为 pixel 字体。

## 测试与检查

```powershell
$env:QT_QPA_PLATFORM='offscreen'
& 'C:\Users\30910\Miniconda3\python.exe' -m unittest tests.test_typography tests.test_floating_window_ui -v
```

覆盖 8/13/20、True/7/21/string/None、完整配置保存、IO 失败、主题切换和两个 scale 互不影响。

## 回滚方式

移除新模块/配置字段/控件，恢复 main/theme 默认字体；旧 JSON 中多余字段可安全忽略。

## 完成定义

- [x] 旧实现失败证据已记录。
- [x] 聚焦测试通过。
- [x] 无真实配置/数据访问。
- [x] 公共契约与 detailed design 一致。

## 实施结果

- 旧实现 10 项 typography 测试出现 2 failure/8 error；实现双配置、命名角色、pixel QFont 与同步控件后聚焦测试通过。
- 独立复核发现全局 `QLineEdit` QSS 覆盖悬浮输入字号；新增双向 8/20px 真实控件回归，旧实现两个子用例失败，局部 objectName QSS 修复后通过。
