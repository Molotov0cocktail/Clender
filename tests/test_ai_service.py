import unittest
import json
from unittest import mock

from ai_service import AIService
from models import Conversation


class AIServiceTests(unittest.TestCase):
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
        self.assertEqual(messages[-1]["content"], "x" * 200)


if __name__ == "__main__":
    unittest.main()
