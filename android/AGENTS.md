# Android 子工程协作规则

## T62 双端外观接续（2026-09-06）

本轮用户授权PC/Android美化与本地自定义背景，历史任务冻结不禁止此新范围。Android新增BackgroundPolicy/BackgroundStore/BackgroundImageDecoder与Compose BackgroundViewModel/BackgroundController/AppBackground/BackgroundSettingsSection；背景私有目录files/background，图片有界读取20MiB、源图100MP及32768边限制、采样最长2048、ExifInterface旋转/镜像归一化PNG。配置fsync+Files.move(ATOMIC_MOVE,REPLACE_EXISTING)原子保存且读取限4KiB，强度0–100，坏图/配置回退主题；新增背景独立即时保存，UI明确说明，现有主题字号/AI/WebDAV草稿契约不变。Activity ViewModel持有controller及viewModelScope，跨屏幕重建保留busy/进行中导入和结果，取消不得吞掉。

OpenDocument只接受用户选中content图片，不新增媒体权限/网络；依赖仅新增下述精确锁定的官方EXIF库。IO在Dispatcher.IO，取消不得转成错误，busy拒并发写。根Scaffold透明但前景控件保持独立surface；亮暗图层上限分别0.28/0.24，保障可读。Widget、Room、同步与launcher资源不属T62范围。新增背景测试使用临时生成图片与隔离Compose host，完整结果见doc/tasks/T62-android-appearance.md；最终整合/发布由主agent记录。

T62收口阶段证据：背景导入/保存处理中显示双语liveRegion提示并禁用操作。真实API26框架短JPEG漏读曾以EXIF缓冲尾补8192验证修复，后续安全lint指出平台实现旧系统漏洞，该阶段方案已移除。当前固定官方androidx.exifinterface:exifinterface:1.4.2，版本目录/9配置锁/SHA-256验证元数据精确锁定；不再使用android.media.ExifInterface、不抑制安全lint、不补零或自制parser。原689字节短JPEG在API26/36仍保留方向6与20×40归一化断言，全部8方向使用共享矩阵实现验证尺寸和角像素，旧依赖锁坐标/版本/配置宇宙不变。最终验证摘要由T62任务记录与主agent发布记录维护。

T62全量清理诊断：CalendarOverflow既有临时DB删除false再次出现，业务断言通过；ProductionActivityTestResources仅增强失败时匿名文件状态/Room.isOpen诊断，保留原check与完整suppressed，不含路径/内容。单次3 suites/18项聚焦未复现，不宣称根因已修复或必属Windows时序；原失败XML保留，主agent全量继续验收。未添加sleep/GC/重试或修改生产关闭逻辑。

2026-09-07根背景文字继承修复：AppBackground必须同时提供主题onBackground作为LocalContentColor，透明Scaffold和其内未显式指定颜色的Text继承该值；不能依赖透明容器的contentColorFor推导。BackgroundContentColorTest通过真实设置标题及CalendarScreen空态的TextLayoutResult和LocalContentColor验证API26/36、light→dark→light切换；旧实现4项实际颜色断言失败，测试/发布结果归T62与主agent记录。

## T51 Android 本地时间修复（2026-09-06，完成）

任务 `doc/tasks/T51-android-local-time-bugfix.md` 接续8f606fd。AI上下文原将UTC当作本地时间；本轮按每次请求的系统时区将同一instant转换为本地日期/分钟/星期，运行中时区变化下次生效。共享UTC metadata与事项墙钟字段不变，不迁移数据；默认生产两参数provider读取动态系统zone，测试可显式注入zone以保持固定时钟确定性。

维护记录：先新增旧实现可编译失败回归，覆盖UTC+8/跨日/DST/动态时区/UTC消息元数据；不使用上一任务已结束的临时Key。按用户本次明确要求跳过Windows EXE、Windows测试和dist/data读取；该任务级例外不取消Android测试、签名、索引审查与提交门禁。旧实现16tests/6RED已留证，修复后44聚焦及同源码完整160suites/1694tests（UI60/723）通过，零失败/跳过/四类泄漏；96项构建门禁、111发布夹具、62策略与签名审计通过。最终APK SHA256 `82e4e504b8dfd5bd62d4ba22f103874402b872ebe646997f23469b402c7b7e09`。本轮无新设备或云端实测，验证直接覆盖生产默认时间路径及实际请求上下文。

## T50 当前契约（2026-09-06，完成）

接续 1d88fae，见 `doc/tasks/T50-ai-conversation-bugfix.md`。AiEndpointPolicy 对明确末段 `v[0-9]+` 保留 API 版本，其他原有 root/gateway 自动 /v1 行为不变；不得硬编码供应商域名、放开HTTPS/路径校验或改Call释放/Thinking fallback。compact AI 根页通过 AppTopBar 的会话列表/返回按钮切既有pane；宽屏双栏与会话管理/确认仍复用，消息区域不得叠放入口。Key 保存已正常，本任务不再修改存储。AppBar标题固定单行省略，保留完整无障碍语义，避免极大字号换行裁切；Drawer打开时列表BackHandler让位于Drawer。

维护追加（2026-09-06，T50完成）：旧实现路径3RED、实际bounds布局12RED；网络/界面独立子代理实施，主代理串行Gradle与签名设备集成。授权在线测试只含合成内容，真实Key不进入源码、证据文件、日志或提交。全量1680后增量41及最终UI723通过，96项构建门禁/policy57/签名审计通过；最终APK哈希31d027917662adf8108c08769827d09df7ada0b45e4148b0ee9e8a8dab7d27f5。最终API26/36英文极大字号双主题复核通过并恢复关闭；中文设备语言前置未完成，中英文Robolectric标题回归通过。

## T49 当前修复契约（2026-09-06，验收完成）

当前任务 `doc/tasks/T49-android-layout-api-key-bugfix.md` 接续已提交/推送的997090f。Calendar EMPTY/LOADING使用独立布局空间，不能叠在标题/时间轴上；保持既有查询/导航/滚动。AES-GCM ENCRYPT_MODE 不再传调用方 IV，由provider产生后写回原v1 envelope；随机化要求、AAD、alias、解密兼容、原子提交与数组擦除保持。设置UI只从尚未保存的内存草稿恢复字段，不解密回填已保存Key、不写SavedState。真实平台Keystore验收必须覆盖保存、强制停止后重开、KEEP与实际解密使用，不能把JVM软件provider通过当作平台证据。

完整Android JVM158/1657、UI59/701，零失败/错误/跳过/四类泄漏；96tasks构建检查、111发布夹具、52策略与签名审计全部分项通过。API26/36真实Keystore保存、切页、强制停止后重开、KEEP及loopback解密使用通过；6+9张日历布局验收通过。Windows原实例退出后，198项/完整PyInstaller/20场景及dist/data完整性均通过，原环境error保留；下方为上一轮历史。

维护追加（2026-09-06，T49完成）：三处最小生产修复与四份专用Kotlin测试全部RED→GREEN；UI clear effect读取secretInputEmpty布尔快照，保存成功清空不依赖全局dirty。策略只加入四份已授权T49文档allowed_exact，新增3项正负回归后52/52；不得扩大prefix或Windows例外。无依赖/Manifest/Room/schema/Windows生产变更，APK/AAB及七验证源码hash一致，设备已恢复设置并关闭。最终构建摘要/Git回执见主任务及最终答复。

## 2026-09-06 当前完成状态与 Git 交付边界

T47/T48 的实现、构建与验收已完成。最终同源码 JVM 155 suites/1611 tests、UI 57 suites/665 tests，failure/error/skip 及四类泄漏标记均为 0；完整 verify-all、签名审计与设备验收全部完成。Git 交付为最后步骤，提交/推送回执以主 Agent 最终答复与 Git 日志为准；本记录不宣称已经 commit 或 push。下文阶段性的“仍进行中”“待设备”“未完成”及旧包摘要均保留为历史过程，当前状态以本节为准。

最终验收 APK SHA-256：`5d90cbb3d271063ad7b9c3645eb94ede9180bde62c46bde109a09cc0c56a897e`。API26/36 phone 与 API36 tablet 已完成最终包 core 8 步及实际 Widget；API36 phone/tablet 的最终 Drawer 18 步通过。所有 8 台设备的最终包安装与 hash 核验均通过。

增量证据复用保持原事实：API29/31/33 phone 的 core 复用 4cf 候选、Drawer 复用 04 候选；API35 phone 的 core 复用 4cf、Drawer 18 步使用最终包；API33 tablet 的 core 复用 c287、Drawer 18 步使用最终包。date/boot 继续复用 c287 证据。这是已完成的增量验收矩阵，不表述为所有场景都在最终包重新执行。

API26 Widget 原 ADB 清理临时 XML 失败记录保留；`completion-widget-api26-r4-resume-result.json` 为 PASS，恢复时只读确认配置已保存，再续跑自动更新/冷启动。此续跑不抹除原失败。历史 Room teardown 的 NON-REPRODUCIBLE 裁决不变，后续全绿不等于根因修复。

独立签名 key 与配置保持忽略，须由用户安全备份，不输出秘密。最终 Git 交付仍按已授权的统一集成提交与 Gitee `codex/android-architecture` 分支执行；不合并远程 main、不推 GitHub。本次仅同步原六份文档，完成即冻结，不测试、不修改生产、不提交。

2026-09-06 接续授权见根 AGENTS 与 `doc/tasks/T47-T48-android-completion.md`。P2B2b/P2C/T48 按该计划依次实施；下文历史阶段冻结仅约束各历史 checkpoint。本轮允许获准功能所需最小源码/Manifest/策略更新和有证据的失败修复，禁止盲目重试、降低断言或绕过安全约束。

历史过程记录（2026-09-06，首次全量阶段；由下方当前基线接续）：QuickAiActivityOwner 复用 AppContainer 会话/gateway/appearance；非导出 QuickAiActivity 在 owner 访问前验证 owned Widget，草稿仅 ViewModel，LARGE/250×250 绑定第四处 immutable action。QuickAI 接受系统按 Manifest 附加的 EXCLUDE_FROM_RECENTS 位；真实主题 Surface 保证背景/内容色。MainActivity 只在首次 cold 消费启动 Widget Intent，onNewIntent 仍独立严格验证，重建保留用户导航/编辑草稿。P2C 已接 mutation、remoteVisibleChanged、配置、前台、Boot 与日期触发，同一 runtime/coordinator 只读本地；唯一 one-time REPLACE 日期 work，不增加网络/通知/Alarm。Boot 只接受 BOOT_COMPLETED 与空 extras 或唯一非负 Int user_handle；Provider/Boot/LocalRefresh 等待均9秒 finish-once，工作归 app scope。49 policy、111 发布/设备 fixture 已通过；完整1539测试仅历史DB删除teardown失败，零泄漏marker，冻结CalendarOverflow/helper未改，独立诊断与最终产物/设备验收进行中。

## 2026-09-06 当前基线与维护记录

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

历史设备计划（当前最终包及全量结果以上方更新为准）：Drawer 修改前阶段曾冻结源码并进入重新签名。T48 仍处于设备验收与最终提交阶段。API 29/31/33/35 phone 在 4cf 候选包的核心 8 步均通过，当时仅 Widget flags 改动，曾按未受影响范围复用核心证据并计划新 APK 轻量安装/冷启；此次 Drawer 改动涉及核心导航，旧复用结论不能自动覆盖该改动，最终设备验收由主 Agent 重新核对。最低 API 26 phone、API 36 phone、API 36 tablet 继续要求完整最终包验收；API 33 tablet 首次核心验收待补。不得将候选包或历史审计哈希标为最终产物，该历史阶段待补的 APK/AAB 哈希现见上方；签名指纹仍由主 Agent 补录，不标 T48 完成。

QuickAI/P2C 与 Drawer 修复已通过同源码全 JVM；最终签名产物审计与 verify-all 全门禁通过，剩余设备与交付收口仍进行中，不标 T48 完成。独立签名材料已生成并保持忽略，脚本不回显密码/alias，用户须安全备份 key 与配置；当前 APK/AAB 哈希见本轮最新证据，最终证书指纹由主 Agent 补录。

当前 Intent 交付契约：创建端仍为 CLEAR_TOP|SINGLE_TOP（0x24000000）。仅 EditEvent/QuickAI 消费 externalTaskFlags：NEW_TASK（0x10000000）可选，只有存在 NEW_TASK 时才允许附加 BROUGHT_TO_FRONT（0x00400000）；单独 BROUGHT_TO_FRONT 拒绝。QuickAI 的 EXCLUDE_FROM_RECENTS（0x00800000）独立可选。EditEvent 合法 flags 为 0x24000000/0x34000000/0x34400000；QuickAI 为这三种各自可选 EXCLUDE_FROM_RECENTS，另有 0x24800000/0x34800000/0x34c00000。内部导航真实交付仍为 0x24000000，不扩展；Configure/LocalRefresh 及 action/component/package、canonical data、唯一 appWidgetId、平台 owner 等边界不变。

真实 JDB 证据：QuickAI 交付 0x34c00000、EditEvent 交付 0x34400000，均比 ActivityTaskManager 日志多 BROUGHT_TO_FRONT；唯一 appWidgetId、canonical identity 与 owner 正常，仅去除此位同一 Intent 即校验成功。tests-first RED 为 QuickAI 58 tests/8 failures、EditEvent 30 tests/2 failures；完整证据见主任务。

P2C 当前契约：平台 RemoteViews（API 26–30 单尺寸、API 31+ 四尺寸）复用同一 Widget scope/store/coordinator，自动汇合本地 mutation、remoteVisibleChanged、配置、前台、手动、日期与 Boot 的本地更新；远端 apply 不发本地 mutation，不产生上传回路。日期只用唯一 one-time REPLACE WorkManager，禁止 periodic/expedited/foreground、Alarm/通知及后台网络。非导出 Boot 仅允许 BOOT_COMPLETED 与空 extras 或唯一非负 Int android.intent.extra.user_handle；9 秒 finish-once。无实例保持惰性，删除 admission/generation 防迟到，close 在 DataStore/Room 前取消并等待 Widget 工作。

真实 UI 修复：日历/事项顶层新建按钮以 selectedDate 进入现有表单；旋转/recreate 对同一 Detail/Edit ID 保留编辑态，对同日期且已有 form 的 New route 不再 startNew 覆盖草稿。MainActivity 重建不重放旧启动 Widget Intent，onNewIntent 继续独立校验。About 使用真实应用/版本 metadata，资源格式占位符已修复，不显示原样占位符；缺失 metadata 仍显示有限不可用。

Windows 198/198、imports 30/30、完整 PyInstaller 与正式 10 cycles/20 scenes 均通过；EXE 45,582,242 bytes，SHA-256 `C52F386542E78C5FA2E85C4A0B02F942FC2A2481C60694D5D29A33832CCA207D`；dist/data 5 files/196,074 bytes，路径、大小、时间及逐文件哈希前后完全一致。

历史 checkpoint 的“未开始”“QuickAI fail-closed”“无 Worker/Boot”与旧测试数量只描述各自阶段，不是当前基线。保留历史 Room/CalendarOverflow 一次 teardown 失败及 NON-REPRODUCIBLE 裁决；后续全量绿色不构成根因修复证明，不得宣称已修复该历史失败。

本轮已授权最终统一一次集成提交。Gitee 仅创建/推送 codex/android-architecture 远程分支；远程 main 只有 LICENSE 且与本地分叉，不合并 main，不推 GitHub。最终提交前复核 staged 白名单及 boundary，不能用暂存前通过代替索引门禁。

本文件补充仓库根 `AGENTS.md`，不得降低根规则。

- 只允许 Android 应用访问自身 sandbox；禁止读取、复制、测试或打包仓库根 `data/`、`dist/data/`。
- JDK、SDK、Gradle、AVD、临时目录、签名材料与构建产物必须位于本目录的忽略路径。
- 禁止 Google Play services、Firebase、Play SDK、ML Kit、NDK/native runtime 和动态/SNAPSHOT 依赖。
- 秘密只允许 Android Keystore 运行时密文或忽略的 release 签名输入，禁止日志、源码、测试夹具和提交。
- 生产修改先写失败测试；每项任务运行 Android 聚焦测试、安全/边界门禁及根 Windows 全量构建回归。
- release 只允许独立签名配置；缺签名必须失败，不得回退 debug key。
- T43 领域写边界：本地事件 add/update/delete 只能走 `EventService → EventRepository`；Room 不得再暴露会重复注入 metadata 的本地写 API。远端 apply 使用独立的单事务入口且不得发本地 `ScheduleMutation`。
- Room 普通查询必须过滤墓碑，半开 overlap 与同步快照排序不得在 UI 重写；时间持久化统一复用严格 `WallClockCodec`/`UtcInstantCodec`。
- 当前 Room 版本为 v1；空库实际打开与 schema export 是首版基线，任何 v2+ 变更必须加入显式 migration 和跨版本 harness，禁止 destructive fallback。
- T47-P0R 已永久拒绝 `androidx.glance:*` 与 `datastore-*-android` native 路径；主应用继续 Compose，Launcher Widget 只使用平台 `AppWidgetProvider/RemoteViews/AppWidgetProviderInfo`。`work-runtime-ktx/work-testing:2.11.2`、producer merged manifest 与 one-time WorkManager 边界保持冻结；禁止 periodic/expedited/long-running/foreground work、精确 alarm、通知和后台网络。
- T47-P2A 已冻结只读生产闭环：Provider 只处理 update/options/delete 并用 `goAsync()` 确定性 finish；app-scoped coordinator 通过抽象 ports 读取现有配置/appearance/repository，复用 P1 state/presentation，按实例 generation、8 秒上限和 delete 失效隔离竞态。API 26–30 只走 options 单尺寸，API 31+ 只走四项 responsive map；原始 root/date/status/event rows 继续无点击或 collection service。
- T47-P2B1 已冻结配置闭环：exported 配置 Activity 必须在 container 前验证精确 action/canonical data/正 ID/provider ownership；草稿只含非秘密 WidgetConfiguration 字段，dirty cancel/back 显式确认，单次 upsert 后只请求目标本地重建。header Configure 保持 explicit immutable getActivity；生产 `ProductionWidgetRenderSink` 必须将目标 `appWidgetId` 传给 renderer，确保实际发布的单尺寸/响应式 RemoteViews 绑定正确实例；provider-info 只允许 configure 与 optional/reconfigurable。
- T47-P2B2a 已冻结 action 闭环：共享 strict contract 验证 EditEvent/LocalRefresh 的显式 component/action/package/canonical data/extras/provider ownership；合法 EditEvent 只建立既有 `EVENTS + EventDetail`，非法热 Intent 不改变当前导航/草稿。LocalRefresh 只能进入 enabled/non-exported/无 filter Receiver，在 container 前验证后以 finish-once/9 秒上限复用 app-scoped runtime，消费 `MANUAL_LOCAL_REFRESH` 并经同一 coordinator 只重建目标。Configure 与 EditEvent 共用唯一 immutable `getActivity` 创建点，Refresh 使用唯一 immutable `getBroadcast`，只显示于 LARGE/250×250；QuickAi、Worker/Boot、网络、EventService/mutation 与第二 runtime 继续禁止。
- 当前 Android JVM 基线为 124 suites/1263 tests，`ui.*` 52 suites/607 tests、P1 6 suites/53 tests、P2B2a 组合 9 suites/119 tests、OkHttp 16/16，SQLite/Room CloseGuard 四类 0；generated/boundary 各 44/44，84 active + stale `androidApis` = 85 configuration universe。
- T47-Baseline4/4R/5R2 测试基础设施已收口：Room 冷 Flow 测试在 mutation 前必须等待真实首次 emission；Windows frozen smoke 固定使用 exact-path onefile cohort 与 QLocalServer quiescence；change-boundary 只允许 `scripts/verify_frozen_single_instance.py` 与 `tests/test_frozen_single_instance_smoke.py` 两个 repo-relative exact path，禁止 prefix、wildcard 或第三项。Baseline5 CalendarOverflow teardown 红灯为 `NON-REPRODUCIBLE`，禁止无证据修改失败类、共享 helper 或生产代码。
- 门禁分类固定：至少一个 test/task 已开始后的 assertion、编译、lint、构建或运行时失败才是 `TEST_GATE_FAILURE`，必须立即 STOP 且不得重试；0 tests/tasks executed 且明确由 cwd、参数、脚本定位或启动前 sandbox/lock 拒绝造成、无状态变化的为 `PRE_EXECUTION_INVOCATION_ERROR`，必要命令固证后只可精确纠正一次，冗余命令不得补跑。Android wrapper 必须从 `android/` cwd 调用，policy discovery 必须从 `android/tests/policy/` cwd 调用。

维护追加（2026-09-06，BROUGHT_TO_FRONT）：本次仅原六份文档同步1577测试基线及受限系统flags、CI fixture、设备证据复用；96tasks的ktlint失败与纯空白修复分开记载。源码冻结重签名中，最终boundary/设备/提交尚未完成，历史NON-REPRODUCIBLE不改。

维护追加（2026-09-06，Drawer）：仅原六份文档补充 AppDrawerState target 同步与 Back 动画去重契约，记录24case/8task聚焦证据；旧153/1577明确为历史，不标T48完成。

维护追加（2026-09-06，同源码最终 JVM）：154 suites/1601 tests、UI57/665与四类泄漏标记全0，最终签名APK/AAB审计PASS及摘要已录；API29 Drawer18步/API26 core8步通过。lint/boundary/其他设备仍进行，T48未收口。

维护追加（2026-09-06，最终门禁）：verify-all wrapper PASS/exit0，96tasks6m25s、111fixtures、foundation49/boundary49全过，154/1601与UI57/665全0、四leak0、六冻结hash零漂移。此前lint/boundary进行中记录为历史，当前仅剩设备与交付收口，T48未完成。

维护追加（2026-09-06，Refresh主题颜色）：原六份文档同步LARGE Refresh使用colors.foreground及10RED→10GREEN、静态8tasks35s证据；154/1601保留为上一full PASS。当前新包/全量/设备待验，T48未完成。

维护追加（2026-09-06，Refresh后全JVM）：155suites/1611tests、UI57/665与四leak全0；新APK/AAB摘要已录，签名审计PASS且证书不变。wrapper后半lint/boundary仍进行，T48设备收口中；154/1601仅历史。

维护追加（2026-09-06，Refresh后完整门禁）：verify-all PASS/exit0，96tasks5m39s，111fixtures及foundation49/boundary49通过；155/1611、UI57/665与四leak全0，六冻结零漂移。当前无进一步生产改动；T48仍待设备矩阵与主Agent最终收口。

维护追加（2026-09-06，验收完成）：T47/T48实现、构建及验收完成，8设备最终安装/hash通过；最终包与候选包证据按上方精确矩阵复用，原失败保留。Git交付为最后步骤，本记录未宣称commit/push；六份文档冻结。
