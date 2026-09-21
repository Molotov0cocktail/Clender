# T80 Android AI 超时与本地诊断（2026-09-21，本地验证完成，待用户验收）

## 目标与授权
用户要求修复频繁错误，USB尽量只读定位，并建立日志、关于页数据清理及导出。允许独立子agent与非功能细节自主决策。基线main/12a63e8，初始工作区干净。截图只能证实AI超时，不把推测当真机根因。

范围：Android请求健壮性、隐私安全诊断、关于页维护。版本/签名、Room v3、WebDAV v1、固定操作契约不变；不发布。手机仅ADB只读系统/本应用诊断元信息，不安装、不清数据、不启动请求、不读取秘密或真实数据库；测试用合成数据及隔离模拟器。Windows生产不改，根契约规定的Windows回归构建由主agent执行并保护dist/data。

## 设计与数据边界
- 请求：调查非流式180秒总时限、格式纠正共享时限、取消/网络生命周期；依据可复现证据修复，保留单请求、有限纠正、执行前完整验证，不盲目重试产生重复操作。
- 诊断：私有目录有界滚动记录，类型化事件/阶段/结果与时长、HTTP状态等白名单指标；禁止任意消息、URL、异常message、请求响应正文、密钥、日程/会话标识。失败不得破坏业务。应用启动与未捕获异常记录安全摘要且委托原处理器；AI、同步、提醒按必要边界接入。无后台上传、无新增依赖。
- 关于页：导出脱敏诊断文本经系统CreateDocument，IO在后台，取消无副作用，错误可见可重试；清空诊断日志与清理明确的临时缓存均有确认/忙碌/结果。绝不删除日程、对话、设置、背景、铃声、秘密、同步墓碑；缓存限定受控路径，不递归清空整个filesDir或cacheDir。
- 跨层接口由diagnostics模块提供类型化记录及维护服务，UI只调用服务，不直接操作DB。并发写入/导出/清理串行化，目录和文件大小有界。

## 分工与影响文件
1. AI agent：data/network/ai、app/ai必要源文件/测试和AI错误资源；先调查/RED再修复。
2. 日志 agent：新增diagnostics模块及测试，ClenderApplication初始化/异常处理；定义共享API；必要同步/提醒诊断集成。AppContainer由主agent集成。
3. About agent：ui/about、app/about维护适配器与中英资源/测试；与日志agent约定维护API。
4. 主agent：USB只读、文档、串行Gradle测试、AppContainer接线、policy精确边界、交叉审查、构建与设备视觉验证、提交。

## 测试矩阵（实现前）
| 模块 | 正常 | 边界 | 异常 | 回归 |
|---|---|---|---|---|
| AI | 完整回复/操作仅一次 | 慢响应、总时限、纠正剩余时间 | 无配置、超时、断网、非200、畸形JSON、取消、危险操作 | secret清空、usage一次、切会话/后台取消、旧能力参数 |
| 日志 | 启动/阶段/结果落盘与导出 | 字节上限/轮转/并发/清理 | IO失败、损坏文件、异常message注入 | 无正文/密钥/URL/标识、原crash handler、业务不被日志失败影响 |
| 关于页 | 导出与两个清理动作/成功反馈 | 大字号/窄屏/明暗/滚动/重复点击 | 选择器取消、启动/写入失败、取消确认 | 旧metadata/更新链接/导航、用户数据完全保留 |
| 集成 | release签名APK/AAB与离线验证 | API26/36合成环境 | 泄漏/静态/边界检查 | Room/schema/依赖/权限不变、Windows既有门禁 |

## 步骤及完成定义
- [x] 初始契约、git基线与截图检查；任务及矩阵先行。
- [x] USB只读诊断并记录可证实结论/不足（只写汇总，不保存真实原始日志）。
- [x] 三agent有效RED→实现→聚焦GREEN及交叉review。
- [x] 完整Android verify-all、签名审计、隔离设备关于页和导出/清理验收；Windows契约门禁。
- [x] diff/敏感扫描、契约与进度更新、本地提交、交付产物及限制（本地提交回执以Git日志与最终答复为准）。

## 风险与回滚
旧包无应用持久诊断时不能追溯截图发生时的具体网络阶段；不声称消除Provider所有错误。磁盘不足的日志不能保证保存。手机不覆盖安装，用户自行安装验收。回滚为源码revert及旧签名APK（不清真实库）；本任务无schema迁移。

## 验证记录
只读搜索rg通配路径调用错误已识别。progress.md已有混合编码，采用二进制前置新段保留旧字节，不全局重编码。

### 调查与新增预算范围
- USB只读：识别Xiaomi fuxi，首轮系统报告Android14/API34，安装正式1.3.1(3)，非debuggable，应用当时未运行。过滤logcat/本应用UID日志均未发现可用的超时/上下文上限栈；系统历史exit-info仅OTHER KILLS BY SYSTEM/UNKNOWN，不能证明截图超时根因。当前有VALIDATED网络不证明历史网络正常。原始系统日志/网络信息仅进程内筛选，不落盘、不打印正文；未读取DB/秘密、未安装/启动/发请求/清数据。第二次包UID字段为appId而非userId，初次字段解析失败后兼容这两个字段，只读查询完成。
- 用户追加历史“输出超过上下文上限”问题。本轮增加有效输入空间预检、模型元数据自动填充保留固定契约与输入空间、明确CONTEXT_BUDGET/OUTPUT_LIMIT分类；不猜测未知厂商能力，不自动改写用户Token配置。HTTP仅依据明确结构化错误code识别上下文上限；length截断保留usage且拒绝执行。
- 现有非流式聊天read/call180秒且继承基础client默认write10秒；本轮独立chatWrite60秒、read/call600秒。总deadline仍覆盖Thinking兼容/格式纠正，保留取消，无网络错误自动重试。OkHttp默认write为每次写IO时限，而不是整个上传耗时；本地实际transport注入1ms的回归证明应用此前未接管写时限。

### 有效RED与维护调用错误
- AI首轮51tests/6failures/0errors：write实际1ms而非60000；明确context HTTP错误仍被Thinking兼容重试；length丢usage；本地预算不可用误报INTERNAL；两项设置缺少实际输入空间校验。`.tmp/t80/ai-red.txt`。
- 日志/About首轮29tests/11failures：日志五项断言（crash、轮转、白名单、数值、导出清理），About四项新动作/确认用例和两项五动作契约。`.tmp/t80/maintenance-red.txt`。
- 追加16tests/7failures：自动填充实际固定契约不够、controller四例、同步失败日志及提醒失败日志；core store七例已GREEN。此前一次追加测试因不同agent正在落跨文件引用产生编译快照缺AiCallDiagnostics的失败，未执行测试，保留`.tmp/t80/extra-red.txt`；统一可编译批次暂停写入后获得`.tmp/t80/extra-red-complete.txt`有效证据。
- 聚焦564tests/3failures：AI网络/协调/预算/呈现、日志核心与绝大多数About通过；保留小context16能力应如实显示而非沿用旧128000的有效RED。另两项About选择器测试错误要求stock CreateDocument含CATEGORY_OPENABLE，经固定activity1.9.2 aar字节验证并无该分类，只删错误断言，仍验证action/type/title、busy、取消等实际契约。
- 三agent交叉review及主agent审查追加有效2tests/2failures：DocumentProvider open/close的UnsupportedOperationException原会逃逸崩溃；R8混淆帧原被app包名前缀过滤而无位置记录。修为CancellationException优先，其余IO/RuntimeException可见失败；崩溃选≤32帧合法Java符号，优先自有/非平台位置，输出仅SHA256位点数值摘要及行号，不输出符号/路径/异常正文。`.tmp/t80/review-red.txt`。
- 上述问题修复后的61项聚焦零失败/错误/跳过；同命令静态检查报告45条detekt及新文件ktlint格式失败（真实门禁失败，未写成整命令成功）。已按文件边界拆小函数、保留异常cause、修正导出清理异常不能覆盖原失败/取消、固定schema索引/guard局部说明式抑制和100列格式；不修改全局门禁阈值。`.tmp/t80/focused-final.txt`。
- policy首次命令类名拼错/adb相对cwd定位错误，尚无有效目标测试执行；改为实际类名后，新T80文档未在精确允许表获得有效RED。只加该任务精确路径，不扩宽通配边界。

### Windows根契约回归（生产源码无改动）
- Miniconda Python3.12.4，384 unittest/21.933秒全通过；37模块AST/导入、build --check通过，完整PyInstaller exit0。
- 隔离EXE普通→静默/静默→普通均readiness/存活/exit0/quiescence/cleanup通过。dist/data5文件187185bytes，逐路径/大小/mtime/hash前后一致，仅完整性摘要，不用于测试。
- EXE45598278bytes/SHA256 e345784d8f10a0b3021897398daed72d57f01df9e0e3132ef6173a2d37a7884c。证据`.tmp/t80/windows-*`，不提交产物/日志。

### 日志维护接口
diagnostics模块提供DiagnosticLogger.record(DiagnosticRecord)、FileDiagnosticStore与DiagnosticMaintenance的exportTo/clearLogs/clearTemporaryCache。生产Application初始化APP_START与委托式crash handler；AppContainer复用唯一store接AI client/coordinator、WebDAV runtime、alert runtime及关于页服务。两文件各128KiB，输出仅白名单枚举/数字，导出重新解析字段并附schema1、versionCode、SDK和列标题。维护IO在后台，串行操作；只清current.log/previous.log及diagnostic-exports/export.tmp，清空日志也清残留导出临时文件。

崩溃位点复算：SHA256(UTF8(className + '#' + methodName))首8字节按big-endian Long并与Long.MAX_VALUE相与，另有有限行号；R8正式包需用该包mapping复算，mapping保留本地构建产物、不随日志导出。不保证磁盘失败或进程被直接杀死时仍有crash记录。

关于页CreateDocument只授本次写入，不申请持久URI权限；picker取消不调用服务，离页/旋转可取消当前IO，不承诺DocumentProvider阻塞写能立即中断。导出失败可能在用户选定位置留下不完整文件，应用不会额外删除用户目标文件。

### 完整验证与正式构建
- 首轮verify-all：全部224suites/2252tests（UI88/954）零失败/错误/跳过，四类泄漏0；lintDebug/lintRelease通过；随后仅AiCoordinator新增核算helper使方法数达11的detekt失败，整命令exit1/6m30s。保持同一取消owner所需不同生命周期方法，采用有说明的局部TooManyFunctions注解，不改全局规则。
- 后续静态命令detekt/main ktlint通过，仅测试两组list参数排版仍失败；改为逐参数换行，零行为变化。最后完整命令另记录，不将前述失败改写为成功。
- `scripts/build-release.ps1 -PythonExecutable <指定Miniconda>` exit0；APK/AAB签名、zipalign、artifact Manifest/network policy、无native/禁用SDK、bundletool、证书一致性审计通过。APK **1897977bytes/SHA256 c18196f98504f6c833e7418da46c46cec5cb334e02689c02ace654d05d0665e6**；AAB **5037818bytes/SHA256 3f75af383623833bd2c2a39fc5eb6e8da66451e63dce0c3c1cdda46a31ff7d70**。
- 证书SHA256保持 **628248932cfd0587a04126290ac2af870591fc7ebe80259c5f29d266c944615f**；包内仍1.3.1(3)。release mapping仅保留忽略的`.tmp/t80/release-mapping.txt`辅助未来指纹排查。最终255份生产源码/资源/schema摘要在签名后冻结，完整门禁后核对零漂移。
- 最终完整`verify-all.ps1 -PythonExecutable C:\Users\30910\Miniconda3\python.exe` **exit0**：Gradle 6m1s、96tasks（32执行/64最新）；224suites/2252tests（UI88/954）零失败/错误/跳过与四类泄漏。lintDebug/lintRelease、detekt、ktlint全部通过，112发布夹具、foundation/boundary各109项通过。证据`.tmp/t80/verify-all-final.txt`及`android-tests-summary-final.json`。不以此前失败命令代替本轮成功。

### 最终同包隔离设备验收
- 仅API36隔离AVD `clender_api36_phone` / `emulator-5584`安装最终签名APK，设备回读字节/SHA256匹配；真实USB手机未覆盖安装。使用全新合成应用状态，未调用Provider或操作真实数据。
- 系统CreateDocument取消无新增状态/文件；实际SAVE到Download成功。首次导出287bytes，固定schema/版本码/SDK头及2条APP_START，12字段白名单校验通过。分别取消日志与临时文件清理后再次导出，SHA256与此前完全一致；实际清理临时文件成功；实际清空日志后导出175bytes，仅固定头、0条记录。
- 设备夹具失败保留：第二次保存文件名含` (1)`，exec-out helper错误多套shell引号，把cat错误输出当作79bytes导出，header断言失败；修正为adb pull精确路径后回读有效287bytes，未改生产代码，未将夹具错误称产品失败。
- 明暗正常字号及320dp/2x（840x1920/420dpi，font_scale=2.0）关于页、两类确认弹窗和结果逐图审查通过，三个维护按钮可滚动到达且实际可点击；大字号结果需继续下滚。测试系统界面保持英文，未宣称中文设备视觉验收（中英资源与Robolectric均有测试）。未扩验厂商真机/真实Provider。
- 设备共15张截图，主agent复核10张正常/大字号关键图，其余由设备agent读图。设置恢复回读font_scale=1.0、1080x1920、420dpi、night=no；三个本次合成导出文件删除、应用卸载成功，确认AVD身份后emu kill返回OK。回执`.tmp/t80/device/cleanup-receipt.json`，真实USB手机全程未修改。

### 交付与后续排查
原证书APK位于`android/app/build/outputs/apk/release/app-release.apk`，AAB位于`android/app/build/outputs/bundle/release/app-release.aab`。本轮保持1.3.1(3)，仅本地提交、不推送或Release。用户自行覆盖安装后，若再次报错，可从关于→诊断与清理→导出诊断日志获取网络阶段、预算与错误分类；只会记录新包运行后的事件，无法补回旧包未记录的历史。不得用日志清理代替业务问题修复。

已同步根/Android AGENTS当前接口与验证记录、progress最新状态，历史字节保留。文档维护脚本曾假设Android历史也以T79开头而中断；改按真实下一标题边界后完成，不涉及生产源码。最终工程diff与敏感/边界检查后提交，详细提交对象以本地Git日志为准。

提交前执行根契约要求的Git HTTPS代理配置；52个工程文件的staged diff检查通过，禁止路径/凭据敏感模式0命中；staged `verify-boundaries.ps1` exit0（109项）。再次核对255份冻结生产文件无漂移，设备验收未修改源码。最终ADB设备列表为空；未对真实手机发修改命令。
