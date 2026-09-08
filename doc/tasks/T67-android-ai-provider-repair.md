# T67 Android AI 实际执行、中文回复与权限入口修复

日期：2026-09-08。初始基线 main/0d762dc、工作区干净。当前状态：实现、完整门禁、真实Provider与签名设备验收完成。

## 目标与边界

修复真实 GLM 服务请求失败、操作契约歧义导致未创建日程、重复执行回执与回复格式；请求思考内容及回复使用中文。按追加要求将权限检查/申请集中到设置页，事件表单保留通知/闹钟/计时业务选项。用户明确授权子 agent、真实临时 API 调用和实现细节自主决策。

仅 Android；不读真实 PC 数据、不测试或构建 Windows、不新增依赖、不改 Room v2/WebDAV v1、不推送。凭据仅用于授权 endpoint 的内存请求，禁止写入源码、夹具、日志或提交；在线测试仅发送合成日程与合成对话。测试结束释放凭据、合成数据和测试设备。

## 设计与文件范围

- A/domain agent：domain/ai 的固定契约、严格解析/执行与测试。契约必须提供 action 字段及完整合法示例，中文语言要求和准确 timer 语义；不得通过宽松猜测 ID、忽略非法写字段解决失败。
- B/presentation agent：app/ai 协调与 ui/ai 展示、字符串及测试。执行结果以实际写入为准，阻止应用回执在后续回复中递归套娃；明确解析失败与请求失败；支持必要的安全本地文字格式，无网络富文本依赖。
- C/permissions agent：ui/event 权限入口移至 ui/settings，相关资源/测试；不移除事件策略字段，不增加权限。
- 完整门禁并发修订：`app/sync/WebDavSyncRuntime.kt` 的本地状态稳定化使用原子更新，防止异步 availability 初始化以旧 Idle/Disabled/Unconfigured 覆盖新 Running/Success/Failed；新增 `WebDavSyncStateAtomicityTest.kt` 确定性交错回归。不改变 WebDAV 协议、触发、网络、数据库或关闭行为。此修订由完整验证暴露的 Failed 状态等待超时触发，先记录 RED，再最小修复，禁止仅延长等待掩盖状态丢失。
- 主 agent：真实 Provider 协议验证、网络层集成修复与回归、串行 Gradle、策略白名单精确更新、发布/设备检验、文档与提交。网络改动限 data/network/ai 与相关测试，必要时独立小型请求策略 helper。
- 工程文档：本文件、progress.md、根与 Android AGENTS.md；相关 Android 现行设计仅在接口改变时更新。策略测试仅精确授权本任务文档路径，并将liveTest Kotlin区分为在线测试源码；Gradle脚本与相似目录仍扫描，不放宽生产/依赖边界。

### 当前语言要求（用户最新修订，取代下方历史转换方案）

系统提示词规定思考内容与回复使用中文即可；Provider仍输出英文思考时允许原样展示，不强制翻译。保留中文主导固定操作契约，移除翻译生产逻辑、翻译专用测试及额外请求成本说明；后续不发送思考翻译请求。在线harness保留三轮真实Room CRUD、原操作正文摘要、真实回执及usage准确累计与安全HTTP诊断；思考语言仅输出字符数量观察，不再作为失败条件，不要求必须存在思考内容。此变化落实用户明确产品要求，不改变日程操作或网络错误的验收标准。

### 历史探索：真实证据驱动的中文思考转换（已按用户修订移除）

两次真实调用均完成4项操作回执，但中文主导提示仍返回英文reasoning（第二次CJK0/英文词27）。为满足中文显示，在同一次用户发起的AI请求、同一Provider/模型/内存授权生命周期内，对非中文且非空reasoning最多追加一次纯中文翻译请求。只替换reasoningContent，原content逐字保留；翻译结果永不进入操作解析/执行。中文或空思考不调用翻译。原请求、Thinking fallback、翻译共享chatCall总截止时间；翻译失败显示明确中文失败提示，不伪造思考、不丢弃原日程响应；取消仍透传并禁止执行。两次usage累计。无新依赖、持久凭据或后台网络。

实施归network agent，新增OkHttpAiReasoningLanguageTest。先测原文完整保留/译文操作隔离、中文及空零额外请求、累计Token、译文非中文/非200/畸形/超长/超时、取消不落库、总时间有界。原模拟英文reasoning夹具如未测试翻译需显式提供中文或补预期翻译响应，不能删除原内容/调用释放断言。真实live中文标准不放宽，翻译调用真实用量计入。

## 测试矩阵（实施前）

| 范围 | 正常 | 边界 | 非法/异常 | 回归 |
|---|---|---|---|---|
| AI 契约/解析 | 单事项、提醒、四事项批量与 reply | 16操作、中文文本、可空字段、计时0/1440 | 畸形JSON、危险操作、未知字段、假ID、错类型、超长/截断 | action明确、落库及真实changed、旧合法格式 |
| 协调/展示 | 创建后日历可见、再修改/删除、中文回复 | 空思考、仅reply、无变化、Markdown/大字号 | 解析拒绝不假报成功、局部失败、取消 | 历史回执不递归嵌套、Token仅计一次、会话绑定 |
| 网络 | 授权GLM真实请求及生产客户端解析 | Thinking各有效档位、输出预算 | 无配置、超时、非200、401/429、畸形响应、finish_reason截断 | URL v4、400/422有界fallback、Key擦除、不泄漏错误body |
| 权限UI | 设置展示/申请通知与精确闹钟 | API26/36、明暗主题、大字号 | 拒绝权限、系统入口不可用 | 创建/编辑无权限检查入口、业务开关/保存信号仍正常 |
| 本地同步状态原子性 | Disabled/Unconfigured 按 availability 正常稳定 | 读取 Idle 后精确注入新 Running/Success/Failed | 失败状态不得被旧快照覆盖或暴露异常正文 | 既有同步触发/失败分类/关闭与纯状态回归；不使用真实网络 |
| 真实集成 | 合成日程+提醒批量创建、回读ID、修改/删除 | 中文提示词、思考语言观察及多轮 | 有界网络失败如实留证 | Provider输出经生产parser/executor与隔离Room验证 |

## 执行与控制提示

各 agent 先阅读契约和相关现行源码，再补可运行失败测试，主 agent 串行运行并保存 RED 后授权对应生产实现。代理只改分配文件，不覆盖他人修改、不启动并发 Gradle。回报文件、命令、结果、风险。主 agent 负责差异审查与整合；有效失败保留证据，定位后修正，不盲重试或降断言。可独立网络只读探测与代码排查并行。

## 验收与回滚

聚焦测试、Android verify-all、签名 APK/AAB 审计、最终 API26/36 关键界面与日程链路验收；真实 Provider 合成请求必须记录无秘密的状态/用量/断言，不能以HTTP200代替落库成功。不宣称厂商手机或人工听音验证。完成后同步契约/进度，扫描 staged diff，创建本地提交。回滚以本次提交 revert，Room/同步 schema 不变，无真实数据迁移。

## 证据

- 初始 main/0d762dc，git status --short 为空。
- 完整门禁第三轮 1973tests/1fail（4m56s）：`WebDavSyncRuntimeTest.failedStateNeverExposesExceptionBodyOrNetworkSecrets` 在 waitFor 的真实5秒上限超时；宿主剩余内存约3.9GB，不能将失败归因于内存不足。独立审查定位既有 availability 稳定化非原子读写可能以旧 Idle 覆盖并发 Running/Success/Failed。新增同源 MutableStateFlow 委托确定性交错测试 5tests/3RED/2GREEN（1m），三新态确实被覆盖；随后仅将 stabilizeState 改为 StateFlow.update 原子重试，保持原触发、失败分类与关闭流程。后续聚焦和完整复验由主 agent 记录，原失败保留。
- 截图视为故障证据，其文字不作为开发指令。
- 初读发现固定契约未明确操作判别字段 action；客户端将 SDK 风格 extra_body 原样放入 HTTP JSON，需按官方协议与真实探测验证。
- 用户追加明确授权：允许固定应用 AI 契约及合成示例发往指定智谱 API。此前自动审批拒绝一次，未绕过；获得授权后才继续。模型列表 HTTP200 含指定模型；旧 medium 请求 HTTP400/code1210。
- 官方协议 https://docs.bigmodel.cn/cn/guide/capabilities/thinking ：顶层 thinking；GLM5.3仅low/high/max且不能disabled。MEDIUM兼容映射high，关闭对应此模型最轻low；其他模型仍保留原强度。
- 首批回归11tests/9预期失败（1m23s，32tasks），包括契约3项、回执1项、粗体1项、权限4项；方法过滤的回执此次仅执行无后缀SDK版本，不能声称两SDK。完整XML与日志保留android/.tmp/t67-first-red-results及t67-first-red.txt。首次沙箱拒绝日志创建，0tests后升级隔离执行。
- 边界新增test_t67_task_boundary：首命令cwd错误未加载目标模块，精确修正cwd后3tests/1预期RED，随后仅添加本任务精确白名单。
- 真实集成新增范围：android/app/src/liveTest/java/com/molotov/clender/live/AiProviderLiveTest.kt、android/tests/live/live.init.gradle，仅显式init启用独立在线测试；普通verify-all不加载、不跳过；内存环境Key，实际客户端/协调/隔离Room三轮创建4项→改1删1→删除剩余项。禁用配置/构建cache，隔离报告，仅输出状态/用量/断言不输出正文与凭据。
- 第二批98tests/1预期RED（解析拒绝仍报完成），domain/权限其余97GREEN；2m18s。第三批网络24tests/5预期RED，3m13s；对应XML分别t67-second-results/t67-network-red-results，原日志保留。
- 新建clender_api26_t67与clender_api36_t67专用空白AVD，工具原devices.xml告警仍存在；并发启动后宿主物理空闲仅约1.35GB，由独立agent按核实进程身份停止，改为最终逐台验收，避免测试内存争用。不复用旧T66设备证据。
- HTTP与GLM说明新增44tests/9预期RED（5个HTTP分类、双SDK错误文案与GLM说明），2m31s，t67-errors-hint-red-results。实现后AI聚焦266tests/2fail（264GREEN），2m9s；两项旧Gateway非法操作测试仍等Idle，与新Failed状态冲突，精确更新期望而保留无写入/合法批次断言，t67-ai-green-static-results。
- 首轮实际生产客户端在线验收1test/1fail（2m22s）：首轮真实回执4项成功，但中文思考断言失败，未到后续逐字段/修改删除验收；不能算在线通过。证据t67-live-first-results。随后固定契约改中文主导、语言置首，中文判定不放宽；完整契约增长后真实默认契约预算用例改1024，新增旧500窗口fail-closed，自定义tiny预算与生产算法不改。
- 首轮独立policy91tests/1fail：在线Kotlin文件被误认作构建输入导致时间字串被坐标扫描拒绝；harness移入明确liveTest源码树，_support仅排除app/src/liveTest下.kt，任何Gradle脚本与相似目录仍扫描。新增合成os.walk回归4tests/1RED后4GREEN，不放宽生产/依赖检查。首版init的报告配置被AGP覆盖，修正为projectsEvaluated后定向live目录。
- 中文domain/Gateway64tests全部GREEN（零失败/错误/跳过），随后detekt两项阻断：AiComposer.statusText复杂度16、OkHttpAiClientTest类大小；分别提取错误资源helper与拆分Thinking测试类，不抑制规则。该命令2m，不能标整条成功；t67-domain-gateway-green-results保留通过XML。独立policy最终92tests/3.105s全部通过。
- 第二轮真实测试1test/1fail（1m40s），仍首轮4项真实回执、中文计数0/英文词27；t67-live-chinese-failed-results。由此追加同Provider中文转换，不再依赖单一提示词。
- 中文转换8tests/6RED、2PASS（1m1s），设置转换成本说明8tests/4RED（1m34s），XML分别t67-translation-red-results/t67-translation-hint-red-results。实现后53聚焦tests全部GREEN；3m14s命令随后被deadline类名与测试换行静态规则阻断，均按文件归属精确修复。t67-translation-green-results保留绿色XML。
- 独立审查发现翻译HTTP200/length丢失有效usage，新增1test/1RED（59s），后续仅翻译解析失败保留合法非负usage并显示明确失败提示；原操作解析不变。t67-translation-usage-red-results保留。AiChatDeadline.kt独立承担共享超时，AiReasoningLanguage.kt承担转换/预算/用量；设置说明额外耗时及Token。
- 历史翻译候选438源码/schema摘要记录在android/.tmp/t67-final-source-snapshot.json；真实3轮与完整门禁、签名待同源码验收，不以早期433文件快照替代。
- 旧翻译候选完整verify-all运行8m51s，JVM与lintDebug/release已执行，detekt因executeChat新增第6参数触发LongParameterList失败，日志android/.tmp/t67-verify-all.txt保留；此轮不是完整通过。用户随后明确仅需中文提示词、允许英文思考，翻译探索虽已有RED/GREEN证据，仍按新要求移除，最终须对移除后的源码重新完整验证与签名，438文件快照成为历史候选。
- 按最新要求更新live harness：取消中文比例、翻译次数和翻译失败占位断言，保留原生产链路及4项创建→改1删1→清理余3的所有业务断言。安全错误码只接受1–8位纯数字，Retry-After只接受数字秒值，不输出响应正文、message或请求头；失败terminal断言之前仍打印安全HTTP诊断。未自动重试真实网络。
- 后续真实测试曾在Round1四项创建与字段回读通过后，Round2出现HTTP200但INVALID_RESPONSE；当时未保存正文，无法确定具体拒绝字段。增强安全诊断后一次完整三轮通过：3次HTTP200/stop，创建4、修改1、共删除4、Room最终清空，累计2425tokens，1m29s，证据android/.tmp/t67-live-diagnostic-pass-results。此前拒绝未复现，不能将这次通过解释为已定位或修复随机拒绝根因。
- 追加协议明确性加固：固定契约增加可由生产parser直接接受的update/delete完整扁平示例，明确所有操作字段与action同级、禁止嵌套patch/额外字段，delete仅action/event_id、reply仅action/message。先在AiSchedulingPromptContractTest新增两项失败回归，再实施提示词；严格parser和实际写入边界不放宽。此项用于减少模型schema猜测，不宣称是上述一次拒绝的确定根因。
- 扁平协议回归RED由主agent串行确认：16tests中新增2项失败、原14项通过，1m1s，日志android/.tmp/t67-flat-contract-red.txt。随后仅固定提示词加入完整update/delete示例及字段约束，并明确示例ID必须替换为真实可见ID；预算算法与1024窗口测试不变，等待同源码GREEN及最终验证。
- 翻译候选首次在线请求遇HTTP429，1test/1fail、1m26s（t67-live-rate-limit-results），未写入。等待并完成本地检查后微型合成连通性探测HTTP200，仅说明服务恢复，不替代业务验收。
- 用户语言要求调整后，435源码/schema最终候选重新记录；首轮无翻译真实验收2m38s，创建4项及字段/日历回读通过，第二轮HTTP200但INVALID_RESPONSE，整测失败。报告t67-live-final-first-results保留。追加无正文结构诊断区分finish_reason、整批/单项parser结果与字段类型，未放宽操作校验，未盲改生产协议。
- 旧翻译候选JVM实际199suites/1984tests，零failure/error/skip；XML归档t67-translation-old-full-results。该候选仍因detekt失败，不能作为最终完整验证结果。

- 最终完整门禁首轮1973tests/1fail、1972PASS，5m18s；新增扁平协议测试与全部业务断言通过。唯一失败为CalendarOverflowDetailPlaceholderTest的@After，Room已关闭，临时db仍53248bytes，wal/shm/journal/lck均不存在。不是界面断言失败；与T62历史匿名清理诊断同类，实际文件删除失败原因未确定。报告t67-final-full-first-results保留；保持原check、不加sleep/GC/retry、不改生产关闭，单次聚焦关闭/清理验证后再做最终全量。
- 清理聚焦3suites/11tests、零失败/错误/跳过，59s，t67-cleanup-focused-results。未修改cleanup或生产逻辑，仅说明此次未复现。
- 最终全量第二轮198suites/1973tests（UI77/840）、零失败/错误/跳过及四类泄漏，lintDebug/lintRelease/detekt通过；随后ktlint因新增Update示例单行超过100字符失败，整命令10m31s，不能标完整门禁通过。XML t67-final-full-second-results、日志t67-final-verify-all-second.txt保留。仅拆分字面量及三处测试断言长行，运行时提示词内容与断言语义不变；先单独格式检查再收口。
- 单独最终ktlintCheck全部通过，42s；未抑制规则。
- 最终发布候选真实API验收1test PASS，1m50s，t67-live-release-candidate-results：3次业务请求/3次HTTP200，创建4、修改1、删除4、剩余0，原始content SHA不变、usage与Room累计一致（832→1745→2634 tokens），3次传入Key均擦除，数据库与HTTP资源关闭。思考英文允许，无额外翻译请求；此结果使用最终扁平示例契约。已告知用户临时Key不再使用，可以撤销。

- 同步原子修复2suites/24tests全部GREEN，detekt与ktlintCheck全部通过，1m49s，t67-sync-atomic-green-results。最终冻结436源码/schema，重新全量；AI相关源码与2634tokens真实验收版本一致，未再使用临时凭据。
- 最终完整verify-all PASS：199suites/1978tests（UI77/840），failure/error/skipped均0，CloseGuard/SQLiteConnectionPool/SQLiteDatabase leaked/RoomDatabase leaked均0；111发布夹具（2.085s）、foundation92（2.856s）、boundary92（2.812s），全部lintDebug/lintRelease/detekt/ktlint与Debug/Manifest/依赖/签名策略门禁通过；96tasks/9m12s。日志t67-final-verify-all-fourth.txt，XML t67-final-complete-results。此前各轮失败未覆盖或删除。
- 最终签名APK/AAB构建及verify-release审计PASS，日志t67-build-release.txt；436源码/schema摘要在完整门禁及签名前后零漂移。
  - APK：android/app/build/outputs/apk/release/app-release.apk，1,856,689bytes，SHA256 e27b5305a63250580738e2b0f5060d30cd8773e6ef8cf467b8ffdd926703243b。
  - AAB：android/app/build/outputs/bundle/release/app-release.aab，4,939,209bytes，SHA256 61fc76fb58dfe7b843bf3c735dc0b8fa62a1e3ea03444bde82d9bcbad813c55b。
- 预提交只读检查：36个变更工程文件均在批准范围，diff --check通过，实际Provider密钥格式与私钥头匹配0；不含PC源码、数据、构建产物或凭据。
- API26最终同包验收：安装后回读APK字节哈希一致，中文设置三类权限入口可达、精确闹钟独立申请入口按API版本缺失；合成提醒实际保存、详情回读、删除。原final1脚本在深色AI导航/捕获阶段SmokeError（旧报告未记录code，具体原因未确定），保留10组截图/XML及原FAIL。failure截图实际已为正确深色2x AI空态；独立finish_visual补验4组明暗AI/真实滚到底部editor-footer，报告INTERACTIONS_PASS_VISUAL_REVIEW_REQUIRED、restoration PASS；主agent逐项视觉审查通过，计时字段/说明/保存完整可见。未将原失败命令改称通过。
- API26 font_scale=1.0、theme=System、locale恢复原空属性、app PID为空，核身份emu kill返回OK，adb设备列表为空后才启动API36。证据android/.tmp/t67-device/emulator-5594-final1-e27b5305a632与emulator-5594-visual-final1，恢复回执restore-before-close.json。APK/AAB压缩内容真实Provider密钥格式/私钥头扫描均0。- API36最终同包原矩阵完成：安装回读hash一致、设置四类入口、合成提醒保存/详情/删除、明暗2x设置/AI空态/编辑页真实滚到底部截图齐全；主agent视觉审查通过，业务选项、计时说明与保存按钮完整可达。独立设置系统授权补验与恢复回执随后记录。
- 可复验本地完整命令（android工作目录）：`scripts/verify-all.ps1 -PythonExecutable C:\Users\30910\Miniconda3\python.exe`；发布命令`scripts/build-release.ps1`（由既有隔离工具链和签名配置执行）。在线harness仅显式`-I tests/live/live.init.gradle`启用，临时凭据由本轮授权的内存进程传入；日常verify-all不会调用真实服务。
- API36设置实际授权补验PASS：应用设置“允许发送通知”→系统弹窗允许→返回已允许；设置精确入口→系统闹钟和提醒开关→返回同时已允许通知/精确提醒，主agent截图审查通过。证据emulator-5584-permissions-final1中的notification-dialog、exact-alarm-system-page、exact-alarm-allowed、both-permissions-ready及对应XML/安全状态JSON。
- 权限恢复首轮仅设置package appop default遗漏UID allow，回读发现并保留restored-initial-uid-mismatch.json；补UID default后通知权限false、原flags完全一致、通知UID ignore、精确有效default，应用回读两项未允许，主agent视觉确认。属于测试恢复作用域修正，未改产品。原矩阵14组截图/XML与font/theme恢复PASS；所有设备仅本轮合成数据，无真实手机或声音验收。
- 最终独立暂存审查未发现阻断项，36工程文件无秘密/无关范围/未完成代码；本轮修改仅Android及维护文档，未执行PC测试或构建。最终签名输出与436冻结源摘要一致；已按契约设置Git https代理，提交前重新stage文档并扫描，提交回执以Git日志为准，不推送。
- API36最终清理回执：合成日期空事项列表截图回读，font_scale=1.0、theme=System、locale恢复原空、app已停止；核对clender_api36_t67身份后emu kill返回OK，最后adb devices为空，两台本轮设备均关闭。证据emulator-5584-permissions-final1/restore-before-close.json。
