# T22 — API Key 保存、构建数据保护与 Git 提交

## 目标

- 修复设置窗口输入 API Key 后无法写入 Windows Credential Manager 的问题。
- 保存失败时保留设置窗口并向用户显示明确错误，不产生“已保存”的假象。
- 每次构建只替换 `dist/Clender.exe`，明确保护既有 `dist/data/` 用户数据。
- 将“修改后必须重新构建、验证、更新文档并提交 Git”的规则写入工程指南。
- 提交当前现代化改动及本次修复，但不提交 `data/`、`dist/data/`、日志、数据库、对话或其他真实用户数据。

## 非目标

- 不读取、迁移、清空或修改真实 `data/` 与 `dist/data/` 内容。
- 不调用真实 AI Provider，不更改 API 协议。
- 不推送远程仓库，不创建桌面快捷方式。

## 影响文件

- `secret_store.py`、`config.py`、`ui/ai_settings.py`、`build.py`
- `tests/test_secret_store.py`、`tests/test_config_security.py`、`tests/test_ui_smoke.py`、`tests/test_build.py`
- `.gitignore`、`AGENTS.md`、`doc/tasks/progress.md`、本任务文件

## 接口与数据影响

- `secret_store.set_api_key()` 按 pywin32 311 契约用 Unicode 字符串写入；持久凭据因错误 1312 不可用时降级为当前 Windows 登录会话凭据；`get_api_key()` 保持对 UTF-16LE bytes 与字符串返回值兼容。
- `SettingsDialog._save()` 捕获安全存储或文件写入错误；失败不发射 `config_saved`、不关闭窗口。
- `build_exe()` 在忽略的 `build/` 中生成临时发布文件，仅原子替换 `dist/Clender.exe`，不得触碰 `dist/data/`。
- 配置 JSON 仍禁止保存 `api_key`；真实用户数据格式无变化。

## 风险

- Windows Credential Manager 的 blob 编码兼容性。
- PyInstaller 输出目录调整可能导致构建产物定位错误。
- 当前工作树包含用户此前暂存的 `.gitignore` 变更；提交前必须以最终工作树内容为准审查并显式暂存。

## 修改前测试矩阵

| 场景 | 用例 |
|---|---|
| 正常路径 | Key 以 Unicode 字符串写入并可从后端 UTF-16LE bytes 读回；设置保存成功发信号并关闭 |
| 边界值 | 空 Key 删除凭据；Unicode Key 往返；持久凭据错误 1312 降级为会话凭据；`dist/data/` 已存在时构建路径不覆盖它 |
| 异常路径 | Credential Manager 写入失败时 JSON 不落秘密；设置窗口显示错误且不关闭、不发信号 |
| 回归路径 | 配置 JSON 不含 Key；全量 3.12 测试、导入检查、build check、PyInstaller build、exe offscreen 冒烟 |
| 提交安全 | staged diff 不含 `data/`、`dist/data/`、密钥模式、日志、数据库、对话或生成缓存 |

## 实施步骤

1. 先新增真实类型约束与 UI 失败行为回归测试，运行并确认旧实现失败。
2. 修复 CredentialBlob 编解码及设置保存错误反馈。
3. 将 PyInstaller 输出隔离到 `build/`，只发布 exe，并增加数据保护测试。
4. 更新 `AGENTS.md` 与进度记录，运行完整验证并重新构建。
5. 审查工作树、敏感数据与 staged diff，只暂存工程文件并创建 Git 提交。

## 回滚方式

- 使用本次提交的父提交恢复工程文件；`data/` 与 `dist/data/` 不在提交内，不参与回滚。
- 若新构建发布失败，保留既有 `dist/Clender.exe` 与全部 `dist/data/`，修复后重新构建。

## 完成定义

- 回归测试证明错误 1312 可安全降级，新实现遵守 pywin32 字符串写入/UTF-16LE bytes 读取契约。
- 设置保存失败有可见反馈，不关闭、不误报成功。
- 全量测试、导入、环境检查、实际构建及 exe 冒烟通过。
- 构建前后的 `dist/data/` 清单/哈希无变化（若目录存在，仅做只读核对）。
- `AGENTS.md` 明确新增构建、提交和用户数据保留规则并追加维护记录。
- Git 提交完成，提交内容仅含工程文件且无敏感数据。

## 实施结果

- 修改前聚焦测试共 9 项：2 项失败、1 项错误，分别证明 blob 契约假设、设置异常未处理和构建未隔离发布目录。
- 使用随机 `Clender/Test-*` 临时目标验证 pywin32 311：写入参数必须是 Unicode `str`，读取返回 UTF-16LE `bytes`；`CRED_PERSIST_LOCAL_MACHINE`/`ENTERPRISE` 在当前登录环境返回错误 1312，`CRED_PERSIST_SESSION` 可正常往返。所有临时凭据均已删除，未读取或覆盖 `Clender/API`。
- `set_api_key()` 先尝试持久凭据，错误 1312 时降级为当前登录会话凭据；其他错误仍以 `SecretStoreError` 报告。设置窗口保存/获取模型时可见反馈失败，不再误报成功或异常关闭。
- PyInstaller 输出已隔离到 `build/release/`、`build/pyinstaller/` 和 `build/spec/`；成功后只用 `os.replace()` 替换 `dist/Clender.exe`。
- 构建前后 `dist/data/` 都是 5 个文件、244469 字节；兼容 PowerShell 的最终只读摘要为 `1DA6AFDF420E3F3F68C84062399C1DA4F0C5FA5BA36D6D504B628D8F74F87CB7`。

## 验证结果

- 聚焦回归：11/11 通过。
- 全量 `unittest discover -s tests -v`：39/39 通过。
- 全模块导入：通过。
- `build.py --check`：通过。
- PyInstaller 完整构建：通过；产物发布为 `dist/Clender.exe`。
- 将 exe 复制到隔离的 `build/smoke/` 后 offscreen 启动 5 秒：通过；两个 onefile 进程均按精确路径停止，临时数据已清理，真实 `dist/data/` 未用于冒烟。
- Git diff、敏感模式、ignored/staged 文件检查与提交结果在提交前最终复核后记录于 `progress.md`/交付报告。

## 实施结果

- 修改前聚焦测试共 9 项：2 项失败、1 项错误，分别证明 blob 契约假设、设置异常未处理和构建未隔离发布目录。
- 使用随机 `Clender/Test-*` 临时目标验证 pywin32 311：写入参数必须是 Unicode `str`，读取返回 UTF-16LE `bytes`；`CRED_PERSIST_LOCAL_MACHINE`/`ENTERPRISE` 在当前登录环境返回错误 1312，`CRED_PERSIST_SESSION` 可正常往返。所有临时凭据均已删除，未读取或覆盖 `Clender/API`。
- `set_api_key()` 先尝试持久凭据，错误 1312 时降级为当前登录会话凭据；其他错误仍以 `SecretStoreError` 报告。设置窗口保存/获取模型时可见反馈失败，不再误报成功或异常关闭。
- PyInstaller 输出已隔离到 `build/release/`、`build/pyinstaller/` 和 `build/spec/`；成功后只用 `os.replace()` 替换 `dist/Clender.exe`。
- 构建前后 `dist/data/` 都是 5 个文件、244469 字节；兼容 PowerShell 的最终只读摘要为 `1DA6AFDF420E3F3F68C84062399C1DA4F0C5FA5BA36D6D504B628D8F74F87CB7`。

## 验证结果

- 聚焦回归：11/11 通过。
- 全量 `unittest discover -s tests -v`：39/39 通过。
- 全模块导入：通过。
- `build.py --check`：通过。
- PyInstaller 完整构建：通过；产物发布为 `dist/Clender.exe`。
- 将 exe 复制到隔离的 `build/smoke/` 后 offscreen 启动 5 秒：通过；两个 onefile 进程均按精确路径停止，临时数据已清理，真实 `dist/data/` 未用于冒烟。
- Git diff、敏感模式、ignored/staged 文件检查与提交结果在提交前最终复核后记录于 `progress.md`/交付报告。
