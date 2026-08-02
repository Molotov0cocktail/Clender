# T23 — API Key 恢复 JSON 持久化

## 目标

- 将 JSON 设为 API Key 唯一来源并保留原子保存。

## 非目标

- 不迁移、读取或删除现有 Credential Manager Key；不改 AI 协议。

## 影响文件

- `config.py`、`secret_store.py`、`ui/ai_settings.py`
- `tests/test_config_security.py`、`tests/test_secret_store.py`、`tests/test_ui_smoke.py`

## 接口/数据影响

- `load_config/save_config` 完整往返 `api_key`；旧的秘密存储接口退出运行链路。

## 风险

- 非 AI 设置保存时丢失 Key；原子失败破坏旧 JSON；测试误触真实配置。

## 实施步骤

- [ ] 先改测试并证明旧实现不满足 JSON 往返。
- [ ] 简化 config 与设置异常处理，删除不再使用的 secret store 代码/测试。
- [ ] 跑聚焦测试并检查导入引用。

## 测试矩阵

- 正常：非空/空 Key 保存加载；设置成功。
- 边界：Unicode、长 Key、缺文件、其他设置合并。
- 异常：坏 JSON、非对象、写入/替换失败。
- 回归：导入无副作用；真实 data 不参与；JSON 原子保存。

## 回滚方式

- 恢复 T22 配置与凭据实现；真实配置不纳入 Git 回滚。

## 完成定义

- [x] 失败证据与修复后聚焦测试齐全。
- [x] JSON 为唯一 Key 来源，无秘密存储运行时引用。

## 实施结果

- 旧实现 14 项聚焦测试出现 5 失败，覆盖 Key 被剥离、空字段删除、Unicode/长 Key 无法往返、读取时迁移和设置重读为空。
- `config.py` 保留临时文件加 `os.replace()` 的原子写入，完整保存 `api_key`；加载只做 JSON 校验与默认合并。
- 删除 `secret_store.py` 与专项测试；设置窗口只处理文件写入错误，失败仍不关闭、不发成功信号。
- 配置、UI 和 AI 客户端聚焦测试 18/18 通过；相关模块导入和 `git diff --check` 通过。
