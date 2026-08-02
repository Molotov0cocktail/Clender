# T15 — 集成验证与清理

## 目标

在所有重构任务（T01-T14）完成后：
1. 验证应用完整功能无回归
2. 清理废弃的兼容别名文件
3. 更新 `build.py` 和 `Clender.spec` 的打包配置
4. 最终检查

## 输入文档

- 所有 T01-T14 任务文件
- `doc/proposal.md` — 成功标准

## 预计变更文件

| 操作 | 文件 | 说明 |
|------|------|------|
| 🗑 删除 | `calendar_widget.py` | 迁移到 ui/calendar_widget.py |
| 🗑 删除 | `event_manager.py` | 迁移到 ui/event_manager.py |
| 🗑 删除 | `ai_chat.py` | 迁移到 ui/ai_chat_widget.py |
| ✏️ 修改 | `build.py` | 更新打包配置 |
| ✏️ 修改 | `Clender.spec` | 更新 spec 文件 |
| ✏️ 修改 | `doc/tasks/progress.md` | 标记所有任务完成 |

## 验证步骤

### Step 1：启动验证

```bash
python main.py
```

检查项：
- [ ] 窗口正常显示，无 import 错误
- [ ] 三栏布局正确（日历 + 事项 + AI）
- [ ] 无控制台异常输出

### Step 2：功能回归验证

**日历功能**：
- [ ] 月视图正常显示，含事件标记（● N项）
- [ ] 周视图正常显示，含事件色块和重叠标记
- [ ] 日视图正常显示，含事件色块
- [ ] 月/周/日切换按钮正常
- [ ] 今天按钮正常
- [ ] 日期导航（◀ ▶）正常

**事项管理**：
- [ ] 事件列表正确显示选中日期的事项
- [ ] 添加提醒正常
- [ ] 添加时间段正常
- [ ] 编辑事件正常
- [ ] 删除事件（含确认对话框）正常
- [ ] 空日期显示"暂无日程安排"

**AI 对话**：
- [ ] 对话侧栏可折叠/展开
- [ ] 新建对话正常
- [ ] 切换对话正常
- [ ] 重命名对话正常
- [ ] 删除对话正常
- [ ] 发送消息正常（需有效 API Key）
- [ ] AI 操作（add/update/delete）正常执行
- [ ] Token 用量进度条更新正常
- [ ] Thinking 展开/收起正常

**主题**：
- [ ] 日间主题显示正常
- [ ] 夜间主题显示正常
- [ ] 状态栏主题切换按钮正常
- [ ] 设置中的主题切换正常

**系统功能**：
- [ ] 系统托盘图标显示正常
- [ ] 最小化到托盘（勾选设置后）正常
- [ ] 托盘菜单"显示主窗口"/"退出"正常
- [ ] 设置对话框正常打开/保存
- [ ] 示例数据首次加载正常

### Step 3：删除废弃文件

确认以下文件仅剩兼容别名导入，可安全删除：

```bash
# 检查是否还有直接引用（除兼容别名外）
grep -r "from calendar_widget" --include="*.py" | grep -v "from ui.calendar_widget import CalendarWidget"
grep -r "from event_manager" --include="*.py" | grep -v "from ui.event_manager"
grep -r "from ai_chat" --include="*.py" | grep -v "from ui.ai_chat_widget"
```

若确认无外部引用：
- 删除 `calendar_widget.py`
- 删除 `event_manager.py`
- 删除 `ai_chat.py`

### Step 4：更新打包配置

修改 `build.py` 的 `--add-data` 参数，添加新的模块：

```python
cmd = [
    sys.executable, '-m', 'PyInstaller',
    '--name=Clender',
    '--onefile',
    '--windowed',
    '--clean',
    '--noconfirm',
    '--add-data', f'requirements.txt{os.pathsep}.',
    '--add-data', f'data{os.pathsep}data',       # 打包 data 目录
    '--add-data', f'ui{os.pathsep}ui',            # 打包 ui 目录
    os.path.join(project_dir, 'main.py'),
]
```

### Step 5：更新 progress.md

将所有任务状态更新为 ✅ 已完成，填写完成时间。

## 最终检查清单

- [ ] `python main.py` 无 import 错误
- [ ] 所有 12 项功能回归通过
- [ ] 无 bare except 残留
- [ ] 模块依赖关系符合分层架构（UI → Service → Data）
- [ ] `.gitignore` 生效，`data/config.json` 未被 Git 跟踪
- [ ] `data/config.json` 中无真实 API Key
- [ ] 日志输出到 `data/clender.log`

## 完成定义

- [x] 所有功能回归验证通过
- [x] 废弃文件已清理
- [x] 打包配置已更新
- [x] progress.md 已更新

## 依赖

- T01-T14 全部完成