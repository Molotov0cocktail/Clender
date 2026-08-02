# Phase 8 多 Agent 控制提示

## 主 Agent

- 读取 `AGENTS.md`、本轮 proposal/high-level/detailed design、`doc/tasks/progress.md` 与当前任务文件。
- 在任务边界清楚后才分派；每个子 agent 只负责一个任务或无冲突模块。
- 审查每个 diff、验证旧实现失败证据、运行聚焦测试并更新进度。
- 发现产品语义、破坏性操作、真实数据、外部依赖或公共接口不确定时暂停询问用户。
- 最终运行全量测试、导入、Qt offscreen/视觉检查、环境检查、完整构建、双实例 exe 冒烟和 `dist/data/` 核对。
- 更新 `AGENTS.md` 和所有任务记录；设置指定 Git 代理后，仅提交工程文件。

## 子 Agent

- 只读取分配任务所需设计、任务文件和源码，不修改其他任务文件或 `AGENTS.md`。
- 先写任务测试并在旧实现运行，记录预期失败；再实现最小修改并使聚焦测试通过。
- 不访问真实 `data/`、`dist/data/`、Provider、凭据或用户会话。
- 完成前检查 diff，报告修改文件、失败证据、验证命令、结果和遗留风险；由主 agent 更新共享进度。
- 需求不清时立即停止并回报，不自行扩大范围。

## 必读上下文

- `doc/desktop-experience-proposal.md`
- `doc/desktop-experience-high-level-design.md`
- `doc/desktop-experience-detailed-design.md`
- `doc/tasks/progress.md`
- 分配到的 `doc/tasks/T23-*.md` 至 `T29-*.md`

## 最终验证

- 聚焦与全量 unittest 全部通过。
- 全模块导入和 `build.py --check` 通过。
- Qt offscreen 构造、信号、主题、Thinking 锚点、Canvas 命中和悬浮生命周期通过。
- Light/Dark 离屏截图无文字/图样重叠。
- 完整 PyInstaller 构建与隔离 exe 单实例/启动冒烟通过。
- 构建前后 `dist/data/` 文件数、总大小和哈希摘要一致。
- Git staged diff 无 data、数据库、日志、对话、Key、exe、缓存或无关改动。
