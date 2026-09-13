# T69 双端 v1.2.0 正式发布（2026-09-14）

## 目标

- 将当前 `main/8616acf` 已验收的 Windows 与 Android 正式应用发布为 `v1.2.0`。
- GitHub 与 Gitee 均创建正式 Release，并上传 Windows EXE、Android APK、Android AAB。
- 更新公开 README 与内部发布记录，使下载入口、产物摘要、验收范围和已知限制可追溯。

## 非目标

- 不修改 Windows/Android 生产源码、配置、数据库、依赖、构建脚本或数据契约。
- 不修改 Android 包内历史版本值 `1.0.0 (1)`，不重新签名 Android 产物。
- 不读取、迁移、上传 `data/` 或 `dist/data/`；不推送秘密、日志、缓存或构建目录。
- 不覆盖既有 `v1.1.0` Release，不强推任何远端分支。

## 影响文件

- `README.md`
- `AGENTS.md`
- `android/AGENTS.md`
- `doc/tasks/T69-dual-platform-release.md`
- `doc/tasks/progress.md`

外部状态：GitHub/Gitee 的 `v1.2.0` 标签、Release 与三个公开附件；源码提交推送到可安全快进的远端分支。Gitee `main` 存在独立 `LICENSE` 提交，未经合并授权不得强推覆盖；Release 标签可直接指向本次文档提交。

## 接口与数据影响

- 无生产接口、UI、Room/SQLite schema、WebDAV、AI、权限、Manifest 或依赖变化。
- Android APK/AAB 沿用 T68 签名产物；Windows EXE 在文档变更后按根门禁重新完整构建，生产源码不变。
- 发布附件只包含三个应用包，不包含用户数据、配置、密钥或源映射。

## 风险

- 双站认证或上传中断可能形成只有标签、草稿或部分附件的中间状态。
- Android Release 标签为 `v1.2.0`，包内仍显示 `1.0.0 (1)`；这是用户确认的现有产物发布范围，后续商店升级前应统一版本代码。
- Gitee `main` 与本地 `main` 自 `v1.1.0` 后分叉；本任务不得用 force push 覆盖远端 `LICENSE` 提交。

## 测试矩阵（修改与发布前确定）

| 类别 | 用例 | 通过条件 |
|---|---|---|
| 正常路径 | 核对 EXE/APK/AAB 路径、大小和 SHA-256 | 与既有 T65/T68 正式记录一致；PC 重建后重新记录 EXE 摘要 |
| 正常路径 | GitHub/Gitee 创建 `v1.2.0` 正式 Release | 均非 draft/prerelease，标签指向本次提交，三个附件可见 |
| 边界 | 远端已有 `v1.1.0`，目标 `v1.2.0` 尚不存在 | 不覆盖旧 Release；名称无冲突；失败可安全重试 |
| 边界 | Gitee `main` 存在独立提交 | 不强推；保留远端分支，使用新标签承载当前发布源码 |
| 异常 | 认证、网络、单个附件上传失败 | 停止宣称完成，核对实际远端状态后补齐或明确报告 |
| 回归 | 文档 diff 与 Git 暂存审查 | 无生产源码/契约改动，无敏感文件和生成物入库 |
| 回归 | Windows 全量测试、完整 PyInstaller 构建、隔离 EXE 普通/静默冒烟 | 全部通过，`dist/data/` 前后路径/大小/时间/hash 不变 |
| 回归 | Android 现有正式包审计复核 | APK/AAB 摘要匹配 T68；不把未验收 debug 包上传 |

## 实施步骤

1. 核对工作区、提交、远端分支/标签/Release 与正式产物摘要。
2. 先建立本任务和测试矩阵，再更新 README、根/Android 协作记录与进度。
3. 执行敏感模式、diff、Windows 测试/构建/冒烟和 `dist/data/` 完整性检查；复核 Android 产物摘要。
4. 按仓库要求配置代理，暂存并审查，仅提交文档文件。
5. 创建 `v1.2.0` 标签；安全推送提交/标签，创建双站正式 Release 并上传三项重命名附件。
6. 远端回读标签、Release 元数据、附件大小/摘要和下载链接，更新本记录最终结果。

## 回滚方式

- 文档提交可用新提交 `git revert` 回滚，禁止重写公共历史。
- 发布前中断不删除既有版本；发布后若附件错误，先将 Release 标为草稿或删除错误附件后重新上传。删除公开标签/Release 属破坏性操作，须再次获得用户授权。
- 不通过清空数据库、删除用户数据或降级 Android Room v3 回滚。

## 完成定义

- 文档准确、契约零修改、工作区最终干净并有独立提交。
- Windows 完整构建与隔离冒烟通过，`dist/data/` 零变化；Android APK/AAB 摘要复核通过。
- GitHub 与 Gitee 的 `v1.2.0` Release 均公开、非预发布，三个附件齐全且摘要与本地一致。
- 最终记录包含提交/标签、双站链接、附件大小/SHA-256、验证结果和保留限制。

## 当前状态

- 进行中：公开 README、根/Android 协作说明与进度已更新，生产源码/契约 diff 为零。
- Windows 首轮 252 项测试因两个真实 Clender 进程触发隔离清理保护而 1 error；用户保存退出后原命令重跑 252/252 通过，未修改测试或生产代码。
- 完整 PyInstaller 构建通过；普通→静默、静默→普通两场景隔离 EXE 冒烟均 readiness/secondary/cleanup/quiescence 通过，结束后 Clender 进程为零。
- `dist/data` 构建前后均为 5 文件、145237 bytes，包含路径/大小/mtime/hash 的清单 SHA256 均为 `06cf276e229733843d17458d7218bea083a84b0bfdeb238d1ff7ab0d5e8e2017`。
- Windows EXE：45573514 bytes，SHA256 `567df4eeb9d2978245e6bcc881222135265f96149a6a3d496fd3c2ea8cd19ced`。
- Android APK：1865613 bytes，SHA256 `c24bbb3aa0d5f57ccb7720b5f7ee9aae3a71d55a8e46b8b91a6992a48311c068`；AAB：4957321 bytes，SHA256 `573e04c0372aa235973fafb36fb8ad630905018be6b3f4d80082df5a5e51939f`。
- 等待 Android 现有包复审、文档提交、标签与双站 Release 上传/回读。
