"""
AI 业务服务层 - 上下文构建、Token 估算、响应解析、操作执行
不依赖 PyQt5，不直接访问 database
"""
import json
import re
from datetime import datetime
from typing import Optional

import config as cfg_mod
from constants import SYSTEM_PROMPT, MODEL_CAPABILITIES
from event_service import EventService
from models import Conversation
from logger import get_logger

_log = get_logger(__name__)


class AIService:
    """AI 业务服务 — 纯静态方法，无状态，不依赖 PyQt5"""

    # ── 上下文构建 ──

    @staticmethod
    def get_current_date_context() -> dict:
        """获取当前日期时间上下文"""
        n = datetime.now()
        return {
            "current_date": n.strftime('%Y-%m-%d'),
            "current_time": n.strftime('%H:%M'),
            "day_of_week": ["周一", "周二", "周三", "周四", "周五", "周六", "周日"][n.weekday()],
        }

    @staticmethod
    def get_effective_system_prompt() -> str:
        """返回最终生效的系统提示词（用户自定义 > 默认）"""
        cfg = cfg_mod.load_config()
        custom = cfg.get('system_prompt', '').strip()
        return custom if custom else SYSTEM_PROMPT

    @staticmethod
    def build_context_messages() -> tuple[list[dict], dict]:
        """构建发送给 AI 的完整消息上下文
        
        Returns:
            tuple: (消息列表, 日期上下文)
        """
        events = EventService.get_all_events()
        ctx = AIService.get_current_date_context()
        cfg = cfg_mod.load_config()
        personality = cfg.get('ai_personality', '').strip()

        main_prompt = AIService.get_effective_system_prompt()
        msgs = [{"role": "system", "content": main_prompt}]

        if personality:
            msgs.append({"role": "system", "content": f"[AI人格设定]\n{personality}"})

        # 序列化事件（将 Event 对象转为 dict 以便 JSON 序列化）
        events_data = [e.to_dict() if hasattr(e, 'to_dict') else e for e in events]
        msgs.append({
            "role": "system",
            "content": f"日程和上下文:\n{json.dumps({'context': ctx, 'events': events_data}, ensure_ascii=False, indent=2)}"
        })
        return msgs, ctx

    # ── Token 估算 ──

    @staticmethod
    def estimate_tokens(text: str) -> int:
        """估算文本的 Token 数量（中文约 0.6 token/字，英文约 0.3 token/字符）"""
        if not text:
            return 0
        cn = sum(1 for c in text if '\u4e00' <= c <= '\u9fff')
        en = len(text) - cn
        return int(cn * 0.6 + en * 0.3)

    @staticmethod
    def count_messages_tokens(msgs: list) -> int:
        """估算消息列表的总 Token 数"""
        return sum(AIService.estimate_tokens(json.dumps(m, ensure_ascii=False)) for m in msgs)

    # ── 模型能力推断 ──

    @staticmethod
    def guess_model_capabilities(model_id: str) -> tuple[int, int]:
        """根据模型名称推断上下文窗口大小和最大输出 token

        Returns:
            tuple: (context_window, max_output_tokens)
        """
        model_lower = model_id.lower()
        ctx_win = 128000
        max_out = 4096

        for key, (ctx, out) in MODEL_CAPABILITIES.items():
            if key in model_lower:
                ctx_win = ctx
                max_out = out
                break
        else:
            if 'deepseek' in model_lower:
                ctx_win = 131072; max_out = 8192
            elif 'gpt-4' in model_lower or 'gpt4' in model_lower:
                ctx_win = 128000; max_out = 16384
            elif 'gpt-3.5' in model_lower:
                ctx_win = 16385; max_out = 4096
            elif 'claude' in model_lower:
                ctx_win = 200000; max_out = 8192
            elif 'gemini' in model_lower:
                ctx_win = 131072; max_out = 8192
            elif 'context' in model_lower or 'large' in model_lower:
                ctx_win = 131072; max_out = 16384

        return ctx_win, max_out

    # ── 响应解析 ──

    @staticmethod
    def parse_ai_response(content: str) -> dict:
        """解析 AI 返回的 JSON 响应

        Returns:
            dict: {'raw', 'operations', 'reply_text', 'think', 'usage'}
        """
        result = {'raw': content, 'operations': [], 'reply_text': '', 'think': '', 'usage': {}}
        txt = content

        # 优先提取 Markdown 代码块中的 JSON
        m = re.search(r'```(?:json)?\s*\n?(.*?)\n?```', content, re.DOTALL)
        if m:
            txt = m.group(1).strip()
        else:
            a = content.find('{')
            b = content.find('[')
            if a != -1 or b != -1:
                start = min(a if a != -1 else 99999, b if b != -1 else 99999)
                txt = content[start:]

        try:
            p = json.loads(txt)
            if isinstance(p, dict) and 'operations' in p:
                result['operations'] = p['operations']
            elif isinstance(p, list):
                result['operations'] = p
            elif isinstance(p, dict):
                result['operations'] = [p]
        except (json.JSONDecodeError, ValueError):
            result['reply_text'] = content

        return result

    # ── 操作执行（通过 EventService，不直接访问 database）──

    @staticmethod
    def execute_operations(ops: list) -> list[str]:
        """执行 AI 返回的操作指令列表

        Args:
            ops: 操作列表 [{'action': 'add', ...}, ...]

        Returns:
            list[str]: 每条操作的执行结果描述
        """
        results = []
        for op in ops:
            action = op.get('action', '')
            try:
                if action == 'add':
                    et = op.get('event_type', 'reminder')
                    if et not in ('reminder', 'timespan'):
                        results.append(f'❌ 未知类型: {et}')
                        continue
                    nid = EventService.add_event(
                        event_type=et,
                        title=op.get('title', '未命名'),
                        start_time=op.get('start_time', ''),
                        end_time=op.get('end_time') if et == 'timespan' else None,
                        description=op.get('description', ''),
                    )
                    results.append(f'✅ 已添加: {op.get("title", "未命名")} (ID:{nid})')
                elif action == 'update':
                    eid = op.get('event_id')
                    if not eid:
                        results.append('❌ 缺少event_id')
                        continue
                    flds = {k: op[k] for k in ('title', 'start_time', 'end_time', 'description') if k in op}
                    if flds:
                        r = EventService.update_event(eid, **flds)
                        results.append(f'✅ 已更新 ID{eid}' if r > 0 else f'⚠️ 未找到ID{eid}')
                    else:
                        results.append('⚠️ 无修改字段')
                elif action == 'delete':
                    eid = op.get('event_id')
                    if not eid:
                        results.append('❌ 缺少event_id')
                        continue
                    results.append(
                        f'✅ 已删除 ID{eid}' if EventService.delete_event(eid)
                        else f'⚠️ 未找到ID{eid}'
                    )
                elif action == 'reply':
                    pass  # 纯文本回复，不执行操作
                else:
                    results.append(f'❌ 未知操作: {action}')
            except Exception as e:
                _log.warning(f"执行操作失败 [{action}]: {e}")
                results.append(f'❌ [{action}]失败: {e}')
        return results