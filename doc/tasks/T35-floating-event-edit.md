# T35：悬浮事项双击编辑与类型转换

## 目标

双击悬浮事项直接编辑，并让 reminder/timespan 类型转换通过服务层真实生效。

## 非目标

- 不新增删除入口。
- 不允许 UI 绕过 EventService。

## 输入文档

- Phase 9 三层设计
- `doc/tasks/T34-floating-shell-state.md`

## 预期文件

- `event_service.py`
- `database.py`
- `ui/event_manager.py`
- `ui/daily_floating_window.py`
- `ui/main_window.py`
- `tests/test_database_service.py`
- `tests/test_floating_window_ui.py`
- `tests/test_main_window_floating.py`

## 接口与数据影响

- `EventService.update_event()` 白名单新增 `event_type`，无 schema 变化。
- `database.update_event()` 的既有字段白名单同步允许 `event_type`；仍使用参数化 SQL，无 schema 迁移。
- `DailyFloatingWindow.event_edit_requested(int)` 取代旧单击详情语义。

## 风险

- timespan→reminder 必须显式清空 end；反向转换必须拒绝缺失/倒序 end。
- 两个编辑入口必须共享完全相同的保存字段。

## 实施步骤

- [x] 先写双向转换、非法转换、取消、成功刷新、单击无动作测试并取得旧失败。
- [x] 扩展 EventService 合并验证和规范化写入。
- [x] 同步 database 动态更新字段白名单，避免服务验证后的 event_type 被静默过滤。
- [x] 更新 EventManager 传递 event_type。
- [x] 接线 itemDoubleClicked → MainWindow → EventDialog → EventService。
- [x] 保证只在成功更新时统一刷新一次。

## 测试与检查

```powershell
$env:QT_QPA_PLATFORM='offscreen'
& 'C:\Users\30910\Miniconda3\python.exe' -m unittest tests.test_database_service tests.test_floating_window_ui tests.test_main_window_floating -v
```

## 回滚方式

从白名单和 UI 保存参数移除 event_type，恢复只读详情信号；数据库数据不需迁移。

## 完成定义

- [x] 双向类型转换和异常零写入测试通过。
- [x] 单击不打开、双击编辑、取消不刷新。
- [x] EventManager 回归通过。

## 实施结果

- `database`/`EventService` 更新白名单支持 `event_type`，timespan→reminder 显式清空 end，非法反向转换在写库前拒绝。
- 悬浮单击保持无动作，双击打开可编辑 `EventDialog`；取消/失败零刷新，成功经服务层统一刷新一次。
