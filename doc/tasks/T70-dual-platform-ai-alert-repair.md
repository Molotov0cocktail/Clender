# T70 双端 AI 与提醒修复

2026-09-14；基线 main/e65bbad，初始工作区干净。状态：实现、完整本地验证及构建完成；用户验收前不Release。

## 目标与非目标

1. 删除 Android 设置中的多余 GLM 专用说明，保留模型兼容逻辑。
2. 复现并修复 Android 闹钟不响；明确模拟器、厂商真机与人工听音证据边界。
3. 起草完整双端系统提示词，要求每轮操作前读取本轮当地日期和最新事项；用户检验确认后才改生产提示词。
4. 使用本轮授权临时凭据，以合成日程验证 Android AI 系统回执、错误、正文显示，修复实际缺口。
5. Windows 对齐操作契约与提醒策略，Android 通知/闹钟映射 Windows 系统通知，不增加独立响铃、服务或新依赖。

不发布远程 Release、不推送、不读取真实对话或配置。临时 API key 仅进程内存使用，不写源码、文件、命令日志、测试报告或提交。用户允许不影响最终功能的实现细节自行决定。

## 设计与分工（控制提示）

主 agent 负责方案、提示词审阅稿、边界与 diff 审查、串行 Android Gradle、真实 Provider、双端最终构建与隔离验收、维护文档和提交。

- android_alarm：alert 调度/接收/播放/runtime 及对应测试；排查自然后台触发、音源失败、停止与计时，先给证据再最小修复。
- android_ai：AI client/coordinator/消息呈现、设置 GLM 说明及对应测试/live harness；提示词未经用户确认不得改。
- windows_align：Python AI/提醒必要模型与持久化/UI接线及 tests；系统通知复用托盘，不开发独立闹铃；提示词未经用户确认不得改。

各 agent 先读相关契约与任务，报告精确文件和矩阵，再写失败测试；主 agent 确认 RED 后实施。禁止覆盖其他 agent 文件；共享文档仅主 agent 写。Android Gradle 串行，子 agent 不自行启动。构建/签名/运行数据均不提交。

## 影响文件与接口/数据边界

预期 Android：app/src/main 内 alert、app/alert、app/ai、domain/ai、data/network/ai、ui/ai、ui/settings/AiModelSettings.kt、双语资源及对应 test/liveTest。Windows：ai_service.py、constants.py、必要 event/model/database 与系统通知 UI 接线及对应 tests。确切清单在子 agent 调查后补充。

WebDAV v1 不变，Android Room v3 优先不改；如 Windows 保存新增本机提醒策略，须旧库默认安全、事务迁移、同 UUID 远端合并保留本机值。只在合成临时库验证，不就地迁移真实用户库。回滚保留新增兼容字段，禁止清空真实库或降级 Android v3。

## 测试矩阵（实现前）

| 范围 | 正常 | 边界 | 非法/异常 | 回归 |
|---|---|---|---|---|
| GLM 说明 | 中英文设置无多余文案 | 模型切换、明暗/大字 | 未知模型 | Thinking payload 行为保留 |
| Android 闹钟 | 自然到时播放、通知停止 | 后台、同刻、Stop先于timer、多活动项 | 拒权限/静音/音源异常/启动失败 | 修改删除取消、收据、防重、服务资源释放 |
| 日期与上下文 | 当地日期/星期/最新事项每轮读取 | 午夜/月年边界、跨时区、上下文裁剪 | 缺失/截断事项、假ID、示例日期 | 不使用旧历史代替当前快照，预算保留 |
| AI 消息 | 正文、纯操作回执、错误均可查 | 切会话/旋转/重开、大字输入 | 无配置、超时、非200、畸形JSON、危险操作 | 请求绑定、取消、真实changed、Token不重复 |
| Windows 映射 | 通知/闹钟均交付系统提醒 | 同刻去重、计时0/1/1440、重开 | 无托盘/非法字段/持久化失败 | 单实例/静默、旧日程、WebDAV v1 |
| 数据（如涉及） | 合成新旧库/CRUD往返 | 旧字段默认与同UUID合并 | 事务失败回滚 | 不迁移真实库、同步格式不变 |
| 真实 Provider | 生产client→协调→隔离Room，创建修改删除回读 | 相对日期/纯操作/多轮历史 | 非契约输出如实失败、不猜执行 | 合成数据、当前ID/字段/可见回执/usage |
| 发布验证 | Android完整verify-all与签名；Windows全量、导入、完整PyInstaller | API26/36与普通/静默EXE | 门禁失败留证后针对性修复 | dist/data只读摘要前后一致、敏感扫描 |

## 步骤与完成定义

先独立调查并提交提示词候选；用户确认是提示词实施的必要前置，其余工作继续。失败回归→最小修复→聚焦验证→真实API与设备→完整门禁→签名和EXE构建→审查维护→本地提交。真实模型语义错误不能由一次成功证明彻底消除。无法复现或环境阻断必须说明输入、证据和剩余风险，不无限重试。

## 风险与回滚

通知可能受系统权限、频道、DND、厂商后台限制影响；不绕过用户系统设置。模型 JSON 合法不保证日期语义。提示词增长必须验证预算。Windows 系统通知依赖应用运行，不承诺退出后唤醒。代码通过后可新提交 revert，数据库兼容列保留；不删除数据回滚。构建仅替换 dist/Clender.exe，并核对 dist/data 元数据及哈希。

## 记录

- 已读双端契约、T68/T69与相关源码；已启动三个独立子 agent。系统消息具体指代已异步询问，同时排查对话与系统通知。
- 规划阶段生产提示词未修改；后经用户明确批准才应用。
- 用户已确认完整审阅稿，允许按该稿改双端提示词；另明确最终交付先本地检验，用户确认后才Release。
- 用户澄清消息问题是“有思考但没有回复正文”，不要求恢复历史执行回执气泡。确定缺口为合法纯操作批次无reply仍交付执行，而UI没有模型正文；修复采用执行前最多一次既有纠正补非空reply，再失败则零写并明确错误。部分执行失败仍抑制模型虚假成功声明，保留真实应用回执。
- Windows精确新增范围：constants.py、models.py、database.py、event_service.py、event_alerts.py、ai_service.py、ai_client.py、ui/main_window.py、ui/event_dialog.py、ui/event_detail_dialog.py、ui/ai_chat_widget.py、必要ui/ai_settings.py说明；对应tests/test_event_alerts.py、test_ai_service.py、test_ai_client.py。固定契约始终保留，自定义仅补充；v4 endpoint不可误追加v1，GLM参数和JSON格式按Android兼容，截断/空正文不得执行。
- Windows本机三列默认0，新增reminder经服务默认通知；事务幂等迁移与alert_receipts本机收据防同触发重开重复，不进WebDAV。系统通知只能确认调用已提交，不能证明操作系统实际展示。到期一分钟窗口内触发，历史不补发；应用关闭不唤醒。
- Windows迁移备份/回滚方案：本轮只合成库迁移；交付前保留用户数据摘要。用户安装后首次正常启动才迁移，可在退出应用后自行备份运行目录；若回滚代码保留兼容列与收据表，不删除列、不清空库。
- 测试记录：首Android定向37tests/4fail（缺reply两项、GLM说明API26/36两项），Stop先timer新回归通过；报告android/.tmp/t70-android-first-red-results。提示词测试首次0tasks因离线插件丢失未执行，Gradle daemon日志证实首轮自动清理68缓存项；联网仅补取原锁定/SHA验证依赖，不改版本，后续恢复offline。
- Android边界精确白名单测试8项/1RED→8GREEN，只新增本任务明确文件，不增加prefix。首次普通沙箱拒写.tmp为启动前环境错误，提升后原测试成功启动。

## 闹钟截图后的追加修复（实现前）

用户确认Xiaomi13/Android14，应用在后台，到时无任何表现。所提供截图显示通知/频道开启、频道使用自定义音乐；精确提醒界面开关右侧蓝色但灰禁用，不能仅截图等同canScheduleExactAlarms实际值。API36旧包自然闹钟可播放，但不代表厂商真机通过。

源码确认一条相关失败路径：独立播放器无法读取自定义音源则失败且事项通知不发布，用户可能毫无可见反馈。本轮修复这个确定的异常处理缺口，不据此宣称已复现手机根因。

- 文件：新增alert/FallbackAlarmAudioPlayer.kt，测试扩展既有ImportantAlarmSessionsTest；ImportantAlarmSessions.kt、PlatformEventAlerts.kt、AlertPlatformContractTest.kt、双语event_alert_platform_strings.xml（实际资源名以diff为准）。
- 设计：仅非null自定义音源prepare的IO/权限/异步准备失败时，释放旧播放器后尝试系统默认闹钟一次。默认也失败不循环；播放/焦点失败不重试，不绕过显式静音/频道/DND；取消/过期回调不得迟到播放。所有音源失败时发布明确声音失败的静默事项通知，仍返回失败并持久FAILED，不能伪报已响铃。
- 矩阵：自定义成功不回退；同步SecurityException/IOException与异步准备失败回退一次；默认失败/自定义等于默认不循环；start焦点失败不回退；Stop/过期/释放/null静音；平台失败可见通知但ledger不报成功；最终签名包正常自然到时与Stop回归。
- 无新增权限、依赖、后台网络、Room版本或WebDAV格式。

## 验证进展

- Android首GREEN23suites/236tests零失败/错误/跳过，ktlint/detekt通过，3m9s。独立复核另发现正式正文恰同回执时被剥离，36tests/3RED后修复。后续功能GREEN但detekt因两个break失败，等价改为单break，保留原失败记录。
- Windows交叉审查补齐finish_reason白名单与整批update字段预验证；最终272tests/14.298s全部通过，完整PyInstaller/普通→静默及静默→普通隔离启动清理通过。首次270项通过为前一候选证据。
- dist/data初始只读摘要：5文件/159343bytes，manifest SHA256 83b9f82cdf98e444cda6c04d6dd8a4e488feb23f0a2039f57b43b399f5e1eb60；不读取内容作测试，仅完整性散列。
- Windows真实验收首轮AssertionError，缺少安全结构诊断不能定位，不宣称根因。加入仅状态/JSON类型/操作数诊断后同三轮输入全部通过：3HTTP200/stop、创建1修改1删除1、明天本地日期/原ID/三策略/非空reply、累计3841tokens，合成库销毁。临时Key使用Read-Host隐藏输入及进程环境，shell参数和文件不含凭据；未保存Provider正文。诊断脚本一次PowerShell引号解析错误发生在执行前，纠正后语法检查通过再调用。
- Windows新增表单明暗×8/20px离屏截图；首次字体未加载导致空字形仅为harness限制，隔离进程显式加载本机字体后重做，标签/控件/按钮可见（无产品字体修改）。


## Policy 历史夹具与当前授权协调（修改前矩阵）

完整 `verify-all` 已取得 Gradle 96 tasks / 10m BUILD SUCCESSFUL；末段 policy 96 tests / 9 failures（`android/.tmp/t70-verify-all.txt`），失败均为 T49/T50/T51/T52/T60/T64/T66 历史夹具继续把本轮明确授权的 `database.py` / `event_service.py` 当作未授权路径。历史任务未改变授权边界，当前检查器使用累计明确授权，不能以历史拒绝样本否认 T70 新授权。

- 范围：仅上述七份 policy 夹具、T67 文件中的 T70 精确路径回归及本记录；`test_ignore_and_boundaries.py` 当前白名单和前缀规则不变，不修改生产。
- 正常：当前 T70 精确 Windows 文件和两份任务文档可接受，历史精确任务文档继续接受。
- 边界：T70 每个精确文件的 `.bak` / 子路径 / 同目录近似文件均拒绝，新增覆盖所有明确白名单项，不能变成整目录或前缀授权。
- 非法：混入 `config.py` / `webdav_sync.py` 等真实未授权 PC 源码、用户数据、EXE、无关任务文档仍拒绝。
- 回归：历史 deny 夹具仅把已获新授权的两个样本替换为真实未授权样本，原断言、历史文档后缀、Windows harness 相邻路径及敏感数据保护保留；运行全部 policy discovery，不用局部通过替代全量。
- 回滚：仅回退本次测试夹具调整；不改授权集合，不触及数据库或运行数据。


## T70 Windows 统一提醒补充（2026-09-14）

用户在 T70 实施中明确：Windows 不保留独立“重要提醒”，只有一种提醒；跨端兼容不应形成重复功能。本补充取代 T70 早期 Windows 双开关设计。

## 范围与实现

- 只改 Windows `ui/event_dialog.py`、`constants.py` 与 `tests/test_event_alerts.py`、`tests/test_ai_service.py`；不改 Android、WebDAV v1、数据库结构或依赖。
- 表单仅一个“提醒”开关；读取现有 notification/alarm 的逻辑或，保存为 notification=开关、alarm=false。详情已按逻辑或显示一个系统提醒，保持该行为。计时仍为开始后到期的系统通知，不新增响铃。
- Windows 提示词追加单一提醒规则：正常操作仅用 notification_enabled，alarm_enabled 仅兼容输入；不向用户介绍独立重要提醒或独立闹钟。
- 两端本机策略本来不属于 WebDAV v1；同 UUID 合并保留本机策略，新导入安全默认全关。Windows 正常新增 reminder 默认提醒，关闭后修改其他字段不得重新开启。无需读真实数据。

## 实现前测试矩阵

| 范围 | 正常 | 边界 | 非法/异常 | 回归 |
|---|---|---|---|---|
| 单一提醒 UI | 新增仅一个提醒开关 | 旧 alarm-only/双开关读为开启，关闭清双字段 | 未输入标题仍拒绝 | 保存、取消、明暗、原计时范围 |
| 提示词 | 平台附加明确单一提醒 | 兼容 alarm 输入但不暴露独立功能 | 固定契约仍不能被自定义覆盖 | 日期快照/JSON/reply规则 |
| 本机事件 | 新增 reminder 默认开 | update不重设默认，旧alarm保存归一 | 非法布尔值不写 | 旧库/收据去重 |
| WebDAV v1 | Windows创建→导出→合成导入 | 同UUID共享标题/时间修改保留本机策略 | 字段无额外导出，不接收新增远端策略格式 | 合成Android策略记录经PC共享字段更新仍保留；新导入默认关 |

## 步骤、验收与回滚

先写单一开关失败测试，验证 RED 后最小修改；运行通知、事件表单、AI契约和WebDAV/数据库聚焦测试及 AST/diff 检查。主 agent 统一全量测试、构建、维护根契约与提交。回滚代码即可，保留兼容字段，不删除数据或重置真实库。

## 风险

已批准的 Android 正文仍描述 Android 闹钟，Windows 末尾平台规则明确覆盖该平台差异。WebDAV v1 不携带本机提醒策略，因此无法从新导入记录推断另一设备是否启用闹钟；沿用关闭默认，不伪称同步提醒设置。

## 验证记录

- 定向 RED：单一开关与Windows提示词两项均失败（旧表单2开关、旧平台提示未限定单一提醒）。
- 修改后指定 Miniconda 执行 `-m unittest tests.test_event_alerts tests.test_ai_service tests.test_event_entry tests.test_sync_database tests.test_webdav_sync -q`：53 tests 全部通过。
- WebDAV验证实际调用Windows serialize_document/parse_document与两个临时库，不以Python模拟结果冒称Android设备验收。Android同UUID保留本机策略沿用其已通过Room迁移/合并测试，主agent负责最终Android门禁。
- 四个变动Python文件AST通过，范围diff-check通过；未运行构建、未操作真实库。

- 结果：从 `android/tests/policy/` 使用指定 Miniconda Python 执行 `-B ../../scripts/verify-foundation.py`，完整 98 tests / 4.933s，零失败，exit 0；证据 `android/.tmp/t70-policy-fixtures-green.txt`。保留原完整wrapper的96项/9失败历史，不能改写成首次完整通过。首次普通沙箱输出重定向被拒（未启动用例），提升后同一全量命令成功；未变更测试断言或扩大授权以规避失败。相关 `git diff --check` 通过。

## 最终构建与验证记录

- Android最终生产源码与签名前490文件快照中的Android子集完全一致。完整verify-all首轮Gradle96tasks/10m通过，208suites/2108tests（UI79/875）零失败/错误/跳过，四类资源泄漏为零；随后历史policy9项失败如上保留。修复仅测试夹具后完整入口再次exit0：96tasks/47s（7executed/89up-to-date），111发布夹具、foundation98、boundary98全部通过。
- build-release.ps1完整assembleRelease/bundleRelease与签名/产物审计exit0；本地签名APK 1,867,533bytes，SHA256 c07f2d151ed365512bd4a1817b30235de500b56df85ffb5c83c90bfa5f90e493；AAB 4,959,856bytes，SHA256 9536f014397dac4c76af275847d40c6de461d1b7935cc15a1510cb6e3362ced3。包内版本沿用既有1.0.0(1)，没有发布新Release。
- Android真实Provider最终7轮/11HTTP200（4次有界纠正），创建7/修改7/删除7，真实当地明天日期、ID与策略、非空模型正文持久回读通过，累计19752tokens，合成Room最终清空，凭据每轮擦除。当前模型思考仍可能英文，提示词语言要求不是强制保证。实际Provider测试通过不保证所有模型语义正确。
- 用户最后要求Windows只显示一种“提醒”后重新完整验收：274tests/12.846s全部通过；完整PyInstaller、普通→静默/静默→普通隔离EXE两场景启动/存活/清理通过。最终EXE 45,582,811bytes，SHA256 0c6fb462b5aa3127041c93bee27b0e6b2bb839dfe6b25aea6a3f2658dee52c41。之前272项、旧EXE摘要只代表前一候选。
- 最终Windows明暗×8/20px四图在明确QT_QPA_PLATFORM=offscreen下检查，单一提醒控件/说明/确认取消均可见；类型既有emoji在offscreen字体中显示方框，不影响中文标签与控件，未修改既有图标/字体实现。先前一次图形平台截图已关闭临时窗口，最终证据以offscreen四图为准。
- dist/data前后均5文件/159343bytes，完整路径/大小/mtime/hash清单SHA256 83b9f82cdf98e444cda6c04d6dd8a4e488feb23f0a2039f57b43b399f5e1eb60不变。Android快照零变更；Windows快照仅用户补充授权的constants.py与ui/event_dialog.py改变，最终完整测试/构建已覆盖。
- 证据日志位于忽略的android/.tmp：t70-verify-all-final.txt、t70-build-release.txt、t70-windows-final-3-tests/build/smoke.txt、t70-android-live-results、t70-unified-*.png。它们不进入Git或交付附件。

- 最终独立只读审查：Windows事务迁移、WebDAV同UUID保留与新导入默认、start/timer去重、单一提醒关闭均未发现新增严重问题；Android音源回退的generation/停止/焦点/静音与失败通知行为已独立复核。
- API26 emulator-5594/clender_api26_t67最终同签名包安装/已安装字节摘要/冷启、设置/AI/三视口模型设置与提醒策略表单通过；未修改设置或创建事项，空草稿返回后force-stop并按身份关闭，7组XML/PNG与report.json在t70-api26-final。没有API26自然响铃、音源回退真机或人工听音声明。

- API36最终c07f2d同包在后台自然到时：2026-09-14 07:25UTC，MediaPlayer USAGE_ALARM/state:started/mutedState:none，ImportantAlarmService活动；07:25:28点真实Stop后播放器与服务均释放。主agent已检查natural-start.png/mixed-stopped.png。证据t68-device/emulator-5584-t70final-c07f2d151ed3；旧包提前Stop再timer的独立自然顺序也已通过，新包该顺序由自动回归覆盖。没有修改系统时间、伪发到期广播或人工听音声明。
- 保留限制：Xiaomi13/Android14后台到时无表现的根因尚未真机证实。已修复自定义音源不可读导致无声且无事项通知这一确定代码缺口，但不能把模拟器播放器状态当作用户手机已修好；需用户新包复验。没有新增OEM权限绕过。部分操作失败仍显示实际失败回执并抑制模型虚假成功正文。

- API36最终AI界面明暗×1x/2x四帧输入区/上下文/发送可达，独立agent逐图检查通过；不宣称2x横屏真实IME场景已覆盖。视觉辅助脚本最后恢复主题时SmokeError保留，四帧已完成，不重跑；根据当前界面恢复并实际回读设置后才关闭设备。
- 完整批准提示词实际Kotlin串联解码后与审阅稿首块逐字一致（3795 UTF-8 bytes）。末次临时核对脚本曾错误地用源码逐行查找、继而包含常量后续函数字符串，出现假不一致；限定常量范围解码后通过，无产品修改。

## 本地交付结论

API36合成事项已删除；原通知granted=false/UID ignore、精确闹钟default、System主题、font1.0、旋转、IME与locale均实际回读恢复。视觉helper错误确认是BACK已回Launcher后寻找Drawer，重开应用后恢复成功，原失败保留。确认clender_api36_t67身份后关闭，最后adb devices为空；API26也已按身份关闭。490最终源码摘要零漂移，53工程文件staged检查与通用凭据模式扫描无命中，没有数据、配置、日志、凭据或生成产物进入提交。

本轮全部授权实现与本地验收完成，用户手机复验属于交付后的人工验收限制。APK/EXE位于既有构建输出路径；完整哈希见上文。已按AGENTS提交前设置既有全局https.proxy，创建本地工程提交（具体提交哈希以Git日志/交付答复为准）。没有推送、打发布标签或创建Release；用户确认无问题后才另行Release。
