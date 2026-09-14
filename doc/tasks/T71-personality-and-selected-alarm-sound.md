# T71 人格设置合并与自选闹铃音源

2026-09-14；基线main/b6a2d7f，初始工作区干净。用户已确认T70闹钟恢复正常，本轮报告选其他铃声仍播放系统默认；继续本地交付，未经用户最终确认不Release。

## 目标、范围与设计

1. Windows移除自定义风格补充框与恢复按钮，统一在人格设定提示和编辑；内置日程契约不变。保留已有自定义文本与人格，无自动改写真实配置。设计兼容加载与保存合并，取消不写，避免隐藏旧配置仍影响请求或反复重复合并。
2. Android诊断频道自选铃声为何降级默认：审查实际channel sound、MediaPlayer读取权限/音源路径与现有有界回退。优先尊重系统选定音源，无法读取时仍保留T70可响/可见失败保护，不添加广泛存储权限或绕过静音。不能复现OEM行为须如实说明；技术取舍由agent基于证据作最小实现。
3. 独立agent负责Windows与Android；主agent读契约、集成审查、全门禁、签名与EXE构建、本地提交。第三agent维护精确policy白名单与独立审查。遵循既有vibe-coding-workflow。

范围：ui/ai_settings.py、ai_service.py及相关tests；Android alert播放器/必要设置音源适配和对应tests；policy精确任务路径、双端AGENTS、本任务/progress。Android具体文件及实现需调查后先补设计/矩阵再改生产。无日程DB/WebDAV格式变更，无新网络或依赖；非目标为重写已批准内置契约、强制提高音量、厂商后台绕过、公开发布。

## 实施前测试矩阵

- Windows正常：只有人格一个编辑区，内置说明可见，旧自定义/人格合并一次；边界：两者空/相同/单独存在、多次保存；异常：取消/写失败不丢配置；回归：固定契约始终注入、无配置/超时/非200/畸形响应既有测试，明暗8/20px与offscreen、信号。
- Android正常：选定可读铃声实际传入播放，不改为默认；边界：默认/静音null/不存在/权限拒绝/异步失败；异常：仅明确不可播放才有界回退，Stop/焦点/取消/超时不得迟到或重响；回归：频道设置不覆盖、自定义音源权限语义、自然后台闹钟/Stop和失败通知。
- 所有Bug先RED再修复；真实设备可控合成音源验收需核对所选URI与实际来源，不能仅播放器started当作自选音源已通过。厂商真机由用户验收。
- policy只新增本次精确文件路径，邻近路径/数据/产物拒绝测试保留。双端完整测试、Android完整verify-all与签名审计、Windows完整PyInstaller及隔离EXE冒烟；dist/data只读摘要前后相同，凭据不读取、不使用真实库测试。

## 回滚与完成定义

代码用新提交回退；用户数据/配置不清空，旧字段兼容不丢内容。用户打开设置不写配置，仅保存才执行明确编辑。全部必要测试/构建、独立复核、文档维护、本地提交后交付；小米音源实际效果未验时明确限制，不宣称根因已证实。

## 记录

规划、独立实现与聚焦验证已完成；最终完整验证和设备记录见下方。


## Windows 单一人格编辑设计（实现前）

保留旧配置字段只作兼容读取：把旧 `system_prompt` 与 `ai_personality` 的非空去首尾空白文本按旧风格在前、人格在后合并，完全相同文本只保留一份。设置界面仅显示这个合并后的人格草稿；打开和取消均不写配置。运行请求使用同一合并函数，并只注入一次人格补充，固定内置契约不改。

用户保存时将可见草稿写入 `ai_personality`，同时把 `system_prompt` 置空；用户清空人格即清除全部自定义内容，不会留下隐藏旧风格。完整配置原子保存失败时保留原磁盘配置与当前草稿，不发保存信号或关闭；重新打开/重复保存不会再次拼接。仅去除重复的完全相同文本，不按子串去重，避免丢失有意义内容。

精确文件为 `ai_service.py`、`ui/ai_settings.py`、`tests/test_ai_service.py`、`tests/test_ai_settings.py`；若既有Typography断言绑定被删除控件，仅改为验证保留的人格标题。测试覆盖旧字段单独/同时/相同/空/异常类型，界面单编辑区、取消、保存失败、保存信号、重复保存、主动清空、请求契约与人格一次注入，保留既有API获取与AI回归。

- Windows dist/data本轮基线：5文件/167599bytes，完整路径/大小/mtime/hash清单SHA256 d07eeb948ed89dad7f7735f4caecf14790b9761b2ba9f9525fa5d0112aad6791。用户T70后实际使用使数据增长，本轮以此新基线保护。


### T71 精确 policy 计划（实施前）

仅新增 `doc/tasks/T71-personality-and-selected-alarm-sound.md` 与既有 Windows 设置测试 `tests/test_ai_settings.py` 两个 exact 例外，复用已有 AI service/settings 路径。`test_t71_task_boundary.py` 先验证两项当前拒绝的正常路径，再保留任务后缀/子路径/近似名、测试文件后缀/子路径/近似名、无关 PC 配置/同步、真实数据及 EXE 拒绝矩阵。禁止增加目录前缀；聚焦 RED→GREEN，随后全 policy 回归。生产 alert 源码不归该子任务修改。


### Windows 人格合并验证记录

- 先RED：指定Miniconda执行 `-m unittest tests.test_ai_settings tests.test_ai_service -q`，16 tests / 3 failures / 6 subtest errors。复现双编辑区、旧字段不清、保存失败修改加载字典与缺少合并入口。
- 实现：移除旧风格框/标题/恢复按钮，新增固定契约与统一人格说明；统一兼容合并函数用于界面和请求；保存拷贝完整配置后写人格并清旧字段，失败不发信号/不关闭/保留草稿，未改内置契约。
- GREEN：`-m unittest tests.test_ai_settings tests.test_ai_service tests.test_typography tests.test_ai_client tests.test_ai_chat_widget -q`，最终42 tests全部通过（此前41通过，后加一次注入/清空回归）。覆盖明暗8/20px样式应用；不宣称完整屏幕截图验收。
- 四个Python文件AST通过、范围diff-check通过。只合成/Mock配置，不读真实配置；无EXE测试/构建，交主agent统一验证。


- T71 policy验证：从 `android/tests/policy/` 使用指定 Miniconda Python，`-B -m unittest test_t71_task_boundary -v` 首轮4项/2RED（两精确路径未允许）、增加仅两个exact例外后4/4GREEN。随后 `-B ../../scripts/verify-foundation.py` 完整102项/5.102s PASS（日志 `android/.tmp/t71-policy-green.txt`）。首次普通沙箱全量在ProductionTextFilesTests临时fixture阶段停滞，无新失败输出，已停止本次自有进程；提升后同源码全量通过，不将停滞归为已证实的产品问题。

### Android 音源调查计划（实现前）

用户补充为系统频道设置选取本地音乐。AOSP Ringtone 可以在应用 MediaPlayer 无读取权限时委托 SystemUI，但 Ringtone 自带隐式默认回退，isPlaying 或 SystemUI mUri 仅能证明播放/请求，不能证明实际选定源。API28才有公开循环API；本轮不使用隐藏API或授予全音乐读取权限。

先以既有隔离API36和独立ignored probe应用（无存储权限）作15分钟有界调查：合成不同波形/时长WAV，分别验证直接MediaPlayer与系统Ringtone，记录准确URI、读取异常和实际媒体来源；覆盖缺失URI作为默认回退对照。probe无生产入口、无真实数据/凭据，结束卸载并删除其合成音源。若系统委托的实际来源或启动生命周期无法可靠证明，则不盲换播放器，改为提案应用内ACTION_OPEN_DOCUMENT有界私有导入，保留现有播放器及Stop/焦点/超时/默认回退；具体文件与矩阵先另列，再RED及实现。

官方依据：https://developer.android.com/reference/android/media/RingtoneManager#getRingtone(android.content.Context,%20android.net.Uri)；https://developer.android.com/sdk/api_diff/28/changes/android.media.Ringtone；https://android.googlesource.com/platform/frameworks/base/%2B/android16-qpr2-release/packages/SystemUI/src/com/android/systemui/media/RingtonePlayer.java 。厂商本地音乐URI行为仍须真机确认。


### Windows 默认窗口20px裁切补修

主agent四帧截图发现默认show时提示尾句裁切、人格编辑区仅单行高。新增实际QSS/default-show测量测试：旧hint高度39px、小于heightForWidth的66px，定向1FAIL；此前人为resize(640,760)没有复现，因此回归固定走真实默认show。修复仅缩短提示为清晰两行，移除长期显示的迁移实现说明；人格编辑区最低100px。测试测量hint全文高度、两段人格文档完整落在viewport、窗口<=800px及保存/取消可达。修后settings/service/typography 29tests通过，2文件AST与diff-check通过；未执行EXE或构建。

### Windows最终集成验收

主agent首候选完整279tests/11.824s、PyInstaller与两场景EXE通过；四帧视觉发现默认show的20px说明被裁切、人格单行过矮，交独立agent补真实布局RED后修复。最终主agent再次完整280tests/12.231s、完整PyInstaller、普通→静默/静默→普通两场景启动/存活/清理全部通过。最终EXE45,584,101bytes，SHA256 6cfaeebfafc61e2fdf0a1cb7224169f3bf08db0bb4392a41a1e60811b491bd7f；dist/data前后5文件/167599bytes与本轮初始清单摘要相同。最终明暗8/20px四帧已全部实看，完整短说明、两段人格文本及保存/取消可见。证据t71-windows-final-tests/build/smoke.txt及t71-personality-final-*.png；只有既有emoji在offscreen字体中为方框，无新增图标功能修改。Windows生产冻结，后续仅Android源码修改。

### Android 私有音源设计与分工（调查后、生产实现前）

有界probe于08:03–08:12 UTC完成：API36隔离AVD中无存储权限探针直接读取合成`content://media/external/audio/media/19`报IOException；系统Ringtone委托SystemUI能实际播放所选WAV（audio/raw、22050Hz单声道），不存在URI却也isPlaying=true并实际播放默认Vorbis/44100Hz。首次合法播放500ms检查为false，之后实际started，证明直接替换不能保持现有同步启动回执。证据位于ignored `android/.tmp/t71-sound-probe/` 的direct/ringtone/missing日志和settled媒体dump；非人工听音、不推断小米同源根因。探针已卸载、唯一合成MediaStore条目已删除回读为空，AVD5584按身份保留给最终同包验收；未改真实设备或Clender设置。

采用应用内单一“闹钟铃声”本地导入入口，不更换MediaPlayer/Ringtone引擎。优先级为频道禁止/显式静音最高，其次本机已导入音源，再次系统频道所选音源；准备失败沿用T70系统默认一次回退。导入/移除只影响下一次闹钟，当前播放持有既有文件描述符，不切曲、不自动重播。

- 存储agent：新增`data/settings/AlarmSoundStore.kt`及其测试。接口`AlarmSoundStore(directory: File, validate: (File)->Unit)`（默认平台验证器）、`selectedFile(): File?`、`importSound(open: ()->InputStream?): File`、`remove(): Unit`。固定私有目录`filesDir/alarm-sound`，固定文件`selected.audio`；流式有界32MiB临时文件、真实音频可准备验证但不启动、sync后原子替换。导入空/超限/读取拒绝/非法内容/验证失败均保留旧音源并清临时文件，不信扩展名。无原URI持久化、广泛媒体权限或AppSettings/Room字段。
- UI agent：新增`ui/settings/AlarmSoundSettingsSection.kt`、`AlarmSoundController.kt`、`AlarmSoundViewModel.kt`及对应测试，SettingsScreen嵌入单一section、中英资源。复用背景的ViewModel生命周期和IO调度，ACTION_OPEN_DOCUMENT仅audio/*；取消不调用导入，忙时禁止二次操作，失败可见并保留旧选择；恢复频道铃声仅删本应用私有选曲。文案明确频道静音仍优先、下一次闹钟生效。
- alert agent：仅`PlatformEventAlerts.kt`及`AlertPlatformContractTest.kt`。`alarmSound()`先判断频道importance与非null sound，再读取私有有效文件URI；无有效私有文件保持原频道。现有请求3秒到期原子语义、独立MediaPlayer、焦点/停止/迟到回调、一次回退、失败通知不变。

追加矩阵：存储正常/恰好32MiB/超限/空/截断/伪扩展/不可播放音频/安全异常/替换写失败/清理及重建；UI OpenDocument类型/取消/选择/恢复/失败/忙/生命周期/明暗大字；alert私有优先、频道null静音优先、低importance/缺失/无私有/超限回退、替换后新实例读取、原频道保留及T70全部播放生命周期。最终同包以合成不同采样率音源经系统文件选择器导入、后台自然闹钟核对实际codec/sample特征及Stop，再恢复频道音源并清理，不能仅started报告自选成功。

并发与取消补充：导入使用唯一pending文件；UI工作协程取消检查必须传入同步存储过程，在流读取块间、验证前后和原子提交前检查。不同实例导入/移除的提交需串行保护，导航离开取消旧操作，不能迟到覆盖新选择。添加取消/双实例/恢复竞争与旧文件保留回归。不会改为prepare期间试播或主线程轮询。

存储并发细化（实现前）：仅开始时分配generation、提交与移除共享每目录锁，provider读取/平台prepare均在锁外，旧慢流不能阻塞新导入或恢复。每次新导入/移除递增generation，旧请求即使未传取消回调也不能迟到覆盖；提交锁内同时检查generation与`checkActive`。`selectedFile`不取锁、不解码。真实验证先由MediaExtractor确认audio轨，再由MediaPlayer.prepare确认平台可准备，finally释放两种资源，不启动播放；不宣称prepare能提前发现音频后续每一帧损坏。

### Android 私有音源存储验证记录

- 新API先建立可编译空stub和严格行为测试。首轮合批在ShadowMediaPlayer创建监听器测试签名上编译失败（应为两个参数），未计行为RED；修正测试后重跑，store 16实例与UI 4实例全部按预期失败，XML保全于ignored目录，日志`t71-store-ui-red2.txt`。
- 存储实现加入流式32MiB上限、非空及无读取进展拒绝、唯一pending、fsync和原子替换；全部失败清理本次pending且不改旧选择。每目录generation/短锁使旧慢读取/prepare不挡新选择或恢复。补两实例并发回归，要求新导入与移除在旧validator解除前完成，并阻止旧请求迟到。
- 默认validator不信扩展名：确认音轨、平台prepare及finally release，绝不start。另新增已prepare但duration=0的空WAV壳边界用例，先RED再增加正时长要求；合成Shadow测试不能替代最终设备所选音源核验。
- 存储最终聚焦20/20实例通过（API26/36）；合批85实例零失败/错误，detekt通过。ktlint发现100列与换行格式问题后仅调整两存储文件格式，业务行为不变；最终静态回执由串行集成agent记录。


### Android 音源 UI 细化矩阵（实现前）

自包含 `AlarmSoundViewModel` 保存 Controller，入口嵌于应用设置且不进入 AppSettings 数据模型。每次进入后台读取选择；页面离开取消唯一 Job 并递增代次。OpenDocument(audio/*)启动时取得唯一ticket并禁用操作，返回空URI仅结束忙态且保留旧文件；旧ticket/页面离开后的回调不得开启导入。文件读取、导入与恢复在IO，传入本次coroutineContext.ensureActive给Store，使取消在流读取/提交前检查；过期finally不能覆盖新代次状态。恢复仅影响私有副本，下次闹钟生效，频道静音仍优先。

UI单测覆盖：进入读取、选择/取消、旧ticket拒绝、忙时二次请求拒绝、IO失败保留旧、离开取消后旧Job不提交且新请求正常、恢复成功/失败。Compose覆盖OpenDocument intent audio/*、明暗8/20字号可滚到48dp入口/恢复、加载禁用与错误可见、移出composition取消。新增两组测试 `ui/settings/AlarmSoundControllerTest.kt` 和 `AlarmSoundSettingsTest.kt`；新中英 `alarm_sound_strings.xml`。

- alert RED：`:app:testDebugUnitTest --tests com.molotov.clender.alert.AlertPlatformContractTest --console=plain`，21 tests / 3 failures，旧实现私有音源选择与show传入仍为频道URI；静音、低importance及缺失/超限回归通过。日志`.tmp/t71-alert-red.txt`与独立XML已保留；实现尚未开始。

- store/UI RED首轮因测试ShadowMediaPlayer监听器误用单参数而编译失败，已修正为双参数，保留`t71-store-ui-red.txt`；不将编译失败计为行为RED。第二轮同命令两类20 tests / 20 failures（store16、UI4），验证空存储stub不具备导入/验证/取消能力、现有设置无音源入口；日志`t71-store-ui-red2.txt`及两类XML已独立保留。随后允许三agent各自实现。

- 首次实现聚焦合批78 tests / 2 failures：仅新增零时长音频容器用例在API26/36按预期RED，其余76通过，含import/controller/UI/alert选择与既有sessions/service回归。空容器虽然有音轨且prepare成功仍应拒绝，后续追加duration>0验证；证据`t71-sound-green1-duration-red.txt`和独立store XML保留。

- picker启动异常RED：AlarmSoundControllerTest 8 tests / 2 failures（API26/36 ActivityNotFoundException未捕获），其余取消/忙/恢复回归通过；证据`t71-picker-red.txt`与独立XML保留。随后补文件选择器缺失/安全拒绝的失败状态复位，保留旧选曲并可重试。

- 最终聚焦第三轮业务85 tests全通过（平台21、sessions26、service4、store20、controller8、UI6），零失败/错误。该合批退出1因ktlint新文件换行和detekt SettingsScreen函数计数，已保留`t71-sound-green3.txt`与六类独立XML；此前`t71-sound-green2.txt`因UI方法复杂度/长度提前终止，不能计业务通过。后续仅格式与等价小Composable搬移，不修改门禁阈值，重新跑静态后交主agent完整verify-all。

- 静态收口：纯格式和等价Composable搬移后`:app:ktlintCheck :app:detekt --continue --console=plain` exit0，15秒/8tasks，通过。日志`.tmp/t71-sound-static-final.txt`。未修改规则阈值；行为85项前轮已通过，此后只有排版及等价控件函数搬移，交主agent完整verify-all再次覆盖最终源码。

### Android 音源 UI 实现与聚焦结果

已交付独立 AlarmSoundController/ViewModel/SettingsSection 及中英资源，不新增 AppSettings 或持久化 URI。OpenDocument 只请求 audio/*；ticket 与协程代次保护取消、页面离开及旧回调，IO 导入/移除传入 ensureActive。ActivityNotFoundException 与 SecurityException 启动失败显示错误、退出忙态且保留旧音源，不混同用户取消。文案说明本机音乐在下次开始响铃时优先，正在响铃不切换，频道静音仍优先。

UI 旧实现 4 实例无入口 RED 已保全；picker 启动异常 8 实例中 API26/36 两失败 RED 后补异常复位。最终业务合批 85/85（Controller 8、Compose 6）通过，含真实 OpenDocument intent 与 MIME、选择取消、忙时禁用、错误可见、页面移出取消、旧 ticket 拒绝、IO 失败保留与恢复。随后静态检查发现复杂度、函数数量和排版门禁，仅抽取失败状态与展示函数并按 100 字符排版；最终 ktlint/detekt 通过。完整回归与最终签名包设备检查由主 agent 串行负责，此处不宣称真实设备导入已通过。


### 最终静态与独立复核

Android最终 `:app:ktlintCheck :app:detekt --continue` 15s/8tasks全通过（t71-sound-static-final.txt）。此后生产及测试冻结，499项双端源码/schema摘要已记录；主agent执行完整verify-all。独立只读复核未发现阻断：取消/启动失败/离页迟到保护、IO读取、短锁generation防覆盖、频道静音优先、API26公开接口和资源finally释放均确认；没有改动既有Stop/焦点/一次回退链路。


### Android 最终完整验证

主agent以固定Miniconda执行 `scripts/verify-all.ps1` exit0：211suites/2151tests，UI81suites/889tests，零失败/错误/跳过及CloseGuard、SQLiteConnectionPool、SQLiteDatabase leaked、RoomDatabase leaked四类泄漏标记。111发布夹具通过；Gradle96tasks/8m7s全部通过；foundation102/3.640s和boundary102/3.427s全部通过。日志t71-verify-all.txt。499项源码/schema与Windows36项生产摘要均无漂移；无新增真实Provider调用，T70在线验收保留为历史证据。


### 最终产物与数据保护

Android `scripts/build-release.ps1` exit0，签名配置及最终APK/AAB审计均通过。APK 1,874,157bytes，SHA256 f08f790932ffe061c85e4e1a23e41f0b5f345ba353f63188ed5b4ec15582e77f；AAB 4,977,277bytes，SHA256 8a2c9f31de9ea41d58b5005154a78e9467422954d8ce6c8dbcd7030e140ff532。Windows EXE45,584,101bytes，SHA256 6cfaeebfafc61e2fdf0a1cb7224169f3bf08db0bb4392a41a1e60811b491bd7f。本轮dist/data前后5文件/167599bytes，含mtime与内容的完整清单SHA256 d07eeb948ed89dad7f7735f4caecf14790b9761b2ba9f9525fa5d0112aad6791不变；没有恢复/改写用户数据。产物均仅本地，不发布。


### API26 最终同包验收

独立agent在emulator-5594/clender_api26_t67安装最终f08f7909 APK，回读base.apk SHA一致；冷启、设置→应用→闹钟铃声、真实OpenDocument取消保持系统默认、Downloads唯一合成WAV导入显示本机音乐、恢复系统声音全部PASS。主agent实看 imported-sound.png，文字/两按钮完整可见。唯一/sdcard/Download/t71-api26-synthetic.wav已删除且回读不存在；未修改权限/主题/字号/事件，force-stop后核AVD身份并关闭。证据android/.tmp/t71-api26-final/report.json及截图/XML。API26不宣称自然播放或人工听音。


### API36 最终同包音源证据

最终f08f7909 APK安装与回读哈希一致；真实DocumentsUI取消保持系统音源、Audio→未知艺术家→Music选t71-tone.wav导入显示本机音乐通过，主agent实看imported.png。随后仅删除按SHA核对的公共合成文件及MediaStore20，文件和条目均回读不存在。既有事项helper遇小时标题/表盘同名选择歧义，原FAIL保留；按实际UI继续同一草稿选09:15分钟并保存，无重复事项、无生产修改。

09:15 UTC后台自然到时；09:15:08媒体dump显示本app uid10150/pid2802，audio/raw、c2.android.raw.decoder、22050Hz单声道PCM16、looping=true；audio piid207 state:started、USAGE_ALARM、mutedState:none。与导入合成WAV一致且不同于默认Vorbis/44100Hz；结合原源已删除证明使用私有副本。不能据此宣称Xiaomi系统频道读取根因或人工听音验证。Stop/清理回执如下。

### API36 最终同包音源与恢复回执

最终签名APK `f08f790932ffe061c85e4e1a23e41f0b5f345ba353f63188ed5b4ec15582e77f` 安装摘要复核通过。真实设置OpenDocument取消保留原频道；从系统音频→未知艺术家→Music选择唯一7秒/22050Hz/单声道PCM16合成WAV，界面显示本地音乐已选。公共源按生成SHA256核对后精确删除（MediaStore20与文件双回读不存在），后续播放来自本机私有副本。

09:15:00 UTC后台自然闹钟（HOME/Launcher状态，无时钟写入或模拟触发）；09:15:08媒体dump为本app UID10150/PID2802、audio/raw/c2.android.raw.decoder、22050Hz单声道PCM16、looping=true，audio piid207 started/USAGE_ALARM/mutedState:none。09:15:35真实通知Stop后媒体Client消失、响铃服务(nothing)，合成事项删除且无未来Clender闹钟。随后通过应用入口恢复系统频道铃声，原始permission/AppOps/font/locale JSON逐字段一致；按AVD身份关闭，最终adb devices为空。未修改系统音量、主题或AI配置。

证据：ignored `android/.tmp/t71-api36-final-report.json`；选择器/导入/恢复截图在`.tmp/t71-sound-device/f08f790932ff/`；自然媒体/音频/Stop/恢复在`.tmp/t68-device/emulator-5584-t71final-f08f790932ff/`；身份关闭见`t71-device-close.txt`与`t71-device-final-devices.txt`。已实看导入/取消/自然通知/恢复截图，不宣称小米真机或人工听音。

辅助脚本历史保留：旧create helper遇09小时标题与9表盘相同content-desc而NODE_AMBIGUOUS，原`report.json`保持FAIL；按实际UI切分钟15完成同一未保存草稿，另存`corrected-report.json`，无重复事项、无生产源码修改。最终媒体/Stop/状态证据自动断言全部通过。

汇总helper首次用Windows默认GBK读取UTF8 XML而解码失败；改为显式UTF8后最终证据断言PASS，未重新操作已关闭设备、未改生产。该工具错误不计产品失败。


### 交付与限制

本轮代码、测试、双端AGENTS与任务进度已维护；完整验证及双端产物完成。Windows只保留人格编辑区，旧自定义文本合并且固定内置契约不变。Android需在设置→应用→闹钟铃声→选择音乐重新选择本地音频（最多32MiB）；原系统频道选曲不会自动迁移私有副本，频道静音仍优先，正在响铃不切曲。Xiaomi13实际选曲效果留给用户检验，不能把API36证据当成厂商真机或人工听音。无新Provider调用，不修改WebDAV/Room/Manifest/权限/依赖。源码摘要无漂移，staged diff-check与敏感模式审查通过后本地提交；提交回执以Git日志为准，不推送、不打标签、不Release。
