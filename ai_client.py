"""AI HTTP 客户端 - 纯网络层，无 UI 依赖"""
import requests
from PyQt5.QtCore import QThread, pyqtSignal

import config as cfg_mod
from constants import MODEL_CAPABILITIES
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
        url = f'{ep}/models' if ep.endswith('/v1') else f'{ep}/v1/models'
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

    def __init__(self, user_msg: str = "", conv=None, messages: list = None, parent=None):
        """两种构造方式：
        1. (user_msg, conv) — 兼容旧版 ai_chat.py 调用（T12 前）
        2. (messages=messages) — 新版，调用方预先构建消息列表
        """
        super().__init__(parent)
        self._msg = user_msg
        self._conv = conv
        self._messages = messages

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
            ctx_win = cfg.get('context_window', 128000)
            if not ep or not key:
                self.error_occurred.emit('请先配置API地址和密钥')
                return

            # 如果传入了预构建的消息列表，直接使用
            if self._messages is not None:
                full = self._messages
            else:
            # 兼容旧版：在 run 内部构建上下文
                base_msgs, _ = AIService.build_context_messages()
                base_tok = AIService.count_messages_tokens(base_msgs) + AIService.estimate_tokens(self._msg)
                avail = max(ctx_win - base_tok - max_tok, 4096)
                hist = [m for m in self._conv.messages[:] if m.get('role') != 'think']
                while AIService.count_messages_tokens(hist) > avail and len(hist) > 2:
                    hist.pop(0)
                full = base_msgs + hist + [{"role": "user", "content": self._msg}]

            headers = {'Content-Type': 'application/json', 'Authorization': f'Bearer {key}'}
            payload = {'model': model, 'messages': full, 'temperature': temp, 'max_tokens': max_tok}
            
            # DeepSeek thinking 模式控制
            thinking_enabled = cfg.get('thinking_enabled', True)
            if thinking_enabled:
                payload['reasoning_effort'] = think_eff if think_eff else 'high'
                payload['extra_body'] = {'thinking': {'type': 'enabled'}}
            else:
                payload['extra_body'] = {'thinking': {'type': 'disabled'}}

            if ep.endswith('/v1'):
                url = f'{ep}/chat/completions'
            elif '/chat/completions' in ep:
                url = ep
            else:
                url = f'{ep}/v1/chat/completions'

            resp = requests.post(url, headers=headers, json=payload, timeout=180)
            if resp.status_code != 200:
                self.error_occurred.emit(f'API错误({resp.status_code}): {resp.text[:300]}')
                return

            data = resp.json()
            choice = data['choices'][0]
            msg = choice['message']
            content = msg.get('content', '') or ''
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