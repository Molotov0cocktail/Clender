# T75 双端 UI 精简与继续美化（2026-09-15）

## 背景与授权

用户在 T74（Windows UI 美化，本地验证完成待验收）之后追加本轮任务：

1. 继续美化 Windows 和 Android 端应用。
2. **Windows 端删除底栏（QStatusBar）**，因为其中的功能都可以在设置弹窗中解决。
3. **Android 端去除不必要的说明文字（尤其是设置界面）**，只保留必要的、确保用户知道怎么用的说明。
4. 素材手段不限（网络下载/自制/其他），但须原创或许可安全（沿用 T74 决策：全部原创绘制，不下载外部素材）。
5. 不影响最终产品功能的问题由代理自主决策，开发效率优先。
6. 完成后汇报，**用户最终确认后才发布 v1.3.1**；本轮不推送、不 Release、不改版本号（Windows 配置与 Android versionName=1.3.0/versionCode=2 均不动）。

基线：main/e3502bb（T74 提交），工作区干净。

## 目标

### Windows（Agent W）

1. 删除主窗口底部 QStatusBar 及其中的「设置」「日间/夜间」按钮与状态文字标签。
2. 状态消息去向：
   - WebDAV 同步状态仍通过 `AppSettingsDialog.set_webdav_status()` 展示（设置弹窗打开时）；其余写入 `logger`（`_log.info/warning`），不再在界面上显示瞬时状态条。
   - 提醒投递不可用/轮询失败改为 `_log.warning`，行为返回值契约不变（`_deliver_event_alert` 仍返回 bool，`has_system_tray` 不变）。
3. 主题快速切换按钮迁移到顶栏 header（ghost 角色，文案仍为「日间/夜间」，tooltip 保留），保持一键切换能力；「外观与设置」按钮保留。设置弹窗内的主题切换入口不变。
4. 顶栏与整体布局精修：底栏移除后 splitter 区域占满窗口，边距/间距协调；不改变 objectName 门禁内的既有文案与结构（`日程安排/编辑选中/删除选中` 等）。
5. 同步更新受影响测试：`test_appearance.py`、`test_panel_visual.py` 中对 MainWindow `_btn_settings`/状态栏的断言；`test_event_alerts.py` 中 `_status_label` 命名空间夹具。新增回归：主窗口无 QStatusBar、header 存在主题按钮且可切换主题、同步状态仍路由到设置弹窗。

### Android（Agent A）

1. 设置及相关界面说明文字精简（values 与 values-en 同步修改，语义一致）：
   - 删除 AI 分节的 `ai_embedded_contract` 展示行（UI 引用与资源一并移除，相关测试同步更新）。
   - 删除 `ModelCapabilityHint`（`ai_model_limits_received/unknown`）展示与资源（能力自动填充行为本身不变）。
   - 精简（保留必要使用说明，删除冗余解释）：`alarm_sound_hint`、`background_local_hint`、`alert_background_explanation`、`alert_alarm_explanation`、`alert_timer_explanation`、`alert_notifications_ready/blocked`、`alert_exact_ready/blocked`、`alert_channel_blocked`、`widget_opacity_explanation`。
   - 保留：全部错误/状态反馈文案、字段标签、确认对话框文案、关于页隐私说明、`ai_token_count_explanation`/`ai_context_explanation`（无障碍 contentDescription，非可见正文，仅可轻度精简）。
2. 设置界面美化（不改任何 testTag、草稿/dirty/保存流程、Keystore/秘密输入契约）：
   - Material3 Card 分组：应用页（外观/字号、背景、闹钟铃声、提醒权限）、AI 页（连接、生成参数、人格）、WebDAV 页（连接、操作），组标题统一样式。
   - 主题三按钮改为分段单选行（保留 `settings_theme_system/light/dark` testTag 与点击语义）。
   - 统一间距/圆角/标题层级，浅色与深色主题下均可读。
3. 事件表单/详情中的提醒说明随 strings 精简自动生效；不改动 AlertPermissionSection 的权限按钮结构与设置页 Intent。

## 非目标

- 不改数据库/Room/WebDAV schema/AI 协议/配置格式/权限/Manifest/依赖。
- 不改版本号、不签名发布 Release、不推送远程。
- 不触碰 `data/`、`dist/data/` 用户数据（只读完整性核对除外）。
- 不下载外部素材；视觉素材全部原创（QSS/矢量/Compose 主题）。
- 不重命名既有 objectName/testTag/门禁文案（本任务明确授权删除的除外）。

## 硬门禁（沿用 T74 清单）

Windows：muted/app_bg 对比度 ≥4.5；mask_opacity ≥0.55；rgba/transparent 替换链（frame_bg/list_bg 字面量）；静态裸字号扫描；TypographyScale 六角色与悬浮窗独立字号；Canvas gutter/命中契约；既有文案与 objectName；事件列表主题重着色契约；悬浮窗 windowOpacity/pin 契约。
Android：全部 testTag 与导航/脏草稿确认契约；秘密输入 charArray/擦除契约；release 签名策略；依赖锁与 offline strict；Room v3/WebDAV v1 不变；policy foundation/boundary 与发布夹具全绿；四类泄漏为零。

## 测试矩阵（先 RED 后 GREEN）

| 用例组 | 正常路径 | 边界 | 异常/非法 | 回归 |
|---|---|---|---|---|
| Windows 底栏删除 | MainWindow 无 QStatusBar；header 主题按钮切换 light/dark | 8px/20px 下 header 不裁切 | 无托盘时 `_deliver_event_alert` 返回 False 且不抛异常 | 同步状态仍到 `set_webdav_status`；既有 335 项全绿（含按授权更新的断言） |
| Windows 视觉 | Light/Dark × 8/13/20px 主窗口截图读图（QT_QPA_FONTDIR=C:\Windows\Fonts） | 960×600 最小窗口 | offscreen 冒烟 | rgba/transparent 链、action labels |
| Android 文字精简 | 被删字符串不再出现在 Compose 树；精简后字符串资源双语言一致 | 大字号 2x 不裁切 | 缺失资源编译失败即门禁 | 相关 UI/Robolectric 测试更新后全绿 |
| Android 设置美化 | 三 tab Card 分组渲染、主题分段选择可点击且状态正确 | 320dp 窄屏、2x 字号 | dirty/确认对话框行为不变 | testTag 全保留；既有 UI 套件全绿 |
| Android 全量 | verify-all（96 tasks）+ release fixtures + foundation/boundary | — | 四类泄漏 0 | 签名 APK/AAB 构建与审计 |
| 设备验收 | API36（或可用 AVD）安装签名包：设置三 tab 明/暗截图审查、无裁切 | — | — | 关闭并清理模拟器 |

## 实施步骤与分工

1. 主 agent：任务文档（本文件）+ progress.md。
2. 并行两个子 agent：
   - Agent W（Windows）：ui/main_window.py、tests（test_appearance/test_panel_visual/test_event_alerts/新增回归）；全量 Windows unittest + imports + build --check + 截图读图。
   - Agent A（Android）：strings（values/values-en 及相关资源文件）、SettingsScreen/背景/铃声/权限/AI 分节 Compose、相关测试；聚焦 RED→GREEN + 全套静态与测试链 + 签名构建。
3. 主 agent 集成验证：Windows 完整 PyInstaller + dist/data 完整性 + 隔离 EXE 普通/静默冒烟；Android verify-all 复核 + 发布夹具 + policy + 设备截图验收（如模拟器可用）。
4. 更新 AGENTS.md（根 + android/）、progress.md、本文件维护记录；`git config --global https.proxy http://127.0.0.1:7890` 后创建 Git 提交。不推送、不 Release。

## 风险与回滚

- 风险：状态栏移除后同步/提醒瞬时反馈不可见（缓解：设置弹窗仍显示 WebDAV 状态，托盘通知仍展示提醒，日志留痕）；Android 字符串删改触碰 policy/夹具（缓解：agent 全量跑 foundation/boundary/release fixtures）；Card 分组改动影响 Robolectric 布局断言（缓解：testTag 全保留，先跑聚焦套件）。
- 回滚：单提交 `git revert`；无数据迁移、无 schema 变化。

## 完成定义

- Windows 全量 unittest 零失败/错误/跳过；imports、build --check 通过；完整 PyInstaller + 隔离 EXE 双场景冒烟通过；dist/data 前后摘要一致；截图读图通过。
- Android verify-all 全绿、发布夹具与 foundation/boundary 全绿、签名 APK/AAB 审计通过、设备（或说明不可用原因）截图审查通过。
- AGENTS.md/progress.md/本文件更新，Git 提交完成；未推送、未 Release、版本号未动。
- 用户验收后才进入 v1.3.1 发布流程。

## 维护记录（2026-09-15，本地验证完成，待用户验收）

### Agent W（Windows，RED→GREEN）
- `ui/main_window.py`：删除 QStatusBar/`_status_bar`/`_status_label`/`_btn_settings`/`setStatusBar`/`_resize_status_buttons`；`_theme_btn`（ghost、tooltip「切换日间/夜间主题」）迁入 header，置于新属性 `_appearance_btn`（原「外观与设置」局部变量）左侧；`_apply_chrome_styles` 新增 `_resize_header_buttons(scale)`（QFontMetrics+setMinimumSize，无裸字号字面量）；`_poll_event_alerts`/`_deliver_event_alert` 改 `_log.warning`（返回值契约不变，不记录日程正文）；`_on_sync_status` 改 `_log.info`（保留 200 截断）且仍路由 `set_webdav_status`；`_on_date_selected`/`_on_data_changed`/`_on_remote_data_changed` 删除状态文字、刷新/同步逻辑保留；main_layout 边距与 splitter、rgba/transparent 链未动。
- 测试：新增 `tests/test_main_window_chrome.py`（6 用例：无 QStatusBar、header 主题按钮切换、无托盘投递返回 False、同步状态路由设置弹窗）；按授权更新 `test_appearance.py`、`test_panel_visual.py`（MainWindow 断言改 `_appearance_btn`/`_theme_btn`；AIChatWidget 的 `_btn_settings` 未动）、`test_event_alerts.py` 夹具。RED 证据：2 failures+5 errors → 聚焦 83/83 GREEN。
- 全量 341/341 unittest（335 既有+6 新增）零失败/错误/跳过；imports ok；build --check 通过。Light/Dark × 8/13/20px 6 张 offscreen 截图读图通过（QT_QPA_FONTDIR=C:\Windows\Fonts；dark in-place 切换月格白块为 T74 已记录的 deleteLater 截图伪影，新窗口直渲干净，非回归）。

### Agent A（Android，RED→GREEN）
- 文字精简（zh/en 同步）：删除 `ai_embedded_contract`、`ModelCapabilityHint`（`ai_model_limits_received/unknown`）及 `values{,-en}/ai_settings.xml`；精简 `alarm_sound_hint`、`background_local_hint`、`alert_background_explanation`、`alert_alarm_explanation`、`alert_timer_explanation`、`alert_notifications_ready/blocked`、`alert_exact_blocked`、`alert_channel_blocked`、`widget_opacity_explanation`（testTag 保留）、`ai_token_count_explanation`、`ai_context_explanation`。新增组标题字符串 `settings_group_*` 六条。错误/状态反馈、标签、确认对话框、关于页全部保留。
- 设置美化：三 tab 全部 Material3 Card 分组（surfaceVariant、16dp 圆角、组内 8dp/组间 16dp），9 个新组 testTag（settings_group_appearance/background/alarm_sound/alert_permissions/ai_connection/ai_generation/ai_prompt/webdav_connection/webdav_operations）；主题改单行三等宽分段（选中实心 Button，tags `settings_theme_*`、回调、enabled、≥48dp 保留）；既有 testTag/秘密输入 charArray/草稿 dirty/确认对话框/保存流程零改动。WebDAV 状态行移入操作卡（文案/tag 不变）。
- 测试与策略：新增 `T75SettingsRefinementTest.kt`（7 用例×2 SDK，RED 14/14 → GREEN）；按授权更新 `GlmThinkingHintTest`、`WebDavSettingsScreenContractTest`、`WebDavSettingsProductionIntegrationTest`（performScrollTo，断言未降低）；`tests/policy/test_ignore_and_boundaries.py` 增 3 条 T75 allowed_exact（T75 任务文档、tests/test_panel_visual.py、tests/test_main_window_chrome.py）。
- 门禁：testDebugUnitTest 全量 214 suites/2183 tests（UI 83/915）零失败/错误/跳过、四类泄漏 0；lintDebug/lintRelease/detekt/ktlintCheck PASS；release fixtures 112/112；policy discovery/foundation/boundary 各 109/109；完整 verify-all exit 0。TEST_GATE_FAILURE 4 起均修复留痕（关键：Card lambda 作用域导致 ApiKeyDraftScreenRegressionTest 失效，最终将 secretInputEmpty+LaunchedEffect 移入连接卡 lambda，T49 契约测试双向验证通过）。
- 签名产物（证书 SHA256 `62824893…44615f` 不变）：APK 1,877,153 bytes SHA256 `9af70e1e8a7983305c4df800940f752790bda0f2410d3119b6c3fe010df2ce1f`；AAB 4,990,734 bytes SHA256 `ff4ec083c21c7119fb6217e4abacbf8a92b9343e05e44520f73e319ca3cb9cce`。

### 主 agent 集成验证
- Windows 独立复核：341/341 unittest、imports ok、build --check 通过；完整 PyInstaller 生成 `dist/Clender.exe` 45,592,003 bytes，SHA256 `CD45E36C8F1C290513F1D1647A6532B00A49C3E2EAC6C832228D08F59A22AF4B`；dist/data 构建前后均 5 文件/167,303 bytes/组合摘要 `1758827fec16fa9962b9e637d9231ae33ce2f5c4b88d27bee5b9675981c6cc04` 完全一致；隔离 EXE 冒烟 cycle-01 normal→silent 与 silent→normal 双场景 readiness/primary_survival/secondary_exit0/quiescence/cleanup 全真，exit 0，零残留进程。
- Android 设备验收：API36 模拟器（clender_api36_t67，headless）安装签名 APK（装后逐字节摘要复核一致），设置三 tab × Light/Dark 共 22 张截图逐张读图通过——Card 分组、主题分段、精简短文案、无「日程操作指令已内嵌」与模型能力提示行、无裁切/重叠/对比度问题；导航首轮一次 needle 语言误配已纠正重跑。清理回执：uninstall Success、emu kill、`adb devices` 为空、无模拟器残留进程；截图证据保留 android/.tmp/t75-*。
- 版本号未动（Android 1.3.0 (2)、Windows 无版本字段变更）；未推送、未 Release；待用户验收后进入 v1.3.1 发布流程。

### 遗留风险
- Windows 瞬时同步/提醒状态不再上屏，仅日志与设置弹窗（授权内）；in-place 主题切换截图伪影为既有现象。
- Android Card 为不透明 surfaceVariant，卡片区域遮挡自定义背景（设计预期）；320dp/2x 字号下主题分段文字可换行为两行（高度≥48dp、可点击已断言）；未验厂商真机；WebDAV 状态行位置移入操作卡。
