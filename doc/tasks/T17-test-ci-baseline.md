# T17 — 自动化测试与 CI 基线

## Objective

使用标准库 `unittest` 建立覆盖核心逻辑、错误路径和 Qt 冒烟的测试套件，并增加 Python 3.12.4 CI。

## Expected Files

- `tests/*.py`
- `.github/workflows/test.yml`

## Dependencies

- 无；必须先于实现修复落地失败回归用例。

## Implementation Steps

- [x] 建立临时配置/数据库隔离工具。
- [x] 为 T18–T20 的修复点先写回归测试。
- [x] 添加全模块导入和 Qt offscreen 冒烟。
- [x] 增加 Windows Python 3.12.4 CI。

## Tests And Checks

- 正常：CRUD、序列化、合法 AI 操作、合法日历块、UI 构造。
- 边界：空集合、午夜、0 时长、重叠边界、最大/最小配置。
- 异常：坏 JSON、数据库异常、畸形 AI JSON、错误 HTTP、非法时间/ID。
- 回归：旧对话 JSON 可加载，配置不写明文 key，`end_time` 可清空。

## Definition Of Done

- [x] `python -m unittest discover -s tests -v` 通过（35 项）。
- [x] CI 文件与本地验证命令一致。
- [x] 测试不访问真实 `data/`、网络或凭据库。
