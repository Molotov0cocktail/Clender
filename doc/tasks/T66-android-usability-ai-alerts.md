# T66 Android 可读性、提醒与 AI 执行可靠性

日期：2026-09-08。基线：main/3e687d4，初始工作区干净。状态：实现、完整验证、签名与设备验收完成；本地提交以Git日志为准，不推送。

最终交付：APK `android/app/build/outputs/apk/release/app-release.apk`，SHA256 `5f5e84db72b6df8d86aca7020e6ede7e33c50247df0a644fa601f589a0dc57b9`；196suites/1941tests（UI76/828）及完整门禁通过。API26/36最终同包原事项标题、后台播放、Stop释放通过；真实Widget时间/透明度、明暗日历、权限与恢复证据分候选保留并注明身份。没有真实Provider、厂商真机或人工听音测试。系统热改字号后须重新保存Widget设置；模型上限需服务端提供元数据，缺失保留手工值。

## 目标、非目标与授权
用户授权子 agent 自主实施并完成 Android 优化，非影响最终功能的细节自主决策。覆盖截图日历色块、Widget 时间与透明度、全面屏、事项通知/闹钟/计时器、AI 实际执行及内嵌提示词；追加模型获取后能力参数与 Thinking 强度设置修复。仅 Android，跳过 Windows 测试/构建/真实数据读取。无推送、无真实 Provider 凭据使用、无依赖升级。

## 提案与设计
1. 日历相邻事项独立间隔/边框/稳定配色，明暗主题分别保证前景对比；短事项与重叠命中、完整详情、月份导航不退化。
2. Widget 时间使用完整且可换行的专用布局，按尺寸与字号测量；透明度只影响背景，设置每实例保存后刷新，旧配置保持原默认外观。
3. Android 窗口使用 edge-to-edge 和正确系统栏图标明暗，内容使用安全 inset；应用导航功能保留，避免顶部灰色区域及重复留白。
4. 事项增加本机提醒策略：notificationEnabled（新增 reminder 默认 true，timespan 默认 false）、alarmEnabled（默认 false）、timerMinutes（0 关闭，1–1440 分钟，从事项开始计时）。闹钟显式重要事项使用；AI 默认普通提醒使用通知，不擅自广泛启用闹钟。本机 Room 显式 v1→v2 迁移保存字段，旧事项关闭新增提醒，防升级补响；WebDAV v1不新增字段，远端合并保留同 UUID 本机策略。通知/闹钟由独立平台适配器调度，修改/删除取消旧调度，重启/时间变化重建未来项，权限拒绝必须明确展示。计时器按事项开始+分钟到期提醒，提供可见状态；平台限制不能伪报调度成功。
5. AI 使用固定内嵌操作契约和每请求新鲜日程/当地时间，用户自定义人格与操作系统契约分离；去除用户系统提示编辑入口。执行结果来自真实落库和操作回执，reply-only/空操作/失败不得显示系统级修改成功；不执行 thinking 内容。模型参数采用服务端可验证元数据，未提供能力信息时保留可编辑值，不编造未知模型上限；Thinking 模式与强度可修改保存并实际进入请求。

## 接口与影响文件
日历 agent：ui/calendar、窗口入口/主题及其测试。Widget agent：domain/widget、data/settings Widget codec、ui/widget、widget renderer、layout 与测试。AI agent：domain/ai、app/ai、data/network/ai、settings 的必要模型能力/提示词表单及测试。提醒 agent（待首个槽位空闲）：core/model/Event、domain/event、data/local、ui/event、新 domain/alert/app/alert/platform 提醒包及独立资源测试。主 agent：AppContainer/Manifest 集成冲突复核、精确策略门禁更新、发布验证与文档。共享 strings 采用新增专用资源文件避免覆盖。

AddEventCommand/Event 新增末尾可默认字段；EventPatch 使用同名 FieldUpdate；AI wire 字段 notification_enabled/alarm_enabled/timer_minutes 可选，非法类型或范围拒绝。Room 迁移必须测试真实 v1 数据升级与二次打开，不允许 destructive fallback。

默认值细化：Event作为持久化/同步领域对象三字段默认false/false/0；只有AddEventCommand和新建表单按reminder默认通知true，避免旧构造路径或远端快照隐式开提醒。

## 测试矩阵（先用例后生产）
| 范围 | 正常 | 边界 | 非法/异常 | 回归 |
|---|---|---|---|---|
| 日历 | 相邻不同色块/点击编辑 | 短事件、重叠、窄屏、大字 | 缺失/空态 | 月周日、背景、明暗切换、跨日 |
| Widget | 完整起止时间、设置刷新 | 最小尺寸、最大字号、透明度两端 | 坏配置回退、无owner | API26/36、实例隔离、原点击安全 |
| 窗口 | 透明系统栏与正确图标 | 横屏、刘海/手势、安全inset | recreate | Drawer/Back/脏草稿、输入键盘 |
| 提醒 | 保存调度、触发、更新取消 | 过去时间、计时0/1/1440、时区 | 拒绝权限、失效ID、重复广播 | 迁移/事务/墓碑/remote保留/Boot |
| AI执行 | add/update/delete真实回读 | 空操作、同值更新、部分成功 | 无配置、超时、非200、坏JSON、危险ID | 对话绑定、取消、预算、Widget刷新 |
| AI设置 | 获取/选模型填充参数、强度修改 | 未知模型、上限约束 | 元数据缺失/畸形、网络失败 | 手工值、保存重开、请求payload |

## 实施步骤与控制提示
- [x] 核对契约/基线/截图，启动三个独立只读分析 agent。
- [x] 各 agent 补充具体设计与失败用例，主 agent 串行 Gradle 取得 RED。
- [x] 分范围实现，聚焦 GREEN 后独立 diff 复核；agent 不自行并行 Gradle、不提交。
- [x] 空闲 agent 接提醒模块；AI 接入相同字段，不跨写其他 agent 归属文件。
- [x] 集成静态与完整 verify-all、Room迁移、签名APK/AAB审计、API26/36隔离设备冒烟。
- [x] 更新根/Android AGENTS 与 progress，敏感/索引审查并纳入本地 Git 提交（回执见Git日志）。

控制要求：子 agent 先读本任务与相关源码契约，测试先行，返回文件/测试命令/结果/风险。主 agent 负责调度、集成修复和最终验证。失败先保留证据定位，禁止盲目重试或弱化断言；当前用户授权必要问题修复，不沿用历史任务冻结阻断本任务。

## 风险、回滚与完成定义
系统通知和精确提醒受用户授权/系统设置/厂商后台管理影响，必须呈现可诊断状态；不得称模拟器结果等于厂商真机。通过 Git revert 回滚源码；升级后的 Room 不用旧包覆盖降级，需保留 v2兼容迁移修复路径，不清数据。完成需所有目标实现、风险匹配自动化和最终签名设备验证、文档同步、本地提交；未实测项明确记录。

### 提醒模块实施约束
平台调度使用系统 AlarmManager；普通通知使用单次可空闲调度，重要闹钟使用获准后的 setAlarmClock，计时到期使用独立身份。通知、闹钟、计时器频道独立；闹钟使用系统闹钟声音并有停止入口，不以普通无声通知冒充闹钟。通知权限与精确闹钟能力检查后展示状态/设置入口；拒绝时保存事项但明确尚未启用提醒，重新授权或回前台重新协调。每条调度使用显式 immutable PendingIntent、非导出接收器和唯一event/kind/触发时间身份，触发前回读本机事项核对未删除/时间与开关仍匹配，防旧广播误响。取消旧调度和已显示通知，重复触发不得重复提示。只使用本机Room与系统服务，不加后台网络、常驻/周期worker或第三方依赖。Boot/时间/时区变更有界重建未来提醒；应用关闭资源时先停止提醒观察再关闭Room。表单开关、计时分钟与详情状态中英可读，允许系统时钟计时器快捷入口作为补充但不取代可取消的本机日程调度。

实施分拆：calendar agent负责Event/Room/service/form/详情与MainActivity权限UI；widget agent接平台alert包、app/alert runtime、AppContainer装配、Manifest与边界门禁；AI agent负责parser/context/回执接本机三字段。平台状态接口由两个agent直接对齐，主agent审查集成，避免单agent承担整个提醒链路。普通通知/计时器已获精确权限时使用setExactAndAllowWhileIdle，未获权才回退非精确且提示延迟；重要闹钟未获精确权限不假报启用。跨进程重复投递须有本机交付记录，停止动作不依赖未来时间条件。

### 验证记录
- 边界门禁新增3用例，旧allowed_exact为1RED/2GREEN；只加入T66任务文档精确路径后3GREEN（0.002s），其他PC/数据/伪后缀仍拒绝。
- 首次Gradle调用在重定向日志前被沙箱拒绝（0tasks），以相同隔离工具链获准重试。首批32tasks/1m20s，22测试失败；AI/设置/预算11、系统栏2、Widget5为预期RED；日历4项PixelCopy测试环境超时，不计作产品RED，修正取图后单独复核。
- 第二批AI两项（旧自定义prompt进入请求、同值update错误回执）2RED/44s，日志t66-ai-extra-red.txt。证据XML分目录保留。
- 日历取图改主线程decor绘制并保留实际bounds/不透明前置断言后，颜色4项为有效RED；独立Thinking选择2RED，合计6/6、55s，日志t66-calendar-thinking-red.txt，不抹除先前PixelCopy超时。
- 首版实现分工已落地；首轮较大聚焦回归t66-first-green.txt运行中。编译通过，当前发现旧AI契约标识断言和MainActivity跨action入口回归，待完整证据定位修复，不标GREEN。
- 提醒边界新增7政策用例，旧门禁4RED/3GREEN；精确权限/Receiver/PI工厂例外实现由独立Widget agent处理，禁止扩大导出组件、full-screen权限、前台服务和后台网络。
- 已创建全新clender_api26_t66/emulator-5594与clender_api36_t66/emulator-5584，两者身份与sys.boot_completed=1确认；旧AVD未覆盖。avdmanager对已有镜像devices.xml输出告警但两次exit0且新AVD正常启动。只使用本轮合成数据。
- 首轮聚焦实际703 tests/5 failures，5m8s（32tasks），XML完整归档t66-first-green-results。Widget组与日历可读性组通过。5失败：AI旧首句标识断言1；SystemBars相同值反复写window引起Compose不idle 2；MainActivity非法入口业务断言通过但DataStore目录清理失败2。后者是DataStore scope.cancel未等待子任务终结的候选根因，新增确定性关闭测试验证，不能与历史Room清理失败混淆。保持所有清理断言，不加sleep/重试。
- 用户追加后台/自启动保障：加入通知、精确提醒、标准电池优化列表与应用详情设置入口及厂商自启动说明；不宣称可自动授予OEM权限、不承诺强停后仍提醒。官方省电行为依据：https://developer.android.com/training/monitoring-device-state/doze-standby 。仅在隔离设备验证重启/后台/空闲，不改宿主或用户手机设置。
- 提醒旧树/DataStore诊断批24tests/10预期RED（提醒字段/Room版本/表单8，DataStore关闭所有权2），系统栏2及AiCoordinator12 GREEN，1m25s；XML t66-alert-close-red-results。DataStore确定性测试证明close早于scope finally结束，修复为cancel+join，不改清理断言。
- 模型/Room/form第一步已实现；下一批初次在RoomAlertMigrationTest混合arrayOf类型推断编译失败（22tasks/0tests，1m39s），保留t66-alert-data-green-ai-red.txt，精确补Any?类型后再验，不把编译失败算业务RED。
- 修正类型后170 tests/9 failures，1m47s：AI新增解析/context/Room闭环/并发回执/取消晚返回7项预期RED；迁移harness2项因v1合法无indices表被强制读取而失败，修正为可选空索引数组，不减事件/消息索引断言。其余模型/表单/remote事务/Room v2 schema及DataStore/原MainActivity清理均GREEN，XML归档t66-alert-data-green-ai-red-results。
- AI并发回执必须来自EventService.updateWithResult返回的event/changed；普通update保持返回Event兼容。取消模型请求的迟到响应不得覆盖新endpoint或catalog。两项由独立审查发现并已取得确定性RED。
- 集成审查发现：设备重启后系统AlarmManager任务已清空，不能以本机持久pending存在为理由跳过重新调度。平台需以同一immutable身份幂等重建未来任务，并保留已投递去重记录；新增跨runtime/空系统调度器恢复用例。通知与闹钟同时开启时只响重要闹钟，独立计时器仍保留。
- 整合批t66-integrated-green-marker-red.txt：666 tests/9 failures，32tasks/2m44s，XML归档t66-integrated-marker-red-results。新提醒runtime/platform/迁移/form、AI新七回归均通过。9项：未配置AI被eager提醒启动提前打开Room2；原Widget集成行查询/自动刷新2；context黄金缺新增提醒字段1；独立交叉审查新增零时长reminder标题2项有效RED；详情原编辑/删除按钮被新增权限Lazy区挤到未组合位置2。分别归属原agent精确修复，保持未配置无IO与动作可达原断言。
- 上述修复后t66-focused-static.txt聚焦247 tests全部GREEN（零失败/错误/跳过），33tasks/1m58s随后被detekt 73项阻断，尚未进入ktlint/lint。颜色字面量、UI参数/数字、平台验证复杂度和长行按归属处理，不放宽全局门禁。新增频道创建异常注入回归，避免事项落库后平台初始化异常影响AI回执。
- 独立政策14项GREEN/0.196s（t66-policy-integrated.txt），首次普通沙箱临时夹具创建卡住后中断，以授权隔离目录运行一次完成；不是产品测试失败。发布工具原111合成夹具GREEN/2.029s（t66-release-fixtures.txt），发布工具源码未修改。
- t66-channel-red-static.txt（--continue，76tasks/4m36s）：新频道异常2项实际先被非整分钟测试时间校验拒绝，不计有效RED，修正夹具前提后重验。detekt降至2项（runtime方法数量、模型能力条件）；ktlint报告本轮新增代码格式；debug/release lint各298项，其中288项源于误新增zh语言资源目录引起整套翻译要求。回归到既有默认中文/values-en英文结构，按SDK显式guard权限Intent，保留同步commit返回值失败检查，修复unused旧prompt资源。原报告归档t66-lint-*-first.txt。新增延迟普通通知用例：已到期仍待系统inexact投递的有效pending不能因回前台刷新被撤销，不补发无历史pending的过去事项。
- 格式化准备阶段83个本轮Kotlin文件列入精确清单，382个源码摘要留存；两个init模式与普通离线Gradle随后均因KSP插件元数据不可用失败（0任务），未完成格式化。按同版本严格校验补齐缓存，首次带代理参数调用被PowerShell拆分为错误任务名（解析已恢复但0任务），去掉该参数后又发现原传递依赖元数据缺失，再按原仓库/原版本补齐测试缓存；依赖声明与锁文件零变更。各失败独立日志保留，不作为业务RED。已在本地插件字节码确认internalKtlintGitFilter提供精确文件列表过滤，后续用该机制而不改构建脚本。
- t66-alert-edge-red-cache-complete.txt最终取得有效14tests/3RED（频道创建SecurityException两API、inexact延迟期间刷新取消一项），其余11GREEN，32tasks/1m32s。XML归档t66-alert-edge-valid-red-results；据此只修频道失败隔离与仍有效已排延迟pending保留。
- 插件内置internalKtlintGitFilter限定83文件格式化（t66-scoped-format-filter.txt，6tasks/23s），保留18条不可自动长行后按归属手工换行；382 Kotlin摘要审计53个文件变化、范围外0。格式检查仍由后续无过滤完整verify执行，不以限定检查替代门禁。
- 首次完整verify-all（t66-verify-all.txt）111发布夹具通过、1912 tests/5fail，Gradle5m52s，尚未进入后续静态步骤。提醒全部（包含最后两边界修复）、AI、Room、编辑与大部分UI通过；失败为SystemBars两API约400万次重组不idle，Widget Manifest旧总数3与新增后5不一致两项、小host旧固定行数2与完整行预算0不一致一项。XML归档t66-first-full-results。系统栏改为以view/dark/background为key的effect，原repeated-setContent回归保留，另加真实状态切换；Widget测试更新精确授权receiver和可用空间契约，保持实际动作安全检查。

## 证据
- **设备发现的新阻断与范围修正（2026-09-08）**：API36候选383cb同刻06:10普通notification/important alarm、06:11timer通知准时送达，然而系统audio/log只有USAGE_NOTIFICATION播放，important没有alarm_alert播放。原“频道+INSISTENT”不足以证明重要闹钟已响，不能按成功交付。官方AOSP NotificationAttentionHelper（https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/notification/NotificationAttentionHelper.java ）明确同包音频rate-limit，且持续通知保护并非通用USAGE_ALARM；仅调整group或先后顺序不足以保证重要闹钟链路。当前用户明确要求系统闹钟及必要后台权限，因此本任务先前自主选择的“不新增前台服务”非目标在此被必要修复取代：仅重要闹钟实际响铃期间启动非导出mediaPlayback前台服务，空闲不常驻，不增加网络/全屏/电池豁免，不替换普通通知与计时器语义。此前383cb为失败候选，后续必须重新测试/签名并验证声音链路。

### 重要闹钟声音修复追加矩阵与实施约束

- 最终5f5e API36通过：07:50自然投递，原事项T66Alarmfinaltitle1/category alarm/actions1，独立66001汇总无actions；USAGE_ALARM piid431 started、mutedState:none。实际事项Stop于07:53:20.921，07:53:21服务为空且无运行中闹钟player。通知与精确权限均由真实系统UI恢复拒绝，font1.0/Light恢复、HOME、服务为空；07:57:19身份核验后emu kill返回OK，回执`widget-emulator-5584-5f5e84db72b6/restored-closed.json`。API26于07:49:14同样身份关闭，回执`api26-audio-5f5e84db72b6/closure.json`。未清库或删除AVD，未操作真实手机。最终122暂存工程文件边界/敏感模式检查通过，签名APK与431冻结摘要无漂移，提交前按契约设置本机Git HTTPS代理，不推送。
- 最终5f5e API26快检通过：07:45:24实际uid10065/pid10192 MediaPlayer started、USAGE_ALARM(4)/looping；当前通知id2/tagclender_event_10保留T66AlarmFinal1/category alarm/actions1，独立66001前台汇总category service且无actions。真实展开→STOP ALERT后双通知消失，07:47:45 services(nothing)、media.player无该应用Client。Notification dump的历史archive仍含旧2ecd污染记录，不能混作当前通知。证据`alerts-emulator-5594-5f5e84db72b6`与agent最终review；恢复与身份关闭回执另记。
- 最终签名`t66-final-signed-release.txt` exit0，发布审计全部通过；APK 1,846,237bytes、SHA256 `5f5e84db72b6df8d86aca7020e6ede7e33c50247df0a644fa601f589a0dc57b9`；AAB 4,911,533bytes、SHA256 `1571e8edf3ba9374e8eff5ab602071e5856c503d84a9ab0bf0b30e1399562797`。两台隔离设备更新安装并读回SHA后，仅补自然单alarm的原事项标题、无动作前台汇总、事项Stop与播放器/服务释放验收；此前同刻通知/timer不中断声音的2ecd证据保留且代码链路未改。
- 最终`t66-final-format-check.txt`完整ktlint 22s/7tasks通过；`t66-final-verify-complete.txt`完整exit0：96tasks/4m45s（19执行、77up-to-date），111发布夹具2.191s、foundation88项2.949s、boundary88项2.961s。全部196suites/1941tests（UI76/828）零失败/错误/跳过，四类泄漏记录0。最终XML逐字节归档`t66-notification-final-results-complete`，摘要`t66-notification-verification-summary-complete.json`，431源码/schema冻结零漂移，PC源码diff为空。签名与最后设备原题/停止复核继续。
- API36真实09:00事件区四张补图位于`emulator-5584-eventregion-supplemental2-2ecd48678789`。root查看明暗日图：紫色提醒、金/绿时间段、间隔和右侧重叠纹理明确，绿色事项完整09:00–23:00可读；AI独立查看明暗周图：不同色边界与窄聚合块清晰、无跨栏溢字，窄周栏标题仍省略，不能宣称周视图显示全文。状态栏明暗图标对比正常。其余字段全文由日视图与点击详情验证，不把脚本INTERACTIONS_PASS当自动视觉通过。
- `t66-notification-verify-ready.txt`再次196/1941及lint/detekt全绿，76tasks/6m32s推进到test源集ktlint后才报新测试Notification.Action.Builder一行超长（前批在main源集停止，尚未检查到这里）。仅换行参数，随后先独立运行完整ktlint再执行`t66-final-verify-complete.txt`，保持所有断言。一次自动权限审核超时导致命令未执行，按回执允许重试一次后执行成功；无安全拒绝、无用户数据操作。
- API36 Widget实证（2ecd、相关源码未变）：真实添加与加宽后两条09:00–22:00/23:00完整时间显示；0/50/100背景透明度正确。系统热切font2会先重排Launcher缓存的旧RemoteViews，旧行预算可能出现半行；重新Edit/Save后按新字号重算，小host显示+3而不裁行，加高后两完整事项+1，事项点击进入详情。此为明确限制：改系统字号后需重新保存Widget设置；没有实现全局字号变化监听，不把未实测Refresh路径等同通过。深壁纸上0%背景配浅主题黑字对比自然降低，用户可调高不透明度或改主题；文字本身不随背景透明。
- 通知隔离后`t66-notification-verify-all.txt`：196suites/1941tests（UI76/828）全部通过、四类泄漏0、431源码/schema冻结无漂移，XML归档`t66-notification-final-results`；debug/release lint与detekt通过。74tasks/7m20s仅因新增factory函数签名超过100字符被ktlint拒绝，root只调整签名/链调用换行，再运行未过滤完整`t66-notification-verify-ready.txt`，不弱化格式门禁。
- 通知隔离回归`t66-notification-isolation-red.txt`：API26/36两项ComparisonFailure（原事项标题被覆盖），58s/32tasks；XML保留`t66-notification-isolation-red-results`。原recoverBuilder逻辑只提取函数不改行为取得RED；随后仅以独立Notification.Builder创建前台汇总，显式私密、静音分组、无actions，保留原事项通知与停止入口。root接手最小修复，另一agent继续设备采证，未并行Gradle；新的完整verify-all继续。
- 新候选2ecd设备声音证据：API36 HOME后台07:05自然触发，07:06:00.014 timer送达后，07:06:30仍有本应用MediaPlayer piid415 state:started、USAGE_ALARM、mutedState:none，FGS启动资格ALARM_MANAGER_ALARM_CLOCK。但设备同时发现`Notification.Builder.recoverBuilder`复用了原通知：tagclender_event_8的事项标题被改成汇总“Important alarms are ringing”、category=service/ONGOING。这是事项识别功能缺陷，2ecd暂不交付；追加原事项通知不可变/汇总无动作回归，保留原逻辑RED后改独立Builder。再完整测试/签名并快速复核通知标题与停止，其余未变Widget/日历验收证据可保留注明包身份。
- API26真实Launcher Widget（旧383cb、相关源码新包未变）：通过系统选择器添加，原生2×2→5×2→5×3拖动缩放；0/50/100背景不透明度、系统font2、事项点击均通过，PNG/XML与`t66-widget-api26-summary.json`保留。独立agent和root实际查看0/100图，09:00–22:00/23:00完整无省略，透明度不影响文字。恢复原系统字号、Widget System主题/13sp/100%，HOME后交给另一agent安装新包进行声音和Widget快速复核。只读审查初稿把Kotlin data命名空间误当真实数据路径，精确按仓库data/dist根目录更正后120暂存工程文件边界与敏感模式检查通过，429冻结摘要不变。
- 新签名构建`t66-audio-signed-release.txt` exit0，APK/AAB签名、证书一致性、对齐、Manifest/网络策略、无native与归档审计通过。APK 1,846,277bytes，SHA256 `2ecd48678789780a73f907947daf83d8934edfabe4cb6e8f3809acd5815318ca`；AAB 4,911,375bytes，SHA256 `0b7514c249a9ab2a64195a1a6ee634a4bbc261a892b4ec5daed7cca71d043399`。最终声音与快速界面验收使用此包，旧383cb仅作前述失败证据与未变UI的补充记录。
- `t66-audio-verify-all.txt`最终完整exit0：96tasks/6m34s（43执行、2缓存、51up-to-date），111发布夹具2.453s、foundation88项2.915s、boundary88项2.881s，debug/release lint、detekt、未过滤ktlint及构建门禁全部通过。此结果包含全部1939项测试；继续以相同冻结源码签名并验证真实后台声音。
- 声音修复后完整测试已完成：195suites/1939tests、UI76/828，失败/错误/跳过及四类资源泄漏记录均0；XML逐字节归档`t66-audio-final-results`，摘要`t66-audio-verification-summary.json`。冻结429个源码/schema文件零漂移，PC源码diff为空。完整verify-all剩余静态/发布门禁与最终签名设备验收尚在继续，测试通过本身不等于声音验收完成。
- 服务首轮集成`t66-important-focused-static.txt`：5suites/45tests全部通过，1m31s；静态5个detekt及局部ktlint格式问题按所属文件修复，不扩大抑制。交叉审查发现不可读取用户铃声URI的IOException未被RuntimeException边界接住；新增明确throw IOException回归，`t66-important-io-red-static.txt` 1/1有效RED（1m24s），XML保留`t66-important-io-red-results`；该批ktlint通过，detekt只余会话类方法数量，后续以只读属性替代纯查询方法并接住IOException，完整复验继续。
- 追加有效RED：`t66-important-sound-service-red.txt` 四项缺少服务类失败（API26/36，45s）；`t66-important-platform-red.txt` API26重要通知不应再请求系统频道声音断言1/1失败（36s）。对应XML分别归档`t66-important-service-red-results`、`t66-important-platform-red-results`。`t66-foreground-policy-red.txt` 11项政策用例中精确新增service及FGS权限两项预期失败、其余9通过；该日志末尾PowerShell命令令进程退出码为0，按unittest正文记录2失败，不误报GREEN。
- API36独立对照：唯一新增T66Alarmsolo2于06:25:00.014准时通知，仍被系统标记SILENT，06:25没有RingtonePlayer或AudioService播放事件。说明同包既有自动分组情况下单独alarm也不能依赖INSISTENT。证据`alerts-emulator-5584-383cb2572a76/solo-after-delivery`；保留前两轮未保存时间选择草稿证据，不算成功创建。
- 影响范围：alert平台适配器、独立播放服务/会话控制、runtime交付回执、Manifest及精确边界策略、专用测试；既有Event/Room/WebDAV/AI/UI不改数据契约。普通notification/timer仍使用原频道；只有已校验的精确ALARM广播请求播放服务。
- 声音采用频道用户选择URI与USAGE_ALARM，尊重关闭频道、无声URI、系统音量与DND，不请求绕过DND权限；播放服务可取消、有界初始化与失败回执，不得把启动/准备失败标记成功，不得在超时/取消后迟到发声。
- 测试先行：原实现重要闹钟仅Notification、无独立播放生命周期应RED；正常后台播放/loop/停止，重复广播幂等，多事项同时响铃不误停其他事项，改期/删除撤销，准备失败/超时、空或非法Intent/过期ticket、频道静音/权限撤销、onDestroy释放与无闲置常驻；API26/36双系统；普通通知/timer不能抢占重要声音，服务不可导出、不可任意action唤起。
- 权限最小化：明确FOREGROUND_SERVICE与FOREGROUND_SERVICE_MEDIA_PLAYBACK（前者已有WorkManager合并权限），若MediaPlayer wake mode需要WAKE_LOCK则源码显式声明并保持精确白名单（合并Manifest已有该权限）；服务foregroundServiceType仅mediaPlayback。相关门禁负例必须继续拒绝额外服务/导出/类型/权限。
- 设备验收：实际API36单独alarm对照、同刻通知+重要闹钟及随后timer情况下存在独立USAGE_ALARM播放器，点击Stop后播放器终止；至少一次后台自然触发，不用仅属性/通知可见代替声音播放证据；API26同链路兼容。恢复权限/字号，最终重新签名APK/AAB，保留旧候选失败证据。无人工听音不得声称人耳测试。
- 回滚：源码按提交revert，Room仍v2，不清用户库；若声音链路未满足验收则不宣称最终完成。独立calendar/widget代理分担服务生命周期及平台/门禁整合，root串行Gradle与最终发布；AI代理继续独立Widget设备验收。
- 最终同包API26/36的真实UI合成事项保存/详情、通知与精确/电池/自启动入口已取PNG/XML。脚本失误分批保留：dial使用`23 hours`无障碍描述而非文本；已选Month不可再次点击；API36时间文本含U+202F而非ASCII空格；一轮ADB_TIMEOUT后截图确认Span已保存，再从剩余项续跑；API26恢复System时部分裁剪节点中心误点AI，经真实滚到完整选项后恢复。均修忽略的设备helper选择器/续跑边界，不改产品或掩盖失败；没有清库/伪造广播/调时。
- API26后台实证：06:03:39采未来计划；真实reboot 06:04:00→06:04:16；06:04:48未打开应用即确认同epoch计划恢复；06:05系统栏见Notify与Alarm，06:06:25系统dump确认notification/alarm/timer三个频道均送达，alarm flags0xc且USAGE_ALARM/defaultalarm。真实STOP ALERT后重要通知消失。证据在api26-natural-delivery与alerts-emulator-5594-383cb2572a76；不声称headless模拟器已有人工听音证据。
- API36通过实际系统UI授予POST_NOTIFICATIONS及精确提醒，返回应用即时显示允许；真实UI创建NotifyTimerdelivery1/Alarmdelivery1，06:10触发、计时06:11，报告create-alerts-emulator-5584-delivery2-383cb2572a76。同名delivery1为失败未保存草稿证据，delivery2验证唯一草稿后继续，没有重复新增。系统投递与Widget验收继续。
- 最终测试XML归档`t66-final-results`并逐字节核验；`t66-final-verification-summary.json`记录193/1915、UI76/828、failure/error/skip及CloseGuard/SQLiteConnectionPool/SQLiteDatabase/RoomDatabase四类泄漏全0，420源码/schema摘要零漂移，PC源码diff空。签名构建`t66-signed-release.txt`exit0；APK/AAB的签名、证书一致性、对齐、Manifest/网络策略、无native与归档审计全通过。APK 1,839,717bytes，SHA256 `383cb2572a76d4358fe9807eb61b950d8a3d14ec28728b2d9eb854b2580c9438`；AAB 4,896,183bytes，SHA256 `ef9b69ecaf92190f6b9a403626fa299de00d884f834c758ee6b979e42e205ac6`。设备验收仅使用此同包，签名审计本身不代表设备通过。
- `t66-verify-all-native.txt`按统一Native图形模式运行193suites/1915tests全部通过（UI76/828），两端lint/detekt通过；ktlint仅余AiReminderPolicyTest两个闭括号缩进12→16，76tasks/5m30s。按诊断只改空白后最终`t66-verify-all-release-ready.txt` **完整exit0**：96tasks/2m34s（20执行/76up-to-date），111发布夹具2.074s、foundation84项2.938s、boundary84项2.877s。纯空白未改变编译产物，Gradle复用该1915全绿测试结果。生产源码持续冻结，签名与设备验证接续。
- 第二次完整`t66-verify-all-final.txt`：1915项中系统栏原/状态切换共4项AppNotIdle，其余通过，32tasks/7m24s；XML保留t66-second-full-results。**更正先前归因**：读取本机Compose 1.6.8字节码确认错误attempts是RobolectricIdlingStrategy空闲轮询次数，不是重组/布局次数；read-before-write及keyed effect未证明解决全量失败。三方交叉检索发现SystemBarsThemeTest是唯一遗漏GraphicsMode.NATIVE的直接Compose host测试类；只添加与全部其他UI一致的原生图形注解，保留两类测试全部断言与默认超时，未再改生产。t66-verify-all-native.txt记录该环境修正后完整复验，不能把聚焦GREEN替代全量。
- 最终聚焦与未过滤静态批 `t66-final-focused-static.txt`：聚焦测试与detekt通过；ktlint剩三份测试换行，已按原断言仅换行。两端lint仍报287项MissingTranslation，定位为已删除资源文件后遗留的两个**空**values-zh/values-zh-rCN目录；核验完全为空后删除目录，保留既有默认中文/values-en结构，不新增抑制或baseline。普通沙箱删除被拒后以授权原生PowerShell完成；76tasks/4m45s失败日志保留。
- 三独立agent最终只读复核：AI实际mutation/部分失败/预算/metadata取消隔离与Thinking、Event/Room/remote本机策略/权限UI/系统栏、平台提醒恢复/去重/改删取消/频道失败与延迟投递均未发现新增阻断。源码冻结，候选420项摘要以t66-release-candidate-source-hashes.json为准，继续完整验证与签名设备验收。
- 提醒故障边界：持久claim与notify之间进程崩溃时不自动重放，以免重复响铃；状态报告失败。重启仅恢复未来计划，不补发历史事项；8秒协程截止不能强制中断同步系统Binder或SharedPreferences提交。厂商后台与音量/DND不等同模拟器验证。
独立分析确认：日历全部 primaryContainer 且矩形无间距；Widget 时间固定52dp单行省略；Widget已有0–100%不透明度存储/渲染，本轮只完善完整时间、发现性预览与高度预算（RemoteViews不支持ScrollView，不引入该组件）。Thinking自由输入即时解析失败回退HIGH；模型列表丢弃能力metadata且不能选取；AiCoordinator直接显示模型reply，预算可能挤掉最新用户消息。补充验收：为最新用户消息保留预算，保留上下文本地时间头；未知模型不编造能力；不新增自动重放已执行AI操作。主agent已批准三agent先写旧实现可编译失败用例，串行Gradle验证。

Android 官方行为依据：https://developer.android.com/develop/background-work/services/alarms 与 https://developer.android.com/about/versions/14/changes/schedule-exact-alarms 。本轮调度需检查精确提醒能力，不能假定系统已授权。
