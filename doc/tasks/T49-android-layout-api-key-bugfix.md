# T49 Android 日历重叠与 API Key 保存修复

最终状态：实现、签名构建、Android/Windows验证与设备验收完成。Git提交和仅Gitee分支推送作为最后交付步骤，实际SHA回执见最终答复与Git日志；以下完整保留历次失败和恢复依据。

## 授权与目标

2026-09-06 用户报告月/周/日空态文字与表头重叠，以及 API Key 无法保存、切页后清空。起点为干净 `997090fb919f8861592b0520375ea783893dcf8b`。修复这两类问题并交付可覆盖安装的同证书 APK/AAB；保留已有用户数据。用户允许安全方案确实困难时使用类似 URL/model 的简单保存；先定位现有实现，不预设必须降低存储保护。

## 非目标与接口

不扩展新功能、不更新依赖、不改 Room/schema/WebDAV wire、Windows 生产源码和用户运行数据；不接真实 API、不读取真实 Key。日历只改状态消息/内容布局，保持现有日期选择、日程导航、滚动与语义。Key 默认修复既有 Keystore AES-GCM envelope 流程，保持原子 KEEP/REPLACE/REMOVE；保存后不回填明文，但必须清楚显示已配置状态，切页/重启不得删除已保存 Key。若定位到独立草稿交互问题，记录测试后修复。

## 写集与 agent 控制

- Calendar agent：`ui/calendar` 最小布局、专用回归测试、`T49-calendar-layout.md`；先写测试并等待主 agent 串行执行 RED。
- Key agent：`data/settings/SecretStore.kt`、必要 `ui/settings`/服务的最小修改与专用测试、`T49-api-key-persistence.md`；先定位并返回测试，不与其他 agent 并发 Gradle。
- 主 agent：集成审查、测试/设备/构建、两级 AGENTS、progress、此任务与发布记录。其他写集需先在本任务补明理由。
- 发布说明补充 `android/README.md`：只记录本轮修复行为、覆盖安装方式、产物摘要及验证限制，避免旧T48摘要被误认为当前包；不修改构建配置或版本锁。

## 测试矩阵（修改前）

|范围|正常|边界|异常/非法|回归|
|---|---|---|---|---|
|日历布局|月/周/日空态与表头 bounds 不相交|窄屏320/360、8/20sp、系统200%、中英、Light/Dark|Loading/Error/重试占独立布局|有事项、日期/模式切换、导航、滚动、无障碍|
|Key保存|输入合成Key保存、切页回来与重启后仍配置且可读|替换、只改URL/model KEEP、Unicode、平台API26/36|Keystore初始化/encrypt/edit失败无部分提交，移除需明确操作|旧envelope解密兼容、数组归零、无配置/超时/非200/畸形JSON/危险输入已有回归|
|发布|完整Android门禁、同签名APK/AAB、真实模拟器使用AndroidKeyStore保存|最低API26/36与窄屏大字体|未配置安全失败、错误原始证据保留|Windows198/import/check/PyInstaller/正式exe冒烟及dist/data元数据hash不变|

## 步骤、风险和回滚

1. 读取契约、源码与历史测试；独立子任务补充精确矩阵并先取得旧实现失败证据。
2. 小步修复，聚焦测试通过后主 agent 审查；真实模拟器验证硬件提供者策略，不能仅依赖 JVM 通用 AES provider。
3. 完整 Android 门禁、签名构建和聚焦设备验收；Windows 完整构建/隔离冒烟，运行数据前后只读核验。
4. 更新契约与结果、staged 敏感/生成物/边界审查，统一提交并按既有交付范围仅推 Gitee 同名分支。

风险：Compose语义存在不代表无视觉重叠；JVM通用加密provider不执行AndroidKeyStore随机IV策略；字段为空不等于Key丢失。必须分别以布局几何与真实提供者/持久化读回证据验证。旧失败保留，不盲目重试或放宽断言。回滚仅撤销本任务精确改动/提交；保留签名文件和用户数据，不做破坏性迁移。

## 完成定义与当前状态

- [x] 读取当前契约、干净起点、分配两个独立子任务。
- [x] RED → 最小修复 → 聚焦 GREEN、独立审查。
- [x] 全门禁分项、实际模拟器、签名产物、Windows构建与数据完整性通过。
- [x] 更新两级契约、任务进度与发布审查；提交/仅Gitee推送最后执行，回执见最终答复。

## 规划期定位记录

CalendarViewport 使用 Box 叠加 CalendarContent 与 TopCenter EMPTY，截图对应真实同坐标布局；LOADING 亦作为覆盖层，专用回归以几何边界验证。

Key 加密的强根因候选得到 [Android 官方 KeyGenParameterSpec 文档](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder.html) 支持：randomizedEncryptionRequired 启用时拒绝加密方指定 IV，官方建议让 Cipher 初始化生成 IV，再读取 getIV。当前生产显式 GCMParameterSpec 与该策略冲突，旧 JVM 普通软件 key 测试未施加此平台限制。真实 API36 旧版保存与带策略的 provider 测试正在验证；没有理由先改明文。

补充写集：Windows agent 只执行本轮一次固定环境构建/回归与 `T49-windows-verification.md` 记录；API36 device agent 只用既有隔离模拟器及合成输入复现，不修改生产或执行 Gradle。另将 SettingsScreen 的未保存内存 Key 草稿切页显示与持久化 Key 的“不回填”分别验证，必要 UI 写集经独立 RED 批准。

## 用户运行中的 Windows 环境边界

本轮检测到真实 `dist/Clender.exe` 正在运行、QLocalServer 可达。主 agent 请求正常退出后，用户明确选择“先继续 Android 修复”。据此 Android 交付继续，Windows EXE 不替换、不结束用户进程，完整 PyInstaller/正式冒烟暂缓且最终必须说明。独立 Windows unittest 为198项/1 error：冻结清理屏障测试实际等待真实 endpoint quiescence，因此在用户实例仍运行时超时；原失败保留，不修改冻结harness或误标全绿。30 imports 与 build check 通过。详情 `T49-windows-verification.md`；本轮不拿上一轮198全绿/EXE结果冒称新验证。

## 首次 RED 与实施批准

新增测试首次编译暴露 `onAllNodes` 非顶层 import 与 Provider 版本参数应为 Double，49s/22tasks；精确修正后第二次编译暴露 AiSettings 夹具缺必填参数，28s/22tasks，改为 DEFAULT.ai.copy。两次均无业务测试执行，属于测试编译失败，不冒称预期RED；日志分别 `t49-layout-key-red.txt`、`t49-layout-key-red-compiled.txt`。

第三次命令同两专用 suite，1m49s/32tasks（3执行/29缓存）：34 tests/16 failures/0 errors。Calendar24项中三模式 EMPTY/LOADING × 两API共12项实际bounds相交，ERROR/CONTENT12项通过；Crypto10项中生产encrypt与真实DataStore重开两方法×两API共4项 InvalidAlgorithmParameterException，其他6项通过。日志 `android/.tmp/t49-layout-key-red-ready.txt`，完整XML已复制至 `t49-layout-key-red-results`，保留原始 cause。

据此批准两 agent 分别修改 CalendarScreen.kt 的独立状态布局、SecretStore.kt 的provider生成IV；UI草稿另建测试先取得RED，不在无证据时修改。当前未宣称修复完成。

第二阶段：`t49-crypto-calendar-green-ui-red.txt`，1m24s/32tasks（7执行/25缓存），44 tests/4 failures/0 errors。Calendar24与Crypto10全部通过；新UI10项中“切页保留草稿”和“保存失败后切页保留输入”两个方法×两API共4项旧实现失败，实际掩码长度期望12却为0；6项保存成功/KEEP/导航对照通过。XML保存在同名前缀 `-results`。据此批准 SettingsScreen.kt 从未保存内存草稿初始化局部字段，临时数组finally擦除，不解密回填、不保存到SavedState。

独立审查已确认两处布局/加密补丁无具体安全或行为回归；按审查补强旧envelope失败原子性、资源locale真实切换及完整合成字符对比，不只验证长度。API36旧签名包也以实际设置页得到Save失败、重进后未configured的RED；文案错误复用models_error不代表发送过模型请求。设备脚本的点击/滚动失败分别留证，未以其替代产品缺陷证据。

首次扩大聚焦 `t49-focused-green-static.txt`：205 tests/2 failures，2m30s/32tasks。失败来自加强后的续输测试未指定光标位置，却假定末尾追加；明确设置光标到末尾后继续完整字符断言，未放宽内容验证。其余203项通过，静态任务因前序失败尚未执行。完整XML `t49-focused-first-results`。

审查另发现保存成功后其他section仍dirty时，clear effect不重启、局部字段可能残留，新增第6个UI回归。首次尝试 `t49-ui-clear-red-static.txt` 采用 --continue 收集独立静态结果：1m26s/38tasks，但新的测试光标API需test-only opt-in且返回Unit不能链式调用，编译失败，**没有新业务测试执行**；Calendar测试helper复杂度/嵌套深度、两文件参数换行同时被静态检查指出。逐项精确修正，不降低规则。误复制到 `t49-ui-clear-red-results` 的XML是上一轮残留，目录内已明确标注 NOT-A-TEST-RUN，不作新RED证据。

修正后 `t49-ui-clear-red-compiled-static.txt`：2m2s/40tasks（6执行/34缓存），UI12项中仅新清空用例×两API失败（期望字段0字符，实际12），原10项全部通过；detekt与ktlint均通过，命令整体仍因预期测试失败返回1。有效新RED XML为 `t49-ui-clear-valid-red-results`。批准clear effect观察 `secretInput.isEmpty()` 的布尔快照，以Key自身状态同步清空，不依赖其他section的dirty，不改变持久化或测试断言。

最终专用门禁 `t49-final-focused-green.txt` exit0、2m20s/40tasks（9执行/31缓存）：Calendar24、Crypto10、UI12，共46项全绿、失败/错误0，detekt/ktlint通过。完整XML `t49-final-focused-results`；独立审查已确认内存恢复、成功清空、完整字符断言和既有安全边界，无新的阻止交付项。七个生产/测试文件的同源码hash固定在 `t49-verified-source-hashes.json`；六冻结构建文件零漂移，Windows生产输入零diff。开始同证书签名构建、完整Android门禁及API26/36实际设备验收。

## 签名产物（构建及审计通过）

`build-release.ps1 -PythonExecutable 'C:/Users/30910/Miniconda3/python.exe'` exit0，日志 `android/.tmp/t49-release-build.txt`，元数据 `android/release/artifact-metadata.json`（忽略）。既有签名未重新生成，证书SHA-256仍为 `628248932cfd0587a04126290ac2af870591fc7ebe80259c5f29d266c944615f`，可覆盖安装；不要通过卸载绕过升级验证。

|产物（相对android/）|bytes|SHA-256|
|---|---:|---|
|app/build/outputs/apk/release/app-release.apk|1725038|`02d29be8879efe509aa624506091cf69599bb4531234e026d0311d3f2011a799`|
|app/build/outputs/bundle/release/app-release.aab|4616953|`58a3acdf24927ad426bf11707771fdf1af76e343e20a868fcd08d8b74e844514`|
|app/build/outputs/mapping/release/mapping.txt|38908155|`5c3a2e16334651d8f1843cc795ed9e6245f5e75e5bfa9df92b8d75362989b08a`|

apksigner/zipalign/aapt2、network policy、bundletool validate/manifest、jarsigner、证书一致与归档/DEX检查均通过，无native/Google/Glance/test hooks/测试CA/明文网络或秘密产物混入。版本仍1.0.0/code1，沿用既有构建锁。审计脚本的device_validation=NOT_PERFORMED_BY_THIS_SCRIPT保持真实含义，实际设备证据单独记录。

## 最终门禁边界接续

首次完整 `verify-all.ps1` 中发布夹具111/111（2.659s）、Gradle96tasks（24执行/72缓存）4m39s BUILD SUCCESSFUL；完整JVM158 suites/1657 tests、UI59/701、失败/错误/跳过全部0。随后foundation49项仅 change-set boundary 失败，拒绝本轮四份T49任务文档，故聚合命令整体为exit1，不能声称整wrapper已成功。完整日志 `t49-final-full-gates.txt`、XML `t49-final-full-results`、计数 `t49-final-full-counts.json`。

新增精确写集：`android/tests/policy/test_ignore_and_boundaries.py` 的 allowed_exact 只增加本任务与 Key/Calendar/Windows 四份T49文档；新 `test_t49_task_boundary.py` 先验证四精确路径在旧规则上失败，未知T49路径、其他Windows源码/脚本与数据路径仍拒绝，两个既有Windows harness精确例外保持。无新增prefix/wildcard、无秘密或生成物豁免。回滚仅本次四路径与fixture。完成定义为有效RED→策略52项通过，应用生产/七验证源码hash不变；策略修复无需重建签名包或重复完整JVM。

策略旧实现RED为3项/1失败（0.003s），仅四份已授权新文档被拒，负向与既有精确例外对照通过；首次日志重定向被sandbox拒绝、0tests执行，宿主权限精确重发后取得此RED。加入四个allowed_exact后，`verify-boundaries.ps1` exit0、52/52（3.166s），日志 `t49-final-policy-boundaries.txt`。原聚合失败保留，最终所有组件门禁已分项通过，不重复无生产变化的Gradle/JVM。

完整XML与日志扫描 CloseGuard、SQLiteConnectionPool、SQLiteDatabase leaked、RoomDatabase leaked均0，证据 `t49-final-leak-scan.json`。七验证源码hash复核零漂移，APK/AAB摘要与审计产物一致。最终17文件只读审查通过，常见敏感模式、秘密/生成物路径均0；四精确文档例外未扩展源码或数据边界。设备验收仍待最终回执。

## 最终 Android 设备验收：PASS

API26/5580 与 API36/5582 均覆盖安装同证书修复包，设备base.apk hash与最终02d29b…1a799完全一致。两设备均通过：合成Key保存→设置页签切换→force-stop本测试应用→重开configured仍在→空输入KEEP更改model→单次无监听loopback HTTPS请求产生合成USER后有限NETWORK失败，之后configured仍在。证明真实AndroidKeyStore保存和重开后解密使用，不只是envelope presence；未连接真实服务、未输出Key/Authorization。

|设备|实际日历矩阵|结果与证据|
|---|---|---|
|API26 phone|13sp/system默认、20sp/system200%，各月/周/日，共6帧|EMPTY存在，真实Month7/Week7/Day1标题bounds不相交；`android/.tmp/t49-api26-final-result.json`，精确报告`*-actual-headers.json`|
|API36 phone|13sp/system1.0、20sp/system1.0、20sp/system2.0，各月/周/日，共9帧|EMPTY存在且与真实标题不相交；`android/.tmp/t49-api36-final/summary.json`，`result.json`含完整截图/bounds索引|

主agent已目检API36保存configured、标准周与最大字号周，以及API26最大字号月截图。极大字号自然换行属于允许行为，不缩小字号或隐藏标题。设备都是隔离AOSP模拟器；不冒称物理OEM手机或真实HTTPS成功响应验收，网络拒绝仅证明请求已接受并实际使用解密后的Key。中英文资源与Loading/Error/Content等完整状态由24项JVM布局回归验证。

API26原工具将已选控件判为不可点击、header selector误抓Month工具栏/时间刻度；原失败与旧选择器JSON保留，最终报告通过对已保存XML重新只读解析真实标题修正，未重放业务操作。API36原旧包RED与工具错误也保留。两台恢复应用13sp与原系统设置（API26原null项删除override、API36恢复1.0），已关闭并确认离线，无事件删除。

## Windows 环境恢复接续

Android验收收尾时主agent再次只读检查发现用户Clender实例已自然退出（未由agent终止）。因此按原构建授权恢复此前暂缓门禁，要求正式harness再次确认无实例/endpoint不可达并建立全新 `t49-windows-final-*` 构建前数据快照；先前197/198环境失败全部保留。此次是在明确环境变化后继续验证，不是无条件重试；最终Windows结果由专用任务记录。

## Windows 最终完成与发布复核

正式harness确认环境自然恢复后，固定Miniconda3.12.4重新执行198 tests全绿（3.713s，命令耗时4.281s），不复用旧197/198失败作PASS；此前30imports与check已通过。完整PyInstaller命令87.047s，唯一正式10cycles/20scenes 129.422s全部通过；原失败/暂停日志均未覆盖。

最终EXE45581390bytes，SHA-256 `08391b3ecec31e71199d561785486c5fbd46061cddec7b2ffd2ed66b72679c9a`。本次实际构建前/后/冒烟后dist/data均5files/198100bytes，路径、大小、UTC和逐文件hash完全一致；最终Clender进程0、QLocalServer不可达、smoke临时目录0。证据 `android/.tmp/t49-windows-final-result.json` 及对应preflight/data/commands/日志，详见Windows子任务。未结束用户实例或修改Windows生产源码。

代码/测试已冻结；最终17文件diff与敏感/生成物审查通过，六冻结文件和七验证源码hash不变，APK/AAB hash保持本节签名摘要。最终Git步骤沿用 `git config --global https.proxy http://127.0.0.1:7890` → 单次整合commit → 普通push到Molotov的codex/android-architecture → 核对远端SHA；不force、不改main、不推GitHub。文件内不预写尚未执行的commit/push成功，最终答复及Git日志提供实际回执。

最终文档纳入索引后的门禁：17文件、forbidden paths=0、常见敏感模式=0，`git diff --cached --check`通过；`verify-boundaries.ps1` exit0、52/52（3.694s），日志 `android/.tmp/t49-final-staged-boundaries.txt`。未暂存差异与未跟踪工程文件均0；此后只追加本回执并进入提交，不改变生产/测试/构建输入。
