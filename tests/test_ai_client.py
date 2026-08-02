import unittest
from unittest import mock

import requests

import ai_client
from ai_client import AICallThread


class FakeResponse:
    def __init__(self, status_code=200, payload=None, text=""):
        self.status_code = status_code
        self._payload = payload or {}
        self.text = text

    def json(self):
        return self._payload


class AIClientTests(unittest.TestCase):
    def config(self):
        return {
            "api_endpoint": "https://example.invalid/v1",
            "api_key": "secret",
            "model": "model",
            "temperature": 0.7,
            "max_tokens": 100,
            "thinking_enabled": True,
            "think_effort": "high",
        }

    def test_thread_only_accepts_prebuilt_messages(self):
        thread = AICallThread(messages=[{"role": "user", "content": "hi"}])
        self.assertFalse(hasattr(thread, "_conv"))
        self.assertFalse(hasattr(thread, "_msg"))

    @mock.patch("ai_client.cfg_mod.load_config")
    @mock.patch("ai_client.requests.post")
    def test_unsupported_thinking_fields_retry_without_extensions(self, post, load_config):
        load_config.return_value = self.config()
        post.side_effect = [
            FakeResponse(400, text="unsupported field"),
            FakeResponse(200, {"choices": [{"message": {"content": '{"operations":[]}'}}]}),
        ]
        results = []
        errors = []
        thread = AICallThread(messages=[{"role": "user", "content": "hi"}])
        thread.result_ready.connect(results.append)
        thread.error_occurred.connect(errors.append)

        thread.run()

        self.assertEqual(post.call_count, 2)
        retry_payload = post.call_args_list[1].kwargs["json"]
        self.assertNotIn("reasoning_effort", retry_payload)
        self.assertNotIn("extra_body", retry_payload)
        self.assertEqual(len(results), 1)
        self.assertFalse(errors)

    @mock.patch("ai_client.cfg_mod.load_config")
    @mock.patch("ai_client.requests.post", side_effect=requests.exceptions.Timeout)
    def test_timeout_is_emitted(self, post, load_config):
        load_config.return_value = self.config()
        errors = []
        thread = AICallThread(messages=[])
        thread.error_occurred.connect(errors.append)
        thread.run()
        self.assertEqual(errors, ["请求超时"])

    @mock.patch("ai_client.cfg_mod.load_config")
    @mock.patch("ai_client.requests.post")
    def test_malformed_success_response_is_emitted_as_error(self, post, load_config):
        load_config.return_value = self.config()
        post.return_value = FakeResponse(200, {"choices": []})
        errors = []
        thread = AICallThread(messages=[])
        thread.error_occurred.connect(errors.append)
        thread.run()
        self.assertIn("API 响应缺少 choices", errors[0])


if __name__ == "__main__":
    unittest.main()
