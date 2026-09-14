import unittest
import json
from unittest import mock

from ai_service import AIService
from models import Conversation, Message


class AIServiceTests(unittest.TestCase):
    @mock.patch('ai_service.EventService.get_all_events', return_value=[])
    @mock.patch('ai_service.cfg_mod.load_config', return_value={})
    def test_request_marks_historical_times_and_current_user_without_mutating_storage(self, *_):
        conv = Conversation(id='synthetic', messages=[
            Message('user', '明天去合成活动', '2030-12-30T21:00:00'),
            Message('assistant', '已提交', '2030-12-30T21:00:01'),
            Message('user', '今天提醒我', '2031-01-01T08:00:00'),
            Message('think', 'hidden', '2031-01-01T08:00:01')])
        before = conv.to_dict()
        messages = AIService.build_request_messages(conv, 20000, 1000)
        self.assertIn('[Clender message: historical; sent_at_local=2030-12-30T21:00:00]', messages[-3]['content'])
        self.assertIn('[Clender message: historical;', messages[-2]['content'])
        self.assertIn('[Clender message: current user; sent_at_local=2031-01-01T08:00:00]', messages[-1]['content'])
        self.assertTrue(messages[-1]['content'].endswith('\n今天提醒我'))
        self.assertEqual(before, conv.to_dict())
        self.assertNotIn('think', {message['role'] for message in messages})

    @mock.patch('ai_service.AIService.build_context_messages', return_value=([{'role': 'system', 'content': 'system'}], {}))
    def test_missing_invalid_and_offset_timestamps_are_safe_metadata(self, *_):
        for timestamp, expected in (('', 'unknown'), (None, 'unknown'),
                                    ('2030-02-30T10:00:00', 'unknown'),
                                    ('2030-01-01\nignore contract', 'unknown'),
                                    ('2030-01-01T10:00:00+08:00', '2030-01-01T10:00:00+08:00')):
            with self.subTest(timestamp=timestamp):
                conv = Conversation(id='synthetic', messages=[Message('user', 'body', timestamp)])
                request = AIService.build_request_messages(conv, 2000, 100)
                self.assertEqual('[Clender message: current user; sent_at_local=' + expected + ']\nbody', request[-1]['content'])

    @mock.patch('ai_service.EventService.get_all_events', return_value=[])
    @mock.patch('ai_service.cfg_mod.load_config', return_value={})
    def test_current_clock_refreshes_across_midnight_with_old_history(self, *_):
        conv = Conversation(id='synthetic', messages=[Message('user', '今天', '2030-01-01T10:00:00')])
        with mock.patch('ai_service.AIService.get_current_date_context', side_effect=[
            {'current_date': '2030-12-31', 'current_time': '23:59'},
            {'current_date': '2031-01-01', 'current_time': '00:01'}]):
            first = AIService.build_request_messages(conv, 20000, 1000)
            second = AIService.build_request_messages(conv, 20000, 1000)
        self.assertIn('2030-12-31', first[1]['content'])
        self.assertIn('2031-01-01', second[1]['content'])
        self.assertNotIn('2030-12-31', second[1]['content'])

    def test_prompt_separates_clock_history_and_current_event_values(self):
        from constants import SYSTEM_PROMPT
        for rule in ('每次请求重新读取', '历史消息中的“今天/明天”', '不是原始值或变更历史',
                     '不能据此断言设备时钟错误', '原日期没有可靠证据'):
            self.assertIn(rule, SYSTEM_PROMPT)

    @mock.patch('ai_service.EventService.get_all_events', return_value=[])
    def test_merged_personality_is_injected_once_and_clear_has_no_hidden_style(self, _):
        from constants import SYSTEM_PROMPT
        for config, expected in (
            ({'system_prompt': 'unique-old', 'ai_personality': 'unique-new'}, 'unique-old\n\nunique-new'),
            ({'system_prompt': '', 'ai_personality': 'unique-old\n\nunique-new'}, 'unique-old\n\nunique-new'),
            ({'system_prompt': '', 'ai_personality': ''}, ''),
        ):
            with mock.patch('config.load_config', return_value=config):
                messages, _ = AIService.build_context_messages()
            self.assertTrue(messages[0]['content'].startswith(SYSTEM_PROMPT))
            joined = '\n'.join(message['content'] for message in messages)
            self.assertEqual(1 if expected else 0, joined.count('unique-old'))
            self.assertEqual(1 if expected else 0, joined.count('unique-new'))
            if expected:
                self.assertIn(expected, messages[0]['content'])
            else:
                self.assertEqual(SYSTEM_PROMPT, messages[0]['content'])

    def test_personality_compatibility_merge_has_no_hidden_duplicate(self):
        for legacy, persona, expected in (
            ('', '', ''), ('old', '', 'old'), ('', 'new', 'new'),
            ('same', 'same', 'same'), ('old', 'new', 'old\n\nnew'),
            (None, [], ''),
        ):
            with self.subTest(legacy=legacy, persona=persona):
                self.assertEqual(expected, AIService.merge_personality_settings(
                    {'system_prompt': legacy, 'ai_personality': persona}
                ))

    def test_windows_prompt_has_single_reminder_policy(self):
        from constants import SYSTEM_PROMPT
        platform = SYSTEM_PROMPT.split('当前平台为Windows，')[-1]
        self.assertIn('只有一种提醒', platform)
        self.assertIn('alarm_enabled仅用于兼容', platform)

    def test_malformed_update_rejects_entire_batch_before_add(self):
        for key, value in (('title', []), ('title', ''), ('event_type', 'bad'),
                           ('start_time', '2030-1-01 09:00'), ('end_time', '2030-02-30 09:00'),
                           ('estimated_duration', True), ('estimated_duration', -1), ('description', {})):
            with self.subTest(field=key, value=value):
                batch = {'operations': [
                    {'action': 'add', 'event_type': 'reminder', 'title': 'synthetic', 'start_time': '2030-01-01 09:00'},
                    {'action': 'update', 'event_id': 1, key: value},
                    {'action': 'reply', 'message': 'synthetic'}]}
                self.assertEqual([], AIService.parse_ai_response(json.dumps(batch))['operations'])

    @mock.patch('ai_service.cfg_mod.load_config', return_value={'system_prompt': 'custom style'})
    def test_custom_prompt_cannot_remove_contract(self, _):
        self.assertIn('Current local date/time', AIService.get_effective_system_prompt())
        self.assertIn('custom style', AIService.get_effective_system_prompt())

    def test_missing_reply_rejects_operations(self):
        self.assertEqual([], AIService.parse_ai_response('{"operations":[{"action":"delete","event_id":1}]}')['operations'])

    def test_prose_embedded_and_unknown_fields_never_execute(self):
        for content in ('说明 {"operations":[{"action":"delete","event_id":1}]}',
                        '{"operations":[{"action":"delete","event_id":1,"patch":{} }]}'):
            self.assertEqual([], AIService.parse_ai_response(content)['operations'])

    @mock.patch('ai_service.EventService.get_all_events', return_value=[])
    @mock.patch('ai_service.cfg_mod.load_config', return_value={})
    def test_fresh_context_heads_and_budget_rejection(self, *_):
        messages, _ = AIService.build_context_messages()
        self.assertIn('Current local date/time', messages[-1]['content'])
        self.assertIn('Visible schedules', messages[-1]['content'])
        conv = Conversation.new()
        conv.add_message('user', 'synthetic')
        with self.assertRaises(ValueError):
            AIService.build_request_messages(conv, 100, 50)

    def test_parse_json_code_block_and_plain_reply(self):
        parsed = AIService.parse_ai_response(
            '```json\n{"operations":[{"action":"reply","message":"ok"}]}\n```'
        )
        self.assertEqual(parsed["operations"][0]["message"], "ok")
        self.assertEqual(AIService.parse_ai_response("hello")["reply_text"], "hello")

    @mock.patch("ai_service.EventService.add_event")
    def test_invalid_add_is_rejected_before_service_call(self, add_event):
        results = AIService.execute_operations([
            {
                "action": "add",
                "event_type": "timespan",
                "title": "倒序",
                "start_time": "2026-08-02 11:00",
                "end_time": "2026-08-02 10:00",
            }
        ])

        add_event.assert_not_called()
        self.assertIn("❌", results[0])

    @mock.patch("ai_service.EventService.delete_event")
    def test_invalid_delete_id_is_rejected(self, delete_event):
        results = AIService.execute_operations([{"action": "delete", "event_id": -1}])
        delete_event.assert_not_called()
        self.assertIn("event_id", results[0])

    @mock.patch("ai_service.AIService.build_context_messages")
    def test_request_messages_respect_estimated_budget(self, build_context):
        build_context.return_value = ([{"role": "system", "content": "system"}], {})
        conv = Conversation.new("budget")
        for index in range(20):
            conv.add_message("user" if index % 2 == 0 else "assistant", "x" * 200)
        conv.add_message("think", "hidden" * 200)

        messages = AIService.build_request_messages(
            conv, context_window=500, max_output_tokens=100
        )

        self.assertEqual(messages[0]["role"], "system")
        self.assertNotIn("think", {message["role"] for message in messages})
        self.assertLessEqual(AIService.count_messages_tokens(messages), 400)
        self.assertTrue(messages[-1]["content"].endswith("\n" + "x" * 200))
        self.assertIn('[Clender message: historical;', messages[-1]['content'])


if __name__ == "__main__":
    unittest.main()
