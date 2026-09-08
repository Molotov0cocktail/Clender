# T68 Android 混合提醒操作与结构化回复修复

日期2026-09-08至09；基线main/de59500，初始工作区干净。状态：实现与发布验证完成；API26设备及极端IME体验限制如下明确保留。

最终交付审查：56个工程文件，453源码/schema对v5冻结摘要零漂移；暂存diff检查与临时凭据片段扫描通过，无数据、日志、APK/AAB、缓存入提交。最终文档同步后boundary94项再次通过（3.189s），根/Android AGENTS与progress已同步接口、迁移、测试及限制。仅本地提交，不推送，提交哈希以Git日志为准。

## 最终候选复验补记
- 历史ACK修复聚焦先被测试参数换行规则阻断（41s、4项ktlint，未执行测试）；仅格式修正后4suites/64tests全通过，detekt/ktlint同时通过（1m4s，t68-history-ack-green-2-results）。有正文历史仍仅正文；纯应用回执在出站历史保留真实已处理结果，防止历史请求失去对应助手回合。存储、UI及THINK过滤不变。
- 最终候选第一次真实复验FAIL（48s，t68-live-final-4-results）：首轮4项全部成功入库，模型将合成课程2026-09-19前30分钟误算为09-18 07:30。严格日期断言拦下验收；属于合法操作的语义误算，不能靠schema保证，不宣称已消除模型日期错误。
- 同源码、同七轮输入与全部断言完整复验PASS（49s，t68-live-final-5-results）：7业务请求/8次HTTP200，第四轮唯一格式纠正成功；创建7/修改7/删除7，实际ID、日期时间、三策略逐字段验证、计账9615tokens和预算持久快照一致，最终Room及月计数为空，7次传入Key均擦除。前次语义错误未复现，所有失败证据保留；不把一次成功扩大为模型输出保证。
- 最终源码/schema冻结为453文件（t68-final-source-snapshot-v4.json）。独立domain/network、UI、data审查未发现新增阻断项。输入指标是最近主请求预算的估算，累计Provider Token另列；v3禁止直接降级v2，3.json纳入源码提交。
- 首完整verify-all在lintDebug因旧ai_token_count_approximate资源未使用而失败（4m15s）；此前208suites/2089tests（UI79/875）全部零失败/错误/跳过，四类泄漏零，报告t68-final-full-results保留。rg确认仅剩两处资源声明，移除中英文旧标签后冻结v5并重新完整门禁；网络/domain/协调代码与9615tokens实测一致，无追加真实调用。
- 最终完整verify-all PASS（6m44s、96tasks，t68-final-verify-all-2.txt）：208suites/2089tests（UI79/875），失败/错误/跳过均0，CloseGuard/SQLiteConnectionPool/SQLiteDatabase leaked/RoomDatabase leaked均0；111发布夹具、foundation与boundary各94项通过，lintDebug/lintRelease/detekt/ktlint、Debug/Manifest/依赖及签名策略全部通过。最终XML为t68-final-full-2-results；453源码/schema对v5摘要零漂移。
- build-release签名APK/AAB及发布审计通过（t68-build-release.txt）。APK 1,865,613bytes，SHA256 `c24bbb3aa0d5f57ccb7720b5f7ee9aae3a71d55a8e46b8b91a6992a48311c068`；AAB 4,957,321bytes，SHA256 `573e04c0372aa235973fafb36fb8ad630905018be6b3f4d80082df5a5e51939f`。设备验收只安装并核对该APK字节摘要。
- API26设备环境阻断（t68-device/api26-environment-blocked-final1.json）：首次后台启动工具exit -1且无进程；确认未启动后受控no-window启动成功并核对AVD身份，ADB显示device但15秒有界shell探测持续超时，模拟器自己的设置命令也卡住。仅一次重建ADB连接仍不可用，按console身份关闭目标AVD并核无emulator/qemu残留。未安装APK/未创建事项/未改应用权限字体，不宣称本轮API26自然到时或视觉验收通过；两SDK自动化测试仍完整通过。继续独立API36设备验收。
- API36最终同包已核installed字节SHA；设置页实际授权后回读通知/精确允许。两项合成提醒保存并详情读回，普通通知false-alarm/timer0，Mixed通知+闹钟开启/timer1；保持设备原UTC时区，2026-09-08 17:55（北京时间09-09 01:55）自然开始、17:56计时，系统3条调度记录匹配。natural-start截图与系统回读确认普通通知、重要闹钟、UID10150 MediaPlayer started/USAGE_ALARM及ImportantAlarmService；natural-timer确认独立计时通知。通知分组展开首操作误入互联网面板，原group-expanded证据保留，未重复创建；Stop未能在timer前执行，因此本轮不宣称设备覆盖“先Stop后timer”顺序（该语义有Room运行时自动化覆盖）。
- API36随后通过实际事项通知的“停止提醒”按钮停止：mixed-stop-visible/mixed-stopped截图与XML保留，主agent审查Mixed及前台播放提示消失、NotifyOnly保留；mixed-stopped当前audio无该UID活动MediaPlayer，services无ImportantAlarmService。证明播放与停止服务释放，不宣称no-audio模拟器人工听音。
- 两项合成事项随后通过UI删除。删除首轮因通知栏仍展开而UI_TIMEOUT（尚未修改），HOME后成功，原失败保留。普通项策略为notification=true/alarm=false/timer0，Mixed为true/true/1。
- API36视觉原compact脚本在浅色横屏后Back回到Launcher，后续Dark导航UI_TIMEOUT；finally主题恢复也受该导航状态影响，原FAIL及截图保留，未将原命令改为通过。独立启动Activity后补深色；主agent逐项确认明暗normal/2x竖屏与无键盘横屏统计左/发送右、文字可读无重叠，8图分别位于visual-final1与visual-supplement1目录。
- 原“keyboard”命名图实际没有键盘，脚本错误匹配input_method转储中另一service的残留mIsInputViewShown=true；全局mInputShown=false/mImeWindowVis=0。该命名不构成IME通过证据。一次横屏后refocus补验得到真实键盘（keyboard-final1，mInputShown=true/mImeWindowVis=3），但2x横屏上部输入被裁切，Send选择器NODE_AMBIGUOUS，底栏可达未验证；该极端组合保留为视觉覆盖/体验限制，不扩大普通布局通过结论。本次不为此扩展产品改动，设备随后按原状态恢复。

## 目标、非目标与授权

设备收尾：API36原/恢复POST_NOTIFICATIONS=false且flags一致，POST_NOTIFICATION UID ignore，SCHEDULE_EXACT_ALARM UID default（缺省与显式default记录语义一致）；font1.0、旋转1/0、硬键盘显示IME0、locale空一致，System主题已实际选择保存。合成两项已删除，无活动播放器/闹钟服务；AVD身份核验后force-stop/emu kill成功，adb devices空且无emulator进程。汇总t68-device/final-device-summary.json；原/恢复快照、UID appops及关闭回执保留。所有本轮设备均为合成AVD，不读取真实手机数据、不做人工听音声明。

修复自然语言混杂operations、reply.message嵌套操作导致零写却展示操作正文；验证通知/重要闹钟/开始后计时的混合创建、修改、关闭、删除与实际调度。用户重新授权上一轮临时Key及生产请求契约的合成在线验证；仅向原智谱endpoint发送合成数据，Key内存使用不入文件/日志/产物。仅Android，不读PC数据、不构建PC、不改WebDAV格式/依赖/Manifest、不推送。按后续持久化授权升级Room v3，仅添加会话预算快照，事件格式不变。中文思考只提示词约定，权限申请只设置页。

## 调查与设计
已确认parser对前置散文+JSON返回PlainReply，合法reply.message也能包裹operations。不能递归执行引用JSON，也不能仅截取首尾括号或部分执行合法前缀。采用Provider response_format=json_object与明确混合策略示例约束输出；不兼容400/422沿用单次去扩展fallback，非400/422不重试。残留明确操作容器混入正文或reply应整批拒绝、中文未执行提示，不显示内部JSON；普通协议教学例子保持现有兼容。严格未知字段/ID/类型/批次限制不放宽。
另外审计发现update冗余event_type=timespan且省略未改end_time被提前拒绝，需由EventService合并旧值后验证最终类型/时段；非法实际转换仍拒绝。三提醒策略字段已贯穿上下文/服务/Room，不能无证据宣称字段丢失。

## 分工与影响文件
A domain：AiResponseParser及新辅助、AiMessageBudgeter固定契约，domain相关测试；先失败回归再实现。
B presentation：AiCoordinator集成测试/必要边界处理、ConversationMessagePane思考展开可见标签与字符串/测试；不改domain/network文件。
C network/alerts：OkHttpAiClient结构化输出与单次兼容fallback测试；混合alert规划/观察/交付的聚焦集成测试，确证bug才改alert生产。
主agent：在线harness生产链路混合真实验收、任务与AGENTS/策略必要精确白名单，串行Gradle RED/GREEN/全量/签名/模拟器自然到时验证、diff复核、提交。

## 测试矩阵（实现前）
|范围|正常|边界|非法/异常|回归|
|---|---|---|---|---|
|输出协议|通知+闹钟+计时混合add/update/delete|16项、0/1440、开关false、现有timespan省略结束|散文+operations、截断/多容器/嵌套reply、未知字段/假ID/错类型整批零写|纯聊天/普通教学例子、单对象/数组/fence已有合法格式|
|网络|JSON对象格式请求|GLM强制thinking/通用模型|400/422单fallback、401/429不重试、超时/取消/畸形响应|Key擦除、资源关闭、无日志正文|
|执行/上下文|创建读回三策略、修改读回、关闭并删除|未指定保留、timespan冗余类型|无配置、非法实际转换/部分服务失败|真实changed/日历可见/当前ID、token只计一次、会话绑定|
|显示|中文真实回执与思考展开/收起|空思考不存、明暗/大字号|拒绝不回显操作正文/假承诺|保留折叠行为和安全文本展示|
|提醒|创建通知/重要闹钟/计时后实际计划与交付|同刻alarm替代notification、timer从start起算|拒权限仍保存、修改旧计划撤销、关闭/删除停止|持久receipt、防重、取消/关闭释放|
|真实API|普通编辑基准及混合策略多轮|自然语句包含时间修改和状态核对|非200如实留证不盲重试|生产client→coordinator→EventService→隔离Room，逐字段/ID/usage回读|

## 步骤、风险、完成定义与回滚
独立agents先提交失败测试；主agent串行运行RED存证后允许对应实现；聚焦GREEN后真实API多轮混合任务并验调度；完整verify-all、签名APK/AAB和API26/36必要设备验收。只有实际写入才能计成功。JSON模式不能保证schema，因此严格parser仍是写入边界；模型可能拒绝或不遵约，明确失败且不猜测执行。在线测试不代表厂商听音，设备须恢复关闭。更新根/Android AGENTS与progress，敏感扫描并本地提交。Room v3不可直接降级到旧APK；回退功能须保留v3兼容代码或后续前向迁移，不清空用户库。

## 验证记录
- 官方智谱对话补全文档支持response_format.type=json_object并建议提示词明确JSON；https://docs.bigmodel.cn/cn/guide/capabilities/struct-output 。具体目标模型仍以实际请求验收为准。
- RED证据：T68精确任务边界6tests/1fail→白名单精确新增后6PASS；首轮网络/协调/UI75tests/12fail（1m12s，t68-first-red-results），domain30tests/5fail（36s，t68-domain-red-results）。确认旧实现散文/嵌套operations零写当正文、JSON模式缺失、思考展开文字、冗余timespan策略更新拒绝。主agent存证后才授权对应实现。

## 用户追加：模型正文与执行反馈分离（实现前补充设计/矩阵）
对话只展示模型正文，不出现“助手消息”“模型回复”标题或执行回执；用户消息与可展开思考保留。输入区Token统计附近显示应用实际执行反馈，优先清晰状态而非强行把长Token行挤到发送按钮同排。兼容历史中英文/嵌套包装，不改Room schema，持久化真实回执仍供分类与审计；分类是呈现处理，不能递归执行其中JSON。状态仅当前会话当前轮，WORKING/新输入提交/切会话不得显示旧成功，关闭状态行为保持。
影响新增纯分类helper及测试、AiConversationScreen/Composer/MessagePane/相关UI测试（presentation agent负责）。先测试：正常更改+正文、仅reply、仅回执/拒绝/部分失败零空泡、旧中英嵌套正文剥离、原始普通文本和用户消息不改；状态真实计数、busy/取消/错误/切会话/关闭、Token一致；明暗/大字号/无显示器UI信号回归。完成验收并入本T68完整门禁与最终设备UI，模型真实在线数据层断言仍保留。
- 用户追加布局：累计Token位于输入框下方左侧，与右侧发送按钮同排；执行状态独立上方。短标签“累计 Token”，保留Provider统计语义，大字号/窄屏可换行且按钮至少48dp；QuickAI隐藏统计的既有行为保留。新增边界/布局信号回归后实现。
- 第一轮聚焦GREEN12suites/141tests零失败/错误/跳过，1m24s，t68-first-green-results。覆盖原修复与10项Room→alert混合测试。
- 真实JSON模式在线首轮1test FAIL/53s：Round1 HTTP200 stop，4项创建读回；Round2 HTTP200 stop，改1删1读回，累计2227tokens；Round3 HTTP200 stop但原始content根类型number，无operations，实际回执数量断言失败。证据t68-live-mixed-first-results，未输出正文或凭据，不能以HTTP200标通过。此时UI追加仅最小接口无行为变化。

## 实测后追加：一次结构化纠正（先测试）
JSON模式仅语法约束，需网络完整调用在应用操作契约解析非Operations时，至多一次格式纠正；不执行初次无效响应，不摘取/递归执行其JSON，纠正输出重新经同一严格parser。纠正复用原有受预算输入与固定指令/有界原响应，不引入新用户任务、不猜ID；超过预算或失败明确错误。首轮有效零额外调用，兼容400/422回退保持有界（最多一次去扩展，不能每次无限重试）。401/429/超时不自动再发。原始与纠正合法usage各计一次且不可溢出，最终content原样；共享取消与Key擦除/资源关闭。
矩阵：有效首响1次、散文/数字/嵌套reply/畸形操作→一次合法纠正2次；纠正仍非法不执行/不再重试；字段/ID安全仍严格；有界输入/输出、usage聚合、HTTP400/422兼容上限、429/超时/取消/Key资源回归。A改网络helper/client/tests，主agentRED后实施。

## 用户追加：上下文比例（先测试）
底栏主指标为最近实际请求预算的inputTokenEstimate/contextWindow与百分比，明确“估算”；来自生产budgeter、按请求会话绑定，不用Provider累计token作分子。未发送/未知上限显示未估算，不伪造0；切会话/预检/取消不串值。Provider累计统计可次行并明确累计含多轮。C负责AiContextUsage/Coordinator/gateway流与测试，B负责SubmissionViewModel/Compose接入与布局（避免文件冲突）。测试0/上限/非法/溢出百分比、真实预算及配置上限一致、会话归属、预检、异步Flow迟到、窄屏大字号与发送按钮空间。

## 用户持久化要求的最小数据修订（实现前）
用户明确要求上下文指标随对话持久保留，覆盖早先本任务“不改Room格式”的计划限制。新增仅Conversation预算快照字段、Room v3显式2→3迁移（Event/提醒字段及WebDAV v1不变），旧会话未知值不得展示伪百分比，新构造保留默认兼容。真实设备数据不用于迁移测试；旧v1→v2→v3与旧v2→v3均用合成库验证，计数/标题/消息/事件与token保持，快照原子更新、删除清除关联且不污染其他会话。全部生产builder注册、schema导出与发布policy精确同步，严禁destructive fallback。C负责data/model/Coordinator/gateway，B仅ViewModel/UI。回滚v3不能直接旧APK降库，需保留新版本并回退功能或由后续显式前向迁移处理；禁止真实用户库降级/清空。
- 一次纠正的失败计账采用typed AiAccountedException携带已消费合法usage及原分类cause；Coordinator规范失败回执中记账一次且抛回原分类。取消不写迟到结果；预算不足/纠正仍非法明确失败。只有完整原生completion且业务schema无效才纠正，HTTP/finish_reason原错误路径不额外重试。
- 新增测试自身问题留证：首追加RED被MockWebServer3 ByteString.readUtf8不存在阻断（45s）；修成utf8后统一RED又被迁移test SQL混合arrayOf类型推断Werror阻断（2m19s），修显式Any。随后网络测试无界等待旧实现不会发送的第二请求，核对隔离worker102188/父99316后仅停止该worker，6m30s/28tests中25fail1skipped，属于中断不完整RED；日志t68-expanded-red-compiled.txt保留，测试改有界等待再完整复验。不掩盖失败/跳过。
- 中断后的XML仍是先前GREEN快照，Gradle未完成生成新XML；t68-expanded-red-interrupted-results已标注NOT_A_COMPLETED_RED_REPORT，不能作为中断RED用例证据。中断过程仅以原控制台日志计，必须重跑完成报告。
- 有界统一RED完整105tests/61fail（1m3s，t68-expanded-red-complete-results）；其中root新增Coordinator测试因返回Boolean为JUnit初始化错误，不能算业务RED。修复Unit返回后独立24tests/4个新增真实断言失败（35s，t68-accounting-red-results）。C20项失败，A13项中10失败，B分类/归属/布局/正文失败均已留证；全部对应生产实现于这些证据之后授权。
- expanded-green命令因detekt六项复杂条件/返回数/魔数/异常类型检查提前失败（27s），尚未执行测试，不能引用旧XML为GREEN。已按职责作等价重构，无新增规则豁免。
- 独立复核发现预算仅更新conversations时，原messages单表Flow不能触发页面刷新；新增ObservationTest先RED：4tests/4fail（API26/36、空与有用户消息均等待预算刷新超时，2m5s，t68-observation-red-results）。随后才授权观察查询JOIN conversations，确保网络失败无回复时预算仍刷新，清空后保持未知值。底栏使用持久化字段作为唯一来源，避免旧瞬时缓存复活。
- 持久信息位于输入框左下角：最近主请求发送时的输入Token估算/当时配置的窗口及百分比，次行Provider多轮累计Token；估算不是账单或纠正请求额外输入。单轮完成/失败/实际操作回执只在输入区状态显示，气泡仅回复正文；会话选择列表保留累计Token。所有旧Room/回滚描述由本记录后续v3数据修订取代。
- 第二次聚焦+静态命令detekt通过，但ktlint先阻断（52s，32项签名/参数换行及混合运算括号风格，tests仍未执行）。按文件职责仅格式修复，不全局格式化。独立审查schema1/2未变，v3仅两nullable列；唯一生产builder注册完整迁移链，无破坏性回退。
- 旧英文安全拒绝精确分类追加RED：5tests/1fail（49s，t68-legacy-receipt-red-results），引用或后接自然说明保持正文；再实施exact-only兼容。
- 聚焦集成首次实际完成：474tests/4fail（1m25s，t68-focused-green-3-results）。Observation四项与全部选中AI/网络/UI/混合提醒通过；失败为旧RoomAlertSchemaTest仍断言version2（两SDK），新迁移夹具SQLiteCantOpenDatabase（两SDK，尚在测试建旧库阶段，未执行迁移）。保留报告并定位修复夹具，不跳过迁移验证。
- 迁移夹具缩短测试方法及合成库名前缀约62字符，保留完整UUID唯一性与所有v1/v2迁移、重开和数据断言；修正旧版本断言。后续两次静态先失败（43s/22项测试格式、45s/最后一处函数换行），仅精确格式调整，报告保留，未执行的测试不记通过。
- 聚焦最终GREEN：42suites/474tests，零失败/错误/跳过，detekt/ktlintCheck同时通过（1m30s，t68-focused-green-6-results）。含两SDK完整迁移/重开、预算-only观察与清空、AI一次纠正/失败计账/取消、所有选中UI与Room混合提醒集成。
- 七轮真实候选验收首试FAIL（1m23s，t68-live-final-candidate-results）：前4轮完成创建7/修改1/删除4，Round4累计4769tokens；第5轮初响有未知根字段被拒，唯一纠正返回合法三update+reply，但真实changed数量未达3，严格断言失败。观察到“只通知不闹钟”的update漏notification_enabled（旧false会保留），该遗漏本身不足解释计数失败，不猜为权限/字段丢失。增强live harness仅输出合成当前ID、目标ID、布尔策略/计时数值及应用计数后复现；不输出原始正文/思考/密钥，不放宽断言或解析。
- 增强诊断完整PASS（1m15s，t68-live-policy-diagnostic-results）：7业务请求/8次HTTP200，Round4首响不合约经唯一纠正成功；创建7/修改7/删除7、全部三策略和ID/时间回读、Room计账9650tokens与原生usage一致、7次传入Key擦除。上一轮计数不足未复现，无法事后确定是否旧ID，不能宣称已定位该次具体原因。

## 在线漏字段后的最小提示词澄清（先测试）
已观察到“只通知”的更新漏显式开启通知，必须保留update未指定保持原值的服务契约；不把缺字段猜成true。增加可执行策略切换update示例（旧通知false/闹钟true→通知true/闹钟false，其余字段保留），明确新增才使用默认通知、update无默认，当前可见ID优先于已删历史/示例ID，根仅operations。先回归测试证明旧示例无法完成策略切换，再替换最小固定prompt；保持既有1024上下文边界用例，不放宽parser、不添加按标题/序号猜ID，不增加纠正次数。最终仍须重验真实七轮，不以随机重跑通过替代已见遗漏的修订。
- 澄清前RED29tests/3fail（35s，t68-policy-switch-red-results）：真实EventService执行原update示例不能开启通知；新增默认/update保留与根字段说明、当前ID说明缺失。既有1024与500预算边界继续通过。重复执行不伪报changed、其余字段保留断言不删减。
- 首版澄清42tests/1fail（1m50s，t68-policy-switch-green-failure-results），策略与纠正测试及静态通过，但761tokens固定文案挤占1024上下文的旧完整保留断言。删除重复update语义和冗词精简至709估算tokens，保留全部新增策略规则/正整数当前ID/示例；未修改任何预算阈值或断言，再验证。
- 精简后GREEN4suites/42tests零失败/错误/跳过，含原1024/500预算、真实策略切换/重复零changed与一次纠正，detekt/ktlint通过（1m47s，t68-policy-switch-green-2-results）。最终源/schema453项另存v2摘要，旧摘要保留，不混用版本证据。
- 709tokens候选真实复验FAIL（1m20s，t68-live-final-2-results）：前4轮正常；第5轮初响数字，唯一纠正返回合法3add+reply，实际创建新ID8/9/10、原ID5/6/7未改。计数3符合但逐字段/原ID断言捕获错误，累计7260tokens；不是Room写入失败，是纠正后的任务语义漂移。此候选不得交付为完成。

## 格式纠正必须锚定最近请求（先测试）
原纠正末条仅泛指“此前用户请求”，多轮下可能重做历史创建。最小修复：末条纠正用户消息明确“仅修格式，不重复执行历史任务；修改现有事项用当前ID/update”并重述原请求中最后一条user内容，保持原始历史不重排、不猜自然语言意图、合法新增仍可执行。重复的最新原文与固定纠正指令先计入输入预算，再有界截断错误回复参考；不足则失败计账且不发额外请求。仍最多一次格式纠正，合法首响不重试、未知字段/ID类型/事件验证不放宽。A先写多轮旧创建+新修改的请求锚定及预算回归，root RED后实施，再真实七轮验收ID和字段，不能仅验计数。
- 锚定回归RED16tests/3fail（41s，t68-correction-anchor-red-results）：纠正未重述最新update/合法create原文，未把重复原文计入预算。边界夹具原请求单次fit，重复必超预算；所有等待有界。此前13项既有纠正安全用例保持通过。
- 实施后首次静态先阻断（45s，仅测试预算表达式换行，未执行测试）；精确格式调整后GREEN3suites/48tests零失败/错误/跳过，detekt/ktlint通过（53s，t68-correction-anchor-green-2-results）。覆盖纠正16项、协调24项与预算8项。最终固定操作契约709tokens，纠正固定指令含原文标题约254tokens，原文额外计入预算。453源/schema另存v3摘要。
- 锚定后真实复验FAIL（1m2s，t68-live-final-3-results）：前三轮成功；Round3原始仅3delete、无reply。Round4首响未触发纠正，返回旧ID1/2/3的delete加3个正确add，应用实际完成3/失败3。严格回执断言拒绝假定完整成功，新增三项实际字段正确。

## 保留无正文轮次的处理结果（先测试）
源码定位到Coordinator构建历史时把纯应用成功回执strip成空后丢弃；模型仅返操作无reply的轮次因此失去assistant确认，旧user删除/创建与新user请求连续出现。该消息丢失是确定的代码缺口；它解释了重放风险，但不能证明模型内部因果。最小修复：历史assistant分类后有正文则仍仅正文，保持旧包装不回流；无正文但有已知应用反馈则保留简短“应用已处理该历史请求，实际结果：…”assistant历史消息。只影响发给Provider的上下文，不写新的DB消息、不把回执放回UI、不含操作JSON/思考。成功/无改/部分失败/已知拒绝均保留真实处理结果，不把失败写成成功；新用户要求重试仍按新任务处理。B先协调器回归测试纯回执与两user之间ack、正文旧兼容、思考排除，root RED后实施。预算按保留后的真实消息计算并持久化。
- 历史ACK回归RED25tests/1fail（38s，t68-history-ack-red-results；普通JVM非双SDK）：六类已知纯反馈、USER/ASSISTANT顺序、THINK排除、DB原文不变、零额外事件写入；旧有正文包装exact断言保留。独立domain agent复核同样确认丢失发生在预算前，不把推测模型因果写成确定事实。
