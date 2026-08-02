"""
配置管理模块
负责API设置、主题、数据库路径等全局配置的读写
"""
import json
import os
import sys
import tempfile
from constants import DEFAULT_CONFIG
from logger import get_logger
import secret_store

_log = get_logger(__name__)


# 获取用户本地数据目录（支持开发环境与打包后的exe环境）
def get_app_data_dir():
    """返回应用数据存储目录，打包后在用户目录下，开发时在项目根目录下"""
    if getattr(sys, 'frozen', False):
        base = os.path.dirname(sys.executable)
    else:
        base = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(base, 'data')


APP_DATA_DIR = get_app_data_dir()

# 配置文件路径
CONFIG_FILE = os.path.join(APP_DATA_DIR, 'config.json')
# 数据库路径
DB_PATH = os.path.join(APP_DATA_DIR, 'clender.db')


def ensure_app_data_dir() -> str:
    """Create the runtime data directory explicitly and return its path."""
    os.makedirs(APP_DATA_DIR, exist_ok=True)
    return APP_DATA_DIR


def _write_config_file(config: dict) -> None:
    """Atomically write a sanitized config dictionary."""
    ensure_app_data_dir()
    fd, temp_path = tempfile.mkstemp(prefix="config-", suffix=".tmp", dir=APP_DATA_DIR)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as handle:
            json.dump(config, handle, ensure_ascii=False, indent=2)
        os.replace(temp_path, CONFIG_FILE)
    finally:
        if os.path.exists(temp_path):
            os.remove(temp_path)


def load_config():
    """Load non-secret JSON config and inject the API key at runtime."""
    cfg_data = {}
    legacy_key = ""
    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, 'r', encoding='utf-8') as f:
                cfg_data = json.load(f)
            if not isinstance(cfg_data, dict):
                raise ValueError("配置根节点必须是对象")

            # One-time migration: scrub legacy plaintext regardless of backend result.
            had_legacy_key = "api_key" in cfg_data
            legacy_key = str(cfg_data.pop("api_key", "") or "").strip()
            if legacy_key:
                try:
                    secret_store.set_api_key(legacy_key)
                except secret_store.SecretStoreError as exc:
                    _log.error("旧 API Key 无法迁移到安全存储，已从 JSON 清除: %s", exc)
            if had_legacy_key:
                _write_config_file(cfg_data)
        except (json.JSONDecodeError, IOError) as e:
            _log.warning(f"配置加载失败: {e}")
            cfg_data = {}
        except ValueError as e:
            _log.warning(f"配置加载失败: {e}")
            cfg_data = {}

    merged = DEFAULT_CONFIG.copy()
    merged.update(cfg_data)
    merged["api_key"] = legacy_key or secret_store.get_api_key()
    return merged


def save_config(config: dict):
    """Persist config while keeping the API key outside JSON."""
    sanitized = dict(config)
    if "api_key" in sanitized:
        secret_store.set_api_key(str(sanitized.pop("api_key") or ""))
    _write_config_file(sanitized)


def is_api_configured():
    """检查API是否已配置（endpoint和key均非空）"""
    cfg = load_config()
    return bool(cfg.get("api_endpoint", "").strip() and cfg.get("api_key", "").strip())


def get_theme():
    """获取当前主题设置"""
    cfg = load_config()
    return cfg.get("theme", "light")


def set_theme(theme: str):
    """保存主题设置"""
    cfg = load_config()
    cfg["theme"] = theme
    save_config(cfg)
