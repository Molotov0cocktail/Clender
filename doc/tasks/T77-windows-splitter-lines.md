# T77 Windows 三栏分隔竖线修复

日期2026-09-15，基线main/34a6aff干净。用户确认Android无问题，仅修Windows截图三栏间半透明竖线。定位MainWindow._apply_chrome_styles给10px QSplitter handle绘制border_soft渐变中线；本轮让handle透明无描边，同时保留10px拖动命中区与调整栏宽功能。若实际像素证据发现其他来源，先记录再局部修复。

目标：日历/事项/AI三栏之间显示连续背景，无额外装饰竖线。非目标：Android、版本、数据、AI/同步逻辑、三栏内容外观、依赖、发布。范围ui/main_window.py、tests/test_main_window_chrome.py或新test_windows_splitter.py、本任务、progress、根AGENTS。数据/接口无变化。风险为拖动失效或主题重设回归，像素+真实鼠标验证。

矩阵：正常明暗两个handle像素透底与拖动改变栏宽；边界无背景/自定义背景/8-13-20字号/resize/主题往返；异常缺失背景沿用回退；回归既有chrome、信号、背景和全量unittest。先旧实现有效RED，再生产GREEN；Qt offscreen合成数据截图审查，完整PyInstaller和隔离EXE普通/静默双场景，dist/data只读摘要前后不变，禁读取真实数据内容作测试。

分工：独立子agent负责精确回归与修复；主agent检查环境/进程/数据摘要、review、全量验证构建、截图、文档提交。先测试再实现、不提交、不触碰真实数据。回滚本任务提交即可，不迁移数据。完成：所有适当门禁、EXE产物/摘要、文档、Git本地提交，无推送Release。

## 实现与验证

- 生产仅MainWindow的局部handle QSS改为`background: transparent; border: none`，覆盖全局frame_border底色及局部渐变；10px handle、三栏比例、拖动和面板边缘不变。新tests/test_windows_splitter.py以独立BackgroundWidget同坐标参考验证两个handle全宽10像素×三个高度点；明暗往返、8/13/20字号、resize、有/无/缺失背景、真实鼠标事件调整栏宽。
- 首RED拖动夹具初始日历达到既有500px上限导致3个子场景额外失败，调整合成初始宽度保持断言后有效RED为3tests/20像素子场景失败，拖动通过（.tmp/t77/splitter-red2.txt）；修复后17/17聚焦通过，31.643s（splitter-green.txt）。无真实用户配置/数据内容用于测试。
- 指定`C:/Users/30910/Miniconda3/python.exe -B`，Qt offscreen及Windows字体：`-m unittest discover -s tests -v`344/344通过（25.272s），零失败/错误/跳过；33模块导入、build.py --check、完整build.py/PyInstaller通过（.tmp/t77/{unittest,build}.txt）。
- 独立review脚本`.tmp/t77/visual_review.py`使用UISmokeTests临时数据与原创渐变，1600×1000、light/dark×8/13/20px六图逐张读图通过：三栏间无额外竖线，背景连续、10px handle保留，面板内容完整；主agent另读dark13。截图不是最终EXE截图，最终EXE另做隔离启动。限制为当前尺寸offscreen视觉，未宣称所有屏幕缩放或全产品可读性；拖动由真实鼠标单测覆盖。
- EXE 45,592,135 bytes，SHA256 `150596a2cee7ae44a5380fccd3ddf5607ac3bb3afbc8b2c65f9dce0cc8d65b0b`。构建前后dist/data五文件167603bytes，路径/大小/mtime/hash一致，清单摘要`697b78718665fad51cdb38cdac9b0ae79f1672856a8e30fd9d01e35378eb2af1`。
- 用户已验收Android，Android文件/测试/构建均未动；版本不变，不推送或Release。最终隔离EXE结果、清理与提交见本任务追加及Git日志。

最终隔离EXE：scripts/verify_frozen_single_instance.py exit0，normal-to-silent与silent-to-normal两场景readiness/primary_survival/secondary_exit0/quiescence/cleanup均通过，日志.tmp/t77/exe-smoke.txt；根AGENTS/progress已同步本地完成。Git提交回执以日志与最终答复为准。
