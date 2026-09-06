# T48 集成发现：手工新建事件入口

## 2026-09-06 最新实施与验证记录

以下为主 agent 本轮已执行结果；后续历史 tests-only 段落保留规划和 RED 前边界，
不再代表当前实施状态。本次更新只修改任务文档，未运行 Gradle 或设备命令。

- 新建入口已进入生产日历/事项顶栏，沿用既有 NewEvent/selectedDate/EventService。
  真实生产保存、取消及新建草稿旋转回归均已通过；未用直接 push route 替代入口验收。
- 编辑旋转与 Widget 旧 Intent 重放的最小生产修复已完成；真实编辑表单/非法草稿
  recreate 保留、明确 Keep/Discard、取消后重载，以及既有 cold/hot 入口回归均绿。
  不增加正文持久化或 SavedState key，不降低原导航与零额外写入断言。
- release 设备 About 实际发现 `Application name: %1$s: Clender`。
  中英文 metadata/不可用 fallback 四方法、API26/36 共 **8 tests** 已在旧实现上 RED，
  行为红灯日志为 `android/.tmp/completion-launcher-about-behavior-red.txt`；
  该组合运行共 92 tests / 22 failures，About 的 8 项失败属于其中；不将整轮写成仅 8 项。
  首次编译失败日志 `android/.tmp/completion-launcher-about-red.txt` 单独保留，
  不将编译失败冒充这 8 项行为断言的 RED。
  修复后 **8/8 GREEN**：AboutScreen 将 metadata 值作为 stringResource 格式参数，
  AboutField 直接展示格式化结果，不再对带占位符的资源手工拼接冒号和值。
  AboutUiModel 保持原 metadata 映射契约；无 metadata reader、版本、依赖或数据协议变更。
- 最新主 agent 全量 JVM 为 **155 suites / 1611 tests，failure/error/skip = 0**，
  包括上述新建、编辑旋转和 About 回归；完整lint、静态、签名、依赖与边界门禁均通过。
  最终5d90签名包在API26/36 phone的真实UI core8已通过，新建/编辑/删除、主题和旋转
  均由实际按钮操作验证；API26实际About字段无占位符。Windows198项、完整EXE及20场景已通过。
  本任务实现与聚焦验收PASS，T48整体仍由主任务完成其余设备及Git交付，不提前宣称整体完成。

About 的自动化 GREEN 不代替修复后签名 release 设备复核；完整发布门禁仍由主 agent
统一记录。此前缺入口、保存按钮定位及旋转失败记录均保留，不改写为未发生。

## 2026-09-06 About 格式占位符展示回归（历史 tests-only 计划）

设备 release About 出现 `Application name: %1$s: Clender`。资源已经包含格式占位符
与本地化冒号，AboutScreen 却无参数读取后再次拼接冒号和值。目标是中英文最终
展示正确 metadata，且无残留占位符或重复冒号；版本不可用只显示有限本地化 fallback。

当前写集仅本任务文档及暂存测试
`android/.tmp/about-format-tests/AboutScreenFormattingRegressionTest.kt`；主 agent 通知
全量 gate 结束并许可搬入后，目标为
`android/app/src/test/java/com/molotov/clender/ui/about/AboutScreenFormattingRegressionTest.kt`。
AboutScreen.kt / AboutUiModel.kt 的后续生产 ownership 归本 agent，但 RED 前不修改。
非目标：改 metadata 契约、包版本、资源/依赖、导航、Room 或任何已冻结测试/helper。

|矩阵（每方法 API26/36）|真实路径与断言|
|---|---|
|英文 metadata|ComponentActivity host 渲染无参生产 AboutScreen，真实 PackageManager 读取；三个字段完整文本匹配英文标签与实际 metadata|
|中文 metadata|同上，zh-rCN 真实资源；中文标签及全角冒号精确匹配|
|英文不可用|test-only ContextWrapper 返回不存在的 packageName，实际 reader 捕获 PackageManager 异常；名称仍正确，版本两个字段仅 Unavailable|
|中文不可用|同上，版本两个字段仅“不可用”|

所有字段同时检查不含 `%1$s`、仅一个标签分隔冒号。期望文本不通过被测格式资源
生成，防止实现与断言共同犯错。ComponentActivity host 用现有确定性销毁 helper，
不启动生产 MainActivity/container，不接网络或读真实数据。不新增 suppress/deps。

风险：这组是生产 About 组件与真实 metadata reader 的展示回归，不代替 release
设备验收。先 tests-only 并由主 agent 留存 RED 后才实施最小修复。回滚仅删除新测试
与本节；完成定义为中英文八 case RED→GREEN 和主 agent 全量门禁/设备复核。
当前状态：暂存准备，不修改 src/test，不运行 Gradle/adb。

2026-09-06 在新建 AOSP API26 空库debug设备上发现日历/事项均无新建按钮；源码全局检索 NewEvent 只有route解析/页面消费，从无生产调用构造该route。既有测试直接push route，未覆盖用户可达性。属于原产品手工CRUD缺陷，当前自主完成授权范围内修复。

目标：日历/事项顶层页面提供本地化、48dp以上新建入口，点击使用当前selectedDate进入既有AppRoute.NewEvent；继续EventService保存，不增数据协议。非目标：新route、自动默认数据、同步/AI/Schema变化。

写集：ui/app/ClenderApp.kt 或专用AppTopBar/Scaffold组件、专用新增ui测试，既有strings screen_event_new可复用；两级AGENTS/progress由主agent统一维护。

测试矩阵：Calendar与Events点击正常进入；选中日期边界与旋转保持草稿；child route/AI/settings/About不显示入口、不叠表单；Light/Dark、8/20sp、横屏/200%字号、≥48dp语义；真实生产点击后填写保存并证明持久化与列表刷新，取消零写入。先在旧生产上证明缺按钮红灯，再实现。

风险：新建按钮不能覆盖dirty编辑器/错误页面导航；使用既有route/draft流程。回滚精确按钮与tests diff。完成定义为聚焦/完整Android+Windows构建+数据检查+文档提交通过。本修复与当前发布接线阶段集成，完整门禁只需在合并后执行，不重复无改动构建。

## 聚焦失败后的最小修复范围（2026-09-06）

实际聚焦 18 tests / 4 failures：两个 API 的保存测试在定位 `event_editor_save` 时失败；两个 API 的 Activity recreate 保留 route/draftId 却丢失 dirty title。XML 与 `.tmp/completion-create-entry-focused.txt` 由主 agent 留证，不删除或弱化旋转断言。

扩写写集：允许 `ui/app/ClenderAppEffects.kt` 在重复同步同一个 NewEvent 时保留现有 EventCrudViewModel 内存表单；优先不修改 EventCrudViewModel 的显式 startNew 语义。专用 `CreateEventEntryProductionTest.kt` 使用 LazyColumn 的 scroll-to-node 操作定位尚未组合的保存按钮，保留真实点击、持久化、列表刷新和取消零写入断言。

测试矩阵补充：旋转同一新建 route 保留日期/标题/draftId 且不写入；真正取消再打开同日入口必须是空的新表单；首次或不同日期 route 仍初始化默认 09:00 表单；真实 LazyColumn 滚动后才能点击保存。接口、SavedState、Room/schema、AI/WebDAV、Widget 安全边界不变。回滚仅撤销本节涉及的精确 effects/test diff。Gradle 由主 agent 串行重验，未验证前不标 PASS。

## 2026-09-06 编辑旋转与旧 Widget 入口重放回归（tests-first）

用户授权本 agent 独占后续最小生产修复的 `ui/app/ClenderAppEffects.kt` 与 `app/MainActivity.kt`，本轮先只追加本节和专用新测试，不修改这两个生产文件，不执行 Gradle。主 agent 串行执行 RED、留证后另行授权修复。

目标：同一 Activity recreate 保留 EventCrudViewModel 的脏 Edit 表单；用户明确取消仍通过既有 openDetail 重新读取已持久化事项；已处理 Widget Intent 不因 recreate 覆盖后来目的地/新建草稿，而真正首次 cold 和后续合法 onNewIntent 继续执行完整校验与导航。编辑重置是既有缺陷；旧 EditEvent Intent 重放也是既有缺陷，本轮 QuickAI 新入口扩大了影响范围，两者均纳入本次完整应用收口。

非目标：新增 route/SavedState key、正文持久化、改变 EventCrudViewModel 显式 startNew/openDetail 语义、改变 Widget Intent envelope/ownership/flags、修改 QuickAiActivity/主题/release helper、修改 Room/schema/service/网络。上述其他发现由主 agent 分配其 ownership。

精确 tests-only 写集：

- `android/app/src/test/java/com/molotov/clender/ui/app/EventEditorRecreationProductionTest.kt`：真实 MainActivity/Compose，隔离 EventService 创建测试事项，实际 Edit/Back/Discard 点击。
- `android/app/src/test/java/com/molotov/clender/app/MainActivityWidgetEntryRecreationTest.kt`：真实 MainActivity controller，合法 owned Widget Edit/QuickAI conversation/QuickAI settings 三类 Intent，recreate 前后的 shell/内存草稿与 cold/hot 导航断言。
- 本任务文档。progress/两级 AGENTS/共享测试 helper 仍由主 agent 统一维护，不修改已有 suites。

|场景|正常/边界|失败与回归断言|
|---|---|---|
|编辑重建|真实事项 Edit，未保存 Unicode 标题/description/time，API26/36 recreate|route 仍 Edit(id)，完整 form/dirty 保留，持久化事件与 mutation version 不变|
|非法草稿重建|非数字 duration 的无效内存表单|recreate 不重置为持久化有效值，validationErrors 保留，零额外写入|
|显式取消|编辑期间测试服务更新已持久化标题；Back→Keep→Back→Discard|Keep 保留草稿；Discard 清 form 并显示最新存储值；再次 Edit 从新持久化值初始化；取消本身零 mutation|
|旧 Widget recreate|Edit、QuickAI conversation、QuickAI settings 各自进入；后来切 EVENTS/NewEvent 并填草稿|recreate 后保留后来目的地、selectedDate、child route、draftId、完整 CRUD 草稿，旧 Intent 不覆盖它们|
|首次 cold|三种合法 Widget Intent，API26/36|Edit 精确 EVENTS+Detail；QuickAI conversation 精确 AI root；settings 精确 SETTINGS+AI section，零事件写入|
|新 onNewIntent|三种入口各自在 recreate 后接收一个合法新 Intent（可与先前 action/data 相同）|仍处理导航并更新 Activity.intent，不用 action/data 相等判断永久吞掉新入口；不叠重复 child route|

资源释放继续使用 ProductionActivityTestResources：Activity close/destroy → main looper drain → container.close → sandbox DB/DataStore 清理。测试正文全部合成，绝不读取 Windows data/dist 或真实秘密。无 sleep/GC/重试/放宽既有断言，异步 Room 加载使用现有有限等待与明确状态条件。

预期 RED：recreate 编辑用例丢失 Edit/form；三类旧 Widget 用例重建后被旧路由覆盖。cold、新 onNewIntent、明确取消是必要对照，防止修复误禁用入口或显式重载。未实际运行前不声明 PASS/RED。回滚只撤销上述两个新增测试及本追加节；最终完成要求主 agent 留存 RED→GREEN、聚焦/完整门禁及既有维护提交证据。
