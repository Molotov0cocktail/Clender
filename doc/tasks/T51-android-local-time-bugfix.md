# T51 Android AI 本地时间修复

最终状态：实现、完整Android验证与签名构建完成；提交/普通Gitee开发分支推送为最后步骤，实际SHA见最终答复及Git日志。本轮未执行Windows工作、外部API请求或新设备验收。

起点8f606fd，2026-09-06，git工作区clean。用户报告手机20:01而AI称11:58，明确本次仅Android，跳过Windows EXE相关构建/测试/数据扫描。

## 目标、非目标与数据契约
AI每次构造请求上下文使用手机当前系统时区的当前日期/分钟/星期，运行中时区或系统时间改变后下次请求立即生效。AppContainer全局UTC时钟仍用于消息/事件/同步metadata，事件本地墙钟字段不转换，不迁移历史会话/数据库。非目标：Windows、UI布局、依赖/Manifest、网络协议/密钥及模型行为修改；不复用已结束任务临时Key，不发真实外部请求。

## 影响文件与分工
生产限定domain/ai/AiContextProvider.kt及必要AppContainer接线/小型时钟适配；回归限定AI上下文/协调器相关测试。主代理任务编排、RED/门禁、签名构建和审查；独立代理诊断/测试实现或只读复核。文档限定本任务、progress、两级AGENTS、android/README；策略仅本任务exact path及相应回归。

## 测试矩阵（实现前）
| 路径 | 验证 |
|---|---|
| 正常 | 同一UTC instant在Asia/Shanghai输出+8本地时间及正确星期 |
| 边界 | UTC与本地跨日/年、负时区、半小时/45分钟偏移、DST跳变 |
| 动态 | 同一provider多次build读取当前instant/系统zone，时区变化不需重建container |
| 异常 | 读取日程失败/取消按现有错误路径传播，不返回虚假缓存时间 |
| 回归 | 事件本地字段不转换、墓碑过滤排序、UTC消息metadata不漂移；无配置/超时/非200/畸形JSON/危险操作沿用AI全套回归 |

## 步骤、风险、回滚与完成定义
先写旧实现可编译失败用例保留RED，再最小修改，聚焦GREEN和完整Android verify-all、签名APK/AAB审计。必须避免固定+8或初始化时缓存zone；可测试注入时钟与时区，production按系统动态读取。时区切换测试恢复全局设置以免污染其他suite。无秘密/历史数据使用，必要模拟器仅合成loopback验证；不依赖云端模型措辞作为正确时间判据。
回滚仅本批工程diff/提交，无真实数据迁移。完成：RED→GREEN、完整门禁/签名和来源证据、契约记录与索引审查、提交/既有Gitee开发分支普通推送；Windows依用户明确指示不运行。


## RED结果
旧实现可编译16tests/6failures/0errors/0skips（40s，32tasks/3executed），五条provider时区与一条实际coordinator上下文回归均失败。原始日志t51-time-red.txt、XML t51-time-red-results保留。首次沙箱复制结果目录权限拒绝，未运行测试；宿主权限精确复制后确认16/6计数，未重试测试。Policy独立5tests/1RED→5/5GREEN，只新增本任务exact path。

实施决策：默认AiContextProvider读取动态ZoneId::systemDefault，生产AppContainer两参构造不变；可注入supplier供测试确定性使用。每build只采样一次instant/zone并从同一LocalDateTime产生日期/星期，事件墙钟不转换，UTC metadata不变。


## 聚焦结果与审查
44/44 tests、failure/error/skip=0，detekt通过；40tasks/12executed/1m24s聚合停在ktlint新增测试换行/函数签名格式（报告共25项），不属于行为失败。仅按报告修正本批测试格式，原t51-time-focused.txt与t51-focused-results保留。独立只读审查无确切新增bug；单次时间/zone采样、动态系统zone、UTCmetadata、取消与全局TimeZone恢复边界通过源码复核。


## 静态修正后冻结
仅两个本批测试文件四处换行消除25项ktlint诊断，断言内容不变；单独ktlintCheck为7tasks/2executed/22s PASS。五份生产/测试文件hash已保存t51-verified-source-hashes.json，随后完整verify-all与签名构建串行执行。精确12文件白名单t51-stage-whitelist.json。无UI改动，本次验收以可确定的默认生产路径/实际请求system消息与完整Android回归为准，不发云端请求，不以模型自然语言回复作为时间正确性的判据；未使用用户实体手机或模拟器新验收。


## 同源码完整JVM
160 suites/1694 tests，UI60/723，failure/error/skip=0；CloseGuard、SQLiteConnectionPool、SQLiteDatabase leaked、RoomDatabase leaked四类标记全部0。完整XML t51-full-results及统计t51-full-counts.json已保存；此后不改变生产/测试源码。完整verify-all与签名产物结果见下文。


## 最终构建、产物与交付
verify-all exit0，96tasks（27executed/69up-to-date）、5m36s BUILD SUCCESSFUL；lintDebug/lintRelease/detekt/ktlint/签名策略/Google/native/版本锁/assembleDebug/APK/merged manifest均通过。release fixtures111/111（2.298s），foundation62/62（3.930s），boundary62/62（3.354s）。同源码完整JVM1694与UI723均通过，不复用旧包测试数字。日志t51-full-gates.txt。

build-release两阶段exit0，签名/zipalign/aapt2/network/bundletool/jarsigner/同证书/archive审计全部通过，日志t51-release-build.txt；实际文件hash与metadata一致，t51-delivery-artifacts.json：

- APK 1726606 bytes，SHA256 `82e4e504b8dfd5bd62d4ba22f103874402b872ebe646997f23469b402c7b7e09`。
- AAB 4621248 bytes，SHA256 `43e3a9af9ba526de89246d0ae677a5c524cfcb4e94838bc0719024315cda549f`。
- mapping 38949446 bytes，SHA256 `9f44a03f8a3132516edb5817d02af1f7c9cc432edffce82d9c7684d18a5a698f`。
- certificate `628248932cfd0587a04126290ac2af870591fc7ebe80259c5f29d266c944615f`，与T50相同，支持覆盖安装；没有数据库迁移。

12文件精确范围/敏感模式审查通过，五份生产测试hash在最终构建后保持一致。生产仅AiContextProvider，AppContainer共享UTC时钟、同步/事件metadata、UI、依赖、Manifest/Room/schema、Windows均无改动。两级AGENTS更新动态本地时区及UTC数据分离契约，README和progress更新构建/验证。Windows依本次用户授权明确跳过，无EXE测试/构建或dist/data读取。未做新模拟器/实体手机验收，也未重用旧Key；实际请求内容已由coordinator合成集成测试断言，本次不声称云端模型或用户手机已实测。

已执行提交前global https.proxy设置，最后精确暂存12工程文件并运行索引边界/秘密审查；随后统一commit、仅普通push到Molotov的codex/android-architecture并核对SHA，不force/不推main/GitHub。实际Git回执见最终答复与Git日志。


最终索引门禁：12/12白名单，未暂存/未跟踪工程文件0，秘密模式0，五源码hash不变；git diff --cached --check通过。索引后boundary62/62、3.198s、exit0，日志t51-final-staged-boundaries.txt。此后仅追加本回执，生产/测试/构建输入保持冻结，进入提交。
