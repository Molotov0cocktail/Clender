"""AI HTTP 客户端 - 纯网络层，无 UI 依赖"""
import re
import requests
from PyQt5.QtCore import QThread, pyqtSignal

import config as cfg_mod
from logger import get_logger
from ai_service import AIService

_log = get_logger(__name__)


def fetch_models_list() -> tuple:
    """获取模型列表，返回 (models_list, error_msg)
    
    Returns:
        tuple: (模型ID列表, 错误信息或None)
    """
    cfg = cfg_mod.load_config()
    ep = cfg.get('api_endpoint', '').strip().rstrip('/')
    key = cfg.get('api_key', '').strip()
    if not ep or not key:
        return [], '请先配置API地址和密钥'
    try:
        url = f'{ep}/models' if re.search(r'/v[0-9]+$', ep) else f'{ep}/v1/models'
        r = requests.get(url, headers={'Authorization': f'Bearer {key}'}, timeout=15)
        if r.status_code == 200:
            models_data = r.json().get('data', [])
            models = [m['id'] for m in models_data]
            cfg['available_models'] = models
            cfg_mod.save_config(cfg)
            return models, None
        return [], f'获取失败 ({r.status_code})'
    except Exception as e:
        _log.warning(f"获取模型列表失败: {e}")
        return [], str(e)


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
                    'high' if requires_thinking and think_eff == 'medium'
                    else (think_eff or 'high')
                )
            payload['thinking'] = {'type': 'enabled' if effective_effort is not None else 'disabled'}
            if effective_effort is not None:
                payload['reasoning_effort'] = effective_effort

            if re.search(r'/v[0-9]+$', ep):
                url = f'{ep}/chat/completions'
            elif '/chat/completions' in ep:
                url = ep
            else:
                url = f'{ep}/v1/chat/completions'

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
                raise ValueError('API 响应缺少 choices')
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
        except Exception as e:
            _log.error(f"AI调用异常: {e}", exc_info=True)
            self.error_occurred.emit(f'AI调用异常: {e}')
