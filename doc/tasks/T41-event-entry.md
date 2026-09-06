# T41 表单与快速编辑

目标、非目标、接口/数据影响、风险、测试矩阵、回滚、完成定义见 `doc/pc-experience-design.md`。
影响文件：ui/event_dialog.py、ui/event_manager.py、新 ui/time_input.py（如需）、tests/test_event_entry.py。
步骤：先写时间顺序/跨天/四数字/异常弹窗/列表双击失败测试；实现；聚焦验证与 diff 审查。仅修改所属文件。
状态：实现及聚焦验证完成，等待主 agent 集成/完整构建/提交。

## 实施记录
- 修改 ui/event_dialog.py、ui/event_manager.py；新增 ui/time_input.py、tests/test_event_entry.py。
- EventDialog 在 accept 前调用 EventService.validate_event，倒序/相等、部分/非法时间弹窗保留表单。正常 start < end 合法，不应禁止。
- 起止日期独立，新事项默认聚焦日；编辑完整保留跨日日期；提醒转时间段默认采用原开始日。保持一个事件及原数据契约。
- TimeInput 是 UI 层 QLineEdit，只依赖 Qt/标准库；setTime/time 读写 QTime。原值 placeholder，空白保持原值，输入四位 HHmm 自动转 HH:mm；非法范围和不完整值拒绝。
- 事件列表双击直接编辑、跨日标签包含日期。保存 ValueError/TypeError/SQLite/OSError 不逸出 Qt 槽，失败重新打开同一表单保留输入；成功才刷新并发 data_changed。
- 表单统一间距、圆角和明暗主题，列表调整项目间距和折行；无新增裸字号。

## 旧实现失败证据
命令：`& 'C:\Users\30910\Miniconda3\python.exe' -m unittest discover -s tests -p test_event_entry.py -v`。
首次 8 项测试为 3 failure、4 error、1 通过：非法顺序没有 warning、双击无编辑、旧时间实值非 placeholder、缺少结束日期、QDateTimeEdit 无文本快速输入、sqlite3.OperationalError 从添加槽逸出。用户描述的合法 start < end 基础表单路径正常通过；已修复可证实的非法输入/存储异常退出风险，未将合法时间拒绝。

## 验证
- 同一聚焦命令最终 13/13 通过：0000/2359/0930、空白原值、部分/非法、倒序/相等、跨年往返、提醒转换、成功/取消信号、SQLite失败/丢失事件、同表单重试、双击信号、Light/Dark grab offscreen 冒烟。
- `git diff --check` 通过（仅已有 LF/CRLF 提示）。
- 测试 mock config 和 EventService，无真实数据/网络/Android 修改。
- 全量、构建、exe启动及AGENTS/进度/提交由主 agent 集成阶段执行。本 agent 未构建或提交。

## 风险与回滚
独立日期由用户显式控制；不自动猜测跨日。原始时间空白即保留，用 placeholder 和 tooltip 告知。Windows 可见桌面输入法/日期选择器人工验证仍由发布阶段补充。回滚这些源码/测试文件即可，无 schema 或数据迁移。

## 主窗口集成补修
主 agent 授权接管 `ui/main_window.py::_on_floating_event_edit_requested` 单方法，补齐日历/悬浮共用编辑链路的保存失败保留同表单重试，成功只刷新一次，失败取消不刷新。其余主窗口代码未修改。
先新增 `tests/test_pc_edit_retry.py` 两个回归，旧实现均因 exec_ 只调用一次而失败；实现后 2/2 通过。同步 `tests/test_floating_ai_edit.py` 的 EditDialogStub 仅改为首次结果、随后拒绝，避免错误分支无限接受；该文件 16/16 通过。
命令均使用指定 Python 的 `-m unittest discover -s tests -p test_pc_edit_retry.py -v` / `-p test_floating_ai_edit.py -v`。读写服务全 mock，既有类型转换用临时数据库。
