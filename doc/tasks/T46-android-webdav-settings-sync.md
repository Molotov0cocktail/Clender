# T46-C2b：WebDAV 设置、连接测试、生产同步装配与有限状态 UI

## 目标

在既有 T44 WebDAV 核心（codec/LWW/transport/sync service/coordinator/Room store）与 T46-C2a section-scoped DataStore/Keystore envelope 之上，完成 Android WebDAV 设置与同步的最后一环：

1. Settings 新增第三个 WebDAV section（Application、AI、WebDAV 固定顺序）。
2. 持久化 enabled、HTTPS directory URL、username；复用 PreferenceWireCodec；不改动既有 key 语义。
3. 使用既有 Keystore/AES-GCM envelope（SecretAlias.WEB_DAV_PASSWORD）保存 WebDAV password。
4. password 支持 KEEP/REPLACE/REMOVE 单次 DataStore edit 原子提交，与既有 AI envelope 并存互不覆盖。
5. `测试连接`执行一次 PROPFIND Depth: 0，只接受 200/207，零保存、零 GET/PUT。
6. `立即同步`先原子保存当前表单，再用刚保存 snapshot 通知生产 runtime，请求 SyncCoordinator 的 MANUAL trigger。
7. 本地成功 mutation、手动请求、前台进入（含冷启动 enabled/configured 首启）触发同步；无周期任务。
8. 显示有限、无秘密的 connection 与 sync 状态（内存 StateFlow，不写 DataStore）。
9. 保持 remote apply 单事务、不递增 mutation signal、不触发本地上传回路。
10. AppContainer.close() 确定性取消 probe、shutdown SyncCoordinator 并等待 worker 退出、解绑 ProcessLifecycle observer、取消 sync scope、擦除 password/关闭 session，再关闭 AI/DataStore/Room。

## 非目标

- C3、About 最终页、Widget、QuickAiActivity、T47、T48。
- 周期 WorkManager、Alarm、后台 service、foreground service、通知。
- OAuth、Digest、客户端证书、cleartext HTTP。
- 多远端文件、对话/AI/设置同步。
- Room entity/schema/version/migration、DAO 变更；生产 Manifest、Gradle、catalog、lock、verification metadata、依赖变更。
- 真实 WebDAV、真实密码、真实用户数据；push。
- 修改 T44 codec、LWW、墓碑、ETag/412 的业务语义；不修改 T44 SyncCoordinator.request(trigger): Boolean 行为（只允许新增向后兼容的有限结果接口）与 T44 全部既有测试。

## 恢复现场（2026-09-01，修改前确认）

- Branch codex/android-architecture，HEAD 1bd5fd3d2ace27c44640b32a9c975314eb48a7b8，提交 `Implement Android T46 application and AI settings UI`。
- working tree clean、staged 为空、无远端跟踪项（未 push）；未 stash/clean/reset/checkout。
- 修改前真实 strict/offline rerun：gradle.ps1 --offline --no-daemon --dependency-verification strict --max-workers 1 --rerun-tasks testDebugUnitTest → BUILD SUCCESSFUL in 4m 9s，**83 suites / 752 tests / 0 failure / 0 error / 0 skip**。
- SQLite/Room CloseGuard 四类扫描（XML+HTML）均为 0；ui.* 428、WebDAV/sync 聚焦 55、C2a settings 家族 134 全绿。
- 基线冻结：Runtime 1.11.3、Lifecycle 2.9.4、SavedState 1.3.2、Annotation Experimental 1.4.1（仅 producer）、UI 1.6.8、85 configuration universe、五个冻结依赖文件 SHA-256。
- T46 状态：P0/A/B1/B2/B/C1a/C1b/C2a 完成；C2b/C3、T47/T48 未开始。

## WebDAV 非秘密/秘密数据契约

### 非秘密（DataStore 既有 keys，KEEP 语义不变）

| key | 类型 | 含义 |
|---|---|---|
| web_dav_enabled | true/false 字符串 | 开关 |
| web_dav_url | String | HTTPS directory URL（可含 path，尾 / 归一） |
| web_dav_username | String | trim 后非空，无冒号，UTF-16 无孤立 surrogate |

校验继续复用 WebDavSettingsPolicy.validate：仅 HTTPS；禁 query/fragment/userInfo；禁重复 /、路径穿越（. / ..）、编码 separator（%2f / %5c）、反斜杠。username 规则同 T44（无冒号、合法 Unicode）。

### 秘密（既有 envelope keys，逐字节语义不变）

secret_envelope_web_dav_password_{version,iv,ciphertext}（PreferenceWireCodec.envelope(SecretAlias.WEB_DAV_PASSWORD)）。使用既有 AesGcmSecretCipher / EnvelopeSecretStore / DataStoreSecretEnvelopeStorage。密码不解密回填 UI，只显示 configured/not-configured presence；任何层不得把密码写入 SettingsUiState、错误、日志、Bundle、SavedStateHandle、toString 或 mutation signal。

### DataStoreAppPreferences.updateWebDav（data 层，单 edit）

```kotlin
sealed interface WebDavPasswordMutation {
    data object Keep : WebDavPasswordMutation
    class Replace(val value: CharArray) : WebDavPasswordMutation
    data object Remove : WebDavPasswordMutation
}

data class WebDavSectionSnapshot(
    val enabled: Boolean,
    val url: String,
    val username: String,
    val passwordConfigured: Boolean
)

suspend fun updateWebDav(
    settings: WebDavPreferences,
    mutation: WebDavPasswordMutation,
    cipher: SecretCipher
): WebDavSectionSnapshot
```

- 空白约束照抄 AI 语义：全空白 Replace 视为 Keep。
- 校验失败（含 enabled=true 而无有效 password：Remove、或 Keep 且无已配置 envelope、或空白 Replace 且无 envelope）→ IllegalArgumentException，**零 edit**。
- Replace：先完成 AES-GCM envelope，再同一次 edit 写非秘密三字段 + envelope 三字段。
- Remove：同一次 edit 写非秘密三字段并删除 envelope 三字段。
- Keep：同一次 edit 只写非秘密三字段；envelope 三字段逐字节不变（configured 在同一 edit transform 内依据入参 presence 判定）。
- validation/encryption/edit 任一步失败：零部分状态，旧非秘密 + 旧 envelope 保持。
- 返回的 WebDavSectionSnapshot 与本次提交同版本。
- 所有 Replace 输入 / cipher 持有 CharArray 在成功/失败/拒绝/取消全路径归零。
- disabled 合法路径：URL/username 全空；或一组完整合法 URL/username（预配置）。disabled 时不要求 password。禁用不自动删除 URL/username/password。

## Save / Test Connection / Sync Now 差异

### Save

- 校验当前 WebDAV section draft（URL/username/enabled+password 组合规则）→ 原子保存当前 section 与 password mutation → 不发任何网络请求。
- 成功清 dirty；失败保留 dirty 并显示有限错误。

### Test Connection

- 只使用当前表单草稿，**不保存任何字段**（原子 port 零调用）。
- disabled 开关不阻止测试；但 URL/username 仍必须合法（disabled + 全空 → VALIDATION_FAILED）。
- 有效 password 取法：当前输入非空白 → 使用输入副本（REPLACE 语义）；否则已配置 envelope → 解密使用（KEEP 语义）；否则（含 removeKeyPending == true）→ UNCONFIGURED，**不得回退旧密码**。
- 只发送一次 PROPFIND 到 directory URL，Depth: 0；只接受 200/207；零 GET/PUT clender-events.json。
- 结果仅 SUCCESS/BUSY/UNCONFIGURED/VALIDATION_FAILED/AUTH/DOCUMENT/TRANSPORT/SECRET/CANCELLED/INTERNAL 有限枚举。
- 成功不改变 dirty 状态；离开 Settings/VM clear 取消 probe、释放 gate、擦除操作副本；operation guard 防重复点击。

### Sync Now

1. 校验当前草稿（同 Save 组合规则）。
2. 原子保存当前 WebDAV section/password mutation。
3. disabled → DISABLED，零网络。
4. enabled 但该次提交无有效 password → UNCONFIGURED，且不得留下 enabled 无密码的非法持久化状态（由 data 层校验前置保证）。
5. 使用刚保存的 WebDavSectionSnapshot（参考身份绑定，=== latestSnapshot）通知生产 runtime。
6. 请求 SyncCoordinator 的 MANUAL trigger。
7. 正在运行时合并为唯一 pending，不创建并行 sync。
8. 保存成功即清 dirty；同步可在离开 Settings 后继续（app-scoped）。

## foreground / local-change / manual 触发语义

- SyncTrigger 仅有 LOCAL_CHANGE、MANUAL、FOREGROUND 三类（T44 冻结，禁止新增）。
- LOCAL_CHANGE：ScheduleMutationVersionSignal 递增触发；必须忽略初始 0 emission（进程内首个 emission 一律忽略，后续每次递增恰好一次）。
- MANUAL：Settings 的 Sync Now（带绑定 snapshot）。
- FOREGROUND：每次 app 由 background 回 foreground 触发一次；冷启动若 persisted enabled+configured 且进程处于 foreground，首启执行一次（runtime 在 AppContainer 构造即开始观察，首个 onStart 即为该触发）。
- 保存 enabled=true 本身不自动同步；disabled/unconfigured 时全部 trigger 零 secret/零网络/零 Room snapshot 读取。
- 无周期 trigger、无定时器、无 WorkManager 网络。
- 进后台：不产生新 trigger；已开始 sync 用捕获 snapshot 尽力完成；回前台再请求 FOREGROUND；不采用 AI 的后台取消语义。
- 配置变化：active sync 使用启动时捕获的 URL/username/password snapshot；保存新配置不修改当前 session；后续 pending/run 使用新配置；active 时禁用 → 当前运行可完成、pending 丢弃（复用 SyncCoordinator 既有 isEnabled() pending 复查）；active 时删除 password → 当前运行用已捕获副本并在结束后擦除、后续 trigger 为 UNCONFIGURED。

## 同步状态与错误码（内存 StateFlow，不写 DataStore）

```kotlin
sealed interface SyncState {
    object Disabled : SyncState
    object Unconfigured : SyncState
    object Idle : SyncState
    data class Running(val pending: Boolean) : SyncState
    data class Success(val uploaded: Boolean, val localChanged: Boolean, val eventCount: Int) : SyncState
    data class Failed(val code: SyncFailureCode) : SyncState
}

enum class SyncFailureCode { AUTH, CONFLICT, DOCUMENT, TRANSPORT, SECRET, SETTINGS, CANCELLED, INTERNAL }
```

- 401/403 → AUTH；412 重试耗尽 / WebDavConflictException / SyncFailureKind.CONFLICT → CONFLICT；WebDavDocumentException / SyncFailureKind.DOCUMENT → DOCUMENT；timeout/disconnect/5xx/redirect → TRANSPORT；坏/失效/KeyInvalidated envelope → SECRET；捕获的 settings 校验失败/绑定过期且不可回退 → SETTINGS；取消（非 remoteVisibleChanged 路径）→ CANCELLED；其余异常 → INTERNAL。
- 状态不得保存或显示：URL、username/password、Authorization、HTTP body、exception message/stack、事件标题/description、完整远端文档。安全字段：uploaded/localChanged 布尔与 eventCount。
- SyncCoordinator 新增 requestDiagnosed(trigger): SyncCoordinatorDecision（STARTED/COALESCED/DISABLED/UNCONFIGURED/REFRESHING/SHUTDOWN）只读扩展；既有 request(trigger): Boolean 行为与 T44 全部测试保持不动。UI Sync Now 状态在应用层映射：SYNC_STARTED/SYNC_COALESCED/DISABLED/UNCONFIGURED/VALIDATION_FAILED/SAVE_FAILED/INTERNAL（REFRESHING 视为 COALESCED）。

## WebDAV operation gate（app-scoped，独立于 AiOperationGate）

- WebDavOperationGate：tryAcquire()（非阻塞，probe 用）+ withExclusive { }（挂起等待，sync worker 用，probe 结束时继续）+ close()。
- 同一时刻最多一个 WebDAV HTTP operation；probe 遇 running sync → BUSY 且零网络；sync worker 遇 running probe → 等待其结束，local/manual trigger 不丢失；离开 Settings 取消 probe 后等待中的 sync 继续。
- 不与 AiOperationGate 混用；不共享 Authorization interceptor；AI 与 WebDAV 各自使用独立、无日志的 OkHttpClient。

## 生产同步 runtime（AppContainer 惰性装配）

- 装配对象：gate、no-logging OkHttpClient、WebDavClient、RoomSyncRecordStore、SyncService、SyncCoordinator（含 requestDiagnosed）、app-scoped coroutine scope、ProcessLifecycle foreground binding、StateFlow<SyncState> runtime。
- runtime 启动只观察三路来源：WebDAV 非秘密 enabled/configured presence（WebDavPreferences flow + envelope presence flow）、ScheduleMutationVersionSignal、ProcessLifecycle onStart/onStop。
- 在真正 probe/sync 前不得：解密 password、创建 WebDavClient/session、发网络、读取同步快照、触碰 AI secret。
- 每次 run：同一 state.first() 快照取 URL/username+envelope 并解密（同版本），或消费 Settings 传入的绑定 snapshot（readPassword(snapshot) 参考身份校验，过期则回退新配置）；WebDavClient.openSession(password).use { SyncService(...).sync() }，finally 关 session + 擦 password。
- remote apply 继续 RoomSyncRecordStore → RoomEventRepository.applyRemote 单事务，不经过 EventService、不递增 mutation signal；onRemoteVisibleChanged 内不得再次请求 LOCAL_CHANGE；Room Flow 自动刷新 Calendar/Events。同步失败若已提交 remote visible changes 仍须刷新一次（T44 既有语义）。
- AppContainer.close() 顺序（幂等）：取消 probe（finally 释放 gate/擦除）→ SyncCoordinator.shutdown() 等待 active worker 退出 → 解绑 ProcessLifecycle observer → 取消 sync scope → 确认 session/password 已关闭擦除 → 既有 AI gate/client/gateway/scope close → DataStore scope cancel → Room close。不允许 Room 先关闭导致 sync coroutine 迟到访问。

## Settings UI（tab 顺序固定 Application → AI → WebDAV）

- WebDAV section：enabled switch、URL、username、masked password 输入、configured presence 文案、显式 remove password、Save、Test Connection、Sync Now、connection 状态、sync 状态。
- Save/Test/Sync 共享 operation guard，显式操作期间禁止重复点击；background sync 不阻塞浏览其他 section。
- password remove 对话框纳入既有最高优先级对话框栈；WebDAV draft + password input + removePending 参与 dirty。
- Drawer、系统 Back、section/navigation 不得绕过 discard confirmation；Discard 恢复最后保存的 WebDAV draft 并擦除 password input；离开 Settings 只取消 probe，不取消 app-scoped sync。
- API Key remove、AI model fetch 与全部 C2a dirty 语义不得退化。
- 矩阵：360/599/600/840dp、8/20sp、200% font、zh/en、Light/Dark/System、API 26/36、页面可滚动、关键操作 >=48dp、password 视觉变换且 semantics 树不含密码。

## Agent 文件边界

- **Agent A**：第一阶段只新增 app/src/test/.../data/settings/** 测试（WebDAV section/password mutation）。红灯审查通过后只实现 app/src/main/.../data/settings/** 生产（WebDavPasswordMutation、updateWebDav、snapshot、envelope presence flow、codec 辅助）与对应测试。不改 app/sync/ui/AppContainer/resources/docs/Git。
- **Agent B**：第一阶段只新增 app/src/test/.../app/sync/**、必要 app/src/test/.../sync/**、app/src/test/.../app/settings/** 测试（runtime/trigger/status/probe/gate/请求结果）。红灯审查通过后只实现 app/src/main/.../app/sync/**、必要 app/src/main/.../sync/SyncCoordinator.kt（仅新增 requestDiagnosed）、app/src/main/.../app/settings/**（WebDavSettingsApplicationService/port 接口/决策枚举）。不改 UI、AppContainer、MainActivity、resources、docs、Git。
- **Agent C**：第一阶段只新增 app/src/test/.../ui/settings/** 测试（WebDAV section、状态、dirty/accessibility；纯 Compose 必须复用 test-only RobolectricComposeHost，不以覆盖生产 content 启动 MainActivity）。红灯审查通过后只实现 app/src/main/.../ui/settings/** 生产与对应测试。不改共享装配、resources、docs、Git。
- **主 Agent 独占**：AppContainer.kt、MainActivity.kt、ClenderApp/effects/settings navigation 共享接线、ProductionSettingsPort 共享桥接（含 ui↔app 决策映射与 SyncState 透出）、跨层生产集成测试、中英 strings、doc/tasks/*、progress.md、根 AGENTS.md、Git 与完整发布验证。

## 测试矩阵（第一阶段只能新增测试；红灯必须来自缺失 C2b contract）

### Agent A（data/settings）

partial update 不覆盖 appearance/AI/active conversation/navigation/AI envelope；KEEP envelope 逐字节不变且单 edit；REPLACE/REMOVE 单 edit；enabled+无有效 password（REMOVE 或 KEEP 无 envelope）零修改且不开始 edit；disabled blank / disabled 完整预配置两条合法路径；incomplete URL/username、冒号、坏 Unicode、HTTP、query/fragment/重复 separator/.. /%2f 等非法输入零 edit；encryption/edit failure 零部分状态且旧状态保持；corrupt/incomplete envelope fail closed（presence 三字段完整才 true，且不解密）；与 DataStoreActiveConversationStore/AI update 并发不丢字段；snapshot 与 URL/user/envelope 同版本；AI envelope 不变；CharArray 全路径归零（校验失败/加密失败/edit 失败/成功）。

### Agent B（app/sync、必要 sync、app/settings）

Test 不保存草稿（port update 零调用）；当前输入 REPLACE、持久化 KEEP（解密使用）、pending REMOVE→UNCONFIGURED；MockWebServer 只收到 PROPFIND Depth: 0 且零 GET/PUT；200/207 成功，401/403→AUTH，404/其他→有限码，5xx/redirect/timeout/disconnect→TRANSPORT，cancel→CANCELLED，gate 被 sync 持有→BUSY；probe/sync gate 互斥、sync 等待 probe、probe 取消后等待中的 sync 继续；Sync Now 顺序 update→request（trace）；持久化 enabled+configured 首次 foreground 恰一次 run；disabled/unconfigured 所有 trigger 零 secret/network/Room snapshot；mutation 初始 0 emission 不同步、后续递增恰一次、网络期间多次 mutation 仅一次 pending follow-up；LOCAL_CHANGE/MANUAL/FOREGROUND 可触发；busy 合并恰一个 pending；active 使用启动时捕获旧配置、保存新配置不修改当前 session；禁用丢 pending；后台不取消 active、回前台再触发；remote apply no-loop（refresh 回调内 request 被拒）；failure-after-remote-apply 刷新一次；SyncState/toString 无 URL/user/password/exception/正文；close/shutdown 顺序（先 probe cancel、shutdown 等待 worker、解绑、scope cancel、session close、password 擦除）；SyncCoordinator.requestDiagnosed 五类决策且 request(trigger) 行为与 T44 测试不变。

### Agent C（ui/settings）

第三 tab 顺序与默认 Application；WebDAV load/edit/save；masked password/config presence；KEEP/REPLACE/REMOVE confirm；Save 零网络；Test 不清 dirty；Sync Now 保存成功清 dirty 并显示 started/queued；operation guard（三操作互斥、双击一次）；connection/sync 有限状态展示；Drawer/Back/discard 覆盖 WebDAV dirty 与 password 擦除；Application/AI C2a 回归；360/599/600/840dp、8/20sp、200% font、zh/en、Light/Dark/System、API 26/36、可滚动、>=48dp、password semantics/visual transformation、semantics 树无密码。

### 主 Agent（跨层生产集成）

AppContainer production assembly（复用同一 Room/EventRepository/DataStore，独立 WebDAV OkHttpClient/gate，不与 AI gate 混用）；MainActivity/Settings 生产 integration（真实 WebDAV section、真实 port）；ProcessLifecycle foreground（enabled+configured 时 onStart→一次 FOREGROUND）；EventService/AI mutation → sync（一次 batch mutation 只产生一次 LOCAL_CHANGE）；remote Room apply → Calendar Flow 刷新且无 mutation loop；disabled 冷启动 lazy（零 secret/network/Room snapshot）；生产 Activity 测试使用 ProductionActivityTestResources；完整 CloseGuard 扫描 0。

## 风险

- DataStore section 并发更新丢 WebDAV 或另一 section 字段；password 与非秘密字段跨版本混用。
- probe/sync gate 互斥失效导致并发 HTTP 或凭据交叉；失去 Settings 后 probe 未取消。
- 冷启动 runtime 触发在 disabled/unconfigured 时意外解密/建 client/发网络/读 Room。
- mutation initial emission 误触发或网内多次 mutation 产生多 pending。
- remote apply 后 LOCAL_CHANGE 回路或失败后未刷新。
- Sync Now 保存后 runtime 尚未观察到新 availability 而误判 DISABLED（以绑定 snapshot 规避）。
- 状态/错误/Toast/semantics/测试夹具泄露 URL/密码/Authorization/正文/异常。
- 大字体/窄屏操作不可达；password remove 对话框优先级破坏 C2a/C1b。
- Reproduction/顺序依赖：Robolectric 下 ProcessLifecycle 首启 FOREGROUND 与 worker 并发的时序竞态。
- CloseGuard：sync scope 在 Room 关闭后迟到访问或 Worker 未等待退出。

## 回滚方式

- 删除 C2b 新增 data/settings WebDAV mutation/codec、app/sync、app/settings WebDAV service/port、ui/settings WebDAV section 与对应测试；精确还原 SyncCoordinator（删除 requestDiagnosed）、AppContainer/MainActivity/ClenderApp/ProductionSettingsPort/strings/docs；Room v1、DAO、Manifest、依赖、T44 core、真实数据均不变。

## STOP 条件（完整回退 C2b 试验代码，仅保留任务与证据）

- 修改前 752 基线或 CloseGuard 门禁失败。
- WebDAV 非秘密字段与 password envelope 无法单 edit 原子提交。
- Test Connection 必须保存草稿或写远端。
- Sync Now 无法绑定刚保存 snapshot（或必须回退旧配置）。
- 无法防止 URL/password 跨版本混用。
- remote apply 触发上传循环；initial mutation emission 导致同步。
- 需要周期任务、后台 service、通知或新权限。
- 需要依赖、Gradle、lock、Manifest、DAO、schema/migration 变更。
- 需要真实 WebDAV/password/用户数据。
- C2a AI gate/secret/theme/dirty 或生命周期基线回归。
- Android、lint、Windows、PyInstaller、exe、CloseGuard 或 dist/data 任一门禁失败。

## 完成定义

- WebDAV section、原子保存、Test Connection、Sync Now、三路 trigger、有限状态 UI 全部满足本文件契约，Agent A/B/C 与主 Agent 测试矩阵全绿。
- 既有 83 suites/752 tests 不退化；Android 完整 strict/offline 门禁（testDebugUnitTest lintDebug lintRelease detekt ktlintCheck verifyReleaseSigningPolicy verifyNoGoogleServices verifyNoNativeRuntimeArtifacts verifyResolvedVersionsLocked assembleDebug verifyDebugApkNoNativeArtifacts）通过；lint 真实 rerun 无 baseline/disable/suppress。
- generated/boundary 各 44/44；CloseGuard 四类扫描 0；85 configuration universe 与五个冻结依赖 SHA-256 零漂移；Runtime 1.11.3 / Lifecycle 2.9.4 / SavedState 1.3.2 / Annotation Experimental 1.4.1 / UI 1.6.8；Manifest、Room schema/entity/DAO/migration、Gradle/catalog/lock/verification metadata 零变化；无 Google/native/graphics-path/Dynamic/SNAPSHOT；网络测试只使用 MockWebServer，未访问真实 Provider/WebDAV/Key/password/用户数据。
- Windows 171/171、全模块导入、build.py --check、完整 PyInstaller、normal primary + silent secondary 与 silent primary + normal secondary 双向隔离冒烟（secondary exit 0、primary 存活、最终 0 测试进程/目录）；dist/data 构建前后只读核对（路径/数量/大小/UTC/逐文件 SHA-256/聚合摘要）完全一致，不打开/解析/复制正文。
- 更新本文件实施结果、T46 总任务 C2b 小节、progress.md（P0/A/B1/B2/B/C1a/C1b/C2a/C2b/C2 全部完成；C3/T47/T48 未开始）、根 AGENTS.md（WebDAV settings、原子 secret、trigger/runtime/status、生命周期、维护记录）；android/AGENTS.md 规则未变化则不修改。
- 审查 diff/staged/秘密/生成物/本地路径/无关重写，设置 git 代理后创建提交 `Implement Android T46 WebDAV settings and sync UI`，不 push。

## 实施结果（2026-09-01）

### 测试先行与红灯

- 三个 Agent 第一阶段只新增测试：Agent A 新增 data/settings 两个文件（C2bWebDavSettingsContractTest 15 方法、C2bWebDavSecretPresenceFlowTest 4 方法，API 26/36 参数化）；Agent B 新增 app/sync 两个文件（WebDavSyncContractsTest 15、WebDavSyncRuntimeTest 19）；Agent C 新增 ui/settings 三个文件（Draft 9、ViewModel 19、Screen 15）。
- 红灯证据：三路各自 compileDebugUnitTestKotlin 均真实失败，错误只指向缺失 C2b 契约符号（updateWebDav/WebDavPasswordMutation/WebDavSectionSnapshot/presence、WebDavSyncRuntime/WebDavOperationGate/WebDavFailureClassifier/WebDavSettingsApplicationService、SettingsSection.WEBDAV/SettingsPort 新成员/SettingsActions 新成员等）；既有测试零修改、零编译错误。
- 裁决：Agent A 的 enabled+Remove 语义按主文档（REMOVE_WHILE_ENABLED 校验失败）；Agent C 的 remove 测试改为先禁用再 REMOVE（与产品语义一致）；SettingsPort 5 个新成员采用 Kotlin interface 默认实现，既有 SettingsViewModelContractTest 私有 FakeSettingsPort 逐字未动。

### 生产实现

- data/settings：AppPreferencesWebDavCodec.kt（WebDavPasswordMutation/WebDavSectionSnapshot/normalizeBlankReplace/encryptIfReplacement/writeWebDavEnvelope/validateWebDavDraftOrNull）；AppPreferences.kt 增加 updateWebDav 成员（先校验→presence 预检→加密→单 edit；KEEP 只写三非秘密 key、REPLACE/REMOVE 同 edit 写/删 envelope；返回同版本 snapshot；全路径归零）；SecretStore.kt 增加 presence Flow。
- sync：SyncCoordinator 仅新增 SyncCoordinatorDecision + requestDiagnosed（request 行为与 T44 测试逐字节不变）。
- app/sync：WebDavOperationGate（tryAcquire/withExclusive/close，CompletableDeferred 脉冲等待）、WebDavSyncStatus（SyncState/SyncFailureCode/SyncRequestDecision/WebDavAvailability）、WebDavFailureClassifier（含 WebDavSecretException/WebDavSettingsException）、WebDavConnectionProbe（PROPFIND Depth:0、gate 忙→Busy、cancelInFlight）、WebDavSyncRuntime（内部 SyncCoordinator 引擎：availability 单收集器+firstEmission、mutation drop(1)、onStart→FOREGROUND、busy 先于 availability 判定、staged binding 单次消费、close 幂等 runBlocking shutdown）。
- app/settings：WebDavSettingsApplicationService（save/testConnection/syncNow/cancelProbe；组合校验、KEEP/REPLACE/REMOVE、persistForSync 区分 VALIDATION/SAVE_FAILED、全路径密码归零）。
- ui/settings：SettingsSection.WEBDAV 第三 tab；WebDavSettingsDraft + validate（URL/USERNAME/PASSWORD_REQUIRED/REMOVE_WHILE_ENABLED）；决策/状态镜像枚举；SettingsPort 5 新成员（带默认）；SettingsViewModel 六动作 + init 收集 webDavSyncState + 共享 operation guard + dirty/discard/probe cancel；SettingsScreen 第三 tab + WebDavSettingsSection.kt（enabled/URL/username/masked password/config presence/remove/Save/Test/Sync Now/状态横幅）+ WebDavSettingsUiLogic.kt（校验/secret 副本/mutation 推导/状态资源映射）。
- 主 Agent：AppContainer 惰性装配 webDav gate/client/scope/atomic port/availability flow/runtime/probe/service/port 接线，close 顺序（probe cancel→httpClient cancelAll→runtime.close→gate close→scope cancel→AI→DataStore→Room）；ProductionSettingsPort + WebDavPortDependencies + ProductionSettingsPortWebDav.kt 映射；ClenderApp SettingsDestination 全动作接线；中英 strings 新增 40 键。
- 跨层集成测试：AppContainerWebDavAssemblyContractTest（disabled 冷启动零 Room/secret/network、单一 gate 与 AI gate 独立、单一 DataStore 复用、close 幂等关 gate）、WebDavSyncProductionIntegrationTest（HTTPS MockWebServer + Room in-memory：本地 mutation→GET/PUT 单轮无回路、remote apply→Room Flow 刷新且 signal 0、disabled 零网络零 run）、WebDavSettingsProductionIntegrationTest（真实 MainActivity：第三 tab 可达、disabled 预配置保存端到端、runtime Disabled 状态显示）。

### 验证结果

- Android 完整 strict/offline 门禁：testDebugUnitTest lintDebug lintRelease detekt ktlintCheck verifyReleaseSigningPolicy verifyNoGoogleServices verifyNoNativeRuntimeArtifacts verifyResolvedVersionsLocked assembleDebug verifyDebugApkNoNativeArtifacts 全部 BUILD SUCCESSFUL；lint 真实 rerun 无 baseline/disable/suppress；**93 suites / 909 tests / 0 failure/error/skip**（基线 752 + C2b 157）；CloseGuard XML+HTML 扫描 0。
- verify-generated.ps1 44/44、verify-boundaries.ps1 44/44（Android boundary passed）。
- 85 configuration universe、五个冻结依赖文件 SHA-256、Runtime 1.11.3 / Lifecycle 2.9.4 / SavedState 1.3.2 / Annotation Experimental 1.4.1 / UI 1.6.8 零漂移；Manifest、Room schema/entity/DAO/migration、Gradle/catalog/lock/verification metadata 零变化；无 Google/native/graphics-path/动态/SNAPSHOT；网络测试只使用 MockWebServer。
- Windows：offscreen unittest 171/171、全模块导入 imports ok、build.py --check 通过；完整 PyInstaller 生成 dist/Clender.exe 45,580,456 bytes（SHA-256 459C61BF6A4C32908EABC72331E5F5AE665A430394F39D32C5986830E05F308B）；normal primary + silent secondary 与 silent primary + normal secondary 双向隔离冒烟均 secondary exit 0、primary 存活并创建隔离 data；最终 0 Clender 进程、0 冒烟临时目录。
- dist/data 构建与冒烟前后均为 5 文件、195,620 bytes，相对路径/大小/UTC/逐文件 SHA-256 聚合摘要 E43733909427A268005A9514FB136CDA0F74C43395046269E73EF20E539668D9 完全一致；只读取 metadata 与哈希，未打开/解析/复制正文。
- 未访问真实 Provider/WebDAV/Key/password/用户数据；未 push。T46 当前 P0/A/B1/B2/B/C1a/C1b/C2a/C2b/C2 全部完成，C3/T47/T48 未开始。
