# Python 3.12 现代化与工程加固提案

## 目标

- 删除 Win7 与项目内 Python 3.8 环境/构建资产，唯一支持环境改为 Miniconda base Python 3.12.4。
- 修复 `AGENTS.md` 已记录的工程问题：无自动化测试/CI、对话双模型、旧线程接口、Token 预算不可靠、AI 操作校验不足、数据库连接/索引/空值更新、构建携带运行数据、依赖未锁定、UI 日历逻辑重复。
- 保持现有用户功能和 SQLite schema 数据兼容，不删除或迁移真实 `data/`。

## 非目标

- 不改变三栏 UI 的产品布局和交互语义。
- 不更换 PyQt5、SQLite 或 OpenAI 兼容 API 技术栈。
- 不访问真实 AI 服务，不修改/打印现有 API Key、对话或日程内容。

## 运行环境

- 唯一开发/测试/构建解释器：`C:\Users\30910\Miniconda3\python.exe`（Miniconda base，Python 3.12.4）。
- 运行依赖锁定为该环境已验证版本；测试优先使用标准库 `unittest`，不为测试框架新增依赖。

## 成功标准

1. 项目内 `.conda/`、`.wheels/`、Win7 脚本/spec/缓存/输出全部移除，无残留引用。
2. 自动化测试覆盖模型、配置、数据库、AI 解析/执行、HTTP 客户端、日历布局与 Qt 冒烟；CI 使用 Python 3.12.4。
3. 对话只使用 `models.Conversation/Message`；`AICallThread` 只接收预构建 messages。
4. API Key 不再以明文写入 `config.json`；旧明文配置可迁移到 Windows Credential Manager。
5. 数据库连接在成功和异常路径均关闭，日期索引存在，可显式清空 `end_time`。
6. AI 操作在写库前验证 action、ID、标题、事件类型、时间格式/顺序和字段范围。
7. PyInstaller 不打包真实 `data/`，依赖版本锁定，标准构建在 Miniconda Python 3.12.4 下通过。
8. 全部自动化测试、全模块导入、offscreen Qt 冒烟、敏感信息扫描和构建冒烟通过。

## 风险与回滚

- Credential Manager 仅适用于 Windows：非 Windows/不可用时允许从 `CLENDER_API_KEY` 环境变量读取，但拒绝明文落盘。
- 对话模型统一可能影响旧 JSON：保留 `Conversation.from_dict()` 兼容并做往返测试。
- 删除旧环境和产物不可从工作区直接恢复，但对应已跟踪文件可由 Git 恢复；删除前必须验证绝对路径均位于 `D:\Clender`。
- 数据库只新增幂等索引，不改变表字段；测试使用临时 DB，真实 `data/` 不参与。
