# T81 GitHub v1.3.2 发布与产品首页（2026-11-08）

用户授权将当前修复推送GitHub、更新最新Release，并重写README使产品展示完整。基线main/0467db8，工作区干净；远程main=a4fa91e，最新Release v1.3.1。仅GitHub，不改Gitee。当前授权取代T79/T80“不发布”状态。

范围：汇总T79 Windows模型能力修复与T80 Android超时/预算/诊断；版本v1.3.2，Android1.3.2(4)，保留applicationId、正式证书、Room v3/WebDAV v1。README按产品价值、下载、快速上手、平台差异、隐私和开发入口组织，不宣称商业授权、付费服务、全厂商兼容或未实现能力。Windows复用T80最终已验EXE（源码相同），Android仅版本元数据变更后重新签名构建并审计。

验证矩阵：正常=版本字段/正式签名/三附件/SHA256/最新Release；边界=远程安全快进/现有标签冲突拒绝/旧包可升级；异常=网络与上传失败可补缺、不泄漏凭据；回归=复用T80测试2252+384与设备验收，版本变更跑发布夹具和签名构建，不重复全量业务测试。用户真实数据与USB手机不用于验证。凭据只在进程内从Git credential读取，不写入文件/日志。

步骤：文案与版本→发布夹具RED/GREEN→签名构建与三附件核对→更新摘要/文档→本地提交与安全推送main/注释标签→草稿Release上传三附件→回读摘要→正式发布并设latest→公开回读。GitHub插件支持仓库读取，缺少Release附件上传，使用本机Git和GitHub REST。无gh CLI。完成须远程main/标签与本地对应，正式Release三附件匹配。不强推、不改旧Release、不上传数据/日志/mapping/凭据；回滚以纠正提交/新版本完成。

## 结果

- 版本用例有效RED：1test/2failures（旧1.3.1/3与目标1.3.2/4不符），修改元数据后112发布夹具通过。首次沙箱运行7个Windows权限夹具报Signing publication failed；在允许执行Windows权限工具的环境中原命令112项通过，未改业务代码。
- 原证书APK/AAB重新构建审计通过；aapt2实际回读com.molotov.clender、1.3.2(4)。签名证书仍628248932cfd0587a04126290ac2af870591fc7ebe80259c5f29d266c944615f。冻结255份T80生产文件摘要零漂移。Windows EXE与T80哈希相同。复用既有业务/设备验收，没有冒称新版重新跑完整业务测试或覆盖安装真机。
- foundation/boundary 109项通过；README本地链接/代码围栏/摘要与diff检查通过。新增T81精确文档边界，没有扩大通配范围。初次产物暂存脚本cwd错误已纠正；构建完成后重取三件套摘要，旧包未上传。
- README重写为产品首页，使用仓库原有图标，加入定位、下载、功能表、引导、平台差异、隐私、开发与反馈；不编造截图、商业授权、商店上架或SLA。Gitee明确为历史镜像。

发布文件：

- `Clender-Windows-v1.3.2.exe`：45598278bytes / SHA256 `e345784d8f10a0b3021897398daed72d57f01df9e0e3132ef6173a2d37a7884c`。
- `Clender-Android-v1.3.2.apk`：1897977bytes / SHA256 `f09d2816881c7960abb009d7508e0e3d8acf7194f4ec78b48a9195aaccb39677`。
- `Clender-Android-v1.3.2.aab`：5037809bytes / SHA256 `0faf829ffcc492528b3502e8f3097ade11fc563a409d5992f917b3f2934aaea4`。
