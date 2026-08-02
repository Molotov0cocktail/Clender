# Python 3.12 现代化详细设计

## 目标架构

依赖方向保持 `ui → service → data/model`。新增纯逻辑模块承接可测试职责：

- `secret_store.py`：Windows Credential Manager API Key 读写；配置层只协调，不持有秘密。
- `calendar_logic.py`：事件到 Canvas block 的纯函数转换及重叠区间合并。
- `tests/`：标准库 `unittest`，所有文件/数据库测试使用临时目录。
- `.github/workflows/test.yml`：Windows + Python 3.12.4 的测试、导入和 offscreen Qt 冒烟。

## 关键设计

### 配置与秘密

`config.json` 不再保存 `api_key`。`load_config()` 在返回对象时从 Credential Manager/环境变量注入运行时 key；`save_config()` 若收到 key，先写凭据库，再写去除 key 的 JSON。检测旧配置中的明文 key 时迁移后原子重写。测试 mock `secret_store`，禁止触碰真实凭据。

### 数据库

所有连接使用 `with closing(get_connection()) as conn` 和事务上下文；查询/写入异常后也关闭。`init_db()` 幂等创建 `idx_events_start_time`。更新字段白名单保留，但 `end_time=None` 是合法清空操作，其他 `None` 字段拒绝。

### 事件与 AI 校验

`EventService` 负责通用业务校验，AI 层负责不可信操作结构校验后调用服务层。日期严格使用 `%Y-%m-%d %H:%M`；timespan 要求结束晚于开始；reminder 强制清除 end_time；ID 为正整数；预计时长为非负整数。

### 对话与线程

删除 `ui.ai_chat_widget.Conversation` 兼容类和包装持久化函数。UI 直接持有 `models.Conversation`，渲染与历史上下文读取 `Message` 属性。`AICallThread(messages=...)` 只做 HTTP；消息裁剪在 `AIService.build_request_messages()` 完成。Token 预算使用保守估算、安全余量和实际 API usage 回写，不再宣称精确 tokenizer。

### 日历逻辑

周/日视图调用纯函数构建 blocks，共享时间解析、标签、持续时间和重叠检测。Canvas 仍只负责绘制。内联 QSS 暂不做视觉重设计，避免扩大回归面；通过逻辑提取显著减少业务重复。

### 构建与依赖

删除 Win7/Python 3.8 资产和生成 spec。`build.py` 不自动安装依赖、不包含 `data/`、不默认创建桌面快捷方式；提供 `--check` 与显式 `--shortcut`。`requirements.txt` 精确锁定 Miniconda base 已验证运行依赖与 PyInstaller。

## 验证层次

1. 纯单元测试：模型、配置/凭据 mock、数据库临时文件、服务、AI、日历逻辑。
2. HTTP mock：模型列表、Chat Completions、非标准 Thinking 字段失败后的安全重试、超时/连接/非 200。
3. 集成：全部模块导入、临时数据目录、Qt offscreen 构造/立即退出。
4. 构建：`build.py --check`，随后 PyInstaller 构建和 exe 启动冒烟；不得打包 `data/`。
