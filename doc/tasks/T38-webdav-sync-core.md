# T38：WebDAV 同步数据契约与核心服务

## 目标

实现跨设备事件 UUID、更新时间、删除墓碑、远端文档验证、确定性合并、Basic WebDAV 客户端和后台同步服务。

## 非目标

- 不修改 UI、自启动或入口可见性。
- 不访问真实 WebDAV 或真实 `data/`。

## 输入文档

- `doc/webdav-autostart-proposal.md`
- `doc/webdav-autostart-high-level-design.md`
- `doc/webdav-autostart-detailed-design.md`
- `AGENTS.md`

## 影响文件

- `database.py`、`event_service.py`、`constants.py`
- `webdav_sync.py`、`sync_controller.py`
- `tests/test_webdav_sync.py`、`tests/test_sync_database.py`、`tests/test_sync_controller.py`

## 接口/数据影响

- `events` 增加 `sync_uid/updated_at/deleted_at` 与唯一索引；delete 改为软删除。
- 新增远端 schema v1、同步数据库接口和 Qt controller 信号。
- 远端仅含日程和同步元数据，不含本机整数 ID、对话或配置。

## 风险

- schema 迁移失败、时钟偏差、远端不可信 JSON、并发 412、本地变更与 worker 竞态。

## 实施步骤

- [x] 先添加完整失败测试并记录旧实现结果。
- [x] 实现幂等迁移和 CRUD 同步元数据。
- [x] 实现文档验证、规范序列化与合并。
- [x] 实现 WebDAV HTTP 与有限 ETag 重试。
- [x] 实现 controller busy/pending/refresh/shutdown。
- [x] 运行聚焦测试并检查 diff。

## 严格测试矩阵

- 正常：新/旧 DB、add/update/delete、local-only/remote-only/union/LWW、GET/PUT/PROPFIND、controller local/manual。
- 边界：空 DB、Unicode/长字段、同时间戳、100000 条上限、404 文件、无 ETag、运行中再次变更。
- 非法/异常：重复/坏 UUID、坏时间/事件、未知 schema、5 MiB 超限、timeout/connection/401/403/500、412 两次、事务失败。
- 回归：所有查询过滤墓碑；Event/AI dict 不暴露同步字段；相交查询、counts、类型转换保持。

## 回滚方式

Git revert 源码；真实库若已产生墓碑不得直接用旧版本打开，按详细设计的受控导出策略处理。

## 完成定义

- [x] 旧实现失败证据与修复后结果已记录。
- [x] 所有同步核心测试通过且无真实网络/数据访问。
- [x] 事务、兼容、错误和日志契约满足设计。

## 实施结果

- 旧实现聚焦运行共 23 项：3 failure、12 error，分别证明缺少模块、schema 字段/接口、设置控件、silent 参数和 probe-only 单实例契约。
- 新增临时旧库迁移、UUID 唯一、soft delete、相交过滤、整批事务回滚和较新本地写保护；普通查询继续只返回活动事件。
- 新增严格 schema v1、规范 JSON、确定性 LWW、5 MiB/100000 条限制、流式截止时间、Basic PROPFIND/GET/PUT、ETag create/update 与 412 一次重试。
- controller 聚焦覆盖 disabled、manual、busy/pending、远端刷新与 transient probe；没有加入启动或周期同步。
- 首轮 T38 聚焦 20/20，通过后与 T39 集成聚焦 50/50；所有 DB/HTTP/配置均为临时或 mock。
