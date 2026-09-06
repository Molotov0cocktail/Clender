# T48 Widget Refresh 主题文字颜色

状态：本颜色修复独立 PASS；主 agent 已确认 T47/T48 实现、构建与验收完成，全门禁完成。真实 RED、行为 GREEN、静态复验、最终签名审计与 API26 Dark 实机视觉闭环均完成。Git 交付为最后步骤，具体回执见最终答复/Git 日志，本文不声称已 commit 或 push。生产、测试及本文档写入冻结；本子任务未运行 Gradle/adb，以下执行证据由主 agent 提供。

## 最终 PASS 证据

- 专用颜色行为测试 10/10 GREEN（10.036s）；纯格式修复后 static 8 tasks / 35s PASS。
- 新签名 APK SHA-256：`5d90cbb3d271063ad7b9c3645eb94ede9180bde62c46bde109a09cc0c56a897e`，build/audit PASS。
- 主 agent 已实际查看最终 API26 Dark 截图 `android/.tmp/completion-final-widget-api26-r4-configuration.png`：Refresh 与 AI/Edit 同为清晰白字，原视觉缺陷已闭环。
- 全量 JVM 155 suites / 1611 tests，UI 57 suites / 665 tests，failure/error/skip 全部 0；CloseGuard、SQLiteConnectionPool、SQLiteDatabase leaked、RoomDatabase leaked 四项均 0。
- 完整门禁 96 tasks（26 executed、70 cached），5m39s，全 PASS；发布工具 fixtures 111/111（2.397s），foundation 49/49（3.852s），boundary 49/49（3.706s）。
- 最终同一 `5d90` APK：API26/36 phone、API36 tablet 的 core8 与实际 Widget 全部通过，API36 phone/tablet 最终 Drawer18 通过；8 台设备均完成最终安装/hash 验证。
- 增量复用仍明确绑定候选：API29/31/33 phone core 复用 `4cf`、Drawer 复用 `04`；API35 phone core 复用 `4cf` 加最终 Drawer18；API33 tablet core 复用 `c287` 加最终 Drawer18。P2C date/boot 复用 `c287`，不声称最终包重跑。
- API26 Widget 原 ADB 清临时 XML 失败保留；`completion-widget-api26-r4-resume-result.json` PASS 为只读确认已保存状态后续跑自动更新/冷启动的回执，不覆盖原失败。

以下保留 RED、首次静态失败与修复过程的时间顺序记录，其中当时的待验状态已由本节最终证据更新，不撤销或掩盖历史失败。

## RED 与实施记录

主 agent 实际运行专用 suite：10 tests / 10 failures，53s / 32 tasks（3 executed、29 cached），失败位于 `assertActionColor` 第101行。日志 `android/.tmp/completion-widget-refresh-color-red.txt`，完整 XML `android/.tmp/completion-widget-refresh-color-red-results`。经授权仅在 renderer 的 LARGE 分支增加 `views.setTextColor(R.id.widget_refresh, colors.foreground)`，与同组 AI/Edit 前景色一致。没有其它生产重构、测试重写、布局或依赖修改；写集仍为本文档、专用新测试及 renderer 单行。修复后尚未执行验证，不宣称 GREEN，等待主 agent 统一验收。

后续主 agent 实际复验：颜色行为测试 10/10 GREEN，耗时 10.036s；detekt PASS。首次组合命令仍因专用测试两处长行 ktlint 失败，整命令耗时 1m20s / 40 tasks（11 executed、29 cached），不能将该组合命令标为整体成功。主 agent 已仅对 assert 与 renderResponsiveMap 两处换行，未改变断言或行为；static 正在复验，结果待补。新签名产物及真实 API26 Dark 截图仍待主 agent，当前行为 GREEN 不代表最终视觉交付已验收。

## 目标与证据

04b468 候选 APK 的 API26 实际截图 `android/.tmp/completion-final-widget-api26phone-configuration.png` 显示 Dark Widget 上 AI/Edit 为浅色，Refresh 为近黑色而难以辨认。只读 renderer 确认 LARGE 分支设置了 widget_quick_ai 的 foreground，却未设置 widget_refresh。目标是使 Refresh 与同组动作采用相同主题前景色。

## 精确写集、接口与非目标

- 本文档。
- 新增 `android/app/src/test/java/com/molotov/clender/widget/WidgetRefreshColorTest.kt`。
- RED 后待授权仅修改 `android/app/src/main/java/com/molotov/clender/widget/WidgetRemoteViewsRenderer.kt` 的 LARGE 文字颜色绑定，预期一行。

不改既有测试、布局资源、依赖、字体大小、点击行为、Intent/owner、数据、刷新调度或功能；无接口/数据格式变化。progress 和发布总记录由主 agent 汇总。

## 测试矩阵

真实生产 RemoteViews render/apply 到 TextView，断言 currentTextColor 等于对应主题资源，且与 AI/Edit 一致；不以源码字符串或模拟 setter 为证据。

- API26/36；Light/Dark 显式主题分别覆盖系统日/夜，System 覆盖系统日/夜。
- CONTENT/EMPTY/UNAVAILABLE，确保空态和有限失败态的 Refresh 同样可读。
- 8/20sp 与透明度 0/100，文字前景仍不透明，透明度仅影响背景。
- API36 额外验证真实 responsive map 的 250×250 LARGE RemoteViews。
- reapply 同一 host 从 Light 切到 Dark 再回 Light，验证颜色实际更新。

非法主题由已有强类型/配置测试承担，不新增放宽；本修复不修改非法输入处理。风险是向不存在的 view ID 发 RemoteViews action，因此生产改动必须限定 LARGE 分支。未设置的主题颜色可能随 host 默认主题变化，测试使用显式资源期望避免依赖默认黑/白。

## 步骤、回滚与完成定义

主 agent 运行专用 suite 保留旧生产 RED 后授权一行补齐，再统一聚焦/static及最终签名包暗色截图复验。回滚仅撤销本任务文件与单行生产变更，不涉及用户数据。Done 要求主题矩阵绿色，最终 API26 Dark Refresh 与 AI/Edit 同样可读；未运行不得宣称 PASS。
