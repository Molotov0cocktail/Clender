# T37：集成、视觉、构建、exe 冒烟与安全提交

## 目标

集成 T31–T36，完成全量验证、视觉回归、PyInstaller 发布、真实数据只读完整性核对、文档同步和安全提交。

## 非目标

- 不访问真实 Provider。
- 不修改、复制或迁移 `data/`、`dist/data/`。
- 不把用户 staged CI 改动纳入本轮提交。

## 输入文档

- Phase 9 三层设计
- `doc/tasks/T31-*.md` 至 `T36-*.md`
- `AGENTS.md`

## 预期文件

- 集成所需最小修复文件
- `AGENTS.md`
- Phase 9 三层设计、任务与 `doc/tasks/progress.md`
- `doc/prompt.md`

## 接口与数据影响

- 最终同步所有新增配置、信号、服务契约、测试和运行命令。
- 不修改数据库 schema。

## 风险

- Windows DWM bottom/top、frameless resize、多屏/DPI、0% 透明度需真实 exe 验证。
- 完整构建必须只替换 exe，不能改变 `dist/data/`。
- 预存 staged CI 文件可能被普通 `git commit` 误带入。

## 实施步骤

- [x] 审查每个 subagent diff 并修复跨任务集成。
- [x] 运行聚焦/全量/导入/offscreen/build check。
- [x] 生成 Light/Dark × 8/20px 的周、日、悬浮截图并人工检查。
- [x] 紧邻构建前记录 `dist/data/` 路径、大小、mtime、SHA256，只输出摘要。
- [x] 完整 PyInstaller 构建并隔离 exe 启动/双实例冒烟。
- [x] 构建后复核 `dist/data/` 快照逐项相等。
- [x] 更新 AGENTS 架构、接口、风险和维护记录。
- [x] 检查 diff/敏感模式；配置全局 Git 代理；精确提交本轮文件。

## 测试与检查

```powershell
$env:QT_QPA_PLATFORM='offscreen'
& 'C:\Users\30910\Miniconda3\python.exe' -m unittest discover -s tests -v
Remove-Item Env:QT_QPA_PLATFORM
& 'C:\Users\30910\Miniconda3\python.exe' -c "import ..."
& 'C:\Users\30910\Miniconda3\python.exe' .\build.py --check
& 'C:\Users\30910\Miniconda3\python.exe' .\build.py
```

## 回滚方式

用 Git revert 本轮单一提交；`data/` 与 `dist/data/` 不参与回滚。若构建失败，staging 不发布，既有 exe 保持。

## 完成定义

- [x] 所有自动化、视觉、构建和 exe 冒烟通过。
- [x] `dist/data/` 前后完整性一致。
- [x] AGENTS 与任务记录完整。
- [x] staged diff 无敏感/生成/无关文件。
- [x] 用户 CI 暂存改动保持原样且未进入本轮提交。
- [x] 创建 Git 提交并记录哈希。

## 实施结果

- 最终全量 `unittest discover -s tests -v` 为 133/133；全模块导入、`build.py --check`、`git diff --check` 和静态字号门禁通过。
- 生成并人工复核 Light/Dark × 8/20px 的周、日、悬浮 12 张源截图与 4 张联系表；使用 mock 配置/事项，未读取真实 `data/`。
- 最终 PyInstaller 生成 45,555,401-byte `dist/Clender.exe`，SHA-256 `4093EFFB380165A836815A1C47C024CD81E7DDBCFB5E2976909EE9C9DBBD5854`。
- 隔离首实例存活并创建临时 DB，次实例 10 秒内以 0 退出；CIM 清理因权限拒绝后改用已核验的精确 `Get-Process.Path` 停止 2 个 onefile 进程，最终 0 进程/0 临时目录残留。
- `dist/data` 构建/冒烟前后均为 5 文件、262248 字节、组合摘要 `9213D0AAC7AB2CFDA41AE4EB527E2F96ED677493155C06B2B724579D7DD9ADD6`。
- 用户预存 staged `.github/workflows/test.yml` 保持原样并由路径限定提交排除；提交哈希见最终交付报告。
