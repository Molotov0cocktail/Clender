# T62 Android背景与界面
最终完成：166 suites/1731 tests、111发布工具、65策略、完整签名APK/AAB及两台专用API26/36背景设备验收通过；失败与接续、包哈希、限制见T60。日夜文字实际颜色4RED→4PASS并重新全门禁/签名/截图，最终APK为936a58ca…a24afbc5。
目标：系统选择图片/持久背景/移除与强度/日夜蒙板和Compose美化。非目标：业务/网络/Room改变。
文件：Android现有appearance/settings/Compose与测试；agent先补精确触点；不改launcher图标。
接口、数据、风险、回滚、先行测试矩阵见doc/appearance-design.md。
步骤：恢复后读契约与源码；补触点和失败测试；实现有界异步图加载与设置UI；串行聚焦验证。
完成定义：旧配置兼容、持久/移除/异常回退、明暗/大字/导航回归与构建通过。状态：实现与背景聚焦/静态通过，交主agent全量/最终签名与设备验收。

## 精确范围与数据契约
新增 data/settings/BackgroundStore.kt、BackgroundPolicy.kt、BackgroundImageDecoder.kt；ui/theme/AppBackground.kt、BackgroundController.kt、BackgroundViewModel.kt；ui/settings/BackgroundSettingsSection.kt；修改 ui/app/ClenderApp.kt 背景宿主与透明 Scaffold、SettingsScreen.kt 插入独立背景区域、ClenderTheme.kt 配色；新增中英文 background_strings.xml；对应策略/存储/UI/重建测试与纯色JPEG夹具，android/AGENTS.md、README.md。
背景为应用私有、独立即时保存外观设置，设置区明确说明；现有主题字号、AI、WebDAV 草稿及保存行为不改。OpenDocument 仅 image/*，用户选中的内容异步有界读取/解码后归一化 PNG 保存私有 files/background，不记录来源 URI，不需要持久授权或新增权限。配置以fsync+Files.move(ATOMIC_MOVE,REPLACE_EXISTING)原子保存，替换失败保留旧背景；强度0–100映射至安全图层透明度，上限light 0.28/dark 0.24。背景无网络/Room/Widget影响。Activity ViewModel持有单controller/viewModelScope，重建保留busy与晚到结果，避免重复写者。

最终EXIF契约：解析由官方androidx.exifinterface:exifinterface:1.4.2提供，精确catalog/lock/verification SHA-256固定；BitmapFactory与EXIF均读取原始有界字节，无平台android.media.ExifInterface、无8192补零、自制parser或安全lint抑制。下方平台短流探索和补零验证只保留历史证据，已被最终AndroidX实现替代。

## 先行测试矩阵
策略：旧/缺失配置默认35、0/100、负数/超界/非法类型归默认；light/dark全部强度保留遮罩下限；非法尺寸、超大图片拒绝，正常及极端宽高有界采样。
存储：临时目录PNG导入/重开/强度持久化/移除；坏图/空流/超限字节/配置损坏/不合法文件名回退；失败导入保留已有图片；不触碰其他文件。所有输入由测试生成。
UI：Light/Dark、8/20字体下背景区域可滚动访问；选择/移除/强度语义与busy禁用；现有草稿/导航回归由原有Settings、Drawer及全量覆盖。纯策略、存储先RED再实现，Gradle串行协调。

## 执行记录
- 根文字继承收口：旧实现4/4实际颜色失败，Light实际纯黑而非主题onBackground（在第一轮Light即截住）；证据android/.tmp/t62-content-color-red.txt/.xml。仅AppBackground根provider增加LocalContentColor=onBackground，随后两路由真实Text颜色与继承值在Light→Dark→Light全部通过。offline strict下精准ktlintFormat/ktlintCheck、detekt、全部Background聚焦11 suites/50 tests（0 failures/errors/skipped），其中新颜色回归4项PASS，BUILD SUCCESSFUL 1m23s（android/.tmp/t62-content-color-green.txt）；新版全量/签名/设备截图由主agent重跑，不复用旧APK视觉结论。
- 2026-09-07设备深色截图发现根透明Scaffold导致普通Text继承黑色。修复范围仅AppBackground.kt、新BackgroundContentColorTest.kt及本记录/Android契约；不逐Text改色、不改背景强度/持久化。先行矩阵：API26/36、light→dark→light运行时切换、透明Scaffold下真实BackgroundSettingsSection标题与CalendarScreen空态的TextLayoutResult实际文字颜色及LocalContentColor均须等于onBackground；无背景配置也须成立。旧实现先RED，再根CompositionLocalProvider最小修复；聚焦Background及ktlint/detekt后交主agent全量/签名，回滚为撤销该根provider增量。
- 完整验证清理诊断接续：AndroidX全量1727项中仅既有CalendarOverflowDetailPlaceholderTest在teardown删除临时DB返回false，业务UI断言未失败。主agent授权仅修改testsupport/ProductionActivityTestResources.kt失败诊断，不改生产/原check/全部suppressed链。矩阵：成功路径不输出；失败路径匿名DB/wal/shm/journal/lck存在性及大小、关库后isOpen状态；诊断本身异常不得替换原失败。先复制原XML到.tmp，再单类与相关资源清理测试各一次；不复现只保留诊断，不盲改生产/放宽断言/循环重试。
- 清理诊断结果：原失败XML保留`.tmp/t62-original-calendar-cleanup-failure.xml`。BackgroundViewModel仅操作background文件，无Room/DB引用；Room2.8.4 close反编译确认CloseBarrier等待blockers归零后关connection manager，不能凭此次返回false声称后台未关闭。原XML缺少删除前后状态，无法区分Windows文件占用与false时文件已消失；当前不确认根因。仅添加失败时匿名存在性/长度及Room.isOpen、原check和suppressed聚合不变；反射字段经实际AppContainer字节码核对。`--tests '*CalendarOverflowDetailPlaceholderTest*' --tests '*MainActivityRobolectricTest*' --tests '*AppContainerWidgetAssemblyContractTest*'`单次聚焦56s BUILD SUCCESSFUL，`.tmp/t62-cleanup-focused.txt`；未复现，不改生产或删除断言、不加入sleep/GC/重试。诊断保留供主agent下一轮全量捕获，历史失败不因本次通过被抹去。
- 安全lint接续：完整1727项通过后lint报告平台EXIF旧系统安全缺陷，主agent授权固定AndroidX ExifInterface 1.4.2并获取官方Google Maven artifact。范围追加gradle/libs.versions.toml、app/build.gradle.kts、app/gradle.lockfile、gradle/verification-metadata.xml及tests/policy/test_dependency_policy.py精确增量；先行依赖policy验证新增模块版本/9 classpath、生产禁止平台导入、旧锁摘要不变，再实现。正常/短JPEG/8方向/坏图/旧锁/配置宇宙回归必须通过，禁止抑制安全lint或放开旧依赖冻结；移除平台8192 workaround，官方artifact校验后恢复offline strict。
- RED：默认沙箱 `.tmp` 输出文件和 `.gradle` wrapper lock 启动前拒绝（0 tasks）；获自动审查提权后执行 `scripts/gradle.ps1 --offline --no-daemon --max-workers=1 --dependency-verification strict :app:testDebugUnitTest --tests '*BackgroundPolicyTest' --tests '*BackgroundStoreTest'`，22 tasks，1m26s，新增背景类缺失导致compileDebugUnitTestKotlin失败，随后开始实现。
- 首轮聚焦：19 tests，18 PASS / 1 FAIL；API36 JSONObject整数读为非Int Number，强度100误回35，API26通过。改为严格接收0–100范围的整数Number，拒字符串/分数。
- 主agent独立审查后补：CancellationException重抛、可注入IO调度器/取消测试、配置4KiB上限、平台ExifInterface旋转与镜像归一化、EXIF生成夹具及超限/坏配置/保存异常测试。首轮测试期间的这些增量必须重新同源码验证，不复用阶段结果作最终证据。
- 收口独立接手：当前API26 EXIF旋转断言20×40得到40×20，API36通过；保留原断言并增加平台ExifInterface读出的方向断言，定位JVM平台限制或生产错误，不自造EXIF解析器。detekt仅BackgroundSettingsSection 63/60 LongMethod，计划抽出错误提示Composable，不改功能；聚焦背景测试及ktlint/detekt重新串行验证。
- API26诊断确认Framework直接读取方向为0（期望6），API36通过。初步以为Robolectric差异、曾临时将e2e限API36；随后真实API26同短JPEG亦读0推翻该假说，立即恢复原API26/36端到端断言，原短夹具未替换。原Matrix方向变换提取internal applyOrientation，在API26/36对全部8方向逐一断言尺寸与角像素（均通过）。品牌agent已证明加入合法COM的大流可读6，正在验证只对EXIF读取补足有界流而不改图片内容的最小兼容。
- busy UI先行节点不存在RED后新增双语liveRegion文本；独立Compose测试宿主使用与实际设置同样滚动容器，滚到状态断言可见。测试需独立IO dispatcher确保操作挂起进入busy，增加busy前置断言，避免同dispatcher即时执行掩盖场景。`t62-busy-green.txt`：精准ktlintFormat/ktlintCheck、detekt及BackgroundSettingsTest双API共6项通过，BUILD SUCCESSFUL 1m3s；主方法LongMethod已解决，状态区保留错误提示与新增处理中提示。
- 真实平台短流根因与最小修复：同689byte原JPEG，API26 ByteArrayInputStream/FileInputStream均读方向0；仅对EXIF读取流尾部补零到8192即方向6，API36均6（android-framework-exif-short-compat.json）。生产仅小于8192的EXIF byte流有界copyOf，BitmapFactory仍读原bytes、原短夹具不替换；readOrientation/全部8方向矩阵共享生产实现，API26/36原20×40断言全部保留。无自制parser、无测试skip/ignore、无降低期望值。
- 最终同源码收口命令：`scripts/gradle.ps1 --offline --no-daemon --max-workers=1 --dependency-verification strict -I .tmp/t62-format.gradle :app:ktlintFormat :app:ktlintCheck :app:detekt :app:testDebugUnitTest --tests '*Background*'`，日志`.tmp/t62-final-green.txt`，BUILD SUCCESSFUL 1m20s，46 tasks（14 executed/32 up-to-date）；精准背景源码格式检查、detekt和全部背景测试通过。原始坏图用例会输出有限的native decoder incomplete input提示，属于预期拒绝路径。Android AGENTS已更新忙状态及实测EXIF兼容契约；Gradle已退出，主agent可接续全量/发布。
- AndroidX安全收口RED：新增`test_background_exif_uses_exact_androidx_dependency`旧锁中1.4.2缺失而失败。一次在线Google Maven解析使用`--write-verification-metadata sha256 --update-locks androidx.exifinterface:exifinterface :app:dependencies verifyNoNativeRuntimeArtifacts`，42s成功；原锁备份`.tmp/t62-before-exif.lock`与metadata备份用于逐行/逐组件比较。锁仅新增EXIF1.4.2一行9 classpath，全部旧行零变化；metadata仅新增该模块AAR/POM/module，SHA-256与缓存官方下载artifact逐一一致，全部旧component零变化。删除报告额外生成的未使用foundation1.6.0 metadata，未更改依赖锁黄金摘要/85配置宇宙。
- AndroidX同源码聚焦：`.tmp/t62-androidx-green.txt`中的ktlint/detekt与10 suites/46 tests全部通过（failure/error/skipped=0），双API原689byte短JPEG方向6和20×40、8方向角像素均保留通过。该命令后续仅因旧空mipmap-anydpi-v26目录lint失败，主agent清理空目录后续跑，不把首次命令记为全成功。
- 最终离线严格门禁：`scripts/gradle.ps1 --offline --no-daemon --max-workers=1 --dependency-verification strict lintDebug lintRelease verifyResolvedVersionsLocked verifyNoNativeRuntimeArtifacts verifyNoGoogleServices`，`.tmp/t62-androidx-final-gates.txt`，BUILD SUCCESSFUL 3m16s，58 tasks（29 executed/5 from cache/24 up-to-date）。依赖policy15项+版本/metadata policy8项通过，主agent另完成65项全policy；git diff --check通过。Android AGENTS已明确AndroidX为当前契约、平台补零仅历史。Gradle完全退出并交主agent完整verify-all/签名/最终设备验收。
