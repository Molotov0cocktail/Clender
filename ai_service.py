"""
AI 业务服务层 - 上下文构建、Token 估算、响应解析、操作执行
不依赖 PyQt5，不直接访问 database
"""
import json
import math
import re
from datetime import datetime
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
        """Conservatively estimate tokens from UTF-8 bytes.

        Provider tokenizers differ. One token per three UTF-8 bytes plus message
        overhead intentionally overestimates most Chinese and English content.
        """
        if not text:
            return 0
        return max(1, math.ceil(len(text.encode("utf-8")) / 3))

    @staticmethod
    def count_messages_tokens(msgs: list) -> int:
        """Estimate messages including conservative per-message framing overhead."""
        return sum(
            AIService.estimate_tokens(json.dumps(message, ensure_ascii=False)) + 4
            for message in msgs
        )

    @staticmethod
    def _truncate_message(message: dict, max_tokens: int) -> dict | None:
        """Return a content-truncated copy that fits an estimated token budget."""
        if max_tokens <= 4:
            return None
        content = str(message.get("content", ""))
        candidate = dict(message)
        low, high = 0, len(content)
        while low < high:
            middle = (low + high + 1) // 2
            candidate["content"] = content[-middle:]
            if AIService.count_messages_tokens([candidate]) <= max_tokens:
                low = middle
            else:
                high = middle - 1
        if low == 0:
            return None
        candidate["content"] = content[-low:]
        return candidate

    @staticmethod
    def build_request_messages(
        conversation: Conversation,
        context_window: int,
        max_output_tokens: int,
    ) -> list[dict]:
        """Build a bounded request using system context and newest conversation turns."""
        base_messages, _ = AIService.build_context_messages()
        safety_margin = max(32, int(context_window * 0.1))
        budget = max(context_window - max_output_tokens - safety_margin, 1)

        selected_base: list[dict] = []
        used = 0
        for message in base_messages:
            cost = AIService.count_messages_tokens([message])
            if used + cost <= budget:
                selected_base.append(message)
                used += cost
                continue
            truncated = AIService._truncate_message(message, budget - used)
            if truncated:
                selected_base.append(truncated)
                used += AIService.count_messages_tokens([truncated])
            break

        history = [
            {"role": message.role, "content": message.content}
            for message in conversation.messages
            if message.role != "think"
        ]
        selected_history: list[dict] = []
        for message in reversed(history):
            remaining = budget - used
            cost = AIService.count_messages_tokens([message])
            if cost <= remaining:
                selected_history.append(message)
                used += cost
            elif not selected_history:
                truncated = AIService._truncate_message(message, remaining)
                if truncated:
                    selected_history.append(truncated)
                    used += AIService.count_messages_tokens([truncated])
            if used >= budget:
                break

        return selected_base + list(reversed(selected_history))

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
            if not isinstance(op, dict):
                results.append('❌ 操作必须是 JSON 对象')
                continue
            action = op.get('action', '')
            try:
                if action == 'add':
                    et = op.get('event_type', 'reminder')
                    title, end_time, description, estimated_duration = EventService.validate_event(
                        op.get('title', ''), et, op.get('start_time', ''),
                        op.get('end_time'), op.get('description', ''),
                        op.get('estimated_duration', 0),
                    )
                    nid = EventService.add_event(
                        event_type=et,
                        title=title,
                        start_time=op.get('start_time', ''),
                        end_time=end_time,
                        description=description,
                        estimated_duration=estimated_duration,
                    )
                    results.append(f'✅ 已添加: {title} (ID:{nid})')
                elif action == 'update':
                    eid = op.get('event_id')
                    if isinstance(eid, bool) or not isinstance(eid, int) or eid <= 0:
                        raise ValueError('event_id 必须是正整数')
                    flds = {
                        key: op[key]
                        for key in ('title', 'start_time', 'end_time', 'description', 'estimated_duration')
                        if key in op
                    }
                    if flds:
                        r = EventService.update_event(eid, **flds)
                        results.append(f'✅ 已更新 ID{eid}' if r > 0 else f'⚠️ 未找到ID{eid}')
                    else:
                        results.append('⚠️ 无修改字段')
                elif action == 'delete':
                    eid = op.get('event_id')
                    if isinstance(eid, bool) or not isinstance(eid, int) or eid <= 0:
                        raise ValueError('event_id 必须是正整数')
                    results.append(
                        f'✅ 已删除 ID{eid}' if EventService.delete_event(eid)
                        else f'⚠️ 未找到ID{eid}'
                    )
                elif action == 'reply':
                    if not isinstance(op.get('message'), str):
                        raise ValueError('reply.message 必须是字符串')
                else:
                    results.append(f'❌ 未知操作: {action}')
            except (TypeError, ValueError) as e:
                _log.warning(f"拒绝非法操作 [{action}]: {e}")
                results.append(f'❌ [{action}]无效: {e}')
            except Exception as e:
                _log.warning(f"执行操作失败 [{action}]: {e}")
                results.append(f'❌ [{action}]失败: {e}')
        return results
