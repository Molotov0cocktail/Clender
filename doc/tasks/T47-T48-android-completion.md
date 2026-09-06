# Android 完成与构建（2026-09-06）

## 授权、目标与非目标

用户本轮授权自主分配子 agent、实现剩余 Android 功能直至完成构建；不影响最终产品功能的实施决策以效率优先。起点为干净的 `2f0925a`。本文件接续历史 T47-P2B2b STOP，保留全部历史证据；本轮允许在记录原因和回归测试后修复编译、lint、测试问题并重新验证，禁止无改动循环重试掩盖失败。阶段完成后继续 P2C/T48，不再套用历史单阶段停止要求。

目标：完成已设计 Quick AI、自动本地 Widget 刷新、签名 APK/AAB 和设备/Windows 验收。非目标：新产品功能、依赖升级、Glance/native、后台网络、真实服务、Windows 生产改动、数据迁移。

## 设计、接口和数据

P2B2b 复用既有详细任务契约：严格 owned Widget Intent、唯一 immutable PendingIntent 创建点、LARGE AI、非导出 QuickAiActivity、既有活动会话和 app-scoped AI、有限 UI、安全冷/热主应用导航。不得把草稿和秘密持久化到 Intent/SavedState。

P2C 复用 P1 refresh policy 与现有 coordinator：本地 mutation、远端可见变化、配置、前台、日期和 boot 只刷新本地 Widget；单 app-wide unique one-time 日期 work，无实例时取消；使用既有 scope/store，显式关闭资源。不得触发 AI/WebDAV 或本地 mutation 回路。

T48 复用固定工具链、锁和签名策略，提供可复现脚本与产物校验；最低 release 设备线仍需真实执行，无法执行须如实记录，不能标为完成。Room/schema/wire 不变。

## 写集与控制提示

主 agent：任务/progress/两级 AGENTS/设计/共享装配/Manifest/策略、最终验证与提交。各 agent 先测试，返回预期红灯后才由主 agent发实施 follow-up；禁止并发 Gradle，共享文件修改前协调。

- A：WidgetActionIntentContract、WidgetPendingIntentFactory、WidgetRemoteViewsRenderer、Widget layout/AI label 资源及这些类测试（包括 LARGE 旧断言契约迁移）。
- B：新增 QuickAiActivity 及专用 UI/ViewModel/测试，不改 A 文件；使用 A 将提供的 validateQuickAi 与内部导航构造 API。
- C（后续）：MainActivity Quick AI 冷热导航及其专用 router/tests。
- D（后续）：P2C runtime/worker/boot 与对应 tests；共享 AppContainer/Manifest 由主 agent协调。
- 发布审计 agent：只读确认 T48 工具链、设备和签名条件。

## 测试矩阵（实施前冻结）

|修改|正常|边界|非法/异常|回归|
|---|---|---|---|---|
|Quick AI action|owned cold/hot、AI/settings|26/36、多实例、四尺寸|错 envelope/ID/owner/已删除、额外字段|Configure/Edit/Refresh 保持，只有 LARGE AI|
|Quick AI UI|active ready、send、导航|旋转、8/20sp、中英、200% font|未配置、busy、加载/网络失败、非法热入口|后台取消、无自动发送、无秘密/正文泄露，既有超时/非200/畸形/危险 AI 用例|
|刷新|mutation/remote/config/foreground/date/boot|无实例、多实例、DST、删除/并发|本地失败/超时/close|不联网、不发本地 mutation、8秒查询/9秒receiver、one-time only|
|发布|签名APK/AAB、安装/冷启/CRUD/Widget|API26/36 phone、36 tablet及扩展矩阵|无签名/离线/无配置|完整JVM/lint/policy/no-native/锁、Windows198/import/build/exe/data不变|

## 实施与完成定义

- [x] 读取契约、确认干净起点、明确写集与测试矩阵。
- [x] P2B2b 测试先行、实现、审查、聚焦门禁。
- [x] P2C 详细测试先行、实现、审查、聚焦门禁。
- [x] T48 脚本、签名构建、设备矩阵、安全审计；最终统一完整Android/Windows回归、PyInstaller/exe与文档。Git交付为最后步骤，回执见最终答复。

风险：历史资源释放失败必须留完整证据；并行编辑须守写集；模拟器/下载/虚拟化可能限制验收；签名不输出秘密。回滚只对本轮精确 diff/提交操作，不清理用户改动或运行数据。产物和秘密不提交。每阶段记录实际命令、结果与剩余风险后才能完成。

执行效率决定：依据用户本次“自行决策、效率优先、直至完成构建”授权，本轮视为一个完整工程变更；各子阶段保留独立RED/聚焦验收，最终对同一完整源码执行全门禁、Windows完整打包/正式冒烟与一次整合提交。取消没有产品收益的中间重复全量打包，不降低任何最终测试或数据完整性要求。P2B2b聚焦通过即可进入P2C；中间签名构建只用于提前暴露真实工具链问题，不作最终产物验收。

## 2026-09-06 执行证据

- Windows修改前全量：Miniconda `-m unittest discover -s tests -v`，198/198，3.443s。
- 本轮 `dist/data` 前置只读快照：5 files / 196074 bytes；与旧报告差异保留，不访问正文，以本轮前置逐文件路径/大小/UTC/哈希为比较基准。
- Manifest先行测试2项红灯：缺QuickAiActivity、renderer只有3处绑定；新增非导出Activity后exact组件策略1/1绿。
- QuickAI `:app:compileDebugUnitTestKotlin` 红灯：21 tasks、42s，计划内缺失Activity/Owner/导航API/AI资源；另发现测试多余`assertDoesNotExist`顶层import并交B修正。保留`.tmp/completion-quick-ai-red.txt`，通过审查后分派A/B/C实现；未降低既有安全断言。
- release工具28项测试首次30个缺脚本断言红灯，`.tmp/completion-release-tools-red.txt`；允许独立agent实施工具，发布构建仍待T47完成。
- Emulator37.1.11、adb37.0.1、WHPX可用；已下载API26/29/31/33/35/36 AOSP default x86_64并新建6 phone+2 tablet AVD。没有覆写已有AVD/key。
- SDK/Gradle两次沙箱均在日志创建前拒绝（0 tasks）；宿主权限运行相同命令后执行。不同于测试失败，未作为重试测试处理。
- QuickAI 聚焦第三轮：29 suites/321 tests、failure/error=0，detekt与测试ktlint通过；MainActivity一处格式问题已修正，待最终全量确认。前两轮真实失败及XML保留在`.tmp/completion-quick-ai-failure-1/`和对应日志，未隐藏失败。
- API26真实Launcher添加、配置、扩为LARGE成功，AI/Refresh/Configure均在真实host出现。QuickAI点击后立即finish；JDB仅附加本次debug模拟器，onCreate断点证明Intent flags从创建时`0x24000000`变为交付时`0x24800000`，系统按Manifest excludeFromRecents增加`FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS`。无crash；增加精确系统交付回归后再修复，只允许QuickAI此一附加位，其他envelope/owner检查保持。该平台差异不是单元测试PASS即可豁免的问题。
- 新建事项真实入口缺失另记T48-android-create-event-entry；旧实现10项测试中6项因按钮不存在失败，已保留RED与XML，再授权最小入口实现和生产保存/取消/旋转验证。
- release helper 33/33、秘密/主机路径策略1/1通过；实际缺签名validateReleaseSigning先获预期RED，再创建独立ignored release key，未读取/显示凭据。官方bundletool1.18.1 all jar SHA-256=`675786493983787FFA11550BDB7C0715679A44E1643F3FF980A529E9C822595C`（本地实测，非上游公布校验和）。
- QuickAI平台RED：44 tests/4 failures（API26/36 contract与Activity各一），完整XML保留`.tmp/completion-quickai-flags-failure/`；修复后组合31 suites/351 tests、failure/error/skip=0，新建保存/取消/旋转同样通过。仅3处新增代码格式/嵌套静态问题，已精确修正待复验。
- 首轮release签名Gradle成功：APK 1708938 bytes、AAB 4574146 bytes，API26真实安装及冷启731ms成功。审计因OkHttp logger的MockWebServer字符串误报停止，实际mapping零对应类；以新增fixture和DEX类型审计修复，不能跳过产物检查。
- 发现android/.gitignore既有`release/`规则也忽略新tests/release/*.py；`git check-ignore -v`已证明两个测试文件被意外排除。写集加入该gitignore，仅对tests/release下两个明确Python测试路径增加例外；验证正常测试可追踪、release key/APK/AAB与秘密仍忽略，提交前检查遗漏。无运行接口影响，回滚仅删除例外行。
- QuickAI/创建入口最终聚焦静态检查 `ktlintCheck detekt` 通过（8 tasks，31s）。
- 发布/设备工具组合101/101通过（2.393s）；实际首轮签名APK/AAB完整审计通过。新增DEX类型表、Build Tools 36 `minSdkVersion` badging与完整Android namespace URI夹具，保留原安全断言；该产物仍非最终P2C版本。
- Windows最终198/198（3.751s）、生产导入30/30、build check和完整PyInstaller通过。EXE 45582242 bytes，SHA-256 `C52F386542E78C5FA2E85C4A0B02F942FC2A2481C60694D5D29A33832CCA207D`。唯一正式frozen smoke 10 cycles/20 scenes全部通过，readiness/secondary exit 0/primary survival/cleanup/quiescence均通过；测试后Clender进程0。
- Windows构建与正式smoke前后 `dist/data` 5 files/196074 bytes，路径、大小、UTC、逐文件SHA完全一致。中间PowerShell数组/时间序列化与NativeCommandError处理错误为编排错误，修复后完整构建才计PASS；不隐藏或重试应用测试失败。
- P2C测试先行缺接口编译红灯已保留；装配编译确认缺WebDav通知参数。增加callback后7项WebDav测试中2项证明既有取消处理吞掉已提交remoteVisibleChanged，XML保留`.tmp/completion-webdav-widget-red-results/`。修复仅将SyncCancellationException交回既有SyncCoordinator处理，保持CANCELLED状态、gate先释放和零mutation；正在统一验证。

### 最终源码门禁（2026-09-06）

- 首次完整集成保留 151 suites/1539 tests 的单次 CalendarOverflow teardown 数据库删除失败；业务断言与泄漏扫描无失败。未改历史 CalendarOverflow 测试或共享 ProductionActivityTestResources；增加独立 Room 关闭诊断后，后续 152/1547 与最终 153/1573 均全绿。诊断不能证明所有关闭/查询交错已覆盖，也不认定历史故障根因已修复，详见 T48 Room 诊断记录。
- 真实 API36 Launcher 给 EditEvent/QuickAI 的启动 Intent 添加 NEW_TASK（activity log `0x34000000`）；先运行 92 tests/22 failures 的行为 RED，再精确允许此系统位。QuickAI 另保留已实证的 EXCLUDE_FROM_RECENTS；内部导航、LocalRefresh、身份/owner/extras 校验与创建 flags 不变。About 实机 `%1$s` 未格式化问题同批先 RED 后修复。
- 最终命令：`scripts/gradle.ps1 --offline --no-daemon --max-workers=1 --dependency-verification strict testDebugUnitTest lintDebug lintRelease detekt ktlintCheck verifyReleaseSigningPolicy verifyNoGoogleServices verifyNoNativeRuntimeArtifacts verifyResolvedVersionsLocked assembleDebug verifyDebugApkNoNativeArtifacts :app:processDebugMainManifest :app:processReleaseMainManifest --continue`。
- `.tmp/completion-android-launcher-final-gates.txt`：BUILD SUCCESSFUL，6m36s，96 tasks（35 executed / 61 up-to-date）；153 suites/1573 tests，UI 56 suites/641 tests，failure/error/skip 全部 0。CloseGuard、SQLiteConnectionPool、SQLiteDatabase leaked、RoomDatabase leaked 扫描全部 0。完整 XML 保存于 `.tmp/completion-android-final-results/`。
- 发布/设备工具最终夹具：111/111，3.292s，`.tmp/completion-release-device-fixtures-final2.txt`。策略先前 49/49，暂存后再执行最终 boundary；六个冻结构建/依赖文件逐一 SHA-256 相同，configuration universe 85。
- 独立复核未发现新的生产缺陷。最终签名构建和设备矩阵继续进行，不能由 JVM 全绿推定设备通过。

### Launcher 最终交付字段的实机闭环

`4cf6e0e…410f566` 候选 APK 的 API36 phone/tablet 核心八步全部通过，但真实 QuickAI 仍拒绝；此候选不能视为交付验收通过。API36 phone 使用同源码 debug 变体与 JDB，直接在 `QuickAiActivity.onCreate` 观察到 flags=`0x34c00000`、唯一 extra=`appWidgetId`、canonical data=`clender-internal://widget/4/quick-ai`。系统 start 日志只显示 `0x34000000`，漏掉后加的 EXCLUDE_FROM_RECENTS 与 BROUGHT_TO_FRONT。原始 Intent 校验为 null；仅在 debug 暂停帧移除 BROUGHT_TO_FRONT 后，同一对象校验为 `QuickAi(widgetId=4)`，页面正常显示。

真实事项行在 `MainActivity.onNewIntent` 得到 `0x34400000`；同样原始校验 null、只移除 BROUGHT_TO_FRONT 后为 `EditEvent(widgetId=4,eventId=1)`。QuickAI 内部“查看会话”实际交付保持 `0x24000000`，严格校验正常，不扩展内部导航。以上调试内存改动只用于定位，不计发布包功能通过；诊断结束后已移除 debug 应用、8700 转发并 clear-debug-app。长断点造成的 debug FocusEvent ANR 保留，不混入正式发布包验收。

先行 RED：QuickAI 58 tests/8 failures，1m28s；EditEvent 30 tests/2 failures，45s，XML分别保存 `.tmp/completion-brought-to-front-quickai-red-results/` 与 `…edit-red-results/`。修复仅抽出 `externalTaskFlags`：BROUGHT_TO_FRONT 只在 NEW_TASK 存在时可选；QuickAI 保留独立 EXCLUDE 可选，EditEvent 仍拒绝 EXCLUDE；创建 flags、internal navigation、LocalRefresh 和全部身份/owner/envelope 校验不变。新增真实组合回归后全量 JVM 为 153 suites/1577 tests、failure/error/skip=0，静态门禁和新签名包验收继续执行。

设备增量验收决策：最低 API26 phone/API36 phone/API36 tablet 仍完整验证最终签名 APK 的核心与真实 Widget。扩展 API29/31/33/35 phone 的核心八步已在 `4cf6e0e…` 通过；此次之后仅修改 Widget Intent 校验，核心代码不变，复用这些明确绑定候选哈希的核心证据，并对新包补安装/冷启动/版本/导航检查；API33 tablet 尚缺首次核心证据，必须补齐。不得把候选版本 Widget 失败或 helper 的 BLOCKED 状态改写为 PASS。

### Drawer 缺陷后的最终验收调整

`c287f7cc…` 上 API33 tablet 已补齐 core8。API29 发现真实遮罩关闭后无法重开 Drawer，独立 `T48-android-drawer-state.md` 已先写回归测试，旧实现20项/18失败，保留完整 XML；swipe 已通过隐藏断言却在状态 false 断言失败，scrim 在仍可见断言失败，二者不得混淆归因。发布保持未完成。

修复后对 API26 phone、API36 phone、API36 tablet 执行最终包 core8、实际 Widget 与 Drawer 回归；API29 优先复现原操作链并要求实际关闭、重开、目的地页面可见，其余 API31/33/35 phone、API33 tablet 补最终包安装/哈希/冷启和相同 Drawer 导航增量。保留此前各候选 core8、失败与 helper BLOCKED 原记录。

日期边界/真实 reboot 已在 `c287f7cc…` 验证；此次仅 Drawer 状态同步发生生产变化时，可沿用绑定旧包哈希的证据，不宣称在新包重跑。已保存除 `ClenderApp.kt` 外166个生产文件的 SHA-256 (`android/.tmp/completion-pre-drawer-production-hashes.json`)，最终比对确保 P2C、Manifest、装配及资源未漂移；新增专用 Drawer helper 单独审查。此效率决策不豁免新发现缺陷的实际回归，不扩大为真实手机、Doze 或精确时钟保证。

### Drawer 全量通过与 Widget 视觉复核

Drawer 24项聚焦回归通过，修复后的完整 `verify-all.ps1` 返回0：154 suites/1601 tests、UI57/665，failure/error/skip与四类泄漏标记均0；Gradle96tasks（30 executed/66 cached）、6m25s，发布工具111/111（2.433s）、foundation49/49（3.936s）、boundary49/49（3.337s）。完整日志与XML为 `android/.tmp/completion-drawer-full-final-gates.txt`、`…drawer-full-final-results`；六冻结文件摘要零漂移。

该源码签名 `04b4681c…454873d` 在 API29/31/33 phone 的安装/哈希/版本/冷启及18步 Drawer 全部通过，API26 core8（含双向旋转）和18步 Drawer通过；真实 Widget Refresh/QuickAI/内部AI设置与会话/配置/自动创建和热编辑均通过。API26 无用户参数的 `am kill` 未移除进程，原冷入口准备失败保留；读取进程与服务证据后，以系统 Settings→HOME、明确 `am kill --user 0` 证明进程消失，随后真实 Widget 事项冷跳通过，不用 force-stop/root。证据 `android/.tmp/completion-api26-cold-explicit-user-result.json`。

视觉检查 `…completion-final-widget-api26phone-configuration.png` 发现 Dark Refresh 默认黑字，独立任务 `T48-android-widget-refresh-color.md` 已先写主题/平台/实际RemoteViews矩阵，旧实现10/10失败后，仅在LARGE分支增加一行 `setTextColor(widget_refresh, colors.foreground)`。不改字体、布局、点击、Intent、状态或日期/重启调度；重新构建后继续最终包验收，保留上述候选证据。P2C 日期/reboot复用仍限于未变行为，不将颜色候选混为最终包。

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

最终暂存复核：111个工程文件，forbidden paths=0、private key/API key/Bearer敏感模式=0，未暂存与未跟踪工程文件=0；`git diff --cached --check`通过。最终文档纳入索引后 `verify-boundaries.ps1` exit0、49/49（2.876s）。独立审查 agent 对最终111文件复核，未发现阻止提交的具体问题，Drawer/Refresh与已验证源码一致。以上检查完成后仅追加本回执，不改变生产源码、测试或构建输入。
