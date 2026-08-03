# T39：设置 UI、开机自启动与静默入口

## 目标

新增 WebDAV 设置、测试/立即同步入口、当前用户级自启动和 `--silent` 启动行为。

## 非目标

- 不实现定时或启动同步。
- 不支持开发态脚本自启动、Digest 或凭据管理器。

## 输入文档

- Phase 10 三层设计
- `doc/tasks/T38-webdav-sync-core.md`
- `AGENTS.md`

## 影响文件

- `startup_manager.py`、`main.py`、`single_instance.py`
- `ui/app_settings.py`、`ui/main_window.py`
- `tests/test_startup_manager.py`、`tests/test_webdav_settings_ui.py`、`tests/test_single_instance.py`、相关 MainWindow 测试

## 接口/数据影响

- `AppSettingsDialog` 新增 WebDAV 测试/同步信号和状态方法。
- `SingleInstanceCoordinator.acquire` 新增是否激活现有实例参数。
- 配置新增 WebDAV、自启动字段；HKCU Run 新增/删除 `Clender` 值。

## 风险

- 注册表与配置保存部分成功、静默 secondary 意外激活、无托盘不可恢复、QThread 退出。

## 实施步骤

- [x] 先添加 UI/注册表/入口失败测试并记录旧实现结果。
- [x] 实现 startup_manager 与保存补偿。
- [x] 实现设置控件、校验、信号、状态和主题。
- [x] MainWindow 接入 controller、区分本地/远端刷新、增加托盘同步。
- [x] 实现 `--silent` 和 probe-only secondary。
- [x] 运行聚焦/offscreen 测试并检查 diff。

## 严格测试矩阵

- 正常：冻结 exe 启停、保存/测试/立即同步、普通/silent、有/无悬浮窗。
- 边界：路径含空格、值不存在、Unicode/长密码、disabled WebDAV、busy worker、无托盘。
- 非法/异常：非 frozen 启用、注册表拒绝、config replace 失败与注册表回滚、非 HTTPS、空连接项、silent secondary。
- 回归：主题/字号/浮窗设置、完整配置保存、普通 secondary 激活、关闭到托盘和退出生命周期。

## 回滚方式

Git revert；移除 HKCU Run 的 `Clender` 值；配置新增字段可留存。

## 完成定义

- [x] 失败证据与修复结果记录。
- [x] 设置/入口/注册表均有聚焦覆盖并通过。
- [x] 所有网络仍在 worker，无凭据日志或源码常量。

## 实施结果

- 设置页新增稳定 objectName、密码遮罩、HTTPS 校验、连接测试、保存并同步、自启动和短状态；旧主题/字号/悬浮设置聚焦回归 25/25。
- 配置保存失败会补偿恢复注册表目标；补偿失败记录异常类型但不记录路径/命令/秘密。开发态控件禁用，不写真实注册表。
- MainWindow 只让本地 `data_changed` 与手动入口调用 controller；`schedules_changed` 仅刷新，独立回归证明不会形成同步循环。
- `--silent` 有托盘时隐藏主窗口、无托盘时回退显示；静默 secondary 使用 probe，不激活 primary。入口/单实例/MainWindow 聚焦 19/19。
- Light/Dark 设置页离屏尺寸最大 460×706 且控件无几何重叠；Windows offscreen 字体未栅格化，因此真实字体观感仍留 exe 可见桌面人工验证。
