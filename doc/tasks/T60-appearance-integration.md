# T60 双端外观集成
目标、非目标、接口/数据影响、风险、回滚和先行测试矩阵见doc/appearance-design.md。
文件：Android基线恢复、设计/进度/AGENTS及必要集成修复。
清理失败诊断范围：仅Android testsupport/ProductionActivityTestResources.kt加入匿名失败状态，保留原check与全部suppressed；先另存原XML，再单类CalendarOverflow与相关资源测试一次，不复现不改生产/不盲重试全量。诊断矩阵覆盖成功无输出、失败保留原原因、DB/sidecar存在性和长度、Room关库状态；不记录路径/内容，诊断错误不得覆盖原清理失败。
步骤：核对并恢复基线；分派T61–T63；审查；全量/视觉/完整双端构建与隔离启动；data只读摘要；staged安全检查、提交。
完成定义：设计全部验收通过，限制如实记录。状态：双端实现、独立审查、完整构建与设备验收完成，安全提交收口。

基线集成发现：Android foundation 64项中2失败，根 .gitignore 的 data/ 模式误忽略 Android 包名 data/。精确改为 /data/（保留根真实数据忽略和 dist 整目录忽略），不放开用户数据；已有正反策略测试覆盖源码必须跟踪与数据隔离。其余62项通过，修复后复验。

视觉集成矩阵补充：24张明暗8/13/20截图中，Light时间轴使用原muted色，和浅背景对比不足。先加主题文字对比度回归（两主题的muted对app_bg至少4.5），再仅加深Light muted；未知输入与主题切换仍沿用T61测试。Android独立审查发现旋转期间旧图片导入可能继续写盘，交T62加ViewModel生命周期与回归。

集成补充范围（实施前）：Android既有policy只允许Android任务，需将本轮PC外观精确路径加入白名单，保留未知源码、路径后缀、data/产物拒绝测试；旧T49/T50/T51中main.py拒绝样本改为未授权database.py。新增T60正负边界测试先红后绿。恢复的Android支撑文档/CI/隔离exe测试均来自6e5b525；本轮统一提交并在diff审查中按来源区分基线与外观增量。

## Windows最终验证
- `QT_QPA_PLATFORM=offscreen` + 指定Miniconda `-m unittest discover -s tests -v`：252/252，最终8.733s。新增对比度旧失败1.711:1，修复后两主题muted文字对背景>=4.5:1。
- 35个根/ui模块AST/导入通过；`build.py --check`与最终完整`build.py`成功。中间构建完成后因截图发现浅时间轴字色，再修改并重跑完整测试和构建，最终仅以下包为交付包。
- Android图标资源lint修整后再次全量Python、完整PyInstaller及两场景隔离exe冒烟通过；最终 `dist/Clender.exe` 45,574,170 bytes，SHA256 `9898dd5a351c48d5d66351d77402b3d1e745cb86fabaf85f547ed8f66215486e`。PC图标内容未改变。
- `scripts/verify_frozen_single_instance.py --cycles 1`：普通→静默、静默→普通两场景secondary=0、primary存活、readiness/quiescence/cleanup均true，使用临时exe/合成数据。
- dist/data构建与冒烟前后均5文件、143610bytes，组合SHA256 `f2535afde38c9dd9c9547661d9245fa8808c5b0ed466aedfa3c8f34c0449eef5`，只读摘要一致。
- 最后一次资源修整重构建，在发布替换前及隔离冒烟后复核data仍5文件/143610bytes；规范紧凑JSON元组(相对路径,大小,文件SHA256)摘要 `903c2535d51e71f698a803e93e7022133e1a7fdacd339f66416ab1782fcf20c5` 完全一致（摘要序列化方式与前轮不同，未输出用户文件内容）。
- 24张PC截图：Light/Dark × 8/13/20px × 月/周/日/480px高设置，合成图片与临时DB/config；查看明暗、长标题、固定保存、图层可见和主题颜色。初次测试截图未处理Qt DeferredDelete导致旧月视图残影，纠正QA脚本事件循环后重拍，非产品故障。
- Android policy 64/64、发布脚本夹具111/111先行通过；最终仍须同源码全门禁和staged边界。
- 隔离新建AVD clender_api36_t60（5580），重命名前clender_t60_api36；第一次刚发退出时文件仍被占用重命名失败，确认退出且目标不存在后成功；未启动/清空原8台模拟器。

## Android平台诊断证据（不能仅归类JVM问题）
- 首先大合成JPEG在API26/36原生ExifInterface(ByteArrayInputStream)均读取orientation6；随后使用与JVM完全相同的689-byte短JPEG发现实际API26读0、API36读6，推翻“只在Robolectric失败”的初步推断。
- 标准COM扩充同一小JPEG后两API均正常；进一步保持原文件/BitmapFactory输入不变，仅EXIF内存流补零至8192字节，两API均读6。API26原FileInputStream仍读0。采取有界EXIF读取兼容，不替换原失败夹具、不实现自定义解析器。
- 平台证据位于本次visualizations的android-framework-exif-probe.json、android-framework-exif-exact-fixture.json、android-framework-exif-padded-fixture.json、android-framework-exif-short-compat.json；全部只用新建AVD/合成图片，未安装旧APK。
- 原Android实现agent因模型服务容量连续错误中断，已将最终聚焦/静态修整交由PC子agent接续；主agent保留编排、集成和完整发布职责。

## Android最终验证
- 最终同源码完整JVM：165 suites / 1727 tests（UI63 suites/737 tests），failures/errors/skipped均0；背景聚焦10 suites / 46 tests通过。
- 最终 `scripts/verify-all.ps1 -PythonExecutable C:\Users\30910\Miniconda3\python.exe` 成功：111发布工具夹具、96 Gradle门禁（完整测试/双变体lint/detekt/ktlint/锁/无native/Debug构建）、65策略及boundary全部通过；Gradle 5m13s，日志`.tmp/t60-verify-all-complete.txt`。离线严格验证，无测试skip/ignore。
- 2026-09-07最终XML/HTML四类SQLiteConnectionPool termination、SQLiteConnection/Pool泄漏、Room未关闭标记均0。此前单次数据库清理失败未在18项聚焦或最终全量复现，保留原断言与失败诊断，不声称已证明其根因。
- `scripts/build-release.ps1 -PythonExecutable C:\Users\30910\Miniconda3\python.exe` 成功，独立既有签名与APK/AAB产物审计通过（日志`.tmp/t60-release-final.txt`）。APK 1,757,333 bytes，SHA256 `f7ea995ce2ab5ac0f49d98114bfd4de7a2500e024ac0bcdac15be458fc3bf5ad`；AAB 4,689,435 bytes，SHA256 `8ea897b122ef89b740353351548bc42e5bc2a525e0565b5eeca47c551b083e16`。签名秘密不输出/不提交，包仅留忽略目录。
- 上述包设备8核心流程双API通过，但主agent检查dark截图发现透明Scaffold导致LocalContentColor默认黑色，背景说明/预览普通Text对深色背景不足；该包因此不作为最终交付。T62先加实际颜色继承失败回归，再根层提供主题onBackground，保持所有子页面继承。修复后必须重新完整Android门禁/签名/双API背景验收，不沿用上述包哈希。
- 最终交付源码：实际TextLayoutResult/LocalContentColor回归4RED→4PASS，根AppBackground统一提供onBackground，背景聚焦50项通过。完整`verify-all.ps1`再次成功：166 suites/1731 tests（UI64 suites/741）、111发布工具、65策略、96 Gradle门禁，零失败/错误/跳过/四类泄漏标记；4m48s，日志`.tmp/t60-verify-all-delivery.txt`。
- 最终交付包：`build-release.ps1`及签名/产物审计通过，日志`.tmp/t60-release-delivery.txt`。APK 1,757,329 bytes，SHA256 `936a58ca92b678a8d05723d54b62604256612cd90b3d57a857235f71a24afbc5`；AAB 4,689,668 bytes，SHA256 `cbf1d6d2e6e0e748e1cbfae3f6cf88f55906f7f08d2bd9e2d33f90517cf09b4e`。仅此包替代前述候选包。

## 最终设备与提交审查
- 专用API26 clender_api26_t60/5582、API36 clender_api36_t60/5580；旧候选包各8项核心流程通过，新包同签名覆盖且不清数据。最终每台覆盖15个背景场景：PNG系统选择/导入、0/100、Light设置/日历、EXIF选择/导入、Dark设置/日历、冷启/保留100/移除/移除后日历/启动器。主agent复核两台夜间设置、日历、方向与图标截图，文字和背景清晰。
- API36 15/15一次通过；API26前10项通过，冷启后导航前ADB identity getprop返回非0，中断报告保留。重新验证专用AVD名、qemu、boot=1及应用存活后只从未完成点击接续，其余5项通过；原FAIL不删除，不将连接中断说成应用Bug。报告为visualizations中emulator-5580/5582-final-background-report.json。
- 两台专用模拟器核对身份后emu kill，最终只读确认均已disconnected；未删除目录、未操作原AVD。准确汇总t60-final-device-summary.json，关闭报告t60-device-close-report.json。
- Widget手动步骤由原核心脚本固定标记REQUIRES_LAUNCHER_DEVICE_TEST，未执行；本轮Widget无业务修改，已有自动化回归通过。设备为英文API26/36模拟器，真实厂商ROM/启动器、多屏DPI/中文真机没有穷举。PC背景引用本地文件，移动/删除后回退；Android复制至私有存储。
- 分别按PC main 3bc9fa4及Android来源6e5b525审查增量；初次470路径staged扫描无用户数据/生成产物/密钥模式，新增实际颜色测试随最终471路径再审。根AGENTS/子AGENTS、设计/T60–T63/progress均同步。已按契约设置Git HTTPS代理；最终提交只含工程文件，不push，提交哈希由Git历史与最终回复提供。
- 首轮完整verify-all在lint发现5errors：平台ExifInterface旧系统安全建议2处、minSdk26冗余资源目录、旧图标无引用、基础adaptive缺monochrome。未降低门禁：分别交独立agent收口照片依赖方案和资源生成器/回归，完成后重跑全门禁。损坏图片测试的incomplete input原生诊断符合异常输入用例，不是测试失败。
- AndroidX替换后46项聚焦及双变体lint/ktlint/detekt/锁/无native全部通过，依赖仅新增官方固定1.4.2，旧锁和校验组件未变。第二轮全量165suites/1727项中1726通过，CalendarOverflowDetailPlaceholderTest的UI断言通过但隔离数据库删除失败（ProductionActivityTestResources.close）；保留失败日志t60-verify-all-final.txt，独立agent检查资源关闭/清理原因后再验证，不能把这一轮标为通过。
