"""AI HTTP 客户端 - 纯网络层，无 UI 依赖"""
import re
from urllib.parse import urlsplit
import requests
from PyQt5.QtCore import QThread, pyqtSignal

import config as cfg_mod
from logger import get_logger
from ai_service import AIService
from model_capabilities import parse_model_metadata

_log = get_logger(__name__)
_OPENAI_REASONING_MODELS = frozenset({
    'o1', 'o1-2024-12-17', 'o3', 'o3-2025-04-16',
    'o3-mini', 'o3-mini-2025-01-31', 'o4-mini', 'o4-mini-2025-04-16',
    'gpt-5', 'gpt-5-mini', 'gpt-5-nano', 'gpt-5-2025-08-07',
    'gpt-5.1', 'gpt-5.1-2025-11-13', 'gpt-5.2', 'gpt-5.2-2025-12-11',
    'gpt-5.4', 'gpt-5.4-2026-03-05', 'gpt-6-astra',
})


def normalize_endpoint(endpoint: str) -> str:
    """Normalize a configured base or complete chat URL for cache binding."""
    endpoint = endpoint.strip().rstrip('/')
    if endpoint.endswith('/chat/completions'):
        endpoint = endpoint[:-len('/chat/completions')]
    return endpoint


def _api_url(endpoint: str, resource: str) -> str:
    """Keep explicit compatibility routes, adding v1 only for bare bases."""
    base = normalize_endpoint(endpoint)
    if (endpoint.strip().rstrip('/').endswith('/chat/completions')
            or re.search(r'/v[0-9]+$', base) or base.endswith('/openai')):
        return f'{base}/{resource}'
    return f'{base}/v1/{resource}'


def fetch_models_list() -> tuple:
    """获取模型列表，返回 (models_list, error_msg)
    
    Returns:
        tuple: (模型ID列表, 错误信息或None)
    """
    cfg = cfg_mod.load_config()
    ep = normalize_endpoint(cfg.get('api_endpoint', ''))
    key = cfg.get('api_key', '').strip()
    if not ep or not key:
        return [], '请先配置API地址和密钥'
    try:
        url = _api_url(cfg.get('api_endpoint', ''), 'models')
        r = requests.get(url, headers={'Authorization': f'Bearer {key}'}, timeout=15)
        if r.status_code == 200:
            response = r.json()
            if not isinstance(response, dict) or not isinstance(response.get('data'), list):
                return [], '模型列表响应格式错误'
            models, capabilities = [], {}
            for record in response['data']:
                if not isinstance(record, dict):
                    continue
                model_id = record.get('id')
                if not isinstance(model_id, str) or not model_id.strip() or model_id in models:
                    continue
                models.append(model_id)
                context, output = parse_model_metadata(record)
                limits = {}
                if context is not None:
                    limits['context_window'] = context
                if output is not None:
                    limits['max_output_tokens'] = output
                if limits:
                    capabilities[model_id] = limits
            cfg['available_models'] = models
            cfg['available_models_endpoint'] = ep
            cfg['model_capabilities'] = capabilities
            cfg_mod.save_config(cfg)
            return models, None
        return [], f'获取失败 ({r.status_code})'
    except Exception:
        _log.warning('获取模型列表失败（连接、响应或配置保存异常）')
        return [], '获取模型失败，请检查连接、响应格式或配置保存权限'


class AICallThread(QThread):
    """AI API 调用线程
    
    在子线程发 HTTP 请求，通过信号回传结果到主线程。
    
    Signals:
        result_ready(dict): 解析后的 AI 响应
        error_occurred(str): 错误信息
    """
    result_ready = pyqtSignal(dict)
    error_occurred = pyqtSignal(str)

    def __init__(self, messages: list[dict], parent=None):
        """Create a worker for an already validated and budgeted message list."""
        super().__init__(parent)
        self._messages = list(messages)

    def run(self):
        """执行 API 调用"""
        try:
            cfg = cfg_mod.load_config()
            ep = cfg.get('api_endpoint', '').strip().rstrip('/')
            key = cfg.get('api_key', '').strip()
            model = cfg.get('model', 'deepseek-v4-pro')
            temp = cfg.get('temperature', 0.7)
            max_tok = cfg.get('max_tokens', 4096)
            think_eff = cfg.get('think_effort', 'max')
            if not ep or not key:
                self.error_occurred.emit('请先配置API地址和密钥')
                return

            headers = {'Content-Type': 'application/json', 'Authorization': f'Bearer {key}'}
            payload = {
                'model': model, 'messages': self._messages,
                'temperature': temp, 'max_tokens': max_tok,
                'response_format': {'type': 'json_object'},
            }
            
            # Match Android's effective thinking policy for these exact models.
            thinking_enabled = cfg.get('thinking_enabled', True)
            requires_thinking = model.lower() in ('glm-5.3', 'glm-5.3-flash')
            if not thinking_enabled:
                effective_effort = 'low' if requires_thinking else None
            else:
                effective_effort = (
                    'high' if requires_thinking and think_eff in ('medium', 'xhigh')
                    else (think_eff or 'high')
                )
            payload['thinking'] = {'type': 'enabled' if effective_effort is not None else 'disabled'}
            if effective_effort is not None:
                payload['reasoning_effort'] = effective_effort

            # OpenAI reasoning families require the completion budget to include
            # hidden reasoning. Keep the same budget on the extension retry.
            endpoint_parts = urlsplit(ep)
            if (endpoint_parts.scheme == 'https'
                    and endpoint_parts.hostname == 'api.openai.com'
                    and endpoint_parts.port in (None, 443)
                    and endpoint_parts.username is None
                    and model in _OPENAI_REASONING_MODELS):
                payload['max_completion_tokens'] = payload.pop('max_tokens')
                payload.pop('temperature', None)
                payload.pop('thinking', None)
                # "max" is a local setting, not a portable OpenAI effort value.
                payload['reasoning_effort'] = (
                    ('high' if think_eff == 'max' else think_eff)
                    if thinking_enabled else 'low'
                )

            if (endpoint_parts.scheme == 'https'
                    and endpoint_parts.hostname in ('api.moonshot.cn', 'api.moonshot.ai', 'api.kimi.com')
                    and endpoint_parts.port in (None, 443)
                    and endpoint_parts.username is None
                    and model == 'kimi-k3'):
                payload['max_completion_tokens'] = payload.pop('max_tokens')
                payload.pop('temperature', None)
                payload.pop('thinking', None)
                payload['reasoning_effort'] = (
                    (think_eff if think_eff in ('low', 'high', 'max') else 'high')
                    if thinking_enabled else 'low'
                )

            url = ep if ep.endswith('/chat/completions') else _api_url(ep, 'chat/completions')

            resp = requests.post(url, headers=headers, json=payload, timeout=180)
            if resp.status_code in (400, 422) and not model.lower().startswith('glm-'):
                # OpenAI-compatible providers may reject DeepSeek extensions.
                fallback_payload = dict(payload)
                fallback_payload.pop('reasoning_effort', None)
                fallback_payload.pop('thinking', None)
                fallback_payload.pop('response_format', None)
                resp = requests.post(
                    url, headers=headers, json=fallback_payload, timeout=180
                )
            if resp.status_code != 200:
                self.error_occurred.emit(f'API错误({resp.status_code})')
                return

            data = resp.json()
            choices = data.get('choices')
            if not isinstance(choices, list) or not choices:
                self.error_occurred.emit('API 响应缺少 choices')
                return
            if choices[0].get('finish_reason') == 'length':
                self.error_occurred.emit('模型回复被截断，未执行操作，请增加输出Token或缩小请求')
                return
            if 'finish_reason' in choices[0] and choices[0]['finish_reason'] != 'stop':
                self.error_occurred.emit('模型回复未正常完成，未执行日程操作，请重试')
                return
            msg = choices[0].get('message', {})
            content = msg.get('content', '') or ''
            if not isinstance(content, str) or not content.strip():
                self.error_occurred.emit('模型没有返回正式正文，未执行操作，请重试')
                return
            think = msg.get('reasoning_content', '') or ''
            usage = data.get('usage', {})

            # 解析响应
            parsed = AIService.parse_ai_response(content)
            parsed['think'] = think
            parsed['usage'] = usage
            self.result_ready.emit(parsed)
        except requests.exceptions.Timeout:
            self.error_occurred.emit('请求超时')
        except requests.exceptions.ConnectionError:
            self.error_occurred.emit('无法连接API服务')
        except Exception:
            _log.error('AI调用异常（配置、网络或响应格式错误）')
            self.error_occurred.emit('AI调用异常，请检查配置、连接或响应格式')
