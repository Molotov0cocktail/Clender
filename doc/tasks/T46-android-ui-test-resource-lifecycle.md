# T46-Baseline：稳定 Robolectric/Compose 生产 Activity 测试资源生命周期

## 目标与非目标

本独立 blocker 只修复 `src/test` 中生产 `MainActivity`、Robolectric `ActivityController`、Compose `ActivityScenario`、Activity ViewModel/coroutine、生产 `AppContainer`、Room 与 DataStore 的资源所有权和确定性清理，恢复 Android JVM 76 suites / 654 tests 的连续稳定通过，并令测试 XML/HTML 中 SQLite/Room 泄漏扫描为零。

不实施 T46-C2a，不新增 Settings、主题生产接线、AI secret、model fetch、network gate、WebDAV/C2b/C3、Widget、T47 或 T48；不修改生产 Activity/Application/AppContainer 生命周期、Room/DAO/schema/migration、Manifest、Gradle、catalog、lock、verification metadata 或依赖；不延长 30 秒超时，不使用 sleep/GC/重试/排序/忽略/过滤/降低断言掩盖失败。

## 起始现场与修改前证据

- Branch：`codex/android-architecture`；HEAD：`2a81b1d2774871751384a3485ecef41d1f3366f5`；staged 为空。
- 受保护未提交文件为 `doc/tasks/T46-android-compose-ui.md` 与 `doc/tasks/progress.md`，内容是已冻结的 C2a 设计和 STOP 证据；本任务只追加状态，不回退、覆盖、stash、clean、reset 或 checkout。
- C2a 修改前第一次与第二次完整 `--max-workers 1 --rerun-tasks testDebugUnitTest` 均为 654 tests / 1 failure，固定失败于 `CalendarAppIntegrationTest.productionActivityConnectsQueryNavigationAndReadOnlyEvents[26]` 的既有 30 秒 calendar load；该类单独 API 26/36 通过。
- 本任务第三次修改前同命令为 76 suites / 654 tests、0 failure/error/skip，但 XML/HTML 仍有 14 个结果文件命中 `Explicit termination method 'SQLiteConnectionPool.close' not called`。这证明超时具有跨 suite 累积/时序性，而资源泄漏在绿色运行中仍稳定可见；现有全量与泄漏扫描就是有效红灯，不新增假失败测试。
- 当前泄漏警告由 GC 延迟上报，可落在非分配 owner 的后续 suite；归因依据是分配栈进入 `RoomDatabase.useConnection` / `TriggerBasedInvalidationTracker.createFlow`，以及所有生产 Activity 都经 `MainActivity → ClenderApplication.container → AppContainer.databaseBuilder(clender.db)` 创建同一 sandbox 资源。

## 审计结果、资源所有权与关闭顺序

- 19 个 `createAndroidComposeRule<MainActivity>()` suite 全部启动生产 Activity/Room；只有 `CalendarAppIntegrationTest`、`EventCrudAppIntegrationTest`、`MainActivityConversationIntegrationTest`、`AiComposerQuickAiC1bContractTest` 有部分 teardown，15 个没有显式关闭生产 container。14 个纯组件 suite 会在真实 Activity 已启动后 `setContent` 覆盖生产页面；覆盖 content 不会清除 Activity ViewModel 或关闭 Room。
- AI 5 个 + Calendar 4 个 suite 中仅 `AiComposerQuickAiC1bContractTest` 有部分 teardown；Event 5 个 suite 均缺生产资源 teardown（`EventDateTimePickerTest` 的既有 `@After` 只恢复 timezone）；App 4 个 suite 中 2 个部分 teardown、2 个无 teardown；`MainActivityConversationIntegrationTest` 为部分 teardown。
- `MainActivityShellTest` 还创建第二个 `Robolectric.buildActivity(MainActivity)` controller，当前断言后没有 `finally` 的 pause/stop/destroy；主 Compose scenario 与额外 controller 共享同一 `ClenderApplication`/container。
- `MainActivityRobolectricTest` 正常路径会销毁 controller，但不是 `finally`，且从未关闭生产 container。
- 六个显式 `Room.databaseBuilder` / `inMemoryDatabaseBuilder` suite 均有名义 close 对称；`EventCrudIntegrationTest` 的自建 in-memory DB 保持独立，不并入生产 Activity helper。
- 共享 helper 的固定顺序：先捕获当前 `ClenderApplication`；调用方先在 `finally` 关闭额外 controller；关闭/销毁主 Activity scenario；drain Compose/main looper，使 ViewModel `onCleared` 与 Activity coroutine cancellation 生效；幂等关闭 `AppContainer`；最后删除该 Robolectric sandbox 的 `clender.db` 与 `filesDir/datastore`。每一步即使前一步失败仍继续，并聚合 cleanup 异常，JUnit 仍保留原测试失败。

## 预计修改文件与 Agent 边界

- 新增共享 test-support helper（`android/app/src/test/java/com/molotov/clender/testsupport/**`）。
- Agent A 只修改 `ui/ai/**`、`ui/calendar/**` 中启动 `MainActivity` 的测试。
- Agent B 只修改 `ui/event/**` 中启动 `MainActivity` 的测试。
- 主 Agent 独占共享 helper、`ui/app/**`、`app/**` 测试、本文档、`T46-android-compose-ui.md`、`progress.md`、根 `AGENTS.md` 与 Git。
- 不修改生产源码、资源、Gradle/依赖/Manifest/Room 文件；若首选 helper 无法消除泄漏，先在本文档记录证据，再评估仅使用现有依赖的 test-only host，且不得改变覆盖矩阵。

## 实施步骤

1. 固化现场、两次 653/654、第三次 654/654 但泄漏、19/15 审计、所有权和测试矩阵。
2. 主 Agent 新增共享确定性 helper，并先迁移一个代表 suite 验证类型和关闭顺序。
3. Agent A/B 按互斥目录迁移其 suite；主 Agent 迁移 app/ui-app suite并修复额外 controller 的 `finally`。
4. 运行 Calendar API 26/36、泄漏高风险组合两轮、C1a/C1b/UI 聚焦与报告扫描。
5. 完整 JVM 连续三次真实 rerun，每次 654/654 且泄漏扫描为零；随后执行完整 Android、generated/boundary、Windows/PyInstaller/exe/`dist/data` 门禁。
6. 更新任务/T46/progress/根 AGENTS，审查 diff/秘密/生成物/staged，设置 Git 代理，创建指定提交后立即 STOP。

## 测试矩阵

| 路径 | 正常 | 边界 | 异常/非法 | 回归 |
|---|---|---|---|---|
| 生产 Compose Activity | Calendar API 26/36；scenario 销毁、looper drain、container close、sandbox 清理 | setContent 覆盖与不覆盖；Activity recreate；重复幂等 close | 测试断言失败时 teardown 仍执行；cleanup 步骤失败不跳过后续且不掩盖原失败 | 19 suite 全部继续执行，断言/30 秒阈值/test count 不变 |
| 直接 ActivityController | 正常 resume 后 finally pause/stop/destroy | 主 scenario + 第二 controller 共享 application/container | controller 断言异常仍销毁 | `MainActivityShellTest` 与 `MainActivityRobolectricTest` API 26/36 |
| Room/DataStore | production Room/Flow 关闭后删 sandbox DB/DataStore | lazy 未初始化、只初始化 Room、初始化 DataStore/AI 的不同路径 | close/delete 幂等；无真实数据路径 | 显式 in-memory Room suite 保持原 close；C1a/C1b 全绿 |
| 套件稳定性 | 高风险六 suite 组合连续两轮 | 完整 suite 连续三轮、每轮真实 rerun/单 worker | 四类泄漏文本均为 0 | 654/654、ui 366/366、0 failure/error/skip |
| 发布门禁 | Android strict/offline、generated/boundary、Windows 171、导入/check/build/exe | API 26/36、debug/release lint、无 native/Google、85 universe | 签名/锁/边界负向门禁不降级 | 五个冻结依赖 SHA-256、版本与 `dist/data` 路径/数量/大小/UTC/逐文件哈希不变 |

## 风险、回滚、完成定义与 STOP

主要风险是 JUnit rule 与 `@After` 的关闭时序、Activity recreate 后捕获错误实例、异步 Room invalidation/coroutine 尚未取消即删文件、cleanup 异常掩盖原断言，以及把 GC 延迟警告误归因到报告所在 suite。

回滚为删除共享 helper并精确还原本任务修改的测试 import/teardown/finally；保留本文档、C2a 冻结设计与完整失败证据。不得触碰用户两份既有文档内容之外的追加段、生产代码或真实数据。

完成定义：Calendar API 26/36 通过；高风险组合连续两轮通过；完整 JVM 连续三次均 654/654、0 failure/error/skip、四类泄漏扫描 0；UI 366/366 与 C1a/C1b 聚焦全绿；完整 Android strict/offline、generated/boundary、冻结依赖、Windows/PyInstaller/exe/`dist/data` 门禁全部通过；文档和根 AGENTS 更新；提交 `Stabilize Android UI test resource lifecycle` 后不 push 并立即 STOP。完成只把状态恢复为“C2a 可重新启动”，C2a 仍未实现。

若连续三次全量仍有失败/CloseGuard，或只能靠超时/重试/排序/降低断言，或需要生产行为、依赖、Gradle、Manifest、Room/schema 修改，或 test count 下降、C1a/C1b/B1/B2 回归、任一 Android/Windows/PyInstaller/exe/`dist/data` 门禁失败，立即 STOP-ESCALATE；撤回试验性测试代码，只保留任务记录与证据，不提交半成品。

## 执行记录

- [x] 现场、规则、保护文件、生产装配、MainActivity/Room 测试入口与既有报告已审计。
- [x] 两名独立子 Agent 完成第一阶段只读审计，未修改文件；主 Agent 已交叉复核 19/15、额外 controller、显式 Room close 与 GC 延迟归因。
- [x] 第三次修改前全量：`BUILD SUCCESSFUL in 2m 7s`，76 suites / 654 tests 全绿，但 14 个 XML/HTML 结果文件仍含 SQLiteConnectionPool 未关闭证据。
- [x] 首选共享 helper 已迁移所有生产 Activity owner；AI/Calendar 115/115、Event 48/48 聚焦绿色且对应报告泄漏扫描为零。主 Agent 52-test app/container 组合仍在 `CalendarAppIntegrationTest[26]` 复现 30 秒 LOADING 超时，但四类 SQLite/Room 泄漏已为零。
- [x] 诊断拆分：`MainActivityConversationIntegrationTest + CalendarAppIntegrationTest` 单独通过，`MainActivityRobolectricTest + CalendarAppIntegrationTest` 单独通过，两者累计在 Calendar 前运行时稳定失败；证明完整清理已消除 CloseGuard，但重复生产 Activity/Room 冷启动仍造成 API 26 累积时序。按预先冻结的 fallback，纯组件 suite 改用现有依赖的 test-only `ComponentActivity` host；真实生产集成 suite、测试语义/数量/API 矩阵保留，生产 Manifest/行为/依赖不变。
- [x] test-only host 与剩余生产 integration helper 收敛：13 个纯组件 suite 使用 `createEmptyComposeRule + RobolectricComposeHost(ComponentActivity)`，6 个生产集成 suite 继续启动真实 `MainActivity` 并统一走 `ProductionActivityTestResources`；额外 controller 和直接 container 均在 `finally`/`@After` 关闭。曾尝试 test Manifest Activity，但 Robolectric binary resource/PackageManager 不兼容，已完整撤回且未修改生产 Manifest。
- [x] 聚焦/组合/连续三轮全量与零泄漏扫描：Calendar API 26/36 绿色；高风险 6-suite 组合连续两轮通过；`ui.*` 37 suites / 366 tests、C1a/C1b 12 suites / 168 tests 全绿；完整 JVM 三次真实 rerun 分别在 1m58s、2m06s、2m03s 完成，均为 76 suites / 654 tests、0 failure/error/skip。最终 XML/HTML 对规定的 SQLiteConnectionPool termination、leaked SQLiteConnection/Pool、Room database not closed 文本扫描全部为 0。原 30 秒阈值未改变；只在既有等待循环中显式 drain Robolectric main looper。
- [x] Android/Windows/PyInstaller/exe/`dist/data` 完整门禁：strict/offline 96 tasks 全绿，包含 test/lintDebug/lintRelease/detekt/ktlint/签名/无 Google/无 native/85 configuration lock/assemble/APK no-native；generated 与 boundary 均 44/44。第一次 boundary 未传 Python 参数而 fail-closed，按脚本契约补 `-PythonExecutable C:\Users\30910\Miniconda3\python.exe` 后通过。五个冻结依赖文件与 HEAD 字节一致，SHA-256 分别为 `238BDDE9...5779`、`C34C3713...2B22`、`3B0DD0B1...92CE`、`025C07DD...ED5`、`1D0DAEFE...24D`；Runtime 1.11.3、Lifecycle 2.9.4、SavedState 1.3.2、UI 1.6.8 零漂移。
- [x] Windows 171/171、全模块导入、`build.py --check` 与完整 PyInstaller 通过；最终 `dist/Clender.exe` 45,582,816 bytes，SHA-256 `C68FB1F8281A9C79DF18296B0160F8D4FF638F62258C004C793968D852650F7C`。normal primary + silent secondary、silent primary + normal secondary 均为 primary 存活、隔离 DB 创建、secondary exit 0；最终 0 Clender 进程/0 冒烟目录。
- [x] `dist/data` 构建前后均为 5 文件、195,620 bytes，相对路径/大小/UTC/逐文件 SHA-256 聚合 `E43733909427A268005A9514FB136CDA0F74C43395046269E73EF20E539668D9`，完全一致；只读取 metadata 与哈希，未打开、解析、复制或修改正文。
- [x] 文档与最终 diff/生成物/秘密审查完成；按指定信息创建单一提交后立即 STOP，提交哈希只在最终报告给出且不 push。
