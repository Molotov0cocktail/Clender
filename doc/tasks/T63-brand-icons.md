# T63 双端原创图标
最终完成：6个图标产物可复现、4项图标测试及双端完整构建通过；API26/36最终启动器图标截图复核正常。PC文字按钮及主题回归随252项全量通过，包与提交见T60。
目标：原创蓝紫日历勾选，两端窗口/托盘/launcher/安装包统一。非目标：外部素材/依赖/业务改变。
文件：app_icon.py、main.py、icon.ico、assets/clender-icon.svg、assets/generate_icons.py、Android drawable/ic_launcher_*.xml、mipmap-anydpi/ic_launcher.xml 与 Manifest、tests/test_app_icon.py、tests/test_startup_entry.py；旧v26/v33资源及无用drawable已移除。
接口：app_icon.create_app_icon()返回无外部文件依赖QIcon；T61接主窗口/托盘。
步骤：先失败测试；矢量与多尺寸ICO；Androidadaptive/monochrome；聚焦检查。
测试：16/32/256像素与透明度、明暗、离屏与启动回归；完整矩阵见doc/appearance-design.md。
风险：小尺寸辨识/adaptive裁切。回滚Git revert，无数据影响。
完成定义：源可复现、双端一致、聚焦通过后主agent构建。状态：实现与聚焦完成，交由 T60 集成构建。

## 实施与验证
### Android lint 集成修正
主 agent 全量 lint 已给出失败证据：minSdk26 下 mipmap-anydpi-v26 冗余、旧 drawable/ic_launcher 未使用、基础 adaptive 缺 monochrome。追加矩阵：唯一 mipmap-anydpi/ic_launcher 同时包含 background/foreground/monochrome；v26/v33 和旧 drawable 不再存在；生成后重复运行产物相同。先改本地资源契约测试复现失败，再移动生成目标与删除冗余资源，不抑制 lint，Gradle/PC 完整构建仍由主 agent 集成。
结果：新资源契约修改前 3 pass/1 error（基础资源不存在）；修改后 4/4（0.033s）。生成器现在输出6个产物，重复生成SHA256完全一致，不再输出旧三资源。最终路径为 android/app/src/main/res/mipmap-anydpi/ic_launcher.xml；下方“8产物”和 v26/v33 描述仅为 lint 前历史阶段。未运行 Gradle/安装APK。
### 追加：PC 缺字方框清理
主 agent 截图复核发现标题/工具按钮 emoji 缺字；新增范围仅 ui/event_manager.py、ui/ai_chat_widget.py、ui/sidebar.py、tests/test_ui_action_labels.py。静态标题、动作、状态与事项类型改文字，聊天内容/业务结果成功匹配保持原样。工具文字按字体宽度适配。
测试矩阵：正常三个面板文字可读及按钮原动作；边界 Light/Dark 与 8/20px 文字宽度；异常无 API 仍禁止发送、空选中仍提示；回归用户标题/消息 emoji 保留、事项 ID 与新建/侧栏信号不变。先失败，再实现，仅聚焦验证，由 T60 构建发布。
追加截图 Bug：事项已有 item 的前景色在切换主题后滞留旧值；先测试 Light→Dark 颜色更新且 selection 保留，不查库；空列表 placeholder 使用新主题 muted 色。实现只补 Qt.UserRole+1 保存类型，apply_theme 原位更新笔刷。
验证：新增四项修改前全部失败，其中颜色回归 #d63031 != #ff959c；修改后 4/4（0.035s），既有 AI thinking 5/5（0.077s）。文字按钮按 QFontMetrics 宽度加 16px 内边距，日夜8/20px均验证；用户 emoji/HTML字面内容原样保留，AIService成功匹配和错误消息不改。

- 原创白色日历/青色勾选，蓝紫渐变底；共用 MARK_PATHS 与 safe-zone 缩放，Qt 无字体/资源文件依赖。ICO 16/24/32/48/64/128/256 七个 PNG 帧；Android API26 adaptive、API33 monochrome、既有 drawable 保留向后引用兼容。
- `python -m unittest discover -s tests -p test_app_icon.py -v`：修改前 4 errors（缺 module/ICO/adaptive资源），实现后 4/4（0.048s）。覆盖独立 cwd/资源不可读、边缘透明、各尺寸可解码、非法size拒绝及 Manifest/adaptive/monochrome 契约。
- `python assets/generate_icons.py` 可复现 8 个产物，二次生成逐字节SHA256完全一致；无新增依赖。Qt 512px PNG视觉复核白色边框/青色勾选清晰，背景渐变和透明角正常。
- `test_startup_entry.py` 原测试 mock QApplication 不创建 Qt 环境，新增图标调用令单独执行提前中止（无 Ran 汇总，不视为通过）。修正测试边界 mock create_app_icon 并检查 setWindowIcon；主入口仅 primary 成功后创建图标。修正后 startup 3/3（0.006s）、single_instance 8/8（0.844s）。未改运行业务和真实数据。
- app_icon/generator/test py_compile、git diff --check 通过。全量、PyInstaller/EXE 与 Android 构建由 T60 统一运行；未跑 Gradle、不提交、不访问签名和真实 data。
- 风险：Android launcher 主题着色/裁切最终由设备决定；矢量标记已置于中央安全圈内，但设备截图仍由集成复核。
- 发布lint补充：删除旧XML后，磁盘上空的mipmap-anydpi-v26目录仍被Lint视为冗余配置；主agent逐一验证绝对路径位于res且目录为空后，仅删除旧v26/v33空目录。生成器已经只生成新的mipmap-anydpi资源，Git不记录空目录。
