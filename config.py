"""
配置管理模块
负责API设置、主题、数据库路径等全局配置的读写
"""
import json
import os
import sys
from constants import DEFAULT_CONFIG
from logger import get_logger

_log = get_logger(__name__)


# 获取用户本地数据目录（支持开发环境与打包后的exe环境）
def get_app_data_dir():
    """返回应用数据存储目录，打包后在用户目录下，开发时在项目根目录下"""
    if getattr(sys, 'frozen', False):
        base = os.path.dirname(sys.executable)
    else:
        base = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(base, 'data')


# 确保数据目录存在
APP_DATA_DIR = get_app_data_dir()
os.makedirs(APP_DATA_DIR, exist_ok=True)

# 配置文件路径
CONFIG_FILE = os.path.join(APP_DATA_DIR, 'config.json')
# 数据库路径
DB_PATH = os.path.join(APP_DATA_DIR, 'clender.db')



def load_config():
    """从本地JSON文件加载配置，文件不存在则返回默认配置"""
    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, 'r', encoding='utf-8') as f:
                cfg = json.load(f)
            merged = DEFAULT_CONFIG.copy()
            merged.update(cfg)
            return merged
        except (json.JSONDecodeError, IOError) as e:
            _log.warning(f"配置加载失败: {e}")
    return DEFAULT_CONFIG.copy()


def save_config(config: dict):
    """保存配置到本地JSON文件"""
    with open(CONFIG_FILE, 'w', encoding='utf-8') as f:
        json.dump(config, f, ensure_ascii=False, indent=2)


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