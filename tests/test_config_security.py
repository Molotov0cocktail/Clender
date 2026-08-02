import json
import logging
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import config
import logger


class ConfigSecurityTests(unittest.TestCase):
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

    @mock.patch("config.secret_store.set_api_key")
    def test_save_config_stores_secret_outside_json(self, set_api_key):
        config.save_config({"theme": "dark", "api_key": "top-secret"})

        saved = self.config_file.read_text(encoding="utf-8")
        self.assertNotIn("top-secret", saved)
        self.assertNotIn("api_key", json.loads(saved))
        set_api_key.assert_called_once_with("top-secret")

    @mock.patch(
        "config.secret_store.set_api_key",
        side_effect=config.secret_store.SecretStoreError("backend unavailable"),
    )
    def test_secret_backend_failure_does_not_write_plaintext(self, set_api_key):
        with self.assertRaises(config.secret_store.SecretStoreError):
            config.save_config({"theme": "dark", "api_key": "top-secret"})
        self.assertFalse(self.config_file.exists())

    @mock.patch("config.secret_store.get_api_key", return_value="credential-secret")
    def test_load_config_injects_secret_at_runtime(self, get_api_key):
        self.data_dir.mkdir(parents=True)
        self.config_file.write_text('{"theme":"dark"}', encoding="utf-8")

        loaded = config.load_config()

        self.assertEqual(loaded["api_key"], "credential-secret")
        self.assertEqual(loaded["theme"], "dark")

    @mock.patch("config.secret_store.get_api_key", return_value="")
    @mock.patch("config.secret_store.set_api_key")
    def test_plaintext_key_is_migrated_and_scrubbed(self, set_api_key, get_api_key):
        self.data_dir.mkdir(parents=True)
        self.config_file.write_text(
            '{"theme":"light","api_key":"legacy-secret"}', encoding="utf-8"
        )

        loaded = config.load_config()

        set_api_key.assert_called_once_with("legacy-secret")
        self.assertEqual(loaded["api_key"], "legacy-secret")
        self.assertNotIn("api_key", json.loads(self.config_file.read_text(encoding="utf-8")))

    def test_import_does_not_create_data_directory(self):
        self.assertFalse(self.data_dir.exists())

    def test_malformed_json_falls_back_to_defaults(self):
        self.data_dir.mkdir(parents=True)
        self.config_file.write_text("{broken", encoding="utf-8")
        with mock.patch("config.secret_store.get_api_key", return_value=""):
            loaded = config.load_config()
        self.assertEqual(loaded["theme"], "light")

    @mock.patch("config.secret_store.get_api_key", return_value="")
    def test_empty_legacy_key_field_is_scrubbed(self, get_api_key):
        self.data_dir.mkdir(parents=True)
        self.config_file.write_text('{"api_key":"","theme":"light"}', encoding="utf-8")
        config.load_config()
        self.assertNotIn("api_key", json.loads(self.config_file.read_text(encoding="utf-8")))

    def test_logging_is_created_only_when_configured(self):
        log_dir = Path(self.temp_dir.name) / "logs"
        self.assertFalse(log_dir.exists())

        logger.configure_logging(str(log_dir))
        self.addCleanup(logger.shutdown_logging)

        self.assertTrue((log_dir / "clender.log").exists())


if __name__ == "__main__":
    unittest.main()
