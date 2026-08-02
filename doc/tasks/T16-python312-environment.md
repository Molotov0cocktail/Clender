# T16 — 统一 Miniconda Python 3.12.4 与标准构建

## Objective

移除 Win7/Python 3.8 资产，锁定 Miniconda base Python 3.12.4 依赖，并确保构建不携带运行数据。

## Input Docs

- `doc/modernization-proposal.md`
- `doc/modernization-design.md`
- `AGENTS.md`

## Expected Files

- 删除：`.conda/`、`.wheels/`、`build_win7/`、`dist/win7/`、`build_win7.py`、`Clender_win7.spec`、生成的 `Clender.spec`
- 修改：`.gitignore`、`requirements.txt`、`build.py`、CI、相关文档

## Dependencies

- T17 测试基线先建立；清理操作在最终验证前执行。

## Implementation Steps

- [x] 核对所有待删路径的绝对路径和大小。
- [x] 锁定 Python 3.12 已验证依赖，重写标准构建入口。
- [x] 删除所有 Win7/Python 3.8 资产与活动引用。
- [x] 验证构建命令不包含 `data/`，并更新文档。

## Tests And Checks

- 正常：Miniconda 3.12.4 全模块导入、`build.py --check`。
- 边界：不存在可选 icon/data 时检查仍通过。
- 异常：解释器版本不符、PyInstaller 缺失时 `--check` 非零退出。
- 回归：构建命令仍包含入口、UI 模块和 requirements，但不包含真实 `data/`。

## Definition Of Done

- [x] 无 Win7/Python 3.8 活动文件、目录或构建引用；任务/维护历史保留删除记录。
- [x] 依赖精确锁定，构建检查/冒烟通过。
- [x] 删除范围和可恢复性已反馈。

## Verification Result

- Miniconda base Python 3.12.4 `build.py --check` 通过。
- PyInstaller 6.21.0 生成 `dist/Clender.exe`（43,880,676 bytes），offscreen 启动 5 秒保持运行。
- 构建环境显式隔离用户 site-packages 并补齐 conda DLL PATH；构建命令无 `--add-data`。
