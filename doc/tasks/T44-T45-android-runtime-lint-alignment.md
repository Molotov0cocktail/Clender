# T44–T45 Android Compose Runtime lint 原子对齐

## 目标

在保留 Compose BOM `2024.08.00` 和现有 Compose UI/graphics/foundation/material 版本的前提下，仅把 `androidx.compose.runtime` 原子组显式对齐到稳定版 `1.11.3`，消除 Runtime `1.6.8` 内嵌 lint detector 对 Kotlin `2.3.0` metadata 的读取崩溃，使 T44/T45 恢复现场真实通过 debug/release lint 与全部发布门禁。

## 非目标

- 不升级 Compose BOM、Compose UI、ui-graphics、foundation、Material/Material3、Kotlin、KSP、AGP、Gradle、compileSdk 或 minSdk。
- 不引入 `graphics-path`、NDK 或任何 native runtime；不接受 `.so` 进入解析 AAR 或 APK。
- 不禁用或抑制任何 Compose lint issue，不添加 baseline、源码排除或伪造 metadata version；不降低 `warningsAsErrors`、`abortOnError`、`checkReleaseBuilds`。
- 不修改 T44/T45 业务源码，不继续、暂存或提交 T46/T47/T48，不连接真实 WebDAV/AI，不 push。

## 选定路径与依据

唯一授权路径为 **D：Runtime-only `1.11.3`**。Compose Runtime `1.6.8` AAR 的内嵌 `lint.jar` 携带最多读取 Kotlin metadata `2.0.0` 的旧 reader，而 Runtime `1.11.3` 的 lint 实现不再携带该 reader。此前整体升级 Compose BOM 的 C3 路径会让 `ui-graphics:1.11.3` 引入含四 ABI `.so` 的 `graphics-path:1.0.1`，因此本任务用显式 runtime 依赖让 Gradle 只对齐 `androidx.compose.runtime` 同组，不移动 UI 图形栈。

## 影响文件

- `android/gradle/libs.versions.toml`：新增 `compose-runtime = "1.11.3"` 与显式 runtime alias。
- `android/app/build.gradle.kts`：在保留 BOM 的同时显式 `implementation` Runtime。
- `android/app/gradle.lockfile`、`android/gradle/verification-metadata.xml`：仅通过 Gradle 官方写锁/校验机制更新。
- `android/tests/policy/test_dependency_policy.py`：增加 Runtime 原子组、UI 组、native/graphics-path 与 lint 质量契约。
- 本任务文件、既有 T44/T45/lint/recovery/progress 文档和根 `AGENTS.md`：记录决策、证据、验证与风险。

明确排除 T46：`doc/tasks/T46-android-compose-ui.md` 与 `android/app/src/**/ui/**` 及其测试不得修改、暂存或提交。

## Runtime 原子组与 UI 组边界

- 允许解析到 `1.11.3`：`runtime`、`runtime-android`、`runtime-annotation`、`runtime-annotation-android`、`runtime-saveable`、`runtime-saveable-android`、对应内嵌 `lint.jar`，以及实际进入解析图的其他 `androidx.compose.runtime` 同组 artifact。
- 必须保持既有版本：`ui`/`ui-android`、`ui-graphics`/`ui-graphics-android`、`foundation`/`foundation-android`、Material `1.6.8` 系与 Material3 `1.2.1`。
- Compose BOM 必须保持 `2024.08.00`；显式 Runtime 覆盖是临时兼容钉扎，未来整体升级 BOM 时必须删除并重新跑完整解析、native、lint 与 APK 门禁。

## 运行时、API 与数据影响

- 应用运行时仅变更 Compose Runtime 同组实现版本；不使用 Runtime `1.11.3` 新 API，不改变 T44/T45 领域接口、WebDAV schema v1、Room v1、AI 操作或 Android/Windows 数据。
- 不读取、迁移或修改仓库根 `data/` 与 `dist/data/` 内容；Windows 构建仅做前后只读完整性摘要。
- UI 与 Runtime 跨版本组合存在二进制/行为兼容风险，必须由 unit、lint、assemble、解析图和 APK 冒烟门禁实证；出现不兼容即撤回 D 路径并 STOP。

## 修改前测试矩阵

| 类别 | 正常路径 | 边界值 | 非法/异常路径 | 回归路径 |
|---|---|---|---|---|
| 版本策略 | 显式 Runtime `1.11.3`；BOM `2024.08.00` | Runtime 同组所有已解析 artifact 原子一致 | 拒绝 `1.11.4`、BOM `2026.06.00`、任意 Runtime 漂移 | UI/ui-graphics/foundation/material 保持原版本 |
| 依赖图 | debug/release runtime classpath 均满足边界 | `runtime-saveable`、`runtime-annotation` 仅在实际选中时核对 | 拒绝 `graphics-path`、`androidx.graphics` 和未知 native artifact | resolved lock 与 verification metadata 完整且 strict offline 可重放 |
| lint | `lintDebug`、`lintRelease` 强制真实执行并分析 `AiCoordinator.kt`、`SyncCoordinator.kt` | Kotlin metadata `2.3.0` 可读 | 不再出现 max `2.0.0` 崩溃；任何 lint issue 按 warnings-as-errors 失败 | 不新增 Compose issue disable/suppress/baseline/source exclusion，三个质量开关保持开启 |
| Android 行为 | T44/T45/Room 聚焦和全部 JVM 测试通过 | API 26/36 Robolectric、debug/release 两变体 | strict lock、签名、Google/native/动态依赖门禁失败时停止 | detekt、ktlint、manifest/boundary、assembleDebug、APK 审计不回归 |
| Windows 发布 | 171 项 offscreen unittest、导入、check、完整 PyInstaller 与隔离双实例冒烟 | frozen primary/secondary | `dist/Clender.exe` 被用户进程占用时记录 PID 并 STOP | `dist/data` 文件集合、大小、时间、哈希摘要前后一致 |
| 安全提交 | 精确路径暂存 D 路径与 T44/T45 checkpoint | 未跟踪 T44/T45 文件逐项归属 | 拒绝 T46、真实数据、秘密、日志、数据库、APK/AAB/exe/cache/keystore | staged diff、敏感模式和 `git diff --check` 通过 |

## 实施步骤

1. 冻结 branch/HEAD/status/diff/untracked/staged/`git diff --check`，扫描失败候选残留。
2. 复现旧 `lintDebug lintRelease` metadata 崩溃和恢复现场非 lint 门禁。
3. 先增加策略测试并在旧配置上取得仅针对 D 路径缺失的预期红灯。
4. 由实现 Agent 仅在 catalog/build 文件加入 Runtime `1.11.3` 显式覆盖；主 Agent 审查 diff。
5. 用 Gradle 官方机制写锁与生成 verification metadata；使用 `dependencyInsight` 审计 Runtime/UI/graphics/foundation/material 的 debug/release compile/runtime 图。
6. 强制重跑 `lintDebug`、`lintRelease`，确认两变体执行、两 coordinator 完成分析且质量配置未放宽。
7. 运行 Android 完整门禁、generated/boundary 脚本，并审计所有解析 AAR 与 APK 的 `jni/`、`lib/`、`.so`，确认未使用 Runtime 新 API。
8. Android 全绿后执行 Windows 171 项、导入、`build.py --check`、完整 PyInstaller、隔离 exe primary/secondary 冒烟与 `dist/data` 前后只读摘要。
9. 同步任务文档、progress 与 `AGENTS.md`，检查 diff/secret/generated/staged，仅精确暂存本任务及 T44/T45 checkpoint 文件，创建普通 commit，不 push，然后停止。

## 精确回滚方式

若 D 路径触发任一 STOP 条件，只使用逐文件补丁撤销本任务新增的 Runtime catalog alias、app dependency 和策略测试断言；随后用 Gradle 官方写锁/verification metadata 机制恢复 Runtime `1.6.8` 解析状态，并复查 catalog、build、lock、metadata、解析图中无 Runtime `1.11.3` 残留。不得使用 `reset`、`clean`、`checkout`、`stash`，不得覆盖整文件，不得触碰既有 T44/T45 恢复现场或 T46 文件。失败后只更新本任务文件和 `progress.md`，不提交。

## STOP 条件

- Runtime `1.11.3` 仍携带会崩溃的旧 metadata reader，或任一 debug/release lint 未真实通过。
- UI/ui-graphics/foundation/material 版本漂移，出现 `graphics-path`、任意 native runtime、未知 artifact 或 APK native 条目。
- 需要升级 AGP/Gradle/Kotlin/SDK、禁用 lint、baseline、源码排除、降低质量开关，或发生二进制/API 不兼容。
- T44/T45 行为回归、T46 被修改/暂存、Windows 发布验证无法闭环、`dist/data` 发生变化。

触发后完整撤回 D 路径，保留原恢复现场，输出 `STOP/ESCALATE`，不得尝试 A/B/C。

## 完成定义

- [ ] Runtime 原子组精确解析为 `1.11.3`，BOM/UI/graphics/foundation/material 保持既有版本且无 `graphics-path`/native。
- [ ] 策略测试先红后绿；`lintDebug`、`lintRelease` 真实执行并在全部 Compose rules/质量开关保持时通过。
- [ ] Android 全部 unit/static/lock/signing/no-Google/no-native/assemble/APK/generated/boundary 门禁通过。
- [ ] Windows unittest/import/check/PyInstaller/exe 与 `dist/data` 核验全部通过。
- [ ] 文档和 `AGENTS.md` 同步；diff、secret、generated、staged 审查通过；T46 未修改或暂存。
- [ ] 在 `codex/android-architecture` 创建普通 checkpoint commit，不 push，并停止在 T44/T45。

## 执行记录

- 2026-08-30：任务建立。基线为 branch `codex/android-architecture`、HEAD `f14178792dea2363ddba1c8ead3732ce2f48403a`、无 staged；既有 T44/T45 恢复现场和 T46 预备文件均按用户所有保护。
- 旧失败已用 `--rerun-tasks lintDebug lintRelease` 真实复现：`:app:lintAnalyzeDebug` 与 `:app:lintAnalyzeRelease` 均执行，均在 `AiCoordinator.kt` 由 `ComposableStateFlowValueDetector` 触发 `metadata 2.3.0 / maximum 2.0.0` 崩溃。
- 修改前非 lint 门禁复现通过：223 项 Android JVM 测试对应的 `testDebugUnitTest`、detekt、ktlint、签名策略、无 Google、无 native、resolved lock、assembleDebug 与 APK native 审计均成功。
- 依赖证据 Agent 确认 Runtime `1.11.3` 是 2026-06-17 的稳定发布；本机 `runtime-android`、`runtime-saveable-android`、`runtime-annotation-android` 与候选 `runtime-retain-android` AAR 均无 `jni/`、`lib/` 或 `.so`。Runtime `1.11.3` 内嵌 lint.jar 仍包含 StateFlow/Coroutine Compose rules，但不再内嵌旧 metadata reader；当前 compileSdk 36 / AGP 8.13.2 满足其 AAR metadata 要求。
- 测试 Agent 先加入 10 项策略测试并取得精确红灯：8 项边界保持绿色，2 项只因缺少显式 Runtime `1.11.3` 覆盖和旧 Runtime `1.6.8` 锁图失败。实现 Agent 随后只加入预期 3 行 catalog/build 覆盖。
- Gradle 官方 `--write-locks --write-verification-metadata sha256 :app:dependencyInsight --configuration debugRuntimeClasspath --dependency androidx.compose.runtime` 证明 Runtime 六个已解析 artifact 原子对齐为 `1.11.3`，Compose BOM 仍为 `2024.08.00`，UI/ui-graphics/foundation/material 保持 `1.6.8`、Material3 保持 `1.2.1`，且未出现 `graphics-path`。

## STOP/ESCALATE 结果

**RESULT：STOP/ESCALATE。** `runtime-saveable-android:1.11.3` 的发布 Gradle Module Metadata 对 `lifecycle-runtime-compose:2.9.4` 与 `savedstate-compose:1.3.2` 使用硬性 `requires`。真实解析因此把现有 Lifecycle 原子组从 `2.8.4` 整组提升到 `2.9.4`，并把 SavedState 从 `1.2.1` 提升/扩展到 `savedstate(-compose):1.3.2`。这不是获准的 `androidx.compose.runtime` 同组原子升级；保留旧版本只能增加 force/strict/exclusion，既违背 producer metadata 的 required 请求，也超出唯一 D 路径和预期最小实现。

STOP 发生在 L3 解析图审计，尚未进入 L4 聚焦 lint。因此不得把 Runtime `1.11.3` “理论上移除旧 reader”宣称为 lint 已通过，也不执行 L5、Windows/PyInstaller/exe 发布验证，不创建 commit，不尝试 A/B/C。

## 回退与恢复验证

- 实现 Agent 已用逐行补丁撤回 catalog/build 的 3 行；测试 Agent已精确撤回本轮策略测试，保留进入任务前已有的 4 项 dependency policy（4/4 PASS）和 T44/T45 分层测试现场。
- 移除显式覆盖后，用 Gradle 官方写锁/verification metadata 机制重新解析旧图；随后只删除官方 metadata writer 的 append-only、已不再解析的 D 候选组件校验块，不编造或修改任何校验值。
- 复查 catalog、build、lock 与 verification metadata 均无 Runtime `1.11.3`、Lifecycle `2.9.4`、SavedState `1.3.2` 或显式 `compose-runtime` 残留；原五个受影响文件的 diff 形状恢复为任务开始时的 `10/0、30/11、11/0、155/0、20/0`。
- 回退后 strict offline 的 `verifyNoNativeRuntimeArtifacts`、`verifyResolvedVersionsLocked`、`verifyDebugApkNoNativeArtifacts` 通过，既有 dependency policy 4/4 通过；`git diff --check` 无内容错误，仅保留既有 LF→CRLF 提示。
- T46 文件未修改、未暂存；staged 仍为空。原 T44/T45 未提交恢复现场保持不提交状态。
