# T47-Baseline5：CalendarOverflow teardown 生命周期稳定性与 Baseline4 最终收口

## 状态、目标与非目标

**状态：已完成（2026-09-03，无代码修复发布收口）。** `CalendarOverflowDetailPlaceholderTest` 的 teardown 历史失败经三层诊断均未复现，裁决为 `NON-REPRODUCIBLE`；不修改失败类、共享 helper 或生产代码。保留 Baseline4 已验证的 Room 冷 Flow 首次 emission 握手、Windows frozen single-instance smoke harness 与两个 exact-path policy 例外，并合并为 checkpoint `Stabilize cross-platform test infrastructure`，不 push。

非目标：不实现 P2B2b、P2C、Worker/Boot、自动触发或 T48；不修改任何 Android/Windows 生产代码、构建文件、依赖、Manifest、provider-info、资源、Room/DAO/schema/repository/EventService/AppContainer/MainActivity/ClenderApplication；不使用 sleep、GC、测试排序、失败重试、过滤、忽略 cleanup 异常或延长超时。

## RECOVERY、白名单与冻结现场

- 指定恢复点：branch `codex/android-architecture`；HEAD `c7403a1bc265f46a55470e34ab912473dfc70e97`；staged empty；现有未提交成果完整保留，禁止 reset、checkout、clean、stash 或自动回滚。
- 保留且无证据不得改写：`EventCrudIntegrationTest.kt`、`scripts/verify_frozen_single_instance.py`、`tests/test_frozen_single_instance_smoke.py`、`android/tests/policy/test_ignore_and_boundaries.py` 与既有五份 T47 STOP/汇总文档。
- 本轮实现白名单：`CalendarOverflowDetailPlaceholderTest.kt`、`ProductionActivityTestResources.kt`；只有共享 helper 需要回归时才允许新增/修改 `ProductionActivityTestResourcesTest.kt`。文档白名单为本文件、`T47-baseline-stability-closeout.md`、`progress.md`，全部成功后才更新 Baseline2/Baseline3/P2B2b/T47 汇总及两级 `AGENTS.md`。
- Android/Windows 生产代码、六冻结构建文件、Manifest/provider-info、依赖图与用户数据保持零漂移；需要生产生命周期修改时立即 STOP-ESCALATE。

## 诊断矩阵与测试用例

| 层级 | 正常路径 | 边界值 | 非法/异常路径 | 回归信号 |
|---|---|---|---|---|
| 单类捕获 | `CalendarOverflowDetailPlaceholderTest` 独立运行一次 | Compose/JUnit rule teardown 顺序 | cleanup 主异常、全部 suppressed、失败 step 必须从 XML 记录 | 复现即停止后续测试；通过才进入组合 |
| 高风险组合 | 所有使用 `ProductionActivityTestResources` 的生产 Activity suite 一次 | scenario/controller 与 helper 两种调用形状 | Activity 已销毁、looper/container/DB/DataStore 任一步失败 | 复现即停止并先固证 XML；通过才进入全量 |
| 完整 JVM | `testDebugUnitTest` 一次 | 至少 124 suites/1263 tests、UI 52/607、OkHttp 16/16 | 0 failure/error/skip，四类 CloseGuard 0 | 三层均不复现则不改代码并 STOP-ESCALATE |
| 类局部修复 | 有效生命周期阶段捕获 application/scenario | helper 恰好调用一次 | 禁止 catch 忽略、null-safe 跳过、重复 close | 只改失败类 |
| 共享 helper 修复 | 按 scenario→looper→container→DB→DataStore 执行全部 step | 多个 cleanup failure 聚合 | 原始失败与所有 cleanup failure 同时保留 | 先新增旧 helper 稳定失败的 tests-only 回归 |
| 稳定性 | 单类 10 次、组合 5 次、完整 JVM 3 次 | 每次独立 invocation、无 retry | 任一失败立即 STOP | 数量不得下降，新增测试如实上调 |
| 跨平台发布 | Flow API26/36、smoke 27/27、Windows 198/198、imports 30/30、build check；Android 96-task gate | generated/boundary/policy 44/44、85 universe | frozen/Manifest/APK/native/Google/Glance/QuickAi/Boot/Worker 零漂移 | PyInstaller + 唯一正式 EXE 20/20；`dist/data` 前后一致 |

固定诊断顺序仅执行一次单类、一次高风险组合、一次完整 JVM；任一层复现后，在运行任何其他 Gradle 测试前复制并记录 XML 的主异常、全部 suppressed、失败 cleanup step 与 ActivityScenario/main looper/container/database/DataStore 状态。三层均无法复现则判定 `NON-REPRODUCIBLE` 并 STOP-ESCALATE，不修改代码。

## Agent 分工

- Agent A（read-only failure analysis）：审计失败类、共享 helper、Compose/JUnit before/after 顺序及所有 helper 调用形状；禁止修改。
- Agent B（reproduction/test-only）：设计固定诊断命令与 tests-only 回归；只在主 Agent取得失败证据并裁决共享 helper 缺陷后才可写 helper 回归，禁止修改 helper 实现或生产代码。
- Agent C（cleanup reviewer）：只读审计 suppressed exception、scenario/container/looper/DB/DataStore 所有权与关闭顺序；实现后复核不得吞掉或覆盖 cleanup failure。
- 主 Agent：RECOVERY、证据捕获、红灯裁决、最小测试修复、diff 审查、完整门禁、文档/AGENTS 与唯一提交。

## 实施步骤、STOP、回滚与完成定义

1. 固证 branch/HEAD/status、三个保留实现 SHA、两个 exact-path 例外、生产/冻结边界零 diff；完成本文与进度同步。
2. 启动三个互斥 Agent，只读审计与测试矩阵设计；主 Agent按冻结三层顺序捕获首个失败 XML。
3. 证据仅允许裁决为类局部所有权或共享 helper 顺序缺陷；后者必须先写旧 helper 稳定失败的回归。无法安全归类或涉及生产生命周期即 STOP。
4. 最小测试侧修复后执行单类、组合、10/5 稳定矩阵、三轮完整 JVM、Flow/frozen smoke/Windows/policy/generated/boundary/85 与 Android 96-task发布门禁。
5. Android 全绿后只读快照 `dist/data`，运行 Windows 198/198、imports、check、完整 PyInstaller 与新 EXE 唯一 10-cycle/20-scene smoke，核对无进程/endpoint/临时目录且数据逐文件一致。
6. 更新相关任务、两级 AGENTS，审查白名单/秘密/二进制/生成物/用户数据、`git diff --check` 与 staged diff；配置指定 HTTPS proxy，只提交授权工程文件，不 push。

任一门禁失败立即 STOP-ESCALATE：保持 staged empty，保留已通过自身矩阵的 Flow、smoke、policy 与本轮生命周期修复；不自动回滚。只有证据证明某项改动自身错误时才用逐行补丁撤回。完成定义为全部矩阵及发布门禁全绿、冻结边界和 `dist/data` 零漂移、文档/AGENTS 完成态、唯一 checkpoint 已创建、working tree clean、staged empty、未 push。

## 执行记录

- 2026-09-02：RECOVERY 确认 branch/HEAD/staged 与指定值一致；现有未提交 Flow、smoke、policy 和文档现场已保留。发现 Flow 文件实际位于 `android/app/src/test/java/com/molotov/clender/ui/event/EventCrudIntegrationTest.kt`，将按该正确路径复核指定 SHA。

## NON-REPRODUCIBLE / STOP-ESCALATE（2026-09-03）

- RECOVERY 完成：branch `codex/android-architecture`、HEAD `c7403a1bc265f46a55470e34ab912473dfc70e97`、staged empty。三个保留实现 SHA-256 精确匹配：Flow `44FFF6CC4A36EB4E45ED201E4A302C8EC40B0A0BAC0F7C24E3A68902A0CAC879`、harness `E0F82307FC519E69E33B53778F1BD8F6D6CBF3D51B524F4B4FF31906E3C2D1B9`、smoke tests `7245571FEDCB796043277EF380C15981AE9AF95E51F26BEA1393D9E54B6101C5`；policy 仍只增加两个完整 exact-path 例外。
- 固定三层诊断矩阵按顺序各执行一次：失败类独立 invocation `BUILD SUCCESSFUL`；13 个 `ProductionActivityTestResources.close` 调用 suite 的高风险组合 `BUILD SUCCESSFUL`；完整 strict/offline、single-worker、`--rerun-tasks testDebugUnitTest` `BUILD SUCCESSFUL`。第三层为 124 suites/1263 tests、UI 52/607、OkHttp 16/16、0 failure/error/skip，CloseGuard/SQLiteConnectionPool/SQLiteDatabase leaked/RoomDatabase leaked 四类扫描均为 0。
- 三 Agent 只读审计一致：JUnit/Compose rule 正常顺序为 scenario before→test→`@After`→scenario after；共享 helper 已按 scenario/controller→main looper→container→database→DataStore 执行全部顶层 step，并把 cleanup failure 依次保留为 suppressed。失败类在 `@After` 进入 helper 前动态读取 `composeRule.activity` 是潜在类局部风险，但旧记录只有调用行号，没有异常类型、suppressed 或失败 step，当前三层又均未复现，证据不足以安全裁决或修改。
- 依预先冻结规则判定 `NON-REPRODUCIBLE` 并立即 STOP-ESCALATE：未修改 `CalendarOverflowDetailPlaceholderTest.kt`、`ProductionActivityTestResources.kt` 或任何生产代码；未执行修复后 10/5/3 稳定矩阵、Flow/Windows复验、Android 96-task 发布门禁、PyInstaller、正式 EXE smoke、AGENTS 完成态或提交；未配置 Git proxy、未暂存、未 push。
- 现场保留：Baseline4 已通过自身矩阵的 Flow/harness/policy 改动继续原地保留；本轮只新增/更新任务文档，staged empty。下一步需要用户明确裁决是否允许基于静态生命周期风险实施预防性类局部 test-only 修复，或等待可保留完整 XML/suppressed 的再次自然复现。

## Baseline5R 无代码最终发布裁决（2026-09-03）

- 用户裁决三层诊断均通过已足以解除当前诊断阻塞；旧失败没有可用的主异常、cause、suppressed exception 或实际 cleanup step，不能证明 `CalendarOverflowDetailPlaceholderTest` 的类局部缺陷，也不能证明 `ProductionActivityTestResources` 的共享 helper 缺陷。因此明确拒绝任何预防性测试修改，两文件继续相对 HEAD 零 diff，不等待无限期自然复现。
- 允许在不执行额外 CalendarOverflow 循环测试的前提下直接恢复一次最终完整发布门禁。Baseline5 结论保持 `NON-REPRODUCIBLE`，本轮目标是以无代码修复方式完成 Baseline4/Baseline4R/Baseline5 的发布收口。
- 若唯一完整 Android 门禁中的 `testDebugUnitTest` 或后续任务失败，必须立即停止且不得执行任何其他 Gradle 命令；在报告被覆盖前读取对应 XML 与 HTML，记录 testcase 全名、exception class/message、完整 cause chain、全部 suppressed exception、实际失败 cleanup step、XML SHA-256 与该测试前最后完成的 suite。生成报告不得加入 Git，staged 保持 empty，并以 STOP-ESCALATE 结束；本任务不修复新失败。

## Baseline5R2 命令调用分类裁决（2026-09-03）

- 后续 `_support` 导入失败是错误 cwd 导致的测试发现前调用错误：0 tests/tasks executed、无工程或外部状态变化，故为 `PRE_EXECUTION_INVOCATION_ERROR`，不属于 `TEST_GATE_FAILURE`。
- `TEST_GATE_FAILURE` 要求至少一个 test/task 已实际开始并发生 assertion、编译、lint、构建或运行时失败，发生即 STOP 且不得重试；0-execution 的 cwd、参数、脚本定位或启动前 sandbox 拒绝可在固证后对必要命令精确纠正一次，冗余命令不补跑。
- 完整 policy 44/44 已在当前未变更 diff 上覆盖 configuration universe 断言，禁止重跑该冗余单方法；直接进入唯一最终 Android 聚合门禁。Baseline5 继续判定 `NON-REPRODUCIBLE`，CalendarOverflow/helper 保持零修改。

## Baseline5R2 最终裁决（2026-09-03）

- 上述历史 STOP 已由用户的 Baseline5R/5R2 裁决解除。唯一最终 Android 聚合门禁在一次 0-task wrapper-lock `PRE_EXECUTION_INVOCATION_ERROR` 经精确权限纠正后通过：96/96 tasks，124 suites/1263 tests、UI 52/607、OkHttp 16/16、0 failure/error/skip、四类 CloseGuard/SQLite/Room 0。
- `CalendarOverflowDetailPlaceholderTest.kt` 与 `ProductionActivityTestResources.kt` 相对 HEAD 继续零 diff；旧失败保持 `NON-REPRODUCIBLE`，没有预防性测试或生产修改。Windows 198/198、imports 30/30、check/build、新 EXE 20/20 与 `dist/data` 一致性通过，最终 policy 44/44 后完成发布收口。
