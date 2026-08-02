import unittest
from unittest import mock

from ai_service import AIService
from models import Conversation


class AIServiceTests(unittest.TestCase):
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
