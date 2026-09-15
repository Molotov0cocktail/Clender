"""Pure capability resolution regression tests; no config or network access."""
import unittest

from ai_service import AIService
from model_capabilities import parse_model_metadata, resolve_model_capabilities


class LegacyCapabilityRegressionTests(unittest.TestCase):
    def test_deepseek_flash_exact_limits(self):
        for model in ('deepseek-flash', 'deepseek-v4-pro', 'deepseek-v4-flash',
                      'deepseek-v4-flash-vision-exp'):
            with self.subTest(model=model):
                self.assertEqual(AIService.guess_model_capabilities(model),
                                 (1048576, 393216))

    def test_unknown_does_not_inherit_brand_limits(self):
        self.assertEqual(AIService.guess_model_capabilities('my-deepseek-future'),
                         (None, None))

    def test_gpt_41_is_not_captured_by_gpt_4_prefix(self):
        self.assertEqual(AIService.guess_model_capabilities('gpt-4.1'),
                         (1047576, 32768))


class MetadataTests(unittest.TestCase):
    def test_provider_metadata_precedes_catalog_per_field(self):
        caps = resolve_model_capabilities('deepseek-flash', 'https://api.deepseek.com',
                                          {'context_window': 900000})
        self.assertEqual((caps.context_window, caps.max_output_tokens), (900000, 393216))
        self.assertEqual(caps.source, 'metadata+official')

    def test_third_party_cannot_inherit_official_limits(self):
        for endpoint in ('https://proxy.example/v1', 'https://api.deepseek.com.evil/v1',
                         'https://api.deepseek.com:444/v1', 'http://api.deepseek.com',
                         'https://name@api.deepseek.com', 'https://[invalid'):
            with self.subTest(endpoint=endpoint):
                caps = resolve_model_capabilities('deepseek-flash', endpoint)
                self.assertEqual((caps.context_window, caps.max_output_tokens), (None, None))

    def test_metadata_supported_on_unknown_route_and_model(self):
        caps = resolve_model_capabilities('custom', 'https://proxy.example',
                                         {'context_length': 2000000, 'max_completion_tokens': 700000})
        self.assertEqual((caps.context_window, caps.max_output_tokens), (2000000, 700000))
        self.assertEqual(caps.source, 'metadata')

    def test_exact_numeric_strings_and_nested_provider_fields(self):
        self.assertEqual(parse_model_metadata({'top_provider': {
            'context_length': '1048576', 'max_completion_tokens': '393216'}}), (1048576, 393216))

    def test_google_and_anthropic_model_metadata(self):
        self.assertEqual(parse_model_metadata({'inputTokenLimit': 1048576,
                                              'outputTokenLimit': 65536}), (1048576, 65536))
        self.assertEqual(parse_model_metadata({'max_input_tokens': 1000000,
                                              'max_tokens': 128000}), (1000000, 128000))

    def test_invalid_values_do_not_become_fictitious_limits(self):
        for value in (True, False, 0, -1, 2**31, 3.5, float('nan'), '1M', '384K',
                      '1,048,576', '99999999999999999999999', [], {}, None):
            with self.subTest(value=value):
                self.assertEqual(parse_model_metadata({'context_window': value,
                    'max_output_tokens': value}), (None, None))

    def test_mismatched_model_metadata_is_ignored(self):
        caps = resolve_model_capabilities('unknown', 'https://proxy.example',
                                         {'id': 'other', 'context_window': 1000000})
        self.assertIsNone(caps.context_window)

    def test_ambiguous_mode_limit_stays_unknown(self):
        caps = resolve_model_capabilities('qwen3-max', 'https://dashscope.aliyuncs.com/compatible-mode/v1')
        self.assertEqual(caps.context_window, 262144)
        self.assertIsNone(caps.max_output_tokens)

    def test_distinct_exact_catalog_ids(self):
        for model, expected in {'gpt-5.4': (1050000, 128000),
                                'gpt-5.2-chat-latest': (128000, 16384),
                                'gemini-2.5-flash': (1048576, 65536),
                                'gpt-6-astra': (1050000, 128000)}.items():
            with self.subTest(model=model):
                self.assertEqual(AIService.guess_model_capabilities(model), expected)

    def test_independent_ceilings_are_not_a_request_budget(self):
        self.assertEqual(parse_model_metadata({'context_window': 1048576,
                                              'max_tokens': 1048576}), (1048576, 1048576))

    def test_glm_exact_output_does_not_guess_million_context_units(self):
        caps = resolve_model_capabilities('glm-5.3-flash', 'https://open.bigmodel.cn/api/paas/v4')
        self.assertEqual((caps.context_window, caps.max_output_tokens), (None, 131072))

    def test_kimi_and_claude_verified_output_and_specific_source_urls(self):
        self.assertEqual(AIService.guess_model_capabilities('kimi-k3'), (None, 1048576))
        self.assertEqual(AIService.guess_model_capabilities('claude-opus-5'), (None, 128000))
        self.assertTrue(resolve_model_capabilities('gpt-6-astra').source_url.endswith('/gpt-6-astra'))
