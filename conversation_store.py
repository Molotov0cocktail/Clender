"""对话持久化模块 - 加载/保存 Conversation 到 JSON 文件"""
import json
import os

import config as cfg_mod
from models import Conversation
from logger import get_logger

_log = get_logger(__name__)


def _conv_file() -> str:
    """获取对话存储文件路径"""
    return os.path.join(cfg_mod.APP_DATA_DIR, 'conversations.json')


def load_conversations() -> dict[str, Conversation]:
    """从 JSON 文件加载所有对话
    
    Returns:
        dict[str, Conversation] — 对话 ID 到 Conversation 对象的映射
    """
    path = _conv_file()
    if os.path.exists(path):
        try:
            with open(path, 'r', encoding='utf-8') as f:
                data = json.load(f)
            return {k: Conversation.from_dict(v) for k, v in data.items()}
        except Exception:
            _log.warning("对话数据加载失败，使用空对话列表", exc_info=True)
    return {}


def save_conversations(convs: dict[str, Conversation]) -> None:
    """将所有对话保存到 JSON 文件"""
    with open(_conv_file(), 'w', encoding='utf-8') as f:
        json.dump({k: v.to_dict() for k, v in convs.items()}, f,
                   ensure_ascii=False, indent=2)
