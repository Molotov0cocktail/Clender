# PC 日程体验改进提案与设计（2026-09-06）

## 目标与决策
修复时间输入退出、短事项误画线；支持双击编辑、四数字时间、跨天日期、悬浮周/日/事件视图及 AI 换行，统一桌面视觉。用户授权自主选择不影响功能的细节。
合法 start < end 正常保存；非法顺序/相等弹窗并保留表单。跨天存一个事件，沿用时间字符串、UUID、WebDAV schema，按每日半开区间切片，午夜结束不产生次日零长事件。不改 android/、数据格式、依赖或真实数据。

## 架构与接口
EventDialog 接受前调用 EventService 校验；EventManager 捕获保存异常。独立起止日期默认聚焦日；时间旧值以 placeholder 显示，HHmm 自动填位，未修改保留原值，部分/非法输入拒绝。
calendar_logic 负责跨日切片；日/月/周采用相交查询。Canvas timespan 画色块，空间足够才画字，只有 reminder 使用红线。Canvas/CalendarWidget 增加 event_edit_requested(tuple IDs)，单击延迟至双击间隔后预览，双击取消预览。主窗口复用编辑保存链路。
悬浮窗保留事件列表，增加日/周简化视图，默认今天，会话内切换无需持久化；event_activated(int) 用于预览，双击编辑保留，拖动不预览。复用 Canvas/纯逻辑及独立字号。
AI 多行输入自动折行、有界增长，Enter 发送、Shift+Enter 换行，兼容 IME/busy。视觉沿用 typography 与 Light/Dark，统一圆角、间距、边框及柔和配色。

## 分工与控制提示
主 agent 编排、审查、集成、文档及发布。子 agent 先读契约/任务/源码，先写测试记录旧实现失败，再实现与验证；只编辑所属文件与独立测试/任务，不提交、不构建、不读取真实 data，不覆盖其他 agent。接口变化通知主 agent。
T41：EventDialog/EventManager/新 time_input 和测试。T42：calendar_logic/Canvas/CalendarWidget/EventService/main_window 和测试。T43：daily_floating_window/ai_chat_widget/theme_manager/新 chat_input 和测试。T44：主 agent 集成与发布。

## 实现前测试矩阵
| 区域 | 正常 | 边界 | 非法/异常 | 回归 |
|---|---|---|---|---|
| 表单 | 保存、HHmm、列表双击 | 0000/2359/跨月年/未改值 | 倒序/相等/2460/部分输入/SQLite失败 | 类型转换/取消不写库 |
| 日历 | 色块/文字/红线/双击 | 1/30/59分钟/午夜/多日/重叠 | 坏日期/缺结束/无效ID | 命中/预览/月计数/8–20px |
| 悬浮 | 三视图/预览/编辑 | 空/跨日/周界/窄窗 | 无效事件/拖动误触 | pin/拖缩/AI/午夜/独立字体 |
| AI/主题 | 折行/Enter/Shift+Enter | 长文/空/IME/最大高度 | 无配置/busy/超时/非200/坏JSON | 对话归属/危险输入/明暗 |
| 发布 | 全量/导入/构建 | 隔离普通/静默exe | 失败不发布 | dist/data摘要/android原样 |

## 风险、回滚与验收
关注 Qt 槽异常、单双击冲突、跨日重复、窄视图。Git revert 回滚，无 schema 迁移。需旧实现失败证据、全量通过、Light/Dark 离屏视觉、完整 PyInstaller、隔离 exe 冒烟、数据完整性一致、契约/任务同步与提交。DWM/多屏和真实网络留人工验证风险。
