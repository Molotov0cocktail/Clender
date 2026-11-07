# T82 README 实际界面展示（2026-11-08）

用户要求README展示实际界面使用，可用生图美化。基线main/5a6f4b7，工作区干净；延续当前README更新的GitHub推送授权。范围仅README、仓库图片与来源说明、任务/进度/契约；不改应用代码、不重建/重发v1.3.2、不改标签。

方案：用户随后明确以Android对话界面展示为主，因此取消Windows截图，不制作辅助日历图。Android由设备agent使用隔离AVD安装当前正式1.3.2(4)，初始化空白Room后仅在此AVD写入合成对话，由正式应用渲染并截图。展示两轮对话和明暗界面。所有截图逐张审查，不包含真实配置、账号、对话或日程。演示AI对话注明为本地示例，不声称在线Provider验收。

美化可用内置imagegen生成独立品牌插画，应用界面保留真实截图，不用生图替换控件/伪造功能；插画与实截图注明区别。图片复制到assets/readme并限定合理大小，README使用仓库相对链接和移动端可读宽度。

验证矩阵：正常=图片可加载/布局清晰/来源可追溯；边界=GitHub浅深主题/窄屏宽度/完整原图链接；异常=无真实数据/生图失败不阻断实截图；回归=生产源码、版本、Release附件和标签保持不变，docs-only无需重跑应用门禁。Git diff/图片解码/链接/敏感边界检查后提交并安全快进GitHub。设备还原关闭回执写本任务。

分工：主agent品牌插画、排版与校验/发布；about_maintenance agent Android隔离截图与设备清理。完成须真实截图入README、所有图片已读图、GitHub公开路径可用。

## 记录

- 最终使用Android正式1.3.2(4) APK（SHA f09d2816881c7960abb009d7508e0e3d8acf7194f4ec78b48a9195aaccb39677），在专用clender_api36_phone / emulator-5584初始化全新空合成应用，再force-stop并以该AVD的adb root方式注入四条本地演示消息。正式应用自身Compose真实渲染，未调用Provider或提交AI日程操作，未改生产代码。无真实手机或真实数据库读取。
- 最终浅/深图各1080x1920、应用字号15sp；英文控件、中文示例对话，保持真实unknown输入预算/0累计用量。每图主agent与设备agent均读图，四条消息、输入区/指标完整，无错误状态。两图复制到assets/readme原样使用，不裁剪/重绘；README双列显示并可点击看原图。
- 生成品牌横幅1774x887，使用内置imagegen两轮：抽象3D日历品牌图→保留原图并添加Clender/让计划落在日历里/本地日历·AI日程助手文字。已逐字读图通过，PNG复制仓库。两轮提示词及图源见assets/readme/README.md，不以插画充当应用实截图。
- 夹具修正留痕：消息timestamp最初缺6位微秒而被严格加载器拒绝，改合成输入格式后成功；调整字号过程中Back触发未保存设置确认，一张中间图被覆盖为该弹窗，主agent发现后丢弃该中间图，真实保存15sp后重拍并逐图复核最终两图。未为截图改业务逻辑。应用中文locale设置虽回读成功但控件仍英文，保留实际英文控件，不冒称中文设备本地化验收。
- 系统设置回读保持font_scale1.0、1080x1920、420dpi、night=no；合成应用卸载Success、精确包名不存在；本次UI XML清除，自启动AVD身份核验后emu kill返回OK并确认已关闭。忽略回执android/.tmp/t82/android/cleanup.json，真实手机未操作。
- 三张PNG完整解码、README/素材说明全部本地链接与代码围栏通过，总2127372bytes（约2.03MiB）。尺寸/哈希记录于忽略android/.tmp/t82/assets.json。109项边界策略通过，只为本任务和四个明确素材路径扩展精确允许表。
- 255生产源码/资源/schema摘要零漂移；正式APK与EXE摘要仍与v1.3.2一致。只更改文档、图片及精确边界检查，不重跑应用测试/构建、不改Release附件/标签。根/Android AGENTS和progress已同步。
- 工程diff、图片内容与敏感扫描后本地提交并安全快进GitHub；公开README与三PNG的最终回读结果、提交对象以Git日志与本轮最终答复为准，不移动v1.3.2标签。

用户追加提醒已在GitHub添加LICENSE；已通过GitHub API只读确认远程提交089707a6b188d6bfc2d2fe4b1fd8ace642552c48（Create LICENSE），父为本轮基线5a6f4b7，内容为MIT、Copyright 2026 Molotov0cocktail。保留该用户提交和全文，README新增MIT徽章与许可证链接。直连GitHub首次reset、重试443连接超时，API读取正常，改用本机既有代理进行Git同步；不强推或删除用户提交。

