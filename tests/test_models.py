import unittest

from models import Conversation, Message


class ConversationTests(unittest.TestCase):
    def test_new_conversation_has_unique_id_and_message_models(self):
        first = Conversation.new("First")
        second = Conversation.new("Second")

        self.assertNotEqual(first.id, second.id)
        self.assertTrue(first.created_at)
        first.add_message("user", "你好")
        self.assertIsInstance(first.messages[0], Message)

    def test_serialization_round_trip_preserves_token_count(self):
        conv = Conversation.new("测试")
        conv.add_message("user", "hello")
        conv.token_count = 42

        restored = Conversation.from_dict(conv.to_dict())

        self.assertEqual(restored.title, "测试")
        self.assertEqual(restored.messages[0].content, "hello")
        self.assertEqual(restored.token_count, 42)


if __name__ == "__main__":
    unittest.main()
