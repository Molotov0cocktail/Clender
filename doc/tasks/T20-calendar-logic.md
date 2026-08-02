# T20 — 日历逻辑提取与 UI 回归

## Objective

将周/日事件 block 构建与重叠检测提取为纯函数，减少重复业务代码并保持视觉行为。

## Expected Files

- `calendar_logic.py`
- `ui/calendar_widget.py`
- `tests/test_calendar_logic.py`、UI 冒烟测试

## Dependencies

- T17、T18。

## Implementation Steps

- [x] 提取严格时间解析、block 构建、重叠合并。
- [x] 周/日视图复用纯函数。
- [x] 保持 Canvas block 契约不变。
- [x] 验证 Light/Dark 和空/重叠视图。

## Tests And Checks

- 正常：reminder、带时长 reminder、timespan、周列定位。
- 边界：00:00、23:59、零/负高度、刚好相邻、三重重叠。
- 异常：坏日期、缺结束、结束早于开始，记录并跳过。
- 回归：Canvas 接收字段完整，offscreen 月/周/日切换无异常。

## Definition Of Done

- [x] 周/日重复逻辑删除。
- [x] 纯逻辑和 UI 冒烟通过。
- [x] 现有视觉契约未改变。
