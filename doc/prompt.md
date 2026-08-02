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

---

# Phase 9 多 Agent 控制提示

## 主 Agent

- 必读 `AGENTS.md`、Phase 9 三层设计、`doc/tasks/progress.md` 和当前 T31–T37 任务文件。
- 严格按“测试先失败 → 聚焦实现 → 聚焦通过 → diff 审查 → 更新进度”推进。
- 只把文件边界清晰且不会与其他 agent 同时修改同一生产模块的任务并行分派。
- 子 agent 不更新共享 `progress.md` 或 `AGENTS.md`；主 agent 复核证据后统一维护。
- 保护用户已暂存 `.github/workflows/test.yml`；最终使用精确路径提交，禁止普通全量 commit 误带。
- 发现 bottom/top 平台行为、Conversation 绑定、event_type 转换、字号角色或真实数据边界的新歧义时立即暂停。
- 主 agent 负责所有 diff 集成、静态字号审计、完整发布验证和最终提交。

## 子 Agent

- 每次只接受一个 T31–T36 任务或其中明确的“仅测试”阶段。
- 读取 Phase 9 三层设计、分配任务文件、相关源码/测试；不得修改其他任务、`progress.md`、`AGENTS.md`、`.github/workflows/test.yml`。
- 第一轮只能添加/更新聚焦测试；在旧实现运行并返回失败/错误名称与原因，不得顺手实现。
- 收到实现 follow-up 后仅修改任务允许文件，运行聚焦检查并返回文件、命令、结果和风险。
- 不读取真实 `data/`、`dist/data/`、Provider、Key 或 Conversation；所有网络/config/DB 使用 mock/临时目录。
- 需求不清、文件冲突或测试无法安全隔离时停止并回报。

## 必读上下文

- `doc/floating-ai-typography-proposal.md`
- `doc/floating-ai-typography-high-level-design.md`
- `doc/floating-ai-typography-detailed-design.md`
- `doc/tasks/progress.md`
- 分配到的 `doc/tasks/T31-*.md` 至 `T36-*.md`

## 实施顺序

1. T31 双字号基础测试失败证据与实现。
2. T32 字号迁移、T33 Canvas、T34 悬浮壳可在共享基础稳定后按无冲突文件分派。
3. T35 编辑、T36 AI 桥接在 T34 接口确定后实施。
4. T37 仅由主 agent 集成和发布。

## 最终验证

- 新增测试在旧实现有明确失败证据，修复后聚焦与全量 unittest 通过。
- 静态审计禁止遗漏裸字号；Light/Dark × 应用/悬浮 8/20px 覆盖主要组件。
- 周/日标签与 grid/event 几何至少相隔 8px，lane/marker/overflow 命中回归通过。
- frameless bottom/top、拖动排除、缩放、双击编辑、current/next、0/100% 和悬浮 AI 当前会话绑定通过。
- 全模块导入、`build.py --check`、完整 PyInstaller、隔离 exe 启动/双实例通过。
- `dist/data/` 构建前后逐项一致；提交无用户 CI、data、Key、日志、数据库、对话、exe 或缓存。
