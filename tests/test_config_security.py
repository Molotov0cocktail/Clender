import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import config
import logger


class ConfigPersistenceTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.data_dir = Path(self.temp_dir.name) / "data"
        self.config_file = self.data_dir / "config.json"
        self.path_patches = [
            mock.patch.object(config, "APP_DATA_DIR", str(self.data_dir)),
            mock.patch.object(config, "CONFIG_FILE", str(self.config_file)),
            mock.patch.object(config, "DB_PATH", str(self.data_dir / "clender.db")),
        ]
        for patcher in self.path_patches:
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_save_config_round_trips_non_empty_key_in_json(self):
        expected = {"theme": "dark", "api_key": "top-secret"}

        config.save_config(expected)

        saved = self.config_file.read_text(encoding="utf-8")
        self.assertEqual(json.loads(saved), expected)
        self.assertEqual(config.load_config()["api_key"], "top-secret")

    def test_save_config_round_trips_empty_key(self):
        config.save_config({"api_key": "", "theme": "light"})

        self.assertEqual(config.load_config()["api_key"], "")
        self.assertIn(
            "api_key", json.loads(self.config_file.read_text(encoding="utf-8"))
        )

    def test_unicode_and_long_key_round_trip(self):
        value = "密钥-🔐-" + "x" * 4096
        config.save_config({"api_key": value})

        self.assertEqual(config.load_config()["api_key"], value)

    def test_webdav_password_round_trips_only_through_ignored_json_config(self):
        value = "同步密码-🔐-" + "x" * 1024
        config.save_config({
            "webdav_url": "https://dav.example.test/root",
            "webdav_username": "用户",
            "webdav_password": value,
        })

        self.assertEqual(config.load_config()["webdav_password"], value)

    def test_json_is_only_key_source(self):
        self.data_dir.mkdir(parents=True)
        self.config_file.write_text(
            '{"theme":"dark","api_key":"json-secret"}', encoding="utf-8"
        )

        with mock.patch.dict(os.environ, {"CLENDER_API_KEY": "environment-secret"}):
            loaded = config.load_config()

        self.assertEqual(loaded["api_key"], "json-secret")
        self.assertEqual(loaded["theme"], "dark")

    def test_missing_fields_are_merged_with_defaults(self):
        self.data_dir.mkdir(parents=True)
        self.config_file.write_text('{"theme":"dark"}', encoding="utf-8")

        loaded = config.load_config()

        self.assertEqual(loaded["theme"], "dark")
        self.assertEqual(loaded["api_key"], "")

    def test_missing_file_returns_defaults(self):
        loaded = config.load_config()

        self.assertEqual(loaded["theme"], "light")
        self.assertEqual(loaded["api_key"], "")

    def test_import_does_not_create_data_directory(self):
        self.assertFalse(self.data_dir.exists())

    def test_malformed_json_falls_back_to_defaults(self):
        self.data_dir.mkdir(parents=True)
        self.config_file.write_text("{broken", encoding="utf-8")
        loaded = config.load_config()
        self.assertEqual(loaded["theme"], "light")
        self.assertEqual(loaded["api_key"], "")

    def test_non_object_json_falls_back_to_defaults(self):
        self.data_dir.mkdir(parents=True)
        self.config_file.write_text('["not", "an", "object"]', encoding="utf-8")

        loaded = config.load_config()

        self.assertEqual(loaded["theme"], "light")
        self.assertEqual(loaded["api_key"], "")

    def test_replace_failure_preserves_previous_config(self):
        self.data_dir.mkdir(parents=True)
        original = '{"theme":"light","api_key":"old-key"}'
        self.config_file.write_text(original, encoding="utf-8")

        with mock.patch("config.os.replace", side_effect=OSError("replace failed")):
            with self.assertRaises(OSError):
                config.save_config({"theme": "dark", "api_key": "new-key"})

        self.assertEqual(self.config_file.read_text(encoding="utf-8"), original)
        self.assertEqual(list(self.data_dir.glob("config-*.tmp")), [])

    def test_logging_is_created_only_when_configured(self):
        log_dir = Path(self.temp_dir.name) / "logs"
        self.assertFalse(log_dir.exists())

        logger.configure_logging(str(log_dir))
        self.addCleanup(logger.shutdown_logging)

        self.assertTrue((log_dir / "clender.log").exists())


if __name__ == "__main__":
    unittest.main()
