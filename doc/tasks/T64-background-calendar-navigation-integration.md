# T64 既有交付恢复与 Android 导航优化

## 目标与基线
用户要求基于上次 `773c2dc` 交付整合本次 `c2ef074` 排程/日历改动，并美化 Android 左侧导航。实际共同祖先为 `cde6e1e4`，`6e5b525` 仅是 Android 内容基线；上次还包含 `3bc9fa4` PC 改进。必须完整保留上次双端背景、图标、PC 功能，不能仅复制背景文件。按用户最新要求，已保存未提交整合工作到 stash 并切回 main/773c2dc 干净基线，在该版本上仅重放 Android 改动。验收提交后删除本地 codex/android-architecture 分支；不推送、不改远程分支。

## 范围、契约与非目标
当前范围为 main/773c2dc 上的 Android 增量；新增修改限 Android Drawer、日历背景兼容、必要整合回归与策略精确路径、两级 AGENTS/README/设计与任务记录。依赖仅恢复上次已验证的 ExifInterface 固定版本，不新增升级。背景选图/可见度/主题蒙板/冷启保存/移除复用上次实现。Drawer 保留全部五个目的地、选中语义、48dp 点击目标、Back/手势/设置脏草稿确认；采用品牌标题、图标、分组间距、主题色选中项与可滚动布局。AI 保留 T52 的全部安全与失败反馈修复。无数据库迁移、无真实对话/API 请求、无真实背景读取。PC 保持 773c2dc 原样，不修改、不测试、不构建、不读取真实数据。

## 测试矩阵（实施前）
- 基线回归：以 Git 对象检查上次新增背景/图标/PC 源码存在且未丢失，T52 AI/calendar 修改存在；在当前旧树上证明背景缺失检查失败，合并后通过。
- 背景：已有导入正常/取消/坏图片/超限/EXIF/清除/生命周期测试；明暗内容色与设置入口，冷启恢复、移除。合成图片实际设备验收，不接触用户图片。
- 日历：T52 月周日、中文英文、超大字号/小屏/横屏、星期字形、时间轴对齐、空/加载/错误与手势回归；与背景主题同屏验证。
- Drawer：先写在旧布局失败的品牌/图标/间距/低高度滚动测试；覆盖五目的地、选中状态、明暗、中文英文、大字号、低高度与返回/草稿回归，不降低既有断言。
- AI：运行已有无配置、超时、非200、畸形JSON、危险操作、截断响应、部分失败及 Room 排程集成测试，不复用临时Key。
- 完整 Android verify-all、签名 APK/AAB 审计；最终包 API26/36 手机背景+Drawer+日历目视/交互验收，平板按风险补充。
- PC 按用户明确要求不修改、不测试、不构建、不读 data；最终只核验工程源码与 773c2dc 一致。

## 分工与步骤
主 agent：规划、Android 改动重放、集成审查、串行 Android 构建、签名与发布门禁、文档/索引提交。
独立审计 agent：只读核对两边功能清单和冲突，完成后复核最终 diff。
Drawer agent：仅导航呈现与专用测试，先 RED 后实现，保留行为。
验收 agent：背景/日历整合测试与设备方案，禁止并发 Gradle，由主 agent 协调。
每次失败保留证据、定位后再测试，不无依据重复全量。

## 风险、回滚与完成定义
风险：旧分支遗漏功能、背景上文字对比、导航遮挡/大字号裁切、策略白名单冲突、依赖缓存缺失。分别以 Git 保留检查、主题回归、真实布局/设备验证、精确边界和严格校验处理。回滚可在干净工作树撤销最终 Android 提交，保留两次原始交付；不改用户数据。全部验收完成、最终产物摘要/限制记录、索引审查通过后提交才标完成。

状态：实现、完整验证、签名与设备验收完成；最终索引、main提交与本地Android分支删除回执见Git日志和最终答复。

## 过程证据与授权更新
- 初次整合发现实际共同祖先 cde6e1e4，6e5b525 仅为上次 Android 内容恢复来源。逐项审计25项：旧 c2ef074 缺失15项，初步整合25/25通过。
- 用户要求先切上次分支、PC不改不测、完成后删除Android分支。已将初步整合与未提交测试安全保存到具名stash，切回main确认HEAD773c2dc且工作树干净，然后仅重放18项Android/任务路径。没有恢复任何PC增量。初步merge方案及其75项策略结果仅为历史，当前已不用merge恢复例外。
- 切基线前背景/AI聚焦构建成功，2m54s；不作为当前最终源码验收。
- main基线策略：T64 exact文档先1RED，再完整74/74 PASS；无PC新增例外，T52拒绝样本避开T60既有授权main.py而使用database.py。
- Drawer测试先行：API26/36共10项，8RED（品牌/图标/低高度滚动），2项原回调/选中PASS；日志t64-drawer-red.txt和XML t64-drawer-red-results保留。

- Drawer首轮实现50tests/2FAIL：既有窄屏回归用Calendar行右边界代表DrawerSheet，新16dp行内距使几何前提失效，实际Back/swipe尚未执行。保留XML t64-drawer-inset-failure-results；先将Sheet加语义tag，并仅将该断言测量对象改成真实Sheet，保持Sheet满宽、Back、真实swipe、scrim所有断言，后再验证。新增范围仅DrawerStateSynchronizationTest该测量目标。

- Sheet测量修正后导航50/50 GREEN，detekt PASS。ktlint发现新Drawer的4处参数/when纯换行，原日志t64-drawer-green-final.txt保留，不将该组合命令标成功；修正后静态再验，逻辑测试证据已归档t64-drawer-green-results。

- 静态第二轮触及测试文件，发现1长行与if分支格式共5项；局部格式修正不改断言。最终detekt+ktlint 8tasks/35s PASS，日志t64-static-complete.txt；此前两轮格式失败保留。当前完整verify-all进行中，生产与测试源码摘要冻结于t64-final-source-hashes.json。

## 当前同源码完整验证
- main/773c2dc基线上仅Android修改。完整verify-all PASS，exit0；Gradle96tasks（49executed/14cache/33up-to-date）、6m38s BUILD SUCCESSFUL；发布工具111/111（2.855s）、foundation74/74（5.510s）、boundary74/74（3.717s）。日志t64-full-final.txt。
- JVM174suites/1806tests；UI68suites/785tests；failure/error/skip全0，四类泄漏标记0。XML保留t64-full-final-results，汇总t64-final-verification-summary.json。372源码摘要零漂移。背景/主题/设置/Manifest/依赖与773c2dc原样一致，PC源码零diff。
- 当前签名构建进行中，尚未把新包设备验收标完成。

- 正式签名build-release与APK/AAB审计PASS（t64-release-final.txt）。APK1791085bytes，SHA256 f1cce5fb4ded4943d6ab2a0191fe72474323d6623fac087734ce23377b031166；AAB4779234bytes，SHA2564081e66de4a51f64a40f00c623a12801db8efa724880c430eb1817bfb6ff596e。沿用既有独立签名；当前两专用模拟器只安装此包进行新验收，不复用旧包作为最终结果。

## 设备目视范围与限制
- 独立Drawer实现agent只读逐张检查API26/36共18图（各设置100%+明暗月周日+明暗Drawer），应用正文/导航均通过：背景可见，图标分组与选中完整，文字无重叠。主agent另目视API26月和明暗Drawer。
- API36浅色系统状态栏白色图标对比不足。主agent对照上一773交付截图 emulator-5580-accepted-light-calendar.png 同样存在，MainActivity/系统theme未改，作为旧系统栏限制保留，不宣称全屏所有文字无问题。
- 大字号场景证明Drawer真实滚动到达五目的地及周视图可达；AI等目标页只验证到达，不宣称其整页大字号视觉完成。中英双主题低高Drawer文字/触区由自动化回归覆盖；当前设备为英文AOSP模拟器，非厂商真机。

- API26最终f1cce5包首轮交互全部通过，无失败/续跑。25captures+1真实周视图手势PNG/XML，共26组；独立目视PASS，主agent复核00:00及网格。App13/System、系统1.0/自动旋转1/rotation0恢复，合成背景移除，按AVD身份关闭5592并确认disconnected。证据目录t64-device/emulator-5592-final1-f1cce5fb4ded，report.json保留INTERACTIONS_PASS_VISUAL_REVIEW_REQUIRED，visual-review.json记录PASS，close-report.json留关闭证明；code2为预设待目视状态，非测试失败。

- API36同一最终包首轮所有交互完成，25captures+1真实周网格证据，共26组全部逐张目视通过；原系统字号/旋转、App13/System恢复，合成背景移除。证据t64-device/emulator-5582-final1-f1cce5fb4ded，原report保留待目视标记，visual-review.json补充PASS及旧限制。两设备均未重跑旧包或清用户数据。

- API36同样按AVD身份关闭5582并确认disconnected，close-report.json留证。两设备本轮全部交互无失败/续跑；恢复与关闭均完成。最终改动32个精确工程路径，敏感扫描无命中；最终索引门禁与提交回执见Git日志/最终答复。保留初步整合stash作为过程恢复点，不恢复其已放弃的PC/merge策略。
