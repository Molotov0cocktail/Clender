# 双端外观提案、设计和控制提示（2026-09-06）

## 目标与决策
统一原创蓝紫日历勾选图标、美化层次与操作入口；双端选择/移除自定义本地背景，日夜自动蒙板与强度调节保证可读。用户已授权非功能性细节自主决定。保持日程、AI、同步、悬浮、导航和数据契约，不上传背景。PC无新增依赖；Android因平台EXIF安全lint新增固定官方ExifInterface 1.4.2，其余依赖不变，严格锁定与校验。

## 基线
PC main 3bc9fa4；Android完整源码在本地codex/android-architecture的6e5b525，当前目录仅有缓存/工具链/签名。只恢复该分支已跟踪android文件，事前核对386个目标均不存在，不覆盖PC与本地材料。统一集成提交，审查时按6e5b525来源区分基线与外观增量。查阅该分支Android契约与相关设计，不把历史冻结误用到本轮授权任务。

## 架构与接口
PC新增纯背景配置校验、UI背景画布，完整JSON保存。选择本机图片，cover裁剪，有界解码和缓存，坏图/缺失回退主题；背景强度可调，日夜遮罩自动保底，前景文字不透明。设置分组可滚动，避免小窗挤出按钮。窗口/托盘接app_icon.create_app_icon()。
Android沿用Compose，背景为独立即时保存外观设置（界面明确提示）；系统选择器选图，有界异步解码/EXIF旋转后存应用私有PNG，不保留来源URI、不申请广泛媒体权限；移除/强度可调，保留既有导航与主题字号未保存草稿语义。Activity ViewModel保存导入状态跨旋转；配置fsync后同目录原子替换，失败保留旧背景。
原创矢量日历勾选标志，蓝紫底青色强调。PC QPainter运行时图标与多尺寸ICO；Android adaptive/monochrome vector，不依赖emoji或外部素材。

## 分工和控制提示
主agent T60仅编排、diff审查、集成修复、文档和发布；子agent先读契约/源码/任务，先写失败测试再实现，所属范围内增量修改，报告文件/命令/失败和修复/风险。不提交、不触碰真实data/签名、不并发Gradle。
T61：constants.py、theme_manager.py、ui/main_window.py、ui/app_settings.py、新背景模块和测试。T62：Android背景设置/Compose/测试/子契约，不改launcher图标。T63：app_icon.py、main.py、icon.ico、assets矢量/生成脚本、Android launcher资源/Manifest、独立测试。

## 修改前测试矩阵
|范围|正常|边界|非法/异常|回归|
|---|---|---|---|---|
|配置|选择/保存/重启/移除|强度两端/旧配置/空值|坏类型/文件缺失损坏/超大/IO失败|完整设置/取消不保存|
|绘制|cover/日夜遮罩/前景清晰|极端宽高/8与20px|解码失败/权限撤销|无背景/主题切换/Qt offscreen与Compose|
|UI|设置分组/快捷入口|小窗/大字/横竖屏|保存失败提示重试|信号/编辑/AI/同步/独立悬浮透明度|
|图标|窗口/托盘/exe/launcher|16/32/256/adaptive裁切|缺失资源/打包路径|启动/Manifest安全/无字体依赖|
|发布|Python和Android全量/静态/构建|隔离exe与APK启动/明暗截图|失败不标完成|dist/data只读摘要一致/提交无秘密和产物|

## 风险、回滚、完成定义
EXIF安全lint新增独立授权：仅引入官方AndroidX ExifInterface 1.4.2，原依赖版本/锁配置宇宙保持，新增artifact与metadata固定SHA-256；移除平台EXIF和8192兼容缓冲，不抑制lint。先精确依赖policy RED，再解析新坐标并恢复offline strict；原短JPEG、全8方向、损坏/超限/取消/UI及锁冻结回归通过后发布。此项替代下句“不升级依赖”的EXIF单项边界，其他依赖不变。
图片可能降低对比度，最亮最暗图与两主题截图复核；限制图片内存。采用强度和自动遮罩即可保持可读，本轮不增加灰度/实时模糊控件，避免解码以外持续渲染开销。无schema迁移，旧依赖不升级。Git revert集成提交回滚，新配置旧版忽略。须失败证据、聚焦/全量、完整PyInstaller与Android构建/隔离启动、视觉、数据摘要、文档与安全提交。未运行设备矩阵如实记录。
