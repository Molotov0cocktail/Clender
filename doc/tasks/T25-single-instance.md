# T25 — Windows 单实例与托盘唤醒

## 目标

- 同一 Windows 用户单实例；二次启动唤醒已有窗口并退出。

## 非目标

- 不做跨用户 IPC，不用进程扫描或第三方依赖。

## 影响文件

- 新增 `single_instance.py`、修改 `main.py`、`ui/main_window.py`
- 新增 `tests/test_single_instance.py`、更新 UI smoke。

## 接口/数据影响

- `SingleInstanceCoordinator.acquire/close/activation_requested`；MainWindow 新增统一激活方法。

## 风险

- 并发启动、陈旧端点、模态窗口、最大化状态、PyInstaller QtNetwork 收集。

## 实施步骤

- [ ] 先写 primary/secondary/activate/入口短路测试并确认旧实现缺失。
- [ ] 实现协调器和入口早期判定。
- [ ] 加固窗口激活并跑随机服务名 IPC 测试。

## 测试矩阵

- 正常：主实例、次实例、消息。
- 边界：隐藏、最小化、最大化、modal、正常退出重启。
- 异常：超时、占用、陈旧端点、竞争。
- 回归：secondary 不初始化 data/log/db/UI；offscreen 无托盘不崩溃。

## 回滚方式

- 从 main 断开协调器并删除新增模块，不涉及用户数据。

## 完成定义

- [x] 自动化 IPC 与入口短路测试通过。
- [x] 隔离 exe 双实例冒烟通过。

## 实施结果

- 旧实现 12 项聚焦测试产生 9 错误，证明缺少协调器、入口早期短路和统一窗口激活接口。
- 新增基于 QLocalServer/QLocalSocket 的同用户协调器；竞争时复查，确认陈旧后才清理端点。
- secondary 在 data/log/db/UI 前返回 0；激活路径保留最大化状态并优先置前 modal。
- 单实例与 UI 聚焦测试 13/13、相关导入和 `git diff --check` 通过；实际 exe 双实例留待 T29。
- PyInstaller 隔离副本中首实例创建测试 DB 并持续运行；第二实例 10 秒内以 0 退出；首实例保持存活。按精确 exe 路径清理两个 onefile 进程后无残留。
