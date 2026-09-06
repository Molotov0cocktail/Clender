# T47-P2B2b：Launcher Widget Quick AI Activity

## 当前状态（2026-09-06，最终 5d90 包）

QuickAI 实现及系统 flags 修复已完成，不处于源码待修或待 RED 阶段。最终完整 JVM 155 suites / 1611 tests，failure/error/skip 全部 0，静态全部 PASS。外部 QuickAI 入站保留必需 CLEAR_TOP/SINGLE_TOP，NEW_TASK 可选、BROUGHT_TO_FRONT 仅在 NEW_TASK 存在时可选、EXCLUDE_FROM_RECENTS 独立可选；EditEvent 不允许 EXCLUDE，内部 QuickAiNavigation 与 LocalRefresh、创建 flags、token identity、owner/envelope 均保持严格既有边界。

最终签名 APK SHA-256 为 `5d90cbb3d271063ad7b9c3645eb94ede9180bde62c46bde109a09cc0c56a897e`。主 agent 已在 API26 真实 Launcher 验证 QuickAI 打开、未配置提交保留 draft、View AI 无 messages、Open settings 到 HTTPS endpoint 页面，全部 PASS；步骤记录见 `android/.tmp/completion-final-widget-api26-r4.json` 及对应实际 XML。

主 agent 最终回执：T47/T48 实现、构建与验收已完成，全门禁完成。最终 `5d90` 包在 API26/36 phone 与 API36 tablet 的 core8 及实际 Widget 全部通过，API36 phone/tablet 最终 Drawer18 通过。最终 JVM 155 suites / 1611 tests、UI 57 suites / 665 tests，failure/error/skip 及四项泄漏扫描全部 0。Git 交付是最后步骤，具体回执以最终答复/Git 日志为准；本文不声称已经 commit 或 push。

保留增量验收事实：8 台设备均完成最终包安装/hash 验证；API29/31/33 phone 的 core 复用 `4cf`、Drawer 复用 `04` 候选证据；API35 phone 的 core 复用 `4cf`，最终 Drawer18 通过；API33 tablet 的 core 复用 `c287`，最终 Drawer18 通过。P2C date/boot 仍复用 `c287` 证据，不表述为最终包重新执行。API26 Widget 原 ADB 清理临时 XML 失败保留，`completion-widget-api26-r4-resume-result.json` PASS 记录只读确认已保存状态后续跑自动更新/冷启动，不覆盖原失败。本文档及本任务写入现已冻结。

以下按时间保留历史诊断、RED 和实施过程；其中“待实施”“待 RED”“待复验”及旧候选失败均是当时状态，当前状态以上节为准，不删除或掩盖历史失败。

## 历史：2026-09-06 Activity 交付 BROUGHT_TO_FRONT 回归（RED 后最小修复）

主 agent 在 API36 phone debug 的真实 Launcher QuickAI `onCreate` 入口通过 JDB 实证：`this.getIntent().flags = 0x34c00000`（十进制 884998144），extras.keySet 仅 `[appWidgetId]`。ActivityTaskManager prelaunch 日志仍为 `0x34000000`，未体现稍后交付增加的 EXCLUDE_FROM_RECENTS 与 BROUGHT_TO_FRONT (`0x00400000`)。此前最终 1573 项 JVM 绿色未覆盖该平台真实交付形状，不能替代签名包实机入口验收。

同一暂停帧的单变量对照进一步定位根因：未修改 Intent 时调用 `WidgetActionIntentContract.INSTANCE.validateQuickAi(this, intent)` 返回 null；仅将 flags 从 `0x34c00000` 改为 `0x34800000`（移除 BROUGHT_TO_FRONT），同一对象、同一 validator 随即返回 `QuickAi(widgetId=4)`。data 精确为 `clender-internal://widget/4/quick-ai`，extras 仅 appWidgetId。该对照证明此次唯一拒绝因素是 BROUGHT_TO_FRONT，排除了此次 owner 或其它 envelope 不匹配；不意味着这些边界可以放宽。

内部导航已另行 JDB 实证：debug 暂停帧移除原始 BROUGHT_TO_FRONT 使 QuickAI 正常打开后，点击 View AI conversation，`MainActivity.onNewIntent` 参数 action 为 `WIDGET_QUICK_AI_OPEN_CONVERSATION`，flags 精确为 `0x24000000`，不含 NEW_TASK/BROUGHT_TO_FRONT/EXCLUDE_FROM_RECENTS。因此内部 QuickAiNavigation 继续保持严格原始 flags，不扩展；该直接证据覆盖本次 conversation 导航，Settings 目标未据此宣称已实机验证。

EditEvent 现亦有直接 JDB 实证：API36 phone Launcher 点击真实事项行后，`MainActivity.onNewIntent` 参数 flags 为 `0x34400000`（NEW_TASK/CLEAR_TOP/SINGLE_TOP/BROUGHT_TO_FRONT），action 为 `WIDGET_EDIT_EVENT`，package/component 正确。现有 EditEvent BROUGHT_TO_FRONT 测试可据此纳入 RED，不再只是同类平台行为候选。当前主 agent 正运行 QuickAI 两个 suite 的 RED；本次仅补录文档，所有测试及生产保持冻结，生产修复仍须等待 RED 后授权。

本轮待实施契约和测试矩阵限定外部 Widget Activity 入站：在已有合法 NEW_TASK 组合上允许 BROUGHT_TO_FRONT；QuickAI 新增 `0x34400000`、`0x34c00000`，覆盖 API26/36、冷/热、READY、草稿与模型保持、零自动提交；EditEvent 新增已由 JDB 证实的 `0x34400000`。所有合法基底继续逐位验证其它非法组合，owner/envelope 校验不变。无 NEW_TASK 的 BROUGHT_TO_FRONT 组合仍保留拒绝测试，等待证据再决定是否需要支持。

修复前证据已由主 agent 保留：QuickAI RED 为 58 tests / 8 failures，1m28s / 32 tasks（3 executed、29 cached），XML 位于 `.tmp/completion-brought-to-front-quickai-red-results`；EditEvent 两 suite RED 为 BUILD FAILED，45s / 32 tasks（1 executed、31 cached），XML 位于 `.tmp/completion-brought-to-front-edit-red-results`，失败与合法 `0x34400000` 被拒一致。EditEvent JDB 同对象单变量对照亦成立：原 `0x34400000` 返回 null，仅去除 BROUGHT_TO_FRONT 改为 `0x34000000` 后返回 `EditEvent(widgetId=4,eventId=1)`。

主 agent 放行后已仅修改 `WidgetActionIntentContract.kt`：两个外部入站 validator 共用私有 `externalTaskFlags`，仅在 NEW_TASK 存在时计入可选 BROUGHT_TO_FRONT；QuickAI 的 EXCLUDE_FROM_RECENTS 仍独立可选，EditEvent 仍拒绝 EXCLUDE。当前精确允许 QuickAI 六种 flags：`0x24000000`、`0x24800000`、`0x34000000`、`0x34800000`、`0x34400000`、`0x34c00000`；EditEvent 三种：`0x24000000`、`0x34000000`、`0x34400000`。内部导航、LocalRefresh、创建 flags、token identity、owner/envelope 均保持不变。测试冻结未重写，未运行 Gradle/adb；实现已冻结，等待主 agent 统一复验及签名包实机验收，尚不宣称修复后 PASS。

风险：系统 START 日志与 Activity 实际 Intent 不等价；不能据日志省略后续平台变换，也不能据此接受未知位或额外 extras。内部 QuickAiNavigation 是否受同类变换影响由主 agent 单独 JDB 验证，本轮不改其契约或测试；LocalRefresh、创建 flags、token identity 均不扩展。写集仅本任务文档及专用 Intent/Activity 测试，生产先不修改，不运行 Gradle/adb。主 agent 统一 RED 后才授权生产修复；回滚只撤回本轮差异。下节四种 QuickAI flags 是上一轮已实现状态，本节新增组合尚待 RED/实施。

## 2026-09-06 API36 Launcher 系统 NEW_TASK 交付（RED 后修复）

实机 release 日志 `.tmp/completion-widget36phone-activitylog.txt` 在 04:31:19 的 START 明确记录 `WIDGET_QUICK_AI flg=0x34000000`：Launcher 在原 PendingIntent `CLEAR_TOP|SINGLE_TOP (0x24000000)` 上增加 NEW_TASK，Activity 交付还可能增加 Manifest excludeFromRecents。用户报告该入口被现有 validator 拒绝并立即 finish。API26 的 EXCLUDE_FROM_RECENTS 修复不能覆盖此 API36 系统形状。

授权最小写集：`WidgetActionIntentContract.kt`、专用 Intent/Activity 测试。先仅测试，主 agent 统一 RED 后才修改 validator。QuickAi 允许原始 flags 加 NEW_TASK/EXCLUDE_FROM_RECENTS 的独立或组合两位；EditEvent 的同 getActivity 系统交付增加 NEW_TASK 用例。主 agent 已确认 `.tmp/completion-widget36phone-edit-activitylog.txt` 的 WIDGET_EDIT_EVENT 为 `flg=0x34000000`，对应 `.tmp/completion-widget36phone-edit-kill-before.xml` 停在 Calendar 而非 EventDetail。PendingIntent 创建 flags 不变，内部 QuickAiNavigation 与 LocalRefresh 不扩展 flags。

本轮 tests-first 已落入 `WidgetQuickAiIntentContractTest`、`QuickAiActivityTest`、`WidgetEditEntryContractTest`、`MainActivityWidgetEditEntryTest`，包含 QuickAI 两种 NEW_TASK 冷入口、两种热入口草稿/模型保持、EditEvent 真实 MainActivity 冷/热导航与零 mutation。既有将 NEW_TASK 当未知位的负向用例迁移为 URI grant 禁止位；其它 envelope/owner 断言保留。主 agent 实际 RED 为 92 tests / 22 failures，其中 NEW_TASK 相关 14 failures，完整 XML 保存在 `.tmp/completion-launcher-about-red-results`。经授权仅对入站 validator 增加精确可选位，并修正新增测试局部格式，无 Suppress。

最终入站 flags 契约：必须包含 `CLEAR_TOP|SINGLE_TOP (0x24000000)`；QuickAI 仅可选 `NEW_TASK (0x10000000)`、`EXCLUDE_FROM_RECENTS (0x00800000)`，因此仅接受 `0x24000000`、`0x24800000`、`0x34000000`、`0x34800000`；EditEvent 仅可选 NEW_TASK，仅接受 `0x24000000`、`0x34000000`。缺必需位或包含其它任何位仍拒绝。内部 QuickAiNavigation 仍精确 `0x24000000`，LocalRefresh 仍精确 `0`；创建 flags、PendingIntent token identity、canonical data、owner 与 envelope 验证均不变。

主 agent 最新最终 `testDebugUnitTest` XML：153 suites / 1573 tests，failure/error/skip 全部 0。本记录更新时 lint 仍运行，最终签名包实机重验仍进行中，不将 JVM 绿色视为最终发布验收完成。本子任务未运行 Gradle/adb；只读 `git diff --check` 已通过（仅既有 CRLF 提示）。

测试矩阵：API26/36；QuickAi 原始/NEW_TASK/EXCLUDE/两位组合验证与冷启动、合法热入口保留草稿；EditEvent NEW_TASK 验证；每个合法 flags 基底逐位翻转其余 30/31 位全部拒绝（包括缺 CLEAR_TOP/SINGLE_TOP）；系统位叠加后仍拒绝非法 owner/action/component/package/canonical data/extra/selector/clip/categories；内部导航和 Refresh 保持逐位精确拒绝。保持既有 owner/envelope、取消、旋转、秘密及网络边界，不执行 adb/Gradle，由主 agent 保留 RED 与设备证据。

## 2026-09-06 Quick AI 主题背景回归（tests-first）

接续当前集成授权，平台修复之外新增独立主题写集：`widget/QuickAiActivityScreen.kt`、`widget/QuickAiActivity.kt` 的主题容器，以及新 `widget/QuickAiActivityThemeTest.kt`。主 agent 统一执行 RED 后才修改生产，不修改共享 ClenderTheme、runtime、Intent validator、Manifest 或既有测试。

只读定位：ClenderTheme 仅提供 MaterialTheme 配色/字体，QuickAiActivityScreen 根为透明 Column，Activity 没有绘制页面背景/提供背景对应 content color 的容器。Theme.Clender 窗口底色不能随 app appearance Flow 中的 Light/Dark 选择同步，因此页面空白区域没有消费当前 Material 背景色。使用实际 Activity 绘制像素证明缺陷，而非检查源码包含 Surface 或模拟主题函数实现。

验收矩阵：API26/36，真实 QuickAiActivity 的 Light/Dark/System 背景像素应为当前 ClenderTheme background（不包括系统栏）；appearance Flow 切换即时重绘；Loading/Ready/Error 同一页面底色；旋转保留暗色和草稿；8/20sp、横屏/200%font不出现窗口底色漏出。未来容器须同时使用对应 onBackground 内容色，保留单滚动容器与≥48dp导航，不改草稿持久化或触发提交。测试只使用专用 fake owner 提供非秘密主题/会话状态，实际 production Activity 和生产 UI 绘制，不用主 Activity 代替组件 host。

生产候选为最小主题 Surface 包裹整个 Quick AI 页面，但本节不预设必须使用哪个 Compose 组件；验收依据为用户实际看到的页面颜色。回滚仅本节专用文件 diff。RED/修复/复验及最终构建由主 agent统一记录，未执行不得宣称 PASS。

## 状态与目标

**2026-09-06 P2B2b 实现已完成：** 当前执行以 `T47-T48-android-completion.md` 为准。Quick AI 与真实新建入口此前组合 31 suites / 351 tests、ktlint/detekt 通过，API26 真实 Launcher 配置/resize/LARGE 点击打开 Quick AI 通过。已完成主题 Surface 与 API36 Launcher NEW_TASK 入站修复；最新最终 JVM 为 153 suites / 1573 tests，failure/error/skip 全部 0。lint 仍运行，最终签名包实机重验仍进行中；最终构建与提交由主 agent 统一收口。下述 STOP-ESCALATE 与撤回为 2026-09-02 历史证据，不代表本轮现场。

平台交付契约修正：PendingIntent 创建仍严格 `CLEAR_TOP|SINGLE_TOP`。旧 API26 JDB/Launcher 日志证实 QuickAI 交付为 `0x24800000`；API36 最新实际 Launcher 日志证实 QuickAI 与 EditEvent START 均为 `0x34000000`。QuickAI 入站仅额外允许 NEW_TASK/EXCLUDE_FROM_RECENTS 独立或组合，EditEvent 入站仅额外允许 NEW_TASK，精确数值见上节；内部导航与 LocalRefresh 不扩宽。旧 EXCLUDE 聚焦 44 tests / 4 failures 与本轮 NEW_TASK RED 均保留为修复前证据，所有验证日志见本轮主任务。

**状态：STOP-ESCALATE（2026-09-02）。** tests-first、聚焦实现验证完成后，最终完整 Android 门禁出现 2 个测试失败；依照本任务不可重试、失败即撤回的约定，全部 P2B2b production/resource/Manifest/test/policy 试验已撤回，仅保留任务与进度文档及 STOP 证据。未创建提交、未 push，P2C/T48 未开始。

## 非目标

- 不实现 P2C mutation/remote/foreground/date-boundary 自动刷新、Worker、BootReceiver、WorkManager enqueue、Alarm、通知或后台 AI。
- 不实现自动提交/重试、Widget 内输入、RemoteInput、RemoteViewsService、历史/THINK/语音/附件/Markdown/WebView。
- 不修改 P1 canonical identity、Widget configuration wire、provider-info、2/4/8 容量、Room schema/DAO/migration、AI/WebDAV/EventService 协议或六个冻结构建文件。
- 不读取真实 Provider/API Key/WebDAV/用户数据，不修改 Windows 生产源码，不开始 T48，不 push。

## 恢复与修改前证据

- branch `codex/android-architecture`，HEAD `c7403a1bc265f46a55470e34ab912473dfc70e97`，subject `Implement Android T47 widget edit and local refresh actions`；working tree clean、staged empty。
- 六冻结 SHA-256：`libs.versions.toml` `1d60ebf0...a54255`、`app/build.gradle.kts` `149231f3...14cec`、`app/gradle.lockfile` `8d6f8088...bdd2a`、`verification-metadata.xml` `1f02c0df...857e`、根 `build.gradle.kts` `53e62522...02903c`、`settings-gradle.lockfile` `6656e3ae...df84`。
- source Manifest `b0b98755...71e6cc`，provider-info `7ec78bf3...a4fd2`，debug merged Manifest `53321dd2...ed9b`，release merged Manifest `93180569...51d0`。
- strict/offline、single-worker、`--rerun-tasks` 完整 96-task gate 为 96/96 executed、BUILD SUCCESSFUL；124 suites/1263 tests、UI 52/607、OkHttp 16/16、failure/error/skip 0，CloseGuard/SQLiteConnectionPool/SQLiteDatabase leaked/RoomDatabase leaked 均 0；generated/boundary 各 44/44。首次沙箱内 wrapper lock access denied，测试未启动；同一命令按既有 P2B1R 方式在沙箱外执行成功，未重试任何测试失败。

## 影响文件与接口

- Widget action：扩展既有 `WidgetActionIntentContract`、`WidgetPendingIntentFactory`、`WidgetRemoteViewsRenderer` 及对应测试，继续复用 `WidgetActionSpec.QuickAi` canonical identity。
- Activity/UI：新增 `.widget.QuickAiActivity` 和 Activity-specific 状态/装配，复用 `ClenderTheme`、`AppearanceViewModel`、`QuickAiScreen`、`ConversationViewModel`、`AiSubmissionViewModel`。
- 主应用导航：扩展 `MainActivity` 的冷/热 strict internal action 路由，并复用既有 AI/Settings UI 与 `SettingsViewModel.showAiSection()`。
- 共享装配：只允许主 Agent 对 `AppContainer`、`ClenderApplication`、strings、Manifest、策略/集成测试做必要最小修改；不得创建第二套 Room/DataStore/repository/active store/gateway/client/scope/lifecycle observer。
- 数据/协议：Room schema、DAO、migration、Widget config wire、AI/WebDAV wire 均无变化；AI 事件操作继续 `AiOperationExecutor → EventService`，P2B2b 不消费 mutation signal。

## Intent、PendingIntent 与 Activity 安全边界

- Quick AI action 精确为 `com.molotov.clender.action.WIDGET_QUICK_AI`；data 精确复用 `clender-internal://widget/{positive-id}/quick-ai`。
- 唯一既有 `getActivity` 创建点生成 explicit `.widget.QuickAiActivity` Intent：package 为本应用，extras key 集仅 `EXTRA_APPWIDGET_ID`，Intent flags 精确 `CLEAR_TOP|SINGLE_TOP`，requestCode 0，PendingIntent flags 精确 `IMMUTABLE|UPDATE_CURRENT`。
- `validateQuickAi` 精确验证 action/component/package/data/flags/extras/正 Int ID/无 selector、clip、categories，并经 `AppWidgetManager` 验证实例属于 `ClenderWidgetProvider`；空白、大小写变体、query、fragment、编码替代、多余 path、错误类型/溢出/其他 provider/删除实例均拒绝。
- `QuickAiActivity.onCreate/onNewIntent` 使用同一 validator；冷启动必须在访问 container/Room/DataStore/Keystore/AI runtime 前验证，无效冷启动立即 finish 且不 setContent；无效热 Intent 不 setIntent、不改 draft/状态/提交，合法热 Intent 只更新已验证 Widget 身份。
- Manifest 唯一允许新增 enabled、`exported=false`、`excludeFromRecents=true`、`Theme.Clender` 的 `QuickAiActivity`；无 filter/data/permission/taskAffinity/launchMode/document mode。
- 打开完整 AI/AI 设置使用有限 internal actions `WIDGET_QUICK_AI_OPEN_CONVERSATION` / `WIDGET_QUICK_AI_OPEN_SETTINGS`，由已验证 Activity 重建 explicit MainActivity Intent，禁止转发原 Intent/Bundle/clip/selector/未知 extras。MainActivity 冷/热入口同样做精确 envelope 与 provider ownership 验证；非法热入口不 setIntent、不改导航/dirty/draft，合法重复入口不堆 route。

## 活动会话与 AI runtime 复用

- 首次合法显示后才幂等 activate conversation/submission；已有活动会话直接复用，preferred 缺失与空库遵循 C1a `ConversationManager.resolveActive`，默认标题使用 Activity locale、`Clock.systemUTC()`、随机 32 位小写 hex。
- 会话未 READY 或 active ID 为空时禁止提交并显示有限 loading/error/retry；初始化只允许同一 Room/DataStore 的活动会话解析，不读/解密 Key、不联网。
- 只有用户点击发送才进入既有 `ConfiguredAiSubmissionGateway`；outer trim、内部 Unicode/emoji/换行保留，accepted 清空 draft，busy/unconfigured/rejected/settings/secret/network 等失败保留。
- operation guard 防重复；draft 仅存 Activity ViewModel 内存，recreate/旋转保留，finish 后重开为空，不进入 Intent/Bundle/SavedStateHandle/DataStore/Widget config/log。
- 请求继续 app-scoped：Activity recreate/finish/ViewModel clear 不取消；进后台由现有 ProcessLifecycle 契约取消且不重试。Key 擦除、single in-flight、Thinking fallback、危险/未知 operation fail-closed 边界保持。

## UI、生命周期、错误与无障碍契约

- Activity 只显示有限 loading/error/retry、纯文本多行 composer、busy/completed/cancelled/unconfigured/failed、Back、打开完整会话、打开 AI 设置。
- 不显示 conversation list/history、assistant/THINK 正文、事件内容、endpoint/model request/conversation ID/异常正文/HTTP body/URL/Key/password。
- 覆盖 Light/Dark/System、8/20sp、200% font、360/600/840dp、横竖屏、中英、API 26/36；所有操作至少 48dp，具备本地化 heading/paneTitle/liveRegion/stateDescription。
- LARGE header 固定为日期 → AI → Refresh → Configure；AI 可见文案紧凑为 `AI`，contentDescription 中英资源，loading/content/empty/error 均保留。API 26–30 仅 LARGE，API31+ 仅 250×250 map；其他尺寸完全不出现/绑定。

## 测试矩阵

| 层级 | 正常路径 | 边界 | 非法/异常 | 回归/安全 |
|---|---|---|---|---|
| Intent validator | cold/hot QuickAi、两个 MainActivity action、provider ownership | API26/36、不同正 ID、重复合法 action | null/错 action/component/package/flags/data、query/fragment/编码替代、坏/额外 extra、selector/clip/categories、删除/他 provider | EditEvent 冷热保持；非法热入口不改 intent/navigation/dirty/draft |
| PendingIntent/RemoteViews | explicit immutable QuickAi、LARGE 四状态 | 多 Widget、重复创建、8/20sp、中英、API31 四 SizeF | 非正 ID、identity 串实例 | Configure/Edit/Refresh/row click 与 2/4/8 不变；真实 host 点击启动 Activity |
| Activity/UI | loading→ready、retry、send、Back、open AI/settings | recreate/旋转/finish-reopen、360/600/840dp、200% font、横竖屏 | invalid cold/hot、late Flow、load/submit 有限失败 | ≥48dp、heading/pane/liveRegion/stateDescription；语义树无秘密/正文/ID/endpoint |
| Conversation | existing active、preferred missing、empty DB default | locale、32 hex ID、迟到 emission | load exception/retry、active null 禁止提交 | 同一 container/store/repository/gateway；不复制 draft/创建会话 |
| AI lifecycle | configured accepted、captured active conversation | recreate/finish 后 app-scoped 继续、后台取消 | no config/busy/timeout/non-200/malformed/dangerous/unknown/cancel | no-config 零 USER/event/mutation/network；Key 擦除与 single in-flight 不变 |
| Assembly/isolation | QuickAiActivity/MainActivity 共用 AppContainer | close/teardown/CloseGuard | 零 HTTP 打开 Activity | 无第二 DataStore/Room/client/scope/observer，不触碰 WebDAV/P2C/config wire |

## 三个 Agent 的互斥写集与 tests-first 流程

第一阶段三个 Agent **只能写测试**，不得写 production、Manifest、资源或文档；主 Agent 审查红灯仅可来自缺失 P2B2b 类型/资源/行为。

- Agent A（Widget action/PendingIntent/RemoteViews）独占新增或修改 `android/app/src/test/.../widget/` 中其自建 P2B2b action/surface/host 测试文件；不得触碰 Activity UI/MainActivity/装配测试。
- Agent B（QuickAiActivity/Compose/lifecycle）独占新增 `android/app/src/test/.../widget/QuickAiActivity*Test.kt` 与必要的专用 fake/support 文件；不得触碰 A/C 测试。
- Agent C（MainActivity navigation/production isolation）独占新增 `android/app/src/test/.../app/*WidgetQuickAi*Test.kt`；不得触碰 A/B 测试。
- 红灯审查通过后，分别让原 Agent 在同一子域的生产文件中实现；共享 strings、Manifest、`ClenderApplication`/`AppContainer`、跨层集成/策略、文档、最终 Git 由主 Agent 独占。Agent 不修改 progress/AGENTS，不机械重写既有测试。

## 实施步骤

1. 三 Agent tests-only，分别运行聚焦编译/测试并回报预期缺失红灯。
2. 主 Agent 审查所有 tests-only diff 与失败；既有回归/环境/依赖失败立即 STOP。
3. 原 Agent 分区实现 strict validator/PI/surface、Activity/UI/lifecycle、MainActivity navigation/isolation；主 Agent完成共享装配/strings/Manifest/策略与集成。
4. 运行 P1/P2A/P2B1/P2B1R/P2B2a/P2B2b、UI、AI/conversation/gateway/OkHttp、完整 JVM 与 strict/offline 96-task gate；审计 85 universe、冻结 SHA、producer allowlist、source/provider/merged Manifest 与 APK。
5. Android 全绿后运行 Windows 171/171、30 imports、build check、完整 PyInstaller、双向隔离 EXE 冒烟与 `dist/data` 只读前后摘要。
6. 更新任务/T47/progress/Android 高低层设计/根 AGENTS；仅规则确有变化时更新 android/AGENTS。完成 diff/secret/binary/generated/staged 审查，配置 git proxy，创建唯一提交 `Implement Android T47 widget Quick AI activity`，不 push，clean/staged empty 后立即 STOP。

## STOP 条件

- 修改前/最终 Android、lint、策略、Windows、PyInstaller、EXE 或 `dist/data` 任一门禁失败，或 tests-only 红灯来自既有回归/测试环境/依赖。
- 非导出 Activity 需改 exported/filter，或需要 mutable/ONE_SHOT/额外 requestCode/extras、改 P1 identity、provider-info、六冻结文件、Room/schema/DAO/migration或新增依赖。
- 需要第二 DataStore/Room/repository/store/gateway/client/scope/lifecycle observer，或把 draft/prompt/conversation ID/Key/正文写入 Intent/SavedState/DataStore/Widget config/log。
- 必须提前实现 P2C/Worker/Boot/后台网络/通知/Alarm，或真实 Provider/Key/WebDAV/用户数据成为前提。

触发 STOP 时撤回全部 P2B2b production/resource/Manifest/test 试验，只保留本任务、progress 与 STOP 证据；不得提交半成品，不继续 P2C/T48。

## 回滚方案

逐文件删除本轮新增 Activity、测试和资源，并逐行回退 validator/PI/renderer/MainActivity/container/application/Manifest/strings/策略改动；不得 stash/clean/reset/checkout/rebase，不得触碰用户数据或冻结构建文件。

## 完成定义

- [ ] 三 Agent tests-only 红灯仅来自 P2B2b 缺失，随后各自实现聚焦全绿。
- [ ] LARGE-only Quick AI、strict PI/Intent/ownership、非导出 Activity、真实 active conversation submit、app-scoped lifecycle 与安全主应用导航全部通过。
- [ ] 既有 Configure/Edit/Refresh/row、2/4/8、P1/P2A/P2B1/P2B1R/P2B2a 和 AI/WebDAV/EventService 边界无回归。
- [ ] Android/策略/Manifest/APK/冻结边界与 Windows/PyInstaller/EXE/`dist/data` 全部门禁通过。
- [ ] 文档/AGENTS/diff/secret/generated/staged 审查完成，唯一 checkpoint 已创建且未 push；P2C/T48 保持未开始。

## STOP 记录（2026-09-02）

- 修改前完整 strict/offline、single-worker、`--rerun-tasks` 96-task gate 通过，基线为 124 suites/1263 tests、0 failure/error/skip；因此开始时不存在已知门禁失败。
- 三个独立 Agent 先提交互斥 tests-only 红灯，主 Agent 确认失败仅来自 P2B2b 缺失；随后各 Agent 分区实现。聚焦 P2B2b 集合最终 150 tests 全绿，`ktlintCheck` 与 `detekt` 亦通过，未使用 suppression、sleep、重试或放宽超时。
- 最终指定完整 Android gate 在 `:app:testDebugUnitTest` 停止：共执行 1333 tests，2 failed。`EventCrudIntegrationTest.addRefreshesExistingB1RangeAndMonthCountFlows[26]` 等待既有 Room Flow 5 秒超时；`WidgetResponsiveRemoteViewsTest.everyResponsiveMappingBindsConfigureAndOnlyLargeAlsoBindsRefresh` 在 `WidgetRemoteViewsRendererTest.kt:536` 发现 LARGE 映射新增 Quick AI 点击与既有断言不一致。
- 按 STOP 条件未重跑、未修复或规避最终门禁。全部 P2B2b Kotlin、资源、source Manifest、测试及策略改动已逐文件撤回；`git diff --exit-code HEAD -- android` 通过，六冻结文件、source Manifest 与 provider-info SHA-256 均恢复并匹配修改前记录。
- Android 门禁未通过，因此未运行 Windows 171/171、imports、build check、PyInstaller、EXE 单实例冒烟或 `dist/data` 完整性验证；未更新 AGENTS（无工程契约变更留存），未配置提交代理，未创建提交，未 push。

## T47-Baseline2 后续裁决（2026-09-02）

- 已建立独立任务 `T47-android-event-flow-test-stability.md`，只修复既有 `EventCrudIntegrationTest` 冷 Flow 的 `async + yield + drop(1)` 订阅 readiness 竞态；P2B2b production/resource/Manifest/test/policy 仍全部撤回且未实现。
- P2B2b 的 RemoteViews 失败仍属于未来重启 P2B2b 时的精确 tests-first 契约迁移，本 Baseline2 不修改该测试、renderer、资源或点击边界。P2C/T48 保持未开始。
- Baseline2 的 Flow 修复、10 次聚焦、三次完整 JVM与全部 Android 门禁曾全绿，但最终 EXE 双向冒烟第二方向失败，已依约 STOP 并撤回唯一 Android 测试改动；未提交。该结果不恢复或改变 P2B2b STOP，RemoteViews 契约仍留待未来独立任务。

## T47-Baseline3 后续裁决（2026-09-02）

- 已启动 `T47-windows-frozen-single-instance-smoke.md`，只处理 PyInstaller onefile 双向单实例验证协议和 Baseline2 silent-primary 提前 exit 0 的分类。
- P2B2b 继续 STOP/未实现；此前 production/resource/Manifest/test/policy 试验仍已全部撤回，不恢复 Flow、Quick AI、P2C 或 T48。

### Baseline3 STOP（2026-09-02）

- Windows frozen harness 和 10-cycle 矩阵均通过，但最终 Android gate 再次命中既有 `EventCrudIntegrationTest` API 26 Flow 超时，依约立即 STOP 且撤回 harness 实现。
- P2B2b 继续 STOP/未实现；不恢复此前试验，不开始 P2C/T48。

### Baseline4 STOP（2026-09-02）

- Flow 与 frozen harness 均通过各自聚焦/稳定性矩阵，Android 96-task gate也通过；generated policy 因本任务获准的两个 Windows harness 文件不在既有 Android change-boundary allowlist 中而 43/44 STOP。
- P2B2b 继续 STOP/未实现；没有恢复任何 Quick AI production/resource/Manifest，不开始 P2C/T48，不提交或 push。

### Baseline5R2 发布收口（2026-09-03）

- Baseline4/4R 的 Flow、frozen smoke 与 exact-path policy 基础设施已完成最终 Android/Windows/PyInstaller/EXE/数据门禁；Baseline5 旧 teardown 失败保持 `NON-REPRODUCIBLE`，未作推测性修改。
- P2B2b 仍为 STOP/未实现；APK 中 QuickAiActivity 命中 0。P2C/T48 仍未开始，本次提交不恢复任何 Quick AI 生产、资源、Manifest 或测试试验。
