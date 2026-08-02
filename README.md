# Clender

Clender 是一个面向 Windows 的桌面智能日程管理应用。它使用 PyQt5 构建月、周、日视图，使用 SQLite 保存本地日程，并可连接 OpenAI 兼容的 Chat Completions API，通过自然语言协助管理日程。

## 功能

- 月、周、日三种日历视图，支持提醒和有开始/结束时间的日程；
- SQLite 本地持久化，事件创建、编辑、删除均经过统一业务校验；
- 多会话 AI 助手，可将已验证的模型操作转换为日程 CRUD；
- 日间/夜间主题，以及独立的应用与悬浮窗字号设置（8–20px）；
- 今日桌面悬浮窗：时间范围筛选、当前/下一事项提示、拖动缩放、双击编辑，以及复用当前 AI 会话的快捷输入；
- 同一 Windows 用户单实例、系统托盘和 PyInstaller 单文件构建。

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
ui/                      PyQt5 界面
tests/                   unittest 与 Qt offscreen 测试
doc/                     设计文档和任务记录
```

## 仓库与发布

- GitHub：[Molotov0cocktail/Clender](https://github.com/Molotov0cocktail/Clender)
- Gitee：[Molotov0coaktail/clender](https://gitee.com/Molotov0coaktail/clender)

发布包和版本说明请在 GitHub Releases 中获取。问题、建议和贡献请通过对应仓库提交。

## 安全说明

`data/` 和 `dist/data/` 是用户运行数据，可能含明文 API Key、日程和对话记录。它们必须始终保持在版本控制之外；请勿将其附加到 issue、日志或发布包。
