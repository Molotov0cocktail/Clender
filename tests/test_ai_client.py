import unittest
from unittest import mock

import requests

import ai_client
from ai_client import AICallThread


class FakeResponse:
    def __init__(self, status_code=200, payload=None, text=""):
        self.status_code = status_code
        self._payload = {} if payload is None else payload
        self.text = text

    def json(self):
        return self._payload


class AIClientTests(unittest.TestCase):
    @mock.patch('ai_client.cfg_mod.save_config')
    @mock.patch('ai_client.cfg_mod.load_config')
    @mock.patch('ai_client.requests.get')
    def test_models_metadata_is_validated_and_bound_to_endpoint(self, get, load, save):
        load.return_value = self.config()
        get.return_value = FakeResponse(200, {'data': [
            {'id': 'large', 'context_length': 1048576,
             'top_provider': {'max_completion_tokens': 393216}, 'private': 'discard'},
            {'id': 'unknown'}, {'id': 'large'}, None, {'id': ''},
            {'id': 'invalid', 'context_length': True, 'max_output_tokens': -1},
        ]})
        models, error = ai_client.fetch_models_list()
        self.assertIsNone(error)
        self.assertEqual(['large', 'unknown', 'invalid'], models)
        saved = save.call_args.args[0]
        self.assertEqual('https://example.invalid/v1', saved['available_models_endpoint'])
        self.assertEqual({'large': {'context_window': 1048576,
                                  'max_output_tokens': 393216}}, saved['model_capabilities'])

    @mock.patch('ai_client.cfg_mod.save_config')
    @mock.patch('ai_client.cfg_mod.load_config')
    @mock.patch('ai_client.requests.get')
    def test_malformed_models_and_failed_fetch_do_not_replace_cache(self, get, load, save):
        load.return_value = self.config()
        for payload in ([], {'data': {}}, {'data': 'bad'}):
            with self.subTest(payload=payload):
                get.return_value = FakeResponse(200, payload)
                models, error = ai_client.fetch_models_list()
                self.assertEqual([], models)
                self.assertTrue(error)
                save.assert_not_called()

    @mock.patch('ai_client.cfg_mod.save_config')
    @mock.patch('ai_client.cfg_mod.load_config')
    @mock.patch('ai_client.requests.get')
    def test_model_url_accepts_complete_chat_endpoint(self, get, load, save):
        load.return_value = {**self.config(), 'api_endpoint': 'https://example.invalid/v1/chat/completions/'}
        get.return_value = FakeResponse(200, {'data': []})
        self.assertEqual(([], None), ai_client.fetch_models_list())
        self.assertEqual('https://example.invalid/v1/models', get.call_args.args[0])

    @mock.patch('ai_client.cfg_mod.load_config')
    @mock.patch('ai_client.requests.get')
    def test_fetch_exception_does_not_echo_sensitive_details(self, get, load):
        load.return_value = self.config()
        get.side_effect = RuntimeError('synthetic-private-key-and-response')
        with self.assertLogs('ai_client', level='WARNING') as logs:
            _, error = ai_client.fetch_models_list()
        self.assertNotIn('synthetic-private', error + str(logs.output))

    @mock.patch('ai_client.cfg_mod.save_config')
    @mock.patch('ai_client.cfg_mod.load_config')
    @mock.patch('ai_client.requests.get')
    def test_successful_refresh_replaces_stale_capabilities(self, get, load, save):
        load.return_value = {**self.config(), 'available_models_endpoint': 'https://old.invalid',
                             'model_capabilities': {'old': {'context_window': 1000}}}
        get.return_value = FakeResponse(200, {'data': [{'id': 'new'}]})
        self.assertEqual((['new'], None), ai_client.fetch_models_list())
        self.assertEqual({}, save.call_args.args[0]['model_capabilities'])

    @mock.patch('ai_client.cfg_mod.load_config')
    @mock.patch('ai_client.requests.post')
    def test_chat_exception_does_not_echo_private_request_details(self, post, load):
        load.return_value = self.config()
        post.side_effect = RuntimeError('synthetic-private-key-and-context')
        errors = []
        thread = AICallThread(messages=[])
        thread.error_occurred.connect(errors.append)
        with self.assertLogs('ai_client', level='ERROR') as logs:
            thread.run()
        self.assertNotIn('synthetic-private', str(errors) + str(logs.output))

    @mock.patch('ai_client.cfg_mod.load_config')
    @mock.patch('ai_client.requests.post')
    def test_compatible_provider_large_output_is_not_clamped(self, post, load):
        for model in ('deepseek-v4-flash', 'glm-5.3-flash', 'gpt-4.1', 'qwen3-max'):
            with self.subTest(model=model):
                load.return_value = {**self.config(), 'model': model, 'max_tokens': 393216}
                post.return_value = FakeResponse(200, {'choices': []})
                AICallThread(messages=[]).run()
                payload = post.call_args.kwargs['json']
                self.assertEqual(393216, payload['max_tokens'])
                self.assertNotIn('max_completion_tokens', payload)

    @mock.patch('ai_client.cfg_mod.load_config')
    @mock.patch('ai_client.requests.post')
    def test_openai_reasoning_token_limit_survives_fallback(self, post, load):
        for model in ('o1', 'o3-mini', 'o4-mini', 'gpt-5', 'gpt-5.4', 'gpt-6-astra'):
            with self.subTest(model=model):
                load.return_value = {**self.config(), 'api_endpoint': 'https://api.openai.com/v1', 'model': model, 'max_tokens': 131072, 'think_effort': 'max'}
                post.reset_mock()
                post.side_effect = [FakeResponse(400), FakeResponse(200, {'choices': []})]
                with mock.patch('ai_client._log'):
                    AICallThread(messages=[]).run()
                for call in post.call_args_list:
                    payload = call.kwargs['json']
                    self.assertEqual(131072, payload['max_completion_tokens'])
                    self.assertNotIn('max_tokens', payload)
                    self.assertNotIn('temperature', payload)
                    self.assertNotIn('thinking', payload)
                self.assertEqual('high', post.call_args_list[0].kwargs['json']['reasoning_effort'])

    @mock.patch('ai_client.cfg_mod.load_config')
    @mock.patch('ai_client.requests.post')
    def test_openai_specialization_does_not_guess_proxy_or_chat_model(self, post, load):
        for endpoint, model in (('https://example.invalid/v1', 'gpt-5.4'),
                                ('https://api.openai.com/v1', 'gpt-5.2-chat-latest')):
            load.return_value = {**self.config(), 'api_endpoint': endpoint, 'model': model}
            post.return_value = FakeResponse(200, {'choices': []})
            AICallThread(messages=[]).run()
            self.assertIn('max_tokens', post.call_args.kwargs['json'])
            self.assertNotIn('max_completion_tokens', post.call_args.kwargs['json'])

    @mock.patch('ai_client.cfg_mod.save_config')
    @mock.patch('ai_client.cfg_mod.load_config')
    @mock.patch('ai_client.requests.get')
    @mock.patch('ai_client.requests.post')
    def test_gemini_openai_route_preserves_explicit_base(self, post, get, load, save):
        endpoint = 'https://generativelanguage.googleapis.com/v1beta/openai'
        load.return_value = {**self.config(), 'api_endpoint': endpoint + '/', 'model': 'gemini-2.5-flash'}
        get.return_value = FakeResponse(200, {'data': []})
        post.return_value = FakeResponse(200, {'choices': []})
        self.assertEqual(([], None), ai_client.fetch_models_list())
        AICallThread(messages=[]).run()
        self.assertEqual(endpoint + '/models', get.call_args.args[0])
        self.assertEqual(endpoint + '/chat/completions', post.call_args.args[0])

    @mock.patch('ai_client.cfg_mod.save_config')
    @mock.patch('ai_client.cfg_mod.load_config')
    @mock.patch('ai_client.requests.get')
    @mock.patch('ai_client.requests.post')
    def test_custom_complete_endpoint_preserves_both_resource_routes(self, post, get, load, save):
        endpoint = 'https://proxy.invalid/api/chat/completions'
        load.return_value = {**self.config(), 'api_endpoint': endpoint + '/'}
        get.return_value = FakeResponse(200, {'data': []})
        post.return_value = FakeResponse(200, {'choices': []})
        ai_client.fetch_models_list()
        AICallThread(messages=[]).run()
        self.assertEqual(endpoint, post.call_args.args[0])
        self.assertEqual('https://proxy.invalid/api/models', get.call_args.args[0])

    @mock.patch('ai_client.cfg_mod.load_config')
    @mock.patch('ai_client.requests.post')
    def test_official_kimi_k3_fixed_parameters_and_budget(self, post, load):
        for enabled, effort, expected in ((True, 'max', 'max'), (True, 'medium', 'high'),
                                         (True, 'xhigh', 'high'), (False, 'max', 'low')):
            load.return_value = {**self.config(), 'api_endpoint': 'https://api.moonshot.cn/v1',
                                 'model': 'kimi-k3', 'thinking_enabled': enabled,
                                 'think_effort': effort, 'max_tokens': 1048576}
            post.return_value = FakeResponse(200, {'choices': []})
            AICallThread(messages=[]).run()
            payload = post.call_args.kwargs['json']
            self.assertEqual(1048576, payload['max_completion_tokens'])
            self.assertEqual(expected, payload['reasoning_effort'])
            self.assertNotIn('temperature', payload)
            self.assertNotIn('thinking', payload)
            self.assertNotIn('max_tokens', payload)

    @mock.patch('ai_client.cfg_mod.load_config')
    @mock.patch('ai_client.requests.post')
    def test_glm_xhigh_maps_to_supported_high(self, post, load):
        load.return_value = {**self.config(), 'model': 'glm-5.3-flash', 'think_effort': 'xhigh'}
        post.return_value = FakeResponse(200, {'choices': []})
        AICallThread(messages=[]).run()
        self.assertEqual('high', post.call_args.kwargs['json']['reasoning_effort'])

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
