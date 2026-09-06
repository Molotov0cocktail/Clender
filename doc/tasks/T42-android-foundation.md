# T42：Android 隔离工具链、Gradle 工程与安全基线

## 目标

创建可复现、与 Windows 完全隔离的 Kotlin/Compose Android 工程，锁定工具链/依赖，建立测试、静态分析、签名注入、CI 和数据/秘密防污染门禁。

## 非目标

- 不实现事件、AI、WebDAV、业务 UI 或 Widget。
- 不提交 JDK/SDK/cache/keystore/APK/AAB。

## 输入文档

- Android proposal/high-level/detailed design
- T41、`AGENTS.md`

## 影响文件

- `android/` 下 Gradle、wrapper、version catalog、manifest、基础 Application/Activity、测试 harness、脚本、README、子级 AGENTS、`.gitignore`
- `.github/workflows/android.yml`

## 接口/数据影响

- 建立 `com.molotov.clender`、API 26–36、Java 17 和 app-scoped dependency container。
- 运行数据仅在 Android sandbox；工具链定向到忽略目录。

## 风险

- AGP/Kotlin/Compose/SDK 版本不兼容；下载大、网络失败；Gradle cache 或签名路径误提交。

## 实施步骤

- [x] 核对官方当前稳定版本与兼容矩阵，研究候选锁定为 AGP 8.13.2/Gradle 8.13/JDK 17/Kotlin 2.3.21/API 36；真实解析构建后才标记已验证。
- [x] 先写 build policy/manifest/security 测试，使空工程对要求产生失败/缺失证据。
- [x] 在隔离路径获取 JDK 17、测试用 JDK 21、SDK 36、build-tools 与 Gradle wrapper；本机另装的 platform-tools/emulator 为滚动观测值，不冒充可复现锁，系统镜像与设备工具复验留 T48。
- [x] 建立 Kotlin DSL、version catalog、dependency verification、dependency locking、lint/detekt/ktlint。
- [x] 建立最小 Compose Activity、依赖装配和无业务数据的“日历”占位，不写示例数据。
- [x] 建立 release signing 环境契约与严格忽略，不生成 key（留 T48）。
- [x] 新增 Android CI；保持 Windows workflow 不变。
- [x] 环境脚本隔离 JAVA/SDK/Gradle/Android/Emulator/AVD/TEMP homes；工具链下载包记录 URL、版本和 SHA-256。

## 测试与检查

```powershell
& 'C:\Users\30910\Miniconda3\python.exe' .\android\scripts\bootstrap-toolchain.py --verify-only
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\android\scripts\gradle.ps1 --offline --no-daemon --dependency-verification strict testDebugUnitTest lintDebug lintRelease detekt ktlintCheck verifyReleaseSigningPolicy verifyNoGoogleServices verifyNoNativeRuntimeArtifacts verifyResolvedVersionsLocked assembleDebug verifyDebugApkNoNativeArtifacts
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\android\scripts\verify-generated.ps1 -PythonExecutable 'C:\Users\30910\Miniconda3\python.exe'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\android\scripts\verify-boundaries.ps1 -PythonExecutable 'C:\Users\30910\Miniconda3\python.exe'
```

覆盖正常构建、缺 SDK/JDK、非法/缺签名但 lintRelease 可运行、产物任务无密钥必败且不回退 debug、debug/release merged manifest、无 cleartext、禁用 backup/device transfer、foundation 组件/权限/exported 精确 allowlist、无应用自有前台服务/周期网络、无 Google services/native 依赖、ignore、工具包校验与全部 home 路径隔离；后续引入 WorkManager/Widget 时必须逐组件扩展黄金表。

## 回滚方式

删除 `android/` 与新增 Android workflow；不会触碰 Windows 源码或数据。

## 完成定义

- [x] wrapper/依赖可复现且校验开启。
- [x] 最小 debug 构建、测试和静态检查通过。
- [x] release 缺签名时安全失败。
- [x] Git 状态无工具链、cache、秘密或生成物。
- [x] 按根门禁完成 Windows 全量/构建/exe/`dist/data` 核验并更新文档；聚焦提交由本任务最终门禁创建。

## 当前红灯证据

- 第一阶段只新增 Python 标准库策略测试和入口，不含任何 Gradle/Manifest/生产实现。
- `C:\Users\30910\Miniconda3\python.exe android/scripts/verify-foundation.py`：27 tests，44 个预期 failure，0 error、0 skip；失败覆盖缺少 scaffold、锁定版本、wrapper/verification metadata、manifest/backup/exported/权限、安全签名、CI、ignore、工具链隔离、无 Google/native 审计任务。

## 实施与验证结果

- 隔离工具链：Microsoft OpenJDK `17.0.20+8`；Robolectric API 36 测试用 Microsoft OpenJDK `21.0.12+8`；Android command-line tools `22.0`、platform `36` revision 2/extension 17、Build Tools `36.0.0`；Gradle `8.13`。四个可寻址下载归档的 URL/SHA-256 记录在 `android/toolchain.lock.toml` 并由 bootstrap 消费。本机观测到 Platform Tools `37.0.1`、Emulator `37.1.11`，但其 sdkmanager 坐标为滚动 latest，明确排除在 T42 可复现声明外，T48 重新获取并记录设备证据。
- 构建锁定：AGP `8.13.2`、Kotlin/Compose compiler `2.3.21`、Compose BOM `2024.08.00`（UI `1.6.8`）、Activity Compose `1.9.2`、Lifecycle `2.8.4`、Core KTX `1.13.1`、Robolectric `4.16`、detekt `1.23.8`、ktlint plugin/core `14.2.0/1.8.0`。较新的 Compose UI 会传递引入含 native artifact 的 `graphics-path`，与纯 JVM/无 `.so` 契约冲突，因此以实际解析和 APK 审计通过的稳定组合为准。
- Robolectric API 26/36 framework JAR 被 Gradle 校验、锁定并复制到忽略的 build 目录，以官方 offline resolver 模式执行；使用 JDK 21 仅是为了运行 API 36 测试，生产 Java/Kotlin bytecode 仍为 17。
- 首轮离线 framework 因两个版本位于同一 configuration 被 Gradle 冲突解析为 API 36，API 26 两项失败；拆成两个 configuration 后 4/4 通过。Emulator 首次下载在解压时损坏，精确删除未完成目录后重试成功。
- 独立审计后将 dependency locking 提升为 `LockMode.STRICT`，CI 固定 Python/Actions/工具归档，keystore 限制在 Android 内受忽略路径，生成 Manifest 由正式脚本刷新，网络仅信任 system CA，生产 runtime 与最终 APK 均扫描所有 native 后缀。
- 最终设备外 Gradle 门禁：93 tasks，`BUILD SUCCESSFUL`，使用 `--offline --dependency-verification strict --write-locks`；Python source/generated-manifest/CI/边界策略 33/33；bootstrap `--verify-only` 与边界脚本通过。
- 无密钥 `assembleRelease` 按预期非零失败，唯一签名诊断为 `Release signing credentials are required`；`lintRelease` 在无密钥条件下成功。debug APK 7,597,105 bytes，SHA-256 `58178044AD1B8393BB83D5E1DD4178EB3194C67F92B49726AF60B1D30F330B47`，包名 `com.molotov.clender.debug`、min/target/compile `26/36/36`，且 ZIP 内无 `lib/**/*.so`。
- Windows 回归：171/171 unittest、全模块导入、`build.py --check`、完整 PyInstaller 与隔离 silent primary/secondary 冒烟通过；最终 `dist/Clender.exe` 45,582,900 bytes、SHA-256 `99FBA2D3626941DCB155F8ED49D29893D69B2F2841E15A23E674EA353F0BFF21`。`dist/data` 构建/冒烟前后保持 5 文件、96,806 bytes、只读摘要 `CF48B8F7434242BF94998DE22985FBF73AC9398EBBE1ED3A27EE32A5EE898688`。
