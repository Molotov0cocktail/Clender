# Clender Android

## 当前交付：T64 背景、日历与导航整合

以 `main/773c2dc` 为基础，保留「设置 → 应用 → 自定义背景」及图标，整合排程修复、日历优化和新左侧导航。APK：`app/build/outputs/apk/release/app-release.apk`，1,791,085 bytes，SHA256 `f1cce5fb4ded4943d6ab2a0191fe72474323d6623fac087734ce23377b031166`。AAB SHA256 `4081e66de4a51f64a40f00c623a12801db8efa724880c430eb1817bfb6ff596e`。沿用原签名，无数据迁移。

1806项应用测试、111发布夹具、74策略和完整门禁通过；API26/36最终包各26组交互/截图验收通过。PC原样保持，不修改测试构建。已知API36浅色系统状态栏对比问题沿用旧版；未做厂商真机/Widget/本轮真实Provider验收。详见 `../doc/tasks/T64-background-calendar-navigation-integration.md`，下方旧包记录仅为历史。


## 自定义背景与外观（T62，2026-09-06）

最终2026-09-07构建：APK `app/build/outputs/apk/release/app-release.apk`，1,757,329 bytes，SHA256 `936a58ca92b678a8d05723d54b62604256612cd90b3d57a857235f71a24afbc5`。同源码1731项应用测试、111发布夹具、65策略及完整离线门禁通过；签名APK/AAB审计通过。透明页面根层统一继承主题文字色，明暗切换有实际渲染颜色回归。当前设备验收记录见根任务T60；下面各历史任务的摘要不代表本包。

在「设置 → 应用 → 自定义背景」选择本地图片，图片会复制到应用私有存储，背景不会上传或参加WebDAV同步。选图、移除及背景可见度调整立即保存；已有主题、字号、AI与同步设置仍使用原来的保存按钮。

背景可见度支持0–100；应用会随日间/夜间模式自动添加保底蒙板，让图层可见并保持文字清晰。图片采用填满裁剪，照片方向按EXIF修正；损坏、失效或超大图片会回退主题底色或显示可重试错误。应用只通过系统选择器取得所选图片，不申请广泛媒体读取权限。处理在后台完成，旋转屏幕保留进行中的选择与busy状态。

当前验证与限制记录见 `../doc/tasks/T62-android-appearance.md`；下文旧任务冻结/包摘要仅代表各历史交付，不代表本轮新包。

## T51 Android 本地时间修复（2026-09-06，完成）

任务 `doc/tasks/T51-android-local-time-bugfix.md` 接续8f606fd。AI上下文原将UTC当作本地时间；本轮按每次请求的系统时区将同一instant转换为本地日期/分钟/星期，运行中时区变化下次生效。共享UTC metadata与事项墙钟字段不变，不迁移数据；默认生产两参数provider读取动态系统zone，测试可显式注入zone以保持固定时钟确定性。

维护记录：先新增旧实现可编译失败回归，覆盖UTC+8/跨日/DST/动态时区/UTC消息元数据；不使用上一任务已结束的临时Key。按用户本次明确要求跳过Windows EXE、Windows测试和dist/data读取；该任务级例外不取消Android测试、签名、索引审查与提交门禁。旧实现16tests/6RED已留证，修复后44聚焦及同源码完整160suites/1694tests（UI60/723）通过，零失败/跳过/四类泄漏；96项构建门禁、111发布夹具、62策略与签名审计通过。最终APK SHA256 `82e4e504b8dfd5bd62d4ba22f103874402b872ebe646997f23469b402c7b7e09`。本轮无新设备或云端实测，验证直接覆盖生产默认时间路径及实际请求上下文。

签名APK：`app/build/outputs/apk/release/app-release.apk`（1726606 bytes），可覆盖安装。AAB SHA256 `43e3a9af9ba526de89246d0ae677a5c524cfcb4e94838bc0719024315cda549f`；完整记录见 `../doc/tasks/T51-android-local-time-bugfix.md`。

## T50 AI 会话修复（2026-09-06，完成）

手机 AI 页通过右上角列表图标打开会话列表，返回按钮回到消息；消息区不再叠放入口。明确的版本地址（例如 `https://open.bigmodel.cn/api/paas/v4`）会直接拼接 `chat/completions`，不再错误追加 `/v1`。根地址及无版本代理地址仍沿用原 `/v1` 默认规则。

本轮继续沿用 T49 的加密 Key 保存方案和原签名，无数据库迁移，可覆盖安装。标题采用单行省略，避免极大字号换行裁切。最终APK `app/build/outputs/apk/release/app-release.apk`，1726466 bytes，SHA-256 `31d027917662adf8108c08769827d09df7ada0b45e4148b0ee9e8a8dab7d27f5`；AAB SHA-256 `51a958da08edf5444cdb8c79b1b06e5a96f6919fd9c08cfaef00e784193dc0da`。最终UI723/723与完整构建门禁通过；API26/36英文极大字号双主题模拟器验收通过，中文设备未完成但中英文自动化标题回归通过。完整测试、签名产物与设备实测见 `../doc/tasks/T50-ai-conversation-bugfix.md`；下方 T49 包摘要为历史。

## T49 日历与 API Key 修复（2026-09-06）

日历空态/加载提示已与星期、日期和时间轴分开布局。API Key 保存继续使用 Android Keystore 加密，修正了随机 IV 初始化；未保存的输入在设置页签间保留，保存成功后清空输入并显示“API Key 已配置”。已保存 Key 不回填明文，空输入配合保存使用 KEEP；主动移除仍须确认。

修复包与旧版使用同一签名，可直接覆盖安装，无数据库迁移。APK：`app/build/outputs/apk/release/app-release.apk`，1725038 bytes，SHA-256 `02d29be8879efe509aa624506091cf69599bb4531234e026d0311d3f2011a799`。AAB：`app/build/outputs/bundle/release/app-release.aab`，SHA-256 `58a3acdf24927ad426bf11707771fdf1af76e343e20a868fcd08d8b74e844514`。签名构建/产物审计、46项专用回归、1657项全量测试及完整门禁分项通过，API26/36设备验收已完成；当前结果见 `../doc/tasks/T49-android-layout-api-key-bugfix.md`。

Windows初期按用户选择暂缓；实例自然退出后，198/198、30imports/check、完整PyInstaller和20场景正式smoke通过，dist/data 5files/198100bytes前后完全一致。原环境失败保留于任务记录。实际设备为隔离AOSP模拟器；loopback请求验证了解密使用，没有连接真实AI服务。下方T47/T48记录属于历史交付。

## 2026-09-06 当前完成状态与 Git 交付边界

T47/T48 的实现、构建与验收已完成。最终同源码 JVM 155 suites/1611 tests、UI 57 suites/665 tests，failure/error/skip 及四类泄漏标记均为 0；完整 verify-all、签名审计与设备验收全部完成。Git 交付为最后步骤，提交/推送回执以主 Agent 最终答复与 Git 日志为准；本记录不宣称已经 commit 或 push。下文阶段性的“仍进行中”“待设备”“未完成”及旧包摘要均保留为历史过程，当前状态以本节为准。

最终验收 APK SHA-256：`5d90cbb3d271063ad7b9c3645eb94ede9180bde62c46bde109a09cc0c56a897e`。API26/36 phone 与 API36 tablet 已完成最终包 core 8 步及实际 Widget；API36 phone/tablet 的最终 Drawer 18 步通过。所有 8 台设备的最终包安装与 hash 核验均通过。

增量证据复用保持原事实：API29/31/33 phone 的 core 复用 4cf 候选、Drawer 复用 04 候选；API35 phone 的 core 复用 4cf、Drawer 18 步使用最终包；API33 tablet 的 core 复用 c287、Drawer 18 步使用最终包。date/boot 继续复用 c287 证据。这是已完成的增量验收矩阵，不表述为所有场景都在最终包重新执行。

API26 Widget 原 ADB 清理临时 XML 失败记录保留；`completion-widget-api26-r4-resume-result.json` 为 PASS，恢复时只读确认配置已保存，再续跑自动更新/冷启动。此续跑不抹除原失败。历史 Room teardown 的 NON-REPRODUCIBLE 裁决不变，后续全绿不等于根因修复。

独立签名 key 与配置保持忽略，须由用户安全备份，不输出秘密。最终 Git 交付仍按已授权的统一集成提交与 Gitee `codex/android-architecture` 分支执行；不合并远程 main、不推 GitHub。本次仅同步原六份文档，完成即冻结，不测试、不修改生产、不提交。

This isolated native Android project targets API 26–36 and does not read or write the Windows application's `data/` or `dist/data/` directories.

## Local environment

All JDK, Android SDK, Gradle, emulator state, temporary files and release signing inputs live under ignored paths in this directory. From a clean Windows checkout, use the repository's required Python 3.12.4 interpreter to install the checksum-locked build toolchain:

```powershell
$ClenderPython = $env:CLENDER_ANDROID_PYTHON
if ([string]::IsNullOrWhiteSpace($ClenderPython)) { throw 'Set CLENDER_ANDROID_PYTHON to the approved Python 3.12.4 executable.' }
& $ClenderPython -c "import sys; assert sys.version_info[:3] == (3, 12, 4)"
& $ClenderPython .\scripts\bootstrap-toolchain.py
```

The verified foundation baseline is JDK 17.0.20+8 for production compilation, JDK 21.0.12+8 only for Robolectric API 36 workers, Gradle 8.13, AGP 8.13.2, Kotlin 2.3.21 and Android API 36/Build Tools 36.0.0. Exact bootstrap archive hashes and reproducible SDK revisions are recorded in `toolchain.lock.toml`. Platform Tools and Emulator use rolling SDK Manager package names, so their locally observed revisions are not claimed as reproducible; T48 records the exact versions used for the final device matrix.

Run the device-free gates from `android/` after the isolated cache is prepared:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-all.ps1 -PythonExecutable $ClenderPython
```

This uses strict dependency verification, offline resolution and one worker. Toolchain bootstrap is a separate setup operation, not part of final verification.

Release credentials are read from four `CLENDER_ANDROID_*` environment variables or ignored `keystore.properties`. Missing credentials intentionally make release packaging fail; they never fall back to the debug key.

The application uses AndroidX/Jetpack but no Google Play services, Firebase, Play SDK, ML Kit, native/NDK runtime or Google-service account.

## Signed release workflow

The release helpers use the same isolated environment and strict offline dependency verification.
Pass the verified Python executable explicitly; they do not choose another Python installation.

```powershell
# This run already has an independent ignored signing identity. Do not regenerate it.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-all.ps1 -PythonExecutable $ClenderPython
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-release.ps1 -PythonExecutable $ClenderPython
```

Keep both `release/clender-release.jks` and `keystore.properties` in a secure backup outside Git.
They are required to sign future updates with the same identity. The scripts never print passwords or aliases.
A separate new project identity can be created once with `new-release-key.ps1 -PythonExecutable $ClenderPython`; existing key/configuration files are never overwritten.
The build produces `app/build/outputs/apk/release/app-release.apk` and
`app/build/outputs/bundle/release/app-release.aab`; the auditor writes sizes, SHA-256 values,
certificate digest and check results to ignored `release/artifact-metadata.json`.
An audit failure returns a nonzero exit code and must be resolved before delivery.

The standalone bundletool is expected at `.toolchain/bundletool-all.jar`. This run uses the
[official bundletool 1.18.1 release](https://github.com/google/bundletool/releases/tag/1.18.1),
asset `bundletool-all-1.18.1.jar`, with observed SHA-256
`675786493983787FFA11550BDB7C0715679A44E1643F3FF980A529E9C822595C`.
This is a locally measured checksum; it is not presented as an upstream published checksum.

Device checks are separate from artifact checks. `scripts/device-smoke.py` accepts an explicit
serial and an allowlist of AVDs created empty for that run. It audits the APK before installation,
clears only that isolated release package, and exercises actual UI gestures. Never allowlist
a personal emulator. Launcher Widget acceptance must be recorded separately; the script does
not turn missing manual evidence into PASS. See `doc/tasks/T48-android-release.md` in the parent
repository for the required phone/tablet matrix and final evidence.

## 2026-09-06 implementation and verification status

2026-09-06 Drawer 最新接续契约：独立 AppDrawerState.kt 的 rememberSynchronizedDrawerState 由 ClenderApp 接入。snapshotFlow 观察 DrawerState.targetValue，以 Closed 为初始已观察值，忽略初始 Closed emission，避免覆盖 shell 已请求的打开意图；后续 target 变化同步 shell 与 settings 的 drawerOpen。rememberUpdatedState 保持观察回调使用最新 dependencies；请求端仅在 requestedOpen 对应 target 与当前 targetValue 不同时调用 open/close 动画。ClenderAppSettingsNavigation 的 Back 只提交 shell/settings 的 close 意图，动画由同步 helper 统一执行，消除重复关闭动画。既有 dirty/确认与导航安全边界不放宽。

2026-09-06 当前 Refresh 颜色修复后完整门禁：verify-all PASS，exit 0；Gradle 96 tasks（26 executed、70 cached），BUILD SUCCESSFUL，5m39s；release/device fixtures 111/111（2.397s），foundation 49/49（3.852s），boundary 49/49（3.706s）。JVM 155 suites/1611 tests，UI 57 suites/665 tests，failure/error/skip 均 0；CloseGuard、SQLiteConnectionPool、SQLiteDatabase leaked、RoomDatabase leaked 全部 0；六个冻结文件零漂移。日志 android/.tmp/completion-refresh-color-full-final-gates.txt，XML 结果 android/.tmp/completion-refresh-color-full-final-results。当前不再进行生产改动；下方154/1601及后半门禁曾进行中的记录保留为历史。完整门禁与设备验收均已完成，T47/T48实现、构建、验收完成；仅Git交付回执待主Agent最终答复/Git日志确认。

当前最终签名产物（含 Refresh 颜色修复）完整签名审计 PASS，证书不变：APK 1,724,626 bytes，SHA-256 `5d90cbb3d271063ad7b9c3645eb94ede9180bde62c46bde109a09cc0c56a897e`；AAB 4,615,672 bytes，SHA-256 `e5c5541df3e6d8e408a530eeb44615d34a3f93eb9511d367212eeef6c1cbacf5`。旧包摘要仅作历史证据，当前新包设备验收已完成；Git交付为最后步骤，回执见主Agent最终答复/Git日志。

2026-09-06 Refresh 主题颜色实现与聚焦历史证据：04候选包在 API26 Dark 下 LARGE Refresh 显示黑字；WidgetRemoteViewsRenderer 为 LARGE 的 widget_refresh 显式调用 setTextColor(..., colors.foreground)，使文字使用当前 Widget 主题前景色。生产仅此一行，不改变点击 Intent、刷新流程或其他尺寸布局。独立 WidgetRefreshColorTest 为10 tests/10 failures RED，修复后10/10 GREEN（10.036s）；detekt通过，测试两处换行的ktlint纯格式修复后 detekt+ktlint 8 tasks/35s成功。该聚焦阶段曾待重新签名和全量；现已取得上方155/1611、签名审计及完整verify-all PASS，设备矩阵仍待收口。下方154/1601、UI57/665和verify-all PASS均为该修复前的上一完整基线；T48仍待新包最终设备验收与交付收口。

2026-09-06 Refresh 颜色修复前上一同源码最终门禁（历史已通过）：verify-all wrapper PASS，exit 0；Gradle 96 tasks（30 executed、66 cached），BUILD SUCCESSFUL，6m25s；release/device fixtures 111/111（2.433s），foundation 49/49（3.936s），boundary 49/49（3.337s）。JVM 154 suites/1601 tests，UI 57 suites/665 tests，failure/error/skipped 均 0；CloseGuard、SQLiteConnectionPool、SQLiteDatabase leaked、RoomDatabase leaked 全部 0。六个冻结文件 hash 零漂移。日志 android/.tmp/completion-drawer-full-final-gates.txt，XML 结果 android/.tmp/completion-drawer-full-final-results。此前 lint/boundary 进行中及153/1577等旧结果保留为历史；该上一源码 gate 已通过；Refresh 修复后的JVM155/1611与新包签名审计已通过，完整wrapper已PASS，设备证据仍待补，T48整体仍待收口。

Refresh 颜色修复前签名产物历史证据（完整签名审计 PASS）：APK 1,724,618 bytes，SHA-256 `04b4681c90d4a51dcf30265fb54938a71d2f8c2381b8fcf33109ee5cd454873d`；AAB 4,615,682 bytes，SHA-256 `cb83dc87b86f08658d9272d5aa5a484862e26c63a36260cec0a3e1224f9a7172`。这些摘要对应修复前签名包，不能作为当前重签新包的最终摘要；证书指纹由主 Agent 补录，签名材料继续忽略且须安全备份。

Refresh 颜色修复前包设备历史证据：API 29 的 Drawer 18 steps 全通过；API 26 的 core 8 steps 全通过且旋转正常。其他设备继续验收，不把上述局部通过等同于完整最低矩阵或 T48 完成；上一源码 verify-all/lint/boundary 已通过，Refresh 修复后的新包最终设备与交付提交仍由主 Agent 收口。

Drawer 聚焦历史证据：API 26/36 合计 24 cases 全通过（22.191s），ktlint 通过；helper 拆为独立文件后 detekt + ktlint 共 8 tasks/36s 成功。后续同源码全 JVM 已获上方154/1601结果；153 suites/1577 tests、UI 56/641 仍仅为 Drawer 修改前历史基线。

2026-09-06 Drawer 修改前历史源码基线：153 suites/1577 tests，UI 56 suites/641 tests，failure/error/skip 均 0，四类资源泄漏标记继续为 0；静态、依赖、Manifest、APK 门禁已通过。本轮完整 96 tasks 用时 4m56s，仅测试列表换行触发 ktlint 失败；精确纯空白修复后 ktlintCheck + detekt 为 8 tasks/30s PASS，未改行为，不能将本轮完整命令记为一次 BUILD SUCCESSFUL。该历史阶段 policy 49/49 已通过、boundary 尚待执行；当前最终 boundary 已通过，见上方门禁记录。六个冻结构建依赖文件零漂移，配置宇宙 85；Room/schema/wire/Windows 生产零变化。

上一源码阶段历史证据：96 tasks（35 executed/61 up-to-date）、6m36s BUILD SUCCESSFUL，153 suites/1573 tests，UI 56/641；该数字不再代表当前源码。本轮绿色 JVM 与纯格式修复不改变历史 Room teardown 的 NON-REPRODUCIBLE 裁决，也不证明历史根因已修复。

发布工具 CI：主 Agent 在 .github/workflows/android.yml 增加独立 release/device fixture 步骤，仓库根执行 `python -B -m unittest discover -s android/tests/release -v`；本机固定解释器运行同一根目录命令为 111/111（2.821s）。这替代当前 fixture 数字引用；先前 111/111（3.292s）保留为历史证据。未新增工具或依赖，Windows job 未改；本机通过不等于声称远端 CI 已运行。

From `android/`, reproduce the CI fixture command at the repository root with the explicitly selected interpreter:

```powershell
Push-Location ..
try {
    & $ClenderPython -B -m unittest discover -s android/tests/release -v
    if ($LASTEXITCODE -ne 0) { throw "Release/device fixtures failed." }
} finally { Pop-Location }
```

历史设备计划（当前最终包及全量结果以上方更新为准）：Drawer 修改前阶段曾冻结源码并进入重新签名。T48 仍处于设备验收与最终提交阶段。API 29/31/33/35 phone 在 4cf 候选包的核心 8 步均通过，当时仅 Widget flags 改动，曾按未受影响范围复用核心证据并计划新 APK 轻量安装/冷启；此次 Drawer 改动涉及核心导航，旧复用结论不能自动覆盖该改动，最终设备验收由主 Agent 重新核对。最低 API 26 phone、API 36 phone、API 36 tablet 继续要求完整最终包验收；API 33 tablet 首次核心验收待补。不得将候选包或历史审计哈希标为最终产物，该历史阶段待补的 APK/AAB 哈希现见上方；签名指纹仍由主 Agent 补录，不标 T48 完成。

QuickAI/P2C 与 Drawer 修复已通过同源码全 JVM；最终签名产物审计与 verify-all 全门禁通过，剩余设备与交付收口仍进行中，不标 T48 完成。独立签名材料已生成并保持忽略，脚本不回显密码/alias，用户须安全备份 key 与配置；当前 APK/AAB 哈希见本轮最新证据，最终证书指纹由主 Agent 补录。

当前 Intent 交付契约：创建端仍为 CLEAR_TOP|SINGLE_TOP（0x24000000）。仅 EditEvent/QuickAI 消费 externalTaskFlags：NEW_TASK（0x10000000）可选，只有存在 NEW_TASK 时才允许附加 BROUGHT_TO_FRONT（0x00400000）；单独 BROUGHT_TO_FRONT 拒绝。QuickAI 的 EXCLUDE_FROM_RECENTS（0x00800000）独立可选。EditEvent 合法 flags 为 0x24000000/0x34000000/0x34400000；QuickAI 为这三种各自可选 EXCLUDE_FROM_RECENTS，另有 0x24800000/0x34800000/0x34c00000。内部导航真实交付仍为 0x24000000，不扩展；Configure/LocalRefresh 及 action/component/package、canonical data、唯一 appWidgetId、平台 owner 等边界不变。

真实 JDB 证据：QuickAI 交付 0x34c00000、EditEvent 交付 0x34400000，均比 ActivityTaskManager 日志多 BROUGHT_TO_FRONT；唯一 appWidgetId、canonical identity 与 owner 正常，仅去除此位同一 Intent 即校验成功。tests-first RED 为 QuickAI 58 tests/8 failures、EditEvent 30 tests/2 failures；完整证据见主任务。

P2C 当前契约：平台 RemoteViews（API 26–30 单尺寸、API 31+ 四尺寸）复用同一 Widget scope/store/coordinator，自动汇合本地 mutation、remoteVisibleChanged、配置、前台、手动、日期与 Boot 的本地更新；远端 apply 不发本地 mutation，不产生上传回路。日期只用唯一 one-time REPLACE WorkManager，禁止 periodic/expedited/foreground、Alarm/通知及后台网络。非导出 Boot 仅允许 BOOT_COMPLETED 与空 extras 或唯一非负 Int android.intent.extra.user_handle；9 秒 finish-once。无实例保持惰性，删除 admission/generation 防迟到，close 在 DataStore/Room 前取消并等待 Widget 工作。

真实 UI 修复：日历/事项顶层新建按钮以 selectedDate 进入现有表单；旋转/recreate 对同一 Detail/Edit ID 保留编辑态，对同日期且已有 form 的 New route 不再 startNew 覆盖草稿。MainActivity 重建不重放旧启动 Widget Intent，onNewIntent 继续独立校验。About 使用真实应用/版本 metadata，资源格式占位符已修复，不显示原样占位符；缺失 metadata 仍显示有限不可用。

Windows 198/198、imports 30/30、完整 PyInstaller 与正式 10 cycles/20 scenes 均通过；EXE 45,582,242 bytes，SHA-256 `C52F386542E78C5FA2E85C4A0B02F942FC2A2481C60694D5D29A33832CCA207D`；dist/data 5 files/196,074 bytes，路径、大小、时间及逐文件哈希前后完全一致。

历史 checkpoint 的“未开始”“QuickAI fail-closed”“无 Worker/Boot”与旧测试数量只描述各自阶段，不是当前基线。保留历史 Room/CalendarOverflow 一次 teardown 失败及 NON-REPRODUCIBLE 裁决；后续全量绿色不构成根因修复证明，不得宣称已修复该历史失败。

本轮已授权最终统一一次集成提交。Gitee 仅创建/推送 codex/android-architecture 远程分支；远程 main 只有 LICENSE 且与本地分叉，不合并 main，不推 GitHub。最终提交前复核 staged 白名单及 boundary，不能用暂存前通过代替索引门禁。

`verify-all.ps1` runs release/device fixtures, strict offline single-worker Gradle gates, and policy/boundaries. It does not build the signed release or certify devices. After staging the reviewed source/document allowlist, run the boundary gate again so newly indexed files are checked:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-boundaries.ps1 -PythonExecutable $ClenderPython
git diff --cached --check
git diff --cached --name-status
```

## Domain and local data baseline

T43 adds the platform-independent models and calendar logic under `core/model` and `domain`, plus an isolated Room v1 database under `data/local`. Local event mutations flow through `EventService` so UUID/UTC metadata, tombstones and `ScheduleMutation` are emitted only after successful persistence. Remote sync applies complete events through a separate Room transaction and does not emit a local mutation.

Room v1 contains `events`, `conversations` and cascade-owned `messages`. Visible queries always exclude tombstones; overlap uses half-open ranges; sync snapshots include tombstones and sort by sync UID. Wall-clock values use strict `yyyy-MM-dd HH:mm` text and sync metadata uses six-digit UTC microseconds. The database is validated on Robolectric API 26 and 36, and its exported schema is committed under `app/schemas/`.

The full device-free T43 regression is included in `testDebugUnitTest`. Focused runs are:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\gradle.ps1 --offline --no-daemon --max-workers=1 --dependency-verification strict testDebugUnitTest --tests "*.core.model.*" --tests "*.domain.*"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\gradle.ps1 --offline --no-daemon --max-workers=1 --dependency-verification strict testDebugUnitTest --tests "*.data.local.*"
```

There is no historical database version to migrate from yet. The v1 baseline is proved by opening a real empty Room database and checking the exported schema, foreign key and indexes. Any future v2 must add an explicit migration and a true cross-version migration harness.
