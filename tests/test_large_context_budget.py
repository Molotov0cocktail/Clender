"""Large provider windows preserve system rules, output reserve and history."""
import unittest
from unittest.mock import patch

from ai_service import AIService
from models import Conversation


class LargeContextBudgetTests(unittest.TestCase):
    @patch('ai_service.AIService.build_context_messages',
           return_value=([{'role': 'system', 'content': 'fixed synthetic rules'}], {}))
    def test_large_windows_keep_history_beyond_old_128k_guess(self, _):
        conversation = Conversation.new('synthetic large budget')
        conversation.add_message('user', 'x' * 450000)
        conversation.add_message('assistant', 'earlier reply')
        conversation.add_message('user', 'latest request')
        before = conversation.to_dict()
        for context, output in ((1048576, 393216), (2097152, 131072)):
            with self.subTest(context=context):
                messages = AIService.build_request_messages(conversation, context, output)
                self.assertEqual(4, len(messages))
                self.assertEqual('fixed synthetic rules', messages[0]['content'])
                self.assertTrue(messages[-1]['content'].endswith('latest request'))
                self.assertLessEqual(AIService.count_messages_tokens(messages),
                                     context - output - int(context * .1))
        self.assertEqual(before, conversation.to_dict())

    @patch('ai_service.AIService.build_context_messages',
           return_value=([{'role': 'system', 'content': 'fixed synthetic rules'}], {}))
    def test_output_and_safety_reserve_cannot_displace_latest_request(self, _):
        conversation = Conversation.new('synthetic insufficient budget')
        conversation.add_message('user', 'x' * 1800000)
        with self.assertRaises(ValueError):
            AIService.build_request_messages(conversation, 1048576, 393216)


if __name__ == '__main__':
    unittest.main()
