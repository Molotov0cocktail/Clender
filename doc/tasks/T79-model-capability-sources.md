# T79 Windows 模型能力来源与精确值

核对日期：2026-09-15。本文件仅记录模型容量调查；不是双端实现或全部 Provider 实测声明。

## 解析契约

`model_capabilities.py` 是纯 Python 服务下层模块，无配置、网络或 Qt 副作用。公开 `parse_model_metadata(record)` 和 `resolve_model_capabilities(model_id, endpoint, metadata)`；结果为不可变 `ModelCapabilities(context_window, max_output_tokens, source, source_url)`。

优先接受当前 endpoint 返回的单模型 metadata 精确正整数，逐字段再查官方 endpoint 与精确 model ID 的内置表。禁止品牌子串、任意版本前缀、第三方同名模型继承官方额度。缺字段返回 None，保留手填；不把 1M、128K 字符串统一乘 1000 或 1024。仅 metadata 数字整型或纯 ASCII 十进制字符串可用，拒绝布尔、浮点、非正数和超过 QSpinBox 的 2147483647。此软件存储边界不是模型上限。

metadata 字段：context_window/context_length/max_context_length/max_model_len/inputTokenLimit/max_input_tokens；max_output_tokens/max_completion_tokens/max_output_length/outputTokenLimit/max_tokens。同时支持 limits/top_provider/capabilities 容器。`max_tokens` 仅解释服务端模型对象，绝不把用户请求配置当能力。输入上限作为旧共享预算的保守上界；输出上限与上下文是独立能力上限，不是可直接同时用满的请求参数。输入/输出/余量预算仍由现有发送链验证。

## 已纳入的值

以下表列出本轮核对的精确模型名；快照仅对文档明确列出的别名纳入。`—` 表示未获得精确数字或有模式歧义，应用保留手填而不编造能力。

| 模型 | 上下文 tokens | 最大输出 tokens | 官方来源与限制 |
|---|---:|---:|---|
| deepseek-flash、deepseek-v4-pro | 1048576 | 393216 | [价格表](https://api-docs.deepseek.com/quick_start/pricing/) 标注 1M/384K；[Chat schema](https://api-docs.deepseek.com/api/create-chat-completion/) 明确 384K=393216。**1048576 来自本轮用户提供的精确查询结果**，不是把网页 1M 自行转换；网页未检得该完整整数。两精确官方 API ID 才适用。 |
| gpt-4.1、gpt-4.1-2025-04-14 | 1047576 | 32768 | [模型页](https://developers.openai.com/api/docs/models/gpt-4.1) 明确 1,047,576，不能误写为 1,048,576。 |
| gpt-5、gpt-5.2、gpt-5.2-2025-12-11 | 400000 | 128000 | [GPT-5](https://developers.openai.com/api/docs/models/gpt-5)、[GPT-5.2](https://developers.openai.com/api/docs/models/gpt-5.2)。 |
| gpt-5.2-chat-latest、gpt-5.3-chat-latest | 128000 | 16384 | [5.2 Chat](https://developers.openai.com/api/docs/models/gpt-5.2-chat-latest)、[5.3 Chat](https://developers.openai.com/api/docs/models/gpt-5.3-chat-latest)，与同代推理模型不同，弃用状态不等于额度继承。 |
| gpt-5.4、gpt-5.4-2026-03-05 | 1050000 | 128000 | [模型页](https://developers.openai.com/api/docs/models/gpt-5.4)。 |
| gpt-6-astra | 1050000 | 128000 | [当前模型页](https://developers.openai.com/api/docs/models/gpt-6-astra)。 |
| chat-latest | 400000 | 128000 | [Chat Latest](https://developers.openai.com/api/docs/models/chat-latest)，可变别名仅记录本日文档。 |
| gemini-2.5-pro、gemini-2.5-flash | 1048576 | 65536 | [Pro](https://ai.google.dev/gemini-api/docs/models/gemini-2.5-pro)、[模型文档](https://ai.google.dev/gemini-api/docs/models/gemini)，官方字段是输入上限，旧统一预算保守扣输出，不声称用满官方独立输入能力。 |
| gemini-2.0-flash | 1048576 | 8192 | [模型文档](https://ai.google.dev/gemini-api/docs/models/gemini)。 |
| qwen3-max、qwen3-max-2026-01-23 | 262144 | — | [模型页](https://www.alibabacloud.com/help/en/model-studio/model-qwen3-max) 非思考输出65536、思考输出32768，思维链另81920；未把某一模式极值冒称通用上限。 |
| qwen3.6-max-preview | 262144 | 65536 | [模型页](https://www.alibabacloud.com/help/en/model-studio/qwen3-6-max)，两模式输出都是65536，思维链另计。 |
| glm-5.3-flash、glm-5.3、glm-5.2、glm-5.1、glm-5、glm-4.7 | — | 131072 | [核心参数精确表](https://docs.bigmodel.cn/cn/guide/start/concept-param)；本地直接读取同页 `.md` 已包含 5.3/Flash 两行。GLM-5.3/Flash 官方 1M 仍未给完整整数，不把其自行换算。 |
| claude-opus-5、claude-sonnet-5 | — | 128000 | [官方模型总表](https://platform.claude.com/docs/en/models/overview) 128K；[streaming](https://platform.claude.com/docs/en/build-with-claude/streaming) 与 [错误文档](https://platform.claude.com/docs/id/api/errors) 分别以 Opus/Sonnet 显式128000；联合佐证输出单位。上下文仅1M，留空。 |
| kimi-k3 | — | 1048576 | [当前 Chat API](https://platform.kimi.ai/docs/api/chat)，max_completion_tokens 注明 K3 默认131072、最高1048576；[参数表](https://platform.kimi.ai/docs/api/models-overview) 上下文仅1M，留空。 |

## 补充调查与边界

- GLM-5.3-Flash 真正官方路径是 [vlm/glm-5.3-flash](https://docs.bigmodel.cn/cn/guide/models/vlm/glm-5.3-flash)，不是 text 子目录。页面及 `.md` 核实支持1M上下文、128K输出，thinking.type仅 enabled。当前模型列表经主 agent 实测无精确字段，需要官方输出回退与上下文手填。
- Claude [Models API](https://platform.claude.com/docs/en/api/models) 已有 max_input_tokens/max_tokens，可由通用解析器接收。官方文档示例的0为占位值，不能当真实能力；1M/128K文字不能自动推导所有新 Claude 型号值。这里只支持容量发现，不声称 Windows 已实现原生 Anthropic Messages 协议。
- Kimi 旧 platform.moonshot.ai 文档重定向到 platform.kimi.ai。正确索引为 `/docs/llms.txt`；旧 K2.5 快速指南被当前模型页替代，当前列出 K3、K2.7 Code、K2.6；后两者只有256K缩写且未获得输出最大值，因此未硬编码。没有根据模型权重配置或阿里转售额度推断第一方 API。
- Qwen 不同地区和模式存在参数差异，第三方 DeepSeek 托管甚至明确为1000000上下文；官方同名不能用于转售路线。设置中所有精确值都可手动调整，metadata 更新优先。
- DeepSeek官方价格表亦明确旧别名 `deepseek-v4-flash`、`deepseek-v4-flash-vision-exp` 指向当前 Flash，已按主 agent 官方页回读纳入相同精确额度；不使用 `deepseek-*` 通配。
- 旧 GPT-4、Qwen、Claude、Llama 等品牌通配表已移除。既有精确目录之外的型号继续可用，容量标为未知；不伪称所有模型在所有接入协议/收费层级上都已经实测。
- GPT-5.6 Sol/Terra/Luna 官网总表标1.05M/128K；未追加没有逐型号精确数字证据的静态值，服务端metadata/手填仍可适配。
- 资料通过 web 官方页检索及公开 `.md`（requests，UTF-8）读取；部分 web open 超时后用 `.md`成功，未发送密钥或项目数据。来源页面随厂商变更，本表为核对日快照。

## 子任务测试证据

- RED：`C:/Users/30910/Miniconda3/python.exe -m unittest tests.test_model_capabilities -v`，3 tests/3 failures。旧deepseek-flash=(131072,8192)，gpt-4.1=(128000,16384)，未知my-deepseek-future也继承DeepSeek，均与预期不符。
- GREEN：`C:/Users/30910/Miniconda3/python.exe -m unittest tests.test_model_capabilities tests.test_ai_service -q`，31 tests 全通过，零失败/错误/跳过。含服务端优先、第三方/相似域名拒绝回退、恶意/畸形metadata、精确快照差异、百万输入/输出与部分已知能力。
- 子任务未运行真实 Provider、读取真实data、构建或提交；整体验证与临时 GLM 调用由主任务记录。
