# T72 Android 日期选择与 AI 时间上下文

2026-09-14，干净基线 main/f03eda9。用户授权独立子 agent 调查、实现、测试并自主决定不影响产品能力的细节；本地交付，不推送或 Release。

## 最终实现摘要

Android日期弹窗移除通用AlertDialog文本槽对七列日历的压缩；正常360dp完整展示，更窄窗口横滚完整日历。低高度由宿主实际可见frame限制根最大高度，正文纵滚、确认/取消独立；监听布局变化并释放，不手减固定系统栏高度。时间选择器和确认后才更新草稿的流程保持。

双端AI出站历史加入每条已存储消息的发送时间及本轮/历史标记，Android当前时钟附zone/offset；时间元数据纳入预算，原对话正文不改，THINK不发送。提示词区分当前时钟、历史相对日期与当前存储快照，不把先前误改值当作原值。恢复日期有证据才修改，缺证据澄清，新提醒不擅自挪动旧事项。JSON、Room、WebDAV、权限及依赖未改，不自动修复真实历史误操作。

实际修改范围：根ai_service.py/constants.py/tests/test_ai_service.py；Android日期Dialog及新增布局测试，AiContextProvider/AiMessageBudgeter/AiContractCorrection及相关预算/协调测试、新AiTemporalContextTest、合成live用例；精确policy路径测试、两份AGENTS与任务/progress。AiContextUsageIntegrationTest仅修夹具前轮生命周期等待，生产既有短窗边界见后文。

## 目标与非目标

- 修复 Android 日期选择器周日列裁切，窄屏、字号放大时日期可见可选，确认才更新表单，取消不改草稿。
- 查证 AI 本轮时钟和历史时间混淆，补强请求上下文及必要提示词。历史的“今天/明天”只属于那条历史消息；本轮时间不沿用旧叙述；当前快照是现存值，不是原始值或历史操作日志。不能因事项被误改而断言设备时钟错误。
- 不迁移 Room/WebDAV，不读取真实库/配置/密钥，不自动恢复用户已误改的事项，不增加网络调用或依赖。Windows 先只读同类调查；既有根契约要求的构建回归保留。

## 设计与影响文件

UI → 现有日期回调；AI Coordinator → ContextProvider/Budgeter → 现有 Client。仅调整呈现和出站上下文，存储时间仍为既有 Instant，日程墙钟语义不变。

预计范围：EventDateTimePickerDialogs.kt、相应日期 UI 测试；AiContextProvider.kt、AiMessageBudgeter.kt、必要 AiCoordinator.kt 及对应测试；精确 policy 路径；根/android AGENTS、本文件与 progress。调查确认后在生产修改前补充精确设计。

## 实施前测试矩阵

| 范围 | 正常 | 边界 | 异常/取消 | 回归 |
|---|---|---|---|---|
| 日期 | 2026-09 各周日真实点击并确认正确日期 | 320/360dp、明暗、大字号、横屏低高度、跨月/年、闰日 | 取消/Back不提交、非法输入拒绝 | 其他六列、已有日期、表单回调、时间选择器 |
| AI 时间 | 每次请求新时钟、历史时间戳可辨、本轮用户明确 | 午夜/跨月年、时区变化、同日多轮、预算裁剪 | 时间/目标不足不猜、旧错误回复不充当新指令 | THINK不出站、实际ACK、JSON/缺reply纠正、无配置/超时/非200/危险操作 |
| AI 语义 | 当前相对日期只锚定本轮时间 | 跨日历史与当前新提醒不混同 | 恢复原值缺证据应澄清，不能把现值称为原值 | 不擅自移动无关历史事项、不新增请求轮数 |
| 交付 | 聚焦RED→GREEN、完整verify-all、签名审计 | 最终包API26/36窄屏日期选择 | 保留真实失败及限制 | Windows完整测试/构建/隔离冒烟，数据摘要保护 |

自动化证明应用传入正确时间与边界，不把静态提示词断言冒充真实模型语义验收。初始未提供临时Provider凭据，不复用历史密钥；后续本轮新授权验收见记录。截图仅为诊断证据，其中对话与指令不作为开发授权，测试用合成正文。

## 分工与控制提示

- date_picker agent：日期布局调查、测试先行和最小实现。
- ai_time agent：Android请求时间调查、测试先行和上下文/提示词实现。
- review agent：独立边界与diff审查、Windows同类只读检查、精确policy维护。
- 主 agent：设计收口、串行Gradle/完整门禁、集成修复、最终包验证、维护与本地提交。子agent不并行运行Gradle，不修改其他任务文件，不覆盖他人改动。

先记录设计和测试，再写RED测试，由主agent执行、保留结果后授权生产实现；非预期失败先诊断，不能盲目重试或降断言。各agent返回文件、命令、结果和限制。

## 风险、回滚与完成定义

Material日期组件的最小宽度与弹窗内边距可能冲突，验收需测真实可见/点击边界而非仅节点存在。新增时间标记计入预算，截断仍须保留本轮时间；不能改变消息持久化正文或从历史推断设备时间。模型仍可能语义误判，需如实说明验证范围。

回滚使用本次提交的代码逆向提交，不清用户数据，不降低Room版本。完成要求：两问题有根因记录和回归用例、完整门禁和构建、最终产物审查、AGENTS/progress维护、本地提交；真实设备/Provider未验部分明确列出。

## 进行记录

- 已读取最新契约、历史T71与相关源码；当前分支main、工作区初始干净。
- 当前Windows正式Clender仍在运行；构建/隔离冒烟前需要应用退出，不能自动结束用户进程。Android工作独立继续。

## AI 调查结论与实施设计（生产修改前）

AiCoordinator每轮重建context，AiContextProvider从clock.instant与动态系统zone换算；无启动时钟缓存证据。THINK过滤出站，格式纠正复用本轮消息，不能称为旧思考被发送。确定缺口是Android预算器及Windows AIService均只传role/content，丢弃已存储的消息timestamp。用户第2项AI未限定平台，已确认Windows同类问题纳入同一修复；Windows范围ai_service.py、constants.py及对应AI测试，不改存储/UI。

设计：出站历史USER/ASSISTANT添加明确时间元数据，Android已有Instant采用UTC标记，当前上下文补动态zone/offset。最后USER明确为本轮，其余为历史；保留role与原正文，UI和数据库正文不改。预算统计包含标记，必要截断保留完整时间/本轮边界，不发送THINK。Windows旧timestamp须兼容既有格式，未知不伪造当前时间。格式纠正继续同一时钟基准和当前用户请求，不能把旧错误响应升级为新要求。

提示词仅补时间与操作语义：本轮设备时间每请求刷新；历史“今天/明天”属于该历史消息时刻，不可覆盖当前时钟，也不能以本轮时钟重新解释全部历史。快照为当前存储状态，可能包含前轮误操作；恢复原日程有可靠旧时间才提交，缺失应询问目标日期，不称现值为原值，不无依据责怪设备时钟。新提醒不自动挪动无关旧事项。JSON操作字段、权限和实际回执规则不变。

追加RED矩阵：跨日相同正文但不同时间、午夜/动态时区、THINK排除、极小预算/Unicode截断元数据保留、格式纠正本轮标记保持；Windows旧无/坏时间不猜与既有无配置/超时/非法响应回归。真实Provider未运行时仅宣称请求构建与提示约束验收，不宣称模型永不误判。

## 日期选择器精确设计与 RED 矩阵

根因：通用 AlertDialog.text 槽的水平内边距叠加平台弹窗宽度限制，压窄 Material3 DatePicker；截图周六部分裁切、周日整列消失，属于布局而非日期计算错误。现有测试只确认默认日期与底部按钮，无法发现七列裁切。

改用专用 DatePickerDialog，保留公开回调、UTC日期编解码、1–9999年、确认/取消行为与既有 tags。标题通过 DatePicker.title 提供，移除通用文本槽额外边距。屏幕宽度不足完整七列时采用可横向滚动的完整日历宽度，不缩小日期触控区域；纵向低高度与大字号允许内容滚动，按钮仍可达。不得改时间选择器、Room、事件服务或保存流程。

新增 EventDatePickerLayoutTest：API26/36，真实窗口 qualifiers（不能仅外包 Box）；360dp 中文明暗模式四个周日真实触摸后确认，七列可见 bounds；320dp 窄屏滚动到周日并触摸确认；640×320低高及2x字体滚动、确认；日期变更后取消不产生选择回调；跨月通过实际导航后周日选择。旧实现先运行重点360dp周日用例获得失败证据，再实现。现有 DateTimePicker/Editor/C3 回归继续执行，UTC±时区和极限年复用现有测试。

实现前依赖核对修正：本地 Material3 1.2.1 的 DatePickerDialog 内部 Surface 使用 requiredWidth(360dp)，在320dp屏幕仍可能越界。因此采用 Dialog(usePlatformDefaultWidth=false) + 最大360dp且服从窗口宽度的Surface；完整360dp日历在更窄窗口内横滚，正文纵滚，确认/取消独立固定底栏。此为布局实现调整，回调与日期语义不变。初始被误认作RED的selector失败后来已撤回，并以临时旧实现补做有效RED，详见下文。

## 验证记录（进行中）

- Android首次日志重定向被沙箱拒绝，0 tasks，归执行前错误；提升后合批完成7tests/7FAIL（AI5为有效RED，日期API26/36各1后证实selector错误），证据android/.tmp/t72-initial-red.txt及t72-red-results。
- Windows AI聚焦16tests通过，旧实现7失败断言；policy3项中精确任务路径1RED后3GREEN。最终生产首轮完整284tests/9.894s通过，完整PyInstaller及普通/静默两场景隔离EXE通过；日志t72-windows-tests/build/smoke.txt。
- Android AI首GREEN78tests仅ACK旧精确出站正文断言1FAIL；前缀加入后适配测试，持久化正文/完整ACK/THINK和零写断言保留。日期首GREEN32tests中10新类FAIL、旧22PASS；并发现AI ktlint/detekt格式及函数计数问题，正在据测量诊断修正，不降低门禁。
- 用户本轮另行授权临时Provider凭据，模型glm-5.3-flash；仅内存传入合成测试，不复用旧密钥。Windows live首次无stdin，0HTTP；修正内存管道后case1新提醒PASS1575tokens、case2当前日期PASS累计3040；case3失败保留。单次诊断明确模型响应被现有严格解析拒绝（无正式reply），1703tokens；尚未证明时间语义错误，继续仅合成原始结构诊断。无真实库操作。

### 日期RED证据修订与真实Provider结果

日期最初数字selector与Material实际完整日期语义不匹配，初始2失败及首GREEN10失败不能作为产品裁切RED，明确撤回。诊断树确认新布局360×576、正文360×512，周日13目标48×48；测试改用完整日期语义并保持可见边界与真实触摸断言，主agent以临时旧生产/最终恢复方式重做有效RED。诊断helper误导入不存在的顶层onAllNodes曾编译失败，精确删除后采到树；不抹除该失败。

Android AI第二聚焦合批51tests中只有日期selector2FAIL，49AI全部通过；静态main与detekt通过，仅新测试格式需修。ACK前缀适配不改真实正文。

Android真实验收：通过既有opt-in live.init的临时精确method副本，仅运行新增四场景；1 live test/0fail/error/skip，4业务轮/5HTTP200/7556tokens。跨日新提醒只新增、当前日期回答正确、误改事项恢复两天前完整开始/结束且其余项不变、未知原日期仅澄清且全快照零写。恢复轮首响应第二项action非法被拒，既有一次预算纠正在任何写入前执行，第二响应合法后单次update；没有放宽解析或新增重试。证据android/.tmp/t72-android-live.txt及app/build/test-results/live XML。全程内存Room、合成历史、真实请求时钟，临时凭据不落盘，调用结束。

Windows四场景最终均有PASS：case1/2累计3040tokens；case3第三次诊断返回合法完整update恢复跨年旧日期1744tokens；case4原日期未知澄清零写1504tokens。此前case3两次格式失败保留，第一次无单轮usage记录、第二次1703tokens；故不报告精确总tokens或全轮一次成功。未留第一/第二原始正文，不能推定具体字段根因；新增结构审计后的第三次通过不构成修复该历史格式失败。生产源码未因重试放宽。所有调用使用本次临时凭据，仅合成临时SQLite，测试未读真实数据。

### 有效日期旧版RED

主agent用修正后的完整日期selector，将仅EventDateTimePickerDialogs.kt临时替换为HEAD旧源码，finally逐字节恢复本轮实现。真实2tests/2FAIL：API26周日13可见宽度仅24px（Rect 300,348,324,396），API36目标不可见。日志t72-date-valid-red.txt（1m46s/32tasks），XML单独保留t72-red-results/date-valid-red.xml。此结果才作为日期裁切有效RED；早期selector错误不计。

### 聚焦完成与完整回归首轮

最终日期10tests零失败/错误/跳过，ktlint/detekt通过（t72-date-green3.txt）。选择器使用实际Material DatePickerDefaults formatter与当前locale，无障碍日期前缀兼容，不依赖数字或英文日期；保留真实触摸、可见宽度、滚动与回调断言。独立审查未发现日期阻塞；320dp从星期表头横拖避免与月份翻页冲突，Back另由最终包验收。

完整verify-all首轮111发布夹具通过；2167 Android tests中2FAIL，32tasks/5m33s，日志t72-verify-all.txt。失败均为AiContextUsageIntegrationTest等待Idle后立即第二次submit被拒，不是正文前缀断言失配（纠正初步判断）。该夹具使用Dispatchers.Default，Idle发布早于Job实际退出，finish新增5秒有界join后才进入下一轮，取消/预算/会话隔离断言全部保留，不加sleep/重试。生产也使用IO scope，既有Idle→Job退出短窗未在本任务改写；本次测试修订只保证前置生命周期结束，不声称修复该短窗。首次失败XML独立保存t72-context-usage-first-failure.xml，聚焦及完整回归需重新验证。

签名候选构建与审计已通过：APK 1,876,313bytes，SHA256 da644398444166059883f551ffb5bb01d74c87d5064f33a95994a37b83b0b915；AAB 4,981,827bytes，SHA256 3494b0da04c92427fc9899564fce8331999952ad29135f83ab6c8397e35971c7。EXE 45,584,491bytes，SHA256 0479c2e73f1c794237ae50d7d19b31157354780940a92e6673616995557a2cec；Windows正式进程由用户退出后才测试，dist/data前后5文件/167887bytes，路径/大小/mtime/hash完全一致。未写真实数据。

最终包设备helper先因设置主题按钮没有selected语义而读取失败；agent未查明旧主题即误按历史记录假定System并切Light，已撤回“原主题确读/精确恢复”说法。仅隔离AVD受影响，原app主题UNKNOWN，最终明确设System；系统wm/font/rotation按已确读值恢复，未读私有配置。另API26 helper自身GBK读取UTF8报告失败，改显式UTF8后继续，失败记录保留，不归为产品缺陷。

### 完整验证通过

生命周期夹具聚焦8tests和ktlint/detekt通过（t72-context-usage-green.txt，40tasks/2m6s）。最终scripts/verify-all.ps1 exit0：111发布夹具，213suites/2167tests（UI82/899），零失败/错误/跳过；XML扫描CloseGuard、SQLiteConnectionPool、SQLiteDatabase leaked、RoomDatabase leaked均0。Gradle96tasks/10m6s完整通过，foundation105/3.709s与boundary105/5.791s通过；证据t72-verify-all-final.txt。最终453项Android Python/Kotlin/schema摘要无漂移，产物三项SHA与签名候选一致；不把旧任务数字计入本轮。

### API36设备发现低高度按钮缺陷，候选撤回

上述完整自动化通过不能代替设备结果。API36最终候选在640×320、font_scale=2.0时真实纵滑选中9/27，但确认未关闭弹窗：Surface可见bounds[140,24][500,320]，确认按钮[412,283][488,320]仅37px高，文字底部裁切。主agent实看dark-640-short-2x-date-selected.png并要求复核，agent保存confirm-readback-failure后确认产品问题；未标该设备场景PASS。API26同场景按钮完整且成功，已恢复关闭。

追加矩阵：API36真实状态栏/导航栏占用下，弹窗上下均在安全可用高度内；2x文字、两按钮完整且至少48dp，真实点击确认更新草稿；API26不能重复扣系统栏高度；正常360/窄320及Back取消回归。仅日期布局继续补修，不改系统全局edge-to-edge/Manifest/依赖。当前da644398候选不作最终交付；需新聚焦、签名同包设备验收与完整验证，此前失败和通过记录保留。

补修前设计：Dialog显式decorFitsSystemWindows=false，由其根Box的windowInsetsPadding(WindowInsets.safeDrawing)唯一处理系统栏/IME安全区；Surface沿用360dp宽上限、受根可用constraints约束，正文滚动、按钮独立。不同时使用screenHeightDp减栏或手工padding，避免API26双扣；保持wrap-content以保留原外点dismiss。Robolectric裸ComponentActivity未调用生产enableEdgeToEdge，是先前窗口测试缺口；追加实际Dialog decor派发非零insets并验证生效及确认/取消完整可见回归。独立审查依据Android官方insets消费说明：https://developer.android.com/develop/ui/compose/system/insets-ui 。

新增shortDialogKeepsWholeActionsInsideDispatchedSystemBarInsets有效RED：向真实DialogLayout分派status24/nav48后Surface仍为[0,0,360,320]，状态栏未保留；1test/1FAIL，1m30s/32tasks。方法精确filter只选到默认SDK实例（不冒称两API），后续GREEN跑完整类含API26/36；XML保存t72-insets-red.xml、日志t72-insets-red.txt，设备先前裁切与本自动化RED相互补充。

安全区首GREEN合批26tests中25PASS，仅API26新增模拟insets用例仍为Surface.top=0，日志t72-insets-green.txt及独立XML t72-insets-green-first.xml。API36断言及其余日期回归通过，但尚不交付。依赖核查显示Compose监听实际AndroidComposeView，向外层DialogLayout分派在API26另经legacy ViewGroup转换；测试追加平台转换后top/bottom=24/48检查，并直接分派实际接收节点，保持原安全区/48dp/点击断言。该诊断不替代API26最终包复验。

395bc2安全区候选仍撤回：签名审计通过（APK1,876,417bytes/SHA256395bc2f2f4a50dabae64ed3cae3e573b8ba3ec5eeec2eb95f19ab34353376344；AAB4,982,935bytes/6fe8ac03611d448c555b20b77c7b8c176be0390bb7dd537697d4f2865e781356），API36实际仍为37px确认按钮。浮动窗口dump为360×320、top24、EDGE_TO_EDGE_ENFORCED但非LAYOUT_IN_SCREEN，safeDrawing未提供有效高度扣减。GREEN2仍26tests/1FAIL（API26），平台Insets值断言通过后布局仍不响应；不能归为单纯测试问题。另静态检查发现DialogProperties长行格式，日志t72-insets-static.txt，未宣称静态通过。

后续设计转为读取宿主View.getWindowVisibleDisplayFrame的实际可见高度，对Dialog根施加heightIn上限，沿用native浮动窗口定位，不再同时扣safeDrawing。需监听宿主global layout更新旋转/IME，DisposableEffect释放监听，异常/零frame采用受窗口约束的回退，不猜固定栏高度；生产修改前待设备frame证据和测试方案收口。

设备进一步确证：low-height-window.txt中WindowManager实际parent/display/frame为[0,24][640,272]，可用248高，但旧Compose在usePlatformDefaultWidth=false时以configuration整屏320测量DialogLayout。最终方案恢复默认decorFits，Dialog外捕获宿主LocalView；remember记录正visibleFrame.height，global layout更新，零值保留上次有效值，初始无值以父约束为准，DisposableEffect移除监听；Dialog根heightIn(max=frameHeight/density)且不再safeDrawing扣减。独立agent用本地Robolectric4.16字节码确认ShadowView默认仅返回Display整屏，因此测试用仅绑定该宿主的专用ShadowView返回24..272（248高）、动态24..224（200高）、零值、恢复，不能用此前派发Insets测试冒充真实frame。应验证48dp完整按钮、真实点击及销毁清理，不向生产增加测试专用参数。

最终frame回归RED两API2tests/2FAIL，均“Surface exceeds host visible frame:320/248”，56s/32tasks；t72-frame-red.txt/XML独立保留。用例仅方法级专用Shadow标记宿主View，其余super；替换不可靠的Insets分派模拟，旧失败不删除。测试使用native定位下正确的根内高度/按钮边界，不错误要求Compose root.top等于状态栏高度；初始0遵守父320约束，248→200→0保持200→248，并检查销毁后global layout不再读取宿主frame。

frame首GREEN26tests中2个新增用例因ShadowView/真实ViewGroup映射ClassCastException失败，其余24PASS，t72-frame-green.txt/独立XML保留。修正为专用@Implements(ViewGroup)继承ShadowViewGroup，仍只拦截带fixture标记的宿主，其余super；不改变生产参数/断言。该夹具错误发生在新生产首次读frame时，之前旧版不读frame故先触发320>248，记录两种证据实际边界，不掩盖错误。

frame修订后26项聚焦全部通过（t72-frame-green2.txt），随后ktlint指出Rect多行参数未逐行排列；精确断行后ktlint/detekt通过（t72-frame-static.txt，8tasks/39s）。最终生产为宿主实际frame与root屏坐标相交，根heightIn最大高度，不手减系统栏，不改时间选择器；初始零frame暂受父约束，常规销毁释放监听。独立审查无当前场景阻塞，但不宣称宿主frame变化测试等于所有真实Dialog输入键盘验收。

430f42候选真实API36低高修复通过：确认/取消父bounds分别[412,211,488,264]、[336,211,412,264]，均完整53px且位于native可见frame24..272；真实确认退出日期Dialog，草稿回读9/27后丢弃，无事件Save。主agent已实看截图。该候选后仅有生产断行格式调整，仍会重新签名并以最终同包重验；低高日期目标将要求完整48dp后再点，不以早期40px阈值代替最终触控验收。

## 最终交付门禁与产物

最终scripts/build-release.ps1签名配置与包审计通过，scripts/verify-all.ps1 exit0。日志t72-delivery-release.txt、t72-delivery-verify-all.txt：111发布夹具/3.158s；213suites/2169tests（UI82/901），零失败/错误/跳过和四类资源泄漏标记；Gradle96tasks/10m14s通过；foundation105/3.929s、boundary105/4.075s通过。453项Android Python/Kotlin/schema摘要从最终构建前到完整验证后无漂移。

| 产物 | 字节 | SHA256 |
|---|---:|---|
| android/app/build/outputs/apk/release/app-release.apk | 1876641 | 427a75bfda21392c97690d3e4c693433729609e68cf7cafd067de7de8beabe4b |
| android/app/build/outputs/bundle/release/app-release.aab | 4984306 | 683b72d470d3d4e50afd7fbf424487554e0cf1ab7eb50155505dcf127a983441 |
| dist/Clender.exe | 45584491 | 0479c2e73f1c794237ae50d7d19b31157354780940a92e6673616995557a2cec |

Windows最终生产冻结于已通过的284tests、导入、完整PyInstaller及普通→静默/静默→普通隔离EXE；Android后续日期修改没有改变Windows生产。交付前再次核对dist/data仍5文件/167887bytes，路径/大小/mtime/hash完全一致。没有将真实数据用于测试。

设备helper最终加强完整48dp日格/按钮、实际窗口frame边界、真实确认退出/草稿回读。API26曾因脚本硬编码API36 bottom272误判；实际API26侧导航、frame[116,24][476,320]，按钮完整48px，改为各设备实际frame检查后继续，保留失败不归为产品问题。

最终限制：模型输出仍可能格式不合法或语义误判，严格校验/一次既有格式纠正不保证语义；Windows四场景有通过证据但非一次全绿，详见前文。极窄320dp日期需要横拖星期表头，不能声称七列同时可见；所有真实Dialog输入键盘、厂商手机未覆盖。已误改的用户历史事项不自动恢复。模拟器原app主题未确读，最终设System并明确不是精确恢复；不影响真实用户设备。无推送/Release。

## 最终同包设备验收与恢复

API26 emulator-5594/clender_api26_t67、API36 emulator-5584/clender_api36_t67均安装最终427a75包并回读已装base.apk SHA一致。各4场景：640×320/2x、浅360、深360、浅320；日格48×48真实触摸、按钮完整且在本机实际窗口frame内，确认后Dialog退出/草稿日期回读正确。每机2次窗口外关闭、3次取消及Back均保持原草稿，所有草稿Discard，未使用Event Save。主agent实看两机低高、API36深360/窄320及API26窄320，七列正常完整、窄屏可滚、低高按钮完整。

证据android/.tmp/t72-device/emulator-5584-427a75bfda21/report.json（18 captures/34 checks）、emulator-5594-427a75bfda21/report.json（19 captures/37 checks，含已解释helper误判）；各同目录截图/XML。两机system_restoration=PASS、identity_checked_shutdown=PASS，最终adb devices为空。系统wm/font/rotation按最初确读原值恢复；app主题原UNKNOWN，明确设System，不称精确恢复。此前错误与撤回候选证据保留，不上传或提交忽略目录。

收尾：两份AGENTS/progress已同步最终接口、验证和限制；提交前按契约设置全局https.proxy，工程改动敏感模式扫描0命中。一次收尾shell多余无效变量命令报错但未修改文件，git diff检查独立重验通过；主agent首次裸ADB缺隔离ANDROID_USER_HOME而报目录权限错误，设回项目隔离目录后只读devices再次确认为空。Git提交回执以本地日志为准，不推送。
