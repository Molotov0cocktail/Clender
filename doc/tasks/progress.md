# T69 双端 v1.2.0 正式发布（2026-09-14，进行中）

用户确认直接发布现有正式产物为 `v1.2.0`，并更新 README 与内部说明，不修改项目契约。范围仅为文档、Windows 门禁要求的重建与 GitHub/Gitee Release；Android 沿用 T68 已签名 APK/AAB，包内历史版本仍为 `1.0.0 (1)`。任务、风险、测试矩阵与回滚见 `T69-dual-platform-release.md`。Gitee `main` 有独立 LICENSE 提交，禁止强推覆盖。Windows 首轮测试受运行实例保护门禁影响 1 error，用户退出后 252/252 通过；完整构建与两场景隔离冒烟通过，`dist/data` 摘要零变化，新 EXE SHA256 `567df4eeb9d2978245e6bcc881222135265f96149a6a3d496fd3c2ea8cd19ced`。双站发布与远端回读尚待完成。

# T68 Android 混合提醒 AI 与会话指标（2026-09-09，完成，保留验收限制）

基线main/de59500，仅Android，三个独立子agent先RED后实现，主agent串行验证。AI请求JSON对象模式；非契约首响最多一次有预算/总时限的格式纠正，锚定本轮请求，失败usage只计一次；散文/嵌套operations整批拒绝，不从正文猜测执行。冗余timespan更新由EventService合并旧时间再校验。出站历史保留纯操作真实ACK，防丢失历史助手回合；有正文仅保留正文。中文思考只提示词约定，权限申请仍仅设置页。

对话气泡只展示模型正文，无助手/模型回复可见前缀；已知旧包装兼容剥离，实际执行反馈仅绑定当前请求在输入区状态显示。底栏左侧持久显示最近主请求输入Token估算/当时配置窗口及百分比、独立累计Provider Token，右侧发送；未知值不伪0。Room v3仅新增Conversation两nullable预算列，显式1→2→3迁移；预算-only观察可刷新，清空原子归零，Event/WebDAV v1不变。v3不可直接降级v2，禁止清空真实库回滚。

维护记录（T68）：网络/domain/AI协调与UI、Conversation/Room迁移、合成live harness、测试/策略及任务记录更新。完整verify-all 208suites/2089tests（UI79/875），零失败/错误/跳过/四类泄漏；111发布夹具与foundation/boundary各94项，96tasks/6m44s全部通过。453源码/schema摘要零漂移。最终真实glm-5.3-flash七轮/8HTTP200，创建7/修改7/删除7、ID/日期/三策略/预算与9615tokens持久回读通过；此前模型合法日期误算失败保留，结构校验不能保证模型语义。签名APK/AAB审计通过，APK SHA256 c24bbb3aa0d5f57ccb7720b5f7ee9aae3a71d55a8e46b8b91a6992a48311c068。API26模拟器ADB shell环境超时，未安装/未改数据并已关闭，不宣称设备验收通过；API36同包设置授权、合成保存/删除、自然通知/重要闹钟/计时及真实Stop释放通过，明暗normal/2x普通底栏审查通过；Stop先于timer顺序未覆盖，2x横屏真实IME输入裁切/底栏可达未验为保留限制。权限含UID appops、字号/主题/旋转/IME均已恢复，两设备按身份关闭。无PC代码/测试/构建或真实数据读取，无厂商真机/人工听音声明；临时Key不落盘、调用已结束。本地提交回执以Git日志为准，不推送。

# T67 当前交付（2026-09-08，完成）

Android AI 真实 Provider 修复、回复格式与权限入口迁移。中文思考仅由系统提示词要求，模型仍输出英文时直接展示，不追加翻译请求。基线 main/0d762dc；计划与测试矩阵见 T67-android-ai-provider-repair.md。三个独立子 agent，主 agent 串行验证；仅 Android，临时凭据不落盘，合成数据在线验收，不推送。

最终199suites/1978tests与完整Android门禁、签名APK/AAB通过；真实Provider三轮创建/修改/删除与Room回读通过，2634tokens。API26/36关键界面及日程链路审查通过，API36设置页系统通知/精确闹钟真实授权通过。中文思考仅提示词约定。APK e27b5305a632，完整哈希、失败历史和恢复证据见T67；无PC构建或厂商真机听音声明。

# T66 当前交付（2026-09-08，完成）

Android 可读性、Widget、全面屏、提醒与 AI 执行/模型参数修复，详见 T66-android-usability-ai-alerts.md。main/3e687d4 干净基线；独立子 agent 分工，仅 Android，不构建 Windows。

已完成所有目标，按设备失败证据补齐响铃期间的mediaPlayback服务，并修复失效铃声IOException与汇总通知污染。最终verify-all：196suites/1941tests（UI76/828）、全部静态、111发布夹具与foundation/boundary各88项通过；431源码/schema摘要零漂移。APK SHA256 5f5e84db72b6df8d86aca7020e6ede7e33c50247df0a644fa601f589a0dc57b9；APK/AAB签名审计、API26/36自然后台播放/原题/Stop、真实Widget与明暗日历验收完成。设备恢复后身份关闭，旧383cb/2ecd失败证据保留。仅Android、无Windows构建或真实数据读取；本地提交以Git日志为准，不推送。限制：改系统字号后重新保存Widget设置，模型上限依赖服务端元数据，厂商自启动需手动设置。

# T64 历史交付（2026-09-07）

已从main/773c2dc恢复正确基线并完成Android排程/日历整合、Drawer美化。背景/图标完整保留，PC零改动且不测试构建。1806应用测试、完整门禁、签名与API26/36各26组验收通过，设备恢复关闭。交付到main并按用户要求删除本地Android分支；提交及删除回执见Git日志/最终答复。T64任务记录含已知旧系统栏限制与验收范围。

# 重构任务进度跟踪

> **最后更新**：2026-07-15
> **总任务数**：15
> **已完成**：15 / 15

---

## 任务状态总览

| 任务 ID | 任务名称 | 状态 | 依赖 | 完成时间 |
|---------|---------|------|------|---------|
| T01 | 创建数据模型 (models.py) | ✅ 已完成 | 无 | 2026-07-15 |
| T02 | 创建常量与日志模块 | ✅ 已完成 | T01 | 2026-07-15 |
| T03 | 重构数据库层 | ✅ 已完成 | T01 | 2026-07-15 |
| T04 | 创建事件业务服务 | ✅ 已完成 | T03 | 2026-07-15 |
| T05 | 重构主题系统 | ✅ 已完成 | T02 | 2026-07-15 |
| T06 | 提取对话持久化模块 | ✅ 已完成 | T01 | 2026-07-15 |
| T07 | 提取 AI HTTP 客户端 | ✅ 已完成 | T02 | 2026-07-15 |
| T08 | 创建 AI 业务服务 | ✅ 已完成 | T04, T06, T07 | 2026-07-15 |
| T09 | 提取 Canvas 渲染组件 | ✅ 已完成 | T05 | 2026-07-15 |
| T10 | 重构日历视图组件 | ✅ 已完成 | T04, T09 | 2026-07-15 |
| T11 | 重构事项管理面板 | ✅ 已完成 | T04 | 2026-07-15 |
| T12 | 重构 AI 对话组件 | ✅ 已完成 | T06, T07, T08 | 2026-07-15 |
| T13 | 重构主窗口入口 | ✅ 已完成 | T10, T11, T12 | 2026-07-15 |
| T14 | 安全加固 | ✅ 已完成 | 无 | 2026-07-15 |
| T15 | 集成验证与清理 | ✅ 已完成 | T01-T14 | 2026-07-15 |

**状态图例**：⬜ 待开始 | 🔄 进行中 | ✅ 已完成 | ❌ 阻塞

---

## 依赖关系图

```
T01 ──┬── T02 ──┬── T05 ── T09 ──┐
      │         ├── T07 ──┐       │
      │         └── T03 ──┤       │
      ├── T03 ── T04 ──────┤       │
      ├── T06 ─────────────┤       │
      │                    │       │
      T14 (独立)           │       │
                           │       │
      T04 + T06 + T07 → T08       │
                           │       │
      T04 + T09 → T10 ─────┤       │
      T04 ──→ T11 ─────────┤       │
      T06+T07+T08 → T12 ───┤       │
                           │       │
      T10 + T11 + T12 → T13       │
                           │       │
           所有任务 → T15 ─────────┘
```

---

## 实施进度（已完成 Phase 1-2）

**Phase 1：基础设施** — ✅ 全部完成
1. ✅ T01 - 创建 models.py（Event/Conversation/Message dataclass）
2. ✅ T02 - 创建 constants.py + logger.py
3. ✅ T14 - 安全加固

**Phase 2：数据层重构** — ✅ 全部完成
4. ✅ T03 - 重构 database.py（Event 模型+白名单+显式 init_db）
5. ✅ T04 - 创建 event_service.py
6. ✅ T05 - 重构 theme_manager.py（ThemeColors）
7. ✅ T06 - 提取 conversation_store.py
8. ✅ T07 - 提取 ai_client.py（AICallThread+fetch_models）

**Phase 3：服务层** — ✅ 全部完成
9. ✅ T08 - 创建 ai_service.py（依赖 T04+T06+T07 ✅）

**Phase 4：UI 层拆分** — 🔄 部分完成
10. ✅ T09 - 提取 canvas.py（WeekCanvas/DayCanvas → ui/canvas.py）
11. ✅ T10 - 重构 calendar_widget.py（EventService 替换 + ui/calendar_widget.py）
12. ✅ T11 - 重构 event_manager.py + event_dialog.py（迁移到 ui/ 包）
13. ✅ T12 - 重构 ai_chat_widget.py + sidebar.py + ai_settings.py（拆分为三文件）
14. ✅ T13 - 重构 main.py → ui/main_window.py + main.py 精简为入口点

**Phase 5：收尾** — ✅ 全部完成
15. ✅ T15 - 集成验证与清理

---

## 重构总结

**重构日期**：2026-07-15
**重构范围**：全项目模块化拆分，从单文件巨石架构重构为分层架构

### 架构变化

```
Before (巨石架构):                  After (分层架构):
├── main.py (1000+ 行)              ├── main.py (入口, 26 行)
│   ├── 日历 + AI + 主题 + 托盘     │   └── ui/main_window.py (窗口组装)
│                                   ├── ui/
│                                   │   ├── calendar_widget.py (日历)
│                                   │   ├── canvas.py (Canvas 渲染)
│                                   │   ├── event_manager.py (事项面板)
│                                   │   ├── event_dialog.py (编辑对话框)
│                                   │   ├── ai_chat_widget.py (AI 对话)
│                                   │   ├── sidebar.py (对话侧栏)
│                                   │   └── ai_settings.py (AI 设置)
│                                   ├── event_service.py (业务服务层)
│                                   ├── ai_service.py (AI 业务服务)
│                                   ├── database.py (数据库层)
│                                   ├── conversation_store.py (对话持久化)
│                                   └── ai_client.py (AI HTTP 客户端)
```

### 关键改动（T14-T15 收尾）

| 类别 | 操作 | 详情 |
|------|------|------|
| 🔒 安全 | 清空 API Key | data/config.json 中真实 Key 已移除 |
| 🔒 安全 | 创建 .gitignore | 排除 data/、build/、dist/、__pycache__/ 等 |
| 🔒 安全 | 修复 bare except | 8 处 bare `except:` → `except Exception` + 日志 |
| 🔒 安全 | 修复静默 pass | 5 处 `except ...: pass` → 添加 logger 记录 |
| 🧹 清理 | 删除废弃文件 | calendar_widget.py, event_manager.py, ai_chat.py |
| 📦 打包 | 更新 build.py | 添加 data/ 和 ui/ 目录打包配置 |
| 📦 打包 | 更新 Clender.spec | datas 添加 data 和 ui 目录 |

### 验证结果

- ✅ 全部 18 个模块导入无错误
- ✅ 语法检查通过（无 SyntaxError）
- ✅ 模块依赖链正确：UI → Service → Data
- ✅ 废弃兼容文件已清理（3 个文件）
- ✅ API Key 已从配置文件中移除
- ✅ .gitignore 生效
- ✅ 日志输出到 data/clender.log

### 剩余事项（历史记录）

- 下列事项已在 2026-08-02 的 Phase 6 完成：生成 spec 不再版本管理；自动化测试与 CI 已建立。
---

## Phase 6：Python 3.12 现代化与工程加固（2026-08-02）

输入文档：`doc/modernization-proposal.md`、`doc/modernization-design.md`

| 任务 ID | 任务名称 | 状态 | 依赖 |
|---|---|---|---|
| T16 | 统一 Miniconda Python 3.12.4 与标准构建 | ✅ 已完成 | T17 后清理 |
| T17 | 自动化测试与 CI 基线 | ✅ 已完成 | 无 |
| T18 | 配置安全与数据库加固 | ✅ 已完成 | T17 |
| T19 | AI 与对话链路加固 | ✅ 已完成 | T17、T18 |
| T20 | 日历逻辑提取与 UI 回归 | ✅ 已完成 | T17、T18 |
| T21 | 集成验证、清理与文档同步 | ✅ 已完成 | T16–T20 |

### 当前决策

- 唯一支持环境为 Miniconda base Python 3.12.4；移除 Win7/Python 3.8 全部资产。
- 使用标准库 `unittest` 建立测试，不新增测试框架依赖。
- Windows API Key 使用 Credential Manager（pywin32），非 Windows 仅支持 `CLENDER_API_KEY` 环境变量，禁止明文 JSON。
- 不修改或读取输出真实用户数据；测试全部使用临时目录和 mock。

### 修改前测试矩阵

- 模型/持久化：旧 JSON、UTF-8、空/坏数据、往返、单一 Conversation 表示。
- 配置/秘密：缺文件、坏 JSON、默认合并、旧明文迁移、凭据失败、不落盘。
- 数据库/服务：新旧 schema、CRUD、非法字段/时间、清空 end_time、索引、异常关闭。
- AI：JSON 变体、action/ID/时间验证、Token 预算、HTTP 成功/错误/重试/超时。
- 日历/UI：提醒/时间段、边界时间、相邻/重叠、空视图、信号、主题、offscreen。
- 构建/环境：Python 3.12.4、精确依赖、无 data 打包、无 Win7/3.8 残留、exe 冒烟。

### 已执行命令与结果

- 首轮 `unittest discover`：23 项中 9 失败、15 错误，证明回归用例覆盖现存问题。
- 修复后 `unittest discover`：35 项全部通过。
- 全部源码模块导入：通过。
- `python build.py --check`：通过。
- PyInstaller 标准构建：首次捕获用户 site-packages 权限问题，第二次捕获 conda DLL PATH 问题；隔离环境修复后通过。
- `dist/Clender.exe` offscreen 启动冒烟：运行 5 秒未退出，通过；测试数据已清理。

### Phase 6 最终状态

✅ T16–T21 全部完成。最终基线为 Miniconda base Python 3.12.4、35 项自动化测试、单一标准 PyInstaller 构建与安全运行时数据边界。

---

## Phase 7：API Key 保存与安全交付（2026-08-02）

| 任务 ID | 任务名称 | 状态 | 依赖 |
|---|---|---|---|
| T22 | API Key 保存、构建数据保护与 Git 提交 | ✅ 已完成 | T16–T21 |

### 修改前约束与测试

- 已建立正常、边界、异常、回归、构建数据保护和提交安全测试矩阵，详见 `T22-api-key-build-commit.md`。
- 不读取真实用户数据内容；只允许只读比较 `dist/data/` 的路径、大小、时间和哈希以证明构建未修改。
- 提交只包含工程文件；`data/`、`dist/data/`、日志、数据库、对话与生成物必须保持忽略且不得暂存。

### 实施与验证结果

- 修改前 9 项聚焦测试得到 2 失败、1 错误；修复后聚焦 11/11、全量 39/39 通过。
- pywin32 真实临时凭据测试确认字符串写入、UTF-16LE bytes 读取，以及持久凭据错误 1312 到会话凭据的安全降级；临时目标均已删除，现有 `Clender/API` 未触碰。
- 全模块导入、`build.py --check`、完整 PyInstaller 构建和隔离目录 exe offscreen 冒烟全部通过。
- `dist/data/` 构建前后保持 5 个文件、244469 字节；最终摘要为 `1DA6AFDF420E3F3F68C84062399C1DA4F0C5FA5BA36D6D504B628D8F74F87CB7`。
- `AGENTS.md` 已增加每次更改后完整构建、exe 冒烟、Git 提交和保护 `dist/data/` 的强制规则。

</task_progress>

---

## Phase 10：WebDAV 日程同步与静默自启动（2026-08-03）

输入文档：`doc/webdav-autostart-proposal.md`、`doc/webdav-autostart-high-level-design.md`、`doc/webdav-autostart-detailed-design.md`、`doc/prompt.md`

| 任务 ID | 任务名称 | 状态 | 依赖 |
|---|---|---|---|
| T38 | WebDAV 同步数据契约与核心服务 | ✅ 已完成 | 无 |
| T39 | 设置 UI、开机自启动与静默入口 | ✅ 已完成 | T38 controller 接口 |
| T40 | 集成、构建、exe 冒烟与提交 | ✅ 已完成 | T38–T39 |

### 已确认决策

- 仅在本地日程成功变更或用户手动同步时同步；不做启动同步和五分钟轮询。
- WebDAV 目录下固定使用 `clender-events.json`，仅 HTTPS + Basic Authentication。
- 密码明文保存在被忽略且视为敏感的 `config.json`；远端日程为未加密 JSON。
- 使用跨设备 UUID、UTC 更新时间、删除墓碑、逐事件 LWW 和 ETag 条件写。
- 自启动仅支持 frozen exe 的当前用户 HKCU Run；`--silent` 隐藏主窗口但保留托盘和已启用悬浮窗。
- 本轮按用户指示不使用 subagent。

### 测试门禁

- 严格正常、边界、异常和回归矩阵见 T38、T39 与详细设计第 10 节。
- 所有自动化先取得旧实现失败/缺失证据；HTTP、DB、注册表、配置均隔离或 mock。

### 当前证据

- 旧实现聚焦 23 项得到 3 failure、12 error，失败对应全部新增契约。
- 修复后同步/设置/入口集成聚焦 50/50；补强后最终全量 171/171。
- 全模块导入、`build.py --check`、静态与敏感扫描通过；未访问真实 WebDAV、配置、数据库、注册表或对话内容。
- PyInstaller 生成 45,582,138-byte exe（SHA-256 `FBCB0BAA…610CD`）；普通/静默双向 primary-secondary 隔离冒烟通过。
- `dist/data` 前后保持 5 文件、45619 字节、摘要 `3CBFF264…ED3E`。

### 下一步

- Phase 10 已完成；提交哈希见最终交付报告。

---

## Phase 8：桌面体验修复与今日悬浮窗（2026-08-02）

输入文档：`doc/desktop-experience-proposal.md`、`doc/desktop-experience-high-level-design.md`、`doc/desktop-experience-detailed-design.md`、`doc/prompt.md`

| 任务 ID | 任务名称 | 状态 | 依赖 |
|---|---|---|---|
| T23 | API Key 恢复 JSON 持久化 | ✅ 已完成 | 无 |
| T24 | Thinking 向下展开与视口锚定 | ✅ 已完成 | 无 |
| T25 | Windows 单实例与托盘唤醒 | ✅ 已完成 | 无 |
| T26 | 周/日视图重叠布局与可点击标识 | ✅ 已完成 | T27 详情组件 |
| T27 | 今日桌面悬浮窗 | ✅ 已完成 | T23 |
| T28 | 工程规则与架构文档同步 | ✅ 已完成 | T23–T27 |
| T29 | 集成验证、构建、双实例冒烟与提交 | 🧪 验证完成，待提交 | T23–T28 |

### 已确认决策

- API Key 不迁移旧凭据，由用户重新保存，之后只以 JSON 为来源。
- 同一 Windows 用户单实例；二次启动恢复并置前已有窗口。
- Thinking 点击标题保持视觉锚点，正文向下展开。
- 日历使用 lane 分栏；短提醒仅标识且可点击；极端重叠允许聚合选择。
- 悬浮窗采用推荐配置，并在主程序每次成功数据更改后立即刷新。

### 修改前测试矩阵

- 配置：Key JSON 正常/空/Unicode 往返、坏 JSON、原子失败、其他设置不丢 Key。
- Thinking：历史中部、首末、长短、多会话、坏链接、新消息滚底。
- 单实例：primary/secondary、托盘/最小化/最大化/modal、陈旧端点、入口副作用。
- 日历：2/3/N lane、相邻、短提醒、窄宽、overflow、纹理/文字矩形、点击命中、两主题。
- 悬浮：配置校验、跨日相交、端点、空列表、详情、几何、托盘、午夜、data_changed。
- 集成：全量测试、导入、Qt offscreen/截图、完整构建、隔离双实例 exe、dist/data 摘要和提交安全。

### 当前状态

- 三个只读审查子 agent 已分别完成配置/单实例、Thinking/日历、悬浮窗现状与风险审查。
- 用户已确认全部阻塞性产品决策；规划文档与 T23–T29 边界已建立。
- 修改前全量基线 39/39 通过。
- T23：旧实现 14 项中 5 失败；修复后配置/UI/AI 客户端 18/18 通过。
- T24：旧实现 4 项中 3 失败、1 错误；修复后聊天/UI/AI service 12/12 通过。
- T25、T26、T27 均已完成旧实现失败复现、实现、聚焦验证和主 agent diff 审查。
- T25：旧实现 12 项中 9 错误；修复后单实例/UI 13/13 通过，待实际 exe 双实例。
- T26：旧实现 11 项中 8 错误；修复后逻辑/Canvas 16/16 通过，待详情接线与可见平台复核。
- 独立审查补获并修复相邻短 marker 错误命中、获取模型沿用旧连接、托盘临时悬浮窗跨日不刷新；每项均新增旧实现失败用例。
- T27：核心旧实现 4/4 错误、主窗口旧实现 4 失败/3 错误；实现与接线后全量 89/89 通过。
- Light/Dark 周/日/悬浮窗隔离截图验证 lane、marker、overflow、纹理、文字和空态；Windows DWM/多屏/DPI 留作人工风险。
- 早期一轮悬浮 Qt 测试存在只读隔离缺口，可能读取开发态 config/DB、无写入；后续测试与截图均已统一隔离。

### 下一步

- 最终文档复核后再次执行完整构建/隔离双实例冒烟，配置指定全局 Git 代理，完成 staged 安全检查和提交；提交哈希由最终报告给出。

---

## Phase 9：悬浮快捷对话、双字号与日历间距（2026-08-02）

输入文档：`doc/floating-ai-typography-proposal.md`、`doc/floating-ai-typography-high-level-design.md`、`doc/floating-ai-typography-detailed-design.md`、`doc/prompt.md`

| 任务 ID | 任务名称 | 状态 | 依赖 |
|---|---|---|---|
| T30 | Phase 9 规划、决策与测试矩阵 | ✅ 已完成 | 无 |
| T31 | 双字号配置与集中 typography 基础 | ✅ 已完成 | T30 |
| T32 | 全 UI 字号迁移与遗漏静态门禁 | ✅ 已完成 | T31 |
| T33 | 周/日时间轴动态 gutter 与间距 | ✅ 已完成 | T31 |
| T34 | 无边框悬浮壳、拖缩、置底/置顶与时态高亮 | ✅ 已完成 | T31 |
| T35 | 悬浮事项双击编辑与类型转换 | ✅ 已完成 | T34 |
| T36 | 悬浮 AI 与主页面当前对话桥接 | ✅ 已完成 | T34 |
| T37 | 集成、视觉、构建、exe 冒烟与安全提交 | ✅ 已完成 | T31–T36 |

### 已确认决策

- 去除悬浮窗原生标题栏和内部日期标题，保留自定义拖动与边缘/四角缩放。
- 默认沉在所有普通窗口底层；置顶仅是当前进程状态，不跨重启持久化。
- 悬浮 AI 写入主页面当前对话历史，复用同一预算、QThread、解析、执行和持久化流程；悬浮窗不显示模型正文。
- AI 删除和批量操作不增加确认。
- 单击事项可参与拖动，双击直接编辑；支持 reminder/timespan 类型真实转换。
- 应用字号与悬浮字号分别为 8–20px；集中角色和静态扫描用于防止遗漏、错改与重复定义。
- 时间标签按字体度量，和网格/事项至少间隔 8px；保持 30px 每小时。
- 透明度允许真实 0%；用户既有暂存 CI 改动不纳入本轮提交。

### 当前状态

- 修改前全量基线：89/89 通过。
- 三个只读审查 subagent 已完成并返回文件触点、风险和测试建议。
- 所有阻塞性产品决策已由用户确认。
- Phase 9 三层设计、T30–T37 任务文件和控制提示已完成；生产实现按文件所有权分三批集成。
- 三个 subagent 分别完成 typography/Canvas、悬浮/AI、测试/服务层的失败用例、实现或独立复核；主 agent 完成桥接审查、发布验证和文档同步。
- 旧实现失败证据：typography 10 项为 2 failure/8 error；Canvas 动态 gutter 产生 10 个失败子用例；悬浮 logic/interaction 17 项为 8 failure/6 error；编辑/AI 13 项为 6 failure/8 error；Phase 8 兼容测试迁移产生 10 个预期 failure、0 个无关 error。
- 首轮整套红灯共运行 129 项，26 failure/22 error；既有 89 项基线路径保持绿色，未发现真实配置、数据库或网络隔离污染。
- Phase 8 旧断言迁移后整套红灯仍为 129 项，最终 36 failure/22 error，所有新增/迁移失败均对应待实现契约。
- 第一实现批次并行分配 T31 typography 基础、T35 EventService 类型转换基础、T36 AIChatWidget 外部提交基础；文件边界互不重叠。
- 第一批主审：typography/设置、类型转换、外部 AI、DB/AI/config/单实例共 52/52 通过；T31 完成，T35/T36 基础完成待 UI 接线。
- 第二批按文件所有权并行：T32 处理 AI/事项/侧栏字号，T33 处理 calendar/canvas 字号与 gutter，T34 处理 floating/app opacity/独立悬浮字号和交互。
- 主窗口已完成悬浮双击编辑、当前主 Conversation AI 提交和短状态 bridge；成功数据变更只统一刷新一次。
- 独立审查发现全局 `QLineEdit` QSS 覆盖悬浮输入字号；新增 8/20 与 20/8 双向真实控件回归，旧实现两个子用例失败，局部 objectName QSS 修复后通过。
- 主页面发起 AI 时的悬浮 busy 状态补充测试在旧实现为 2 failure/1 error；修复后主/悬浮两个入口共享 busy、完成和受限错误状态。
- 最终 133/133 unittest、全模块导入、`build.py --check`、静态字号门禁和 `git diff --check` 通过。
- Light/Dark × 8/20px 的周、日、悬浮共 12 张源截图与 4 张联系表人工复核通过；时间轴与事项至少 8px，current/next 和底部快捷输入可辨。
- 最终 PyInstaller 生成 45,555,401-byte exe，SHA-256 `4093EFFB380165A836815A1C47C024CD81E7DDBCFB5E2976909EE9C9DBBD5854`；隔离首实例存活、次实例退出码 0，最终无进程/临时目录残留。
- `dist/data` 构建与冒烟前后均为 5 文件、262248 字节、组合摘要 `9213D0AAC7AB2CFDA41AE4EB527E2F96ED677493155C06B2B724579D7DD9ADD6`。
- 用户预存 staged `.github/workflows/test.yml` 保持原样并从本轮路径限定提交中排除；提交哈希见最终交付报告。

### 最终状态

✅ T30–T37 全部完成。仅保留 Windows 可见桌面下的真正 0% 透明度恢复、置顶/置底、无边框多屏/DPI 拖缩人工风险，以及 current/next 60 秒巡检可能带来的边界延迟。

## Phase 11：PC 日程体验（2026-09-06）
设计与控制提示：doc/pc-experience-design.md。用户授权自主决策；android/ 保留原样。
- T41 表单与快速编辑：实现完成；13项聚焦及2项主窗口重试回归通过。
- T42 日历块、跨日与双击：实现完成；12项新测试及既有日历/主窗口回归通过。
- T43 悬浮视图、AI 输入与视觉：实现完成；54项聚焦通过，独立审查修复隐藏后预览和周表头字号。
- T44 集成发布：已完成；209/209全量、33模块语法/导入、build --check与明暗8/13/20px截图通过；完整PyInstaller及隔离普通/静默双实例通过，dist/data摘要一致，28文件staged检查通过；随本记录安全提交。exe SHA-256 `A5E9522A09B163918B2C9D051AA31095DE275FE32396E3F2850E890D8EF40C87`。

## 双端外观与背景（2026-09-06）
设计与控制提示：doc/appearance-design.md。用户授权功能不变前提下自主决策。
- T60 基线恢复/编排/集成发布：完成，双端构建/视觉/数据保护及提交审查通过。
- T61 PC背景与美化：完成，完整Windows发布验证通过。
- T62 Android背景与美化：完成，最终166 suites/1731 tests、111发布工具、65策略、完整签名APK/AAB与API26/36背景验收通过。
- T63 原创双端图标：完成，产物可复现；双端最终包及Android启动器图标验收通过。
- T61/T63 PC实现与独立修整完成；252/252全量、35模块、24张视觉、最终PyInstaller与两场景exe隔离冒烟通过，dist/data组合摘要完全一致。
- T62照片EXIF与旋转导入生命周期回归已通过；API26短JPEG读取兼容经真实平台证明，原失败夹具保持。
- 2026-09-07交付：PC exe SHA256 `9898dd5a…215486e`，Android APK `936a58ca…a24afbc5`。设备截图发现的深色普通文字继承问题已4RED→4PASS并重新全门禁/签名/设备复验。限制：仅两台专用模拟器英文界面实测，不宣称真机/厂商启动器/本轮Widget手动验收。详细失败、恢复、摘要及最终Git回执见T60和Git历史。
# T65 PC 本地应用更新完成（2026-09-07）

按用户新授权已用main当前PC源码更新dist/Clender.exe，252测试/导入/完整构建/隔离双场景通过，用户数据摘要不变。生产功能与Android零改动；结果与EXE哈希见T65任务，文档提交回执见Git日志/最终答复。
