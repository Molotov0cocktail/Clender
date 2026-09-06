# T44–T45 Android Annotation Experimental 传递对齐（F 路径）

## 状态

**PASS（2026-08-30）**。本任务已恢复 E 的 Runtime/Lifecycle/SavedState 最小一致依赖集，并仅新增授权 `androidx.annotation:annotation-experimental:1.4.0 → 1.4.1`。完整解析图、真实 lint、Android/Windows 发布门禁、`dist/data` 完整性与 checkpoint 前审查均已闭环。

## 目标与决策

- 决策：**E + Annotation Experimental 1.4.1**。
- 原子组：`androidx.compose.runtime:* = 1.11.3`、`androidx.lifecycle:* = 2.9.4`、`androidx.savedstate:* = 1.3.2`。
- 普通传递变化：ProfileInstaller `1.3.1 → 1.4.0`、新增 Core ViewTree `1.0.0`、Annotation Experimental `1.4.0 → 1.4.1`。
- `annotation-experimental:1.4.1` 必须仅由 `runtime-android:1.11.3` producer metadata 传递进入，不增加 app 直接依赖或 version-catalog alias。
- 目标是在保持 T44/T45 行为、SDK、工具链和既有 Compose UI 图不变的前提下，使 debug/release lint 与完整 Android、Windows 发布门禁闭环。

## 非目标

- 不升级 Compose BOM `2024.08.00`、UI/ui-graphics/foundation/material `1.6.8`、Material3 `1.2.1`、Activity Compose `1.9.2`、Core/Core KTX `1.13.1`。
- 不升级 Kotlin `2.3.21`、KSP `2.3.9`、AGP `8.13.2`、Gradle `8.13` 或 API 26–36。
- 不使用 force、strictly、resolutionStrategy、新 exclusion、lint baseline、rule suppression 或源码排除。
- 不新增 `graphics-path`、`runtime-retain`、allowlist 外 Lifecycle/LiveData、Google SDK、native、动态或 SNAPSHOT 依赖。
- 不修改 T44/T45 业务行为、Room/WebDAV/AI 数据契约；不继续、修改、暂存或提交 T46/T47/T48；不 push。

## 受保护现场与影响文件

- branch `codex/android-architecture`，基线 HEAD `f14178792dea2363ddba1c8ead3732ce2f48403a`，开始时 staged 为空。
- 所有既有 modified/untracked 文件均为用户现场；禁止 reset、clean、checkout、stash、删除未跟踪文件或覆盖整个文件。
- 预期生产/解析文件：`android/gradle/libs.versions.toml`、`android/app/build.gradle.kts`、`android/app/gradle.lockfile`、`android/gradle/verification-metadata.xml`。
- 预期测试/策略文件：`android/tests/policy/test_dependency_policy.py`、F resolved-graph policy fixture、非 T46 的 Lifecycle/SavedState compatibility tests。
- 文档：本文件、`doc/tasks/progress.md`、T44/T45/lint/recovery 文档与根 `AGENTS.md`。
- 明确排除 `doc/tasks/T46-android-compose-ui.md`、`android/app/src/**/ui/**` 及其测试。

## 完整 configuration 清单门禁

配置发现使用旧图的 `:app:dependencies` 与独立 `canBeResolved` 枚举交叉核对；清单必须覆盖以下全部族，不能只检查 debug/release compile/runtime：

1. AGP/KSP 内部配置：`_agp_internal_*_kspClasspath`，覆盖 debug、release、debug/release unit test 与 debug androidTest。
2. 主变体：debug/release compile、runtime、annotation processor、KSP、metadata、wear/bundling 及 APK 打包相关可解析配置。
3. 单元测试：debug/release unit-test compile/runtime、KSP、Robolectric 与 test fixture 相关可解析配置。
4. instrumentation：debug AndroidTest compile/runtime、KSP、UTP/device-provider 与 instrumentation 相关可解析配置。
5. lint：debug/release lint analyze/model/report 相关 classpath、`lintClassPath`、`lintChecks`/`lintPublish` 中实际可解析项。
6. 静态质量：detekt、ktlint 的 plugin/configuration classpath。
7. 自定义：`robolectricFrameworkApi26`、`robolectricFrameworkApi36`、release signing/policy、no-Google、no-native、resolved-lock、APK native audit 所消费或创建的 configuration。
8. lockfile 中出现但不属于以上分组的其他 configuration，以及 AGP `_internal-unified-test-platform-*` 配置。

只读枚举确认：Gradle 原生报告 81 项；其中 `kotlin-extension`、`robolectricFrameworkApi26`、`robolectricFrameworkApi36` 可由逐项命令真实解析；strict lock 另含当前 configuration container 已不存在的 stale 名称 `androidApis`。因此审计 universe 合计 85 项，即 **84 个当前可解析 configuration + 1 个 lock-only stale 名称**：

```text
_agp_internal_javaPreCompileDebugAndroidTest_kspClasspath
_agp_internal_javaPreCompileDebugUnitTest_kspClasspath
_agp_internal_javaPreCompileDebug_kspClasspath
_agp_internal_javaPreCompileReleaseUnitTest_kspClasspath
_agp_internal_javaPreCompileRelease_kspClasspath
_internal-unified-test-platform-android-device-provider-ddmlib
_internal-unified-test-platform-android-driver-instrumentation
_internal-unified-test-platform-android-test-plugin
_internal-unified-test-platform-android-test-plugin-host-additional-test-output
_internal-unified-test-platform-android-test-plugin-host-apk-installer
_internal-unified-test-platform-android-test-plugin-host-coverage
_internal-unified-test-platform-android-test-plugin-host-device-info
_internal-unified-test-platform-android-test-plugin-host-emulator-control
_internal-unified-test-platform-android-test-plugin-host-logcat
_internal-unified-test-platform-android-test-plugin-result-listener-gradle
_internal-unified-test-platform-core
_internal-unified-test-platform-launcher
androidApis
androidJdkImage
androidTestDebugImplementationDependenciesMetadata
androidTestImplementationDependenciesMetadata
androidTestReleaseImplementationDependenciesMetadata
androidTestUtil
composeMappingProducerClasspath
coreLibraryDesugaring
debugAndroidTestAnnotationProcessorClasspath
debugAndroidTestCompileClasspath
debugAndroidTestImplementationDependenciesMetadata
debugAndroidTestRuntimeClasspath
debugAnnotationProcessorClasspath
debugCompileClasspath
debugImplementationDependenciesMetadata
debugReverseMetadataValues
debugRuntimeClasspath
debugUnitTestAnnotationProcessorClasspath
debugUnitTestCompileClasspath
debugUnitTestImplementationDependenciesMetadata
debugUnitTestRuntimeClasspath
debugWearBundling
detekt
detektPlugins
implementationDependenciesMetadata
kotlin-extension
kotlinBuildToolsApiClasspath
kotlinCompilerClasspath
kotlinCompilerPluginClasspath
kotlinCompilerPluginClasspathDebug
kotlinCompilerPluginClasspathDebugAndroidTest
kotlinCompilerPluginClasspathDebugUnitTest
kotlinCompilerPluginClasspathRelease
kotlinCompilerPluginClasspathReleaseUnitTest
kotlinInternalAbiValidation
kotlinKlibCommonizerClasspath
kotlinNativeCompilerPluginClasspath
kspDebugAndroidTestKotlinProcessorClasspath
kspDebugKotlinProcessorClasspath
kspDebugUnitTestKotlinProcessorClasspath
kspPluginClasspath
kspPluginClasspathNonEmbeddable
kspReleaseKotlinProcessorClasspath
kspReleaseUnitTestKotlinProcessorClasspath
ktlint
ktlintBaselineReporter
ktlintReporter
ktlintRuleset
lintChecks
lintPublish
releaseAnnotationProcessorClasspath
releaseCompileClasspath
releaseImplementationDependenciesMetadata
releaseReverseMetadataValues
releaseRuntimeClasspath
releaseUnitTestAnnotationProcessorClasspath
releaseUnitTestCompileClasspath
releaseUnitTestImplementationDependenciesMetadata
releaseUnitTestRuntimeClasspath
releaseWearBundling
robolectricFrameworkApi26
robolectricFrameworkApi36
testDebugImplementationDependenciesMetadata
testFixturesDebugImplementationDependenciesMetadata
testFixturesImplementationDependenciesMetadata
testFixturesReleaseImplementationDependenciesMetadata
testImplementationDependenciesMetadata
testReleaseImplementationDependenciesMetadata
```

AGP 不生成 release AndroidTest compile/runtime 配置；`debugAndroidTestRuntimeClasspath` 存在但不继承 app 主图。lint 没有 variant 专属可解析 configuration，`lintChecks`/`lintPublish` 旧图与 F 均应为空；lintAnalyze 消费对应 variant compile/runtime 图。

### 逐 configuration 旧图 → F delta

仅以下 9 项允许非零 delta：

- `debugAndroidTestCompileClasspath`：Runtime 既有四项 `1.6.8 → 1.11.3`，新增 runtime-annotation 两项 `1.11.3`；Lifecycle 既有 14 项 `2.8.4 → 2.9.4`，新增 `lifecycle-viewmodel-savedstate-android:2.9.4`；SavedState 两项 `1.2.1 → 1.3.2`，新增 savedstate-android/compose/compose-android 三项 `1.3.2`；Annotation Experimental `1.4.0 → 1.4.1`。该 compile 图不得出现 Core ViewTree/ProfileInstaller。
- `debugCompileClasspath`、`releaseCompileClasspath`、`debugUnitTestCompileClasspath`、`releaseUnitTestCompileClasspath`：同样的三个原子组变化；Annotation Experimental 已由其他 producer 解析为 `1.5.0`，故相对旧图 delta 为零；不得出现 Core ViewTree/ProfileInstaller。
- `debugRuntimeClasspath`、`releaseRuntimeClasspath`、`debugUnitTestRuntimeClasspath`、`releaseUnitTestRuntimeClasspath`：同样的三个原子组变化，并含 `lifecycle-common-java8 2.8.4 → 2.9.4`、ProfileInstaller `1.3.1 → 1.4.0`、新增 Core ViewTree `1.0.0`；Annotation Experimental 仍为冻结的 `1.5.0`，delta 为零。
- 其余 76 项必须 delta 为零，包括 debug AndroidTest runtime、所有 AGP/KSP/UTP/Kotlin compiler、detekt、ktlint、lintChecks/lintPublish、Robolectric、JDK/API、metadata/reverse/wear/custom 项。

测试门禁必须冻结 85 项审计 universe（84 active + stale lock-only `androidApis`）、全部未授权 lock 行，以及上面 9 项授权坐标的精确 configuration 集合；writer 后由独立图 Agent 再对真实 lock 与 dependencyInsight 复核。`androidApis` 不得被虚报为当前可解析，也不得由 writer 制造候选 delta。

## 授权 dependency delta

### 三个原子组

| 组 | 旧图 | F 图 | 允许的 artifact 范围 |
|---|---:|---:|---|
| `androidx.compose.runtime:*` | 1.6.8/缺失 | 1.11.3 | E 文档列明的 runtime、runtime-android、runtime-saveable、runtime-saveable-android、runtime-annotation、runtime-annotation-android |
| `androidx.lifecycle:*` | 2.8.4/缺失 | 2.9.4 | E 文档列明的 common/runtime/process/viewmodel/livedata-core/compose/ktx/savedstate 及 Android/KMP variants；不得出现额外 LiveData artifact |
| `androidx.savedstate:*` | 1.2.1/缺失 | 1.3.2 | E 文档列明的 savedstate、savedstate-ktx、savedstate-android、savedstate-compose、savedstate-compose-android |

### 普通传递变化

| 坐标 | 旧图 | F 图 | 依据 |
|---|---:|---:|---|
| `androidx.profileinstaller:profileinstaller` | 1.3.1 | 1.4.0 | Lifecycle 2.9.4 producer metadata |
| `androidx.core:core-viewtree` | 无 | 1.0.0 | Lifecycle/SavedState producer metadata |
| `androidx.annotation:annotation-experimental` | 1.4.0 | 1.4.1 | `runtime-android:1.11.3` producer metadata；仅传递依赖 |

除上表外，不允许任何坐标、版本、configuration 特例或选择原因变化。特别保持 Annotation `1.9.1`、Compose BOM/UI/graphics/foundation/material、Material3、Activity、Core/Core KTX 与全部工具链版本。

## Producer、AAR 与 lint.jar 风险

- 必须保存 Runtime 1.11.3 的 `.module`/POM 证据，证明其请求 Annotation Experimental 1.4.1；不得用直接声明改变来源。
- 必须检查 Runtime/Lifecycle/SavedState/Annotation Experimental 候选 POM、module、AAR 与 lint.jar；扫描 `jni/`、`lib/`、`.so`、Google group、动态/SNAPSHOT。
- Runtime 1.11.3 lint 必须不再加载 Runtime 1.6.8 的 metadata 2.0 reader，且 Compose StateFlow/Coroutine rules 继续执行。
- Lifecycle 2.9.4 lint 不得出现 metadata 2.1/2.3 或 `KaCallableMemberCall` 二进制崩溃。
- Annotation Experimental 1.4.1 的 lint.jar 必须可加载并通过人工负向 fixture 证明 `RequiresOptIn` 类规则真实执行；只“存在于 classpath”不算完成。
- 任一候选 archive 或 APK 出现 native、Google 或未授权 artifact，立即完整回退并 STOP。

## 修改前测试矩阵

| 类别 | 正常路径 | 边界值 | 非法/异常路径 | 回归路径 |
|---|---|---|---|---|
| 全图 | 每个 canBeResolved configuration 等于冻结 F allowlist | 空配置、仅 constraints、变体专属 artifact | 多/少坐标、版本漂移、未知 configuration | lockfile 与真实解析双向覆盖 |
| 原子组 | Runtime/Lifecycle/SavedState 全组精确版本 | Android/KMP 与新增 variants | 混版、缺 artifact、额外 LiveData/runtime-retain | BOM/UI/material/activity/core 不漂移 |
| 普通传递 | 三项 delta 精确 | Annotation Experimental 仅在需要的配置出现 | 1.4.0 残留、直接声明/alias、第四项变化 | `annotation:1.9.1` 保持 |
| 策略 | 无 force/strict/resolutionStrategy/新增 exclusion | 保留既有 DataStore JVM variant exclusion | dynamic/SNAPSHOT/Google/native/graphics-path | strict lock 与 verification metadata 离线重放 |
| Lifecycle | registry/process lifecycle 既有行为 | API 26/36、DESTROYED 终态 | DESTROYED 后恢复、非法状态推进 | AI lifecycle binding 取消/解绑语义不变 |
| SavedState | Bundle round-trip、Unicode、一次消费 | 空状态、缺 key、整数边界 | 重复 provider、晚 restore、畸形字段 fail closed | schema 与 T44/T45 行为不变 |
| Lint | debug/release analyze 均真实执行两个 coordinator | Runtime/Lifecycle/Annotation detector 同时加载 | metadata/ABI/detector crash | warningsAsErrors、abortOnError、checkReleaseBuilds 与规则集保持 |
| Android | 223+ JVM、detekt/ktlint/assemble/generated/boundary | API 26/36 | signing/lock/no-Google/no-native 负向门禁 | APK/archive 无 native，生产代码不使用新 API |
| Windows | 171 offscreen、imports、check、PyInstaller、隔离双实例 | frozen primary/secondary | exe 占用则记录 PID并 STOP | `dist/data` 文件/大小/时间/哈希不变 |
| 提交 | 精确路径 staged 与 checkpoint | 未跟踪文件逐项归属 | secret/generated/T46 命中即拒绝 | 不 push、不继续后续任务 |

## 实施步骤

1. 冻结 branch/HEAD/status/diff/untracked/staged，确认 E 生产、测试、lock、verification 已撤回。
2. 完成所有 canBeResolved configuration 的旧图清单与旧图→F 逐配置 delta；在清单审查完成前不改生产依赖。
3. 以 `--rerun-tasks lintDebug lintRelease` 复现旧 Runtime 1.6.8 metadata 崩溃，并复验旧图非 lint 门禁。
4. 测试 Agent 先恢复并补强 E 策略/compatibility tests；旧图新增红灯只对应三个原子组和三项普通传递 delta，Annotation Experimental 必须因 1.4.0 而非缺失失败。
5. 主 Agent 审查后，实施 Agent 只恢复最小 catalog/build 依赖；不得直接声明 Annotation Experimental。
6. 仅用 Gradle 官方 `--write-locks` 和 `--write-verification-metadata sha256` 更新 lock/SHA-256。
7. 对全 configuration 运行硬审计；一项偏差即完整回退并 STOP。
8. `--rerun-tasks lintDebug lintRelease`，验证两个 analyze task、两个 coordinator 和三类 lint detector 真执行，质量开关不放宽。
9. 执行 Android 全门禁、generated/boundary、API 26/36 compatibility、负向 lint fixture、archive/APK native 审计。
10. Android 全绿后做 `dist/data` 前摘要、Windows 171/import/check/PyInstaller/隔离 exe、后摘要。
11. 更新任务/progress/AGENTS，审查 diff/secret/generated/staged/T46，配置 Git 代理，仅精确路径暂存并创建 checkpoint commit；不 push并停止在 T44/T45。

## 精确回滚

触发 STOP 时仅用逐行补丁撤回 F 的 catalog/build/策略/compatibility test 修改；用 Gradle 官方 writer 恢复旧 lock 与 verification metadata，再只移除 writer 追加且旧图不解析的候选校验块，不手工编造或改写 SHA-256。不得 reset/clean/checkout/stash、覆盖整个 lock/metadata、删除用户未跟踪文件或触碰 T46。复查无 `1.11.3`、`2.9.4`、`1.3.2`、`annotation-experimental:1.4.1` 候选残留，重跑 strict lock、no-Google、no-native、assemble、APK no-native 和旧 dependency policy；保留本任务与 progress STOP 记录，不提交。

## STOP 条件

- 全 configuration 图出现 allowlist 外坐标、版本、选择原因或 configuration 特例。
- Annotation Experimental 需要直接声明；需要 force/strict/exclude/resolutionStrategy。
- Runtime/Lifecycle/Annotation 任一 lint infrastructure crash，或需要关闭、抑制、绕开规则。
- UI/graphics/foundation/material/activity/toolchain/SDK 漂移。
- 出现 graphics-path/runtime-retain/native/Google/dynamic/SNAPSHOT 或额外 Lifecycle/LiveData。
- Lifecycle/SavedState/T44/T45 行为回归，生产代码需要使用新 API。
- T46 被本任务修改或暂存；Windows发布无法闭环；`dist/data` 变化。

触发后完整回退 F 并 STOP，不扩充 allowlist，不转向其他候选。

## 完成定义

- [x] 完整 configuration inventory 与逐配置 delta 写入并严格等于 F allowlist。
- [x] 策略与 compatibility tests 精确先红后绿，Annotation Experimental 仅为传递依赖。
- [x] debug/release lint 真实通过，Runtime/Lifecycle/Annotation detector 真执行且质量规则未放宽。
- [x] Android 完整门禁、API 26/36、negative lint fixture、archive/APK native 审计通过。
- [x] Windows/PyInstaller/exe/`dist/data` 发布闭环通过。
- [x] 文档与 `AGENTS.md` 同步，diff/secret/generated/staged 审查通过，T46 未暂存。
- [x] 创建 `codex/android-architecture` checkpoint commit，不 push，停止在 T44/T45（哈希由最终报告给出）。

## 执行记录

- 2026-08-30：建立 F 任务。branch/HEAD 与授权基线一致，staged 为空；确认 E 的生产、测试、lock 和 verification 修改已撤回，保留 E 任务文档与 progress STOP 记录。
- 旧图 `:app:dependencies` strict offline 成功，证明配置面包含 AGP 内部 KSP、UTP、lint、unit/androidTest、Robolectric、release 与自定义配置；完整精确清单与逐配置 delta 尚在独立只读审计，因此生产依赖门禁仍关闭。
- 完整解析图 Agent 只读确认 85 项审计 universe（84 active + stale lock-only `androidApis`）；仅 9 个 compile/runtime configuration 允许上述冻结 delta，其余 76 项必须零变化。候选 68 个 POM/module 与 23 个 AAR/JAR 扫描无 Google、dynamic/SNAPSHOT 或 native；缓存中的 runtime-retain/额外 LiveData 仅是候选 archive，F lock 禁止出现。
- L1：`--rerun-tasks lintDebug lintRelease` 的 debug/release analyze 均真实执行，并在 `AiCoordinator.kt` 精确复现 Runtime 1.6.8 metadata `2.3.0 / max 2.0.0` 崩溃。
- L2：新增 compatibility 共同契约为 3 methods × API 26/36；首次旧图全量在测试写入窗口捕获旧版专属断言 2 failure，测试 Agent 随即移除 2.9 专属假设，保持不改业务源码。策略 14 项为 8 PASS / 6 expected FAIL，六项只对应三个原子组与三个普通传递 delta。
- L3：catalog/build 仅加入 Runtime `1.11.3`、Lifecycle `2.9.4`、SavedState `1.3.2` 与必要 Runtime/SavedState 直接依赖；Annotation Experimental 无 alias/直接声明。官方 writer `:app:dependencies --write-locks --write-verification-metadata sha256` 成功。
- L4：独立图审计 PASS。lock 仅 `23 removed / 30 added`，恰好只触及 9 项 configuration；其余 75 active + stale `androidApis` 共 76 项逐行不变。实际三原子组与 ProfileInstaller/Core ViewTree/Annotation Experimental delta 严格等于 F allowlist。
- L5：`--rerun-tasks lintDebug lintRelease` 为 55 tasks 全执行、BUILD SUCCESSFUL；debug/release 主 analyze、AndroidTest/UnitTest analyze 均完成。受控临时 Java fixture 令 `UnsafeOptInUsageError from androidx.annotation.experimental` 真实失败，证明 Annotation detector 执行；fixture 随后删除且最终 lint 重新通过。质量开关和既有 disabled 更新提示规则保持不变，无 baseline/源码排除。
- L6：完整 Android 命令 97 tasks 成功，当前 JVM 229 项通过；generated/boundary 各 44/44；Lifecycle/SavedState compatibility 6/6；70 个候选 AAR/JAR/APK ZIP 内容扫描无 `jni/`、`lib/`、`.so/.dll/.dylib`。
- L7：Windows 171/171、全模块导入、`build.py --check`、完整 PyInstaller 与两种隔离双实例冒烟通过。`dist/Clender.exe` 为 45,581,912 bytes，SHA-256 `53AE16C0E34BA42B63AACB8C8BE2F950E1827C34051272C0847FD7C1EBE38ECC`；`dist/data` 前后均为 5 文件、195,620 bytes、摘要 `2BB907BD17FD9981D5CEAB67BEDEB226305051D7BB2092D3A908212286983849`，最终 0 Clender 进程且隔离目录已删除。
