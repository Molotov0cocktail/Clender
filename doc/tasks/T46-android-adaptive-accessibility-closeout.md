# T46-C3：Android 自适应、无障碍与集成收口

## 目标

在不改变既有 T46-P0/A/B1/B2/C1a/C1b/C2a/C2b 数据、网络、秘密、生命周期和路由契约的前提下，完成 T46 最终收口：以安装包 metadata 实现真实 About 页，补齐全页面自适应、滚动位置恢复、TalkBack/Compose semantics、RTL/对比度和动态窗口集成回归，并通过 Android/Windows/PyInstaller 全部发布前门禁。

## 非目标

- 不实施 T47 Widget、Widget 配置、receiver、boot、QuickAiActivity。
- 不实施 T48 emulator/device matrix、release APK/AAB、keystore、R8 或 Gitee push。
- 不新增依赖，不修改 Gradle/catalog/lock/verification metadata、Manifest、权限、Room entity/schema/version/migration、DAO、EventService、AI/WebDAV 协议或同步存储契约。
- 不使用真实 Provider/WebDAV、真实 Key/password、真实用户数据；不读取或解析根 `data/`、`dist/data/` 正文。
- 不引入 Navigation Compose、WindowManager adaptive、Hilt、WebView、外部链接或网络入口。

## 输入文档与恢复现场

- `AGENTS.md`、`android/AGENTS.md`
- `doc/android-architecture-proposal.md`
- `doc/android-architecture-high-level-design.md`
- `doc/android-architecture-detailed-design.md` 第 7 节
- `doc/tasks/T46-android-compose-ui.md`
- `doc/tasks/T46-android-webdav-settings-sync.md`
- `doc/tasks/T46-android-ui-test-resource-lifecycle.md`
- `doc/tasks/progress.md`
- 用户提供的 T46-C3 执行提示

恢复现场须核对：branch `codex/android-architecture`、HEAD `b0fcd7cc4154b433035bf599cba08d2cca74c1a6`、working tree clean、staged empty、未 push；如不匹配立即 STOP-ESCALATE，不 stash/clean/reset/checkout/覆盖。

修改前基线必须真实重跑：

```powershell
& 'C:\Users\30910\Miniconda3\python.exe' .\android\scripts\bootstrap-toolchain.py --verify-only
.\android\scripts\gradle.ps1 --offline --no-daemon --dependency-verification strict --max-workers 1 --rerun-tasks testDebugUnitTest
```

要求为 93 suites / 909 tests、0 failure/error/skip，`ui.*` 45 suites / 509 tests，SQLite/Room CloseGuard 四类 XML/HTML 扫描为 0。失败不算 C3 红灯，立即停止并保留证据。

## 影响文件与所有权

### Agent A：About（先测试、后同包实现）

第一阶段只能新增：

- `android/app/src/test/java/com/molotov/clender/app/about/**`
- `android/app/src/test/java/com/molotov/clender/ui/about/**`

获批后只能实现：

- `android/app/src/main/java/com/molotov/clender/app/about/**`
- `android/app/src/main/java/com/molotov/clender/ui/about/**`

不得修改 shell、AppContainer、资源、文档或 Git。

### Agent B：日历/事项自适应与语义（先测试、后同包实现）

第一阶段只能新增 `android/app/src/test/java/com/molotov/clender/ui/calendar/**` 与 `ui/event/**` 的 C3 测试；获批后只能修改对应生产包。不得改 domain/data/repository/Room/EventService、共享 shell、资源、文档或 Git。

### Agent C：AI/Settings 自适应与语义（先测试、后同包实现）

第一阶段只能新增 `android/app/src/test/java/com/molotov/clender/ui/ai/**` 与 `ui/settings/**` 的 C3 测试；获批后只能修改对应生产包。不得改 app/ai、app/sync、data/settings、network、AppContainer、资源、文档或 Git。

### 主 Agent 独占

主 Agent 负责 `ui/app/**`、`ui/foundation/**`、MainActivity/Application/AppContainer 的必要最小接线、中英文资源、跨页面 saveable-state、生产集成测试、共享测试 helper、任务文档/progress/AGENTS、diff 审查、发布门禁和 Git。不得把 C3 扩展到 T47/T48。

## About 数据契约

- `AppDestination.ABOUT` 进入真实只读 About 页面；页面以 `PackageManager`/已安装包 metadata 提供 `applicationLabel`、`versionName`、`longVersionCode`，不硬编码版本，不启用 BuildConfig，不改 Gradle。
- 版本字段正常显示；PackageManager 失败、空 versionName 或异常统一为有限本地化“不可用”，不显示异常正文。
- 页面本地化展示：应用名称、版本名/版本号、Android 私有 sandbox 数据说明、WebDAV 仅同步日程且不同步对话/设置、AI 仅在用户明确提交且配置完成后请求网络。
- 不显示 applicationId、绝对路径、设备标识、endpoint、用户名、Key、密码、Authorization、对话/事件正文；不创建网页链接、外部 Intent、WebView、分享、反馈或联网入口。
- About 首显仅访问 package metadata；不得初始化 AI secret/Keystore/AiClient、WebDAV secret/probe/sync、Room 或网络。
- 页面根提供 paneTitle，主标题提供 heading；360dp、横屏、20sp 应用字号与 system fontScale 2.0 下内容可纵向滚动到达。

## 自适应、滚动恢复与 SavedState 契约

- 断点冻结为 `<600dp` COMPACT、`600..839dp` MEDIUM、`>=840dp` EXPANDED；AI 在 compact 单栏、medium/expanded 双栏；所有宽度仍使用 ModalNavigationDrawer。
- 日历 mode/range、Application/AI/WebDAV actions、事件详情/编辑/picker/dialog、Settings 三 section、AI composer 在 360/599/600/839/840dp、横竖屏、20sp/200% 字体下不裁剪且可滚动到达；IME 出现时底部操作仍可达。
- 所有顶层页面和需滚动子区域使用稳定 page key 隔离的 Compose saveable state，仅保存页面 key、list index、scroll offset 等整数位置；calendar 月网格、timeline 横/纵滚动、event list、AI conversation/message、Settings/About 返回时恢复。
- Activity recreate、旋转、窗口尺寸变化恢复允许的位置状态；活动会话、AI 草稿和业务状态继续由既有 ViewModel/store 持有，不复制。
- `AppShellViewModel` SavedStateHandle 仍只允许 destination/selectedDate/calendarMode/draftId 四个 key；不得保存 secret、URL、正文、标题/描述、Thinking 内容、子路由栈、drawer/dialog 状态。
- AI 在 599→600→839→840 动态变化中不得重复 activate、提交或联网；Calendar Canvas 与 semantic overlay 在 LTR/RTL、横向滚动、窄 lane 下几何一致。selected/today/outside-month、marker/overlap/overflow 不得只依赖颜色。

## TalkBack、语义与视觉契约

- Drawer、TopAppBar 返回、日期/mode/range、timeline event/overflow、事项列表/CRUD、AI 会话/Thinking/composer、Settings tabs/fields/actions/dialogs、WebDAV 状态和 About 全部建立清单。
- 每个交互控件都有稳定 testTag、本地化 label、正确 role 与 selected/checked/expanded/disabled 状态，语义 bounds 至少 48×48dp；焦点/阅读/视觉顺序一致；同一逻辑动作只暴露一个 clickable/toggleable 节点。
- Thinking 与 WebDAV enabled 使用单一 Switch/toggleable 语义，不同时暴露父 Row 与子 Switch 两个动作；THINK 默认折叠，折叠正文不进语义树，展开/折叠状态明确。
- 月日期和时间轴列使用 locale-aware 朗读；timeline 语义只含安全标题/时间摘要与 marker/lane/overlap 状态；overflow 给准确正数计数，选择项无重复 ID；不含 description/sync metadata。
- AI/WebDAV/save/delete/load/有限错误使用 polite liveRegion/stateDescription，不播报异常正文、HTTP body、URL、Key、密码或 Authorization；密码/API Key merged/unmerged semantics 均不含输入或 envelope。
- Light/Dark 关键文字满足 WCAG AA（普通文字至少 4.5:1）；selected/today/overlap 另有边框、纹理或语义区分。

## 测试矩阵（先写入 tests，再实现）

必须记录实际组合，不要求不可控的全笛卡尔积，但至少覆盖：

| 维度 | 必测值 |
|---|---|
| API | 26、36 |
| 宽度 | 360、599、600、839、840dp |
| 姿态/方向 | portrait、landscape、LTR、RTL |
| 字体 | appFontSizeSp 8、20；system fontScale 1.0、2.0 |
| locale/theme | zh-CN、en-US；Light、Dark、System |
| 状态 | empty、loading、content、error、busy、disabled |
| 生命周期 | Activity recreate、旋转、窗口尺寸变化、返回恢复 |

覆盖要求：About metadata 正常/空值/异常、安全字段/无外部 action/惰性副作用；日历和事项的边界宽度、大字体、横竖/RTL、日期朗读、timeline 几何/overflow/48dp、CRUD/picker/dialog/滚动；AI 动态断点、会话/消息滚动、Thinking/LiveRegion/composer；Settings 三 tab、单一 switch、dirty/back/dialog、秘密 merged/unmerged 零泄漏、有限状态和保存可达性；Drawer/About/AI/Settings/事件路由；Back 优先级、主题/字号即时变化、disabled/unconfigured 冷启动和 C1b/C2a/C2b gate/close 不回归。

禁止 sleep、GC/finalization、重试、排序、ignored/assume/filter、降低断言或测试后门。新增测试需报告源码方法数、API 26/36 展开后的实例数和真实红灯原因；已满足契约只能作为绿色回归，不能伪造红灯。

## 实施步骤

- [x] 固化现场与 93/909 基线、CloseGuard、冻结文件哈希。
- [x] 三 Agent 第一阶段仅添加互不重叠测试；主 Agent 审查 diff 和真实红灯。
- [x] 获批后分别实现 About、calendar/event、ai/settings；主 Agent 最小接线 shell/resources/saveable-state/生产集成测试。
- [x] 聚焦测试、动态尺寸/语义/RTL/主题/恢复验证；修复后再跑全量。
- [x] 执行 Android strict/offline、generated/boundary、冻结依赖与边界审计。
- [x] 执行 Windows 171、导入、build check、完整 PyInstaller、双向 exe 冒烟与 `dist/data` 只读前后摘要。
- [x] 更新本文件、T46 总文档、progress、根 AGENTS；审查 diff/秘密/生成物并提交后立即 STOP，不 push。

## 风险与 STOP 条件

风险为 About metadata API 兼容、动态窗口状态丢失、Compose semantics 与 Canvas 几何不一致、大字体/IME 操作不可达、纯组件与生产 Activity 资源释放回归，以及意外触碰冻结依赖或秘密契约。

任一 Android 修改前基线失败、CloseGuard 非零、需要依赖/Gradle/Manifest/Room/schema 变化、About 只能硬编码版本、需要真实网络/秘密/数据、需要把敏感正文或子路由放入 SavedState、360dp+20sp+200% 关键操作不可达、RTL overlay 无法对齐、C1b/C2a/C2b gate/取消/原子 secret/close 退化，立即撤回 C3 试验代码，仅保留任务与证据，不提交。Android/lint/Windows/PyInstaller/exe/CloseGuard/`dist/data` 任一最终门禁失败同样 STOP-ESCALATE，不继续 T47/T48。

## 回滚方式

精确删除 C3 新增 About、UI 自适应/语义、测试和最小资源/接线改动，恢复 `ClenderApp` About placeholder 与原测试；不触碰 T46 既有领域、数据、网络、秘密、Room、Manifest、依赖和真实运行数据。若测试先行阶段失败，仅保留本任务文档与证据。

## 完成定义

- [x] 真实 About 使用安装包 metadata，内容/安全/无网络/可滚动/本地化契约通过。
- [x] 全页面 phone/tablet、横竖、RTL、8/20sp、200% 字体、IME、状态和动态窗口矩阵通过。
- [x] 全交互语义具备稳定 tag、label、role/state、48dp bounds、唯一动作、liveRegion；TalkBack/对比度/Canvas overlay 回归通过。
- [x] 顶层路由、dirty/back、主题/字号、Activity recreate、saveable scroll、AI/WebDAV gate/close 全量回归通过。
- [x] Android 93 基线不退化并如实报告新增 suite/test 数；strict/offline、lint、detekt、ktlint、签名/Google/native/版本/边界门禁通过；CloseGuard 四类为 0。
- [x] generated/boundary 44/44、85 configuration universe、五冻结文件 SHA-256、Runtime 1.11.3/Lifecycle 2.9.4/SavedState 1.3.2/Annotation Experimental 1.4.1/UI 1.6.8 零漂移；Manifest/Room/Gradle 零变化。
- [x] Windows 171/171、导入、build check、完整 PyInstaller、双向 primary/secondary 冒烟通过；`dist/data` 5 files/195,620 bytes/既定聚合摘要不变。
- [x] 更新所有要求文档，staged 仅任务范围工程文件，工作树 clean；提交 `Complete Android T46 adaptive accessibility UI`，不 push。

## 实施结果（2026-09-01）

- 恢复现场与基线：branch `codex/android-architecture`，起始 HEAD `b0fcd7cc4154b433035bf599cba08d2cca74c1a6`，初始 working tree/staged 均 clean。`bootstrap-toolchain.py --verify-only` 通过；基线 strict/offline `testDebugUnitTest` 为 93 suites/909 tests、`ui.*` 45 suites/509 tests、0 failure/error/skip，四类 SQLite/Room CloseGuard 扫描为 0。
- 测试先行：Newton/Agent A、Zeno/Agent B、Hooke/Agent C 首轮只写互不重叠测试；真实编译红灯仅来自尚不存在的 About/API，主 Agent 审查后才授权同包实现。三 Agent 新增 34 个源码测试方法、API 26/36 展开为 70 个 test instances；最终新增 6 suites/70 tests，Android JVM 为 99 suites/979 tests、0 failure/error/skip，`ui.*` 为 50 suites/567 tests。
- About：新增 package metadata reader 与真实只读 About 页面，稳定显示本地化应用名、版本名、版本号、sandbox/WebDAV/AI 安全说明；空值、PackageManager 异常和 API 26/P 版本 API 均回退有限本地化文本。About 不创建 Room、Keystore、AI/WebDAV client、网络、外部 Intent 或敏感输出；生产 Activity 集成测试验证 About 首显时相关 lazy holders 未初始化。
- 自适应与状态：完成 360/599/600/839/840dp 断点、compact/medium/expanded、Modal Drawer、FlowRow/滚动可达、IME/大字体布局、stable page-key `rememberSaveableStateHolder`，并恢复日历 timeline、事项、AI conversation/message、Settings/About 的滚动位置。`AppShellViewModel` 仍只保存四个既定 SavedState key，草稿正文/秘密/子路由栈不进入 SavedStateHandle；Conversation/AI 状态继续由既有 ViewModel/store 持有。
- 无障碍与 RTL：补齐稳定 testTag、本地化 label、heading/paneTitle、Button/Switch role、selected/checked/expanded/disabled、polite liveRegion、有限 stateDescription 与至少 48dp 目标；THINK/WebDAV enabled 各只有一个 toggle 语义，折叠 THINK 正文不进语义树。时间轴使用同坐标系 semantic overlay，物理 RTL 放置避免 Canvas/语义偏移；颜色之外保留边框/纹理/语义状态，未引入异常正文、URL、秘密或 Authorization。
- Android 最终命令为：`gradle.ps1 --offline --no-daemon --dependency-verification strict --max-workers 1 --rerun-tasks testDebugUnitTest lintDebug lintRelease detekt ktlintCheck verifyReleaseSigningPolicy verifyNoGoogleServices verifyNoNativeRuntimeArtifacts verifyResolvedVersionsLocked assembleDebug verifyDebugApkNoNativeArtifacts`；在仅为主机提交资源限制设置 `JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4 -Xss512k` 后 BUILD SUCCESSFUL（96 actionable tasks）。全部测试、lint、detekt、ktlint、签名、Google/native、锁、assemble/APK 审计通过；generated/boundary 脚本各 44/44，85 configuration universe 未改变。
- 冻结审计：以下 SHA-256 均保持既定值：`android/app/build.gradle.kts` `238BDDE91A52C21F1F7B6ED539F638732DCE6978B6184B3E37E3B34ED5BA5779`；`android/gradle/libs.versions.toml` `3B0DD0B144035C7C918DDF2B7BFFA49313EE85F92801F3C02AAE48B78AAD92CE`；`android/app/gradle.lockfile` `C34C37132A3E46259EB7BD8370DD3B43B2966B7DB6DB643F30ACF736E0982B22`；`android/gradle/verification-metadata.xml` `025C07DD08B6C4FAFBEE6E317C406890778713EE4CAD57681689EA6BB745ED5`；`android/tests/policy/test_dependency_policy.py` `1D0DAEFE226A92BC3FF2E087E48D2760B8147BE4BF607D58FDDEAE1FC720D24D`。Runtime 1.11.3、Lifecycle 2.9.4、SavedState 1.3.2、Annotation Experimental 1.4.1、UI/graphics 1.6.8、Manifest/Room/Gradle/依赖均无漂移。
- Windows 发布门禁：offscreen `unittest discover` 171/171；30 个模块导入通过；`build.py --check` 通过；最终完整 PyInstaller 生成 `dist/Clender.exe` 45,583,001 bytes，SHA-256 `694269E4A38D4D788D3F961CA6C1056AE0BD3E0E72F6B680255E63C8808BCE3F`。normal primary + silent secondary、silent primary + normal secondary 均 secondary exit 0、primary 存活；清理后 Clender 进程和 smoke 目录均为 0。
- `dist/data` 只读前后核对：5 files、195,620 bytes、相对路径/大小/时间/哈希元数据一致，既定聚合摘要 `E43733909427A268005A9514FB136CDA0F74C43395046269E73EF20E539668D9` 不变；未读取或修改其中正文。
- 风险与边界：未访问真实 Provider/WebDAV/API Key/password/用户数据；本轮没有依赖、Manifest、Room、schema、DAO 或协议变化。未运行 T48 真机/模拟器/APK/AAB/keystore 发布矩阵，按用户要求保留给 T48；T47 Widget、QuickAiActivity、receiver/boot 仍未开始。主机资源参数仅用于稳定本地 JVM，不改变源码或依赖契约。
