# T33：周/日时间轴动态 gutter 与间距

## 目标

按实际字体度量计算时间轴空间，使时间标签与网格、marker、事项块保持至少 8px 间距。

## 非目标

- 不修改 lane/cluster 纯逻辑。
- 不改变每小时 30px 或主窗口三栏比例。

## 输入文档

- Phase 9 三层设计
- `doc/tasks/T31-typography-foundation.md`

## 预期文件

- `ui/canvas.py`
- `ui/calendar_widget.py`
- `tests/test_canvas.py`
- `tests/test_ui_smoke.py`

## 接口与数据影响

- Canvas 新增可测试的 timeline geometry/helper；block 数据契约不变。
- 周 header spacer 与 Canvas gutter 共享计算。

## 风险

- 动态 gutter 压缩周列宽，可能更早触发 overflow。
- 命中区域必须与重算后的绘制矩形一致。

## 实施步骤

- [x] 先写日/周、8/20px、窄/宽、端点与 8px 间距测试并取得旧失败。
- [x] 实现 metrics gutter 和右对齐标签 QRect。
- [x] 重构周/日 column geometry 和 header spacer。
- [x] 动态化文本行高阈值并回归 lane/marker/overflow 点击。

## 测试与检查

```powershell
$env:QT_QPA_PLATFORM='offscreen'
& 'C:\Users\30910\Miniconda3\python.exe' -m unittest tests.test_canvas tests.test_calendar_logic tests.test_ui_smoke -v
```

## 回滚方式

恢复旧固定坐标；不涉及数据或配置迁移。

## 完成定义

- [x] 所有测试几何满足 `label.right + 8 <= grid/event.left`。
- [x] 既有 Canvas 命中与聚合回归通过。
- [x] 8/20px Light/Dark 截图无重叠。

## 实施结果

- 旧实现动态 gutter 产生 10 个失败子用例；改为 `horizontalAdvance("00:00")`、右对齐 label QRect 与共享 header spacer 后全部通过。
- 周/日 lane、marker、overflow 命中回归通过；四组 Light/Dark × 8/20px 联系表人工复核无时间轴重叠。
