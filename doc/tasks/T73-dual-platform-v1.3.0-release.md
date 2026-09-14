# T73 双端 v1.3.0 正式发布（2026-09-14）

## 目标

- 将 `main/c397a4d` 已完成的 T70–T72 双端修复发布为新的正式版本 `v1.3.0`。
- Android 包内版本同步提升为 `versionName=1.3.0`、`versionCode=2`，保持原 applicationId、签名和覆盖升级能力。
- GitHub 与 Gitee 均创建正式 Release，上传 Windows EXE、Android APK、Android AAB，并更新公开 README 与内部记录。

## 非目标

- 不修改 T70–T72 的生产行为、固定 AI 契约、Room/SQLite schema、WebDAV v1、权限、Manifest 或依赖。
- 不读取、迁移或上传 `data/`、`dist/data/`、真实配置、对话、密钥、日志、mapping、缓存或 debug 包。
- 不覆盖 v1.1.0/v1.2.0，不强推任何远端分支；不合并 Gitee `main` 的独立历史。
- 不再次调用真实 Provider；T72 已完成且已结束的临时凭据验收只作为功能证据，凭据不得落盘或进入发布材料。

## 影响文件与外部状态

- `android/app/build.gradle.kts`
- `android/tests/release/test_release_tools.py`
- `android/tests/policy/test_ignore_and_boundaries.py`
- `android/tests/policy/test_t73_task_boundary.py`
- `README.md`
- `AGENTS.md`
- `android/AGENTS.md`
- `doc/tasks/T73-dual-platform-v1.3.0-release.md`
- `doc/tasks/progress.md`
- GitHub/Gitee 的 `v1.3.0` 注释标签、正式 Release 与三个公开附件

## 接口与数据影响

- Android 仅修改公开包版本元数据：`1.0.0 (1)` → `1.3.0 (2)`；applicationId、签名证书、minSdk/targetSdk、数据库和同步格式不变。
- Windows 无独立包内版本字段，发布版本由 Git 标签与附件名表达；按仓库门禁重新完整构建。
- 正式附件命名为 `Clender-Windows-v1.3.0.exe`、`Clender-Android-v1.3.0.apk`、`Clender-Android-v1.3.0.aab`。

## 风险

- 版本元数据改动会改变 Android 包摘要，必须重新执行完整签名构建和升级兼容审计，不能沿用 T72 的产物摘要。
- 双站认证或上传中断可能留下标签、Release 或附件的部分状态；每步必须先回读再安全重试。
- Gitee `main` 与本地历史分叉；只推送新标签并创建 Release，保留其独立 `LICENSE` 提交。
- Windows 正式实例或隔离冒烟残留进程会触发保护门禁；用户已明确退出正式应用，仍须操作前复核。

## 测试矩阵（修改前确定）

| 类别 | 用例 | 通过条件 |
|---|---|---|
| 正常 | Android release 包版本元数据 | APK/AAB 显示 `1.3.0 (2)`，applicationId 和签名证书保持一致 |
| 正常 | Android 完整离线门禁和签名构建 | 全部测试、静态策略、APK/AAB 审计通过，零失败/错误/跳过及既定泄漏标记 |
| 正常 | Windows 全量测试、完整 PyInstaller 和两种隔离启动顺序 | 全部通过，结束后无 Clender 测试进程 |
| 正常 | GitHub/Gitee `v1.3.0` 正式 Release | 非 draft/prerelease，标签解引用到同一发布提交，三个附件可下载 |
| 边界 | 从旧 Android 正式包覆盖安装新 APK | 先在隔离模拟器用旧签名包建立合成事项/设置，再 `adb install -r` 新包；包名/签名一致、versionCode 单调递增且合成数据保留，不卸载、不清库 |
| 边界 | Gitee `main` 有独立提交 | 不强推、不覆盖；新标签可独立承载本次源码 |
| 异常 | 目标标签/Release/附件已存在或单步失败 | 停止重复创建，回读现状，只补齐缺项且不覆盖不同摘要附件 |
| 异常 | 正式 Windows 进程仍在运行 | 在构建/冒烟前停止并报告，不绕过数据隔离保护 |
| 回归 | `dist/data` 构建前后完整性 | 路径、大小、mtime、SHA-256 清单完全一致 |
| 回归 | diff、暂存区和敏感模式审查 | 仅计划文件；无密钥、用户数据、生成物或无关改动入库 |

## 实施步骤

1. 读取仓库契约与发布先例，建立本任务和进度条目。
2. 只读核对 GitHub/Gitee 的分支、标签、Release、认证与现有产物；独立复核版本和发布边界。
3. 先更新版本与精确任务边界断言并取得 RED，再修改 Android 版本元数据/边界白名单；运行聚焦 GREEN。
4. 更新 README、根/Android 协作说明、发布说明和进度。
5. 执行 Android 完整门禁、签名 APK/AAB 构建与审计；执行 Windows 全量测试、完整 PyInstaller、普通/静默两场景隔离冒烟，并核对 `dist/data`。
6. 检查最终 diff、暂存区和敏感模式；按要求设置 Git 代理并创建发布提交。
7. 创建 `v1.3.0` 注释标签；安全推送 GitHub `main` 和双站标签，创建双站正式 Release 并上传三项附件。
8. 回读双站标签、Release 元数据和附件摘要；记录完成结果并确保工作区干净。

## 回滚方式

- 发布前的工程改动使用新提交 `git revert` 回滚，不重写公共历史；Android 包内版本代码不降级发布。
- 发布上传失败时保留正确标签与已验证附件，只补齐缺项；若公开附件错误，先停止宣称完成并按实际状态修正。
- 删除公开 Release/标签属于破坏性操作，不在本次预授权回滚范围内。
- 不以清空数据库、删除用户数据或降级 Room v3 作为回滚方式。

## 完成定义

- Android 包内版本为 `1.3.0 (2)`，双端完整构建、测试、签名和隔离验证通过。
- Windows `dist/data` 完整性零变化；公开附件不含秘密、用户数据或调试材料。
- GitHub/Gitee `v1.3.0` 正式 Release 均公开，标签和三个附件的大小/SHA-256 经回读一致。
- 最终记录包含发布提交、标签对象、验证数字、产物摘要、双站链接和保留限制。

## 完成结果

- 版本与任务边界均先添加聚焦断言并取得有效 RED：旧 Gradle 元数据的 `versionCode=1`、`versionName=1.0.0` 两个子项失败；T73 精确任务路径被旧边界白名单拒绝。最小实现后版本1项与边界3项全部 GREEN。
- Android `scripts/build-release.ps1` 使用既有忽略签名材料完成构建与完整包审计；APK/AAB 清单分别用 aapt2、bundletool 回读为 `com.molotov.clender`、`1.3.0 (2)`。新证书 SHA256 `628248932cfd0587a04126290ac2af870591fc7ebe80259c5f29d266c944615f` 与 T72 基线相同。
- Android APK：1876645 bytes，SHA256 `3d80a52bcf055df7e710e2f568bfa30641edde0032a7781baca4972c7fe86f32`；AAB：4984304 bytes，SHA256 `df93d42d02bdde3a615ef586799492f8001356477ec250541019ba6da6141711`。
- `verify-all.ps1` 首轮组合会话持续约47分钟单核推进且无错误输出；核对 T72/新包 `classes.dex` 均为 SHA256 `0e68ef5bf8519a5826997b3b4ef257f5e436228905cbd88b473466a502f5f72b` 后，按 T69 先例中止重复深扫，不将该会话记录为 exit0。随后严格按入口脚本原参数分段复验：112发布夹具/2.638s，Gradle96tasks/5m23s，foundation108/3.458s，boundary108/3.376s，全部通过。
- Android 应用测试为213suites/2169tests（UI82/901），零失败/错误/跳过；XML 扫描 `CloseGuard`、`SQLiteConnectionPool`、`SQLiteDatabase leaked`、`RoomDatabase leaked` 均0。
- 一次性 API26 AVD `clender_api26_t73_upgrade` 先安装 T72 旧包 `1.0.0 (1)` 并写入合成事项/深色设置，再用 `adb install -r` 安装新包；回读为 `1.3.0 (2)`、UID不变、事项与设置均保留。AVD按身份关闭后删除，最终adb设备为空；没有卸载、清库或读取真实数据。
- Windows 最终284tests/10.275s通过；完整 PyInstaller 构建及普通→静默、静默→普通两场景隔离EXE均通过，收口后正式/测试Clender进程为零。`dist/data` 前后均为5文件/167951 bytes，路径/大小/mtime/SHA-256清单逐字节一致；只做摘要核对，不读取内容。
- Windows EXE：45584112 bytes，SHA256 `196c0eea521d58be7ba085d82ebf737abd36140f549b34e4215b3c7e48b921bd`。
- T72临时Provider凭据本轮未再次调用、未落盘；当前待发布提交、双站标签/Release和附件回读。
