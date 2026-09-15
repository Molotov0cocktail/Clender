# T74 Windows UI 美化（2026-09-15）

## 目标

用户反馈当前应用画面较为简朴，要求对 UI 和各界面进行美化，尤其是 Windows 端。本任务对 Windows PyQt5 客户端做一轮系统性视觉升级：

1. 设计令牌体系：theme_manager 扩充语义色板（渐变强调色、表面层次、柔和边框、网格线、聊天气泡底色），双主题成对维护。
2. 全局 QSS 升级：按钮角色化（primary/danger/info/ghost 动态属性选择器）、渐变主按钮、pressed/hover/disabled 完整状态、复选框/单选框指示器、菜单圆角、滚动条细化、输入框 focus 环。
3. 日历画布：事件块圆角+左侧色条+柔和填充、现代化 EVENT_COLORS 双色板（含暗色变体）、网格线主题化、提醒 marker 精修。
4. 月视图单元格卡片化、导航/分段切换按钮精修、周列头 chip 化。
5. 主窗口品牌顶栏（图标+标题）、状态栏与 splitter 精修。
6. AI 聊天区真气泡化（HTML 表格背景色块），token 条颜色改走主题键。
7. 侧栏选中态、事件列表按钮角色统一、四个对话框统一区块标题/按钮角色/间距。
8. 悬浮窗仅随主题令牌自动获得新配色，不做结构改动。

## 非目标

- 不修改 Android 端（T60–T63 已 Material 3 化；改动需全套 Android 验证链，性价比低，留待用户确认后续任务）。
- 不修改数据库/WebDAV schema/AI 协议/配置格式/权限；无任何数据契约变化。
- 不修改版本号与发布产物（v1.3.1 待用户验收后另行发布）。
- 不引入新依赖、不下载外部素材（全部原创 QSS/矢量绘制，规避许可与离线风险）。
- 不改图标（app_icon/generate_icons 受 test_app_icon 门禁锁定，现状已是品牌渐变）。
- 不重命名任何测试依赖的私有属性、objectName、文案。

## 影响文件

| 文件 | 变更 |
|---|---|
| theme_manager.py | 新增令牌键（见契约）、全局 QSS 升级 |
| constants.py | EVENT_COLORS 现代化 + 新增 EVENT_COLORS_DARK |
| ui/canvas.py | 事件块/marker/网格线绘制精修 |
| ui/calendar_widget.py | 月格/表头/导航/周列头样式 |
| ui/main_window.py | 品牌顶栏、splitter、状态栏 |
| ui/sidebar.py | 选中态、新建按钮 |
| ui/event_manager.py | 按钮角色属性化、列表卡片化 |
| ui/ai_chat_widget.py | 气泡化、token 条主题化 |
| ui/event_dialog.py / event_detail_dialog.py / app_settings.py / ai_settings.py | 统一角色按钮与区块样式 |
| ui/daily_floating_window.py | 仅根 QSS 微调（不动字号体系/结构） |
| background.py | 硬编码底色改走主题键 |
| tests/ | 新增 test_theme_tokens.py、test_calendar_visual.py、test_panel_visual.py、test_dialog_visual.py；扩充 test_canvas.py THEME 夹具 |

## 令牌契约（Agent 间接口，双主题各一份，值由实现 agent 决定）

新增键（追加，不改既有键含义；既有键可调整值但必须保持对比度门禁）：

```
theme_name                 # 'light'/'dark'，Canvas 选择色板变体
accent_gradient_start      # 主按钮/强调渐变起点
accent_gradient_end        # 渐变终点
primary_pressed            # 主按钮按下态
surface_raised             # 卡片/凸起表面
surface_sunken             # 凹陷区（输入区/列表底）
border_soft                # 更浅的分隔边框
grid_line_color            # 画布整点网格线
grid_line_minor_color      # 画布半点网格线
event_block_text           # 事件块文字色
chat_user_bubble_bg        # 用户气泡底
chat_ai_bubble_bg          # 助手气泡底
chat_think_bubble_bg       # think 折叠区底
success_soft / warning_soft / danger_soft   # 状态条/徽标柔和底
```

constants.py：`EVENT_COLORS`（12 色，现代化调和色板）、新增 `EVENT_COLORS_DARK`（12 色，暗底可读变体）。

Canvas 契约：theme dict 读取 `theme_name`、`grid_line_color`、`grid_line_minor_color`、`event_block_text` 时需 `theme.get(key, fallback)` 或同步扩充 test_canvas THEME 夹具（本任务选择扩充夹具）。既有几何契约（gutter 公式、命中、纹理避让、_paint_regions）不变。

## 硬门禁（所有 agent 必须遵守，来自现有测试）

1. `muted_color` vs `app_bg` 双主题对比度 ≥ 4.5（test_appearance）。
2. background.py `mask_opacity` ≥ 0.55（系数 0.40/0.45 不得调低）。
3. rgba/transparent 替换链：面板 QSS 必须保留 `theme['frame_bg']` 字面量，列表/聊天区 QSS 必须保留 `theme['list_bg']` 字面量（main_window._apply_background_surfaces 字符串 replace；test_appearance 断言 rgba/transparent）。
4. 静态裸字号扫描：生产代码禁止 `font-size: 数字px/pt` 字面量、`QFont(x, 数字)`、`.setPixelSize(数字)`；一律走 typography scale 插值。
5. TypographyScale 六角色名/顺序/夹取不变；悬浮窗四控件字号独立、`floatingAiInput` objectName 唯一、悬浮窗禁 QTextEdit/QTextBrowser。
6. Canvas gutter/命中/纹理/构造签名 `(blocks, timeline_height, per_hour, theme)`；周视图布局嵌套 content[0]→container[0]→hdr_row[0]=spacer 宽=gutter±1。
7. 文案与结构：`日程安排/编辑选中/删除选中/对话/设置/清空`、列表项 `提醒 HH:MM␣␣标题`、`上一页` accessibleName、AI 头部按钮宽 ≥ 文本+16、`settingsScroll` footer 结构、全部既有 objectName。
8. 事件列表主题重着色不重查库、item 前景色 == 主题键值、选中不丢。
9. 图标 7 尺寸/角透明/中心不透明（不动图标即天然满足）。
10. 悬浮窗 windowOpacity 契约、pin 行为不变。

## 测试矩阵（先 RED 后 GREEN）

| 用例组 | 正常路径 | 边界 | 异常/非法 | 回归 |
|---|---|---|---|---|
| 令牌（test_theme_tokens.py） | 新键双主题齐全、值为合法 hex | 渐变起≠止、气泡底与文字对比可读 | ThemeColors 缺键仍 AttributeError | 既有 41 键不删、muted/app_bg ≥4.5 复算 |
| 画布（test_calendar_visual.py + test_canvas 夹具） | 事件块绘制含左色条与圆角、dark 用 EVENT_COLORS_DARK | 8px/20px 字号 gutter 不变 | 坏 block 跳过不崩 | 既有 test_canvas/test_calendar_experience 全绿 |
| 月视图 | 单元格 QSS 含新令牌、today/selected 状态色正确 | 560×480 小屏 | — | date_selected 信号、标记刷新 |
| 面板（test_panel_visual.py） | 按钮 btnClass 动态属性、气泡 HTML 含 bubble_bg、token 条走主题键 | 双主题切换后属性/颜色刷新 | 无背景图时 rgba 不出现 | rgba/transparent 链、action labels、按钮宽度门禁 |
| 对话框（test_dialog_visual.py） | 保存=primary 角色、删除类=danger、区块标题样式 | 双主题 | 保存失败留在窗口 | settingsScroll 结构、objectName、webdav 控件 |
| 全量 | 284 项既有 unittest 全绿；新增用例全绿 | Light/Dark × 8/20px offscreen 截图人工（agent 读图）审查 | QT_QPA_PLATFORM=offscreen 冒烟 | imports、build --check、PyInstaller、隔离 EXE 普通/静默双实例、dist/data 摘要前后一致 |

## 实施步骤与子 agent 分工

1. Agent A（设计系统，先行串行）：theme_manager.py 令牌+全局 QSS、constants.py 色板、background.py 底色、tests/test_theme_tokens.py。
2. 并行三 agent（依赖 A 的键名契约）：
   - Agent B（日历）：ui/canvas.py、ui/calendar_widget.py、tests/test_canvas.py 夹具、tests/test_calendar_visual.py。
   - Agent C（面板）：ui/main_window.py、ui/sidebar.py、ui/event_manager.py、ui/ai_chat_widget.py、tests/test_panel_visual.py。
   - Agent D（对话框/悬浮）：ui/event_dialog.py、ui/event_detail_dialog.py、ui/app_settings.py、ui/ai_settings.py、ui/daily_floating_window.py、tests/test_dialog_visual.py。
3. 主 agent 集成：全量 unittest、imports、build --check、Light/Dark×8/20px 截图读图审查、修复集成问题。
4. 完整 PyInstaller 构建 + dist/data 完整性 + 隔离 EXE 普通/静默冒烟。
5. 更新 progress.md、AGENTS.md，Git 提交（不推送、不 Release）。

各 agent 必须先提交失败测试（RED）证据再实现（GREEN），返回：改动文件、命令、结果、失败与修复记录、遗留风险。

## 风险与回滚

- 风险：全局 QSS 改动波及悬浮窗/对话框既有外观测试；气泡 HTML 改动可能破坏 toPlainText 契约；rgba replace 链对 QSS 字面量敏感。缓解：硬门禁清单随任务下发，各 agent 跑既有全量测试。
- 回滚：本任务全部为本地提交，`git revert` 单提交即可；无数据迁移、无 schema 变化、无远程操作。

## 完成定义

- 新增+既有全部 unittest 通过（零失败/零错误/零跳过）；imports、build --check 通过。
- Light/Dark × 8/20px 截图审查通过（无对比度、裁切、错位问题）。
- 完整 PyInstaller 构建成功，dist/data 前后摘要一致，隔离 EXE 普通/静默双实例冒烟通过。
- progress.md、AGENTS.md 更新，Git 提交完成，未推送、未 Release。
- 用户验收后才进入 v1.3.1 发布流程。

## 维护记录（2026-09-15，本地验证完成，待用户验收）

子 agent 分工执行：A 设计令牌/全局 QSS/色板（串行先行），B 日历画布与月视图，C 主窗口/侧栏/事项面板/聊天气泡，D 对话框与悬浮窗（并行），主 agent 集成修复与最终验证。

- Agent A：theme_manager 双主题各 +16 令牌键（theme_name/accent_gradient_*/primary_pressed/surface_raised/surface_sunken/border_soft/grid_line_color/grid_line_minor_color/event_block_text/chat_*_bubble_bg/*_soft），light primary 对齐全品牌 #4968e8；全局 QSS 增加 btnClass 角色选择器（primary 渐变/danger/info/ghost）、indicator、QMenu、focus 环；constants 换 12 色现代色板并新增 EVENT_COLORS_DARK；background 底色改读主题键。RED 3 failures/11 errors → 12/12 GREEN。
- Agent B：canvas 事件块圆角 6+alpha225 填充+左 3px 色条+event_block_text 文字色、dark 用 EVENT_COLORS_DARK、整点/半点网格线主题化、marker 加浅色环与 RoundCap；月格卡片化、导航圆形、分段/今天 pill、周列头 chip；test_canvas 夹具 +6 键。RED 15 failures → 43/43 GREEN。
- Agent C：品牌顶栏（图标+Clender/我的日程）、按钮角色化（add=primary/edit=info/delete=danger/头部与状态栏=ghost/send=primary）、聊天气泡 table bgcolor 方案（user 右 85%/assistant 左 85%/think 左 90%）、token 条三档改主题键、sidebar 选中态；rgba/transparent 替换链零改动保持。RED 9 failures+1 error → 42/42 GREEN。
- Agent D：四对话框区块标题（objectName *Section + 3px accent 竖条）、OK/Save/settingsSave=primary、Close=ghost、surface_sunken/border_soft 输入面；悬浮窗仅根 QSS 令牌化（字号体系/结构/opacity 零改动，回归用例锁定）。RED 11 failures → 83/83 GREEN；并修复一处预存在 ai_settings 大字号高度压线（人格编辑器 100→110px）。
- 主 agent 集成：offscreen 截图初版全空白定位为环境字体库空（QT_QPA_FONTDIR=C:\Windows\Fonts 解决，非产品回归）；周视图月格重叠为 deleteLater 截图伪影（HEAD 即存在，真实事件循环瞬消，脚本 flush DeferredDelete 后干净）；20px 下事项面板三按钮裁切修复为两行栅格（添加事项整行+编辑/删除并排）。
- 最终验证：335/335 unittest（284 既有+51 新增）零失败/错误/跳过；35 模块 imports ok；build --check 通过；完整 PyInstaller 生成 45,590,808 bytes EXE，SHA256 `86592e76761a9b9b8ab7ed879d6ad9023e5345e28f3d159294d2647f40aa6dd2`；dist/data 构建前后均 5 文件/167951 bytes/组合摘要 `13bde8e6b2ff25438321798251355a33787619ffb894686c72e315aa186aa119` 完全一致；隔离 EXE 普通→静默、静默→普通两场景 readiness/primary_survival/secondary_exit0/quiescence/cleanup 全真，结束零 Clender 进程。
- 视觉审查：Light/Dark × 8/13/20px 主窗口月/周/日/聊天 24 图 + 事件表单/应用设置/AI 设置/悬浮窗 14 图读图通过；气泡、色条、圆角、区块标题、渐变按钮均按设计呈现，无裁切/错位/对比度问题。
- 限制：offscreen 不渲染彩色 emoji（组合框 🎉 显示为方框，真实桌面有 Segoe UI Emoji 不受影响）；气泡圆角受 QTextBrowser 限制为直角色块；20px 周视图需横向滚动（表头随滚同步，既有行为）。
- 未推送、未 Release；版本号不动，待用户验收后随 v1.3.1 发布流程处理。
