# T76 Android 设置透明化与关于页版本更新

## 第三阶段：用户截图反馈修订（2026-09-15，本地完成，待用户验收）

基线main/210bd18干净。用户更偏好半透明蒙板，取消上一阶段纯透明+细边框视觉契约；本轮采用无描边、surface色24%不透明度圆角分组蒙板，全局背景强度与遮罩不变。设置标签栏去掉外框，保留透明底与选中指示。关于两站平等呈现、统一OutlinedButton，纵向保留大字适配且增加12dp间距，zh/en移除Gitee备用标注。版本保持1.3.1(3)，仅Android，不发布。

范围：SettingsComponents.kt、SettingsScreen.kt、T76TransparentSettingsTest.kt；AboutUpdatesSection.kt、values{,-en}/about_updates.xml、AboutUpdatesTest.kt；本任务/progress/根与Android AGENTS。数据、权限、依赖、URL、导航与秘密草稿契约不改。风险是复杂背景文字对比与大字号可达；用轻薄主题蒙板、原文字色及真实截图核对。回滚本阶段提交，无数据迁移。

矩阵：设置正常三tab九分组alpha合成像素/无外框，边界明暗/无背景/320dp与2x，异常原背景回退/禁用控件及dirty回归；关于两按钮同样式且间距至少12dp、zh/en无备用，边界2x换行与48dp触区，异常无浏览器/重试仍通过；先变更视觉回归并取得RED再生产实现。主agent串行完整verify-all/签名构建，最终同包背景明暗三tab与关于大字截图审查后提交。

分工：settings子agent仅设置实现与测试；update子agent仅关于实现与测试；主agent负责串行测试调度、集成/设备验收、文档与提交。两agent不得同时启动Gradle，测试就绪通知主agent。完成定义：视觉反馈全部落实，完整Android门禁与签名产物、文档、Git本地提交齐备，无Windows修改或构建。

第三阶段先行记录：settings 8tests/4 RED（蒙板/顶框），about 12tests/8 RED（填充/间距/文案），日志android/.tmp/t76c-{settings,about}-red.txt；实现后20tests全部通过，ktlint只发现AboutUpdatesTest四处长行，按报告换行无语义变化；首次GREEN组合exit1保留于t76c-green.txt。review独立确认生产改动及像素采样/12dp边界覆盖，不降低旧异常/秘密草稿测试。主agent随后启动完整verify-all，470源码/schema预先记录。设备helper首轮因Python默认GBK读取UTF8失败（未产生文件/设备动作），显式UTF8精确纠正后AST通过。

日期：2026-09-15。基线：main/76ab3b5，开始时工作区干净。

## 目标与非目标

用户授权独立子 agent 实施，非功能细节自主决策、效率优先。
1. 设置九个分组去除不透明填充，保留全局背景强度和日夜遮罩、清晰文字、分组间距与细边界。
2. 应用/AI/WebDAV 标签栏透明化，圆角、选中指示与无障碍选择状态清晰，保持既有导航和脏草稿确认。
3. 关于页增加版本更新。具体检查/下载交互已集中询问用户，确认前仅调查该子任务。

不改 Windows 功能、Room v3、WebDAV v1、AI 契约、真实数据、依赖或权限；不推送或 Release。本地签名交付后待用户验收。

## 设计、范围与控制提示

- Agent settings：`android/app/src/main/java/com/molotov/clender/ui/settings/SettingsComponents.kt`、`SettingsScreen.kt`，必要时同目录新增标签栏组件；新 `ui/settings/T76*Test.kt`。Card 使用透明容器与显式 onBackground 内容色、零抬升；保留组 tag、控件回调、秘密 charArray 及清空 effect 作用域。
- Agent update：只读关于页、生产装配和网络架构，用户确认后补齐精确范围、状态设计和测试矩阵再实施。
- 主 agent：编排、审查、集成修复、最终验证；维护本文件、progress、根与 Android AGENTS；Android policy 只新增本任务文档精确允许路径。
- 各 agent 先写可编译的回归测试、记录旧实现 RED，再修改生产并聚焦 GREEN；不提交、不并发 Gradle、不读真实数据。最终由主 agent 统一提交。

## 先行测试矩阵

| 范围 | 正常 | 边界 | 异常/非法 | 回归 |
|---|---|---|---|---|
| 分组透明 | 三分节各分组透出背景，文字采用主题前景 | 明/暗、无背景、0/100强度、320dp/2x | 背景失效保持原回退 | 九个tag、保存/清空/dirty、秘密草稿测试 |
| 标签栏 | 三标签回调及选中语义、背景透明 | 窄屏/大字/最小48dp触区 | 未保存离开确认/取消 | tab tag、滚动、主题切换、无显示器 Robolectric |
| 版本更新 | 待用户确认产品交互后细化 | 当前版本未知/重复点击/生命周期 | 网络失败、无浏览器等随方案补齐 | 关于元数据、隐私说明、Drawer导航 |
| 集成 | Android verify-all、签名APK/AAB审计 | API26/36聚焦、可用模拟器截图 | 四类泄漏零、失败调查留痕 | offline strict、policy/发布夹具 |
| Windows | 用户追加明确本轮只构建 Android | 不改动 Windows | 不测试/构建/读取真实数据 | 本任务例外，历史门禁不取消 |

## 步骤与完成定义

1. 阅读契约、源码、T75及外观设计；确认更新功能范围。
2. 分组/标签与版本更新分别 RED→GREEN，串行 Gradle。
3. 主 agent 审查 diff、运行全量及静态门禁，构建签名包、模拟器实际截图并读图。
4. 按用户追加明确指令，仅构建 Android，不修改/测试/构建 Windows，不读取 dist/data。
5. 更新文档与风险，执行指定 Git 代理配置，审核 staged diff/敏感模式后创建本地提交。

完成须功能、测试、构建、文档及提交均有本轮证据，不沿用 T75 数字。

## 风险与回滚

透明表面依赖既有全局遮罩提供文字对比度，必须有背景实图验证。更新功能网络/浏览器可用性须有明确失败反馈，不能伪报已是最新。回滚使用最终提交的 git revert，无数据迁移，禁止清空真实库。

## 执行记录

- 已阅读基线、根/Android契约、外观设计与T75。设置遮挡源为 surfaceVariant Card 与默认 TabRow 背景。
- 两个实现 agent 已启动，只读调查完成后按上面边界推进。版本更新交互等待用户回答。

- 用户追加确认：无需对 Windows 端进行改动，本次只构建 Android；因此跳过 Windows 全部测试、构建与数据读取。

## 用户确认补充：Android 1.3.1

用户明确版本维持1.3.1。当前基线包内为1.3.0(2)，本次改为1.3.1(3)，applicationId及正式证书不变。Agent update 负责 android/app/build.gradle.kts 的两版本字段和 android/tests/release/test_release_tools.py 的精确版本断言。先改断言验证旧实现版本两项RED，再改生产字段GREEN；正常=1.3.1/3/同applicationId，边界=code单调高于2，异常=重复/错误字段被精确断言拒绝，回归=112发布夹具与APK/AAB审计及实际覆盖安装。关于的检查/下载交互仍待回答，与版本字段独立。

### 设置与版本聚焦结果
- 设置 RED：T76 6 tests /4 failures，API26/36 tab/分组透出与文字实际颜色回归；实现后 T76/T75/ApiKeyDraft/C3 共4 suites/44 tests零失败/错误/跳过，ktlintCheck与detekt通过。首GREEN组合因新增测试换行违反ktlint失败，局部修格式后green2通过（1m35s），失败日志保留。
- 生产仅SettingsComponents/SettingsScreen呈现改动：透明容器、onBackground、1dp outlineVariant细边、0dp elevation；TabRow透明、16dp圆角轮廓与内缩3dp选中线。独立review agent审查无阻断问题。
- 版本：测试先1 test/2 RED，1.3.0/2不符合1.3.1/3；仅两字段改动后指定Miniconda -B 同测试GREEN。RED误使用系统Python314（agent收到统一解释器指令前执行），后续统一Miniconda，不将其表述为指定环境RED。applicationId/签名/依赖未变。
- 112发布工具夹具在版本修改前通过，最终verify-all将重验新版本。设备模拟器为既有隔离clender_api36_t67、port5584，启动前adb为空，启动后验证身份及app未安装；待最终签名包。
### 完整门禁与签名包（当前设置/版本改动）

- 指定 Miniconda：`C:\Users\30910\Miniconda3\python.exe`。Android cwd：`powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-all.ps1 -PythonExecutable <Miniconda>`，exit0；Gradle BUILD SUCCESSFUL /6m13s，215 suites/2189 tests（UI84/921），零失败/错误/跳过，CloseGuard/SQLiteConnectionPool/SQLiteDatabase leaked/RoomDatabase leaked全部0；112发布夹具与foundation/boundary各109通过。日志 `.tmp/t76-verify-all.txt`。
- `scripts/build-release.ps1 -PythonExecutable <Miniconda>` exit0，原正式证书 SHA256 `628248932cfd0587a04126290ac2af870591fc7ebe80259c5f29d266c944615f`；APK/AAB签名、zipalign、Manifest、bundletool及依赖安全审计通过，版本1.3.1(3)。日志`.tmp/t76-build-release.txt`。
- APK：1,877,137 bytes，SHA256 `5094c0b3240a596b2fe35101a5c56d0c214b62323d1bac723b9d76fa21187ccf`。
- AAB：4,990,906 bytes，SHA256 `c5583bd59d235b8adef2220ef5cee5104369bd454cd29106b5a009f3a4a01dd2`。
- 从T75正式签名1.3.0(2)（SHA256 9af70e1e…ce1f）经真实`adb install -r`升级，UID10150保持，合成深色设置保留；升级前后XML与升级后PNG留`.tmp/t76-upgrade-*`，主agent已读图。只用隔离AVD/合成状态，无真实用户数据。
- 构建末至设备验证期465个源码/schema摘要一致。没有改Room/WebDAV/AI/权限/Manifest/依赖，没有Windows修改/测试/构建或dist/data读取。
- 设备harness：`.tmp/t76-device/acceptance.py --serial emulator-5584 --sha256 <上述APK> --run final1`，安装包逐字节SHA复核；合成原创色块PNG通过真实DocumentsUI导入，0/100端点回读。设备矩阵与清理尚在进行，当前不宣称全部通过。

### 尚待产品选择的第二项

“关于→应用版本更新”功能尚未实施。已分别询问“检查后跳官方发布页”、“只开发布页”、“应用内下载并系统安装”；用户后续回答仅限定Android与1.3.1版本，尚未选择更新功能。因该选择改变联网/下载/安装能力，按项目规划契约暂停此子任务，其他明确授权工作继续。当前包不能表述为已具备检查更新能力，T76整体不标完成。

### 设备与当前交付收口
- helper exit2为预定义“交互通过、待读图”，非失败；65张UI截图全部已读（主agent9张顶部+独立review56张），透明化/三tab目标通过，正常About读取1.3.1/3无回归。
- 320dp/2x WebDAV标签自然两行且可点击。Widget字号多行浮动label局部压到上一字段边框（字段布局本次未改，但未旧包对照，不宣称已证明旧版同现象）；大字应用页5次滚动未覆盖最底部，不称穷尽全页。AI/WebDAV底部操作可达，关于版本及隐私文本完整。未验厂商真机。
- cleanup：恢复命令通过且回读font_scale=1.0、1080x1920、420dpi无覆盖；身份clender_api36_t67已核对、卸载Success、emu kill完成，最终adb与进程核对见提交前回执。
- 中途主agent误判helper缺少返回Application步骤，独立review指出该步骤原已存在，立即更正；未修改或停止harness，没有该项设备失败。
- 当前仅设置透明化/版本子任务完成并可本地提交；第二项关于更新需用户选择，整体任务待续，不发布。


## 第二项继续实施：用户选择直接跳转发布页（2026-09-15）

基线main/5a17081，工作区干净。用户询问应用内下载安装复杂度，并明确复杂则用直接跳发布页；采用直接跳转，复杂度显著低于下载/校验/取消/安装授权闭环。无需再确认。版本保持1.3.1(3)，只构建Android、不推送/Release。

### 设计与文件边界
- 关于页新增“应用更新”区块，短说明“打开发布页下载新版本。”，GitHub与Gitee两个站点纵向全宽按钮，分别打开项目正式固定HTTPS发布页。明确展示站点，避免自动失败切换引发重复跳转。
- 固定GitHub地址 https://github.com/Molotov0cocktail/Clender/releases/latest；备用Gitee地址 https://gitee.com/Molotov0coaktail/clender/releases。来源为项目README/历史正式Release；GitHub latest已实时回读到v1.3.0，Gitee web读取受工具限制，不冒称在线成功，设备只验证正确Intent交接。
- 点击才ACTION_VIEW/BROWSABLE交给浏览器；不读取用户配置，不联网检查、不下载APK、不申请安装权限、不增加依赖/Manifest/后台服务，不声称“已是最新”。无浏览器或SecurityException时显示通用失败提示并可重试，不能展示底层异常。
- Agent update：ui/about/AboutScreen.kt、新app/about/OfficialReleasePageOpener.kt（或同义文件），必要时ui/about独立更新组件；values/values-en/about_updates.xml；AboutScreenTest及新About更新UI/opener测试。旧metadata/隐私说明/tag/滚动/导航契约保留；只把“0外部操作”旧断言替换成精确新增的两站入口。
- 主agent：文档、独立审查、完整verify-all、签名构建与设备小范围关于页验收、提交。设置透明化生产代码不再改；上一轮65图仅作为该不变区域历史证据，不重做全矩阵。

### 先行矩阵与完成定义
|范围|正常|边界|异常/非法|回归|
|---|---|---|---|---|
|更新入口|两站按钮仅点击启动正确固定HTTPS Intent|无版本metadata仍可打开；重复显式点击|无handler/SecurityException通用错误且可重试|打开About不自动launch；无秘密/extras/用户URL|
|UI|zh/en标签与版本/隐私说明保留|API26/36、320dp/2x、明暗可滚动、48dp触区|失败不导航、不显示底层异常；重试成功清提示|Drawer/Back与其他About原测试|
|集成|聚焦RED→GREEN、verify-all与签名审计|最终APK同包About明暗/大字截图|模拟器无浏览器时实际失败可见|1.3.1(3)/原证书/权限/依赖保持|

完成须两项功能均落地、门禁通过、签名APK/AAB、About新设备证据、文档与本地提交。回滚本次追加提交即可，无数据迁移，禁止清真实库。历史待确认段落保留为上一交付事实，本节取代其当前状态。


### 第二项聚焦结果与独立审查
- 新AboutUpdatesTest API26/36共8 tests/8 RED（缺更新节点，可编译），实现后About/metadata/navigation共6 suites/57 tests全通过，ktlintCheck/detekt通过。green1仅新增测试import排序失败，交换两行后green2 exit0（1m2s）；失败日志保留，未降低断言。
- 独立review检查固定enum地址/无自动launch/无extras/两个平台异常/重试清理/metadata及隐私/48dp触区，未发现阻断问题。
- 主agent已启动完整verify-all（.tmp/t76b-verify-all.txt），待最终签名包与About新设备矩阵；470源码/schema预先记录摘要。上一轮设置透明65图为未变区域已有证据，当前不重做，不冒称全部由新包重新拍摄。

- 完整门禁首轮在2197应用测试全通过后，lintDebug因新opener使用Uri.parse触发UseKtx error而exit1（5m4s）；主agent按报告仅改既有androidx.core.net.toUri调用/import，不加依赖或抑制。原日志t76b-verify-all.txt和修前470摘要保留，修后完整重验记录另列。

### 最终完成记录（第二阶段，取代上方待确认状态）
- 完整命令：Android cwd运行 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-all.ps1 -PythonExecutable C:\Users\30910\Miniconda3\python.exe`，日志 `.tmp/t76b-verify-all2.txt`，exit0/Gradle 8m7s。216 suites/2197 tests（UI85/929），零失败/错误/跳过；CloseGuard/SQLiteConnectionPool/SQLiteDatabase leaked/RoomDatabase leaked全部0。112发布夹具、foundation与boundary各109全部通过。
- 同一Python执行 `scripts/build-release.ps1`，日志 `.tmp/t76b-build-release.txt`，exit0；apksigner/zipalign/aapt2/网络策略/bundletool/jarsigner/证书一致性/归档审计通过。版本1.3.1(3)，原证书SHA256 `628248932cfd0587a04126290ac2af870591fc7ebe80259c5f29d266c944615f`。
- `app/build/outputs/apk/release/app-release.apk`：1878829 bytes，SHA256 `3ae6ac22a162aab6c2f84841f2f7cdc6fef665bfc39794354e64b8a375da6775`。
- `app/build/outputs/bundle/release/app-release.aab`：4995885 bytes，SHA256 `3ceb831f91aa6a445305c37bf3d956231b221ab3eb3479f194619b7c3732c758`。
- 470源码/schema预后摘要一致。构建期间曾安装仍存在的上一阶段APK，未对其执行本轮验收；构建exit0后重新安装最终包，并读取设备实际APK字节核对最终SHA256，以下验收只属于最终包。
- `.tmp/t76-about-device.py --run final1`：API36/clender_api36_t67，helper exit2为交互完成待读图；两站与恢复重试的真实浏览器前台交接及hostname记录通过，精确URL路径由API26/36单测断言。禁用浏览器后实际失败提示可见，恢复default-state后成功重试且提示消失。不宣称外网站点加载或APK下载成功。
- 主agent已读7张图（light-top/light-updates/no-browser-error/dark-updates/320dp-2x-top/github/gitee），文字与两个按钮完整可达，Gitee备用文案在2x自然两行。旧设置生产代码未改，65图仍是第一阶段证据，未重做；原Widget多行label局部压线/大字应用页未穷尽底部/未验厂商真机限制保留。
- 清理回读font_scale=1.0、1080x1920、420dpi无覆盖，浏览器enabled=0恢复默认；按AVD身份卸载合成应用Success、emu kill完成。最终adb空与工作区状态以提交前回读为准。无Windows改动/测试/构建/真实数据读取，无推送或Release。
- 根与Android AGENTS已同步新入口/错误语义/验证结果；任务与progress已标两项本地完成，待用户验收。提交哈希以Git日志及最终答复为准。

### 第三阶段最终结果（取代前两阶段视觉约定）
- Android cwd、指定Miniconda执行`powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-all.ps1 -PythonExecutable C:\Users\30910\Miniconda3\python.exe`，日志`.tmp/t76c-verify-all.txt`，exit0，Gradle9m40s。216 suites/2203 tests（UI85/935），failure/error/skipped全0，CloseGuard/SQLiteConnectionPool/SQLiteDatabase leaked/RoomDatabase leaked全部0；112发布夹具、foundation/boundary各109通过，lintDebug/lintRelease/detekt/ktlint全部通过。
- 同一Python执行scripts/build-release.ps1，`.tmp/t76c-build-release.txt` exit0；APK/AAB签名、zipalign、aapt2/Bundletool清单、网络策略、证书一致性与归档审计通过。正式证书SHA256仍628248932cfd0587a04126290ac2af870591fc7ebe80259c5f29d266c944615f；版本1.3.1(3)。
- `app/build/outputs/apk/release/app-release.apk`：1878621 bytes，SHA256 `c516e1b763f5095a51d8d2597f9d437a4b326aa35aed05c60035035e5f2a09d8`。
- `app/build/outputs/bundle/release/app-release.aab`：4995662 bytes，SHA256 `887aa42de62b79986411a7abd8d35b8837cab5b2bf21becccd5d101867456fd2`。
- 最终设备实际APK字节摘要匹配，`.tmp/t76-device/refinement.py --run refinement1`交互完成exit2（待读图）；44图已逐张读图，主5/review39。正常明暗三tab9组蒙板无外框、透出原创背景、文字清晰；about-normal两站样式相同、间距清楚、无备用；320dp/2x应用Widget label仍压前一字段边框，且应用5滚未穷尽页面底部，这两项历史限制未修复。AI/WebDAV大字底部操作完整。只对合成背景/API36截图负责，不声称任意背景对比度或厂商真机通过。
- about-320dp-2x-updates中reveal仅保证文本部分露出，Gitee底部被viewport裁切，不能据此宣称完整。保留原图并追加`.tmp/t76c-about-bottom.py`（同一APK、无背景、320dp/2x、滚动到底）截图核验。设备第一次重启安装遇offline（尚未boot，未安装/未运行helper）；确认boot_completed=1后才安装验收。无生产改动或掩盖旧截图限制。
- 初次与补验均恢复font_scale1.0、1080x1920、420dpi无覆盖；原背景测试图删除，合成应用卸载、按clender_api36_t67身份关闭模拟器。最终adb空、补图结论、工作区清洁和提交哈希以提交前回读及最终答复为准。
- 470源码/schema摘要前后一致。根/Android AGENTS与progress已同步本次视觉约定、验证与限制。无Windows修改/测试/构建/真实数据读取；无推送或Release。本轮Git提交见日志。

第三阶段最终回读：about-bottom补图已逐张审查，浅色无背景320dp/2x两按钮均完整可见且12dp间距清楚；共45截图（review39/主6）。补验恢复显示参数并卸载Success，AVD身份核对后关闭。
