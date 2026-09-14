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
    @mock.patch('ai_client.cfg_mod.load_config')
    @mock.patch('ai_client.requests.post')
    def test_unfinished_choices_never_deliver_executable_json(self, post, load):
        load.return_value = self.config()
        for reason in ('content_filter', 'tool_calls', None, 'unknown'):
            with self.subTest(reason=reason):
                post.return_value = FakeResponse(200, {'choices': [{'finish_reason': reason, 'message': {'content': '{"operations":[{"action":"reply","message":"ok"}]}'}}]})
                results, errors = [], []
                thread = AICallThread(messages=[])
                thread.result_ready.connect(results.append)
                thread.error_occurred.connect(errors.append)
                thread.run()
                self.assertEqual([], results)
                self.assertTrue(errors)

    @mock.patch('ai_client.cfg_mod.load_config')
    @mock.patch('ai_client.requests.post')
    def test_thinking_mapping_matches_android_exact_models(self, post, load):
        post.return_value = FakeResponse(200, {'choices': [{'message': {'content': '{"operations":[{"action":"reply","message":"ok"}]}'}}]})
        for model in ('glm-5.3', 'glm-5.3-flash', 'GLM-5.3-FLASH', 'glm-5.3-other', 'other'):
            target = model.lower() in ('glm-5.3', 'glm-5.3-flash')
            for effort in ('low', 'medium', 'high', 'max'):
                for enabled in (True, False):
                    with self.subTest(model=model, effort=effort, enabled=enabled):
                        load.return_value = {**self.config(), 'model': model, 'think_effort': effort, 'thinking_enabled': enabled}
                        AICallThread(messages=[]).run()
                        payload = post.call_args.kwargs['json']
                        expected = ('high' if effort == 'medium' else effort) if target and enabled else ('low' if target else (effort if enabled else None))
                        self.assertEqual(expected, payload.get('reasoning_effort'))
                        self.assertEqual({'type': 'enabled' if enabled or target else 'disabled'}, payload['thinking'])

    @mock.patch('ai_client.cfg_mod.load_config')
    @mock.patch('ai_client.requests.post')
    def test_glm_v4_url_and_top_level_thinking(self, post, load):
        load.return_value = {**self.config(), 'api_endpoint': 'https://open.bigmodel.cn/api/paas/v4/', 'model': 'glm-5.3-flash', 'think_effort': 'medium'}
        post.return_value = FakeResponse(200, {'choices': [{'message': {'content': '{"operations":[{"action":"reply","message":"ok"}]}'}}]})
        AICallThread(messages=[]).run()
        self.assertEqual('https://open.bigmodel.cn/api/paas/v4/chat/completions', post.call_args.args[0])
        payload = post.call_args.kwargs['json']
        self.assertEqual({'type': 'enabled'}, payload['thinking'])
        self.assertEqual('high', payload['reasoning_effort'])
        self.assertNotIn('extra_body', payload)
        self.assertEqual({'type': 'json_object'}, payload['response_format'])

    @mock.patch('ai_client.cfg_mod.load_config')
    @mock.patch('ai_client.requests.post')
    def test_glm_disabled_and_truncated_response(self, post, load):
        load.return_value = {**self.config(), 'model': 'glm-5.3-flash', 'thinking_enabled': False}
        post.return_value = FakeResponse(200, {'choices': [{'finish_reason': 'length', 'message': {'content': '{"operations":[{"action":"reply","message":"ok"}]}'}}]})
        results, errors = [], []
        thread = AICallThread(messages=[])
        thread.result_ready.connect(results.append)
        thread.error_occurred.connect(errors.append)
        thread.run()
        self.assertEqual([], results)
        self.assertTrue(errors)
        self.assertEqual('low', post.call_args.kwargs['json']['reasoning_effort'])

    @mock.patch('ai_client.cfg_mod.load_config')
    @mock.patch('ai_client.requests.post')
    def test_reasoning_only_is_visible_error_not_success(self, post, load):
        load.return_value = self.config()
        post.return_value = FakeResponse(200, {'choices': [{'message': {'content': '', 'reasoning_content': 'synthetic'}}]})
        results, errors = [], []
        thread = AICallThread(messages=[])
        thread.result_ready.connect(results.append)
        thread.error_occurred.connect(errors.append)
        thread.run()
        self.assertEqual([], results)
        self.assertIn('没有返回正式正文', errors[0])

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
        self.assertNotIn("response_format", retry_payload)
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
