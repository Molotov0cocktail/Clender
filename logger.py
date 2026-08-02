"""统一日志系统 - 输出到文件和控制台"""
import logging
import os
import sys


def _get_log_path() -> str:
    """获取日志文件路径，与 config.py 保持一致的 data 目录"""
    if getattr(sys, 'frozen', False):
        base = os.path.dirname(sys.executable)
    else:
        base = os.path.dirname(os.path.abspath(__file__))
    log_dir = os.path.join(base, 'data')
    os.makedirs(log_dir, exist_ok=True)
    return os.path.join(log_dir, 'clender.log')


def get_logger(name: str) -> logging.Logger:
    """获取命名 logger

    Args:
        name: logger 名称，通常使用 __name__

    Returns:
        配置好的 Logger 实例（级别 DEBUG，输出到文件 + 控制台）
    """
    logger = logging.getLogger(name)

    if not logger.handlers:
        logger.setLevel(logging.DEBUG)

        # 文件 handler — 记录所有级别
        fh = logging.FileHandler(_get_log_path(), encoding='utf-8')
        fh.setLevel(logging.DEBUG)
        fh.setFormatter(logging.Formatter(
            '[%(asctime)s] %(name)s %(levelname)s: %(message)s',
            datefmt='%Y-%m-%d %H:%M:%S'
        ))
        logger.addHandler(fh)

        # 控制台 handler — 仅 WARNING 及以上避免干扰用户
        ch = logging.StreamHandler()
        ch.setLevel(logging.WARNING)
        ch.setFormatter(logging.Formatter('%(levelname)s: %(message)s'))
        logger.addHandler(ch)

    return logger