# T26 — 周/日视图重叠布局与可点击标识

## 目标

- lane 分栏、重叠/非重叠区分、短提醒标识与详情点击、极端重叠聚合。

## 非目标

- 不改变事件 schema，不重做月视图。

## 影响文件

- `calendar_logic.py`、`ui/canvas.py`、`ui/calendar_widget.py`
- 日历逻辑、Canvas 几何/渲染/点击测试。

## 接口/数据影响

- block 新增 lane/cluster 字段；Canvas 与 CalendarWidget 新增 `event_activated(tuple IDs)` 信号。

## 风险

- lane 算法边界；窄列命中；纹理盖字；聚合事件不可访问；Light/Dark/DPI。

## 实施步骤

- [ ] 先写 2/3/N 重叠、相邻、短提醒、命中失败测试。
- [ ] 实现纯 lane 算法与动态绘制/命中。
- [ ] 连接详情信号并完成离屏截图复核。

## 测试矩阵

- 正常：2/N lane、长标题、周/日、点击。
- 边界：00:00、23:59、极短提醒、最小宽度、overflow group。
- 异常：坏日期、倒序、负时长。
- 回归：相邻不重叠、两主题、不同尺寸、文字与纹理矩形不相交。

## 回滚方式

- 恢复原 block/Canvas 合同；数据库无变化。

## 完成定义

- [x] 纯逻辑和 Qt 命中测试通过。
- [x] 两主题离屏几何截图确认 lane、marker 和纹理区域无已知重叠；可见平台字体复核留待 T29。

## 实施结果

- 旧实现 11 项聚焦测试中 8 个错误，证明缺 lane、点击信号和命中区域。
- block 新增 lane/lane_count/cluster；真实碰撞高度与最小视觉高度分离，短相邻事项不再误判。
- Canvas 实现 lane、窄栏 overflow 聚合、可点击短 marker、自适应文字和仅右缘 overlap 纹理。
- Canvas/CalendarWidget 发射或转发 `event_activated(tuple IDs)`；详情选择由 T27/主线集成。
- 日历逻辑与 Canvas 聚焦测试 16/16 通过；两主题离屏几何检查通过。
