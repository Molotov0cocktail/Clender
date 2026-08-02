# T14 — 安全加固

## 目标

消除高风险安全问题：
1. 移除 `data/config.json` 中硬编码的 API Key
2. 创建 `.gitignore` 防止密钥再次泄露
3. 消除代码中的 bare except 和 silent pass
4. 添加 SQL 参数化查询保护

## 输入文档

- `doc/proposal.md` — 安全性问题清单
- `data/config.json` — 当前包含真实 API Key

## 预计变更文件

| 操作 | 文件 | 说明 |
|------|------|------|
| ✨ 新建 | `.gitignore` | Git 忽略规则 |
| ✏️ 修改 | `data/config.json` | 移除真实 API Key |
| ✏️ 修改 | `database.py` | 替换 bare except |
| ✏️ 修改 | `calendar_widget.py` | 替换 bare except |
| ✏️ 修改 | `ai_chat.py`、`config.py`、`event_manager.py` | 替换 bare except |

## 实现步骤

### Step 1：创建 .gitignore

```gitignore
# 运行时数据（含敏感信息）
data/
!data/.gitkeep

# Python
__pycache__/
*.py[cod]
*.egg-info/

# 打包产物
build/
dist/
*.spec

# IDE
.vscode/
.idea/

# 日志
*.log

# 系统文件
Thumbs.db
Desktop.ini
```

创建 `data/.gitkeep`（空文件）以保留 data 目录结构。

### Step 2：清理 config.json 中的 API Key

将 `data/config.json` 中的 `api_key` 值改为空字符串：
```json
"api_key": "",
```

### Step 3：替换 bare except

在以下文件中搜索并修复：

**规则**：
- `except:` → `except Exception:`（至少捕获 Exception）
- `except Exception: pass` → 添加 `logger.warning(...)` 日志
- 对于已知异常类型（如 `ValueError`, `json.JSONDecodeError`），使用具体异常类型

**重点文件**：
| 文件 | 位置 | 修复方式 |
|------|------|---------|
| `database.py:37` | `ALTER TABLE` try-except | `except sqlite3.OperationalError:` ✓ 已是正确类型 |
| `config.py:56` | `json.load()` try-except | 添加 `logger.warning(f"配置加载失败: {e}")` |
| `calendar_widget.py:235` | 日期解析 | 添加 `logger.debug(f"跳过无效事件: {ev}")` |
| `ai_chat.py:162` | `load_conversations()` | 添加 `logger.warning(f"对话加载失败: {e}")` |
| `event_manager.py:158,166` | 时间解析 | 添加 fallback 日志 |

### Step 4：导入 logger

在各需要修复的文件中添加：
```python
from logger import get_logger
_log = get_logger(__name__)
```

## 测试与检查

```bash
git check-ignore data/config.json  # 应输出 data/config.json（表示已忽略）
```

## 完成定义

- [x] .gitignore 创建，data/ 目录被忽略
- [x] config.json 中无真实 API Key
- [x] 所有 bare except 已替换
- [x] 静默 pass 已添加日志

## 依赖

- 无（独立任务，可与 Phase 1 同步执行）