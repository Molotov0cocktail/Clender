# T47-Baseline：Android AI client 请求所有权稳定化

## 目标

修复 `OkHttpAiClient` 在 Thinking 400/422 fallback 边界的生产并发竞态：每个 OkHttp `Call` 必须在恢复其 coroutine continuation 之前完成响应读取/受限分类、响应关闭、取消状态清理和 `inFlight` 条件释放。首个 Thinking 请求完成 terminal release 后，才允许同一次 `complete()` 的无 Thinking fallback 发起第二个请求。

本任务是 T47-P0 的独立 blocker 修复 checkpoint。完成并提交后立即 STOP。

## 非目标与冻结边界

- 不重启 T47-P0 的 Glance/WorkManager 依赖解析，不修改任何 dependency、lock、verification metadata、Manifest 或配置宇宙。
- 不实现 Widget、Glance、WorkManager、Receiver、Activity、Worker、Quick AI 或 T48。
- 不修改 `AiCoordinator`、gate、gateway、ViewModel、UI、Room、DAO、schema、协议、API Key、endpoint、HTTP payload 或错误公开契约。
- 不放宽 single in-flight；真正仍在途的独立请求仍必须被 `Another AI request is already active` 拒绝。
- 不改 Windows/Python 生产代码；只按根门禁执行其验证。

## 已有失败证据与时序根因

回退 T47-P0 依赖试验后，Android 完整 JVM 基线出现唯一既有失败：

`OkHttpAiClientTest.thinking400Or422FallsBackExactlyOnceWithoutExtensions` 抛出 `AiClientException: Another AI request is already active`。

现有 `execute()` 以 `AtomicReference<Call?> inFlight` 保持单请求。`onResponse` 读取并关闭响应后，在 `catch` 中先 `resumeWithException(AiHttpException(400/422))`，而 `inFlight` 与 `cancellationRequested` 要到随后的 `finally` 才清理。可立即恢复的 continuation 会立刻进入 `complete()` 的 fallback `executeChat(..., includeThinking = false)`，第二个 Call 因仍观察到首个 Call 而被拒绝。该问题是生产 callback 顺序竞态，不是测试排序或 MockWebServer 噪声。

## Call 所有权状态机

每个成功安装到 `inFlight` 的 Call 都有唯一 terminal owner；迟到 callback 只能释放自己仍拥有的引用，不得清除后来 Call 的所有权。

```text
CREATED
  -> OWNED (inFlight == current)
  -> TERMINAL CLASSIFIED (response fully read or bounded failure; Response closed)
  -> RELEASED (cancellationRequested.compareAndSet(current, null),
               inFlight.compareAndSet(current, null))
  -> CONTINUATION RESUMED / IGNORED IF CANCELLED
```

- `cancelInFlight()` 和 continuation cancellation 只对当前 `inFlight` Call 设置 `cancellationRequested` 并调用 `Call.cancel()`。
- 当前 Call 的所有 terminal path（success、HTTP、protocol、oversize、timeout、network/disconnect、cancel）都必须先完成 release，再 resume 或 `resumeWithException`。
- release 必须幂等且使用 expected-value CAS；不得无条件覆盖 `inFlight`，不得移除 guard、排队或让 fallback 使用另一 client。
- 400/422 仅允许第一次 Thinking 请求触发一次 fallback；fallback 自身任何失败均不再 fallback。取消、后台取消和 coroutine 已取消不得触发 fallback。

## 影响文件

允许修改的生产文件：

- `android/app/src/main/java/com/molotov/clender/data/network/ai/OkHttpAiClient.kt`

允许修改的测试文件：

- `android/app/src/test/java/com/molotov/clender/data/network/ai/OkHttpAiClientTest.kt`

本任务文档、`doc/tasks/T47-android-widget.md`、`doc/tasks/progress.md` 和根 `AGENTS.md` 可补充证据与维护记录。其余文件冻结。

## TEST-FIRST 测试矩阵

先新增一个确定性回归测试 `thinkingFallbackReleasesFinishedCallBeforeInlineContinuationRetries`，使用可立即恢复 continuation 的受控 dispatcher（优先 `Dispatchers.Unconfined` 或等价确定性调度），不使用 sleep、随机循环、测试重试、反射清理、额外 production hook 或放宽断言。旧实现必须稳定因 active-request 错误红灯；若不能稳定红灯，先增加确定性调度屏障，不进入修复。

| 场景 | 断言 |
|---|---|
| Thinking 400 | 首次请求含 `reasoning_effort`/`extra_body.thinking`；fallback 仅一次且不含 Thinking 扩展；总请求数为 2；成功返回；caller key 全部归零 |
| Thinking 422 | 与 400 相同；两个状态各自精确两次请求 |
| 401/429/500 | 零 fallback；请求数各为 1；受限 HTTP 错误与 key 擦除保持 |
| fallback 返回 500 | 仅 2 次请求，不进行第二次 fallback；key 擦除保持 |
| fallback 边界取消 | 只发首个请求，不重试；取消异常与 key 擦除保持 |
| response-body stall / 真并发 | 首个 Call 仍在途时第二请求继续被拒绝；取消后状态可复用 |
| success、HTTP、protocol、oversize、timeout、disconnect | 每个 terminal path release 后，下一请求可立即开始；映射和错误边界不变 |
| `cancelInFlight()` | 只取消当前 Call；完成后可发新请求；不触发 fallback |
| request/安全契约 | payload、requestCount、无 redirect/retry、无 URL/Authorization/Key/prompt/全文泄露不变 |

预期聚焦 suite 为 `OkHttpAiClientTest` 16/16（当前基线已有 15 个 `@Test`，本任务新增 1 个）。

## 实施步骤

1. 记录初始 branch/HEAD、三份受保护 STOP 文档差异、冻结文件哈希和 Android/Windows 基线。
2. 只在现有 client test 增加确定性 regression，运行该测试证明旧实现稳定红灯，并记录失败类型/堆栈。已完成：`Dispatchers.Unconfined` 聚焦命令在旧实现稳定失败，失败为 `AiClientException: Another AI request is already active`，堆栈为 `OkHttpAiClient.execute(OkHttpAiClient.kt:138) → executeChat(OkHttpAiClient.kt:123) → complete(OkHttpAiClient.kt:100) → DispatchedTaskKt.resumeUnconfined`。
3. 在 `OkHttpAiClient` 集中 terminal outcome/release 顺序；保持所有公开接口、异常类型/映射、请求 payload 和 single in-flight 语义。
4. 运行 `OkHttpAiClientTest` 聚焦测试及 AI network/app/coordinator 相关测试；失败立即停下排查，不以重复运行掩盖竞态。
5. 按冻结顺序运行 Android 三轮严格离线 JVM、完整静态/构建门禁，再执行 Windows、PyInstaller、EXE 双向隔离启动和 `dist/data` 只读摘要核对。
6. 审查 staged diff、敏感模式、生成物和冻结文件；更新本任务、T47 task、progress、AGENTS；提交单一 checkpoint，不 push，提交后立即 STOP。

## 回滚方式

仅在未提交前用 `apply_patch` 反向删除本任务新增的测试/生产改动并保留既有三份 STOP 文档；不得使用 `reset`、`checkout`、`stash`、`clean` 或覆盖用户数据。提交后如需回滚，应由后续明确任务创建反向提交，不改变 T47-P0 STOP 证据。

## 风险与控制

- callback 可能在任意线程运行：terminal helper 必须对 response close、CAS release 和 continuation 恢复顺序提供单一实现。
- continuation 可能在恢复时立即进入 fallback：释放必须发生在任何 resume 之前。
- cancellation callback 与 response/failure callback 可能交错：保留 expected-value CAS 和当前 Call 绑定，避免清除后来请求。
- 认证值仍须只在 `withWipedKey` 内构造并按既有路径擦除；本任务不增加日志或测试秘密。
- Android dependency graph、Manifest、Room 和 Widget 边界冻结；任何越界改动均不提交并 STOP。

## 完成定义

- [x] 新增 deterministic regression 在旧实现稳定红灯、修复后通过；`OkHttpAiClientTest` 为 16/16。
- [x] 400/422 fallback 精确一次，首个 Call release 先于 fallback；非 fallback 错误和取消语义不变。
- [x] in-flight guard、cancel ownership、超时/网络/协议/oversize 映射和 API Key 全路径擦除通过聚焦矩阵。
- [x] Android 三轮完整 JVM 为 99 suites/980 tests、0 failure/error/skip，CloseGuard 四类为 0，`ui.*` 50/567，generated/boundary 各 44/44。
- [x] 完整 Android 门禁、Windows 171/171、30 模块导入、build check、完整 PyInstaller、EXE 双向隔离冒烟和 `dist/data` 摘要均通过。
- [x] 依赖/Manifest/Room/Widget/T48 未变；三份 T47-P0 STOP 文档证据保留；已创建提交且未 push，完成后立即 STOP。

## 后续决策明确不属于本任务

T47-P0 的依赖兼容性仍未完成。`tracing 1.2.0`、`tracing-ktx`、DataStore Android variant 漂移尚未获授权；Glance/WorkManager/Widget、T47-A/B/C 和 T48 均未开始。

## 完成证据（2026-09-01）

- test-first 红灯：仅新增 `thinkingFallbackReleasesFinishedCallBeforeInlineContinuationRetries`，旧实现以 `AiClientException: Another AI request is already active` 稳定失败；修复后 Thinking 400/422 各精确两次请求、首请求含 Thinking 扩展、fallback 不含扩展、返回成功且 key 全部归零。
- 生产修复：`responseOutcome` 先完成受限 body 读取和 `Response.use` 关闭；`completeCall` 在任意 continuation resume 前执行 cancellation/in-flight expected-value CAS release，并在 release 后决定取消/成功/异常结果。
- 聚焦与稳定性：`OkHttpAiClientTest` 16/16；AI/network/app 聚焦 9 suites/70 tests；完整 `testDebugUnitTest --rerun-tasks` 连续三轮均为 99 suites/980 tests、0 failure/error/skip；CloseGuard 四类均为 0。
- Android 发布门禁：完整 strict/offline 单 worker 命令（test、lintDebug/lintRelease、detekt、ktlint、签名、锁定版本、无 Google/native、assembleDebug、APK native 审计）BUILD SUCCESSFUL；`verify-generated.ps1` 与 `verify-boundaries.ps1` 均为 44/44。
- Windows 发布门禁：标准库 unittest 171/171；30 模块导入、`build.py --check`、完整 PyInstaller、normal/silent 双向隔离 EXE 启动均通过。构建前后 `dist/data` 均为 5 文件/195,620 字节，逐文件字节数与 SHA-256 一致。
- 边界与提交：六个冻结依赖文件哈希、85 configuration universe 与既有 dependency boundary 未变；未修改 T47-P0 依赖/Manifest/Room/Widget/T48 范围。文档已更新，提交后立即 STOP，未 push。
