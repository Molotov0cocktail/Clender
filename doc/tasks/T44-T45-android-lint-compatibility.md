# T44–T45 Android lint 与 Kotlin 2.3 metadata 兼容性阻塞

## 目标

解决 Android lint 对 Kotlin 2.3.0 metadata 的读取崩溃，使 T44/T45 恢复现场能够真实通过 `lintDebug` 与 `lintRelease`，并在不引入 native runtime、不禁用质量规则、不改变应用运行时依赖图的前提下形成可复现的最小构建修复或明确的 STOP/ESCALATE 结论。

## 非目标

- 不继续 T46 Compose 页面/导航/接线；不开始 T47/T48。
- 不降级 Kotlin、不降低 `warningsAsErrors`/`abortOnError`、不添加 lint baseline、不排除源码目录、不使用 `-Xmetadata-version`、不禁用 `StateFlowValueCalledInComposition`/`CoroutineCreationDuringComposition` 或任何 Compose lint rule。
- 不修改 Windows 产品行为；不触碰真实 `data/`、`dist/data/`。
- 不以“绿色结果”为目的放宽任何强制门禁。

## 当前复现命令及完整错误摘要

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\android\scripts\gradle.ps1 --offline --no-daemon --dependency-verification strict lintDebug lintRelease
```

实际结果（2026-08-30 本轮复现，`lintAnalyzeDebug` 崩溃）：

```
Execution failed for task ':app:lintAnalyzeDebug'.
Message: Unexpected failure during lint analysis of AiCoordinator.kt (this is a bug in lint or one of the libraries it depends on)
Message: Provided Metadata instance has version 2.3.0, while maximum supported version is 2.0.0.
  To support newer versions, update the kotlinx-metadata-jvm library.
The crash seems to involve the detector `androidx.compose.runtime.lint.ComposableStateFlowValueDetector`.
Stack: ... KotlinClassMetadata$Companion.throwIfNotCompatible$kotlinx_metadata_jvm
  -> KotlinMetadataUtilsKt.getKmDeclarationContainer -> ComposableUtilsKt.isComposable
  -> ComposableStateFlowValueDetector$createUastHandler$1.visitSimpleNameReferenceExpression
```

## 根因分析（一手证据）

1. Kotlin 2.3.21 编译产物携带 metadata 版本 **2.3.0**。
2. 崩溃读取器 FQCN 为 `kotlinx.metadata.jvm.KotlinClassMetadata`（旧包名 `kotlinx_metadata_jvm`，`LATEST_STABLE_SUPPORTED = 2.0.0`）。
3. 该读取器 **内嵌在 `androidx.compose.runtime:runtime-android:1.6.8` AAR 的 `lint.jar` 中**，并随 Compose 1.6.8 的 `ComposableUtilsKt`/`KotlinMetadataUtilsKt`/`ComposableStateFlowValueDetector` 一起被打包。
4. AGP 8.13.2 lint 模型自动发现依赖 AAR 的 `lint.jar`（lint model `lintJar="lint.jar"` 属性），迁移进 `build/intermediates/lint-cache/.../migrated-jars/androidx.compose.runtime.lint.RuntimeIssueRegistry-*.jar` 并加载执行 → 命中旧读取器。
5. 对照缓存样本：
   - `runtime-lint` 1.11.4 standalone jar：**不含**任何 metadata reader，检测器改为基于 PSI/UAST 注解与 `KaAnnotation` 判定 composability，理论上不会因 metadata 版本崩溃；
   - `runtime-android` 1.9.2/1.9.5 AAR 内嵌 `androidx.lint.kotlin.metadata.jvm.KotlinClassMetadata`（relocated，`LATEST_STABLE_SUPPORTED=2.1.0`），**仍低于 2.3.0**，同样会崩；
   - `runtime-android` 1.11.3 AAR 内嵌 lint.jar **不含** metadata reader（注解判定），不会因 metadata 版本崩溃。
6. 官方文档：KGP 2.3.20–2.3.21 官方支持的 AGP 区间为 8.2.2–9.0.0（`kotlinlang.org/docs/gradle-configure-project.html` 兼容表），AGP 8.13.2 在支持区间内；因此问题不是 KGP/AGP 配对，而是 Compose 1.6.8 内嵌 lint 读取器与 Kotlin 2.3 metadata 的硬性不兼容。

## 候选方案矩阵（L2 只读结论）

| # | 候选 | 是否改变应用运行时依赖图 | 是否触碰已锁定工具链 | 可行性证据 | 结论 |
|---|---|---|---|---|---|
| C1 | 通过 `lintChecks` 配置添加更新版 `androidx.compose.runtime:runtime-lint` standalone jar（如 1.11.x），使其在 lint classpath 上遮蔽 AAR 内嵌的 1.6.8 lint.jar | 否（`lintChecks` 是构建期配置，不进应用 classpath） | 否（不升级 AGP/Compose runtime） | 1.11.4 jar 已缓存且无内嵌 reader；但 AGP 仍会加载 AAR 内嵌 1.6.8 lint.jar，需要实证 classpath 顺序与遮蔽是否生效 | 待 L3 实测（首选，最贴合“只修 lint 工具执行环境”） |
| C2 | 排除 Compose runtime AAR 的 `lint.jar` 不进 lint 分析（AGP 无公开按依赖排除 lint.jar 的 DSL） | 否 | 否 | 无公开机制，需 deep AGP 源码介入 | 不采用 |
| C3 | 升级 Compose runtime/UI 至 1.11.x（其内嵌 lint.jar 无旧 reader） | **是**（改变运行时依赖图，且可能引入 `graphics-path` native） | 是（BOM/Compose 原子组） | 1.11.x 内嵌 lint.jar 无 reader，理论可行 | 受约束禁止，除非证明无 native 且用户确认 → 潜在 STOP/ESCALATE |
| C4 | 升级 AGP 使 lint 引擎自带支持 2.3.0 的 metadata reader | 否（AGP 是构建工具） | 是（改变锁定 AGP/Gradle 工具链） | 崩溃 reader 在 Compose AAR 而非 AGP lint 引擎内；仅升 AGP 不足以保证遮蔽 Compose 内嵌 reader | 受约束禁止；需用户确认 |
| C5 | 降级 Kotlin / `-Xmetadata-version` / 禁用规则 / baseline / 排除目录 | — | — | — | 全部被约束明令禁止 |
| C6 | 在 lint classpath 上放置新包名 `kotlin.metadata.jvm`（2.3.21）以“替换”旧读取器 | 否 | 否 | **证伪**：旧 FQCN 是 `kotlinx.metadata.jvm`，新库包名不同，无法遮蔽；且不存在更新版旧包名 artifact | 不采用 |

## 影响文件（L3 视候选而定）

- 首选 C1：`android/app/build.gradle.kts`（新增 `lintChecks(...)` 依赖）、`android/gradle/libs.versions.toml`、`android/app/gradle.lockfile`、`android/gradle/verification-metadata.xml`。
- 若候选失败需完整撤回并清理 lock/verification metadata。

## 依赖/运行时影响

- C1 只增加构建期 lint 检查 classpath；应用 runtime/compile classpath、APK 内容与无 native 契约不受影响。
- 若必须转向 C3/C4（工具链原子组），属于新契约决策，按任务要求 STOP/ESCALATE 等待用户确认。

## 测试矩阵

| 路径 | 正常 | 边界 | 非法/异常 | 回归 |
|---|---|---|---|---|
| lint 门禁 | `lintDebug`/`lintRelease` 真实通过 | metadata 2.3.0 全部源文件可分析 | 不再出现 “maximum supported version” 崩溃 | 既有 149 项聚焦 + 223 项 Android JVM 全量不回归 |
| 构建/锁 | strict offline 全任务 | API 26/36 Robolectric | 失败候选不残留 lock/metadata | 无 Google/native、resolved lock、APK 审计 |
| Windows | 171/171 unittest、导入、build check、PyInstaller、隔离 exe 冒烟 | — | `dist/Clender.exe` 被进程占用时的发布路径 | `dist/data` 只读完整性前后一致 |

## 风险

- C1 遮蔽是否生效取决于 AGP lint classpath 顺序；若 1.6.8 AAR 内嵌 lint.jar 仍先加载，崩溃依旧，需撤回。
- 即使 C1 解决 Compose 检测器崩溃，AGP lint 引擎自身读取 2.3.0 metadata 的能力仍需实证；若引擎自带 reader 也崩溃，则必须转向工具链升级（STOP/ESCALATE）。
- 不得为了通过而禁用任何 Compose rule 或降低门禁；不得把“未运行”谎报为“通过”。

## 回滚方式

- 只回滚本任务引入的 `lintChecks` 依赖、version catalog、lockfile 与 verification metadata 条目；不得 reset/clean/checkout 或改动 T44/T45 生产文件。
- 失败候选使用官方 Gradle 机制撤销后重写 lock/metadata，并复查无残留。

## L3 实测结果（2026-08-30）

- 首选候选 C1 已实施并验证：在 `android/app/build.gradle.kts` 添加 `lintChecks(libs.androidx.compose.runtime.lint)`，catalog 添加 `runtime-lint = "1.11.4"`，用官方 `--write-locks` 更新 `gradle.lockfile`，用 `--write-verification-metadata sha256` 更新校验元数据。
- 验证命令：`gradle.ps1 --offline --no-daemon --write-verification-metadata sha256 :app:lintDebug`。
- 结果：**C1 无效**。`lintAnalyzeDebug` 以完全相同的错误崩溃（`Provided Metadata instance has version 2.3.0, while maximum supported version is 2.0.0`，检测器 `ComposableStateFlowValueDetector`，读取器 `kotlinx_metadata_jvm.KotlinClassMetadata`）。原因是 AGP 8.13.2 的 lint 模型仍会自动加载 Compose Runtime 1.6.8 AAR 内嵌的 `lint.jar`（`lintJar="lint.jar"` 属性 + migrated-jars），其内嵌旧读取器在类路径上先于 standalone jar，且 standalone 1.11.4 不包含旧包名 `kotlinx.metadata.jvm` 的类，无法遮蔽。
- 已按失败候选规则**完整撤回 C1**：移除 `lintChecks` 依赖、catalog 条目、lockfile 行与 verification-metadata 组件，并恢复 `empty=` 行。复查后无 `runtime-lint` 残留；Android 非 lint 门禁（test/detekt/ktlint/policy/lock/signing/无 Google/无 native/APK 审计）在恢复现场下全部通过。
- 结论：与恢复记录一致——在“不升级 Compose/AGP、不降级 Kotlin、不禁用规则、不改运行时图”的约束内，不存在单一的 lint 工具环境最小修复；进入 STOP/ESCALATE。

## STOP/ESCALATE 结论

按任务约束（升级 AGP/Gradle/Compose 运行时原子组属新契约、不得自行扩大工具链升级），本轮不自行选择 C3/C4；候选矩阵、证据与推荐方案提交用户确认。未创建 commit。

## 用户决策（2026-08-30 确认）

用户明确选择 **C3：升级 Compose 至 1.11.x 系**（推荐路径）。按约束要求：升级前必须先证明不会引入 `graphics-path` 或其他 native artifact（`verifyNoNativeRuntimeArtifacts` + APK 审计），通过后才允许实施升级并重跑完整门禁；若升级引入 native，立即停止。

## C3 实测结果（2026-08-30，STOP/ESCALATE 决定性证据）

- 实施：`compose-bom = "2024.08.00"` → `"2026.06.00"`（对应 Compose runtime/UI/foundation 1.11.3），在线获取缺失传递依赖，官方 `--write-locks`/`--write-verification-metadata` 更新 lock 与校验元数据。
- **无 native 门禁失败**：`verifyNoNativeRuntimeArtifacts` 报告 `Native runtime artifact is forbidden in debugRuntimeClasspath: graphics-path-1.0.1.aar`。
- 一手证据：`androidx.compose.ui:ui-graphics:1.11.3` 硬依赖 `androidx.graphics:graphics-path:1.0.1`；其 AAR 内含 4 个 ABI 的 `jni/*/libandroidx.graphics.path.so`（x86_64/arm64-v8a/armeabi-v7a/x86）。
- 结论：**C3 在无 native 契约下不可行**，即使已获用户确认也不得实施（约束原文要求“先证明不会引入 graphics-path 或其他 native artifact”）；也未达到放宽契约的用户授权。C3 已完整回退。
- 回退后状态：`compose-bom` 还原为 `2024.08.00`；lockfile/metadata 重新生成后无 1.11.3/graphics-path/2026 残留；Android 非 lint 门禁（test/detekt/ktlint/policy/lock/signing/无 Google/无 native/APK 审计）在恢复现场下 strict 通过；`lintDebug` 仍为已知的 metadata 读取崩溃（唯一阻塞）。

## 最终决策（2026-08-30，用户再次确认：保持现状，暂不实施）

- 在完整约束集内（不升级工具链、不改变运行时图、不引入 native、不禁用 Compose lint rules、不降级 Kotlin、不 `-Xmetadata-version`、不加 baseline、不放宽 warningsAsErrors），C1（lintChecks 遮蔽）与 C3（Compose 升级）均已实测失败或受强制门禁阻塞；没有单一最小修复。
- 用户明确选择**保持现状、暂不实施**：不进行工具链/契约变更，不创建 commit；恢复现场与本文档一并保留，供后续决策。

## 最终决策（2026-08-30 用户确认：保持现状，暂不实施）

- 在完整约束集（不升级工具链、不改变运行时图、不引入 native、不禁用 Compose lint rules、不降级 Kotlin、不 `-Xmetadata-version`、不加 baseline、不放宽 warningsAsErrors）内，C1（lintChecks 遮蔽）与 C3（Compose 升级）均已实测失败且被强制门禁阻塞，不存在单一可行修复。
- 用户明确选择**保持现状、暂不实施**：本轮不进行任何工具链/契约变更，不创建 commit。受保护恢复现场与本文档一同保留，供后续决策。



## 完成定义

- [ ] 复现命令记录在案，`lintDebug`/`lintRelease` 真实通过（非禁用、非 baseline、非放宽）。
- [ ] Android 全量 unit、detekt、ktlint、policy、lock、签名策略、无 Google/native、APK 审计通过。
- [ ] Windows 全量、导入、完整 PyInstaller、隔离 exe 冒烟通过；`dist/data` 前后一致。
- [ ] `git diff --check` 通过；staged 无 T46 扩展、生成物、秘密或真实数据。
- [ ] `AGENTS.md`、任务文档与 progress 同步；创建普通 checkpoint commit。
- [ ] 若任何候选无法在约束内闭环 → 明确 STOP/ESCALATE，保留现场，不提交。

## 后续 F 路径结果（2026-08-30）

本文件记录的 C1/C3 STOP 结论保持为历史证据；用户随后通过独立 F 任务授权 Runtime/Lifecycle/SavedState 最小一致集及唯一 producer 传递变化 Annotation Experimental 1.4.1。F 已在不升级 UI/graphics、不引入 native、不禁用规则的前提下真实通过 debug/release lint 与完整发布门禁。最终证据以 `T44-T45-android-annotation-experimental-alignment.md` 为准。
