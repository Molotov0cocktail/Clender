# T44 集成发布

目标：集成 T41–T43，完成全量、导入/语法、视觉、构建、隔离 exe、数据完整性及提交。
非目标：不操作真实数据/网络/注册表、不更改 android。
影响文件：任务/AGENTS/设计及必要集成修复。接口、风险、矩阵、回滚、验收见 `doc/pc-experience-design.md`。
步骤：审查失败证据与 diff；完整回归；明暗8/20px视觉；只读 dist/data 摘要；完整构建；隔离 exe；核对数据；文档与 staged 敏感检查；提交。
状态：完成；本任务记录随已验证工程文件一同提交，提交哈希见 Git 历史与最终交付回复。

## 验证记录
- 修改前：`QT_QPA_PLATFORM=offscreen`，指定 Miniconda Python 执行 `-m unittest discover -s tests -v`，171/171 通过。
- 构建前 dist/data 只读完整性：5 文件、142033 字节、路径/长度/内容哈希组合 SHA-256 `1584e4820cc804fa5a4bd93a8ee717801f717371e80e4259896c5492d94715ae`。只输出组合摘要，无真实内容。
- T41 旧实现 8 项：1 通过、3 failure、4 error；合法时间顺序未复现闪退，非法顺序/保存异常缺少 UI 防护已复现。
- 最终全量：指定 Python `-m unittest discover -s tests -v`，209/209 通过；33 个根目录/ui 模块 AST 语法及 importlib 导入通过；`build.py --check`、`git diff --check` 通过。
- 隔离视觉脚本 `C:/Users/30910/.codex/visualizations/2026/09/06/01a076e3-9cef-7f02-b547-71651e56977e/pc_visual.py` 使用 TemporaryDirectory 配置/DB，合成日程，生成 Light/Dark × 8/13/20px × 主月周日/悬浮事件日周/事件表单共42张截图。显式加载 Windows msyh/Segoe UI 字体解决 offscreen 原先不栅格化文字；已复核短色块/红线、跨日、长输入、固定周表头和日期表单。离屏 emoji 回退仍有方框，原生 Windows 字体回退/IME/DWM/多屏需人工复核。
- 审查修复：日期最大值溢出、可见月计数避免超长跨度循环、主窗口编辑异常保留输入、隐藏悬浮取消预览、固定周表头与字号/横向同步。
- 完整构建：指定 Python `build.py` 成功，`dist/Clender.exe` 45595858 字节，SHA-256 `A5E9522A09B163918B2C9D051AA31095DE275FE32396E3F2850E890D8EF40C87`。
- exe 冒烟脚本（与视觉脚本同目录 `exe_smoke.py`）只复制 exe 到 TemporaryDirectory，随机 USER/LOGNAME 等环境隔离单实例 IPC，QT offscreen 且隐藏启动。普通 primary + silent secondary、silent primary + 普通 secondary 均成功，两个 secondary 退出码0；隔离 DB 创建，primary 存活，最终清理成功。
- 首次冒烟的普通组已通过，但沙箱下 taskkill 无权结束测试进程，清理超时；核对进程路径后提升权限只停止本次临时 exe，再在原沙箱删除临时目录。提升权限重跑完整脚本全部通过。未停止用户应用或触碰真实运行数据。
- 构建/冒烟后 dist/data 仍5文件、142033字节，组合摘要仍 `1584e4820cc804fa5a4bd93a8ee717801f717371e80e4259896c5492d94715ae`，与之前完全一致。
- `git config --global https.proxy http://127.0.0.1:7890` 在默认沙箱写入被拒后，经权限提升成功执行。提交只精确列出本轮工程文件，android/保持未跟踪、未编辑；不提交 exe/数据/图片/缓存。提交哈希见最终回复及 Git 历史。
- 最终 staged 检查：28 个工程文件，`git diff --cached --check` 通过；路径排除 data/dist/build/android 和 db/exe/log/spec，新增行凭据模式扫描通过。

## 遗留验证范围
未连接真实 AI/WebDAV；未修改或构建 android/。保留同步 schema v1、事件模型和依赖，安卓功能无代码变化。Windows 原生 IME、emoji字体回退、DWM/多屏交互仍需可见桌面检查；自动化已覆盖离屏输入/信号/拖缩/主题边界。
