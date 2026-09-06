# T61 PC背景与界面
目标：自定义背景/日夜遮罩/强度与界面便捷美化。非目标：业务/DB/网络改变。
文件：constants.py、theme_manager.py、ui/main_window.py、ui/app_settings.py、ui/calendar_widget.py（刷新时保留主窗口背景面板属性）、新背景模块和独立tests。
接口、数据、风险、回滚、先行测试矩阵：doc/appearance-design.md。
步骤：读源码/测试；新增失败用例；实现校验、画布、设置、主题接线；聚焦验证与记录。
完成定义：配置往返/异常回退、明暗/字号边界、信号/offscreen通过。状态：实现与聚焦验证完成，交主agent全量/构建/提交。

增量验收：日历刷新/月周日切换不得重置背景；主窗口/日历导航/设置入口不依赖emoji字体，8px与20px文字按钮不裁切。设置提示说明图片移动删除回退纯色。根因：日历每次渲染重写样式，使用主窗口注入的backgroundSurface属性保持外观。风险：属性仅由主窗口设置，独立日历/悬浮日历维持原始背景。

## 实现与证据
- 新增background.py：background_image空字符串默认、background_strength严格整数0–100默认60；32MiB/2400万像素输入上限，最长边1920解码、单图片按路径/mtime/大小缓存、cover裁剪；坏图/缺失/异常回退纯色。日间蒙板至少55%、夜间60%，不改变文字透明度；不新增灰度/模糊控件，避免额外渲染开销与过多选项。
- 综合设置改可滚动分组、固定保存/取消底栏；背景选择/移除/强度随完整JSON持久化，取消与失败不发config_saved。图片只存本地路径，不复制/上传真实图片，不改DB/同步schema。
- 主窗口加品牌标题与外观直达按钮，主面板150/255不透明度、列表/AI正文透明背景、10px面板间距；日历根样式在视图刷新后保留半透明背景。窗口/托盘调用T63的create_app_icon。主题使用深靛蓝与提高可辨识度的暗色日程文字。悬浮透明度/字号契约不变。
- 先行旧实现失败：指定Miniconda执行 `-m unittest tests.test_appearance -v`，ModuleNotFoundError: background（主agent同时记录build/t60-python-baseline.txt）。第一轮WebDAV回归暴露旧测试的最小主题字典不含新增键，设置样式使用已有frame_bg回退后修复。
- 最终聚焦：`C:\Users\30910\Miniconda3\python.exe -m unittest tests.test_appearance tests.test_main_window_floating tests.test_ui_smoke tests.test_typography tests.test_webdav_settings_ui tests.test_calendar_experience -q`，51项通过，2.792s；包含新增7项背景/设置测试，纯临时DB和合成图片，覆盖非法配置、图片缺失损坏/像素文件大小上限、单图缓存、选择取消、保存失败/成功信号、移除、Light/Dark×8/20px、小窗480px滚动底栏、主窗口月周日和移除恢复。
- Qt offscreen合成渐变截图：build/t61-main-light.png、t61-main-dark.png、t61-settings-light.png、t61-settings-dark.png；人工检查背景透出/前景文字/滚动底栏。Windows offscreen默认字体库缺失，截图和本测试显式注册系统msyh.ttc（仅进程内），无系统修改；其它旧emoji由T63清理。
- `git diff --check`通过；完整PyInstaller、exe隔离冒烟、dist/data只读摘要、根AGENTS/progress、提交由主agent串行完成。

## 限制与回滚
最终T60集成完成：252项Python、24张视觉、完整PyInstaller及普通/静默隔离冒烟通过，dist/data摘要一致；最终包与提交见T60。
保留背景文件本地路径，移动/删除在下次加载/应用外观后回退；不监控文件系统实时变化。24MP以下PNG可能由Qt先解码再缩放，峰值内存有界但大图首次加载会短暂占用主线程；不做每帧解码。图片不用于悬浮窗，避免破坏既有桌面透明语义。Git revert本任务文件恢复原外观，旧版本忽略两个新配置键。
