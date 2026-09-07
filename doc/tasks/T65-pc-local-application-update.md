# T65 更新 PC 本地应用

用户本轮明确授权更新 PC 本地应用，替代 T64 仅该轮“不测试/不构建 PC”的限制。基线为 main/710a8f7，PC 源码与上次完整外观版773c2dc一致；仅重新构建本地 EXE，不新增功能、不修改 Android。

范围：dist/Clender.exe由既有build.py生成替换；任务、progress与根AGENTS维护记录。用户数据、密钥、真实对话不编辑、不输出、不提交。无接口/数据库/依赖变化。

先行验收矩阵：只读进程与本地单实例endpoint屏障（有用户实例则不终止、不覆盖）；指定Miniconda Python3.12.4全量unittest、生产模块导入、build.py --check；完整PyInstaller构建；既有隔离EXE正常/静默双场景smoke；dist/data构建前后路径/大小/mtime/hash完全一致；最终EXE大小/SHA256与Git范围审查。损坏输入、取消、并发单实例及背景回归复用已有测试，不另写镜像用例。

步骤：环境/清洁屏障 → 数据只读摘要 → 测试/导入 → 完整构建 → 隔离EXE启动验收 → 摘要对比 → 文档与main提交。风险为运行中替换及用户数据变化，使用屏障和只读完整性检查防护。失败保留日志、定位后再验证；原子EXE替换由既有build.py负责，构建失败不主动删除原产物；必要时可从上次Git版本重新构建回滚，不回滚用户数据。

完成定义：本地EXE更新成功、上述门禁通过、用户数据不变、仅维护文档提交，最终答复提供EXE链接与摘要。状态：本地应用更新及验收完成，文档提交回执见Git日志与最终答复。

## 最终结果
- 本轮无PC/Android生产源码或依赖变更，沿用main/710a8f7中的既有PC外观版本。
- 指定Miniconda全量252/252 tests（9.001s报告时间）、生产模块导入及build.py --check通过。
- 完整PyInstaller59.688s成功，已原子替换dist/Clender.exe。普通→静默、静默→普通两场景11.484s通过，secondary退出0、primary存活、readiness/quiescence/cleanup全true。
- dist/data五文件共143973bytes，路径/大小/mtime_ns/UTC/SHA256构建前后完全一致；未输出文件内容。结束无Clender进程、endpoint不可达、smoke临时目录0；没有启动真实数据实例，仅使用隔离合成环境验收。
- EXE45,573,969bytes；SHA256 `8fc351ed90c8c72c032a8369104fb6bcc05c5a46cc64d3e5e351aa7983d4c634`。完整证据位于忽略目录build/t65/t65-windows-result.json及各步骤日志。
- 仅提交本任务、progress及根AGENTS维护记录，EXE/数据/日志不提交；Android未重测，原因是本轮仅PC重新构建且Android零改动。
