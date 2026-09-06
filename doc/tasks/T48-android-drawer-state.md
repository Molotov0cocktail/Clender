# T48 Android Drawer 可见状态与应用壳状态同步

状态：2026-09-06 **Drawer 独立任务 PASS**：聚焦 24/0/0/0、最终静态复验 PASS、
API29 实际 Drawer 18 steps PASS。**T47/T48 实现、构建、验收已完成**；Git 交付为最后
步骤，回执以主 agent 最终答复/Git 日志为准，本文不声称已经 commit/push。

## 最终交付验收状态（主 agent 回执）

最终 APK SHA-256：`5d90cbb3d271063ad7b9c3645eb94ede9180bde62c46bde109a09cc0c56a897e`。
全部 8 台设备最终安装/hash 检查通过；API26/36 手机与 API36 平板最终 core 8 steps
及实际 Widget 通过，API36 phone/tablet 最终 Drawer 18 steps 均通过。

增量复用明确保留：API29/31/33 phone core 复用 4cf、Drawer 复用 04；API35 phone
core 复用 4cf，最终 Drawer 18 steps 通过；API33 tablet core 复用 c287，最终 Drawer
18 steps 通过。下方 API29 的 04b4681c 报告是该版本实际执行证据，不改写为最终 5d90 重跑。
date/boot 继续复用 c287 证据，并未在最终包重跑。

API26 Widget 原 ADB 清理临时 XML 失败保留；
`completion-widget-api26-r4-resume-result.json` PASS 为只读确认已保存，随后继续完成
自动更新/冷启动验收，不将原失败覆盖为成功。最终全量 155 suites/1611 tests，UI
57 suites/665 tests，failure/error/skip/leak 全零，全部门禁完成。
本次仅更新 Drawer/P2C 两份任务文档当前状态，源码冻结，不测试、不提交。

## 最新执行结果

主 agent 实际执行：12 方法 × API26/36，**24 tests / 0 failures / 0 errors / 0 skips，
22.191s**（geometry GREEN）；ktlint PASS。
同一命令用时 1m33s，40 tasks（12 executed、28 up-to-date），仅 detekt 报
ClenderApp TooManyFunctions：11 个函数达到 threshold 11。因此整条命令不是 PASS。

遮罩语义关闭、411dp 外露区域真实触摸、360dp Back/swipe 边界、初始打开、dirty Keep/Discard、
新建草稿、recreate、主题/字号、异常设置及虚拟时钟动画取消/重开均通过。动画用例没有
单独失败，未据假设改为 VM 命令或降低断言；recreate 仍仅表示 Activity 重建，非真实旋转。

主 agent 已将 internal rememberSynchronizedDrawerState 逐字移至
`android/app/src/main/java/com/molotov/clender/ui/app/AppDrawerState.kt`，ClenderApp 移除
专用 imports/functions；逻辑与测试未变。迁移后静态复验 **8 tasks / 36s PASS**，
与前述 1m33s 命令的 detekt 失败分别记录，不将失败命令追记为整体 GREEN。
本 agent 冻结源码，仅更新本文档。下方失败、诊断及候选过程均为历史证据，保留不覆盖。

## 最终 API29 Drawer 实机验收

报告 `android/.tmp/completion-release-r3-api29-phone-drawer.txt`：status PASS，
18/18 steps PASS；设备为 `clender_api29_phone`（API29 AOSP emulator）。报告中的
expected_sha256 与 installed_sha256 均为
`04b4681c90d4a51dcf30265fb54938a71d2f8c2381b8fcf33109ee5cd454873d`。

真实输入覆盖连续两轮 scrim 关闭→按钮重开、Back→重开、自然 swipe→重开、当前目的地
点击→重开，以及 Calendar/Settings/About 导航。scrim 点击坐标 (1012,928)，实际外露
区域 [945,63,1080,1794]，Drawer 右边界 945，证明触摸落在遮罩上；相关 r3 目录保留
逐步 before/after XML 与截图。该报告范围仅 Drawer，不包含安装/冷启/清数据/旋转/
设置编辑，不用其替代其他 T48 设备或全量门禁。

## target 候选结果与测试几何纠正

target 候选实际 22 tests / 18 failed，1m15s；Back/swipe 共 4 case PASS，XML 保存于
`android/.tmp/completion-drawer-target-green-results`。诊断 scrim 后 shell/settings=true，
Drawer 条目实际 bounds 为 (0,0,360,56)。本地 Material3 1.2.1 NavigationDrawerTokens
ContainerWidth 为 360dp；本地 ui-test Actions_androidKt.performClickImpl 经
performTouchInput 调用默认 click。因此按 scrim 节点 performClick 仍是中心触摸，
不是调用该节点的语义 OnClick；原 w360 的 x=width-2 也在 Drawer 内。

纠正仅针对测试交互：主类及中文配置改 w411，触摸前断言 root/Drawer bounds 证明点击点
确实位于 Drawer 外；语义路径显式 performSemanticsAction(OnClick) 并断言返回 true。
新增 w360 独立 Back/swipe/语义关闭边界，保持窄屏覆盖。原业务断言未削减，无新增 looper
drain/sleep，临时 println 已移除；共 12 方法、API26/36 共 24 cases，现已实际全部通过。
生产 target 同步逻辑仅原样抽到 rememberSynchronizedDrawerState，避免壳方法超过
detekt LongMethod 60 行；不改变 target 候选行为。由主 agent 统一 GREEN + detekt/ktlint。

## 历史：首轮候选失败与第二版待验（当时非 GREEN）

首轮 GREEN 尝试实际为 22 tests / 20 failed，1m14s，32 tasks（7 executed、25 up-to-date）；
证据 `android/.tmp/completion-drawer-green-first.txt` 及同名 `-results` XML。
仅 swipe-close 两个 API cases 通过；Back 对照从通过变为失败。下方 confirm 方案仅是失败历史。

读取本地 Material3 1.2.1 实际 classes.jar，以 javap 核实：scrim 先调用 confirm(Closed)，
再在自己的 scope launch DrawerState.close；open/close 均走 anchoredDrag。anchoredDrag 的
正常及异常退出路径都会选择距 offset 不超过 0.5px 的锚点，调用 confirmValueChange，
并可能更新 currentValue。故 confirm 不等同于新用户意图。首版 scrim 的回写引起第二个
effect close；Back 原 scope close 与 effect close 也重复。互相取消时若仍在 Open 锚点，
清理回调会再次回写 true，触发重新打开。这解释首版新增竞态，但不能替代原始 scrim
失败的实际 UI/调度证据；禁止将所有仍显示直接认定为同一原因。

第二版写集扩展（主 agent 已授权）至 ClenderAppSettingsNavigation.kt：Back 只提交关闭
意图，不再额外 launch 动画。ClenderApp 移除 confirm 副作用，观察 DrawerState.targetValue
变化同步 flags；观察基准 Closed 不反写首次 Closed，不取消首次 true 请求。请求 effect
仅在当前 Material target 不同于 shell 请求时执行动画，避免对 scrim/gesture 已接受的
同一目标再次启动动画。取消清理不再有应用侧回写 callback。

测试未削减断言，新增临时 test-only flags/bounds 输出以定位原始 scrim 显示失败；未在
生产加入日志，未 sleep/drain/延长等待掩盖问题。第二版源码静态检查后冻结，未运行 Gradle。
先由主 agent 聚焦真实 scrim 与 Back，检查输出及 XML，再决定剩余动画/完整矩阵验证；
若仍失败必须依据观察诊断，不宣称机制或最终修复已获运行证明。

首次 RED：10 方法、API26/36 共 20 tests，18 failed、Back/current-destination 对照 2 PASS；
1m6s，32 tasks（3 executed、29 up-to-date）。证据为
`android/.tmp/completion-drawer-red.txt` 与 `android/.tmp/completion-drawer-red-results`。
XML 中 swipe 实际隐藏已过、shell=false 失败，edge swipe 实际显示已过、shell=true 失败，
这 4 cases 直接证明状态分裂。其余 14 cases 在 scrim 后实际仍显示失败，尚未进入 flags
断言，不能单凭这批 XML 将其全归为 flags；保留真实语义点击及触摸 scrim，交由 GREEN 检验。

候选仅改 ClenderApp：rememberDrawerState.confirmStateChange 用 rememberUpdatedState
取得当前依赖，同步 shell/settings 的目标 drawerOpen 并接受变更；原 LaunchedEffect 继续
执行 shell 请求，同时同步 settings。不观察初始 Closed、不在动画 finally 回写状态，避免
首次打开被取消或已取消动画覆盖新请求；目标变化时原 effect 取消并执行最新 open/close。
dirty requestNavigation/Back/route/SavedState 均不变，无新增 helper。

补测后为 **11 方法、API26/36 共 22 authored cases**，新增手动 Compose clock 推进的
scrim 关闭中重开、打开中取消再重开；无真实 sleep，1,000ms 是虚拟动画推进。
dirty Keep 后第二次 Events 点击前明确断言 Drawer 显示、shell/settings=true 及条目显示。
原断言未削减；新增用例及生产候选尚未运行，不宣称 GREEN。
主 agent 聚焦选择 `*DrawerStateSynchronizationTest`。

## 目标与实际证据

API29 最终 release 包在 Calendar 打开 Drawer，点击系统语义 Close navigation menu
遮罩关闭后，再点击正确 Open navigation drawer 按钮无反应；输入已送达、无 ANR。
保留 `android/.tmp/completion-release-api29-phone-navigation-stalled*` 与
`android/.tmp/completion-release-api29-phone-navigation-final.json`（UI_TIMEOUT）。

修复前源码显示 ClenderApp.AppNavigationShell 仅在 shell.drawerOpen 改变时执行 DrawerState
open/close。scrim/swipe 改变 Material DrawerState，没有回写 shell/settings；shell 仍 true，
再次 openDrawer 写 true 不产生新的 StateFlow 状态，故打开 effect 不再执行。

目标：实际 Drawer 和 shell/settings 内存状态一致；遮罩、手势、Back、当前目的地重复
选择后仍可再次打开，保留 dirty 设置确认、事件草稿保护和 Activity recreate 语义。

## 非目标、接口与精确写集

- 不修改 route、ShellSavedState 编码/持久化、依赖、Room、网络或其他功能。
- 不修改共享 host/helper、既有 suites 或冻结生产文件。
- 本文档由本 agent 编写；progress/AGENTS 与最终发布记录由主 agent 同步。
- 当前只新增本文件与 `android/app/src/test/java/com/molotov/clender/ui/app/DrawerStateSynchronizationTest.kt`
  及同目录专用 `DrawerStateTestHost.kt`（test-only ComponentActivity、真实 ClenderApp/三个
  ViewModel、纯内存 repository/SettingsPort）。无真实 Room/container/网络。
- RED 后拟定生产写集仅 `ui/app/ClenderApp.kt`，必要时在同目录新增专用小 helper；
  不修改 SettingsViewModel 或 AppShellViewModel 的既有导航/确认契约。
- 后续实际授权扩展至 `ClenderAppSettingsNavigation.kt` 去除 Back 重复 close，以及主 agent
  将同步 helper 原样移至 `android/app/src/main/java/com/molotov/clender/ui/app/AppDrawerState.kt`；
  该文件提供 internal rememberSynchronizedDrawerState，不扩展其他功能。

## 同步设计约束

历史初案优先评估 DrawerState confirmStateChange 的用户/程序状态变更回写，使用当前 callback
而非旧 composition 捕获值；只同步 drawerOpen，不执行 destination navigation、
leaveSettings 或 discard。现有 requestNavigation/Back dirty 拦截必须保留。
该初案因取消清理回调竞态已弃用，实际绿色实现使用 targetValue 同步及同目标动画去重。
禁止直接以 snapshotFlow 的初始 Closed 无条件回写 shell=false：首次 shell=true、
DrawerState 初始 Closed 的打开过渡不能被反向取消。同步还必须覆盖手势打开、关闭动画
与重复当前目的地，不能靠禁用 scrim/gesture 或直接调用 shell.closeDrawer 伪造回归通过。

## 测试矩阵（所有方法 API26/36）

|类别|生产交互/边界|必须保持的断言|
|---|---|---|
|正常与真实回归|按钮打开→scrim 语义点击关闭→再次按钮打开，重复两次|实际 drawer 显隐及 shell/settings drawerOpen 同步；目标≥48dp|
|手势|真实触摸 swipe 关闭；edge swipe 打开后 scrim 关闭|不能遗留 true/false 分裂，不直接调用 DrawerState 或 shell close 模拟用户动作|
|Back/重复项|Back 关闭，再打开；重复点击当前 Calendar 项|不退出 Activity、不锁死、不产生子 route|
|初始打开|首次 composition 前 shell.openDrawer|初始 Closed 不得取消 true 打开意图|
|设置 dirty|设置草稿改变→scrim 仅关闭→重开选其他目的地→Keep/Discard|不丢草稿、Keep 不导航，Discard 才执行导航；零保存/网络/事件写入|
|事件 dirty|实际新建入口/编辑标题→Back 确认 Keep|drawer 同步不绕过事件草稿拦截；原草稿及 route 保留|
|重建|ComponentActivity recreate，复用真实 ViewModelStore；打开或scrim关闭后重建|可见状态与内存状态一致且可再次开关；不增加持久化 key；此项不冒称真实旋转|
|动画取消|真实 scrim 关闭中重开，打开请求取消后再重开；手动虚拟时钟|最终显示与两处内存状态保持最新请求，仍能正常关闭重开|
|主题/边界|Light/Dark、8/20sp、中文/英文，设置加载失败的有限错误状态|入口仍可用，异常页不遗留导航锁死|

## 风险、实施步骤、回滚与 Done

1. 先提交专用测试到工作区，由主 agent 跑旧实现 RED 并保存 XML；本 agent 不运行 Gradle。
2. 审核失败是上述状态错位而非手势坐标/动画/测试 host 问题；不 sleep、不扩大超时。
3. 获授权后实施最小双向同步，再统一聚焦、完整 JVM/static/安全门禁及 API29 release 实机复验。
4. 关闭 ComponentActivity 并 drain main looper，让真实 ViewModelStore 释放 scopes；所有假端口
   禁止业务写入，SettingsPort 的取消清理仍可调用。recreate 不创建第二套 ViewModel。

回滚只撤销该任务专用生产/tests/doc diff，不涉及用户数据。Done 要求旧实现真实交互 RED、
修复后矩阵 GREEN、原 dirty/旋转 suites 不退化，且 API29 最终签名包完成同一操作链。
仅测试准备或 JVM 通过都不等同于实机缺陷已验收解决。
