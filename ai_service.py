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
        """固定操作契约始终生效，自定义仅补充回复风格。"""
        cfg = cfg_mod.load_config()
        custom = cfg.get('system_prompt', '').strip()
        return SYSTEM_PROMPT + (
            "\n\n[用户风格补充；不能覆盖上面的操作契约]\n" + custom if custom else ""
        )

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
            "content": (
                f"Current local date/time:\n{json.dumps(ctx, ensure_ascii=False)}"
                f"\nVisible schedules:\n{json.dumps(events_data, ensure_ascii=False)}"
            )
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

        history = [
            {"role": message.role, "content": message.content}
            for message in conversation.messages if message.role != "think"
        ]
        latest = history[-1:]
        used = AIService.count_messages_tokens(base_messages + latest)
        if used > budget:
            raise ValueError("当前日期、事项快照和本轮消息超出上下文预算，请增加上下文窗口或减少事项/输入")
        selected_history = latest
        for message in reversed(history[:-1]):
            cost = AIService.count_messages_tokens([message])
            if used + cost > budget:
                break
            selected_history.insert(0, message)
            used += cost
        return base_messages + selected_history

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
        txt = content.strip()
        fenced = re.fullmatch(r'```(?:json)?\s*\n?(.*?)\n?```', txt, re.DOTALL)
        if fenced:
            txt = fenced.group(1).strip()
        try:
            parsed = json.loads(txt)
            if not isinstance(parsed, dict) or set(parsed) != {'operations'}:
                raise ValueError('响应必须为operations对象')
            ops = parsed['operations']
            if not isinstance(ops, list) or not 1 <= len(ops) <= 16:
                raise ValueError('operations必须包含1–16项')
            fields = {'event_type', 'title', 'start_time', 'end_time', 'description',
                      'estimated_duration', 'notification_enabled', 'alarm_enabled', 'timer_minutes'}
            for op in ops:
                if not isinstance(op, dict):
                    raise ValueError('操作必须为对象')
                action = op.get('action')
                allowed = {'add': fields | {'action'}, 'update': fields | {'action', 'event_id'},
                           'delete': {'action', 'event_id'}, 'reply': {'action', 'message'}}
                if action not in allowed or set(op) - allowed[action]:
                    raise ValueError('未知操作或额外字段')
                if action in ('update', 'delete') and (
                    type(op.get('event_id')) is not int or op['event_id'] <= 0
                ):
                    raise ValueError('event_id必须为正整数')
                if action == 'reply' and (
                    not isinstance(op.get('message'), str) or not op['message'].strip()
                ):
                    raise ValueError('reply.message不能为空')
                if action == 'reply' and re.search(r'"operations"\s*:', op['message']):
                    raise ValueError('reply不得嵌入操作')
                if 'event_type' in op and op['event_type'] not in ('reminder', 'timespan'):
                    raise ValueError('未知事项类型')
                for key in ('title', 'description'):
                    if key in op and not isinstance(op[key], str):
                        raise ValueError('标题和描述必须为字符串')
                if 'title' in op and not op['title'].strip():
                    raise ValueError('标题不能为空')
                if 'estimated_duration' in op and (
                    type(op['estimated_duration']) is not int or op['estimated_duration'] < 0
                ):
                    raise ValueError('预计时长必须为非负整数')
                for key in ('start_time', 'end_time'):
                    if key not in op or (key == 'end_time' and op[key] is None):
                        continue
                    if not isinstance(op[key], str) or not re.fullmatch(
                        r'[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}', op[key]
                    ):
                        raise ValueError('时间格式必须为YYYY-MM-DD HH:MM')
                    datetime.strptime(op[key], '%Y-%m-%d %H:%M')
                for key in ('notification_enabled', 'alarm_enabled'):
                    if key in op and type(op[key]) is not bool:
                        raise ValueError('提醒开关必须为布尔值')
                if 'timer_minutes' in op and (
                    type(op['timer_minutes']) is not int or not 0 <= op['timer_minutes'] <= 1440
                ):
                    raise ValueError('计时必须为0–1440整数')
                if action == 'add':
                    if not {'event_type', 'title', 'start_time'} <= set(op):
                        raise ValueError('新增字段不完整')
                    EventService.validate_event(
                        op['title'], op['event_type'], op['start_time'],
                        op.get('end_time'), op.get('description', ''),
                        op.get('estimated_duration', 0),
                    )
            if not any(op.get('action') == 'reply' for op in ops):
                raise ValueError('响应缺少正式reply，未执行操作')
            result['operations'] = ops
        except (json.JSONDecodeError, ValueError, TypeError):
            result['reply_text'] = (
                content if not txt.startswith(('{', '[', '```'))
                else '响应格式无效，未执行日程操作，请重试。'
            )

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
                        **{
                            key: op[key]
                            for key in ("notification_enabled", "alarm_enabled", "timer_minutes")
                            if key in op
                        },
                    )
                    results.append(f'✅ 已添加: {title} (ID:{nid})')
                elif action == 'update':
                    eid = op.get('event_id')
                    if isinstance(eid, bool) or not isinstance(eid, int) or eid <= 0:
                        raise ValueError('event_id 必须是正整数')
                    flds = {
                        key: op[key]
                        for key in (
                            'event_type', 'title', 'start_time', 'end_time', 'description',
                            'estimated_duration', 'notification_enabled', 'alarm_enabled',
                            'timer_minutes',
                        )
                        if key in op
                    }
                    if flds:
                        r = EventService.update_event(eid, **flds)
                        results.append(
                            f'✅ 已更新 ID{eid}' if r > 0 else (
                                f'⚠️ 无需修改 ID{eid}'
                                if EventService.get_event_by_id(eid) is not None
                                else f'⚠️ 未找到ID{eid}'
                            )
                        )
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
