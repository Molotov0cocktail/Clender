# T70 双端 AI 与提醒修复（2026-09-14，本地验证完成，待用户验收）

用户授权双端修复、独立子agent和本地测试构建；完成后先交用户检验，确认后才Release，本轮不推送或发布。基线main/e65bbad。完整提示词已在doc/tasks/T70-system-prompt-review.md经用户确认后才实施；每轮读取日期与最新事项，必须有正式reply，不用思考代替正文。Android移除多余GLM说明但保留兼容逻辑；闹钟先以隔离设备查证。Windows界面仅一种“提醒”，旧alarm字段仅兼容输入且与notification取OR映射系统通知，允许必要本机字段/收据与旧库兼容迁移，不增加独立响铃或退出后调度，WebDAV v1不变。真实验收仅本轮临时凭据与合成数据、凭据不落盘，真实库不用于测试。任务/矩阵/分工/回滚见doc/tasks/T70-dual-platform-ai-alert-repair.md。双端完整验证与签名/EXE构建已完成，设备验收和本地提交回执见T70记录，不沿用T69数字作为本轮通过。

维护记录（T70，2026-09-14）：三独立子agent完成双端AI/提醒修复；批准提示词已应用，Android新增音源一次回退与声音失败可见通知，缺reply执行前纠正与唯一正文保留，Windows单一提醒及本机事务字段/收据，WebDAV v1保持。Android208suites/2108tests零失败/错误/跳过/四类泄漏、111发布夹具与两组98策略、完整verify-all及签名APK/AAB审计通过；Windows最终274tests、完整PyInstaller及普通/静默两场景隔离EXE全部通过。真实Provider Android7轮/11HTTP200/19752tokens、Windows3轮/3HTTP200/3841tokens，均合成CRUD及正文回读通过，临时Key不落盘且调用结束。APK SHA256 c07f2d151ed365512bd4a1817b30235de500b56df85ffb5c83c90bfa5f90e493；EXE SHA256 0c6fb462b5aa3127041c93bee27b0e6b2bb839dfe6b25aea6a3f2658dee52c41；dist/data5文件/159343bytes摘要不变。API26同包冷启/设置/AI/表单通过；API36同包后台自然播放器启动及Stop释放通过，无厂商真机/人工听音结论。Windows通知依赖应用运行，到期一分钟窗口内提交，系统展示不作保证。失败历史、各产物完整摘要与设备恢复记录见T70任务；本地交付后待用户验收，不推送、不Release，提交以Git日志为准。

T70设备清理回执：两模拟器合成事项与临时状态已清理/恢复，按AVD身份关闭，最终adb devices为空。API36视觉helper恢复导航曾失败，重开应用纠正后回读原值通过，失败保留；不影响已完成自然到时/Stop和四帧视觉证据。

# T69 双端 v1.2.0 正式发布（2026-09-14，完成）

用户确认以 `v1.2.0` 发布当前正式双端应用，并更新公开 README 与内部说明，不修改项目契约。范围仅含文档、Windows 既有门禁要求的重新构建与 GitHub/Gitee Release；不改生产源码、接口、数据库、同步、AI、权限、Manifest 或依赖。Android 沿用 T68 已签名 APK/AAB（APK SHA256 `c24bbb3aa0d5f57ccb7720b5f7ee9aae3a71d55a8e46b8b91a6992a48311c068`，AAB SHA256 `573e04c0372aa235973fafb36fb8ad630905018be6b3f4d80082df5a5e51939f`），包内历史版本仍为 `1.0.0 (1)`；Release 标签为 `v1.2.0`。不得上传 debug 包、用户数据、配置、密钥、日志、映射或缓存。

发布任务、测试矩阵与回滚见 `doc/tasks/T69-dual-platform-release.md`。GitHub `main` 已安全快进；Gitee `main` 自 `v1.1.0` 后有独立 LICENSE 提交且保持未改，当前发布源码通过 `v1.2.0` 标签交付。Windows 首轮 252 项中仅运行中真实 Clender 触发隔离清理保护而 1 error；用户退出后同命令 252/252 通过。完整 PyInstaller 构建及普通→静默、静默→普通两场景隔离冒烟通过，清理后 Clender 进程为零；`dist/data` 前后均为5文件/145237bytes，清单SHA256 `06cf276e229733843d17458d7218bea083a84b0bfdeb238d1ff7ab0d5e8e2017`。新 EXE 45573514bytes，SHA256 `567df4eeb9d2978245e6bcc881222135265f96149a6a3d496fd3c2ea8cd19ced`。GitHub/Gitee 注释标签对象均为 `131d7a1a` 并解引用到发布提交 `7ae8c3cc`；双站正式 Release 和三个附件均已公开，GitHub 服务端digest及Gitee重新下载字节摘要与本地三件套一致。GitHub：`https://github.com/Molotov0cocktail/Clender/releases/tag/v1.2.0`；Gitee：`https://gitee.com/Molotov0coaktail/clender/releases`。

# T68 Android 混合提醒 AI 与会话指标（2026-09-09，完成，保留验收限制）

基线main/de59500，仅Android，三个独立子agent先RED后实现，主agent串行验证。AI请求JSON对象模式；非契约首响最多一次有预算/总时限的格式纠正，锚定本轮请求，失败usage只计一次；散文/嵌套operations整批拒绝，不从正文猜测执行。冗余timespan更新由EventService合并旧时间再校验。出站历史保留纯操作真实ACK，防丢失历史助手回合；有正文仅保留正文。中文思考只提示词约定，权限申请仍仅设置页。

对话气泡只展示模型正文，无助手/模型回复可见前缀；已知旧包装兼容剥离，实际执行反馈仅绑定当前请求在输入区状态显示。底栏左侧持久显示最近主请求输入Token估算/当时配置窗口及百分比、独立累计Provider Token，右侧发送；未知值不伪0。Room v3仅新增Conversation两nullable预算列，显式1→2→3迁移；预算-only观察可刷新，清空原子归零，Event/WebDAV v1不变。v3不可直接降级v2，禁止清空真实库回滚。

维护记录（T68）：网络/domain/AI协调与UI、Conversation/Room迁移、合成live harness、测试/策略及任务记录更新。完整verify-all 208suites/2089tests（UI79/875），零失败/错误/跳过/四类泄漏；111发布夹具与foundation/boundary各94项，96tasks/6m44s全部通过。453源码/schema摘要零漂移。最终真实glm-5.3-flash七轮/8HTTP200，创建7/修改7/删除7、ID/日期/三策略/预算与9615tokens持久回读通过；此前模型合法日期误算失败保留，结构校验不能保证模型语义。签名APK/AAB审计通过，APK SHA256 c24bbb3aa0d5f57ccb7720b5f7ee9aae3a71d55a8e46b8b91a6992a48311c068。API26模拟器ADB shell环境超时，未安装/未改数据并已关闭，不宣称设备验收通过；API36同包设置授权、合成保存/删除、自然通知/重要闹钟/计时及真实Stop释放通过，明暗normal/2x普通底栏审查通过；Stop先于timer顺序未覆盖，2x横屏真实IME输入裁切/底栏可达未验为保留限制。权限含UID appops、字号/主题/旋转/IME均已恢复，两设备按身份关闭。无PC代码/测试/构建或真实数据读取，无厂商真机/人工听音声明；临时Key不落盘、调用已结束。本地提交回执以Git日志为准，不推送。

# T67 Android AI 与权限修复（2026-09-08，完成）

Android AI 真实 Provider 修复、回复格式与权限入口迁移。中文思考仅由系统提示词要求，模型仍输出英文时直接展示，不追加翻译请求。基线 main/0d762dc；计划与测试矩阵见 T67-android-ai-provider-repair.md。三个独立子 agent，主 agent 串行验证；仅 Android，临时凭据不落盘，合成数据在线验收，不推送。

T67 当前接口：固定契约中文主导并明确 action/完整示例；提前提醒单独创建，timer 仅开始后计时。顶层 thinking 替代 SDK extra_body；GLM-5.3/Flash 的 MEDIUM 映射 high、关闭映射强制 low，设置页说明真实行为。AI 中文回执基于实际 changed，拒绝进入 INVALID_RESPONSE，HTTP 鉴权/限流/参数分类恢复；已知回执包装不再重复进入历史。权限检查和申请仅设置页，表单保留业务开关、详情保留策略展示。全量验证发现的同步状态竞态以原子更新修复，防止旧空闲状态覆盖运行/终态；无Room结构/同步格式/Manifest/依赖修改。

维护记录（T67）：domain/ai、网络client、AI协调与展示、权限入口、同步状态CAS及测试/策略/在线harness更新。最终verify-all通过199suites/1978tests（UI77/840）、零失败/错误/跳过/四类泄漏，111发布夹具与两组92项策略通过；436源码/schema摘要零漂移。真实glm-5.3-flash三次HTTP200，经生产链路创建4、修改1、删除4、Room清空，累计2634tokens；临时Key不落盘且不再使用。签名APK/AAB审计通过，APK SHA256 e27b5305a63250580738e2b0f5060d30cd8773e6ef8cf467b8ffdd926703243b。API26/36同包创建/详情/删除及明暗大字号视觉审查通过，API36设置页真实通知/精确闹钟授权及状态回读通过。API26原脚本一次SmokeError保留，补验通过；详见T67记录。无厂商真机或人工听音验证，模型输出仍可能被严格校验拒绝；本地提交以Git日志为准，不推送。

## T66 Android 可读性、提醒与 AI（2026-09-08，完成）

当前用户授权 Android 日历/Widget/全面屏、系统提醒、AI实际执行和模型参数/Thinking修复，任务与测试矩阵见 doc/tasks/T66-android-usability-ai-alerts.md。main/3e687d4干净基线，独立子agent先测试后实现；仅Android，不测试/构建Windows，不读取真实PC数据。本轮提醒所需最小权限/Room显式迁移属于新授权，历史任务禁止通知/Alarm和冻结仅约束历史范围；不新增后台网络或依赖。用户授权非影响最终功能的细节自主决策。完整verify-all已通过：196suites/1941tests（UI76/828），111发布夹具与foundation/boundary各88项，全部静态与构建门禁通过；431源码/schema摘要无漂移。最终5f5e签名APK/AAB审计与API26/36自然闹钟原题/停止验收通过。

Android当前接口：Room v2显式迁移本机notificationEnabled/alarmEnabled/timerMinutes，旧事项全部关闭，新增reminder默认通知；WebDAV v1保持原格式且同UUID保留本机策略。单次系统调度由独立alert适配器、非导出Receiver与immutable PendingIntent处理；通知/精确权限拒绝、频道关闭和OEM后台入口可见。AI固定内嵌操作契约，updateWithResult返回真实changed，元数据驱动所选模型预算，Thinking改为枚举选择。日历使用独立配色与安全文字空间，Widget按实际host尺寸显示完整时间和不透明度预览，系统栏随主题适配。维护失败、修复与最终验收证据保留在T66任务记录。

设备验收修订（T66，2026-09-08）：候选383cb出现通知准时送达但闹钟静音，已修复为独立播放器；候选2ecd的recoverBuilder污染事项通知，最终改独立Builder并以5f5e原题/Stop验收通过。按用户必要后台权限授权，T66任务追加重要闹钟专用非导出mediaPlayback前台服务及测试矩阵，仅响铃期间运行；此修订取代上文/历史“不加前台服务”限制，仍不加全屏/网络/电池豁免。声音初始化失败不得报成功，停止/改删必须释放，遵守频道静音与系统音量/DND。完成状态以T66最新验证记录为准。

维护记录（2026-09-08，T66）：日历/Widget/系统栏、Event与Room v2、AI执行与模型设置、alert平台/播放服务、测试/策略及本任务文档更新；196suites/1941tests零失败/错误/跳过与四类泄漏，完整verify-all 96tasks/4m45s通过，431源码/schema摘要无漂移。最终APK SHA256 5f5e84db72b6df8d86aca7020e6ede7e33c50247df0a644fa601f589a0dc57b9（1,846,237bytes），AAB哈希及设备恢复/关闭回执见T66。仅Android，无PC源码/测试/构建或真实数据读取；无真实Provider、厂商真机或人工听音声明。系统热改字号后须重新保存Widget设置，未知模型上限依赖服务端元数据；厂商后台权限需用户设置。提交以本地Git日志为准，不推送。

# AGENTS.md — Clender 工程协作指南

## T65 PC 本地应用更新（2026-09-07，完成）

用户本轮明确要求更新PC本地应用，因此本轮授权PC测试与完整构建；T64“不测试/不构建PC”仅适用于上一轮。以main/710a8f7的既有PC源码构建dist/Clender.exe，不改生产功能/Android/数据契约；先检查进程与单实例屏障，dist/data只读摘要前后核对，使用指定Miniconda与既有隔离EXE冒烟。任务见doc/tasks/T65-pc-local-application-update.md。维护结果：252 tests、模块导入/环境检查、完整PyInstaller及隔离普通/静默2场景全部通过；dist/data五文件143973bytes的路径/大小/时间/hash不变。EXE45,573,969bytes，SHA256 `8fc351ed90c8c72c032a8369104fb6bcc05c5a46cc64d3e5e351aa7983d4c634`。生产及Android零修改，结束测试进程/endpoint/临时目录均清理，仅维护文档提交。

## T64 Android 整合与导航（2026-09-07，实现与验收完成）

本轮按用户要求先保存旧分支工作并恢复 `main/773c2dc` 干净基线，再叠加 T52 排程/日历修复和 Android Drawer 美化。PC 源码与该基线完全一致，没有修改、测试、构建或读取真实数据。背景完整链路、图标、设置、主题、Manifest及依赖保持上次实现；新日历使用完整标题、轻量导航、透明表面与可达时间轴。Drawer呈现拆入 `AppDrawerContent.kt`，品牌/图标/两组目的地、自然文字高度与最低56dp行、低高度整列滚动；原导航、Back与脏草稿确认不变。

维护记录：Drawer10项先8RED后全导航50GREEN；旧窄屏测试仅改为测量真实Sheet，保留Back/真实滑动全部断言；纯格式失败留证。最终静态与完整verify-all通过：174suites/1806tests（UI68/785）、零失败/错误/跳过/四类泄漏，96tasks/6m38s、111发布夹具与foundation/boundary各74项通过。372源码摘要零漂移，签名APK/AAB审计通过。API26/36最终同包各26组截图/XML、背景导入/方向/0与100/冷启/移除、明暗月周日/Drawer和最大字号横屏导航通过；设备恢复后按身份关闭。APK SHA256 `f1cce5fb4ded4943d6ab2a0191fe72474323d6623fac087734ce23377b031166`。已知API36浅色系统状态栏低对比为旧版保留限制；AI大字号仅验导航到达，不宣称整页视觉优化。没有本轮真实Provider/厂商真机/Widget/PC测试。完整证据和历史失败见 `doc/tasks/T64-background-calendar-navigation-integration.md`。

后续开发统一从本地 `main` 最新提交开始；新任务先核对当前分支、HEAD和上一交付，不能用旧Android任务记录替代最新产品基线。本轮按用户要求在提交后删除本地 `codex/android-architecture`；不推送、不修改远程分支。Git提交/分支删除回执以Git日志与最终答复为准。


> **适用范围：** 本文件位于工程根目录，规则适用于整个仓库。若子目录以后出现更具体的 `AGENTS.md`，子目录规则只能补充本文件，不得降低这里的质量、测试和维护要求。
>
> **上次功能基线（历史）：** 2026-09-06，双端外观与背景（T60–T63）。当前以顶部 T66/main 交付为准。`doc/` 中的旧文档保留历史背景，代码现状、本文件、`doc/appearance-design.md`、`doc/pc-experience-design.md` 与 Android 子契约优先。

## 🚨 强制维护门禁（所有代理和开发者必须遵守）

### 修改前：先有任务清单和严格测试用例，再改代码

任何代码、配置、数据库结构、构建脚本或工程文档修改开始前，必须先完成：

1. 阅读本文件、`git status --short`、相关源码、相关 `doc/` 文档和已有任务记录；不得覆盖用户未提交的改动。
2. 在 `doc/tasks/` 新建或更新本次任务文件，并同步 `doc/tasks/progress.md`。任务文件至少写明：目标、非目标、影响文件、接口/数据影响、风险、实施步骤、回滚方式、完成定义。
3. **先写测试矩阵。** 每项修改至少覆盖正常路径、边界值、非法输入/异常路径和回归路径；数据库修改还要覆盖事务与旧数据兼容；UI 修改还要覆盖信号、主题切换和无显示器冒烟；AI 修改还要覆盖无配置、超时、非 200、畸形 JSON 与危险操作输入。
4. 能自动化的用例应先写入 `tests/`。修复 Bug 时先证明回归用例能在旧实现上失败，再实施修复。
5. 未明确验收标准、测试用例或数据契约前不得实施。发现需求歧义、破坏性操作、真实数据迁移或外部依赖变化时，先向用户确认。
6. **所有不确定事项必须在规划阶段集中询问并确认。** 不得把产品语义、交互、数据边界、兼容策略、破坏性行为或验收方式留到实施中自行猜测；若实施中出现新的不确定点，立即暂停对应任务并询问用户。
7. **大规模修改必须先形成详细提案、设计、任务拆分与控制提示，并启用独立子 agent。** 子 agent 分别负责边界清晰的实现或测试任务，先写失败用例、完成聚焦验证并把文件、命令、结果和风险返回主 agent；主 agent只负责任务编排、diff 审查、集成修复和最终发布验证，避免单一上下文承载全部细节。

### 修改中：小步、分层、可回滚

- 只修改任务清单列出的范围，避免无关重构和全局格式化。
- 保持依赖方向：`ui → service/pure logic → data/model`；数据层不得依赖 UI。
- UI 的事件写入走 `EventService`；AI 日程操作走 `AIService → EventService`。
- 网络请求只能在 `QThread` 等工作线程中执行；Qt 控件只能由主线程更新。
- API Key、真实对话、数据库和日志不得进入源码、测试夹具、构建资源、日志输出或提交记录。
- 不手工编辑 `build/`、`dist/`、`__pycache__/` 和 `*.spec` 生成产物。

### 修改后：测试、复核、同步文档缺一不可

1. 运行与风险匹配的单元测试、语法检查、导入检查、Qt offscreen 冒烟和构建检查；未运行项必须说明原因及风险。
2. **每次更改完成后都必须重新执行完整 PyInstaller 构建并做 exe 启动冒烟**，不能只运行 `build.py --check`。构建只能替换 `dist/Clender.exe`，必须保留既有 `dist/data/` 用户数据；构建前后对该目录做只读完整性核对。
3. 检查 `git diff`，排除敏感数据、生成垃圾、无关改动和意外重写。
4. 更新对应 `doc/tasks/*.md` 与 `doc/tasks/progress.md`，记录命令、结果、失败、修复和遗留项。
5. **每次工程修改后必须立即更新本 `AGENTS.md`：** 更新受影响的结构、接口、契约、命令和风险，并在“维护记录”追加日期、任务、改动文件和验证摘要。即使接口无变化也要记录。
6. **每次更改完成并验证通过后都必须创建 Git 提交。** 提交前先执行 `git config --global https.proxy http://127.0.0.1:7890`，再检查 staged diff 和敏感模式，只提交工程文件；严禁提交 `data/`、`dist/data/`、数据库、配置秘密、真实对话、日志、构建缓存或 exe。若用户明确要求暂不提交，才可例外并在任务记录中说明。
7. 最终向用户明确反馈：改了什么、为何修改、执行了哪些测试、结果如何、构建与提交哈希、`AGENTS.md` 更新了什么、还有哪些风险。

## 1. 项目速览

Clender 是 Windows 桌面智能日程管理应用，使用 Python 3.12.4、PyQt5、SQLite 和 OpenAI 兼容 Chat Completions API。主要能力：

- 月/周/日三种日历视图；周/日视图使用 `QPainter` 绘制 lane、时间段色块、提醒红线、重叠纹理与可点击聚合块；单击预览、双击编辑；
- 提醒（`reminder`）与时间段（`timespan`）事件的添加、编辑、删除和 SQLite 持久化；
- 多对话 AI 助手，将经过验证的模型 JSON 操作转换成日程 CRUD；
- 日间/夜间主题、系统托盘、同用户单实例、JSON 运行时配置和日志；
- 可选的今日桌面悬浮窗：0%–100% 透明度、时间范围、默认桌面底层/临时置顶、位置大小记忆、拖动缩放、current/next 高亮、双击编辑和复用当前 AI 对话的快捷输入；
- 基于 HTTPS + Basic Authentication 的 WebDAV 日程双向同步，使用逐事件 UUID、更新时间、删除墓碑、ETag 条件写与后台 QThread；不上传对话；
- 当前 Windows 用户级开机自启动，打包版使用 `--silent` 隐藏主窗口并保留托盘及已启用悬浮窗；
- PyInstaller Windows 单文件构建。

Windows 唯一支持的开发、测试和构建环境是本机 Miniconda base Python 3.12.4。不再维护项目内 Python 环境、离线 wheel 集合或旧版 Windows 兼容构建。Android 原生 Compose 子工程独立位于 `android/`，沿用其隔离 JDK 17/21、Gradle 8.13、API 26–36 契约，详见 `android/AGENTS.md`。

## 2. 技术栈与环境

| 层面 | 当前实现 |
|---|---|
| 语言/环境 | `C:\Users\30910\Miniconda3\python.exe`，Python 3.12.4 |
| GUI | PyQt5 5.15.11（Widgets、Signals/Slots、QThread、QPainter、QtNetwork） |
| 数据库 | 标准库 `sqlite3` + `data/clender.db` |
| 配置/对话 | UTF-8 JSON；写入使用原子替换；用户确认 API Key 明文保存在忽略的 `config.json` |
| HTTP | requests 2.32.5，Bearer Token，OpenAI 兼容 API |
| 打包 | PyInstaller 6.21.0，单文件 windowed exe |
| 测试 | 标准库 `unittest` + Qt offscreen；GitHub Actions Python 3.12.4 |

`requirements.txt` 精确锁定该环境已验证的直接与关键传递依赖。不要使用系统默认 `C:\Python314\python.exe`，不要在工程根目录重新创建 `.conda/` 或 `.venv/`。升级 Python/依赖必须作为独立任务，更新锁定版本、CI、构建检查和本文件。

## 3. 文件结构与职责

```text
Clender/
├─ main.py                    # 入口：QApplication、单实例判定、数据/日志/DB/MainWindow
├─ models.py                  # EventType、Event、Message、唯一 Conversation 模型
├─ constants.py               # 系统提示词、模型能力、默认配置、Canvas 常量
├─ config.py                  # 完整 JSON 配置原子读写、运行路径
├─ logger.py                  # 显式 configure/shutdown，无导入时文件副作用
├─ database.py                # SQLite schema、迁移、索引与 CRUD
├─ event_service.py           # 事件业务验证与数据层边界
├─ event_alerts.py            # 本机到期系统通知、开始后计时与持久收据去重
├─ conversation_store.py      # Conversation JSON 持久化
├─ ai_service.py              # 上下文、预算、解析、操作验证与执行
├─ ai_client.py               # 模型列表请求、Chat Completions QThread
├─ calendar_logic.py          # 周/日 block、真实碰撞、lane/cluster 与重叠区间纯函数
├─ floating_window_logic.py   # 悬浮设置校验、日期窗口、时间标签与 current/next 分类纯函数
├─ webdav_sync.py             # WebDAV 设置/文档校验、LWW 合并、HTTP 与同步服务
├─ sync_controller.py         # WebDAV QThread、busy/pending、状态与刷新编排
├─ startup_manager.py         # Windows HKCU Run 自启动启停与命令构造
├─ typography.py              # 8px–20px 双字号校验、集中语义角色与 QFont 工厂
├─ single_instance.py         # 同用户 QLocalServer/QLocalSocket 协调器
├─ theme_manager.py           # Light/Dark 配色与全局主题应用
├─ background.py              # 本地背景校验、有界解码/缓存、cover绘制与日夜遮罩
├─ app_icon.py                # 原创日历勾选QPainter图标，窗口/托盘统一
├─ assets/                    # 原创SVG与generate_icons.py；icon.ico用于PyInstaller
├─ android/                   # 独立Compose应用/Gradle/测试，私有背景与adaptive图标
├─ build.py                   # Python 3.12.4 环境检查、PyInstaller 构建、可选快捷方式
├─ requirements.txt           # Miniconda base 已验证版本锁定
├─ ui/
│  ├─ main_window.py          # 三栏主窗口、设置、主题、托盘、刷新编排
│  ├─ calendar_widget.py      # 月/周/日视图；复用 calendar_logic
│  ├─ canvas.py               # WeekCanvas、DayCanvas 绘制
│  ├─ event_manager.py        # 某日事件列表与 CRUD 入口
│  ├─ event_dialog.py         # 事件表单、前端校验与数据组装
│  ├─ time_input.py           # UI-only HHmm/HH:mm 输入；原值灰显，空输入保留
│  ├─ chat_input.py           # UI-only 自动折行输入；Enter发送/Shift+Enter换行
│  ├─ event_detail_dialog.py  # 共享只读事项详情
│  ├─ daily_floating_window.py# 无边框悬浮窗、拖缩、时态、快捷输入与生命周期
│  ├─ app_settings.py         # 托盘/悬浮/双字号/透明度/时间范围设置
│  ├─ ai_chat_widget.py       # 对话 UI、外部提交入口、请求预算与线程结果编排
│  ├─ ai_settings.py          # API、模型、Token、Thinking、提示词设置
│  ├─ sidebar.py              # 对话选择/新建/删除/重命名信号
│  └─ __init__.py
├─ tests/                     # 252 项 unittest：既有能力、隔离exe工具、背景/图标/动作文字/对比度
├─ .github/workflows/test.yml # Windows + Python 3.12.4 CI
├─ data/                      # 真实运行数据；被忽略，视为敏感数据
│  ├─ clender.db
│  ├─ config.json             # 完整配置，含用户确认明文保存的 api_key；严格忽略/敏感
│  ├─ conversations.json
│  ├─ clender.log
│  └─ sample_loaded.flag
├─ doc/
│  ├─ proposal.md、high-level-design.md、detailed-design.md  # 历史重构背景
│  ├─ modernization-proposal.md、modernization-design.md     # Phase 6 历史方案
│  ├─ desktop-experience-proposal/high-level-design/detailed-design.md # Phase 8 历史方案
│  ├─ floating-ai-typography-proposal/high-level-design/detailed-design.md # 当前方案
│  ├─ prompt.md               # 大改多 agent 控制提示
│  └─ tasks/                  # T01–T44 与 progress.md
├─ references/prompt-templates.md
├─ build/                     # PyInstaller 中间产物，忽略
└─ dist/
   ├─ Clender.exe            # 构建输出，忽略；每次更改后重新生成
   └─ data/                   # 冻结版用户运行数据，忽略；构建和提交均不得触碰
```

`data/`、`build/`、`dist/` 和 `*.spec` 不属于源码。不得把构建生成的 spec 或缓存加入接口文档，也不得用真实 `data/` 做测试。

## 4. 架构与数据流

### 启动

`main.main()` → `QApplication` → `SingleInstanceCoordinator.acquire()`；secondary 发送 `activate` 后在任何运行时文件副作用前返回，primary 才执行 `config.ensure_app_data_dir()` → `logger.configure_logging()` → `database.init_db()` → `MainWindow` → Qt 事件循环。

导入 `config`、`logger` 或业务模块不应创建目录或日志；所有运行时文件副作用由入口显式触发。

### 手工事件

`CalendarWidget.date_selected(date)` → `MainWindow._on_date_selected()` → `EventManager.set_date()`。

`EventManager` 从 `EventDialog` 取得数据 → `EventService` 验证标题、类型、时间和预计时长 → `database` 参数化写入 → `data_changed` → 主窗口统一刷新日历标记、事件列表和今日悬浮窗。

### AI 日程操作

`AIChatWidget._send_message()` → `Conversation.add_message()` → `AIService.build_request_messages()` 按窗口/输出/安全余量裁剪 → `AICallThread(messages)` 发送 HTTP → `AIService.parse_ai_response()` → 主线程 `_on_result()` → `AIService.execute_operations()` 校验不可信操作 → `EventService` → `database`。

线程结果绑定发起请求时的 conversation ID；切换侧栏不会把响应写入错误对话。只有成功的 add/update/delete 才发出 `data_changed`。

`DailyFloatingWindow.ai_message_submitted(str)` → `MainWindow` → `AIChatWidget.submit_external_message(text)` 复用同一 Conversation、预算、单 worker、QThread、解析、执行和持久化链路。主页面与悬浮输入共享 busy 状态；悬浮窗只消费 `working/changed/unchanged/error:` 短状态，不渲染模型正文。

### WebDAV 日程同步

`EventManager.data_changed` / `AIChatWidget.data_changed` / 悬浮编辑成功 → `MainWindow._on_data_changed()` → 刷新 UI → `SyncController.request_sync("local-change")`。设置页或托盘的手动入口使用同一 controller；应用启动和空闲期间不自动同步，也没有周期定时器。

`SyncController` 将网络工作放入单个 `SyncWorker(QThread)`，运行中再次发生本地变化只记录一次 pending，当前任务结束后补跑。`SyncService` 读取包含墓碑的本地快照，GET 远端 `clender-events.json`，按 `sync_uid` 与 UTC `updated_at` 逐事件 LWW 合并，通过数据库单事务应用，并使用 ETag 的 `If-Match` / `If-None-Match` 条件 PUT；412 最多重新拉取合并一次。远端改变本地后只发 `schedules_changed` 刷新 UI，不再次触发同步循环。

### 静默开机自启动

设置保存通过 `startup_manager` 写入/删除当前用户 `HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run` 的 `Clender` 值；只允许 frozen exe 注册，命令为当前 exe 加 `--silent`。`main.main()` 移除内部参数后创建 QApplication；静默 primary 不显示主窗口，但托盘和已启用悬浮窗正常初始化，无托盘时回退显示主窗口。静默 secondary 只向单实例端点发送 `probe`，不会激活已有窗口。

### 日历绘制

`CalendarWidget` 查询 `EventService` → `calendar_logic.build_week_blocks()` / `build_day_blocks()` → lane/cluster/真实碰撞 block → `WeekCanvas` / `DayCanvas`。跨日时间段按每日半开区间切片，保留同一 ID，午夜边界显示 24:00/00:00；数据库和同步仍只有一条事件。短时间段画色块，文字放不下就不绘字；只有提醒画红线。窄栏聚合为多 ID 命中块，重叠纹理只占右缘。单击延迟至双击间隔后发 `event_activated(tuple IDs)` 预览，双击取消待预览并发 `event_edit_requested(tuple IDs)` 编辑；聚合项先选择。导航同步选中日期，新增起止日期均默认当前聚焦日。

`EventDialog` 独立选择起止日期，接受前经 `EventService.validate_event()` 校验；非法/相等/倒序时间弹窗留在表单。`TimeInput.setTime()/time()` 提供原值 placeholder 与四数字自动补冒号，未输入保留原值，部分或非法时间拒绝。保存失败在界面捕获，重新打开同一个表单保留输入以重试或取消。

### 今日悬浮窗

`MainWindow` 唯一持有 `DailyFloatingWindow`。`FloatingWindowSettings.from_config()` 校验开关、0%–100% 透明度、同日起止时间和 geometry；悬浮窗通过 `EventService.get_events_overlapping_range()` 查询 `[start, end)`，跨日 timespan 按区间相交纳入。`set_view_mode('events'|'day'|'week')` 在会话内切换事件列表、全天时间轴、当前周时间轴；事件视图遵循设置时间范围，日/周采用整日/整周。周表头固定并与横向滚动同步，默认定位聚焦日。无边框窗口默认置底，pin 仅本次进程置顶；列表和拖动把手可拖动，边缘缩放，输入/pin/切换按钮排除。单击延迟预览，双击编辑，拖动/隐藏取消待预览；聚合块先选事项。全视图保持独立悬浮字号。

`classify_event_states()` 按半开区间标出全部 current，并标出最早 future 的全部并列 next；同日巡检至少每 60 秒重算状态。手工/AI `data_changed`、启用设置和日期变化都会刷新；关闭只隐藏，托盘可恢复，应用退出调用 `shutdown()`。

### 主题

`MainWindow._toggle_theme()` → `theme_manager.switch_theme()` 原子保存完整配置并应用全局 Palette/QSS → 日历、事件、AI、悬浮窗、设置和打开的详情对话框 `apply_theme()`。`typography.py` 从独立的 `app_font_size_px` 与 `floating_font_size_px` 构建命名语义角色；全局应用字号不得覆盖悬浮窗的事项、状态、输入和 pin 字号。

主窗口中央 `BackgroundWidget` 使用 `BackgroundSettings.from_config()`，本机 `background_image` 路径与 `background_strength`（整数0–100，默认60）随完整配置保存。输入限制32MiB/2400万像素，最长边解码到1920，按路径/mtime/大小缓存；失效回退纯色。日间/夜间最低55%/60%蒙板，文字与悬浮透明度不跟随图片透明；calendar背景属性在刷新/月周日切换后保留。设置选择/移除仅改草稿，保存成功才生效，取消不保存；图片移动删除后下次应用外观或启动回退。首次有界解码在主线程，极慢磁盘仍可能短暂等待。

`app_icon.create_app_icon()` 只在 QApplication 存在后调用；入口在单实例primary确定后设置，secondary仍早退。`assets/generate_icons.py` 用同一原创路径生成7尺寸PNG-in-ICO、SVG与Android adaptive/monochrome；无需资源打包数据参数。事项列表切换主题原位更新颜色并保留选择。

## 5. 核心接口与契约

### 5.1 模型

- `EventType.REMINDER == "reminder"`；`EventType.TIMESPAN == "timespan"`。
- `Event(id, event_type, title, start_time, end_time, description, estimated_duration, created_at)`：
  - 时间字符串严格为 `YYYY-MM-DD HH:MM`；
  - reminder 的 `end_time=None`；`estimated_duration` 为非负整数分钟；
  - timespan 必须有晚于开始时间的 `end_time`；
  - 提供 `from_row()`、`to_dict()`、`date`，暂时兼容 `event['title']`/`event.get(...)`。

T70追加三个本机策略字段：`notification_enabled: bool=False`、`alarm_enabled: bool=False`、`timer_minutes: int=0`。服务新增reminder默认普通通知；旧库迁移和新远端事项保持全部关闭，同UUID同步保留本机策略。三列及`alert_receipts(event_id, signature)`均本机持久化，初始化显式事务，WebDAV v1不增字段。Windows通知和闹钟合并为同刻一个系统通知，timer为开始后1–1440分钟，0关闭；只在应用运行且到期一分钟窗口内提交，不补发历史，不在退出后唤醒。Qt提交不等于OS展示确认，缺托盘不记成功。
- `Message(role, content, timestamp)`：角色为 `user`、`assistant` 或 `think`。
- `Conversation.new(title)` 创建 ID 和 `created_at`；`add_message()`、`to_dict()`、`from_dict()` 保持旧 JSON 兼容并持久化 `token_count`。

项目只允许 `models.Conversation/Message` 一套表示。不得在 UI 新建 dict-based Conversation 兼容类。

### 5.2 配置与日志

`config.py`：

- `get_app_data_dir()`：开发态为工程 `data/`；冻结态为 exe 同目录 `data/`。
- `ensure_app_data_dir()`：显式创建数据目录。
- `load_config()`：读取 JSON 对象并与默认配置合并；文件缺失、坏 JSON 或非对象时回退默认值，不读取环境变量或 Credential Manager。
- `save_config(config)`：临时文件加 `os.replace()` 原子保存完整字典，包括空或非空 `api_key`。
- `is_api_configured()`、`get_theme()`、`set_theme(theme)`。
- `app_font_size_px` 与 `floating_font_size_px` 是相互独立的整数配置，合法范围均为 8–20px；缺失或非法值回退 13px。悬浮 pin 状态不属于配置。
- `webdav_enabled`、`webdav_url`、`webdav_username`、`webdav_password` 与 `startup_enabled` 随完整 JSON 原子保存；密码按用户决策明文保存并视为高敏感字段。启用/测试/手动同步时 URL 必须为无 query/fragment/内嵌凭据的 HTTPS 目录，用户名和密码非空。

`logger.configure_logging(data_dir)` 显式安装 DEBUG 文件 handler 和 WARNING 控制台 handler；`shutdown_logging()` 用于测试/关闭；`get_logger(name)` 无文件副作用。绝不记录 Key 或 Authorization header。

### 5.3 数据库与事件服务

`database.py`：

- `init_db()` 显式建表、迁移 `estimated_duration`、创建 `idx_events_start_time`。
- `add_event(...) -> int`、`update_event(id, **fields) -> int`、`delete_event(id) -> bool`。
- `get_events_by_date()`、`get_events_date_range()`、`get_events_overlapping_range(start, end)`、`get_all_events()`、`get_event_by_id()`。
- `get_sync_records()` 返回包含墓碑的同步记录；`apply_sync_records(records) -> bool` 按 UUID/更新时间在单事务应用并报告可见日程是否改变。
- 所有连接使用上下文管理，在成功与异常路径关闭；值参数化，动态更新字段使用白名单。
- `update_event(event_type=...)` 支持 `reminder`/`timespan` 类型转换；转换为 reminder 时服务层显式传入 `end_time=None` 清除旧结束时间。其他可空字段仍遵循显式清空契约。
- 本地 add 生成 `sync_uid` 与 UTC `updated_at`；update 刷新 `updated_at`；delete 软删除并令 `deleted_at == updated_at`。所有普通查询过滤墓碑，只有同步接口读取墓碑。

`get_events_overlapping_range()` 使用半开区间 `[start, end)`：reminder 的开始时刻落入区间；timespan 与区间相交且结束晚于起点。`EventService` 在查询前验证参数必须是递增的 `datetime`。

`EventService.validate_event(...)` 是通用业务校验边界。`add_event`、`update_event`、`delete_event` 验证类型、ID、标题、时间格式/顺序、描述和预计时长；UI/AI 不得绕过该服务直接写库。

`EventService.get_events_by_date()` 与 `get_events_date_range()` 按相交范围返回单个事件一次，日期区间两端均包含，拒绝 datetime 代替 date；日期上界有溢出保护。`get_event_counts(start=None, end=None)` 支持可见日期区间计数，主窗口只计算聚焦月份，避免数千年跨度逐日展开。数据库原查询接口保持不变，WebDAV schema v1 不变。

`events` 表：

| 字段 | 约束/含义 |
|---|---|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT |
| `event_type` | `reminder` 或 `timespan` |
| `title` | 非空文本 |
| `start_time` | `YYYY-MM-DD HH:MM`，有 `idx_events_start_time` |
| `end_time` | 可空；时间段结束时间 |
| `description` | 默认空字符串 |
| `estimated_duration` | 非负整数分钟，默认 0（业务层保证） |
| `created_at` | 默认 CURRENT_TIMESTAMP |
| `sync_uid` | 32 位小写 UUID hex；业务保证非空唯一，`idx_events_sync_uid` 唯一索引 |
| `updated_at` | UTC RFC3339 微秒时间戳，以 `Z` 结尾 |
| `deleted_at` | 可空；非空为同步删除墓碑 |

Schema 变化必须有幂等迁移、旧库测试和回滚说明；禁止在真实 `data/clender.db` 上试验。

### 5.4 AI 服务与客户端

`AIService`：

- `build_context_messages()` 生成系统提示、人格和事件上下文。
- `estimate_tokens()` 使用 UTF-8 字节的保守估算；`count_messages_tokens()` 加消息开销。
- `build_request_messages(conversation, context_window, max_output_tokens)` 预留输出和至少 10% 安全余量，保留系统消息与最新非 think 历史，必要时截断内容。
- `parse_ai_response()` 接受纯 JSON、Markdown JSON 块、数组或单对象；解析失败作为普通回复。
- `execute_operations()` 只接受 JSON 对象和 `add/update/delete/reply`，写库前验证事件、正整数 ID、字段与 reply message。

模型目标响应：

```json
{"operations":[
  {"action":"add","event_type":"reminder|timespan","title":"...","start_time":"YYYY-MM-DD HH:MM","end_time":null},
  {"action":"update","event_id":1,"title":"..."},
  {"action":"delete","event_id":2},
  {"action":"reply","message":"..."}
]}
```

`ai_client.fetch_models_list()` 调用 `GET /v1/models`，超时 15 秒。

`AICallThread(messages: list[dict])` 只接收已构建消息，调用 `POST /v1/chat/completions`，超时 180 秒：

- `result_ready(dict)` 返回解析结果、`think` 和 `usage`；
- `error_occurred(str)` 返回配置、连接、超时、HTTP 或响应结构错误；
- Thinking 扩展在 400/422 时只重试一次不含非标准字段的请求。

不得恢复 `user_msg + conv` 旧构造路径，也不得从线程直接读取活动 UI 对话。

### 5.5 日历纯逻辑与 UI

`calendar_logic.py`：

- `merge_ranges(ranges)` 合并重叠/相邻区间；
- `build_week_blocks(events, week_start, per_hour)`；
- `build_day_blocks(events, per_hour, target_date=None)`；日视图必须显式传目标日以裁剪跨日事件，省略仅保留旧调用兼容。

两者输出统一 block：`col/top/height/title/tlabel/color_idx/event_type/is_reminder/id/duration_minutes/overlap_ranges/lane/lane_count/cluster_id`。真实碰撞高度独立于最小视觉高度；非法日期、负时长、缺失/倒序结束时间会被跳过。生成逻辑、Canvas 几何、命中优先级与信号必须成对测试。

`WeekCanvas` 与 `DayCanvas` 使用当前字体 `QFontMetrics.horizontalAdvance("00:00")` 计算时间轴 gutter；标签与网格/事项起点至少保留 8px。周视图表头 spacer 必须复用同一计算，8px 与 20px 边界都要验证绘制和命中区域。

Qt 信号：

| 类 | 对外信号/方法 |
|---|---|
| `CalendarWidget` | `date_selected(date)`、`event_activated(tuple[int,...])`、`event_edit_requested(tuple[int,...])`；`set_selected_date()`、`update_event_markers()`、`apply_theme()` |
| `EventManager` | `data_changed()`；`set_date()`、`refresh()`、`apply_theme()` |
| `AIChatWidget` | `data_changed()`、`external_request_status(str)`；`submit_external_message(text) -> bool`、`refresh_api_state()`、`apply_theme()` |
| `SettingsDialog` | `config_saved()` |
| `AppSettingsDialog` | `config_saved(dict)`、`theme_toggle_requested()`、`ai_settings_requested()`、`webdav_test_requested(dict)`、`webdav_sync_requested()`；`set_webdav_test_result()`、`set_webdav_status()` |
| `DailyFloatingWindow` | `event_activated(int)` 预览、`event_edit_requested(int)`、`ai_message_submitted(str)`、`geometry_changed(tuple)`、`visibility_change_requested(bool)`；`set_view_mode()`、`set_ai_request_status(str)`、`apply_settings()`、`refresh()`、`apply_theme()`、`shutdown()` |
| `SyncController` | `status_changed(str)`、`schedules_changed()`、`test_finished(bool,str)`；`request_sync()`、`test_connection()`、`shutdown()` |
| `ConversationSidebar` | selected/new/delete/rename 四类信号 |

`SettingsDialog._save()` 只有在完整 JSON 配置成功写入后才提示成功、发射 `config_saved` 并关闭；IO 失败必须留在窗口内显示错误。获取模型前 endpoint 与 Key 都必须来自当前表单且非空，先保存再请求；任一为空或保存失败时不得沿用磁盘旧连接发起请求。

`ChatInput(QTextEdit)` 仅负责纯文本自动折行和一至六行高度；Enter 发 `returnPressed`，Shift+Enter 换行，IME preedit 期间不发送。`text()/setText()` 兼容既有 AI 提交链路；不新增网络、数据或操作权限。

### 5.6 单实例

`SingleInstanceCoordinator.acquire(timeout_ms=500, activate_existing=True) -> bool` 使用按当前 Windows 用户摘要命名的 `QLocalServer`。普通 secondary 发送严格的 `activate\n`；静默 secondary 发送 `probe\n`，只确认现有实例而不置前。监听竞争时先复查已有实例，只有确认不可通信才清理陈旧端点；无法确认所有权时保守退出，禁止双开。`MainWindow.activate_existing_instance()` 保留最大化状态并优先置前活动 modal。

## 6. 运行、测试和构建

### 解释器与依赖

```powershell
$ClenderPython = 'C:\Users\30910\Miniconda3\python.exe'
& $ClenderPython --version
& $ClenderPython -m pip install -r .\requirements.txt
```

不要修改 Miniconda base 中与本任务无关的包；依赖安装/升级需要用户授权。当前环境已满足锁文件，不需要重复安装。

### 开发运行

```powershell
& 'C:\Users\30910\Miniconda3\python.exe' .\main.py
```

运行会使用真实 `data/`，可能建立索引、加载首次示例数据或保存悬浮窗 geometry。自动化检查禁止直接运行此命令，必须隔离 exe/数据路径。

### 必须验证

```powershell
$env:QT_QPA_PLATFORM = 'offscreen'
& 'C:\Users\30910\Miniconda3\python.exe' -m unittest discover -s tests -v
Remove-Item Env:QT_QPA_PLATFORM

& 'C:\Users\30910\Miniconda3\python.exe' -c "import main, models, config, database, event_service, conversation_store, ai_service, ai_client, calendar_logic, floating_window_logic, single_instance, startup_manager, webdav_sync, sync_controller, theme_manager, typography; import ui.main_window, ui.calendar_widget, ui.canvas, ui.event_manager, ui.event_dialog, ui.event_detail_dialog, ui.daily_floating_window, ui.app_settings, ui.ai_chat_widget, ui.ai_settings, ui.sidebar; print('imports ok')"

& 'C:\Users\30910\Miniconda3\python.exe' .\build.py --check
```

测试必须使用 `TemporaryDirectory`、mock 网络、mock 配置与 mock 事件查询。Qt 测试设置 `QT_QPA_PLATFORM=offscreen`，单实例测试使用随机服务名，涉及视觉变更时补做 Light/Dark 人工或截图回归；禁止任何测试读取真实 `data/`。

### 构建

```powershell
# 标准单文件构建
& 'C:\Users\30910\Miniconda3\python.exe' .\build.py

# 仅在用户明确要求时创建桌面快捷方式
& 'C:\Users\30910\Miniconda3\python.exe' .\build.py --shortcut
```

`build.py` 会验证 Python 3.12.4、Conda base 标识（base prefix 下存在 `conda-meta`）和关键依赖版本；子进程禁用用户 site-packages，并补齐 conda DLL 搜索路径。PyInstaller 只输出到 `build/release/`，成功后仅原子替换 `dist/Clender.exe`。构建不使用 `--add-data`，不会把真实 `data/` 打入 exe，也不得删除、覆盖或迁移既有 `dist/data/`。生成的 `build/`、`dist/` 和 `*.spec` 均被忽略。

## 7. 测试矩阵

| 变更类型 | 最低自动化覆盖 | 专项验证 |
|---|---|---|
| 模型/对话 | 新建 ID、Message 类型、旧 JSON、UTF-8、token_count 往返 | UI 只持有模型 Conversation |
| 配置/秘密 | 缺/坏/非对象 JSON、完整原子保存、空/Unicode/长 Key、replace 失败 | config 可含明文 Key，但 log/diff/exe/提交不得含真实值 |
| 数据库/服务 | 新旧 schema、CRUD、相交查询、跨日/端点、非法字段/时间、清空、异常关闭 | 临时 DB；真实数据不触碰 |
| AI 解析/执行 | JSON 变体、action/ID/时间验证、预算/截断 | 无配置、非 200、超时、连接、Thinking 重试 |
| 字号/主题 | 两个 8–20px 配置独立、非法值、命名角色、控件 objectName、静态裸字号扫描 | Light/Dark × 8/20px；全局 QSS 不覆盖悬浮字号 |
| 日历/Canvas | 空值、00:00/23:59、动态 gutter、lane/cluster、短相邻 marker、N 重叠/overflow、坏数据、命中 | 月/周/日、Light/Dark、8/20px、至少 8px 时间轴间距 |
| 单实例 | primary/secondary、合法/非法消息、竞争、陈旧端点、入口早退 | 打包 exe 双实例、托盘/modal/最大化恢复 |
| 悬浮窗/UI | 配置校验、跨日查询、0/100% 透明度、geometry、current/next、拖缩、top/bottom、双击编辑、AI bridge/busy、午夜 | Windows DWM、置顶/置底、无边框拖缩、托盘、多屏/DPI |
| WebDAV 同步 | URL/schema/UUID/LWW/墓碑、旧库迁移、事务、GET/PUT/PROPFIND、ETag/412、busy/pending | 仅 mock 网络；两个隔离 DB 合并与真实服务兼容留发布后验证 |
| 自启动/静默 | HKCU 启停/失败补偿、quoted command、普通/silent/secondary/无托盘 | 打包 exe `--silent` 隔离冒烟；不改真实 Run 值 |
| 构建 | 精确环境、无 `--add-data`、用户 site 隔离、DLL PATH、暂存发布路径 | PyInstaller + exe 隐藏启动冒烟；`dist/data/` 构建前后完整性一致 |

Bug 修复必须包含一个修复前失败、修复后通过的用例。若无法复现失败态，任务文件必须记录输入、预期/实际结果和限制。

## 8. 安全与数据约束

- `data/` 与 `dist/data/` 都属于用户，不得清空、覆盖、复制、纳入测试或提交。只有用户运行应用时才允许就地迁移；数据库结构修改前必须备份方案。
- WebDAV 密码与 API Key 同级敏感；不得记录 Basic Authorization、URL 内嵌凭据、密码或远端日程正文。远端 JSON 未端到端加密，只允许 HTTPS。
- 用户已明确接受 `config.json` 明文保存 `api_key`；因此 `data/config.json` 与 `dist/data/config.json` 必须始终被忽略、视为高敏感文件，不得读取进测试、打印、复制、打包或提交。
- 所有 SQL 值参数化，字段名白名单；连接与事务必须在异常路径关闭。
- AI 输出是不可信输入，不得扩展为文件、命令、任意 SQL 或未确认的外部操作。
- HTTP 错误文本最多展示受限长度，不记录 Authorization header、请求完整上下文或私人日程。
- 构建前扫描命令和 spec，确认没有 `data/`、本机秘密、绝对用户数据路径。

## 9. 当前剩余风险

T70更新：双端内置固定契约先经用户审阅，要求每轮读取当地日期/最新可见事项且包含正式reply；Windows自定义提示仅补充风格，不能替换固定操作规则。Windows上下文不足拒绝发送，不截断日期/操作契约；GLM指定模型/vN地址按Android映射，非正常finish_reason/畸形批次/缺reply不执行。Android缺reply使用既有单次有预算纠正，明确正文边界后的真实正文不因回执兼容被清空；部分执行失败仍以实际回执为准，防模型虚假成功。日期语义和思考语言仍受模型影响。Android自定义铃声不可访问的默认音源回退及失败可见通知验证见T70，不能把AOSP模拟器证据替代Xiaomi13/Android14真机验收。

1. Token 预算是跨 Provider 的保守估算，不是官方 tokenizer；10% 余量降低溢出概率，最终限制仍由服务端决定。
2. API Key 依用户决策明文保存在 `config.json`，可解决凭据库兼容问题，但增加本机文件、备份、肩窥和恶意软件读取风险；仓库/构建/日志仍不得包含真实值。
3. SQLite schema 仍是轻量幂等迁移，没有独立版本表；后续复杂迁移需先引入 schema version。
4. AI 网络测试全部使用 mock，本轮未向真实 Provider 发送请求；Provider 特有字段仍可能存在差异。
5. 字号已集中到命名语义角色并有静态门禁，Light/Dark × 8/20px 的周、日和悬浮截图已覆盖；但 Windows DWM 的真正 0% 恢复、置顶/置底、无边框多屏/DPI 拖缩和系统字体回退仍需可见桌面人工检查。悬浮 current/next 使用 60 秒周期巡检，状态边界最多可能延迟约 60 秒。
6. CI 配置已加入仓库，但远程工作流结果需在推送到 GitHub 后确认。
7. 单实例依赖本地 IPC；若端点在受限系统中不可用，当前为防双开会保守退出且没有启动前图形诊断。
8. 依赖锁定针对当前 Windows Miniconda base；升级需重新运行完整测试和 exe 构建冒烟。
9. WebDAV 冲突使用设备 UTC 时钟的 LWW，明显时钟偏差可能令错误设备获胜；墓碑首版不清理，远端整体 JSON 会随历史删除增长，并受 5 MiB/100000 条上限约束。
10. WebDAV 自动化只使用 mock，未连接真实服务；服务端对 PROPFIND、ETag、重定向和 Basic 应用密码的细节仍可能存在 Provider 差异。

## 10. 文档与提交约定

- 所有文本使用 UTF-8；公共接口使用类型注解和简洁 docstring。
- 禁止新增裸 `except:`、静默吞错、导入时文件副作用和 UI 直连数据库。
- 新模块必须说明层级、公开接口、依赖方向和测试入口，并同步本文件。
- `doc/proposal.md`、旧设计、`doc/modernization-*`、`doc/desktop-experience-*` 与 T01–T29 是历史；当前产品/架构契约见 `doc/floating-ai-typography-*.md`、`doc/prompt.md` 与 T30–T37。
- 最终报告必须列出实际测试命令和结果，不能只写“已测试”。
- 每次验证通过后必须重新构建、确认用户数据未变化，执行 `git config --global https.proxy http://127.0.0.1:7890` 后创建 Git 提交；提交信息应概括任务目的，不得包含秘密。

## 11. 维护记录

### Phase 11 完成（2026-09-06）

本轮 PC 体验任务以 `doc/pc-experience-design.md` 与 T41–T44 为当前实施契约。用户已授权不影响功能的细节自主决策，三个独立子 agent 按文件边界先测试后实现。跨日保持单一事件/UUID 和 WebDAV schema v1；不得修改用户预存的未跟踪 `android/`。新接口已同步上文；完整发布证据见 T44。

| 日期 | 任务 | 变更与接口影响 | 验证 |
|---|---|---|---|
| 2026-09-06 | T41–T44 PC 体验 | TimeInput/ChatInput；表单校验及失败重试；跨日同 ID/日期相交/月计数边界；短色块/双击编辑；悬浮三视图/固定周表头/预览；主题微调；设计、任务、接口文档同步 | 旧失败及独立审查修复已记录；209/209 unittest、33模块语法/导入、环境检查、Light/Dark × 8/13/20px 视觉通过；PyInstaller 45595858字节 SHA-256 `A5E9522A…F40C87`，隔离普通/静默双实例通过，dist/data前后相同；详见T44，提交哈希见Git历史 |
| 2026-08-02 | 建立工程级代理指南 | 新增根目录指南，记录初始结构和风险 | 全源码与文档盘点、语法检查 |
| 2026-08-02 | T16–T21 Python 3.12 现代化与工程加固 | 统一 Miniconda base 3.12.4；删除旧环境/旧构建资产；新增测试/CI、Credential Manager、日历纯逻辑；统一 Conversation/AICallThread；加固 DB/AI/构建；公共接口按本文件更新 | 首轮回归失败得到复现；修复后 35 项 unittest、全模块导入、build `--check`、PyInstaller 构建和 43,880,676-byte exe offscreen 启动冒烟通过；真实 `data/` 未用于测试 |
| 2026-08-02 | T22 API Key 保存、构建数据保护与 Git 提交 | CredentialBlob 遵循 pywin32 字符串写入契约并对错误 1312 降级会话凭据；设置保存失败保留窗口并提示；构建改为 staging 后仅发布 exe；新增“每次修改后重新构建、提交且保护 `dist/data/`”强制规则 | 修改前 9 项聚焦测试出现 2 失败、1 错误；修复后 39 项全量测试、真实临时凭据、导入、环境检查、完整构建及隔离 exe 冒烟通过；`dist/data/` 完整性不变 |
| 2026-08-02 | T23–T29 桌面体验修复与今日悬浮窗 | Key 改为完整 JSON 原子保存并删除秘密存储；Thinking 视觉锚定；同用户单实例；日历 lane/marker/聚合点击；新增相交查询、应用设置、只读详情和今日悬浮窗；新增规划澄清、多 agent 与 Git 代理规则 | 各 Bug 均先取得旧实现失败；89 项 unittest、全模块导入、build `--check`、独立代码/敏感扫描和 Light/Dark 离屏截图通过；PyInstaller 生成 45,538,690-byte exe，隔离双实例/启动冒烟通过；`dist/data` 前后保持 5 文件、261367 字节、摘要 `1CC055FB…8864`；提交哈希见最终报告 |
| 2026-08-02 | T30–T37 悬浮快捷对话、双字号与日历间距 | 新增集中 `typography` 与独立应用/悬浮 8–20px 设置；日历时间轴动态 gutter；悬浮窗改为无边框默认置底、临时置顶、拖缩、current/next、双击编辑与 AI 当前对话 bridge；类型转换可持久化；透明度支持 0% | 所有契约先取得旧实现失败证据；三批 subagent 实现与独立复核；133 项 unittest、全模块导入、build `--check`、静态字号/敏感扫描和 Light/Dark × 8/20px 视觉矩阵通过；最终 PyInstaller 生成 45,555,401-byte exe（SHA-256 `4093EFFB…D5854`），隔离首/次实例冒烟通过；`dist/data` 前后保持 5 文件、262248 字节、摘要 `9213D0AA…9ADD6`；提交哈希见最终报告 |
| 2026-08-03 | T38–T40 WebDAV 日程同步与静默自启动 | 事件新增 UUID/UTC 更新时间/删除墓碑并以事务迁移旧库；新增 HTTPS Basic WebDAV schema v1、确定性 LWW、ETag 条件写、QThread controller；设置页新增连接/手动同步与 frozen exe HKCU Run；入口新增 `--silent` 和 probe-only secondary | 旧实现 23 项聚焦为 3 failure/12 error；修复后 171 项 unittest、全模块导入、`build.py --check`、静态/敏感扫描通过；PyInstaller 生成 45,582,138-byte exe（SHA-256 `FBCB0BAA…610CD`），普通/静默 primary-secondary 隔离冒烟通过；`dist/data` 前后保持 5 文件、45619 字节、摘要 `3CBFF264…ED3E`；未访问真实 WebDAV/注册表/运行数据内容，提交哈希见最终报告 |

后续每次工程修改都必须在此追加一行，并同时更新受影响章节。维护记录用于定位，完整实施细节和测试证据保存在对应 `doc/tasks/` 文件中。

### T60–T63 双端外观任务启动（2026-09-06）
用户本轮授权PC与Android外观、背景及图标美化，非功能细节自主决策，独立子agent实施。当前设计为doc/appearance-design.md，任务T60–T63；先行测试矩阵已建立。Android源码从本地6e5b525的386个跟踪路径恢复，逐路径不存在检查通过，不覆盖原有工具链/签名/缓存；本轮允许Android外观修改，取代Phase11中仅适用于上一任务的“android保持原样”。PC继续保留main Phase11实现，不整体合并旧PC分支。Android使用其独立JDK/Gradle/Compose契约，背景不进入日程同步或Room。根指南与Android指南中的旧任务冻结/Windows跳过只描述历史授权；本轮双端测试、构建和提交仍须完成。

T60–T63 Windows验证完成：252/252 unittest、35模块AST/导入、build --check、完整PyInstaller与隔离普通/静默双实例通过；dist/data只读摘要一致。Light/Dark × 8/13/20px × 月周日/设置24图复核，浅色时间轴muted对比度回归由1.711修复至>=4.5；图标与清晰文字动作、选中状态保持均有失败→通过证据。构建/摘要/命令见T60；Android完整发布与最终Git回执另记。

Android照片方向读取使用固定AndroidX ExifInterface 1.4.2，只有该精确依赖新增，旧依赖锁与校验项保持原样；不得恢复平台ExifInterface或历史8192字节补零兼容。Android图标在mipmap-anydpi统一声明adaptive/monochrome，minSdk26无需旧v26/v33重复资源。首次依赖获取后所有验证恢复offline strict；图片仍仅在后台有界解码至私有背景，不进入同步数据。

2026-09-07 T60–T63发布维护记录：PC background/app_icon、设置/主题/动作文字、Android背景存储/Controller/ViewModel/Compose/原创图标及相关测试文档完成；恢复Android本地既有基线并保留工具链与用户资料。252项Python、1731项Android（UI741）、111发布工具、65策略、完整双端构建与签名审计通过，泄漏标记0；exe普通/静默隔离冒烟和dist/data摘要一致。Android最终APK SHA256 936a58ca…a24afbc5；Windows9898dd5a…215486e。AppBackground必须向透明页面提供LocalContentColor=onBackground，实际文字渲染色有明暗切换回归。一次临时测试DB清理失败未复现，保留严格断言并补匿名诊断。设备与Git最终回执见T60及Git历史；没有迁移用户日程数据。
