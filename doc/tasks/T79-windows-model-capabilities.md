# T79 Windows 模型上下文与输出限制修复

2026-09-15。用户授权独立子 agent 实施、官方资料查询、本地测试及使用本轮临时 GLM 凭据进行合成验证。仅 Windows；不改 Android、版本、数据库、WebDAV 或日程操作契约，不推送或发布。凭据只在进程内使用，不写入源码、配置、日志或测试产物。

## 目标与设计

修复获取模型时旧模型名、过时宽泛能力匹配和数值控件硬上限造成的错误。模型列表保留服务端能力元数据并绑定端点；优先有效元数据，其次经官方来源核验的精确模型能力，未知能力明确提示并允许手工填写。区分服务端上限和用户本次请求输出预算；不得把未知值假称为 API 返回值。模型切换以实际选中项为准，整数保存无截断。

保留 OpenAI 兼容 Chat Completions 架构，核验主流模型的 token 限值及必要请求字段；不增加原生厂商协议或依赖。输入预算继续预留输出和安全余量，固定系统指令不足时拒绝发送。

## 分工与验收矩阵

- capabilities：官方 DeepSeek、GLM、OpenAI、Claude、Gemini、Qwen、Kimi 资料与统一能力解析；constants/ai_service/新能力模块及独立测试。
- settings：设置控件范围、当前选择联动、能力来源显示、保存与未知模型回退；UI 及设置测试。
- client：模型元数据缓存、端点绑定、请求 token 参数及客户端测试。
- 主 agent：方案、预算链路审查、集成与独立复核、全量 unittest、明暗/字号视觉、指定环境完整 EXE 构建、隔离普通/静默启动、数据只读摘要对照、本地提交。

各 bug 先 RED 再 GREEN。覆盖实际选择与旧配置不同、未知模型、同名不同端点、部分/畸形/缺失元数据、1,048,576 上下文与 393,216 输出、超过 1M 上下文、取消/保存/重开、输出和上下文关系及预算拒绝。网络单测全部 mock；真实 GLM 只用短合成消息验证生产请求/响应链路，不发送真实日程、不穷举最大额度生成。

完成标准：相关回归及完整 Windows 门禁通过、官方证据与限制可追溯、最终 EXE 可本地交付。回滚为还原本轮代码提交；不需要数据迁移。

## 验证记录

本地验证完成；以下均为本轮实际结果。

### 集成与已完成证据

- 基线 main/a4fa91ee7741cf3a833fd7623d765b679ef31640，初始工作树干净。三实现 agent 与独立交叉审查。只改 Windows AI 配置/客户端/能力解析及测试文档；保留固定提示词、预算算法、日程执行、存储协议和先保存连接再获取模型的既有契约。
- 新增纯 model_capabilities 模块；服务端精确数字优先，官方端点精确模型 ID 回退，取消品牌通配猜测。全来源/逐模型表见[T79模型资料](T79-model-capability-sources.md)。DeepSeek上下文1048576来自用户明确数据，官方网页可核1M与输出393216；GLM等未核精确上下文的项目显示未知且保留手填。
- RED：能力3失败（DeepSeek旧131072/8192、GPT4.1旧128000/16384、未知品牌误配）；设置2失败（393216被截65536、output=context仍保存），后续部分能力/独立输入输出极限整链回归先失败再通过；client首批6f/6e、官方端点/Gemini追加3f、自定义GET/Kimi追加1f/1e，均修复。最终聚焦settings16/client22/capabilities15；budget2通过，验证历史可超过旧128K且大输出预留不能驱逐系统与最新消息。
- 客户端补充：官方OpenAI精确推理模型使用max_completion_tokens，去不兼容temperature/thinking；GLM5.3 xhigh映射high；Gemini兼容base不加多余/v1；完整自定义chat地址保留资源路径。Kimi K3精确官方模型使用max_completion_tokens与low/high/max，其他未知模型不推断协议。错误日志不打印异常正文/traceback，避免秘密与响应泄漏。
- 临时 GLM 验证：生产fetch_models_list + AIService.build_request_messages + AICallThread，配置读写被mock为纯内存、真实HTTP，无真实日程/数据库/日志处理器。两轮GET models均HTTP200（10models、target存在、无target能力元数据），两轮POST均HTTP200且只有reply操作。输出预算分别2048、131072；估算输入2004；实际usage分别1291+19=1310、1291+15=1306，合计2616。仅短合成回复验证服务接受大输出参数，不声称实际生成131072 tokens或满1M输入压力测试。首个无stdin启动未取得凭据、零HTTP直接失败，后通过仅进程管道输入执行；秘密未写脚本、配置或输出。测试调用已结束。
- 视觉：Qt offscreen、Windows字体、light/dark×8/13/20px共6张合成设置截图逐张读图，获取后1048576/393216完整显示，来源换行无重叠、保存取消可见。最大字号窗口540×813，未声明低于813px可用高度或任意DPI全屏无裁切；offscreen emoji为方框，不影响数字。最终自动填充边界变更不改变此场景数值与布局。
- 指定Miniconda Python3.12.4，37根/UI模块AST+导入及build.py --check通过。首轮完整unittest384 tests/22.068s全过；随后冻结全部文件再执行最终门禁，结果追加如下。
- 数据保护：dist/data仅作完整性只读摘要，5文件169092bytes，前清单SHA256 296e7ec49e2653c94d4a41ccebf4ae282bc2f01355ea000d4c1ae07f4e155460；未用于测试或复制。历史progress.md存在非UTF8字节，按字节前插UTF8本轮记录保留旧字节，不重编码历史。第一次尝试UTF8读取失败且未写文件，随后安全前插。

### 验证范围限制

仅GLM模型进行真实授权调用；其他模型为官方资料与mock请求/metadata覆盖。未知模型及第三方端点按接口精确字段或手填，不伪称所有厂商所有模型均已在线验收。Claude原生Messages协议未实现；Kimi K3多轮要求回传reasoning_content，当前会话仅保留正文，故不宣称其多轮完整兼容。Qwen部分模型依模式变化的输出上限留空。Token预算是既有保守估算、非官方tokenizer；服务端最终决定额度。

### 最终门禁与交付

所有命令使用 `C:/Users/30910/Miniconda3/python.exe -B`；GUI验证设置 `QT_QPA_PLATFORM=offscreen`、`QT_QPA_FONTDIR=C:/Windows/Fonts`。

| 命令/检查 | 实际结果 |
|---|---|
| `-m unittest discover -s tests -v` | 冻结代码后384 tests / 21.915s，零失败/错误/跳过，exit0；`.tmp/t79/unittest-final.txt` |
| 根目录及ui全部Python AST/导入 | 37模块通过 |
| `build.py --check` | 指定Python3.12.4/Conda base与依赖通过 |
| `build.py` | 完整PyInstaller约57s，exit0；`.tmp/t79/build.txt` |
| `scripts/verify_frozen_single_instance.py` | exit0；normal-to-silent与silent-to-normal的readiness/primary_survival/secondary_exit0/quiescence/cleanup全部通过；`.tmp/t79/exe-smoke.txt` |
| `git diff --check`及精确文件敏感/范围扫描 | 通过，Android/真实数据/构建产物/临时文件不入提交 |

最终 `dist/Clender.exe` 为 **45,599,685 bytes**，SHA256 `f1dfe7bc575cdc4a87aeb611236fb1ad8966cf633358482d1b75088a3929c22b`。构建/隔离启动后 `dist/data` 五文件169092bytes，逐路径/大小/mtime/hash与构建前完全一致。临时Key未写文件/日志/提交，真实调用已结束。版本与Android不变；不推送或Release。按项目约定配置Git代理并创建本地修复提交，提交回执以Git日志及最终答复为准。
