# T45：Android AI、多对话、配置与安全网络链路

## 目标

实现本地多对话、AI 配置与 Keystore 秘密、Token 预算、OpenAI-compatible 客户端、Thinking、严格操作解析执行和生命周期取消。

## 非目标

- 不连接真实 Provider；不后台续跑/自动重试；不允许 AI 执行日程以外外部操作。

## 输入文档

- Android detailed design 第 4、6 节；T42、T43

## 影响文件

- `android/app/src/main/.../domain/ai/`
- `android/app/src/main/.../data/network/ai/`
- `android/app/src/main/.../data/settings/`
- `android/app/src/main/.../app/AiCoordinator*`
- 对应 JVM/Robolectric/MockWebServer 测试

## 接口/数据影响

- `SecretStore`、`AppPreferences`、`AiSettings`、`AiClient`、`AiMessageBudgeter`、`AiResponseParser`、`AiOperationExecutor`、`AiCoordinator`。

## 风险

- Key 泄露；响应写错 conversation；取消后重复/部分 CRUD；危险 action 绕过字段白名单；Thinking retry 重复请求。

## 当前状态

- 2026-08-30 恢复审计确认 secrets/preferences/conversation/budget/parser/executor/client/coordinator 生产实现均已存在，80 项 T45 聚焦测试通过；旧文字“仅红灯”已过期。
- dependency policy 曾命中 `AppPreferences → data.network` 具体 adapter；现由 `data.settings.NetworkEndpointPolicies` 提供共享纯校验，preferences 与 transport 保持相同 URL 契约。
- DataStore 锁定为 1.2.1 JVM core 变体以满足无 native runtime；F 路径已解除完整 lint 阻塞。Compose UI 入口接线属于 T46，不再作为 T45 核心完成的阻塞项。

## 实施步骤

- [x] 第一轮只写 secrets/preferences/conversation/budget/parser/client/coordinator 失败测试。
- [x] 实现 Keystore AES-GCM envelope、坏 key/tag 安全回退与 backup exclusion。
- [x] 实现 conversation repository/use cases 与至少保留一个契约。
- [x] 实现 HTTPS endpoint validation、models/chat、受限错误、Thinking 400/422 单次降级。
- [x] 实现预算/截断、JSON 变体、白名单操作和 EventService 执行；AI delete 直接执行。
- [x] 实现 single in-flight、conversation capture、background cancel/no retry 核心编排。
- [x] 执行安全/敏感扫描和聚焦测试。
- [x] 解决完整 lint 工具链阻塞并完成全门禁；与 T44 共享 checkpoint。Compose 入口接线留 T46。

## 测试与检查

```powershell
.\android\scripts\gradle.ps1 testDebugUnitTest --tests "*.ai.*" --tests "*.settings.*" --tests "*.conversation.*"
```

覆盖空/Unicode/长秘密、加解密/失效/坏 envelope、缺配置、HTTPS 校验、200/400/401/429/5xx、超时/断网/取消、畸形/Markdown/数组/对象 JSON、未知/危险 action、非法 ID/时间/字段、类型转换、长上下文、切会话、busy、旋转/配置重建不取消、ProcessLifecycle 后台取消与无日志泄露。

## 红灯证据

- 第一阶段及独立审计补强共新增 11 个 T45 测试文件，覆盖 secrets/preferences/conversation/budget/parser/executor/client/coordinator；自身 `ktlintTestSourceSetCheck` 零诊断，聚焦 Gradle 在 `compileDebugUnitTestKotlin` 真实失败于缺失 T45 类型与 Conversation/Room 扩展接口；共享 DataStore/OkHttp/mockwebserver3/TLS 依赖随后由主 Agent 锁定引入。
- 独立审计追加真实 AES-GCM/AAD 与 envelope adapter、WebDAV 非秘密设置、全部秘密擦除路径、Room 对话清空事务、redirect/oversize/取消、Thinking fallback 和 EventService 批处理要求；修订测试仍须先保持红灯再进入生产实现。

## 回滚方式

删除 AI/settings packages 和 Room conversation 调用；Key alias 留空或测试 fake，不触碰用户秘密。

## 完成定义

- [x] 所有红灯测试转绿；真实 Provider 未访问。
- [x] Key/password 无明文持久化/日志；取消不执行操作、不重试。
- [x] AI 只能经 EventService 执行受限日程操作。
- [x] 按根门禁完成 Android、Windows、构建、exe 与 `dist/data` 核验；与 T44 共享 checkpoint 提交。

## 最终结果（2026-08-30）

T45 核心随 F 路径通过完整 lint 和发布门禁。Runtime/Lifecycle/SavedState 升级未改变 AI 行为，API 26/36 compatibility 6/6，Android JVM 229 项与 Windows 171 项均通过；未连接真实 Provider，T46 UI 接线保持未开始。
