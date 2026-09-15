"""Pure model-limit catalog and metadata parsing, below AI service and UI.

No config/network/Qt dependencies. Public APIs: resolve_model_capabilities and
parse_model_metadata. Sources and verification scope: T79-model-capability-sources.
Tests: python -m unittest tests.test_model_capabilities.
"""
from dataclasses import dataclass
from urllib.parse import urlsplit


TOKEN_LIMIT_MAX = 2147483647  # QSpinBox / signed 32-bit storage boundary, not a model limit.


@dataclass(frozen=True)
class ModelCapabilities:
    context_window: int | None = None
    max_output_tokens: int | None = None
    source: str = 'unknown'
    source_url: str = ''


# Exact model IDs only. Never infer capabilities from brand substrings.
# DeepSeek context exact value supplied by user; output confirmed by API schema.
_CATALOG = {
    'deepseek-flash': ('deepseek', 1048576, 393216),
    'deepseek-v4-pro': ('deepseek', 1048576, 393216),
    'deepseek-v4-flash': ('deepseek', 1048576, 393216),
    'deepseek-v4-flash-vision-exp': ('deepseek', 1048576, 393216),
    'gpt-4.1': ('openai', 1047576, 32768),
    'gpt-4.1-2025-04-14': ('openai', 1047576, 32768),
    'gpt-5.2': ('openai', 400000, 128000),
    'gpt-5.2-2025-12-11': ('openai', 400000, 128000),
    'gpt-5.2-chat-latest': ('openai', 128000, 16384),
    'gpt-5.4': ('openai', 1050000, 128000),
    'gpt-5.4-2026-03-05': ('openai', 1050000, 128000),
    'gpt-6-astra': ('openai', 1050000, 128000),
    'gpt-5': ('openai', 400000, 128000),
    'gpt-5.3-chat-latest': ('openai', 128000, 16384),
    'chat-latest': ('openai', 400000, 128000),
    'gemini-2.5-pro': ('google', 1048576, 65536),
    'gemini-2.5-flash': ('google', 1048576, 65536),
    'gemini-2.0-flash': ('google', 1048576, 8192),
    # Mode-dependent maximum output is intentionally unknown without metadata.
    'qwen3-max': ('qwen', 262144, None),
    'qwen3-max-2026-01-23': ('qwen', 262144, None),
    'qwen3.6-max-preview': ('qwen', 262144, 65536),
    'glm-5.3-flash': ('glm', None, 131072),
    'glm-5.3': ('glm', None, 131072),
    'glm-5.2': ('glm', None, 131072),
    'glm-5.1': ('glm', None, 131072),
    'glm-5': ('glm', None, 131072),
    'glm-4.7': ('glm', None, 131072),
    'claude-opus-5': ('anthropic', None, 128000),
    'claude-sonnet-5': ('anthropic', None, 128000),
    'kimi-k3': ('moonshot', None, 1048576),
}
_HOSTS = {
    'deepseek': {'api.deepseek.com'},
    'openai': {'api.openai.com'},
    'google': {'generativelanguage.googleapis.com'},
    'qwen': {'dashscope.aliyuncs.com', 'dashscope-intl.aliyuncs.com',
             'dashscope-us.aliyuncs.com'},
    'glm': {'open.bigmodel.cn'},
    'anthropic': {'api.anthropic.com'},
    'moonshot': {'api.moonshot.cn', 'api.moonshot.ai', 'api.kimi.com'},
}
_SOURCES = {
    'deepseek': 'https://api-docs.deepseek.com/api/create-chat-completion/',
    'openai': 'https://developers.openai.com/api/docs/models/',
    'google': 'https://ai.google.dev/gemini-api/docs/models',
    'qwen': 'https://www.alibabacloud.com/help/en/model-studio/model-qwen3-max',
    'glm': 'https://docs.bigmodel.cn/cn/guide/start/concept-param',
    'anthropic': 'https://platform.claude.com/docs/en/build-with-claude/streaming',
    'moonshot': 'https://platform.kimi.ai/docs/api/chat',
}


def _source_url(model: str, provider: str) -> str:
    if provider == 'openai':
        aliases = {'gpt-4.1-2025-04-14': 'gpt-4.1',
                   'gpt-5.2-2025-12-11': 'gpt-5.2',
                   'gpt-5.4-2026-03-05': 'gpt-5.4'}
        return _SOURCES[provider] + aliases.get(model, model)
    if provider == 'google':
        return _SOURCES[provider] + '/' + model
    if model == 'qwen3.6-max-preview':
        return 'https://www.alibabacloud.com/help/en/model-studio/qwen3-6-max'
    return _SOURCES[provider]

# Retained import compatibility; these are exact IDs, never prefix patterns.
MODEL_CAPABILITIES = {key: (value[1], value[2]) for key, value in _CATALOG.items()}


def _positive_integer(value: object) -> int | None:
    if type(value) is int:
        return value if 0 < value <= TOKEN_LIMIT_MAX else None
    if isinstance(value, str) and value.isascii() and value.isdecimal() and len(value) <= 10:
        parsed = int(value)
        return parsed if 0 < parsed <= TOKEN_LIMIT_MAX else None
    return None


def parse_model_metadata(metadata: dict | None) -> tuple[int | None, int | None]:
    """Parse exact numeric token counts, rejecting booleans and ambiguous K/M units.

    Input-token limits are conservative context ceilings for the existing shared
    input/output budget. max_tokens is only read from a model info object, never
    from the user's generation configuration.
    """
    if not isinstance(metadata, dict):
        return None, None
    containers = [metadata]
    containers.extend(metadata[key] for key in ('limits', 'top_provider', 'capabilities')
                      if isinstance(metadata.get(key), dict))

    def read(keys):
        for container in containers:
            for key in keys:
                parsed = _positive_integer(container.get(key))
                if parsed is not None:
                    return parsed
        return None

    context = read(('context_window', 'context_length', 'max_context_length',
                    'max_model_len', 'inputTokenLimit', 'max_input_tokens'))
    output = read(('max_output_tokens', 'max_completion_tokens', 'max_output_length',
                   'outputTokenLimit', 'max_tokens'))
    # Limits are independent ceilings, not a valid request pair: a provider may
    # advertise output equal to context. Actual budget validation stays upstream.
    return context, output


def resolve_model_capabilities(
    model_id: str, endpoint: str = '', metadata: dict | None = None,
) -> ModelCapabilities:
    """Resolve each limit from server metadata, then an exact official route.

    Empty endpoint supports legacy callers. A nonempty third-party endpoint
    requires its own metadata; official model limits do not describe resellers.
    """
    model = model_id.strip() if isinstance(model_id, str) else ''
    if isinstance(metadata, dict) and metadata.get('id', model) != model:
        metadata = None
    ctx, out = parse_model_metadata(metadata)
    has_metadata = ctx is not None or out is not None
    record = _CATALOG.get(model)
    official = False
    if record:
        provider, official_ctx, official_out = record
        try:
            parsed = urlsplit(endpoint.strip())
            official = not endpoint or (parsed.scheme == 'https'
                and parsed.hostname in _HOSTS[provider]
                and parsed.port in (None, 443) and parsed.username is None)
        except (ValueError, AttributeError):
            official = False
        if official:
            used_official = (ctx is None and official_ctx is not None
                             or out is None and official_out is not None)
            ctx = ctx if ctx is not None else official_ctx
            out = out if out is not None else official_out
            if used_official:
                return ModelCapabilities(ctx, out, 'metadata+official' if has_metadata
                                         else 'official', _source_url(model, provider))
    return ModelCapabilities(ctx, out, 'metadata' if has_metadata else 'unknown')
