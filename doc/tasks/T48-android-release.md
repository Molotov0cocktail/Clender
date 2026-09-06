# T48：Android 全矩阵验证、APK/AAB、Windows 回归与 Gitee 推送

2026-09-06：T47/P2B2b/P2C 与 T48 实现、完整门禁、签名构建和八组模拟器验收已完成。最终 JVM155 suites/1611 tests、UI57/665，零失败/错误/跳过/泄漏；Git 提交与仅 Gitee 分支推送作为最后交付步骤，实际回执见最终答复与 Git 日志。

## 目标

集成 T42–T47，完成全量测试、设备矩阵、release keystore/APK/AAB、安全审计、Windows 回归、文档、提交和仅 Gitee 分支推送。

## 非目标

- 不把 APK/AAB/keystore 提交 Git；不推 GitHub；不创建线上 Release；不连接真实 AI/WebDAV。

## 输入文档

- Phase 11 全部设计与任务；根/Android AGENTS

## 影响文件

- 必要最小集成修复
- Android README/scripts/release notes、CI
- `AGENTS.md`、任务/进度/控制文档
- 忽略的本地 keystore 与 release 生成物

## 接口/数据影响

- 最终锁定 Android v1 公共契约、构建命令、签名证书摘要与 artifact 哈希。

## 风险

- 模拟器下载/启动失败；release R8 反射问题；keystore 丢失；Windows 构建误触用户数据；Gitee 与本地历史分叉。

## 实施步骤

- [x] 全量 unit/Robolectric/Room/MockWebServer/Compose/RemoteViews、lint/detekt/ktlint。
- [x] API 26/29/31/33/35/36 phone 与 API 33/36 tablet instrumentation/冒烟；其中 API 26 phone、API 36 phone、API 36 tablet 的 release 安装/冷启/核心冒烟不可自动豁免。
- [x] 生成本地 release keystore/随机强密码并写忽略配置；脚本不得回显密码/alias/绝对敏感路径，交付时提示用户安全备份。
- [x] assembleRelease/bundleRelease；zipalign/apksigner/aapt2/manifest/R8/native/secret 审计。
- [x] debug/test variant 完成 mock AI/WebDAV；安装交付 release APK，完成冷启、空库、导航、CRUD、无配置/离线安全失败、生产装配和 Widget 冒烟，并证明无 fake/test hook/测试 CA。
- [x] 记录 artifact 路径、大小、SHA-256、证书摘要；二进制保持未跟踪/忽略。
- [x] 执行 Windows 198 unittest、导入、build check、完整 PyInstaller 与隔离 exe；`dist/data` 前后只读摘要一致。
- [x] 更新任务/progress/AGENTS；提交前再检查最终 staged diff/敏感/生成物。
- Git 交付最后执行：Gitee 原远端未有 `codex/android-architecture`，本地分支已存在；设置代理、整合提交并普通推送 `Molotov` 创建同名分支，不改 main、不推 GitHub。实际回执见最终答复与 Git 日志。

## 测试与检查

### 发布工具 CI 接线（2026-09-06）

最终复核确认现有 Android workflow 运行 policy/Gradle，却未运行本轮新增的发布与设备工具夹具。修改前冻结写集为 `.github/workflows/android.yml` 的一个独立 Python unittest 步骤；使用已有 Python3.12.4，不增加安装、依赖、签名材料、网络或模拟器要求，不改 Windows job。正常路径是在仓库根目录执行 `python -B -m unittest discover -s android/tests/release -v` 的全部111项；边界/非法路径复用其中路径越界、秘密材料、伪造产物/设备、脚本失败传播夹具；回归为既有 Android policy 和 Windows workflow 零改动。回滚只删除该CI步骤；完成定义为同一根目录命令本地通过、workflow diff检查通过，远端workflow未执行则如实说明。

### 2026-09-06 本地配置源码扫描边界（小修完成）

已确认 production_text_files 的 os.walk 将本地 ignored keystore.properties 纳入源码扫描。影响仅 `android/tests/policy/_support.py`、新 `test_production_text_files.py` 及本节。精确允许根 keystore.properties/local.properties 在 Git 同时证明 ignored 且 untracked 时排除；tracked、未忽略、Git 查询失败均不得静默排除，嵌套同名文件及其他源码不豁免。秘密 patterns、MUST_IGNORE 与 tracked artifact 门禁保持原样。先使用假 root/合成文件/mock Git 取得 RED，再最小修复 helper；测试不得读取真实配置。回滚仅撤销本次 helper/fixture 改动；完成定义为聚焦 GREEN、限定 diff 检查，整体 T48 仍未收口。

固定 Miniconda Python，cwd `android/tests/policy/`，`-m unittest discover -s . -p test_production_text_files.py -v`：旧实现 5 tests/3 failures（0.045s，工具记录 ac9416）；修复后 5/5（0.040s，ce9e64）。fixture 对合法排除路径设置 read_text 禁读断言；tracked/未忽略合成配置实际调用原秘密 pattern 门禁并断言拒绝。额外运行既有 `IgnoreAndBoundaryTests` 的 `test_android_text_has_no_real_secrets_or_host_specific_paths`、`test_generated_artifacts_and_secrets_are_ignored`、`test_tracked_files_contain_no_android_generated_or_secret_artifacts`，3/3（1.276s，28c814）；调用外层为真实根 keystore.properties/local.properties 注入 read_text 禁读保护，Git 仅查索引/忽略元数据，未读取配置正文。限定 diff 检查通过，未执行 Gradle，未改其他脚本或秘密规则。此前扫描误报已由此小修消除，T48 最终集成状态不变。

### 2026-09-06 非 BMP 签名路径回归（小修完成，T48 未收口）

影响限定 `scripts/verify-release.py` 与 release fixture：原 storeFile 的 `\\u%04x` 按 Unicode code point 转义，emoji 会生成超过四位的 Java Properties escape，导致路径被错误解析。先以 fake keytool 在临时目录覆盖 ASCII/空格/BMP/非 BMP 路径，验证精确 UTF-16 surrogate pair 与 helper 读取回环，再修复转义；保留既有不覆写、路径限定与失败传播测试。写出改为每个 UTF-16 code unit 四位转义，read_signing 合并代理对，使 Python 路径与 Java Properties 一致。未调用真实 keytool、读取或改动已有签名配置/key，未执行 Gradle。回滚仅撤销此 helper/fixture 小修；不触碰签名材料。

宿主固定 Miniconda Python、cwd `android/`：`-m unittest discover -s tests/release -p test_release_tools.py -k java_properties_unicode_path -v` 在旧实现运行 2 tests/3 failures（0.154s），精确暴露非 BMP 转义与读回错误；修复后 `-m unittest discover -s tests/release -p test_release_tools.py -k key -v` 为 8/8（0.178s），包括不覆写、发布竞争、越界/debug key 拒绝、工具错误传播与秘密参数间接传递。未重跑 PowerShell wrapper 或全发布 suite，由主 Agent 统一执行最终集成。

历史边界检查：首次指定错误测试类名导致 loader error，未执行实际 policy；纠正为 cwd `android/tests/policy/` 下 `-m unittest discover -s . -p test_ignore_and_boundaries.py -k test_android_text_has_no_real_secrets_or_host_specific_paths -v` 后，1 test/1 failure（0.400s），唯一报告路径为 `android/keystore.properties`，未输出配置值。此现场随后由上节精确 ignored/untracked 扫描边界修复，49/49 policy 已通过；保留失败证据，不作为当前未解决问题。限定 helper/tests/本节 diff 检查通过。

```powershell
.\android\scripts\verify-all.ps1 -PythonExecutable 'C:\Users\30910\Miniconda3\python.exe'
.\android\scripts\build-release.ps1 -PythonExecutable 'C:\Users\30910\Miniconda3\python.exe'
$env:QT_QPA_PLATFORM='offscreen'
& 'C:\Users\30910\Miniconda3\python.exe' -m unittest discover -s tests -v
& 'C:\Users\30910\Miniconda3\python.exe' .\build.py --check
& 'C:\Users\30910\Miniconda3\python.exe' .\build.py
```

## 最终源码的已审计产物（2026-09-06，设备验收完成）

`build-release.ps1 -PythonExecutable 'C:\Users\30910\Miniconda3\python.exe'` 返回0，完整签名/R8 构建与审计通过；最新日志 `.tmp/completion-release-refresh-color-build.txt`，机器可读元数据 `android/release/artifact-metadata.json`（忽略）。先前 `4cf6e0e…` 的 Widget 入口失败、`c287f7cc…` 的 Drawer 失败及 `04b4681c…` 的 Dark Refresh 黑字均保留为候选证据，不混入此产物通过记录。

|产物|相对 android/ 路径|字节|SHA-256|
|---|---|---:|---|
|APK|app/build/outputs/apk/release/app-release.apk|1724626|`5d90cbb3d271063ad7b9c3645eb94ede9180bde62c46bde109a09cc0c56a897e`|
|AAB|app/build/outputs/bundle/release/app-release.aab|4615672|`e5c5541df3e6d8e408a530eeb44615d34a3f93eb9511d367212eeef6c1cbacf5`|

发布证书 SHA-256：`628248932cfd0587a04126290ac2af870591fc7ebe80259c5f29d266c944615f`。R8 mapping 为 38889596 bytes，SHA-256 `202ff0ba95ea46b4590ae4209a150183f90dace8e4d488f0c0035adcdc48b9b0`；应与该版本二进制一同本地保留。

审计实际运行 apksigner（minSdk26）、zipalign、aapt2 Manifest/network policy、bundletool validate/manifest、jarsigner、APK/AAB 证书一致性、归档/DEX 类型检查；无 native、Glance、Google/Firebase、测试类/测试 hook/测试 CA 或明文网络策略。独立发布 key 与忽略配置仅在本机生成，不回显、不提交；交付时提示安全备份。官方 bundletool 1.18.1 的本地 SHA-256 见主任务记录，该值是本地实测而非上游公布的校验和。

最终 `verify-all.ps1` 返回0：JVM155 suites/1611 tests、UI57/665，失败/错误/跳过全部0，完整XML和日志的四类泄漏标记均0。Gradle96 tasks（26 executed/70 cached）、5m39s，BUILD SUCCESSFUL；发布工具111/111（2.397s）、foundation49/49（3.852s）、boundary49/49（3.706s）。六个冻结构建/依赖文件摘要未变，configuration universe仍85。日志 `android/.tmp/completion-refresh-color-full-final-gates.txt`，完整XML `…completion-refresh-color-full-final-results`。

历史153/1577的96-task命令为4m56s，曾因一处测试列表换行ktlint失败；纯空白修复后 `ktlintCheck detekt` 8tasks/30s通过，未将原命令冒称整体成功。后续Drawer与Refresh颜色各自RED/修复/静态记录见专用任务。设备矩阵区分最终包完整验收、未受影响的候选核心证据与最终包增量验收，不将候选 Widget 失败标绿。

## 历史设备验收问题与已保留证据

`c287f7cc…` 候选在 API36 phone/tablet 的核心与真实 Widget 操作通过；API36 phone 还以仅测试模拟器的临时时钟验证自然日期边界 one-time work（151.97 秒后刷新且产生后继工作），恢复时钟后真实 reboot、无 MainActivity 启动即可恢复 Widget。详见 `android/.tmp/completion-release-widget36-date.json`、`completion-release-widget36-boot.json`。均不代表精确分钟刷新或真实设备 Doze 验收。

API33 tablet 同候选 core8 通过。API29 最终包增量导航发现遮罩关闭后 Drawer 无法再次打开，已新增独立任务 `T48-android-drawer-state.md`，发布保持未完成。旧候选的扩展 API29/31/33/35 core8 证据仍保留，后续包需要覆盖实际变更的 Drawer 路径。

API26 同候选 core 前7项通过，旋转步骤原始报告为 `ADB_FAILED`；独立逐命令诊断随后完成两个方向旋转与 About 可见性检查、全部 adb 返回0，并恢复原旋转设置。保留原失败，不声称已证明该暂态命令失败根因。实际 Launcher 添加、放大 Widget 后，Refresh、QuickAI 未配置拒绝/保留草稿、View AI 无消息通过。配置脚本点击已选 Dark 得到 `NODE_NOT_ACTIONABLE`（真实 XML checked=true），改选 Light 后保存通过；后续事项创建被同一 Drawer 路径阻断，等待生产修复后验收。原始报告与逐步 XML 均保留在 `android/.tmp/completion-release-widget26phone-preflight*`。

## 回滚方式

- 源码：回滚 Phase 11 commits/分支。
- artifact：保留 keystore 备份后删除忽略的 release 目录；APK/AAB 可重建。
- 不对 WebDAV/Windows 数据做迁移，无真实数据回滚。

## 完成定义

- [x] 全矩阵按明确增量证据策略通过；API 26 phone、API 36 phone、API 36 tablet 最低线未通过时任务保持阻塞，除非用户另行明确豁免。其他不可运行项有命令/错误/风险证据且不掩盖失败。
- [x] release APK/AAB 完整签名、安全、可安装；keystore 未提交并已提示备份。
- [x] Windows 回归、exe 和 `dist/data` 门禁通过。
- 提交门禁：staged diff 仅含获准源码/文档/CI，无二进制、秘密、数据、cache；最终审查与提交顺序执行。
- Git 交付完成凭据：本地 commit 与 Gitee `codex/android-architecture` 远端 SHA 一致；GitHub 不推送，实际回执见最终答复与 Git 日志。

## 2026-09-06 最终验收裁决

实现、构建与验收 PASS。最终 APK SHA-256 为 `5d90cbb3d271063ad7b9c3645eb94ede9180bde62c46bde109a09cc0c56a897e`；APK/AAB/证书/mapping 摘要见 T48 发布任务。全部八组均安装最终 APK 并核对 hash/version；按已授权效率决策保留未受影响的候选证据，未声称所有项目在最终包重跑。

|AOSP 模拟器|核心八项|Drawer 回归|实际 Widget|
|---|---|---|---|
|API26 phone|最终包8/8|04b468候选18项；最终核心导航通过|最终包通过，原ADB失败及续验分别保留|
|API29/31/33 phone|复用4cf6e0候选8/8|复用04b468候选18项；最终包About导航通过|扩展线不要求|
|API35 phone|复用4cf6e0候选8/8|最终包18项通过|扩展线不要求|
|API33 tablet|复用c287f7候选8/8|最终包18项通过|扩展线不要求|
|API36 phone/tablet|最终包各8/8|最终包各18项通过|最终包各10阶段通过|

最低三类的实际 Widget 验证包括 Launcher 添加/配置、Dark Refresh 可读性、QuickAI 未配置保留草稿、View AI 零消息、Settings HTTPS endpoint 导航、事项创建自动更新、热编辑和明确进程消失后的冷详情入口。API26 `completion-final-widget-api26-r4.json` 原报告 FAIL：保存已完成，删除临时 UI XML 的 adb 命令失败；未重放写入，`completion-widget-api26-r4-resume-result.json` 只读确认已保存再完成自动更新/冷入口，结果 PASS。原失败原因未臆断，无应用 crash/ANR。自动 core 工具原 Widget BLOCKED 保持原样，由独立实际 Widget 证据补齐。

本地证据均位于 `android/.tmp/`：`completion-release-r4-api26-phone.json`、`completion-release-r4-api36-phone.json`、`completion-release-r4-api36-phone-drawer/result.json`、`completion-final-widget-api36phone-r4.json`；六组扩展及平板的精确路径/候选 SHA 索引为 `completion-release-r4-agent-c-index.json`，其中平板最终 core/Drawer/Widget 均为最终 SHA。日期边界与真实模拟器 reboot 复用 c287f7 的 `completion-release-widget36-date.json`、`completion-release-widget36-boot.json`；自然等待151.97秒、后继 one-time work、无 MainActivity 启动恢复均通过，时钟已恢复。后续仅 Drawer 状态与 Refresh 颜色发生生产变更，调度/Worker/Boot 未变。

最终 `verify-all.ps1` exit0：155/1611、UI57/665，完整 XML/日志四类泄漏0；96tasks（26执行/70缓存）、5m39s，lintDebug/lintRelease/detekt/ktlint/签名/native/Google/锁均通过；发布夹具111/111，foundation/boundary各49/49，六冻结文件零漂移、配置宇宙85。最终证据 `completion-refresh-color-full-final-gates.txt` 和对应完整结果目录。

Windows198/198、imports30/30、check、完整PyInstaller、10cycles/20scenes正式冒烟均通过；EXE45582242bytes，SHA-256 `C52F386542E78C5FA2E85C4A0B02F942FC2A2481C60694D5D29A33832CCA207D`。交付前再次只读核对 dist/data：5files/196074bytes，路径/大小/UTC/逐文件hash零变化，Clender进程0；`completion-dist-data-delivery-check.json` 留证。Windows生产输入未改，不重复同一整合批次构建。验收模拟器全部关闭，adb devices为空。

限制：八组均为隔离 AOSP 模拟器，不代表物理设备/OEM Launcher/Doze 验收；真实 AI/WebDAV 服务未接入，异常与协议由隔离测试验证；日期刷新为系统 best-effort。历史 Room teardown NON-REPRODUCIBLE 保留，未修改 CalendarOverflow/helper 或宣称根因修复。签名 key 与配置须安全备份，mapping 与此版本产物一起保留。二进制、秘密、数据库、日志与缓存不提交。

Git 最后步骤：复核 staged diff/敏感与边界，执行要求的 global https.proxy 设置，创建一次整合提交；普通推送 `Molotov HEAD:refs/heads/codex/android-architecture` 并核对远端 SHA。Gitee main 原为 `ab94d324ffd776148aa7479d6b33ce242d782f0f`，保留分叉不合并、不 force；GitHub 不推送。文档不预写尚未执行的提交/推送成功，实际回执以最终答复与 Git 日志为准。
