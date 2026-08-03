# T40：Phase 10 集成、构建、exe 冒烟与提交

## 目标

集成 T38–T39，完成全量回归、文档、完整 PyInstaller、隔离 exe 冒烟、真实数据只读完整性核对和安全提交。

## 非目标

- 不访问真实 WebDAV、真实配置内容、真实数据库内容或对话。
- 不修改 `dist/data/`、不创建桌面快捷方式。

## 输入文档

- Phase 10 三层设计
- T38、T39
- `AGENTS.md`

## 影响文件

- 必要的最小集成修复
- `AGENTS.md`、`doc/tasks/progress.md`、Phase 10 文档与 `doc/prompt.md`

## 接口/数据影响

- 汇总 schema v1、controller、设置、自启动与 silent 启动契约。

## 风险

- 完整构建误触 `dist/data/`；隔离 silent 进程难清理；提交包含生成物、秘密或无关文件。

## 实施步骤

- [x] 全量 unittest、导入、Qt offscreen、build check。
- [x] 构建前只读记录 `dist/data/` 文件元数据和组合摘要。
- [x] 完整 PyInstaller，只原子替换 `dist/Clender.exe`。
- [x] 隔离临时目录执行普通、silent 与 secondary exe 冒烟。
- [x] 构建后核对 `dist/data/` 完全一致。
- [x] 更新任务、progress 和 AGENTS 结构/接口/风险/维护记录。
- [x] diff/敏感扫描，设置指定全局 Git 代理，精确暂存并提交。

## 测试与检查

```powershell
$env:QT_QPA_PLATFORM='offscreen'
& 'C:\Users\30910\Miniconda3\python.exe' -m unittest discover -s tests -v
Remove-Item Env:QT_QPA_PLATFORM
& 'C:\Users\30910\Miniconda3\python.exe' -c "import ..."
& 'C:\Users\30910\Miniconda3\python.exe' .\build.py --check
& 'C:\Users\30910\Miniconda3\python.exe' .\build.py
```

## 完成定义

- [x] 所有自动化和构建检查通过。
- [x] 普通与 `--silent` 隔离 exe 冒烟通过。
- [x] `dist/data/` 前后完整性一致。
- [x] staged diff 无秘密、真实数据、生成物和无关文件。
- [x] AGENTS 与任务证据完整并创建 Git 提交。

## 实施结果

- 最终 `QT_QPA_PLATFORM=offscreen` 全量 unittest 为 171/171；新增模块全导入、`build.py --check`、`git diff --check`、裸异常/主线程网络/敏感日志扫描通过。
- Light/Dark 设置页离屏几何最大 460×706，无控件重叠；Windows offscreen 未栅格化字体，文字外观留可见桌面人工复核。
- 完整 PyInstaller 生成 45,582,138-byte `dist/Clender.exe`，SHA-256 `FBCB0BAAF361F43857E0D629FD1FFC1C79A256374764BFC6F3A1FC4B59F610CD`。
- 隔离 exe 完成“普通 primary + 静默 secondary”和“静默 primary + 普通 secondary”；secondary 均在 10 秒内以 0 退出，隔离 DB 创建成功，最终 0 进程且临时目录已删除。
- `dist/data/` 在构建及冒烟前后均为 5 文件、45619 字节、组合摘要 `3CBFF2649022C98D0C4FCAD16B33024071DBFAFC4C6A1089E787DD5DAA2CED3E`。
- 未连接真实 WebDAV，未读取真实配置/数据库/对话内容，未改真实 HKCU Run 值。
