# T21 — 集成验证、清理与文档同步

## Objective

执行完整测试/构建/安全检查，清理遗留引用并同步全部工程文档。

## Expected Files

- `doc/tasks/progress.md`、各 T16–T20 状态
- `AGENTS.md`、必要的设计文档

## Dependencies

- T16–T20。

## Implementation Steps

- [x] 运行单元、导入、offscreen UI、构建检查和 exe 冒烟。
- [x] 扫描旧环境引用、秘密模式和生成垃圾。
- [x] 审查最终 diff 与真实数据状态。
- [x] 更新 AGENTS.md 结构、接口、命令、风险和维护记录。

## Tests And Checks

- 正常：全部自动化检查与标准构建通过。
- 边界：无 API 配置、空临时数据库、无系统托盘/offscreen。
- 异常：不触发真实网络/凭据/数据写入。
- 回归：源码功能入口仍为 `main.py`，三栏 UI 和两类事件契约保持。

## Definition Of Done

- [x] 所有检查结果和遗留风险有证据记录。
- [x] `git diff` 无敏感数据或无关改动。
- [x] AGENTS.md 与实际代码一致。

## Verification Result

- 35 项 `unittest`、33 个 Python 文件无 pyc 语法编译、全模块导入通过。
- CI workflow 已校验使用 Miniconda base Python 3.12.4 契约。
- 旧环境路径/活动引用与常见秘密模式扫描无命中；生成 spec 不存在。
- 核心真实数据文件时间戳未被本轮测试改变；exe 冒烟生成的 `dist/data` 已清理。
- `git diff --check` 与 staged diff check 通过；`.gitignore` 原有 staged 用户改动保留，本轮工作区修改在其上做去重和补充。
