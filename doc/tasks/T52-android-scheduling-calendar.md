# T52 Android 排程可靠性与日历视觉优化

> 后续核验更正（T64）：本任务 c2ef074 基于旧 Android 分支，未包含已在 main/773c2dc 交付的自定义背景与图标。下文 T52 验收只证明该分支范围，不能证明保留了 T60–T63 功能。当前用户要求已先恢复 main/773c2dc，再叠加本任务 Android 改动，最终以 T64 交付记录为准。

2026-09-07，起点 `6e5b525`，开始时工作区 clean。当前状态：实现、完整构建、签名审计、真实Provider与Android响应重放、Windows验收及三模拟器视觉/手势均完成；最终索引审查和统一Git提交为最后步骤，实际回执见Git日志和最终答复。

## 最终验收汇总

- 最终源码：167 suites/1759 tests，UI63/757，failure/error/skip及四类泄漏标记均0；349份源码与签名时摘要一致。完整verify-all 96tasks/7m2s、111发布夹具、foundation与boundary各67项PASS。
- APK：`android/app/build/outputs/apk/release/app-release.apk`，1,735,854 bytes，SHA256 `448c8372ee71a508e9d3cbd9492ff704a57c0d34250d54fcc98b77ebfa87a7e7`。AAB4,656,828 bytes，SHA256 `0bc28e7cc8160dbbbc2f9719a180998d2359e7a69fd0efc52dcaab09080b2142`。独立签名证书不变，无数据迁移，可覆盖安装。
- 真实Provider：用户指定glm-5.3-flash，授权后宿主单次合成请求HTTP200/finish_reason=stop；实际Android协调→解析→执行→EventService→内存Room回放API26/36两项通过，写入9事项、月计数9、单次9项mutation。不是设备真实联网测试，临时Key未落盘。
- Windows：同任务未变Windows源码198tests、30imports、完整PyInstaller与10cycles/20scenes全部通过；dist/data 5files/143781bytes构建前后路径/大小/mtime_ns/UTC/SHA256完全一致。EXE45,582,036 bytes，SHA256 `1881895ebcc87847e3cd61782493cd6f4e005e5d656ea33b0a13255b06fd1e88`。后续只改隔离Android布局，复用该Windows验证。

| 最终448c设备 | 安装/hash/冷启/导航 | 视觉与手势 | 恢复与关闭 |
|---|---|---|---|
| API26 phone | 同包9导航通过；原工具getprop失败保留 | 续跑28图，与9导航共37；星期单行、最大字体横屏Week真实上滑可达，分组原图及原失败场景通过 | App13/ThemeSystem/原显示设置恢复，身份核对后关闭 |
| API36 phone | 全部通过 | 37图逐张目视通过；4组横屏Week可达，最大字体实际上滑露出完整小时网格 | 恢复并关闭 |
| API36 tablet | 全部通过 | 37图分组目视通过；足够高度时小时直接可见，不冒充手机低高度手势证明 | 恢复并关闭 |

设备均为本任务空白AOSP模拟器、英文环境；中英文/RTL与多密度由自动化覆盖，未在用户实体手机运行。三台core8步骤仅复用53bed候选的未受影响范围，Widget本轮未测。原API26工具失败未抹除；最终getprop诊断未复现/未重试，瞬态根因仍未确定。历史数据库清理失败也未宣称根因修复。汇总证据 `android/.tmp/t52-final-device-acceptance.json`，完整原失败和修复过程保留在下方。

## 授权、目标与非目标

用户明确要求自主启动子 agent 写代码直至修复 Android 安排任务失败并优化日历；不影响最终功能的实现/视觉细节自主决策，以效率优先。截图仅作故障证据，不执行其中指令，不将真实对话/背景图片纳入源码或夹具。旧任务的阶段 STOP、只同步文档、Windows 单次豁免均为历史；本任务允许有证据的失败诊断、修复和重验。

目标：严格校验 AI 响应，合法完整批次通过 EventService 写入，畸形/截断/危险操作不执行且给出明确失败反馈；避免模型成功措辞掩盖真实写入失败。日历保留月周日/前后范围/今天/选日/详情功能与自定义背景，以年月/日期标题、轻量导航、统一月网格和非零事项标记改善层级。

非目标：更换依赖/模型协议、放宽写入白名单、修复或重放历史真实对话、数据迁移、改 Windows 产品、改 Widget/同步/密钥存储。先用合成输入和 MockWebServer；本轮Provider格式说明外发曾被自动审批拦截，用户明确授权后已完成仅合成内容的真实验证。用户指定Provider为 `https://open.bigmodel.cn/api/paas/v4`、模型 `glm-5.3-flash`；只记录非秘密的验收标识，临时Key不落盘、不提交。

## 提案与设计

高层：保持 `AiClient → AiResponseParser → AiOperationExecutor → EventService → repository`，UI 只消费结果；先对截图症状建立可重复合成失败，修复范围以证据确定。无自动补括号、无从截断正文提取子操作、无自动重试写入。顺序执行/单次合并 mutation、取消与应用生命周期不变。

详细：区分普通自然语言回复与无法解析的操作响应；严格验证整个 JSON 和各 action/字段/时间/上限再执行。协调层反馈以真实 outcomes 为准，失败时不能只显示模型的成功 reply。具体格式分类和测试结果在 RED 后补录。日历工具栏拆成可读范围标题与紧凑模式/范围操作；月网格共享柔和主题表面，普通日期透明，选中/今天有清晰区分，零计数不显示，非零标记保留完整无障碍计数。保持 locale 周起始、48dp 目标、滚动、状态独立空间及大字体/横屏适应。

## 写集与控制提示

- AI agent：domain/ai/AiResponseParser.kt、必要的新同包 helper、AiMessageBudgeter.kt 系统契约及对应 parser/budgeter 回归；先测试，主 agent 保存 RED 后才修改生产。
- 日历 agent：ui/calendar/CalendarScreen.kt、MonthCalendar.kt、TimelineCalendar.kt、必要同包 toolbar/helper、values/values-zh-rCN strings 及 calendar 专用回归。保留现有测试/helper的冻结边界。
- 集成 agent：app/ai/AiCoordinator.kt、必要同包反馈 helper 与专用 coordinator/Room 集成测试。先只读审计，再按主 agent 明确分派写入；不与 parser agent 重叠。
- 集成 agent 补充：data/network/ai/OkHttpAiClient.kt 与专用完成状态回归；显式非 stop 的 finish_reason 不允许进入写入，缺失字段保持既有 Provider 兼容。先合成 RED 再生产。
- 主 agent：本任务/progress、根与 Android AGENTS、Android README/高层与详细设计/android-prompt 的必要同步；tests/policy 精确加入本任务路径及正负测试；测试编排、审查、集成修复、发布/设备/Windows验证、最终索引/提交。

各 agent 读取两级 AGENTS、相关设计和本任务；只改写集，不自行提交，不并行运行 Gradle、不读取真实 data/秘密，使用合成内容。报告文件、命令、RED/GREEN、风险。主 agent 串行 Gradle，审核 diff 后集成；独立 agent 在最终阶段只读复核另一写集。

## 实施前测试矩阵

| 范围 | 正常 | 边界 | 异常/非法 | 回归 |
|---|---|---|---|---|
| AI parser | 完整对象/数组/单操作/JSON fence，多项 add 与 reply | 16/17项、32KiB/string上限、跨日和空批次 | 缺闭合/截断、类型/字段/时间/危险action、混合无效批次 | 普通文本仍可回复；畸形操作零写入；不修补执行 |
| 执行/反馈 | 多事项真实写入、reply、一次mutation | 部分成功/全部失败/无操作，缺失ID | 仓储失败/取消/畸形响应 | 真实 outcomes 优先；原会话归属、usage、无重复写入 |
| 网络/请求 | 合成完整Chat Completion | 输出预算与响应限制 | 无配置/超时/非200/坏JSON/取消 | Thinking fallback与Key擦除/请求释放原契约 |
| 日历布局 | 月周日/标题/前后/今天/选日/详情 | 月末/跨年/闰日、中英文周起始、360dp/平板/横屏/200%字体 | 空/加载/错误/重试、负计数夹紧 | 状态不遮盖网格/时间轴；48dp/选中/today/语义计数 |
| 日历视觉 | 统一层级、柔和表面、隐藏0计数 | 大计数/长标题/深浅主题/背景开启关闭 | 不裁切、不重叠、动作可达 | API26/36 Compose与最终release截图检查 |
| 交付 | 全Android JVM/lint/静态/策略/签名 | API26/36phone与API36tablet release | 缺签名/敏感/generated边界 | Windows198/import/check/完整PyInstaller/20场景与dist/data只读摘要 |

## 步骤、风险、回滚与完成定义

1. 独立诊断与视觉方案，新增合成自动化回归并保留旧实现可编译 RED。
2. 各 agent 小范围实现，主 agent 聚焦 GREEN，失败留证后按明确原因修复，不盲目重跑。
3. 独立复核与完整 Android verify-all、签名 APK/AAB；最低设备安装/冷启和日历视觉/交互验收。
4. 根契约 Windows 回归/构建/隔离 smoke，dist/data仅由Windows验证读元信息/hash，Android 不访问；若用户实例占用，保留原状并记录限制。
5. 同步文档、验证来源哈希、精确 staged 范围/敏感审查和索引 boundary；按根契约统一提交，Git回执以实际结果为准。

风险：截图不足以证明唯一根因；不得以猜测放宽解析。主题透明度需真机图像核验。仓储部分失败保持既有逐项语义，不声称事务原子性或修复历史 Room NON-REPRODUCIBLE。回滚仅 revert 本任务工程提交，无数据迁移/真实数据回写。

完成定义：故障路径 RED→GREEN、正常排程确实写入且失败反馈可信、日历功能/自适应回归与视觉验收通过、所需构建门禁和产物审计、文档维护及 Git 提交完成；未完成验收必须明确列出，不能复用历史计数宣称本轮通过。

## 实施记录

- Parser旧实现15 tests/5 failures，32 tasks/1m54s；合成截断/缺闭合/围栏/尾随内容均因PlainReply失败，完整10项与既有合法/安全路径通过。日志 `android/.tmp/t52-parser-red.txt`，XML `t52-parser-red-results`。修复仅语法异常时识别结构开头且含quoted operations/action字段的响应并有限拒绝；不恢复执行。
- Policy专用5项/1 RED → 5/5 GREEN，仅新增本任务exact path。首次policy调用cwd拼接错误/临时目录沙箱拒绝，0 tests；纠正cwd并以宿主权限保存证据。
- 首次Gradle daemon退出触发30天artifact GC，`daemon-173732.out.log:271`起记录删除files-2.1的1608 files/directories、metadata删除0；随后offline在0任务阶段丢失plugin marker。help诊断/联网缓存恢复日志保留，strict检查拒绝3份未登记父metadata，未修改锁/信任文件。恢复marker后进一步发现已有运行依赖缓存缺失，正在按原锁定版本/strict联网恢复，不将环境失败算业务RED。
- Windows预检发现现有Clender进程或endpoint，`t52-windows-blocked.json`；尚未启动Windows测试/构建，也未读取dist/data。保持用户实例原状，Android工作继续。
- 用户提供新的临时Provider授权；凭据不复制入任何文件。真实probe的源码格式提示词外发被自动审批拒绝，已向用户请求该具体发送内容授权，未发送请求。合成测试不受影响。
- 缓存恢复完成：既有933项未重写，241项缺失文件逐项匹配冻结SHA256恢复（首轮236，5个Google测试平台POM按正确原仓库补取）；生成占位夹具不作为远程库下载。未更改任何锁/verification，恢复后严格offline进入测试。
- 第二组真实RED为34 tests/23 failures/0 errors：Calendar12/8、coordinator Room12/6、client8/7、mandatory prompt2/2；32tasks/1m2s。Parser已最小修复，因此该轮截断集成用例GREEN；Room九事项与单批mutation已通过。日志 `t52-ui-integration-red-final.txt`，XML `t52-ui-integration-red-results`。此后授权三agent实现。
- 完整policy67/67通过（3.523s），不是最终索引门禁。
- 第一组修复后25 suites/232 tests全部GREEN，failure/error/skip=0，XML `t52-focused-results`；同命令后续detekt因残余解析缓存缺失失败，不记整命令PASS。静态工具缓存恢复后detekt仅2项（completion复杂度与测试常量），已提取helper/改常量，无行为放宽。
- 独立审查发现大字号文字裁切：新增真实TextLayoutResult回归4/4 RED（1m55s），证据 `t52-calendar-text-red-results`。随后日期列最小宽按当前字体实际1..31最大字宽+原内距测量，窄屏可横滚；完整范围标题与导航不足一行时拆行，保留字号和完整文字。
- 精确格式化只处理本轮12份Kotlin文件；临时CLI因plugin排除Clikt而未启动，改用已锁定ktlint插件filter/API。保留原格式诊断，测试常量命名手工修正；无全局格式化/新依赖/信任修改。当前最终聚焦和静态检查运行中。
- Windows安全独立项：生产30模块导入与build.py --check均exit0；完整tests/EXE因用户实例仍暂缓，未读取dist/data。
- 本轮明确新建三空白AVD clender_api26_t52phone、clender_api36_t52phone、clender_api36_t52tablet，全部隐藏窗口启动；与旧AVD分离，最终release安装/截图待执行。
- 大字号聚焦140 tests/2 failures，余下均为日期didOverflowWidth；诊断2/2重现size38、intrinsic38、可用40，属于Compose段落约束宽度与Text尺寸比较的假阳性，不能据此宣称旧日期字形裁切。改为逐行/逐字形边界及禁止省略的真实几何验收；标题原省略RED仍有效。保留 `t52-final-focused-failure-results`、`t52-date-diagnostic-results`。静态detekt通过，ktlint仅新增诊断行换行失败，已修正。
- 独立AI审查未见阻断问题；再次只读Windows预检为2个Clender进程、endpoint=false，仍不满足清洁构建条件，已向用户询问正常退出或本轮Android范围例外；未读取dist/data。
- 日期几何断言稳定后启动完整verify-all，日志 `t52-full-final-gates.txt`；不将尚在运行的门禁标为通过。
- 首次完整JVM165 suites/1741 tests，仅既有Drawer边缘右滑API26/36两失败，error/skip=0；32tasks/3m13s，wrapper未进入后半门禁。XML `t52-full-initial-results`。独立审查确认新增月网格横滚在411dp无溢出时仍抢手势，最小修复为仅gridWidth>maxWidth启用滚动，不修改既有Drawer回归。日期逐字几何及标题回归均通过。
- 手势修复后完整运行165/1741中Drawer24/24通过，唯一CalendarOverflowDetailPlaceholderTest的After清理失败，suppressed明确隔离数据库删除失败；RoomShutdownOwnershipTest8/8且delete=true。3m18s/32tasks，XML `t52-full-after-gesture-results`。冻结测试/helper/AppContainer无修改；这是历史同签名失败再现，根因未确认，不能宣称修复。独立审查建议一次有界聚焦诊断（同时验证本轮Drawer），未复现才再次全门禁；再次复现须进一步句柄/关闭次序诊断。
- 相同源码的有界CalendarOverflow+Drawer诊断25/25通过（57s/32tasks），XML `t52-teardown-diagnostic-results`；记录为本次聚焦未复现，历史清理根因仍未确认。开始签名构建，之后同源码最终全门禁与隔离设备并行验收。
- 首次release在lintVitalAnalyzeRelease因锁定lint-checks:31.13.2离线缓存描述缺失失败，签名检查本身通过。只从daemon提取固定错误类别，不输出签名日志。启动strict联网lintDebug/lintRelease/detekt/ktlint恢复工具描述缓存；未更改依赖或verification。
- 工具描述恢复后lintDebug/lintRelease/detekt/ktlint全部通过，63tasks（20执行/1缓存/42up-to-date）、4m20s，日志 `t52-lint-cache-hydration.txt`。相同源码重新启动独立签名构建。
- 签名构建及APK/AAB完整审计PASS，日志 `t52-release-after-cache.txt`。APK 1,730,098 bytes，SHA256 `53bed340661a1408a5531a4a5fa1a34d0f40b52acaf9b67233bbad7293b60d61`；AAB 4,640,421 bytes，SHA256 `29d3670e23625c11c7f944469387b74d379da9e7b5545ff10003fd06f4085783`；证书SHA256 `628248932cfd0587a04126290ac2af870591fc7ebe80259c5f29d266c944615f`，与既有独立证书一致。设备脚本不把Widget范围外BLOCKED伪称通过。
- 用户明确授权发送内置格式说明和合成内容后，真实Provider单请求HTTP200、finish_reason=stop、938字符完整JSON、10操作/9add。临时Key隐藏输入仅内存，未写入文件/测试/日志。合成响应仅忽略目录保存用于后续实际Android链路重放；尚不能把HTTP成功等同Room写入验证。
- 同源码最终JVM165 suites/1741 tests、UI61/739均零failure/error/skip，四类泄漏标记0；XML `t52-verified-final-results`。完整wrapper后半门禁仍运行，日志 `t52-verified-final-gates.txt`。
- 最终完整verify-all PASS/exit0：96tasks（25执行/5缓存/66up-to-date）、7m3s；111发布夹具2.860s、foundation67/67（4.452s）、boundary67/67（4.030s）。345份app/src文件与手势修复后摘要完全一致，冻结构建文件无diff。全量绿色不抹除此前历史数据库清理失败，也不构成根因修复证明。
- 三台最终包核心验收各8/8通过；日历工具初次sandbox ADB home失败发生身份查询前，升权后正常。初版calendar工具误点已选中不可点击Tab、误读child日期desc/parent checked节点，属于工具前提错误，保留首轮报告并按真实XML改独有r2工具，产品不改。
- 真实响应重放2/2通过（API26/36，1m8s/32tasks）：实际AiCoordinator→AiResponseParser→AiOperationExecutor→EventService→内存Room写入9条，精确核对Test1–9标题、09:00–17:00时间、月计数9和一次9项mutation batch；reply与解析后实际reply一致。临时init script仅添加忽略目录测试源，未改产品或提交测试集；XML `t52-live-replay-results`。这是宿主真实HTTP请求+Android响应重放，不宣称设备上真实联网请求。
- 设备视觉发现额外确定性问题：API36 tablet的week-today日期列头居中而时间网格留在左侧，因TimelineCalendar只取固定最小bodyWidth，header requiredWidth在fillMaxWidth约束内居中。候选包53bed的日历验收因此未完成，不能作为最终通过包。新增TimelineCalendar范围：日/周共享实际可用宽度，同时保留48dp lane最小与横滚、RTL/scroll-state/事件点击；先新增真实列头与Canvas边界回归，在320/360/840dp、1/7列、空/多lane、API26/36与RTL验证并留RED，再修复。回滚仅此布局改动，无数据影响。
- Windows实例自然退出后只读确认0进程/endpoint=false，原授权下恢复完整门禁。198/198（4.213s）、imports30/30、build.py --check、完整PyInstaller（68.016s）、10cycles/20scenes smoke（121.735s）全部PASS；dist/data 5files/143781bytes的路径/大小/mtime_ns/UTC/SHA256前后完全一致，清理0进程/endpoint=false/0smoke目录。EXE 45,582,036 bytes，SHA256 `1881895ebcc87847e3cd61782493cd6f4e005e5d656ea33b0a13255b06fd1e88`。原blocked记录保留；本任务Windows源码始终未改，后续Timeline修复仍为隔离Android范围。
- Timeline专用初次0tests编译失败（DpRect无width，改为right-left）；之后可编译RED为6tests/4failures（55s/32tasks），XML `t52-timeline-alignment-red-results`。600dp day应填544dp却只有304dp；20sp×2时hourLines=2、glyph底68>height60、日期词宽68>可用40。窄屏scroll现有2项通过。授权统一实际宽度、按字宽测量gutter与日期最小列，保持原文本/点击/48dp lane/RTL，不降低字号或隐藏文字。
- TimelineDimensions按真实文字统一gutter/最小列/viewport宽后，150项聚焦仅2项像素偏差（600/7列header2=210，Canvas=211.42857）；改按共享body像素边界差分配列宽，150/150通过，原失败XML `t52-timeline-focused-failure-results`与绿色 `t52-timeline-pixel-green-results`。detekt通过，ktlint只一处测试参数换行；纯格式修正后8tasks/35s静态PASS。独立审查指出真实手机density2.625时左右4dp分别取整比合计8dp多1px，正在增加该密度回归后精确处理内距舍入，不加任意余量。
- density2.625补充8tests/4failures，证据 `t52-fractional-density-red-results`：Timeline确有hourLines2/glyph178>158及dateWord184>183，授权按左右4dp分别roundToPx计算内距。月格字宽100<=可用104，但size高度0来自测试缺少生产父verticalScroll、物理root高度有限压缩后排；仅修测试容器为真实滚动测量，不以此修改月格生产或声称字宽bug。保留原glyph断言。
- 精确内距修复后150/150通过，XML `t52-density-focused-green-results`。detekt仅测试render参数数目，改private参数对象；ktlint仅Viewport声明换行，纯格式修正后静态8tasks/36s PASS（`t52-delivery-static.txt`），未放宽规则/断言。347份app/src最终摘要 `t52-signed-delivery-source-hashes.json`。
- 新交付候选签名审计PASS（`t52-delivery-release.txt`、`t52-delivery-artifact-metadata.json`）：APK 1,730,962 bytes，SHA256 `091fa76dabe4748b32f15c880a5cd7f59f6de9989d4ce38131b43a6d4e5d7c34`；AAB 4,644,115 bytes，SHA256 `dab1dd1ac4e7cf4fda9510443c9849e8e0e8ef75c5dc8759168c04e1032b2e5d`；同一独立证书。最终verify-all与三台新包安装/hash/冷启/33图矩阵并行。后续仅Timeline几何改动，旧53bed核心每台8步按未受影响范围复用，明确不说所有核心场景在新包重跑；新包覆盖所有日历模式/实际修改路径。
- 最终源码JVM166 suites/1747 tests、UI62/745均failure/error/skip=0，四类泄漏标记0；快照 `t52-delivery-full-results`，347份签名源码摘要零变化。后半lint/构建门禁仍运行，此处不提前标完整wrapper通过。
- 最终API26设备工具完成9导航图后在font20设置阶段超时，恢复进入弃草稿对话框；原 `failure.txt` 保留。诊断为键盘遮挡Save使工具点击未完成保存，按当前InputMethod真实可见状态修独有工具后仅续跑24视觉；不修改生产、不抹除首次工具失败。
- 最终完整verify-all PASS/exit0，日志 `t52-delivery-full-gates.txt`：96tasks（25执行/71up-to-date）、8m35s；111发布夹具4.671s、foundation67/67（6.981s）、boundary67/67（6.587s）。对应上方166/1747、UI62/745的最终源码，冻结依赖/测试helper均无改动；设备视觉与索引审查继续收口。
- 091fa候选phone实图 `light-20-portrait-month.png` 显示20sp×2的英文星期缩写Sun/Mon/Wed拆成两行，数字与周/日已正常；裁定为本轮视觉阻塞。扩展既有MonthCalendar写集：先在CalendarVisualRegressionTest补星期真实TextLayoutResult单行/词完整回归（320/360/840dp、density1/2.625、中英文、API26/36），再将星期实际字宽与逐边内距纳入最小列宽；不缩小字号/截断文字，不改变48dp或overflow-only滚动。新包重验月模式，091fa未受影响周/日证据明确复用；原候选全量和失败证据保留，不冒称新源码已验收。
- 星期旧实现可编译RED为2tests/2failures，32tasks/2m4s（`t52-weekday-red.txt`、`t52-weekday-red-results`）；320dp/en_US/density1.0的weekday0 intrinsic52>available40、lineCount2，直接重现实图。已授权最小宽度修复，不改变文本/字号/断言。
- 星期首修后75项聚焦73通过/2失败（2m27s/32tasks），`t52-weekday-measurement-failure-results`：已单行且advance/size60，但Wed实际glyph右端60.5越界。按实测左右glyph overhang对称分配居中文字宽后ceil，再加左右padding取整；不任意加宽、不放松字形完整断言。Java枚举改values()在编译前完成，未产生编译失败。
- 实测居中字形边界修复后星期2/2与detekt/ktlint全部通过（40tasks/2m19s，`t52-weekday-glyph-green.txt`、`t52-weekday-glyph-green-results`），启动签名构建。随后API26真机模拟器低高度手势证实另一阻塞：20sp×2横屏Week上滑两次仍没有时间网格（`t52-api26-week-scroll-proof`）；此前“整页滚动”解释错误，实际仅MONTH滚动、Timeline表头在body滚动之外，权重将body压到0。原解释不作为通过依据。
- 低高度修复写集扩展CalendarScreen、必要同包测量helper及新CalendarViewportAccessibilityRegressionTest：先覆盖API26/36、840×320/320×480、app20×2、中英文、EMPTY/CONTENT/LOADING，上滑后小时/网格正高度且事件点击可达；正常高度复用既有几何/Drawer回归。按真实toolbar、状态、日期表头及既有1小时60dp构成最小高度，仅不足时允许外层纵滚并给viewport有限正高度，不缩字、不截断、不改数据/导航/状态分区。最终新包覆盖三模式；旧core8仍按无关范围复用，原候选不标全部视觉通过。
- 低高度可编译RED6tests/3failures（32tasks/1m34s，`t52-low-height-red-results`）：真实滚动后的第一小时仅54/18/57dp可见，不能满足完整60dp；其余正常高/部分触摸路径通过。eventFixture为合法REMINDER/end=null，无无效数据误报。独立审查同意捕获外层有限高度、单一外滚动和有限viewport；要求复用实际日期列像素取整、避免无限约束/重复有状态子组合，ERROR保持独立可访问。
- 星期单独候选签名审计PASS：APK1,731,586 bytes，SHA256 `999c5d427ced46d78b3312dc6227aa8e20cdc6bbef3568ab44cc3af7e5db8f6e`；AAB4,645,813 bytes，SHA256 `bbbef25cf7c3301e8e57945e729007f73efdfcf4eb17cc3750988c2bb56dec48`；证书不变。因正在修低高度，不对此候选重复最终全量/设备矩阵，不将其作为最终交付。
- 低高度首修85项/6失败（1m38s）保留 `t52-low-height-first-failure-results`；tests-only诊断10项/6失败（1m11s）保留 `t52-low-height-diagnostic-results`。实测正文已正确为60dp、外滚max3/6/42px，但真实swipe使内滚到740px而外滚始终0，故仍被外层裁切；需向上手势优先消耗外层余量再滚小时，不能以估高不准继续加padding。另API26纯手势case正文94dp且外滚max0，强制标题移动不合理；ERROR内容完全容纳时无ScrollAction也不是故障。按真实可见高度/触摸可达目标修测试前提，保留原失败，不降低字形/小时完整断言。
- preScroll仅向上先消耗外层实际余量，向下保持内层回顶/余量传外层链。10项验证8通过/2纯手势失败（1m15s，`t52-low-height-scroll-failure-results`）；实测API36外滚3/3且正文可见60dp、API26正文94dp，修复已生效。剩余断言错误要求任意fling后单个小时格完整，737/740px偏移跨小时使60dp视口自然分成两格；改验证完整60dp网格视口及可见小时文本，不使用语义滚动。另8项的hour0完整60dp、事项实触与ERROR重试断言继续保持。
- 低高度最终聚焦10/10通过，failure/error/skip=0，XML `t52-low-height-final-focused-results`；同命令detekt通过，ktlint仅TimelineDimensions数据类声明换行（1m24s/38tasks）失败，已作单行纯格式修正再执行静态门禁，不将原命令记为整体PASS。新增纯触摸同时验证下滑5次恢复原工具栏位置，无生产诊断日志。
- 纯格式修正后静态8tasks/39s PASS（`t52-low-height-final-static.txt`）。最终候选签名审计PASS（`t52-accessible-release.txt`、`t52-accessible-artifact-metadata.json`）：APK1,735,854 bytes，SHA256 `448c8372ee71a508e9d3cbd9492ff704a57c0d34250d54fcc98b77ebfa87a7e7`；AAB4,656,828 bytes，SHA256 `0bc28e7cc8160dbbbc2f9719a180998d2359e7a69fd0efc52dcaab09080b2142`；证书仍为 `628248932cfd0587a04126290ac2af870591fc7ebe80259c5f29d266c944615f`。最终源码摘要 `t52-accessible-final-source-hashes.json`。完整verify-all及每台37图（原33+4横屏Week真实上滑可达）并行，新包覆盖三模式，旧core8按无关范围复用。
- 448c最终源码完整JVM167 suites/1759 tests、UI63/757全绿，failure/error/skip及四类泄漏标记均0；XML `t52-accessible-full-results`。349份源码摘要与签名时一致、27份工程精确白名单无秘密模式。后半完整wrapper与设备矩阵仍运行，不提前记录未完成验收。
- 448c同源码最终完整verify-all PASS/exit0（`t52-accessible-full-gates.txt`）：96tasks（28执行/68up-to-date）、7m2s；111发布夹具2.693s、foundation67/67（5.691s）、boundary67/67（5.315s）。后续不再改生产，仅设备验收、文档和最终索引提交。
- 448c API26安装/hash/冷启/9导航通过后，font20阶段只读getprop identity非零ADB_FAILED导致工具中断，finally Drawer状态未恢复又报NODE_NOT_FOUND；保留accessible-final原报告。只读再次确认qemu=1/MainActivity resumed，先恢复设置后仅续未完成28图；不把工具失败当产品修复或重复写入依据。

- 最终索引审查：27个工程exact-path白名单、staged diff check、敏感模式扫描全部通过；349份源码及448c APK摘要无漂移。暂存后boundary67/67（4.294s）PASS，日志 `t52-final-index-boundary.txt`；文档收口后再次执行最终索引门禁，再统一提交到当前 `codex/android-architecture`。实际提交哈希以Git日志与最终答复为准。
