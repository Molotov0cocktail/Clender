# T44–T45 Android Runtime/Lifecycle/SavedState 最小一致依赖集

## 最终状态

**STOP-ESCALATE（2026-08-30）**。E 候选图在 `debugAndroidTestCompileClasspath` 将 `androidx.annotation:annotation-experimental` 从冻结旧值 `1.4.0` 解析为 `1.4.1`；该变化不属于修改前 allowlist，命中“出现 allowlist 外版本漂移”的强制 STOP 条件。未进入 L5 lint，未执行 Android/Windows 发布闭环，不提交。

## 目标

实施已授权路径 E：在保留 Compose BOM `2024.08.00` 与既有 Compose UI 图形栈的前提下，将 `androidx.compose.runtime` 对齐到 `1.11.3`、`androidx.lifecycle` 对齐到 `2.9.4`、`androidx.savedstate` 对齐到 `1.3.2`，消除 Runtime `1.6.8` lint 对 Kotlin metadata `2.3.0` 的读取崩溃，并使 T44/T45 恢复现场通过完整 Android 与 Windows 发布门禁。

## 非目标

- 不升级 Kotlin `2.3.21`、KSP `2.3.9`、AGP `8.13.2`、Gradle `8.13`、API 26–36 或 Compose BOM。
- 不升级 Compose UI/ui-graphics/foundation/material `1.6.8`、Material3 `1.2.1` 或 Activity Compose `1.9.2`。
- 不使用 force、strictly、resolutionStrategy 或新增 exclusion 绕过 producer metadata；保留且仅允许现有 DataStore JVM variant exclusion。
- 不接受 `graphics-path`、Google Play services、native runtime、动态/SNAPSHOT 依赖。
- 不禁用/抑制 lint issue，不添加 baseline、源码排除或伪造 metadata。
- 不修改 T44/T45 业务行为，不继续、修改、暂存或提交 T46/T47/T48，不连接真实 WebDAV/AI，不 push。

## 选定路径 E

Runtime `1.11.3` 的发布 metadata 硬性要求 Lifecycle `2.9.4` 与 SavedState `1.3.2`。路径 D 已证明只升级 Runtime 会产生未授权组漂移；路径 E 将这三个组作为最小一致运行时集合原子授权，但不允许在实际图出现第四个未预先列明的版本决策。

## 升级前后 resolved dependency delta allowlist

### 直接授权组与原子 artifact

| 分类 | Artifact | 旧图 | E 目标图 |
|---|---|---:|---:|
| Runtime 原子组 | `runtime`, `runtime-android`, `runtime-saveable`, `runtime-saveable-android` | 1.6.8 | 1.11.3 |
| Runtime 原子组新增 | `runtime-annotation`, `runtime-annotation-android` | 无 | 1.11.3 |
| Lifecycle 原子组 | `common`, `common-jvm`, `common-java8`, `livedata-core`, `process`, `runtime`, `runtime-android`, `runtime-compose`, `runtime-compose-android`, `runtime-ktx`, `runtime-ktx-android`, `viewmodel`, `viewmodel-android`, `viewmodel-ktx`, `viewmodel-savedstate` | 2.8.4 | 2.9.4 |
| Lifecycle 原子组新增 | `lifecycle-viewmodel-savedstate-android` | 无 | 2.9.4 |
| SavedState 原子组 | `savedstate`, `savedstate-ktx` | 1.2.1 | 1.3.2 |
| SavedState 原子组新增 | `savedstate-android`, `savedstate-compose`, `savedstate-compose-android` | 无 | 1.3.2 |

### 普通传递 allowlist

| Artifact | 旧图 | E 目标图 | Producer 依据 |
|---|---:|---:|---|
| `androidx.profileinstaller:profileinstaller` | 1.3.1 | 1.4.0 | Lifecycle 2.9.4 runtime metadata |
| `androidx.core:core-viewtree` | 无 | 1.0.0 | Lifecycle/SavedState runtime metadata |

除这两项外，不允许普通传递版本或坐标变化。特别保持：Core/Core KTX `1.13.1`、Annotation `1.9.1`、Annotation Experimental `1.5.0`、Collection `1.5.0`、Concurrent Futures `1.1.0`、Startup Runtime 的 debug/release `1.2.0`、Tracing debug/release `1.0.0` 与 unit `1.1.0`、Coroutines `1.9.0`、Serialization `1.11.0`、Kotlin stdlib `2.3.21`、Activity `1.9.2`、BOM/UI/graphics/foundation/material 旧值。

不得进入实际图：`runtime-retain(-android)`、额外 Lifecycle LiveData artifact、`graphics-path` 或其他 `androidx.graphics` native artifact。只存在于发布 constraints、缓存或旧 C3 图的模块不构成 allowlist。

## 影响文件

- 生产依赖：`android/gradle/libs.versions.toml`、`android/app/build.gradle.kts`。
- 解析与校验：`android/app/gradle.lockfile`、`android/gradle/verification-metadata.xml`。
- 策略：`android/build.gradle.kts`、`android/tests/policy/test_dependency_policy.py` 和冻结的 E 目标图 allowlist。
- 回归测试：新增非 T46 的 Lifecycle/SavedState Robolectric compatibility tests。
- 文档：本任务、既有 lint/recovery/T44/T45/progress 与最终 `AGENTS.md`。

明确排除：`doc/tasks/T46-android-compose-ui.md`、`android/app/src/**/ui/**` 及其测试。

## 接口、运行时、SDK 与数据影响

- 不改变 T44/T45 接口、WebDAV schema v1、Room v1、AI 操作或持久化 schema；不读取、迁移或写入真实 Android/Windows 数据。
- Production Lifecycle 使用仅为 `AiProcessLifecycleBinding` 的 `DefaultLifecycleObserver.onStart/onStop`；没有 LiveData、SavedStateHandle、SavedStateRegistry 或 production `currentState`。SavedState 1.3 继续以 Android `Bundle` 为底层表示，自有 `AppSavedStateCodec` 不属于 AndroidX registry。
- Runtime/Lifecycle/SavedState/ProfileInstaller/Core ViewTree 候选 manifest minSdk 均不高于 23；AAR minCompileSdk 最高 35，最低 AGP 最高 8.6.0。当前 minSdk 26、compileSdk 36、AGP 8.13.2 满足，无需工具链升级。
- 不使用三个新版本组的新 API改变产品行为；行为兼容只由现有入口与新增测试证明。

## Lifecycle/SavedState 行为变化与 lint 风险

- Lifecycle 2.9 将 `DESTROYED` 作为终态；从 `DESTROYED` 恢复必须抛 `IllegalStateException`。Process lifecycle 前后台 start/stop 与 registry 正常推进必须回归。
- Lifecycle 2.9 的空 Bundle provider、SavedStateHandle 新 API变化当前无直接 production 使用，但不得假设无影响。
- SavedState 1.3 将 KTX API移入 base，并引入 `savedstate-compose`；Android `Bundle` 二进制表示保持兼容，仍需验证 round-trip、空状态、缺 key、Unicode、重复 provider 与非法恢复。
- Runtime 1.11.3 lint 不再内嵌旧 Kotlin metadata reader，且仍保留 Compose StateFlow/Coroutine rules。
- Lifecycle 2.9.4 lint 存在已知 `KaCallableMemberCall` class→interface 二进制风险；本机 AGP lint 31.13.2 字节码签名与 detector 对齐，但必须由真实 lint 证明。
- `lifecycle-runtime-compose:2.9.4` lint 仍内嵌支持到 metadata `2.1.0` 的 relocated reader；production 当前没有 `Lifecycle.currentState` 调用，不能据此豁免，任何 metadata 2.3.0 崩溃都触发 STOP。

## 修改前测试矩阵

| 类别 | 正常路径 | 边界值 | 非法/异常路径 | 回归路径 |
|---|---|---|---|---|
| 解析图 | debug/release compile/runtime 与冻结 E allowlist 完全一致 | 原子组新增 Android/KMP artifact | 额外/缺失/版本漂移、forced selection、动态/SNAPSHOT | BOM/UI/foundation/material/material3/activity 不漂移 |
| 依赖策略 | 三授权组精确版本，两个传递 delta 精确 | 现有 DataStore exclusion 唯一允许 | 拒绝 force/strictly/resolutionStrategy/新增 exclusion | strict lock 与 verification metadata 离线重放 |
| Native/Google | 全部候选 AAR/JAR/APK 无 `.so` | `jni/`、`lib/`、多 ABI、zip 嵌套 | 拒绝 graphics-path、未知 native、Google group | 既有 no-native/no-Google/APK task 保持有效 |
| Lifecycle | registry create/start/resume/退回/destroy 顺序；ProcessLifecycleOwner 前后台 | API 26/36、约 700ms stop 延迟 | DESTROYED 后恢复抛异常 | 既有 AI lifecycle binding 取消一次且解绑后不观察 |
| SavedState | Bundle round-trip、Unicode、一次消费 | 空状态、空串、整数边界、缺 key | 重复 provider、晚 restore、畸形/不兼容字段 fail closed | 不新增 schema、不触碰 T46 saved state 文件 |
| Lint | `lintDebug`/`lintRelease --rerun-tasks` 完整分析两个 coordinator | Runtime 与 Lifecycle detectors 同时加载 | metadata/KaCallableMemberCall infrastructure crash | 全部 Compose/Lifecycle rules 与三质量开关保持 |
| Android | T44/T45/Room 聚焦、223 JVM、detekt/ktlint/assemble | API 26/36 | signing/lock/generated/boundary 失败即停 | 无 Google/native、APK 审计 |
| Windows | 171 offscreen unittest、导入、check、完整 PyInstaller、隔离双实例 | frozen primary/secondary | exe 被占用时记录 PID并 STOP | `dist/data` 文件/大小/时间/哈希摘要不变 |
| 提交 | 精确路径暂存 T44/T45 checkpoint 与 E 路径 | 未跟踪文件逐项归属 | 拒绝 T46、秘密、数据、日志、数据库、APK/AAB/exe/cache/keystore | staged diff、敏感扫描、`git diff --check` |

## 实施步骤

1. 冻结 branch/HEAD/status/full diff/untracked/staged/`git diff --check`，确认 D 无残留。
2. 用 `--rerun-tasks` 复现旧 debug/release lint metadata 崩溃；复现旧图非 lint 门禁。
3. 先增加真实 resolved-graph 策略门禁和 Lifecycle/SavedState compatibility tests，旧图新增失败只允许对应 E 版本缺失。
4. 实现 Agent 只修改授权的 catalog/build/lock/verification 文件；catalog 显式钉扎 Runtime `1.11.3`、Lifecycle `2.9.4`、SavedState `1.3.2`，只添加有业务/metadata必要的直接声明。
5. 使用 Gradle 官方 `--write-locks` 与 `--write-verification-metadata sha256`，不手工编造校验值。
6. 对四个 debug/release compile/runtime classpath 做 dependencyInsight 和冻结 allowlist 比较；审计 forced/strict/exclusion、native、Google、dynamic、APK 预解析图。
7. 强制重跑 debug/release lint，确认 Runtime 旧 reader 未加载、Lifecycle lint 与 AGP 兼容、两个 coordinator 完成分析且质量规则未放宽。
8. 运行 Android 完整门禁、generated/boundary 和所有候选 archive/APK 内容审计。
9. Android 全绿后执行 Windows 171 项、导入、`build.py --check`、完整 PyInstaller、隔离 exe primary/secondary 与 `dist/data` 前后摘要。
10. 更新文档与 `AGENTS.md`，路径限定暂存并审查 secret/generated/T46，创建普通 checkpoint commit，不 push，然后停止。

## 精确回滚

若触发 STOP，仅用逐行补丁撤回本任务的 catalog/build/策略/compatibility test 修改；用 Gradle 官方写锁与 verification metadata 命令恢复旧图，再只删除官方 writer 追加且旧图不再解析的候选校验块，不编造校验值。不得 reset/clean/checkout/stash、覆盖整 lock/metadata 或触碰既有 T44/T45/T46 文件。复查无 `1.11.3`、`2.9.4`、`1.3.2`、相关 alias/新增 allowlist/候选 metadata 残留，重跑 strict lock、no-native、APK no-native 与旧 dependency policy。失败只保留本任务与 progress STOP 记录，不提交。

## STOP 条件

- 实际图出现 allowlist 外变化或需要第四个直接版本决策。
- 需要 force、strictly、resolutionStrategy 或新增 exclusion。
- Runtime/Lifecycle lint 出现 metadata、KaCallableMemberCall 或其他 infrastructure crash。
- BOM/UI/graphics/foundation/material/material3/activity/toolchain/SDK 漂移。
- 出现 graphics-path/native/Google/dynamic/SNAPSHOT。
- Lifecycle/SavedState、T44/T45 行为测试回归，或需要使用新 API修复。
- T46 被修改/暂存；Windows发布不能闭环；`dist/data` 变化。

触发后完整回退 E，不尝试 A/B/C/D 或第四组。

## 完成定义

- [ ] 冻结 E allowlist 与真实四 classpath 完全一致，三授权组和两个普通传递 delta 精确，无其他漂移。
- [ ] 策略与行为测试先红后绿，Lifecycle/SavedState API 26/36 兼容。
- [ ] `lintDebug`、`lintRelease` 真实执行并在全部规则、质量开关保持时通过。
- [ ] Android 全量 unit/static/lock/signing/no-Google/no-native/assemble/APK/generated/boundary 通过。
- [ ] Windows unittest/import/check/PyInstaller/exe/`dist/data` 全部通过。
- [ ] 文档、`AGENTS.md`、diff/secret/generated/staged 审查通过，T46 未暂存。
- [ ] 在 `codex/android-architecture` 创建普通 checkpoint commit，不 push，并停止在 T44/T45。

## 执行记录

- 2026-08-30：E 任务建立。基线 branch `codex/android-architecture`、HEAD `f14178792dea2363ddba1c8ead3732ce2f48403a`、staged 空；D 版本/graphics/BOM/runtime-lint 残留扫描为空，既有依赖文件 diff 形状与 D 前一致。
- L1：`--rerun-tasks lintDebug lintRelease` 的 debug/release analyze 均真实执行，并在 `AiCoordinator.kt` 复现 metadata `2.3.0` / maximum `2.0.0` 崩溃；旧图非 lint 门禁成功。
- L2：新增 resolved-graph 策略门禁按预期仅对 E 目标版本红灯；既有策略 36/36、Android JVM 235/235、detekt、ktlint 通过。Lifecycle/SavedState compatibility 用例先在旧图通过，未用于扩大授权。
- L3：catalog 显式声明 Runtime `1.11.3`、Lifecycle `2.9.4`、SavedState `1.3.2`；官方 Gradle writer 更新 lock/SHA-256。debug/release compile/runtime 图符合三组原子 artifact 与 ProfileInstaller/Core ViewTree allowlist。
- L4 STOP 证据：主 Agent 复核完整 lockfile 发现 `debugAndroidTestCompileClasspath` 的 `annotation-experimental:1.4.0 → 1.4.1`。独立 `dependencyInsight` 证明 `runtime-android:1.11.3` 请求 `1.4.1`，不是 writer 噪声。该第四个未规划版本变化不可在实施后补入 allowlist，因此未运行 E 图 lint。
- 回退：逐行撤销 E catalog/build、resolved-graph 策略与 compatibility tests；用官方 `:app:dependencies --write-locks --write-verification-metadata sha256` 恢复旧图，并移除仅由 E writer 追加的候选/临时校验块。Runtime/Lifecycle/SavedState 恢复 `1.6.8`/`2.8.4`/`1.2.1`，生产依赖 diff 形状恢复到 E 前现场，T46 未触碰、staged 为空。
- 回退复验：受影响文件 diff 形状恢复为 `app build 10/0`、`lock 30/11`、`catalog 11/0`、`verification 155/0`、`dependency policy 20/0`；`verifyNoGoogleServices`、`verifyNoNativeRuntimeArtifacts`、`verifyResolvedVersionsLocked`、`assembleDebug`、`verifyDebugApkNoNativeArtifacts` strict offline 全部通过，既有 dependency policy 4/4 通过。首次清理曾误删旧图仍需的 Startup Runtime 1.2.0 校验块，strict verification 按设计失败；随后仅采用官方 writer 生成的 SHA-256 恢复该块，移除 writer 对未选中 Okio 3.9.1 module 的临时追加，复验通过且形状精确恢复。
- 未执行：E 图 lint、Android L6 全量、Windows/PyInstaller/exe/`dist/data`。原因是 L4 已触发强制 STOP；按授权不得继续执行后续阶段。未提交、未 push、未继续 T46/T47/T48。
