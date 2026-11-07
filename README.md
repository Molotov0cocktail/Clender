<div align="center">

<img src="assets/clender-icon.svg" width="88" alt="Clender 应用图标">

# Clender

### 让计划落在日历里，让每一天更有条理。

本地日历 · AI 日程助手 · Windows 与 Android · WebDAV 同步

[![Release](https://img.shields.io/github/v/release/Molotov0cocktail/Clender?label=最新版本)](https://github.com/Molotov0cocktail/Clender/releases/latest)
![Windows](https://img.shields.io/badge/Windows-10%20%2F%2011-0078D4)
![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84)

[下载 Windows 版](https://github.com/Molotov0cocktail/Clender/releases/download/v1.3.2/Clender-Windows-v1.3.2.exe) · [下载 Android 版](https://github.com/Molotov0cocktail/Clender/releases/download/v1.3.2/Clender-Android-v1.3.2.apk) · [更新日志](https://github.com/Molotov0cocktail/Clender/releases) · [反馈问题](https://github.com/Molotov0cocktail/Clender/issues)

</div>

---

Clender 将日历、提醒与 AI 助手放在同一个工作流里：用月、周、日视图安排生活，在电脑上查看今日计划，在手机上接收提醒，也可以通过对话创建和调整日程。日程保存在本机，跨设备同步和 AI 服务由你自行配置。

适合需要统筹工作、学习与个人生活，希望保留本地数据，同时使用自己选择的 AI 与同步服务的人。

## 从计划到行动

| 能力 | 你可以做什么 |
|---|---|
| **清晰规划** | 在月、周、日视图切换，创建、编辑和查看有明确时间范围的日程。 |
| **对话安排** | 用自然语言提出计划，由 AI 助手生成受限的日程操作，通过业务校验后执行。支持多个会话。 |
| **及时提醒** | Windows 提供运行期间的系统通知；Android 提供通知、重要闹钟和计时提醒。 |
| **随时查看** | Windows 今日悬浮窗显示当前与下一事项；Android 桌面 Widget 让日程留在桌面。 |
| **跨端衔接** | 连接自己的 HTTPS WebDAV 服务，按事项合并新增、修改与删除。 |
| **个性外观** | 日间与夜间主题、自定义背景及字号设置，让日历融入自己的桌面和手机。 |

例如，你可以对助手说：“把明天下午三点到四点安排为项目复盘。”或“把今晚的阅读往后移半小时。”使用后请核对日期、时间和实际操作回执，模型理解仍可能出错。

## 下载与安装

当前版本：**v1.3.2**。从 [GitHub Releases](https://github.com/Molotov0cocktail/Clender/releases/latest) 获取正式附件，无需配置开发环境。

| 平台 | 下载 | 安装方式 |
|---|---|---|
| Windows 10 / 11 | [Clender-Windows-v1.3.2.exe](https://github.com/Molotov0cocktail/Clender/releases/download/v1.3.2/Clender-Windows-v1.3.2.exe) | 放入固定的可写文件夹，双击运行。升级时退出旧应用，替换 EXE，保留旁边的 `data/`。 |
| Android 8.0+ | [Clender-Android-v1.3.2.apk](https://github.com/Molotov0cocktail/Clender/releases/download/v1.3.2/Clender-Android-v1.3.2.apk) | 下载后安装；系统询问时为当前下载来源允许安装。原正式签名用户可直接覆盖升级。 |
| Android 分发 | `Clender-Android-v1.3.2.aab` | 供应用商店分发使用，不能直接安装。 |

Android 包内版本为 **1.3.2 (4)**，沿用正式签名。升级前建议备份重要数据；不要通过卸载旧应用来完成升级。

<details>
<summary>文件完整性校验（SHA-256）</summary>

下载后可在 PowerShell 中运行 `Get-FileHash .\文件名 -Algorithm SHA256`，与本页及 Release 中的摘要核对。

```text
Clender-Windows-v1.3.2.exe  e345784d8f10a0b3021897398daed72d57f01df9e0e3132ef6173a2d37a7884c
Clender-Android-v1.3.2.apk  f09d2816881c7960abb009d7508e0e3d8acf7194f4ec78b48a9195aaccb39677
Clender-Android-v1.3.2.aab  0faf829ffcc492528b3502e8f3097ade11fc563a409d5992f917b3f2934aaea4
```

</details>

## 三步开始

1. **添加第一条日程。** 打开日历，选择日期并创建事项。基本日历功能无需配置 AI 或 WebDAV。
2. **按需连接 AI。** 在 AI 设置中填写 OpenAI 兼容接口地址、API Key 和模型，核对上下文与输出预算，再开始对话。服务可用性与费用取决于你选择的供应商。
3. **设置提醒与同步。** Android 在设置页检查通知和精确闹钟权限，按需选择音乐；跨设备使用时，为两端配置同一个可信 HTTPS WebDAV 目录与凭据。

Windows 可从托盘恢复主窗口，在设置中开启今日悬浮窗或静默开机启动。Android 的重要提醒受系统权限、通知频道和厂商后台管理影响，请在首次使用时检查状态。

## v1.3.2 更新

- **AI 预算更可靠：** Windows 根据有效元数据与已知精确模型信息解析能力，展示和保存完整整数；未知能力可手动填写。
- **慢响应处理改进：** Android 独立设置聊天写入时限，延长读取与会话总时限；格式纠正仍受同一总时限约束，并保留取消能力。
- **上下文与截断提示更明确：** Android 预留固定操作契约和输入空间，区分上下文不足与输出上限；截断回复不会执行日程操作。
- **问题更容易定位：** Android 新增有大小上限的诊断日志，覆盖 AI、同步、提醒与崩溃摘要；关于页支持导出、清空日志和清理日志导出临时文件。

完整变更和验证范围见 [Release 说明](https://github.com/Molotov0cocktail/Clender/releases/tag/v1.3.2)。

## 数据与隐私

- **本地保存。** 日程与会话保存在设备上。Windows 的 `data/` 和打包版旁的 `data/` 含私人数据；Windows 配置可能明文保存 API Key 与 WebDAV 密码，Android API Key 使用 Keystore 加密保存。
- **AI 按需请求。** 只有用户提交消息后才请求配置的 AI 服务。请求会包含生成回复与操作所需的消息、日程及时间上下文，请选择可信供应商并了解其数据政策。
- **同步范围明确。** WebDAV 同步日程，不同步 AI 会话。远端 `clender-events.json` 未端到端加密，请使用可信 HTTPS 服务和专用应用密码。
- **诊断可以控制。** Android 日志仅记录诊断枚举、数字指标与崩溃位点摘要，不记录密钥、聊天正文或日程内容；不自动上传。关于页的两项清理只处理诊断日志及导出临时文件，保留业务数据。

提交问题时，请勿附上数据库、配置文件、真实对话或密钥。Android 可以通过“关于 → 诊断与清理 → 导出诊断日志”提供诊断信息。

## 平台差异与使用边界

| 项目 | Windows | Android |
|---|---|---|
| 桌面入口 | 今日悬浮窗、托盘、静默自启动 | 桌面 Widget |
| 提醒 | 应用运行期间提交系统通知 | 通知、重要闹钟、开始后计时 |
| 诊断导出界面 | 尚未提供 Android 同款维护入口 | 关于页导出与清理 |

AI 接口兼容不代表所有供应商、模型与协议均已验证；模型输出仍可能因格式或语义问题失败。Windows 通知需要应用保持运行。Android 在 320dp 窄屏上日期表头需横向拖动，极大字号下部分 Widget 设置标签可能局部压线。新版诊断仅能记录安装后发生的事件。

## 开发与贡献

Windows 客户端基于 **Python / PyQt5 / SQLite**，Android 客户端基于 **Kotlin / Jetpack Compose / Room**。两端通过 WebDAV 日程协议协作。

```text
main.py / ui/            Windows 应用与界面
ai_service.py           AI 上下文、响应解析与操作校验
webdav_sync.py          WebDAV 协议与事项合并
android/app/            Android 应用、业务与测试
android/scripts/        隔离构建、质量检查与签名审计
tests/                  Windows 自动化测试
doc/                    设计、测试矩阵与维护记录
```

本仓库现有 Windows 验证环境为 Miniconda base Python 3.12.4，依赖版本见 `requirements.txt`。请使用自己的 Miniconda 安装路径；不要把运行数据用于测试。

```powershell
$ClenderPython = 'C:\Users\你的用户名\Miniconda3\python.exe'
& $ClenderPython -m pip install -r requirements.txt
& $ClenderPython main.py

$env:QT_QPA_PLATFORM = 'offscreen'
& $ClenderPython -m unittest discover -s tests -v
Remove-Item Env:QT_QPA_PLATFORM
& $ClenderPython build.py --check
& $ClenderPython build.py
```

Windows 构建输出为 `dist/Clender.exe`。Android 的工具链、离线检查和签名构建步骤见 [Android 开发说明](android/README.md)。修改前请阅读 [工程契约](AGENTS.md)，并在 Issue 中说明问题、复现步骤、平台及版本。

[报告问题](https://github.com/Molotov0cocktail/Clender/issues) · [查看版本](https://github.com/Molotov0cocktail/Clender/releases) · [GitHub 仓库](https://github.com/Molotov0cocktail/Clender) · [Gitee 历史镜像](https://gitee.com/Molotov0coaktail/clender)

Gitee 的版本可能落后于 GitHub，最新发布以本页 GitHub 下载入口为准。
