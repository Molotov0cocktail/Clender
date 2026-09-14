# Clender

Clender 是一个面向 Windows 与 Android 的本地智能日程管理应用。Windows 端使用 PyQt5，Android 端使用原生 Jetpack Compose；双端均提供月、周、日视图、本地日程存储、WebDAV 同步和 OpenAI 兼容的 AI 日程助手。

## 功能

- 月、周、日三种日历视图，支持提醒和有开始/结束时间的日程；
- SQLite 本地持久化，事件创建、编辑、删除均经过统一业务校验；
- 多会话 AI 助手，可将已验证的模型操作转换为日程 CRUD；
- 日间/夜间主题，以及独立的应用与悬浮窗字号设置（8–20px）；
- 今日桌面悬浮窗：时间范围筛选、当前/下一事项提示、拖动缩放、双击编辑，以及复用当前 AI 会话的快捷输入；
- WebDAV 日程双向同步：按事件合并新增、修改与删除，不同步 AI 对话；
- 同一 Windows 用户单实例、系统托盘、静默开机自启动和 PyInstaller 单文件构建。
- Android 原生应用支持桌面 Widget、通知/重要闹钟/计时提醒、全面屏与本地自定义背景。

## 下载正式版

当前正式版本为 **v1.3.0**：

| 平台 | 文件 | 用途 |
|---|---|---|
| Windows | `Clender-Windows-v1.3.0.exe` | Windows 10/11 单文件桌面应用 |
| Android | `Clender-Android-v1.3.0.apk` | Android 8.0（API 26）及以上直接安装包 |
| Android | `Clender-Android-v1.3.0.aab` | Android 应用商店发布包，不用于直接安装 |

- [GitHub Releases](https://github.com/Molotov0cocktail/Clender/releases/tag/v1.3.0)
- [Gitee Releases](https://gitee.com/Molotov0coaktail/clender/releases)

下载后可用 SHA-256 校验文件：

```text
Clender-Windows-v1.3.0.exe  196c0eea521d58be7ba085d82ebf737abd36140f549b34e4215b3c7e48b921bd
Clender-Android-v1.3.0.apk  3d80a52bcf055df7e710e2f568bfa30641edde0032a7781baca4972c7fe86f32
Clender-Android-v1.3.0.aab  df93d42d02bdde3a615ef586799492f8001356477ec250541019ba6da6141711
```

Android 包内版本为 `1.3.0 (2)`，沿用原正式签名，可从上一正式包直接覆盖升级并保留本机数据。

### v1.3.0 更新

- 双端 AI 请求带入明确的当前时钟、本轮边界和历史消息时间，降低跨日对话把旧“今天/明天”当成当前日期的风险；
- Android 日期选择器在窄屏、低高度和大字号下保持完整日历与可达操作按钮；
- Windows AI 人格设置合并为单一入口，并兼容保留旧自定义内容；
- Android 可导入应用私有的自选闹钟音源，原文件删除后仍可播放，并继续遵守频道静音、停止和音频焦点；
- 双端提醒与 AI 正文执行链路继续使用严格校验、真实回执和有界回退。

已知限制：Android 首次使用自选闹钟音源时需在“设置 → 应用 → 闹钟铃声”重新选择一次；320dp 窄屏日期表头需要横向拖动；模型输出仍可能因格式或语义错误被严格校验拒绝；Windows 通知依赖应用保持运行。

## 环境要求

开发、测试和构建仅支持 Windows 上的 Miniconda base Python 3.12.4：

```powershell
$ClenderPython = 'C:\Users\30910\Miniconda3\python.exe'
& $ClenderPython --version
& $ClenderPython -m pip install -r .\requirements.txt
```

请勿使用系统默认 Python，也不要在仓库中创建 `.venv` 或 `.conda`。依赖版本已锁定在 `requirements.txt`。

## 运行

```powershell
& 'C:\Users\30910\Miniconda3\python.exe' .\main.py
```

首次运行会在 `data/` 创建本地数据库、配置和对话记录。该目录可能含 API Key 与私人日程，已被 Git 忽略，不能提交或共享。

在应用的 AI 设置中填写 OpenAI 兼容 API 的 endpoint、API Key 和模型后，即可使用 AI 对话。模型输出只会执行受限且经过校验的日程操作。

在应用设置中可启用 WebDAV，填写 HTTPS 目录 URL、用户名和密码。Clender 会在该目录读写 `clender-events.json`，仅在本地日程变更或手动请求时后台同步。WebDAV 密码明文保存在已忽略的本机 `config.json`；远端日程 JSON 未端到端加密，请只使用可信 HTTPS 服务和专用应用密码。

打包版可为当前 Windows 用户启用开机自启动。自启动使用 `--silent`：主窗口不弹出，托盘仍可恢复；已启用的今日悬浮窗会照常显示。

## 测试与检查

```powershell
$env:QT_QPA_PLATFORM = 'offscreen'
& 'C:\Users\30910\Miniconda3\python.exe' -m unittest discover -s tests -v
Remove-Item Env:QT_QPA_PLATFORM

& 'C:\Users\30910\Miniconda3\python.exe' .\build.py --check
```

测试使用临时目录与 mock，不读取真实 `data/`。完整的架构契约、测试矩阵和维护流程见 [AGENTS.md](AGENTS.md)。

## 构建发布版

```powershell
& 'C:\Users\30910\Miniconda3\python.exe' .\build.py
```

构建成功后生成 `dist/Clender.exe`。构建过程只替换该 exe，不会打包或覆盖既有 `dist/data/` 用户数据。仅在明确需要桌面快捷方式时使用 `--shortcut`。

## 项目结构

```text
main.py                  应用入口
models.py                Event、Message、Conversation 模型
event_service.py         日程业务验证边界
database.py              SQLite schema、迁移与 CRUD
ai_service.py            AI 上下文、响应解析与操作校验
ai_client.py             OpenAI 兼容网络线程
calendar_logic.py        周/日视图纯布局逻辑
floating_window_logic.py 悬浮窗设置与状态逻辑
webdav_sync.py          WebDAV 协议、文档验证与事件合并
sync_controller.py      WebDAV 后台线程与触发编排
startup_manager.py      Windows 当前用户开机自启动
ui/                      PyQt5 界面
tests/                   unittest 与 Qt offscreen 测试
doc/                     设计文档和任务记录
```

## 仓库与发布

- GitHub：[Molotov0cocktail/Clender](https://github.com/Molotov0cocktail/Clender)
- Gitee：[Molotov0coaktail/clender](https://gitee.com/Molotov0coaktail/clender)

发布包和版本说明可从 GitHub Releases 或 Gitee Releases 获取。问题、建议和贡献请通过对应仓库提交。

## 安全说明

`data/` 和 `dist/data/` 是用户运行数据，可能含明文 API Key、WebDAV 密码、日程和对话记录。它们必须始终保持在版本控制之外；请勿将其附加到 issue、日志或发布包。
