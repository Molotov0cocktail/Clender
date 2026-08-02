# T18 — 配置安全与数据库加固

## Objective

将 API Key 移出 JSON，消除导入时文件副作用，保证数据库连接/事务安全并补充索引和清空字段语义。

## Expected Files

- `secret_store.py`、`config.py`、`logger.py`、`main.py`
- `database.py`、`event_service.py`
- 对应测试

## Dependencies

- T17。

## Implementation Steps

- [x] 实现 Credential Manager/环境变量秘密存储与旧明文迁移。
- [x] 配置保存原子化，导入时不创建目录/日志文件。
- [x] 数据库使用上下文管理并创建日期索引。
- [x] 统一事件验证，支持 `end_time=None` 清空。

## Tests And Checks

- 正常：配置/凭据往返、CRUD、索引存在、合法清空。
- 边界：空 key、缺文件、空描述、0 时长、空数据库。
- 异常：坏 JSON、凭据后端失败、SQL 失败、非法类型/时间顺序。
- 回归：旧 schema 自动补列、旧明文 key 迁移后 JSON 不再含 key。

## Definition Of Done

- [x] 无明文 key 落盘或日志泄露。
- [x] 真实数据未被测试修改。
- [x] 数据库异常路径连接关闭。
